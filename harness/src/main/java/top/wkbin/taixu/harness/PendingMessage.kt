package top.wkbin.taixu.harness

import kotlinx.serialization.Serializable
import top.wkbin.taixu.harness.queue.PromptQueue

/** User-facing projection of a durable queued prompt. */
@Serializable
data class PendingMessage(
    val text: String,
    val imageUrls: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /** Durable task owning this queued prompt. Null keeps old queue payloads readable. */
    val taskId: String? = null,
)

/** Durable prompt plus its delivery semantics, exposed for queue-aware UI. */
data class QueuedPrompt(
    val id: String,
    val queue: PromptQueue,
    val message: PendingMessage,
)
