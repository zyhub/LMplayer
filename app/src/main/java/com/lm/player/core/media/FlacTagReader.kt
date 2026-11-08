package com.lm.player.core.media

import java.io.File
import java.io.RandomAccessFile

/**
 * FLAC 文件头直读 (扫描提速，第二十节，移植自 TV 端)。
 *
 * 只顺序读取文件开头的元数据块 (STREAMINFO / VORBIS_COMMENT / PICTURE 的头部)，**不读取任何音频数据**，
 * 也不启动系统的通用解码器 (MediaMetadataRetriever)。一首无损歌曲通常只需读几 KB。
 * 封面只记录它在文件中的位置与长度，真正的图片字节留到「补封面」阶段再按位置读取。
 *
 * 任何不确定的情况都返回 null，由调用方退回 MediaMetadataRetriever，保证读出的信息与原来一致：
 * - 不是 FLAC、文件头损坏；
 * - 标题 / 歌手 / 专辑字段在同一文件中出现多次 (不同解码器对多值字段的取舍不同)；
 * - STREAMINFO 缺失或总采样数未知 (无法算出时长)。
 */
object FlacTagReader {

    data class FlacInfo(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long,
        /** 封面图片字节在文件中的起始位置，-1 表示没有内嵌封面 */
        val pictureOffset: Long,
        val pictureLength: Int
    )

    private const val MAX_BLOCK = 16 * 1024 * 1024
    private const val MAX_COMMENT_BLOCK = 4 * 1024 * 1024

    fun read(file: File): FlacInfo? {
        return try {
            RandomAccessFile(file, "r").use { raf -> parse(raf) }
        } catch (_: Exception) {
            null
        }
    }

    private fun parse(raf: RandomAccessFile): FlacInfo? {
        val fileLength = raf.length()
        var start = 0L
        val head = ByteArray(10)
        if (raf.read(head) < 4) return null
        // 兼容前置 ID3v2 标签的 FLAC
        if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            val id3Size = ((head[6].toInt() and 0x7F) shl 21) or
                ((head[7].toInt() and 0x7F) shl 14) or
                ((head[8].toInt() and 0x7F) shl 7) or
                (head[9].toInt() and 0x7F)
            start = 10L + id3Size
        }
        raf.seek(start)
        val magic = ByteArray(4)
        raf.readFully(magic)
        if (magic[0] != 'f'.code.toByte() || magic[1] != 'L'.code.toByte() ||
            magic[2] != 'a'.code.toByte() || magic[3] != 'C'.code.toByte()
        ) return null

        var sampleRate = 0L
        var totalSamples = 0L
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var sawComments = false
        var frontOffset = -1L
        var frontLength = 0
        var firstOffset = -1L
        var firstLength = 0

        var isLast = false
        while (!isLast) {
            val blockHeader = raf.read()
            if (blockHeader == -1) return null
            isLast = (blockHeader and 0x80) != 0
            val type = blockHeader and 0x7F
            val b1 = raf.read(); val b2 = raf.read(); val b3 = raf.read()
            if (b1 < 0 || b2 < 0 || b3 < 0) return null
            val length = (b1 shl 16) or (b2 shl 8) or b3
            if (length < 0 || length > MAX_BLOCK) return null
            val dataStart = raf.filePointer
            if (dataStart + length > fileLength) return null

            when (type) {
                0 -> { // STREAMINFO
                    if (length < 18) return null
                    val si = ByteArray(18)
                    raf.readFully(si)
                    // 字节 10..17：采样率 20 位 | 声道 3 位 | 位深 5 位 | 总采样数 36 位
                    sampleRate = ((si[10].toLong() and 0xFF) shl 12) or
                        ((si[11].toLong() and 0xFF) shl 4) or
                        ((si[12].toLong() and 0xF0) shr 4)
                    totalSamples = ((si[13].toLong() and 0x0F) shl 32) or
                        ((si[14].toLong() and 0xFF) shl 24) or
                        ((si[15].toLong() and 0xFF) shl 16) or
                        ((si[16].toLong() and 0xFF) shl 8) or
                        (si[17].toLong() and 0xFF)
                }
                4 -> { // VORBIS_COMMENT
                    if (sawComments || length > MAX_COMMENT_BLOCK) return null
                    sawComments = true
                    val bytes = ByteArray(length)
                    raf.readFully(bytes)
                    val fields = parseComments(bytes) ?: return null
                    title = fields["TITLE"]
                    artist = fields["ARTIST"]
                    album = fields["ALBUM"]
                }
                6 -> { // PICTURE：只读到图片数据之前的描述部分
                    val pos = readPictureDataPosition(raf, dataStart, length)
                    if (pos != null) {
                        val (picType, offset, picLen) = pos
                        if (firstOffset < 0) { firstOffset = offset; firstLength = picLen }
                        if (picType == 3 && frontOffset < 0) { frontOffset = offset; frontLength = picLen }
                    }
                }
            }
            raf.seek(dataStart + length)
        }

        if (sampleRate <= 0L || totalSamples <= 0L) return null
        val durationMs = totalSamples * 1000L / sampleRate
        val (picOffset, picLength) = if (frontOffset >= 0) frontOffset to frontLength else firstOffset to firstLength
        return FlacInfo(
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            pictureOffset = picOffset,
            pictureLength = if (picOffset >= 0) picLength else 0
        )
    }

    /** 解析 Vorbis 注释；目标字段出现多次时返回 null (交给系统解码器处理，保持原有结果) */
    private fun parseComments(b: ByteArray): Map<String, String>? {
        var p = 0
        fun le32(): Long {
            if (p + 4 > b.size) throw IndexOutOfBoundsException()
            val v = (b[p].toLong() and 0xFF) or ((b[p + 1].toLong() and 0xFF) shl 8) or
                ((b[p + 2].toLong() and 0xFF) shl 16) or ((b[p + 3].toLong() and 0xFF) shl 24)
            p += 4
            return v
        }
        return try {
            val vendorLen = le32()
            if (vendorLen < 0 || p + vendorLen > b.size) return null
            p += vendorLen.toInt()
            val count = le32()
            if (count < 0 || count > 100_000) return null
            val out = HashMap<String, String>(4)
            for (i in 0 until count) {
                val len = le32()
                if (len < 0 || p + len > b.size) return null
                val entry = String(b, p, len.toInt(), Charsets.UTF_8)
                p += len.toInt()
                val eq = entry.indexOf('=')
                if (eq <= 0) continue
                val key = entry.substring(0, eq).uppercase()
                if (key == "TITLE" || key == "ARTIST" || key == "ALBUM") {
                    if (out.containsKey(key)) return null
                    out[key] = entry.substring(eq + 1)
                }
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    /** 返回 (图片类型, 图片字节起始位置, 图片字节长度) */
    private fun readPictureDataPosition(raf: RandomAccessFile, blockStart: Long, blockLength: Int): Triple<Int, Long, Int>? {
        return try {
            val end = blockStart + blockLength
            val picType = raf.readInt()
            val mimeLen = raf.readInt()
            if (mimeLen < 0 || raf.filePointer + mimeLen > end) return null
            raf.seek(raf.filePointer + mimeLen)
            val descLen = raf.readInt()
            if (descLen < 0 || raf.filePointer + descLen > end) return null
            raf.seek(raf.filePointer + descLen + 16) // 宽、高、色深、调色板数
            val dataLen = raf.readInt()
            val offset = raf.filePointer
            if (dataLen <= 0 || offset + dataLen > end) return null
            Triple(picType, offset, dataLen)
        } catch (_: Exception) {
            null
        }
    }

    /** 按位置读取封面字节 */
    fun readPicture(file: File, offset: Long, length: Int): ByteArray? {
        if (offset < 0 || length <= 0 || length > MAX_BLOCK) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val bytes = ByteArray(length)
                raf.readFully(bytes)
                bytes
            }
        } catch (_: Exception) {
            null
        }
    }
}
