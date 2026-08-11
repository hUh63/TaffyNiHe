package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import com.soreverse.mcp.engine.standalone.Arm64Disasm
import com.soreverse.mcp.engine.standalone.HexDump
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * 塔菲逆核: 纯 Java 内置 SO 分析工具（基于 SO逆向分析工具移植）。
 *
 * 提供不依赖 rizin/LIEF 等 native 引擎的纯 Java ELF/SO 分析能力：
 *  - taffy_so_standalone_elf:    纯 Java ELF 解析（ELF 头/节区/符号/字符串/段）
 *  - taffy_so_standalone_disasm: 纯 Java ARM64 反汇编
 *  - taffy_so_standalone_hexdump:十六进制转储
 *
 * 适用于无法加载 native 组件的环境，或作为 rizin 的轻量化备选。
 */
object SoStandaloneTools {

    // ── 纯 Java ARM64 反汇编 ──

    val disasm: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_so_standalone_disasm",
            "【纯 Java ARM64 反汇编】读取 .so/.elf 文件并用内置纯 Java 反汇编引擎解码 AArch64 指令。不依赖任何 native 库（rizin/capstone）。适用于 arm64-v8a SO 文件。",
            "【Pure Java ARM64 Disassembly】Read .so/.elf files and decode AArch64 instructions using the built-in pure Java disassembler. No native dependencies (rizin/capstone). Works with arm64-v8a SO files.",
            "analyze",
            ToolClass.EXTRA,
            heavy = true,
        ) {
            objectSchema(props {
                "path" str "SO/ELF 文件绝对路径"
                "filePath" str "path 的别名"
                "offset" str "起始虚拟地址(十六进制,如 0x1000,为空则从 .text 节开始)"
                "count" int "反汇编指令数(默认 64,最大 5000)"
                "baseAddr" str "基址(十六进制,默认 0),影响地址显示"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val inputPath = args.str("path").ifBlank { args.str("filePath") }
            if (inputPath.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path(SO 文件路径)", "path", "")
            val input = File(inputPath)
            if (!input.isFile) return err("FILE_NOT_FOUND", "文件不存在: $inputPath", "path", inputPath)

            return runCatching {
                val data = Files.readAllBytes(input.toPath())
                val offset = args.str("offset").ifBlank { null }
                val count = args.intValue("count", 64).coerceIn(1, 5000)
                val baseAddr = java.lang.Long.decode(args.str("baseAddr").ifBlank { "0x0" })

                var startPos = 0
                if (offset != null) {
                    // Try to find the .text section offset
                    startPos = java.lang.Long.decode(offset).toInt()
                }

                // If offset wasn't specified, try to find .text section
                if (offset == null) {
                    try {
                        val parser = com.soreverse.mcp.engine.ElfParser(data)
                        val elf = parser.parse()
                        val textSection = elf.sections.find { it.name == ".text" }
                        if (textSection != null) {
                            startPos = textSection.offset.toInt()
                        }
                    } catch (_: Exception) {
                        // Fall back to offset 0
                    }
                }

                val disasm = Arm64Disasm(data, baseAddr)
                val instructions = disasm.disassemble(count)

                val arr = JSONArray()
                for (insn in instructions) {
                    arr.put(JSONObject()
                        .put("address", "0x" + java.lang.Long.toHexString(insn.address))
                        .put("mnemonic", insn.mnemonic)
                        .put("operands", insn.operands)
                        .put("text", insn.toString()))
                }

                ok(JSONObject()
                    .put("tool", "taffy_so_standalone_disasm")
                    .put("engine", "pure_java")
                    .put("file", input.name)
                    .put("fileSize", data.size)
                    .put("baseAddr", "0x" + java.lang.Long.toHexString(baseAddr))
                    .put("instructions", arr)
                    .put("count", instructions.size)
                    .put("hint", "基于 Arm64Disasm 纯 Java 反汇编引擎（SO逆向分析工具移植）"))
            }.getOrElse { e ->
                err("DISASM_FAILED", "反汇编失败: ${e.message ?: e.javaClass.simpleName}", "path", inputPath)
            }
        }
    }

    // ── 纯 Java ELF 解析 ──

    val elf: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_so_standalone_elf",
            "【纯 Java ELF 分析】读取 .so/.elf 文件并用内置解析器提取 ELF 结构信息（头/节区/符号表/重定位/字符串）。可独立运行，不依赖 rizin/LIEF 等 native 引擎。",
            "【Pure Java ELF Analysis】Parse .so/.elf files with the built-in pure Java parser to extract ELF structure info (header/sections/symbols/relocations/strings). Standalone — no rizin/LIEF native dependencies required.",
            "analyze",
            ToolClass.EXTRA,
            heavy = true,
        ) {
            objectSchema(props {
                "path" str "SO/ELF 文件绝对路径"
                "filePath" str "path 的别名"
                "view".oneOf("full (default) | header | sections | symbols | strings | hexdump", "full", "header", "sections", "symbols", "strings", "hexdump")
                "limit" int "部分内容的最大返回数(默认 50,最大 5000)"
                "hexOffset" str "十六进制转储起始偏移(默认 0,仅 hexdump 视图)"
                "hexLength" int "十六进制转储字节数(默认 256,仅 hexdump 视图)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val inputPath = args.str("path").ifBlank { args.str("filePath") }
            if (inputPath.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path(SO 文件路径)", "path", "")
            val input = File(inputPath)
            if (!input.isFile) return err("FILE_NOT_FOUND", "文件不存在: $inputPath", "path", inputPath)

            return runCatching {
                val data = Files.readAllBytes(input.toPath())
                val parser = com.soreverse.mcp.engine.ElfParser(data)
                val elf = parser.parse()
                val view = args.str("view", "full")
                val limit = args.intValue("limit", 50).coerceIn(1, 5000)

                when (view) {
                    "header" -> buildElfHeader(elf, input.name)
                    "sections" -> buildElfSections(elf, limit, input.name)
                    "symbols" -> buildElfSymbols(elf, limit, input.name)
                    "strings" -> buildElfStrings(elf, limit, input.name)
                    "hexdump" -> {
                        val hexOffset = java.lang.Long.decode(args.str("hexOffset").ifBlank { "0x0" })
                        val hexLen = args.intValue("hexLength", 256).coerceIn(1, 65536)
                        val from = hexOffset.toInt().coerceIn(0, data.size)
                        val len = hexLen.coerceAtMost(data.size - from)
                        val dump = HexDump.dump(data, 0, from, len)
                        ok(JSONObject()
                            .put("tool", "taffy_so_standalone_elf")
                            .put("view", "hexdump")
                            .put("file", input.name)
                            .put("offset", "0x" + java.lang.Long.toHexString(hexOffset))
                            .put("length", len)
                            .put("hexdump", dump))
                    }
                    else -> buildElfFull(elf, input.name, limit)
                }
            }.getOrElse { e ->
                err("ELF_PARSE_FAILED", "ELF 解析失败: ${e.message ?: e.javaClass.simpleName}", "path", inputPath)
            }
        }
    }

    // ── 纯 Java 十六进制转储 ──

    val hexdump: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_so_standalone_hexdump",
            "【纯 Java 十六进制转储】读取 .so/.elf 或任意二进制文件的十六进制+ASCII 转储。独立于 rizin 等 native 引擎。",
            "【Pure Java Hex Dump】Read .so/.elf or any binary file and show hex+ASCII dump. Standalone — no rizin native dependency.",
            "read",
            ToolClass.EXTRA,
            heavy = true,
        ) {
            objectSchema(props {
                "path" str "文件绝对路径"
                "filePath" str "path 的别名"
                "offset" int "起始偏移(字节,默认 0)"
                "length" int "转储长度(字节,默认 256,最大 65536)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val inputPath = args.str("path").ifBlank { args.str("filePath") }
            if (inputPath.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path(文件路径)", "path", "")
            val input = File(inputPath)
            if (!input.isFile) return err("FILE_NOT_FOUND", "文件不存在: $inputPath", "path", inputPath)

            return runCatching {
                val data = Files.readAllBytes(input.toPath())
                val offset = args.intValue("offset", 0).coerceIn(0, data.size)
                val length = args.intValue("length", 256).coerceIn(1, 65536)
                val actualLen = length.coerceAtMost(data.size - offset)
                val dump = HexDump.dump(data, 0, offset, actualLen)

                ok(JSONObject()
                    .put("tool", "taffy_so_standalone_hexdump")
                    .put("file", input.name)
                    .put("fileSize", data.size)
                    .put("offset", offset)
                    .put("length", actualLen)
                    .put("hexdump", dump))
            }.getOrElse { e ->
                err("HEXDUMP_FAILED", "转储失败: ${e.message ?: e.javaClass.simpleName}", "path", inputPath)
            }
        }
    }

    val ALL: List<ToolHandler> = listOf(disasm, elf, hexdump)

    // ── Response builders ──

    private fun buildElfHeader(elf: com.soreverse.mcp.engine.ElfFile, fileName: String): JSONObject {
        val hdr = JSONObject()
        hdr.put("bits", "${elf.bits}-bit")
        hdr.put("endian", if (elf.littleEndian) "Little Endian" else "Big Endian")
        hdr.put("type", elfTypeName(elf.type))
        hdr.put("machine", elfMachineName(elf.machine))
        hdr.put("entry", "0x" + java.lang.Long.toHexString(elf.entry))
        hdr.put("sections", elf.sections.size)
        hdr.put("symbols", elf.symbols.size + elf.dynSymbols.size)

        return ok(JSONObject()
            .put("tool", "taffy_so_standalone_elf")
            .put("view", "header")
            .put("file", fileName)
            .put("header", hdr))
    }

    private fun buildElfSections(elf: com.soreverse.mcp.engine.ElfFile, limit: Int, fileName: String): JSONObject {
        val arr = JSONArray()
        for (sec in elf.sections.take(limit)) {
            arr.put(JSONObject()
                .put("name", sec.name)
                .put("type", "0x" + java.lang.Long.toHexString(sec.type))
                .put("flags", "0x" + java.lang.Long.toHexString(sec.flags))
                .put("addr", "0x" + java.lang.Long.toHexString(sec.addr))
                .put("offset", sec.offset)
                .put("size", sec.size))
        }
        return ok(JSONObject()
            .put("tool", "taffy_so_standalone_elf")
            .put("view", "sections")
            .put("file", fileName)
            .put("total", elf.sections.size)
            .put("returned", minOf(limit, elf.sections.size))
            .put("sections", arr))
    }

    private fun buildElfSymbols(elf: com.soreverse.mcp.engine.ElfFile, limit: Int, fileName: String): JSONObject {
        val allSymbols = elf.symbols + elf.dynSymbols
        val arr = JSONArray()
        for (sym in allSymbols.take(limit)) {
            val isImported = sym.imported
            arr.put(JSONObject()
                .put("name", sym.name)
                .put("bind", sym.bind)
                .put("type", sym.`type`)
                .put("visibility", sym.visibility)
                .put("value", "0x" + java.lang.Long.toHexString(sym.value))
                .put("size", sym.size)
                .put("imported", isImported))
        }
        return ok(JSONObject()
            .put("tool", "taffy_so_standalone_elf")
            .put("view", "symbols")
            .put("file", fileName)
            .put("total", allSymbols.size)
            .put("returned", minOf(limit, allSymbols.size))
            .put("symbols", arr))
    }

    private fun buildElfStrings(elf: com.soreverse.mcp.engine.ElfFile, limit: Int, fileName: String): JSONObject {
        val arr = JSONArray()
        for (s in elf.strings.take(limit)) {
            arr.put(JSONObject()
                .put("offset", s.offset)
                .put("text", s.value)
                .put("section", s.section))
        }
        return ok(JSONObject()
            .put("tool", "taffy_so_standalone_elf")
            .put("view", "symbols")
            .put("file", fileName)
            .put("total", elf.strings.size)
            .put("returned", minOf(limit, elf.strings.size))
            .put("strings", arr))
    }

    private fun buildElfFull(elf: com.soreverse.mcp.engine.ElfFile, fileName: String, limit: Int): JSONObject {
        val hdr = JSONObject()
        hdr.put("bits", "${elf.bits}-bit")
        hdr.put("endian", if (elf.littleEndian) "Little Endian" else "Big Endian")
        hdr.put("type", elfTypeName(elf.type))
        hdr.put("machine", elfMachineName(elf.machine))
        hdr.put("entry", "0x" + java.lang.Long.toHexString(elf.entry))

        val secArr = JSONArray()
        for (sec in elf.sections.take(limit)) {
            secArr.put(JSONObject().put("name", sec.name).put("size", sec.size).put("addr", "0x" + java.lang.Long.toHexString(sec.addr)))
        }
        val symArr = JSONArray()
        val allSym = elf.symbols + elf.dynSymbols
        for (sym in allSym.take(limit)) {
            symArr.put(JSONObject().put("name", sym.name).put("bind", sym.bind).put("type", sym.`type`))
        }

        return ok(JSONObject()
            .put("tool", "taffy_so_standalone_elf")
            .put("view", "full")
            .put("file", fileName)
            .put("header", hdr)
            .put("sections", JSONObject().put("total", elf.sections.size).put("items", secArr))
            .put("symbols", JSONObject().put("total", allSym.size).put("items", symArr))
            .put("relocations", elf.relocations.size)
            .put("strings", elf.strings.size))
    }

    private fun elfTypeName(type: Int): String = when (type) {
        0 -> "NONE"
        1 -> "REL (Relocatable)"
        2 -> "EXEC (Executable)"
        3 -> "DYN (Shared Object)"
        4 -> "CORE"
        0xFE00 -> "LOOS"
        0xFEFF -> "HIOS"
        0xFF00 -> "LOPROC"
        0xFFFF -> "HIPROC"
        else -> "0x" + type.toString(16)
    }

    private fun elfMachineName(machine: Int): String = when (machine) {
        0 -> "None"
        2 -> "SPARC"
        3 -> "x86"
        8 -> "MIPS"
        20 -> "PowerPC"
        21 -> "PowerPC64"
        40 -> "ARM"
        43 -> "SPARCv9"
        50 -> "IA-64"
        62 -> "x86-64"
        183 -> "AArch64"
        243 -> "RISC-V"
        else -> "Unknown(0x" + machine.toString(16) + ")"
    }
}
