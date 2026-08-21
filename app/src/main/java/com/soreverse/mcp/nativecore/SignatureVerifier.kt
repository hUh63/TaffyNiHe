package com.soreverse.mcp.nativecore

import android.content.Context
import com.soreverse.mcp.core.AppLog
import java.security.MessageDigest
import java.security.cert.CertificateFactory

/**
 * Native APK signature verifier（上游 SOMCP 1.0.18 移植，防御 SigKill/TweakMe/ApkSignatureKiller）。
 *
 * 与 [com.soreverse.mcp.core.IntegrityGuard]（走 Java PackageManager API）不同，
 * 本类在 native (C++) 层直接读 APK 文件系统里的 META-INF/*.RSA/.DSA/.EC PKCS7 签名，
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
}
