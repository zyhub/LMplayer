package com.lm.player.core.source

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.lm.player.core.database.ZdsDatabase
import com.lm.player.core.database.entity.ResolvedStreamCacheEntity
import com.lm.player.core.database.entity.ServerSourceEntity
import com.lm.player.core.database.entity.ServerSourceMetaEntity
import com.lm.player.core.model.ServerConfig
import com.lm.player.core.model.ServerSourceSnapshot
import com.lm.player.core.model.SourceHealthInfo
import com.lm.player.core.model.SourceHealthPlatform
import com.lm.player.core.model.SourcePlatformCapability
import com.lm.player.core.network.LemonMusicProtocol
import com.lm.player.core.network.ServerLatencyMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 服务器音源状态缓存 + 短时直链兜底。
 *
 * 说明：
 * 1. 音源状态（激活、健康度、平台/音质能力、自动切换策略）全部来自服务端
 *    /api/source/list 与 /api/settings，不做本地猜测；
 * 2. 服务器延迟偏高且**最近仍在线**时，解析在线播放地址会优先使用最近成功
 *    解析过的直链缓存（10 分钟 TTL）；服务器长时间不在线时缓存自然失效；
 * 3. 当前不在手机端执行音源 JS 脚本（服务端 /api/source/:id/raw 未暴露脚本正文，
 *    App 也没有 JS 引擎），因此这里缓存的是「音源状态 + 已验证直链」，不缓存可执行脚本。
 */
object ServerSourceRepository {

    private const val TAG = "ServerSourceRepository"
    private const val SYNC_THROTTLE_MS = 30_000L
    private const val ONLINE_GRACE_MS = 10 * 60 * 1000L
    private const val STREAM_CACHE_TTL_MS = 10 * 60 * 1000L

    @Volatile
    private var database: ZdsDatabase? = null

    private val syncMutex = Mutex()
    private val lastSyncAt = ConcurrentHashMap<String, Long>()
    private val lastSnapshots = ConcurrentHashMap<String, ServerSourceSnapshot>()

    fun init(context: Context) {
        if (database == null) {
            synchronized(this) {
                if (database == null) {
                    database = ZdsDatabase.getInstance(context.applicationContext)
                }
            }
        }
    }

    private fun db(): ZdsDatabase? = database

    suspend fun getCachedSources(serverId: String): List<ServerSourceEntity> =
        db()?.serverSourceDao()?.getSources(serverId) ?: emptyList()

    fun observeSources(serverId: String): Flow<List<ServerSourceEntity>>? =
        db()?.serverSourceDao()?.observeSources(serverId)

    fun observeMeta(serverId: String): Flow<ServerSourceMetaEntity?>? =
        db()?.serverSourceDao()?.observeMeta(serverId)

    fun lastSnapshot(serverId: String): ServerSourceSnapshot? = lastSnapshots[serverId]

    /**
     * 从服务器同步音源状态并落库。
     * @param force true 时绕过 30s 节流（设置页手动刷新）。
     */
    suspend fun syncFromServer(
        server: ServerConfig,
        protocol: LemonMusicProtocol,
        force: Boolean = false
    ): Result<ServerSourceSnapshot> = withContext(Dispatchers.IO) {
        val database = db()
            ?: return@withContext Result.failure(Exception("本地数据库尚未初始化"))
        val dao = database.serverSourceDao()

        val now = System.currentTimeMillis()
        val last = lastSyncAt[server.id] ?: 0L
        if (!force && now - last < SYNC_THROTTLE_MS) {
            val cached = lastSnapshots[server.id]
            if (cached != null) return@withContext Result.success(cached)
        }

        syncMutex.withLock {
            val snapshotResult = protocol.fetchSourceSnapshot()
            val host = server.serverUrl
                .substringAfter("://", server.serverUrl)
                .substringBefore('/')
                .substringBefore('?')

            snapshotResult.onSuccess { raw ->
                val snapshot = raw.copy(serverId = server.id, serverName = server.name)
                val entities = snapshot.sources.map { s ->
                    ServerSourceEntity(
                        key = "${server.id}::${s.id}",
                        serverId = server.id,
                        sourceId = s.id,
                        name = s.name,
                        description = s.description,
                        author = s.author,
                        version = s.version,
                        homepage = s.homepage,
                        isActive = s.isActive,
                        healthLevel = s.health.level,
                        healthBadge = s.health.badge,
                        healthTip = s.health.tip,
                        healthUnhealthy = s.health.unhealthy,
                        healthSampleCount = s.health.sampleCount,
                        healthPreviewCount = s.health.previewCount,
                        healthRatio = s.health.ratio,
                        healthPlatformsJson = healthPlatformsToJson(s.health.platforms),
                        platformsJson = platformsToJson(s.platforms),
                        disabledPlatformsJson = JSONArray(s.disabledPlatforms).toString(),
                        rawScriptCached = s.rawScriptCached,
                        fetchedAt = now
                    )
                }
                database.withTransaction {
                    dao.deleteSources(server.id)
                    dao.insertSources(entities)
                }
                val latency = ServerLatencyMonitor.latencyMs(host).takeIf { it > 0L } ?: snapshot.latencyMs
                dao.upsertMeta(
                    ServerSourceMetaEntity(
                        serverId = server.id,
                        serverName = server.name,
                        fallbackMode = snapshot.fallbackMode,
                        activeSourceCount = snapshot.sources.count { it.isActive },
                        totalSourceCount = snapshot.sources.size,
                        latencyMs = latency,
                        lastOnlineAt = now,
                        lastSyncAt = now,
                        lastError = null
                    )
                )
                lastSyncAt[server.id] = now
                lastSnapshots[server.id] = snapshot
                Log.i(TAG, "Synced ${entities.size} source(s) for ${server.name}, latency=${latency}ms")
            }.onFailure { e ->
                Log.w(TAG, "Sync sources failed for ${server.name}", e)
                lastSyncAt[server.id] = now
                val old = dao.getMeta(server.id)
                dao.upsertMeta(
                    (old ?: ServerSourceMetaEntity(serverId = server.id, serverName = server.name)).copy(
                        latencyMs = ServerLatencyMonitor.latencyMs(host),
                        lastError = e.message ?: "同步音源状态失败"
                    )
                )
            }
            snapshotResult
        }
    }

    fun isLocalFallbackActive(host: String): Boolean =
        ServerLatencyMonitor.isRecentlyOnline(host, ONLINE_GRACE_MS) &&
            ServerLatencyMonitor.isDegraded(host)

    fun isServerRecentlyOnline(host: String): Boolean =
        ServerLatencyMonitor.isRecentlyOnline(host, ONLINE_GRACE_MS)

    fun isLatencyHigh(host: String): Boolean = ServerLatencyMonitor.isDegraded(host)

    /** 最近成功解析的直链；只在服务器近期在线且延迟偏高时由协议层读取 */
    suspend fun getCachedStream(host: String, songId: String, quality: String): ResolvedStreamCacheEntity? {
        val dao = db()?.serverSourceDao() ?: return null
        val now = System.currentTimeMillis()
        val key = cacheKey(host, songId, quality)
        val row = dao.getCachedStream(key) ?: return null
        if (row.expiresAt <= now) {
            return null
        }
        return row
    }

    suspend fun cacheStream(
        host: String,
        songId: String,
        quality: String,
        url: String,
        format: String = "",
        bitRate: Int = 0,
        sourceId: String = ""
    ) {
        if (host.isBlank() || songId.isBlank() || url.isBlank()) return
        val dao = db()?.serverSourceDao() ?: return
        val now = System.currentTimeMillis()
        runCatching {
            dao.upsertCachedStream(
                ResolvedStreamCacheEntity(
                    cacheKey = cacheKey(host, songId, quality),
                    serverHost = host,
                    songId = songId,
                    quality = quality,
                    url = url,
                    format = format,
                    bitRate = bitRate,
                    sourceId = sourceId,
                    updatedAt = now,
                    expiresAt = now + STREAM_CACHE_TTL_MS
                )
            )
            dao.purgeExpiredStreams(now)
        }.onFailure { Log.w(TAG, "cache stream failed", it) }
    }

    private fun cacheKey(host: String, songId: String, quality: String): String =
        "${host.trim().lowercase()}|${songId}|${quality.trim().lowercase()}"

    private fun healthPlatformsToJson(items: List<SourceHealthPlatform>): String {
        val arr = JSONArray()
        items.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("label", p.label)
                put("level", p.level)
                put("sampleCount", p.sampleCount)
                put("previewCount", p.previewCount)
                put("ratio", p.ratio)
            })
        }
        return arr.toString()
    }

    private fun platformsToJson(items: List<SourcePlatformCapability>): String {
        val arr = JSONArray()
        items.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("enabled", p.enabled)
                put("qualitys", JSONArray(p.qualitys))
                put("actions", JSONArray(p.actions))
            })
        }
        return arr.toString()
    }

    fun parseHealthPlatforms(json: String?): List<SourceHealthPlatform> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                SourceHealthPlatform(
                    id = o.optString("id"),
                    label = o.optString("label", o.optString("id")),
                    level = o.optString("level", "unknown"),
                    sampleCount = o.optInt("sampleCount", 0),
                    previewCount = o.optInt("previewCount", 0),
                    ratio = o.optDouble("ratio", 0.0)
                )
            }
        }.getOrDefault(emptyList())
    }

    fun parsePlatforms(json: String?): List<SourcePlatformCapability> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val qualitys = mutableListOf<String>()
                o.optJSONArray("qualitys")?.let { qa ->
                    for (qi in 0 until qa.length()) qa.optString(qi).takeIf { it.isNotBlank() }?.let { qualitys.add(it) }
                }
                val actions = mutableListOf<String>()
                o.optJSONArray("actions")?.let { aa ->
                    for (ai in 0 until aa.length()) aa.optString(ai).takeIf { it.isNotBlank() }?.let { actions.add(it) }
                }
                SourcePlatformCapability(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    qualitys = qualitys,
                    actions = actions,
                    enabled = o.optBoolean("enabled", true)
                )
            }
        }.getOrDefault(emptyList())
    }

    fun parseStringList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun healthFromEntity(entity: ServerSourceEntity): SourceHealthInfo = SourceHealthInfo(
        level = entity.healthLevel,
        badge = entity.healthBadge,
        tip = entity.healthTip,
        unhealthy = entity.healthUnhealthy,
        sampleCount = entity.healthSampleCount,
        previewCount = entity.healthPreviewCount,
        ratio = entity.healthRatio,
        platforms = parseHealthPlatforms(entity.healthPlatformsJson)
    )
}
