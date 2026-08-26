package com.soreverse.mcp.nativecore

import android.content.Context
import com.soreverse.mcp.core.AppLog
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory

/**
 * Native APK signature verifier（上游 SOMCP 1.0.18 移植，防御 SigKill/TweakMe/ApkSignatureKiller）。
 *
 * 与 [com.soreverse.mcp.core.IntegrityGuard]（走 Java PackageManager API）不同，
 * 本类在 native (C++) 层直接读 APK 文件系统里的 META-INF 目录下 .RSA/.DSA/.EC PKCS7 签名，
 * 绕过 Binder 代理 —— 无法被 kstools / ApkSignatureKiller / MT 的 Binder-hook 技术拦截。
 *
 * 依赖: librz_native.so（新全静态 so 内置 nativeGetExpectedSignerDigest / nativeReadApkCertificate）。
 */
object SignatureVerifier {

    private const val TAG = "SignatureVerifier"

    @Volatile
    private var loaded: Boolean = false

    @Volatile
    private var loadError: String = ""

    init {
        val result = runCatching { System.loadLibrary("rz_native") }
        loaded = result.isSuccess
        if (!loaded) {
            loadError = result.exceptionOrNull()?.message ?: "Unknown load error"
            AppLog.w("SignatureVerifier: rz_native load FAILED: $loadError")
        } else {
            AppLog.i("SignatureVerifier: rz_native load OK")
        }
    }

    // JNI: implemented in cpp/signature_verify.cpp (静态链接进 librz_native.so)
    private external fun nativeReadApkCertificate(apkPath: String): ByteArray?
    private external fun nativeGetExpectedSignerDigest(): String
    // 上游 1.0.19 新增导出（1.0.19 重新发布后资产可用，已升级 so 获得）
    private external fun nativeVerifyPackageName(packageName: String): Boolean
    private external fun nativeVerifyApkIntegrity(apkPath: String): Int
    private external fun nativeComputeSha256Hex(data: ByteArray): String?

    /** 直接从 APK 文件读取签名证书（绕过 PackageManager Binder）。@return DER X.509 或 null */
    fun readApkCertificate(apkPath: String): ByteArray? {
        if (!loaded) {
            AppLog.e("SignatureVerifier: native library not loaded: $loadError")
            return null
        }
        return try {
            nativeReadApkCertificate(apkPath)
        } catch (e: Exception) {
            AppLog.e("SignatureVerifier: nativeReadApkCertificate failed", e)
            null
        }
    }

    /** 计算本应用 APK 的签名者 SHA-256（大写 hex），失败返回 null */
    fun computeApkSignerDigest(context: Context): String? {
        val apkPath = try {
            context.packageCodePath
        } catch (e: Exception) {
            AppLog.e("SignatureVerifier: cannot get packageCodePath", e)
            return null
        }
        return readApkCertificate(apkPath)?.let { certBytesToDigest(it) }
    }

    /** 计算任意 APK 文件的签名者 SHA-256（大写 hex），失败返回 null */
    fun signerDigest(apkPath: String): String? {
        if (!loaded) return null
        return readApkCertificate(apkPath)?.let { certBytesToDigest(it) }
    }

    private fun certBytesToDigest(certBytes: ByteArray): String? = try {
        val cf = CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(certBytes.inputStream())
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        digest.joinToString("") { "%02X".format(it) }
    } catch (e: Exception) {
        AppLog.e("SignatureVerifier: certificate parsing failed", e)
        // 回退: 对原始 DER 字节直接哈希
        try {
            val digest = MessageDigest.getInstance("SHA-256").digest(certBytes)
            digest.joinToString("") { "%02X".format(it) }
        } catch (e2: Exception) {
            AppLog.e("SignatureVerifier: fallback digest failed", e2)
            null
        }
    }

    /** 上游 1.0.18: 该 APK 是否用塔菲逆核官方签名密钥签名（签名者摘要 == 内嵌期望值） */
    fun isSelfSignedApk(apkPath: String): Boolean {
        if (!loaded) return false
        val expected = getExpectedSignerDigest().let { normalizeSignerDigest(it) }
        if (expected.isBlank()) return false // 未配置发布签名 pin
        return signerDigest(apkPath) == expected
    }

    /** native 里 XOR 混淆存储的期望签名者摘要（新 so 导出） */
    fun getExpectedSignerDigest(): String {
        if (!loaded) return ""
        return try {
            nativeGetExpectedSignerDigest()
        } catch (e: Exception) {
            AppLog.e("SignatureVerifier: nativeGetExpectedSignerDigest failed", e)
            ""
        }
    }

    private fun normalizeSignerDigest(value: String): String = value.filter { it.isLetterOrDigit() }.uppercase()

    // ── 上游 1.0.19 移植: APK 完整性校验（Kotlin 等价实现）──
    // 上游用 cpp/signature_verify.cpp 的 nativeVerifyApkIntegrity（mmap + 64 位溢出防护 +
    // 流式 CRC32）；我们的 1.0.17 预编译 so 无该导出，故用纯 Kotlin 实现等价检查项：
    //   EOCD/central-directory 边界、关键条目存在性、classes.dex CRC（ZipFile 自动处理 deflate）。

    object IntegrityCode {
        const val OK = 0
        const val READ_FAILED = 1 shl 0
        const val EOCD_NOT_FOUND = 1 shl 1
        const val CENTRAL_DIR_INVALID = 1 shl 2
        const val MISSING_CLASSES = 1 shl 3
        const val MISSING_MANIFEST = 1 shl 4
        const val MISSING_ARSC = 1 shl 5
        const val MISSING_SIGNATURE = 1 shl 6
        const val MISSING_NATIVE = 1 shl 7
        const val CRC_MISMATCH = 1 shl 8
    }

    /**
     * 上游 1.0.19: 包名原生校验（native 层比对，防 context.packageName 被 hook/spoof）。
     */
    fun verifyPackageName(context: Context): Boolean {
        if (!loaded) return false
        val packageName = try {
            context.packageName
        } catch (e: Exception) {
            AppLog.e("SignatureVerifier: cannot get packageName", e)
            return false
        }
        return try {
            nativeVerifyPackageName(packageName)
        } catch (e: Exception) {
            AppLog.e("SignatureVerifier: nativeVerifyPackageName failed", e)
            false
        }
    }

    /**
     * 上游 1.0.19: APK 完整性原生校验（mmap + 64 位溢出防护 + 流式 CRC32，比 Kotlin 版更快更全）。
     * native 不可用时回退到 [verifyApkIntegrityKotlin]。
     * @return [IntegrityCode] 位标志；[IntegrityCode.OK] 表示完整。
     */
    fun verifyApkIntegrity(context: Context): Int {
        if (loaded) {
            val apkPath = try {
                context.packageCodePath
            } catch (e: Exception) {
                AppLog.e("SignatureVerifier: cannot get packageCodePath", e)
                return IntegrityCode.READ_FAILED
            }
            val native = try {
                nativeVerifyApkIntegrity(apkPath)
            } catch (e: Exception) {
                AppLog.e("SignatureVerifier: nativeVerifyApkIntegrity failed", e)
                -1
            }
            if (native >= 0) return native
        }
        return verifyApkIntegrityKotlin(context)
    }

    /** 计算数据的 SHA-256 hex（native 实现，供防篡改比对） */
    fun computeSha256Hex(data: ByteArray): String? {
        if (!loaded) return null
        return try {
            nativeComputeSha256Hex(data)
        } catch (e: Exception) {
            AppLog.e("SignatureVerifier: nativeComputeSha256Hex failed", e)
            null
        }
    }

    /**
     * 纯 Kotlin APK 完整性校验（等价上游 native verify_apk_integrity；native 不可用时 fallback）。
     */
    fun verifyApkIntegrityKotlin(context: Context): Int {
        val apkPath = try {
            context.packageCodePath
        } catch (e: Exception) {
            AppLog.e("SignatureVerifier: cannot get packageCodePath", e)
            return IntegrityCode.READ_FAILED
        }
        val file = File(apkPath)
        if (!file.isFile || file.length() < 22L) return IntegrityCode.READ_FAILED
        return try {
            var code = 0
            // 1) EOCD 存在性 + central-directory 边界（64 位安全计算，防 32 位溢出）
            //    ⚠ 修复: ZIP 为小端存储, RandomAccessFile.readInt() 大端读出 0x504B0506,
            //    旧比较 0x06054b50 永远 false 导致 EOCD_NOT_FOUND 恒置位、完整性检查失效。
            val eocdOk = java.io.RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val searchStart = if (len > 65557L) len - 65557L else 0L
                var pos = len - 22L
                var found = false
                while (pos >= searchStart && pos >= 0) {
                    raf.seek(pos)
                    if (raf.readInt() == 0x504b0506) { found = true; break }
                    pos--
                }
                if (!found) {
                    code = IntegrityCode.EOCD_NOT_FOUND
                    false
                } else {
                    raf.seek(pos + 16)
                    val cdSize = raf.readIntLE().toLong() and 0xFFFFFFFFL
                    val cdOffset = raf.readIntLE().toLong() and 0xFFFFFFFFL
                    if (cdOffset + cdSize > len) {
                        code = IntegrityCode.CENTRAL_DIR_INVALID
                        false
                    } else true
                }
            }
            // 2) 关键条目存在性 + classes.dex CRC
            if (eocdOk) {
                java.util.zip.ZipFile(file).use { zf ->
                    val names = zf.entries().asSequence().map { it.name }.toSet()
                    if ("classes.dex" !in names) code = code or IntegrityCode.MISSING_CLASSES
                    if ("AndroidManifest.xml" !in names) code = code or IntegrityCode.MISSING_MANIFEST
                    if ("resources.arsc" !in names) code = code or IntegrityCode.MISSING_ARSC
                    if (!names.any { it.startsWith("META-INF/") && (it.endsWith(".RSA") || it.endsWith(".DSA") || it.endsWith(".EC")) }) {
                        code = code or IntegrityCode.MISSING_SIGNATURE
                    }
                    if (!names.any { it.startsWith("lib/") && it.endsWith("librz_native.so") }) {
                        code = code or IntegrityCode.MISSING_NATIVE
                    }
                    zf.getEntry("classes.dex")?.let { e ->
                        val expected = e.crc
                        val actual = zf.getInputStream(e).use { input ->
                            val crc = java.util.zip.CRC32()
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                crc.update(buf, 0, n)
                            }
                            crc.value
                        }
                        if (actual != expected) code = code or IntegrityCode.CRC_MISMATCH
                    }
                }
            }
            code
        } catch (e: Exception) {
            AppLog.e("SignatureVerifier: verifyApkIntegrityKotlin failed", e)
            IntegrityCode.READ_FAILED
        }
    }
}

// ====================================================================
// APK Signing Block（v2/v3 签名方案）证书提取 —— 纯 Kotlin
// 上游 SOMCP 1.0.20 借鉴: 防"签名方案混淆重打包"——攻击者保留 v1(META-INF) 真证书,
// 把 v2/v3 Signing Block 换成自己密钥签名; 只校验 v1 的检查器会误判为可信。
// 这里从 APK Signing Block 提取 v2/v3 签名证书并计算 SHA-256, 与内嵌 pin 对比。
// 布局 (google/apksigner ApkSigningBlockUtils):
//   [uint64 blockSize][(uint64 pairSize + uint32 id + value)...][uint64 blockSize]["APK Sig Block 42"]
//   紧邻 ZIP central directory 之前。
// ====================================================================
object ApkSigningBlock {

    /** 签名块存在但解析失败（可疑，视为不匹配）。 */
    const val PARSE_ERROR = "__PARSE_ERROR__"

    private val MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
    private const val V2_ID = 0x7109871aL
    private const val V3_ID = 0xf05368c0L

    /**
     * 提取 APK 的 v2/v3 签名块证书 SHA-256（大写 hex）。
     * @return null=无 v2/v3 签名块（纯 v1 签名）；[PARSE_ERROR]=块存在但解析失败；否则证书摘要。
     */
    fun signingBlockCertDigest(apkPath: String): String? {
        val file = File(apkPath)
        if (!file.isFile || file.length() < 32L) return null
        return try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val cert = findSignBlockCert(raf) ?: return null
                MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02X".format(it) }
            }
        } catch (e: Exception) {
            AppLog.e("ApkSigningBlock: parse failed", e)
            PARSE_ERROR
        }
    }

    /** 在 APK 中定位 v2/v3 签名块并提取第一张签名证书（X.509 DER）。 */
    private fun findSignBlockCert(raf: java.io.RandomAccessFile): ByteArray? {
        val len = raf.length()

        // 1) EOCD：文件末尾 22..65557 字节内搜索（ZIP 小端; readInt 大端读出 0x504b0506）
        val searchStart = if (len > 65557L) len - 65557L else 0L
        var eocdPos = -1L
        var pos = len - 22L
        while (pos >= searchStart && pos >= 0) {
            raf.seek(pos)
            if (raf.readInt() == 0x504b0506) { eocdPos = pos; break }
            pos--
        }
        if (eocdPos < 0) return null

        // 2) central directory offset（ZIP 小端存储）
        raf.seek(eocdPos + 16)
        val cdOffset = raf.readIntLE().toLong() and 0xFFFFFFFFL
        if (cdOffset < 24L || cdOffset > len) return null

        // 3) magic "APK Sig Block 42" 位于 central directory 前 16 字节
        val magicOff = cdOffset - 16
        raf.seek(magicOff)
        val magic = ByteArray(16)
        raf.readFully(magic)
        if (!magic.contentEquals(MAGIC)) return null // 无 v2/v3 签名块

        // 4) trailing block size（magic 前 8 字节）；结构异常抛异常 → 外层转 PARSE_ERROR
        raf.seek(magicOff - 8)
        val trailingSize = raf.readLongLE()
        if (trailingSize <= 0) throw IllegalStateException("invalid signing block size")
        val blockEnd = magicOff - 8
        if (trailingSize > blockEnd - 8) throw IllegalStateException("signing block size out of range")
        val blockStart = blockEnd - trailingSize - 8

        // 5) leading size 交叉校验
        raf.seek(blockStart)
        val leadingSize = raf.readLongLE()
        if (leadingSize != trailingSize) throw IllegalStateException("signing block size mismatch")

        // 6) 遍历 block pairs
        var pairPos = blockStart + 8
        val pairsEnd = blockEnd
        var v2Cert: ByteArray? = null
        var v3Cert: ByteArray? = null
        while (pairPos + 8 <= pairsEnd) {
            raf.seek(pairPos)
            val pairSize = raf.readLongLE()
            val valueOff = pairPos + 8
            if (pairSize < 4) { pairPos = valueOff + pairSize; continue }
            if (valueOff + pairSize > pairsEnd) throw IllegalStateException("signing block pair out of bounds")
            raf.seek(valueOff)
            val id = raf.readInt().toLong() and 0xFFFFFFFFL
            val valueLen = (pairSize - 4).toInt()
            val value = ByteArray(valueLen)
            raf.readFully(value)
            when (id) {
                V3_ID -> v3Cert = extractCertFromSignBlock(value)
                V2_ID -> v2Cert = extractCertFromSignBlock(value)
            }
            pairPos = valueOff + pairSize
        }
        // 优先 v3（Android 校验最强方案优先）
        return v3Cert ?: v2Cert
    }

    /**
     * 从 v2/v3 签名块 payload 提取第一张 X.509 证书。
     * 布局（对照 apksig getApkSignatureBlockSigners，已用真实 apksig 签名 APK 验证）:
     *   signers(长度前缀) → signer(长度前缀) → signedData(长度前缀) →
     *   digests(长度前缀, 跳过) → certificates(长度前缀) → 每张证书 = [u32 长度][DER bytes]。
     * 所有长度均为 uint32 LE。
     */
    private fun extractCertFromSignBlock(block: ByteArray): ByteArray? {
        var pos = 0
        fun u32(): Long {
            if (pos + 4 > block.size) return -1
            val v = ((block[pos].toLong() and 0xFF)) or
                ((block[pos + 1].toLong() and 0xFF) shl 8) or
                ((block[pos + 2].toLong() and 0xFF) shl 16) or
                ((block[pos + 3].toLong() and 0xFF) shl 24)
            pos += 4
            return v
        }
        // signers 序列
        val signersLen = u32()
        if (signersLen < 0 || pos + signersLen > block.size) return null
        // signer（单个，v2 通常只有 1 个 signer）
        val signerLen = u32()
        if (signerLen < 0 || pos + signerLen > block.size) return null
        val signerEnd = pos + signerLen
        // signedData
        val sdLen = u32()
        if (sdLen < 0 || pos + sdLen > signerEnd) return null
        val sdEnd = pos + sdLen
        // digests（跳过）
        val digestsLen = u32()
        if (digestsLen < 0 || pos + digestsLen > sdEnd) return null
        pos += digestsLen.toInt()
        // certificates 序列
        val certsLen = u32()
        if (certsLen < 0 || pos + certsLen > sdEnd) return null
        val certsEnd = pos + certsLen
        // 第一张证书: [u32 长度][DER bytes]（直接 getInt，非 length-prefixed slice）
        val certLen = u32()
        if (certLen < 0 || pos + certLen > certsEnd) return null
        return block.copyOfRange(pos, pos + certLen.toInt())
    }

    private fun java.io.RandomAccessFile.readLongLE(): Long {
        val b = ByteArray(8)
        readFully(b)
        return (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
            ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24) or
            ((b[4].toLong() and 0xFF) shl 32) or ((b[5].toLong() and 0xFF) shl 40) or
            ((b[6].toLong() and 0xFF) shl 48) or ((b[7].toLong() and 0xFF) shl 56)
    }
}

/** ZIP 小端 int 读取（RandomAccessFile.readInt() 是大端，直接用于 ZIP 字段会读反）。 */
private fun java.io.RandomAccessFile.readIntLE(): Int {
    val b = ByteArray(4)
    readFully(b)
    return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
        ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
}
