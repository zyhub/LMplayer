package com.lm.player.core.source

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 记录在线曲目最近一次实际由谁取到播放链接（播放页「音频参数」里的「解析来源」）：
 * - 本地音源加速命中：具体脚本名（例如某个落雪音源脚本的名字）+ 平台；
 * - 服务器取链：服务端不返回具体用了哪个音源，只能记为「柠檬服务器」+ 平台。
 * 仅保存在内存里（最近 64 首），不落盘。
 */
object PlaybackSourceTracker {
    data class Info(
        /** 音源名称：本地脚本名，或「柠檬服务器」 */
        val sourceName: String,
        /** 平台标识（kw / tx / wy / kg / mg …） */
        val platform: String,
        val viaLocal: Boolean
    )

    private const val MAX_ENTRIES = 64
    private val _state = MutableStateFlow<Map<String, Info>>(emptyMap())
    val state: StateFlow<Map<String, Info>> = _state.asStateFlow()

    fun record(songId: String, info: Info) {
        if (songId.isBlank()) return
        _state.update { old ->
            val next = LinkedHashMap<String, Info>(old)
            next.remove(songId)
            next[songId] = info
            while (next.size > MAX_ENTRIES) next.remove(next.keys.first())
            next
        }
    }

    fun get(songId: String): Info? = _state.value[songId]

    fun platformDisplayName(platform: String): String = when (platform.lowercase()) {
        "tx" -> "QQ音乐"
        "kw" -> "酷我"
        "wy" -> "网易云"
        "kg" -> "酷狗"
        "mg" -> "咪咕"
        "bd" -> "百度"
        "" -> ""
        else -> platform.uppercase()
    }
}
