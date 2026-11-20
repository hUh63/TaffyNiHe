package com.soreverse.mcp.core

import android.app.ActivityManager
import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * 内存压力守卫 —— 借鉴上游 SOMCP #63 修复思路：
 * 系统内存不足时停止继续大块分配，避免 OOM 崩溃 / 触发 LMK 杀进程。
 *
 * 塔菲的分析引擎会把整个 SO/APK 读进内存（workspace.data），APK 上限 512MiB，
 * 在低端机或系统内存压力下直接分配会 OOM。大块读取前调用 [guardAllocation]：
 * 返回 null 表示可继续，否则返回可直接展示的拒绝原因（MCP err / UI 提示通用）。
 */
object MemoryPressure {

    /**
     * 「可重建缓存」的释放钩子（键 → 动作）。
     *
     * 分析引擎把整份 SO 的解析结果常驻在共享 RzCore 里（大文件几十 MB），这类数据
     * 随时可以重建，但不会自己让出来。内存紧张时先把它们丢掉，比直接拒绝用户操作好：
     * 丢掉之后重新 load 只是慢一点，而拒绝就是功能不可用。
     */
    private val reclaimers = ConcurrentHashMap<String, () -> Unit>()

    /** 按 key 注册（重复注册是幂等的，后注册的覆盖先注册的）。 */
    fun registerReclaimer(key: String, action: () -> Unit) {
        reclaimers[key] = action
    }

    /** 立即释放所有登记的缓存。 */
    fun reclaimAll() {
        for (action in reclaimers.values) runCatching { action() }
    }

    /** 系统级低内存标志（所有应用共享的物理内存状态，系统建议停止大分配）。 */
    fun systemLowMemory(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return runCatching {
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.lowMemory
        }.getOrDefault(false)
    }

    /** 本进程 Dalvik 堆已用比例 0..1（含不可及时回收的 GC 浮动空间，仅用于粗略判断）。 */
    fun heapUsedFraction(): Float {
        val rt = Runtime.getRuntime()
        val max = rt.maxMemory()
        if (max <= 0L) return 0f
        return (rt.totalMemory() - rt.freeMemory()).toFloat() / max.toFloat()
    }

    /**
     * 大块分配守卫。
     * @param sizeBytes 将要分配/加载的字节数（未知传 0，则只查系统低内存）
     * @param what 资源描述，用于拒绝文案（如 "SO 文件" / "APK"）
     * @return null = 允许分配；非 null = 拒绝原因（中文，可直接展示）
     */
    fun guardAllocation(context: Context, sizeBytes: Long, what: String): String? {
        // 自救只在「本来就要拒绝」时发生：丢掉共享 RzCore 之后下一次命令要重新 load +
        // 全量分析（大文件 3~10 秒），无脑在每次大分配前调用等于把性能砸掉。
        if (systemLowMemory(context)) {
            reclaimAll()
            if (systemLowMemory(context)) {
                return "系统内存不足（low memory），已停止加载${what}以避免崩溃。请关闭部分后台应用后重试"
            }
        }
        if (sizeBytes > 0L) {
            val rt = Runtime.getRuntime()
            val max = rt.maxMemory()
            // 余量按 0.8 打折：GC 后可回收空间并不总能及时释放，且 native 解析（LIEF/xanso）
            // 还会产生额外副本，实际峰值需求高于文件大小本身。
            fun headroom(): Long = max - (rt.totalMemory() - rt.freeMemory())
            if (sizeBytes > headroom() * 0.8) {
                reclaimAll()
                if (sizeBytes > headroom() * 0.8) {
                    val mb = sizeBytes / 1024 / 1024
                    val room = headroom() / 1024 / 1024
                    return "加载${what}约需 ${mb}MiB，超过当前堆余量（可用约 ${room}MiB / 上限 ${max / 1024 / 1024}MiB），已停止以防 OOM"
                }
            }
        }
        return null
    }
}
