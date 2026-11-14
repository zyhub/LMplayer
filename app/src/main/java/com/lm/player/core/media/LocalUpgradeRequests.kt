package com.lm.player.core.media

import java.util.concurrent.ConcurrentHashMap

/**
 * 本地「升级音质」请求：下载菜单里对已下载歌曲选了更高音质时登记旧文件路径，
 * [DownloadEngine.startDownload] 取走后：跳过「本地已有同档文件」的拦截照常下载，
 * 新文件落盘成功后删除旧文件（新旧文件名不同时，例如 .mp3 → .flac），完成替换。
 */
object LocalUpgradeRequests {
    private val pending = ConcurrentHashMap<String, String>()

    fun mark(songId: String, oldLocalPath: String?) {
        if (songId.isBlank() || oldLocalPath.isNullOrBlank()) return
        pending[songId] = oldLocalPath
    }

    fun take(songId: String): String? = pending.remove(songId)
}
