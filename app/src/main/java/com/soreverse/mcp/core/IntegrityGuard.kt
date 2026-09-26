package com.soreverse.mcp.core

import android.app.Activity
import com.soreverse.mcp.nativecore.NativeProbe
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

    /**
     * 深检快照（`probeArchive` 的 dex-CRC 结构检查 + `verifyBlock` 的真实验签/内容摘要）。
     * 两者都是数十 MB～整包级别的重活，因此只允许在后台低频执行一次并缓存。
     */
    private data class DeepSnapshot(val at: Long, val probeCode: Int, val blockCode: Int)

    @Volatile private var deepCache: DeepSnapshot? = null

    /** 深检结果有效期：同一份 APK 的内容不会变，缓存久一点没有安全损失。 */
    private const val DEEP_TTL_MS = 10 * 60_000L

    private val deepLock = Any()
    @Volatile private var deepRecheckStarted = false

    /** 深检缓存中是否已判定「签名有效但内容被改」。未做过深检时返回 false（不误判）。 */
    private fun deepTampered(): Boolean {
        val cachedDeep = deepCache ?: return false
        if (System.currentTimeMillis() - cachedDeep.at > DEEP_TTL_MS) return false
        return NativeProbe.isTamper(cachedDeep.blockCode)
    }

    /** 深检缓存中的 probe（结构 + dex CRC）错误码；未做过深检时返回 0（不误判）。 */
    private fun deepProbeCode(): Int {
        val cachedDeep = deepCache ?: return 0
        if (System.currentTimeMillis() - cachedDeep.at > DEEP_TTL_MS) return 0
        return cachedDeep.probeCode
    }

    /** 深检缓存中的 v2/v3 验签错误码（仅用于日志/诊断文案）；未做过深检时返回 0。 */
    private fun deepBlockCode(): Int {
        val cachedDeep = deepCache ?: return 0
        if (System.currentTimeMillis() - cachedDeep.at > DEEP_TTL_MS) return 0
        return cachedDeep.blockCode
    }

    /**
     * 重量级校验：v2/v3 真实验签 + 全量内容摘要重算（上游 #111）。
     *
     * ⚠ 必须只在后台低频调用（见 [scheduleDeepRecheck]）：单次要读完整包并做数百次
     * SHA-256，放在主线程或高频路径上会直接卡死 UI。
     * 结果写入 [deepCache] 供 [verify] 复用，因此在 [DEEP_TTL_MS] 内重复调用几乎零成本。
     */
    fun deepVerify(context: Context): Result {
        deepCache?.let { snap ->
            if (System.currentTimeMillis() - snap.at < DEEP_TTL_MS) {
                return verify(context).let { base ->
                    if (NativeProbe.isTamper(snap.blockCode)) {
                        base.copy(
                            trusted = false,
                            reason = "v2/v3 signature/content re-verification FAILED (code=0x${snap.blockCode.toString(16)})",
                            threats = (base.threats + "apk-content-tamper").distinct(),
                        )
                    } else if (snap.probeCode != 0) {
                        base.copy(
                            trusted = false,
                            reason = "archive probe failed (code=0x${snap.probeCode.toString(16)})",
                            integrityCode = snap.probeCode,
                        )
                    } else {
                        base
                    }
                }
            }
        }
        val code = NativeProbe.verifyBlock(context)
        val probe = NativeProbe.probeArchive(context)
        deepCache = DeepSnapshot(System.currentTimeMillis(), probe, code)
        AppLog.i("Integrity deep check: probeCode=0x${probe.toString(16)} blockCode=0x${code.toString(16)}")
        return deepVerify(context)
    }

    /**
     * 后台周期深检（对齐上游 SOMCP v1.0.21 的 schedulePeriodicRecheck）：
     * 首次 10 秒后跑一次，之后每 2-5 分钟随机一次 —— 随机间隔让「先绕过启动检查、
     * 再打补丁」的计划难以掐时间。
     *
     * 终止条件刻意收得很窄：**只有** 深检结果为「确定性篡改」（签名有效但内容/签名数据
     * 对不上，此时 native 侧本就已判定 fatal）**且** v1 pin 匹配时才杀进程。
     * 「签名者 ≠ pin」（用户自行重签，native 返回 CERT_MISMATCH 非 fatal）只记日志不杀，
     * 保持塔菲逆核对逆向用户自行重签的既有兼容策略。
     */
    fun scheduleDeepRecheck(context: Context) {
        synchronized(deepLock) {
            if (deepRecheckStarted) return
            deepRecheckStarted = true
        }
        val app = context.applicationContext ?: context
        Thread({
            var delay = 10_000L
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(delay)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                delay = 120_000L + kotlin.random.Random.nextLong(180_000L)
                val result = runCatching { deepVerify(app) }.getOrNull() ?: continue
                if (!result.trusted && result.threats.contains("apk-content-tamper")) {
                    val pinned = expectedSignerDigest()
                    val pinMatched = pinned.isNotBlank() && result.actual.any { it == pinned }
                    if (pinMatched) {
                        AppLog.e("INTEGRITY DEEP CHECK FAILED: pinned-signer archive modified at runtime")
                        recordFailure(app, result)
                        exitProcess(173)
                    } else {
                        AppLog.w("Integrity deep check: archive signature differs from the pinned signer (re-signed build?) — not terminating")
                    }
                }
            }
        }).apply {
            isDaemon = true
            name = "taffy-integrity-deep"
            start()
        }
    }

    /** 失败记录的「签名」，用于去重（避免每 3 秒轮询时刷屏日志/写盘）。 */
    @Volatile private var lastFailureSignature: String = ""

    private const val PREFS = "integrity_guard"
    private const val KEY_LAST_FAILURE = "last_failure"

    /**
     * 最近一次完整性校验失败记录（诊断用）。
     * v1.3.9 (上游 v1.0.21 借鉴)：以前失败后进程被静默退出，用户无从查因；现在把失败原因
     * 落到 SharedPreferences，重启后仍能在弹窗/日志里看到「上次为什么被杀」。
     */
    fun lastFailure(context: Context): String? = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_FAILURE, null)
    }.getOrNull()

    private fun recordFailure(context: Context, result: Result) {
        val signature = "${result.reason}|${result.expected}|${result.actual.joinToString()}|${result.integrityCode}"
        if (signature == lastFailureSignature) return
        lastFailureSignature = signature
        runCatching {
            val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            val line = "$stamp | ${result.reason} | expected=${result.expected.take(16)}… | " +
                "actual=${result.actual.firstOrNull()?.take(16) ?: "-"}… | code=0x${result.integrityCode.toString(16)}"
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_FAILURE, line).apply()
            AppLog.e(
                "Integrity check FAILED: ${result.reason}; expected=${result.expected}; " +
                    "actual=${result.actual.joinToString()}; integrityCode=0x${result.integrityCode.toString(16)}"
            )
        }
    }

    fun verify(context: Context): Result {
        cached?.let { (time, result) ->
            if (System.currentTimeMillis() - time < 2_000L) return result
        }
        val result = runCatching {
            val expected = expectedSignerDigest()
            val actual = signingCertificateDigests(context).map { it.normalizeDigest() }
            // 上游 1.0.19 借鉴: APK 完整性校验（ZIP 结构 + 关键条目 + classes.dex CRC32）。
            // ⚠ 性能：dex CRC 需要遍历/解压 classes.dex（数十 MB），同样不能出现在本方法
            // 这种高频路径上；这里只读后台深检缓存（见 [deepVerify]）。
            val integrityCode = deepProbeCode()
            // 上游 1.0.20 借鉴: v2/v3 APK Signing Block 证书校验——防"签名方案混淆重打包"
            // （攻击者保留 v1 真证书、把 v2/v3 块换成自己密钥）。
            // ⚠ 提示不阻断：v1(PackageManager) 已匹配 pin 时，v2/v3 不匹配只可能是用户
            // 用签名工具自行重签（逆向工具用户常态），而非攻击。仅作为可疑信号记录。
            val v23Warning = runCatching {
                val norm = NativeProbe.fingerprintV234Of(context) ?: return@runCatching ""
                if (norm == expected || actual.any { it == norm }) ""
                else "v2/v3 signing block signer differs from v1 (possible signing-scheme confusion, v1 still verified)"
            }.getOrDefault("")
            // 上游 v1.0.22 (#111) 借鉴: v2/v3 真实验签 + apksig 1MiB 分块内容摘要重算。
            // ⚠ 性能：这一项要 mmap 整个 APK 并对全部内容重算分块 SHA-256（发布包约 190MB，
            // ≈190 次哈希 + 一次 RSA 验签），单次数百毫秒级。它绝不能出现在高频路径上
            // （本方法被 UI 每 3 秒轮询、被前台服务反复调用）—— 否则主线程会被反复阻塞，
            // 表现为整机「一卡一卡」。因此这里只**读深检结果缓存**，真正的验签由
            // [deepVerify] 在后台低频执行（启动一次 + 之后 2-5 分钟随机一次）。
            val blockTampered = deepTampered()
            if (blockTampered) {
                Result(
                    trusted = false,
                    reason = "v2/v3 signature/content re-verification FAILED (code=0x${deepBlockCode().toString(16)})",
                    expected = expected,
                    actual = actual,
                    threats = listOf("apk-content-tamper"),
                    integrityCode = integrityCode,
                )
            } else if (expected.isBlank()) {
                Result(true, "no release signer pin configured", expected, actual, integrityCode = integrityCode)
            } else {
                val signerTrusted = actual.any { it == expected }
                Result(
                    trusted = signerTrusted,
                    reason = when {
                        !signerTrusted -> "application signature mismatch"
                        v23Warning.isNotEmpty() -> v23Warning
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
        if (!result.trusted) recordFailure(context, result)
        cached = System.currentTimeMillis() to result
        return result
    }

    // NOTE(塔菲逆核): 保留签名校验（适配塔菲逆核自己的签名），但禁用反调试/反注入检测。
    // 原因: 本 App 面向逆向场景, 用户常开 root/frida/调试器, runtimeThreats() 会误伤正常使用。
    // 如需恢复完整检测, 取消下方注释并改 isTrusted 调用 verify(context).trusted。
    fun isTrusted(context: Context): Boolean = verify(context).trusted

    /**
     * 终止进程（签名明确不匹配时的最终拦截）。
     * v1.3.9: 终止前先把失败原因落日志/落盘 —— 否则用户只会看到"应用闪退"，无从判断是
     * 安装包被改过还是校验误判（上游 v1.0.21 同类修复）。
     */
    fun terminate(activity: Activity) {
        runCatching {
            AppLog.e("Integrity gate: terminating process; lastFailure=${lastFailure(activity.applicationContext) ?: "-"}")
        }
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
