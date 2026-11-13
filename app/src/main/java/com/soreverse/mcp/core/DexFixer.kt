package com.soreverse.mcp.core

import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * DEX 头部修复：把 dump / 解密出来的 dex 修到能被解析器接受。
 *
 * 壳脱壳出来的 dex 常常是"内容对、头不对"：
 *  - file_size 还是原始大小，实际数据被截断或被加了一层填充；
 *  - header_checksum(adler32) 与 signature(sha1) 对不上（内容被改过 / 头被抹掉）；
 *  - endian_tag 被清零、header_size 被改。
 *
 * 这里的修复策略是"以内容为准重算头"，不猜内容：
 *  1. magic 不合法 → 直接拒绝（不是 dex，别硬修）；
 *  2. file_size 与实际数据不符 → 取两者较小值写回（多出来的当填充丢掉）；
 *  3. header_size 非法 → 写 0x70；endian_tag 非法 → 写 0x12345678；
 *  4. 先重算 sha1(signature) 再重算 adler32(checksum)（顺序不能反，checksum 覆盖 signature）。
 */
object DexFixer {

    data class Report(
        val fixed: Boolean,
        val sizeBefore: Int,
        val sizeAfter: Int,
        val checksumOkBefore: Boolean,
        val signatureOkBefore: Boolean,
        val checksumAfter: Long,
        val signatureAfter: String,
        val changes: List<String>,
        val note: String,
    )

    private val MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0A)

    /** 返回（修复后的字节, 报告）。修复失败时返回原数据 + fixed=false。 */
    fun fix(input: ByteArray): Pair<ByteArray, Report> {
        if (input.size < 112) {
            return input to Report(false, input.size, input.size, false, false, 0, "", emptyList(), "数据不足 112 字节，不是 dex 头")
        }
        for (i in MAGIC.indices) {
            if (input[i] != MAGIC[i]) {
                return input to Report(false, input.size, input.size, false, false, 0, "", emptyList(), "magic 不是 dex\\n，拒绝修复")
            }
        }
        val changes = ArrayList<String>()
        var size = input.size
        val fileSizeRaw = u32(input, 32)
        if (fileSizeRaw in 112..input.size.toLong()) {
            if (fileSizeRaw.toInt() != input.size) {
                changes.add("按 file_size 截断：${input.size} → $fileSizeRaw")
                size = fileSizeRaw.toInt()
            }
        } else if (fileSizeRaw > input.size) {
            changes.add("file_size($fileSizeRaw) 大于实际数据(${input.size})，按实际长度写回")
        }
        val body = input.copyOf(size)

        val headerSize = u32(body, 36)
        if (headerSize != 0x70L) {
            changes.add("header_size ${headerSize} → 112")
            putU32(body, 36, 0x70L)
        }
        val endian = u32(body, 40)
        if (endian != 0x12345678L && endian != 0x78563412L) {
            changes.add("endian_tag $endian → 0x12345678")
            putU32(body, 40, 0x12345678L)
        }
        if (u32(body, 32) != size.toLong()) {
            putU32(body, 32, size.toLong())
            changes.add("file_size → $size")
        }

        val checksumOkBefore = checksumOf(body) == u32(body, 8)
        val signatureOkBefore = signatureOf(body)?.contentEquals(body.copyOfRange(12, 32)) == true

        // 先签名（范围 32..size），再校验和（范围 12..size）
        val sig = signatureOf(body)
        if (sig != null) {
            System.arraycopy(sig, 0, body, 12, 20)
        }
        val adler = Adler32()
        adler.update(body, 12, size - 12)
        putU32(body, 8, adler.value)

        val checksumAfter = u32(body, 8)
        val signHex = XorDexScanner.hex(body.copyOfRange(12, 32))
        if (!checksumOkBefore) changes.add("重算 header_checksum")
        if (!signatureOkBefore) changes.add("重算 signature")
        val note = when {
            changes.isEmpty() -> "头本来就是对的，无需修改"
            checksumOkBefore && signatureOkBefore -> "只修正了尺寸/头字段"
            else -> "已重算校验和与签名；若解析仍失败，说明内容本身不完整（dump 被截断）"
        }
        return body to Report(
            fixed = true,
            sizeBefore = input.size,
            sizeAfter = body.size,
            checksumOkBefore = checksumOkBefore,
            signatureOkBefore = signatureOkBefore,
            checksumAfter = checksumAfter,
            signatureAfter = signHex,
            changes = changes,
            note = note,
        )
    }

    /**
     * 扫出数据里所有"像 dex 头"的偏移（对齐加固壳的 findHeaderOffsets 思路）：
     * magic + header_size=0x70 + endian_tag 合法三条同时成立才算。
     * 用于处理"带一截前缀 / 多个 dex 拼在一起 / 偏移错位"的 dump。
     */
    fun findDexHeaders(data: ByteArray, limit: Int = 64): List<Int> {
        val out = ArrayList<Int>()
        if (data.size < 112) return out
        var i = 0
        val end = data.size - 112
        while (i <= end && out.size < limit) {
            if (data[i] == MAGIC[0] && data[i + 1] == MAGIC[1] && data[i + 2] == MAGIC[2] && data[i + 3] == MAGIC[3]) {
                val headerSize = u32(data, i + 36)
                val endian = u32(data, i + 40)
                val fileSize = u32(data, i + 32)
                if (headerSize == 0x70L && (endian == 0x12345678L || endian == 0x78563412L) &&
                    fileSize in 112..(data.size - i).toLong()
                ) {
                    out.add(i)
                    // 跳到该 dex 末尾，避免把同一个 dex 内部的字节序列误判成新头
                    i += fileSize.toInt()
                    continue
                }
            }
            i++
        }
        return out
    }

    /** 只判断头是否自洽（不改数据）。 */
    fun inspect(data: ByteArray): Report {
        if (data.size < 112) {
            return Report(false, data.size, data.size, false, false, 0, "", emptyList(), "数据不足 112 字节")
        }
        for (i in MAGIC.indices) {
            if (data[i] != MAGIC[i]) {
                return Report(false, data.size, data.size, false, false, 0, "", emptyList(), "magic 不是 dex\\n")
            }
        }
        val fileSize = u32(data, 32)
        val limit = minOf(data.size.toLong(), if (fileSize in 112..data.size.toLong()) fileSize else data.size.toLong()).toInt()
        val view = data.copyOf(limit)
        val checksumOk = checksumOf(view) == u32(view, 8)
        val sigOk = signatureOf(view)?.contentEquals(view.copyOfRange(12, 32)) == true
        val changes = ArrayList<String>()
        if (u32(data, 36) != 0x70L) changes.add("header_size 非 112")
        if (u32(data, 40) != 0x12345678L && u32(data, 40) != 0x78563412L) changes.add("endian_tag 非法")
        if (fileSize != data.size.toLong()) changes.add("file_size($fileSize) 与实际长度(${data.size}) 不一致")
        return Report(
            fixed = false,
            sizeBefore = data.size,
            sizeAfter = limit,
            checksumOkBefore = checksumOk,
            signatureOkBefore = sigOk,
            checksumAfter = u32(view, 8),
            signatureAfter = XorDexScanner.hex(view.copyOfRange(12, 32)),
            changes = changes,
            note = when {
                checksumOk && sigOk -> "头自洽"
                else -> "头不自洽（可 fix）"
            },
        )
    }

    private fun checksumOf(view: ByteArray): Long {
        if (view.size <= 12) return -1
        val adler = Adler32()
        adler.update(view, 12, view.size - 12)
        return adler.value
    }

    private fun signatureOf(view: ByteArray): ByteArray? {
        if (view.size <= 32) return null
        return runCatching {
            MessageDigest.getInstance("SHA-1").digest(view.copyOfRange(32, view.size))
        }.getOrNull()
    }

    private fun u32(b: ByteArray, off: Int): Long {
        if (off + 4 > b.size) return -1
        return (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)
    }

    private fun putU32(b: ByteArray, off: Int, v: Long) {
        if (off + 4 > b.size) return
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }
}
