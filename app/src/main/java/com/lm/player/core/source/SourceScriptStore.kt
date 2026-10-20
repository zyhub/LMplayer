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
    private const val FILE_NAME = "source_scripts.v1.enc"
    private const val KEY_ALIAS = "lm_source_scripts_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    data class Cached(
        val serverId: String,
        val savedAt: Long,
        val bundle: SourceScriptBundle
    )

    suspend fun save(context: Context, serverId: String, bundle: SourceScriptBundle): Boolean =
        withContext(Dispatchers.IO) {
            if (bundle.sources.isEmpty()) return@withContext false
            runCatching {
                val json = encode(serverId, bundle).toString()
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, secretKey())
                val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
                val wrapper = JSONObject()
                    .put("v", 1)
                    .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                    .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                val target = file(context)
                val tmp = File(target.parentFile, "${target.name}.tmp")
                tmp.writeText(wrapper.toString(), Charsets.UTF_8)
                if (target.exists() && !target.delete()) {
                    Log.w(TAG, "old cache delete failed")
                }
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                Log.i(TAG, "saved ${bundle.sources.size} script(s) for $serverId")
                true
            }.getOrElse {
                Log.w(TAG, "save failed", it)
                false
            }
        }

    suspend fun load(context: Context): Cached? = withContext(Dispatchers.IO) {
        runCatching {
            val source = file(context)
            if (!source.exists() || source.length() == 0L) return@withContext null
            val wrapper = JSONObject(source.readText(Charsets.UTF_8))
            if (wrapper.optInt("v", 0) != 1) return@withContext null
            val iv = Base64.decode(wrapper.optString("iv"), Base64.NO_WRAP)
            val data = Base64.decode(wrapper.optString("data"), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val plain = cipher.doFinal(data).toString(Charsets.UTF_8)
            decode(JSONObject(plain))
        }.getOrElse {
            Log.w(TAG, "load failed; cache ignored", it)
            runCatching { file(context).delete() }
            null
        }
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun file(context: Context): File = File(context.noBackupFilesDir, FILE_NAME)

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

    private fun encode(serverId: String, bundle: SourceScriptBundle): JSONObject {
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
            .put("savedAt", System.currentTimeMillis())
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
