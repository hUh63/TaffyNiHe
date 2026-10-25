package com.soreverse.mcp.mcp

import android.content.Context
import com.soreverse.mcp.core.SelfArtifactGuard
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.core.err
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * 工具调用前置策略：**MCP 服务端与 `taffy_script` 脚本引擎共用同一套**。
 *
 * 之前脚本里的 `tool(name, args)` 只走了 heavy 闸门，其余策略全部绕过，因此脚本可以：
 * - 调用用户在设置里禁用的工具（`disabledTools` 形同虚设）；
 * - 调用被「阻止破坏性工具」拦下的写/签/注入类工具；
 * - 绕过每分钟限频；
 * - **绕过自身 artifact 守卫**（`SelfArtifactGuard`）—— 能读写塔菲自己的 APK / 内置 SO，
 *   直接破坏完整性校验。这条是安全问题，不是体验问题。
 *
 * 收敛到一处之后，服务端与脚本的行为才真正一致。
 */
internal object ToolPolicy {
    private const val RATE_WINDOW_MS = 60_000L

    private val rateLock = Any()
    private val rateByTool = HashMap<String, ArrayDeque<Long>>()

    /** 每分钟限频（按工具名）。limit <= 0 表示不限。 */
    fun tryAcquireRate(name: String, limit: Int): Boolean = synchronized(rateLock) {
        if (limit <= 0) return@synchronized true
        val now = System.currentTimeMillis()
        val stamps = rateByTool.getOrPut(name) { ArrayDeque() }
        while (stamps.firstOrNull()?.let { now - it > RATE_WINDOW_MS } == true) stamps.removeFirst()
        if (stamps.size >= limit) return@synchronized false
        stamps.addLast(now)
        true
    }

    fun resetRate(name: String? = null) = synchronized(rateLock) {
        if (name == null) rateByTool.clear() else rateByTool.remove(name)
    }

    fun isDisabled(raw: String, name: String): Boolean =
        raw.isNotBlank() && raw.split(',').any { it.trim() == name }

    /**
     * 统一前置校验；返回非 null 表示这次调用应被拒绝（调用方直接回该 JSON 信封）。
     *
     * @param source "server" / "script"，只影响错误文案里标明来源。
     */
    fun precheck(
        context: Context,
        name: String,
        args: JSONObject,
        source: String,
    ): JSONObject? {
        val settings = SettingsStore(context)
        if (name.isNotBlank() && isDisabled(settings.disabledTools, name)) {
            return err(
                "TOOL_DISABLED",
                "Tool $name is disabled by settings (disabledTools).",
                "source", source,
            )
        }
        if (settings.blockDestructiveTools && ToolSafety.isDestructive(name)) {
            return err(
                "DESTRUCTIVE_BLOCKED",
                "Tool $name is destructive (write/delete/sign) and blocked by settings.blockDestructiveTools.",
                "source", source,
            )
        }
        val rateLimit = settings.toolCallRateLimitPerMin
        if (rateLimit > 0 && !tryAcquireRate(name, rateLimit)) {
            return err(
                "RATE_LIMITED",
                "Tool $name hit the per-minute rate limit ($rateLimit/min). Retry shortly.",
                "source", source,
            )
        }
        // 自身 artifact 守卫：任何来源都不允许引用塔菲自己的 APK / 内置 SO
        SelfArtifactGuard.findSelfArg(context, args)?.let { return SelfArtifactGuard.forbidden(it, name) }
        return null
    }
}
