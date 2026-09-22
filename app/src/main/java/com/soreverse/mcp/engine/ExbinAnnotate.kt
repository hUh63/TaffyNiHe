package com.soreverse.mcp.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * Exbin [com.exbin.app.elf.DisasmAnnotator] 的接入层。
 *
 * 对 `taffy_read_disasm` 产出的反汇编窗口做 IDA 风格语义注解：
 *  - 全局变量 / 数据对象引用（.symtab/.dynsym 的 OBJECT 符号）
 *  - ADRP+ADD/LDR 融合后的符号名
 *  - 字符串引用、loc_ 标签、栈帧变量名（var_XX）、增强操作数
 *
 * 输入是塔菲已规整的 `0xADDR: insn opnd` 文本行；输出在原行尾追加 `; 注释`，
 * 并把结构化注解放进 `annotations` 字段。
 */
internal object ExbinAnnotate {

    /** 对 disasm 结果做 Exbin 注解（原地补充 annotations / 重写 textWindow.text）。 */
    fun apply(result: JSONObject, elf: ElfFile, bytes: ByteArray): JSONObject {
        val tw = result.optJSONObject("textWindow") ?: return result
        val text = tw.optString("text")
        if (text.isBlank()) return result
        val lines = text.split("\n")
        if (lines.isEmpty()) return result

        val insns = ArrayList<com.exbin.app.elf.DisassembledInstruction>(lines.size)
        val addrToLine = LinkedHashMap<Long, Int>()
        for (idx in lines.indices) {
            val p = parseLine(lines[idx]) ?: continue
            insns.add(com.exbin.app.elf.DisassembledInstruction(p.first, null, p.second, p.third))
            addrToLine[p.first] = idx
        }
        if (insns.isEmpty()) return result

        val exbinElf = ExbinElfBridge.from(elf, bytes)
        try {
            com.exbin.app.elf.DisasmAnnotator().annotate(insns, exbinElf, elf.machine)
        } catch (t: Throwable) {
            return result
        }

        val byAddr = HashMap<Long, com.exbin.app.elf.DisassembledInstruction>(insns.size * 2)
        for (di in insns) byAddr[di.address] = di

        val outLines = lines.toMutableList()
        val annotArr = JSONArray()
        var annotated = 0
        for ((addr, idx) in addrToLine) {
            val di = byAddr[addr] ?: continue
            val ann = di.annotation
            val enh = di.enhancedOpStr
            val sv = di.stackVar
            val hasAnn = !ann.isNullOrBlank()
            val hasSv = !sv.isNullOrBlank()
            val hasEnh = !enh.isNullOrBlank()
            if (!hasAnn && !hasSv && !hasEnh) continue
            val extra = StringBuilder()
            if (hasAnn) extra.append(" ; ").append(ann)
            if (hasSv && (!hasAnn || ann!!.indexOf(sv!!) < 0)) extra.append(" ; ").append(sv)
            outLines[idx] = outLines[idx] + extra
            annotated++
            annotArr.put(
                JSONObject()
                    .put("addr", hexStr(addr))
                    .put("annotation", if (hasAnn) ann else JSONObject.NULL)
                    .put("enhancedOpStr", if (hasEnh) enh else JSONObject.NULL)
                    .put("stackVar", if (hasSv) sv else JSONObject.NULL),
            )
        }

        if (annotated > 0) {
            tw.put("text", outLines.joinToString("\n"))
            result.put("annotations", annotArr)
            result.put("annotatedBy", "exbin-disasm-annotator")
            result.put("annotatedCount", annotated)
        }
        return result
    }

    /** 解析塔菲规整后的反汇编行 `0xADDR: mnemonic operands`。 */
    private fun parseLine(line: String): Triple<Long, String, String>? {
        val s = line.trim()
        if (!s.startsWith("0x", ignoreCase = true)) return null
        val sp = s.indexOfFirst { it == ' ' || it == '\t' }
        if (sp <= 0) return null
        var addrStr = s.substring(0, sp).trim()
        if (addrStr.endsWith(":")) addrStr = addrStr.dropLast(1)
        val addr = addrStr.removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: return null
        val rest = s.substring(sp).trim()
        if (rest.isEmpty()) return null
        val parts = rest.split(Regex("\\s+"), limit = 2)
        val mn = parts.getOrElse(0) { "" }
        if (mn.isEmpty()) return null
        val opstr = parts.getOrElse(1) { "" }
        return Triple(addr, mn, opstr)
    }

    private fun hexStr(v: Long): String = "0x" + java.lang.Long.toHexString(v)
}
