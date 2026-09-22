package com.soreverse.mcp.engine

import com.exbin.app.elf.ElfFile as ExbinElfFile
import com.exbin.app.elf.ElfHeader as ExbinElfHeader
import com.exbin.app.elf.SectionHeader as ExbinSectionHeader
import com.exbin.app.elf.SymbolEntry as ExbinSymbolEntry
import com.exbin.app.elf.RelocationEntry as ExbinRelocEntry
import com.exbin.app.elf.ImportedFunction as ExbinImport
import com.exbin.app.elf.ExtractedString as ExbinString
import com.exbin.app.elf.FunctionInfo as ExbinFunctionInfo
import com.exbin.app.nativebridge.NativeBridge

/**
 * 塔菲 ELF 模型 → Exbin ELF 模型 的桥接层。
 *
 * Exbin 的 [com.exbin.app.elf.DisasmAnnotator] / [com.exbin.app.elf.FunctionSignatureAnalyzer]
 * 消费的是 Exbin 自己的 `ElfFile` 数据模型（符号表/字符串/重定位/数据标签）。
 * 塔菲用 lief/rizin 解析出 [com.soreverse.mcp.engine.ElfFile]，本对象把它完整映射为
 * Exbin 模型，从而让移植过来的纯 Java 分析模块能直接工作。
 */
internal object ExbinElfBridge {

    const val ELFCLASS32 = 1
    const val ELFCLASS64 = 2
    const val ELFDATA2LSB = 1
    const val ELFDATA2MSB = 2

    /** Exbin DisasmAnnotator 读取的 DataLabelNative.type 取值。 */
    private const val DL_SYMTAB = 0
    private const val DL_RELOC = 1
    private const val DL_STRING = 2

    /** 由塔菲 ElfFile + 原始字节构建 Exbin ElfFile（只填 Exbin 分析模块实际消费的字段）。 */
    fun from(elf: ElfFile, bytes: ByteArray): ExbinElfFile {
        val out = ExbinElfFile()
        out.header = ExbinElfHeader().apply {
            eiClass = if (elf.bits == 64) ELFCLASS64 else ELFCLASS32
            eiData = if (elf.littleEndian) ELFDATA2LSB else ELFDATA2MSB
            eType = elf.type
            eMachine = elf.machine
            eEntry = elf.entry
            ePhentsize = elf.programHeaders.firstOrNull()?.let { 0 } ?: 0
            ePhnum = elf.programHeaders.size
            eShentsize = 0
            eShnum = elf.sections.size
        }

        // ── 节区头 ──
        out.sectionHeaders = elf.sections.map { s ->
            ExbinSectionHeader().apply {
                name = s.name
                shType = s.type.toInt()
                shFlags = s.flags
                shAddr = s.addr
                shOffset = s.offset
                shSize = s.size
                shLink = s.link
                shInfo = s.info
                shAddralign = s.addralign
                shEntsize = s.entsize
            }
        }

        // ── 符号表（.symtab / .dynsym）──
        out.symtabEntries = elf.symbols.map { symToEntry(it) }
        out.dynsymEntries = elf.dynSymbols.map { symToEntry(it) }

        // ── 导入函数（PLT）──
        out.imports = elf.dynSymbols.filter { it.imported && it.name.isNotBlank() }.map { s ->
            ExbinImport().apply {
                name = s.name
                pltAddress = s.value
                pltSize = 0
                section = ".plt"
            }
        }

        // ── 重定位 ──
        out.relocations = elf.relocations.map { r ->
            ExbinRelocEntry().apply {
                rOffset = r.offset
                symbolName = r.symbol
                typeName = r.type.toString()
            }
        }

        // ── 字符串（Exbin 需要 address；塔菲只有文件偏移，这里按节区换算 VA）──
        out.strings = elf.strings.map { st ->
            val va = vaForOffset(elf.sections, st.offset)
            ExbinString(st.offset, va, st.value, st.section).also { it.encoding = st.encoding }
        }

        // ── 函数（供 callback/caller 场景使用）──
        out.functions = elf.symbols.filter { it.type == "FUNC" && !it.imported }.map { s ->
            ExbinFunctionInfo(s.name, s.value, s.size, ".text")
        }

        // ── 数据标签（DisasmAnnotator 的全局变量 / 常量注释来源）──
        val labels = ArrayList<NativeBridge.DataLabelNative>()
        for (s in elf.symbols + elf.dynSymbols) {
            if (s.value == 0L || s.name.isBlank()) continue
            if (s.type == "OBJECT") {
                labels.add(
                    NativeBridge.DataLabelNative().apply {
                        address = s.value
                        name = s.name
                        size = s.size.toInt()
                        type = DL_SYMTAB
                    },
                )
            }
        }
        for (r in elf.relocations) {
            if (r.offset != 0L && r.symbol.isNotBlank()) {
                labels.add(
                    NativeBridge.DataLabelNative().apply {
                        address = r.offset
                        name = r.symbol
                        type = DL_RELOC
                    },
                )
            }
        }
        for (st in elf.strings) {
            val va = vaForOffset(elf.sections, st.offset)
            if (va != 0L && st.value.isNotBlank()) {
                labels.add(
                    NativeBridge.DataLabelNative().apply {
                        address = va
                        name = st.value.take(40)
                        type = DL_STRING
                    },
                )
            }
        }
        out.dataLabels = labels.toTypedArray()
        return out
    }

    private fun symToEntry(s: SymbolInfo): ExbinSymbolEntry = ExbinSymbolEntry().apply {
        name = s.name
        stValue = s.value
        stSize = s.size
        stInfo = (bindNum(s.bind) shl 4) or typeNum(s.type)
        stShndx = s.sectionIndex
    }

    private fun bindNum(b: String): Int = when (b.uppercase()) {
        "LOCAL" -> 0
        "GLOBAL" -> 1
        "WEAK" -> 2
        else -> 0
    }

    private fun typeNum(t: String): Int = when (t.uppercase()) {
        "OBJECT" -> 1
        "FUNC" -> 2
        "SECTION" -> 3
        "FILE" -> 4
        else -> 0
    }

    /** 文件偏移 → 虚拟地址（按节区换算；不可映射返回 0）。 */
    fun vaForOffset(sections: List<SectionInfo>, offset: Long): Long {
        for (s in sections) {
            if (s.size > 0 && offset >= s.offset && offset < s.offset + s.size) {
                return s.addr + (offset - s.offset)
            }
        }
        return 0L
    }
}
