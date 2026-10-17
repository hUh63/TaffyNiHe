package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.str
import com.soreverse.mcp.engine.NativeSoEngine
import org.json.JSONObject

/**
 * 塔菲逆核: SO 静态深挖三件套 —— 加密字符串、JNI 注册表、导入归因。
 *
 * 这三个问题在逆向现场最常被「猜」而不是被「查」:
 *  - 字符串在 `.rodata` 里是乱码 → 一般直接放弃, 或靠动态调试读内存;
 *  - `RegisterNatives` 之后函数表没有符号 → 只能看到 `sub_xxxx`, 不知道对应哪个 Java 方法;
 *  - 看到 `pthread_create`/`SSL_read` 却答不出「是哪个依赖库提供的」。
 *
 * 三个工具都只做静态分析、只读工作区, 不执行目标代码。共同约定:
 *  - 只传 `path` 时会自动打开临时工作区（与 analyze_elf / read_disasm 一致）, 不必先 taffy_so_open;
 *  - 每个结果都带 `method` / `knownFailureMode` / `nextStep`, 把「这一步能证什么、证不了什么、
 *    下一步换哪条路」写清楚, 避免把启发式猜测当成确证。
 */
object DeepReverseTools {

    /** 解析目标工作区: 显式 workspaceId 优先, 否则按 path 自动打开临时工作区。 */
    private class WsRef(val workspaceId: String, val error: JSONObject?)

    private fun workspaceOf(engine: NativeSoEngine, args: JSONObject): WsRef {
        val explicit = args.str("workspaceId").trim()
        if (explicit.isNotBlank()) return WsRef(explicit, null)
        val p = args.str("path").ifBlank { args.str("filePath") }.trim()
        if (p.isBlank()) {
            return WsRef(
                "",
                err(
                    "INVALID_ARGUMENT",
                    "缺少 workspaceId 或 path（可只传 path 自动打开临时工作区）",
                    "workspaceId",
                    ""
                )
            )
        }
        val opened = engine.open(p, true)
        if (!opened.optBoolean("ok", false)) return WsRef("", opened)
        val id = opened.optString("workspaceId")
        if (id.isBlank()) {
            return WsRef("", err("OPEN_FAILED", "打开文件后引擎未返回 workspaceId", "path", p))
        }
        return WsRef(id, null)
    }

    val stringDecrypt: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_so_string_decrypt",
            "【加密字符串辅助解密】静态恢复被字符串加密（OLLVM struc / 商业壳 / 手写 XOR 解密桩）保护的字符串字面量。action=scan(默认) 扫描 .rodata/.data 中「既不是可打印文本也不是零填充」的字节段并按熵排序；action=decrypt 对指定段尝试单字节变换(XOR/加/减/取反/位反转)、循环密钥 XOR、RC4，用「可打印率 + 像语言比率」双闸门判真，返回排名候选 + 置信度 + 每个候选的实际输出；action=hints 说明本工具证不了的情形（运行时派生的密钥、AES/白盒、表驱动 S 盒）以及该转哪条路（dynamic_api 内存读 / unidbg）。只读，不执行目标代码。",
            "Statically recover string literals protected by string-encryption passes (OLLVM struc, commercial packers, hand-rolled XOR stubs). action=scan (default) walks .rodata/.data for byte runs that are neither printable text nor zero padding, ranked by entropy; action=decrypt tries single-byte transforms (XOR/add/sub/NOT/bit-reverse), repeating-key XOR and RC4 on a given blob, gating candidates on printable-ratio plus language-likeness, returning ranked candidates with confidence and their actual output; action=hints documents what this pass cannot prove (runtime-derived keys, AES/white-box, table-driven S-boxes) and where to go instead (dynamic_api memory read / unidbg). Read-only, never executes target code.",
            "analyze",
            ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "action" str "scan(默认)=扫描密文段 | decrypt=对指定段尝试解密 | hints=能力边界与替代路径"
                "workspaceId" str "工作区 ID（可与 path 二选一）"
                "path" str "SO 文件绝对路径：未提供 workspaceId 时自动打开临时工作区"
                "filePath" str "path 的别名"
                "locator" str "decrypt 必填：密文段起始位置（0x 虚拟地址 / 节区定位符 / 节区名）"
                "length" int "decrypt：密文长度字节（默认 32，范围 6~4096）"
                "keyHex" str "decrypt 可选：已知密钥（十六进制），提供后只验证该密钥不再盲扫"
                "limit" int "scan：最多返回多少段（默认 40）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "scan").ifBlank { "scan" }.lowercase()
            val engine = EngineProvider.get(ctx.context)
            if (action == "hints") return engine.strDecryptHints()
            if (action == "capabilities") return engine.strDecryptCapabilities()
            val ws = workspaceOf(engine, args)
            ws.error?.let { return it }
            return when (action) {
                "scan" -> engine.strDecryptScan(ws.workspaceId, "", args.intValue("limit", 40))
                "decrypt" -> engine.strDecrypt(
                    ws.workspaceId,
                    "",
                    args.str("locator").trim(),
                    args.intValue("length", 32),
                    args.str("keyHex").trim(),
                )
                else -> err(
                    "INVALID_ARGUMENT",
                    "未知 action: $action（可选 scan / decrypt / hints / capabilities）",
                    "action",
                    action
                )
            }
        }
    }

    val jniRegister: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_so_jni_register",
            "【JNI 注册表恢复】静态恢复 JNI_OnLoad 里 RegisterNatives 注册的 JNINativeMethod 表：给出「Java 方法名 → (签名, 函数地址, 所属类)」的完整映射，把一堆 sub_xxxx 还原成 com.xxx.Foo.nativeBar 这种可读身份。action=scan(默认) 扫描只读数据段里的 {name*, sig*, fnPtr} 三元组并按 Java_* 符号反推类名；action=resolve 按方法名/类名/地址查具体条目。纯静态（不执行 SO），因此运行时才拼出来的表、加密的方法名字符串、走 GOT 重定位后才有效的 fnPtr 会有明确标注。",
            "Statically recover the JNINativeMethod table registered through RegisterNatives inside JNI_OnLoad: a full 'Java method -> (signature, fn pointer, owning class)' mapping that turns sub_xxxx stubs back into com.xxx.Foo.nativeBar identities. action=scan (default) sweeps read-only data sections for {name*, sig*, fnPtr} triples and unmangles class names from Java_* symbols; action=resolve looks up an entry by method name, class name or address. Purely static (never executes the SO), so runtime-built tables, encrypted name strings and GOT-resolved fn pointers are explicitly flagged instead of guessed.",
            "analyze",
            ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "action" str "scan(默认)=恢复整张注册表 | resolve=按关键字查条目"
                "workspaceId" str "工作区 ID（可与 path 二选一）"
                "path" str "SO 文件绝对路径：未提供 workspaceId 时自动打开临时工作区"
                "filePath" str "path 的别名"
                "query" str "resolve 必填：方法名 / 类名 / 0x 函数地址（子串匹配）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "scan").ifBlank { "scan" }.lowercase()
            val engine = EngineProvider.get(ctx.context)
            if (action == "capabilities") return engine.jniCapabilities()
            val ws = workspaceOf(engine, args)
            ws.error?.let { return it }
            return when (action) {
                "scan" -> engine.jniScan(ws.workspaceId, "")
                "resolve" -> {
                    val q = args.str("query").trim()
                    if (q.isBlank()) {
                        err("INVALID_ARGUMENT", "resolve 需要 query（方法名 / 类名 / 0x 地址）", "query", "")
                    } else {
                        engine.jniResolve(ws.workspaceId, "", q)
                    }
                }
                else -> err(
                    "INVALID_ARGUMENT",
                    "未知 action: $action（可选 scan / resolve / capabilities）",
                    "action",
                    action
                )
            }
        }
    }

    val importTrace: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_so_import_trace",
            "【导入归因】回答「这个导入符号到底来自哪个库」。DT_NEEDED 只列依赖库、不说明符号来源；本工具读 .gnu.version / .gnu.version_r（DT_VERNEED）把每个 dynsym 的版本索引映射回版本名与提供库，并给每个库贴上角色说明（liblog=日志、libssl=网络加密、libc++_static=C++ 运行时…）。同时按能力分组（调试/反调试、网络、加密、JNI、动态加载、文件 IO、进程线程），让「这个 SO 会不会联网 / 会不会 dlopen 自解密 / 有没有反调试」一眼可答。版本表缺失时退化为「按 DT_NEEDED 声明与符号名推断」，并在每条记录里标明来源模式，不把推断当确证。",
            "Answer 'which library does this imported symbol actually come from'. DT_NEEDED only lists dependencies without attributing symbols; this tool reads .gnu.version/.gnu.version_r (DT_VERNEED) to map each dynsym's version index back to a version name and providing library, annotating each library with its role (liblog=logging, libssl=network crypto, libc++_static=C++ runtime...). Imports are also grouped by capability (debug/anti-debug, network, crypto, JNI, dynamic loading, file IO, process/threads) so 'does this SO talk to the network / dlopen-decrypt itself / carry anti-debug' is answerable at a glance. When the version table is absent attribution degrades to a documented DT_NEEDED-based inference, and every record states which mode produced it.",
            "analyze",
            ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "workspaceId" str "工作区 ID（可与 path 二选一）"
                "path" str "SO 文件绝对路径：未提供 workspaceId 时自动打开临时工作区"
                "filePath" str "path 的别名"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val engine = EngineProvider.get(ctx.context)
            val ws = workspaceOf(engine, args)
            ws.error?.let { return it }
            return engine.importTrace(ws.workspaceId, "")
        }
    }

    val ALL: List<ToolHandler> = listOf(stringDecrypt, jniRegister, importTrace)
}
