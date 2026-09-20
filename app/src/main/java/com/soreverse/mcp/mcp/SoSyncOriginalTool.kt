package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.bool
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.str
import org.json.JSONObject

/**
 * 塔菲逆核: 就地写回原始 SO。
 *
 * 与 taffy_build_so 的区别：taffy_build_so 另存新文件（build 目录），本工具把编辑会话的当前字节
 * **写回工作区源文件本身**，写前备份、写后回读 sha256 校验，用于「改完即生效」的工作流。
 */
object SoSyncOriginalTool {

    val syncOriginal: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_so_sync_original",
            "【就地写回原始 SO】把某个编辑会话的当前字节写回工作区源文件本身（不是另存新文件），写前自动备份为 <原名>.bak，写后立即从磁盘回读并对比 sha256 校验。dryRun 默认 true（只预览差异），确认无误后再用 dryRun=false 执行写入。",
            "Write an edit session's current bytes back into the workspace's ORIGINAL SO file in place (not a new file). Backs the original up to <name>.bak first, then re-reads the file from disk and verifies sha256. dryRun defaults to true (preview only); call again with dryRun=false to actually write.",
            "build", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(
                props {
                    "workspaceId" str "工作区 ID（taffy_so_open 返回）"
                    "editSessionId" str "编辑会话 ID（taffy_session_open / taffy_edit_hex / taffy_edit_symbol 返回）"
                    "dryRun" bool "为 true（默认）时只预览不写入磁盘"
                    "backup" bool "为 true（默认）时写入前把原文件另存为 <原名>.bak"
                },
                required = listOf("workspaceId", "editSessionId"),
            )
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val workspaceId = args.str("workspaceId")
            if (workspaceId.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 workspaceId", "workspaceId", "")
            val editSessionId = args.str("editSessionId")
            if (editSessionId.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 editSessionId", "editSessionId", "")
            return ctx.engine.soSyncOriginal(
                workspaceId,
                editSessionId,
                args.bool("dryRun", true),
                args.bool("backup", true),
            )
        }
    }

    val ALL: List<ToolHandler> = listOf(syncOriginal)
}
