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

    /** 串行化 FunctionSignatureAnalyzer 的 setSoPath + analyze（进程级静态状态）。 */
    private val sigLock = Any()

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

            val exbinElf = ExbinElfBridge.from(elf, ws.data)
            val fnName = name.ifBlank { "sub_" + java.lang.Long.toHexString(va) }
            val fn = com.exbin.app.elf.FunctionInfo(fnName, va, size, ".text")
            // setSoPath 是分析器的进程级静态状态：两个工作区并发分析会互相覆盖，
            // 结果按另一个文件算出来却当成正常签名返回。串行化这一小段。
            val res = synchronized(sigLock) {
                com.exbin.app.elf.FunctionSignatureAnalyzer.setSoPath(path)
                com.exbin.app.elf.FunctionSignatureAnalyzer.analyze(fn, elf.machine, null, exbinElf)
            } ?: return@runCatching null

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
                .put(
                    "paramInfos",
                    JSONArray().also { arr ->
                        res.paramInfos?.forEach { pi ->
                            arr.put(
                                JSONObject()
                                    .put("reg", pi.reg)
                                    .put("index", pi.index)
                                    .put("type", pi.type)
                                    .put("detail", pi.detail)
                                    .put("confidence", pi.confidence),
                            )
                        }
                    },
                )
                .put(
                    "locals",
                    JSONArray().also { arr ->
                        res.localVars?.forEach { lv ->
                            arr.put(
                                JSONObject()
                                    .put("spOffset", lv.spOffset)
                                    .put("accessCount", lv.accessCount)
                                    .put("size", lv.size),
                            )
                        }
                    },
                )
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

