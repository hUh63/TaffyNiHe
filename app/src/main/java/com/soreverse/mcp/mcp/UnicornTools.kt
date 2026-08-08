package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import com.soreverse.mcp.nativecore.NativeEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 塔菲逆核: Unicorn 直接 CPU 模拟工具(参考 Flutter解析工具的 libunicorn_java.so 独立模拟能力)。
 *
 * 塔菲逆核已有 Unicorn2(通过 unidbg 间接使用), 但 unidbg 会强制创建 Android 环境(DalvikVM/JNI)，
 * 无法做"裸 CPU 模拟"。本工具绕过 unidbg 的 Android 框架, 直接用 rizin 汇编 + 反射调用
 * unidbg Backend 做:
 *  - 汇编 ARM/ARM64 指令(用 rizin rasm2)
 *  - 映射内存 + 写入代码
 *  - 执行任意地址
 *  - 读取寄存器/内存
 *
 * 工作流: uc_emulate(asm="mov x0, #42; ret") → 直接返回 x0=42
 *
 * 适用场景:
 *  - 验证一段 shellcode 的行为
 *  - 模拟单个函数(不依赖 Android 框架)
 *  - 调试 ARM/ARM64 指令逻辑
 *  - 加密算法片段复现(如一段 AES/TEA 实现)
 */
object UnicornTools {

    /** 直接 CPU 模拟: 汇编→映射→执行→读寄存器 */
    val ucEmulate: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "uc_emulate",
            "【Unicorn 直接模拟】绕过 Android 框架, 直接用 Unicorn2 做 CPU 级模拟。输入 ARM/ARM64 汇编代码(或十六进制字节), 自动汇编→映射内存→执行→返回寄存器+内存状态。action=run 执行代码; action=asm_only 只汇编不执行; action=disasm 反汇编。适合验证 shellcode、模拟单个函数、调试指令逻辑、复现加密算法片段。",
            "Direct Unicorn2 CPU emulation without Android framework. Input ARM/ARM64 assembly or hex bytes, auto-assemble→map memory→execute→return registers+memory. action=run executes; asm_only assembles; disasm disassembles. For shellcode verification, single-function emulation, instruction debugging, crypto algorithm reproduction.",
            "emulate", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("run(执行) | asm_only(仅汇编) | disasm(反汇编)", "run", "asm_only", "disasm")
                "arch".oneOf("CPU 架构", "arm64", "arm")
                "code" str "汇编代码(如 'mov x0, #42; ret') 或十六进制字节(如 'D2800540 C0035FD6', action=disasm 时)"
                "hex" str "直接传十六进制字节跳过汇编(可选, 如 'D2800540 C0035FD6')"
                "entryAddr" int "代码加载地址(默认 0x10000)"
                "stackAddr" int "栈地址(默认 0x80000)"
                "stackSize" int "栈大小(默认 0x10000=64KB)"
                "memReads" arr "执行后要读取的内存地址 [{addr: '0x10000', size: 64}]"
                "maxInstructions" int "最大执行指令数(默认 10000, 防止死循环)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "run")
            val arch = args.str("arch", "arm64")
            val code = args.str("code")
            val hexInput = args.str("hex")
            val entryAddr = args.intValue("entryAddr", 0x10000).toLong()
            val stackAddr = args.intValue("stackAddr", 0x80000).toLong()
            val stackSize = args.intValue("stackSize", 0x10000)
            val maxInsns = args.intValue("maxInstructions", 10000)

            return runCatching {
                when (action) {
                    "asm_only" -> {
                        // 仅汇编, 返回十六进制字节
                        val bytes = assembleCode(code, arch, entryAddr)
                        if (bytes.isEmpty()) return err("ASM_FAILED", "汇编失败, 检查指令语法", "code", code)
                        ok(JSONObject()
                            .put("action", "asm_only")
                            .put("arch", arch)
                            .put("assembly", code)
                            .put("hex", bytesToHex(bytes))
                            .put("size", bytes.size))
                    }

                    "disasm" -> {
                        // 反汇编
                        val input = if (hexInput.isNotBlank()) hexInput else code
                        val bytes = parseHex(input)
                        if (bytes.isEmpty()) return err("INVALID_INPUT", "需要 hex 或 code 参数(十六进制字节)", "code", code)
                        val disasmText = disassembleCode(bytes, arch, entryAddr)
                        ok(JSONObject()
                            .put("action", "disasm")
                            .put("arch", arch)
                            .put("hex", bytesToHex(bytes))
                            .put("size", bytes.size)
                            .put("disassembly", disasmText))
                    }

                    "run" -> {
                        // 汇编(或用hex) → 映射内存 → 执行 → 读寄存器
                        val codeBytes = if (hexInput.isNotBlank()) {
                            parseHex(hexInput)
                        } else {
                            if (code.isBlank()) return err("INVALID_ARGUMENT", "需要 code(汇编) 或 hex(十六进制) 参数", "code", code)
                            assembleCode(code, arch, entryAddr)
                        }
                        if (codeBytes.isEmpty()) return err("ASM_FAILED", "汇编失败或字节为空", "code", code)

                        // 通过反射创建 unidbg backend 做裸模拟
                        val result = emulateRaw(ctx, codeBytes, arch, entryAddr, stackAddr, stackSize, maxInsns, args)

                        // 读取请求的内存
                        val memReads = args.optJSONArray("memReads")
                        if (memReads != null && result.optBoolean("ok", false)) {
                            val memResults = JSONArray()
                            // 内存读取在 emulateRaw 中已处理
                            result.optJSONArray("memReads")?.let { /* pass through */ }
                        }

                        result
                    }

                    else -> err("UNKNOWN_ACTION", "未知 action: $action", "action", action)
                }
            }.getOrElse { e ->
                err("UC_EMULATE_FAILED", "模拟失败: ${e.message ?: e.javaClass.simpleName}", "action", action)
            }
        }

        /** 用 rizin 汇编 */
        private fun assembleCode(asm: String, arch: String, addr: Long): ByteArray {
            val engine = NativeEngine.active()
            if (!engine.available()) return ByteArray(0)
            val thumb = false
            return engine.assemble(asm, arch, addr, thumb)
        }

        /** 用 rizin 反汇编 */
        private fun disassembleCode(bytes: ByteArray, arch: String, addr: Long): String {
            val engine = NativeEngine.active()
            if (!engine.available()) return "(rizin 不可用)"
            return engine.disassemble(bytes, arch, addr, false, 500)
        }

        /** 通过反射创建 unidbg AndroidEmulator 但跳过 DalvikVM/JNI, 做裸模拟 */
        private fun emulateRaw(
            ctx: ToolContext,
            codeBytes: ByteArray,
            arch: String,
            entryAddr: Long,
            stackAddr: Long,
            stackSize: Int,
            maxInsns: Int,
            args: JSONObject,
        ): JSONObject {
            val for64Bit = arch == "arm64"
            val out = JSONObject()

            // 创建 emulator builder
            val builderCls = Class.forName("com.github.unidbg.linux.android.AndroidEmulatorBuilder")
            val builder = builderCls.getDeclaredMethod(if (for64Bit) "for64Bit" else "for32Bit").invoke(null)!!

            // 添加 Unicorn2 backend
            val factoryCls = Class.forName("com.github.unidbg.arm.backend.Unicorn2Factory")
            val factory = factoryCls.getConstructor(Boolean::class.javaPrimitiveType).newInstance(true)
            val backendFactoryCls = Class.forName("com.github.unidbg.arm.backend.BackendFactory")
            builderCls.getMethod("addBackendFactory", backendFactoryCls).invoke(builder, factory)

            val emulator = builderCls.getMethod("build").invoke(builder)!!
            out.put("stage", "emulator_created")

            try {
                val backend = emulator.javaClass.getMethod("getBackend").invoke(emulator)
                    ?: return err("BACKEND_ERROR", "Unicorn backend 创建失败", "arch", arch)

                // 映射代码段 (entryAddr, 64KB, RWX)
                val memMap = backend.javaClass.methods.first { it.name == "mem_map" && it.parameterCount == 3 }
                memMap.invoke(backend, entryAddr, 0x10000L, 7) // rwx
                out.put("stage", "code_mapped")

                // 映射栈段
                memMap.invoke(backend, stackAddr, stackSize.toLong(), 7) // rwx
                out.put("stage", "stack_mapped")

                // 写入代码
                val memWrite = backend.javaClass.methods.first { it.name == "mem_write" && it.parameterCount == 2 }
                memWrite.invoke(backend, entryAddr, codeBytes)
                out.put("stage", "code_written")

                // 设置 SP 寄存器
                if (for64Bit) {
                    // ARM64: UC_ARM64_REG_SP = 4
                    val regWrite = backend.javaClass.methods.first { it.name == "reg_write" && it.parameterCount == 2 }
                    regWrite.invoke(backend, 4, stackAddr + stackSize / 2) // SP
                    // LR = 0 (ret 时跳到 0, 然后我们设 until=0 让模拟停止)
                    regWrite.invoke(backend, 30, 0L) // LR
                } else {
                    // ARM32: UC_ARM_REG_SP = 13, UC_ARM_REG_LR = 14
                    val regWrite = backend.javaClass.methods.first { it.name == "reg_write" && it.parameterCount == 2 }
                    regWrite.invoke(backend, 13, stackAddr + stackSize / 2) // SP
                    regWrite.invoke(backend, 14, 0L) // LR
                }
                out.put("stage", "registers_set")

                // 执行: emu_start(begin=entryAddr, until=0, timeout=0, count=maxInsns)
                val emuStart = backend.javaClass.methods.first { it.name == "emu_start" && it.parameterCount == 4 }
                emuStart.invoke(backend, entryAddr, 0L, 0L, maxInsns.toLong())
                out.put("stage", "executed")

                // 读取寄存器
                val regs = JSONObject()
                val regRead = backend.javaClass.methods.first { it.name == "reg_read" && it.parameterCount == 1 }
                if (for64Bit) {
                    // ARM64 寄存器 ID: X0=0..X28=28, FP=29, LR=30, SP=31, PC=32
                    for (i in 0..28) {
                        runCatching { regRead.invoke(backend, i) }
                            .onSuccess { regs.put("x$i", it?.toString() ?: "0") }
                    }
                    runCatching { regRead.invoke(backend, 29) }.onSuccess { regs.put("fp", it?.toString() ?: "0") }
                    runCatching { regRead.invoke(backend, 30) }.onSuccess { regs.put("lr", it?.toString() ?: "0") }
                    runCatching { regRead.invoke(backend, 31) }.onSuccess { regs.put("sp", it?.toString() ?: "0") }
                    runCatching { regRead.invoke(backend, 32) }.onSuccess { regs.put("pc", it?.toString() ?: "0") }
                    // NZCV (PSTATE)
                    runCatching { regRead.invoke(backend, 104) }.onSuccess { regs.put("nzcv", it?.toString() ?: "0") }
                } else {
                    // ARM32 寄存器 ID: R0=0..R12=12, SP=13, LR=14, PC=15
                    for (i in 0..12) {
                        runCatching { regRead.invoke(backend, i) }
                            .onSuccess { regs.put("r$i", it?.toString() ?: "0") }
                    }
                    runCatching { regRead.invoke(backend, 13) }.onSuccess { regs.put("sp", it?.toString() ?: "0") }
                    runCatching { regRead.invoke(backend, 14) }.onSuccess { regs.put("lr", it?.toString() ?: "0") }
                    runCatching { regRead.invoke(backend, 15) }.onSuccess { regs.put("pc", it?.toString() ?: "0") }
                    runCatching { regRead.invoke(backend, 25) }.onSuccess { regs.put("cpsr", it?.toString() ?: "0") }
                }

                // 读取请求的内存
                val memResults = JSONArray()
                val memReads = args.optJSONArray("memReads")
                if (memReads != null) {
                    val memRead = backend.javaClass.methods.first { it.name == "mem_read" && it.parameterCount == 2 }
                    for (i in 0 until memReads.length()) {
                        val req = memReads.optJSONObject(i) ?: continue
                        val rAddr = parseAddr(req.str("addr"))
                        val rSize = req.intValue("size", 64).coerceAtMost(4096)
                        runCatching {
                            val data = memRead.invoke(backend, rAddr, rSize) as ByteArray
                            memResults.put(JSONObject()
                                .put("addr", "0x${rAddr.toString(16)}")
                                .put("size", data.size)
                                .put("hex", bytesToHex(data))
                                .put("ascii", data.joinToString("") { if (it in 32..126) it.toInt().toChar().toString() else "." }))
                        }
                    }
                }

                // 读取代码段(返回执行后的代码, 方便确认 patch 效果)
                val codeAfter = runCatching {
                    val memRead = backend.javaClass.methods.first { it.name == "mem_read" && it.parameterCount == 2 }
                    memRead.invoke(backend, entryAddr, minOf(codeBytes.size, 256)) as ByteArray
                }.getOrDefault(codeBytes)

                return ok(JSONObject()
                    .put("action", "run")
                    .put("arch", arch)
                    .put("entry", "0x${entryAddr.toString(16)}")
                    .put("codeHex", bytesToHex(codeBytes))
                    .put("codeSize", codeBytes.size)
                    .put("executed", true)
                    .put("maxInstructions", maxInsns)
                    .put("registers", regs)
                    .put("memReads", memResults)
                    .put("codeAfter", bytesToHex(codeAfter))
                    .put("hint", "寄存器值以十进制返回, 可用 0x前缀转十六进制. 如 x0=42 表示返回值 42"))

            } finally {
                // 关闭 emulator
                runCatching {
                    emulator.javaClass.getMethod("close").invoke(emulator)
                }
            }
        }

        private fun bytesToHex(bytes: ByteArray): String =
            bytes.joinToString(" ") { "%02X".format(it) }

        private fun parseHex(s: String): ByteArray {
            val cleaned = s.replace(Regex("[^0-9A-Fa-f]"), "")
            return cleaned.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        private fun parseAddr(s: String): Long {
            val trimmed = s.trim().removePrefix("0x").removePrefix("0X")
            return trimmed.toLong(16)
        }
    }

    val ALL = listOf(ucEmulate)
}
