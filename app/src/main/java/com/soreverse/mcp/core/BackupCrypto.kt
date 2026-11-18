package com.soreverse.mcp.core

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts and decrypts backup files using Argon2id key derivation + AES-256-GCM.
 *
 * ## 文件格式
 * ```
 * [0..8)      "SOMCP_ENC" magic
 * [8]         version
 * [9]         salt 长度
 * [10..)      salt
 * [..]        nonce 长度
 * [..]        nonce
 * [remainder] AES-GCM 密文（末尾 16 B 是认证标签）
 * ```
 *
 * - **version 1**：密文解出来是一段 **Base64 文本**（更里面才是 zip 字节）。早期实现为了把
 *   「二进制 → 字符串 → 加密」拼起来才这么做的；代价是导出/恢复要同时持有
 *   「zip 字节 + Base64 字符串 + 密文」≈ 3 倍内存，所以过去有 200 MB 闸门。
 * - **version 2**：密文就是 zip 字节本身，支持 [encryptStream] / [decryptStream]
 *   全流式加解密，内存占用与备份大小无关。**新备份一律写 v2**，旧 v1 备份仍可读。
 *
 * 纯文本（无密码）备份不加密，保持 zip 原样。
 */
object BackupCrypto {

    private const val MAGIC = "SOMCP_ENC"
    private const val VERSION_LEGACY: Byte = 1
    private const val VERSION_STREAM: Byte = 2
    private const val SALT_SIZE = 16
    private const val NONCE_SIZE = 12
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    private const val KEY_BYTES = 32 // AES-256
    private const val COPY_BUFFER = 64 * 1024

    private val random = SecureRandom()
    // 懒加载：Argon2Kt 构造会触发 Android JNI 初始化，纯 JVM 单测里一碰就
    // ExceptionInInitializerError。只有真正加解密（deriveKey）时才需要它，
    // 而「解析文件头 / 判断是否加密」这些纯逻辑必须能在单测里跑。
    private val argon2 by lazy { Argon2Kt() }

    /** 解析出来的文件头。 */
    data class Header(val version: Int, val salt: ByteArray, val nonce: ByteArray, val headerBytes: Int)

    /**
     * Encrypt [plaintext] with [password]（v1 格式，保留给「文本备份」这条老链路）。
     * Returns the binary blob (magic + params + ciphertext).
     */
    fun encrypt(plaintext: String, password: String): ByteArray {
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val key = deriveKey(password, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        return ByteArrayOutputStream().apply {
            write(MAGIC.toByteArray(Charsets.UTF_8))
            write(VERSION_LEGACY.toInt())
            write(salt.size)
            write(salt)
            write(nonce.size)
            write(nonce)
            write(ciphertext)
        }.toByteArray()
    }

    /**
     * Decrypt [data] with [password]（支持 v1 与 v2）。返回明文的 UTF-8 字符串 ——
     * v2 是二进制 zip 时不要用这个，用 [decryptStream]。
     */
    fun decrypt(data: ByteArray, password: String): String {
        val header = parseHeader(data) ?: throw IllegalArgumentException("Not a valid encrypted backup")
        if (header.version != VERSION_LEGACY.toInt()) {
            throw IllegalArgumentException("该备份是流式格式（v${header.version}），请使用流式解密")
        }
        val ciphertext = data.copyOfRange(header.headerBytes, data.size)
        val key = deriveKey(password, header.salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, header.nonce))
        return cipher.doFinal(ciphertext).decodeToString()
    }

    /**
     * 流式加密 [source]（zip 字节）到 [target]，写 v2 格式。
     * @return 写出的总字节数（含文件头与 16 B 认证标签）
     */
    fun encryptStream(source: InputStream, target: OutputStream, password: String): Long {
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val key = deriveKey(password, salt)
        val headerBytes = writeHeader(target, VERSION_STREAM, salt, nonce)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        // CipherOutputStream.close() 会 doFinal（写认证标签）并关闭底层流 ——
        // 包一层「close 只 flush」的壳，避免把调用方的文件流一起关掉。
        val cipherOut = CipherOutputStream(NonClosingOutputStream(target), cipher)
        val buffer = ByteArray(COPY_BUFFER)
        var plainBytes = 0L
        while (true) {
            val n = source.read(buffer)
            if (n <= 0) break
            cipherOut.write(buffer, 0, n)
            plainBytes += n
        }
        cipherOut.close()
        target.flush()
        return headerBytes + plainBytes + GCM_TAG_BYTES
    }

    /**
     * 流式解密 [source] 到 [target]，自动识别 v1 / v2。
     *
     * @return true = 解密成功且认证标签校验通过；false = 密码错误或文件损坏
     *         （此时 [target] 里可能已经有部分明文，调用方必须删掉它）
     */
    fun decryptStream(source: InputStream, target: OutputStream, password: String): Boolean {
        val header = runCatching { readHeader(source) }.getOrNull() ?: return false
        val key = deriveKey(password, header.salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, header.nonce))

        if (header.version == VERSION_STREAM.toInt()) {
            // 不用 CipherInputStream：它历史上会把认证失败吞成「读到 EOF」，
            // 于是错误密码会产出一个 0 字节文件却报成功。这里显式 doFinal 收尾，
            // 标签不匹配时抛 AEADBadTagException，能准确返回 false。
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                val n = source.read(buffer)
                if (n <= 0) break
                val out = cipher.update(buffer, 0, n) ?: continue
                if (out.isNotEmpty()) target.write(out)
            }
            val tail = try {
                cipher.doFinal()
            } catch (e: GeneralSecurityException) {
                return false
            } catch (e: IllegalStateException) {
                return false
            }
            if (tail.isNotEmpty()) target.write(tail)
            target.flush()
            return true
        }

        // v1：密文解出来是 Base64 文本，然后才是 zip 字节。旧备份才走这条，
        // 受调用方的体积闸门保护（新备份不会再写这种格式）。
        val rest = source.readBytes()
        val plain = try {
            cipher.doFinal(rest)
        } catch (e: GeneralSecurityException) {
            return false
        } catch (e: IllegalStateException) {
            return false
        }
        val decoded = runCatching { Base64.getDecoder().decode(plain.decodeToString()) }.getOrNull() ?: return false
        target.write(decoded)
        target.flush()
        return true
    }

    /** Returns true when [data] starts with the encrypted-backup magic bytes. */
    fun isEncrypted(data: ByteArray): Boolean =
        data.size >= MAGIC.length && data.copyOfRange(0, MAGIC.length).decodeToString() == MAGIC

    /** 只读 8 字节头判断，不把整个文件读进内存。 */
    fun isEncryptedStream(source: InputStream): Boolean {
        val head = ByteArray(MAGIC.length)
        var read = 0
        while (read < head.size) {
            val n = source.read(head, read, head.size - read)
            if (n <= 0) break
            read += n
        }
        return read == head.size && isEncrypted(head)
    }

    private fun writeHeader(target: OutputStream, version: Byte, salt: ByteArray, nonce: ByteArray): Int {
        target.write(MAGIC.toByteArray(Charsets.UTF_8))
        target.write(version.toInt())
        target.write(salt.size)
        target.write(salt)
        target.write(nonce.size)
        target.write(nonce)
        target.flush()
        return MAGIC.length + 1 + 1 + salt.size + 1 + nonce.size
    }

    /** 仅供同包单测：解析字节数组形式的文件头。 */
    internal fun parseHeader(data: ByteArray): Header? {
        if (data.size < MAGIC.length + 3) return null
        if (!isEncrypted(data)) return null
        var offset = MAGIC.length
        val version = data[offset].toInt() and 0xFF
        offset++
        if (version != VERSION_LEGACY.toInt() && version != VERSION_STREAM.toInt()) return null
        val saltLen = data[offset].toInt() and 0xFF
        offset++
        if (saltLen <= 0 || offset + saltLen > data.size) return null
        val salt = data.copyOfRange(offset, offset + saltLen)
        offset += saltLen
        if (offset >= data.size) return null
        val nonceLen = data[offset].toInt() and 0xFF
        offset++
        if (nonceLen <= 0 || offset + nonceLen > data.size) return null
        val nonce = data.copyOfRange(offset, offset + nonceLen)
        offset += nonceLen
        return Header(version, salt, nonce, offset)
    }

    private fun readHeader(input: InputStream): Header? {
        val magic = ByteArray(MAGIC.length)
        if (!readFully(input, magic)) return null
        if (magic.decodeToString() != MAGIC) return null
        val version = readByte(input) ?: return null
        val saltLen = readByte(input) ?: return null
        if (saltLen <= 0 || saltLen > 64) return null
        val salt = ByteArray(saltLen)
        if (!readFully(input, salt)) return null
        val nonceLen = readByte(input) ?: return null
        if (nonceLen <= 0 || nonceLen > 64) return null
        val nonce = ByteArray(nonceLen)
        if (!readFully(input, nonce)) return null
        return Header(version, salt, nonce, MAGIC.length + 1 + 1 + saltLen + 1 + nonceLen)
    }

    private fun readByte(input: InputStream): Int? {
        val b = input.read()
        return if (b < 0) null else b
    }

    private fun readFully(input: InputStream, dest: ByteArray): Boolean {
        var read = 0
        while (read < dest.size) {
            val n = input.read(dest, read, dest.size - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    /** close() 只 flush，不关闭真正的目标流。 */
    private class NonClosingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        override fun close() {
            flush()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        return argon2.hash(
            mode = Argon2Mode.ARGON2_ID,
            password = password.toByteArray(Charsets.UTF_8),
            salt = salt,
            tCostInIterations = 3,
            mCostInKibibyte = 65536,  // 64 MiB in KiB
            parallelism = 2,
            hashLengthInBytes = KEY_BYTES,
        ).rawHashAsByteArray()
    }
}
