package com.soreverse.mcp.core

import android.content.Context
import java.io.File

/**
 * 缓存目录里的一次性中间产物清理器。
 *
 * 背景：dex/smali 工具会在 `cacheDir` 下留 `dexinspect_*`、`smali_patch_*` 等目录与文件。
 * 这些路径分布在多处提前 return 分支里，逐个补 `finally` 既容易漏也容易改坏主流程；
 * 改成"每次进入工具时按前缀扫描并回收过期项"，任何 return 路径都不会导致永久累积。
 */
internal object TempJanitor {

    fun sweep(context: Context, prefixes: Collection<String>, maxAgeMs: Long = 2 * 60 * 60 * 1000L) {
        runCatching {
            val base = context.applicationContext.cacheDir
            val now = System.currentTimeMillis()
            base.listFiles()?.forEach { f ->
                if (prefixes.any { f.name.startsWith(it) } && now - f.lastModified() > maxAgeMs) {
                    runCatching { if (f.isDirectory) f.deleteRecursively() else f.delete() }
                }
            }
        }
    }
}
