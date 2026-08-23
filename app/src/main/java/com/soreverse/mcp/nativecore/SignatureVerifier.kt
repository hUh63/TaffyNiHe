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
            val eocdOk = java.io.RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val searchStart = if (len > 65557L) len - 65557L else 0L
                var pos = len - 22L
                var found = false
                while (pos >= searchStart && pos >= 0) {
                    raf.seek(pos)
                    if (raf.readInt() == 0x06054b50.toInt()) { found = true; break }
                    pos--
                }
                if (!found) {
                    code = IntegrityCode.EOCD_NOT_FOUND
                    false
                } else {
                    raf.seek(pos + 16)
                    val cdSize = raf.readInt().toLong() and 0xFFFFFFFFL
                    val cdOffset = raf.readInt().toLong() and 0xFFFFFFFFL
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
