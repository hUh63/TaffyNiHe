package com.soreverse.mcp.core

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 统一插件平台。
 *
 * 在此之前，插件相关逻辑全部长在设置页里（安装/导入/运行/模板），UI 与实现绑死，MCP 侧
 * 完全看不到插件。这里把它收成一层：
 *
 *  - **单一清单来源**：[list] 扫 `filesDir/plugins/<id>/`，解析 `meta.json`，给出统一模型
 *  - **健康诊断**：[diagnose] 回答「这个插件能不能跑、为什么不能跑」——缺入口、版本不兼容、
 *    元数据损坏、权限未声明，全都在这里一次性列出，而不是等运行时才炸
 *  - **权限模型**：`meta.json` 的 `permissions` 声明能力边界（`tools:taffy_so_*` / `files:read` /
 *    `net:outbound`），[checkRunAllowed] 在运行前校验；未声明权限的旧插件按「只读文件」处理并标注
 *  - **运行审计**：[recordRun] / [audit] 记录每次运行的成败与摘要，便于回答「上次是不是它弄坏的」
 *
 * 刻意不在这里做运行本身：执行仍由设置页的 `plugin_runner.py` 负责（它需要把环境变量与
 * 工具句柄注入子进程）。这一层只回答「能不能跑、跑过什么、有没有越权」。
 */
internal object PluginManager {

    /** 插件清单模型。issue 非空表示「装了但有问题」。 */
    data class PluginInfo(
        val id: String,
        val name: String,
        val version: String,
        val author: String,
        val description: String,
        val entry: String,
        val minAppVersion: String,
        val permissions: List<String>,
        val settingsCount: Int,
        val dir: File,
        val hasEntry: Boolean,
        val issues: List<String>,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("version", version)
            .put("author", author)
            .put("description", description)
            .put("entry", entry)
            .put("minAppVersion", minAppVersion)
            .put("permissions", JSONArray(permissions))
            .put("settingsCount", settingsCount)
            .put("path", dir.absolutePath)
            .put("hasEntry", hasEntry)
            .put("healthy", issues.isEmpty())
            .put("issues", JSONArray(issues))
    }

    fun pluginsRoot(context: Context): File =
        File(context.filesDir, "plugins").apply { if (!exists()) mkdirs() }

    /** 扫描并解析所有插件目录（不执行任何插件代码）。 */
    fun list(context: Context): List<PluginInfo> {
        val root = pluginsRoot(context)
        val dirs = root.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: return emptyList()
        return dirs.mapNotNull { parse(context, it) }
    }

    private fun parse(context: Context, dir: File): PluginInfo? {
        val metaFile = File(dir, "meta.json")
        val issues = ArrayList<String>()
        val meta = if (metaFile.isFile) {
            runCatching { JSONObject(metaFile.readText(Charsets.UTF_8).take(64 * 1024)) }
                .getOrElse {
                    issues.add("meta.json 无法解析：${it.message ?: "invalid json"}")
                    JSONObject()
                }
        } else {
            issues.add("缺少 meta.json")
            JSONObject()
        }
        val entry = meta.optString("entry").ifBlank { "plugin.py" }
        val entryFile = File(dir, entry)
        val hasEntry = entryFile.isFile
        if (!hasEntry) issues.add("入口文件不存在：$entry")

        val minVersion = meta.optString("minAppVersion")
        if (minVersion.isNotBlank() && !versionAtLeast(appVersionName(context), minVersion)) {
            issues.add("需要 App ≥ $minVersion（当前 $appVersionName(context)）")
        }

        val perms = ArrayList<String>()
        val permArr = meta.optJSONArray("permissions")
        if (permArr != null) {
            for (i in 0 until permArr.length()) {
                val p = permArr.optString(i).trim()
                if (p.isNotBlank()) perms.add(p)
            }
        }
        if (perms.isEmpty()) {
            // 老插件没有权限声明：不阻断运行，但要让用户知道它按「只读文件」对待
            issues.add("未声明 permissions（按只读文件处理）")
        }
        val settingsCount = meta.optJSONArray("settings")?.length() ?: 0

        return PluginInfo(
            id = dir.name,
            name = meta.optString("name").ifBlank { dir.name },
            version = meta.optString("version").ifBlank { "0" },
            author = meta.optString("author"),
            description = meta.optString("description"),
            entry = entry,
            minAppVersion = minVersion,
            permissions = perms,
            settingsCount = settingsCount,
            dir = dir,
            hasEntry = hasEntry,
            issues = issues,
        )
    }

    /**
     * 运行前校验。
     *
     * 返回 `null` 表示放行；返回字符串表示拒绝原因。版本不兼容与缺入口是硬阻断，
     * 权限未声明只警告（老插件兼容），由调用方决定是否展示。
     */
    fun checkRunAllowed(context: Context, id: String): String? {
        val info = list(context).firstOrNull { it.id == id } ?: return "插件不存在：$id"
        if (!info.hasEntry) return "入口文件缺失：${info.entry}"
        if (info.minAppVersion.isNotBlank() && !versionAtLeast(appVersionName(context), info.minAppVersion)) {
            return "版本不兼容：需要 App ≥ ${info.minAppVersion}（当前 ${appVersionName(context)}）"
        }
        return null
    }

    /** 权限是否覆盖某个工具调用（`tools:taffy_so_*` 支持通配）。 */
    fun allowsTool(permissions: List<String>, toolName: String): Boolean {
        if (permissions.isEmpty()) return false
        return permissions.any { p ->
            when {
                p == "tools:*" -> true
                p.startsWith("tools:") -> {
                    val pattern = p.removePrefix("tools:").trim()
                    pattern == toolName || (pattern.endsWith("*") && toolName.startsWith(pattern.dropLast(1)))
                }
                else -> false
            }
        }
    }

    /** 全量诊断：统计 + 每个插件的问题列表 + 权限概览。 */
    fun diagnose(context: Context): JSONObject {
        val plugins = list(context)
        val healthy = plugins.count { it.issues.isEmpty() }
        val perms = linkedMapOf<String, Int>()
        plugins.forEach { p -> p.permissions.forEach { perms[it] = (perms[it] ?: 0) + 1 } }
        val permObj = JSONObject()
        perms.forEach { (k, v) -> permObj.put(k, v) }

        val arr = JSONArray()
        plugins.forEach { arr.put(it.toJson()) }

        val issues = JSONArray()
        plugins.filter { it.issues.isNotEmpty() }.forEach { p ->
            p.issues.forEach { issue ->
                issues.put(JSONObject().put("plugin", p.id).put("issue", issue))
            }
        }

        return JSONObject()
            .put("root", pluginsRoot(context).absolutePath)
            .put("total", plugins.size)
            .put("healthy", healthy)
            .put("problematic", plugins.size - healthy)
            .put("permissions", permObj)
            .put("plugins", arr)
            .put("issues", issues)
            .put(
                "note",
                "诊断只读：不执行插件代码、不修改任何文件。硬阻断项（缺入口、版本不兼容）会让该插件无法运行；" +
                    "权限未声明只作提示，老插件仍可运行但按只读文件对待。"
            )
    }

    // ── 运行审计 ─────────────────────────────────────────────

    private const val PREFS = "taffy_plugin_prefs"
    private const val KEY_AUDIT = "run_audit"
    private const val MAX_AUDIT = 40

    /** 记录一次运行结果（只留 id/时间/成败/摘要，不记录插件输出正文）。 */
    fun recordRun(context: Context, id: String, ok: Boolean, note: String) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = runCatching { JSONArray(prefs.getString(KEY_AUDIT, "[]")) }.getOrNull() ?: JSONArray()
            val next = JSONArray()
            next.put(
                JSONObject()
                    .put("id", id)
                    .put("at", System.currentTimeMillis())
                    .put("ok", ok)
                    .put("note", note.take(300))
            )
            for (i in 0 until arr.length()) {
                if (next.length() >= MAX_AUDIT) break
                next.put(arr.opt(i))
            }
            prefs.edit().putString(KEY_AUDIT, next.toString()).apply()
        }
    }

    fun audit(context: Context, limit: Int = 20): JSONArray {
        val arr = runCatching {
            JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_AUDIT, "[]"))
        }.getOrNull() ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until minOf(arr.length(), limit.coerceIn(1, MAX_AUDIT))) out.put(arr.opt(i))
        return out
    }

    // ── 版本比较 ─────────────────────────────────────────────

    fun appVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /** 逐段数字比较；缺失段视为 0，非数字段按字典序兜底。 */
    fun versionAtLeast(current: String, required: String): Boolean {
        if (required.isBlank()) return true
        fun seg(s: String) = s.trim().trimStart('v').split('.', '-', '+', ' ')
        val a = seg(current)
        val b = seg(required)
        for (i in 0 until maxOf(a.size, b.size)) {
            // 缺失段按 0 处理：1.4 与 1.4.0 必须等价
            val x = a.getOrNull(i).orEmpty().ifBlank { "0" }
            val y = b.getOrNull(i).orEmpty().ifBlank { "0" }
            val xn = x.toIntOrNull()
            val yn = y.toIntOrNull()
            when {
                xn != null && yn != null -> {
                    if (xn != yn) return xn > yn
                }
                x == y -> Unit
                else -> {
                    if (y.isBlank()) return true
                    if (x.isBlank()) return false
                    return x > y
                }
            }
        }
        return true
    }
}
