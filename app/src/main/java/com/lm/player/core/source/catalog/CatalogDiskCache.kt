package com.lm.player.core.source.catalog

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * 目录接口响应的磁盘缓存（服务器 / 本地两条路径的成功响应都会写入）。
 *
 * - 本地优先 / 只走本地时，新鲜期内直接返回缓存（发现页秒开）；
 * - 服务器与本地都失败时，返回不超过「可显示旧数据上限」的旧数据（服务器离线/已删除时仍能看到上次内容）。
 */
object CatalogDiskCache {
    private const val TAG = "CatalogDiskCache"
    private const val DIR = "lm_catalog_cache"
    private const val MAX_FILES = 600
    private const val MAX_BYTES = 2 * 1024 * 1024

    @Volatile
    private var dir: File? = null

    fun init(context: Context) {
        if (dir != null) return
        dir = File(context.applicationContext.cacheDir, DIR).apply { mkdirs() }
    }

    data class Entry(val body: String, val savedAt: Long)

    private fun fileFor(key: String): File? {
        val d = dir ?: return null
        val name = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return File(d, "$name.json")
    }

    fun get(key: String, maxAgeMs: Long): Entry? {
        if (maxAgeMs <= 0L) return null
        val f = fileFor(key) ?: return null
        if (!f.exists()) return null
        val age = System.currentTimeMillis() - f.lastModified()
        if (age > maxAgeMs) return null
        return runCatching { Entry(f.readText(Charsets.UTF_8), f.lastModified()) }.getOrNull()
    }

    fun put(key: String, body: String) {
        if (body.length > MAX_BYTES) return
        val f = fileFor(key) ?: return
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(body, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
            trimIfNeeded()
        }.onFailure { Log.w(TAG, "cache write failed", it) }
    }

    @Volatile
    private var lastTrimAt = 0L

    private fun trimIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastTrimAt < 60_000L) return
        lastTrimAt = now
        val files = dir?.listFiles()?.filter { it.name.endsWith(".json") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    fun clear() {
        dir?.listFiles()?.forEach { it.delete() }
    }
}
