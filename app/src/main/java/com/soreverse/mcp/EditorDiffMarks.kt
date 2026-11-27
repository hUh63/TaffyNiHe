package com.soreverse.mcp

import java.io.File

/**
 * 编辑器「Git 风格」行级改动标记。
 *
 * 设计来源：Xed-Editor 用 GitColorScheme 把 gutter 变成"新增/修改/删除"三色标记，
 * 让用户一眼看出哪些行动过。我们编辑器已有"保存前自动快照备份"（editor_files/backup）
 * 的机制，但没有版本库，所以这里用**最近一次备份**作为「HEAD」来算行级 diff。
 *
 * 采用前缀/后缀双向裁剪 + 中段配对的启发式算法（O(n)），避免在手机上做
 * O(n·m) 的 LCS 造成卡顿；对"少量改动"这种最常见场景结果与 git 一致。
 */
internal object EditorDiffMarks {

    enum class Mark { ADDED, MODIFIED, DELETED }

    /** 参与 diff 的最大行数：超过则返回空（不标记），避免大文件卡主线程。 */
    private const val MAX_LINES = 6000

    /**
     * 计算标记：key = 新文本行号（1-based），value = 标记类型。
     * [DELETED] 标在"删除发生处的前一行"（即新文本中该行下方曾有内容被删）。
     * oldText 为 null 时返回空（无基线可比）。
     */
    fun diff(oldText: String?, newText: String): Map<Int, Mark> {
        if (oldText == null || oldText == newText) return emptyMap()
        // 先挡体积：原来不管多大都先 split 成两份全量行数组，再判断行数上限，
        // 2 MB 文本白分配几十万个 String 对象。超过 1.5 MB 的大文件不画行级标记。
        if (oldText.length > 1_500_000 || newText.length > 1_500_000) return emptyMap()
        val old = oldText.split('\n')
        val new = newText.split('\n')
        if (old.size > MAX_LINES || new.size > MAX_LINES) return emptyMap()

        val out = HashMap<Int, Mark>()

        // ① 前缀裁剪
        var head = 0
        while (head < old.size && head < new.size && old[head] == new[head]) head++

        // ② 后缀裁剪
        var tail = 0
        while (tail < old.size - head && tail < new.size - head &&
            old[old.size - 1 - tail] == new[new.size - 1 - tail]
        ) tail++

        val oldMid = old.subList(head, old.size - tail)
        val newMid = new.subList(head, new.size - tail)

        when {
            // 纯新增
            oldMid.isEmpty() -> newMid.indices.forEach { out[head + it + 1] = Mark.ADDED }
            // 纯删除：标在改动插入点的前一行（若无前一行则标第一行）
            newMid.isEmpty() -> out[(head).coerceAtLeast(1)] = Mark.DELETED
            else -> {
                val pairs = minOf(oldMid.size, newMid.size)
                // 中段成对出现 → 视为「修改」
                for (i in 0 until pairs) out[head + i + 1] = Mark.MODIFIED
                // 新文本多出的行 → 新增
                for (i in pairs until newMid.size) out[head + i + 1] = Mark.ADDED
                // 旧文本多出的行 → 删除（标在中段最后一行，或插入点前一行）
                if (oldMid.size > pairs) {
                    val at = if (newMid.isEmpty()) head.coerceAtLeast(1) else (head + pairs).coerceAtLeast(1)
                    out[at] = if (out[at] == Mark.MODIFIED) Mark.MODIFIED else Mark.DELETED
                }
            }
        }
        return out
    }

    // 差异标记每 400ms 算一次，每次都去磁盘重读同一份备份太浪费：
    // 按 (文件名, mtime, size) 缓存基线；保存/回滚改了备份后指纹变化，缓存自然失效。
    @Volatile private var cachedBaseName: String? = null
    @Volatile private var cachedBaseStamp: Long = -1L
    @Volatile private var cachedBaseSize: Long = -1L
    @Volatile private var cachedBaseText: String? = null

    /** 取该文件最近一次备份内容（作为 diff 基线）；无备份返回 null。 */
    fun latestBackupText(context: android.content.Context, fileName: String?): String? {
        if (fileName.isNullOrBlank()) return null
        val dir = File(context.filesDir, "editor_files/backup")
        val f = dir.listFiles { it -> it.name.startsWith("$fileName.") && it.name.endsWith(".bak") }
            ?.maxByOrNull { it.lastModified() } ?: return null
        val stamp = f.lastModified()
        val size = f.length()
        if (cachedBaseName == fileName && cachedBaseStamp == stamp && cachedBaseSize == size) {
            return cachedBaseText
        }
        val text = runCatching { if (size > 4L * 1024 * 1024) null else f.readText() }.getOrNull()
        cachedBaseName = fileName
        cachedBaseStamp = stamp
        cachedBaseSize = size
        cachedBaseText = text
        return text
    }

    /** 便捷入口：按文件路径/名称 + 当前内容算标记。 */
    fun marksFor(context: android.content.Context, fileName: String?, currentText: String): Map<Int, Mark> =
        diff(latestBackupText(context, fileName), currentText)
}
