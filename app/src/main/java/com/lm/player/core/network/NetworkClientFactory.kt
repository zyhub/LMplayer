package com.lm.player.core.network

import android.content.Context
import android.os.Build
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.conscrypt.Conscrypt
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

object NetworkClientFactory {
    private const val TAG = "NetworkClientFactory"

    @Volatile
    private var isConscryptInstalled = false

    /**
     * 为老旧系统 (Android 6.0 ~ 9.0, API 23 ~ 28) 强力注入 Conscrypt 安全引擎，
     * 补齐现代 TLS 1.3、ECC 加密套件以及 Let's Encrypt 等现代根证书信任链，
     * 根治 HTTPS/SSL 握手失败异常 (SSLHandshakeException)。
     */
    fun installSecurityProvider() {
        if (isConscryptInstalled) return
        synchronized(this) {
            if (isConscryptInstalled) return
            try {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    val conscryptProvider = Conscrypt.newProvider()
                    Security.insertProviderAt(conscryptProvider, 1)
                    Log.i(TAG, "Successfully installed Conscrypt TLS 1.3 security provider on API ${Build.VERSION.SDK_INT}")
                }
                isConscryptInstalled = true
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to insert Conscrypt security provider", e)
            }
        }
    }

    @Volatile
    private var sharedClient: OkHttpClient? = null

    @Volatile
    private var activeAuthToken: String = ""

    /**
     * 允许自动注入 [activeAuthToken] 的目标主机集合（小写，不含端口）。
     *
     * **为什么必须绑定主机**：共享 OkHttpClient 的拦截器此前对「任意主机 + 路径以 /api/ 开头
     * + 无 Authorization 头」的请求一律注入柠檬服务器的 Bearer 令牌。用户同时配置了 NAS、
     * 第三方音源或其它自建服务时，只要其接口路径以 /api/ 开头，就会收到本机的柠檬服务器令牌
     * —— 属于凭据外泄。现在只有登录成功过的那台服务器主机才会被注入。
     */
    private val authTokenHosts = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun setActiveAuthToken(token: String, host: String? = null) {
        if (token.isNotBlank()) {
            activeAuthToken = token.trim()
            // Token 必须始终与唯一宿主绑定；host 为空时清空旧白名单，避免新 Token 发给旧主机。
            authTokenHosts.clear()
            host?.trim()?.takeIf { it.isNotBlank() }?.let {
                authTokenHosts.clear()
                authTokenHosts[it.substringBefore(':').lowercase()] = true
            }
        }
    }

    fun getActiveAuthToken(): String = activeAuthToken

    fun clearActiveAuthToken() {
        activeAuthToken = ""
        authTokenHosts.clear()
    }

    /** 该主机是否被授权接收全局令牌（未登记任何主机时一律不注入） */
    private fun isTokenHostAllowed(host: String): Boolean {
        if (authTokenHosts.isEmpty()) return false
        return authTokenHosts.containsKey(host.lowercase())
    }

    fun createOkHttpClient(context: Context): OkHttpClient {
        return getSharedClient(context)
    }

    @Synchronized
    fun getSharedClient(context: Context): OkHttpClient {
        sharedClient?.let { return it }

        installSecurityProvider()

        val appContext = context.applicationContext
        val cacheDir = appContext.cacheDir.resolve("okhttp_http_cache")
        val cache = try {
            // HTTP 磁盘缓存 16MB（原 64MB）：音频流接口已强制 FORCE_NETWORK 绕过该缓存，
            // 其余接口（JSON/图片）收益有限，没必要占 64MB 磁盘配额
            okhttp3.Cache(cacheDir, 16L * 1024 * 1024)
        } catch (_: Exception) {
            null
        }

        val pool = okhttp3.ConnectionPool(32, 5, TimeUnit.MINUTES)
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        }

        val builder = OkHttpClient.Builder()
            .connectionPool(pool)
            .dispatcher(dispatcher)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // **整个请求的总时长上限**（含连接、写入、服务端处理与读取）。
            //
            // connect/read/write 三个超时都是「单次 IO 操作」的语义，不是「请求总时长」：
            // 只要服务端持续慢速吐数据，readTimeout 就永远不会触发，请求可以拖到几分钟甚至更久。
            // 下载收尾阶段会同步拉取【封面图】与【歌词】，这两次请求此前没有任何总时长约束 ——
            // 一旦对方响应缓慢，下载进度条就会在 100% 停留很久（用户反复反馈的现象之一）。
            //
            // 300 秒足以覆盖整库同步这类大响应（readTimeout 仍是 60 秒，
            // 正常情况下单次读取不会超时），只用来兜住「长时间挂死」的极端情况。
            .callTimeout(300, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (cache != null) {
            builder.cache(cache)
        }

        // 统一注入现代主流 User-Agent 与自动补齐柠檬服务端 Authorization 头
        builder.addInterceptor { chain ->
            val request = chain.request()
            val reqBuilder = request.newBuilder()
            if (request.header("User-Agent").isNullOrBlank()) {
                reqBuilder.header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            }
            val encodedPath = request.url.encodedPath
            // 自动为第三方音乐平台 CDN 音频直链补齐防盗链 Referer / Origin 头
            val cdnPlatform = MusicCdnHeaders.guessSourceFromUrl(request.url.toString())
            if (cdnPlatform.isNotBlank() && request.header("Referer").isNullOrBlank()) {
                val cdnHeaders = MusicCdnHeaders.buildHeaders(cdnPlatform)
                cdnHeaders.forEach { (k, v) ->
                    if (request.header(k).isNullOrBlank()) {
                        reqBuilder.header(k, v)
                    }
                }
            }
            // 音频流式接口绕过 OkHttp 磁盘缓存（交由 Media3 SimpleCache 统一管理），避免 206 Range 截断冲突
            if (encodedPath.contains("/api/play/")) {
                reqBuilder.cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
            }
            // 若 URL 中携带 token 参数或访问柠檬服务端 API 且未显式指定 Authorization 请求头，自动补充 Bearer Token
            // 仅对「已登记令牌的服务器主机」注入，避免把柠檬服务器令牌发给第三方 /api/ 接口
            val tokenParam = request.url.queryParameter("token")
            if (request.header("Authorization").isNullOrBlank()) {
                if (!tokenParam.isNullOrBlank() && isTokenHostAllowed(request.url.host)) {
                    reqBuilder.header("Authorization", "Bearer $tokenParam")
                } else if (activeAuthToken.isNotBlank() &&
                    encodedPath.startsWith("/api/") &&
                    isTokenHostAllowed(request.url.host)
                ) {
                    reqBuilder.header("Authorization", "Bearer $activeAuthToken")
                }
            }
            val sampleLatency = encodedPath.startsWith("/api/") &&
                !encodedPath.contains("/api/play/local") &&
                !encodedPath.contains("/api/play/proxy") &&
                !encodedPath.contains("/api/play/local-ape") &&
                !encodedPath.contains("/api/download/") &&
                // 曲库全量/批量接口天然很慢（当前服务器 /api/library/tracks?all=1 可达 15s），
                // 把它计入会污染「服务器控制面延迟」，让音源看板误报 15s 并误触发直链兜底。
                !encodedPath.contains("/api/library/") &&
                !encodedPath.contains("/api/source/health/") &&
                !encodedPath.contains("/api/tag/")
            val startedAt = System.currentTimeMillis()
            try {
                val response = chain.proceed(reqBuilder.build())
                val elapsed = System.currentTimeMillis() - startedAt
                if (sampleLatency) {
                    ServerLatencyMonitor.record(
                        host = request.url.host,
                        elapsedMs = elapsed,
                        success = response.isSuccessful
                    )
                    // 4xx/5xx 也说明服务器可达；只有 IO 失败/超过 10s 才算 LOST
                    com.lm.player.core.source.SourceAccelerationController.onServerSample(
                        host = request.url.host,
                        elapsedMs = elapsed,
                        success = true
                    )
                }
                response
            } catch (e: java.io.IOException) {
                val elapsed = System.currentTimeMillis() - startedAt
                if (sampleLatency) {
                    ServerLatencyMonitor.record(
                        host = request.url.host,
                        elapsedMs = elapsed,
                        success = false
                    )
                    com.lm.player.core.source.SourceAccelerationController.onServerSample(
                        host = request.url.host,
                        elapsedMs = elapsed,
                        success = false
                    )
                }
                throw e
            }
        }

        // API 23~28 专属 SSLContext 配置
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            try {
                val conscryptProvider = Conscrypt.newProvider()
                val sslContext = SSLContext.getInstance("TLS", conscryptProvider).apply {
                    init(null, null, null)
                }
                val trustManager: X509TrustManager = Conscrypt.getDefaultX509TrustManager()
                builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            } catch (e: Exception) {
                Log.w(TAG, "Using platform default SSL engine as fallback", e)
            }
        }

        // 自动兼容自签名、过期、域名不匹配、证书链不完整的 HTTPS 服务器。
        // 仅用于当前自建服务端场景；这会放宽全局 TLS 校验，请勿用于公共支付/账号场景。
        val trustAllManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val trustAllContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAllManager), SecureRandom())
        }
        builder.sslSocketFactory(trustAllContext.socketFactory, trustAllManager)
        builder.hostnameVerifier { _, _ -> true }

        val client = builder.build()
        sharedClient = client
        return client
    }
}
