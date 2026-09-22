package com.soreverse.mcp.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * Exbin [com.exbin.app.elf.FunctionSignatureAnalyzer] 的接入层。
 *
 * 与塔菲原有的 rizin `afvj` 启发式互补：FSA 提供
 *  - JNI 标准函数（JNI_OnLoad/JNI_OnUnload）的特判签名（高置信）
 *  - 结构化结果（returnType/paramTypes/paramRegs/minArgs/maxArgs/confidence/notes）
 *  - 局部变量与字符串引用汇总
 * 其 native 还原通道由 [ExbinSignatureBackend] 提供（纯 Java AArch64 反汇编 + read-before-write）。
 */
internal object ExbinSignature {

    /**
     * 还原单个函数签名。
     *
     * @return 结构化签名的 JSON；架构不支持 / 无法还原时返回 null（调用方降级到 afvj）
     */
    fun analyze(engine: EngineRuntime, workspaceId: String, name: String, va: Long, size: Long = 0L): JSONObject? {
        return runCatching {
            val ws = engine.workspace(workspaceId)
            val elf = ws.elf
            if (elf.architecture != "arm64" && elf.architecture != "arm32") return@runCatching null
            val path = ws.source.path
            if (path.isBlank()) return@runCatching null

            ExbinSignatureBackend.ensureRegistered()
            com.exbin.app.elf.FunctionSignatureAnalyzer.setSoPath(path)

            val exbinElf = ExbinElfBridge.from(elf, ws.data)
            val fnName = name.ifBlank { "sub_" + java.lang.Long.toHexString(va) }
            val fn = com.exbin.app.elf.FunctionInfo(fnName, va, size, ".text")
            val res = com.exbin.app.elf.FunctionSignatureAnalyzer
                .analyze(fn, elf.machine, null, exbinElf) ?: return@runCatching null

            JSONObject()
                .put("signature", res.signature ?: JSONObject.NULL)
                .put("returnType", res.returnType ?: JSONObject.NULL)
                .put("paramTypes", JSONArray(res.paramTypes ?: emptyList<String>()))
                .put("paramRegs", JSONArray(res.paramRegs ?: emptyList<String>()))
                .put("argCount", res.paramTypes?.size ?: 0)
                .put("minArgs", res.minArgs)
                .put("maxArgs", res.maxArgs)
                .put("confidence", res.overallConfidence ?: JSONObject.NULL)
                .put("notes", res.notes ?: JSONObject.NULL)
                .put("demangled", res.demangled)
                .put("localVarCount", res.localVars?.size ?: 0)
                .put("stringRefCount", res.stringRefs?.size ?: 0)
                .put("engine", "exbin-fsa")
                .put(
                    "evidence",
                    "Exbin FunctionSignatureAnalyzer（JNI 特判 + 寄存器 read-before-write 反汇编分析）",
                )
        }.getOrNull()
    }
}
