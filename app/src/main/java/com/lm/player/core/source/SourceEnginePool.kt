package com.lm.player.core.source

import android.content.Context
import android.util.Log
import com.lm.player.core.model.SourceScriptEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 本地取链请求优先级：播放 > 预取 > 下载。 */
enum class ResolvePriority(val weight: Int) { PLAY(3), PREFETCH(2), DOWNLOAD(1) }

/**
 * 单个音源脚本的 QuickJS 实例池。
 *
 * - 第一个实例在会话启动时初始化；其余实例只在出现争用时懒创建（上限 [maxSize]）；
 * - 实例忙时请求按优先级排队，在调用方预算内等待，而不是像以前那样立即失败；
 * - 实例单次求值超过 [STUCK_MS] 视为卡死：不再计入容量（允许另建新实例），等它自己返回后关闭；
 *   同一脚本累计卡死 [MAX_STUCK] 次，整个池子停用（由会话标记为 poisoned）。
 */
internal class SourceEnginePool(
    private val context: Context,
    val entry: SourceScriptEntry,
    private val maxSize: Int,
    private val scope: CoroutineScope,
    private val initTimeoutMs: Long
) {
    companion object {
        private const val TAG = "SourceEnginePool"
        const val STUCK_MS = 40_000L
        const val MAX_STUCK = 3
    }

    private class Waiter(val priority: Int, val seq: Long, val deferred: CompletableDeferred<LocalSourceEngine?>) {
        var assigned: LocalSourceEngine? = null
    }

    private val lock = Any()
    private val idle = ArrayDeque<LocalSourceEngine>()
    private val live = mutableListOf<LocalSourceEngine>()
    private val stuck = mutableSetOf<LocalSourceEngine>()
    private val waiters = mutableListOf<Waiter>()
    private var creating = 0
    private var waiterSeq = 0L
    private var stuckCount = 0

    @Volatile
    var closed = false
        private set

    /** 首个实例（供 supports() 等只读查询）。 */
    @Volatile
    var primary: LocalSourceEngine? = null
        private set

    val sourceId: String get() = entry.id
    val sourceName: String get() = entry.name
    val isPoisoned: Boolean get() = synchronized(lock) { stuckCount >= MAX_STUCK }

    fun supports(platform: String, action: String = "musicUrl"): Boolean =
        primary?.supports(platform, action) ?: false

    /** 初始化第一个实例。失败时池子不可用。 */
    suspend fun start(): Result<Unit> {
        val engine = LocalSourceEngine(context, entry)
        val result = initWithTimeout(engine)
        if (result.isSuccess) {
            synchronized(lock) {
                live.add(engine)
                idle.addLast(engine)
            }
            primary = engine
        } else {
            engine.close()
        }
        return result
    }

    private suspend fun initWithTimeout(engine: LocalSourceEngine): Result<Unit> {
        // 不在当前协程 withTimeout 取消 native 求值：独立 scope 执行，超时只停止等待。
        val deferred = CompletableDeferred<Result<Unit>>()
        scope.launch {
            deferred.complete(runCatching { engine.initialize() }.getOrElse { Result.failure(it) })
        }
        return withTimeoutOrNull(initTimeoutMs) { deferred.await() }
            ?: Result.failure(IllegalStateException("脚本初始化超时(${initTimeoutMs}ms)"))
    }

    /**
     * 借出一个实例执行 [block]。等待超过 [waitMs] 仍没有空闲实例时返回 null。
     * block 本身不受 waitMs 限制（由调用方以「停止等待」方式控制预算）。
     */
    suspend fun <T> withEngine(priority: ResolvePriority, waitMs: Long, block: suspend (LocalSourceEngine) -> T): T? {
        val engine = acquire(priority, waitMs) ?: return null
        try {
            return block(engine)
        } finally {
            release(engine)
        }
    }

    private suspend fun acquire(priority: ResolvePriority, waitMs: Long): LocalSourceEngine? {
        val waiter: Waiter
        synchronized(lock) {
            if (closed) return null
            reapStuckLocked()
            idle.removeFirstOrNull()?.let { return it }
            waiter = Waiter(priority.weight, waiterSeq++, CompletableDeferred())
            waiters.add(waiter)
            waiters.sortWith(compareByDescending<Waiter> { it.priority }.thenBy { it.seq })
            maybeGrowLocked()
        }
        val got = withTimeoutOrNull(waitMs.coerceAtLeast(1L)) { waiter.deferred.await() }
        if (got == null) {
            synchronized(lock) {
                waiters.remove(waiter)
                // 超时瞬间恰好被分配：交还给池子，避免泄漏
                waiter.assigned?.let { handOffLocked(it) }
            }
        }
        return got
    }

    private fun release(engine: LocalSourceEngine) {
        synchronized(lock) {
            if (engine in stuck) {
                // 卡死实例最终返回了：直接关闭，不再复用
                stuck.remove(engine)
                live.remove(engine)
                engine.close()
                maybeGrowLocked()
                return
            }
            if (closed || !engine.isReady) {
                live.remove(engine)
                engine.close()
                maybeGrowLocked()
                return
            }
            handOffLocked(engine)
        }
    }

    private fun handOffLocked(engine: LocalSourceEngine) {
        while (waiters.isNotEmpty()) {
            val next = waiters.removeAt(0)
            next.assigned = engine
            if (next.deferred.complete(engine)) return
            next.assigned = null
        }
        idle.addLast(engine)
    }

    /** 把超时未返回的实例移出容量统计。 */
    private fun reapStuckLocked() {
        val now = System.currentTimeMillis()
        live.filter { it !in stuck && it.busySince > 0L && now - it.busySince > STUCK_MS }.forEach {
            stuck.add(it)
            stuckCount++
            Log.w(TAG, "engine stuck: ${entry.name} (count=$stuckCount)")
        }
    }

    private fun maybeGrowLocked() {
        if (closed || waiters.isEmpty() || stuckCount >= MAX_STUCK) return
        val capacity = live.size - stuck.size + creating
        if (capacity >= maxSize) return
        creating++
        scope.launch {
            val engine = LocalSourceEngine(context, entry)
            val result = initWithTimeout(engine)
            synchronized(lock) {
                creating--
                if (result.isSuccess && !closed) {
                    live.add(engine)
                    if (primary == null) primary = engine
                    handOffLocked(engine)
                    Log.i(TAG, "pool grown: ${entry.name} size=${live.size}")
                } else {
                    engine.close()
                }
            }
        }
    }

    fun snapshotSize(): Int = synchronized(lock) { live.size - stuck.size }

    fun close() {
        val toClose: List<LocalSourceEngine>
        synchronized(lock) {
            closed = true
            toClose = live.toList()
            live.clear()
            idle.clear()
            stuck.clear()
            waiters.forEach { it.deferred.complete(null) }
            waiters.clear()
        }
        primary = null
        toClose.forEach { runCatching { it.close() } }
    }
}
