package com.soreverse.mcp.engine

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.engine.standalone.Arm64Disasm
import org.json.JSONArray
import org.json.JSONObject

/**
 * 纯 Java 反编译兜底（方案 B）。
 *
 * 当 Rizin/Ghidra 原生后端为当前 ABI 不可用时，用自带的
 * [Arm64Disasm]（纯 Java AArch64 反汇编器）对目标函数区间做反汇编，
 * 并整理为结构化输出，让 MCP/UI 在最坏情况下仍能给出可读的指令级
 * 结果，而不是直接报 RIZIN_UNAVAILABLE。
 *
 * 注意：这是反汇编兜底，不是真正的 Ghidra SLEIGH 反编译——它不恢复
 * 变量/控制流伪代码，只把机器码还原成汇编并标注边界。能力弱于
 * rizin-ghidra 的 pdg，但当原生引擎不可用时是唯一可用的降级路径。
 *
 * 仅支持 arm64（Arm64Disasm 只解码 AArch64）。
 */
internal fun EngineRuntime.standaloneJavaPseudoDecompile(
    bytes: ByteArray,
    elf: ElfFile,
    va: Long,
    locator: String = "",
): JSONObject {
    val arch = elf.architecture
    if (arch != "arm64") {
        return err(
            "DECOMPILER_UNAVAILABLE",
            "Pure-Java fallback only supports arm64 (got '$arch'). Rizin/Ghidra native backend not loaded for this ABI.",
            "backend", "standalone-java",
            "architecture" to arch,
        )
    }

    val fileOffset = vaToOffset(elf, va)
    if (fileOffset == null || fileOffset < 0 || fileOffset >= bytes.size) {
        return err(
            "INVALID_ARGUMENT",
            "VA 无法映射到 ELF 文件偏移（可能不在可执行节/段内）",
            "locator", locator,
            "addr" to hex(va),
        )
    }

    // 计算函数边界：优先符号表里同名/同 VA 的 FUNC 符号大小，
    // 否则取下一个函数边界。最后收紧到可执行节边界与硬上限。
    val symbol = (elf.symbols + elf.dynSymbols)
        .firstOrNull { it.name == locator || (it.value and -2L) == va }
    var endVa: Long? = null
    val declaredSize = symbol?.size?.takeIf { it > 0 }
    if (declaredSize != null) {
        endVa = va + declaredSize
    } else {
        endVa = (elf.symbols + elf.dynSymbols)
            .asSequence()
            .filter { it.type == "FUNC" && !it.imported && (it.value and -2L) > va }
            .map { it.value and -2L }
            .minOrNull()
    }

    // 起点文件偏移
    val startOffset = fileOffset
    var endOffset = bytes.size.toLong()

    // 优先用解析出的 endVa 对应的文件偏移
    endVa?.let { end ->
        vaToOffset(elf, end)?.let { endOffset = it }
    }
    // 收紧到可执行节边界
    sectionFor(elf, va)?.let { sec -> endOffset = endOffset.coerceAtMost(sec.offset + sec.size) }
    // 硬上限：最多反汇编 4096 字节，避免 .text 过长导致内存/时延问题
    val MAX_BYTES = 4096L
    val boundedEnd = (startOffset + MAX_BYTES).coerceAtMost(endOffset)

    val sliceLength = (boundedEnd - startOffset).toInt()
    if (sliceLength < 4) {
        return err(
            "INVALID_ARGUMENT",
            "函数区间过短（$sliceLength 字节），无法反编译/反汇编",
            "addr", hex(va),
            "fileOffset" to hex(fileOffset),
        )
    }
    val slice = bytes.copyOfRange(startOffset.toInt(), (startOffset + sliceLength).toInt())

    // 用偏置数组从 pos=0 开始，baseAddr 指到目标 VA
    val disasm = Arm64Disasm(slice, va)
    val insns = disasm.disassemble(sliceLength / 4)
    if (insns.isEmpty()) {
        return err(
            "DECOMPILER_FAILED",
            "Arm64Disasm 未能解码目标地址处的任何指令",
            "backend", "standalone-java",
            "addr" to hex(va),
        )
    }

    // 组装伪代码风格文本
    val sb = StringBuilder()
    sb.append("// === Pure-Java fallback disassembly (Arm64Disasm, no Ghidra) ===\n")
    sb.append("// ${sectionFor(elf, va)?.name ?: "unknown"} @ ${hex(va)}\n")
    for (insn in insns) {
        sb.append("    ").append("0x").append(insn.address.toString(16)).append(": ")
            .append(insn.mnemonic)
            .append(if (insn.operands.isBlank()) "" else " ${insn.operands}")
            .append("\n")
    }

    // 汇编指令数组（结构化，供 UI/MCP 复用）
    val insnArray = JSONArray()
    for (insn in insns) {
        insnArray.put(JSONObject()
            .put("addr", hex(insn.address))
            .put("mnemonic", insn.mnemonic)
            .put("operands", insn.operands)
            .put("text", "${insn.mnemonic}${if (insn.operands.isBlank()) "" else " ${insn.operands}"}"))
    }

    return ok(JSONObject()
        .put("decompile", "standalone-java")
        .put("backend", "standalone-java")
        .put("usesBuiltinPseudo", true)
        .put("requestedBackend", "rizin-ghidra")
        .put("addr", hex(va))
        .put("locator", locator)
        .put("requestedFunction", locator)
        .put("pseudocode", sb.toString())
        .put("instructions", insnArray)
        .put("functionBounds", JSONObject()
            .put("startAddr", hex(va))
            .put("endAddr", if (endVa != null) hex(endVa) else JSONObject.NULL)
            .put("size", sliceLength)
            .put("source", "standalone-arm64disasm"))
        .put("typeInference", JSONObject.NULL)
        .put("typeConfidenceNote", "Pure-Java fallback performs disassembly only; type inference is not available without Ghidra SLEIGH.")
        .put("pseudocodePolicy", "standalone-java fallback: AArch64 disassembly (instruction-level), not decompiled pseudo-C."))
}
