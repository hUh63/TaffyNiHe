package com.soreverse.mcp.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * MCP 工具临时工作区管理：
 *  - 识别应用生成的所有中间工作目录（filesDir 下，按已知工具命名）；
 *  - [count] / [stats]：统计临时工作区数量与占用；
 *  - [cleanAll]：一键清理全部临时工作区；
 *  - [pruneToLimit]：按设置页「临时工作区数量」上限裁剪最旧的临时工作区。
 *
 * 对应设置页「MCP 工具 → 临时工作区」卡片，以及 MCP 工具 taffy_temp_workspace。
 */
object TempWorkspaceManager {

    /** filesDir 下被认定为「临时工作区」的根目录名。 */
    val TEMP_ROOTS: List<String> = listOf(
        "smali-batch",   // taffy_smali_batch init 产物
        "apkeditor-out", // taffy_apk_rebuild decode/build 产物
        "extracted",     // taffy_apk_extract 提取产物
        "jadx-out",      // taffy_jadx save 产物
        "deodex-out",    // taffy_deodex 产物
        "smali-out",     // taffy_smali_decompile 产物
        "dexdump",       // taffy_unpack 脱壳产物
    )

    private fun roots(context: Context): List<File> {
        val base = context.applicationContext.filesDir
        return TEMP_ROOTS.map { File(base, it) }
    }

    /** 每个临时根下的子项（子目录/文件）数量总和。 */
    fun count(context: Context): Int =
        roots(context).sumOf { root -> root.listFiles()?.size ?: 0 }

    /** 统计详情：每个根的名称、子项数、占用字节。 */
    fun stats(context: Context): JSONArray {
        val arr = JSONArray()
        roots(context).forEach { root ->
            val items = root.listFiles() ?: emptyArray()
            val bytes = items.sumOf { item ->
                if (item.isDirectory) runCatching { item.walkTopDown().sumOf { it.length() } }.getOrDefault(0L)
                else item.length()
            }
            arr.put(JSONObject()
                .put("name", root.name)
                .put("path", root.absolutePath)
                .put("itemCount", items.size)
                .put("bytes", bytes))
        }
        return arr
    }

    /** 一键清理全部临时工作区。返回删除的子项数量。 */
    fun cleanAll(context: Context): Int {
        var removed = 0
        roots(context).forEach { root ->
            root.listFiles()?.forEach { item ->
                if (item.deleteRecursively()) removed++
            }
        }
        return removed
    }

    /** 按设置的数量上限裁剪最旧的临时工作区（删除超过 limit 的最旧子项）。 */
    fun pruneToLimit(context: Context): Int {
        val limit = SettingsStore(context).tempWorkspaceLimit
        var removed = 0
        roots(context).forEach { root ->
            val items = (root.listFiles() ?: emptyArray())
                .sortedBy { it.lastModified() } // 最旧在前
            if (items.size > limit) {
                for (i in 0 until items.size - limit) {
                    if (items[i].deleteRecursively()) removed++
                }
            }
        }
        return removed
    }
}
