package com.soreverse.mcp.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import kotlin.math.min

class ElfParser(private val data: ByteArray) {
    fun parse(): ElfFile {
        require(data.size >= 16 && data[0] == 0x7f.toByte() && data[1] == 'E'.code.toByte() && data[2] == 'L'.code.toByte() && data[3] == 'F'.code.toByte()) {
            "Not an ELF file"
        }
        val bits = if (data[4].toInt() == 2) 64 else 32
        val little = data[5].toInt() != 2
        val r = Reader(data, little)
        val type = r.u16(16)
        val machine = r.u16(18)
        val entry = if (bits == 64) r.u64(24) else r.u32(24)
        val shoff = if (bits == 64) r.u64(40) else r.u32(32)
        val shentsize = if (bits == 64) r.u16(58) else r.u16(46)
        val shnum = if (bits == 64) r.u16(60) else r.u16(48)
        val shstrndx = if (bits == 64) r.u16(62) else r.u16(50)

        val rawSections = (0 until shnum).map { idx ->
            val off = (shoff + idx.toLong() * shentsize).toInt()
            RawSection(
                nameOffset = r.u32(off).toInt(),
                type = r.u32(off + 4),
                flags = if (bits == 64) r.u64(off + 8) else r.u32(off + 8),
                addr = if (bits == 64) r.u64(off + 16) else r.u32(off + 12),
                offset = if (bits == 64) r.u64(off + 24) else r.u32(off + 16),
                size = if (bits == 64) r.u64(off + 32) else r.u32(off + 20),
                link = if (bits == 64) r.u32(off + 40).toInt() else r.u32(off + 24).toInt(),
                info = if (bits == 64) r.u32(off + 44).toInt() else r.u32(off + 28).toInt(),
                addralign = if (bits == 64) r.u64(off + 48) else r.u32(off + 32),
                entsize = if (bits == 64) r.u64(off + 56) else r.u32(off + 36),
            )
        }
        val shstr = rawSections.getOrNull(shstrndx)?.bytes(data) ?: ByteArray(0)
        val sections = rawSections.map {
            SectionInfo(
                name = cstr(shstr, it.nameOffset),
                type = it.type,
                flags = it.flags,
                addr = it.addr,
                offset = it.offset,
                size = it.size,
                link = it.link,
                info = it.info,
                addralign = it.addralign,
                entsize = it.entsize,
            )
        }
        val symbols = mutableListOf<SymbolInfo>()
        val dynSymbols = mutableListOf<SymbolInfo>()
        // 原始下标保真：重定位的 symIndex 指向**未被过滤**的符号表条目。ELF 的
        // .dynsym[0] 一定是空名符号，一旦按「名字为空就跳过」来收集，列表整体前移
        // 一格，所有重定位的符号名都会错位（实测 libandroidx.graphics.path.so：
        // .rela.dyn 里每条 R_AARCH64_RELATIVE 都被标成 __cxa_finalize，
        // .rela.plt 里 free 被标成 JNI_OnLoad）。这两个表按原始下标存，不参与展示。
        val rawSymbols = ArrayList<SymbolInfo?>()
        val rawDynSymbols = ArrayList<SymbolInfo?>()
        for ((index, sec) in sections.withIndex()) {
            if (sec.type != 2L && sec.type != 11L) continue
            val strtab = sections.getOrNull(sec.link)?.let { sectionBytes(it) } ?: ByteArray(0)
            // 上限：伪造 entsize=1 + size=0x7FFFFFFF 会让循环跑 21 亿次（CPU 占满、不抛异常）
            val count = if (sec.entsize > 0) {
                val bySize = sec.size / sec.entsize
                val byFile = data.size.toLong() / sec.entsize
                bySize.coerceAtMost(byFile).coerceAtMost(1_000_000L).toInt().coerceAtLeast(0)
            } else 0
            val dest = if (sec.type == 11L) dynSymbols else symbols
            val raw = if (sec.type == 11L) rawDynSymbols else rawSymbols
            for (i in 0 until count) {
                val off = (sec.offset + i * sec.entsize).toInt()
                if (off < 0 || off >= data.size) continue
                val nameOffset: Int
                val info: Int
                val other: Int
                val shndx: Int
                val value: Long
                val size: Long
                if (bits == 64) {
                    nameOffset = r.u32(off).toInt()
                    info = r.u8(off + 4)
                    other = r.u8(off + 5)
                    shndx = r.u16(off + 6)
                    value = r.u64(off + 8)
                    size = r.u64(off + 16)
                } else {
                    nameOffset = r.u32(off).toInt()
                    value = r.u32(off + 4)
                    size = r.u32(off + 8)
                    info = r.u8(off + 12)
                    other = r.u8(off + 13)
                    shndx = r.u16(off + 14)
                }
                val name = cstr(strtab, nameOffset)
                val bind = when (info ushr 4) { 0 -> "LOCAL"; 1 -> "GLOBAL"; 2 -> "WEAK"; else -> "OTHER" }
                val typ = when (info and 0xf) { 0 -> "NOTYPE"; 1 -> "OBJECT"; 2 -> "FUNC"; 6 -> "TLS"; else -> "OTHER" }
                val vis = when (other and 0x3) { 0 -> "DEFAULT"; 1 -> "INTERNAL"; 2 -> "HIDDEN"; 3 -> "PROTECTED"; else -> "DEFAULT" }
                val sym = SymbolInfo(name, bind, typ, vis, shndx, value, size, shndx == 0, bind != "LOCAL" && shndx != 0)
                while (raw.size <= i) raw.add(null)
                raw[i] = sym
                if (name.isEmpty()) continue
                dest += sym
            }
        }
        val relocs = mutableListOf<RelocInfo>()
        // 重定位的 section 语义 = 它**作用于**哪个节（与 native LIEF 的 reloc.section() 一致），
        // 不是它来自哪张重定位表。概览里的 PLT 桩计数只认 contains("plt")，两条路径必须同语义。
        fun sectionOfVa(va: Long): String {
            if (va <= 0) return ""
            for (sec in sections) {
                if (sec.addr > 0 && va >= sec.addr && va - sec.addr < sec.size) return sec.name
            }
            return ""
        }
        for (sec in sections) {
            if (sec.type != 9L && sec.type != 4L) continue
            val symtab = if (sec.link in sections.indices) {
                val linked = sections[sec.link]
                if (linked.type == 11L) rawDynSymbols else rawSymbols
            } else rawSymbols + rawDynSymbols
            // 上限：伪造 entsize=1 + size=0x7FFFFFFF 会让循环跑 21 亿次（CPU 占满、不抛异常）
            val count = if (sec.entsize > 0) {
                val bySize = sec.size / sec.entsize
                val byFile = data.size.toLong() / sec.entsize
                bySize.coerceAtMost(byFile).coerceAtMost(1_000_000L).toInt().coerceAtLeast(0)
            } else 0
            for (i in 0 until count) {
                val off = (sec.offset + i * sec.entsize).toInt()
                val relocOffset: Long
                val info: Long
                val addend: Long
                if (bits == 64) {
                    relocOffset = r.u64(off)
                    info = r.u64(off + 8)
                    addend = if (sec.type == 4L) r.s64(off + 16) else 0L
                } else {
                    relocOffset = r.u32(off)
                    info = r.u32(off + 4)
                    addend = if (sec.type == 4L) r.s32(off + 8).toLong() else 0L
                }
                val symIndex = if (bits == 64) (info ushr 32).toInt() else (info ushr 8).toInt()
                val relocType = if (bits == 64) info and 0xffffffffL else info and 0xff
                relocs += RelocInfo(
                    sectionOfVa(relocOffset).ifEmpty { sec.name },
                    relocOffset,
                    relocType,
                    symtab.getOrNull(symIndex)?.name.orEmpty(),
                    addend,
                )
            }
        }
        val strings = mutableListOf<StringInfo>()
        for (sec in sections) {
            if (sec.name !in setOf(".rodata", ".strtab", ".dynstr")) continue
            extractStrings(sectionBytes(sec), sec.offset, sec.name, strings)
        }
        return ElfFile(data, bits, little, type, machine, entry, sections, symbols, dynSymbols, relocs, strings, symbolVersions = ElfGnuVersions.parse(data, sections))
    }

    private fun sectionBytes(section: SectionInfo): ByteArray {
        if (section.offset < 0 || section.size <= 0) return ByteArray(0)
        val start = section.offset.coerceIn(0L, data.size.toLong()).toInt()
        // 两点：①size 可能大于 Int.MAX_VALUE（u64 读出来的），先按文件长度夹住；
        // ②即便夹过，start + size 仍能溢出 Int 回绕成负数（start=1000, size=0x7FFFFF00 即可），
        // 那时 copyOfRange(start, 负数) 直接抛 IllegalArgumentException —— 畸形 sh_size 就能崩。
        val sizeCap = section.size.coerceAtMost(data.size.toLong())
        val end = min(data.size.toLong(), start.toLong() + sizeCap).toInt()
        return if (end > start) data.copyOfRange(start, end) else ByteArray(0)
    }

    private fun extractStrings(bytes: ByteArray, base: Long, section: String, out: MutableList<StringInfo>) {
        var start = 0
        var i = 0
        while (i <= bytes.size) {
            if (i == bytes.size || bytes[i] == 0.toByte()) {
                emitStringCandidate(bytes, start, i, base, section, out)
                start = i + 1
            }
            i++
        }
    }

    private fun emitStringCandidate(bytes: ByteArray, start: Int, end: Int, base: Long, section: String, out: MutableList<StringInfo>) {
        if (end - start < 4) return
        val raw = bytes.copyOfRange(start, end)
        val text = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
                .toString()
        }.getOrNull() ?: return
        val clean = text.takeWhile { it == '\t' || it == '\n' || it == '\r' || !it.isISOControl() }.trimEnd()
        if (clean.length < 2) return
        val useful = clean.any { it.isLetterOrDigit() || Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        val mostlyText = clean.count { it == '\t' || it == '\n' || it == '\r' || !it.isISOControl() } >= clean.length
        if (useful && mostlyText) {
            out += StringInfo(base + start, clean.take(1024), raw.size, section)
        }
    }

    private fun cstr(bytes: ByteArray, offset: Int): String {
        if (offset < 0 || offset >= bytes.size) return ""
        var end = offset
        while (end < bytes.size && bytes[end] != 0.toByte()) end++
        return bytes.copyOfRange(offset, end).toString(Charsets.UTF_8)
    }

    private data class RawSection(
        val nameOffset: Int,
        val type: Long,
        val flags: Long,
        val addr: Long,
        val offset: Long,
        val size: Long,
        val link: Int,
        val info: Int,
        val addralign: Long,
        val entsize: Long,
    ) {
        fun bytes(data: ByteArray): ByteArray {
            // 与 sectionBytes 同款：offset/size 都是 u64，start + size 必须走 Long，否则回绕成负数崩溃
            if (offset < 0 || size <= 0) return ByteArray(0)
            val start = offset.coerceIn(0L, data.size.toLong()).toInt()
            val end = min(data.size.toLong(), start.toLong() + size.coerceAtMost(data.size.toLong())).toInt()
            return if (end > start) data.copyOfRange(start, end) else ByteArray(0)
        }
    }

    private class Reader(private val bytes: ByteArray, little: Boolean) {
        private val order = if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        /** 越界检查：畸形 ELF 的 sh_offset/size 会把读取推到文件外，旧实现直接 IndexOutOfBounds。 */
        private fun has(o: Int, n: Int): Boolean = o >= 0 && n >= 0 && o <= bytes.size - n

        fun u8(o: Int): Int = if (has(o, 1)) bytes[o].toInt() and 0xff else 0
        fun u16(o: Int): Int =
            if (has(o, 2)) ByteBuffer.wrap(bytes, o, 2).order(order).short.toInt() and 0xffff else 0
        fun u32(o: Int): Long =
            if (has(o, 4)) ByteBuffer.wrap(bytes, o, 4).order(order).int.toLong() and 0xffffffffL else 0L
        fun s32(o: Int): Int = if (has(o, 4)) ByteBuffer.wrap(bytes, o, 4).order(order).int else 0
        fun u64(o: Int): Long = if (has(o, 8)) ByteBuffer.wrap(bytes, o, 8).order(order).long else 0L
        fun s64(o: Int): Long = if (has(o, 8)) ByteBuffer.wrap(bytes, o, 8).order(order).long else 0L
    }
}
