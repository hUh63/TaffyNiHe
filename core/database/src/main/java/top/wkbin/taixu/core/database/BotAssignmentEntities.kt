package top.wkbin.taixu.core.database

import androidx.room.ColumnInfo
import androidx.room.Transaction
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import top.wkbin.taixu.core.model.bot.BotWorkBatch

@Entity(
    tableName = "bot_assignments",
    foreignKeys = [
        ForeignKey(
            entity = GroupChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["groupId", "createdAt"]),
        Index(value = ["status"]),
        Index(value = ["sessionId"]),
        Index(value = ["batchId", "status"]),
    ],
)
data class BotAssignmentEntity(
    @PrimaryKey val id: String,
    val groupId: String?,
    val botId: String,
    val botName: String,
    val title: String,
    val task: String,
    val status: String,
    val writePathsJson: String,
    val createdAt: Long,
    val startedAt: Long,
    val finishedAt: Long?,
    val summary: String?,
    val error: String?,
    val sessionId: String?,
    val laneName: String?,
    @ColumnInfo(defaultValue = "NULL") val reportToBotId: String? = null,
    @ColumnInfo(defaultValue = "0") val independent: Boolean = false,
    @ColumnInfo(defaultValue = "0") val revision: Int = 0,
    @ColumnInfo(defaultValue = "NULL") val activeRequestId: String? = null,
    @ColumnInfo(defaultValue = "'[]'") val dependsOnJson: String = "[]",
    @ColumnInfo(defaultValue = "NULL") val batchId: String? = null,
)

@Dao
interface BotAssignmentDao : GroupDeliveryDao, BotAssignmentRequestDao {
    @Query("SELECT * FROM bot_assignments WHERE botId = :botId AND independent = 1")
    suspend fun assignmentsForBot(botId: String): List<BotAssignmentEntity>

    @Query("SELECT * FROM bot_assignments WHERE sessionId = :sessionId AND independent = 1 LIMIT 1")
    suspend fun assignmentForSession(sessionId: String): BotAssignmentEntity?

    @Transaction
    suspend fun createIndependent(
        task: BotAssignmentEntity,
        session: HarnessSessionEntity,
        request: BotAssignmentRequestEntity,
        batch: BotWorkBatchEntity? = null,
    ) {
        require(task.independent && task.sessionId == session.id && request.assignmentId == task.id)
        val group = requireNotNull(task.groupId)
        require(deliveryMember(group, task.botId)) { "执行成员已移出群聊" }
        require(task.reportToBotId != null && deliveryMember(group, task.reportToBotId)) { "请求者已移出群聊" }
        if (batch == null) {
            require(task.batchId == null) { "批次实体缺失" }
        } else {
            require(task.batchId == batch.id) { "任务与批次不一致" }
            require(batch.revision >= 1) { "批次 revision 必须从 1 开始" }
            require(batchTaskCount(batch.id) < BotWorkBatch.MAX_TASKS) {
                "本批任务已达上限（${BotWorkBatch.MAX_TASKS}）"
            }
            if (insertBatchIfAbsent(batch) == -1L) bumpBatchRevision(batch.id, batch.updatedAt)
        }
        insertIndependentSession(session)
        insertIndependentAssignment(task)
        insertAssignmentRequest(request)
    }

    @Transaction
    suspend fun enqueueRequest(request: BotAssignmentRequestEntity) {
        val task = requireNotNull(findById(request.assignmentId)) { "任务不存在" }
        require(task.independent) { "旧版任务没有独立会话，请重新派发" }
        require(deliveryMember(requireNotNull(task.groupId), task.botId)) { "执行成员已移出群聊" }
        val existing = findAssignmentRequest(request.id)
        if (existing != null) {
            require(existing.assignmentId == request.assignmentId && existing.instruction == request.instruction) { "补充要求 ID 已被其他内容使用" }
            return
        }
        if (task.status == "INTERRUPTED") cancelAssignmentRequests(task.id)
        task.batchId?.let { bumpBatchRevision(it, System.currentTimeMillis()) }
        insertAssignmentRequest(request)
        queueAssignment(task.id)
    }

    @Transaction
    suspend fun claimRequest(id: String, now: Long): Boolean {
        val request = findAssignmentRequest(id)?.takeIf { it.status == "pending" } ?: return false
        val task = findById(request.assignmentId) ?: return false
        if (task.status != "QUEUED" || !task.independent) return false
        if (!deliveryMember(requireNotNull(task.groupId), task.botId) ||
            task.reportToBotId?.let { !deliveryMember(task.groupId, it) } == true) {
            cancelAssignment(task.id, now)
            return false
        }
        if (activateAssignment(task.id, id, now) != 1) return false
        check(claimAssignmentRequest(id) == 1)
        return true
    }

    @Transaction
    suspend fun finishRequest(id: String, success: Boolean, uncertain: Boolean, summary: String?, error: String?, now: Long) {
        val request = findAssignmentRequest(id) ?: return
        val task = findById(request.assignmentId) ?: return
        if (task.activeRequestId != id || task.status != "RUNNING") return
        val requestStatus = if (uncertain) "unknown" else if (success) "completed" else "failed"
        if (settleAssignmentRequest(id, requestStatus, summary, error) != 1) return
        val outcome = when {
            uncertain -> "INTERRUPTED"
            !success -> "FAILED"
            !request.blockedReason.isNullOrBlank() -> "BLOCKED"
            else -> "COMPLETED"
        }
        // Uncertain native effects require explicit resumption, even if a later instruction was queued.
        val status = if (!uncertain && queuedAssignmentRequests(task.id) > 0) "QUEUED" else outcome
        if (uncertain) settleInterruptedAssignmentMessages(task.id, "⚠️ 原执行结果待核对，未自动重跑")
        val detail = if (uncertain || !success) error else request.blockedReason
        settleAssignment(task.id, status, now, summary, detail)
        if (task.batchId == null) {
            stageAssignmentReport(task, status, now, summary, detail)
        } else if (status != "QUEUED") {
            settleBatchIfIdle(task.batchId, now)
        }
    }

    @Transaction
    suspend fun recoverRequests(now: Long) {
        interruptedAssignmentRequests().forEach { request ->
            finishRequest(request.id, false, true, null, "进程退出，原执行结果待核对；继续前先核验原会话和产物", now)
        }
    }

    @Transaction
    suspend fun cancelAssignment(id: String, now: Long) {
        val task = findById(id) ?: return
        if (!task.independent) {
            finish(id, "CANCELLED", now, null, "用户取消")
            return
        }
        cancelAssignmentRequests(id)
        if (task.status == "CANCELLED") return
        settleAssignment(id, "CANCELLED", now, task.summary, "用户取消")
        if (task.batchId != null) {
            settleBatchIfIdle(task.batchId, now)
        } else {
            val lastRequest = assignmentRequests(id).firstOrNull()?.id.orEmpty()
            stageAssignmentReport(task.copy(activeRequestId = "cancel:$lastRequest"), "CANCELLED", now, null, "用户取消")
        }
    }

    @Query("SELECT * FROM bot_assignments WHERE groupId = :groupId ORDER BY createdAt DESC")
    fun observeForGroup(groupId: String): Flow<List<BotAssignmentEntity>>

    @Query("SELECT * FROM bot_assignments WHERE groupId = :groupId AND status = 'RUNNING' ORDER BY createdAt ASC")
    suspend fun listRunning(groupId: String): List<BotAssignmentEntity>

    @Query("SELECT * FROM bot_assignments ORDER BY createdAt DESC LIMIT :limit")
    suspend fun listRecent(limit: Int): List<BotAssignmentEntity>

    @Query("SELECT * FROM bot_assignments WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): BotAssignmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(assignment: BotAssignmentEntity)

    @Query(
        "UPDATE bot_assignments SET status = :status, finishedAt = :finishedAt, " +
            "summary = :summary, error = :error WHERE id = :id",
    )
    suspend fun settleAssignment(id: String, status: String, finishedAt: Long, summary: String?, error: String?)

    @Transaction
    suspend fun finish(id: String, status: String, finishedAt: Long, summary: String?, error: String?) {
        val previous = findById(id) ?: return
        if (previous.status != "RUNNING") return
        settleAssignment(id, status, finishedAt, summary, error)
        stageAssignmentReport(previous, status, finishedAt, summary, error)
    }

    suspend fun stageAssignmentReport(assignment: BotAssignmentEntity, status: String, now: Long, summary: String?, error: String?) {
        val recipient = assignment.reportToBotId ?: return
        val groupId = assignment.groupId ?: return
        val label = when (status) {
            "COMPLETED" -> "已完成"
            "CANCELLED" -> "已取消"
            "BLOCKED" -> "受阻"
            "INTERRUPTED" -> "执行结果待核对"
            "QUEUED" -> "本轮结束，仍有补充要求待处理"
            else -> "执行未完成"
        }
        val content = "【成员委派结果】${assignment.botName} · $label（任务 ${assignment.id}）\n" +
            (summary ?: "无结果摘要").take(4_000) + (error?.let { "\n原因：$it" } ?: "") +
            "\n请核对原任务与产物；结果未知时先只读核验，勿重复执行。"
        stageGroupDelivery(GroupMessageEntity(
            id = "assignment-report:${assignment.id}" + (assignment.activeRequestId?.let { ":$it" } ?: ""), groupId = groupId, senderId = "system", senderName = "系统通知",
            senderType = "SYSTEM", content = content, timestamp = now, isStreaming = false, delegatedTaskId = assignment.id,
        ), if (status == "CANCELLED") emptyList() else listOf(recipient))
    }

    @Query("DELETE FROM bot_assignments WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: String)

    @Query(
        "UPDATE bot_assignments SET status = 'FAILED', finishedAt = :now, error = :error " +
            "WHERE status = 'RUNNING' AND independent = 0",
    )
    suspend fun markRunningInterrupted(now: Long, error: String)

    @Query("SELECT * FROM bot_assignments WHERE status = 'RUNNING' AND independent = 0")
    suspend fun allRunning(): List<BotAssignmentEntity>

    @Transaction
    suspend fun failRunning(now: Long, error: String) {
        val interrupted = allRunning()
        markRunningInterrupted(now, error)
        interrupted.forEach { stageAssignmentReport(it, "FAILED", now, null, error) }
    }

    @Query("SELECT * FROM bot_work_batches WHERE id = :id LIMIT 1")
    suspend fun findBatch(id: String): BotWorkBatchEntity?

    @Query("SELECT * FROM bot_work_batches WHERE groupId = :groupId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun listBatches(groupId: String, limit: Int): List<BotWorkBatchEntity>

    @Query("SELECT * FROM bot_work_batches WHERE status IN ('WAITING_CHILDREN','WAITING_TURN') ORDER BY updatedAt ASC LIMIT 64")
    suspend fun waitingBatches(): List<BotWorkBatchEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBatchIfAbsent(batch: BotWorkBatchEntity): Long

    @Query(
        "UPDATE bot_work_batches SET revision = revision + 1, status = 'WAITING_CHILDREN', " +
            "updatedAt = :now, settledAt = NULL WHERE id = :id",
    )
    suspend fun bumpBatchRevision(id: String, now: Long)

    @Query("UPDATE bot_work_batches SET status = :status, updatedAt = :now, settledAt = :settledAt WHERE id = :id")
    suspend fun setBatchStatus(id: String, status: String, now: Long, settledAt: Long?)

    @Query(
        "UPDATE bot_work_batches SET status = :status, reportRevision = :revision, " +
            "updatedAt = :now, settledAt = :now WHERE id = :id",
    )
    suspend fun settleBatch(id: String, status: String, revision: Int, now: Long)

    @Query("SELECT COUNT(*) FROM bot_assignments WHERE batchId = :batchId AND status IN ('QUEUED','RUNNING')")
    suspend fun activeBatchTaskCount(batchId: String): Int

    @Query("SELECT COUNT(*) FROM bot_assignments WHERE batchId = :batchId")
    suspend fun batchTaskCount(batchId: String): Int

    @Query("SELECT * FROM bot_assignments WHERE batchId = :batchId ORDER BY createdAt ASC, id ASC")
    suspend fun batchAssignments(batchId: String): List<BotAssignmentEntity>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM harness_lanes WHERE sessionId = :sessionId " +
            "AND currentOperationId = :operationId AND updatedAt >= :staleBefore)",
    )
    suspend fun isOperationActive(sessionId: String, operationId: String, staleBefore: Long): Boolean

    @Transaction
    suspend fun reconcileWaitingBatches(now: Long) {
        waitingBatches().forEach { settleBatchIfIdle(it.id, now) }
    }

    suspend fun settleBatchIfIdle(batchId: String, now: Long) {
        val batch = findBatch(batchId) ?: return
        if (batch.reportRevision == batch.revision && batch.status in setOf("SETTLED", "CANCELLED")) return
        if (activeBatchTaskCount(batchId) > 0) {
            setBatchStatus(batchId, "WAITING_CHILDREN", now, null)
            return
        }
        val operationActive = batch.originOperationId != null &&
            isOperationActive(batch.requesterSessionId, batch.originOperationId, now - BATCH_OPERATION_STALE_MS)
        if (operationActive) {
            setBatchStatus(batchId, "WAITING_TURN", now, null)
            return
        }
        if (!deliveryMember(batch.groupId, batch.requesterBotId)) {
            settleBatch(batchId, "CANCELLED", batch.revision, now)
            return
        }
        val children = batchAssignments(batchId)
        if (children.isEmpty()) {
            settleBatch(batchId, "CANCELLED", batch.revision, now)
            return
        }
        stageGroupDelivery(
            GroupMessageEntity(
                id = "assignment-batch-report:${batch.id}:${batch.revision}",
                groupId = batch.groupId,
                senderId = "system",
                senderName = "系统通知",
                senderType = "SYSTEM",
                content = buildBatchReport(batch, children),
                timestamp = now,
                isStreaming = false,
                delegatedTaskId = batch.id,
            ),
            listOf(batch.requesterBotId),
        )
        val status = if (children.all { it.status == "CANCELLED" }) "CANCELLED" else "SETTLED"
        settleBatch(batchId, status, batch.revision, now)
    }
}
