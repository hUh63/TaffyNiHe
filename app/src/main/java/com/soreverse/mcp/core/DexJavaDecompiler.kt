package com.soreverse.mcp.core

import jadx.api.DecompilationMode
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.JavaClass
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * DEX → Java 反编译（jadx）。
 *
 * 移植说明：上游那个 DEX 编辑器（net.dexedit）的「转成 Java」走的就是 jadx。塔菲原本只有
 * 伪 C（rizin）与 smali，缺 Java 视图，所以在编辑器菜单里补上这条链路。
 *
 * 为什么不是"用 smali 拼个 Java 骨架"：那样只能给签名，看不到方法体，改代码时判断不了逻辑。
 * jadx 在 Android 上能跑（class 版本 55，d8 可处理；用到的 java.nio.file 在 API 26+ 齐全）。
 *
 * 性能取舍：`load()` 会把整个 DEX 走一遍分析（真实样本 549 个类约 20 秒，手机上更慢），
 * 之后单个类的 `code` 就很快。所以这里**缓存已 load 的 decompiler**，同一个文件反复看不同类
 * 不会重复付这笔成本；文件变了（size/mtime）才重建。
 */
internal object DexJavaDecompiler {

    /** jadx 版本号（写进结果里，便于用户对账）。 */
    val version: String by lazy {
        runCatching { JadxDecompiler.getVersion() }.getOrDefault("unknown")
    }

    private var cached: Pair<String, JadxDecompiler>? = null

    private fun keyOf(f: File): String =
        "${f.absolutePath}@${f.length()}@${f.lastModified()}"

    /**
     * 打开（或复用）一个 decompiler。失败返回 null，并把原因写进 [lastError]。
     * 调用方必须容忍这是"秒级到几十秒级"的操作，只在 IO 线程调。
     */
    private var lastError: String = ""

    fun errorMessage(): String = lastError

    private fun open(path: String): JadxDecompiler? = synchronized(this) {
        val f = File(path)
        if (!f.isFile) {
            lastError = "文件不存在：$path"
            return null
        }
        val key = keyOf(f)
        cached?.let { (k, d) -> if (k == key) return d }
        runCatching { cached?.second?.close() }
        lastError = ""
        return try {
            val args = JadxArgs()
            args.setInputFiles(listOf(f))
            // 只反编译代码：资源/XML 解析依赖 aapt2-proto，本功能用不到，关掉能省一大截时间
            args.setSkipResources(true)
            args.setShowInconsistentCode(true)
            args.setDecompilationMode(DecompilationMode.AUTO)
            args.setThreadsCount(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            val d = JadxDecompiler(args)
            d.load()
            cached = key to d
            d
        } catch (t: Throwable) {
            lastError = "jadx 解析失败：${t.javaClass.simpleName}: ${t.message ?: ""}"
            null
        }
    }

    fun close() = synchronized(this) {
        runCatching { cached?.second?.close() }
        cached = null
    }

    /** 可反编译的类名清单（含内部类），按名字排序；[query] 非空时按包含匹配过滤。 */
    fun listClasses(path: String, query: String = "", limit: Int = 200): JSONObject {
        val started = System.currentTimeMillis()
        val d = open(path) ?: return JSONObject().put("error", lastError)
        val cap = limit.coerceIn(1, 2000)
        val q = query.trim().lowercase()
        val out = JSONArray()
        var total = 0
        try {
            for (cls in d.classesWithInners) {
                val name = cls.fullName
                if (name.isEmpty()) continue
                if (q.isNotEmpty() && !name.lowercase().contains(q)) continue
                total++
                if (out.length() < cap) out.put(name)
            }
        } catch (t: Throwable) {
            return JSONObject().put("error", "列举类失败：${t.javaClass.simpleName}: ${t.message ?: ""}")
        }
        return JSONObject()
            .put("classes", out)
            .put("total", total)
            .put("limit", cap)
            .put("engine", "jadx $version")
            .put("tookMs", System.currentTimeMillis() - started)
    }

    /**
     * 反编译单个类。返回 `code`（Java 源码）；[className] 允许写 "com.a.B" 或 "com/a/B"。
     * 失败返回带 error 的对象。
     */
    fun decompileClass(path: String, className: String, maxBytes: Int = 256 * 1024): JSONObject {
        if (className.isBlank()) return JSONObject().put("error", "缺少 className")
        val started = System.currentTimeMillis()
        val d = open(path) ?: return JSONObject().put("error", lastError)
        val full = className.trim().replace('/', '.')
        val cls: JavaClass? = try {
            d.searchJavaClassByOrigFullName(full)
                ?: d.classesWithInners.firstOrNull { it.fullName == full }
        } catch (t: Throwable) {
            null
        }
        if (cls == null) {
            return JSONObject().put("error", "找不到类：$full（可先用 action=classes 列出来）")
        }
        val code = try {
            cls.code
        } catch (t: Throwable) {
            return JSONObject()
                .put("error", "反编译失败（该类可能用了 jadx 不支持的构造）：${t.javaClass.simpleName}")
                .put("className", full)
        }
        if (code.isNullOrBlank()) {
            return JSONObject().put("error", "反编译结果为空（可能是纯接口/注解类）").put("className", full)
        }
        val text = if (code.length > maxBytes) {
            code.substring(0, maxBytes) + "\n\n// …结果超过 ${maxBytes / 1024} KB，已截断"
        } else code
        return JSONObject()
            .put("className", full)
            .put("code", text)
            .put("lineCount", text.count { it == '\n' } + 1)
            .put("truncated", code.length > maxBytes)
            .put("engine", "jadx $version")
            .put("tookMs", System.currentTimeMillis() - started)
    }
}
