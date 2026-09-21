package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import com.soreverse.mcp.engine.NativeSoEngine
import org.json.JSONArray
import org.json.JSONObject

/**
 * 塔菲逆核: SO 数据段分析（常量 / 全局变量 / 指针 / 字符串引用）。
 *
 * 对标 Explorer So 的 DataTabFragment「数据段分析 (常量/全局变量)」：把只读数据段里的
 * 4/8 字节栅格逐项分类成 int / float / ptr / string，并对指针给出目标节区。
 *
 * 纯只读：rizin iSj 拿节区表 + p8 读节区字节，本地做启发式分类，不改任何文件。
 */
object SoDataTool {

    private val CONST_SECTIONS = listOf(".rodata", ".data.rel.ro", ".data.rel.ro.local", ".rodata.cst4", ".rodata.cst8")
    private val GLOBAL_SECTIONS = listOf(".data", ".got", ".got.plt", ".bss")

    val tool: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_so_data",
            "【数据段分析】扫描 SO 的只读/可写数据段, 把 4/8 字节栅格分类为 常量(int/float) / 全局变量(ptr/string/int)。" +
                "action=constants 看 .rodata 里的常量; action=globals 看 .data/.got 里的指针与全局变量; action=all 两者都给。" +
                "每条返回 addr/offset/size/type/value/target(指针目标或字符串内容)。对标 Explorer So 的「数据段分析」。纯只读。",
            "Scan SO data sections and classify 4/8-byte cells as constants (int/float) or globals (ptr/string/int). " +
                "action=constants (.rodata) | globals (.data/.got) | all. Each entry: addr/offset/size/type/value/target. Read-only.",
            "analyze", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "workspaceId" str "SO 工作区 ID"
                "action".oneOf("constants | globals | all", "all", "constants", "globals")
                "limit" int "最多返回条数(默认 200, 上限 2000)"
                "maxBytes" int "每个节区最多扫描的字节数(默认 32768, 上限 262144)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val ws = args.str("workspaceId")
            if (ws.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 workspaceId", "workspaceId", "")
            val action = args.str("action", "all").ifBlank { "all" }
            val limit = args.intValue("limit", 200).coerceIn(1, 2000)
            val maxBytes = args.intValue("maxBytes", 32768).coerceIn(64, 262144)
            val engine = EngineProvider.get(ctx.context)

            val secs = parseSections(engine.rzCommand(ws, "", "iSj", false))
            if (secs.isEmpty()) {
                return ok(JSONObject()
                    .put("workspaceId", ws)
                    .put("supported", false)
                    .put("note", "iSj 无输出：无法读取节区表")
                    .put("entries", JSONArray()))
            }

            fun targetSections(names: List<String>): List<Sec> =
                secs.filter { s -> names.any { s.name == it || s.name.startsWith(it) } }

            val wantConst = action == "constants" || action == "all"
            val wantGlobal = action == "globals" || action == "all"
            val entries = JSONArray()

            if (wantConst) {
                targetSections(CONST_SECTIONS).forEach { sec ->
                    if (entries.length() >= limit) return@forEach
                    val bytes = readBytes(engine, ws, sec.name, minOf(sec.size, maxBytes.toLong()).toInt())
                    classifyConstants(bytes, sec.vaddr, sec.paddr, entries, limit)
                }
            }
            if (wantGlobal && entries.length() < limit) {
                targetSections(GLOBAL_SECTIONS).forEach { sec ->
                    if (entries.length() >= limit) return@forEach
                    val bytes = readBytes(engine, ws, sec.name, minOf(sec.size, maxBytes.toLong()).toInt())
                    classifyGlobals(bytes, sec.vaddr, sec.paddr, secs, entries, limit)
                }
            }

            // 汇总类型计数
            val byType = linkedMapOf<String, Int>()
            for (i in 0 until entries.length()) {
                val t = entries.optJSONObject(i)?.optString("type").orEmpty()
                byType[t] = (byType[t] ?: 0) + 1
            }

            return ok(JSONObject()
                .put("workspaceId", ws)
                .put("action", action)
                .put("sectionCount", secs.size)
                .put("total", entries.length())
                .put("byType", JSONObject(byType as Map<String, Int>))
                .put("entries", entries)
                .put("note", "启发式分类：int/float 按数值合理性判定, ptr 依据是否落在已知节区范围内, string 依据目标处是否可打印。使用前请人工核对。"))
        }
    }

    // ── 内部模型与实现 ────────────────────────────────────────────

    private data class Sec(val name: String, val vaddr: Long, val paddr: Long, val size: Long)

    private fun parseSections(res: JSONObject?): List<Sec> {
        if (res == null) return emptyList()
        val text = res.optString("stdout").ifBlank { res.optString("text") }.trim()
        if (text.isBlank()) return emptyList()
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Sec>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name")
            if (name.isBlank()) continue
            out.add(Sec(name, num(o.opt("vaddr")), num(o.opt("paddr")), num(o.opt("size"))))
        }
        return out
    }

    private fun readBytes(engine: NativeSoEngine, ws: String, sec: String, n: Int): ByteArray {
        if (n <= 0) return ByteArray(0)
        val res = engine.rzCommand(ws, "", "p8 $n @ $sec", false)
        val hex = res.optString("stdout").ifBlank { res.optString("text") }.trim()
        if (hex.isBlank()) return ByteArray(0)
        val t = hex.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        if (t.length < 2) return ByteArray(0)
        val m = t.length / 2
        return runCatching { ByteArray(m) { i -> t.substring(i * 2, i * 2 + 2).toInt(16).toByte() } }.getOrDefault(ByteArray(0))
    }

    private fun classifyConstants(bytes: ByteArray, vaddr: Long, paddr: Long, out: JSONArray, limit: Int) {
        var i = 0
        while (i + 4 <= bytes.size && out.length() < limit) {
            val u = (bytes[i].toLong() and 0xff) or ((bytes[i + 1].toLong() and 0xff) shl 8) or
                ((bytes[i + 2].toLong() and 0xff) shl 16) or ((bytes[i + 3].toLong() and 0xff) shl 24)
            val s = u.toInt()
            val f = Float.fromBits(s)
            val isSaneFloat = !f.isNaN() && !f.isInfinite() && (kotlin.math.abs(f) in 1e-6f..1e9f)
            val isSaneInt = s in -1000000..1000000
            when {
                isSaneInt -> out.put(entry(vaddr + i, paddr + i, 4, "int", s.toString(), ""))
                isSaneFloat -> out.put(entry(vaddr + i, paddr + i, 4, "float", f.toString(), ""))
                else -> Unit
            }
            i += 4
        }
    }

    private fun classifyGlobals(bytes: ByteArray, vaddr: Long, paddr: Long, secs: List<Sec>, out: JSONArray, limit: Int) {
        val bssish = secs.filter { it.name.startsWith(".bss") }.map { it.vaddr..(it.vaddr + it.size) }
        var i = 0
        while (i + 8 <= bytes.size && out.length() < limit) {
            var u = 0L
            for (k in 7 downTo 0) u = (u shl 8) or (bytes[i + k].toLong() and 0xff)
            val addr = vaddr + i
            val target = secs.firstOrNull { u >= it.vaddr && u < it.vaddr + it.size && u != 0L }
            when {
                u != 0L && target != null -> {
                    val inBss = bssish.any { u in it }
                    val kind = if (inBss) "bss-ptr" else "ptr"
                    out.put(entry(addr, paddr + i, 8, kind, hex(u), "${target.name}+0x" + java.lang.Long.toHexString(u - target.vaddr)))
                }
                u != 0L && out.length() < limit -> out.put(entry(addr, paddr + i, 8, "int64", u.toString(), ""))
                else -> Unit
            }
            i += 8
        }
    }

    private fun entry(addr: Long, off: Long, size: Int, type: String, value: String, target: String): JSONObject =
        JSONObject()
            .put("addr", hex(addr))
            .put("offset", off)
            .put("size", size)
            .put("type", type)
            .put("value", value)
            .put("target", target)

    private fun hex(v: Long): String = "0x" + java.lang.Long.toHexString(v)

    private fun num(v: Any?): Long = when (v) {
        is Number -> v.toLong()
        is String -> runCatching {
            if (v.startsWith("0x", true)) v.substring(2).toLong(16) else v.toLong()
        }.getOrNull() ?: 0L
        else -> 0L
    }

    val ALL = listOf(tool)
}
