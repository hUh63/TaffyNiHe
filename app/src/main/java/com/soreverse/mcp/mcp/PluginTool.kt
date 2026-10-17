package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.PluginManager
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject

/**
 * 塔菲逆核: 插件平台网关。
 *
 * 让 AI/外部客户端能「看见并核对」本机装了哪些插件、能不能跑、为什么不能跑、跑过什么——
 * 而不是只能在设置页里用眼睛看。刻意**不提供安装/卸载**：装什么由用户手动决定，
 * 模型只负责诊断与解释。
 */
object PluginTool {

    val gateway: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_plugin",
            "【插件平台】查看与诊断本机安装的扩展插件（filesDir/plugins）。action=list(默认) 列出插件清单（id/名称/版本/入口/权限/是否可运行）；diagnose 给出全量诊断（健康数、问题插件、逐条 issue、权限统计）；check 校验指定插件能否运行并给出拒绝原因；audit 查看最近的插件运行记录（时间/成败/摘要）。只读：不执行插件代码、不安装卸载、不改文件。",
            "Inspect and diagnose locally installed extensions (filesDir/plugins). action=list (default) returns the manifest (id/name/version/entry/permissions/runnable); diagnose returns a full health report (healthy count, problematic plugins, per-issue rows, permission histogram); check validates whether a plugin can run and why not; audit shows recent run records (time/ok/summary). Read-only: never executes plugin code, never installs or removes anything.",
            "runtime",
            ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "action" str "list(默认)=插件清单 | diagnose=全量诊断 | check=校验能否运行 | audit=运行记录"
                "id" str "check 必填：插件 id（目录名）"
                "limit" int "audit 返回条数（默认 20）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "list").ifBlank { "list" }.lowercase()
            return when (action) {
                "list" -> {
                    val plugins = PluginManager.list(ctx.context)
                    val arr = JSONArray()
                    plugins.forEach { arr.put(it.toJson()) }
                    ok(
                        JSONObject()
                            .put("root", PluginManager.pluginsRoot(ctx.context).absolutePath)
                            .put("count", plugins.size)
                            .put("runnable", plugins.count { it.issues.isEmpty() })
                            .put("plugins", arr)
                    )
                }

                "diagnose" -> ok(PluginManager.diagnose(ctx.context))

                "check" -> {
                    val id = args.str("id").trim()
                    if (id.isBlank()) {
                        err("INVALID_ARGUMENT", "check 需要 id（插件目录名）", "id", "")
                    } else {
                        val reason = PluginManager.checkRunAllowed(ctx.context, id)
                        val info = PluginManager.list(ctx.context).firstOrNull { it.id == id }
                        if (info == null) {
                            err("NOT_FOUND", "插件不存在：$id", "id", id)
                        } else {
                            ok(
                                JSONObject()
                                    .put("id", id)
                                    .put("allowed", reason == null)
                                    .put("reason", reason ?: JSONObject.NULL)
                                    .put("plugin", info.toJson())
                            )
                        }
                    }
                }

                "audit" -> ok(
                    JSONObject().put("runs", PluginManager.audit(ctx.context, args.intValue("limit", 20)))
                )

                else -> err(
                    "INVALID_ARGUMENT",
                    "未知 action: $action（可选 list / diagnose / check / audit）",
                    "action",
                    action
                )
            }
        }
    }

    val ALL: List<ToolHandler> = listOf(gateway)
}
