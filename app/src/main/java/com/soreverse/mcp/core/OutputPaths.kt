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
 * 规则（两步判定，两步都在纯函数层可单测）：
 *  1. 输入不在应用私有 cacheDir 内（用户自己选的工作区文件）→ 产物就地放它旁边，
 *     别把东西挪到他找不到的地方；
 *  2. 输入在 cacheDir 内（选文件复制出来的临时副本）→ 产物写 `工作区/taffy-out/<feature>/`，
 *     没配工作区才退到应用私有 `filesDir/<feature>/`（私有目录非 root 看不见，仅兜底）。
 *
 * 这里**不做**产物自动清理：APK / dex 是用户要的东西，删掉比占几 MB 严重得多
 * （同 [TempFiles.pruneProducts] 的既定原则，那边也只清可再生成的 hook 脚本 / 报告）。
 */
internal object OutputPaths {

    /** 工作区里的产物根目录名。 */
    const val OUTPUT_ROOT = "taffy-out"

    /** 该功能的产物目录（保证存在）。 */
    fun productDir(context: Context, input: File, feature: String): File {
        val app = context.applicationContext
        val parent = input.parentFile
        val cache = runCatching { app.cacheDir.canonicalPath }.getOrNull()
        // 输入是自己选的文件 → 就地放；输入是缓存副本 → 换个用户拿得到的地方
        if (parent != null && !isInsideCache(parent, cache)) return parent
        val ws = runCatching { WorkspacePolicy.workDirPath(app) }.getOrNull()
        val target = if (ws.isNullOrBlank()) {
            File(app.filesDir, feature)
        } else {
            val root = productRoot(ws, app.filesDir)
            runCatching { root.mkdirs() }
            ensureIgnore(root)
            ensureReadme(root)
            File(root, feature)
        }
        runCatching { target.mkdirs() }
        return target
    }

    /**
     * 判定输入所在目录是否在应用缓存内（纯函数）。
     *
     * 用「cache 路径 + 分隔符」做前缀，避免把 `cache-extra/` 这种兄弟目录误判成缓存内。
     */
    fun isInsideCache(inputParent: File?, cacheDir: String?): Boolean {
        if (cacheDir == null) return false
        val p = runCatching { inputParent?.canonicalPath }.getOrNull() ?: return false
        return p == cacheDir || p.startsWith(cacheDir + File.separator)
    }

    /** 产物根目录（纯函数）：配了工作区 → `<工作区>/taffy-out`，否则 → `filesDir`。 */
    fun productRoot(workspacePath: String?, filesDir: File): File =
        if (!workspacePath.isNullOrBlank()) File(workspacePath, OUTPUT_ROOT) else filesDir

    /**
     * 产物根目录自带 `.gitignore`。
     *
     * 工作区常常同时是 Git 仓库（Git 页默认就是它），`git add -A` 会把几百 MB 的产物一起提交。
     * 根目录一条 `*` 就能覆盖所有子目录（含以后新增的），比每个子目录各放一份省事。
     */
    private fun ensureIgnore(root: File) {
        val ignore = File(root, ".gitignore")
        if (!ignore.isFile) runCatching { ignore.writeText("*\n!.gitignore\n!README.md\n") }
    }

    /** 产物根目录放一份说明：用户翻到这里时能对上「哪个子目录是谁生成的」。 */
    private fun ensureReadme(root: File) {
        val readme = File(root, "README.md")
        if (readme.isFile) return
        runCatching {
            readme.writeText(
                """
                # 塔菲逆核产物目录

                这里放各工具「默认输出」的产物（显式指定输出路径时不会用到这里）。整个目录可以随时删除，
                需要时重新执行对应工具即可，同时它已被本目录的 `.gitignore` 忽略，不会进你的 Git 提交。

                | 子目录 | 来源 |
                |---|---|
                | `apk-sign/` | `taffy_apk_editor` / `taffy_apk_build` / `taffy_smali_batch` 的签名产物 |
                | `aes-dex/` | `taffy_dex_aes` 解密出来的 dex |
                | `dex-recovered/` | `taffy_dex_xor_scan` 还原 / 修复的 dex |
                | `dex-out/` | `taffy_dex_compile` 重编出来的 dex |
                | `dex-renamed/` | `taffy_dex_rename_mapping` 重命名后的 dex / apk |
                | `vip-scan/` | `taffy_vip_entry_scan` 生成的 Frida hook 脚本、最小验证脚本与报告 |

                输入文件不在应用缓存里时（例如你自己选的工作区文件），产物仍然写在它旁边，不进这个目录。
                本目录不会自动删除你的产物；只有 `vip-scan/` 里的脚本 / 报告会按份数回收（可再生成）。
                """.trimIndent()
            )
        }
    }
}
