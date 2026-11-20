package com.soreverse.mcp.core

import android.content.Context

/**
 * 轻量 ELF 节区表读取：只读文件头 + 节区头表 + 节名字符串表。
 *
 * 地址查看器需要「跳到某个节区」，但为了拿到节区名把 500MB 的 SO 整份读进内存完全不划算。
 * 这里复用 [HexSourceReader] 的随机访问，通常只读几十 KB。
 *
 * 这不是完整 ELF 解析器：不处理 e_shnum == 0 时的「section 0 扩展」写法，遇到直接返回空表，
 * 而不是猜一个错误结果给 UI。
 */
internal object ElfSections {

    data class Item(
        val name: String,
        val type: Int,
        val flags: Long,
        val addr: Long,
        val offset: Long,
        val size: Long,
    ) {
        val isAlloc: Boolean get() = flags and 0x2L != 0L
        val isExecutable: Boolean get() = flags and 0x4L != 0L

        /** 节区类型的可读名（只覆盖常见几种，其余给十六进制）。 */
        fun typeLabel(): String = when (type) {
            0 -> "NULL"
            1 -> "PROGBITS"
            2 -> "SYMTAB"
            3 -> "STRTAB"
            4 -> "RELA"
            5 -> "HASH"
            6 -> "DYNAMIC"
            7 -> "NOTE"
            8 -> "NOBITS"
            9 -> "REL"
            11 -> "DYNSYM"
            14 -> "INIT_ARRAY"
            15 -> "FINI_ARRAY"
            16 -> "PREINIT_ARRAY"
            17 -> "GROUP"
            18 -> "SYMTAB_SHNDX"
            else -> "0x" + Integer.toHexString(type)
        }
    }

    private const val MAX_SECTIONS = 4096
    private const val MAX_STRTAB = 1 shl 20

    fun read(context: Context, path: String): List<Item> {
        // 64 字节够 64 位 ELF 头（32 位头只有 52 字节）。
        val head = HexSourceReader.readWindow(context, path, 0, 64).data ?: return emptyList()
        if (head.size < 52) return emptyList()
        if (head[0] != 0x7F.toByte() || head[1] != 'E'.code.toByte() || head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()) {
            return emptyList()
        }
        val is64 = head[4].toInt() == 2
        val little = head[5].toInt() != 2
        fun u16(at: Int): Int {
            if (at + 2 > head.size) return 0
            val a = head[at].toInt() and 0xFF
            val b = head[at + 1].toInt() and 0xFF
            return if (little) a or (b shl 8) else (a shl 8) or b
        }

        val shoff: Long
        val shentsize: Int
        val shnum: Int
        val shstrndx: Int
        if (is64) {
            shoff = u64(head, 0x28, little)
            shentsize = u16(0x3A)
            shnum = u16(0x3C)
            shstrndx = u16(0x3E)
        } else {
            shoff = u32(head, 0x20, little)
            shentsize = u16(0x2E)
            shnum = u16(0x30)
            shstrndx = u16(0x32)
        }
        val minEnt = if (is64) 64 else 40
        if (shoff <= 0L || shnum <= 0 || shnum > MAX_SECTIONS || shentsize < minEnt) return emptyList()

        val tableSize = shnum.toLong() * shentsize
        if (tableSize > 8L * 1024 * 1024) return emptyList()
        val table = HexSourceReader.readWindow(context, path, shoff, tableSize.toInt()).data ?: return emptyList()
        if (table.size < tableSize) return emptyList()

        // 节名字符串表：先拿到 shstrndx 那一项的 offset/size。
        fun field(base: Int, off: Int, wide: Boolean): Long =
            if (wide) u64(table, base + off, little) else u32(table, base + off, little)

        val strIdx = shstrndx.coerceIn(0, shnum - 1)
        val strBase = strIdx * shentsize
        val strOff = if (strBase + minEnt <= table.size) field(strBase, if (is64) 24 else 16, is64) else 0L
        val strSize = if (strBase + minEnt <= table.size) field(strBase, if (is64) 32 else 20, is64) else 0L
        val strtab = if (strOff > 0L && strSize in 1..MAX_STRTAB.toLong()) {
            HexSourceReader.readWindow(context, path, strOff, strSize.toInt()).data
        } else null

        val out = ArrayList<Item>(shnum)
        for (i in 0 until shnum) {
            val base = i * shentsize
            if (base + minEnt > table.size) break
            val nameOff = u32(table, base, little).toInt()
            val type = u32(table, base + 4, little).toInt()
            val flags = field(base, 8, is64)
            val addr = field(base, if (is64) 16 else 12, is64)
            val offset = field(base, if (is64) 24 else 16, is64)
            val size = field(base, if (is64) 32 else 20, is64)
            out += Item(
                name = if (strtab != null) cstr(strtab, nameOff) else "",
                type = type,
                flags = flags,
                addr = addr,
                offset = offset,
                size = size,
            )
        }
        return out
    }

    private fun cstr(b: ByteArray, from: Int): String {
        if (from < 0 || from >= b.size) return ""
        var end = from
        while (end < b.size && end - from < 256 && b[end] != 0.toByte()) end++
        return String(b, from, end - from, Charsets.UTF_8)
    }

    private fun u32(b: ByteArray, at: Int, little: Boolean): Long {
        if (at < 0 || at + 4 > b.size) return 0L
        var v = 0L
        for (k in 0 until 4) {
            val shift = if (little) 8 * k else 8 * (3 - k)
            v = v or ((b[at + k].toLong() and 0xFF) shl shift)
        }
        return v
    }

    private fun u64(b: ByteArray, at: Int, little: Boolean): Long {
        if (at < 0 || at + 8 > b.size) return 0L
        var v = 0L
        for (k in 0 until 8) {
            val shift = if (little) 8 * k else 8 * (7 - k)
            v = v or ((b[at + k].toLong() and 0xFF) shl shift)
        }
        return v
    }
}
