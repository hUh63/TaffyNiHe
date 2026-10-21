package top.wkbin.taixu.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Immutable node in a session conversation tree. */
@Entity(
    tableName = "harness_entries",
    indices = [
        Index(value = ["id"], unique = true),
        Index(value = ["sessionId", "sequence"]),
        Index(value = ["sessionId", "parentId"]),
    ],
)
data class HarnessEntryEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val id: String,
    val sessionId: String,
    val parentId: String?,
    val createdAt: Long,
    /** message | compaction | branch_summary | custom */
    val entryType: String,
    /** user | assistant | tool_call | tool_result, or an application custom type. */
    val customType: String? = null,
    val payloadJson: String,
)

/**
 * 用量统计的 SQL 聚合行（按会话 + 消息类型分组）。
 *
 * 存在的意义：避免把整张 harness_entries（含巨大 payloadJson）读进内存造成 OOM。
 * 所有数值均在 SQLite 侧用 json_extract / LENGTH 算出，Kotlin 只接收几十行标量。
 */
data class UsageAggregateRow(
    val sessionId: String,
    val customType: String?,
    val entryCount: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
    val textChars: Long,
    val reasoningChars: Long,
)

/**
 * 按「本地日 + 会话 + 类型」聚合的用量行（供热力图/趋势使用，同样不携带 payloadJson）。
 *
 * [localEpochDay] 是把 `createdAt` 加上调用方传入的时区偏移后再按 86400000ms 取整，
 * 与 `Instant.atZone(zone).toLocalDate().toEpochDay()` 同口径（同一偏移下）。
 * Token 列与 [UsageAggregateRow] 使用同一套 json_extract + json_valid 守卫。
 */
data class DailyCountRow(
    val localEpochDay: Long,
    val sessionId: String,
    val customType: String?,
    val entryCount: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
    val textChars: Long,
    val reasoningChars: Long,
)
