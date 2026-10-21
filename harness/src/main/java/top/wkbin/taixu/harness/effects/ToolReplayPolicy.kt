package top.wkbin.taixu.harness.effects

import top.wkbin.taixu.harness.HarnessTool
import top.wkbin.taixu.harness.operation.ReplayPolicy

/** Recovery contract for external tool effects. */
object ToolReplayPolicy {
    fun forTool(tool: HarnessTool, rawToolName: String? = null): ReplayPolicy = when {
        rawToolName?.startsWith("mcp__") == true -> ReplayPolicy.NEVER
        tool in SAFE_TO_REPLAY -> ReplayPolicy.SAFE
        else -> ReplayPolicy.NEVER
    }

    private val SAFE_TO_REPLAY = setOf(
        HarnessTool.READ,
        HarnessTool.HISTORY_SEARCH,
        HarnessTool.HISTORY_READ,
    )
}
