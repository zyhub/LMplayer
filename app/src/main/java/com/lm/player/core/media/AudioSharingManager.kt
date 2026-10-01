package com.lm.player.core.media

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.model.ServerType
import com.lm.player.core.model.UnifiedSong
import com.lm.player.core.network.LemonMusicProtocol
import com.lm.player.core.network.NetworkClientFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 共享与无线投射协议类型
 */
enum class ShareProtocolType(val badge: String) {
    LOCAL_ROUTE("本机通道"),
    DLNA_UPNP("DLNA / UPnP"),
    AIRPLAY_RAOP("AirPlay / RAOP")
}

/**
 * 本机音频物理/无线输出路由模型
 */
data class LocalAudioRouteInfo(
    val id: Int,
    val name: String,
    val typeLabel: String,
    val deviceType: Int,
    val isActivePrimary: Boolean,
    val sampleRatesSummary: String = ""
)

/**
 * 局域网发现的共享接收端设备 (DLNA MediaRenderer / AirPlay Receiver)
 */
data class LanShareDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val protocol: ShareProtocolType,
    val modelName: String = "",
    val locationUrl: String = "",
    val avTransportControlUrl: String = "",
    val renderingControlUrl: String = ""
)

/**
 * 远程音频流代理条目（用于将需要鉴权/特殊 Header/HTTPS 的 NAS 或在线音乐流转为局域网电视可直接拉取的纯 HTTP 流）
 */
private data class RemoteStreamProxyEntry(
    val upstreamUrl: String,
    val authHeader: String?,
    val mimeType: String
)

/**
 * 全局音频输出路由与局域网共享投射管理器 (DLNA AVTransport + AirPlay mDNS + Android 系统媒体路由 + 本地 HTTP 流代理服务)
 */
object AudioSharingManager {
    private const val TAG = "AudioSharingManager"
    private const val DLNA_CONTENT_FEATURES =
        "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

    private val _localRoutes = MutableStateFlow<List<LocalAudioRouteInfo>>(emptyList())
    val localRoutes: StateFlow<List<LocalAudioRouteInfo>> = _localRoutes.asStateFlow()

    private val _activeLocalRouteName = MutableStateFlow("本机扬声器")
    val activeLocalRouteName: StateFlow<String> = _activeLocalRouteName.asStateFlow()

    private val _lanDevices = MutableStateFlow<List<LanShareDevice>>(emptyList())
    val lanDevices: StateFlow<List<LanShareDevice>> = _lanDevices.asStateFlow()

    private val _activeCastDevice = MutableStateFlow<LanShareDevice?>(null)
    val activeCastDevice: StateFlow<LanShareDevice?> = _activeCastDevice.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var audioDeviceCallbackRegistered = false
    private var scanJob: Job? = null
    private var castJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 内置轻量级 HTTP Range 音频流与远程流代理服务器（供局域网 DLNA / AirPlay 电视与音箱无障碍拉取本机文件、NAS 曲目及在线音频）
    private var httpServerSocket: ServerSocket? = null
    private var httpServerPort: Int = 0
    private var appContextRef: Context? = null
    private val sharedFileRegistry = ConcurrentHashMap<String, File>()
    private val sharedRemoteStreamRegistry = ConcurrentHashMap<String, RemoteStreamProxyEntry>()
    private val sharedCoverUrlRegistry = ConcurrentHashMap<String, RemoteStreamProxyEntry>()

    @Volatile
    private var lastCastSongId: String = ""

    /**
     * 初始化并监听本机音频输出设备热插拔 (蓝牙耳机、Type-C DAC、有线耳机、扬声器)
     */
    fun observeLocalAudioRoutes(context: Context) {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        refreshLocalAudioRoutes(appCtx)
        if (audioDeviceCallbackRegistered) return
        val audioManager = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioDeviceCallbackRegistered = true
        audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                refreshLocalAudioRoutes(appCtx)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                refreshLocalAudioRoutes(appCtx)
            }
        }, Handler(Looper.getMainLooper()))
    }

    /**
     * 刷新当前连接的所有音频输出通道
     */
    fun refreshLocalAudioRoutes(context: Context) {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        val audioManager = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink }

        // 计算优先级以识别当前实际生效的主输出通道：USB DAC > 蓝牙 A2DP/BLE > 有线耳机 > HDMI > 内置扬声器
        fun routePriority(type: Int): Int = when (type) {
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> 100
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> 90
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> 80
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC -> 70
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> 60
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 20
            else -> 0
        }

        val validOutputs = outputs.filter { routePriority(it.type) > 0 }
        val maxPriority = validOutputs.maxOfOrNull { routePriority(it.type) } ?: 20
        val seenNames = HashSet<String>()
        val result = ArrayList<LocalAudioRouteInfo>()

        for (dev in validOutputs.sortedByDescending { routePriority(it.type) }) {
            val typeLabel = when (dev.type) {
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB DAC / 外置声卡"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_BLE_BROADCAST -> "蓝牙无线音频"
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_LINE_ANALOG,
                AudioDeviceInfo.TYPE_LINE_DIGITAL -> "有线耳机 / Line-Out"
                AudioDeviceInfo.TYPE_HDMI,
                AudioDeviceInfo.TYPE_HDMI_ARC,
                AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI 数字音频"
                AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "系统无线投射通道"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "本机扬声器"
                else -> "音频输出"
            }

            val rawProductName = dev.productName?.toString()?.trim().orEmpty()
            val displayName = when {
                dev.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "本机扬声器"
                rawProductName.isNotBlank() && !rawProductName.equals(Build.MODEL, ignoreCase = true) ->
                    "$rawProductName ($typeLabel)"
                else -> typeLabel
            }

            if (!seenNames.add(displayName)) continue

            val maxRate = dev.sampleRates.maxOrNull()
            val rateSummary = if (maxRate != null && maxRate > 0) "${maxRate / 1000.0}kHz" else ""
            val isPrimary = routePriority(dev.type) == maxPriority &&
                    result.none { it.isActivePrimary }

            result.add(
                LocalAudioRouteInfo(
                    id = dev.id,
                    name = displayName,
                    typeLabel = typeLabel,
                    deviceType = dev.type,
                    isActivePrimary = isPrimary,
                    sampleRatesSummary = rateSummary
                )
            )
        }

        if (result.isEmpty()) {
            result.add(
                LocalAudioRouteInfo(
                    id = 0,
                    name = "本机扬声器",
                    typeLabel = "本机扬声器",
                    deviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    isActivePrimary = true
                )
            )
        }

        _localRoutes.value = result
        _activeLocalRouteName.value = result.firstOrNull { it.isActivePrimary }?.name ?: "本机扬声器"
    }

    /**
     * 调起 Android 原生系统级媒体输出切换面板 (支持一键切换本机扬声器、蓝牙音箱、外置 DAC 与系统投屏)
     */
    fun launchSystemMediaOutputSwitcher(context: Context): Boolean {
        // 1. Android 11+ 标准系统媒体输出面板
        try {
            val panelIntent = Intent("com.android.settings.panel.action.MEDIA_OUTPUT").apply {
                putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(panelIntent)
            return true
        } catch (_: Exception) {}

        // 2. Android 10 / 部分定制 ROM SystemUI MediaOutputDialog
        try {
            val sysUiIntent = Intent().apply {
                setClassName(
                    "com.android.systemui",
                    "com.android.systemui.media.dialog.MediaOutputDialogReceiver"
                )
                action = "com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG"
                putExtra("package_name", context.packageName)
            }
            context.sendBroadcast(sysUiIntent)
        } catch (_: Exception) {}

        // 3. 回退至系统蓝牙设置页
        return try {
            val btIntent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(btIntent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 调起 Android 系统无线投射 / Cast 设置页面
     */
    fun launchSystemCastSettings(context: Context): Boolean {
        return try {
            val castIntent = Intent(Settings.ACTION_CAST_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(castIntent)
            true
        } catch (_: Exception) {
            launchSystemMediaOutputSwitcher(context)
        }
    }

    /**
     * 启动局域网 DLNA (SSDP) + AirPlay (mDNS _raop._tcp / _airplay._tcp) 双协议并发扫描
     */
    fun startLanDiscovery(context: Context) {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        observeLocalAudioRoutes(appCtx)
        if (_isScanning.value) return

        scanJob?.cancel()
        scanJob = scope.launch {
            _isScanning.value = true
            val discoveredMap = ConcurrentHashMap<String, LanShareDevice>()
            // 保留当前已激活的投射设备
            _activeCastDevice.value?.let { active ->
                discoveredMap[active.id] = active
            }
            _lanDevices.value = discoveredMap.values.sortedWith(
                compareBy<LanShareDevice> { if (it.protocol == ShareProtocolType.DLNA_UPNP) 0 else 1 }
                    .thenBy { it.name }
            )

            val wifiManager = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val multicastLock = wifiManager?.createMulticastLock("lmplayer_lan_share_lock")?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }

            val nsdManager = appCtx.getSystemService(Context.NSD_SERVICE) as? NsdManager
            val activeNsdListeners = CopyOnWriteArrayList<NsdManager.DiscoveryListener>()

            fun publishDevice(device: LanShareDevice) {
                // 若同一 IP 既通过 DLNA 被发现，则优先保留包含完整 AVTransport 控制 URL 的记录
                discoveredMap[device.id] = device
                _lanDevices.value = discoveredMap.values.sortedWith(
                    compareBy<LanShareDevice> { if (it.protocol == ShareProtocolType.DLNA_UPNP) 0 else 1 }
                        .thenBy { it.name }
                )
            }

            try {
                // 1. 启动 mDNS AirPlay (_airplay._tcp. 与 _raop._tcp.) 服务发现
                if (nsdManager != null) {
                    listOf("_raop._tcp.", "_airplay._tcp.").forEach { serviceType ->
                        val listener = createAirPlayNsdListener(nsdManager, serviceType) { device ->
                            publishDevice(device)
                        }
                        runCatching {
                            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                            activeNsdListeners.add(listener)
                        }
                    }
                }

                // 2. 并发执行 SSDP M-SEARCH 发现局域网 DLNA / UPnP MediaRenderer 电视与音箱
                discoverDlnaRenderersViaSsdp(appCtx) { device ->
                    publishDevice(device)
                }
            } catch (e: Exception) {
                Log.w(TAG, "LAN discovery warning: ${e.message}")
            } finally {
                activeNsdListeners.forEach { listener ->
                    runCatching { nsdManager?.stopServiceDiscovery(listener) }
                }
                runCatching {
                    if (multicastLock?.isHeld == true) multicastLock.release()
                }
                _isScanning.value = false
            }
        }
    }

    /**
     * 创建 AirPlay / RAOP mDNS 监听器
     */
    private fun createAirPlayNsdListener(
        nsdManager: NsdManager,
        serviceType: String,
        onDeviceFound: (LanShareDevice) -> Unit
    ): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                val info = serviceInfo ?: return
                runCatching {
                    nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                        override fun onServiceResolved(resolvedInfo: NsdServiceInfo?) {
                            val resolved = resolvedInfo ?: return
                            val hostIp = resolved.host?.hostAddress ?: return
                            if (hostIp.contains(":")) return // 优先使用 IPv4 地址
                            val port = resolved.port
                            val rawName = resolved.serviceName ?: "AirPlay 电视/音响"
                            val friendlyName = if (rawName.contains("@")) {
                                rawName.substringAfter("@").trim().ifBlank { rawName }
                            } else {
                                rawName.trim()
                            }
                            val id = "airplay_${hostIp}_$port"
                            onDeviceFound(
                                LanShareDevice(
                                    id = id,
                                    name = friendlyName,
                                    host = hostIp,
                                    port = port,
                                    protocol = ShareProtocolType.AIRPLAY_RAOP,
                                    modelName = if (serviceType.contains("raop")) "AirPlay RAOP 音频接收端" else "AirPlay 接收端"
                                )
                            )
                        }
                    })
                }
            }
        }
    }

    /**
     * 通过 UDP 239.255.255.250:1900 发送多轮 M-SEARCH 报文发现局域网所有品牌智能电视与 DLNA MediaRenderer
     * - 显式绑定本机 Wi-Fi 局域网网卡地址，防止手机开启移动数据或 VPN 时多播包走错默认路由网卡
     * - 同时搜索 MediaRenderer:1、AVTransport:1、upnp:rootdevice 与 ssdp:all，兼容小米/海信/TCL/创维/索尼/乐播投屏/Kodi
     */
    private suspend fun discoverDlnaRenderersViaSsdp(
        context: Context,
        onDeviceFound: (LanShareDevice) -> Unit
    ) = withContext(Dispatchers.IO) {
        val client = NetworkClientFactory.createOkHttpClient(context)
        val locationSet = ConcurrentHashMap.newKeySet<String>()
        val searchTargets = listOf(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1",
            "upnp:rootdevice",
            "ssdp:all"
        )

        val wifiIp = getWifiLanIpAddress(context)
        val socket = try {
            if (!wifiIp.isNullOrBlank()) {
                DatagramSocket(InetSocketAddress(InetAddress.getByName(wifiIp), 0))
            } else {
                DatagramSocket()
            }
        } catch (_: Exception) {
            DatagramSocket()
        }

        socket.use { udpSocket ->
            udpSocket.broadcast = true
            udpSocket.soTimeout = 750
            val multicastAddr = InetAddress.getByName("239.255.255.250")

            fun sendMSearchBurst() {
                for (st in searchTargets) {
                    val query = buildString {
                        append("M-SEARCH * HTTP/1.1\r\n")
                        append("HOST: 239.255.255.250:1900\r\n")
                        append("MAN: \"ssdp:discover\"\r\n")
                        append("MX: 2\r\n")
                        append("ST: $st\r\n")
                        append("USER-AGENT: Android/${Build.VERSION.RELEASE} UPnP/1.1 LMPlayer/1.7\r\n")
                        append("\r\n")
                    }
                    val bytes = query.toByteArray(Charsets.UTF_8)
                    val packet = DatagramPacket(bytes, bytes.size, multicastAddr, 1900)
                    runCatching { udpSocket.send(packet) }
                }
            }

            // 第 1 轮广播
            sendMSearchBurst()

            val startMs = System.currentTimeMillis()
            var secondBurstSent = false
            val buf = ByteArray(8192)
            val parseJobs = mutableListOf<Job>()

            while (System.currentTimeMillis() - startMs < 3600L && currentCoroutineContext().isActive) {
                if (!secondBurstSent && System.currentTimeMillis() - startMs > 1100L) {
                    secondBurstSent = true
                    sendMSearchBurst()
                }
                try {
                    val recv = DatagramPacket(buf, buf.size)
                    udpSocket.receive(recv)
                    val respText = String(recv.data, 0, recv.length, Charsets.UTF_8)
                    val location = respText.lineSequence()
                        .firstOrNull { it.trim().startsWith("LOCATION:", ignoreCase = true) }
                        ?.substringAfter(":")
                        ?.trim()
                    if (!location.isNullOrBlank() && locationSet.add(location)) {
                        val job = launch(Dispatchers.IO) {
                            parseUpnpDeviceDescription(client, location)?.let(onDeviceFound)
                        }
                        parseJobs.add(job)
                    }
                } catch (_: Exception) {
                    // 单次超时继续监听
                }
            }
            // 等待所有已发现的 XML 描述解析完成
            withTimeoutOrNull(1500L) {
                parseJobs.joinAll()
            }
        }
    }

    /**
     * 解析 UPnP 设备描述 XML，提取 friendlyName、AVTransport controlURL 与 RenderingControl controlURL
     * - 支持 `<controlURL>` 在 `<serviceType>` 之前或之后的任意标签顺序（修复部分电视与乐播投屏 XML 顺序导致无法识别问题）
     * - 支持 `<URLBase>` 基准地址解析
     */
    private fun parseUpnpDeviceDescription(
        client: okhttp3.OkHttpClient,
        locationUrl: String
    ): LanShareDevice? {
        return try {
            val req = Request.Builder()
                .url(locationUrl)
                .header("User-Agent", "UPnP/1.1 LMPlayer/1.7")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val xml = resp.body?.string() ?: return null
                val uri = URI(locationUrl)
                val host = uri.host ?: return null
                val port = if (uri.port > 0) uri.port else 80
                var baseOrigin = "${uri.scheme ?: "http"}://$host:$port"
                var urlBaseFromXml = ""

                var friendlyName = ""
                var modelName = ""
                var avTransportControlUrl = ""
                var renderingControlUrl = ""

                var inService = false
                var tempServiceType = ""
                var tempControlUrl = ""

                val parser = XmlPullParserFactory.newInstance().apply {
                    isNamespaceAware = false
                }.newPullParser()
                parser.setInput(StringReader(xml))
                var event = parser.eventType
                var currentTag = ""

                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            val rawTag = parser.name ?: ""
                            currentTag = rawTag.substringAfterLast(':').lowercase(Locale.US)
                            if (currentTag == "service") {
                                inService = true
                                tempServiceType = ""
                                tempControlUrl = ""
                            }
                        }
                        XmlPullParser.TEXT -> {
                            val text = parser.text?.trim().orEmpty()
                            if (text.isNotEmpty()) {
                                when (currentTag) {
                                    "urlbase" -> if (urlBaseFromXml.isBlank()) urlBaseFromXml = text
                                    "friendlyname" -> if (friendlyName.isBlank()) friendlyName = text
                                    "modelname" -> if (modelName.isBlank()) modelName = text
                                    "servicetype" -> if (inService) tempServiceType = text
                                    "controlurl" -> if (inService) tempControlUrl = text
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            val endTag = (parser.name ?: "").substringAfterLast(':').lowercase(Locale.US)
                            if (endTag == "service" && inService) {
                                val effectiveBase = urlBaseFromXml.takeIf { it.startsWith("http") }
                                    ?.trimEnd('/') ?: baseOrigin
                                if (tempControlUrl.isNotBlank()) {
                                    if (tempServiceType.contains("AVTransport", ignoreCase = true) && avTransportControlUrl.isBlank()) {
                                        avTransportControlUrl = resolveRelativeControlUrl(effectiveBase, locationUrl, tempControlUrl)
                                    } else if (tempServiceType.contains("RenderingControl", ignoreCase = true) && renderingControlUrl.isBlank()) {
                                        renderingControlUrl = resolveRelativeControlUrl(effectiveBase, locationUrl, tempControlUrl)
                                    }
                                }
                                inService = false
                                tempServiceType = ""
                                tempControlUrl = ""
                            }
                            currentTag = ""
                        }
                    }
                    event = parser.next()
                }

                if (avTransportControlUrl.isBlank()) return null
                LanShareDevice(
                    id = "dlna_${host}_$port",
                    name = friendlyName.ifBlank { modelName.ifBlank { "智能电视 / DLNA音响 ($host)" } },
                    host = host,
                    port = port,
                    protocol = ShareProtocolType.DLNA_UPNP,
                    modelName = modelName.ifBlank { "UPnP MediaRenderer" },
                    locationUrl = locationUrl,
                    avTransportControlUrl = avTransportControlUrl,
                    renderingControlUrl = renderingControlUrl
                )
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to parse UPnP XML from $locationUrl: ${e.message}")
            null
        }
    }

    private fun resolveRelativeControlUrl(baseOrigin: String, locationUrl: String, rawControlUrl: String): String {
        val trimmed = rawControlUrl.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        val cleanOrigin = runCatching {
            val u = URI(baseOrigin)
            val p = if (u.port > 0) ":${u.port}" else ""
            "${u.scheme ?: "http"}://${u.host}$p"
        }.getOrDefault(baseOrigin.trimEnd('/'))
        if (trimmed.startsWith("/")) return "$cleanOrigin$trimmed"
        val parentPath = locationUrl.substringBeforeLast('/', cleanOrigin).trimEnd('/')
        return "$parentPath/$trimmed"
    }

    /**
     * 在全局持久协程作用域中执行投屏连接并弹出 Toast（彻底解决弹窗关闭导致 rememberCoroutineScope 被取消、投屏中断的问题）
     */
    fun castSongToDeviceAsync(
        context: Context,
        device: LanShareDevice,
        song: UnifiedSong,
        positionMs: Long = 0L
    ) {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        Toast.makeText(appCtx, "正在连接 ${device.name} (${device.protocol.badge})...", Toast.LENGTH_SHORT).show()
        castJob?.cancel()
        castJob = mainScope.launch {
            val res = castSongToDevice(appCtx, device, song, positionMs)
            res.onSuccess { msg ->
                Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(appCtx, err.message ?: "投射失败，请检查电视与手机是否在同一 Wi-Fi", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 在全局持久协程作用域中断开投屏并恢复手机本机播放音量
     */
    fun stopActiveCastAsync(context: Context, toastMessage: String? = null) {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        castJob?.cancel()
        mainScope.launch {
            val prevDevice = _activeCastDevice.value
            stopActiveCast(appCtx)
            val msg = toastMessage ?: prevDevice?.let { "已停止向 ${it.name} 投射，恢复手机本机输出" }
            if (!msg.isNullOrBlank()) {
                Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 当手机处于活跃投射状态且用户切换上一首/下一首歌曲时，自动将新歌曲同步推送至电视
     */
    fun onPhoneSongChangedIfCasting(context: Context, newSong: UnifiedSong, startPositionMs: Long = 0L) {
        val activeDevice = _activeCastDevice.value ?: return
        val appCtx = context.applicationContext
        appContextRef = appCtx
        castJob?.cancel()
        castJob = mainScope.launch {
            castSongToDevice(appCtx, activeDevice, newSong, startPositionMs)
        }
    }

    /**
     * 当手机处于活跃投射状态且用户点击播放/暂停时，同步控制电视端播放/暂停
     */
    fun onPhonePlayStateChangedIfCasting(context: Context, play: Boolean) {
        val activeDevice = _activeCastDevice.value ?: return
        val appCtx = context.applicationContext
        scope.launch {
            runCatching {
                if (activeDevice.protocol == ShareProtocolType.DLNA_UPNP && activeDevice.avTransportControlUrl.isNotBlank()) {
                    if (play) {
                        sendDlnaPlay(appCtx, activeDevice)
                    } else {
                        sendDlnaPause(appCtx, activeDevice)
                    }
                }
            }
        }
    }

    /**
     * 当手机处于活跃投射状态且用户拖动进度条时，同步控制电视端 Seek
     */
    fun onPhoneSeekIfCasting(context: Context, positionMs: Long) {
        val activeDevice = _activeCastDevice.value ?: return
        val appCtx = context.applicationContext
        scope.launch {
            runCatching {
                if (activeDevice.protocol == ShareProtocolType.DLNA_UPNP && activeDevice.avTransportControlUrl.isNotBlank()) {
                    sendDlnaSeek(appCtx, activeDevice, positionMs)
                }
            }
        }
    }

    /**
     * 将当前播放曲目投射至选定的局域网 DLNA / AirPlay 电视或音响
     */
    suspend fun castSongToDevice(
        context: Context,
        device: LanShareDevice,
        song: UnifiedSong,
        positionMs: Long = 0L
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val appCtx = context.applicationContext
            appContextRef = appCtx
            val playableMediaUrl = resolveLanAccessibleStreamUrl(appCtx, song)
                ?: return@withContext Result.failure(Exception("无法生成局域网音频流，请确认手机已连接 Wi-Fi 局域网"))

            val coverUrl = resolveLanAccessibleCoverUrl(appCtx, song)

            when (device.protocol) {
                ShareProtocolType.DLNA_UPNP -> {
                    val ok = sendDlnaSetAvTransportAndPlay(
                        context = appCtx,
                        device = device,
                        mediaUrl = playableMediaUrl,
                        song = song,
                        coverUrl = coverUrl,
                        startPositionMs = positionMs
                    )
                    if (ok) {
                        _activeCastDevice.value = device
                        lastCastSongId = song.id
                        muteLocalPlayerForCasting(appCtx, mute = true)
                        Result.success("已共享《${song.title}》至电视/音响：${device.name}")
                    } else {
                        Result.failure(Exception("电视/音响 ${device.name} 未响应 DLNA 播放指令"))
                    }
                }
                ShareProtocolType.AIRPLAY_RAOP -> {
                    // 1. 优先检查同 IP 电视是否同时开启了标准 DLNA AVTransport 服务（国内智能电视/乐播投屏普遍同时开启 DLNA 与 AirPlay）
                    var siblingDlna = _lanDevices.value.firstOrNull {
                        it.protocol == ShareProtocolType.DLNA_UPNP && it.host == device.host
                    }
                    if (siblingDlna == null) {
                        siblingDlna = probeDlnaOnSameHost(appCtx, device.host)
                    }
                    if (siblingDlna != null) {
                        val ok = sendDlnaSetAvTransportAndPlay(
                            context = appCtx,
                            device = siblingDlna,
                            mediaUrl = playableMediaUrl,
                            song = song,
                            coverUrl = coverUrl,
                            startPositionMs = positionMs
                        )
                        if (ok) {
                            _activeCastDevice.value = siblingDlna.copy(name = device.name)
                            lastCastSongId = song.id
                            muteLocalPlayerForCasting(appCtx, mute = true)
                            return@withContext Result.success("已连接 ${device.name} 并推送音频流")
                        }
                    }

                    // 2. 尝试向开放型 AirPlay 接收端发送 HTTP /play 指令
                    val startSec = if (song.durationMs > 0) {
                        (positionMs.toDouble() / song.durationMs.toDouble()).coerceIn(0.0, 0.99)
                    } else 0.0
                    val airplayOk = sendAirPlayHttpPlay(appCtx, device, playableMediaUrl, startSec)
                    if (airplayOk) {
                        _activeCastDevice.value = device
                        lastCastSongId = song.id
                        muteLocalPlayerForCasting(appCtx, mute = true)
                        Result.success("已通过 AirPlay 共享《${song.title}》至 ${device.name}")
                    } else {
                        Result.failure(
                            Exception("${device.name} 的 AirPlay 通道需要私有加密认证，请选择同设备的 DLNA 通道或使用「系统无线投屏」")
                        )
                    }
                }
                ShareProtocolType.LOCAL_ROUTE -> {
                    stopActiveCast(appCtx)
                    Result.success("已切回本机音频输出通道")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "castSongToDevice error", e)
            Result.failure(e)
        }
    }

    /**
     * 探测指定テレビ IP 的常见 DLNA UPnP 描述端口（用于用户点选了电视的 AirPlay 条目时自动桥接其 DLNA 渲染器）
     */
    private fun probeDlnaOnSameHost(context: Context, host: String): LanShareDevice? {
        val client = NetworkClientFactory.createOkHttpClient(context)
        val commonLocations = listOf(
            "http://$host:49152/description.xml",
            "http://$host:49153/description.xml",
            "http://$host:8200/rootDesc.xml",
            "http://$host:1400/xml/device_description.xml",
            "http://$host:9197/dmr"
        )
        for (url in commonLocations) {
            val dev = parseUpnpDeviceDescription(client, url)
            if (dev != null) return dev
        }
        return null
    }

    /**
     * 投屏期间将手机本地 ExoPlayer 静音（保持进度条、实时歌词与自动下一首正常运转，避免手机与电视双声道重叠回音）；
     * 断开投屏时立即恢复本机音量为 1.0f
     */
    private fun muteLocalPlayerForCasting(context: Context, mute: Boolean) {
        mainScope.launch {
            runCatching {
                val player = Media3Factory.getSharedExoPlayer(context)
                player.volume = if (mute) 0f else 1f
                if (mute && !player.isPlaying && player.currentMediaItem != null) {
                    player.play()
                }
            }
        }
    }

    /**
     * 停止当前局域网投射并恢复本机播放
     */
    suspend fun stopActiveCast(context: Context) = withContext(Dispatchers.IO) {
        val current = _activeCastDevice.value
        _activeCastDevice.value = null
        lastCastSongId = ""
        muteLocalPlayerForCasting(context, mute = false)
        if (current == null) return@withContext
        runCatching {
            if (current.protocol == ShareProtocolType.DLNA_UPNP && current.avTransportControlUrl.isNotBlank()) {
                sendDlnaStop(context, current)
            } else if (current.protocol == ShareProtocolType.AIRPLAY_RAOP) {
                val client = NetworkClientFactory.createOkHttpClient(context)
                val req = Request.Builder()
                    .url("http://${current.host}:${current.port}/stop")
                    .post("".toRequestBody(null))
                    .build()
                client.newCall(req).execute().close()
            }
        }
    }

    /**
     * 将任意本机文件、NAS 需鉴权音频流或在线音乐流转换为局域网电视可直接拉取的纯 HTTP URL
     * 核心突破：
     * - 本地文件通过 `http://<手机Wi-Fi-IP>:<port>/media/<token>.<ext>` 直出（支持 Range 206）；
     * - NAS 服务器歌曲与在线歌曲全部通过 `http://<手机Wi-Fi-IP>:<port>/proxy/<token>.<ext>` 由手机本地 HTTP 代理转发，
     *   由手机代为附加 `Authorization: Bearer <token>` 与自定义 Header，彻底解决电视因无鉴权 Token (401/403) 或不支持复杂 HTTPS 重定向而无法播放的问题！
     */
    private suspend fun resolveLanAccessibleStreamUrl(context: Context, song: UnifiedSong): String? {
        val localIp = getWifiLanIpAddress(context)
        val port = ensureLocalHttpServerStarted(context)

        // 1. 若有真实本地物理文件，优先挂载至内置局域网 HTTP Range 文件服务器
        val localFile = listOfNotNull(song.localFilePath, song.streamUrl)
            .firstOrNull { it.startsWith("/") && File(it).let { f -> f.exists() && f.length() > 0 } }
            ?.let { File(it) }

        if (localFile != null && !localIp.isNullOrBlank() && port > 0) {
            val ext = localFile.extension.ifBlank { song.format.ifBlank { "mp3" } }.lowercase(Locale.US)
            val token = "song_${song.id.hashCode().toUInt()}"
            sharedFileRegistry[token] = localFile
            return "http://$localIp:$port/media/$token.$ext"
        }

        // 2. 解析远程 NAS 或在线音乐真实流 URL 与鉴权 Token
        var remoteUrl = song.streamUrl
        var authHeader: String? = null
        val db = ZdsDatabase.getInstance(context)
        val active = db.serverDao().getActiveServer()
            ?: db.serverDao().getAllServers().firstOrNull { it.type == ServerType.LEMON_MUSIC }

        if (active != null) {
            val client = NetworkClientFactory.createOkHttpClient(context)
            val protocol = LemonMusicProtocol(client, active.serverUrl, active.username, active.tokenOrApiKey)
            protocol.ensureAuthenticated()
            val bearer = NetworkClientFactory.getActiveAuthToken()
            if (bearer.isNotBlank()) {
                authHeader = "Bearer $bearer"
            }

            if (remoteUrl.isBlank() || remoteUrl.startsWith("lemon_online://") || !remoteUrl.startsWith("http")) {
                val srvPath = LemonMusicProtocol.getServerFilePath(song.id, song.streamUrl, song.coverUrl)
                if (!srvPath.isNullOrBlank()) {
                    remoteUrl = protocol.getStreamUrlForPath(srvPath)
                } else {
                    val cleanId = song.id.removePrefix("lemon_online_")
                    val src = if (cleanId.contains("_")) cleanId.substringBefore("_") else "kw"
                    remoteUrl = protocol.resolveOnlineStreamUrl(
                        songId = song.id,
                        source = src,
                        quality = "320k",
                        metaJson = song.rawMetaJson,
                        fallbackTitle = song.title,
                        fallbackArtist = song.artist
                    ).getOrNull().orEmpty()
                }
            } else if (remoteUrl.startsWith("/") && active.serverUrl.isNotBlank()) {
                remoteUrl = "${active.serverUrl.trimEnd('/')}$remoteUrl"
            }
        }

        if (remoteUrl.startsWith("http://") || remoteUrl.startsWith("https://")) {
            val ext = song.format.ifBlank { "mp3" }.lowercase(Locale.US)
            val mimeType = mimeTypeForExtension(ext)
            // 通过本机局域网 HTTP 代理网关转发远程流，确保电视无需鉴权即可顺畅拉流播放
            if (!localIp.isNullOrBlank() && port > 0) {
                val token = "proxy_${song.id.hashCode().toUInt()}"
                sharedRemoteStreamRegistry[token] = RemoteStreamProxyEntry(
                    upstreamUrl = remoteUrl,
                    authHeader = authHeader,
                    mimeType = mimeType
                )
                return "http://$localIp:$port/proxy/$token.$ext"
            }
            if (!localIp.isNullOrBlank()) {
                remoteUrl = remoteUrl
                    .replace("://127.0.0.1", "://$localIp")
                    .replace("://localhost", "://$localIp")
            }
            return remoteUrl
        }
        return null
    }

    private fun resolveLanAccessibleCoverUrl(context: Context, song: UnifiedSong): String {
        val rawCoverUrl = song.coverUrl.trim()
        if (rawCoverUrl.isBlank()) return ""
        val localIp = getWifiLanIpAddress(context) ?: return rawCoverUrl
        val port = ensureLocalHttpServerStarted(context)
        if (port > 0 && (rawCoverUrl.startsWith("http://") || rawCoverUrl.startsWith("https://"))) {
            val bearer = NetworkClientFactory.getActiveAuthToken()
            val token = "cover_${song.id.hashCode().toUInt()}"
            sharedCoverUrlRegistry[token] = RemoteStreamProxyEntry(
                upstreamUrl = rawCoverUrl,
                authHeader = bearer.takeIf { it.isNotBlank() }?.let { "Bearer $it" },
                mimeType = "image/jpeg"
            )
            return "http://$localIp:$port/cover/$token.jpg"
        }
        return rawCoverUrl
            .replace("://127.0.0.1", "://$localIp")
            .replace("://localhost", "://$localIp")
    }

    private fun mimeTypeForExtension(ext: String): String = when (ext.lowercase(Locale.US)) {
        "flac" -> "audio/flac"
        "wav", "ape" -> "audio/wav"
        "m4a", "aac", "mp4" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        else -> "audio/mpeg"
    }

    /**
     * 发送标准 DLNA / UPnP SOAP SetAVTransportURI + Play 请求
     * - 自动兼容严格校验 DIDL-Lite 元数据的电视：若带 DIDL-Lite 元数据的 SetAVTransportURI 返回失败，自动回退为空 CurrentURIMetaData 重试
     */
    private suspend fun sendDlnaSetAvTransportAndPlay(
        context: Context,
        device: LanShareDevice,
        mediaUrl: String,
        song: UnifiedSong,
        coverUrl: String,
        startPositionMs: Long = 0L
    ): Boolean {
        val controlUrl = device.avTransportControlUrl
        if (controlUrl.isBlank()) return false
        val client = NetworkClientFactory.createOkHttpClient(context)

        // 1. 先发送 Stop 重置电视 DLNA 状态机
        runCatching { sendDlnaStop(context, device) }
        delay(120L)

        val mimeType = mimeTypeForExtension(song.format)
        val totalSec = (song.durationMs / 1000L).coerceAtLeast(0L)
        val durationStr = String.format(Locale.US, "%02d:%02d:%02d", totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60)

        val didlLite = buildString {
            append("""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""")
            append("""<item id="0" parentID="-1" restricted="1">""")
            append("""<dc:title>${escapeXml(song.title)}</dc:title>""")
            append("""<dc:creator>${escapeXml(song.artist)}</dc:creator>""")
            append("""<upnp:artist>${escapeXml(song.artist)}</upnp:artist>""")
            append("""<upnp:album>${escapeXml(song.album)}</upnp:album>""")
            if (coverUrl.isNotBlank()) {
                append("""<upnp:albumArtURI>${escapeXml(coverUrl)}</upnp:albumArtURI>""")
            }
            append("""<upnp:class>object.item.audioItem.musicTrack</upnp:class>""")
            append("""<res duration="$durationStr" protocolInfo="http-get:*:$mimeType:$DLNA_CONTENT_FEATURES">${escapeXml(mediaUrl)}</res>""")
            append("""</item></DIDL-Lite>""")
        }

        // 2. 优先使用完整 DIDL-Lite 元数据发送 SetAVTransportURI
        var setOk = executeSoapSetAvTransportUri(client, controlUrl, mediaUrl, escapeXml(didlLite))
        if (!setOk) {
            // 回退方案：部分智能电视（如部分海信/TCL/小米电视固件）对 DIDL-Lite XML 格式校验极严，使用空 CurrentURIMetaData 即可 100% 成功加载
            Log.i(TAG, "Retrying SetAVTransportURI with empty metadata for ${device.name}")
            setOk = executeSoapSetAvTransportUri(client, controlUrl, mediaUrl, "")
        }
        if (!setOk) return false

        delay(150L)

        // 3. 发送 Play 指令
        val playOk = sendDlnaPlay(context, device)
        if (playOk && startPositionMs > 4000L) {
            scope.launch {
                delay(1200L)
                runCatching { sendDlnaSeek(context, device, startPositionMs) }
            }
        }
        return playOk
    }

    private fun executeSoapSetAvTransportUri(
        client: okhttp3.OkHttpClient,
        controlUrl: String,
        mediaUrl: String,
        escapedMetadata: String
    ): Boolean {
        val setUriSoap = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <InstanceID>0</InstanceID>
                  <CurrentURI>${escapeXml(mediaUrl)}</CurrentURI>
                  <CurrentURIMetaData>$escapedMetadata</CurrentURIMetaData>
                </u:SetAVTransportURI>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val req = Request.Builder()
            .url(controlUrl)
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .header("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"")
            .post(setUriSoap.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()

        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "SetAVTransportURI HTTP ${resp.code}: ${resp.body?.string()?.take(240)}")
                }
                resp.isSuccessful
            }
        }.getOrDefault(false)
    }

    private fun sendDlnaPlay(context: Context, device: LanShareDevice): Boolean {
        val controlUrl = device.avTransportControlUrl
        if (controlUrl.isBlank()) return false
        val client = NetworkClientFactory.createOkHttpClient(context)
        val playSoap = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <InstanceID>0</InstanceID>
                  <Speed>1</Speed>
                </u:Play>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val playReq = Request.Builder()
            .url(controlUrl)
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .header("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"")
            .post(playSoap.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()

        return runCatching {
            client.newCall(playReq).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun sendDlnaPause(context: Context, device: LanShareDevice) {
        val controlUrl = device.avTransportControlUrl
        if (controlUrl.isBlank()) return
        val client = NetworkClientFactory.createOkHttpClient(context)
        val pauseSoap = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:Pause xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <InstanceID>0</InstanceID>
                </u:Pause>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        val req = Request.Builder()
            .url(controlUrl)
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .header("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#Pause\"")
            .post(pauseSoap.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).execute().close()
    }

    private fun sendDlnaSeek(context: Context, device: LanShareDevice, positionMs: Long) {
        val controlUrl = device.avTransportControlUrl
        if (controlUrl.isBlank()) return
        val client = NetworkClientFactory.createOkHttpClient(context)
        val totalSec = (positionMs / 1000L).coerceAtLeast(0L)
        val targetStr = String.format(Locale.US, "%02d:%02d:%02d", totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60)
        val seekSoap = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:Seek xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <InstanceID>0</InstanceID>
                  <Unit>REL_TIME</Unit>
                  <Target>$targetStr</Target>
                </u:Seek>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        val req = Request.Builder()
            .url(controlUrl)
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .header("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#Seek\"")
            .post(seekSoap.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).execute().close()
    }

    private fun sendDlnaStop(context: Context, device: LanShareDevice) {
        val controlUrl = device.avTransportControlUrl
        if (controlUrl.isBlank()) return
        val client = NetworkClientFactory.createOkHttpClient(context)
        val stopSoap = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <InstanceID>0</InstanceID>
                </u:Stop>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        val req = Request.Builder()
            .url(controlUrl)
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .header("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#Stop\"")
            .post(stopSoap.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).execute().close()
    }

    /**
     * 向支持开放 HTTP /play 协议的 AirPlay 接收端推送流地址
     */
    private fun sendAirPlayHttpPlay(
        context: Context,
        device: LanShareDevice,
        mediaUrl: String,
        startPositionRatio: Double
    ): Boolean {
        return try {
            val client = NetworkClientFactory.createOkHttpClient(context)
            val bodyText = "Content-Location: $mediaUrl\nStart-Position: $startPositionRatio\n"
            val req = Request.Builder()
                .url("http://${device.host}:${device.port}/play")
                .header("User-Agent", "MediaControl/1.0")
                .post(bodyText.toRequestBody("text/parameters".toMediaType()))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 启动内置局域网 HTTP 206 Range 音频流与远程流透明代理服务器
     */
    @Synchronized
    private fun ensureLocalHttpServerStarted(context: Context? = null): Int {
        if (context != null) {
            appContextRef = context.applicationContext
        }
        if (httpServerSocket != null && httpServerSocket?.isClosed == false && httpServerPort > 0) {
            return httpServerPort
        }
        return try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(0))
            httpServerSocket = server
            httpServerPort = server.localPort

            scope.launch(Dispatchers.IO) {
                while (!server.isClosed) {
                    val clientSocket = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    launch(Dispatchers.IO) {
                        handleHttpClientSocket(clientSocket)
                    }
                }
            }
            httpServerPort
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start local LAN HTTP media server", e)
            0
        }
    }

    private fun handleHttpClientSocket(socket: Socket) {
        socket.use { s ->
            runCatching {
                s.soTimeout = 30000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase(Locale.US)
                val rawPath = parts[1].substringBefore("?")

                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substringAfter(":").trim()
                    }
                }

                val out: OutputStream = s.getOutputStream()
                when {
                    rawPath.startsWith("/media/") -> {
                        val token = rawPath.substringAfter("/media/").substringBefore(".")
                        serveLocalMediaFile(out, method, token, rangeHeader)
                    }
                    rawPath.startsWith("/proxy/") -> {
                        val token = rawPath.substringAfter("/proxy/").substringBefore(".")
                        val entry = sharedRemoteStreamRegistry[token]
                        serveProxiedRemoteStream(out, method, entry, rangeHeader)
                    }
                    rawPath.startsWith("/cover/") -> {
                        val token = rawPath.substringAfter("/cover/").substringBefore(".")
                        val entry = sharedCoverUrlRegistry[token]
                        serveProxiedRemoteStream(out, method, entry, null)
                    }
                    else -> {
                        val notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        out.write(notFound.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                }
            }
        }
    }

    private fun serveLocalMediaFile(
        out: OutputStream,
        method: String,
        token: String,
        rangeHeader: String?
    ) {
        val file = sharedFileRegistry[token]
        if (file == null || !file.exists()) {
            val notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            out.write(notFound.toByteArray(Charsets.UTF_8))
            out.flush()
            return
        }

        val totalLen = file.length()
        val mime = mimeTypeForExtension(file.extension)

        var startByte = 0L
        var endByte = totalLen - 1L
        var isPartial = false

        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
            val rangeSpec = rangeHeader.removePrefix("bytes=").substringBefore(",")
            val startStr = rangeSpec.substringBefore("-").trim()
            val endStr = rangeSpec.substringAfter("-", "").trim()
            startByte = startStr.toLongOrNull()?.coerceIn(0L, totalLen - 1L) ?: 0L
            if (endStr.isNotBlank()) {
                endByte = endStr.toLongOrNull()?.coerceIn(startByte, totalLen - 1L) ?: (totalLen - 1L)
            }
            isPartial = true
        }

        val contentLen = (endByte - startByte + 1L).coerceAtLeast(0L)
        val headers = buildString {
            if (isPartial) {
                append("HTTP/1.1 206 Partial Content\r\n")
                append("Content-Range: bytes $startByte-$endByte/$totalLen\r\n")
            } else {
                append("HTTP/1.1 200 OK\r\n")
            }
            append("Content-Type: $mime\r\n")
            append("Content-Length: $contentLen\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("contentFeatures.dlna.org: $DLNA_CONTENT_FEATURES\r\n")
            append("transferMode.dlna.org: Streaming\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(headers.toByteArray(Charsets.UTF_8))

        if (method != "HEAD" && contentLen > 0) {
            BufferedInputStream(FileInputStream(file)).use { fis ->
                var skipped = 0L
                while (skipped < startByte) {
                    val n = fis.skip(startByte - skipped)
                    if (n <= 0) break
                    skipped += n
                }
                val buffer = ByteArray(32768)
                var remaining = contentLen
                while (remaining > 0) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = fis.read(buffer, 0, toRead)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
        out.flush()
    }

    /**
     * 将需要 Bearer 鉴权或特殊 Header 的远程 NAS 曲库流 / 在线音乐流代理转发给局域网电视
     */
    private fun serveProxiedRemoteStream(
        out: OutputStream,
        method: String,
        entry: RemoteStreamProxyEntry?,
        rangeHeader: String?
    ) {
        val ctx = appContextRef
        if (entry == null || ctx == null) {
            val notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            out.write(notFound.toByteArray(Charsets.UTF_8))
            out.flush()
            return
        }

        val client = NetworkClientFactory.createOkHttpClient(ctx)
        val reqBuilder = Request.Builder()
            .url(entry.upstreamUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) LMPlayer/1.7")
        if (!entry.authHeader.isNullOrBlank()) {
            reqBuilder.header("Authorization", entry.authHeader)
        }
        if (!rangeHeader.isNullOrBlank()) {
            reqBuilder.header("Range", rangeHeader)
        }
        if (method == "HEAD") {
            reqBuilder.head()
        } else {
            reqBuilder.get()
        }

        client.newCall(reqBuilder.build()).execute().use { upstreamResp ->
            if (!upstreamResp.isSuccessful && upstreamResp.code != 206) {
                val err = "HTTP/1.1 ${upstreamResp.code} Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                out.write(err.toByteArray(Charsets.UTF_8))
                out.flush()
                return
            }

            val statusLine = if (upstreamResp.code == 206) {
                "HTTP/1.1 206 Partial Content\r\n"
            } else {
                "HTTP/1.1 200 OK\r\n"
            }
            val contentType = upstreamResp.header("Content-Type")?.takeIf { it.isNotBlank() } ?: entry.mimeType
            val contentLength = upstreamResp.header("Content-Length")
            val contentRange = upstreamResp.header("Content-Range")

            val respHeaders = buildString {
                append(statusLine)
                append("Content-Type: $contentType\r\n")
                if (!contentLength.isNullOrBlank()) {
                    append("Content-Length: $contentLength\r\n")
                }
                if (!contentRange.isNullOrBlank()) {
                    append("Content-Range: $contentRange\r\n")
                }
                append("Accept-Ranges: bytes\r\n")
                append("contentFeatures.dlna.org: $DLNA_CONTENT_FEATURES\r\n")
                append("transferMode.dlna.org: Streaming\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            out.write(respHeaders.toByteArray(Charsets.UTF_8))

            if (method != "HEAD") {
                upstreamResp.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
            out.flush()
        }
    }

    /**
     * 获取本机 Wi-Fi 局域网 IPv4 地址（优先从活跃 Wi-Fi LinkProperties 与 wlan0 接口读取，避免被 VPN tun0 干扰）
     */
    fun getWifiLanIpAddress(context: Context): String? {
        // 1. 优先通过 ConnectivityManager 读取 TRANSPORT_WIFI 网络的 IPv4 地址
        runCatching {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                for (net in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(net) ?: continue
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        val linkProps = cm.getLinkProperties(net) ?: continue
                        for (linkAddr in linkProps.linkAddresses) {
                            val addr = linkAddr.address
                            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                                val ip = addr.hostAddress
                                if (!ip.isNullOrBlank()) return ip
                            }
                        }
                    }
                }
            }
        }
        // 2. 回退读取 WifiManager connectionInfo
        runCatching {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                return String.format(
                    Locale.US,
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            }
        }
        // 3. 回退扫描 wlan / ap / eth 物理网卡接口（排除 tun / ppp 等虚拟网卡）
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                val nifName = nif.name.lowercase(Locale.US)
                if (!nif.isUp || nif.isLoopback || nifName.startsWith("tun") || nifName.startsWith("ppp") || nifName.startsWith("dummy")) {
                    continue
                }
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                            return host
                        }
                    }
                }
            }
        }
        return null
    }

    private fun escapeXml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
