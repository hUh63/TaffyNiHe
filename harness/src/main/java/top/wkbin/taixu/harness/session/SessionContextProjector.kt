package top.wkbin.taixu.harness.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import top.wkbin.taixu.core.database.HarnessEntryEntity
import top.wkbin.taixu.core.database.HarnessRuntimeRepository
import top.wkbin.taixu.harness.HarnessMessage
import top.wkbin.taixu.harness.compaction.BranchSummaryPayload
import top.wkbin.taixu.harness.compaction.CompactedContext
import top.wkbin.taixu.harness.compaction.CompactionManager
import top.wkbin.taixu.harness.compaction.CompactionPayload
import top.wkbin.taixu.harness.core.ProjectionIssue
import top.wkbin.taixu.harness.core.ProjectionIssueCode
import top.wkbin.taixu.harness.core.SessionProjection
import top.wkbin.taixu.harness.core.SessionProjectionBuilder
import top.wkbin.taixu.harness.core.SessionProjectionCheckpoint
import top.wkbin.taixu.harness.core.SessionProjectionContent
import top.wkbin.taixu.harness.core.SessionProjectionEntry

data class SessionContextProjection(
    val sessionId: String,
    val laneName: String,
    /** All repository reads use this captured leaf, even if the lane moves during a read. */
    val leafId: String?,
    val projection: SessionProjection<HarnessMessage>,
) {
    fun context(): CompactedContext = CompactedContext(
        summary = projection.summary,
        messages = projection.messages.map { it.message },
        branchSummaries = projection.branchSummaries.map { it.text },
        recallBlocks = projection.recallBlocks.mapValues { it.value.text },
        sourceMaxSequence = projection.sourceMaxSequence,
    )
}

/** Read-only, provenance-preserving projection; no lane mutation, summary generation or prompt injection. */
class SessionContextProjector(private val repository: HarnessRuntimeRepository, private val json: Json) {
    suspend fun inspect(sessionId: String, laneName: String = SessionTreeStore.MAIN_LANE): SessionContextProjection {
        currentCoroutineContext().ensureActive()
        val leafId = read { repository.findLane(sessionId, laneName)?.leafId }
        val latest = read { repository.latestBranchEntryOfType(sessionId, leafId, CompactionManager.ENTRY_TYPE) }
        val issues = mutableListOf<ProjectionIssue>()
        val checkpoint = latest?.let { decodeCheckpoint(it, issues) }
        // Retain the window query: summarized message blobs must stay off the mobile heap.
        val entries = read {
            if (checkpoint == null) repository.branch(sessionId, leafId)
            else repository.branchWindow(sessionId, leafId, minOf(checkpoint.sourceWatermarkSequence ?: checkpoint.sequence, checkpoint.sequence))
        }
        val projection = SessionProjectionBuilder.project(entries.map { entry ->
            currentCoroutineContext().ensureActive()
            decodeEntry(entry)
        }, checkpoint, issues)
        currentCoroutineContext().ensureActive()
        return SessionContextProjection(sessionId, laneName, leafId, projection)
    }

    private fun decodeCheckpoint(entry: HarnessEntryEntity, issues: MutableList<ProjectionIssue>): SessionProjectionCheckpoint<HarnessMessage>? {
        val payload = decode { json.decodeFromString(CompactionPayload.serializer(), entry.payloadJson) }
            ?: return invalid(entry, issues, ProjectionIssueCode.MALFORMED_CHECKPOINT)
        if (payload.sourceWatermarkSequence != null && (payload.sourceWatermarkSequence < 0 || payload.sourceWatermarkSequence > entry.sequence))
            return invalid(entry, issues, ProjectionIssueCode.INVALID_WATERMARK)
        val retained = payload.retainedMessages ?: if (payload.retainedMessagesJson.isNullOrBlank()) {
            if (payload.retainedMessageCount > 0) return invalid(entry, issues, ProjectionIssueCode.MALFORMED_RETAINED_MESSAGES)
            emptyList()
        } else decode { json.decodeFromString(ListSerializer(HarnessMessage.serializer()), payload.retainedMessagesJson) }
            ?: return invalid(entry, issues, ProjectionIssueCode.MALFORMED_RETAINED_MESSAGES)
        return SessionProjectionCheckpoint(entry.id, entry.sequence, payload.summary, retained, payload.sourceWatermarkSequence)
    }

    private fun invalid(entry: HarnessEntryEntity, issues: MutableList<ProjectionIssue>, code: ProjectionIssueCode): Nothing? {
        issues += ProjectionIssue(entry.id, code)
        return null
    }

    private fun decodeEntry(entry: HarnessEntryEntity): SessionProjectionEntry<HarnessMessage> {
        val content = when (entry.entryType) {
            "message" -> decode { json.decodeFromString(HarnessMessage.serializer(), entry.payloadJson) }
                ?.let { SessionProjectionContent.MessageValue(it) }
                ?: SessionProjectionContent.Unreadable(entry.entryType, ProjectionIssueCode.MALFORMED_MESSAGE)
            CompactionManager.BRANCH_SUMMARY_ENTRY_TYPE -> decode { json.decodeFromString(BranchSummaryPayload.serializer(), entry.payloadJson).summary }
                ?.let { SessionProjectionContent.BranchSummary(it) }
                ?: SessionProjectionContent.Unreadable(entry.entryType, ProjectionIssueCode.MALFORMED_BRANCH_SUMMARY)
            SessionTreeStore.RECALL_ENTRY_TYPE -> SessionProjectionContent.Recall(entry.customType.orEmpty(), entry.payloadJson)
            else -> SessionProjectionContent.State(entry.entryType)
        }
        return SessionProjectionEntry(entry.id, entry.sequence, content)
    }

    private inline fun <T> decode(block: () -> T): T? = try { block() }
        catch (cancellation: CancellationException) { throw cancellation }
        catch (_: Exception) { null }

    private suspend fun <T> read(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        return try {
            val value = block()
            currentCoroutineContext().ensureActive()
            value
        } catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            throw failure
        }
    }
}
