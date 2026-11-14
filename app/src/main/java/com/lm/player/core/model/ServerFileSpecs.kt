package com.lm.player.core.model

import org.json.JSONObject

/**
 * 服务器曲库「原文件」的规格。
 *
 * 一首歌在数据库里只有一条记录：本机下载 / 本地导入后，记录里的 format / bitRate 会被换成**本地文件**的规格
 * （本地播放、本地升级判断都依赖这一点）。于是服务器上是无损、本地存了一份 128K 时，
 * 再用 format / bitRate 去说「服务器音质」就会被本地文件污染成 128K。
 *
 * 服务器原文件的规格在同步曲库时就随服务器元数据存进了 rawMetaJson（filePath / format / size / duration /
 * bitDepth / sampleRate），之后下载、本地导入都不会改它 —— 「服务器音质」一律从这里取。
 */
data class ServerFileSpecs(
    /** 小写扩展名，如 flac / mp3 */
    val format: String,
    /** kbps，按「文件大小 ÷ 时长」算（与同步时的算法一致） */
    val bitRate: Int,
    val sizeBytes: Long,
    val bitDepth: Int?,
    val sampleRate: Int?
) {
    val isLossless: Boolean
        get() = format in LOSSLESS_FORMATS || bitRate >= 800

    val isHiRes: Boolean
        get() = isLossless && ((bitDepth ?: 0) >= 24 || (sampleRate ?: 0) >= 88_200 || bitRate >= 1200)

    companion object {
        val LOSSLESS_FORMATS = setOf("flac", "wav", "alac", "ape", "dsd", "dsf", "aiff")
    }
}

/**
 * 从服务器元数据读出服务器原文件规格；不是服务器曲库元数据（没有 filePath）时返回 null，调用方再退回 format / bitRate。
 */
fun UnifiedSong.serverFileSpecs(): ServerFileSpecs? {
    val raw = rawMetaJson?.trim()?.takeIf { it.startsWith("{") } ?: return null
    val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    val filePath = o.optString("filePath").trim()
    if (filePath.isBlank()) return null
    val format = o.optString("format").trim().lowercase()
        .ifBlank { filePath.substringAfterLast('.', "").lowercase() }
    if (format.isBlank()) return null
    val size = o.optLong("size", 0L)
    // 时长：服务器一般给秒；大于 10 小时视为毫秒；没有时用记录里的时长（下载 / 本地导入不会改它）
    val rawDuration = o.optDouble("duration", 0.0)
    val durationSec = when {
        rawDuration > 36_000.0 -> rawDuration / 1000.0
        rawDuration > 0.0 -> rawDuration
        else -> durationMs / 1000.0
    }
    val bitRate = if (durationSec > 0 && size > 0) {
        ((size * 8) / durationSec / 1000).toInt().coerceIn(64, 4608)
    } else {
        if (format in ServerFileSpecs.LOSSLESS_FORMATS) 960 else 320
    }
    val bitDepth = o.optInt("bitDepth", 0).takeIf { it > 0 } ?: o.optInt("bitsPerSample", 0).takeIf { it > 0 }
    val sampleRate = o.optInt("sampleRate", 0).takeIf { it > 0 }
    return ServerFileSpecs(format, bitRate, size, bitDepth, sampleRate)
}

/** 非扩展写法（便于在其它包里不加 import 直接调用） */
fun serverFileSpecsOf(song: UnifiedSong): ServerFileSpecs? = song.serverFileSpecs()
