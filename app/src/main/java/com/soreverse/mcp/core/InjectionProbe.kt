package com.soreverse.mcp.core

import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

// 注入痕迹探针（诊断用：只记录，不阻断）。
//
// 背景：本 App 的目标用户就是逆向人员，root / LSPosed / Frida 是常态而非异常。
// 因此本对象产出的全部结论不会进入完整性门禁判定、不杀进程、不拒绝启动服务，
// 也不写文件 / 不发网络 / 不弹窗。它只是「自查工具」：
// 用户想知道「我这个包是不是被 LSPatch 二次打包了、有没有被注入 LSPosed」时看一眼。
//
// 设计原则：全部 runCatching 包裹，任何异常都在内部吃掉，绝不冒泡影响主流程；
// 只在 IO 线程调用（detectAsync 自带 withContext(Dispatchers.IO)）；
// 结果做短 TTL 缓存，UI 反复重组时不会重复扫 ZIP。
object InjectionProbe {

    // severity 只有两档：info = 正常现象/仅告知，warn = 值得看一眼
    data class Finding(val kind: String, val detail: String, val severity: String)

    private const val SEV_INFO = "info"
    private const val SEV_WARN = "warn"

    // 每个关键字最多记录几个映射文件名，避免 detail 变成一坨
    private const val MAX_SAMPLES = 5

    // /proc/self/maps 行数上限（正常 3000 行以内，防止异常环境卡住）
    private const val MAX_MAPS_LINES = 40000

    // ZIP 条目遍历上限（正常包 3000 条以内）
    private const val MAX_ZIP_ENTRIES = 40000

    // 缓存有效期：包不会中途变，短缓存只为吸收 UI 重组与连续点击
    private const val CACHE_TTL_MS = 20_000L

    @Volatile private var cacheAt = 0L
    @Volatile private var cacheFindings: List<Finding>? = null

    // 检测项 1 的关键字：Xposed 系注入框架的典型映射名。
    // 注意这里刻意不含 frida / gadget：用户主动开 Frida 调试是正常工作流，
    // 把它算进来只会制造噪音（v1.3.102 也从完整性校验里删掉了同类子串）。
    private val MAPS_KEYWORDS = listOf(
        "lsposed", "edxposed", "xposed", "sandhook", "yuki", "riru", "zygisk",
    )

    // 检测项 2 的典型类名：能加载到说明有框架的类被塞进了当前 ClassLoader 体系。
    private val PROBE_CLASSES = listOf(
        "de.robv.android.xposed.XposedBridge",
        "de.robv.android.xposed.XposedHelpers",
        "de.robv.android.xposed.XposedInit",
        "com.elderdrivers.riru.edxp.config.EdXpConfig",
        "org.lsposed.lspd.nativebridge.HookBridge",
        "org.lspatch.loader.LSPApplication",
    )

    // 检测项 3 的 ZIP 条目标记（一律小写比较，ZIP 名大小写不可靠）
    private val REPACK_MARKERS = listOf(
        "assets/lspatch/",
        "meta-inf/lspatch",
        "assets/xposed_init",
        "meta-inf/xposed/",
    )

    // 说明：被 LSPatch 二次打包/被安装器改过位置时，base.apk 常出现在这些可写目录
    private val SUSPICIOUS_APK_PREFIXES = listOf(
        "/data/local/tmp", "/sdcard", "/storage/emulated", "/storage/self",
        "/tmp", "/cache", "/data/data/", "/data/user/0/", "/data/user_de/0/",
    )

    // 只读系统分区：系统集成预装（非注入）的合法位置
    private val SYSTEM_APK_PREFIXES = listOf(
        "/system/", "/system_ext/", "/product/", "/vendor/", "/odm/", "/apex/", "/oem/",
    )

    // 在 IO 线程执行全部检测。UI 侧直接调用这个即可，不要在组合期读。
    suspend fun detectAsync(context: Context): List<Finding> =
        withContext(Dispatchers.IO) { detect(context) }

    // 同步检测入口：内部逐项 runCatching，任何异常都退化为「该项无结论」。
    fun detect(context: Context): List<Finding> {
        val cached = cacheFindings
        val now = System.currentTimeMillis()
        if (cached != null && now - cacheAt < CACHE_TTL_MS) return cached

        val out = ArrayList<Finding>(8)
        out += runCatching { scanMaps() }.getOrDefault(emptyList())
        out += runCatching { scanClassLoader(context) }.getOrDefault(emptyList())
        out += runCatching { scanSelfApk(context) }.getOrDefault(emptyList())
        out += runCatching { scanFlags(context) }.getOrDefault(emptyList())

        cacheFindings = out
        cacheAt = now
        return out
    }

    // 一段可直接展示/复制的人话总结。同样不抛异常。
    fun summary(context: Context): String = runCatching {
        val fs = detect(context)
        val warn = fs.count { it.severity == SEV_WARN }
        buildString {
            if (fs.isEmpty()) {
                append("注入痕迹探针：未发现 Xposed / LSPatch 相关痕迹。")
            } else {
                append("注入痕迹探针：共 ").append(fs.size).append(" 项（warn ").append(warn)
                append(" / info ").append(fs.size - warn).append("）")
                for (f in fs) {
                    append('\n').append("· [").append(f.severity).append("] ")
                    append(f.kind).append(" — ").append(f.detail)
                }
            }
            append('\n').append("以上仅为诊断信息，不参与完整性判定，也不会阻断任何功能。")
        }
    }.getOrElse { e -> "注入痕迹探针：检测过程异常（已忽略，" + e.javaClass.simpleName + "）" }

    // ── 1. /proc/self/maps 里的 Xposed 系映射 ────────────────────────────
    // 只保留映射文件名（basename），不贴整行，也不泄露完整路径。
    private fun scanMaps(): List<Finding> {
        val hits = LinkedHashMap<String, LinkedHashSet<String>>()
        var lines = 0
        runCatching {
            File("/proc/self/maps").bufferedReader().use { br ->
                while (lines < MAX_MAPS_LINES) {
                    val line = br.readLine() ?: break
                    lines++
                    if (line.isEmpty()) continue
                    val lower = line.lowercase()
                    val matched = MAPS_KEYWORDS.firstOrNull { lower.contains(it) } ?: continue
                    val bucket = hits.getOrPut(matched) { LinkedHashSet() }
                    if (bucket.size >= MAX_SAMPLES) continue
                    val slash = line.indexOf('/')
                    val name = if (slash >= 0) line.substring(slash).trim().substringAfterLast('/') else ""
                    bucket.add(if (name.isEmpty()) "(匿名映射段)" else name)
                }
            }
        }
        if (hits.isEmpty()) return emptyList()
        val out = ArrayList<Finding>(hits.size)
        for ((kw, names) in hits) {
            out.add(Finding("xposed.maps", kw + " → " + names.joinToString(", "), SEV_INFO))
        }
        return out
    }

    // ── 2. ClassLoader 里能直接找到框架类 ────────────────────────────────
    // 多 ClassLoader 尝试：App 自身、系统、当前线程上下文。全部 runCatching，
    // NoClassDefFoundError / SecurityException 一律视为「未命中」。
    private fun scanClassLoader(context: Context): List<Finding> {
        val loaders = ArrayList<ClassLoader>(3)
        runCatching { context.classLoader }.getOrNull()?.let { loaders.add(it) }
        runCatching { ClassLoader.getSystemClassLoader() }.getOrNull()?.let { loaders.add(it) }
        runCatching { Thread.currentThread().contextClassLoader }.getOrNull()?.let { loaders.add(it) }
        if (loaders.isEmpty()) return emptyList()

        val found = LinkedHashSet<String>()
        for (ld in loaders) {
            for (cn in PROBE_CLASSES) {
                if (cn in found) continue
                val ok = runCatching { Class.forName(cn, false, ld) }.isSuccess
                if (ok) found.add(cn)
            }
        }
        if (found.isEmpty()) return emptyList()
        val out = ArrayList<Finding>(found.size)
        for (cn in found) {
            out.add(Finding("xposed.class", "可加载: " + cn, SEV_INFO))
        }
        return out
    }

    // ── 3. 自身 APK 的 LSPatch / 二次打包痕迹 ─────────────────────────────
    // 只看 base APK（split 不重复扫），只读 ZIP 中央目录的条目名，不解压、不读内容。
    private fun scanSelfApk(context: Context): List<Finding> {
        val src = runCatching { context.applicationInfo.sourceDir }.getOrNull()
        if (src.isNullOrEmpty()) return emptyList()
        val out = ArrayList<Finding>(2)

        val zipHits = LinkedHashSet<String>()
        runCatching {
            val f = File(src)
            if (!f.isFile || f.length() <= 0L) return@runCatching
            ZipFile(f).use { zip ->
                val e = zip.entries()
                var n = 0
                while (e.hasMoreElements() && n < MAX_ZIP_ENTRIES) {
                    val name = runCatching { e.nextElement().name }.getOrNull() ?: break
                    n++
                    val low = name.lowercase()
                    for (m in REPACK_MARKERS) {
                        if (low.startsWith(m) || low == m.trimEnd('/')) zipHits.add(m)
                    }
                    if (zipHits.size == REPACK_MARKERS.size) break
                }
            }
        }
        if (zipHits.isNotEmpty()) {
            out.add(Finding("repack.zip", "base.apk 内含 LSPatch/Xposed 标记: " + zipHits.joinToString(", "), SEV_WARN))
        }

        val path = src.replace('\\', '/')
        val suspicious = SUSPICIOUS_APK_PREFIXES.any { path.startsWith(it) }
        val normalInstall = path.startsWith("/data/app/") || path.startsWith("/mnt/expand/")
        val systemPart = SYSTEM_APK_PREFIXES.any { path.startsWith(it) }
        // /mnt/expand = 外置/adoptable 存储安装，属合法位置，不算异常
        if (suspicious && !path.startsWith("/mnt/expand/")) {
            out.add(Finding("repack.path", "base.apk 位于可写/临时目录: " + path, SEV_WARN))
        } else if (!normalInstall && !systemPart) {
            out.add(Finding("repack.path", "base.apk 不在 /data/app 下: " + path, SEV_WARN))
        }
        return out
    }

    // ── 4. 可调试 / 可备份 / testOnly 标记 ────────────────────────────────
    // 全是 info：调试版与允许备份对逆向工具来说是常见配置，不是威胁。
    private fun scanFlags(context: Context): List<Finding> {
        val flags = runCatching { context.applicationInfo.flags }.getOrNull() ?: return emptyList()
        val out = ArrayList<Finding>(3)
        if ((flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            out.add(Finding("flags.debuggable", "APK 带 debuggable 标记（可 attach 调试器）", SEV_INFO))
        }
        if ((flags and ApplicationInfo.FLAG_ALLOW_BACKUP) != 0) {
            out.add(Finding("flags.backup", "APK 允许 adb backup（FLAG_ALLOW_BACKUP）", SEV_INFO))
        }
        if ((flags and ApplicationInfo.FLAG_TEST_ONLY) != 0) {
            out.add(Finding("flags.testOnly", "APK 带 testOnly 标记（第三方安装器/重签常见）", SEV_INFO))
        }
        return out
    }

    // ── 5. Layout Inspect / 无障碍注入标记：本版跳过 ──────────────────────
    // 跳过原因：Android 没有「某个无障碍服务正在检视我这个包」的公开只读信号。
    // 能拿到的只有 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES（全局已启用服务列表），
    // 它既无法定位到「是否指向本包」，也会把所有正常使用无障碍功能的用户误判成注入；
    // 唯一更接近的方案（AccessibilityManager 的窗口/节点信息）需要绑定服务的权限，
    // 成本与误报都远超收益。故本版不实现，避免给逆向用户制造噪音。
}
