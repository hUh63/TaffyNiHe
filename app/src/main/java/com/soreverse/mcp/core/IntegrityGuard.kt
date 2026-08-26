package com.soreverse.mcp.core

import android.app.Activity
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import kotlin.system.exitProcess

object IntegrityGuard {
    data class Result(
        val trusted: Boolean,
        val reason: String,
        val expected: String,
        val actual: List<String>,
        val threats: List<String> = emptyList(),
        val integrityCode: Int = 0, // 上游 1.0.19: APK 完整性位标志（0=完整）
    )

    // ── 签名者摘要混淆存储 ──
    // 上游 SOMCP 1.0.17 加固项: 移除 BuildConfig 明文, 防止 dex 字符串直接提取签名摘要。
    // 塔菲逆核禁用 CMake native 编译(用预编译 so), 故用 XOR 混淆字节数组替代 native 混淆,
    // 达到同等效果 —— APK 内不再有明文的 64 位 hex 签名摘要字符串。
    // 原始摘要(仅供参考, 勿回填): 3CC2D37005933116AC2C91735BC9C72A48C2319AF58EA224AD84EF850F7E1ABB
    private val OBFUSCATION_KEY: Byte = 0x5A
    private val OBFUSCATED_SIGNER_DIGEST = byteArrayOf(
        0x66, 0x98.toByte(), 0x89.toByte(), 0x2A, 0x5F, 0xC9.toByte(), 0x6B, 0x4C,
        0xF6.toByte(), 0x76, 0xCB.toByte(), 0x29, 0x01, 0x93.toByte(), 0x9D.toByte(), 0x70,
        0x12, 0x98.toByte(), 0x6B, 0xC0.toByte(), 0xAF.toByte(), 0xD4.toByte(), 0xF8.toByte(), 0x7E,
        0xF7.toByte(), 0xDE.toByte(), 0xB5.toByte(), 0xDF.toByte(), 0x55, 0x24, 0x40, 0xE1.toByte(),
    )

    private fun expectedSignerDigest(): String {
        val bytes = ByteArray(OBFUSCATED_SIGNER_DIGEST.size)
        for (i in bytes.indices) {
            bytes[i] = (OBFUSCATED_SIGNER_DIGEST[i].toInt() xor OBFUSCATION_KEY.toInt()).toByte()
        }
        return bytes.joinToString("") { "%02X".format(it) }
    }

    @Volatile private var cached: Pair<Long, Result>? = null

    fun verify(context: Context): Result {
        cached?.let { (time, result) ->
            if (System.currentTimeMillis() - time < 2_000L) return result
        }
        val result = runCatching {
            val expected = expectedSignerDigest()
            val actual = signingCertificateDigests(context).map { it.normalizeDigest() }
            // 上游 1.0.19 借鉴: APK 完整性校验（native mmap/CRC 优先，Kotlin fallback）
            val integrityCode = com.soreverse.mcp.nativecore.SignatureVerifier.verifyApkIntegrity(context)
            // 上游 1.0.20 借鉴: v2/v3 APK Signing Block 证书校验——防"签名方案混淆重打包"
            // （攻击者保留 v1 真证书、把 v2/v3 块换成自己密钥）。v2/v3 块存在时必须匹配 pin。
            val v23Digest = runCatching {
                com.soreverse.mcp.nativecore.ApkSigningBlock.signingBlockCertDigest(
                    context.packageCodePath
                )
            }.getOrNull()
            val v23Trusted = when (v23Digest) {
                null -> true                                   // 无 v2/v3 块（纯 v1 签名）
                com.soreverse.mcp.nativecore.ApkSigningBlock.PARSE_ERROR -> false // 块存在但解析失败（可疑）
                else -> v23Digest.normalizeDigest() == expected
            }
            if (expected.isBlank()) {
                Result(true, "no release signer pin configured", expected, actual, integrityCode = integrityCode)
            } else {
                val signerTrusted = actual.any { it == expected } && v23Trusted
                Result(
                    trusted = signerTrusted,
                    reason = when {
                        !actual.any { it == expected } -> "application signature mismatch"
                        !v23Trusted -> "v2/v3 signing block signer mismatch (signing-scheme confusion)"
                        else -> "trusted release signer"
                    },
                    expected = expected,
                    actual = actual,
                    integrityCode = integrityCode,
                )
            }
        }.getOrElse {
            Result(false, it.message ?: it.javaClass.simpleName, expectedSignerDigest(), emptyList())
        }
        cached = System.currentTimeMillis() to result
        return result
    }

    // NOTE(塔菲逆核): 保留签名校验（适配塔菲逆核自己的签名），但禁用反调试/反注入检测。
    // 原因: 本 App 面向逆向场景, 用户常开 root/frida/调试器, runtimeThreats() 会误伤正常使用。
    // 如需恢复完整检测, 取消下方注释并改 isTrusted 调用 verify(context).trusted。
    fun isTrusted(context: Context): Boolean = verify(context).trusted

    fun terminate(activity: Activity) {
        runCatching { activity.finishAffinity() }
        exitProcess(173)
    }

    private fun signingCertificateDigests(context: Context): List<String> {
        val info = packageInfo(context)
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            val signingInfo = info.signingInfo ?: return emptyList()
            val signers = if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners else signingInfo.signingCertificateHistory
            signers.orEmpty().map { it.toByteArray() }
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty().map { it.toByteArray() }
        }
        return certs.map { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }
        }
    }

    private fun runtimeThreats(): List<String> {
        val threats = linkedSetOf<String>()
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) threats += "debugger attached"
        val tracer = tracerPid()
        if (tracer > 0) threats += "native tracer attached"
        val maps = procMapsIndicators()
        if (maps.isNotEmpty()) threats += maps
        val ports = openLocalInstrumentationPorts()
        if (ports.isNotEmpty()) threats += ports.map { "instrumentation port open: $it" }
        return threats.toList()
    }

    private fun tracerPid(): Int = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("TracerPid:") }
                ?.substringAfter(':')
                ?.trim()
                ?.toIntOrNull() ?: 0
        }
    }.getOrDefault(0)

    private fun procMapsIndicators(): List<String> = runCatching {
        val needles = listOf("frida", "gum-js-loop", "gadget", "xposed", "lsposed", "edxp", "zygisk", "substrate")
        val hits = linkedSetOf<String>()
        File("/proc/self/maps").useLines { lines ->
            lines.take(8_000).forEach { line ->
                val lower = line.lowercase()
                needles.firstOrNull { lower.contains(it) }?.let { hits += "runtime hook artifact: $it" }
            }
        }
        hits.toList()
    }.getOrDefault(emptyList())

    private fun openLocalInstrumentationPorts(): List<Int> {
        val ports = listOf(27042, 27043)
        return ports.filter { port ->
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 80)
                    true
                }
            }.getOrDefault(false)
        }
    }

    private fun packageInfo(context: Context): PackageInfo {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
    }

    private fun String.normalizeDigest(): String = filter { it.isLetterOrDigit() }.uppercase()
}
