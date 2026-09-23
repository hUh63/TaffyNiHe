package com.soreverse.mcp.engine

import org.json.JSONObject

/**
 * Exbin 自研 microcode + SSA 反编译器（`libexbin_decomp.so`）的接入层。
 *
 * 独立于 rizin-ghidra 的第六条伪 C 路径，流水线：
 *   AsmInsn → MicrocodeEmitter → SSA → Optimize → CFGStructure → CTree → Beautify → CPrinter
 *
 * 输入契约（与 Exbin `NativeBridge.nativeDecompileFunction` 一致）：
 * 函数元信息 + 反汇编指令流 + ELF 符号/导入/标签，由塔菲从 rizin(`pdfj`) 与 ELF 模型组装。
 */
internal object ExbinDecompiler {

    /** libexbin_decomp.so 是否随当前 ABI 打包。 */
    fun available(): Boolean = runCatching {
        com.exbin.app.nativebridge.NativeBridge.isExbinDecompilerAvailable()
    }.getOrDefault(false)

    private fun machineOf(arch: String): Int = when (arch) {
        "arm32" -> 40
        "arm64" -> 183
        "x86" -> 3
        "x86_64" -> 62
        else -> 183
    }

    /**
     * 反编译单个函数。
     *
     * @return 伪 C 文本；so 未打包 / 指令为空 / native 失败时返回 null（调用方降级）
     */
    fun decompile(
        engine: EngineRuntime,
        workspaceId: String,
        editSessionId: String,
        locator: String,
    ): String? {
        if (!available()) return null
        return runCatching {
            val ws = engine.workspace(workspaceId)
            val elf = ws.elf
            val soPath = ws.source.path

            // 1) 结构化指令流（rizin pdfj：offset / bytes / disasm）
            val rj = engine.rzCommand(workspaceId, editSessionId, "s $locator; af; pdfj")
            val txt = rj.optString("stdout").ifBlank { rj.optString("text") }.trim()
            if (txt.isEmpty()) return@runCatching null
            val obj = runCatching { JSONObject(txt) }.getOrNull() ?: return@runCatching null
            val ops = obj.optJSONArray("ops") ?: return@runCatching null
            val n = ops.length()
            if (n == 0) return@runCatching null

            val mnemonics = Array(n) { "" }
            val opStrs = Array(n) { "" }
            val addresses = LongArray(n)
            val sizes = IntArray(n)
            var funcAddr = 0L
            var thumb = false
            for (i in 0 until n) {
                val o = ops.optJSONObject(i) ?: continue
                val off = o.optLong("offset", 0L)
                addresses[i] = off
                if (i == 0) funcAddr = off
                val disasm = o.optString("disasm").trim()
                val sp = disasm.indexOf(' ')
                if (sp > 0) {
                    mnemonics[i] = disasm.substring(0, sp)
                    opStrs[i] = disasm.substring(sp + 1).trim()
                } else {
                    mnemonics[i] = disasm
                }
                val bytes = o.optString("bytes").replace(" ", "")
                sizes[i] = if (bytes.isEmpty()) 4 else (bytes.length / 2)
                if (o.optBoolean("thumb", false)) thumb = true
            }
            if (funcAddr == 0L) return@runCatching null

            // 2) 分支标签（jump / ptr 目标）
            val labelA = ArrayList<Long>()
            val labelN = ArrayList<String>()
            val seenLabel = HashSet<Long>()
            for (i in 0 until n) {
                val o = ops.optJSONObject(i) ?: continue
                for (key in arrayOf("jump", "ptr")) {
                    if (o.isNull(key)) continue
                    val t = o.optLong(key, 0L)
                    if (t != 0L && seenLabel.add(t)) {
                        labelA.add(t)
                        labelN.add("loc_" + java.lang.Long.toHexString(t))
                    }
                }
            }

            // 3) ELF 符号 / 导入
            val symA = ArrayList<Long>()
            val symN = ArrayList<String>()
            for (s in elf.symbols + elf.dynSymbols) {
                if (s.value != 0L && s.name.isNotBlank()) {
                    symA.add(s.value)
                    symN.add(s.name)
                }
            }
            val impA = ArrayList<Long>()
            val impN = ArrayList<String>()
            for (s in elf.dynSymbols) {
                if (s.imported && s.name.isNotBlank()) {
                    impA.add(s.value)
                    impN.add(s.name)
                }
            }

            val fnName = obj.optString("name").ifBlank { locator }
            val machine = machineOf(elf.architecture)

            com.exbin.app.nativebridge.NativeBridge.decompileFunction(
                fnName, funcAddr, machine, thumb, soPath,
                mnemonics, opStrs, addresses, sizes,
                labelA.toLongArray(), labelN.toTypedArray(),
                impA.toLongArray(), impN.toTypedArray(),
                emptyArray<String>(), emptyArray<String>(),
                symA.toLongArray(), symN.toTypedArray(),
            )?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
