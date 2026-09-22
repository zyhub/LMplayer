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

    fun createOkHttpClient(context: Context): OkHttpClient {
        installSecurityProvider()

        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        // 日志拦截器
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        builder.addInterceptor(logging)

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

        return builder.build()
    }
}
