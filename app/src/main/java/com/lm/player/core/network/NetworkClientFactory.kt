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
            okhttp3.Cache(cacheDir, 64L * 1024 * 1024)
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
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (cache != null) {
            builder.cache(cache)
        }

        // 统一注入现代主流 User-Agent 与自动补齐 Authorization 头
        builder.addInterceptor { chain ->
            val request = chain.request()
            val reqBuilder = request.newBuilder()
            if (request.header("User-Agent").isNullOrBlank()) {
                reqBuilder.header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            }
            // 若 URL 中携带 token 参数且未显式指定 Authorization 请求头，自动补充 Bearer Token
            val tokenParam = request.url.queryParameter("token")
            if (!tokenParam.isNullOrBlank() && request.header("Authorization").isNullOrBlank()) {
                reqBuilder.header("Authorization", "Bearer $tokenParam")
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
