package com.soreverse.mcp.core

import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * 加密/Random-XOR 的 DEX 扫描与还原。
 *
 * 场景：壳把 dex 加密后藏在 so/assets/内存里，静态看不到；常见的三种藏法
 *  1. 明文直接嵌在某个二进制里（plain）；
 *  2. 单字节 XOR（0x00..0xFF 某个 key）；
 *  3. 重复 key XOR（2..8 字节 key，用 `dex\n035\0` 前 8 字节反推）。
 *
 * 判定不靠"看起来像"，而是逐层校验 dex 结构：
 * magic → 版本号 → header_size=0x70 → endian_tag=0x12345678 →
 * file_size 落在合理区间 → **header_checksum(adler32)** 与 **signature(sha1)** 双校验。
 * 双校验都过的才算 hit，避免把随机数据误判成 dex。
 */
object XorDexScanner {

    /** 一个候选。 */
    data class Found(
        val offset: Int,
        val mode: String,
        val keyHex: String,
        val version: String,
        val size: Int,
        val strongValid: Boolean,
        val note: String,
    )

    /** 已还原出的 dex 字节。 */
    data class Extracted(val hit: Found, val bytes: ByteArray)

    private val MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0A) // dex\n
    private const val HEADER_SIZE = 112
    private const val MAX_DEX_BYTES = 256L * 1024 * 1024

    /**
     * 扫描 [data]。[modes] 可选 plain / xor1 / xorn。
     * 返回去重后的候选（同一偏移同一模式只留一条，强校验通过优先）。
     */
    fun scan(
        data: ByteArray,
        modes: Set<String> = setOf("plain", "xor1", "xorn"),
        maxHits: Int = 32,
    ): List<Found> {
        if (data.size < HEADER_SIZE) return emptyList()
        val out = ArrayList<Found>()
        val seen = HashSet<String>()
        if (modes.contains("plain")) {
            var i = 0
            while (i <= data.size - 4) {
                if (matchesAt(data, i, null)) {
                    val f = evaluate(data, i, "plain", "")
                    if (f != null && seen.add("plain|$i")) out.add(f)
                    if (out.size >= maxHits) return out
                }
                i++
            }
        }
        if (modes.contains("xor1") || modes.contains("xorn")) {
            var i = 0
            while (i <= data.size - HEADER_SIZE) {
                // 由 magic 反推 key：第 0 字节决定单字节 key
                val k0 = (data[i].toInt() xor MAGIC[0].toInt()) and 0xFF
                // key=0 等价于明文，plain 模式已经报过；不跳过会让同一段数据重复出一条
                // "xor1 key=00" 的伪候选（扫描工具里多一条噪音，链路里会重复落盘同一个 dex）。
                if (modes.contains("xor1") && k0 != 0) {
                    // 单字节 key 直接内联判断，避免每字节分配一个 key 数组（要扫的是几十 MB 的 dump）
                    if (matchesAtOneByte(data, i, k0)) {
                        val f = evaluate(data, i, "xor1", HEX_DIGITS[(k0 ushr 4) and 0xF].toString() + HEX_DIGITS[k0 and 0xF])
                        if (f != null && seen.add("xor1|$i")) {
                            out.add(f)
                            if (out.size >= maxHits) return out
                        }
                    }
                }
                if (modes.contains("xorn")) {
                    // key 长度 2..4：**上限就是 magic 的长度**。反推 key 的第 j 字节靠的是
                    // magic[j]，n>4 时 MAGIC[j] 直接数组越界（真机上表现为整个扫描抛
                    // ArrayIndexOutOfBounds，被上层 catch 成「扫描失败」，等于 xorN 完全不可用）。
                    // 长度 5..8 的 key 无法用 "dex\n" 反推，只能靠 body 结构暴力验证，代价不值得。
                    val keyBuf = ByteArray(MAGIC.size)
                    for (n in 2..MAGIC.size) {
                        var ok = true
                        for (j in 0 until n) {
                            keyBuf[j] = (data[i + j].toInt() xor MAGIC[j].toInt()).toByte()
                        }
                        for (j in n until MAGIC.size) {
                            val v = (data[i + j].toInt() xor keyBuf[j % n].toInt()) and 0xFF
                            if (v != (MAGIC[j].toInt() and 0xFF)) {
                                ok = false
                                break
                            }
                        }
                        if (!ok) continue
                        // 全同字节的情况已由 xor1 覆盖，跳过免得重复
                        if ((0 until n).all { keyBuf[it] == keyBuf[0] }) continue
                        val f = evaluate(data, i, "xorN", hex(keyBuf.copyOfRange(0, n)))
                        if (f != null && seen.add("xorN|$i")) {
                            out.add(f)
                            if (out.size >= maxHits) return out
                        }
                        break
                    }
                }
                i++
            }
        }
        return out.sortedWith(compareByDescending<Found> { it.strongValid }.thenBy { it.offset })
    }

    /** 把某个命中还原成完整 dex 字节（按 file_size 截断、去掉尾部填充）。 */
    fun extract(data: ByteArray, hit: Found): ByteArray? {
        val key = hexToBytes(hit.keyHex)
        val size = hit.size
        if (size <= 0 || hit.offset + size > data.size) return null
        val out = ByteArray(size)
        if (key.isEmpty()) {
            System.arraycopy(data, hit.offset, out, 0, size)
        } else {
            for (i in 0 until size) {
                out[i] = (data[hit.offset + i].toInt() xor key[i % key.size].toInt()).toByte()
            }
        }
        return out
    }

    // ──────────────── 内部 ────────────────

    private const val HEX_DIGITS = "0123456789abcdef"

    /** 单字节 key 的快速匹配（不分配数组）。 */
    private fun matchesAtOneByte(data: ByteArray, i: Int, k: Int): Boolean {
        if (i + MAGIC.size > data.size) return false
        for (j in MAGIC.indices) {
            if (((data[i + j].toInt() xor k) and 0xFF) != (MAGIC[j].toInt() and 0xFF)) return false
        }
        return true
    }

    /** [i] 处（用 [key] 解，key 为 null 表示明文）是否以 "dex\n" 开头。 */
    private fun matchesAt(data: ByteArray, i: Int, key: ByteArray?): Boolean {
        if (i + MAGIC.size > data.size) return false
        for (j in MAGIC.indices) {
            val v = if (key == null || key.isEmpty()) data[i + j].toInt()
            else (data[i + j].toInt() xor key[j % key.size].toInt())
            if ((v and 0xFF) != (MAGIC[j].toInt() and 0xFF)) return false
        }
        return true
    }

    /**
     * 读 header 做校验。前 112 字节按 key 解开后判断：
     * 版本号 / header_size / endian_tag / file_size 区间；再对整段做 adler32 + sha1。
     */
    private fun evaluate(data: ByteArray, offset: Int, mode: String, keyHex: String): Found? {
        val key = hexToBytes(keyHex)
        val head = ByteArray(HEADER_SIZE)
        if (offset + HEADER_SIZE > data.size) return null
        for (i in 0 until HEADER_SIZE) {
            head[i] = if (key.isEmpty()) data[offset + i]
            else (data[offset + i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        // magic
        for (j in MAGIC.indices) {
            if ((head[j].toInt() and 0xFF) != (MAGIC[j].toInt() and 0xFF)) return null
        }
        // 版本 "035".."039"
        val v0 = head[4].toInt() and 0xFF
        val v1 = head[5].toInt() and 0xFF
        val v2 = head[6].toInt() and 0xFF
        if (v0 != 0x30) return null
        if (v1 !in 0x30..0x39 || v2 !in 0x30..0x39) return null
        val version = "" + v0.toChar() + v1.toChar() + v2.toChar()
        // header_size / endian_tag
        val headerSize = u32(head, 36)
        if (headerSize != 0x70L) return null
        val endian = u32(head, 40)
        if (endian != 0x12345678L && endian != 0x78563412L) return null
        // file_size
        val fileSize = u32(head, 32)
        if (fileSize < HEADER_SIZE || fileSize > MAX_DEX_BYTES) return null
        if (offset.toLong() + fileSize > data.size.toLong()) {
            return Found(offset, mode, keyHex, version, fileSize.toInt(), false, "file_size 超出可用数据（被截断的 dump？）")
        }
        val size = fileSize.toInt()
        val body = ByteArray(size)
        if (key.isEmpty()) {
            System.arraycopy(data, offset, body, 0, size)
        } else {
            for (i in 0 until size) {
                body[i] = (data[offset + i].toInt() xor key[i % key.size].toInt()).toByte()
            }
        }
        // 双校验
        val checksumExpect = u32(body, 8)
        val adler = Adler32()
        adler.update(body, 12, size - 12)
        val checksumActual = adler.value
        val sigExpect = body.copyOfRange(12, 32)
        val sigActual = runCatching {
            MessageDigest.getInstance("SHA-1").digest(body.copyOfRange(32, size))
        }.getOrNull()
        val checksumOk = checksumExpect == checksumActual
        val sigOk = sigActual != null && sigActual.contentEquals(sigExpect)
        val note = when {
            checksumOk && sigOk -> "校验和与签名均通过"
            sigOk -> "签名通过，校验和不是标准 adler32（被改过内容）"
            checksumOk -> "校验和通过，签名不符（被改过内容）"
            else -> "头部结构合法，但校验和与签名都不符（可能是脱壳后未修复，需 fixDex）"
        }
        return Found(offset, mode, keyHex, version, size, checksumOk && sigOk, note)
    }

    private fun u32(b: ByteArray, off: Int): Long {
        if (off + 4 > b.size) return -1
        return ((b[off].toLong() and 0xFF)) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)
    }

    fun hex(b: ByteArray): String {
        if (b.isEmpty()) return ""
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    fun hexToBytes(s: String): ByteArray {
        val t = s.trim().replace(" ", "").removePrefix("0x")
        if (t.isEmpty() || t.length % 2 != 0) return ByteArray(0)
        val out = ByteArray(t.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(t[i * 2], 16)
            val lo = Character.digit(t[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return ByteArray(0)
            out[i] = (((hi shl 4) or lo) and 0xFF).toByte()
        }
        return out
    }
}
