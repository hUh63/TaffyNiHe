package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import com.soreverse.mcp.engine.NativeSoEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 塔菲逆核: SO 深度分析工具集(补齐 C++/原生二进制的分析闭环)。
 *
 *  - taffy_so_vtable:         C++ 虚表/RTTI 分析(rizin av/avj) + 导出 vtable 头文件
 *  - taffy_so_demangle:       C++ Itanium 符号还原(保守子集, 纯 Kotlin, 不依赖引擎)
 *  - taffy_so_func_sig:       函数签名还原(rizin afvj 聚合, 带 confidence/evidence)
 *  - taffy_so_jni_reg:        JNI RegisterNatives 静态还原(字符串描述符启发式)
 *  - taffy_so_pseudoc_batch:  批量伪 C 反编译(rizin-ghidra, 逐函数, 失败不中断)
 *
 * 设计原则: 引擎不可用/命令不支持时一律优雅降级(返回 supported=false + 原因),
 * 绝不抛未捕获异常; 所有启发式结论都带 confidence 字段, 不伪造成确定事实。
 */
object SoDeepTools {

    // ── 公共小工具 ────────────────────────────────────────────────

    /** rizin 命令的文本输出(原生层用 stdout, 兼容 text)。 */
    private fun cmdStdout(r: JSONObject): String =
        r.optString("stdout", "").ifBlank { r.optString("text", "") }

    /** 引擎返回的错误说明(可能是 message / error 字段)。 */
    private fun engineMessage(r: JSONObject): String =
        r.optString("message", "").ifBlank { r.optString("error", "") }

    /** stdout 若本身是 JSON 就解析出来, 否则 null。 */
    private fun parseJsonOrNull(s: String): Any? {
        val t = s.trim()
        if (t.isEmpty()) return null
        return runCatching {
            when {
                t.startsWith("[") -> JSONArray(t) as Any
                t.startsWith("{") -> JSONObject(t) as Any
                else -> null
            }
        }.getOrNull()
    }

    /** 把 JSON 里的地址值(数字或字符串)统一成 0x 十六进制字符串。 */
    private fun addrHex(v: Any?): String? {
        val n = when (v) {
            is Number -> v.toLong()
            is String -> {
                val s = v.trim()
                if (s.isEmpty()) null
                else runCatching {
                    if (s.startsWith("0x", true)) s.substring(2).toLong(16) else s.toLong()
                }.getOrNull()
            }
            else -> null
        } ?: return null
        return "0x" + java.lang.Long.toHexString(n)
    }

    /** 从函数列表项里取地址(兼容 addr / offset / startAddr)。 */
    private fun funcAddr(item: JSONObject): Long? {
        if (item.has("addr")) return item.optLong("addr")
        if (item.has("offset")) return item.optLong("offset")
        val s = item.optString("startAddr", "")
        if (s.isBlank()) return null
        return runCatching {
            if (s.startsWith("0x", true)) s.substring(2).toLong(16) else s.toLong()
        }.getOrNull()
    }

    /** 取函数列表(rzFunctions 的 functions 数组)。 */
    private fun functionItems(engine: NativeSoEngine, ws: String, limit: Int): JSONArray {
        val r = engine.rzFunctions(ws, "", limit, "")
        return r.optJSONArray("functions") ?: JSONArray()
    }

    /** JNI 字符串项(地址 + 内容)。 */
    private class JniStr(val va: Long, val text: String)

    // ── 1. 虚表 / RTTI ────────────────────────────────────────────

    val soVtable: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_so_vtable",
            "【C++ 虚表分析】识别 SO 中的 C++ 虚表(vtable)与 RTTI：类名、虚表地址、每个槽位指向的函数。" +
                "action=list 概览(默认); action=detail 看槽位明细; action=export 生成可直接参考的 vtable 头文件文本。" +
                "逆向 C++ 库时把『数偏移』变成『读代码』。底层走 rizin 的 av/avj 分析。",
            "Analyze C++ vtables/RTTI in a SO: class names, vtable addresses, slot functions. " +
                "action=list (default) | detail | export (generates a vtable header text). Backed by rizin av/avj.",
            "analyze", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "workspaceId" str "SO 工作区 ID(taffy_so_open 返回)"
                "action".oneOf("list(默认) | detail | export", "list", "detail", "export")
                "className" str "detail/export 指定类名或虚表地址(可空=全部/第一个)"
                "limit" int "返回的虚表条数上限(默认 50)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val ws = args.str("workspaceId")
            if (ws.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 workspaceId", "workspaceId", "")
            val action = args.str("action", "list").ifBlank { "list" }
            val want = args.str("className")
            val limit = args.intValue("limit", 50).coerceIn(1, 500)
            val engine = EngineProvider.get(ctx.context)

            val raw = engine.rzCommand(ws, "", "avj", false)
            val stdout = cmdStdout(raw)
            if (stdout.isBlank()) {
                return ok(JSONObject()
                    .put("workspaceId", ws)
                    .put("supported", false)
                    .put("vtableCount", 0)
                    .put("vtables", JSONArray())
                    .put("command", "avj")
                    .put("engineMessage", engineMessage(raw))
                    .put(
                        "note",
                        "rizin avj 无输出：该 SO 可能不含 C++ 虚表(RTTI)，或当前 rizin 构建未启用 C++ 分析",
                    )
                    .put("hint", "可用 taffy_rizin_api(workspaceId=..., action=\"command\", command=\"av\") 交叉验证"))
            }

            // 兼容数组 / {vtables:[...]} / 纯文本三种形态
            val parsed = parseJsonOrNull(stdout)
            val list = when (parsed) {
                is JSONArray -> parsed
                is JSONObject -> parsed.optJSONArray("vtables") ?: JSONArray().put(parsed)
                else -> null
            }
            if (list == null) {
                return ok(JSONObject()
                    .put("workspaceId", ws)
                    .put("supported", true)
                    .put("parsed", false)
                    .put("command", "avj")
                    .put("rawText", stdout)
                    .put("note", "avj 输出不是 JSON，已原样返回；可用 taffy_rizin_api 手动解析"))
            }

            val parsedItems = JSONArray()
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val methods = o.optJSONArray("methods") ?: JSONArray()
                val slots = JSONArray()
                for (j in 0 until methods.length()) {
                    val m = methods.optJSONObject(j) ?: continue
                    slots.put(JSONObject()
                        .put("index", j)
                        .put("name", m.optString("name", ""))
                        .put("addr", addrHex(m.opt("addr")) ?: m.optString("addr", "")))
                }
                parsedItems.put(JSONObject()
                    .put("className", o.optString("class", o.optString("name", "")))
                    .put("vtableAddr", addrHex(o.opt("addr")) ?: o.optString("addr", ""))
                    .put("slotCount", methods.length())
                    .put("slots", slots))
            }

            if (parsedItems.length() == 0) {
                return ok(JSONObject()
                    .put("workspaceId", ws)
                    .put("supported", true)
                    .put("vtableCount", 0)
                    .put("vtables", JSONArray())
                    .put("command", "avj")
                    .put("note", "avj 无虚表条目：该 SO 不含 RTTI 可见的 C++ 虚表"))
            }

            if (action == "export") {
                val sb = StringBuilder()
                sb.append("// vtable header generated by TaffyNiHe / taffy_so_vtable\n")
                sb.append("// source: rizin avj — 启发式还原, 使用前请人工核对\n\n")
                var emitted = 0
                for (i in 0 until parsedItems.length()) {
                    val v = parsedItems.optJSONObject(i) ?: continue
                    val cls = v.optString("className", "CxxClass$i")
                    val vAddr = v.optString("vtableAddr", "")
                    if (want.isNotBlank() && !cls.contains(want, true) && !vAddr.equals(want, true)) continue
                    val slots = v.optJSONArray("slots") ?: JSONArray()
                    sb.append("// vtable @ ").append(vAddr).append("  slots=").append(slots.length()).append('\n')
                    sb.append("class ").append(cls.replace(Regex("[^A-Za-z0-9_]"), "_")).append(" {\npublic:\n")
                    for (j in 0 until slots.length()) {
                        val s = slots.optJSONObject(j) ?: continue
                        val nm = s.optString("name", "").ifBlank { "slot_$j" }
                        sb.append("    virtual void* ").append(nm.replace(Regex("[^A-Za-z0-9_]"), "_")).append("();")
                        sb.append("  // slot ").append(j).append(" -> ").append(s.optString("addr", "?"))
                        sb.append('\n')
                    }
                    sb.append("};\n\n")
                    emitted++
                    if (emitted >= limit) break
                }
                return ok(JSONObject()
                    .put("workspaceId", ws)
                    .put("action", "export")
                    .put("exportedClasses", emitted)
                    .put("header", sb.toString())
                    .put("confidence", "heuristic")
                    .put("note", "槽位函数名多为 sub_xxx/未命名，可结合 taffy_analyze_functions 与 taffy_so_demangle 理解"))
            }

            val limited = JSONArray()
            var kept = 0
            for (i in 0 until parsedItems.length()) {
                val v = parsedItems.optJSONObject(i) ?: continue
                val okSel = want.isBlank() ||
                    v.optString("className").contains(want, true) ||
                    v.optString("vtableAddr").equals(want, true)
                if (!okSel) continue
                if (action == "detail") {
                    limited.put(v)
                } else {
                    limited.put(JSONObject()
                        .put("className", v.optString("className"))
                        .put("vtableAddr", v.optString("vtableAddr"))
                        .put("slotCount", v.optInt("slotCount")))
                }
                kept++
                if (kept >= limit) break
            }

            return ok(JSONObject()
                .put("workspaceId", ws)
                .put("action", action)
                .put("supported", true)
                .put("vtableCount", parsedItems.length())
                .put("returned", limited.length())
                .put("vtables", limited)
                .put("command", "avj")
                .put("confidence", "heuristic")
                .put("hint", "action=detail 看槽位明细; action=export 生成头文件"))
        }
    }

    // ── 2. C++ 符号还原(Itanium 保守子集) ──────────────────────────

    /** Itanium ABI 符号还原的保守实现: 只覆盖常见情形, 解析不了原样返回。 */
    private object Itanium {

        private val SIMPLE: Map<Char, String> = mapOf(
            'v' to "void", 'b' to "bool", 'c' to "char", 'a' to "signed char", 'h' to "unsigned char",
            's' to "short", 't' to "unsigned short", 'i' to "int", 'j' to "unsigned int",
            'l' to "long", 'm' to "unsigned long", 'x' to "long long", 'y' to "unsigned long long",
            'n' to "__int128", 'o' to "unsigned __int128", 'f' to "float", 'd' to "double",
            'e' to "long double", 'w' to "wchar_t", 'z' to "...",
        )

        private fun readNumber(s: String, i0: Int): Pair<Int, Int>? {
            var i = i0
            while (i < s.length && s[i] == '_') i++
            val start = i
            while (i < s.length && s[i].isDigit()) i++
            if (i == start) return null
            val n = s.substring(start, i).toIntOrNull() ?: return null
            return n to i
        }

        /** <长度><名字> 形式的名字(含 St = std)。 */
        private fun plainName(s: String, i0: Int): Pair<String, Int>? {
            if (i0 >= s.length) return null
            if (s.startsWith("St", i0)) return "std" to (i0 + 2)
            val n = readNumber(s, i0) ?: return null
            val start = n.second
            val end = start + n.first
            if (end > s.length) return null
            return s.substring(start, end) to end
        }

        /** 解析一个类型码, 返回 (类型名, 下一个位置); 无法解析返回 null。 */
        private fun type(s: String, i0: Int): Pair<String, Int>? {
            if (i0 >= s.length) return null
            var i = i0
            var prefix = ""
            while (i < s.length && (s[i] == 'K' || s[i] == 'V' || s[i] == 'r')) {
                if (s[i] == 'K') prefix += "const "
                if (s[i] == 'V') prefix += "volatile "
                i++
            }
            if (i >= s.length) return null
            when (s[i]) {
                'P' -> {
                    val inner = type(s, i + 1) ?: return null
                    return (prefix + inner.first + "*") to inner.second
                }
                'R' -> {
                    val inner = type(s, i + 1) ?: return null
                    return (prefix + inner.first + "&") to inner.second
                }
                'O' -> {
                    val inner = type(s, i + 1) ?: return null
                    return (prefix + inner.first + "&&") to inner.second
                }
                'A' -> {
                    val n = readNumber(s, i + 1) ?: return null
                    var k = n.second
                    if (k < s.length && s[k] == '_') k++
                    val inner = type(s, k) ?: return null
                    return (prefix + inner.first + "[" + n.first + "]") to inner.second
                }
                'N' -> {
                    val names = ArrayList<String>()
                    var j = i + 1
                    while (j < s.length && s[j] != 'E') {
                        val nm = plainName(s, j) ?: break
                        names.add(nm.first)
                        j = nm.second
                    }
                    if (names.isEmpty()) return null
                    val after = if (j < s.length && s[j] == 'E') j + 1 else j
                    return (prefix + names.joinToString("::")) to after
                }
                'L' -> {
                    val n = readNumber(s, i + 1) ?: return null
                    val end = (n.second + n.first).coerceAtMost(s.length)
                    if (end <= n.second) return null
                    return (prefix + s.substring(n.second, end)) to (end + 1).coerceAtMost(s.length + 1)
                }
                else -> {
                    val simple = SIMPLE[s[i]] ?: return null
                    return (prefix + simple) to (i + 1)
                }
            }
        }

        /** 尝试还原; 失败返回 null。 */
        fun demangle(mangled: String): String? {
            val m = mangled.trim()
            if (!m.startsWith("_Z")) return null
            if (m.startsWith("_ZTV") || m.startsWith("_ZTI") || m.startsWith("_ZTS") || m.startsWith("_ZTT")) return null
            var i = 2
            val names = ArrayList<String>()
            var nested = false
            if (i < m.length && m[i] == 'N') {
                nested = true
                i++
            }
            while (i < m.length && m[i] != 'E') {
                val n = plainName(m, i) ?: break
                names.add(n.first)
                i = n.second
            }
            if (names.isEmpty()) return null
            if (nested) {
                if (i >= m.length || m[i] != 'E') return null
                i++
            }
            val sb = StringBuilder(names.joinToString("::"))
            if (i >= m.length) return sb.toString()
            sb.append('(')
            val args = ArrayList<String>()
            var j = i
            while (j < m.length) {
                val t = type(m, j) ?: return null
                args.add(t.first)
                j = t.second
            }
            sb.append(args.joinToString(", ")).append(')')
            return sb.toString()
        }
    }

    val soDemangle: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_so_demangle",
            "【C++ 符号还原(demangle)】把 Itanium ABI 的 mangled 符号(如 _ZN4Test3fooEi)还原成可读形式" +
                "(Test::foo(int))。给 symbol 参数还原单个; 给 workspaceId 则批量还原该 SO 符号表里的 _Z* 符号。" +
                "覆盖常见情形(全局/嵌套名/指针/引用/const/数组), 解析不了的会原样返回并标 ok=false。",
            "Demangle Itanium ABI C++ symbols (_ZN4Test3fooEi -> Test::foo(int)). " +
                "Pass symbol for one, or workspaceId to batch-demangle _Z* symbols in a SO. Unsupported forms return ok=false.",
            "analyze", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "symbol" str "要还原的单个 mangled 符号(如 _ZN4Test3fooEi)"
                "workspaceId" str "SO 工作区 ID(批量模式, 与 symbol 二选一)"
                "filter" str "批量模式: 只处理名字含该子串的符号"
                "limit" int "批量模式: 最多扫描多少个符号(默认 200)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val one = args.str("symbol")
            if (one.isNotBlank()) {
                val d = Itanium.demangle(one)
                return ok(JSONObject()
                    .put("mangled", one)
                    .put("demangled", d ?: one)
                    .put("ok", d != null)
                    .put("engine", "builtin-itanium-subset")
                    .put("note", if (d == null) "解析不了该形式(可能是模板/替换/非 Itanium), 原样返回" else ""))
            }

            val ws = args.str("workspaceId")
            if (ws.isBlank()) return err("INVALID_ARGUMENT", "需要 symbol(单个) 或 workspaceId(批量) 之一", "symbol", "")
            val filter = args.str("filter")
            val limit = args.intValue("limit", 200).coerceIn(1, 2000)
            val engine = EngineProvider.get(ctx.context)

            val symbols = engine.list(ws, "", "dynsyms", filter, limit).optJSONArray("items") ?: JSONArray()
            val arr = JSONArray()
            var mangled = 0
            var restored = 0
            for (i in 0 until symbols.length()) {
                val o = symbols.optJSONObject(i) ?: continue
                val nm = o.optString("name", "")
                if (nm.isBlank() || !nm.startsWith("_Z")) continue
                mangled++
                val d = Itanium.demangle(nm)
                if (d != null) restored++
                arr.put(JSONObject()
                    .put("mangled", nm)
                    .put("demangled", d ?: nm)
                    .put("ok", d != null)
                    .put("addr", addrHex(o.opt("value").let { if (it == null) o.opt("addr") else it }) ?: ""))
            }
            return ok(JSONObject()
                .put("workspaceId", ws)
                .put("source", "dynsyms")
                .put("scanned", symbols.length())
                .put("mangledFound", mangled)
                .put("restored", restored)
                .put("items", arr)
                .put("engine", "builtin-itanium-subset")
                .put("confidence", "partial")
                .put("note", "内置实现只覆盖 Itanium 常见形式; 模板/替换(S_)、MSVC 符号不支持"))
        }
    }

    // ── 3. 函数签名还原 ───────────────────────────────────────────

    val soFuncSig: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_so_func_sig",
            "【函数签名还原】还原函数的参数列表: 基于 rizin 的局部变量/参数寄存器信息启发式推断(strip 后不再满屏 sub_xxxx)。" +
                "给 locator 看单个函数; 不给则批量取前 count 个函数。每个结果都带 confidence 与 evidence 字段, 低置信会明确标注, 不伪造确定结论。",
            "Recover function signatures (argument list) from rizin local-variable/argument metadata. " +
                "Pass locator for one function, or omit for the first `count` functions. Every result carries confidence and evidence.",
            "analyze", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "workspaceId" str "SO 工作区 ID"
                "locator" str "目标函数: 函数名/符号/虚拟地址(可空=批量)"
                "count" int "批量模式最多分析多少个函数(默认 10, 上限 50)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val ws = args.str("workspaceId")
            if (ws.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 workspaceId", "workspaceId", "")
            val engine = EngineProvider.get(ctx.context)

            val targets = ArrayList<Pair<String, Long>>()
            val locator = args.str("locator")
            if (locator.isNotBlank()) {
                val va = runCatching {
                    if (locator.startsWith("0x", true)) locator.substring(2).toLong(16) else null
                }.getOrNull()
                targets.add(locator to (va ?: 0L))
            } else {
                val count = args.intValue("count", 10).coerceIn(1, 50)
                val fns = functionItems(engine, ws, 500)
                for (i in 0 until fns.length()) {
                    if (targets.size >= count) break
                    val f = fns.optJSONObject(i) ?: continue
                    val nm = f.optString("name")
                    val a = funcAddr(f) ?: continue
                    if (nm.isBlank()) continue
                    targets.add(nm to a)
                }
                if (targets.isEmpty()) {
                    return ok(JSONObject()
                        .put("workspaceId", ws)
                        .put("functionCount", 0)
                        .put("signatures", JSONArray())
                        .put("note", "rizin 未识别到函数(可能 strip 严重); 可先用 taffy_rizin_api action=analyze 触发分析"))
                }
            }

            val out = JSONArray()
            for (pair in targets) {
                val nm = pair.first
                val va = pair.second
                val seek = if (va > 0) "0x" + java.lang.Long.toHexString(va) else nm
                val varsRaw = engine.rzCommand(ws, "", "s $seek; afvj", false)
                val parsed = parseJsonOrNull(cmdStdout(varsRaw))
                val argArr = JSONArray()
                val localArr = JSONArray()
                if (parsed is JSONArray) {
                    for (i in 0 until parsed.length()) {
                        val v = parsed.optJSONObject(i) ?: continue
                        val reg = v.optString("reg", "")
                        val isArg = v.optString("kind", "").contains("arg", true) ||
                            reg.startsWith("x") || reg.startsWith("r") || reg.startsWith("w")
                        val entry = JSONObject()
                            .put("name", v.optString("name", ""))
                            .put("type", v.optString("type", "unknown"))
                            .put("reg", reg)
                            .put("delta", v.optLong("delta", 0L))
                        if (isArg) argArr.put(entry) else localArr.put(entry)
                    }
                }
                val confidence = when {
                    argArr.length() > 0 -> "medium"
                    parsed is JSONArray -> "low"
                    else -> "none"
                }
                out.put(JSONObject()
                    .put("function", nm)
                    .put("addr", if (va > 0) "0x" + java.lang.Long.toHexString(va) else "")
                    .put("returnType", "unknown")
                    .put("args", argArr)
                    .put("argCount", argArr.length())
                    .put("locals", localArr)
                    .put("confidence", confidence)
                    .put("evidence", "rizin afvj(kind=arg 或参数寄存器)" + if (parsed !is JSONArray) "; afvj 无 JSON 输出" else "")
                    .put("typeConfidenceNote", "类型与返回值为启发式推断, 不是 ABI 保证的事实"))
            }

            return ok(JSONObject()
                .put("workspaceId", ws)
                .put("analyzed", out.length())
                .put("signatures", out)
                .put("hint", "参数寄存器(ARM64 x0-x7 / ARM32 r0-r3)读取位置是主要依据; 建议结合 taffy_so_demangle 看懂符号"))
        }
    }

    // ── 4. JNI RegisterNatives 静态还原 ───────────────────────────

    private val JNI_DESCRIPTOR =
        Regex("""^\((?:\[*(?:[VZBCSIJFD]|L[^;]+;))*\)(?:\[*(?:[VZBCSIJFD]|L[^;]+;))+$""")
    private val JAVA_METHOD_NAME = Regex("""^[A-Za-z_$][A-Za-z0-9_$]{0,127}$""")

    val soJniReg: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_so_jni_reg",
            "【JNI RegisterNatives 还原】静态还原动态注册的 JNI 方法表: 扫描 SO 内的 JNI 方法描述符(如 (Landroid/content/Context;)V)与相邻方法名," +
                "配对出「Java 方法名 ↔ 签名」映射, 并检查 RegisterNatives / JNI_OnLoad 是否存在。用于补回动态注册后『看不见』的 Java_xxx 函数名。" +
                "结果为字符串启发式(带 via/confidence 字段), 建议与 taffy_analyze_functions 交叉验证。",
            "Statically recover JNI RegisterNatives tables: scans JNI method descriptors and adjacent names to pair " +
                "\"Java method name <-> signature\", and reports whether RegisterNatives / JNI_OnLoad exist. " +
                "Heuristic (carries via/confidence); cross-check with taffy_analyze_functions.",
            "analyze", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "workspaceId" str "SO 工作区 ID"
                "limit" int "最多返回多少条配对(默认 200)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val ws = args.str("workspaceId")
            if (ws.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 workspaceId", "workspaceId", "")
            val limit = args.intValue("limit", 200).coerceIn(1, 2000)
            val engine = EngineProvider.get(ctx.context)

            val raw = engine.rzCommand(ws, "", "izzj", false)
            val parsed = parseJsonOrNull(cmdStdout(raw))
            if (parsed !is JSONArray) {
                return ok(JSONObject()
                    .put("workspaceId", ws)
                    .put("supported", false)
                    .put("pairs", JSONArray())
                    .put("engineMessage", engineMessage(raw))
                    .put("note", "izzj 无 JSON 输出, 无法做字符串级扫描; 可用 taffy_rizin_api action=command 执行 izz 查看原始字符串"))
            }

            val list = ArrayList<JniStr>()
            for (i in 0 until parsed.length()) {
                val o = parsed.optJSONObject(i) ?: continue
                val s = o.optString("string", "")
                if (s.isEmpty()) continue
                list.add(JniStr(o.optLong("vaddr", 0L), s))
            }
            list.sortBy { it.va }

            var hasOnLoad = false
            var hasRegisterNatives = false
            for (s in list) {
                if (s.text == "JNI_OnLoad") hasOnLoad = true
                if (s.text.contains("RegisterNatives")) hasRegisterNatives = true
            }

            val pairs = JSONArray()
            val descriptors = ArrayList<Int>()
            for (i in list.indices) {
                val s = list[i].text
                if (s.length in 3..200 && JNI_DESCRIPTOR.matches(s)) descriptors.add(i)
            }
            for (idx in descriptors) {
                if (pairs.length() >= limit) break
                val sig = list[idx]
                val prev = if (idx - 1 >= 0) list[idx - 1] else null
                val name = prev?.text?.takeIf { JAVA_METHOD_NAME.matches(it) }.orEmpty()
                pairs.put(JSONObject()
                    .put("javaName", name)
                    .put("signature", sig.text)
                    .put("sigAddr", "0x" + java.lang.Long.toHexString(sig.va))
                    .put("nameAddr", if (prev != null) "0x" + java.lang.Long.toHexString(prev.va) else "")
                    .put("confidence", if (name.isNotBlank()) "medium" else "low")
                    .put("via", "string-scan-adjacent"))
            }

            return ok(JSONObject()
                .put("workspaceId", ws)
                .put("supported", true)
                .put("hasJniOnLoad", hasOnLoad)
                .put("hasRegisterNatives", hasRegisterNatives)
                .put("stringCount", list.size)
                .put("descriptorCount", descriptors.size)
                .put("pairCount", pairs.length())
                .put("pairs", pairs)
                .put("confidence", "heuristic")
                .put(
                    "note",
                    "配对依据『描述符前一个字符串即方法名』的结构相邻性; 无 RegisterNatives 时也可能命中普通字符串, " +
                        "请结合 taffy_analyze_functions 校验地址",
                ))
        }
    }

    // ── 5. 批量伪 C ──────────────────────────────────────────────

    val soPseudocBatch: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_so_pseudoc_batch",
            "【批量伪 C 反编译】对多个函数一次性生成伪 C(rizin-ghidra), 用于快速通读一个 SO 的主要逻辑。" +
                "默认取前 5 个函数, 可用 count(上限 30)/startIndex/filter(函数名子串) 控制; 单个函数失败不会中断整体。" +
                "可选 outFile 把结果合并写成一个 .c 文件。",
            "Batch-decompile multiple functions to pseudo C (rizin-ghidra) in one call. Defaults to the first 5 " +
                "functions; use count (max 30) / startIndex / filter. A failing function does not abort the batch. " +
                "Optional outFile merges everything into one .c file.",
            "analyze", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "workspaceId" str "SO 工作区 ID"
                "count" int "生成函数数量(默认 5, 上限 30)"
                "startIndex" int "从第几个函数开始(默认 0)"
                "filter" str "只处理函数名含该子串的函数"
                "outFile" str "可选: 把结果合并写入该文件名(保存到 App 的 pseudoc-out/ 目录)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val ws = args.str("workspaceId")
            if (ws.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 workspaceId", "workspaceId", "")
            val count = args.intValue("count", 5).coerceIn(1, 30)
            val startIndex = args.intValue("startIndex", 0).coerceAtLeast(0)
            val filter = args.str("filter")
            val engine = EngineProvider.get(ctx.context)

            val fns = functionItems(engine, ws, 500)
            val chosen = ArrayList<Pair<String, Long>>()
            for (i in 0 until fns.length()) {
                val f = fns.optJSONObject(i) ?: continue
                val nm = f.optString("name")
                if (nm.isBlank()) continue
                if (filter.isNotBlank() && !nm.contains(filter, true)) continue
                val a = funcAddr(f) ?: continue
                chosen.add(nm to a)
            }
            if (chosen.isEmpty()) {
                return ok(JSONObject()
                    .put("workspaceId", ws)
                    .put("generated", 0)
                    .put("functions", JSONArray())
                    .put("note", "没有可用函数(可能 strip 严重或 filter 过严); 可先用 taffy_rizin_api action=analyze"))
            }

            val slice = chosen.drop(startIndex).take(count)
            val out = JSONArray()
            val merged = StringBuilder()
            var okCount = 0
            var failCount = 0
            for (pair in slice) {
                val nm = pair.first
                val loc = "0x" + java.lang.Long.toHexString(pair.second)
                val r = runCatching { engine.rzDecompile(ws, "", loc, false) }.getOrNull()
                val code = r?.optString("pseudocode", "").orEmpty()
                if (code.isBlank()) {
                    failCount++
                    out.put(JSONObject()
                        .put("function", nm)
                        .put("addr", loc)
                        .put("ok", false)
                        .put("error", if (r != null) engineMessage(r) else "decompile failed"))
                    continue
                }
                okCount++
                out.put(JSONObject()
                    .put("function", nm)
                    .put("addr", loc)
                    .put("ok", true)
                    .put("size", r?.optLong("functionSize", 0L) ?: 0L)
                    .put("pseudocode", code))
                merged.append("// ===== ").append(nm).append(" @ ").append(loc).append(" =====\n")
                merged.append(code).append("\n\n")
            }

            val payload = JSONObject()
                .put("workspaceId", ws)
                .put("requested", slice.size)
                .put("generated", okCount)
                .put("failed", failCount)
                .put("functions", out)
                .put("backend", "rizin-ghidra")

            val outFile = args.str("outFile")
            if (outFile.isNotBlank() && merged.isNotEmpty()) {
                val safe = outFile.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                val dir = File(ctx.context.filesDir, "pseudoc-out")
                dir.mkdirs()
                val f = File(dir, if (safe.endsWith(".c")) safe else "$safe.c")
                val w = runCatching { f.writeText(merged.toString()) }
                if (w.isSuccess) {
                    payload.put("outPath", f.absolutePath).put("outBytes", f.length())
                } else {
                    payload.put("outError", w.exceptionOrNull()?.message ?: "write failed")
                }
            }

            return ok(payload)
        }
    }

    val ALL: List<ToolHandler> = listOf(soVtable, soDemangle, soFuncSig, soJniReg, soPseudocBatch)
}
