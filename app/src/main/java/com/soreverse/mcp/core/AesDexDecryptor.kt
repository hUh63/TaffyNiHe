package com.soreverse.mcp.core

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES 加密 DEX 的定向解密（对应加固壳里"自定义 AES 表 + 藏 key"那一类）。
 *
 * 壳的常见做法有三层：
 *  1. 用 AES-CBC/ECB 把 dex 整段加密，key 藏在 so / assets / 字符串里；
 *  2. 把 AES 的 S-box / InvS-box / Rcon 表也搬走（甚至换成自定义表），让别人拿标准 AES 解不开；
 *  3. 解出来还要校验一段魔数 / 长度，不满足就当失败。
 *
 * 这里提供三件事：
 *  - [findTables]：在数据里找 AES 表（标准表 / 逆表 / Rcon），判断是不是被换成了自定义表；
 *  - [keyCandidates]：从数据里挑出"像 key"的 16/24/32 字节块（高熵、不重复）；
 *  - [autoDecrypt]：拿候选 key × 几种 IV × CBC/ECB 穷举，解出来就扫 dex，命中即返回。
 *
 * 「定向」的意思是：给它一段 blob 和可选的 key/iv 就能直接解；不给 key 时按候选穷举。
 */
object AesDexDecryptor {

    /** AES 表在数据里的存在情况。 */
    data class Tables(
        val hasSbox: Boolean,
        val hasInvSbox: Boolean,
        val hasRcon: Boolean,
        val sboxOffset: Int,
        val invSboxOffset: Int,
        val rconOffset: Int,
        val sboxIsStandard: Boolean,
        val note: String,
    )

    /** 一个候选 key。 */
    data class Candidate(
        val offset: Int,
        val length: Int,
        val hex: String,
        val distinctBytes: Int,
        val repeated: Boolean,
    )

    /** 自动解密的一个成功结果。 */
    data class Hit(
        val keyHex: String,
        val ivHex: String,
        val mode: String,
        val entry: XorDexScanner.Found,
        val bytes: ByteArray,
    )

    /** 标准 AES S-box 前 32 字节（长度足够唯一识别，不用拷整张表）。 */
    private val SBOX_SIG = byteArrayOf(
        0x63, 0x7C.toByte(), 0x77, 0x7B, 0xF2.toByte(), 0x6B, 0x6F, 0xC5.toByte(),
        0x30, 0x01, 0x67, 0x2B, 0xFE.toByte(), 0xD7.toByte(), 0xAB.toByte(), 0x76,
        0xCA.toByte(), 0x82.toByte(), 0xC9.toByte(), 0x7D, 0xFA.toByte(), 0x59, 0x47, 0xF0.toByte(),
        0xAD.toByte(), 0xD4.toByte(), 0xA2.toByte(), 0xAF.toByte(), 0x9C.toByte(), 0xA4.toByte(), 0x72, 0xC0.toByte(),
    )

    /** 标准逆 S-box 前 32 字节。 */
    private val INV_SBOX_SIG = byteArrayOf(
        0x52, 0x09, 0x6A, 0xD5.toByte(), 0x30, 0x36, 0xA5.toByte(), 0x38,
        0xBF.toByte(), 0x40, 0xA3.toByte(), 0x9E.toByte(), 0x81.toByte(), 0xF3.toByte(), 0xD7.toByte(), 0xFB.toByte(),
        0x7C, 0xE3.toByte(), 0x39, 0x82.toByte(), 0x9B.toByte(), 0x2F, 0xFF.toByte(), 0x87.toByte(),
        0x34, 0x8E.toByte(), 0x43, 0x44, 0xC4.toByte(), 0xDE.toByte(), 0xE9.toByte(), 0xCB.toByte(),
    )

    /** 标准 Rcon 前 16 字节。 */
    private val RCON_SIG = byteArrayOf(
        0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80.toByte(),
        0x1B, 0x36, 0x6C, 0xD8.toByte(), 0xAB.toByte(), 0x4D, 0x9A.toByte(), 0x00,
    )

    /** 自动穷举时先只解前 1MB 做判断（dex 头通常在开头附近），命中后再解全量。 */
    private const val PROBE_BYTES = 128 * 1024

    // ──────────────── 表识别 ────────────────

    /** 在数据里找 AES 表；同时给出"表在但前面 32 字节不是标准值"的判断（= 自定义表）。 */
    fun findTables(data: ByteArray): Tables {
        val sbox = indexOf(data, SBOX_SIG)
        val inv = indexOf(data, INV_SBOX_SIG)
        val rcon = indexOf(data, RCON_SIG)
        val hasAny = sbox >= 0 || inv >= 0 || rcon >= 0
        val note = when {
            sbox >= 0 && inv >= 0 && rcon >= 0 ->
                "标准 AES 表齐全（S-box/InvS-box/Rcon 都在）：可以直接用标准 AES 解，key 藏在附近"
            sbox >= 0 || inv >= 0 ->
                "只找到部分 AES 表：可能是自定义表或被切分存放，标准 AES 解不开，需要从运行时 dump 表"
            rcon >= 0 ->
                "只有 Rcon：AES 实现存在但表被别的实现复用"
            hasAny -> ""
            else -> "没有标准 AES 表特征：要么是自定义表（完全重写），要么压根不是 AES 方案"
        }
        return Tables(
            hasSbox = sbox >= 0,
            hasInvSbox = inv >= 0,
            hasRcon = rcon >= 0,
            sboxOffset = sbox,
            invSboxOffset = inv,
            rconOffset = rcon,
            sboxIsStandard = sbox >= 0,
            note = note,
        )
    }

    // ──────────────── key 候选 ────────────────

    /**
     * 从数据里挑"像 key"的块：16/24/32 字节，字节种类足够多（>= 长度*0.6），
     * 且在整段数据里只出现一次（重复的通常是表/常量，不是 key）。
     */
    fun keyCandidates(data: ByteArray, limit: Int = 48): List<Candidate> {
        if (data.size < 16) return emptyList()
        // 先一趟统计"前 4MB 里 16 字节块"的重复情况（O(n)），
        // 原来对每个候选都全文件搜一遍是 O(n²)，512MB 的 dump 会跑到几十分钟。
        val scanLimit = minOf(data.size, 4 * 1024 * 1024)
        val freq = HashMap<Long, Int>()
        var p = 0
        while (p + 16 <= scanLimit) {
            val h = blockHash(data, p, 16)
            freq[h] = (freq[h] ?: 0) + 1
            p += 16
        }
        val out = ArrayList<Candidate>()
        val seen = HashSet<String>()
        var offset = 0
        while (offset + 16 <= data.size && out.size < limit * 4) {
            for (len in intArrayOf(16, 32, 24)) {
                if (offset + len > data.size) continue
                val distinct = distinctBytes(data, offset, len)
                if (distinct < len * 6 / 10) continue
                val block = data.copyOfRange(offset, offset + len)
                val h = XorDexScanner.hex(block)
                if (!seen.add(h)) continue
                val repeated = len == 16 && (freq[blockHash(data, offset, 16)] ?: 1) > 1
                out.add(Candidate(offset, len, h, distinct, repeated))
                break
            }
            offset += 4
        }
        // 排序策略（按重要性）：
        //  1. 不重复的优先——重复出现的块多半是表/常量，不是 key；
        //  2. 16 字节优先（AES-128 最常见），再 32，最后 24；
        //  3. 靠前的偏移优先——key 通常在常量区/开头，密文在中间；
        //  4. 字节种类多的优先。
        // 注意别把 distinctBytes 放前面：加密数据区的任意 16 字节也都是满熵，
        // 会把它自己顶到前面，把真正的 key 挤出候选窗口。
        return out.sortedWith(
            compareBy<Candidate> { it.repeated }
                .thenBy { when (it.length) { 16 -> 0; 32 -> 1; else -> 2 } }
                .thenBy { it.offset }
                .thenByDescending { it.distinctBytes }
        ).take(limit)
    }

    /** 16 字节块的轻量 hash（FNV-1a），只用于重复度统计。 */
    private fun blockHash(data: ByteArray, offset: Int, len: Int): Long {
        var h = -0x340d631b7bdddcdbL // FNV offset basis
        for (i in offset until minOf(offset + len, data.size)) {
            h = h xor (data[i].toLong() and 0xFF)
            h *= 0x100000001b3L
        }
        return h
    }

    // ──────────────── 解密 ────────────────

    fun decryptCbc(data: ByteArray, key: ByteArray, iv: ByteArray, unpad: Boolean): ByteArray? = runCatching {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        val ivSpec = IvParameterSpec(if (iv.size >= 16) iv.copyOfRange(0, 16) else ByteArray(16))
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), ivSpec)
        val out = cipher.doFinal(padToBlock(data, 16))
        if (unpad) unpadPkcs7(out) else out
    }.getOrNull()

    fun decryptEcb(data: ByteArray, key: ByteArray, unpad: Boolean): ByteArray? = runCatching {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        val out = cipher.doFinal(padToBlock(data, 16))
        if (unpad) unpadPkcs7(out) else out
    }.getOrNull()

    /**
     * 穷举：候选 key × IV 变体（全 0 / key 本身 / key 后 16 字节）× CBC/ECB。
     * 只解前 [PROBE_BYTES]（dex 头通常在开头附近），命中后再解全量。
     */
    fun autoDecrypt(data: ByteArray, limit: Int = 12): List<Hit> {
        val cands = keyCandidates(data, limit)
        if (cands.isEmpty()) return emptyList()
        val probe = if (data.size > PROBE_BYTES) data.copyOfRange(0, PROBE_BYTES) else data
        val hits = ArrayList<Hit>()
        for (c in cands) {
            val key = XorDexScanner.hexToBytes(c.hex)
            if (key.size !in intArrayOf(16, 24, 32)) continue
            // 密文起点不确定：key 后面可能紧跟密文，也可能隔着一小段 header/填充。
            // 前 256 字节按 16 字节步进全试一遍（探测段只有 128KB，17 个起点 × 6 种组合
            // 每个候选约 13MB 解密量，秒级可接受），命中后再解全量。
            val starts = LinkedHashSet<Int>()
            var st = 0
            val maxStart = minOf(256, probe.size - 16)
            while (st <= maxStart) {
                if (st + 16 <= probe.size) starts.add(st)
                st += 16
            }
            if (starts.isEmpty()) starts.add(0)
            for (start in starts) {
                val region = if (start == 0) probe else probe.copyOfRange(start, probe.size)
                val ivs = ArrayList<Pair<String, ByteArray>>()
                ivs.add("00000000000000000000000000000000" to ByteArray(16))
                if (key.size >= 16) ivs.add(XorDexScanner.hex(key.copyOfRange(0, 16)) to key.copyOfRange(0, 16))
                for ((ivHex, iv) in ivs) {
                    for (mode in listOf("cbc", "ecb")) {
                        val maybe: ByteArray? = if (mode == "cbc") decryptCbc(region, key, iv, false)
                        else decryptEcb(region, key, false)
                        // 注意：`if/else` 与 `?:` 的优先级，写成一行时 ?: 只绑定 else 分支
                        val plain: ByteArray = maybe ?: continue
                        val found = XorDexScanner.scan(plain, setOf("plain"), 1).firstOrNull() ?: continue
                        // 命中后再解全量，保证写出的 dex 完整
                        val fullRegion = if (start == 0) data else data.copyOfRange(start, data.size)
                        val full: ByteArray = if (fullRegion.size == region.size) plain
                        else (if (mode == "cbc") decryptCbc(fullRegion, key, iv, false)
                        else decryptEcb(fullRegion, key, false)) ?: plain
                        val fullFound = if (full === plain) found
                        else XorDexScanner.scan(full, setOf("plain"), 1).firstOrNull() ?: found
                        val dexBytes = XorDexScanner.extract(full, fullFound) ?: continue
                        hits.add(Hit(c.hex, ivHex, mode + (if (start > 0) "@$start" else ""), fullFound, dexBytes))
                        if (hits.size >= 8) return hits
                    }
                }
            }
        }
        return hits
    }

    // ──────────────── 工具函数 ────────────────

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int = indexOf(hay, needle, 0)

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || hay.size < needle.size) return -1
        val first = needle[0]
        var i = from.coerceAtLeast(0)
        val end = hay.size - needle.size
        while (i <= end) {
            if (hay[i] == first) {
                var j = 1
                while (j < needle.size && hay[i + j] == needle[j]) j++
                if (j == needle.size) return i
            }
            i++
        }
        return -1
    }

    private fun distinctBytes(data: ByteArray, offset: Int, len: Int): Int {
        val seen = BooleanArray(256)
        var n = 0
        for (i in offset until offset + len) {
            val v = data[i].toInt() and 0xFF
            if (!seen[v]) {
                seen[v] = true
                n++
            }
        }
        return n
    }

    private fun padToBlock(data: ByteArray, block: Int): ByteArray {
        val rem = data.size % block
        if (rem == 0) return data
        return data.copyOf(data.size + (block - rem))
    }

    private fun unpadPkcs7(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val pad = data[data.size - 1].toInt() and 0xFF
        if (pad !in 1..16 || pad > data.size) return data
        for (i in data.size - pad until data.size) {
            if ((data[i].toInt() and 0xFF) != pad) return data
        }
        return data.copyOfRange(0, data.size - pad)
    }
}
