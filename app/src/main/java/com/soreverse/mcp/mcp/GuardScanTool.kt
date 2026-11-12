package com.soreverse.mcp.mcp

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.soreverse.mcp.core.GuardSignatures
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * 反分析 / 加固扫描：把一个 APK（或单 dex / 单 so / decode 目录）里所有"拦路"的东西列出来，
 * 并按类别给出处置建议。
 *
 * 和 taffy_so_hardening 的区别：那个只看 so 的加固特征（段属性、符号剥离），
 * 这个看的是**行为层**——目标自己会不会检测调试器 / Hook 框架 / 模拟器 / 改包，
 * 以及它被哪家加固厂商处理过、类名混淆到什么程度。
 */
object GuardScanTool {

    private const val MAX_DEX_BYTES = 96 * 1024 * 1024L
    private const val MAX_SO_SAMPLE = 32 * 1024 * 1024L
    private const val DEFAULT_MAX_STRINGS = 200_000

    val guardScan: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_guard_scan",
            "【反分析扫描】扫出目标里的拦路货：签名校验 / 完整性校验 / 反调试 / 模拟器检测 / Hook 框架检测(Frida·Xposed·Substrate) / Root 检测 / VMP / 加固厂商(爱加密·梆梆·百度·乐固·360·阿里·易盾·顶象·娜迦) / 资源加密 / 类名混淆度，每条都带处置建议。path 支持 APK、dex、so 或 decode 目录。",
            "Scan an APK/DEX/SO for anti-analysis guards: signature & integrity checks, anti-debug, emulator, Frida/Xposed/Substrate detection, root checks, VMP, packer identity, encrypted resources, class-name obfuscation — each with advice.",
            "analyze", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "path" str "目标路径：APK 文件 / classes.dex / *.so / decode 目录（必填）"
                "scope".oneOf("扫描范围：all(默认) | apk(只看包结构+加固) | dex(只看代码层) | so(只看 native)", "all", "apk", "dex", "so")
                "maxStrings" int "每个 dex 最多扫多少条字符串（默认 200000，越大越全越慢）"
                "deep" str "true=连 lib/**/*.so 也做字节特征扫描（默认 true）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path", "path", "")
            val target = File(path)
            if (!target.exists()) return err("FILE_NOT_FOUND", "路径不存在: $path", "path", path)
            val scope = args.str("scope", "all").ifBlank { "all" }
            val maxStrings = args.intValue("maxStrings", DEFAULT_MAX_STRINGS).coerceIn(1000, 2_000_000)
            val deep = args.str("deep", "true").ifBlank { "true" }.toBoolean()

            return runCatching { scan(target, scope, maxStrings, deep) }
                .getOrElse { err("SCAN_FAILED", "扫描失败: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    private fun scan(target: File, scope: String, maxStrings: Int, deep: Boolean): JSONObject {
        val findings = ArrayList<JSONObject>()
        val packers = ArrayList<JSONObject>()
        val stringsScanned = IntArray(1)
        var arscJson: JSONObject? = null
        var obfJson: JSONObject? = null
        val soSeen = ArrayList<String>()
        val dexSeen = ArrayList<String>()
        val classNames = ArrayList<String>()

        when {
            target.isDirectory -> scanDirectory(target, scope, maxStrings, deep, findings, packers, classNames, stringsScanned, soSeen, dexSeen)
            isZip(target) && target.name.lowercase().let { it.endsWith(".apk") || it.endsWith(".zip") } ->
                ZipFile(target).use { z ->
                    val names = z.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
                    if (scope == "all" || scope == "apk") {
                        collectPackers(names, packers)
                        arscJson = readArscVerdict(z, names)
                    }
                    if (scope == "all" || scope == "dex") {
                        scanDexInZip(target, maxStrings, findings, classNames, stringsScanned, dexSeen)
                    }
                    if ((scope == "all" || scope == "so") && deep) {
                        scanSoInZip(z, names, findings, soSeen)
                    }
                }
            target.name.lowercase().endsWith(".dex") -> {
                scanDexFile(target, maxStrings, findings, classNames, stringsScanned, dexSeen)
            }
            isElf(target) -> {
                scanSoFile(target, findings, soSeen)
            }
            else -> return err("UNSUPPORTED", "不认识的输入类型（支持 APK/zip、dex、so、decode 目录）: ${target.name}", "path", target.absolutePath)
        }

        if (classNames.isNotEmpty()) obfJson = obfuscationJson(classNames)

        val byCategory = LinkedHashMap<String, Int>()
        for (i in 0 until findings.length()) {
            val cat = findings.getJSONObject(i).optString("category")
            byCategory[cat] = (byCategory[cat] ?: 0) + 1
        }
        val advice = LinkedHashSet<String>()
        for (i in 0 until findings.length()) advice.add(findings.getJSONObject(i).optString("advice"))
        if (packers.length() > 0) advice.add("目标被加固过：直接改包会踩壳的完整性校验，先考虑壳内注入（Frida gadget）或脱壳")
        if (obfJson != null && obfJson!!.optInt("score") >= 70) advice.add("类名高度混淆：按类名/字符串定位逻辑几乎不可行，优先按调用点或运行时 hook")

        val result = JSONObject()
            .put("path", target.absolutePath)
            .put("kind", kindOf(target))
            .put("findings", JSONArray().apply { for (f in findings) put(f) })
            .put("categories", JSONObject().apply { for ((k, v) in byCategory) put(k, v) })
            .put("packers", JSONArray().apply { for (p in packers) put(p) })
            .put("advice", JSONArray().apply { for (a in advice) put(a) })
            .put("scanned", JSONObject()
                .put("dex", JSONArray().apply { for (d in dexSeen) put(d) })
                .put("so", JSONArray().apply { for (s in soSeen) put(s) })
                .put("strings", stringsScanned[0])
                .put("classes", classNames.size))
        arscJson?.let { result.put("resourcesArsc", it) }
        obfJson?.let { result.put("obfuscation", it) }
        if (findings.isEmpty() && packers.isEmpty()) {
            result.put("summary", "没有命中已知的反分析特征（不代表一定没有：自研/花指令型的检测需要动态跟踪）")
        }
        return ok(result)
    }

    // ────────── APK / 目录 ──────────

    private fun scanDirectory(
        dir: File, scope: String, maxStrings: Int, deep: Boolean,
        findings: MutableList<JSONObject>, packers: MutableList<JSONObject>,
        classNames: MutableList<String>, stringsScanned: IntArray,
        soSeen: MutableList<String>, dexSeen: MutableList<String>,
    ) {
        val all = ArrayList<File>()
        dir.walkTopDown().maxDepth(6).forEach { if (it.isFile) all.add(it) }
        if (scope == "all" || scope == "apk") {
            val rel = all.map { it.relativeTo(dir).invariantSeparatorsPath }
            collectPackers(rel, packers)
            val arsc = File(dir, "resources.arsc")
            if (arsc.isFile && arsc.length() > 0) {
                val head = runCatching {
                    arsc.inputStream().use { readUpTo(it, 256) }
                }.getOrNull() ?: ByteArray(0)
                val plain = GuardSignatures.isPlainArsc(head)
                if (!plain) {
                    findings.add(JSONObject()
                        .put("category", "arsc")
                        .put("label", "资源表异常")
                        .put("evidence", "resources.arsc 头部不是合法明文旅表")
                        .put("where", "resources.arsc")
                        .put("advice", "资源被加密或被壳接管：资源定位/替换会失败，改用运行时读取"))
                }
            }
        }
        for (f in all) {
            val name = f.name.lowercase()
            when {
                (scope == "all" || scope == "dex") && name.endsWith(".dex") ->
                    runCatching { scanDexFile(f, maxStrings, findings, classNames, stringsScanned, dexSeen) }
                (scope == "all" || scope == "so") && deep && isElf(f) ->
                    runCatching { scanSoFile(f, findings, soSeen) }
            }
        }
    }

    private fun collectPackers(names: List<String>, out: MutableList<JSONObject>) {
        for ((packer, hits) in GuardSignatures.detectPackers(names)) {
            out.add(JSONObject()
                .put("name", packer)
                .put("evidence", JSONArray().apply { for (h in hits) put(h) })
                .put("advice", "被 $packer 加固：直接改包会踩完整性校验，先考虑壳内注入或脱壳"))
        }
    }

    private fun readArscVerdict(z: ZipFile, names: List<String>): JSONObject? {
        val entry = names.firstOrNull { it == "resources.arsc" } ?: return null
        val head = z.getInputStream(z.getEntry(entry)).use { readUpTo(it, 256) }
        val plain = GuardSignatures.isPlainArsc(head)
        return JSONObject()
            .put("plain", plain)
            .put("maybeEncrypted", !plain && GuardSignatures.looksEncrypted(head))
            .put("advice", if (plain) "" else "resources.arsc 头部不是合法的明文旅表：资源被加密或被壳接管，资源定位/替换会失败")
    }

    // ────────── DEX ──────────

    private fun scanDexInZip(
        apk: File, maxStrings: Int, findings: MutableList<JSONObject>,
        classNames: MutableList<String>, stringsScanned: IntArray, dexSeen: MutableList<String>,
    ) {
        val container = runCatching { DexFileFactory.loadDexContainer(apk, Opcodes.getDefault()) }.getOrNull() ?: return
        for (dexName in container.dexEntryNames) {
            val dexFile = runCatching { container.getEntry(dexName)?.dexFile }.getOrNull() ?: continue
            dexSeen.add(dexName)
            collectFromDex(dexFile, maxStrings, findings, classNames, stringsScanned, dexName)
        }
    }

    private fun scanDexFile(
        dex: File, maxStrings: Int, findings: MutableList<JSONObject>,
        classNames: MutableList<String>, stringsScanned: IntArray, dexSeen: MutableList<String>,
    ) {
        if (dex.length() > MAX_DEX_BYTES) return
        val dexFile = runCatching { DexFileFactory.loadDexFile(dex, Opcodes.getDefault()) }.getOrNull() ?: return
        dexSeen.add(dex.name)
        collectFromDex(dexFile, maxStrings, findings, classNames, stringsScanned, dex.name)
    }

    private fun collectFromDex(
        dexFile: com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile,
        maxStrings: Int, findings: MutableList<JSONObject>,
        classNames: MutableList<String>, stringsScanned: IntArray, where: String,
    ) {
        runCatching {
            for (cls in dexFile.classes) {
                classNames.add(cls.type)
                if (classNames.size > 400_000) break
            }
        }
        val refs = runCatching { dexFile.stringReferences }.getOrNull() ?: return
        var n = 0
        val matched = LinkedHashMap<String, JSONObject>()
        runCatching {
            for (ref in refs) {
                if (n >= maxStrings) break
                val s = runCatching { ref.string }.getOrNull() ?: continue
                n++
                for (hit in GuardSignatures.scanText(s, hitLimit = 6)) {
                    val key = hit.category + "|" + hit.evidence
                    if (!matched.containsKey(key)) {
                        matched[key] = JSONObject()
                            .put("category", hit.category)
                            .put("label", hit.label)
                            .put("evidence", hit.evidence)
                            .put("where", "$where 字符串池")
                            .put("advice", hit.advice)
                    }
                }
            }
        }
        stringsScanned[0] += n
        findings.addAll(matched.values)
    }

    // ────────── SO ──────────

    private fun scanSoInZip(z: ZipFile, names: List<String>, findings: MutableList<JSONObject>, soSeen: MutableList<String>) {
        for (name in names) {
            if (!name.startsWith("lib/") || !name.endsWith(".so")) continue
            if (soSeen.size >= 40) break
            val bytes = runCatching {
                z.getInputStream(z.getEntry(name)).use { readUpTo(it, MAX_SO_SAMPLE) }
            }.getOrNull() ?: continue
            if (bytes.size < 64) continue
            soSeen.add(name)
            for (hit in GuardSignatures.scanBytes(bytes, hitLimit = 24)) {
                findings.add(JSONObject()
                    .put("category", hit.category)
                    .put("label", hit.label)
                    .put("evidence", hit.evidence)
                    .put("where", name)
                    .put("advice", hit.advice))
            }
        }
    }

    private fun scanSoFile(so: File, findings: MutableList<JSONObject>, soSeen: MutableList<String>) {
        if (so.length() > MAX_SO_SAMPLE) return
        val bytes = runCatching { so.readBytes() }.getOrNull() ?: return
        soSeen.add(so.name)
        for (hit in GuardSignatures.scanBytes(bytes, hitLimit = 24)) {
            findings.add(JSONObject()
                .put("category", hit.category)
                .put("label", hit.label)
                .put("evidence", hit.evidence)
                .put("where", so.name)
                .put("advice", hit.advice))
        }
    }

    // ────────── 工具函数 ──────────

    private fun obfuscationJson(classNames: List<String>): JSONObject {
        val s = GuardSignatures.obfuscationScore(classNames)
        return JSONObject()
            .put("score", s.score)
            .put("level", s.level)
            .put("classCount", s.classCount)
            .put("shortNameRatio", round3(s.shortRatio))
            .put("junkNameRatio", round3(s.junkRatio))
            .put("sample", JSONArray().apply { for (x in s.sample) put(x) })
    }

    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0

    private fun kindOf(f: File): String = when {
        f.isDirectory -> "directory"
        f.name.lowercase().endsWith(".dex") -> "dex"
        isElf(f) -> "so"
        f.name.lowercase().let { it.endsWith(".apk") || it.endsWith(".zip") } -> "apk"
        else -> "unknown"
    }

    private fun isElf(f: File): Boolean {
        if (!f.isFile || f.length() < 4) return false
        return runCatching {
            f.inputStream().use { ins ->
                val b = ByteArray(4)
                if (ins.read(b) != 4) return false
                b[0].toInt() == 0x7F && b[1].toInt() == 0x45 && b[2].toInt() == 0x4C && b[3].toInt() == 0x46
            }
        }.getOrDefault(false)
    }

    private fun isZip(f: File): Boolean {
        if (!f.isFile || f.length() < 4) return false
        return runCatching {
            f.inputStream().use { ins ->
                val b = ByteArray(4)
                if (ins.read(b) != 4) return false
                b[0].toInt() == 0x50 && b[1].toInt() == 0x4B && b[2].toInt() == 0x03 && b[3].toInt() == 0x04
            }
        }.getOrDefault(false)
    }

    /** 有界读取：绝不因为输入是超大文件而把内存吃穿。 */
    private fun readUpTo(input: InputStream, max: Long): ByteArray {
        val out = ByteArrayOutputStream(8192)
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (total < max) {
            val want = minOf(buf.size.toLong(), max - total).toInt()
            val n = input.read(buf, 0, want)
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    val ALL = listOf(guardScan)
}
