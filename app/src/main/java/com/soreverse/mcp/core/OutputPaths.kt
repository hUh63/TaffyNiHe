package com.soreverse.mcp.core

import android.content.Context
import java.io.File

/**
 * 产物落盘位置。
 *
 * 问题：不少工具把「解出来的 dex / 签好的 apk」默认写在**输入文件旁边**。而输入常常是 cacheDir 里
 * 的临时副本（选文件 → 复制到 cache → 交给引擎），产物于是落在 cache 里：缓存一回收就没了，
 * 用户还找不到（v1.5.82 的 VIP hook 脚本就是这个问题）。
 *
 * 规则：输入位于应用私有 cacheDir 之内（或没有父目录）时，产物改落 `filesDir/<feature>/`；
 * 否则沿用输入旁边——用户自己选的工作区文件，产物就该在旁边，别把东西挪到他找不到的地方。
 */
internal object OutputPaths {

    /**
     * 该功能的产物目录（保证存在）。
     *
     * 落点优先级：
     *  1. 输入不在 cache 里 → 就近放（用户自己选的文件，产物就该在旁边）；
     *  2. 输入在 cache 里 → 用户**能拿到**的地方：先工作区 `taffy-out/<feature>/`，
     *     没配工作区才退到应用私有 `filesDir/<feature>/`（私有目录非 root 看不见，只作兜底）。
     */
    fun productDir(context: Context, input: File, feature: String): File {
        val app = context.applicationContext
        val parent = input.parentFile
        val cache = runCatching { app.cacheDir.canonicalPath }.getOrNull()
        val p = runCatching { parent?.canonicalPath }.getOrNull()
        val insideCache = cache != null && p != null &&
            (p == cache || p.startsWith(cache + File.separator))
        if (parent != null && !insideCache) return parent
        val ws = runCatching { WorkspacePolicy.workDirPath(app) }.getOrNull()
        return if (!ws.isNullOrBlank()) {
            File(ws, "taffy-out/$feature").apply { ensureDir(this) }
        } else {
            File(app.filesDir, feature).apply { ensureDir(this) }
        }
    }

    /**
     * 建目录，并在其中放一个 `.gitignore`。
     *
     * 工作区常常同时是 Git 仓库（Git 页默认就是它），`git add -A` 会把几百 MB 的产物一起提交。
     * 产物目录自带 ignore 规则就不会污染用户的提交。
     */
    private fun ensureDir(dir: File) {
        runCatching { dir.mkdirs() }
        val ignore = File(dir, ".gitignore")
        if (!ignore.isFile) {
            runCatching { ignore.writeText("*\n!.gitignore\n") }
        }
    }

    /**
     * 纯函数版本：给定「输入父目录」与「cacheDir」，判断产物该落哪（供单测覆盖判定逻辑）。
     *
     * @return 目录（cache 内 → [filesDir]/[feature]；否则 → 输入父目录）
     */
    fun resolveDir(inputParent: File?, cacheDir: String?, filesDir: File, feature: String): File {
        val p = runCatching { inputParent?.canonicalPath }.getOrNull()
        val insideCache = cacheDir != null && p != null &&
            (p == cacheDir || p.startsWith(cacheDir + File.separator))
        return if (inputParent == null || insideCache) {
            File(filesDir, feature)
        } else {
            inputParent
        }
    }
}
