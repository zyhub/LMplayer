package com.lm.player.core.source.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/*
 * 本地目录服务（LocalCatalog）的 JS 语义辅助层。
 *
 * 目录代码逐函数移植自柠檬服务端 server/musicSdk.js 与 server/discoverSdk.js（MIT License,
 * Copyright (c) 2026 jia070310, https://github.com/jia070310/lemon-muisc @143fa81）。
 * 为了与原 JS 行为一致，这里提供 `a || b`、String(x)、parseInt 等语义的 Kotlin 版本。
 */

/** JS 的 null / undefined（JSONObject.NULL 也算） */
internal fun Any?.isNullish(): Boolean = this == null || this == JSONObject.NULL

/** JS 真值判断 */
internal fun truthy(v: Any?): Boolean = when (v) {
    null, JSONObject.NULL -> false
    is String -> v.isNotEmpty()
    is Boolean -> v
    is Number -> v.toDouble().let { it != 0.0 && !it.isNaN() }
    else -> true
}

/** JS 的 `a || b || c`：返回第一个真值，否则返回最后一个操作数 */
internal fun jor(vararg values: Any?): Any? {
    for (v in values) if (truthy(v)) return v
    return values.lastOrNull()
}

/** JS 的 String(x)，数字不带多余的 .0；null/undefined → "" */
internal fun jstr(v: Any?): String = when (v) {
    null, JSONObject.NULL -> ""
    is String -> v
    is Double -> if (!v.isNaN() && !v.isInfinite() && v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
    is Float -> jstr(v.toDouble())
    else -> v.toString()
}

/** `(a || b || '')` 再转字符串 */
internal fun jos(vararg values: Any?): String {
    for (v in values) if (truthy(v)) return jstr(v)
    return ""
}

/** JS Number(x)（失败返回 NaN） */
internal fun jnum(v: Any?): Double = when (v) {
    null, JSONObject.NULL -> 0.0
    is Number -> v.toDouble()
    is Boolean -> if (v) 1.0 else 0.0
    is String -> v.trim().let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() ?: Double.NaN }
    else -> Double.NaN
}

/** JS parseInt(x, 10)：取前导整数，失败为 null */
internal fun jparseInt(v: Any?): Long? {
    if (v.isNullish()) return null
    if (v is Number) return v.toDouble().let { if (it.isNaN()) null else it.toLong() }
    val m = Regex("^\\s*([+-]?\\d+)").find(jstr(v)) ?: return null
    return m.groupValues[1].toLongOrNull()
}

/** `parseInt(x) || 0` */
internal fun jint(v: Any?): Long = jparseInt(v) ?: 0L

internal fun JSONObject?.g(key: String): Any? = this?.opt(key)?.takeIf { it != JSONObject.NULL }
internal fun JSONObject?.o(key: String): JSONObject? = this?.optJSONObject(key)
internal fun JSONObject?.arr(key: String): JSONArray? = this?.optJSONArray(key)

/** 取第一个真值字段转字符串（`item.a || item.b || ''`） */
internal fun JSONObject?.s(vararg keys: String): String {
    if (this == null) return ""
    for (k in keys) {
        val v = opt(k)
        if (truthy(v)) return jstr(v)
    }
    return ""
}

internal fun JSONArray?.objs(): List<JSONObject> {
    if (this == null) return emptyList()
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) optJSONObject(i)?.let { out.add(it) }
    return out
}

internal fun JSONArray?.values(): List<Any?> {
    if (this == null) return emptyList()
    return (0 until length()).map { opt(it) }
}

internal fun jarr(list: List<Any?>): JSONArray = JSONArray().also { a -> list.forEach { a.put(it ?: JSONObject.NULL) } }

/** 构造对象；值为 null 的键不写入（对应 JS undefined） */
internal fun obj(vararg pairs: Pair<String, Any?>): JSONObject {
    val o = JSONObject()
    pairs.forEach { (k, v) -> if (v != null) o.put(k, v) }
    return o
}

internal fun JSONObject.copyObj(): JSONObject = JSONObject(toString())

/** JS encodeURIComponent */
internal fun encodeUri(s: String): String =
    URLEncoder.encode(s, "UTF-8")
        .replace("+", "%20")
        .replace("%21", "!")
        .replace("%27", "'")
        .replace("%28", "(")
        .replace("%29", ")")
        .replace("%7E", "~")

/** 对齐服务端 parseJSON：去 BOM，失败时截取首个 { / [ 到最后一个 } / ] 再试 */
internal fun parseJsonAny(text: String?): Any? {
    if (text == null) return null
    val t = text.removePrefix("﻿").trim()
    if (t.isEmpty()) return null
    val direct = runCatching { JSONTokener(t).nextValue() }.getOrNull()
    if (direct is JSONObject || direct is JSONArray) return direct
    val start = t.indexOfFirst { it == '{' || it == '[' }
    val end = maxOf(t.lastIndexOf('}'), t.lastIndexOf(']'))
    if (start >= 0 && end > start) {
        return runCatching { JSONTokener(t.substring(start, end + 1)).nextValue() }.getOrNull()
            ?.takeIf { it is JSONObject || it is JSONArray }
    }
    return null
}

internal fun parseJsonObj(text: String?): JSONObject? = parseJsonAny(text) as? JSONObject

/** 酷我接口偶发单引号 JSON */
internal fun parseKuwoBody(raw: String): JSONObject {
    val text = raw.trim()
    return runCatching { JSONObject(text) }.getOrElse { JSONObject(text.replace('\'', '"')) }
}

internal const val UA_PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
internal const val UA_PC_SHORT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
internal const val UA_IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15"
internal const val UA_IPHONE_FULL = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"

/**
 * 目录专用 HTTP（对应服务端 needle 封装的 req）：
 * - 不经过柠檬服务器、绝不注入服务器 Token；
 * - 与服务端一致：非 2xx 也返回响应体（由调用方按内容判断），只有网络错误才抛异常。
 */
internal object CatalogHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    data class Resp(val code: Int, val body: String, val finalUrl: String)

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String = request("GET", url, null, null, headers).body

    suspend fun post(url: String, body: String, contentType: String?, headers: Map<String, String> = emptyMap()): String =
        request("POST", url, body, contentType, headers).body

    /**
     * 可取消的请求：协程超时/取消时同步取消 OkHttp Call（上层用 withTimeout 控制预算才真正生效）。
     */
    suspend fun request(
        method: String,
        url: String,
        body: String?,
        contentType: String?,
        headers: Map<String, String> = emptyMap()
    ): Resp = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        var type = contentType
        headers.forEach { (k, v) ->
            if (k.equals("Content-Type", true)) type = v else builder.header(k, v)
        }
        if (headers.keys.none { it.equals("User-Agent", true) }) builder.header("User-Agent", UA_PC)
        val reqBody = if (method == "GET") null else (body ?: "").toRequestBody(type?.toMediaTypeOrNull())
        builder.method(method, reqBody)
        val call = client.newCall(builder.build())
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if (cont.isActive) cont.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val result = runCatching {
                        response.use { resp ->
                            val bytes = resp.body?.bytes() ?: ByteArray(0)
                            val charset = resp.body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                            Resp(resp.code, String(bytes, charset), resp.request.url.toString())
                        }
                    }
                    if (cont.isActive) cont.resumeWith(result)
                }
            })
        }
    }
}
