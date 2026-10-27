package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.DeepAnalysisService
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONObject

/**
 * 借鉴 SOMCP v1.0.23 的工具目录扩容（PR #173）里「引擎已实现、此前只有 UI 能调到」的那几项，
 * 外加 AI 静态侧的对等入口。
 *
 * 这批工具的共同点：**能力早就在 engine / service 层了，只是没长 MCP 出口**。所以它们不改
 * 任何既有行为，纯粹是把已经验证过的代码路径接到工具目录上。
 */
object WorkspaceExtrasTools {

    private const val DEFAULT_EXPORT_BYTES = 32 * 1024 * 1024

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val overview = EngineToolHandler(
        ToolMeta(
            "taffy_workspace_overview",
            "【工作区概要】一次拿全「这个 SO 是什么」：架构/入口/节区与符号计数/加固指纹/依赖库。" +
                "比逐个 read_stats 省调用，适合作为分析的第一步。",
            "One-shot workspace overview: architecture, entry point, section/symbol counts, hardening " +
                "fingerprints and dependencies. Cheaper than several read_stats calls; good first step.",
            "workspace",
            ToolClass.EXTRA,
        ) {
            objectSchema(
                props {
                    "workspaceId" str "工作区 ID（留空则按 pathHint 或当前唯一工作区解析）"
                    "editSessionId" str "编辑会话 ID（可选）"
                    "pathHint" str "路径提示（workspaceId 为空时用于定位工作区）"
                },
            )
        }
    ) { e, a, _ -> e.overview(a.str("workspaceId"), a.str("editSessionId"), a.str("pathHint")) }

    private val cacheClear = EngineToolHandler(
        ToolMeta(
            "taffy_cache_clear",
            "【清缓存】清掉扫描结果与分页缓存（符号表/字符串/反汇编分页）。" +
                "改过字节后如果列表仍是旧数据，调它再重取。不影响已打开的工作区与编辑会话。",
            "Clear scan results and pagination caches (symbols/strings/disassembly pages). Call it after " +
                "byte edits when lists still show stale data. Open workspaces and edit sessions are kept.",
            "workspace",
            ToolClass.EXTRA,
        ) {
            objectSchema(props {})
        }
    ) { e, _, _ ->
        runCatching { e.clearCaches() }
            .fold(
                onSuccess = { ok(JSONObject().put("cleared", true)) },
                onFailure = { err("CACHE_CLEAR_FAILED", it.message ?: "clearCaches failed") },
            )
    }

    private val export = EngineToolHandler(
        ToolMeta(
            "taffy_workspace_export",
            "【导出字节】把当前工作区（含编辑会话改动）的 SO 字节以 base64 返回，附 SHA-256 与架构。" +
                "默认上限 32 MiB，超过直接报错而不是截断——截断的 SO 拿到手是废的。" +
                "需要落盘请用 taffy_build_so。",
            "Export the current workspace bytes (including edit-session changes) as base64 with SHA-256 " +
                "and architecture. Default cap 32 MiB; exceeding it is an error, never a silent truncation " +
                "(a truncated SO is useless). Use taffy_build_so to write a file instead.",
            "workspace",
            ToolClass.EXTRA,
            heavy = true,
        ) {
            objectSchema(
                props {
                    "workspaceId" str "工作区 ID"
                    "editSessionId" str "编辑会话 ID（提供时导出含补丁的字节）"
                    "maxBytes" int "返回上限，默认 33554432（32 MiB）"
                },
            )
        }
    ) { e, a, _ ->
        val cap = a.intValue("maxBytes", DEFAULT_EXPORT_BYTES).coerceIn(1024, DEFAULT_EXPORT_BYTES)
        val (bytes, arch) = e.snapshotBytes(a.str("workspaceId"), a.str("editSessionId"))
        if (bytes.size > cap) {
            err(
                "EXPORT_TOO_LARGE",
                "导出 ${bytes.size} 字节超过上限 $cap（base64 会再放大约 1.33 倍）。改用 taffy_build_so 落盘，或用 taffy_read_hexdump 分段取。",
                "maxBytes", cap,
            )
        } else {
            ok(
                JSONObject()
                    .put("architecture", arch)
                    .put("size", bytes.size)
                    .put("sha256", sha256Hex(bytes))
                    .put("base64", Base64.getEncoder().encodeToString(bytes)),
            )
        }
    }

    private val outline = EngineToolHandler(
        ToolMeta(
            "taffy_analyze_outline",
            "【函数结构速览】给出某个函数的骨架：基本块、调用/被调用、字符串引用与分支目标，" +
                "不返回逐条指令。用来先看清结构再决定要不要读全过程反汇编。",
            "Function outline: basic blocks, calls/callees, string references and branch targets — " +
                "without per-instruction detail. Use it to decide whether full disassembly is worth reading.",
            "analyze",
            ToolClass.EXTRA,
        ) {
            objectSchema(
                props {
                    "workspaceId" str "工作区 ID"
                    "editSessionId" str "编辑会话 ID（可选）"
                    "locator" str "函数定位：符号名或 0x 十六进制地址（必须是函数入口）"
                    "limit" int "条目上限（默认由设置决定）"
                },
            )
        }
    ) { e, a, s ->
        e.outline(a.str("workspaceId"), a.str("editSessionId"), a.str("locator"), a.intValue("limit", s.defaultLimit))
    }

    private val staticAnalyzeAi = EngineToolHandler(
        ToolMeta(
            "taffy_static_analyze_ai",
            "【静态分析 AI】走 App 内 AI 配置，对静态证据（ELF 结构/符号/字符串/加固指纹等）做自主取证并输出" +
                "结构化报告。是 taffy_dynamic_analyze_ai 的静态对等入口：动态那边看运行期，这边看文件本身。" +
                "AI 会自行调用静态工具补证；重活，受并发闸门限制。",
            "Run the standalone static-analysis AI over file-side evidence (ELF structure, symbols, strings, " +
                "hardening fingerprints...) and produce a structured report. Static counterpart of " +
                "taffy_dynamic_analyze_ai. The agent calls static MCP tools itself for follow-up evidence.",
            "analyze",
            ToolClass.EXTRA,
            heavy = true,
        ) {
            objectSchema(
                props {
                    "path" str "目标 SO 路径（本地绝对路径或工作目录内的相对路径）"
                    "workspaceId" str "工作区 ID（path 为空时用它定位）"
                    "request" str "本轮分析的用户关注点"
                },
            )
        }
    ) { _, a, ctx ->
        // EngineToolHandler 的第三参是 SettingsStore，不是 ToolContext；取 Context 走统一入口
        val appContext = com.soreverse.mcp.core.WorkspacePolicy.appContext()
            ?: return@EngineToolHandler err("CONTEXT_UNAVAILABLE", "应用上下文不可用", "path", a.str("path"))
        val target = a.str("path").ifBlank { a.str("workspaceId") }
        if (target.isBlank()) {
            return@EngineToolHandler err("INVALID_ARGUMENT", "path 或 workspaceId 至少给一个", "path", "")
        }
        DeepAnalysisService(appContext)
            .analyzeSync(target, ctx.settings, zh = true, request = a.str("request"))
            .fold(
                onSuccess = { ok(JSONObject().put("report", it).put("target", target)) },
                onFailure = { err("STATIC_AI_FAILED", it.message ?: "AI static analysis failed", "path", target) },
            )
    }

    val ALL: List<ToolHandler> = listOf(overview, cacheClear, export, outline, staticAnalyzeAi)
}
