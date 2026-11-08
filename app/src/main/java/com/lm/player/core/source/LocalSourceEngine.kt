package com.lm.player.core.source

import android.content.Context
import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.evaluate
import com.lm.player.core.model.SourceScriptEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 单个 QuickJS 上下文（一个音源脚本的一个实例）。
 *
 * 运行时（assets/lx/lx_runtime.js）与柠檬服务端 sourceManager.js 对齐：lx.request 回调 (err, resp, body)、
 * body 自动 JSON 解析、lx.utils(crypto/buffer/zlib)、setTimeout、Buffer、require 等。
 *
 * 线程模型：所有 evaluate/define/close 固定在本实例自己的单线程 dispatcher 上执行
 * （quickjs-kt 在调用线程直接做 native 求值，跨线程会 SIGSEGV）。并发由 [SourceEnginePool] 控制：
 * 同一时刻只把本实例交给一个调用方。
 *
 * quickjs-kt 的 evaluate 会等到全部原生异步任务（请求/定时器）结束才返回，且任一异步绑定抛异常会
 * 取消其它异步任务 —— 因此所有 asyncFunction 绑定都**绝不抛异常**，错误以 JSON 返回。
 */
class LocalSourceEngine(
    private val context: Context,
    private val entry: SourceScriptEntry
) {
    private companion object {
        const val TAG = "LocalSourceEngine"
        const val MAX_RESPONSE_BYTES = 5L * 1024 * 1024
        const val DEFAULT_HTTP_TIMEOUT_MS = 10_000L
        const val MAX_HTTP_TIMEOUT_MS = 15_000L
        const val MAX_SLEEP_MS = 10_000L
        const val MEMORY_LIMIT_BYTES = 32L * 1024 * 1024
        const val MAX_STACK_BYTES = 1L * 1024 * 1024
        const val RUNTIME_ASSET = "lx/lx_runtime.js"
        private val instanceSeq = AtomicLong(0)

        @Volatile
        private var runtimeScriptCache: String? = null

        fun runtimeScript(context: Context): String {
            runtimeScriptCache?.let { return it }
            val text = context.assets.open(RUNTIME_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            runtimeScriptCache = text
            return text
        }

        /** 音源脚本禁止访问的地址：本机、内网、链路本地、组播。只约束脚本发起的请求，不影响 App 连内网服务器。 */
        fun isBlockedAddress(addr: java.net.InetAddress): Boolean =
            addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress || addr.isMulticastAddress || NetworkScopeDetector.isPrivate(addr)

        // S-06：在连接层再拦一次，覆盖「302 跳到内网」与 DNS 重绑定。
        private val scriptDns = object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                val all = okhttp3.Dns.SYSTEM.lookup(hostname)
                if (all.any { isBlockedAddress(it) }) {
                    throw java.net.UnknownHostException("private network blocked: $hostname")
                }
                return all
            }
        }

        /** 所有引擎共享一个连接池，避免每个上下文各建一套连接。 */
        val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(DEFAULT_HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .dns(scriptDns)
                .addNetworkInterceptor { chain ->
                    val route = chain.connection()?.route()
                    if (route != null && route.proxy.type() == java.net.Proxy.Type.DIRECT) {
                        val remote = route.socketAddress.address
                        if (remote != null && isBlockedAddress(remote)) {
                            throw java.io.IOException("private network blocked: ${chain.request().url.host}")
                        }
                    }
                    chain.proceed(chain.request())
                }
                .build()
        }
    }

    private val mutex = Mutex()
    private val instanceId = instanceSeq.incrementAndGet()

    @Volatile
    private var closed = false

    private val engineExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "qjs-${entry.id.takeLast(6)}-$instanceId").apply { isDaemon = true }
    }
    private val engineDispatcher = engineExecutor.asCoroutineDispatcher()

    private val inflightCalls = ConcurrentHashMap<String, okhttp3.Call>()
    private val abortedRids = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var quickJs: QuickJs? = null

    @Volatile
    var initError: String? = null
        private set

    /** 本次求值开始时间；0 表示空闲。供池子判断「卡死」。 */
    @Volatile
    var busySince: Long = 0L
        private set

    /** 脚本对某平台的声明：actions / qualitys 为空表示未声明（视为不限制）。 */
    data class PlatformDecl(val actions: Set<String>, val qualitys: Set<String>)

    /** 脚本 inited 声明的平台；为空时回退 bundle 里的 sourcesJson。 */
    @Volatile
    private var declaredPlatforms: Map<String, PlatformDecl> = emptyMap()

    val isReady: Boolean
        get() = quickJs != null && initError == null && !closed

    val sourceId: String get() = entry.id
    val sourceName: String get() = entry.name

    private fun declared(): Map<String, PlatformDecl> = declaredPlatforms.ifEmpty { parseDeclared(entry.sourcesJson) }

    private fun declFor(platform: String): PlatformDecl? {
        val expected = platformAliases(platform)
        return declared().entries.firstOrNull { it.key.lowercase() in expected }?.value
    }

    /** 是否支持该平台的该动作。脚本未声明平台表时视为支持。tx/qq 视为同一平台。 */
    fun supports(platform: String, action: String = "musicUrl"): Boolean {
        if (declared().isEmpty()) return true
        val decl = declFor(platform) ?: return false
        return decl.actions.isEmpty() || action in decl.actions
    }

    /** 脚本是否声明了该音质；未声明音质表时视为支持。 */
    fun supportsQuality(platform: String, quality: String): Boolean {
        val decl = declFor(platform) ?: return true
        return decl.qualitys.isEmpty() || decl.qualitys.any { it.equals(quality, ignoreCase = true) }
    }

    fun declaredPlatformKeys(): Set<String> = declared().keys

    private fun platformAliases(platform: String): Set<String> = when (platform.lowercase()) {
        "tx", "qq" -> setOf("tx", "qq")
        else -> setOf(platform.lowercase())
    }

    private fun parseDeclared(raw: String?): Map<String, PlatformDecl> {
        if (raw.isNullOrBlank() || raw == "{}" || raw == "null") return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            val out = LinkedHashMap<String, PlatformDecl>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = obj.optJSONObject(key)
                out[key] = PlatformDecl(
                    actions = item?.optJSONArray("actions").toStringSet(),
                    qualitys = item?.optJSONArray("qualitys").toStringSet()
                )
            }
            out
        }.getOrDefault(emptyMap())
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        val set = HashSet<String>()
        for (i in 0 until length()) optString(i).takeIf { it.isNotBlank() }?.let { set.add(it) }
        return set
    }

    suspend fun initialize(): Result<Unit> = try {
        initializeLocked()
    } finally {
        // 初始化期间被 close()：close 拿不到锁，原生资源由这里释放
        releaseIfClosed()
    }

    private suspend fun initializeLocked(): Result<Unit> = mutex.withLock {
        if (closed) return@withLock Result.failure(IllegalStateException("音源已关闭"))
        if (quickJs != null) return@withLock Result.success(Unit)
        // 尚未交给 quickJs 字段的实例：初始化中途抛异常时由 catch 负责关闭，避免原生运行时泄漏
        var pending: QuickJs? = null
        try {
            val runtime = runtimeScript(context)
            val meta = JSONObject(LxNativeUtils.parseScriptMeta(entry.script) as Map<*, *>).toString()
            val qjs = QuickJs(engineDispatcher)
            pending = qjs
            // quickjs-kt 的 evaluate 实际在调用线程执行 native 求值，必须固定在 engineDispatcher 线程。
            withContext(engineDispatcher) {
                try {
                    qjs.memoryLimit = MEMORY_LIMIT_BYTES
                    qjs.maxStackSize = MAX_STACK_BYTES
                } catch (e: Throwable) {
                    Log.w(TAG, "设置 QuickJS 配额失败: ${entry.name}", e)
                }
                qjs.define("__native") {
                    asyncFunction<String>("request") { args ->
                        val url = (args.getOrNull(0) as? String).orEmpty()
                        val optionsRaw = (args.getOrNull(1) as? String).orEmpty()
                        withContext(Dispatchers.IO) { executeRequest(url, optionsRaw) }
                    }
                    asyncFunction<String>("sleep") { args ->
                        val ms = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                        delay(ms.coerceIn(0L, MAX_SLEEP_MS))
                        ""
                    }
                    function<String>("abort") { args ->
                        abortRequest((args.getOrNull(0) as? String).orEmpty())
                        ""
                    }
                    function<String>("md5") { args -> LxNativeUtils.md5Hex(args.getOrNull(0)?.toString().orEmpty()) }
                    function<String>("hash") { args ->
                        LxNativeUtils.hash(args.str(0), args.str(1))
                    }
                    function<String>("hmac") { args ->
                        LxNativeUtils.hmac(args.str(0), args.str(1), args.str(2))
                    }
                    function<String>("aes") { args ->
                        LxNativeUtils.aes(
                            encrypt = args.getOrNull(0) == true,
                            alg = args.str(1),
                            keyB64 = args.str(2),
                            ivB64 = args.str(3),
                            dataB64 = args.str(4),
                            autoPadding = args.getOrNull(5) != false
                        )
                    }
                    function<String>("rsa") { args ->
                        LxNativeUtils.rsa(args.str(0), args.str(1), args.getOrNull(2) == true)
                    }
                    function<String>("randomBytes") { args ->
                        LxNativeUtils.randomBytes((args.getOrNull(0) as? Number)?.toInt() ?: 0)
                    }
                    function<String>("zlib") { args ->
                        LxNativeUtils.zlib(args.str(0), args.str(1), args.str(2))
                    }
                    function<String>("scriptInfo") { _ -> meta }
                    function<String>("log") { args ->
                        Log.i("$TAG/${entry.id.takeLast(8)}", args.joinToString(" ") { it?.toString().orEmpty() }.take(800))
                        ""
                    }
                }
                qjs.evaluate("$runtime\n;true", Any::class.java, "lx_runtime.js")
                val script = sanitizeScript(entry.script)
                if (script.length < 20) throw IllegalStateException("脚本为空")
                // evaluate 会等待脚本初始化阶段发起的全部请求/定时器结束，异步 send('inited') 也能被捕获
                qjs.evaluate("$script\n;true", Any::class.java, "source.js")
                val inited = qjs.evaluate(
                    "JSON.stringify(globalThis.__sent && globalThis.__sent.inited || null)",
                    String::class.java
                )
                if (inited.isNullOrBlank() || inited == "null") {
                    throw IllegalStateException("脚本未发送 inited")
                }
                declaredPlatforms = runCatching {
                    parseDeclared(JSONObject(inited).optJSONObject("sources")?.toString())
                }.getOrDefault(emptyMap())
                if (closed) {
                    throw IllegalStateException("音源在初始化期间被关闭")
                }
                quickJs = qjs
                pending = null
            }
            initError = null
            Log.i(TAG, "initialized ${entry.name} v${entry.version} platforms=${declaredPlatforms.keys}")
            Result.success(Unit)
        } catch (e: Throwable) {
            initError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "initialize failed: ${entry.name}", e)
            // 关闭尚未发布的实例（脚本语法错误、未发送 inited、求值异常等）；必须在引擎线程上关闭
            val leftover = pending ?: quickJs
            quickJs = null
            if (leftover != null) {
                runCatching { engineExecutor.execute { runCatching { leftover.close() } } }
            }
            Result.failure(e)
        }
    }

    private fun Array<Any?>.str(index: Int): String = getOrNull(index)?.toString().orEmpty()

    /**
     * 调用脚本 request 处理器。返回值为 handler 结果的 JSON 文本（字符串结果会带引号）。
     * 不在这里做超时：取消 native 求值会破坏 QuickJS 状态。预算由上层控制「停止等待」。
     */
    suspend fun invoke(action: String, source: String, info: JSONObject): Result<String> {
        if (closed) return Result.failure(IllegalStateException("音源已关闭"))
        return try {
            invokeLocked(action, source, info)
        } finally {
            // 求值期间被 close()（脚本刷新替换实例池 / 会话停止）：等求值完整结束后再释放原生资源
            releaseIfClosed()
        }
    }

    private suspend fun invokeLocked(action: String, source: String, info: JSONObject): Result<String> {
        return mutex.withLock {
            val qjs = quickJs ?: return@withLock Result.failure(IllegalStateException("音源未就绪"))
            busySince = System.currentTimeMillis()
            try {
                val payload = JSONObject()
                    .put("action", action)
                    .put("source", source)
                    .put("info", info)
                val quoted = JSONObject.quote(payload.toString())
                val resultJson = withContext(engineDispatcher) {
                    if (closed) throw IllegalStateException("音源已关闭")
                    // 第一次求值：调用处理器并等待其全部异步任务结束；第二次读取结果。
                    qjs.evaluate("globalThis.__lmInvoke($quoted);\ntrue", Any::class.java)
                    qjs.evaluate("globalThis.__lmResultJson()", String::class.java)
                }
                val obj = JSONObject(resultJson ?: "{}")
                if (!obj.optBoolean("ok")) {
                    Result.failure(IllegalStateException(obj.optString("error").ifBlank { "音源请求失败" }))
                } else {
                    val value = obj.opt("value")
                    if (value == null || value == JSONObject.NULL) {
                        Result.failure(IllegalStateException("音源返回空结果"))
                    } else {
                        Result.success(if (value is String) JSONObject.quote(value) else value.toString())
                    }
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "invoke failed: ${entry.name}/$action/$source: ${e.message}")
                Result.failure(e)
            } finally {
                busySince = 0L
            }
        }
    }

    suspend fun resolveMusicUrl(source: String, quality: String, musicInfoJson: String): Result<String> {
        val info = JSONObject()
            .put("type", quality)
            .put("musicInfo", runCatching { JSONObject(musicInfoJson) }.getOrDefault(JSONObject()))
        val raw = invoke("musicUrl", source, info).getOrElse { return Result.failure(it) }
        val url = extractMusicUrl(raw)
        return if (url.isBlank()) Result.failure(IllegalStateException("音源返回空地址"))
        else Result.success(url)
    }

    /** 对齐服务端 sourceResult.extractMusicUrl：字符串或 {url|musicUrl|link|playUrl|src|data|body}。 */
    private fun extractMusicUrl(rawJson: String): String {
        val value: Any? = runCatching { org.json.JSONTokener(rawJson).nextValue() }.getOrNull()
        return extractFrom(value, 0)
    }

    private fun extractFrom(value: Any?, depth: Int): String {
        if (value == null || depth > 4) return ""
        return when (value) {
            is String -> value.trim().takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }.orEmpty()
            is JSONObject -> {
                listOf("url", "musicUrl", "link", "playUrl", "src")
                    .map { value.optString(it).trim() }
                    .firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
                    ?: extractFrom(value.opt("data"), depth + 1).ifBlank { extractFrom(value.opt("body"), depth + 1) }
            }
            else -> ""
        }
    }

    /**
     * 关闭实例。
     *
     * 实例可能正被调用方借用、处于一次求值的中途（脚本刷新替换实例池、会话停止都会触发）：
     * 此时 QuickJS 正挂起等待网络请求，若立刻释放运行时并关停引擎线程，求值恢复时会访问已释放的原生内存
     * （或续体被线程池拒绝），可能直接闪退。所以：先取消在途请求让求值尽快结束；
     * 拿得到锁（空闲）就立即释放，拿不到（正在求值 / 初始化）就交给 invoke / initialize 结束时的 [releaseIfClosed]。
     */
    fun close() {
        closed = true
        initError = null
        inflightCalls.values.forEach { runCatching { it.cancel() } }
        inflightCalls.clear()
        if (mutex.tryLock()) {
            try {
                releaseNative()
            } finally {
                mutex.unlock()
            }
        }
    }

    /** 已关闭且当前没有求值在进行时释放原生资源；可重复调用。 */
    private fun releaseIfClosed() {
        if (!closed) return
        if (mutex.tryLock()) {
            try {
                releaseNative()
            } finally {
                mutex.unlock()
            }
        }
    }

    /** 调用方必须持有 [mutex]。关闭运行时（排在引擎线程上执行）并关停引擎线程；幂等。 */
    private fun releaseNative() {
        val qjs = quickJs
        quickJs = null
        if (qjs != null) {
            runCatching { engineExecutor.execute { runCatching { qjs.close() } } }
        }
        runCatching { engineExecutor.shutdown() }
    }

    /** 去掉脚本自带的更新检查，避免启动时额外发请求/阻塞会话。 */
    private fun sanitizeScript(raw: String): String {
        return raw.replace(
            Regex("""if\s*\(\s*UPDATE_ENABLE\s*\)\s*checkUpdate\s*\(\s*\)\s*;?"""),
            ""
        )
    }

    private fun abortRequest(rid: String) {
        if (rid.isBlank()) return
        abortedRids.add(rid)
        inflightCalls.remove(rid)?.let { runCatching { it.cancel() } }
    }

    private fun errorJson(message: String): String = JSONObject().put("error", message).toString()

    private fun executeRequest(url: String, optionsRaw: String): String {
        var rid = ""
        return try {
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                return errorJson("invalid url")
            }
            // 远程音源脚本只能访问公网，禁止 localhost/内网/链路本地地址，避免 SSRF。
            val targetHost = runCatching { java.net.URI(url).host }.getOrNull()
                ?: return errorJson("invalid host")
            val addresses = runCatching { java.net.InetAddress.getAllByName(targetHost) }.getOrNull()
                ?: return errorJson("dns failed")
            if (addresses.any { isBlockedAddress(it) }) {
                return errorJson("private network blocked")
            }
            val options = runCatching { JSONObject(optionsRaw.ifBlank { "{}" }) }.getOrDefault(JSONObject())
            rid = options.optString("rid")
            if (rid.isNotBlank() && abortedRids.remove(rid)) return errorJson("aborted")
            val method = options.optString("method", "GET").uppercase().ifBlank { "GET" }
            val timeoutMs = options.optLong("timeout", 0L).let { if (it <= 0L) DEFAULT_HTTP_TIMEOUT_MS else it }
                .coerceIn(2_000L, MAX_HTTP_TIMEOUT_MS)
            val requestBuilder = Request.Builder().url(url)
            var contentType: String? = null
            options.optJSONObject("headers")?.let { headers ->
                val keys = headers.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = headers.opt(key)?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()
                    if (value.isBlank()) continue
                    // 交给 OkHttp 处理压缩（自动解压）；脚本自带 Accept-Encoding 会拿到 gzip 原文
                    if (key.equals("Accept-Encoding", true) || key.equals("Content-Length", true) ||
                        key.equals("Host", true) || key.equals("Connection", true)
                    ) continue
                    if (key.equals("Content-Type", true)) contentType = value
                    runCatching { requestBuilder.header(key, value) }
                }
            }
            val body: RequestBody? = if (method == "GET" || method == "HEAD") null else buildBody(options, contentType)
            requestBuilder.method(method, body ?: if (method == "GET" || method == "HEAD") null else ByteArray(0).toRequestBody(null))
            val client = sharedHttpClient.newBuilder()
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .connectTimeout(minOf(timeoutMs, 8_000L), TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs + 3_000L, TimeUnit.MILLISECONDS)
                .build()
            val call = client.newCall(requestBuilder.build())
            if (rid.isNotBlank()) inflightCalls[rid] = call
            if (rid.isNotBlank() && abortedRids.remove(rid)) {
                call.cancel()
                return errorJson("aborted")
            }
            call.execute().use { response ->
                val responseBody = response.body
                val declaredLength = responseBody?.contentLength() ?: -1L
                if (declaredLength > MAX_RESPONSE_BYTES) return errorJson("response too large")
                val buffer = Buffer()
                if (responseBody != null) {
                    // BufferedSource.read(sink, n) 每次只读一个 Segment，必须循环读到 EOF（C-05）。
                    val source = responseBody.source()
                    while (buffer.size <= MAX_RESPONSE_BYTES) {
                        val read = source.read(buffer, MAX_RESPONSE_BYTES + 1 - buffer.size)
                        if (read == -1L) break
                    }
                    if (buffer.size > MAX_RESPONSE_BYTES) return errorJson("response too large")
                }
                val bytesCount = buffer.size
                val mediaType = responseBody?.contentType()
                val typeText = mediaType?.toString().orEmpty().lowercase()
                val binary = options.optBoolean("binary") ||
                    typeText.startsWith("audio/") || typeText.startsWith("image/") ||
                    typeText.contains("octet-stream") || typeText.contains("zip")
                val headersObj = JSONObject()
                response.headers.names().forEach { name ->
                    val values = response.headers.values(name)
                    val lower = name.lowercase()
                    if (lower == "set-cookie") headersObj.put(lower, JSONArray(values))
                    else headersObj.put(lower, values.joinToString(", "))
                }
                val responseObj = JSONObject()
                    .put("statusCode", response.code)
                    .put("status", response.code)
                    .put("statusMessage", response.message)
                    .put("headers", headersObj)
                    .put("bytes", bytesCount)
                    .put("url", response.request.url.toString())
                if (binary) {
                    responseObj.put("bodyBase64", android.util.Base64.encodeToString(buffer.readByteArray(), android.util.Base64.NO_WRAP))
                } else {
                    val charset = mediaType?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                    responseObj.put("body", buffer.readString(charset))
                }
                JSONObject().put("response", responseObj).toString()
            }
        } catch (e: Throwable) {
            errorJson(if (rid.isNotBlank() && abortedRids.remove(rid)) "aborted" else (e.message ?: e.javaClass.simpleName))
        } finally {
            if (rid.isNotBlank()) inflightCalls.remove(rid)
        }
    }

    private fun buildBody(options: JSONObject, contentType: String?): RequestBody? {
        if (options.has("bodyBase64")) {
            val bytes = android.util.Base64.decode(options.optString("bodyBase64"), android.util.Base64.DEFAULT)
            return bytes.toRequestBody((contentType ?: "application/octet-stream").toMediaTypeOrNull())
        }
        if (options.has("body")) {
            val defaultType = if (options.optBoolean("bodyIsJson")) "application/json" else "text/plain; charset=utf-8"
            return options.optString("body").toRequestBody((contentType ?: defaultType).toMediaTypeOrNull())
        }
        if (options.has("form")) {
            val text = options.optString("form")
            return text.toRequestBody((contentType ?: "application/x-www-form-urlencoded").toMediaTypeOrNull())
        }
        val formData = options.optJSONObject("formData")
        if (formData != null) {
            val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            val keys = formData.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = formData.opt(key)
                if (value == null || value == JSONObject.NULL) continue
                builder.addFormDataPart(key, value.toString())
            }
            return runCatching { builder.build() }.getOrElse { FormBody.Builder().build() }
        }
        return null
    }
}
