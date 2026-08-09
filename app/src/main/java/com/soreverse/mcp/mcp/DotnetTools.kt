package com.soreverse.mcp.mcp

import com.dsmcp.tool.engine.BinaryEngine
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.core.HexCodec
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.bool
import com.soreverse.mcp.core.str
import com.soreverse.mcp.core.err
import org.json.JSONObject

/**
 * PE/.NET 工具适配器 —— 将 DSMCP 的 [BinaryEngine] 桥接到 SOMCP 工具分发系统。
 *
 * 每个处理器是 [BinaryEngineToolHandler]，从 [ToolContext] 取出
 * [BinaryEngine] 并转发调用。若 BinaryEngine 未初始化（native 库加载失败），
 * 返回明确错误而非崩溃。
 *
 * BinaryEngine 同时支持 ELF (.so) 和 PE (.dll/.exe) 格式，但 .NET 专属操作
 * （dotnet_*）仅对 PE 文件有效。PE 专属的原始编辑工具（taffy_pe_edit_hex / taffy_pe_edit_va /
 * taffy_pe_edit_section）补充了 somcp 原有 edit_* 工具仅支持 ELF 的缺口。
 */
object DotnetTools {

    // ── PE 工作区管理 ──

    val peOpen = BinaryEngineToolHandler(
        ToolMeta("taffy_pe_open",
            "【PE/.NET 分析入口】打开 PE/DLL/EXE 文件并创建工作区（自动检测格式，也支持 ELF）。所有 PE/.NET 文件操作必须从 taffy_pe_open 开始。",
            "Open a PE (.dll/.exe) or ELF (.so) file and create a BinaryEngine workspace. Auto-detects file format. All PE/.NET operations MUST start from taffy_pe_open. Use action=list to see open workspaces.",
            "dotnet", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "action".oneOf("open (default) | list", "open", "list")
            "path" str "PE/DLL/EXE 文件的绝对路径（action=open）"
            "filePath" str "path 的别名"
            "temporary" bool "为 true 时工作区重启后不保留"
        }) }
    ) { e, a, _ ->
        when (a.str("action", "open")) {
            "list" -> e.listWorkspaces()
            else -> e.open(a.str("path").ifBlank { a.str("filePath") }, a.bool("temporary", true))
        }
    }

    val peClose = BinaryEngineToolHandler(
        ToolMeta("taffy_pe_close",
            "关闭 PE 工作区（action=list 列出已打开工作区）",
            "Close a PE workspace. Use action=list to see open workspaces.",
            "dotnet", ToolClass.CORE,
        ) { objectSchema(props {
            "action".oneOf("close (default) | list", "close", "list")
            "workspaceId" str "工作区 ID（action=close）"
        }) }
    ) { e, a, _ ->
        when (a.str("action", "close")) {
            "list" -> e.listWorkspaces()
            else -> e.close(a.str("workspaceId"))
        }
    }

    // ── PE 结构分析 ──

    val peAnalyze = BinaryEngineToolHandler(
        ToolMeta("taffy_pe_analyze",
            "PE 结构分析（节区/导入/导出/资源）",
            "Full PE structure analysis: sections, imports, exports, resources via native parser.",
            "dotnet", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（可选，为空则用原始文件）"
        }) }
    ) { e, a, _ -> e.analyze(a.str("workspaceId"), a.str("editSessionId").ifBlank { null }) }

    // ── .NET 分析 ──

    val dotnetDetect = BinaryEngineToolHandler(
        ToolMeta("taffy_dotnet_detect",
            "检测 .NET/Mono 程序集并返回诊断信息（MZ/PE/CLR 头、BSJB 签名、流信息）",
            "Detect .NET/Mono assembly and return diagnostics: MZ/PE/CLR headers, BSJB signature, stream info.",
            "dotnet", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（可选）"
        }) }
    ) { e, a, _ -> e.dotnetDetect(a.str("workspaceId"), a.str("editSessionId").ifBlank { null }) }

    val dotnetListTypes = BinaryEngineToolHandler(
        ToolMeta("taffy_dotnet_list_types",
            "列出 .NET 程序集中的所有类型（类名/命名空间/类型标记）",
            "List all .NET types in the assembly (class name, namespace, type token).",
            "dotnet", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（可选）"
        }) }
    ) { e, a, _ -> e.dotnetListTypes(a.str("workspaceId"), a.str("editSessionId").ifBlank { null }) }

    val dotnetListMethods = BinaryEngineToolHandler(
        ToolMeta("taffy_dotnet_list_methods",
            "列出 .NET 类型的方法（方法名/标记/IL 偏移/RVA）",
            "List .NET methods (name, token, IL offset, RVA). Use typeFilter to narrow to a specific type token.",
            "dotnet", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（可选）"
            "typeFilter" int "过滤方法的类型令牌（0 = 所有类型）"
        }) }
    ) { e, a, _ -> e.dotnetListMethods(a.str("workspaceId"), a.str("editSessionId").ifBlank { null }, a.intValue("typeFilter", 0)) }

    val dotnetListStrings = BinaryEngineToolHandler(
        ToolMeta("taffy_dotnet_list_strings",
            "列出 .NET 用户字符串（US 堆内容）",
            "List .NET user strings from the #US heap.",
            "dotnet", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（可选）"
            "maxCount" int "返回字符串数量上限（默认 5000，最大 10000）"
        }) }
    ) { e, a, _ -> e.dotnetListStrings(a.str("workspaceId"), a.str("editSessionId").ifBlank { null }, a.intValue("maxCount", 5000)) }

    val dotnetDumpIl = BinaryEngineToolHandler(
        ToolMeta("taffy_dotnet_dump_il",
            "转储 .NET 方法的 IL 字节码（hex + 操作码解析）",
            "Dump IL bytecode for a .NET method (hex bytes + opcode disassembly).",
            "dotnet", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（可选）"
            "methodToken" str "方法令牌（hex，例如 0x06000001）"
        }) }
    ) { e, a, _ ->
        val tokenStr = a.str("methodToken")
        val token = parseToken(tokenStr)
        if (token < 0) {
            err("INVALID_TOKEN", "Invalid method token: $tokenStr (expected hex like 0x06000001)")
        } else {
            e.dotnetDumpIl(a.str("workspaceId"), a.str("editSessionId").ifBlank { null }, token)
        }
    }

    val dotnetDisasm = BinaryEngineToolHandler(
        ToolMeta("taffy_dotnet_disasm",
            "反汇编 .NET 方法 IL（指令级反汇编 + 可选伪代码）",
            "Disassemble .NET method IL (instruction-level disassembly + optional pseudocode).",
            "dotnet", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（可选）"
            "methodToken" str "方法令牌（hex，例如 0x06000001）"
            "pseudoCode" bool "可用时包含伪代码（默认 false）"
        }) }
    ) { e, a, _ ->
        val tokenStr = a.str("methodToken")
        val token = parseToken(tokenStr)
        if (token < 0) {
            err("INVALID_TOKEN", "Invalid method token: $tokenStr (expected hex like 0x06000001)")
        } else {
            e.dotnetDisasm(a.str("workspaceId"), a.str("editSessionId").ifBlank { null }, token, a.bool("pseudoCode", false))
        }
    }

    val dotnetResolveToken = BinaryEngineToolHandler(
        ToolMeta("taffy_dotnet_resolve_token",
            "解析 .NET 元数据标记（类型/方法/字段/字符串引用）",
            "Resolve a .NET metadata token to its referenced entity (type/method/field/string).",
            "dotnet", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（可选）"
            "token" str "元数据令牌（hex，例如 0x06000001）"
        }) }
    ) { e, a, _ ->
        val tokenStr = a.str("token")
        val token = parseToken(tokenStr)
        if (token < 0) {
            err("INVALID_TOKEN", "Invalid token: $tokenStr (expected hex like 0x06000001)")
        } else {
            e.dotnetResolveToken(a.str("workspaceId"), a.str("editSessionId").ifBlank { null }, token)
        }
    }

    // ── .NET 编辑 ──

    val dotnetEditIl = BinaryEngineToolHandler(
        ToolMeta("taffy_dotnet_edit_il",
            "修改 .NET 方法的 IL 字节码（按 IL 偏移写入 hex 补丁）",
            "Patch IL bytecode of a .NET method at a given IL offset. Requires an edit session.",
            "dotnet", ToolClass.EXTRA, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（必填）"
            "methodToken" str "方法令牌（hex，例如 0x06000001）"
            "ilOffset" int "方法 IL 体内的字节偏移"
            "hexData" str "要写入的十六进制字节（例如 2A 表示 ret）"
            "dryRun" bool "预览而不应用（默认 false）"
        }) }
    ) { e, a, _ ->
        val tokenStr = a.str("methodToken")
        val token = parseToken(tokenStr)
        if (token < 0) {
            err("INVALID_TOKEN", "Invalid method token: $tokenStr (expected hex like 0x06000001)")
        } else {
            e.dotnetEditIl(
                a.str("workspaceId"), a.str("editSessionId"),
                token, a.intValue("ilOffset", 0),
                a.str("hexData"), a.bool("dryRun", false)
            )
        }
    }

    val dotnetEditString = BinaryEngineToolHandler(
        ToolMeta("taffy_dotnet_edit_string",
            "修改 .NET 用户字符串（US 堆偏移写入新字符串）",
            "Patch a .NET user string at the given US heap offset. Requires an edit session.",
            "dotnet", ToolClass.EXTRA, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（必填）"
            "usOffset" int "#US 堆中的字节偏移"
            "newStr" str "新字符串内容（必须能放入原槽位）"
            "dryRun" bool "预览而不应用（默认 false）"
        }) }
    ) { e, a, _ ->
        e.dotnetEditString(
            a.str("workspaceId"), a.str("editSessionId"),
            a.intValue("usOffset", 0), a.str("newStr"), a.bool("dryRun", false)
        )
    }

    // ── PE 原始编辑（补充 somcp edit_* 仅支持 ELF 的缺口）──

    val peEditHex = BinaryEngineToolHandler(
        ToolMeta("taffy_pe_edit_hex",
            "按文件偏移写入 hex 补丁到 PE/DLL 文件（需要编辑会话）",
            "Patch raw hex bytes at a file offset in a PE/DLL file. Requires an edit session. Use this for PE files where taffy_edit_hex (ELF-only) does not apply.",
            "dotnet", ToolClass.EXTRA, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（必填）"
            "offset" str "文件偏移（hex，例如 0x400，或十进制）"
            "hexData" str "要写入的十六进制字节（例如 9090 表示两个 NOP）"
            "dryRun" bool "预览而不应用（默认 false）"
        }) }
    ) { e, a, _ ->
        val offset = parseOffset(a.str("offset"))
        if (offset < 0) {
            err("INVALID_OFFSET", "Invalid offset: ${a.str("offset")} (expected hex like 0x400 or decimal)")
        } else {
            e.editHex(a.str("workspaceId"), a.str("editSessionId"), offset, a.str("hexData"), a.bool("dryRun", false))
        }
    }

    val peEditVa = BinaryEngineToolHandler(
        ToolMeta("taffy_pe_edit_va",
            "按虚拟地址写入 hex 补丁到 PE/DLL 文件（需要编辑会话，自动 VA→offset 转换）",
            "Patch raw hex bytes at a virtual address in a PE/DLL file. Auto-resolves VA to file offset. Requires an edit session.",
            "dotnet", ToolClass.EXTRA, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（必填）"
            "va" str "虚拟地址（hex，例如 0x10004000，或十进制）"
            "hexData" str "要写入的十六进制字节"
            "dryRun" bool "预览而不应用（默认 false）"
        }) }
    ) { e, a, _ ->
        val va = parseOffset(a.str("va"))
        if (va < 0) {
            err("INVALID_VA", "Invalid virtual address: ${a.str("va")} (expected hex like 0x10004000 or decimal)")
        } else {
            e.editVa(a.str("workspaceId"), a.str("editSessionId"), va, a.str("hexData"), a.bool("dryRun", false))
        }
    }

    val peEditSection = BinaryEngineToolHandler(
        ToolMeta("taffy_pe_edit_section",
            "按节区名写入 hex 补丁到 PE/DLL 文件的指定节区（需要编辑会话）",
            "Patch hex bytes into a named section of a PE/DLL file. Requires an edit session.",
            "dotnet", ToolClass.EXTRA, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（必填）"
            "sectionName" str "节区名（例如 .text、.rdata）"
            "hexData" str "作为新节区内容写入的十六进制字节"
            "dryRun" bool "预览而不应用（默认 false）"
        }) }
    ) { e, a, _ ->
        e.editSection(a.str("workspaceId"), a.str("editSessionId"), a.str("sectionName"), a.str("hexData"), a.bool("dryRun", false))
    }

    // ── PE 搜索与读取 ──

    val peSearchBytes = BinaryEngineToolHandler(
        ToolMeta("taffy_pe_search_bytes",
            "在 PE/DLL 文件中搜索十六进制字节模式",
            "Search for a hex byte pattern in a PE/DLL file. Returns match offsets.",
            "dotnet", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（可选）"
            "pattern" str "十六进制字节模式（例如 5F2403D5 或 5F 24 ?? D5）"
        }) }
    ) { e, a, _ -> e.searchBytes(a.str("workspaceId"), a.str("editSessionId").ifBlank { null }, a.str("pattern")) }

    val peReadSection = BinaryEngineToolHandler(
        ToolMeta("taffy_pe_read_section",
            "读取 PE/DLL 文件指定节区的内容（返回 hex 预览）",
            "Read a named section from a PE/DLL file. Returns hex preview and size info.",
            "dotnet", ToolClass.CORE,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（可选）"
            "sectionName" str "节区名（例如 .text、.rdata、.rsrc）"
        }) }
    ) { e, a, _ -> e.readSection(a.str("workspaceId"), a.str("editSessionId").ifBlank { null }, a.str("sectionName")) }

    // ── PE 编辑会话 ──

    val peEditSession = BinaryEngineToolHandler(
        ToolMeta("taffy_pe_edit_session",
            "PE 编辑会话管理（action=open|snapshot|undo|redo|reset|history）",
            "Manage PE edit sessions: open, snapshot, undo, redo, reset, history.",
            "dotnet", ToolClass.CORE,
        ) { objectSchema(props {
            "action".oneOf("open (default) | snapshot | undo | redo | rollback | reset | history", "open", "snapshot", "undo", "redo", "rollback", "reset", "history")
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（除 open 外的所有 action 必填）"
            "label" str "快照标签（action=snapshot）"
            "count" int "撤回/重做次数（默认 1）"
            "snapshotIndex" int "回滚到的快照索引（action=rollback，-1 = 最近）"
        }) }
    ) { e, a, _ ->
        val wsId = a.str("workspaceId")
        val sid = a.str("editSessionId")
        when (a.str("action", "open")) {
            "open" -> e.openEditSession(wsId)
            "snapshot" -> e.snapshot(wsId, sid, a.str("label", "manual"))
            "undo" -> e.undo(wsId, sid, a.intValue("count", 1))
            "redo" -> e.redo(wsId, sid, a.intValue("count", 1))
            "rollback" -> e.rollback(wsId, sid, a.intValue("snapshotIndex", -1))
            "reset" -> e.reset(wsId, sid)
            "history" -> e.sessionHistory(wsId, sid)
            else -> err("UNKNOWN_ACTION", "Unknown action: ${a.str("action")}")
        }
    }

    // ── PE 构建与输出 ──

    val peBuild = BinaryEngineToolHandler(
        ToolMeta("taffy_pe_build",
            "构建输出补丁后的 PE/DLL 文件",
            "Build and export the patched PE/DLL file from an edit session.",
            "dotnet", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID"
            "outputName" str "输出文件名（默认：original_patched.dll）"
        }) }
    ) { e, a, _ -> e.build(a.str("workspaceId"), a.str("editSessionId"), a.str("outputName")) }

    val peListOutputs = BinaryEngineToolHandler(
        ToolMeta("taffy_pe_list_outputs",
            "列出所有已构建的 PE/DLL 输出文件",
            "List all built PE/DLL output files from previous build operations.",
            "dotnet", ToolClass.CORE,
        ) { objectSchema(props { }) }
    ) { e, _, _ -> e.listBuildOutputs() }

    // ── PE 读取 ──

    val peReadHex = BinaryEngineToolHandler(
        ToolMeta("taffy_pe_read_hex",
            "读取 PE 文件的十六进制转储",
            "Read a hex dump from a PE file at the given offset.",
            "dotnet", ToolClass.CORE,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（可选）"
            "offset" int "字节偏移（默认 0）"
            "length" int "读取字节数（默认 256，最大 65536）"
        }) }
    ) { e, a, _ -> e.readHex(a.str("workspaceId"), a.str("editSessionId").ifBlank { null }, a.intValue("offset", 0), a.intValue("length", 256)) }

    // ── 诊断 ──

    val peHealth = BinaryEngineToolHandler(
        ToolMeta("taffy_pe_health",
            "PE/.NET 引擎健康检查（native 库状态、工作区/会话/输出数量、输出目录）",
            "PE/.NET engine health check: native library status, workspace/session/output counts, output directory.",
            "dotnet", ToolClass.META,
        ) { objectSchema(props { }) }
    ) { e, _, _ -> e.health() }

    // ── 全部处理器 ──

    val ALL: List<ToolHandler> = listOf(
        peOpen, peClose, peAnalyze,
        dotnetDetect, dotnetListTypes, dotnetListMethods, dotnetListStrings,
        dotnetDumpIl, dotnetDisasm, dotnetResolveToken,
        dotnetEditIl, dotnetEditString,
        peEditHex, peEditVa, peEditSection,
        peSearchBytes, peReadSection,
        peEditSession, peBuild, peListOutputs, peReadHex,
        peHealth,
    )
}

/**
 * 转发到 [BinaryEngine] 方法的处理器。
 * 若 [BinaryEngine] 在上下文中不可用，返回明确错误。
 */
class BinaryEngineToolHandler(
    override val meta: ToolMeta,
    private val invoke: (BinaryEngine, JSONObject, SettingsStore) -> JSONObject,
) : ToolHandler {
    override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
        val be = ctx.binaryEngine
            ?: return err("BINARY_ENGINE_UNAVAILABLE",
                "BinaryEngine (libdsmcp_native) is not loaded. PE/.NET tools are unavailable.")
        return invoke(be, args, ctx.settings)
    }
}

// ── 辅助函数 ──

/** 解析 hex token 字符串（如 "0x06000001" 或 "06000001"）为 Int，失败返回 -1 */
private fun parseToken(s: String): Int {
    val cleaned = s.removePrefix("0x").removePrefix("0X").trim()
    return cleaned.toIntOrNull(16) ?: cleaned.toIntOrNull() ?: -1
}

/** 解析偏移/VA 字符串（如 "0x400" 或 "1024"）为 Long，失败返回 -1 */
private fun parseOffset(s: String): Long {
    if (s.isBlank()) return -1L
    HexCodec.long(s)?.let { return it }
    return s.trim().toLongOrNull() ?: -1L
}
