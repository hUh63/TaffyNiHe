package com.soreverse.mcp.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * .gnu.version / .gnu.version_r / .gnu.version_d 解析（纯 Kotlin，不依赖 native）。
 *
 * 单独抽成 object 的原因有两个：
 *  1. LIEF 主路径此前完全不带符号版本 —— `ElfFile.symbolVersions` 恒为空，只有手写
 *     [ElfParser] 会填，于是 `taffy_so_versions` 一类输出在主路径下永远是空表。
 *  2. 两侧的动态符号列表都会过滤掉空名符号（ELF 的 .dynsym[0] 一定是空符号），
 *     而 versym 的下标是**原始符号表**下标。用过滤后的列表按位置对齐会整体错位一格。
 *     这里直接按原始 .dynsym 表读名字，不再依赖外部传进来的符号列表。
 */
internal object ElfGnuVersions {

    private const val SHT_DYNSYM = 11L
    private const val SHT_GNU_VERDEF = 0x6ffffffdL
    private const val SHT_GNU_VERNEED = 0x6ffffffeL
    private const val SHT_GNU_VERSYM = 0x6fffffffL

    fun parse(data: ByteArray, sections: List<SectionInfo>): List<SymbolVersionInfo> {
        if (data.size < 16) return emptyList()
        val versym = sections.firstOrNull { it.type == SHT_GNU_VERSYM } ?: return emptyList()
        val dynsym = sections.firstOrNull { it.type == SHT_DYNSYM } ?: return emptyList()
        val little = data[5].toInt() != 2
        val bits = if (data[4].toInt() == 2) 64 else 32
        val r = Reader(data, little)

        // 版本索引 → 版本名（从 verneed / verdef 两张表来）
        val idxName = HashMap<Int, String>()
        sections.firstOrNull { it.type == SHT_GNU_VERNEED }?.let { vn ->
            val strtab = sections.getOrNull(vn.link)?.let { bytesOf(data, it) } ?: ByteArray(0)
            val end = (vn.offset + vn.size).toInt()
            var off = vn.offset.toInt()
            var guard = 0
            while (off >= 0 && off + 16 <= end && guard++ < 8192) {
                val auxCount = r.u16(off + 2)
                var aux = off + r.u32(off + 8).toInt()
                var i = 0
                while (i < auxCount && aux >= 0 && aux + 16 <= end) {
                    idxName[r.u16(aux + 6)] = cstr(strtab, r.u32(aux + 8).toInt())
                    val next = r.u32(aux + 12).toInt()
                    if (next == 0) break
                    aux += next
                    i++
                }
                val nextVn = r.u32(off + 12).toInt()
                if (nextVn == 0) break
                off += nextVn
            }
        }
        sections.firstOrNull { it.type == SHT_GNU_VERDEF }?.let { vd ->
            val strtab = sections.getOrNull(vd.link)?.let { bytesOf(data, it) } ?: ByteArray(0)
            val end = (vd.offset + vd.size).toInt()
            var off = vd.offset.toInt()
            var guard = 0
            while (off >= 0 && off + 20 <= end && guard++ < 8192) {
                val ndx = r.u16(off + 4)
                val auxOff = off + r.u32(off + 12).toInt()
                if (auxOff >= 0 && auxOff + 8 <= end) idxName[ndx] = cstr(strtab, r.u32(auxOff).toInt())
                val next = r.u32(off + 16).toInt()
                if (next == 0) break
                off += next
            }
        }

        // versym 与 .dynsym 一一对应：条数以两者较小值为准（entsize 缺失时按 elf 规定默认值）
        val vsEntsize = if (versym.entsize > 0) versym.entsize else 2L
        val dsEntsize = if (dynsym.entsize > 0) dynsym.entsize else if (bits == 64) 24L else 16L
        val byVersym = (versym.size / vsEntsize).coerceAtLeast(0)
        val byDynsym = (dynsym.size / dsEntsize).coerceAtLeast(0)
        val count = min(min(byVersym, byDynsym), 1_000_000L).toInt()
        if (count <= 0) return emptyList()

        val dynstr = sections.getOrNull(dynsym.link)?.let { bytesOf(data, it) } ?: ByteArray(0)
        val out = ArrayList<SymbolVersionInfo>(count)
        for (i in 0 until count) {
            val symOff = (dynsym.offset + i * dsEntsize).toInt()
            val eoff = (versym.offset + i * vsEntsize).toInt()
            if (eoff < 0 || eoff + 2 > data.size) break
            val raw = r.u16(eoff)
            val hidden = (raw and 0x8000) != 0
            val idx = raw and 0x7fff
            val vname = when (idx) {
                0 -> "local"
                1 -> "global"
                else -> idxName[idx] ?: "ver$idx"
            }
            if (idx <= 1) continue // local / global 是内建索引，不是真正的版本名
            val name = if (symOff >= 0 && symOff + 4 <= data.size) cstr(dynstr, r.u32(symOff).toInt()) else ""
            if (name.isEmpty()) continue
            out += SymbolVersionInfo(name, vname, hidden, false)
        }
        return out
    }

    /** 节内容（越界/超大一律夹到文件范围内，Long 运算避免 offset+size 溢出成负数）。 */
    private fun bytesOf(data: ByteArray, section: SectionInfo): ByteArray {
        if (section.offset <= 0 || section.size <= 0) return ByteArray(0)
        val start = section.offset.coerceIn(0L, data.size.toLong()).toInt()
        val end = min(data.size.toLong(), start.toLong() + section.size.coerceAtMost(data.size.toLong())).toInt()
        return if (end > start) data.copyOfRange(start, end) else ByteArray(0)
    }

    private fun cstr(bytes: ByteArray, offset: Int): String {
        if (offset < 0 || offset >= bytes.size) return ""
        var end = offset
        while (end < bytes.size && bytes[end] != 0.toByte()) end++
        return bytes.copyOfRange(offset, end).toString(Charsets.UTF_8)
    }

    private class Reader(private val bytes: ByteArray, little: Boolean) {
        private val order = if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

        private fun has(o: Int, n: Int): Boolean = o >= 0 && n >= 0 && o <= bytes.size - n

        fun u16(o: Int): Int =
            if (has(o, 2)) ByteBuffer.wrap(bytes, o, 2).order(order).short.toInt() and 0xffff else 0

        fun u32(o: Int): Long =
            if (has(o, 4)) ByteBuffer.wrap(bytes, o, 4).order(order).int.toLong() and 0xffffffffL else 0L
    }
}
