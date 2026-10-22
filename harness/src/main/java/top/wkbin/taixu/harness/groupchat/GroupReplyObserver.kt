package top.wkbin.taixu.harness.groupchat

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withTimeoutOrNull
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.model.SessionRunState
import top.wkbin.taixu.core.model.ChunkedText
import top.wkbin.taixu.core.model.bot.Bot
import top.wkbin.taixu.core.model.bot.GroupToolStep
import top.wkbin.taixu.harness.AssistantText
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.ToolCall
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.session.SessionControl

internal data class GroupReplyProgress(
    val contentText: ChunkedText,
    val reasoning: String,
    val steps: List<GroupToolStep>,
) {
    constructor(content: String, reasoning: String, steps: List<GroupToolStep>) :
        this(ChunkedText.of(content), reasoning, steps)
    val content: String get() = contentText.toString()
}

/** Projects provider output without mixing reasoning, transient status, and answer text. */
internal class GroupReplyObserver(
    private val sessionControl: SessionControl,
    private val logger: AppLogger,
    private val taskTracker: GroupReplyTaskTracker? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    internal data class ReplyOutcome(
        val candidate: AssistantText?,
        val firstTokenTimedOut: Boolean = false,
        val steps: List<GroupToolStep> = emptyList(),
        val replyTimedOut: Boolean = false,
        val reasoning: String = "",
        val stalled: Boolean = false,
        val failed: Boolean = false,
        val interrupted: Boolean = false,
    )

    suspend fun awaitReply(
        sessionId: String,
        bot: Bot,
        baseline: Set<String>,
        startedAt: Long,
        baselineOutputRevision: Long = 0L,
        completionOnly: Boolean = false,
        taskId: String? = null,
        onProgress: suspend (GroupReplyProgress) -> Unit,
    ): ReplyOutcome = coroutineScope {
        var latestWithText: AssistantText? = null
        var latestCandidate: AssistantText? = null
        var latestSteps: List<GroupToolStep> = emptyList()
        var reasoning = ""
        var seenRunning = false
        var lastProgress: GroupReplyProgress? = null
        var firstTokenDeadline = startedAt + FIRST_TOKEN_TIMEOUT_MS
        var deadline = startedAt + REPLY_TIMEOUT_MS
        var previous = startedAt
        var previousState: SessionRunState? = null
        var receivedOutput = false
        var progressDeadline = startedAt + FIRST_TOKEN_TIMEOUT_MS
        var previousOutput = emptyList<Pair<ChunkedText, String?>>()
        var previousSteps = emptyList<GroupToolStep>()
        var outputRevision = baselineOutputRevision
        var taskStarted = false
        val messageFlow = sessionControl.messagesForSession(sessionId)
        val taskFlow = if (taskId != null && taskTracker != null) taskTracker.observe(sessionId, taskId) else flowOf(null)
        val events = combine(messageFlow, sessionControl.sessionRunStates, sessionControl.modelOutputRevisions, taskFlow) { messages, states, revisions, task ->
            ReplySnapshot(messages, states[sessionId] ?: SessionRunState.IDLE, revisions[sessionId] ?: 0L, task)
        }.distinctUntilChanged().conflate().produceIn(this)
        try {
            var snapshot = events.receive()
            while (true) {
                val now = clock()
                val task = snapshot.task
                if (taskId != null && taskTracker != null) {
                    if (task == null) return@coroutineScope ReplyOutcome(null, failed = true, interrupted = true)
                    if (task.completed) return@coroutineScope taskTracker.completed(sessionId, taskId)
                    if (task.failed) return@coroutineScope ReplyOutcome(null, failed = true, interrupted = task.interrupted)
                    // Queue time belongs to the preceding task, and consumes none of our output budget.
                    if (task.queued) {
                        snapshot = events.receive()
                        continue
                    }
                    if (!taskStarted) {
                        val began = task.startedAt ?: now
                        deadline = began + REPLY_TIMEOUT_MS
                        firstTokenDeadline = now + FIRST_TOKEN_TIMEOUT_MS
                        progressDeadline = firstTokenDeadline
                        previous = now
                        outputRevision = snapshot.revision
                        taskStarted = true
                    }
                }
                val messages = task?.messages(snapshot.messages) ?: snapshot.messages
                val state = task?.state ?: snapshot.state
                val revision = snapshot.revision
                if (revision > outputRevision) {
                    receivedOutput = true
                    progressDeadline = now + FIRST_TOKEN_TIMEOUT_MS
                    outputRevision = revision
                }
                if (previousState == SessionRunState.WAITING_APPROVAL) {
                    deadline += (now - previous).coerceAtLeast(0)
                    firstTokenDeadline = now + FIRST_TOKEN_TIMEOUT_MS
                    progressDeadline = now + FIRST_TOKEN_TIMEOUT_MS
                }
                previous = now
                previousState = state
                val candidates = messages.filterIsInstance<AssistantText>()
                    .filter { it.id !in baseline && it.createdAt >= startedAt - CLOCK_SKEW_MS }
                candidates.lastOrNull()?.let { candidate ->
                    latestCandidate = candidate
                    if (candidate.textContent.hasText) latestWithText = candidate
                }
                reasoning = candidates.mapNotNull { it.reasoning?.takeIf(String::isNotBlank) }
                    .joinToString("\n\n").take(MAX_REASONING_CHARS)
                latestSteps = groupReplyToolSteps(messages, baseline, startedAt)
                // Observe actual output, including reasoning beyond the bounded UI preview.
                val output = candidates.map { it.textContent to it.reasoning }
                if ((output != previousOutput || latestSteps != previousSteps) &&
                    (output.any { it.first.hasText || !it.second.isNullOrBlank() } || latestSteps.isNotEmpty())) {
                    receivedOutput = true
                    progressDeadline = now + FIRST_TOKEN_TIMEOUT_MS
                }
                previousOutput = output
                previousSteps = latestSteps
                val progress = GroupReplyProgress(latestWithText?.textContent ?: ChunkedText.EMPTY, reasoning, latestSteps)
                if (progress != lastProgress) {
                    onProgress(progress)
                    lastProgress = progress
                }
                if (state == SessionRunState.RUNNING || state == SessionRunState.WAITING_APPROVAL) seenRunning = true
                // A persisted text response can still consume follow-ups and continue this run.
                // The background watcher may attach long after the turn started, so it waits for
                // evidence that the turn ran or produced output before treating IDLE as settled.
                // Settled must win over a deadline: a completed turn may attach after the deadline.
                val settled = state in TERMINAL_STATES && (
                    if (completionOnly) seenRunning || latestCandidate != null || latestSteps.isNotEmpty()
                    else seenRunning || now - startedAt >= START_GRACE_MS
                    )
                if (settled) return@coroutineScope ReplyOutcome(latestWithText ?: latestCandidate,
                    steps = latestSteps, reasoning = reasoning, failed = state == SessionRunState.FAILED,
                    interrupted = state == SessionRunState.IDLE)
                if (state != SessionRunState.WAITING_APPROVAL && now >= deadline) {
                    cancel(sessionId, taskId)
                    return@coroutineScope ReplyOutcome(latestWithText ?: latestCandidate, steps = latestSteps, replyTimedOut = true, reasoning = reasoning)
                }
                if (!completionOnly && state != SessionRunState.WAITING_APPROVAL && !receivedOutput && now >= firstTokenDeadline) {
                    logger.w("[GroupReplyObserver] Bot ${bot.name} 等待首 token 超时，已请求取消")
                    cancel(sessionId, taskId)
                    return@coroutineScope ReplyOutcome(null, firstTokenTimedOut = true, steps = latestSteps, reasoning = reasoning)
                }
                val toolRunning = latestSteps.any { it.success == null }
                if (!completionOnly && state != SessionRunState.WAITING_APPROVAL && receivedOutput && !toolRunning && now >= progressDeadline) {
                    logger.w("[GroupReplyObserver] Bot ${bot.name} 连续 ${FIRST_TOKEN_TIMEOUT_MS / 1000} 秒无新输出，已请求取消")
                    cancel(sessionId, taskId)
                    return@coroutineScope ReplyOutcome(latestWithText ?: latestCandidate, steps = latestSteps,
                        reasoning = reasoning, stalled = true)
                }
                val wakeAt = when {
                    state == SessionRunState.WAITING_APPROVAL -> Long.MAX_VALUE
                    completionOnly -> deadline
                    state in TERMINAL_STATES && !seenRunning -> startedAt + START_GRACE_MS
                    !receivedOutput -> minOf(deadline, firstTokenDeadline)
                    !toolRunning -> minOf(deadline, progressDeadline)
                    else -> deadline
                }
                // No polling: wait for model/state changes, with timers only for actual deadlines.
                val next = withTimeoutOrNull((wakeAt - clock()).coerceAtLeast(1)) { events.receive() }
                // A receive racing its timeout can discard an event; re-read the authoritative flows.
                snapshot = next ?: snapshot.copy(messages = messageFlow.value,
                    state = sessionControl.sessionRunStates.value[sessionId] ?: SessionRunState.IDLE,
                    revision = sessionControl.modelOutputRevisions.value[sessionId] ?: 0L)
            }
            @Suppress("UNREACHABLE_CODE")
            ReplyOutcome(latestWithText ?: latestCandidate, steps = latestSteps, reasoning = reasoning)
        } finally {
            events.cancel()
        }
    }

    private suspend fun cancel(sessionId: String, taskId: String?) {
        if (taskId != null && taskTracker != null) taskTracker.cancelIfActive(sessionId, taskId)
        else sessionControl.cancel(sessionId)
    }

    private data class ReplySnapshot(
        val messages: List<HarnessMessage>, val state: SessionRunState, val revision: Long,
        val task: GroupReplyTaskTracker.Snapshot?,
    )

    private companion object {
        const val REPLY_TIMEOUT_MS = GroupReplyTimeoutPolicy.REPLY_TIMEOUT_MS
        const val FIRST_TOKEN_TIMEOUT_MS = GroupReplyTimeoutPolicy.OUTPUT_WAIT_MS
        const val START_GRACE_MS = 2_000L
        const val CLOCK_SKEW_MS = 2_000L
        const val MAX_REASONING_CHARS = 32_000
        val TERMINAL_STATES = setOf(SessionRunState.COMPLETED, SessionRunState.FAILED, SessionRunState.IDLE)
    }
}

internal fun groupReplyToolSteps(messages: List<HarnessMessage>, baseline: Set<String>, startedAt: Long): List<GroupToolStep> {
    val earliest = if (startedAt == Long.MIN_VALUE) Long.MIN_VALUE else startedAt - 2_000L
    val results = messages.filterIsInstance<ToolResult>().groupBy { it.toolCallId }
    return messages.filterIsInstance<ToolCall>()
        .filter { it.id !in baseline && it.createdAt >= earliest }
        .map { call ->
            val settled = results[call.id]?.lastOrNull { !it.awaitingApproval }
            val name = call.rawToolName ?: call.tool.name
            GroupToolStep(call.id, name, settled?.success, settled?.durationMs, toolStepDetail(name, call.args))
        }
}
