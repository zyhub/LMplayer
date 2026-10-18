package com.lm.player.core.source

import android.content.Context
import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.evaluate
import com.lm.player.core.model.SourceScriptEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 单音源 QuickJS 沙箱。
 *
 * LX 自定义音源的协议是 globalThis.lx:
 *  - on(EVENT_NAMES.request, handler)
 *  - send(EVENT_NAMES.inited, { sources })
 *  - request(url, options, callback)
 *
 * 这里用 QuickJS 的异步 binding 把 request 接到 OkHttp；脚本里的 async/await 由
 * quickjs-kt 的 evaluate 驱动。每个源独立 context，任何脚本异常只影响本源。
 */
class LocalSourceEngine(
    private val context: Context,
    private val entry: SourceScriptEntry
) {
    private val TAG = "LocalSourceEngine"
    private val mutex = Mutex()
    private val engineExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "qjs-${entry.id.takeLast(8)}")
    }
    private val engineDispatcher = engineExecutor.asCoroutineDispatcher()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile
    private var quickJs: QuickJs? = null

    @Volatile
    var initError: String? = null
        private set

    val isReady: Boolean
        get() = quickJs != null && initError == null

    val sourceId: String get() = entry.id

    fun supports(platform: String): Boolean {
        val raw = entry.sourcesJson
        if (raw.isBlank() || raw == "{}") return true
        return runCatching { JSONObject(raw).has(platform) }.getOrDefault(true)
    }

    suspend fun initialize(): Result<Unit> = mutex.withLock {
        if (quickJs != null) return@withLock Result.success(Unit)
        try {
            val qjs = QuickJs(engineDispatcher)
            // quickjs-kt 的 evaluate 实际在当前调用线程执行 native 求值，jobDispatcher 只调度
            // JS 异步任务。必须把所有 evaluate/define 固定到同一个 engineDispatcher 线程，
            // 否则多首歌连续取链时会在 js_is_promise / getEvaluateResult 内 SIGSEGV。
            withContext(engineDispatcher) {
                qjs.define("__native") {
                    asyncFunction<String>("request") { args ->
                        val url = (args.getOrNull(0) as? String).orEmpty()
                        val optionsRaw = (args.getOrNull(1) as? String).orEmpty()
                        withContext(Dispatchers.IO) { executeRequest(url, optionsRaw) }
                    }
                    function<Any?>("log") { args ->
                        Log.i("${TAG}/${entry.id}", args.joinToString(" ") { it?.toString().orEmpty() })
                        null
                    }
                }
                qjs.evaluate(setupScript(), Any::class.java)
                val script = sanitizeScript(entry.script)
                if (script.length < 20) throw IllegalStateException("脚本为空")
                qjs.evaluate(script, Any::class.java)
            }
            val inited = withContext(engineDispatcher) {
                qjs.evaluate("JSON.stringify(globalThis.__sent && globalThis.__sent.inited || null)", String::class.java)
            }
            if (inited.isNullOrBlank() || inited == "null") {
                throw IllegalStateException("脚本未发送 inited")
            }
            quickJs = qjs
            initError = null
            Log.i(TAG, "initialized ${entry.name} v${entry.version}")
            Result.success(Unit)
        } catch (e: Throwable) {
            initError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "initialize failed: ${entry.name}", e)
            runCatching { quickJs?.close() }
            quickJs = null
            Result.failure(e)
        }
    }

    suspend fun resolveMusicUrl(
        source: String,
        quality: String,
        musicInfoJson: String
    ): Result<String> = mutex.withLock {
        val qjs = quickJs ?: return@withLock Result.failure(IllegalStateException("音源未就绪"))
        return@withLock try {
            val payload = JSONObject()
                .put("action", "musicUrl")
                .put("source", source)
                .put("info", JSONObject().put("type", quality).put("musicInfo", JSONObject(musicInfoJson)))
            val quoted = JSONObject.quote(payload.toString())
            // 不要在 native quickjs 求值期间用 withTimeout 取消：取消会破坏 QuickJS 状态，
            // 下一次 evaluate 可能在 js_is_promise 内 SIGSEGV。超时回退放在上层、不取消求值。
            val result = withContext(engineDispatcher) {
                qjs.evaluate(
                    """
                    var __payload = JSON.parse(${quoted});
                    var __handler = globalThis.__handlers[globalThis.lx.EVENT_NAMES.request];
                    if (typeof __handler !== "function") throw new Error("no request handler");
                    var __value = await __handler(__payload);
                    if (__value == null || __value === "") throw new Error("empty music url");
                    JSON.stringify({ value: String(__value) });
                    """.trimIndent(),
                    String::class.java
                )
            }
            val value = JSONObject(result).optString("value")
            if (value.isBlank()) Result.failure(IllegalStateException("音源返回空地址"))
            else Result.success(value)
        } catch (e: Throwable) {
            Log.w(TAG, "resolve failed: ${entry.name}/${source}/${quality}", e)
            Result.failure(e)
        }
    }

    fun close() {
        val qjs = quickJs
        if (qjs != null) {
            // QuickJS 的 eval/close 都应在同一个 engineDispatcher 线程执行。
            runCatching { kotlinx.coroutines.runBlocking(engineDispatcher) { qjs.close() } }
        }
        quickJs = null
        initError = null
        runCatching { engineExecutor.shutdown() }
    }

    private fun setupScript(): String = """
        globalThis.__handlers = {};
        globalThis.__sent = {};
        globalThis.console = {
          log: function() { __native.log(Array.prototype.join.call(arguments, " ")); },
          error: function() { __native.log(Array.prototype.join.call(arguments, " ")); }
        };
        globalThis.lx = {
          EVENT_NAMES: { request: "request", inited: "inited", updateAlert: "updateAlert" },
          env: "android",
          version: "1.7.27",
          on: function(event, handler) { globalThis.__handlers[event] = handler; },
          send: function(event, data) { globalThis.__sent[event] = data; },
          request: function(url, options, callback) {
            var promise = __native.request(String(url), JSON.stringify(options || {})).then(function(raw) {
              var result = JSON.parse(raw);
              if (result.error) throw new Error(result.error);
              return result.response;
            });
            if (typeof callback === "function") {
              promise.then(function(response) { callback(null, response); })
                     .catch(function(error) { callback(error); });
              return;
            }
            return promise;
          }
        };
    """.trimIndent()

    /** 去掉脚本自带的更新检查，避免启动时额外发请求/阻塞会话。 */
    private fun sanitizeScript(raw: String): String {
        return raw.replace(
            Regex("""if\s*\(\s*UPDATE_ENABLE\s*\)\s*checkUpdate\s*\(\s*\)\s*;?"""),
            ""
        )
    }

    private fun executeRequest(url: String, optionsRaw: String): String {
        return try {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return JSONObject().put("error", "invalid url").toString()
            }
            // 远程音源脚本只能访问公网，禁止 localhost/内网/链路本地地址，避免 SSRF。
            val targetHost = runCatching { java.net.URI(url).host }.getOrNull()
                ?: return JSONObject().put("error", "invalid host").toString()
            val addresses = runCatching { java.net.InetAddress.getAllByName(targetHost) }.getOrNull()
                ?: return JSONObject().put("error", "dns failed").toString()
            if (addresses.any {
                    it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress ||
                        it.isSiteLocalAddress || it.isMulticastAddress
                }
            ) {
                return JSONObject().put("error", "private network blocked").toString()
            }
            val options = runCatching { JSONObject(optionsRaw.ifBlank { "{}" }) }.getOrDefault(JSONObject())
            val method = options.optString("method", "GET").uppercase()
            val timeoutMs = options.optLong("timeout", 15_000L).coerceIn(2_000L, 25_000L)
            val requestBuilder = Request.Builder().url(url)
            val headers = options.optJSONObject("headers")
            if (headers != null) {
                val keys = headers.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = headers.optString(key)
                    if (value.isNotBlank()) requestBuilder.header(key, value)
                }
            }
            val bodyText = options.optString("body")
            val body = if (method == "GET" || method == "HEAD") null
            else bodyText.toRequestBody((headers?.optString("Content-Type") ?: "application/json").toMediaType())
            requestBuilder.method(method, body)
            val client = httpClient.newBuilder()
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .connectTimeout(minOf(timeoutMs, 10_000L), TimeUnit.MILLISECONDS)
                .build()
            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (bodyString.length > 5 * 1024 * 1024) {
                    return JSONObject().put("error", "response too large").toString()
                }
                val responseObj = JSONObject()
                    .put("statusCode", response.code)
                    .put("status", response.code)
                    .put("body", bodyString)
                JSONObject().put("response", responseObj).toString()
            }
        } catch (e: Throwable) {
            JSONObject().put("error", e.message ?: e.javaClass.simpleName).toString()
        }
    }
}
