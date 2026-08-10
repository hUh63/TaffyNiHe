package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.bool
import com.soreverse.mcp.core.doubleValue
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.HexCodec
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.obj
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject

object ToolCatalog {

    private val pathArg: JSONObject.() -> String = { str("path").ifBlank { str("filePath").ifBlank { str("inputPath").ifBlank { str("soPath") } } } }

    // ── WORKSPACE ──

    private val soOpen = EngineToolHandler(
        ToolMeta("taffy_so_open",
            "【SO 分析入口】打开 SO 文件并创建工作区（action=list 列出可用 SO）。所有 .so/.ELF 文件操作必须从 taffy_so_open 开始，不要使用 mt_apk_* 或 np_*。",
            "【PRIMARY SO ENTRY POINT】Open a SO file and create a workspace. Use action=list to discover available SO files. Use action=open_url to download a http(s) SO into the selected work directory, then open and analyze it. All .so/ELF tasks MUST start from taffy_so_open — do NOT use mt_apk_* or np_* for SO files.",
            "workspace", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "action".oneOf("open (默认) | list | open_url", "open", "list", "open_url")
            "path" str "SO 文件绝对路径或 content:// URI（action=open 必需）"
            "filePath" str "path 的别名"
            "url" str "指向 .so/ELF 文件的 http(s) URL（action=open_url 必需）"
            "outputName" str "open_url 下载到工作目录后保存的文件名（可选）"
            "prefix" str "文件路径或前缀过滤（action=list）"
            "limit" int "返回条数上限（action=list）"
            "cursor" str "分页游标（action=list）"
            "temporary" bool "为 true 时工作区重启后不保留"
        }, required = listOf("action")) }
    ) { e, a, s ->
        when (a.str("action", "open")) {
            "list" -> e.listAvailableSos(a.str("prefix"), a.intValue("limit", s.defaultLimit), a.str("cursor"))
            "open_url" -> e.openUrl(a.str("url"), a.str("outputName"), a.bool("temporary", false))
            else -> e.open(a.pathArg(), a.bool("temporary", true))
        }
    }

    private val soClose = EngineToolHandler(
        ToolMeta("taffy_so_close",
            "关闭工作区（action=list 列出已打开工作区）；关闭同时回收该工作区的 SO 编辑会话",
            "Close an open workspace (action=list lists open workspaces); closing also releases the SO edit session of that workspace.",
            "workspace", ToolClass.CORE,
        ) { objectSchema(props {
            "action".oneOf("close (默认) | list", "close", "list")
            "workspaceId" str "工作区 ID（action=close）"
        }, required = listOf("action")) }
    ) { e, a, _ ->
        when (a.str("action", "close")) {
            "list" -> e.listWorkspaces()
            else -> e.close(a.str("workspaceId"))
        }
    }

    private val apkAnalyze = EngineToolHandler(
        ToolMeta("taffy_apk_analyze",
            "独立解析本地 APK：ZIP 条目、Manifest 格式、DEX 头、ABI/SO、资源与 v1 签名文件；不依赖外部 APK MCP。",
            "Standalone local APK parser for ZIP entries, manifest format, DEX headers, ABI/SO inventory, resources, and v1 signature files; no external APK MCP required.",
            "workspace",
            ToolClass.CORE,
        ) {
            objectSchema(props {
                "path" str "本地 APK 路径，或相对于所选工作目录的路径"
                "entryLimit" int "返回的 ZIP 条目数上限，1..5000（默认 500）"
            })
        },
    ) { engine, args, _ -> engine.analyzeApk(args.str("path").ifBlank { args.str("filePath") }, args.intValue("entryLimit", 500)) }

    private val flutterBlutter = EngineToolHandler(
        ToolMeta("taffy_flutter_blutter",
            "Flutter AOT/Blutter 聚合工具：识别 Flutter APK、提取版本指纹，并使用内置 Flutter 3.44.x / Dart 3.12.2 arm64 Runner 完成本地分析。其他版本会明确返回不支持。",
            "Aggregated Flutter AOT and Blutter tool using the embedded Flutter 3.44.x / Dart 3.12.2 arm64 runner. Other versions return an explicit unsupported-version result.",
            "analyze",
            ToolClass.CORE,
            heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("inspect | analyze | status | result | cancel | packages | prune | strings", "inspect", "analyze", "status", "result", "cancel", "packages", "prune", "strings")
                "path" str "APK 路径，或包含 libapp.so 和 libflutter.so 的目录"
                "jobId" str "查询状态/结果/取消用的持久化 Blutter 任务 ID"
                "abi".oneOf("目标 ABI", "auto", "arm64-v8a", "x86_64", "armeabi-v7a", "x86")
                "backend".oneOf("执行后端", "auto", "embedded")
                "limit" int "返回结果实体数上限，1..1000"
                "kind".oneOf("分页结果集", "libraries", "classes", "functions", "objects")
                "cursor" str "上一页结果返回的不透明游标"
                "olderThanMillis" int "清理比该时长更早的缓存结果"
                "keyword" str "strings: 过滤用关键词(URL/文案/类名, 大小写不敏感)"
                "minLength" int "strings: 最短字符串长度(默认4)"
            })
        },
    ) { engine, args, _ -> engine.flutterBlutter(args) }

    // ── ANALYZE (Rizin-backed deep analysis) ──

    private val analyzeElf = EngineToolHandler(
        ToolMeta("taffy_analyze_elf",
            "ELF 结构与统计（LIEF 解析：节区/符号/重定位/程序头/动态段）",
            "Full ELF structure and triage stats via LIEF: sections, symbols, relocations, program headers, dynamic entries.",
            "analyze", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（可选，为空则用原始 SO）"
            "view".oneOf("full（默认）| stats | list", "full", "stats", "list")
            "subView".oneOf("view=list 时的子视图", "sections", "symbols", "dynsyms", "functions", "relocations", "strings", "imports")
            "prefix" str "名称前缀过滤（view=list）"
            "limit" int "返回条数上限（view=list）"
        }, required = listOf("workspaceId")) }
    ) { e, a, s ->
        val view = a.str("view", "full")
        when (view) {
            "stats" -> e.readStats(a.str("workspaceId"), a.str("editSessionId"))
            "list" -> e.list(a.str("workspaceId"), a.str("editSessionId"), a.str("subView", "sections"), a.str("prefix"), a.intValue("limit", s.defaultLimit))
            else -> e.readElf(a.str("workspaceId"), a.str("editSessionId"))
        }
    }

    private val readStats = EngineToolHandler(
        ToolMeta("taffy_read_stats",
            "【DEPRECATED】SO 快速统计（taffy_analyze_elf view=stats 的直观别名）建议改用 taffy_analyze_elf",
            "[DEPRECATED] Direct alias for taffy_analyze_elf(view=stats). Prefer taffy_analyze_elf(view=stats) — this alias is kept only for legacy clients.",
            "analyze", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID"
        }, required = listOf("workspaceId")) }
    ) { e, a, _ -> e.readStats(a.str("workspaceId"), a.str("editSessionId")) }

    private val analysisReport = EngineToolHandler(
        ToolMeta("taffy_analysis_report",
            "【DEPRECATED】生成综合分析报告（taffy_meta_info action=report 的直观别名）建议改用 taffy_meta_info",
            "[DEPRECATED] Generate a full analysis report. Direct alias for taffy_meta_info(action=report)/taffy_lief_api(action=report). Prefer taffy_meta_info.",
            "analyze", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID"
            "writeToFile" bool "将报告 JSON 写入应用文件"
        }) }
    ) { e, a, _ -> e.analysisReport(a.str("workspaceId"), a.str("editSessionId"), a.optBoolean("writeToFile", true)) }

    private val analyzeFunctions = EngineToolHandler(
        ToolMeta("taffy_analyze_functions",
            "列出 Rizin 自动分析发现的所有函数（含地址/大小/调用数）",
            "List all functions discovered by Rizin auto-analysis (address, size, call count). Example: analyzeFunctions(workspaceId='ws1', limit=50) to list the first 50 symbols.",
            "analyze", ToolClass.CORE, heavy = true,
            outputSchema = SchemaBuilder.outputSchema(
                "Function list result: { ok, total, functions: [[name, va, size, calls]] }.",
                required = listOf("ok"),
                {
                    "ok" bool "成功时为 true"
                    "total" int "返回的函数数量"
                    "functions" arr "[name, va, size, calls] 元组数组"
                }
            )
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（可选，为空则用原始 SO）"
            "limit" int "返回函数条数上限"
            "cursor" str "分页游标"
        }, required = listOf("workspaceId")) }
    ) { e, a, s -> e.rzFunctions(a.str("workspaceId"), a.str("editSessionId"), a.intValue("limit", s.defaultLimit), a.str("cursor")) }

    private val analyzeCfg = EngineToolHandler(
        ToolMeta("taffy_analyze_cfg",
            "函数控制流图（Rizin CFG：基本块 + 跳转边）",
            "Control flow graph for a function via Rizin: basic blocks and jump edges. Example: analyzeCfg(workspaceId='ws1', locator='so_function:lib.so!check') to get the CFG of function check.",
            "analyze", ToolClass.CORE, heavy = true,
            outputSchema = SchemaBuilder.outputSchema(
                "CFG result: { ok, function, basic_blocks: [{va,size,insns}...], edges: [[from,to]...] }.",
                required = listOf("ok"),
                {
                    "ok" bool "成功时为 true"
                    "function" str "解析后的函数定位符"
                    "basic_blocks" arr "基本块对象数组 {va, size, insns}"
                    "edges" arr "[fromVa, toVa] 跳转边数组"
                }
            )
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（可选）"
            "locator" str "函数定位符：可接受 taffy_analyze_functions 返回的完整 locator（so_function:file!Name）或短函数名"
        }, required = listOf("workspaceId")) }
    ) { e, a, _ -> e.rzCfg(a.str("workspaceId"), a.str("editSessionId"), a.str("locator")) }

    private val analyzeCrypto = EngineToolHandler(
        ToolMeta("taffy_analyze_crypto",
            "密码学特征扫描（AES/RSA/ECC 常量 + 熵分析）",
            "Scan for cryptographic material (AES/RSA/ECC constants) and high-entropy regions via Rizin. Example: analyzeCrypto(workspaceId='ws1') to reveal cipher algorithm hints before emulating an export.",
            "analyze", ToolClass.CORE, heavy = true,
            outputSchema = SchemaBuilder.outputSchema(
                "Crypto scan result: { ok, algorithms: [{name,count,variants}...], entropy_regions: [{va,size,entropy}...] }.",
                required = listOf("ok"),
                {
                    "ok" bool "成功时为 true"
                    "algorithms" arr "匹配到的加密算法：[{name, count, variants}]"
                    "entropy_regions" arr "高熵区域：[{va, size, entropy}]"
                }
            )
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（可选）"
        }, required = listOf("workspaceId")) }
    ) { e, a, _ -> e.rzScanCrypto(a.str("workspaceId"), a.str("editSessionId")) }

    private val analyzeXrefs = EngineToolHandler(
        ToolMeta("taffy_analyze_xrefs",
            "交叉引用（Rizin：direction=to 入引用 / from 出引用 / both 双向）",
            "Cross-references via Rizin. direction: to (incoming refs) | from (outgoing refs) | both.",
            "analyze", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（可选）"
            "locator" str "符号/函数定位符：可接受 taffy_analyze_elf/taffy_analyze_functions 的完整定位符或短符号名"
            "direction".oneOf("to（默认）| from | both", "to", "from", "both")
            "limit" int "引用数量上限"
        }, required = listOf("workspaceId")) }
    ) { e, a, s -> e.rzXrefs(a.str("workspaceId"), a.str("editSessionId"), a.str("locator"), a.str("direction", "to")) }

    private val analyzeEsil = EngineToolHandler(
        ToolMeta("taffy_analyze_esil",
            "ESIL 指令级模拟追踪（Rizin ESIL VM：寄存器快照 + 内存读写）",
            "ESIL instruction-level emulation trace via Rizin: register snapshots and memory reads/writes.",
            "analyze", ToolClass.EXTRA, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（可选）"
            "locator" str "函数定位符或十六进制 VA：可接受 taffy_analyze_functions 的完整定位符、短函数名或 0x… 地址"
            "addr" str "无函数符号可用时的十六进制虚拟地址兜底，例如 0x1234"
            "stepCount" int "模拟执行的指令条数（默认 1，最大 1000）"
        }) }
    ) { e, a, _ -> e.rzEsilStep(a.str("workspaceId"), a.str("editSessionId"), a.str("locator").ifBlank { a.str("addr") }, a.intValue("stepCount", 1)) }

    // ── SEARCH ──

    private val searchBytes = EngineToolHandler(
        ToolMeta("taffy_search_bytes",
            "十六进制模式搜索（Rizin byte pattern：紧凑 hex，MCP 会兼容空格和 ??）",
            "Hex pattern search via Rizin byte-pattern syntax. Native syntax is compact hex/nibble wildcard, e.g. 5F2403D5, 5F24..D5, bytes:mask; MCP also normalizes spaced hex like '5F 24 ?? D5'.",
            "search", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（可选）"
            "pattern" str "Rizin 字节模式：紧凑 hex 如 5F2403D5、用 . 表示半字节通配如 5F24..D5、可选 bytes:mask；为兼容会归一化带空格的 hex 和 ??"
            "fromVa" str "起始 VA 十六进制字符串（空/0 表示从开头）"
            "toVa" str "结束 VA 十六进制字符串（空/0 表示到结尾）"
        }, required = listOf("workspaceId")) }
    ) { e, a, _ -> e.rzSearchBytes(a.str("workspaceId"), a.str("editSessionId"), a.str("pattern"), HexCodec.long(a.str("fromVa")) ?: 0L, HexCodec.long(a.str("toVa")) ?: 0L) }

    private val searchStrings = EngineToolHandler(
        ToolMeta("taffy_search_strings",
            "字符串搜索（prefix 过滤，扫描 .rodata/.strtab/.dynstr）",
            "Search extracted UTF-8 and UTF-16LE strings, including Chinese text, with optional prefix/content filter.",
            "search", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（可选）"
            "prefix" str "字符串包含过滤或正则模式，支持中文/UTF-8 文本"
            "regex" bool "为 true 时把 prefix 当作 Kotlin 正则表达式"
            "ignoreCase" bool "忽略大小写匹配（默认 true）"
            "encoding" str "可选编码过滤：UTF-8、UTF-16LE，或留空表示任意"
            "minConfidence" num "字符串置信度下限 [0,1]；可用于过滤嘈杂的 UTF-16 候选"
            "limit" int "结果数量上限"
            "cursor" str "分页游标"
        }, required = listOf("workspaceId")) }
    ) { e, a, s -> e.strings(a.str("workspaceId"), a.str("editSessionId"), "", a.str("prefix"), a.intValue("limit", s.defaultLimit), "", a.str("cursor"), a.bool("regex"), a.bool("ignoreCase", true), a.str("encoding"), a.doubleValue("minConfidence", 0.0)) }

    // ── READ ──

    private val readDisasm = EngineToolHandler(
        ToolMeta("taffy_read_disasm",
            "反汇编（Rizin：按函数/地址返回汇编和 Ghidra 伪代码）",
            "Disassemble via Rizin and include rizin-ghidra pseudocode when the Android native backend has pdg available.",
            "read", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（可选）"
            "locator" str "函数定位符：可接受 taffy_analyze_functions 的完整定位符或短函数名"
            "limit" int "指令条数上限"
            "cursor" str "分页游标"
            "instructionOffset" int "跳过 N 条指令"
            "byteOffset" int "函数内的字节偏移"
            "maxBytes" int "最多读取的字节数"
            "addr" str "十六进制虚拟地址兜底；ARM32 Thumb 可用奇数地址或 thumb=true"
            "thumb" bool "强制 ARM32 Thumb 模式"
            "mode".oneOf("指令模式", "auto", "arm", "thumb")
        }, required = listOf("workspaceId")) }
    ) { e, a, s -> e.disasm(a.str("workspaceId"), a.str("editSessionId"), a.str("locator"), a.intValue("limit", s.defaultLimit), a.str("cursor"), a.intValue("instructionOffset"), a.intValue("byteOffset"), a.intValue("maxBytes", 4096), a.str("addr"), if (a.has("thumb")) a.bool("thumb") else null, a.str("mode", "auto")) }

    private val readHexdump = EngineToolHandler(
        ToolMeta("taffy_read_hexdump",
            "十六进制转储（按偏移/地址读取原始字节）",
            "Hex dump: read raw bytes at a given offset or address.",
            "read", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（可选）"
            "locator" str "节区定位符：可接受 taffy_analyze_elf 的完整定位符、so_section:.text 或类似 .text 的短节区名"
            "byteOffset" int "节区内字节偏移"
            "maxBytes" int "最多转储的字节数"
        }, required = listOf("workspaceId")) }
    ) { e, a, _ -> e.hexdump(a.str("workspaceId"), a.str("editSessionId"), a.str("locator"), a.intValue("byteOffset"), a.intValue("maxBytes", 4096)) }

    // ── EDIT ──

    private val editHex = EngineToolHandler(
        ToolMeta("taffy_edit_hex",
            "字节级补丁（edits[] 写 newHex；或 va+patch 通过 LIEF patch_address）",
            "Patch raw bytes. Use edits[] with byteOffset for offset-based patching, or va+patchHex for VA-based patching via LIEF.",
            "edit", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID"
            "locator" str "基于偏移打补丁用的节区定位符：可接受 taffy_analyze_elf 的完整定位符、so_section:.text 或短节区名"
            "edits" arr ("十六进制编辑数组" to SchemaBuilder.editsHexSchema())
            "va" str "基于 VA 打补丁的虚拟地址（hex，例如 0x1234）"
            "patchHex" str "写入 va 处的十六进制字节（例如 '20 00 80 52'）"
            "dryRun" bool "为 true 时返回预览而不实际应用改动"
        }, required = listOf("workspaceId")) }
    ) { e, a, _ ->
        val vaStr = a.str("va")
        if (vaStr.isNotEmpty()) {
            val va = HexCodec.long(vaStr)
                ?: return@EngineToolHandler err("INVALID_ARGUMENT", "va must be a hexadecimal address", "va", vaStr)
            val patch = HexCodec.bytes(a.str("patchHex"))
                ?: return@EngineToolHandler err("INVALID_HEX", "patchHex must contain valid byte pairs", "patchHex", a.str("patchHex"))
            e.editHexVa(a.str("workspaceId"), a.str("editSessionId"), va, patch, a.bool("dryRun"))
        } else {
            e.editHex(a.str("workspaceId"), a.str("editSessionId"), a.str("locator"), a.getJSONArray("edits"), a.bool("dryRun"))
        }
    }

    private val editAsm = EngineToolHandler(
        ToolMeta("taffy_edit_asm",
            "汇编级补丁（Rizin assemble：edits[] 替换指令；dryRun 预览）",
            "Patch assembly using Rizin assembler. edits[] each replace one or more instructions; dryRun=true previews.",
            "edit", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID"
            "locator" str "函数或地址定位符：可接受 so_function:file!name@0xVA、函数名或裸 0xVA"
            "dryRun" bool "为 true 时返回预览而不实际应用改动"
            "edits" arr ("汇编编辑数组" to SchemaBuilder.editsAsmSchema())
        }, required = listOf("workspaceId")) }
    ) { e, a, _ -> e.editAsm(a.str("workspaceId"), a.str("editSessionId"), a.str("locator"), a.getJSONArray("edits"), a.bool("dryRun")) }

    private val editSymbol = EngineToolHandler(
        ToolMeta("taffy_edit_symbol",
            "符号管理（rename 重命名 / add 添加导出函数 / remove 移除符号）。rename 也用于重命名导入(import)符号：将调用重定向到同长/更短的另一个符号，实现 import 级补丁（对标 soedit 编辑导入表）。",
            "Symbol management: rename (same-or-shorter, also redirects imports by renaming the UNDEF symbol), add exported function via LIEF, or remove symbol via LIEF.",
            "edit", ToolClass.EXTRA, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID"
            "locator" str "重命名的符号定位符：可接受 taffy_analyze_elf 的完整符号定位符或短符号名"
            "edits" arr ("符号编辑数组" to SchemaBuilder.editsSymbolSchema())
            "dryRun" bool "为 true 时返回预览（仅重命名）"
            "addr" str "新增导出函数的十六进制 VA（add 操作，快捷方式）"
            "name" str "符号名（add：新函数名，remove：要移除的符号，快捷方式）"
            "op".oneOf("rename | add | remove（快捷方式，绕过 edits[]）", "rename", "add", "remove")
        }, required = listOf("workspaceId")) }
    ) { e, a, _ ->
        val op = a.str("op")
        if (op.isNotEmpty()) {
            when (op) {
                "add" -> HexCodec.long(a.str("addr"))?.let {
                    e.liefAddExportedFunction(a.str("workspaceId"), a.str("editSessionId"), it, a.str("name"))
                } ?: err("INVALID_ARGUMENT", "addr must be a hexadecimal address", "addr", a.str("addr"))
                "remove" -> e.liefRemoveSymbol(a.str("workspaceId"), a.str("editSessionId"), a.str("name"))
                else -> e.editSymbol(a.str("workspaceId"), a.str("editSessionId"), a.str("locator"), a.getJSONArray("edits"), a.bool("dryRun"))
            }
        } else {
            e.editSymbol(a.str("workspaceId"), a.str("editSessionId"), a.str("locator"), a.getJSONArray("edits"), a.bool("dryRun"))
        }
    }

    private val editFixSections = EngineToolHandler(
        ToolMeta("taffy_edit_fix_sections",
            "xAnSo 节区头重建（LIEF Builder：从 .dynamic 段重建节区头）",
            "Reconstruct ELF section headers from the .dynamic segment via LIEF Builder (xAnSo algorithm). Essential for NDK-compiled SOs with stripped section headers.",
            "edit", ToolClass.EXTRA, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID"
        }) }
    ) { e, a, _ -> e.fixSections(a.str("workspaceId"), a.str("editSessionId")) }

    // ── EMULATE (Unidbg + DalvikVM) ──

    private val emulateCall = EngineToolHandler(
        ToolMeta("taffy_emulate_call",
            "函数模拟执行（Unidbg + DalvikVM：导出函数/JNI_OnLoad/Java_*，返回阶段化诊断）",
            "Emulate an exported function via Unidbg with DalvikVM JNI support. Best for JNI_OnLoad, exported functions, Java_* JNI methods, and patch validation; failures include stage and nextActions diagnostics. Example: emulateCall(workspaceId='ws1', symbolName='JNI_OnLoad') to force initialization.",
            "emulate", ToolClass.CORE, heavy = true,
            outputSchema = SchemaBuilder.outputSchema(
                "Emulation result: { ok, returnValue, backend, durationMs; on failure: stage, nextActions }.",
                required = listOf("ok"),
                {
                    "ok" bool "模拟成功时为 true"
                    "returnValue" str "函数返回值（来自目标 ABI）"
                    "backend" str "使用的模拟后端，例如 unidbg"
                    "durationMs" num "模拟耗时（毫秒）"
                    "stage" str "失败阶段（若 ok 不为 true）"
                    "nextActions" arr "失败时的建议后续步骤"
                }
            )
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（设置后使用打过补丁的字节）"
            "symbolName" str "要调用的导出符号，例如 JNI_OnLoad 或 Java_com_example_Class_method。先用 taffy_analyze_elf 查看 dynsyms"
            "args" arr "Java_* 方法隐式 JNI 参数之后的整数/字符串参数数组"
            "trace" bool "启用详细 Unidbg 追踪用于诊断；仅适用于小函数"
        }, required = listOf("workspaceId", "symbolName")) }
    ) { e, a, s -> if (!s.emulationEnabled) err("EMULATION_DISABLED", "Emulation is disabled in settings. Enable emulationEnabled to use this feature.", "emulationEnabled", false) else e.emulate(a.str("workspaceId"), a.str("editSessionId"), a.str("symbolName"), a.optJSONArray("args") ?: JSONArray(), a.bool("trace", false)) }

    private val emulateDump = EngineToolHandler(
        ToolMeta("taffy_emulate_dump",
            "内存转储（Unidbg：加载 SO 后读取指定地址的内存）",
            "Dump memory at an Unidbg runtime absolute virtual address after loading the SO. Add the module base to an ELF RVA/VA.",
            "emulate", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID（可选）"
            "addr" str "Unidbg 运行时绝对虚拟地址：用 taffy_unidbg_session(action=modules) 得到的模块基址加上 ELF 的 RVA/VA"
            "size" int "转储的字节数（1-65536）"
        }, required = listOf("workspaceId")) }
    ) { e, a, s ->
        if (!s.emulationEnabled) err("EMULATION_DISABLED", "Emulation is disabled in settings. Enable emulationEnabled to use this feature.", "emulationEnabled", false)
        else {
            val addr = a.str("addr").trim().removePrefix("0x").removePrefix("0X").toLongOrNull(16)
                ?: return@EngineToolHandler err("INVALID_ARGUMENT", "addr must be a hex Unidbg runtime absolute virtual address", "addr", a.str("addr"))
            e.dumpMemory(a.str("workspaceId"), a.str("editSessionId"), addr, a.intValue("size", 256))
        }
    }

    // ── DIFF ──

    private val diffSo = EngineToolHandler(
        ToolMeta("taffy_diff_so",
            "结构化差异对比（Rizin：字节级 + 函数级相似度）",
            "Structural diff between two SO versions via Rizin: byte-level differences and function similarity ratio.",
            "diff", ToolClass.EXTRA, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "工作区 A ID"
            "editSessionId" str "编辑会话 A ID（可选）"
            "workspaceIdB" str "工作区 B ID"
            "editSessionIdB" str "编辑会话 B ID（可选）"
            "limit" int "最大差异块数（0 = 全部）"
        }, required = listOf("workspaceId")) }
    ) { e, a, s ->
        val wsB = a.str("workspaceIdB")
        if (wsB.isNotEmpty()) {
            e.rzDiff(a.str("workspaceId"), a.str("editSessionId"), wsB, a.str("editSessionIdB"))
        } else {
            e.diff(a.str("workspaceId"), a.str("editSessionId"), a.intValue("limit", s.defaultLimit))
        }
    }

    // ── LOW-LEVEL API GATEWAYS ──

    private val rizinApi = EngineToolHandler(
        ToolMeta("taffy_rizin_api",
            "Rizin 底层能力网关（analyze/functions/cfg/xrefs/search_bytes/crypto/esil/diff/asm/disasm）⚠️ 优先使用高级工具（analyze_*/search_*/read_*），仅当高级工具无法满足时再使用本低级 API",
            "Low-level Rizin gateway with enum actions for analyze, functions, cfg, xrefs, search_bytes, crypto, esil, diff, asm, and disasm. ⚠️ PREFER high-level tools (analyze_*/search_*/read_*) — use this low-level API ONLY when no high-level tool covers the need.",
            "lowlevel", ToolClass.EXTRA, heavy = true,
        ) { objectSchema(props {
            "action".oneOf("Rizin 操作", "capabilities", "command", "analyze", "functions", "cfg", "xrefs", "search_bytes", "crypto", "esil", "diff", "asm", "disasm", "decompile")
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID"
            "workspaceIdB" str "工作区 B ID（diff）"
            "editSessionIdB" str "编辑会话 B ID（diff）"
            "locator" str "函数/符号定位符或十六进制 VA"
            "direction".oneOf("xref 方向", "to", "from", "both")
            "pattern" str "search_bytes 的十六进制模式"
            "fromVa" str "search_bytes 的起始 VA 十六进制"
            "toVa" str "search_bytes 的结束 VA 十六进制"
            "asm" str "asm 的汇编文本"
            "command" str "action=command 时任意 Rizin core 命令。变更/文件/shell/调试器命令需要 unsafe=true"
            "unsafe" bool "允许变更、文件、shell、调试器和外部命令。需要已认证的 MCP 访问"
            "addr" str "asm/disasm/esil 的十六进制 VA"
            "thumb" bool "为 asm/disasm 强制 ARM32 Thumb 模式"
            "mode".oneOf("指令模式", "auto", "arm", "thumb")
            "limit" int "指令/结果数量上限"
            "stepCount" int "ESIL 步进数"
            "strict" bool "decompile 时：若 rizin-ghidra 不可用则失败"
        }, required = listOf("workspaceId")) }
    ) { e, a, s ->
        when (a.str("action", "analyze")) {
            "capabilities" -> ok(e.capabilityRegistry().getJSONObject("backends").getJSONObject("rizin"))
            "command" -> e.rzCommand(a.str("workspaceId"), a.str("editSessionId"), a.str("command"), a.bool("unsafe", false))
            "analyze" -> e.rzAnalyze(a.str("workspaceId"), a.str("editSessionId"))
            "functions" -> e.rzFunctions(a.str("workspaceId"), a.str("editSessionId"), a.intValue("limit", s.defaultLimit), a.str("cursor"))
            "cfg" -> e.rzCfg(a.str("workspaceId"), a.str("editSessionId"), a.str("locator"))
            "xrefs" -> e.rzXrefs(a.str("workspaceId"), a.str("editSessionId"), a.str("locator"), a.str("direction", "to"))
            "search_bytes" -> e.rzSearchBytes(a.str("workspaceId"), a.str("editSessionId"), a.str("pattern"), HexCodec.long(a.str("fromVa")) ?: 0L, HexCodec.long(a.str("toVa")) ?: 0L)
            "crypto" -> e.rzScanCrypto(a.str("workspaceId"), a.str("editSessionId"))
            "esil" -> e.rzEsilStep(a.str("workspaceId"), a.str("editSessionId"), a.str("locator").ifBlank { a.str("addr") }, a.intValue("stepCount", 1))
            "diff" -> e.rzDiff(a.str("workspaceId"), a.str("editSessionId"), a.str("workspaceIdB"), a.str("editSessionIdB"))
            "asm" -> e.assembleRaw(a.str("workspaceId"), a.str("editSessionId"), a.str("asm"), HexCodec.long(a.str("addr")) ?: 0L, if (a.has("thumb")) a.bool("thumb") else null, a.str("mode", "auto"))
            "disasm" -> e.disasm(a.str("workspaceId"), a.str("editSessionId"), a.str("locator"), a.intValue("limit", s.defaultLimit), "", 0, 0, 4096, a.str("addr"), if (a.has("thumb")) a.bool("thumb") else null, a.str("mode", "auto"))
            "decompile" -> e.rzDecompile(a.str("workspaceId"), a.str("editSessionId"), a.str("locator").ifBlank { a.str("addr") }, a.bool("strict", true))
            else -> err("UNKNOWN_ACTION", "Unknown Rizin action", "action", a.str("action"))
        }
    }

    private val liefApi = EngineToolHandler(
        ToolMeta("taffy_lief_api",
            "LIEF 全格式能力网关（ELF/PE/Mach-O/DEX/ART/OAT/VDEX）⚠️ 优先使用高级工具（analyze_elf/apk_*/so_*），仅当高级工具无法满足时再使用本低级 API",
            "Full-format LIEF gateway for ELF, PE, Mach-O, DEX, ART, OAT, and VDEX parsing plus format-specific mutations. ⚠️ PREFER high-level tools (analyze_elf, apk_*, so_*). Use this low-level API ONLY when no high-level tool covers the need.",
            "lowlevel", ToolClass.EXTRA, heavy = true,
        ) { objectSchema(props {
            "action".oneOf("LIEF 操作", "capabilities", "dispatch", "parse", "parse_any", "list", "patch_address", "add_export", "remove_symbol", "build", "fix_sections", "report")
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID"
            "subView".oneOf("list 子视图", "sections", "symbols", "dynsyms", "functions", "relocations", "strings", "imports")
            "query" str "过滤查询"
            "limit" int "列表结果上限"
            "va" str "打补丁/新增导出的 VA 十六进制"
            "patchHex" str "patch_address 的十六进制字节"
            "name" str "符号/导出名"
            "outputName" str "构建输出名"
            "conflictStrategy".oneOf("构建冲突策略", "skip", "overwrite", "rename")
            "writeReport" bool "写入补丁报告"
            "writeToFile" bool "写入分析报告文件"
            "format".oneOf("parse_any 的输入格式", "auto", "elf", "pe", "macho", "dex", "art", "oat", "vdex")
            "op".oneOf("分发器操作", "roots", "methods", "parse_any", "validate", "get", "list", "set", "call")
            "objectPath" str "对象路径，例如 sections[0].name 或 binary.entry"
            "method" str "分发器调用的方法名"
            "args" arr "分发器参数"
            "dryRun" bool "预览分发器的变更/构建而不实际应用"
        }, required = listOf("workspaceId")) }
    ) { e, a, s ->
        when (a.str("action", "parse")) {
            "capabilities" -> ok(e.capabilityRegistry().getJSONObject("backends").getJSONObject("lief"))
            "dispatch" -> e.liefDispatch(a.str("workspaceId"), a.str("editSessionId"), a.str("op", "roots"), a.str("objectPath"), a.str("method"), a.optJSONArray("args") ?: JSONArray(), a.bool("dryRun", false))
            "parse" -> e.readStats(a.str("workspaceId"), a.str("editSessionId"))
            "parse_any" -> e.liefDispatch(a.str("workspaceId"), a.str("editSessionId"), "parse_any", args = JSONArray().put(a.str("format", "auto")))
            "list" -> e.list(a.str("workspaceId"), a.str("editSessionId"), a.str("subView", "sections"), a.str("query"), a.intValue("limit", s.defaultLimit))
            "patch_address" -> e.liefPatchAddress(a.str("workspaceId"), a.str("editSessionId"), HexCodec.long(a.str("va")) ?: return@EngineToolHandler err("INVALID_ARGUMENT", "va must be hex", "va", a.str("va")), HexCodec.bytes(a.str("patchHex")) ?: return@EngineToolHandler err("INVALID_HEX", "patchHex must be valid hex", "patchHex", a.str("patchHex")))
            "add_export" -> e.liefAddExportedFunction(a.str("workspaceId"), a.str("editSessionId"), HexCodec.long(a.str("va")) ?: return@EngineToolHandler err("INVALID_ARGUMENT", "va must be hex", "va", a.str("va")), a.str("name"))
            "remove_symbol" -> e.liefRemoveSymbol(a.str("workspaceId"), a.str("editSessionId"), a.str("name"))
            "build" -> e.build(a.str("workspaceId"), a.str("editSessionId"), a.str("outputName"), a.str("conflictStrategy"), a.optBoolean("writeReport", s.writePatchReport), a.optBoolean("writeToWorkDir", s.buildCopyToWorkDir))
            "fix_sections" -> e.fixSections(a.str("workspaceId"), a.str("editSessionId"))
            "report" -> e.analysisReport(a.str("workspaceId"), a.str("editSessionId"), a.optBoolean("writeToFile", true))
            else -> err("UNKNOWN_ACTION", "Unknown LIEF action", "action", a.str("action"))
        }
    }

    private val unidbgApi = EngineToolHandler(
        ToolMeta("taffy_unidbg_api",
            "Unidbg 底层能力网关（session/call/memory/registers/trace/breakpoints）⚠️ 优先使用高级工具（emulate_call/unidbg_session/unidbg_memory/unidbg_debug），仅当高级工具无法满足时再使用本低级 API",
            "Low-level Unidbg gateway for live sessions, function/address calls, memory map/read/write/protect/unmap, registers, modules, exports, trace, and breakpoints. ⚠️ PREFER high-level tools (emulate_call, unidbg_session, unidbg_memory, unidbg_debug) — use this low-level API ONLY when no high-level tool covers the need.",
            "lowlevel", ToolClass.EXTRA, heavy = true,
        ) { objectSchema(props {
            "action".oneOf("Unidbg 操作", "capabilities", "dispatch", "status", "call", "dump")
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID"
            "symbolName" str "要调用的导出符号"
            "args" arr "整数/字符串参数"
            "trace" bool "启用追踪"
            "addr" str "内存转储 VA 十六进制"
            "size" int "转储大小"
            "op".oneOf("分发器操作", "status", "roots", "methods", "session_open", "session_list", "session_close", "session_call", "session_call_address", "session_dump", "session_memory_maps", "session_registers", "session_modules", "session_exports", "session_trace_code", "session_breakpoint_add", "session_debugger_status", "session_breakpoint_remove", "session_single_step", "session_emu_stop", "session_memory_write", "session_memory_map", "session_memory_protect", "session_memory_unmap", "reflect_roots", "reflect_methods", "reflect_invoke", "native_schemas", "native_tool", "call", "dump", "modules", "exports", "imports", "debugger_plan", "memory_map_plan", "registers_plan", "breakpoints_plan", "trace_plan", "framework_matrix", "stub_template", "hook_template", "env_template")
            "method" str "分发器方法/符号名。native_tool 请用 native_schemas 返回的上游 Unidbg MCP 工具名"
            "args" arr "native_tool：[emulatorSessionId, toolName, toolArgumentsObject]。其他分发操作使用其文档化的位置参数"
            "dispatchArgs" arr "action=dispatch 时 args 的别名；为 schema 兼容保留"
        }, required = listOf("workspaceId")) }
    ) { e, a, s ->
        when (a.str("action", "status")) {
            "capabilities" -> ok(e.capabilityRegistry().getJSONObject("backends").getJSONObject("unidbg"))
            "dispatch" -> e.unidbgDispatch(a.str("workspaceId"), a.str("editSessionId"), a.str("op", "status"), a.str("method"), a.optJSONArray("args") ?: a.optJSONArray("dispatchArgs") ?: JSONArray())
            "status" -> ok(e.emulationStatus().put("enabled", s.emulationEnabled))
            "call" -> if (!s.emulationEnabled) err("EMULATION_DISABLED", "Emulation is disabled", "emulationEnabled", false) else e.emulate(a.str("workspaceId"), a.str("editSessionId"), a.str("symbolName"), a.optJSONArray("args") ?: JSONArray(), a.bool("trace", false))
            "dump" -> if (!s.emulationEnabled) err("EMULATION_DISABLED", "Emulation is disabled", "emulationEnabled", false) else e.dumpMemory(a.str("workspaceId"), a.str("editSessionId"), HexCodec.long(a.str("addr")) ?: return@EngineToolHandler err("INVALID_ARGUMENT", "addr must be hex", "addr", a.str("addr")), a.intValue("size", 256))
            else -> err("UNKNOWN_ACTION", "Unknown Unidbg action", "action", a.str("action"))
        }
    }

    private val unidbgSession = EngineToolHandler(
        ToolMeta("taffy_unidbg_session",
            "Unidbg 会话工具（open/list/close/call/dump/modules/exports/registers/maps）【模拟运行时会话，不修改任何文件；与 SO 编辑会话 taffy_session_open / PE 编辑会话 taffy_pe_edit_session 相互独立】",
            "Typed Unidbg session tool for shell-friendly live emulator workflows: open/list/close/call/call_address/dump/modules/exports/registers/memory_maps. [EMULATION RUNTIME session — does NOT patch files; independent from SO edit session (taffy_session_open) and PE edit session (taffy_pe_edit_session).]",
            "emulate", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "action".oneOf("Session action — open(需workspaceId+editSessionId) | list(无参数) | close(需emulatorSessionId) | call(需emulatorSessionId+symbolName) | call_address(需emulatorSessionId+addr) | dump(需emulatorSessionId+addr) | modules(需emulatorSessionId) | exports(需emulatorSessionId) | registers(需emulatorSessionId) | memory_maps(需emulatorSessionId) | Session action — open(needs workspaceId+editSessionId) | list(no args) | close(session) | call(session+symbolName) | call_address(session+addr) | dump(session+addr) | modules/exports/registers/memory_maps(session)", "open", "list", "close", "call", "call_address", "dump", "modules", "exports", "registers", "memory_maps")
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID"
            "emulatorSessionId" str "实时 Unidbg 模拟器会话 ID"
            "symbolName" str "要调用的符号"
            "addr" str "call_address 或 dump 的十六进制地址"
            "args" arr "函数参数"
            "trace" bool "为 call 启用追踪"
            "size" int "转储大小"
            "callJniOnLoad" bool "打开会话时调用 JNI_OnLoad"
        }, required = listOf("action")) }
    ) { e, a, _ ->
        val sessionId = a.str("emulatorSessionId")
        val callArgs = a.optJSONArray("args") ?: JSONArray()
        val dispatchArgs = when (a.str("action", "open")) {
            "open" -> JSONArray().put(a.str("editSessionId")).put(a.bool("callJniOnLoad", true))
            "list" -> JSONArray()
            "close" -> JSONArray().put(sessionId)
            "call" -> JSONArray().put(sessionId).put(a.str("symbolName")).put(callArgs).put(a.bool("trace", false))
            "call_address" -> JSONArray().put(sessionId).put(a.str("addr")).put(callArgs)
            "dump" -> JSONArray().put(sessionId).put(a.str("addr")).put(a.intValue("size", 256))
            "modules", "exports", "registers", "memory_maps" -> JSONArray().put(sessionId)
            else -> return@EngineToolHandler err("UNKNOWN_ACTION", "Unknown Unidbg session action", "action", a.str("action"))
        }
        val op = when (a.str("action", "open")) {
            "open" -> "session_open"
            "list" -> "session_list"
            "close" -> "session_close"
            "call" -> "session_call"
            "call_address" -> "session_call_address"
            "dump" -> "session_dump"
            "modules" -> "session_modules"
            "exports" -> "session_exports"
            "registers" -> "session_registers"
            "memory_maps" -> "session_memory_maps"
            else -> a.str("action")
        }
        e.unidbgDispatch(a.str("workspaceId"), a.str("editSessionId"), op, a.str("symbolName"), dispatchArgs)
    }

    private val unidbgMemory = EngineToolHandler(
        ToolMeta("taffy_unidbg_memory",
            "Unidbg 内存工具（map/read/write/protect/unmap/maps）",
            "Typed Unidbg memory tool for command-line scripts: map/read/write/protect/unmap/maps on a live emulator session.",
            "emulate", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "action".oneOf("内存操作", "map", "read", "write", "protect", "unmap", "maps")
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID"
            "emulatorSessionId" str "实时 Unidbg 模拟器会话 ID"
            "addr" str "十六进制虚拟地址"
            "size" int "字节大小"
            "prot" int "内存保护标志：1=r，2=w，4=x"
            "hex" str "write 的十六进制字节"
        }, required = listOf("workspaceId")) }
    ) { e, a, _ ->
        val sessionId = a.str("emulatorSessionId")
        val dispatchArgs = when (a.str("action", "maps")) {
            "map" -> JSONArray().put(sessionId).put(a.str("addr")).put(a.intValue("size", 4096)).put(a.intValue("prot", 3))
            "read" -> JSONArray().put(sessionId).put(a.str("addr")).put(a.intValue("size", 256))
            "write" -> JSONArray().put(sessionId).put(a.str("addr")).put(a.str("hex"))
            "protect" -> JSONArray().put(sessionId).put(a.str("addr")).put(a.intValue("size", 4096)).put(a.intValue("prot", 1))
            "unmap" -> JSONArray().put(sessionId).put(a.str("addr")).put(a.intValue("size", 4096))
            "maps" -> JSONArray().put(sessionId)
            else -> return@EngineToolHandler err("UNKNOWN_ACTION", "Unknown Unidbg memory action", "action", a.str("action"))
        }
        val op = when (a.str("action", "maps")) {
            "map" -> "session_memory_map"
            "read" -> "session_dump"
            "write" -> "session_memory_write"
            "protect" -> "session_memory_protect"
            "unmap" -> "session_memory_unmap"
            "maps" -> "session_memory_maps"
            else -> a.str("action")
        }
        e.unidbgDispatch(a.str("workspaceId"), a.str("editSessionId"), op, "", dispatchArgs)
    }

    private val unidbgDebug = EngineToolHandler(
        ToolMeta("taffy_unidbg_debug",
            "Unidbg 调试生命周期（trace/breakpoint/step/stop/status）",
            "Typed Unidbg debugger lifecycle for trace, breakpoint add/list/remove, single-step configuration, stop, and status.",
            "emulate", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "action".oneOf("Debug action — 均需emulatorSessionId trace_code/start_events(可加begin/end/traceType/cursor/limit) trace_stop(+traceId) trace_clear hook_start(+hookType/begin/end) hook_list hook_stop(+hookId) breakpoint_add/remove(+addr) single_step(+count) status stop | Debug action — all need emulatorSessionId; trace_stop adds traceId, hook_stop adds hookId, breakpoint_add/remove add addr, single_step adds count", "trace_code", "trace_start", "trace_events", "trace_stop", "trace_clear", "hook_start", "hook_list", "hook_stop", "breakpoint_add", "breakpoint_remove", "status", "single_step", "stop", "debugger_plan", "trace_plan", "breakpoints_plan")
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID"
            "emulatorSessionId" str "实时 Unidbg 模拟器会话 ID"
            "begin" str "追踪起始地址"
            "end" str "追踪结束地址"
            "addr" str "断点地址"
            "count" int "单步指令条数"
            "traceType".oneOf("追踪回调类型", "code", "read", "write")
            "traceId" str "追踪钩子 ID"
            "cursor" int "追踪事件游标"
            "limit" int "追踪事件页大小，最大 1000"
            "hookType".oneOf("后端钩子类型", "syscall", "interrupt", "code", "read", "write")
            "hookId" str "后端钩子 ID"
        }, required = listOf("action")) }
    ) { e, a, _ ->
        val action = a.str("action", "debugger_plan")
        val op = when (action) {
            "trace_code" -> "session_trace_code"
            "trace_start" -> "session_trace_start"
            "trace_events" -> "session_trace_events"
            "trace_stop" -> "session_trace_stop"
            "trace_clear" -> "session_trace_clear"
            "hook_start" -> "session_hook_start"
            "hook_list" -> "session_hook_list"
            "hook_stop" -> "session_hook_stop"
            "breakpoint_add" -> "session_breakpoint_add"
            "breakpoint_remove" -> "session_breakpoint_remove"
            "status" -> "session_debugger_status"
            "single_step" -> "session_single_step"
            "stop" -> "session_emu_stop"
            "debugger_plan", "trace_plan", "breakpoints_plan" -> action
            else -> return@EngineToolHandler err("UNKNOWN_ACTION", "Unknown Unidbg debug action", "action", action)
        }
        val dispatchArgs = when (action) {
            "trace_code" -> JSONArray().put(a.str("emulatorSessionId")).put(a.str("begin")).put(a.str("end"))
            "trace_start" -> JSONArray().put(a.str("emulatorSessionId")).put(a.str("traceType", "code")).put(a.str("begin")).put(a.str("end"))
            "trace_events" -> JSONArray().put(a.str("emulatorSessionId")).put(a.intValue("cursor", 0)).put(a.intValue("limit", 100))
            "trace_stop" -> JSONArray().put(a.str("emulatorSessionId")).put(a.str("traceId"))
            "trace_clear" -> JSONArray().put(a.str("emulatorSessionId"))
            "hook_start" -> JSONArray().put(a.str("emulatorSessionId")).put(a.str("hookType", "syscall")).put(a.str("begin")).put(a.str("end"))
            "hook_list" -> JSONArray().put(a.str("emulatorSessionId"))
            "hook_stop" -> JSONArray().put(a.str("emulatorSessionId")).put(a.str("hookId"))
            "breakpoint_add" -> JSONArray().put(a.str("emulatorSessionId")).put(a.str("addr"))
            "breakpoint_remove" -> JSONArray().put(a.str("emulatorSessionId")).put(a.str("addr"))
            "status", "stop" -> JSONArray().put(a.str("emulatorSessionId"))
            "single_step" -> JSONArray().put(a.str("emulatorSessionId")).put(a.intValue("count", 1))
            else -> JSONArray()
        }
        e.unidbgDispatch(a.str("workspaceId"), a.str("editSessionId"), op, "", dispatchArgs)
    }

    private val unidbgBatch = EngineToolHandler(
        ToolMeta("taffy_unidbg_batch",
            "Unidbg 批处理工具（一条 JSON 顺序执行多个 Unidbg op）",
            "Run a serial Unidbg pipeline in one MCP call. Steps support ${'$'}{key.path} placeholders, ideal for curl/PowerShell batch scripts.",
            "emulate", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "workspaceId" str "步骤的默认工作区 ID"
            "editSessionId" str "步骤的默认编辑会话 ID"
            "steps" arr "步骤数组：{op, method?, args?, resultKey?}。支持类似 ${'$'}{open.emulatorSessionId} 的占位符"
            "stopOnError" bool "首个步骤失败即终止，默认 true"
            "maxSteps" int "最大步骤数，默认 30，最大 100"
        }) }
    ) { e, a, _ -> UnidbgBatchRunner.run(e, a) }

    private val xansoApi = EngineToolHandler(
        ToolMeta("taffy_xanso_api",
            "真实 xAnSo 上游能力网关（status/help/build-section）",
            "Real freakishfox/xAnSo upstream gateway covering its complete public CLI/core functionality.",
            "lowlevel", ToolClass.EXTRA, heavy = true,
        ) { objectSchema(props {
            "action".oneOf("xAnSo 操作", "capabilities", "dispatch", "status", "help", "build-section", "fix_sections")
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID"
            "op".oneOf("分发器操作", "status", "roots", "methods", "capabilities", "help", "build-section", "fix_sections")
            "force" bool "即使已完成可解析的节区表也强制重建"
        }, required = listOf("workspaceId")) }
    ) { e, a, _ ->
        when (a.str("action", "status")) {
            "capabilities" -> ok(e.capabilityRegistry().getJSONObject("backends").getJSONObject("xanso"))
            "dispatch" -> e.xansoDispatch(a.str("workspaceId"), a.str("editSessionId"), a.str("op", "status"))
            "status", "help" -> e.xansoDispatch(a.str("workspaceId"), a.str("editSessionId"), a.str("action"))
            "build-section", "fix_sections" -> e.xansoBuildSections(a.str("workspaceId"), a.str("editSessionId"), a.bool("force", false))
            else -> err("UNKNOWN_ACTION", "Unknown xAnSo action", "action", a.str("action"))
        }
    }

    // ── SESSION ──

    private val sessionOpen = EngineToolHandler(
        ToolMeta("taffy_session_open",
            "【SO 编辑会话】基于当前工作区 SO 创建可修改副本，配合 taffy_edit_hex/asm/symbol / taffy_build_so 进行补丁。注意区别于：taffy_unidbg_session（模拟运行，不改文件）、taffy_pe_edit_session（PE/DLL 编辑）",
            "[SO EDIT SESSION] Open an edit session: creates a mutable copy of the workspace SO for patching. Use with taffy_edit_hex/asm/symbol / taffy_build_so. Distinguish from taffy_unidbg_session (emulation runtime, does NOT patch files) and taffy_pe_edit_session (PE/DLL editing).",
            "session", ToolClass.CORE,
        ) { objectSchema(props {
            "workspaceId" str "工作区 ID"
        }, required = listOf("workspaceId")) }
    ) { e, a, _ -> e.editOpen(a.str("workspaceId")) }

    private val sessionHistory = object : ToolHandler {
        override val meta = ToolMeta("taffy_session_history",
            "【SO 编辑会话】编辑历史管理（snapshot/rollback/undo/redo/reset/check）",
            "[SO EDIT SESSION] Edit session history: snapshot, rollback, undo, redo, reset, or check integrity.",
            "session", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "action".oneOf("snapshot（默认）| rollback | undo | redo | reset | check", "snapshot", "rollback", "undo", "redo", "reset", "check")
            "workspaceId" str "工作区 ID"
            "editSessionId" str "编辑会话 ID"
            "label" str "快照标签（action=snapshot）"
            "snapshotIndex" int "回滚的快照索引（-1 = 最近，action=rollback）"
            "count" int "撤回/重做次数（action=undo|redo）"
        }, required = listOf("workspaceId")) }
        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val e = ctx.engine
            return when (args.str("action", "snapshot")) {
                "snapshot" -> e.editSnapshot(args.str("workspaceId"), args.str("editSessionId"), args.str("label"))
                "rollback" -> e.editRollback(args.str("workspaceId"), args.str("editSessionId"), args.intValue("snapshotIndex", -1))
                "undo" -> e.editUndo(args.str("workspaceId"), args.str("editSessionId"), args.intValue("count", 1))
                "redo" -> e.editRedo(args.str("workspaceId"), args.str("editSessionId"), args.intValue("count", 1))
                "reset" -> e.editReset(args.str("workspaceId"), args.str("editSessionId"))
                "check" -> e.editCheck(args.str("workspaceId"), args.str("editSessionId"))
                else -> err("UNKNOWN_ACTION", "Unknown action: ${args.str("action")}", "action", args.str("action"))
            }
        }
    }

    private val sessionAudit = object : ToolHandler {
        override val meta = ToolMeta("taffy_session_audit",
            "【SO 编辑会话】审计日志（audit/persist/list/load）",
            "[SO EDIT SESSION] Edit session audit trail: view audit, persist to file, list saved audits, or load a saved audit.",
            "session", ToolClass.EXTRA, heavy = true,
        ) { objectSchema(props {
            "action".oneOf("audit（默认）| persist | list | load", "audit", "persist", "list", "load")
            "workspaceId" str "工作区 ID（audit/persist）"
            "editSessionId" str "编辑会话 ID（audit/persist）"
            "prefix" str "文件前缀过滤（list）"
            "limit" int "返回条数上限（list）"
            "file" str "审计文件路径（load）"
        }, required = listOf("workspaceId")) }
        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val e = ctx.engine
            return when (args.str("action", "audit")) {
                "audit" -> e.editAudit(args.str("workspaceId"), args.str("editSessionId"))
                "persist" -> e.persistAudit(args.str("workspaceId"), args.str("editSessionId"))
                "list" -> e.listAudits(args.str("prefix"), args.intValue("limit", 100))
                "load" -> e.loadAudit(args.str("file"))
                else -> err("UNKNOWN_ACTION", "Unknown action: ${args.str("action")}", "action", args.str("action"))
            }
        }
    }

    // ── BUILD ──

    private val buildSo = object : ToolHandler {
        override val meta = ToolMeta("taffy_build_so",
            "构建补丁后的 SO（action=build 输出文件 / action=list 列出已构建）",
            "Build patched SO to file, or list built outputs. Supports single and multi-variant build.",
            "build", ToolClass.CORE, heavy = true,
        ) { objectSchema(props {
            "action".oneOf("build（默认）| list", "build", "list")
            "workspaceId" str "工作区 ID（build）"
            "editSessionId" str "编辑会话 ID（build）"
            "outputName" str "输出文件名（build）"
            "outputs" arr ("输出变体数组（multi-build）" to SchemaBuilder.outputsSchema())
            "conflictStrategy".oneOf("skip | overwrite | rename（默认）", "skip", "overwrite", "rename")
            "writeReport" bool "写入补丁报告 JSON 伴随文件"
            "writeToWorkDir" bool "把输出镜像到工作目录"
            "prefix" str "文件前缀过滤（list）"
            "limit" int "返回条数上限（list）"
        }, required = listOf("workspaceId")) }
        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val e = ctx.engine
            val s = ctx.settings
            return when (args.str("action", "build")) {
                "list" -> e.listBuildOutputs(args.str("prefix"), args.intValue("limit", 200))
                else -> {
                    val outputs = args.optJSONArray("outputs")
                    if (outputs != null && outputs.length() > 0) {
                        e.buildMany(args.str("workspaceId"), args.str("editSessionId"), outputs, args.str("conflictStrategy"), args.optBoolean("writeReport", s.writePatchReport), args.optBoolean("writeToWorkDir", s.buildCopyToWorkDir))
                    } else {
                        e.build(args.str("workspaceId"), args.str("editSessionId"), args.str("outputName"), args.str("conflictStrategy"), args.optBoolean("writeReport", s.writePatchReport), args.optBoolean("writeToWorkDir", s.buildCopyToWorkDir))
                    }
                }
            }
        }
    }

    // ── SYSTEM ──

    private val systemControl = object : ToolHandler {
        override val meta = ToolMeta("taffy_system_control",
            "系统控制（tunnel/apk_mcp/status）",
            "System control: tunnel start/stop/status, APK MCP bridge status/probe/ping, overall system status.",
            "system", ToolClass.META,
        ) { objectSchema(props {
            "action".oneOf("status | tunnel_start | tunnel_stop | tunnel_status | tunnel_stats | apk_status | apk_probe | apk_ping", "status", "tunnel_start", "tunnel_stop", "tunnel_status", "tunnel_stats", "apk_status", "apk_probe", "apk_ping")
            "mode".oneOf("隧道模式：quick | named（tunnel_start）", "quick", "named")
            "targetPort" int "隧道目标端口（tunnel_start）"
            "publicUrl" str "要显示并持久化的具名隧道公网 HTTPS 主机名/URL（tunnel_start）"
            "probe" bool "强制重新探测（apk_status/status）"
        }, required = listOf("action")) }
        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val hooked = ctx as? HookedContext ?: return JSONObject().put("error", "System hooks not available")
            return when (args.str("action", "status")) {
                "status" -> hooked.sysStatusHook(args.bool("probe", false))
                "tunnel_start" -> hooked.tunnelStartHook(args.str("mode", "quick"), args.intValue("targetPort", 0), "", args.optString("publicUrl").takeIf { it.isNotBlank() })
                "tunnel_stop" -> hooked.tunnelStopHook()
                "tunnel_status" -> hooked.tunnelStatusHook()
                "tunnel_stats" -> hooked.tunnelStatsHook(args.bool("probe", false))
                "apk_status" -> hooked.apkStatusHook(args.bool("probe", false))
                "apk_probe" -> hooked.apkProbeHook()
                "apk_ping" -> hooked.apkPingHook()
                else -> err("UNKNOWN_ACTION", "Unknown action: ${args.str("action")}", "action", args.str("action"))
            }
        }
    }

    private val appConfig = object : ToolHandler {
        override val meta = ToolMeta("taffy_app_config",
            "读写应用配置（外观/服务/引擎/隧道/桥接）。安全敏感字段（authEnabled/bindHost/accessToken）不可通过此工具修改。",
            "Read and write app settings: appearance, engine limits, tunnel, APK bridge. Security-sensitive fields (authEnabled, bindHost, accessToken) are read-only via this tool for safety. ⚠️ For UI changes prefer the in-app Settings pages; use this tool only when you need programmatic batch configuration.",
            "system", ToolClass.META,
        ) {
            objectSchema(props {
                "action".oneOf("get（默认）| set | schema", "get", "set", "schema")
                "maskSecrets" bool "get 输出中屏蔽令牌（默认 true）"
                "config" str "set 用的 JSON 对象字符串或嵌套对象（appearance/service/engine/tunnel/apkBridge 或扁平键）"
                "themeMode".oneOf("扁平设置辅助项", "system", "light", "dark")
                "accentColor".oneOf("扁平设置辅助项", "blue", "teal", "indigo", "purple", "green", "orange", "red", "mono")
                "uiDensity".oneOf("扁平设置辅助项", "compact", "comfortable", "spacious")
                "cornerStyle".oneOf("扁平设置辅助项", "small", "medium", "large", "xlarge")
                "motionMode".oneOf("扁平设置辅助项", "system", "reduced", "full")
                "textScale".oneOf("扁平设置辅助项", "normal", "large", "xlarge")
                "language".oneOf("扁平设置辅助项", "system", "zh", "en")
                "pureBlackDark" bool "扁平设置辅助项"
                "showAdvancedHome" bool "扁平设置辅助项"
                "highContrast" bool "扁平设置辅助项"
                "port" int "扁平设置辅助项"
                "leanTools" bool "扁平设置辅助项"
                "emulationEnabled" bool "扁平设置辅助项"
                "tunnelMode".oneOf("扁平设置辅助项", "off", "quick", "named")
                "apkMcpUrl" str "扁平设置辅助项"
            })
        }
        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val settings = ctx.settings
            return when (args.str("action", "get")) {
                "schema" -> ok(settings.schema())
                // 安全修复: 移除 reset_token 远程调用能力。
                // 原实现允许通过 MCP 工具远程重置 accessToken 并返回新 Token，
                // 攻击者可在无需认证的情况下获取有效 Token。
                "set" -> {
                    val patch = when {
                        args.opt("config") is JSONObject -> args.getJSONObject("config")
                        args.optString("config").isNotBlank() -> runCatching { JSONObject(args.optString("config")) }.getOrElse {
                            return err("INVALID_CONFIG", "config must be a JSON object: ${it.message}")
                        }
                        else -> args
                    }
                    // 安全修复: 强制 allowSecrets=false, allowSecurityFields=false
                    // 防止通过远程工具调用修改认证开关、绑定地址和 Token。
                    settings.applyPatch(patch, allowSecrets = false, allowSecurityFields = false)
                }
                else -> ok(settings.snapshot(maskSecrets = args.bool("maskSecrets", true)))
            }
        }
    }

    // ── META ──

    private val metaInfo = object : ToolHandler {
        override val meta = ToolMeta("taffy_meta_info",
            "元信息（help/tools/stats/batch/continue/health）",
            "Meta information: help text, tool list/describe, stats, batch pipeline, continue pagination, health check.",
            "meta", ToolClass.META,
        ) { objectSchema(props {
            "action".oneOf("help（默认）| tools | describe | stats | batch | continue | health | count | workflows | suggest | errors | report | capabilities", "help", "tools", "describe", "stats", "batch", "continue", "health", "count", "workflows", "suggest", "errors", "report", "capabilities")
            "category" str "类别过滤（tools）"
            "query" str "搜索查询词（tools）"
            "tools" arr "工具名数组（describe）"
            "steps" arr ("批处理流水线步骤（batch）" to SchemaBuilder.batchStepsSchema())
            "cursor" str "分页游标（continue）"
            "transactional" bool "为 true 时在批处理步骤前做编辑会话快照，后续步骤失败时回滚"
            "workspaceId" str "工作区 ID（suggest/report）"
            "editSessionId" str "编辑会话 ID（suggest/report）"
            "format".oneOf("报告格式", "json")
            "writeToFile" bool "将报告 JSON 写入应用文件（report）"
            "reset" bool "重置统计（stats）"
        }, required = listOf("action")) }
        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val hooked = ctx as? HookedContext ?: return JSONObject().put("error", "Meta hooks not available")
            return when (args.str("action", "help")) {
                "help" -> hooked.helpHook()
                "tools" -> hooked.listToolsHook(args.str("category"), args.str("query"))
                "describe" -> {
                    val names = args.optJSONArray("tools") ?: JSONArray()
                    val list = (0 until names.length()).map { names.optString(it) }
                    hooked.describeToolsHook(list)
                }
                "stats" -> {
                    if (args.bool("reset", false)) hooked.resetStatsHook()
                    hooked.statsHook()
                }
                "workflows" -> hooked.workflowsHook()
                "suggest" -> hooked.suggestHook(args)
                "errors" -> hooked.errorsHook()
                "report" -> hooked.reportHook(args)
                "capabilities" -> hooked.capabilitiesHook()
                "batch" -> hooked.batchHook(args)
                "continue" -> hooked.continueHook(args.str("cursor"))
                "health" -> hooked.healthHook()
                "count" -> hooked.toolsCountHook()
                else -> err("UNKNOWN_ACTION", "Unknown action: ${args.str("action")}", "action", args.str("action"))
            }
        }
    }

    // ── Registry ──

    val ALL: List<ToolHandler> = listOf(
        soOpen, soClose, apkAnalyze, flutterBlutter,
        analyzeElf, readStats, analysisReport, analyzeFunctions, analyzeCfg, analyzeCrypto, analyzeXrefs, analyzeEsil, ArscTool.analyze, AnalyzeGuideTool.guide,
        searchBytes, searchStrings,
        readDisasm, readHexdump,
        editHex, editAsm, editSymbol, editFixSections,
        emulateCall, emulateDump,
        unidbgSession, unidbgMemory, unidbgDebug, unidbgBatch,
        diffSo,
        rizinApi, liefApi, unidbgApi, xansoApi,
        sessionOpen, sessionHistory, sessionAudit,
        buildSo,
        systemControl,
        appConfig,
        metaInfo,
        JadxTool.decompile,
        StaticTools.baksmali,
        StaticTools.apkDecode,
        FridaTool.control,
        ApkBuildTool.smaliAssemble,
        ApkBuildTool.apkSign,
        ApkEditorTool.rebuild,
        UnpackTool.dexDump,
        DexKitTool.search,
        StringScanTool.scan,
        ApkSignInfoTool.info,
        ApkDiffTool.diff,
        ApkExtractTool.extract,
        // 塔菲逆核: 通用文件操作
        FileTools.list,
        FileTools.read,
        FileTools.write,
        FileTools.search,
        FileTools.replace,
        FileTools.diff,
        FileTools.rename,
        FileTools.copy,
        FileTools.delete,
        FileTools.batchRename,
        // 塔菲逆核: 通用压缩解压
        ArchiveTools.list,
        ArchiveTools.extract,
        ArchiveTools.create,
        ArchiveTools.add,
        ArchiveTools.delete,
        ArchiveTools.rename,
        *DotnetTools.ALL.toTypedArray(),
        // 塔菲逆核: 纯 Java SO 分析工具(SO逆向分析工具移植)
        SoStandaloneTools.disasm,
        SoStandaloneTools.elf,
        SoStandaloneTools.hexdump,
        // 塔菲逆核: 设备信息/系统/应用/通讯/网络/实用工具(参考mcp-server)
        *DeviceTools.ALL.toTypedArray(),
        // 塔菲逆核: APK 细粒度编辑(参考"我的工具"APK)
        *ApkEditTools.ALL.toTypedArray(),
        // 塔菲逆核: Logcat 日志采集(参考NexusBridge LogFox)
        *LogcatTools.ALL.toTypedArray(),
        // 塔菲逆核: 进阶逆向辅助(参考MT管理器 native_xref/cfg/patch_bytes/taffy_apk_search)
        *AdvancedTools.ALL.toTypedArray(),
        // 塔菲逆核: Unicorn 直接CPU模拟(参考Flutter解析工具的libunicorn_java独立模拟)
        *UnicornTools.ALL.toTypedArray(),
        // 塔菲逆核: Smali增量编辑(参考MT管理器edit_open/edit_text增量流程)
        *SmaliEditTools.ALL.toTypedArray(),
        // 塔菲逆核: Smali全量批处理(参考MT管理器全量改包: 解包→批量改→回编签名)
        *SmaliBatchTool.ALL.toTypedArray(),
        // 塔菲逆核: 通用编辑快照(diff/回滚, 对任意文件格式生效)
        EditSnapshotService.handler(),
        // 塔菲逆核: 常用逆向 Patch 模板(一键高频操作, 复用现有工具)
        *PatchTemplateTool.ALL.toTypedArray(),
        // 塔菲逆核: 高级DEX分析(指令级交叉引用/类大纲/字节码/deodex/增量重编)
        *DexAnalysisTools.ALL.toTypedArray(),
        // 塔菲逆核: 高精度Manifest编辑+资源交叉引用(用ARSCLib, 不用正则)
        *ManifestEditTools.ALL.toTypedArray(),
        // 塔菲逆核: Native指令/字符串补丁(CAS乐观锁)+APK统一搜索(分页游标)
        *NativePatchTools.ALL.toTypedArray(),
    )

    internal val registry = ToolCatalogRegistry(ALL)
    val byName: Map<String, ToolHandler> = registry.byName
    val heavyNames: Set<String> = registry.heavyNames
    val names: List<String> = registry.names
    fun leanNames(): List<String> = registry.leanNames()

    fun leanNames(popularity: Map<String, Long>?): List<String> = registry.leanNames(popularity)

    fun description(name: String, zh: Boolean): String = registry.description(name, zh)

    fun categoryDescriptions(zh: Boolean): List<Pair<String, String>> = ToolCatalogPresentation.categoryDescriptions(zh)

    fun grouped(zh: Boolean, includeApk: List<String> = emptyList()): List<Pair<String, List<Pair<String, String>>>> =
        ToolCatalogPresentation.grouped(zh, includeApk)

    fun toolDescriptor(handler: ToolHandler, includeCategory: Boolean): JSONObject =
        ToolCatalogPresentation.toolDescriptor(handler, includeCategory)

    fun categoryOf(name: String): String? = registry.categoryOf(name)
}
