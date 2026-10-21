package top.wkbin.taixu.harness.session

import kotlinx.coroutines.flow.StateFlow
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.PendingMessage
import top.wkbin.taixu.harness.QueuedPrompt
import top.wkbin.taixu.harness.checkpoint.CheckpointMeta
import top.wkbin.taixu.harness.checkpoint.RewindPlan
import top.wkbin.taixu.harness.checkpoint.RewindResult
import top.wkbin.taixu.harness.checkpoint.RewindScope
import top.wkbin.taixu.harness.mcp.McpWorkspaceRecommender
import top.wkbin.taixu.harness.queue.PromptQueue

/** Foreground navigation/editing conveniences layered over the same session runtime. */
interface InteractiveSessionControl : SessionControl {
    val messages: StateFlow<List<HarnessMessage>>
    val running: StateFlow<Boolean>
    val workspace: StateFlow<String>
    val projectType: StateFlow<String>
    val mcpRecommendations: StateFlow<List<McpWorkspaceRecommender.Recommendation>>
    val error: StateFlow<String?>
    val status: StateFlow<String?>
    val thinkingLive: StateFlow<Boolean>
    val pendingMessages: StateFlow<List<PendingMessage>>
    val queuedPrompts: StateFlow<List<QueuedPrompt>>
    fun enableRecommendedMcp(presetId: String)
    fun dismissMcpRecommendation(presetId: String)
    fun sessionCheckpoints(sessionId: String): List<CheckpointMeta>
    fun prepareRewind(sessionId: String, turn: Int, scope: RewindScope): RewindPlan
    suspend fun commitRewind(plan: RewindPlan, workspace: String = ""): RewindResult
    suspend fun undoRewind(sessionId: String, workspace: String = ""): RewindResult?
    suspend fun newSession(title: String, workspace: String = "", projectType: String = ""): String
    suspend fun loadSession(id: String)
    suspend fun renameSession(id: String, title: String)
    fun regenerateLast(targetSessionId: String? = null)
    fun retryToolCall(toolCallId: String, targetSessionId: String? = null)
    suspend fun activateBranch(leafId: String?, targetSessionId: String? = null): Boolean
    fun truncateAndResend(userMessageId: String, newText: String, targetSessionId: String? = null)
    suspend fun deleteMessage(messageId: String, targetSessionId: String? = null)
    fun removePendingMessage(index: Int, targetSessionId: String? = null)
    fun removeQueuedPrompt(queue: PromptQueue, index: Int, targetSessionId: String? = null)
    fun clearPendingMessages(targetSessionId: String? = null)
    fun clearError(targetSessionId: String? = null)
}
