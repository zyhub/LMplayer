package com.lm.player.core.source

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.lm.player.core.model.SourceScriptBundle
import com.lm.player.core.model.SourceScriptEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 音源脚本 bundle 的本地加密缓存。
 *
 * 目的：App 冷启动时服务器不可达、但手机公网正常时，仍能用上次成功加载的脚本
 * 从平台 CDN 取链。脚本可能包含平台 Cookie/Token，因此：
 * 1. 只写入 app 私有且不参与备份的 [Context.getNoBackupFilesDir]；
 * 2. 使用 Android Keystore 的 AES/GCM 密钥加密，密钥不可导出；
 * 3. 解密失败/密钥失效时直接删除缓存，绝不回退明文。
 */
object SourceScriptStore {
    private const val TAG = "SourceScriptStore"
    private const val KEY_ALIAS = "lm_source_scripts_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    /**
     * 三份脚本（2026-11-13 用户规则「从服务器拉取 / 更新的音源下次启动才生效」）：
     * - 当前版：本次启动装载用的，只在冷启动转正时变化（沿用原来的缓存文件名，旧版本升级后直接当作当前版）；
     * - 待用版：后台拉到的新版本，下次冷启动时转正；
     * - 上一版：转正时留下的旧当前版，新版某个脚本装载失败时退回它。
     */
    enum class Slot(val fileName: String) {
        CURRENT("source_scripts.v1.enc"),
        PENDING("source_scripts.pending.enc"),
        PREVIOUS("source_scripts.prev.enc")
    }

    data class Cached(
        val serverId: String,
        val savedAt: Long,
        val bundle: SourceScriptBundle
    )

    /** 一份脚本的概要（不含脚本内容，明文存在配置里，详情窗口 / 版本核对用） */
    data class SlotSummary(
        val serverId: String,
        val savedAt: Long,
        /** (id, 名称, 版本) */
        val sources: List<Triple<String, String, String>>
    )

    suspend fun save(context: Context, serverId: String, bundle: SourceScriptBundle, slot: Slot = Slot.CURRENT): Boolean =
        withContext(Dispatchers.IO) {
            if (bundle.sources.isEmpty()) return@withContext false
            runCatching {
                val savedAt = System.currentTimeMillis()
                val json = encode(serverId, bundle, savedAt).toString()
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, secretKey())
                val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
                val wrapper = JSONObject()
                    .put("v", 1)
                    .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                    .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                val target = file(context, slot)
                val tmp = File(target.parentFile, "${target.name}.tmp")
                tmp.writeText(wrapper.toString(), Charsets.UTF_8)
                synchronized(this@SourceScriptStore) {
                    if (target.exists() && !target.delete()) {
                        Log.w(TAG, "old cache delete failed")
                    }
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    writeSummary(context, slot, SlotSummary(serverId, savedAt, bundle.sources.map { Triple(it.id, it.name, it.version) }))
                }
                Log.i(TAG, "saved ${bundle.sources.size} script(s) for $serverId into $slot")
                true
            }.getOrElse {
                Log.w(TAG, "save failed", it)
                false
            }
        }

    suspend fun load(context: Context, slot: Slot = Slot.CURRENT): Cached? = withContext(Dispatchers.IO) {
        runCatching {
            val source = file(context, slot)
            if (!source.exists() || source.length() == 0L) return@withContext null
            val wrapper = JSONObject(source.readText(Charsets.UTF_8))
            if (wrapper.optInt("v", 0) != 1) return@withContext null
            val iv = Base64.decode(wrapper.optString("iv"), Base64.NO_WRAP)
            val data = Base64.decode(wrapper.optString("data"), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val plain = cipher.doFinal(data).toString(Charsets.UTF_8)
            decode(JSONObject(plain))?.also { cached ->
                // 旧版本写的当前版没有概要：读到一次就补上
                if (readSummary(context, slot) == null) {
                    writeSummary(context, slot, SlotSummary(cached.serverId, cached.savedAt, cached.bundle.sources.map { Triple(it.id, it.name, it.version) }))
                }
            }
        }.getOrElse {
            Log.w(TAG, "load failed; cache ignored ($slot)", it)
            runCatching { file(context, slot).delete() }
            removeSummary(context, slot)
            null
        }
    }

    /** 删除全部三份脚本 */
    fun clear(context: Context) {
        Slot.values().forEach { slot ->
            runCatching { file(context, slot).delete() }
            removeSummary(context, slot)
        }
    }

    /** 这一份是否存在（只看文件，不解密） */
    fun hasSlot(context: Context, slot: Slot): Boolean =
        runCatching { file(context, slot).let { it.isFile && it.length() > 0L } }.getOrDefault(false)

    /**
     * 冷启动、装载引擎之前调用：有待用版就转正（当前版 → 上一版，待用版 → 当前版）。
     * 只是改文件名，不解密、不耗时。@return 是否转正了
     */
    @Synchronized
    fun promotePendingIfAny(context: Context): Boolean {
        val pending = file(context, Slot.PENDING)
        if (!pending.isFile || pending.length() == 0L) return false
        return runCatching {
            val current = file(context, Slot.CURRENT)
            val previous = file(context, Slot.PREVIOUS)
            val currentSummary = readSummary(context, Slot.CURRENT)
            val pendingSummary = readSummary(context, Slot.PENDING)
            if (previous.exists()) previous.delete()
            removeSummary(context, Slot.PREVIOUS)
            if (current.isFile) {
                if (!current.renameTo(previous)) {
                    current.copyTo(previous, overwrite = true)
                    current.delete()
                }
                if (currentSummary != null) writeSummary(context, Slot.PREVIOUS, currentSummary)
            }
            if (!pending.renameTo(current)) {
                pending.copyTo(current, overwrite = true)
                pending.delete()
            }
            removeSummary(context, Slot.CURRENT)
            removeSummary(context, Slot.PENDING)
            if (pendingSummary != null) writeSummary(context, Slot.CURRENT, pendingSummary)
            Log.i(TAG, "pending scripts promoted to current")
            true
        }.getOrElse {
            Log.w(TAG, "promote pending failed", it)
            false
        }
    }

    /** 删除待用版（换了服务器等情况） */
    fun clearPending(context: Context) {
        runCatching { file(context, Slot.PENDING).delete() }
        removeSummary(context, Slot.PENDING)
    }

    fun summary(context: Context, slot: Slot): SlotSummary? = if (hasSlot(context, slot)) readSummary(context, slot) else null

    private fun summaryKey(slot: Slot) = "slot_summary_${slot.name}"

    private fun readSummary(context: Context, slot: Slot): SlotSummary? = runCatching {
        val raw = prefs(context).getString(summaryKey(slot), null) ?: return null
        val o = JSONObject(raw)
        val arr = o.optJSONArray("s") ?: JSONArray()
        SlotSummary(
            serverId = o.optString("server"),
            savedAt = o.optLong("at"),
            sources = (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                Triple(e.optString("id"), e.optString("name"), e.optString("v"))
            }
        )
    }.getOrNull()

    private fun writeSummary(context: Context, slot: Slot, s: SlotSummary) {
        val arr = JSONArray()
        s.sources.forEach { (id, name, v) -> arr.put(JSONObject().put("id", id).put("name", name).put("v", v)) }
        val raw = JSONObject().put("server", s.serverId).put("at", s.savedAt).put("s", arr).toString()
        prefs(context).edit().putString(summaryKey(slot), raw).commit()
    }

    private fun removeSummary(context: Context, slot: Slot) {
        prefs(context).edit().remove(summaryKey(slot)).commit()
    }

    fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // ---------------- 服务器删除后的「孤儿脚本」管理 ----------------
    // 规则（用户 2026-11-05 确认）：删除服务器后不立即删除本地音源脚本，重启后也继续可用；
    // 自删除起 30 分钟内没有检测到任何登录连接，则在程序关闭时清理。

    private const val PREFS = "source_accel_prefs"
    private const val KEY_ORPHAN_SERVER = "orphan_server_id"
    private const val KEY_ORPHAN_SINCE = "orphan_since"
    private const val KEY_LAST_LOGIN_AT = "last_login_at"
    const val ORPHAN_GRACE_MS = 30 * 60 * 1000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markOrphan(context: Context, serverId: String, at: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putString(KEY_ORPHAN_SERVER, serverId)
            .putLong(KEY_ORPHAN_SINCE, at)
            .apply()
    }

    /** (服务器 id, 变成孤儿的时间)；没有孤儿脚本时返回 null。 */
    fun orphanInfo(context: Context): Pair<String, Long>? {
        val p = prefs(context)
        val id = p.getString(KEY_ORPHAN_SERVER, null)?.takeIf { it.isNotBlank() } ?: return null
        return id to p.getLong(KEY_ORPHAN_SINCE, 0L)
    }

    fun clearOrphan(context: Context) {
        prefs(context).edit().remove(KEY_ORPHAN_SERVER).remove(KEY_ORPHAN_SINCE).apply()
    }

    fun markLoginConnected(context: Context, at: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_LAST_LOGIN_AT, at).putInt(KEY_NO_LOGIN_LAUNCHES, 0).apply()
    }

    // ---------------- 连续启动未登录计数（2026-11-06 旧规则；2026-11-13 起改为「12 小时内未登录才清除」，此计数已不再使用） ----------------
    // 每次冷启动 +1，任何一次登录成功清零；连续两次启动都没有登录服务器 → 彻底清除本地音源脚本。

    private const val KEY_NO_LOGIN_LAUNCHES = "no_login_launches"

    /** 记录一次冷启动，返回本次之前已连续多少次启动未登录。 */
    fun recordLaunch(context: Context): Int {
        val p = prefs(context)
        val prev = p.getInt(KEY_NO_LOGIN_LAUNCHES, 0)
        // commit：进程可能很快被杀，计数必须落盘
        p.edit().putInt(KEY_NO_LOGIN_LAUNCHES, prev + 1).commit()
        return prev
    }

    /** 彻底清除本地音源脚本缓存（当前版 / 待用版 / 上一版三份）与相关标记。 */
    fun purgeAll(context: Context) {
        clear(context)
        prefs(context).edit().remove(KEY_ORPHAN_SERVER).remove(KEY_ORPHAN_SINCE).putInt(KEY_NO_LOGIN_LAUNCHES, 0).commit()
        Log.i(TAG, "local source scripts purged (no login within 12 hours)")
    }

    fun lastLoginAt(context: Context): Long = prefs(context).getLong(KEY_LAST_LOGIN_AT, 0L)

    /**
     * 保留期的起点（2026-11-13）：上次登录 / 服务器在线的时间与脚本拉取到本地的时间取较晚者。
     * 旧版本没记过登录时间时，按加密缓存文件的保存时间算。
     */
    fun lastConnectedAt(context: Context): Long {
        val login = lastLoginAt(context)
        val saved = runCatching {
            maxOf(
                file(context, Slot.CURRENT).takeIf { it.isFile }?.lastModified() ?: 0L,
                file(context, Slot.PENDING).takeIf { it.isFile }?.lastModified() ?: 0L
            )
        }.getOrDefault(0L)
        return maxOf(login, saved)
    }

    /**
     * 程序关闭时调用：孤儿脚本超过 30 分钟且期间没有任何登录连接 → 删除加密缓存。
     * @return 是否执行了清理
     */
    fun cleanupOrphanIfExpired(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val (_, since) = orphanInfo(context) ?: return false
        if (since <= 0L || now - since < ORPHAN_GRACE_MS) return false
        if (lastLoginAt(context) >= since) {
            // 期间已登录过：脚本已被新服务器的 bundle 覆盖或仍有意义，只撤销孤儿标记
            clearOrphan(context)
            return false
        }
        clear(context)
        clearOrphan(context)
        Log.i(TAG, "orphan source scripts cleaned (no login within 30min)")
        return true
    }

    private fun file(context: Context, slot: Slot = Slot.CURRENT): File = File(context.noBackupFilesDir, slot.fileName)

    /**
     * 是否有可装载的音源脚本（只看文件，不解密、不加载；TV 端按需启动引擎前用它决定界面形态）。
     * 待用版也算：它会在下次冷启动转正
     */
    fun hasCache(context: Context): Boolean = hasSlot(context, Slot.CURRENT) || hasSlot(context, Slot.PENDING)

    // 同步：首次并发 save/load 会各生成一把密钥，后生成的覆盖前者，先写入的缓存就再也解不开
    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encode(serverId: String, bundle: SourceScriptBundle, savedAt: Long): JSONObject {
        val sources = JSONArray()
        bundle.sources.forEach { entry ->
            sources.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("name", entry.name)
                    .put("version", entry.version)
                    .put("script", entry.script)
                    .put("homepage", entry.homepage)
                    .put("sourcesJson", entry.sourcesJson)
                    .put("hash", entry.hash)
            )
        }
        return JSONObject()
            .put("serverId", serverId)
            .put("savedAt", savedAt)
            .put("version", bundle.version)
            .put("generatedAt", bundle.generatedAt)
            .put("sources", sources)
    }

    private fun decode(obj: JSONObject): Cached? {
        val sources = obj.optJSONArray("sources") ?: return null
        val list = ArrayList<SourceScriptEntry>(sources.length())
        for (i in 0 until sources.length()) {
            val item = sources.optJSONObject(i) ?: continue
            val script = item.optString("script")
            if (script.isBlank()) continue
            list.add(
                SourceScriptEntry(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    version = item.optString("version"),
                    script = script,
                    homepage = item.optString("homepage"),
                    sourcesJson = item.optString("sourcesJson", "{}"),
                    hash = item.optString("hash")
                )
            )
        }
        if (list.isEmpty()) return null
        return Cached(
            serverId = obj.optString("serverId"),
            savedAt = obj.optLong("savedAt"),
            bundle = SourceScriptBundle(
                version = obj.optInt("version", 1),
                generatedAt = obj.optString("generatedAt"),
                sources = list
            )
        )
    }
}
