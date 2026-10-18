package com.soreverse.mcp.core

import java.io.File

/**
 * 运行时 ART/DEX 转储框架探针（诊断用：只记录，不阻断）。
 *
 * 背景：上游 SOMCP 1.0.22 新增「ROM 级 ART/DEX 转储运行时检测」，用来识别 ArkOS / FART 系
 * 脱壳机与「一键脱壳」平台补丁。塔菲逆核的用户本身就是逆向人员——这类环境对他既可能是工具，
 * 也可能是对手（分析结果被旁路抓走）。因此这里只做「告知」：不进完整性门禁、不杀进程、
 * 不拒绝启动服务、不写文件、不发网络。
 *
 * 检测面刻意保守。上游自己踩过坑：早期把 `gadget` / `lsposed` 子串放进 `/proc/self/maps`
 * 特征表，导致正常设备误报，最后只能整段删掉。这里只保留特异性足够高的标识：
 *  1) build.prop 里的 ROM 标识（arkos / fart / dumpdex / youpk / blackdex）
 *  2) 系统库目录里已知的转储框架 .so（libfart* / libdexdump* / libyoupk* ...）
 *  3) 当前进程 maps 中从可写目录加载的 .so（脱壳机常见的 LD_PRELOAD 注入手法）
 */
internal object RuntimeDumpProbe {

    data class Hit(val kind: String, val detail: String, val severity: String)

    private const val SEV_INFO = "info"
    private const val SEV_WARN = "warn"
    private const val MAX_PROP_BYTES = 512L * 1024
    private const val MAX_MAPS_LINES = 40000
    private const val MAX_HITS_PER_KIND = 3

    private val ROM_MARKERS = listOf("arkos", "fart", "dumpdex", "youpk", "blackdex")

    private val LIB_PREFIXES = listOf(
        "libfart", "libark", "libdexdump", "libyoupk", "libblackdex", "libepic",
    )

    private val PROP_FILES = listOf(
        "/system/build.prop",
        "/vendor/build.prop",
        "/odm/etc/build.prop",
        "/system_ext/build.prop",
        "/product/build.prop",
    )

    private val LIB_DIRS = listOf(
        "/system/lib64", "/system/lib",
        "/vendor/lib64", "/vendor/lib",
        "/system_ext/lib64", "/product/lib64", "/odm/lib64",
    )

    /** 脱壳机常把注入的 .so 放在这些可写目录。 */
    private val WRITABLE_PREFIXES = listOf(
        "/data/local/tmp/", "/sdcard/", "/storage/emulated/", "/storage/self/",
    )

    fun detect(): List<Hit> {
        val out = ArrayList<Hit>(4)
        out += runCatching { scanProps() }.getOrDefault(emptyList())
        out += runCatching { scanLibs() }.getOrDefault(emptyList())
        out += runCatching { scanWritableMaps() }.getOrDefault(emptyList())
        return out
    }

    private fun scanProps(): List<Hit> {
        val hits = ArrayList<Hit>()
        for (path in PROP_FILES) {
            if (hits.size >= MAX_HITS_PER_KIND) break
            val f = File(path)
            if (!f.isFile || f.length() <= 0 || f.length() > MAX_PROP_BYTES) continue
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            val line = text.lineSequence().firstOrNull { l ->
                val low = l.lowercase()
                low.startsWith("ro.build.") && ROM_MARKERS.any { low.contains(it) }
            } ?: continue
            hits += Hit("运行时转储环境", "$path: ${line.trim().take(160)}", SEV_WARN)
        }
        return hits
    }

    private fun scanLibs(): List<Hit> {
        val hits = ArrayList<Hit>()
        for (dir in LIB_DIRS) {
            if (hits.size >= MAX_HITS_PER_KIND) break
            val files = runCatching { File(dir).listFiles() }.getOrNull() ?: continue
            for (f in files) {
                val name = f.name.lowercase()
                if (!name.endsWith(".so")) continue
                if (LIB_PREFIXES.none { name.startsWith(it) }) continue
                hits += Hit("转储框架库", "$dir/${f.name}", SEV_WARN)
                if (hits.size >= MAX_HITS_PER_KIND) break
            }
        }
        return hits
    }

    private fun scanWritableMaps(): List<Hit> {
        val maps = File("/proc/self/maps")
        if (!maps.isFile) return emptyList()
        val hits = ArrayList<Hit>()
        val seen = HashSet<String>()
        runCatching {
            maps.useLines { lines ->
                var n = 0
                for (line in lines) {
                    if (++n > MAX_MAPS_LINES) break
                    val path = line.substringAfterLast(' ', "").trim()
                    if (!path.endsWith(".so")) continue
                    if (WRITABLE_PREFIXES.none { path.startsWith(it) }) continue
                    if (seen.add(path)) {
                        hits += Hit("可写目录注入", path.take(180), SEV_WARN)
                        if (hits.size >= MAX_HITS_PER_KIND) break
                    }
                }
            }
        }
        // 一个都没有是正常情况：返回空列表让 UI 显示「未发现」。
        return hits
    }
}
