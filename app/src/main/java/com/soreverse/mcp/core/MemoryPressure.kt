package com.soreverse.mcp.core

import android.app.ActivityManager
import android.content.Context

/**
 * 内存压力守卫 —— 借鉴上游 SOMCP #63 修复思路：
 * 系统内存不足时停止继续大块分配，避免 OOM 崩溃 / 触发 LMK 杀进程。
 *
 * 塔菲的分析引擎会把整个 SO/APK 读进内存（workspace.data），APK 上限 512MiB，
 * 在低端机或系统内存压力下直接分配会 OOM。大块读取前调用 [guardAllocation]：
 * 返回 null 表示可继续，否则返回可直接展示的拒绝原因（MCP err / UI 提示通用）。
 */
object MemoryPressure {

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
        if (systemLowMemory(context)) {
            return "系统内存不足（low memory），已停止加载${what}以避免崩溃。请关闭部分后台应用后重试"
        }
        if (sizeBytes > 0L) {
            val rt = Runtime.getRuntime()
            val max = rt.maxMemory()
            val headroom = max - (rt.totalMemory() - rt.freeMemory())
            // 余量按 0.8 打折：GC 后可回收空间并不总能及时释放，且 native 解析（LIEF/xanso）
            // 还会产生额外副本，实际峰值需求高于文件大小本身。
            if (sizeBytes > headroom * 0.8) {
                val mb = sizeBytes / 1024 / 1024
                val room = headroom / 1024 / 1024
                return "加载${what}约需 ${mb}MiB，超过当前堆余量（可用约 ${room}MiB / 上限 ${max / 1024 / 1024}MiB），已停止以防 OOM"
            }
        }
        return null
    }
}
