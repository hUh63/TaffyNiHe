package com.soreverse.mcp.core

/**
 * 反分析特征库：反调试 / 模拟器 / Hook 框架 / Root / 加固 / 混淆 的识别规则。
 *
 * 纯逻辑、零 Android 依赖，便于单测。[scanText] 与 [scanBytes] 用同一套 needle，
 * 只是输入形态不同（字节输入按 ISO-8859-1 单字节映射成字符串，长度与偏移一一对应，
 * 不会因多字节字符错位）。
 */
object GuardSignatures {

    /** 一类特征的规则。 */
    data class Category(
        val id: String,
        val label: String,
        val advice: String,
        val needles: List<String>,
    )

    /** 一条命中。[count] 是该特征在输入里出现的次数（用于区分"用了"和"检测"）。 */
    data class Hit(
        val category: String,
        val label: String,
        val evidence: String,
        val advice: String,
        val count: Int = 1,
    )

    /**
     * 组合规则：单个关键词几乎必然误报（逆向工具自己就带 frida 字样），
     * 必须几个特征同时出现才算"目标在检测"。
     */
    data class Combo(
        val id: String,
        val label: String,
        val all: List<String>,
        val advice: String,
    )

    /** 混淆评分结果。 */
    data class ObfuscationScore(
        val score: Int,
        val level: String,
        val classCount: Int,
        val shortRatio: Double,
        val junkRatio: Double,
        val sample: List<String>,
    )

    val CATEGORIES: List<Category> = listOf(
        Category(
            "signature", "签名校验",
            "改包后校验失败会直接退出：先定位校验调用点再 patch，或用同一证书重签",
            listOf(
                "getPackageInfo", "GET_SIGNATURES", "getSigningInfo", "toCharsString",
                "getCertificateChain", "GET_SIGNING_CERTIFICATES", "signatures",
            ),
        ),
        Category(
            "checksum", "完整性/校验和",
            "文件 CRC/MD5/资源自检：patch 后要同步改期望值，或把比较分支改成恒等",
            listOf(
                "CRC32", "checksum", "MessageDigest", "SHA-1", "SHA-256", "MD5",
                "getAssets().open", "ZipEntry",
            ),
        ),
        Category(
            "anti_debug", "反调试",
            "读 TracerPid / ptrace / isDebuggerConnected：hook 或 patch 掉检测函数后再附加",
            listOf(
                "TracerPid", "ptrace", "isDebuggerConnected", "/proc/self/status",
                "PTRACE_TRACEME", "android.os.Debug",
            ),
        ),
        Category(
            "emulator", "模拟器检测",
            "只放行真机：hook 设备属性返回、或改判定分支",
            listOf(
                "goldfish", "ranchu", "qemu", "genymotion", "bluestacks", "nox",
                "vbox86", "ro.kernel.qemu", "ro.build.tags", "Build.FINGERPRINT",
            ),
        ),
        Category(
            "hook", "Hook 框架检测",
            "检测 Frida/Xposed/Substrate：注入前先确认目标是否会顺手抓注入痕迹",
            listOf(
                "XposedBridge", "de.robv.android.xposed", "de.robv", "substrate",
                "frida", "gum-js", "linjector", "libfrida", "frida_agent",
                "re.frida.server", "dl_iterate_phdr",
            ),
        ),
        Category(
            "root", "Root 检测",
            "扫 su/Magisk 痕迹：root 设备上可能直接拒跑",
            listOf(
                "/system/bin/su", "/system/xbin/su", "magisk", "Superuser.apk",
                "busybox", "test-keys", "/su/bin",
            ),
        ),
        Category(
            "vmp", "VMP/字节码加密",
            "关键方法被虚拟化或抽取，静态看不到真实逻辑：需要内存 dump 后修复 dex",
            listOf(
                "libDexHelper", "libjiagu", "libshell", "libmobisec", "libexec.so",
                "libprotectClass", "libnesec", "libandsec",
            ),
        ),
    )

    /**
     * 加固厂商 -> 其特征资产/库文件。命中即说明 APK 被该厂商加固，
     * 直接改包会破坏壳的完整性校验，需要先脱壳或走「壳内注入」。
     */
    val PACKERS: List<Pair<String, List<String>>> = listOf(
        "爱加密" to listOf("assets/ijiami.dat", "assets/ijiami.ajm", "assets/ijiami.ajm.dat"),
        "梆梆加固" to listOf("assets/secData0.dat", "assets/bangcle_classes.jar", "assets/libBDSec.so"),
        "百度加固" to listOf("assets/baiduprotect.jar", "assets/baiduprotect1.jar"),
        "腾讯乐固" to listOf("assets/0OO00l111l1l", "assets/tosversion", "assets/classes.jar.locked"),
        "360加固" to listOf("assets/libprotect.so", "assets/libprotectClass.so", "assets/storedata"),
        "阿里聚安全" to listOf("assets/ali.dat", "assets/libmobisec.so"),
        "网易易盾" to listOf("assets/nedsec.dat"),
        "顶象" to listOf("assets/dingxiang.dat", "assets/dxlib.so"),
        "娜迦" to listOf("assets/libchaosvmp.so", "assets/libchaos.so"),
    )

    /**
     * 检测型组合特征。这是和上面 CATEGORIES 的关键区别：
     * CATEGORIES 回答"出现了什么词"，COMBO 回答"它是不是真的在检测你"。
     */
    val COMBOS: List<Combo> = listOf(
        Combo(
            "frida_detect_maps", "Frida 检测（扫 maps）",
            listOf("/proc/self/maps", "frida"),
            "目标会读 /proc/self/maps 找 frida 关键字：注入前先 hook open/read 或改名 gadget",
        ),
        Combo(
            "frida_detect_phdr", "Frida 检测（dl_iterate_phdr）",
            listOf("dl_iterate_phdr", "frida"),
            "目标遍历已加载模块找 frida：gadget 需改名并去掉特征串",
        ),
        Combo(
            "frida_detect_port", "Frida 检测（端口扫描）",
            listOf("/proc/net/tcp", "27042"),
            "目标扫 27042/27043 端口：改用非默认端口（gadget config 里 listen 改成别的）",
        ),
        Combo(
            "debugger_detect", "调试器检测（TracerPid）",
            listOf("/proc/self/status", "TracerPid"),
            "目标读 TracerPid 判断被调试：附加前先 patch 读取处或 hook open",
        ),
        Combo(
            "emulator_detect", "模拟器检测（组合）",
            listOf("goldfish", "ro.kernel.qemu"),
            "目标组合多个特征判断模拟器：真机验证，或 hook 属性返回",
        ),
        Combo(
            "xposed_detect", "Xposed 检测",
            listOf("XposedBridge", "ClassLoader"),
            "目标用 ClassLoader 找 XposedBridge：改用非 Xposed 方案（gadget）",
        ),
        Combo(
            "signature_check", "签名校验（取签名+比对）",
            listOf("getPackageInfo", "signatures"),
            "目标会取签名做比对：改包必须用同证书重签，或 patch 比对逻辑",
        ),
        Combo(
            "packer_loaded", "壳已加载（内存 dex）",
            listOf("DexClassLoader", "getDex"),
            "目标运行时动态加载 dex（壳）：静态改 dex 无效，需要 dump 内存 dex",
        ),
    )

    /** Frida 默认监听端口（frida-server / gadget 的默认值，目标常拿它做检测）。 */
    val FRIDA_PORTS: List<String> = listOf("27042", "27043")

    /** 常见框架包前缀：统计混淆率时排除，否则第三方库会把分数拉低。 */
    val DEX_FRAMEWORK_PREFIXES: List<String> = listOf(
        "Ljava/", "Ljavax/", "Lkotlin/", "Lkotlinx/", "Lcom/android/", "Ldalvik/",
        "Lokio/", "Lokhttp3/", "Lcom/google/", "Lcom/squareup/", "Lorg/junit/",
        "Ljunit/", "Lorg/intellij/", "Lorg/jetbrains/", "Lio/flutter/",
        "Landroid/support/", "Lcom/facebook/", "Lcom/bumptech/", "Landroidx/",
        "Lorg/json/", "Lorg/apache/", "Lorg/w3c/", "Lorg/xml/",
    )

    private const val HIT_LIMIT = 40

    /** 扫描任意文本（dex 字符串池、manifest 文本、网络状态文件内容）。 */
    fun scanText(text: String, categories: List<Category> = CATEGORIES, hitLimit: Int = HIT_LIMIT): List<Hit> {
        val out = ArrayList<Hit>()
        for (c in categories) {
            var found = 0
            for (n in c.needles) {
                if (found >= 3) break
                val cnt = countOf(text, n)
                if (cnt > 0) {
                    out.add(Hit(c.id, c.label, n, c.advice, cnt))
                    found++
                }
                if (out.size >= hitLimit) return out
            }
        }
        return out
    }

    /**
     * 扫描二进制（so/dex 原始字节）。按 ISO-8859-1 映射成字符串后复用 [scanText] 的规则，
     * 单字节一一对应，不会因为编码问题漏判。
     */
    fun scanBytes(bytes: ByteArray, categories: List<Category> = CATEGORIES, hitLimit: Int = HIT_LIMIT): List<Hit> {
        if (bytes.isEmpty()) return emptyList()
        return scanText(String(bytes, Charsets.ISO_8859_1), categories, hitLimit)
    }

    /** 按 APK 条目名识别加固厂商，返回 厂商 -> 命中条目。 */
    fun detectPackers(entryNames: List<String>): List<Pair<String, List<String>>> {
        val lower = entryNames.map { it.lowercase() }
        val out = ArrayList<Pair<String, List<String>>>()
        for ((packer, sigs) in PACKERS) {
            val hits = sigs.filter { s -> lower.any { it == s.lowercase() } }
            if (hits.isNotEmpty()) out.add(packer to hits)
        }
        return out
    }

    /**
     * 混淆评分：只看非框架类。类名末段长度 <= 2 记为短名，符合「1~2 个字母 + 可选数字」
     * 记为无意义名。两者占比越高，说明类名被 R8/混淆器重命名得越彻底。
     */
    fun obfuscationScore(classNames: List<String>): ObfuscationScore {
        var total = 0
        var shortCount = 0
        var junkCount = 0
        val sample = ArrayList<String>()
        for (raw in classNames) {
            if (DEX_FRAMEWORK_PREFIXES.any { raw.startsWith(it) }) continue
            val path = raw.removePrefix("L").removeSuffix(";")
            val simple = path.substringAfterLast('/')
            if (simple.isEmpty()) continue
            total++
            if (simple.length <= 2) {
                shortCount++
                if (sample.size < 8) sample.add(path)
            }
            if (isJunkName(simple)) junkCount++
        }
        if (total == 0) return ObfuscationScore(0, "none", 0, 0.0, 0.0, emptyList())
        val shortRatio = shortCount.toDouble() / total
        val junkRatio = junkCount.toDouble() / total
        val score = Math.round(shortRatio * 60 + junkRatio * 40).toInt().coerceIn(0, 100)
        val level = when {
            score >= 70 -> "high"
            score >= 40 -> "medium"
            score >= 15 -> "low"
            else -> "none"
        }
        return ObfuscationScore(score, level, total, shortRatio, junkRatio, sample)
    }

    /** 1~2 个字母，可选跟随数字（a / ab / a1 / zz9）。 */
    private fun isJunkName(name: String): Boolean {
        var letters = 0
        var i = 0
        while (i < name.length) {
            val c = name[i]
            if (c in 'a'..'z' || c in 'A'..'Z') {
                letters++
                if (letters > 2) return false
            } else if (c !in '0'..'9') {
                return false
            }
            i++
        }
        return letters in 1..2
    }

    /**
     * resources.arsc 是否是可解析的明文资源表：类型 0x0002(RES_TABLE_TYPE) + 头部大小 0x000C
     * + 包数 > 0。加固/加密过的 APK 这里会被打乱，读出来不是这个形状。
     */
    fun isPlainArsc(head: ByteArray): Boolean {
        if (head.size < 12) return false
        val type = (head[0].toInt() and 0xFF) or ((head[1].toInt() and 0xFF) shl 8)
        val headerSize = (head[2].toInt() and 0xFF) or ((head[3].toInt() and 0xFF) shl 8)
        val packageCount = (head[8].toInt() and 0xFF) or ((head[9].toInt() and 0xFF) shl 8) or
            ((head[10].toInt() and 0xFF) shl 16) or ((head[11].toInt() and 0xFF) shl 24)
        return type == 0x0002 && headerSize >= 0x000C && packageCount > 0
    }

    /** 头部字节种类极多 → 疑似加密容器（明文 arsc/zip/elf 的头部字节都高度重复）。 */
    fun looksEncrypted(head: ByteArray): Boolean {
        if (head.size < 64) return false
        val seen = BooleanArray(256)
        var distinct = 0
        for (b in head) {
            val v = b.toInt() and 0xFF
            if (!seen[v]) {
                seen[v] = true
                distinct++
            }
        }
        return distinct >= 48
    }

    /** 统计出现次数（有上限，避免超长文本里数数本身成为开销）。 */
    fun countOf(text: String, needle: String, cap: Int = 999): Int {
        if (needle.isEmpty()) return 0
        var idx = text.indexOf(needle)
        if (idx < 0) return 0
        var n = 0
        while (idx >= 0 && n < cap) {
            n++
            idx = text.indexOf(needle, idx + needle.length)
        }
        return n
    }

    /** 只返回"好几个特征同时出现"的组合命中。 */
    fun scanCombos(text: String, combos: List<Combo> = COMBOS): List<Hit> {
        val out = ArrayList<Hit>()
        for (c in combos) {
            var minCount = Int.MAX_VALUE
            var all = true
            for (k in c.all) {
                val n = countOf(text, k, 999)
                if (n == 0) {
                    all = false
                    break
                }
                if (n < minCount) minCount = n
            }
            if (all) out.add(Hit("detect", c.label, c.all.joinToString(" + "), c.advice, minCount))
        }
        return out
    }

    /** 从 /proc/net/tcp 这类文本里找 27042/27043 的十六进制形式（0x69A2 / 0x69A3 小端）。 */
    fun fridaPortHits(netText: String): List<String> {
        val hexes = listOf("69A2" to "27042", "69A3" to "27043")
        val out = ArrayList<String>()
        for ((hex, port) in hexes) {
            if (netText.contains(hex, ignoreCase = true)) out.add(port)
        }
        return out
    }
}
