package com.lm.player.core.podcast

import java.util.Calendar

/** 播客界面文案格式化（约定：界面直接显示，不自行换算） */
object PodcastFormat {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** 「今天」「昨天」「3 天前」「10月2日」「2024年10月2日」；未知为空串 */
    fun published(ms: Long, now: Long = System.currentTimeMillis()): String {
        if (ms <= 0L) return ""
        val days = dayIndex(now) - dayIndex(ms)
        return when {
            days <= 0L -> "今天"
            days == 1L -> "昨天"
            days < 7L -> "$days 天前"
            else -> {
                val c = Calendar.getInstance().apply { timeInMillis = ms }
                val n = Calendar.getInstance().apply { timeInMillis = now }
                val md = "${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日"
                if (c.get(Calendar.YEAR) == n.get(Calendar.YEAR)) md else "${c.get(Calendar.YEAR)}年$md"
            }
        }
    }

    /** 节目最近更新：「今天更新」「3 天前更新」「10月2日更新」 */
    fun updated(latestPubMs: Long, now: Long = System.currentTimeMillis()): String =
        published(latestPubMs, now).let { if (it.isBlank()) "" else "${it}更新" }

    /** 「1 小时 12 分」「38 分钟」「45 秒」；未知为空串 */
    fun duration(totalSec: Long): String {
        if (totalSec <= 0L) return ""
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        return when {
            h > 0 && m > 0 -> "$h 小时 $m 分"
            h > 0 -> "$h 小时"
            m > 0 -> "$m 分钟"
            else -> "$totalSec 秒"
        }
    }

    /** 「剩余 23 分钟」「剩余 1 小时 5 分」 */
    fun remaining(remainingMs: Long): String {
        val totalMin = ((remainingMs + 59_999L) / 60_000L).coerceAtLeast(1L)
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "剩余 $h 小时 $m 分"
            h > 0 -> "剩余 $h 小时"
            else -> "剩余 $m 分钟"
        }
    }

    /** 「共 268 期」 */
    fun episodeCount(n: Int): String = if (n > 0) "共 $n 期" else ""

    private fun dayIndex(ms: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return (ms + c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET)) / DAY_MS
    }
}
