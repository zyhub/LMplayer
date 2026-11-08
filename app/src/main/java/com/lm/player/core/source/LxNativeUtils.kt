package com.lm.player.core.source

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 本地音源运行时（assets/lx/lx_runtime.js）用到的原生能力。
 *
 * 对齐服务端 server/utils/lxSourceRuntime.js：md5 / aes / rsa / randomBytes / zlib。
 * JS 与 Kotlin 之间的二进制统一用 base64 传递；任何错误都返回以 [ERR] 开头的字符串，
 * 由 JS 侧 unwrap() 转成异常 —— 绑定函数本身绝不抛异常，避免影响 QuickJS 求值状态。
 */
internal object LxNativeUtils {
    const val ERR = "\u0000ERR:"

    private const val MAX_INFLATE_BYTES = 8 * 1024 * 1024

    private val random = SecureRandom()

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun unb64(text: String?): ByteArray =
        if (text.isNullOrEmpty()) ByteArray(0) else Base64.decode(text, Base64.DEFAULT)

    private inline fun guard(block: () -> String): String = try {
        block()
    } catch (e: Throwable) {
        ERR + (e.message ?: e.javaClass.simpleName)
    }

    fun md5Hex(text: String): String = guard {
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun hash(alg: String, dataB64: String): String = guard {
        b64(MessageDigest.getInstance(alg).digest(unb64(dataB64)))
    }

    fun hmac(alg: String, keyB64: String, dataB64: String): String = guard {
        // alg 为 Java 摘要名（MD5 / SHA-1 / SHA-256 / SHA-512），见 lx_runtime.js 的 HASH_ALGS
        val name = "Hmac" + alg.replace("-", "").uppercase()
        val mac = Mac.getInstance(name)
        mac.init(SecretKeySpec(unb64(keyB64).let { if (it.isEmpty()) ByteArray(1) else it }, name))
        b64(mac.doFinal(unb64(dataB64)))
    }

    /**
     * Node 风格算法名，如 aes-128-ecb / aes-128-cbc / aes-256-cbc / aes-128-ctr。
     * ECB/CBC 默认 PKCS5（= PKCS7 对 16 字节块），autoPadding=false 时不填充。
     */
    fun aes(
        encrypt: Boolean,
        alg: String,
        keyB64: String,
        ivB64: String,
        dataB64: String,
        autoPadding: Boolean
    ): String = guard {
        val lower = alg.trim().lowercase()
        val mode = when {
            lower.endsWith("ecb") -> "ECB"
            lower.endsWith("cbc") -> "CBC"
            lower.endsWith("ctr") -> "CTR"
            lower.endsWith("cfb") || lower.endsWith("cfb128") -> "CFB"
            lower.endsWith("ofb") -> "OFB"
            lower.endsWith("gcm") -> throw IllegalArgumentException("GCM 模式暂不支持")
            else -> "ECB"
        }
        val padding = if (autoPadding && (mode == "ECB" || mode == "CBC")) "PKCS5Padding" else "NoPadding"
        val cipher = Cipher.getInstance("AES/$mode/$padding")
        val key = SecretKeySpec(unb64(keyB64), "AES")
        val opMode = if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
        if (mode == "ECB") {
            cipher.init(opMode, key)
        } else {
            val iv = unb64(ivB64).let { if (it.size == 16) it else it.copyOf(16) }
            cipher.init(opMode, key, IvParameterSpec(iv))
        }
        b64(cipher.doFinal(unb64(dataB64)))
    }

    /** RSA 公钥加密。noPadding=true 时与服务端一致（RSA_NO_PADDING，调用方已左侧补零到密钥长度）。 */
    fun rsa(dataB64: String, pem: String, noPadding: Boolean): String = guard {
        val body = pem
            .replace(Regex("-----BEGIN [A-Z ]+-----"), "")
            .replace(Regex("-----END [A-Z ]+-----"), "")
            .replace(Regex("\\s"), "")
        val keySpec = X509EncodedKeySpec(Base64.decode(body, Base64.DEFAULT))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec)
        val cipher = Cipher.getInstance(if (noPadding) "RSA/ECB/NoPadding" else "RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        b64(cipher.doFinal(unb64(dataB64)))
    }

    fun randomBytes(size: Int): String = guard {
        val n = size.coerceIn(0, 4096)
        val bytes = ByteArray(n)
        random.nextBytes(bytes)
        b64(bytes)
    }

    /**
     * op = inflate / deflate；mode = zlib（带头）/ raw / gzip / auto（仅解压：按魔数判断 gzip 或 zlib）。
     */
    fun zlib(op: String, mode: String, dataB64: String): String = guard {
        val data = unb64(dataB64)
        if (op == "inflate") {
            val effective = if (mode == "auto") {
                if (data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) "gzip" else "zlib"
            } else mode
            val input = when (effective) {
                "gzip" -> GZIPInputStream(data.inputStream())
                "raw" -> InflaterInputStream(data.inputStream(), Inflater(true))
                else -> InflaterInputStream(data.inputStream(), Inflater(false))
            }
            val out = ByteArrayOutputStream()
            input.use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_INFLATE_BYTES) throw IllegalStateException("解压数据过大")
                }
            }
            b64(out.toByteArray())
        } else {
            val out = ByteArrayOutputStream()
            val stream = when (mode) {
                "gzip" -> GZIPOutputStream(out)
                "raw" -> DeflaterOutputStream(out, Deflater(Deflater.DEFAULT_COMPRESSION, true))
                else -> DeflaterOutputStream(out, Deflater(Deflater.DEFAULT_COMPRESSION, false))
            }
            stream.use { it.write(data) }
            b64(out.toByteArray())
        }
    }

    /** 解析脚本头部注释元数据（对齐服务端 parseScriptMeta）。 */
    fun parseScriptMeta(script: String): Map<String, String> {
        val tags = setOf("name", "description", "version", "author", "homepage")
        val match = Regex("^/\\*!?([\\s\\S]*?)\\*/").find(script.trimStart()) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        match.groupValues[1].split('\n').forEach { line ->
            val cleaned = line.replace(Regex("^\\s*\\*?\\s*"), "").trim()
            val tag = Regex("^@(\\w+)\\s+(.*)").find(cleaned) ?: return@forEach
            val key = tag.groupValues[1].lowercase()
            if (key !in tags) return@forEach
            var value = tag.groupValues[2].trim().replace(Regex("\\s*\\*+\\s*\$"), "").trim()
            if (key == "version") value = value.removePrefix("v").removePrefix("V")
            if (value.isNotBlank()) out[key] = value
        }
        return out
    }
}
