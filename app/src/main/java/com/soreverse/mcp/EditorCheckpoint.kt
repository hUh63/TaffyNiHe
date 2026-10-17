package com.soreverse.mcp

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 编辑器「会话恢复」三件套（借鉴太墟 taixu 的 SessionFork / checkpoint / rewind 思路）：
 *
 * 1. 文件 checkpoint 栈：编辑过程中按节流自动存快照，可 rewind 到任意历史点。
 * 2. 外部修改冲突检测：打开文件时记录 (mtime, size, sha256)，保存前再比一次。
 * 3. 会话元信息：记录已打开标签 + 未保存状态，供进程重启后「恢复上次编辑会话」。
 *
 * 与既有机制的边界（刻意不重复造轮子）：
 * - `filesDir/editor_files/backup` 目录下的 `.bak`（EditorDiffMarks 的 diff 基线、closeTab 的兜底备份）
 *   **不动**，仍是「最近一次」单档备份；checkpoint 是**多历史点**栈，两者并存互不影响。
 * - `filesDir/editor_files/autosave.json`（多标签未保存内容）**继续复用**，本文件只补
 *   会话元信息（路径 / 脏标记 / 大小 / 是否因过大而省略正文）。
 * - `EditSnapshotService`（mcp 包）是给 MCP 工具用的低频、带 diff/rollback 的快照，目录在
 *   `edit-snapshots/`，粒度与保留策略都不适合编辑器高频快照，因此不复用，只借鉴其 sha256 思路。
 *
 * 全部 IO 都包 runCatching：快照/会话失败绝不能让编辑器崩。
 */
data class CheckpointMeta(
    val id: Long,
    val label: String,
    val atMillis: Long,
    val sizeBytes: Long,
    val lines: Int,
)

/**
 * checkpoint 栈存储。
 *
 * 目录约定：`filesDir/editor-checkpoints/<sha256(path) 前 16 位>/`
 * - `<id>.txt` 单份快照正文（UTF-8）
 * - `index.txt` 索引（TSV 手写格式，不用 org.json，避免依赖它那套不稳定缩进）
 *
 * 环形淘汰：份数 > [MAX_COUNT] 或总字节 > [MAX_BYTES] 时从最旧删起（至少保留 1 份）。
 */
object EditorCheckpointStore {

    /** 每文件保留份数上限。 */
    const val MAX_COUNT = 20

    /** 每文件保留字节上限（4MB）。 */
    const val MAX_BYTES = 4L * 1024 * 1024

    private const val DIR_NAME = "editor-checkpoints"
    private const val INDEX_NAME = "index.txt"
    private const val FIELD_SEP = "\t"

    /** 路径指纹：SHA-256 前 16 位（路径含 / 与中文，绝不能直接当目录名）。 */
    fun keyOf(path: String): String = sha256Hex(path.toByteArray(Charsets.UTF_8)).take(16)

    fun root(context: Context, path: String): File =
        File(File(context.filesDir, DIR_NAME), keyOf(path))

    // ── Context 版公开 API ──

    /**
     * 压入一份快照。内容与栈顶相同则不重复存（返回 null，不写盘）。
     * 返回新快照元信息；失败返回 null。
     */
    fun push(context: Context, path: String, content: String, label: String): CheckpointMeta? =
        pushAt(root(context, path), content, label)

    fun list(context: Context, path: String): List<CheckpointMeta> = listAt(root(context, path))

    fun read(context: Context, path: String, id: Long): String? = readAt(root(context, path), id)

    fun clear(context: Context, path: String) {
        runCatching { root(context, path).deleteRecursively() }
    }

    /** 所有文件快照占用总字节。 */
    fun totalBytes(context: Context): Long = totalBytesAt(File(context.filesDir, DIR_NAME))

    // ── 纯 File 版（便于 JVM 单测，不碰 Android Context）──

    fun pushAt(dir: File, content: String, label: String): CheckpointMeta? = runCatching {
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val rows = readRows(dir)
        val bytes = content.toByteArray(Charsets.UTF_8)
        val digest = sha256Hex(bytes)
        // 去重：栈顶内容相同就不再存一份
        if (rows.isNotEmpty() && rows.last().sha == digest) return null
        val id = ((rows.maxOfOrNull { it.id } ?: 0L) + 1L).coerceAtLeast(System.currentTimeMillis() and 0x7fffffffL)
        val body = File(dir, "$id.txt")
        body.writeBytes(bytes)
        val meta = CheckpointMeta(
            id = id,
            label = sanitizeLabel(label),
            atMillis = System.currentTimeMillis(),
            sizeBytes = bytes.size.toLong(),
            lines = content.count { c -> c == '\n' } + 1,
        )
        rows.add(Row(meta, digest))
        trim(dir, rows)
        writeRows(dir, rows)
        meta
    }.getOrNull()

    fun listAt(dir: File): List<CheckpointMeta> =
        runCatching { readRows(dir).map { it.meta }.sortedByDescending { it.id } }.getOrDefault(emptyList())

    fun readAt(dir: File, id: Long): String? =
        runCatching { File(dir, "$id.txt").readText() }.getOrNull()

    fun clearAt(dir: File) {
        runCatching { dir.deleteRecursively() }
    }

    fun totalBytesAt(dir: File): Long = runCatching {
        var sum = 0L
        val subs = dir.listFiles().orEmpty()
        for (sub in subs) {
            if (!sub.isDirectory) continue
            sum += sub.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
        }
        sum
    }.getOrDefault(0L)

    // ── 内部：索引读写 / 环形淘汰 ──

    private data class Row(val meta: CheckpointMeta, val sha: String) {
        val id: Long get() = meta.id
    }

    private fun readRows(dir: File): MutableList<Row> {
        val out = mutableListOf<Row>()
        val idx = File(dir, INDEX_NAME)
        if (!idx.isFile) return out
        runCatching {
            idx.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val p = line.split(FIELD_SEP)
                if (p.size < 6) return@forEachLine
                val id = p[0].toLongOrNull() ?: return@forEachLine
                if (!File(dir, "$id.txt").isFile) return@forEachLine
                out.add(
                    Row(
                        CheckpointMeta(
                            id = id,
                            atMillis = p[1].toLongOrNull() ?: 0L,
                            sizeBytes = p[2].toLongOrNull() ?: 0L,
                            lines = p[3].toIntOrNull() ?: 0,
                            label = p[5],
                        ),
                        p[4],
                    )
                )
            }
        }
        return out
    }

    private fun writeRows(dir: File, rows: List<Row>) {
        runCatching {
            val sb = StringBuilder()
            rows.sortedBy { it.id }.forEach { r ->
                sb.append(r.meta.id).append(FIELD_SEP)
                    .append(r.meta.atMillis).append(FIELD_SEP)
                    .append(r.meta.sizeBytes).append(FIELD_SEP)
                    .append(r.meta.lines).append(FIELD_SEP)
                    .append(r.sha).append(FIELD_SEP)
                    .append(sanitizeLabel(r.meta.label)).append('\n')
            }
            File(dir, INDEX_NAME).writeText(sb.toString())
        }
    }

    /** 环形淘汰：超份数或超字节就从最旧删起，至少留 1 份。 */
    private fun trim(dir: File, rows: MutableList<Row>) {
        rows.sortBy { it.id }
        var total = rows.sumOf { it.meta.sizeBytes }
        while (rows.size > 1 && (rows.size > MAX_COUNT || total > MAX_BYTES)) {
            val victim = rows.removeAt(0)
            runCatching { File(dir, "${victim.id}.txt").delete() }
            total -= victim.meta.sizeBytes
        }
    }

    private fun sanitizeLabel(s: String): String =
        s.replace(FIELD_SEP, " ").replace("\n", " ").replace("\r", " ").ifBlank { "checkpoint" }.take(60)
}

/** 文件指纹：修改时间 + 大小 + 内容 SHA-256。 */
data class FileStamp(val mtime: Long, val size: Long, val sha256: String)

/**
 * 外部修改冲突检测（纯函数，易单测）。
 *
 * 语义：known == null 表示「打开时就没记到指纹」（新建文件 / SAF 打开 / 读失败），
 * 此时一律不判冲突，避免误报。
 */
object FileStampUtil {

    /** 超过此大小不算 sha256（只靠 mtime + size 判），避免大文件拖慢打开。 */
    const val MAX_HASH_BYTES = 32L * 1024 * 1024

    /** 读不到返回 null。 */
    fun stamp(file: File?): FileStamp? {
        if (file == null) return null
        return runCatching {
            if (!file.isFile) return null
            val len = file.length()
            val digest = if (len > MAX_HASH_BYTES) "" else sha256Hex(file.readBytes())
            FileStamp(file.lastModified(), len, digest)
        }.getOrNull()
    }

    fun changed(known: FileStamp?, current: FileStamp?): Boolean {
        if (known == null) return false
        if (current == null) return true
        if (known.size != current.size || known.mtime != current.mtime) return true
        if (known.sha256.isNotEmpty() && current.sha256.isNotEmpty() && known.sha256 != current.sha256) return true
        return false
    }
}

/** 会话里一个标签的元信息（正文仍在 editor_files/autosave.json）。 */
data class SessionEntry(
    val name: String,
    val path: String?,
    val dirty: Boolean,
    val sizeBytes: Long,
    val omitted: Boolean,
)

/**
 * 会话元信息（SharedPreferences `editor_session`）。
 *
 * 只存**元信息**：标题、路径、是否脏、字节数、正文是否因过大被省略。
 * 正文为什么放 autosave.json 而不是 prefs：prefs 是 XML，塞 200KB 正文会让整个 prefs
 * 加载变慢，且 Android 对单个 prefs 有明显体积压力。所以正文沿用既有 autosave.json。
 */
object EditorSessionStore {

    private const val PREFS = "editor_session"
    private const val KEY_TABS = "tabs"
    private const val KEY_AT = "at_millis"
    private const val FIELD_SEP = "\u0001"

    /** 正文超过此大小就不写进 autosave.json，只留元信息（避免爆盘/卡顿）。 */
    const val MAX_INLINE_BYTES = 200L * 1024

    fun save(context: Context, entries: List<SessionEntry>) {
        runCatching {
            val sb = StringBuilder()
            entries.forEach { e ->
                sb.append(clean(e.name)).append(FIELD_SEP)
                    .append(clean(e.path ?: "")).append(FIELD_SEP)
                    .append(if (e.dirty) "1" else "0").append(FIELD_SEP)
                    .append(e.sizeBytes).append(FIELD_SEP)
                    .append(if (e.omitted) "1" else "0").append('\n')
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_TABS, sb.toString())
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply()
        }
    }

    fun load(context: Context): List<SessionEntry> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TABS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        raw.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split(FIELD_SEP)
            if (p.size < 5) return@mapNotNull null
            SessionEntry(
                name = p[0],
                path = p[1].ifBlank { null },
                dirty = p[2] == "1",
                sizeBytes = p[3].toLongOrNull() ?: 0L,
                omitted = p[4] == "1",
            )
        }.toList()
    }.getOrDefault(emptyList())

    fun savedAt(context: Context): Long = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_AT, 0L)
    }.getOrDefault(0L)

    fun clear(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }

    private fun clean(s: String): String = s.replace(FIELD_SEP, " ").replace("\n", " ").replace("\r", " ")
}

/** SHA-256 十六进制（小写）。 */
internal fun sha256Hex(bytes: ByteArray): String = runCatching {
    val d = MessageDigest.getInstance("SHA-256").digest(bytes)
    val sb = StringBuilder(d.size * 2)
    for (b in d) {
        val v = b.toInt() and 0xff
        sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
    }
    sb.toString()
}.getOrDefault("")

private const val HEX = "0123456789abcdef"
