package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.core.SigningKeyStore
import com.soreverse.mcp.core.TempWorkspaceManager
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import com.soreverse.mcp.core.intValue
import org.json.JSONObject

/**
 * 设置页「MCP 工具」分组的 MCP 侧能力：
 *  - taffy_signing_keys:     APK 签名密钥管理（list/import/delete/active）——密钥管理页后端；
 *  - taffy_temp_workspace:   临时工作区管理（stats/clean/prune/set_limit）——临时工作区卡片后端。
 */
object McpToolsConfig {

    /** APK 签名密钥管理（对应设置页「APK 签名设置 → 其他密钥 → 密钥管理页」）。 */
    val signingKeys: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_signing_keys",
            "【APK 签名密钥管理】管理导入的自定义签名密钥。action=list 列出已导入密钥(含证书主题); action=import 导入 keystore 文件(需口令+别名); action=delete 删除指定密钥; action=active 查看/设置当前使用的密钥。与设置页「APK 签名设置」共用同一密钥库。",
            "Manage imported APK signing keystores: list / import (keystore file + store pass + alias) / delete / active. Shared with the Settings > MCP tools > APK signing key store.",
            "build", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "action".oneOf("list | import | delete | active", "list", "import", "delete", "active")
                "sourcePath" str "import: 外部 keystore 文件路径(.jks/.p12, 绝对路径)"
                "alias" str "import: 密钥别名"
                "storePass" str "import: keystore 口令"
                "keyPass" str "import: 密钥口令(默认同 storePass)"
                "name" str "delete: 要删除的密钥文件名"
                "setActive" str "active: 设为当前使用(需已在密钥库中); 空则仅查询"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "list")
            return when (action) {
                "list" -> ok(JSONObject()
                    .put("keys", SigningKeyStore.list(ctx.context))
                    .put("active", SettingsStore(ctx.context).apkSignKeystoreName))
                "import" -> {
                    val src = args.str("sourcePath")
                    val alias = args.str("alias")
                    val pass = args.str("storePass")
                    if (src.isBlank()) return err("INVALID_ARGUMENT", "缺少 sourcePath", "sourcePath", "")
                    if (alias.isBlank()) return err("INVALID_ARGUMENT", "缺少 alias", "alias", "")
                    if (pass.isBlank()) return err("INVALID_ARGUMENT", "缺少 storePass", "storePass", "")
                    SigningKeyStore.import(ctx.context, src, alias, pass, args.str("keyPass").takeIf { it.isNotBlank() })
                }
                "delete" -> {
                    val name = args.str("name")
                    if (name.isBlank()) return err("INVALID_ARGUMENT", "缺少 name", "name", "")
                    val deleted = SigningKeyStore.delete(ctx.context, name)
                    if (deleted) ok(JSONObject().put("deleted", name))
                    else err("NOT_FOUND", "密钥不存在或删除失败: $name", "name", name)
                }
                "active" -> {
                    val settings = SettingsStore(ctx.context)
                    val setActive = args.str("setActive")
                    if (setActive.isNotBlank()) {
                        val exists = SigningKeyStore.list(ctx.context).let { arr ->
                            (0 until arr.length()).any { arr.optJSONObject(it)?.optString("name") == setActive }
                        }
                        if (!exists) return err("NOT_FOUND", "密钥库中不存在: $setActive", "setActive", setActive)
                        settings.apkSignKeystoreName = setActive
                        settings.apkSignKeySource = "custom"
                    }
                    ok(JSONObject()
                        .put("active", settings.apkSignKeystoreName)
                        .put("keySource", settings.apkSignKeySource))
                }
                else -> err("UNKNOWN_ACTION", "未知 action: $action", "action", action)
            }
        }
    }

    /** 临时工作区管理（对应设置页「临时工作区」卡片）。 */
    val tempWorkspace: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_temp_workspace",
            "【临时工作区管理】管理 MCP 工具产生的临时工作区(smali-batch/apkeditor-out/extracted/jadx-out 等)。action=stats 查看数量与占用; action=clean 一键清理全部; action=prune 按设置的数量上限裁剪最旧的; action=set_limit 设置临时工作区数量上限。",
            "Manage MCP temp workspaces (smali-batch/apkeditor-out/extracted/jadx-out etc.): stats / clean all / prune to the configured limit / set_limit.",
            "workspace", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("stats | clean | prune | set_limit", "stats", "clean", "prune", "set_limit")
                "limit" int "set_limit: 临时工作区数量上限(1..100)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "stats")
            val settings = SettingsStore(ctx.context)
            return when (action) {
                "stats" -> ok(JSONObject()
                    .put("count", TempWorkspaceManager.count(ctx.context))
                    .put("limit", settings.tempWorkspaceLimit)
                    .put("roots", TempWorkspaceManager.stats(ctx.context)))
                "clean" -> ok(JSONObject()
                    .put("removed", TempWorkspaceManager.cleanAll(ctx.context))
                    .put("count", TempWorkspaceManager.count(ctx.context)))
                "prune" -> ok(JSONObject()
                    .put("removed", TempWorkspaceManager.pruneToLimit(ctx.context))
                    .put("count", TempWorkspaceManager.count(ctx.context))
                    .put("limit", settings.tempWorkspaceLimit))
                "set_limit" -> {
                    val limit = args.intValue("limit", 8)
                    settings.tempWorkspaceLimit = limit
                    ok(JSONObject().put("limit", settings.tempWorkspaceLimit))
                }
                else -> err("UNKNOWN_ACTION", "未知 action: $action", "action", action)
            }
        }
    }

    val ALL: List<ToolHandler> = listOf(signingKeys, tempWorkspace)
}
