package com.soreverse.mcp.core

import com.soreverse.mcp.core.AppLog
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 进程级 heavy 工具闸门。
 *
 * 1) 全局单例：MCP 服务端与 `taffy_script` 内的工具调用共用同一套许可。脚本里连调
 *    `apk_analyze` / `jadx` 这类重工具原来完全绕过服务端许可，会和外部调用并发操作
 *    同一份引擎状态与文件。
 * 2) 租约 + 看门狗：`future.cancel(true)` 打断不了 native 调用。许可由任务自身在真正
 *    结束时释放，一旦某个 native 永久阻塞，许可就再也回不来（所有重工具恒返回
 *    SERVER_BUSY，只能重启进程）。现在每次获取都登记租约，看门狗回收超期槽位并计入
 *    `leaked`；任务之后若真的返回，[release] 不会重复释放（否则许可凭空变多）。
 */
internal object HeavyGate {
    private const val DEFAULT_LEASE_MS = 30L * 60L * 1000L

    private val lock = Any()
    private val seq = AtomicLong(0)
    private val leases = ConcurrentHashMap<Long, Lease>()

    @Volatile private var permitTarget = 1
    @Volatile private var appliedPermits = 1
    @Volatile private var gate = Semaphore(1, true)
    @Volatile private var leaked = 0
    /**
     * 租约到期被回收后置位：说明有 native 任务可能仍在后台跑（cancel 打不断 native）。
     * 置位后仍放行新任务（否则功能永久不可用），但会在 /health 与状态里报告；
     * 用户确认「重建引擎」后由 [clearPoisoned] 清除。
     */
    @Volatile private var poisoned = false
    @Volatile private var watcherStarted = false

    private class Lease(val startedAt: Long, val leaseMs: Long)

    /** 立即尝试获取；0 表示当前没有空位。 */
    fun tryAcquire(leaseMs: Long = DEFAULT_LEASE_MS): Long {
        ensureWatcher()
        if (!gate.tryAcquire()) return 0L
        return register(leaseMs)
    }

    /** 带等待的获取（脚本内部使用，避免一次竞争就失败）。 */
    fun acquire(timeoutMs: Long, leaseMs: Long = DEFAULT_LEASE_MS): Long {
        ensureWatcher()
        if (!gate.tryAcquire(timeoutMs.coerceIn(1_000, 600_000), TimeUnit.MILLISECONDS)) return 0L
        return register(leaseMs)
    }

    fun release(handle: Long) {
        if (handle == 0L) return
        // 已被看门狗回收（或重复释放）就不还许可
        if (leases.remove(handle) == null) return
        gate.release()
        applyPendingPermits()
    }

    fun setPermits(p: Int) {
        permitTarget = p.coerceIn(1, 8)
        applyPendingPermits()
    }

    fun inFlight(): Int = leases.size

    fun isPoisoned(): Boolean = poisoned

    /** 引擎重建后调用：僵尸任务已经和新引擎无关，可以清掉告警（旧任务仍可能持有旧对象）。 */
    fun clearPoisoned() {
        poisoned = false
        leaked = 0
    }

    fun stats(): JSONObject = JSONObject()
        .put("permits", appliedPermits)
        .put("pendingPermits", permitTarget)
        .put("inFlight", leases.size)
        .put("leaked", leaked)
        .put("poisoned", poisoned)

    private fun register(leaseMs: Long): Long {
        val id = seq.incrementAndGet()
        leases[id] = Lease(System.currentTimeMillis(), leaseMs.coerceAtLeast(60_000L))
        return id
    }

    /** 有任务在跑时不替换闸实例：在跑任务的 release 会还到旧实例上，新实例许可凭空翻倍。 */
    private fun applyPendingPermits() {
        synchronized(lock) {
            if (permitTarget == appliedPermits) return
            if (leases.isNotEmpty()) return
            gate = Semaphore(permitTarget, true)
            appliedPermits = permitTarget
            AppLog.i("heavy gate permits=$permitTarget")
        }
    }

    private fun ensureWatcher() {
        if (watcherStarted) return
        synchronized(lock) {
            if (watcherStarted) return
            watcherStarted = true
        }
        Thread({
            while (true) {
                try {
                    Thread.sleep(15_000)
                    val now = System.currentTimeMillis()
                    for ((id, lease) in leases.entries) {
                        if (now - lease.startedAt <= lease.leaseMs) continue
                        if (leases.remove(id) == null) continue
                        leaked++
                        poisoned = true
                        gate.release()
                        AppLog.e(
                            "heavy slot lease expired after ${(now - lease.startedAt) / 1000}s; " +
                                "recycled (leaked=$leaked)"
                        )
                    }
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (t: Throwable) {
                    AppLog.w("heavy gate watchdog: ${t.message}")
                }
            }
        }, "heavy-gate-watchdog").apply { isDaemon = true }.start()
    }
}
