package top.wkbin.taixu.harness.groupchat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import top.wkbin.taixu.core.database.HarnessRuntimeRepository
import top.wkbin.taixu.core.database.task.AgentTaskRepository
import top.wkbin.taixu.core.database.task.AgentTaskStatus
import top.wkbin.taixu.core.model.SessionRunState
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.TaskDelivery
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.session.SessionControl

/** Session flags can belong to a predecessor/successor. Only the admitted task owns this reply. */
internal class GroupReplyTaskTracker(
    private val tasks: AgentTaskRepository,
    private val runtime: HarnessRuntimeRepository,
    private val control: SessionControl,
) {
    data class Snapshot(val status: String, val startedAt: Long?, val anchorId: String?) {
        val queued get() = status == AgentTaskStatus.QUEUED || status == AgentTaskStatus.RECOVERING
        val completed get() = status == AgentTaskStatus.COMPLETED
        val interrupted get() = status == AgentTaskStatus.CANCELLED || status == AgentTaskStatus.SUSPENDED
        val failed get() = status == AgentTaskStatus.FAILED || interrupted
        val state get() = if (status == AgentTaskStatus.WAITING_APPROVAL) SessionRunState.WAITING_APPROVAL
            else SessionRunState.RUNNING

        fun messages(all: List<HarnessMessage>): List<HarnessMessage> {
            if (queued) return emptyList()
            val anchor = anchorId?.let { id -> all.indexOfFirst { it.id == id } } ?: -1
            return if (anchor >= 0) all.drop(anchor + 1)
            else all.filter { it.createdAt >= (startedAt ?: Long.MAX_VALUE) }
        }
    }

    fun observe(sessionId: String, taskId: String): Flow<Snapshot?> = tasks.observeTask(taskId).map { task ->
        if (task == null) return@map null
        check(task.sessionId == sessionId) { "群回复任务不属于当前会话" }
        val operation = task.operationId?.let { runtime.findOperation(it) }
        check(operation == null || operation.sessionId == sessionId) { "群回复运行归属不匹配" }
        Snapshot(task.status, operation?.startedAt ?: task.startedAt, operation?.startLeafId)
    }

    suspend fun completed(sessionId: String, taskId: String): GroupReplyObserver.ReplyOutcome {
        val messages = control.persistedMessages(sessionId)
        val report = messages.filterIsInstance<TaskDelivery>().lastOrNull { it.report.taskId == taskId }?.report
            ?: error("群回复任务已完成，但缺少持久化交付记录；结果待核对")
        check(report.sessionId == sessionId)
        val candidate = report.assistantClaimEntryId?.let { id ->
            messages.filterIsInstance<AssistantText>().firstOrNull { it.id == id }
                ?: error("群回复交付正文缺失；结果待核对")
        }
        val calls = report.observations.map { it.callEntryId }.toSet()
        val results = report.observations.mapNotNull { it.resultEntryId }.toSet()
        val owned = messages.filter { it is ToolCall && it.id in calls || it is ToolResult && it.id in results }
        return GroupReplyObserver.ReplyOutcome(
            candidate, steps = groupReplyToolSteps(owned, emptySet(), Long.MIN_VALUE),
            reasoning = candidate?.reasoning.orEmpty(),
        )
    }

    suspend fun cancelIfActive(sessionId: String, taskId: String) {
        val task = tasks.find(taskId) ?: return
        if (task.sessionId == sessionId && task.status in setOf(AgentTaskStatus.RUNNING, AgentTaskStatus.WAITING_APPROVAL) &&
            runtime.listActiveOperations(sessionId).any { it.id == task.operationId }) control.cancel(sessionId)
    }
}
