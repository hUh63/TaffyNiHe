package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject

/**
 * 塔菲逆核: 常用逆向 patch 模板库。
 *
 * 把 MT 高频操作封装成「选模板 + 填参数 → 一步执行」的预设步骤(复用现有工具):
 *  - resign_apk            重新签名 APK
 *  - dex_string_global_replace  整个 APK 全局字符串替换
 *  - apk_patch_bytes_write  APK 内 ZIP 条目 CAS 字节写入
 *  - apk_add_permission     decode 目录里给 AndroidManifest 加权限
 *  - dex_string_patch_single 单方法字符串替换
 * 模板步骤支持 ${resultKey.路径} 占位符(复用 BatchTemplateResolver)。
 */
object PatchTemplateTool {

    private data class Tpl(val name: String, val zh: String, val params: List<String>, val steps: List<Pair<String, JSONObject>>)

    private val templates: List<Tpl> = listOf(
        Tpl(
            "resign_apk",
            "重新签名 APK(用内置密钥 v1/v2/v3)",
            listOf("apk"),
            listOf(
                "taffy_apk_sign" to JSONObject().put("inputApk", "\${p.apk}"),
            ),
        ),
        Tpl(
            "dex_string_global_replace",
            "全局字符串替换(整个 APK 所有 DEX 内替换某 const-string)",
            listOf("path", "oldString", "newString", "mode"),
            listOf(
                "taffy_smali_edit" to JSONObject()
                    .put("action", "replace_string")
                    .put("scope", "global")
                    .put("path", "\${p.path}")
                    .put("oldString", "\${p.oldString}")
                    .put("newString", "\${p.newString}")
                    .put("mode", "\${p.mode}"),
            ),
        ),
        Tpl(
            "dex_string_patch_single",
            "单方法字符串替换(定位到类+方法内替换)",
            listOf("path", "className", "method", "oldString", "newString", "mode"),
            listOf(
                "taffy_smali_edit" to JSONObject()
                    .put("action", "replace_string")
                    .put("scope", "method")
                    .put("path", "\${p.path}")
                    .put("className", "\${p.className}")
                    .put("method", "\${p.method}")
                    .put("oldString", "\${p.oldString}")
                    .put("newString", "\${p.newString}")
                    .put("mode", "\${p.mode}"),
            ),
        ),
        Tpl(
            "apk_patch_bytes_write",
            "APK 内 ZIP 条目 CAS 字节写入(如改 so/dex 指定偏移字节)",
            listOf("path", "entry", "offset", "hex", "expectedHex"),
            listOf(
                "taffy_apk_patch_bytes" to JSONObject()
                    .put("action", "write")
                    .put("path", "\${p.path}")
                    .put("entry", "\${p.entry}")
                    .put("offset", "\${p.offset}")
                    .put("hex", "\${p.hex}")
                    .put("expectedHex", "\${p.expectedHex}"),
            ),
        ),
        Tpl(
            "apk_add_permission",
            "给 decode 目录的 AndroidManifest 加权限",
            listOf("dir", "permission"),
            listOf(
                "taffy_apk_manifest_edit" to JSONObject()
                    .put("action", "add_permission")
                    .put("dir", "\${p.dir}")
                    .put("permission", "\${p.permission}"),
            ),
        ),
    )

    val tool: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_patch_template",
            "【常用 Patch 模板】把 MT 高频逆向操作封装成一步调用(复用现有工具)。action=list 列出内置模板及所需参数; action=apply 选模板并填参数执行。内置: resign_apk(重签名) / dex_string_global_replace(全局字符串替换) / dex_string_patch_single(单方法字符串替换) / apk_patch_bytes_write(CAS字节写) / apk_add_permission(加权限)。对标 MT 的一键改包常用操作。",
            "Common reverse-engineering patch templates that call existing tools in one step. action=list shows built-in templates + required params; action=apply runs one. Templates: resign_apk / dex_string_global_replace / dex_string_patch_single / apk_patch_bytes_write / apk_add_permission. Mirrors MT-style one-click common patches.",
            "apk", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("模板操作", "list(列出模板) | apply(执行模板)", "list", "apply")
                "template" str "apply: 模板名(来自 list)"
                "args" str "apply: 模板参数(JSON 对象, 键见 list 的 params, 如 {\"path\":\"/x.apk\"})"
                "stopOnError" bool "apply: 步骤出错是否停止(默认 true)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            return when (args.str("action", "list")) {
                "list" -> {
                    val arr = JSONArray()
                    templates.forEach { t ->
                        arr.put(JSONObject()
                            .put("name", t.name)
                            .put("description", t.zh)
                            .put("params", JSONArray(t.params))
                            .put("steps", JSONArray(t.steps.map { it.first })))
                    }
                    ok(JSONObject().put("tool", "taffy_patch_template").put("templates", arr))
                }
                "apply" -> applyTemplate(ctx, args)
                else -> err("UNKNOWN_ACTION", "未知 action: ${args.str("action")}", "action", args.str("action"))
            }
        }

        private fun applyTemplate(ctx: ToolContext, args: JSONObject): JSONObject {
            val name = args.str("template")
            if (name.isBlank()) return err("INVALID_ARGUMENT", "缺少 template", "template", "")
            val tpl = templates.firstOrNull { it.name == name }
                ?: return err("TEMPLATE_NOT_FOUND", "模板不存在: $name", "template", name)
            val params = args.optJSONObject("args") ?: JSONObject()
            val missing = tpl.params.filter { params.isBlank(it) }
            if (missing.isNotEmpty()) return err("MISSING_PARAMS", "缺少参数: ${missing.joinToString(", ")}", "args", missing.joinToString(","))

            val stopOnError = args.optBoolean("stopOnError", true)
            val keyed = HashMap<String, JSONObject>()
            val stepsJson = JSONArray()
            val results = JSONArray()
            // 用参数作为占位符上下文
            keyed["p"] = params
            for ((idx, pair) in tpl.steps.withIndex()) {
                val toolName = pair.first
                // 先替换参数占位符: ${p.xxx}
                val argsObj = pair.second
                val resolved = BatchTemplateResolver.substitute(argsObj, keyed)
                // 再按模板语义直接调用工具(单步模板无 resultKey 链, 但保留通用性)
                val handler = ToolCatalog.byName[toolName]
                val payload = if (handler != null) {
                    runCatching { handler.handle(ctx, resolved) }.getOrElse { JSONObject().put("ok", false).put("error", JSONObject().put("message", it.message ?: "tool exception")) }
                } else JSONObject().put("ok", false).put("error", JSONObject().put("message", "tool not found: $toolName"))
                val okFlag = payload.optBoolean("ok", true)
                results.put(JSONObject().put("step", idx).put("tool", toolName).put("arguments", resolved).put("ok", okFlag).put("result", payload))
                if (!okFlag && stopOnError) {
                    return ok(JSONObject().put("tool", "taffy_patch_template").put("template", name).put("steps", results).put("executedCount", idx + 1).put("aborted", true))
                }
            }
            return ok(JSONObject().put("tool", "taffy_patch_template").put("template", name).put("steps", results).put("executedCount", results.length()).put("aborted", false))
        }

        private fun JSONObject.isBlank(key: String): Boolean {
            val v = optString(key)
            return v.isBlank()
        }
    }

    val ALL = listOf(tool)
}
