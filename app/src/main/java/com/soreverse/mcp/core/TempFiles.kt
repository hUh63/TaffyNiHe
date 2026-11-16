package com.soreverse.mcp.core

import android.content.Context
import java.io.File

/**
 * 应用私有 cache 目录的清理。
 *
 * 为什么需要它：所有「选文件 → 复制到 cacheDir 再交给引擎」的路径都会新建副本，而副本名常带
 * `System.currentTimeMillis()` / `nanoTime()` / `UUID`（避免互相覆盖），于是**每选一次文件就多一份**，
 * 从不删除。多 dex APK、APK 里的 `.so` 动辄几十上百 MB，用一段时间 cacheDir 就能堆到 GB 级——
 * 而系统只在「存储不足」时才清 cache，那时用户已经看到写失败或者分析直接失败了。
 *
 * 策略：只清「明确可重建」的前缀，且每个前缀保留最新的 [keepRecent] 份（正在被已打开工作区引用的
 * 通常就是最后一份）。`tunnel_creds.json` / `tunnel_config.yml` / `updates/` / `unidbg` /
 * `flutter-inspect` 这类可能被运行中的服务或未完成的 Blutter 任务持有的，不在名单里。
 */
internal object TempFiles {

    /** 临时副本前缀（与各页面的 `File(cacheDir, "xxx_...")` 命名保持一致）。 */
    val CACHE_PREFIXES: List<String> = listOf(
        "picked_",              // 选择文件的通用副本（分析页、引擎来源）
        "apk_so_",              // APK 里抽出来的 .so
        "dexindex_src_",        // DEX 索引的源副本（每次带 nanoTime）
        "dexedit_", "rz_open_", // 反汇编/编辑输入副本（每次带时间戳）
        "apk_edit_",            // APK 编辑器输入
        "picked-full-backup-", "restore-",       // 备份/恢复临时文件
        "dexx_",                // DEX 浏览器按 entry 解出的 dex
        "dexinspect_",          // 本地 dex 检查工作目录
        "xed_conv_",            // 扩展格式转换中间产物
        "taffy_cmd_",           // 终端命令输出
        "native-exec-",         // 可执行文件副本
        "import_", "keystore-import-",  // 扩展/密钥导入副本
        "vip_",                 // 会员入口扫描导入副本
        // 故意不列入：dexindex / dexidx / edbg_decompile_input / unidbg / flutter-inspect。
        // 它们都是**固定名的一份**副本，不会堆积；写进名单反而会在启动时把「当前打开工作区
        // 正在引用的那份」按 keepRecent 删掉，下次用虽然会重建，但那一次读取会拿到空。
    )

    /**
     * 按前缀清理 cacheDir，每个前缀保留最新 [keepRecent] 份。
     *
     * @return (删除的份数, 释放的字节数)
     */
    fun sweepCache(
        context: Context,
        prefixes: List<String> = CACHE_PREFIXES,
        keepRecent: Int = 1,
    ): Pair<Int, Long> {
        val root = runCatching { context.applicationContext.cacheDir }.getOrNull() ?: return 0 to 0L
        return sweepDir(root, prefixes, keepRecent)
    }

    /**
     * 纯文件系统版本（可在 JVM 单测里用临时目录跑，不依赖 Android）。
     *
     * @return (删除的份数, 释放的字节数)
     */
    fun sweepDir(
        root: File,
        prefixes: List<String> = CACHE_PREFIXES,
        keepRecent: Int = 1,
    ): Pair<Int, Long> {
        if (!root.isDirectory) return 0 to 0L
        var removed = 0
        var bytes = 0L
        for (prefix in prefixes) {
            val matches = runCatching {
                root.listFiles { f -> f.name.startsWith(prefix) }?.sortedByDescending { it.lastModified() }
            }.getOrNull() ?: continue
            matches.drop(keepRecent.coerceAtLeast(0)).forEach { f ->
                bytes += runCatching { if (f.isDirectory) dirSize(f) else f.length() }.getOrDefault(0L)
                if (runCatching { f.deleteRecursively() }.getOrDefault(false)) removed++
            }
        }
        return removed to bytes
    }

    private fun dirSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
