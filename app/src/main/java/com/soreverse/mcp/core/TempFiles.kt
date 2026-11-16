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
 * 策略：只清「明确可重建」的前缀，且
 *  - 每个前缀保留最新 [keepRecent] 份（正在被已打开工作区引用的通常就是最后一份）；
 *  - 文件名在 [keepNames] 里的一律不动（调用方正在用的那份）；
 *  - 太新的不动（[minAgeMs]，防止把「刚创建、还没来得及被引用」的副本删掉）。
 * `tunnel_creds.json` / `tunnel_config.yml` / `updates/` / `unidbg` / `dexindex` / `dexidx` /
 * `edbg_decompile_input` 这类固定名或运行中可能持有的，不在默认名单里。
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
        // Blutter 的 scratch（cacheDir/flutter-inspect/<UUID>/）：正常路径有 finally 清理，
        // 但进程被杀/被回收时 finally 不跑，残留会永远留着（每个目录下是 libapp/libflutter）。
        // 启动时不可能有正在跑的任务，所以回收是安全的。
        "flutter-inspect",
        // 故意不列入：dexindex / dexidx / edbg_decompile_input / unidbg —— 它们都是**固定名的一份**
        // 副本，不会堆积；写进名单反而会在启动时把「当前打开工作区正在引用的那份」按 keepRecent 删掉。
    )

    /** 年轻文件保护窗：默认 10 分钟。 */
    const val DEFAULT_MIN_AGE_MS: Long = 10L * 60 * 1000

    /**
     * 按前缀清理 cacheDir。
     *
     * @return (删除的份数, 释放的字节数)
     */
    fun sweepCache(
        context: Context,
        prefixes: List<String> = CACHE_PREFIXES,
        keepRecent: Int = 1,
        keepNames: Set<String> = emptySet(),
        minAgeMs: Long = DEFAULT_MIN_AGE_MS,
    ): Pair<Int, Long> {
        val root = runCatching { context.applicationContext.cacheDir }.getOrNull() ?: return 0 to 0L
        return sweepDir(root, prefixes, keepRecent, keepNames, minAgeMs)
    }

    /**
     * 纯文件系统版本（可在 JVM 单测里用临时目录跑，不依赖 Android）。
     *
     * @param keepRecent 每个前缀保留最新的几份
     * @param keepNames 文件名命中这里的不动（调用方正在用的那份）
     * @param minAgeMs 修改时间距今不足这个值的文件不动（0 = 不保护；默认 0，生产入口
     *   [sweepCache] 才带 10 分钟保护窗，单测因此不用等时间）
     * @return (删除的份数, 释放的字节数)
     */
    fun sweepDir(
        root: File,
        prefixes: List<String> = CACHE_PREFIXES,
        keepRecent: Int = 1,
        keepNames: Set<String> = emptySet(),
        minAgeMs: Long = 0,
    ): Pair<Int, Long> {
        if (!root.isDirectory) return 0 to 0L
        val now = System.currentTimeMillis()
        var removed = 0
        var bytes = 0L
        for (prefix in prefixes) {
            val matches = runCatching {
                root.listFiles { f -> f.name.startsWith(prefix) }?.sortedByDescending { it.lastModified() }
            }.getOrNull() ?: continue
            matches.drop(keepRecent.coerceAtLeast(0)).forEach { f ->
                if (f.name in keepNames) return@forEach
                if (minAgeMs > 0 && now - f.lastModified() < minAgeMs) return@forEach
                bytes += runCatching { if (f.isDirectory) dirSize(f) else f.length() }.getOrDefault(0L)
                if (runCatching { f.deleteRecursively() }.getOrDefault(false)) removed++
            }
        }
        return removed to bytes
    }

    /**
     * 只清某个前缀下的「旧副本」，并跳过 [keep] 里指定的文件名。
     *
     * DEX 浏览器用它：选单个 `.dex` 时传进来的路径本身就是 cacheDir 里的副本，
     * 无差别清理会把它自己删掉，紧接着解析就报「文件不存在」。
     */
    fun sweepCopies(root: File, prefix: String, keep: String): Pair<Int, Long> =
        sweepDir(
            root = root,
            prefixes = listOf(prefix),
            keepRecent = 0,
            keepNames = if (keep.isBlank()) emptySet() else setOf(keep),
            minAgeMs = 0,
        )

    private fun dirSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
