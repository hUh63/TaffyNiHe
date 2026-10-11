package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject

/**
 * 塔菲逆核: OLLVM 控制流平坦化「去平坦化」。
 *
 * 控制流平坦化把每个真实块 b 的出口改成「写状态常量 S，跳回分发器 D」，由 D 按 S 分派。
 * 本工具把这些出口跳转**等长重定向**到真实后继 T，从而消掉分发器。
 *
 * 取数 + 规划在引擎（EngineRuntime.deflatAnalyze，分析报告也复用同一实现）；
 * 本工具只负责：dryRun 出计划 / 经编辑内核 editHexVa 等长写入（带快照/undo/redo）。
 *
 * 安全边界：
 *  - cmp 链分派：`cmp #S; b.cond T` 是直接证据，重定向默认可写；
 *  - 跳表分派：解表得到映射但含「索引=状态值」假设，默认只读展示，需 allowTable=true 才写；
 *  - 其余（状态不可识别 / 越界 / 不支持编码）如实标注原因，绝不改写。
 */
object SoDeFlattenTool {

    private fun hexToBytes(hex: String): ByteArray? {
        val h = hex.replace(Regex("[^0-9a-fA-F]"), "")
        if (h.isEmpty() || h.length % 2 != 0) return null
        return runCatching { h.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()
    }

    private val tool: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_so_deflatten",
            "【去平坦化(OLLVM)】检测控制流平坦化并生成/应用「出口跳转重定向」补丁：把每个真实块末尾跳回分发器的跳转，等长改写为直接跳到真实后继，从而消掉分发器。仅处理「出口指向分发器 + 状态可识别 + 分发器解析出唯一目标 + 可等长编码」的块，其余如实标注原因不写。dryRun 默认 true（只出计划）；dryRun=false 经编辑内核 editHexVa 等长写入（带快照/undo），返回 editSessionId 供后续重新分析。分发器支持 cmp 链与跳表(switch)两类；跳表解出的映射默认只读展示（需 allowTable=true 才写入）。返回 compare（前后块/边数）与 afterCfg（去平坦化后的 CFG JSON）供对比图使用。",
            "De-flatten OLLVM control-flow flattening: retarget each block's tail jump (to the dispatcher) directly to its real successor (equal-length rewrite). Only blocks with a resolvable single successor and encodable branch are patched; everything else is reported, never written. Supports cmp-chain and jump-table dispatchers; jump-table-derived mappings are report-only unless allowTable=true. dryRun defaults to true (plan only); dryRun=false applies via the edit kernel (snapshot/undo). Returns compare (before/after block & edge counts) and afterCfg (de-flattened CFG JSON) for the comparison view.",
            "edit", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "workspaceId" str "工作区 id（必填）"
                "editSessionId" str "编辑会话 id（写入时留空自动新建；给出则写入该会话）"
                "locator" str "目标函数：符号名或 0xVA（必填）"
                "arch" str "架构 arm64/arm32/x86/x86_64（留空按 ELF e_machine 推断）"
                "maxInsns" str "每个函数最多反汇编的指令数（默认 3000）"
                "allowTable" str "true=允许写入跳表(switch)解出的重定向（默认 false，仅展示）"
                "dryRun" str "true=只返回计划（默认 true）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val ws = args.str("workspaceId")
            if (ws.isBlank()) return err("INVALID_ARGUMENT", "workspaceId 必填")
            val locator = args.str("locator").trim()
            if (locator.isBlank()) return err("INVALID_ARGUMENT", "locator 必填")

            val eng = EngineProvider.get(ctx.context)
            val arch = args.str("arch")
            val maxInsns = args.str("maxInsns").toIntOrNull()?.coerceIn(64, 20000) ?: 3000
            val allowTable = args.str("allowTable", "false").toBoolean()

            if (args.str("dryRun", "true").toBoolean()) {
                return eng.deflatAnalyze(ws, args.str("editSessionId"), locator, arch, maxInsns, allowTable)
            }

            val plan = eng.deflatAnalyze(ws, args.str("editSessionId"), locator, arch, maxInsns, allowTable)
            if (plan.optJSONObject("error") != null) return plan
            val patches = plan.optJSONArray("patches") ?: JSONArray()
            if (patches.length() == 0) return plan.put("dryRun", false).put("applied", 0)

            var sid = args.str("editSessionId")
            if (sid.isBlank()) {
                val eo = eng.editOpen(ws)
                sid = eo.optString("editSessionId")
                    .ifBlank { eo.optJSONObject("data")?.optString("editSessionId").orEmpty() }
            }
            if (sid.isBlank()) return err("EDIT_SESSION_OPEN_FAILED", "无法创建编辑会话")

            val applied = JSONArray()
            for (i in 0 until patches.length()) {
                val p = patches.optJSONObject(i) ?: continue
                val bytes = hexToBytes(p.optString("newHex"))
                    ?: return err("PATCH_ENCODE_FAILED", "补丁 newHex 无效")
                val vs = p.optString("insn")
                val va = if (vs.startsWith("0x", true)) vs.substring(2).toLongOrNull(16) else vs.toLongOrNull()
                if (va == null) return err("PATCH_ENCODE_FAILED", "补丁地址无效")
                val res = runCatching { eng.editHexVa(ws, sid, va, bytes, false) }.getOrNull()
                    ?: return err("PATCH_FAILED", "写入失败 @ " + vs)
                res.optJSONObject("error")?.let { return it }
                applied.put(p)
            }

            return plan
                .put("dryRun", false)
                .put("editSessionId", sid)
                .put("applied", applied.length())
                .put("appliedPatches", applied)
                .put("hint", "已写入编辑会话；用该 editSessionId 重新查询 CFG / 伪 C 即看去平坦化结果，确认后 build 落盘。")
        }
    }

    val ALL = listOf(tool)
}
