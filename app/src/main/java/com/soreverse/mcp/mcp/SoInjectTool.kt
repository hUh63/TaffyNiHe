package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import com.soreverse.mcp.engine.ElfInjector
import org.json.JSONArray
import org.json.JSONObject

/**
 * 塔菲逆核: SO 代码注入（变长）。
 *
 * 把机器码注入到 SO 并使其**被加载器映射且可执行**：追加一个新 PT_LOAD(R+X) 与新
 * SHT_PROGBITS 节区（phdr/shdr/shstrtab 表搬到文件末尾，已有字节一个都不动），
 * 可选把目标函数入口改写为跳转到新代码（trampoline，按 ABI 编码、等长覆盖）。
 *
 * 全部经编辑内核写入（editHexAt 等长头部改写 + editAppend 尾部追加），因此具备
 * 快照 / undo / redo / check / build 全套能力。内核实现见 engine/ElfInjector.kt。
 */
object SoInjectTool {

    private val tool: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_so_inject_code",
            "【SO 代码注入·变长】把机器码注入 SO 并让其可被加载执行：新增 PT_LOAD(R+X) 与 .taffy.inject 节区（搬 phdr/shdr 表到文件末尾，不改动任何已有字节），可选把目标函数入口改写为跳转(trampoline)。写入走编辑内核，支持快照/undo/redo/build。产物需回填 APK 并重签名后才可安装。",
            "Inject machine code into an SO so it is mapped & executable: append a new PT_LOAD(R+X) + .taffy.inject section (phdr/shdr tables moved to EOF, no existing bytes moved), optionally rewrite a function entry to jump into it (trampoline). Written through the edit kernel (snapshot/undo/redo/build).",
            "edit", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "workspaceId" str "工作区 id（必填）"
                "editSessionId" str "编辑会话 id（留空自动新建）"
                "codeHex" str "要注入的机器码（十六进制字节，如 b82a000000c3 = mov eax,42; ret）"
                "trampoline" str "把该函数入口改为跳到新代码：符号名或 0xVA（留空=只挂载不跳转）"
                "arch" str "架构 x86_64/x86/arm64/arm32/thumb（留空按 ELF e_machine 推断）"
                "dryRun" str "true=只返回注入计划不写入"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val ws = args.str("workspaceId")
            if (ws.isBlank()) return err("INVALID_ARGUMENT", "workspaceId 必填")
            val codeHex = args.str("codeHex").replace(Regex("[\\s,]"), "")
            if (codeHex.isEmpty() || codeHex.length % 2 != 0 ||
                !codeHex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
            ) {
                return err("INVALID_HEX", "codeHex 必须是偶数长度十六进制", "codeHex", args.str("codeHex"))
            }
            val code = codeHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

            val eng = EngineProvider.get(ctx.context)
            var sid = args.str("editSessionId")
            if (sid.isBlank()) {
                val eo = eng.editOpen(ws)
                sid = eo.optString("editSessionId")
                    .ifBlank { eo.optJSONObject("data")?.optString("editSessionId").orEmpty() }
            }
            if (sid.isBlank()) return err("EDIT_SESSION_OPEN_FAILED", "无法创建编辑会话")

            val src = eng.editSessionBytes(ws, sid)
                ?: return err("EDIT_SESSION_NOT_FOUND", "读取编辑会话字节失败（会话不存在？）")
            if (!ElfInjector.isElf(src)) return err("NOT_ELF", "当前编辑目标不是 ELF 文件")

            val arch = args.str("arch").ifBlank { inferArch(src) }
            val trampSpec = args.str("trampoline").trim()
            val trampVa: Long = when {
                trampSpec.isBlank() -> -1L
                trampSpec.startsWith("0x", true) -> trampSpec.substring(2).toLongOrNull(16)
                    ?: return err("INVALID_ARGUMENT", "trampoline VA 无效", "trampoline", trampSpec)
                else -> ElfInjector.findSymbolVa(src, trampSpec)
                    ?: return err("SYMBOL_NOT_FOUND", "在 .symtab 里找不到符号 $trampSpec")
            }

            val res = runCatching { ElfInjector.inject(src, code, trampVa, arch) }
                .getOrElse { return err("INJECT_FAILED", it.message ?: "注入失败") }

            // 差异 = 若干「等长原位改写」（ELF 头 / 入口 trampoline）+ 一段「尾部追加」
            val n = src.size
            val out = res.elf
            val patches = JSONArray()
            var i = 0
            while (i < n) {
                if (src[i] == out[i]) { i++; continue }
                val start = i
                while (i < n && src[i] != out[i]) i++
                patches.put(JSONObject().put("fileOffset", start).put("newHex", hexOf(out, start, i - start)))
            }
            val appendLen = out.size - n
            val appendHex = if (appendLen > 0) hexOf(out, n, appendLen) else ""

            val plan = JSONObject()
                .put("editSessionId", sid)
                .put("arch", arch)
                .put("newVa", "0x" + java.lang.Long.toHexString(res.codeVa))
                .put("codeOffset", res.codeOffset)
                .put("pageAlign", res.pageAlign)
                .put("sizeBefore", n)
                .put("sizeAfter", out.size)
                .put("inPlacePatchCount", patches.length())
                .put("appendLength", appendLen)
                .put("trampolineAt", if (trampVa >= 0L) "0x" + java.lang.Long.toHexString(trampVa) else "")
                .put("variableLength", true)
                .put("notes", JSONArray(res.notes))

            if (args.str("dryRun", "false").toBoolean()) return ok(plan.put("dryRun", true))

            if (patches.length() > 0) {
                val r1 = eng.editHexAt(ws, sid, patches)
                r1.optJSONObject("error")?.let { return r1 }
            }
            if (appendHex.isNotBlank()) {
                val r2 = eng.editAppend(ws, sid, appendHex)
                r2.optJSONObject("error")?.let { return r2 }
            }
            val after = eng.editSessionBytes(ws, sid)
            val verified = after != null && after.size == out.size && after.contentEquals(out)
            plan.put("verified", verified)
            plan.put("newTargetVersion", sha256Of(after ?: ByteArray(0)))
            return ok(plan)
        }
    }

    /** 按 ELF e_machine(@0x12, 小端) 推断架构。 */
    private fun inferArch(elf: ByteArray): String {
        if (elf.size < 0x14) return "arm64"
        val m = (elf[0x12].toInt() and 0xff) or ((elf[0x13].toInt() and 0xff) shl 8)
        return when (m) {
            0x3E -> "x86_64"
            0x03 -> "x86"
            0xB7 -> "arm64"
            0x28 -> "arm32"
            else -> "arm64"
        }
    }

    private fun hexOf(b: ByteArray, off: Int, len: Int): String {
        val sb = StringBuilder(len * 2)
        for (i in off until off + len) sb.append("%02x".format(b[i].toInt() and 0xff))
        return sb.toString()
    }

    private fun sha256Of(b: ByteArray): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(b)
        val sb = StringBuilder(d.size * 2)
        for (x in d) sb.append("%02x".format(x.toInt() and 0xff))
        return sb.toString()
    }

    val ALL = listOf(tool)
}
