package com.lm.player.core.media

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
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
 * 全局音频输出路由与局域网共享投射管理器 (DLNA AVTransport + AirPlay mDNS + Android 系统媒体路由 + 本地 HTTP 流服务)
 */
object AudioSharingManager {
    private const val TAG = "AudioSharingManager"

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 内置轻量级 HTTP Range 音频流服务器（供局域网 DLNA / AirPlay 设备拉取手机本地文件与封面）
    private var httpServerSocket: ServerSocket? = null
    private var httpServerPort: Int = 0
    private val sharedFileRegistry = ConcurrentHashMap<String, File>()

    /**
     * 初始化并监听本机音频输出设备热插拔 (蓝牙耳机、Type-C DAC、有线耳机、扬声器)
     */
    fun observeLocalAudioRoutes(context: Context) {
        val appCtx = context.applicationContext
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
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
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
            _lanDevices.value = discoveredMap.values.toList()

            val wifiManager = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val multicastLock = wifiManager?.createMulticastLock("lmplayer_lan_share_lock")?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }

            val nsdManager = appCtx.getSystemService(Context.NSD_SERVICE) as? NsdManager
            val activeNsdListeners = CopyOnWriteArrayList<NsdManager.DiscoveryListener>()

            try {
                // 1. 启动 mDNS AirPlay (_airplay._tcp. 与 _raop._tcp.) 服务发现
                if (nsdManager != null) {
                    listOf("_raop._tcp.", "_airplay._tcp.").forEach { serviceType ->
                        val listener = createAirPlayNsdListener(nsdManager, serviceType) { device ->
                            discoveredMap[device.id] = device
                            _lanDevices.value = discoveredMap.values.sortedBy { it.name }
                        }
                        runCatching {
                            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                            activeNsdListeners.add(listener)
                        }
                    }
                }

                // 2. 并发执行 SSDP M-SEARCH 发现局域网 DLNA / UPnP MediaRenderer 音箱与电视
                discoverDlnaRenderersViaSsdp(appCtx) { device ->
                    discoveredMap[device.id] = device
                    _lanDevices.value = discoveredMap.values.sortedBy { it.name }
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
                            val port = resolved.port
                            // RAOP 服务名形如 "AABBCCDDEEFF@Living Room Speaker"，提取 @ 后的可读名称
                            val rawName = resolved.serviceName ?: "AirPlay 音响"
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
     * 通过 UDP 239.255.255.250:1900 发送 M-SEARCH 报文发现局域网 DLNA / UPnP MediaRenderer
     */
    private suspend fun discoverDlnaRenderersViaSsdp(
        context: Context,
        onDeviceFound: (LanShareDevice) -> Unit
    ) = withContext(Dispatchers.IO) {
        val client = NetworkClientFactory.createOkHttpClient(context)
        val locationSet = ConcurrentHashMap.newKeySet<String>()
        val searchTargets = listOf(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1"
        )

        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 900
            val multicastAddr = InetAddress.getByName("239.255.255.250")

            for (st in searchTargets) {
                val query = buildString {
                    append("M-SEARCH * HTTP/1.1\r\n")
                    append("HOST: 239.255.255.250:1900\r\n")
                    append("MAN: \"ssdp:discover\"\r\n")
                    append("MX: 2\r\n")
                    append("ST: $st\r\n")
                    append("\r\n")
                }
                val bytes = query.toByteArray(Charsets.UTF_8)
                val packet = DatagramPacket(bytes, bytes.size, multicastAddr, 1900)
                runCatching { socket.send(packet) }
            }

            val startMs = System.currentTimeMillis()
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() - startMs < 3200L && currentCoroutineContext().isActive) {
                try {
                    val recv = DatagramPacket(buf, buf.size)
                    socket.receive(recv)
                    val respText = String(recv.data, 0, recv.length, Charsets.UTF_8)
                    val location = respText.lineSequence()
                        .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                        ?.substringAfter(":")
                        ?.trim()
                    if (!location.isNullOrBlank() && locationSet.add(location)) {
                        launch(Dispatchers.IO) {
                            parseUpnpDeviceDescription(client, location)?.let(onDeviceFound)
                        }
                    }
                } catch (_: Exception) {
                    // 单次读取超时继续监听直到总窗口结束
                }
            }
        }
    }

    /**
     * 解析 UPnP 设备描述 XML，提取 friendlyName 与 AVTransport controlURL
     */
    private fun parseUpnpDeviceDescription(
        client: okhttp3.OkHttpClient,
        locationUrl: String
    ): LanShareDevice? {
        return try {
            val req = Request.Builder().url(locationUrl).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val xml = resp.body?.string() ?: return null
                val uri = URI(locationUrl)
                val host = uri.host ?: return null
                val port = if (uri.port > 0) uri.port else 80
                val baseOrigin = "${uri.scheme ?: "http"}://$host:$port"

                var friendlyName = ""
                var modelName = ""
                var currentServiceType = ""
                var avTransportControlUrl = ""
                var renderingControlUrl = ""

                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(StringReader(xml))
                var event = parser.eventType
                var currentTag = ""

                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            currentTag = parser.name ?: ""
                        }
                        XmlPullParser.TEXT -> {
                            val text = parser.text?.trim().orEmpty()
                            if (text.isNotEmpty()) {
                                when (currentTag.lowercase()) {
                                    "friendlyname" -> if (friendlyName.isBlank()) friendlyName = text
                                    "modelname" -> if (modelName.isBlank()) modelName = text
                                    "servicetype" -> currentServiceType = text
                                    "controlurl" -> {
                                        if (currentServiceType.contains("AVTransport", ignoreCase = true) && avTransportControlUrl.isBlank()) {
                                            avTransportControlUrl = resolveRelativeControlUrl(baseOrigin, locationUrl, text)
                                        } else if (currentServiceType.contains("RenderingControl", ignoreCase = true) && renderingControlUrl.isBlank()) {
                                            renderingControlUrl = resolveRelativeControlUrl(baseOrigin, locationUrl, text)
                                        }
                                    }
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (parser.name.equals("service", ignoreCase = true)) {
                                currentServiceType = ""
                            }
                            currentTag = ""
                        }
                    }
                    event = parser.next()
                }

                if (avTransportControlUrl.isBlank()) return null
                LanShareDevice(
                    id = "dlna_${host}_$port",
                    name = friendlyName.ifBlank { modelName.ifBlank { "DLNA 音响 ($host)" } },
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
        if (trimmed.startsWith("/")) return "$baseOrigin$trimmed"
        val parentPath = locationUrl.substringBeforeLast('/', baseOrigin)
        return "$parentPath/$trimmed"
    }

    /**
     * 将当前播放曲目投射至选定的局域网 DLNA / AirPlay 设备
     */
    suspend fun castSongToDevice(
        context: Context,
        device: LanShareDevice,
        song: UnifiedSong,
        positionMs: Long = 0L
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val playableMediaUrl = resolveLanAccessibleStreamUrl(context, song)
                ?: return@withContext Result.failure(Exception("无法解析当前歌曲的局域网可访问音频流"))

            val coverUrl = resolveLanAccessibleCoverUrl(context, song.coverUrl)

            when (device.protocol) {
                ShareProtocolType.DLNA_UPNP -> {
                    val ok = sendDlnaSetAvTransportAndPlay(
                        context = context,
                        device = device,
                        mediaUrl = playableMediaUrl,
                        song = song,
                        coverUrl = coverUrl
                    )
                    if (ok) {
                        _activeCastDevice.value = device
                        Result.success("已通过 DLNA 投射《${song.title}》至 ${device.name}")
                    } else {
                        Result.failure(Exception("DLNA 设备 ${device.name} 未响应播放指令"))
                    }
                }
                ShareProtocolType.AIRPLAY_RAOP -> {
                    // 1. 优先尝试同 IP 是否同时暴露了标准 DLNA AVTransport 控制端点
                    val siblingDlna = _lanDevices.value.firstOrNull {
                        it.protocol == ShareProtocolType.DLNA_UPNP && it.host == device.host
                    }
                    if (siblingDlna != null) {
                        val ok = sendDlnaSetAvTransportAndPlay(
                            context = context,
                            device = siblingDlna,
                            mediaUrl = playableMediaUrl,
                            song = song,
                            coverUrl = coverUrl
                        )
                        if (ok) {
                            _activeCastDevice.value = device
                            return@withContext Result.success("已连接 ${device.name} 并推送无损音频流")
                        }
                    }

                    // 2. 尝试向开放型 AirPlay 接收端发送 HTTP /play 指令
                    val startSec = if (song.durationMs > 0) {
                        (positionMs.toDouble() / song.durationMs.toDouble()).coerceIn(0.0, 0.99)
                    } else 0.0
                    val airplayOk = sendAirPlayHttpPlay(context, device, playableMediaUrl, startSec)
                    if (airplayOk) {
                        _activeCastDevice.value = device
                        Result.success("已通过 AirPlay 推送《${song.title}》至 ${device.name}")
                    } else {
                        Result.failure(
                            Exception("${device.name} 需要 Apple FairPlay 硬件加密认证，建议使用蓝牙/系统音频输出或支持 DLNA 的音响")
                        )
                    }
                }
                ShareProtocolType.LOCAL_ROUTE -> {
                    stopActiveCast(context)
                    Result.success("已切回本机音频输出通道")
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 停止当前局域网投射并恢复本机播放
     */
    suspend fun stopActiveCast(context: Context) = withContext(Dispatchers.IO) {
        val current = _activeCastDevice.value ?: return@withContext
        _activeCastDevice.value = null
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
     * 将任意本机文件或远程流转换为局域网音响可直接拉取的 HTTP URL
     */
    private suspend fun resolveLanAccessibleStreamUrl(context: Context, song: UnifiedSong): String? {
        val localIp = getWifiLanIpAddress(context)

        // 1. 若有真实本地物理文件，挂载至内置局域网 HTTP Range 文件服务器
        val localFile = listOfNotNull(song.localFilePath, song.streamUrl)
            .firstOrNull { it.startsWith("/") && File(it).let { f -> f.exists() && f.length() > 0 } }
            ?.let { File(it) }

        if (localFile != null && !localIp.isNullOrBlank()) {
            val port = ensureLocalHttpServerStarted()
            if (port > 0) {
                val ext = localFile.extension.ifBlank { song.format.ifBlank { "mp3" } }
                val token = "song_${song.id.hashCode().toUInt()}"
                sharedFileRegistry[token] = localFile
                return "http://$localIp:$port/media/$token.$ext"
            }
        }

        // 2. 若为在线或服务端歌曲但 streamUrl 尚未解析，动态调用 LemonMusicProtocol 解析
        var remoteUrl = song.streamUrl
        val db = ZdsDatabase.getInstance(context)
        val active = db.serverDao().getActiveServer()
            ?: db.serverDao().getAllServers().firstOrNull { it.type == ServerType.LEMON_MUSIC }

        if ((remoteUrl.isBlank() || remoteUrl.startsWith("lemon_online://")) && active != null) {
            val client = NetworkClientFactory.createOkHttpClient(context)
            val protocol = LemonMusicProtocol(client, active.serverUrl, active.username, active.tokenOrApiKey)
            protocol.ensureAuthenticated()

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
        }

        if (remoteUrl.startsWith("http://") || remoteUrl.startsWith("https://")) {
            // 若服务端配置的是 127.0.0.1 / localhost，替换为本机局域网 IP
            if (!localIp.isNullOrBlank()) {
                remoteUrl = remoteUrl
                    .replace("://127.0.0.1", "://$localIp")
                    .replace("://localhost", "://$localIp")
            }
            return remoteUrl
        }
        return null
    }

    private fun resolveLanAccessibleCoverUrl(context: Context, rawCoverUrl: String): String {
        if (rawCoverUrl.isBlank()) return ""
        val localIp = getWifiLanIpAddress(context)
        if (!localIp.isNullOrBlank()) {
            return rawCoverUrl
                .replace("://127.0.0.1", "://$localIp")
                .replace("://localhost", "://$localIp")
        }
        return rawCoverUrl
    }

    /**
     * 发送标准 DLNA / UPnP SOAP SetAVTransportURI + Play 请求
     */
    private fun sendDlnaSetAvTransportAndPlay(
        context: Context,
        device: LanShareDevice,
        mediaUrl: String,
        song: UnifiedSong,
        coverUrl: String
    ): Boolean {
        val controlUrl = device.avTransportControlUrl
        if (controlUrl.isBlank()) return false
        val client = NetworkClientFactory.createOkHttpClient(context)

        // 先尝试 Stop 重置音响状态机
        runCatching { sendDlnaStop(context, device) }

        val mimeType = when (song.format.lowercase()) {
            "flac" -> "audio/flac"
            "wav", "ape" -> "audio/wav"
            "m4a", "aac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            else -> "audio/mpeg"
        }

        val totalSec = (song.durationMs / 1000L).coerceAtLeast(0L)
        val durationStr = String.format("%02d:%02d:%02d", totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60)

        val didlLite = buildString {
            append("""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""")
            append("""<item id="0" parentID="-1" restricted="1">""")
            append("""<dc:title>${escapeXml(song.title)}</dc:title>""")
            append("""<upnp:artist>${escapeXml(song.artist)}</upnp:artist>""")
            append("""<upnp:album>${escapeXml(song.album)}</upnp:album>""")
            if (coverUrl.isNotBlank()) {
                append("""<upnp:albumArtURI>${escapeXml(coverUrl)}</upnp:albumArtURI>""")
            }
            append("""<upnp:class>object.item.audioItem.musicTrack</upnp:class>""")
            append("""<res duration="$durationStr" protocolInfo="http-get:*:$mimeType:*">${escapeXml(mediaUrl)}</res>""")
            append("""</item></DIDL-Lite>""")
        }

        val setUriSoap = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <InstanceID>0</InstanceID>
                  <CurrentURI>${escapeXml(mediaUrl)}</CurrentURI>
                  <CurrentURIMetaData>${escapeXml(didlLite)}</CurrentURIMetaData>
                </u:SetAVTransportURI>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val setUriReq = Request.Builder()
            .url(controlUrl)
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .header("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"")
            .post(setUriSoap.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()

        val setOk = client.newCall(setUriReq).execute().use { it.isSuccessful }
        if (!setOk) return false

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

        return client.newCall(playReq).execute().use { it.isSuccessful }
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
     * 启动内置局域网 HTTP 206 Range 音频流服务器（使局域网 DLNA/AirPlay 音响可直接读取手机本地无损音乐）
     */
    @Synchronized
    private fun ensureLocalHttpServerStarted(): Int {
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
                        handleHttpFileClient(clientSocket)
                    }
                }
            }
            httpServerPort
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start local LAN HTTP media server", e)
            0
        }
    }

    private fun handleHttpFileClient(socket: Socket) {
        socket.use { s ->
            runCatching {
                s.soTimeout = 15000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1]

                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substringAfter(":").trim()
                    }
                }

                val token = path.substringAfter("/media/").substringBefore(".")
                val file = sharedFileRegistry[token]
                val out: OutputStream = s.getOutputStream()

                if (file == null || !file.exists()) {
                    val notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    out.write(notFound.toByteArray(Charsets.UTF_8))
                    out.flush()
                    return
                }

                val totalLen = file.length()
                val ext = file.extension.lowercase()
                val mime = when (ext) {
                    "flac" -> "audio/flac"
                    "wav" -> "audio/wav"
                    "m4a", "aac" -> "audio/mp4"
                    "ogg", "opus" -> "audio/ogg"
                    else -> "audio/mpeg"
                }

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
                    append("transferMode.dlna.org: Streaming\r\n")
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
                        val buffer = ByteArray(16384)
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
        }
    }

    /**
     * 获取本机局域网 IPv4 地址
     */
    fun getWifiLanIpAddress(context: Context): String? {
        runCatching {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            }
        }
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
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
