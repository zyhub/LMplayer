package com.lm.player.core.network

import android.content.Context
import android.os.Build
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.conscrypt.Conscrypt
import java.security.Security
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
    private val authTokenHosts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun setActiveAuthToken(token: String, host: String? = null) {
        if (token.isNotBlank()) {
            activeAuthToken = token.trim()
            host?.trim()?.takeIf { it.isNotBlank() }?.let {
                authTokenHosts.add(it.substringBefore(':').lowercase())
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
        return authTokenHosts.contains(host.lowercase())
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
            chain.proceed(reqBuilder.build())
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

        val client = builder.build()
        sharedClient = client
        return client
    }
}
