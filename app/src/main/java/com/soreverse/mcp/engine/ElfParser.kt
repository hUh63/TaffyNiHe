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
        for ((index, sec) in sections.withIndex()) {
            if (sec.type != 2L && sec.type != 11L) continue
            val strtab = sections.getOrNull(sec.link)?.let { sectionBytes(it) } ?: ByteArray(0)
            val count = if (sec.entsize > 0) (sec.size / sec.entsize).toInt() else 0
            val dest = if (sec.type == 11L) dynSymbols else symbols
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
                if (name.isEmpty()) continue
                val bind = when (info ushr 4) { 0 -> "LOCAL"; 1 -> "GLOBAL"; 2 -> "WEAK"; else -> "OTHER" }
                val typ = when (info and 0xf) { 0 -> "NOTYPE"; 1 -> "OBJECT"; 2 -> "FUNC"; 6 -> "TLS"; else -> "OTHER" }
                val vis = when (other and 0x3) { 0 -> "DEFAULT"; 1 -> "INTERNAL"; 2 -> "HIDDEN"; 3 -> "PROTECTED"; else -> "DEFAULT" }
                dest += SymbolInfo(name, bind, typ, vis, shndx, value, size, shndx == 0, bind != "LOCAL" && shndx != 0)
            }
        }
        val allSymbols = symbols + dynSymbols
        val relocs = mutableListOf<RelocInfo>()
        for (sec in sections) {
            if (sec.type != 9L && sec.type != 4L) continue
            val symtab = if (sec.link in sections.indices) {
                val linked = sections[sec.link]
                if (linked.type == 11L) dynSymbols else symbols
            } else allSymbols
            val count = if (sec.entsize > 0) (sec.size / sec.entsize).toInt() else 0
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
                relocs += RelocInfo(sec.name, relocOffset, relocType, symtab.getOrNull(symIndex)?.name.orEmpty(), addend)
            }
        }
        val strings = mutableListOf<StringInfo>()
        for (sec in sections) {
            if (sec.name !in setOf(".rodata", ".strtab", ".dynstr")) continue
            extractStrings(sectionBytes(sec), sec.offset, sec.name, strings)
        }
        return ElfFile(data, bits, little, type, machine, entry, sections, symbols, dynSymbols, relocs, strings, symbolVersions = parseSymbolVersions(sections, dynSymbols, r))
    }

    /**
     * 解析 .gnu.version (SHT_GNU_versym) + .gnu.version_r (verneed) + .gnu.version_d (verdef),
     * 为每个动态符号关联其版本名（如 GLIBC_2.17）。纯 Kotlin, 不依赖 native。
     */
    private fun parseSymbolVersions(sections: List<SectionInfo>, dynSymbols: List<SymbolInfo>, r: Reader): List<SymbolVersionInfo> {
        val versym = sections.firstOrNull { it.type == 0x6fffffffL } ?: return emptyList()
        if (dynSymbols.isEmpty()) return emptyList()
        val idxName = HashMap<Int, String>()
        // verneed：依赖的版本（如 GLIBC_2.17）
        sections.firstOrNull { it.type == 0x6ffffffeL }?.let { vn ->
            val strtab = sections.getOrNull(vn.link)?.let { sectionBytes(it) } ?: ByteArray(0)
            val end = (vn.offset + vn.size).toInt()
            var off = vn.offset.toInt()
            var guard = 0
            while (off + 16 <= end && off >= 0 && guard++ < 8192) {
                val cnt = r.u16(off + 2)
                var aux = off + r.u32(off + 8).toInt()
                var i = 0
                while (i < cnt && aux + 16 <= end && aux >= 0) {
                    idxName[r.u16(aux + 6)] = cstr(strtab, r.u32(aux + 8).toInt())
                    val next = r.u32(aux + 12).toInt()
                    if (next == 0) break
                    aux += next; i++
                }
                val nextVn = r.u32(off + 12).toInt()
                if (nextVn == 0) break
                off += nextVn
            }
        }
        // verdef：库自定义版本
        sections.firstOrNull { it.type == 0x6ffffffdL }?.let { vd ->
            val strtab = sections.getOrNull(vd.link)?.let { sectionBytes(it) } ?: ByteArray(0)
            val end = (vd.offset + vd.size).toInt()
            var off = vd.offset.toInt()
            var guard = 0
            while (off + 20 <= end && off >= 0 && guard++ < 8192) {
                val ndx = r.u16(off + 4)
                val auxOff = off + r.u32(off + 12).toInt()
                if (auxOff + 8 <= end && auxOff >= 0) idxName[ndx] = cstr(strtab, r.u32(auxOff).toInt())
                val next = r.u32(off + 16).toInt()
                if (next == 0) break
                off += next
            }
        }
        val out = ArrayList<SymbolVersionInfo>()
        val maxN = if (versym.entsize > 0) (versym.size / versym.entsize).toInt() else (versym.size / 2).toInt()
        for (i in dynSymbols.indices) {
            if (i >= maxN) break
            val eoff = versym.offset.toInt() + i * 2
            if (eoff < 0 || eoff + 2 > data.size) break
            val raw = r.u16(eoff)
            val hidden = (raw and 0x8000) != 0
            val idx = raw and 0x7fff
            val vname = when (idx) {
                0 -> "local"
                1 -> "global"
                else -> idxName[idx] ?: "ver$idx"
            }
            out.add(SymbolVersionInfo(dynSymbols[i].name, vname, hidden, idx <= 1))
        }
        return out
    }

    private fun sectionBytes(section: SectionInfo): ByteArray {
        if (section.offset < 0 || section.size <= 0) return ByteArray(0)
        val start = section.offset.toInt().coerceIn(0, data.size)
        val end = min(data.size, start + section.size.toInt())
        return data.copyOfRange(start, end)
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
            val start = offset.toInt().coerceIn(0, data.size)
            val end = min(data.size, start + size.toInt().coerceAtLeast(0))
            return data.copyOfRange(start, end)
        }
    }

    private class Reader(private val bytes: ByteArray, little: Boolean) {
        private val order = if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        fun u8(o: Int): Int = bytes[o].toInt() and 0xff
        fun u16(o: Int): Int = ByteBuffer.wrap(bytes, o, 2).order(order).short.toInt() and 0xffff
        fun u32(o: Int): Long = ByteBuffer.wrap(bytes, o, 4).order(order).int.toLong() and 0xffffffffL
        fun s32(o: Int): Int = ByteBuffer.wrap(bytes, o, 4).order(order).int
        fun u64(o: Int): Long = ByteBuffer.wrap(bytes, o, 8).order(order).long
        fun s64(o: Int): Long = ByteBuffer.wrap(bytes, o, 8).order(order).long
    }
}
