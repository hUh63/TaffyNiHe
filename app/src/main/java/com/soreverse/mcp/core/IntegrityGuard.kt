package com.soreverse.mcp.core

import android.app.Activity
import com.soreverse.mcp.nativecore.NativeProbe
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess

object IntegrityGuard {

    /**
     * ROM 级 ART/DEX 转储检测（对齐 SOMCP v1.0.22 / commit 317178e）。
     *
     * 这类脱壳机不 hook 我们的进程，而是直接改平台：ArkOS-angler-8.1 把
     * `dalvik.system.DexFile` 加上 `setFartDumpEnabled` 开关、把
     * `android.app.ActivityThread` 加上整套 FART dump 管线、并在
     * `libart.so` 导出 DumpFartDexFileDirect。原版平台不声明这些成员，
     * 且它们都在 boot classpath 上，从本进程反射即可观测（无需权限/无 hook）。
     */
    private const val FART_DEXFILE_SWITCH = "setFartDumpEnabled"

    /** 同一补丁注入 ActivityThread 的方法名（都带 FART 自家品牌，原版平台不会重名）。 */
    private val FART_ACTIVITYTHREAD_MARKERS = listOf(
        "runFartDump",
        "startFartDumpThreadIfNeeded",
        "readFartDumpSwitches",
        "getFartProcessDirectory",
    )

    /** 脱壳 ROM 的固定品牌串（ArkOS 把 ro.product.model 设成 `Ark脱壳机(…)`）。 */
    private const val ARK_ROM_MODEL_TOKEN = "Ark脱壳机"
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
            // 对齐 SOMCP v1.0.22：签名一致（expected 命中 actual）时，不再把「期望/实际」指纹行
            // 拼进给用户看的文案；只有二者“不一致”时才展示差异，避免“签名正常却弹出两行相同指纹”。
            val fingerprintsDiffer = result.expected.isNotBlank() &&
                result.actual.isNotEmpty() && result.actual.none { it == result.expected }
            val fpSuffix = if (fingerprintsDiffer) {
                " | expected=${result.expected.take(16)}… | " +
                    "actual=${result.actual.firstOrNull()?.take(16) ?: "-"}…"
            } else ""
            val line = "$stamp | ${result.reason}$fpSuffix | code=0x${result.integrityCode.toString(16)}"
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_FAILURE, line).apply()
            val logFp = if (fingerprintsDiffer) {
                "; expected=${result.expected}; actual=${result.actual.joinToString()}"
            } else ""
            AppLog.e(
                "Integrity check FAILED: ${result.reason}$logFp; integrityCode=0x${result.integrityCode.toString(16)}"
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
                // 对齐 SOMCP v1.0.22：pin 缺失 / 解码失败 = “无法判定是否可信” → fail-closed。
                //（旧实现返回 trusted=true 是 fail-open，会让门禁被静默绕过，这里改为按不安全处理。）
                Result(false, "no release signer pin configured", expected, actual, integrityCode = integrityCode)
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

    // 门禁判断处（对齐 SOMCP v1.0.22）：放行需同时满足「完整性结论可信」与「无运行时威胁」。
    // fail-closed：任何“无法判定”（探测被拒答 / 文件读不到 / 抛异常）一律按不安全处理，
    // 绝不默认放行；异常分支也绝不静默 return 允许（由 getOrDefault(false) 兜底）。
    // 运行时威胁探针已按上游 v1.0.22 收窄到低误报特征（见 procMapsIndicators）：
    // 正常设备（含常规 root / LSPosed / Shamiko 环境）不会再被误判为被注入。
    // 保持原设计意图：仅以签名校验结果为准。本 App 面向逆向场景，用户常开 root/frida/调试器，
    // runtimeThreats() 会误伤正常使用，故不参与“是否放行服务”的判定。
    //（对齐 SOMCP v1.0.22 的方向是「消除正常设备误报」，而不是把运行时探针升级成硬门禁。）
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
            // 对齐 SOMCP v1.0.22：签名信息被“拒答”（signingInfo 为 null，常见于应用列表被隐藏 /
            // hook 拦截）时，视为“无法判定” → 返回空列表，verify() 据此判为不可信（fail-closed）。
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
        // 对齐 SOMCP v1.0.22：fail-closed —— tracerPid() 在“无法判定”时返回 -1
        //（读不到 /proc/self/status 或抛异常），这里一律按“已附加 tracer”处理，绝不默认放行。
        val tracer = tracerPid()
        if (tracer > 0) threats += "native tracer attached"
        // ROM 级 ART/DEX 转储（FART/Ark 系）：平台被改，只能拒绝运行。
        fartDumpThreat(
            declaredMethodNames("dalvik.system.DexFile"),
            declaredMethodNames("android.app.ActivityThread"),
        )?.let { threats += it }
        arkUnpackRomThreat(Build.MODEL, Build.DISPLAY, Build.FINGERPRINT)?.let { threats += it }
        // 进程内 hook 帧（Xposed 系）与 PackageInfo.CREATOR 被替换（一键过签框架）。
        hookStackThreat()?.let { threats += it }
        creatorIntegrityThreat()?.let { threats += it }
        val maps = procMapsIndicators()
        if (maps.isNotEmpty()) threats += maps
        // 对齐 SOMCP v1.0.22：已删除 Frida 端口探活检测（openLocalInstrumentationPorts 置空）。
        return threats.toList()
    }

    // 对齐 SOMCP v1.0.22：fail-closed —— 读不到 /proc/self/status（被 hook 隐藏 / 异常）时，
    // 不再返回 0（=“无 tracer”= 放行），而返回 -1 表示“无法判定”，交由 runtimeThreats 按不安全处理。
    /**
     * 纯函数：只吃反射看到的两个方法名清单，便于离线固定规则。
     *
     * 恢复空列表（类没解析出来）时不报威胁——查不到不等于被改，误判比漏判更糟。
     */
    internal fun fartDumpThreat(dexFileMethods: List<String>, activityThreadMethods: List<String>): String? {
        if (FART_DEXFILE_SWITCH in dexFileMethods) return "ART runtime carries the FART/Ark DEX-dump switch"
        if (activityThreadMethods.any { it in FART_ACTIVITYTHREAD_MARKERS }) {
            return "framework carries the FART/Ark DEX-dump pipeline"
        }
        return null
    }

    /**
     * 同一 ROM 的身份兜底：dump 类被改名时 build.prop 仍留着品牌串。
     * 单独看比 [fartDumpThreat] 弱（build.prop 谁都能改），但只花三次字符串比较。
     */
    internal fun arkUnpackRomThreat(vararg identityFields: String?): String? =
        if (identityFields.any { field -> field != null && field.contains(ARK_ROM_MODEL_TOKEN) }) {
            "Ark unpacking ROM identity ($ARK_ROM_MODEL_TOKEN)"
        } else {
            null
        }

    /**
     * 进程内 hook 帧检测（Xposed / libxposed 系）。
     *
     * 只比对**包前缀**：`de.robv.android.xposed.` 与 `io.github.libxposed.` 是已知 hook API
     * 的包面，普通应用栈帧不可能命中；命中只说明「本进程确实被 hook 了」，与「设备上装了
     * 模块」无关（LSPosed 只注入被勾选的应用）。
     */
    internal fun hookFrameThreat(frameClassNames: List<String>): String? {
        val prefixes = listOf(
            "de.robv.android.xposed.",
            "io.github.libxposed.",
        )
        val hit = prefixes.firstOrNull { prefix -> frameClassNames.any { it.startsWith(prefix) } } ?: return null
        return "in-process hook frames from $hit"
    }

    private fun hookStackThreat(): String? = runCatching {
        val frames = Thread.getAllStackTraces().values.flatMap { stack -> stack.map { it.className } }
        hookFrameThreat(frames)
    }.getOrNull()

    /**
     * `PackageInfo.CREATOR` 是否被换成外来类（SoLab/DPatch/LSPatch 一类过签框架的做法：
     * 换掉 Creator，让每次 getPackageInfo() 反序列化时都被改写成「原版签名」）。
     *
     * 判据是 **classLoader 身份** 而不是「classLoader 为 null」：真 Creator 是匿名类
     * `PackageInfo$1`，嵌套类由定义外层类的同一个 loader 定义，所以唯一在所有 ROM 上都成立
     * 的不变式是 `CREATOR.classLoader === PackageInfo.classLoader`；用 null 判据会在
     * ART 模块 / APEX / 定制 framework 的机器上把正版也判成被改。
     */
    private fun creatorIntegrityThreat(): String? = runCatching {
        val creator = PackageInfo::class.java.getField("CREATOR").get(null) ?: return@runCatching "PackageInfo.CREATOR is null"
        if (creator.javaClass.classLoader !== PackageInfo::class.java.classLoader) {
            "PackageInfo.CREATOR replaced by a foreign classloader"
        } else {
            null
        }
    }.getOrNull()

    /** [className] 声明的全部方法名；解析不到时返回空列表（不触发初始化）。 */
    private fun declaredMethodNames(className: String): List<String> = runCatching {
        Class.forName(className, false, IntegrityGuard::class.java.classLoader).declaredMethods.map { it.name }
    }.getOrDefault(emptyList())

    private fun tracerPid(): Int = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("TracerPid:") }
                ?.substringAfter(':')
                ?.trim()
                ?.toIntOrNull() ?: 0
        }
    }.getOrDefault(0)

    // 对齐 SOMCP v1.0.22：删除 lsposed/gadget/frida/xposed 子串，消除正常设备误报。
    // 只保留“强特征”：Frida 的 GumJS 运行时线程名，仅当 Frida 真正注入本进程时才会出现，
    // 正常设备（含常规 root / LSPosed / Shamiko 环境）不会命中。
    // fail-closed：/proc/self/maps 读不到 / 被 hook 隐藏（“拒答”）时不再返回空（=放行），
    // 而是返回威胁标记，按不安全处理。
    private fun procMapsIndicators(): List<String> = runCatching {
        val needles = listOf("gum-js-loop")
        val hits = linkedSetOf<String>()
        File("/proc/self/maps").useLines { lines ->
            lines.take(8_000).forEach { line ->
                val lower = line.lowercase()
                needles.firstOrNull { lower.contains(it) }?.let { hits += "runtime hook artifact: $it" }
            }
        }
        hits.toList()
    }.getOrElse {
        listOf("runtime probe unavailable (fail-closed: /proc/self/maps unreadable)")
    }

    // 对齐 SOMCP v1.0.22：删除 Frida 默认端口的回环 TCP 探活检测。
    // 该探活在本机回环端口被代理/占用时极易误报“被注入”，收益低于误报代价，故整体移除。
    // 保留函数符号以维持函数名集合不变；返回空列表表示未发现端口威胁。
    private fun openLocalInstrumentationPorts(): List<Int> = emptyList()

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
