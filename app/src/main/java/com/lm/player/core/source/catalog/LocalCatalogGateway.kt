package com.lm.player.core.source.catalog

import android.util.Log
import com.lm.player.core.source.ServerLinkState
import com.lm.player.core.source.SourceAccelerationController
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地目录网关（挂在共享 OkHttpClient 最前面的应用拦截器）。
 *
 * 1. **目录类接口本地加速**（需求③）：发往当前柠檬服务器的搜索 / 推荐歌单 / 榜单 / 歌单 / 专辑 / 新歌新碟 / 歌词请求，
 *    按 [SourceAccelerationController.catalogMode] 决定本地优先还是服务器优先，本地结果与服务端响应同形，
 *    各端 LemonMusicProtocol 的解析代码完全不用改，歌曲 ID 规则天然一致；
 * 2. **服务器熔断**（需求②）：服务器 LOST 时，控制面 JSON 请求立即失败，不再等 12~300 秒超时；
 *    音频流、下载、健康探活不拦截；
 * 3. 成功响应（无论来自服务器还是本地）写入 [CatalogDiskCache]，两边都失败时返回旧数据。
 */
object LocalCatalogGateway : Interceptor {
    private const val TAG = "LocalCatalogGateway"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** 连续本地失败熔断：同一「接口 × 平台」连续失败 3 次，10 分钟内改为服务器优先 */
    private const val LOCAL_FAIL_LIMIT = 3
    private const val LOCAL_COOLDOWN_MS = 10 * 60 * 1000L

    private class FailStat(@Volatile var count: Int = 0, @Volatile var lastAt: Long = 0L)

    private val localFails = ConcurrentHashMap<String, FailStat>()

    /** 服务器离线时仍放行的路径（音频流 / 下载 / 探活） */
    private val BREAKER_EXEMPT = listOf(
        "/api/health", "/api/play/local", "/api/play/proxy", "/api/play/stream", "/api/download/", "/api/tag/cover"
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        if (!path.startsWith("/api/")) return chain.proceed(request)
        val link = ServerLinkState.state.value
        val bound = link.serverHost
        if (bound.isBlank() || !request.url.host.equals(bound, ignoreCase = true)) return chain.proceed(request)

        val op = runCatching { CatalogOp.from(request) }.getOrNull()
        if (op == null) {
            if (link.isLost && request.header(ServerLinkState.PROBE_HEADER) == null && BREAKER_EXEMPT.none { path.startsWith(it) }) {
                throw IOException("柠檬服务器离线（已由本地音源接管）")
            }
            return chain.proceed(request)
        }
        return when (SourceAccelerationController.catalogMode(hasServer = true)) {
            SourceAccelerationController.Mode.LOCAL_ONLY -> localOnly(request, op)
            SourceAccelerationController.Mode.LOCAL_FIRST -> localFirst(chain, request, op)
            else -> serverFirst(chain, request, op)
        }
    }

    /** 没有服务器（已解绑）时 UI 直接调用：只走本地 + 旧数据兜底。返回响应体 JSON。 */
    suspend fun executeDetached(op: CatalogOp): JSONObject? {
        CatalogDiskCache.get(op.cacheKey, op.kind.freshMs)?.let { cached ->
            runCatching { return JSONObject(cached.body) }
        }
        val local = withTimeoutOrNull(op.kind.localOnlyBudgetMs) {
            runCatching { LocalCatalog.execute(op) }.onFailure { Log.w(TAG, "detached local ${op.kind} failed: ${it.message}") }.getOrNull()
        }
        if (local != null) {
            if (!LocalCatalog.isEmpty(op, local)) CatalogDiskCache.put(op.cacheKey, local.toString())
            return local
        }
        return CatalogDiskCache.get(op.cacheKey, op.kind.staleMs)?.let { runCatching { JSONObject(it.body) }.getOrNull() }
    }

    private fun failKey(op: CatalogOp) = "${op.kind}|${op.platform}"

    private fun localCoolingDown(op: CatalogOp): Boolean {
        val s = localFails[failKey(op)] ?: return false
        return s.count >= LOCAL_FAIL_LIMIT && System.currentTimeMillis() - s.lastAt < LOCAL_COOLDOWN_MS
    }

    private fun recordLocal(op: CatalogOp, success: Boolean) {
        val s = localFails.getOrPut(failKey(op)) { FailStat() }
        if (success) s.count = 0 else {
            s.count++
            s.lastAt = System.currentTimeMillis()
        }
    }

    private fun runLocal(op: CatalogOp, budgetMs: Long): JSONObject? {
        val started = System.currentTimeMillis()
        val result = runBlocking {
            withTimeoutOrNull(budgetMs) {
                runCatching { LocalCatalog.execute(op) }
                    .onFailure { Log.w(TAG, "local ${op.kind}/${op.platform} failed: ${it.message}") }
                    .getOrNull()
            }
        }
        recordLocal(op, result != null)
        if (result != null) Log.i(TAG, "local ${op.kind}/${op.platform} ok in ${System.currentTimeMillis() - started}ms")
        return result
    }

    private fun respond(request: Request, body: String, source: String, code: Int = 200): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Service Unavailable")
            .header("Content-Type", "application/json; charset=utf-8")
            .header("X-LM-Catalog", source)
            .body(body.toResponseBody(JSON))
            .build()

    private fun errorResponse(request: Request, message: String): Response =
        respond(request, JSONObject().put("error", message).toString(), "error", 503)

    private fun stale(op: CatalogOp): String? = CatalogDiskCache.get(op.cacheKey, op.kind.staleMs)?.body

    private fun localOnly(request: Request, op: CatalogOp): Response {
        CatalogDiskCache.get(op.cacheKey, op.kind.freshMs)?.let { return respond(request, it.body, "cache") }
        val local = runLocal(op, op.kind.localOnlyBudgetMs)
        if (local != null) {
            val text = local.toString()
            if (!LocalCatalog.isEmpty(op, local)) CatalogDiskCache.put(op.cacheKey, text)
            return respond(request, text, "local")
        }
        stale(op)?.let { return respond(request, it, "stale") }
        return errorResponse(request, "服务器离线，本地音源暂时获取失败")
    }

    private fun localFirst(chain: Interceptor.Chain, request: Request, op: CatalogOp): Response {
        CatalogDiskCache.get(op.cacheKey, op.kind.freshMs)?.let { return respond(request, it.body, "cache") }
        var localEmpty: JSONObject? = null
        if (!localCoolingDown(op)) {
            val local = runLocal(op, op.kind.localFirstBudgetMs)
            if (local != null) {
                if (!LocalCatalog.isEmpty(op, local)) {
                    val text = local.toString()
                    CatalogDiskCache.put(op.cacheKey, text)
                    return respond(request, text, "local")
                }
                localEmpty = local
            }
        }
        // 本地失败/空结果：问服务器（服务器上可能有插件音源）
        return try {
            val resp = chain.proceed(request)
            if (resp.isSuccessful) cacheServerResponse(op, resp) else {
                val fallback = localEmpty?.toString() ?: stale(op)
                if (fallback != null) {
                    resp.close()
                    respond(request, fallback, if (localEmpty != null) "local" else "stale")
                } else resp
            }
        } catch (e: IOException) {
            localEmpty?.let { return respond(request, it.toString(), "local") }
            stale(op)?.let { return respond(request, it, "stale") }
            throw e
        }
    }

    private fun serverFirst(chain: Interceptor.Chain, request: Request, op: CatalogOp): Response {
        val resp = try {
            chain.proceed(request)
        } catch (e: IOException) {
            // 服务器不可达：本地兜底（ServerLinkState 会被拦截器另行通知并很快进入 LOST）
            val local = runLocal(op, op.kind.localFirstBudgetMs)
            if (local != null) return respond(request, local.toString(), "local")
            stale(op)?.let { return respond(request, it, "stale") }
            throw e
        }
        val type = resp.header("Content-Type").orEmpty().lowercase()
        val broken = resp.code >= 500 || type.contains("text/html")
        if (!broken) return if (resp.isSuccessful) cacheServerResponse(op, resp) else resp
        val local = runLocal(op, op.kind.localFirstBudgetMs)
        if (local != null) {
            resp.close()
            return respond(request, local.toString(), "local")
        }
        stale(op)?.let {
            resp.close()
            return respond(request, it, "stale")
        }
        return resp
    }

    /** 读出服务器响应写入磁盘缓存，再原样重建响应交给调用方。 */
    private fun cacheServerResponse(op: CatalogOp, resp: Response): Response {
        val type = resp.header("Content-Type").orEmpty().lowercase()
        if (!type.contains("json")) return resp
        val declared = resp.body?.contentLength() ?: -1L
        if (declared > 2L * 1024 * 1024) return resp
        // 读响应体中途断网：body 已被消费，不能再把原响应交回去（调用方会读到「closed」）；按网络错误抛出，
        // 上层（localFirst / serverFirst / 协议层）照常走本地兜底或失败重试
        val text = try {
            resp.body?.string()
        } catch (e: IOException) {
            resp.close()
            throw e
        } ?: return resp
        runCatching {
            val obj = JSONObject(text)
            if (obj.optBoolean("ok", true) && !LocalCatalog.isEmpty(op, obj)) CatalogDiskCache.put(op.cacheKey, text)
        }
        return resp.newBuilder().body(text.toResponseBody(resp.body?.contentType() ?: JSON)).build()
    }
}
