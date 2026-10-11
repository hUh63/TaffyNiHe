package com.soreverse.mcp.engine

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * ELF 变长注入器（阶段 2a/2b）。
 *
 * 目标：在**不移动任何已有字节**的前提下，让"追加的代码"被加载器映射且可执行。
 *
 * 做法（与 /sandbox/workspace/capfork/elfinject.py 原型完全一致，原型已用真实 dlopen 实证）：
 *   1. 新段 VA = alignUp(max(p_vaddr + p_memsz of PT_LOAD), pageAlign)
 *   2. 文件末尾按 pageAlign 对齐后追加代码 —— 使新 `p_offset` 与 `p_vaddr` 同余 (mod pageAlign)
 *   3. 新增 PT_LOAD(R|X) 指向新代码；新增 SHT_PROGBITS 节区 `.taffy.inject`
 *   4. phdr / shdr / shstrtab 表整体搬到文件末尾，更新 ELF 头 e_phoff / e_phnum / e_shoff / e_shnum
 *   5. 可选：把目标函数入口改写成跳转到新 VA 的 trampoline（按 ABI 编码，等长覆盖）
 *
 * pageAlign 取「现有 PT_LOAD 的最大 p_align」（>=0x1000 且为 2 的幂），这样在 4K / 16K
 * 页设备上都满足 mmap 对 offset 必须是系统页大小整倍数的要求。
 *
 * 纯 JVM 逻辑，不依赖 Android / LIEF，因此可单测、可在 CI 的 `gradlew test` 里做端到端验证。
 */
object ElfInjector {

    const val ELFCLASS32 = 1
    const val ELFCLASS64 = 2

    class InjectException(message: String) : IllegalStateException(message)

    data class Result(
        val elf: ByteArray,
        val codeVa: Long,
        val codeOffset: Int,
        val pageAlign: Long,
        val trampolineLength: Int,
        val notes: List<String>,
    )

    private class Ph(
        val type: Long, val flags: Long, val offset: Long, val vaddr: Long,
        val paddr: Long, val filesz: Long, val memsz: Long, val align: Long,
    )

    private class Sh(
        val name: Long, val type: Long, val flags: Long, val addr: Long,
        var offset: Long, var size: Long, val link: Long, val info: Long,
        val addralign: Long, val entsize: Long,
    )

    // ───────────────────────── 小端字节读写 ─────────────────────────

    private fun rd16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

    private fun rd32(b: ByteArray, o: Int): Long {
        return (b[o].toLong() and 0xff) or ((b[o + 1].toLong() and 0xff) shl 8) or
            ((b[o + 2].toLong() and 0xff) shl 16) or ((b[o + 3].toLong() and 0xff) shl 24)
    }

    private fun rd64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xff)
        return v
    }

    private fun rdX(b: ByteArray, o: Int, is64: Boolean): Long = if (is64) rd64(b, o) else rd32(b, o)

    private fun w16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xff).toByte()
        b[o + 1] = ((v ushr 8) and 0xff).toByte()
    }

    private fun w32(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 4) b[o + i] = ((v ushr (8 * i)) and 0xff).toByte()
    }

    private fun w64(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 8) b[o + i] = ((v ushr (8 * i)) and 0xff).toByte()
    }

    private fun wX(b: ByteArray, o: Int, v: Long, is64: Boolean) = if (is64) w64(b, o, v) else w32(b, o, v)

    private fun le(v: Long, n: Int): ByteArray {
        val a = ByteArray(n)
        for (i in 0 until n) a[i] = ((v ushr (8 * i)) and 0xff).toByte()
        return a
    }

    fun alignUp(v: Long, a: Long): Long = if (a <= 1L) v else ((v + a - 1L) / a) * a

    fun isElf(b: ByteArray): Boolean =
        b.size >= 16 && b[0] == 0x7f.toByte() &&
            b[1] == 'E'.code.toByte() && b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte()

    fun elfClass(b: ByteArray): Int = if (isElf(b)) b[4].toInt() else -1

    // ───────────────────────── 表项读写 ─────────────────────────

    private fun writePh(bos: ByteArrayOutputStream, is64: Boolean, p: Ph) {
        if (is64) {
            bos.write(le(p.type, 4)); bos.write(le(p.flags, 4))
            bos.write(le(p.offset, 8)); bos.write(le(p.vaddr, 8)); bos.write(le(p.paddr, 8))
            bos.write(le(p.filesz, 8)); bos.write(le(p.memsz, 8)); bos.write(le(p.align, 8))
        } else {
            bos.write(le(p.type, 4)); bos.write(le(p.offset, 4)); bos.write(le(p.vaddr, 4)); bos.write(le(p.paddr, 4))
            bos.write(le(p.filesz, 4)); bos.write(le(p.memsz, 4)); bos.write(le(p.flags, 4)); bos.write(le(p.align, 4))
        }
    }

    private fun writeSh(bos: ByteArrayOutputStream, is64: Boolean, sh: Sh) {
        if (is64) {
            bos.write(le(sh.name, 4)); bos.write(le(sh.type, 4)); bos.write(le(sh.flags, 8)); bos.write(le(sh.addr, 8))
            bos.write(le(sh.offset, 8)); bos.write(le(sh.size, 8)); bos.write(le(sh.link, 4)); bos.write(le(sh.info, 4))
            bos.write(le(sh.addralign, 8)); bos.write(le(sh.entsize, 8))
        } else {
            bos.write(le(sh.name, 4)); bos.write(le(sh.type, 4)); bos.write(le(sh.flags, 4)); bos.write(le(sh.addr, 4))
            bos.write(le(sh.offset, 4)); bos.write(le(sh.size, 4)); bos.write(le(sh.link, 4)); bos.write(le(sh.info, 4))
            bos.write(le(sh.addralign, 4)); bos.write(le(sh.entsize, 4))
        }
    }

    private fun parsePhdrs(elf: ByteArray, is64: Boolean, off: Long, num: Int, entsize: Int): List<Ph> {
        val out = ArrayList<Ph>(num)
        for (i in 0 until num) {
            val o = (off + i.toLong() * entsize).toInt()
            if (o < 0 || o + entsize > elf.size) throw InjectException("phdr#$i 越界")
            out += if (is64) {
                Ph(rd32(elf, o), rd32(elf, o + 4), rd64(elf, o + 8), rd64(elf, o + 16),
                    rd64(elf, o + 24), rd64(elf, o + 32), rd64(elf, o + 40), rd64(elf, o + 48))
            } else {
                Ph(rd32(elf, o), rd32(elf, o + 24), rd32(elf, o + 4), rd32(elf, o + 8),
                    rd32(elf, o + 12), rd32(elf, o + 16), rd32(elf, o + 20), rd32(elf, o + 28))
            }
        }
        return out
    }

    private fun parseShdrs(elf: ByteArray, is64: Boolean, off: Long, num: Int, entsize: Int): List<Sh> {
        val out = ArrayList<Sh>(num)
        for (i in 0 until num) {
            val o = (off + i.toLong() * entsize).toInt()
            if (o < 0 || o + entsize > elf.size) throw InjectException("shdr#$i 越界")
            out += if (is64) {
                Sh(rd32(elf, o), rd32(elf, o + 4), rd64(elf, o + 8), rd64(elf, o + 16),
                    rd64(elf, o + 24), rd64(elf, o + 32), rd32(elf, o + 40), rd32(elf, o + 44),
                    rd64(elf, o + 48), rd64(elf, o + 56))
            } else {
                Sh(rd32(elf, o), rd32(elf, o + 4), rd32(elf, o + 8), rd32(elf, o + 12),
                    rd32(elf, o + 16), rd32(elf, o + 20), rd32(elf, o + 24), rd32(elf, o + 28),
                    rd32(elf, o + 32), rd32(elf, o + 36))
            }
        }
        return out
    }

    private fun vaToOffset(loads: List<Ph>, va: Long): Int? {
        for (p in loads) {
            if (va >= p.vaddr && va < p.vaddr + p.filesz) return (p.offset + (va - p.vaddr)).toInt()
        }
        return null
    }

    // ───────────────────────── 主入口 ─────────────────────────

    /**
     * 注入 [code] 到 [source]，返回新 ELF。
     *
     * @param trampolineAtVa >=0 时，把该 VA 处的字节改写成跳转到新代码的 trampoline（按 [arch] 编码）
     * @param arch "x86_64" / "x86" / "arm64" / "arm32" / "thumb"
     */
    fun inject(source: ByteArray, code: ByteArray, trampolineAtVa: Long = -1L, arch: String = ""): Result {
        if (!isElf(source)) throw InjectException("不是 ELF 文件")
        val cls = source[4].toInt()
        val is64 = when (cls) {
            ELFCLASS64 -> true
            ELFCLASS32 -> false
            else -> throw InjectException("未知 ELF class: $cls")
        }
        if (source[5].toInt() != 1) throw InjectException("仅支持小端 ELF（EI_DATA=1）")
        if (code.isEmpty()) throw InjectException("注入代码为空")
        if (source.size < 64) throw InjectException("ELF 头不完整")

        val ePhOffPos = if (is64) 0x20 else 0x1C
        val eShOffPos = if (is64) 0x28 else 0x20
        val ePhEntPos = if (is64) 0x36 else 0x2A
        val ePhNumPos = if (is64) 0x38 else 0x2C
        val eShEntPos = if (is64) 0x3A else 0x2E
        val eShNumPos = if (is64) 0x3C else 0x30
        val eShStrPos = if (is64) 0x3E else 0x32

        val ePhOff = rdX(source, ePhOffPos, is64)
        val eShOff = rdX(source, eShOffPos, is64)
        val ePhEnt = rd16(source, ePhEntPos)
        val ePhNum = rd16(source, ePhNumPos)
        val eShEnt = rd16(source, eShEntPos)
        val eShNum = rd16(source, eShNumPos)
        val eShStrNdx = rd16(source, eShStrPos)

        val phdrs = parsePhdrs(source, is64, ePhOff, ePhNum, ePhEnt)
        val shdrs = parseShdrs(source, is64, eShOff, eShNum, eShEnt)

        val loads = phdrs.filter { it.type == 1L }
        if (loads.isEmpty()) throw InjectException("没有 PT_LOAD，无法选择可执行段")

        val rawAlign = loads.maxOf { it.align }
        val pageAlign = if (rawAlign < 0x1000L || (rawAlign and (rawAlign - 1L)) != 0L) 0x1000L else rawAlign
        val codeVa = alignUp(loads.maxOf { it.vaddr + it.memsz }, pageAlign)

        // 组装新文件：原文 + [页对齐] + 代码 + 新 shstrtab + 新 shdr 表 + 新 phdr 表
        val bos = ByteArrayOutputStream(source.size + code.size + 4096)
        bos.write(source, 0, source.size)
        val pad = ((pageAlign - (bos.size().toLong() % pageAlign)) % pageAlign).toInt()
        for (i in 0 until pad) bos.write(0)
        val codeOffset = bos.size()
        bos.write(code, 0, code.size)

        val shstrtab = shdrs.getOrNull(eShStrNdx) ?: throw InjectException("无 shstrtab（e_shstrndx=$eShStrNdx）")
        val newName = ".taffy.inject\u0000".toByteArray(StandardCharsets.US_ASCII)
        val newShStrOff = bos.size()
        if (shstrtab.size > 0L && shstrtab.offset + shstrtab.size <= source.size) {
            bos.write(source, shstrtab.offset.toInt(), shstrtab.size.toInt())
        }
        bos.write(newName, 0, newName.size)
        val newShStrSize = shstrtab.size + newName.size
        val newSecNameOff = shstrtab.size

        val newShdrOff = bos.size()
        for (i in shdrs.indices) {
            val sh = shdrs[i]
            if (i == eShStrNdx) {
                sh.offset = newShStrOff.toLong()
                sh.size = newShStrSize
            }
            writeSh(bos, is64, sh)
        }
        // 新增节区：SHT_PROGBITS(1) / SHF_ALLOC(0x2)|SHF_EXECINSTR(0x4)
        writeSh(bos, is64, Sh(newSecNameOff, 1L, 0x6L, codeVa, codeOffset.toLong(), code.size.toLong(), 0L, 0L, 4L, 0L))

        val newPhdrOff = bos.size()
        for (p in phdrs) writePh(bos, is64, p)
        // 新增段：PT_LOAD(1) / PF_R(4)|PF_X(1)
        writePh(bos, is64, Ph(1L, 5L, codeOffset.toLong(), codeVa, codeVa, code.size.toLong(), code.size.toLong(), pageAlign))

        val out = bos.toByteArray()
        wX(out, ePhOffPos, newPhdrOff.toLong(), is64)
        w16(out, ePhNumPos, ePhNum + 1)
        wX(out, eShOffPos, newShdrOff.toLong(), is64)
        w16(out, eShNumPos, eShNum + 1)

        val notes = ArrayList<String>()
        var trampLen = 0
        if (trampolineAtVa >= 0L) {
            val fo = vaToOffset(loads, trampolineAtVa)
                ?: throw InjectException("入口 VA 0x${java.lang.Long.toHexString(trampolineAtVa)} 不在任何 PT_LOAD(含 filesz) 内")
            val tb = encodeTrampoline(trampolineAtVa, codeVa, arch)
            if (fo < 0 || fo + tb.size > source.size) throw InjectException("trampoline 写入越界")
            System.arraycopy(tb, 0, out, fo, tb.size)
            trampLen = tb.size
            notes += "trampoline @VA 0x${java.lang.Long.toHexString(trampolineAtVa)} -> 0x${java.lang.Long.toHexString(codeVa)} (${tb.size}B, $arch)"
        }
        notes += "PT_LOAD(R+X) off=0x${Integer.toHexString(codeOffset)} va=0x${java.lang.Long.toHexString(codeVa)} size=${code.size}"
        notes += "pageAlign=0x${java.lang.Long.toHexString(pageAlign)} size ${source.size} -> ${out.size}"
        return Result(out, codeVa, codeOffset, pageAlign, trampLen, notes)
    }

    // ───────────────────────── trampoline 编码 ─────────────────────────

    /** 生成从 [fromVa] 跳到 [toVa] 的等长跳转指令（按 ABI）。 */
    fun encodeTrampoline(fromVa: Long, toVa: Long, arch: String): ByteArray =
        when (arch.lowercase()) {
            "x86_64", "amd64", "x86", "i386", "i686" -> {
                val rel = toVa - (fromVa + 5)
                if (rel < Int.MIN_VALUE.toLong() || rel > Int.MAX_VALUE.toLong()) {
                    throw InjectException("x86 rel32 超出 ±2GB 范围: $rel")
                }
                byteArrayOf(0xE9.toByte(), (rel and 0xff).toByte(), ((rel ushr 8) and 0xff).toByte(),
                    ((rel ushr 16) and 0xff).toByte(), ((rel ushr 24) and 0xff).toByte())
            }
            "arm64", "aarch64" -> {
                val d = toVa - fromVa
                if (d % 4L != 0L) throw InjectException("arm64 跳转目标需 4 字节对齐")
                val imm = d shr 2
                if (imm < -(1L shl 25) || imm > (1L shl 25) - 1L) throw InjectException("arm64 B 超出 ±128MB")
                le(0x14000000L or (imm and 0x03FFFFFFL), 4)
            }
            "thumb" -> encodeThumbBw(fromVa and -2L, toVa and -2L)
            "arm32", "arm" -> {
                if ((fromVa and 1L) != 0L) encodeThumbBw(fromVa and -2L, toVa and -2L)
                else encodeArmB(fromVa and -2L, toVa and -2L)
            }
            else -> throw InjectException("未知架构: $arch")
        }

    /** ARM(32) 模式 B：`0xEA000000 | ((target-(pc+8))>>2 & 0xFFFFFF)`。 */
    private fun encodeArmB(from: Long, to: Long): ByteArray {
        val d = to - (from + 8)
        if (d % 4L != 0L) throw InjectException("arm 跳转目标需 4 字节对齐")
        val imm = d shr 2
        if (imm < -(1L shl 23) || imm > (1L shl 23) - 1L) throw InjectException("arm B 超出 ±32MB")
        return le(0xEA000000L or (imm and 0x00FFFFFFL), 4)
    }

    /** Thumb-2 `B.W`(T4)：`11110 S imm10` + `10 J1 1 J2 imm11`，PC = from+4。 */
    private fun encodeThumbBw(from: Long, to: Long): ByteArray {
        val off = to - (from + 4)
        if (off % 2L != 0L) throw InjectException("thumb 跳转目标需 2 字节对齐")
        if (off < -(1L shl 24) || off >= (1L shl 24)) throw InjectException("thumb B.W 超出 ±16MB")
        val s = (off shr 24) and 1L
        val i1 = (off shr 23) and 1L
        val i2 = (off shr 22) and 1L
        val imm10 = (off shr 12) and 0x3FFL
        val imm11 = (off shr 1) and 0x7FFL
        val j1 = (i1 xor s) xor 1L
        val j2 = (i2 xor s) xor 1L
        val hw1 = 0xF000L or (s shl 10) or imm10
        val hw2 = 0x8000L or (j1 shl 13) or 0x1000L or (j2 shl 11) or imm11
        return le(hw1, 2) + le(hw2, 2)
    }

    // ───────────────────────── 符号查找（供测试/工具定位入口） ─────────────────────────

    /** 在 .symtab 里按名字找符号 VA（找不到返回 null）。 */
    fun findSymbolVa(elf: ByteArray, name: String): Long? {
        if (!isElf(elf)) return null
        val is64 = elfClass(elf) == ELFCLASS64
        val eShOff = rdX(elf, if (is64) 0x28 else 0x20, is64)
        val eShEnt = rd16(elf, if (is64) 0x3A else 0x2E)
        val eShNum = rd16(elf, if (is64) 0x3C else 0x30)
        val shdrs = runCatching { parseShdrs(elf, is64, eShOff, eShNum, eShEnt) }.getOrNull() ?: return null
        val symtab = shdrs.firstOrNull { it.type == 2L } ?: return null
        val strtab = shdrs.getOrNull(symtab.link.toInt()) ?: return null
        val entsize = if (symtab.entsize > 0L) symtab.entsize else if (is64) 24L else 16L
        val n = (symtab.size / entsize).toInt()
        for (i in 0 until n) {
            val o = (symtab.offset + i.toLong() * entsize).toInt()
            if (o < 0 || o + entsize > elf.size) continue
            val stName = rd32(elf, o)
            val stValue = if (is64) rd64(elf, o + 8) else rd32(elf, o + 4)
            val s = (strtab.offset + stName).toInt()
            if (s < 0 || s >= elf.size) continue
            var e = s
            while (e < elf.size && elf[e].toInt() != 0) e++
            if (String(elf, s, e - s, StandardCharsets.UTF_8) == name) return stValue
        }
        return null
    }
}
