package top.wkbin.taixu.harness.bot

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.database.HarnessSessionEntity
import top.wkbin.taixu.core.database.HarnessSessionRepository
import top.wkbin.taixu.core.database.repository.BotAssignmentRepository
import top.wkbin.taixu.core.database.repository.GroupChatRepository
import top.wkbin.taixu.core.database.repository.PersonaBotRepository
import top.wkbin.taixu.core.model.ApprovalMode
import top.wkbin.taixu.core.model.SessionRunState
import top.wkbin.taixu.core.model.bot.BotAssignment
import top.wkbin.taixu.core.model.bot.BotAssignmentRequest
import top.wkbin.taixu.core.model.bot.BotAssignmentStatus
import top.wkbin.taixu.core.model.bot.BotWorkBatch
import top.wkbin.taixu.core.model.bot.BotWorkBatchStatus
import top.wkbin.taixu.harness.groupchat.GroupTurnOutcome
import top.wkbin.taixu.harness.HarnessPathResolver
import top.wkbin.taixu.harness.session.SessionControl

fun interface BotAssignmentExecutor {
    suspend fun execute(task: BotAssignment, request: BotAssignmentRequest, admitted: suspend (String?) -> Unit): GroupTurnOutcome
}

/** Each task owns one native root session. Later instructions become durable turns in that session. */
class BotAssignmentRuntime(
    private val repository: BotAssignmentRepository,
    private val groups: GroupChatRepository,
    private val bots: PersonaBotRepository,
    private val sessions: HarnessSessionRepository,
    private val control: SessionControl,
    private val tracker: BotAssignmentTracker,
    private val executor: BotAssignmentExecutor,
    private val logger: AppLogger,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val modelManager: BotModelManager? = null,
) {
    private val pump = AssignmentRequestPump(repository, scope, logger, ::execute)
    private val maintenanceLock = Mutex()
    private var maintenanceJob: Job? = null

    suspend fun start() {
        pump.start()
        runCatching { repository.reconcileWaitingBatches() }
            .onFailure { logger.w("[BotAssignment] 批次收敛失败: ${it.message}") }
        maintenanceLock.withLock {
            if (maintenanceJob?.isActive == true) return@withLock
            maintenanceJob = scope.launch {
                while (true) {
                    delay(BATCH_SWEEP_MS)
                    runCatching { repository.reconcileWaitingBatches() }
                        .onFailure { logger.w("[BotAssignment] 批次收敛失败: ${it.message}") }
                }
            }
        }
    }

    suspend fun create(botId: String, groupId: String, requesterId: String, sourceSessionId: String, instruction: String,
        writePaths: List<String> = emptyList(), dependsOn: List<String> = emptyList(), workId: String? = null): BotAssignment {
        val create = suspend { createTask(botId, groupId, requesterId, sourceSessionId, instruction, writePaths, dependsOn, workId) }
        return modelManager?.withBotLock(botId, create) ?: create()
    }

    suspend fun batchSize(batchId: String): Int = repository.batchTaskCount(batchId)

    private suspend fun createTask(botId: String, groupId: String, requesterId: String, sourceSessionId: String, instruction: String,
        writePaths: List<String>, dependsOn: List<String>, workId: String?): BotAssignment {
        start()
        require(instruction.isNotBlank()) { "任务内容不能为空" }
        if (workId != null) {
            require(repository.batchTaskCount(workId) < BotWorkBatch.MAX_TASKS) {
                "本批任务已达上限（${BotWorkBatch.MAX_TASKS}），请等待已派任务结束或拆到下一轮"
            }
        }
        val bot = requireNotNull(bots.getBot(botId)) { "执行成员不存在" }
        val source = requireNotNull(sessions.findById(sourceSessionId)) { "来源会话不存在" }
        require(groups.getMembers(groupId).any { it.id == requesterId }) { "请求者不在群内" }
        require(groups.getMembers(groupId).any { it.id == botId }) { "执行成员不在群内" }
        val workspace = source.workspace.ifBlank { "/workspace" }
        require(writePaths.all { HarnessPathResolver().isWithinWorkspace(it, workspace) }) {
            "写入路径必须在来源工作区 $workspace 内；请使用工作区相对路径或该工作区的绝对路径，不能使用 /root"
        }
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val batch = workId?.let {
            BotWorkBatch(
                id = it,
                groupId = groupId,
                requesterBotId = requesterId,
                requesterSessionId = sourceSessionId,
                originOperationId = it,
                revision = 1,
                status = BotWorkBatchStatus.WAITING_CHILDREN,
                createdAt = now,
                updatedAt = now,
            )
        }
        val task = BotAssignment(id, groupId, botId, bot.name, instruction.lineSequence().first().take(60), instruction,
            BotAssignmentStatus.QUEUED, writePaths, now, now, sessionId = UUID.randomUUID().toString(),
            reportToBotId = requesterId, independent = true, dependsOn = dependsOn, batchId = workId)
        val session = HarnessSessionEntity(
            id = requireNotNull(task.sessionId), title = "${bot.name} · ${task.title}", createdAt = now, updatedAt = now,
            modelId = bot.modelId,
            modelVariant = bot.modelVariant,
            // Empty is a persisted workspace identity too; the environment maps it to /workspace.
            workspace = source.workspace, projectType = source.projectType,
            approvalMode = ApprovalMode.FULL_ACCESS.id, executionTargetJson = source.executionTargetJson,
        )
        repository.createIndependent(task, session, BotAssignmentRequest("initial:$id", id, instruction, now), batch)
        return task
    }

    suspend fun continueTask(id: String, groupId: String, instruction: String, requestId: String = UUID.randomUUID().toString()): String {
        start()
        require(instruction.isNotBlank()) { "请填写补充要求" }
        val task = requireNotNull(repository.get(id)) { "任务不存在" }
        require(task.independent && task.groupId == groupId) { "该任务不属于当前群聊或没有独立会话" }
        val sessionId = requireNotNull(task.sessionId)
        requireNotNull(sessions.findById(sessionId)) { "原任务会话已删除，不能猜测其他会话继续" }
        // A blocked/interrupted session may have been resumed independently by native recovery.
        if (!task.status.isActive) {
            control.prepareRemoteSession(sessionId)
            require(control.sessionRunStates.value[sessionId] !in BUSY_STATES) { "原会话仍在执行，请先核对运行状态" }
        }
        repository.enqueueRequest(BotAssignmentRequest(requestId, id, instruction.trim(), System.currentTimeMillis()))
        return requestId
    }

    suspend fun awaitRequest(id: String): BotAssignmentRequest = repository.observeRequest(id).first {
        it == null || it.status !in setOf(BotAssignmentRequest.PENDING, BotAssignmentRequest.SUBMITTING, BotAssignmentRequest.ADMITTED)
    } ?: error("补充要求已清除")

    suspend fun taskForId(id: String) = repository.get(id)
    suspend fun cancelGroup(groupId: String) {
        repository.observeForGroup(groupId).first().filter { it.independent }.forEach { task ->
            if (task.status.isActive || control.sessionRunStates.value[task.sessionId] in BUSY_STATES) {
                tracker.cancel(task.id)
                task.sessionId?.let(control::cancel)
            }
        }
    }
    suspend fun history(id: String) = repository.requestsFor(id)
    suspend fun taskForSession(sessionId: String) = repository.assignmentForSession(sessionId)
    suspend fun report(sessionId: String, reason: String?, artifacts: List<String>) = repository.reportBlock(sessionId, reason, artifacts)

    private suspend fun execute(request: BotAssignmentRequest) {
        if (!repository.claimRequest(request.id, System.currentTimeMillis())) return
        val task = repository.get(request.assignmentId) ?: return
        tracker.track(task, currentCoroutineContext()[Job])
        try {
            val sessionId = requireNotNull(task.sessionId)
            requireNotNull(sessions.findById(sessionId)) { "独立任务会话已删除" }
            control.prepareRemoteSession(sessionId)
            check(control.sessionRunStates.value[sessionId] !in BUSY_STATES) { "原任务会话仍在运行，未重复提交" }
            // Repair legacy source-model inheritance and interrupted selection synchronizations before resuming.
            val syncModel: suspend () -> Unit = {
                val bot = requireNotNull(bots.getBot(task.botId)) { "执行成员不存在" }
                sessions.setModelSelection(sessionId, bot.modelId, bot.modelVariant, System.currentTimeMillis())
            }
            if (modelManager != null) modelManager.withBotLock(task.botId, syncModel) else syncModel()
            val result = executor.execute(task, request) { nativeId ->
                check(repository.admitRequest(request.id, nativeId)) { "任务已停止或受理回执未保存" }
            }
            if (!result.uncertain) withTimeout(10_000) {
                control.sessionRunStates.first { it[sessionId] !in BUSY_STATES }
            }
            val state = control.sessionRunStates.value[sessionId]
            val uncertain = result.uncertain || state == SessionRunState.IDLE
            val failed = result.failed || state == SessionRunState.FAILED
            repository.finishRequest(request.id, !failed, uncertain, result.text, if (failed || uncertain) result.text else null)
        } catch (cancelled: CancellationException) {
            task.sessionId?.let(control::cancel)
            withContext(NonCancellable) {
                repository.finishRequest(request.id, false, true, null, "执行被中断，结果待核对")
            }
            throw cancelled
        } catch (failure: Exception) {
            logger.e("[BotAssignment] 独立任务执行结果待核对", failure)
            repository.finishRequest(request.id, false, true, null, "执行结果待核对：${failure.message}")
        } finally {
            tracker.untrack(task.id)
        }
    }

    companion object {
        private val BUSY_STATES = setOf(SessionRunState.RUNNING, SessionRunState.WAITING_APPROVAL)
        private const val BATCH_SWEEP_MS = 2_000L
    }
}
