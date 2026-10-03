package com.soreverse.mcp.core

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * iApp v3 加密包（assets/lib.so）解密所需的密码学原语。
 *
 * 移植自 aiysss/iapp-decrypt（MIT）：`slky` 自定义哈希 + AES-128-CBC(+循环 XOR)。
 * 全部为纯 JVM 实现，可在单元测试中直接运行。
 */
object IappCrypto {

    /** 无符号字节 0..255 -> 有符号 -128..127。 */
    fun toSigned(value: Int): Int = if (value < 128) value else value - 256

    /** 截断向零除法（与原生 C 整数除法一致）。 */
    fun signedDiv(a: Int, b: Int): Int {
        require(b != 0) { "division by zero" }
        // Kotlin 的 `/` 本身就是「截断向零」除法，与原生 C 一致；
        // 原版 Python 需 +1 是因为 Python `//` 向下取整，Kotlin 不需要。
        return a / b
    }

    /** 截断取余，符号随被除数。 */
    fun signedMod(a: Int, b: Int): Int = a - signedDiv(a, b) * b

    fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    fun md5Hex(text: String): String = md5(text.toByteArray(Charsets.UTF_8)).toHex()

    fun cyclicXor(data: ByteArray, key: ByteArray): ByteArray {
        require(key.isNotEmpty()) { "empty xor key" }
        val out = ByteArray(data.size)
        for (i in data.indices) out[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        return out
    }

    /** AES-128-CBC 解密（密钥与 IV 都取 key[:16]），不处理填充。 */
    fun aesCbcDecrypt(cipher: ByteArray, key: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        val k = key.copyOfRange(0, 16)
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(k))
        return c.doFinal(cipher)
    }

    fun pkcs5Unpad(data: ByteArray): ByteArray {
        if (data.isEmpty()) throw IllegalArgumentException("invalid PKCS5 padding: empty buffer")
        val pad = data[data.size - 1].toInt() and 0xFF
        if (pad == 0 || pad > 16) throw IllegalArgumentException("invalid PKCS5 padding length: $pad")
        for (i in data.size - pad until data.size) {
            if ((data[i].toInt() and 0xFF) != pad) throw IllegalArgumentException("invalid PKCS5 padding bytes")
        }
        return data.copyOfRange(0, data.size - pad)
    }

    /** AES-CBC 解密 -> 去填充 -> 与 key 循环异或。 */
    fun aesCbcThenXorDecrypt(cipher: ByteArray, key: ByteArray): ByteArray {
        require(cipher.size % 16 == 0) { "ciphertext is not block aligned" }
        return cyclicXor(pkcs5Unpad(aesCbcDecrypt(cipher, key)), key)
    }

    /** AES-CBC 解密 -> 去填充（不做异或，transitional 族用）。 */
    fun aesCbcDecryptOnly(cipher: ByteArray, key: ByteArray): ByteArray {
        require(cipher.size % 16 == 0) { "ciphertext is not block aligned" }
        return pkcs5Unpad(aesCbcDecrypt(cipher, key))
    }

    /**
     * `slky` 自定义哈希：有符号统计 + MD5 + 置换，输出 16 字节。
     *
     * @param input     主输入（非空）
     * @param secondary 次级输入（可空）
     * @param postKey   置换末尾的后处理密钥（可空）
     */
    fun slky(input: ByteArray, secondary: ByteArray?, postKey: ByteArray?): ByteArray {
        require(input.isNotEmpty()) { "slky input is empty" }
        val n = input.size
        val first = toSigned(input[0].toInt() and 0xFF)
        val last = toSigned(input[n - 1].toInt() and 0xFF)
        var signedSum = n
        for (b in input) signedSum += toSigned(b.toInt() and 0xFF)
        val avg = signedDiv(signedSum, n)
        val seed = signedDiv(signedSum + last * first, n)
        var remainder = signedMod(signedSum, n)

        val work = ArrayList<Byte>(input.size + 8 + (secondary?.size ?: 0))
        for (b in input) work.add(b)
        for (b in seed.toString().toByteArray(Charsets.US_ASCII)) work.add(b)
        if (secondary != null && secondary.isNotEmpty()) {
            for (b in secondary) work.add(b)
            remainder = (remainder + secondary.size) and 0xFF
        }
        val avgByte = avg and 0xFF
        val w = ByteArray(work.size) { i -> (work[i].toInt() xor avgByte).toByte() }

        val digest = md5(w)
        val len = digest.size
        val v64 = ((len / 2) + remainder) and 0xFF
        val out = ByteArray(len)
        System.arraycopy(digest, 0, out, 0, len)
        for (pos in 0 until len) {
            var current = out[pos].toInt() and 0xFF
            val swapIdx = Math.abs(toSigned(current)) % len
            if (swapIdx > len / 2) {
                current = (current xor v64) and 0xFF
                out[pos] = current.toByte()
            }
            val target = out[swapIdx].toInt() and 0xFF
            out[swapIdx] = current.toByte()
            out[pos] = if (postKey != null) {
                ((target xor (postKey[pos % postKey.size].toInt() and 0xFF)) and 0xFF).toByte()
            } else {
                target.toByte()
            }
        }
        return out
    }

    fun hexToBytes(hex: String): ByteArray {
        val s = hex.trim()
        require(s.length % 2 == 0) { "bad hex length" }
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return out
    }
}

internal fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) sb.append(String.format("%02x", b.toInt() and 0xFF))
    return sb.toString()
}
