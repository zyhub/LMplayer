package com.lm.player.core.source

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** 粗粒度网络范围：只用于决定「服务器优先」还是「音源加速优先」。 */
enum class NetworkScope { WAN, LAN, UNKNOWN }

object NetworkScopeDetector {
    private const val TAG = "NetworkScope"

    suspend fun detect(context: Context, serverUrl: String): NetworkScope = withContext(Dispatchers.IO) {
        val host = hostOf(serverUrl)
        if (host.isBlank()) return@withContext NetworkScope.UNKNOWN
        val resolved = runCatching {
            InetAddress.getAllByName(host).toList()
        }.getOrDefault(emptyList())
        if (resolved.isNotEmpty()) {
            val hasPrivate = resolved.any { isPrivate(it) }
            val hasPublic = resolved.any { !isPrivate(it) }
            return@withContext when {
                hasPrivate -> NetworkScope.LAN
                hasPublic -> NetworkScope.WAN
                else -> NetworkScope.UNKNOWN
            }
        }
        // DNS 失败：.local/无点主机名按内网，其余按外网，宁可加速也不让服务器慢等待。
        val looksLocal = host == "localhost" || host.endsWith(".local") || !host.contains('.')
        val transport = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        }.getOrDefault(false)
        when {
            looksLocal && transport -> NetworkScope.LAN
            else -> NetworkScope.WAN
        }
    }

    fun hostOf(serverUrl: String): String {
        val raw = serverUrl.trim()
        return raw.substringAfter("://", raw).substringBefore('/').substringBefore('?').substringBefore(':')
    }

    /**
     * 当前是否存在可用公网连接。
     *
     * 只用于“要不要尝试本地音源加速”：无网络时本地脚本同样拿不到 CDN，
     * 提前返回 false 可以避免播放/下载先白等 10~25 秒再回退。
     */
    fun isOnline(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
    }.getOrDefault(false)

    fun isPrivate(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        if (address is Inet4Address) {
            val b = address.address
            val v0 = b[0].toInt() and 0xff
            val v1 = b[1].toInt() and 0xff
            // 100.64.0.0/10：Carrier-Grade NAT，Tailscale/ZeroTier 等组网默认使用该网段。
            return v0 == 10 ||
                (v0 == 172 && v1 in 16..31) ||
                (v0 == 192 && v1 == 168) ||
                (v0 == 100 && v1 in 64..127)
        }
        if (address is Inet6Address) {
            val b = address.address
            val first = b[0].toInt() and 0xff
            return (first and 0xfe) == 0xfc // fc00::/7 ULA
        }
        return false
    }
}
