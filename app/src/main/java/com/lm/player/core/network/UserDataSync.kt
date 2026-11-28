package com.lm.player.core.network

import org.json.JSONArray
import org.json.JSONObject

/**
 * 2026-11-25：服务器 user-data（歌单 / 收藏）多端同步的 revision 协议与歌单三方合并。
 * 手机端与 TV 端两份逐字一致。
 *
 * 服务器 lemon-muisc（v1.2.17.23 起）给 user-data 加了 revision：
 * - GET 的 ETag 是 `W/"user-data-<revision>"`，客户端带同一个 ETag 协商一律回 304；
 * - PUT 带 baseRevision 时必须等于服务器当前 revision，否则 409（响应里带服务器当前数据），保存后 revision + 1；
 *   带 revision 时必须大于当前；两个都不带也照存，但 **revision 不变**。
 * iOS 端、网页端每次保存都推进 revision，并靠它判断「别的设备改过没有」。
 *
 * 安卓两端此前保存从不带 revision —— 服务器内容变了、revision 却不变：
 * 1. 其他安卓设备的 OkHttp 缓存拿旧 ETag 协商得到 304，继续用旧数据：看不到改动，下次读改写再把旧数据整表盖回去；
 * 2. iOS 以为服务器没人动过，下一次保存（建歌单、点红心、播一首歌记最近播放）直接把安卓的改动整表覆盖。
 *
 * 现在：读 user-data 一律绕过 HTTP 缓存；保存一律带 baseRevision；冲突（409）时拿服务器当前数据重算 / 三方合并后重存。
 */
object UserDataSync {
    /** 409 冲突后最多重试几次 */
    const val MAX_CONFLICT_RETRIES = 3

    private val KEY_FIELDS = listOf("trackKeys", "paths", "tracks")

    /** user-data 对象里的 revision（网页端用毫秒时间戳做 revision，必须按 Long 读） */
    fun revisionOf(data: JSONObject?): Long? {
        if (data == null || !data.has("revision")) return null
        return when (val v = data.opt("revision")) {
            is Number -> v.toLong()
            is String -> v.trim().toDoubleOrNull()?.toLong()
            else -> null
        }
    }

    fun isConflict(code: Int, body: String): Boolean = code == 409 || body.contains("REVISION_CONFLICT")

    /** 409 响应里带的服务器当前数据；没有就返回 null，由调用方重新 GET */
    fun conflictData(body: String): JSONObject? = runCatching {
        val json = JSONObject(body)
        val data = json.optJSONObject("data") ?: return@runCatching null
        if (!data.has("revision") && json.has("serverRevision")) data.put("revision", json.opt("serverRevision"))
        data
    }.getOrNull()

    /** 深拷贝（调用方会原地改读到的歌单对象，基准必须是读到时的原样） */
    fun copyOf(arr: JSONArray?): JSONArray? = arr?.let { runCatching { JSONArray(it.toString()) }.getOrNull() }

    private fun keysField(pl: JSONObject): String =
        KEY_FIELDS.firstOrNull { pl.optJSONArray(it) != null } ?: "trackKeys"

    /** 歌单里一条曲目的识别键：新版是字符串键；旧数据可能是对象 */
    private fun entryIdentity(item: Any?): String = when (item) {
        null, JSONObject.NULL -> ""
        is String -> item.trim()
        is JSONObject -> item.optString("key")
            .ifBlank { item.optString("localPath") }
            .ifBlank { item.optString("filePath") }
            .ifBlank { item.optString("path") }
            .ifBlank { item.optString("id") }
            .ifBlank { item.toString() }
            .trim()
        else -> item.toString().trim()
    }

    private fun byId(arr: JSONArray?): LinkedHashMap<String, JSONObject> {
        val map = LinkedHashMap<String, JSONObject>()
        if (arr == null) return map
        for (i in 0 until arr.length()) {
            val pl = arr.optJSONObject(i) ?: continue
            val id = pl.optString("id")
            if (id.isNotBlank() && !map.containsKey(id)) map[id] = pl
        }
        return map
    }

    private fun same(a: Any?, b: Any?): Boolean {
        val na = a == null || a == JSONObject.NULL
        val nb = b == null || b == JSONObject.NULL
        if (na || nb) return na && nb
        return a == b || a.toString() == b.toString()
    }

    /**
     * 歌单三方合并（与 iOS UserDataMerger.mergePlaylists 同一规则）：
     * base = 本次修改前从服务器读到的那份；mine = 本机改完准备保存的；server = 服务器现在的。
     * 本机新建的放最前；本机删掉的去掉；本机改过的（改名、封面、加歌、移歌）叠到服务器版本上；
     * 其余（别的设备新建、删除、修改）以服务器为准。没有基准时取并集，宁可多留也不丢。
     */
    fun mergePlaylists(base: JSONArray?, mine: JSONArray, server: JSONArray): JSONArray {
        val baseById = base?.let { byId(it) }
        val mineById = byId(mine)
        val serverById = byId(server)
        val out = JSONArray()
        // 服务器上没有的：本机新建的留下；别的设备删掉、本机又改过的也留下；本机没动过的随服务器删掉
        for ((id, pl) in mineById) {
            if (serverById.containsKey(id)) continue
            val old = baseById?.get(id)
            if (old == null || !same(pl, old)) out.put(pl)
        }
        for ((id, remote) in serverById) {
            val old = baseById?.get(id)
            val local = mineById[id]
            if (local == null) {
                // 本机删掉了（基准里有、本机没有）就不要；否则是别的设备新建的，留下
                if (old == null) out.put(remote)
                continue
            }
            if (old != null && same(local, old)) {
                out.put(remote)
                continue
            }
            out.put(mergeOne(old, local, remote))
        }
        return out
    }

    /** 本机改过的一个歌单叠到服务器版本上 */
    private fun mergeOne(old: JSONObject?, mine: JSONObject, remote: JSONObject): JSONObject {
        val merged = JSONObject(remote.toString())
        // 普通字段（名字、封面、类型…）：本机改过的用本机的
        val names = mine.keys()
        while (names.hasNext()) {
            val k = names.next()
            if (k in KEY_FIELDS || k == "trackSnapshots") continue
            if (old == null || !same(mine.opt(k), old.opt(k))) merged.put(k, mine.opt(k))
        }
        // 曲目：以服务器为准，去掉本机删掉的，末尾追加本机新加的
        val remoteField = keysField(remote)
        val mineKeys = mine.optJSONArray(keysField(mine)) ?: JSONArray()
        val remoteKeys = remote.optJSONArray(remoteField) ?: JSONArray()
        val oldKeys = old?.optJSONArray(keysField(old))
        val oldIds = oldKeys?.let { arr -> (0 until arr.length()).mapTo(HashSet()) { entryIdentity(arr.opt(it)) } }
        val mineIds = (0 until mineKeys.length()).mapTo(HashSet()) { entryIdentity(mineKeys.opt(it)) }
        val removed = oldIds?.filterTo(HashSet()) { it !in mineIds }.orEmpty()
        val seen = HashSet<String>()
        val resultKeys = JSONArray()
        for (i in 0 until remoteKeys.length()) {
            val item = remoteKeys.opt(i)
            val id = entryIdentity(item)
            if (id in removed || !seen.add(id)) continue
            resultKeys.put(item)
        }
        val added = ArrayList<String>()
        for (i in 0 until mineKeys.length()) {
            val item = mineKeys.opt(i)
            val id = entryIdentity(item)
            if (oldIds?.contains(id) == true || !seen.add(id)) continue
            resultKeys.put(item)
            added.add(id)
        }
        merged.put(remoteField, resultKeys)
        // 快照：服务器的 + 本机新加的，去掉本机删掉的
        val mineSnaps = mine.optJSONObject("trackSnapshots")
        if (mineSnaps != null || merged.has("trackSnapshots")) {
            val snapshots = merged.optJSONObject("trackSnapshots") ?: JSONObject()
            if (mineSnaps != null) for (id in added) mineSnaps.opt(id)?.let { snapshots.put(id, it) }
            for (id in removed) snapshots.remove(id)
            merged.put("trackSnapshots", snapshots)
        }
        return merged
    }
}
