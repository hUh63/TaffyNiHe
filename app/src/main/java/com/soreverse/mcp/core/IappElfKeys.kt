package com.soreverse.mcp.core

/**
 * 从原生库（如 `libygsiyu.so`）中**启发式提取** iApp 可能使用的密钥表候选。
 *
 * 原版会精确追踪「函数对 .rodata 表的 PC 相对引用」；此处做等效的静态近似：
 * 在 `.rodata` / `.data.rel.ro*` / `.data` 里滑动窗口，挑出**高二进制度**（非可打印字节占比高、
 * 无零字节、字节多样性高）的定长窗口作为候选。纯本地启发式，命中与否由外层 `looksLikeIappPlain`
 * 明文特征门禁兜底，因此少量误报无副作用。
 */
object IappElfKeys {

    private const val KEY_LEN = 20
    private const val MAX_OUT = 8

    fun extractCandidates(soData: ByteArray, keyLen: Int = KEY_LEN, maxOut: Int = MAX_OUT): List<ByteArray> {
        if (soData.size < 64) return emptyList()
        if ((soData[0].toInt() and 0xFF) != 0x7F || (soData[1].toInt() and 0xFF) != 0x45) return emptyList()
        val is64 = (soData[4].toInt() and 0xFF) == 2
        val le = (soData[5].toInt() and 0xFF) == 1

        fun u16(o: Int): Int = if (o < 0 || o + 2 > soData.size) 0 else if (le) {
            (soData[o].toInt() and 0xFF) or ((soData[o + 1].toInt() and 0xFF) shl 8)
        } else {
            ((soData[o].toInt() and 0xFF) shl 8) or (soData[o + 1].toInt() and 0xFF)
        }
        fun u32(o: Int): Long {
            if (o < 0 || o + 4 > soData.size) return 0
            var v = 0L
            for (i in 0 until 4) {
                val b = soData[o + i].toInt() and 0xFF
                v = if (le) v or (b.toLong() shl (8 * i)) else (v shl 8) or b.toLong()
            }
            return v
        }
        fun u64(o: Int): Long {
            if (o < 0 || o + 8 > soData.size) return 0
            var v = 0L
            for (i in 0 until 8) {
                val b = soData[o + i].toInt() and 0xFF
                v = if (le) v or (b.toLong() shl (8 * i)) else (v shl 8) or b.toLong()
            }
            return v
        }

        val shoff = if (is64) u64(0x28) else u32(0x20)
        val shentsize = u16(if (is64) 0x3A else 0x2E)
        val shnum = u16(if (is64) 0x3C else 0x30)
        val shstrndx = u16(if (is64) 0x3E else 0x32)
        if (shoff <= 0 || shentsize <= 0 || shnum <= 0 || shoff + shnum.toLong() * shentsize > soData.size + 8) {
            return emptyList()
        }

        class Sec(val nameOffset: Long, val off: Long, val size: Long)
        val secs = ArrayList<Sec>(shnum)
        for (i in 0 until shnum) {
            val base = (shoff + i.toLong() * shentsize).toInt()
            if (base < 0 || base + shentsize > soData.size) break
            val nameOff = u32(base)
            val shOffset = if (is64) u64(base + 0x18) else u32(base + 0x10)
            val shSize = if (is64) u64(base + 0x20) else u32(base + 0x14)
            secs.add(Sec(nameOff, shOffset, shSize))
        }
        if (secs.isEmpty()) return emptyList()

        val shstr = secs.getOrNull(shstrndx) ?: return emptyList()
        fun secName(off: Long): String {
            var p = (shstr.off + off).toInt()
            if (p < 0 || p >= soData.size) return ""
            val sb = StringBuilder()
            while (p < soData.size && soData[p].toInt() != 0 && sb.length < 64) {
                sb.append((soData[p].toInt() and 0xFF).toChar()); p++
            }
            return sb.toString()
        }

        val out = ArrayList<ByteArray>()
        val seen = HashSet<String>()
        for (s in secs) {
            val name = secName(s.nameOffset)
            val isTarget = name == ".rodata" || name.startsWith(".data.rel.ro") || name == ".data"
            if (!isTarget) continue
            val from = s.off.toInt()
            val size = s.size.toInt()
            if (from < 0 || size < keyLen || from + size > soData.size) continue
            val to = from + size - keyLen
            val step = if (size > 65536) 4 else 2
            var p = from
            while (p <= to) {
                if (looksLikeKeyTable(soData, p, keyLen)) {
                    val cand = soData.copyOfRange(p, p + keyLen)
                    if (seen.add(cand.toHex())) out.add(cand)
                }
                p += step
            }
        }
        return out.sortedByDescending { binaryness(it) }.take(maxOut)
    }

    private fun looksLikeKeyTable(data: ByteArray, off: Int, len: Int): Boolean {
        var nonAscii = 0
        val distinct = HashSet<Int>()
        for (i in 0 until len) {
            val b = data[off + i].toInt() and 0xFF
            if (b == 0) return false
            if (b < 0x20 || b >= 0x7F) nonAscii++
            distinct.add(b)
        }
        return nonAscii >= len * 6 / 10 && distinct.size >= len * 6 / 10
    }

    private fun binaryness(bytes: ByteArray): Int {
        var score = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 0x20 || v >= 0x7F) score += 2
            score += (Integer.bitCount(v) * 1)
        }
        return score
    }
}
