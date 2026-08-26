package com.soreverse.mcp.core

/**
 * 堆内存守卫（借鉴上游 SOMCP 1.0.20 MemoryGuard）。
 *
 * 分析前估算堆余量：SOMCP/塔菲把整个 ELF/APK 读进 ByteArray 后还要保留多份
 * 解析副本（LIEF native 解析、rizin 缓冲、节区恢复），受限设备上大文件会直接
 * OOM 崩溃。此守卫在入口处提前拒绝并给出明确错误（INSUFFICIENT_MEMORY），
 * 而不是让进程崩掉。
 *
 * 接线点（读取完整文件进内存的入口）：
 *  - EngineRuntimeSources.open（so_open / UI 打开 / AI 深度分析）
 *  - EngineRuntimeSources.analyzeApk（apk_analyze）
 *  - Blutter inspect/analyze 路径
 */
object MemoryGuard {

    /** 一次完整 ELF/APK 解析需要的瞬时副本数（输入 + LIEF + rizin/恢复）。 */
    const val DEFAULT_MULTIPLICITY = 3

    /** 解析完成后保持应用响应的额外余量（MiB）。 */
    const val DEFAULT_RESERVE_MIB = 48L

    /** 绝对下限（MiB）：低于此值直接拒绝。 */
    const val MIN_HEADROOM_MIB = 16L

    private const val MIB = 1024L * 1024L

    /** 进程还能分配的堆空间（MiB），OOM 前余量。 */
    fun heapHeadroomMiB(): Long {
        val runtime = Runtime.getRuntime()
        val headroom = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        return headroom.coerceAtLeast(0L) / MIB
    }

    /**
     * 需要在内存中持有 [requiredBytes] 输入的分析开始前调用。
     * [what] 用于错误消息。估算需求（输入 × [DEFAULT_MULTIPLICITY] +
     * [DEFAULT_RESERVE_MIB]）超过可用余量、或已低于 [MIN_HEADROOM_MIB] 时抛
     * [InsufficientMemoryException]。
     */
    fun ensureAnalysisMemory(requiredBytes: Long, what: String) {
        val requirementMiB = requiredBytes.coerceAtLeast(0L) * DEFAULT_MULTIPLICITY / MIB
        val headroomMiB = heapHeadroomMiB()
        if (headroomMiB < MIN_HEADROOM_MIB || headroomMiB < requirementMiB + DEFAULT_RESERVE_MIB) {
            val runtime = Runtime.getRuntime()
            val maxMiB = runtime.maxMemory() / MIB
            val usedMiB = (runtime.totalMemory() - runtime.freeMemory()) / MIB
            throw InsufficientMemoryException(
                "$what 需要约 $requirementMiB MiB 堆余量，但当前仅 ~$headroomMiB MiB 可用" +
                    "（堆上限 ~$maxMiB MiB，已用 ~$usedMiB MiB）。请关闭其他已打开的 SO 工作区、" +
                    "停止模拟会话后重试，或分析更小的文件。"
            )
        }
    }

    /** 非抛出版本：返回 null=可继续，否则返回错误消息。 */
    fun checkAnalysisMemory(requiredBytes: Long, what: String): String? {
        val requirementMiB = requiredBytes.coerceAtLeast(0L) * DEFAULT_MULTIPLICITY / MIB
        val headroomMiB = heapHeadroomMiB()
        if (headroomMiB < MIN_HEADROOM_MIB || headroomMiB < requirementMiB + DEFAULT_RESERVE_MIB) {
            return "$what 需要约 $requirementMiB MiB 堆余量，但当前仅 ~$headroomMiB MiB 可用；" +
                "请关闭其他工作区/停止模拟后重试，或分析更小的文件。"
        }
        return null
    }
}

/**
 * 分析因堆余量不足无法开始时抛出（[MemoryGuard.ensureAnalysisMemory]）。
 * 由 EngineRuntime.guarded 映射为 INSUFFICIENT_MEMORY MCP 错误，
 * 调用方看到明确消息而不是 OutOfMemoryError 崩溃。
 */
class InsufficientMemoryException(message: String) : IllegalStateException(message)
