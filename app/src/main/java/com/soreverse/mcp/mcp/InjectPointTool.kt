package com.soreverse.mcp.mcp

import com.reandroid.apk.ApkModule
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import com.soreverse.mcp.engine.ElfParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * 注入点扫描：一个 APK 能往哪儿塞代码，先把候选位置和理由摊开。
 *
 * 三类注入点：
 *  1. manifest_application —— Application 子类，进程起来第一个执行的业务代码，
 *     在它的 attachBaseContext/onCreate 里 loadLibrary 最不容易被时序问题打掉；
 *  2. launcher_activity —— 没有自定义 Application 时的退路；
 *  3. native_lib —— lib/<abi>/ 目录下的 .so，含 JNI_OnLoad 的库适合 gadget 同目录落地或 so 级注入。
 *
 * 扫描只读、不改任何东西；真正的落地交给 taffy_apk_frida_gadget / taffy_so_inject_code。
 */
object InjectPointTool {

    private const val MAX_SO_BYTES = 64L * 1024 * 1024
    private const val MAX_JNI_LISTED = 40

    val injectPoints: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_apk_inject_points",
            "【注入点扫描】列出 APK 里可用的代码注入点：Application（最稳）、launcher Activity、带 JNI_OnLoad 的 native 库、可 hook 的 JNI 导出函数，每个点给出「为什么选它 / 用哪个工具落地」。同时报告 packageName / debuggable / extractNativeLibs / ABI 集合，判断该走 gadget 内置还是 so 注入。只读不改包。",
            "Enumerate code-injection points in an APK (Application, launcher Activity, native libs with JNI_OnLoad, exported JNI functions) with rationale and next tool.",
            "analyze", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "path" str "APK 文件路径，或 apktool 解出的 decode 目录（必填）"
                "maxJni" int "每个 so 最多列多少个 JNI 导出函数（默认 40）"
                "abi" str "只看某个 ABI 的 so（如 arm64-v8a；留空=全部）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path", "path", "")
            val f = File(path)
            if (!f.exists()) return err("FILE_NOT_FOUND", "路径不存在: $path", "path", path)
            val maxJni = args.intValue("maxJni", MAX_JNI_LISTED).coerceIn(0, 500)
            val abiFilter = args.str("abi").trim()
            return runCatching { scan(f, maxJni, abiFilter) }
                .getOrElse { err("SCAN_FAILED", "扫描失败: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    private fun scan(target: File, maxJni: Int, abiFilter: String): JSONObject {
        val points = JSONArray()
        val warnings = JSONArray()
        val abis = LinkedHashSet<String>()
        val nativeLibs = ArrayList<Pair<String, ByteArray>>()

        val info: ManifestInfo = if (target.isDirectory) {
            val xml = File(target, "AndroidManifest.xml")
            if (!xml.isFile) return err("MANIFEST_MISSING", "decode 目录里没有 AndroidManifest.xml", "path", target.absolutePath)
            parseManifestText(xml.readText())
        } else {
            val apk = runCatching { ApkModule.loadApkFile(target) }.getOrNull()
                ?: return err("NOT_APK", "无法作为 APK 打开: ${target.name}", "path", target.absolutePath)
            parseManifestText(apk.androidManifest.toString())
        }

        // ── 1. manifest 级注入点 ──
        if (info.applicationClass.isNotBlank()) {
            points.put(JSONObject()
                .put("kind", "manifest_application")
                .put("target", info.applicationClass)
                .put("why", "进程启动后第一个执行的业务类，在其 attachBaseContext/onCreate 注入 loadLibrary 最稳，不怕 Activity 被壳替换")
                .put("how", "taffy_apk_frida_gadget(action=patch_entry) 或 taffy_smali_batch")
                .put("priority", 1))
        }
        if (info.launcherActivity.isNotBlank()) {
            points.put(JSONObject()
                .put("kind", "launcher_activity")
                .put("target", info.launcherActivity)
                .put("why", if (info.applicationClass.isBlank()) "没有自定义 Application，入口 Activity 是最早的业务代码" else "备用注入点（Application 被加固壳代理时可用）")
                .put("how", "taffy_apk_frida_gadget(action=patch_entry)")
                .put("priority", if (info.applicationClass.isBlank()) 1 else 2))
        }
        for (e in info.exportedActivities.take(10)) {
            points.put(JSONObject()
                .put("kind", "exported_activity")
                .put("target", e)
                .put("why", "可被外部拉起：可以指定启动它来触发注入路径，也方便 adb am start 调试")
                .put("how", "adb shell am start -n ${info.packageName}/${e}")
                .put("priority", 3))
        }

        // ── 2. native 库 ──
        if (target.isDirectory) {
            val libDir = File(target, "lib")
            if (libDir.isDirectory) {
                libDir.listFiles()?.sortedBy { it.name }?.forEach { abiDir ->
                    if (!abiDir.isDirectory) return@forEach
                    if (abiFilter.isNotBlank() && abiDir.name != abiFilter) return@forEach
                    abis.add(abiDir.name)
                    abiDir.listFiles()?.filter { it.isFile && it.name.endsWith(".so") }?.forEach { so ->
                        if (so.length() > MAX_SO_BYTES) return@forEach
                        val bytes = runCatching { so.readBytes() }.getOrNull() ?: return@forEach
                        nativeLibs.add("lib/${abiDir.name}/${so.name}" to bytes)
                    }
                }
            }
        } else {
            runCatching {
                ZipFile(target).use { z ->
                    val names = z.entries().asSequence()
                        .filter { !it.isDirectory && it.name.startsWith("lib/") && it.name.endsWith(".so") }
                        .map { it.name }.sorted().toList()
                    for (name in names) {
                        val parts = name.split('/')
                        if (parts.size < 3) continue
                        val abi = parts[1]
                        if (abiFilter.isNotBlank() && abi != abiFilter) continue
                        abis.add(abi)
                        val entry = z.getEntry(name) ?: continue
                        if (entry.size > MAX_SO_BYTES) continue
                        val bytes = z.getInputStream(entry).use { readUpTo(it, MAX_SO_BYTES) }
                        nativeLibs.add(name to bytes)
                    }
                }
            }
        }

        val jniSeen = ArrayList<String>()
        for ((name, bytes) in nativeLibs) {
            val elf = runCatching { ElfParser(bytes).parse() }.getOrNull()
            if (elf == null) {
                warnings.put("$name 不是可解析的 ELF（已加壳/加密？）")
                continue
            }
            val exports = elf.dynSymbols.filter { it.exported && it.name.isNotBlank() }
            val jni = exports.filter { it.name.startsWith("Java_") }
            val hasJniOnLoad = exports.any { it.name == "JNI_OnLoad" } || elf.symbols.any { it.exported && it.name == "JNI_OnLoad" }
            val point = JSONObject()
                .put("kind", "native_lib")
                .put("target", name)
                .put("arch", elf.architecture)
                .put("exportedCount", exports.size)
                .put("jniCount", jni.size)
                .put("hasJniOnLoad", hasJniOnLoad)
                .put("why", if (hasJniOnLoad)
                    "导出 JNI_OnLoad：库加载即执行，适合 gadget 同目录落地或 so 级注入（注意可能在 init_array 里有先跑的校验）"
                else "普通 native 库（无 JNI_OnLoad）：只能作为被 dlopen 的目标，注入价值低")
                .put("how", if (hasJniOnLoad) "taffy_apk_frida_gadget(action=place) 或 taffy_so_inject_code" else "taffy_so_decompile / taffy_read_disasm 先看实现")
                .put("priority", if (hasJniOnLoad) 2 else 4)
            points.put(point)
            if (maxJni > 0 && jni.isNotEmpty()) {
                val arr = JSONArray()
                for (sym in jni.take(maxJni)) {
                    arr.put(JSONObject()
                        .put("name", sym.name)
                        .put("addr", "0x" + java.lang.Long.toHexString(sym.value))
                        .put("size", sym.size))
                    if (jniSeen.size < 200) jniSeen.add("${name}:${sym.name}")
                }
                point.put("jniFunctions", arr)
            }
        }

        // ── 3. 建议 ──
        val recommend = JSONArray()
        if (info.extractNativeLibs == false) {
            recommend.put("extractNativeLibs=false：gadget 必须以未压缩方式落地才能被 dlopen 到，先改成 true（taffy_apk_frida_gadget 的 place 会自动改）")
        }
        if (info.debuggable) {
            recommend.put("目标 debuggable=true：可直接 run-as / adb 调试，动态分析阻力最小")
        }
        if (abis.isEmpty()) {
            recommend.put("没有 native 库：走纯 smali 注入（loadLibrary）或 Java 层 hook，gadget 需要自己带 so")
        } else {
            recommend.put("ABI 集合 ${abis.joinToString()}：gadget 要按每个 ABI 各放一份，否则在对应设备上会加载失败")
        }
        if (info.packageName.isNotBlank()) {
            recommend.put("注入后启动用：adb shell am start -n ${info.packageName}/${info.launcherActivity.ifBlank { "<launcher>" }}")
        }

        return ok(JSONObject()
            .put("path", target.absolutePath)
            .put("packageName", info.packageName)
            .put("applicationClass", info.applicationClass)
            .put("launcherActivity", info.launcherActivity)
            .put("debuggable", info.debuggable)
            .put("extractNativeLibs", info.extractNativeLibs ?: JSONObject.NULL)
            .put("activityCount", info.activityCount)
            .put("abis", JSONArray().apply { for (a in abis) put(a) })
            .put("points", points)
            .put("jniFunctionsSeen", jniSeen.size)
            .put("recommend", recommend)
            .put("warnings", warnings)
            .put("next", "选好注入点后：taffy_apk_frida_gadget(action=place) → patch_entry → taffy_apk_rebuild(build) → taffy_apk_sign"))
    }

    private data class ManifestInfo(
        val packageName: String,
        val applicationClass: String,
        val launcherActivity: String,
        val debuggable: Boolean,
        val extractNativeLibs: Boolean?,
        val activityCount: Int,
        val exportedActivities: List<String>,
    )

    /**
     * 从 manifest 文本里取注入相关字段。用正则而不是 DOM：这里只要四个属性，
     * 而 decode 目录与 APK 两条路径拿到的都是同一份 XML 文本。
     */
    private fun parseManifestText(xml: String): ManifestInfo {
        val pkg = Regex("package=\"([^\"]+)\"").find(xml)?.groupValues?.get(1).orEmpty()
        val appName = Regex("<application[^>]*android:name=\"([^\"]+)\"").find(xml)?.groupValues?.get(1).orEmpty()
        val debuggable = Regex("android:debuggable=\"(true|false)\"").find(xml)?.groupValues?.get(1) == "true"
        val extract = Regex("android:extractNativeLibs=\"(true|false)\"").find(xml)?.groupValues?.get(1)
        val extractBool = when (extract) {
            "true" -> true
            "false" -> false
            else -> null
        }
        val activityRegex = Regex("<activity\\b([^>]*)>")
        val allActivities = ArrayList<String>()
        val exported = ArrayList<String>()
        for (m in activityRegex.findAll(xml)) {
            val attrs = m.groupValues.getOrNull(1) ?: ""
            val name = Regex("android:name=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1) ?: continue
            allActivities.add(name)
            if (Regex("android:exported=\"true\"").containsMatchIn(attrs)) exported.add(name)
        }
        // launcher：text 里带 MAIN + LAUNCHER 的 activity 在它自己的 <intent-filter> 内，
        // 简化处理——取第一个 activity，它通常在 MAIN/LAUNCHER 块之前；再用文本块定位修正。
        var launcher = allActivities.firstOrNull().orEmpty()
        val blockRegex = Regex("<activity\\b([^>]*)>(.*?)</activity>", RegexOption.DOT_MATCHES_ALL)
        for (m in blockRegex.findAll(xml)) {
            val attrs = m.groupValues.getOrNull(1) ?: ""
            val body = m.groupValues.getOrNull(2) ?: ""
            if (body.contains("android.intent.category.LAUNCHER") && body.contains("android.intent.action.MAIN")) {
                val name = Regex("android:name=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)
                if (!name.isNullOrBlank()) {
                    launcher = name
                    break
                }
            }
        }
        return ManifestInfo(
            packageName = pkg,
            applicationClass = appName,
            launcherActivity = launcher,
            debuggable = debuggable,
            extractNativeLibs = extractBool,
            activityCount = allActivities.size,
            exportedActivities = exported,
        )
    }

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

    val ALL = listOf(injectPoints)
}
