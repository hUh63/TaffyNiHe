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

    /**
     * 反编译工作线程。jadx 的 `code` 是纯 CPU 计算、不吃协程取消，只能靠 Future 超时把
     * 结果面向上层"切掉"：线程本身停不下来，但界面不会无限转圈。
     * 用 cached pool 而不是单线程，避免一个卡住的类把后面所有请求都堵死。
     */
    private val worker = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "jadx-decompile").apply { isDaemon = true }
    }

    /** 同时在跑的反编译数。超时的任务停不下来，只能靠这个把新请求挡在外面。 */
    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
    private const val MAX_IN_FLIGHT = 3

    /** 在 worker 上跑 [block]，超时返回 null 并写 [lastError]。 */
    private fun <T> timed(timeoutMs: Long, label: String, block: () -> T): T? {
        // 超时的反编译线程不会真的停下（jadx 是纯 CPU 计算），连续点五个类就是五个线程
        // 各跑二十秒、手机直接发烫。超过上限就明确拒绝，让用户等前一个结束。
        val running = inFlight.get()
        if (running >= MAX_IN_FLIGHT) {
            lastError = "已有 $running 个反编译任务在跑（上一个可能卡在难解的类上），稍等再试。"
            return null
        }
        inFlight.incrementAndGet()
        val fut = worker.submit(java.util.concurrent.Callable {
            try {
                block()
            } finally {
                inFlight.decrementAndGet()
            }
        })
        return try {
            fut.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            fut.cancel(true)
            lastError = "$label 超时（>${timeoutMs / 1000}s）。这个类可能用了 jadx 处理不动的构造，" +
                "可以先用 smali 视图看，或换一个类。"
            null
        } catch (e: java.util.concurrent.ExecutionException) {
            lastError = "$label 失败：${e.cause?.javaClass?.simpleName ?: "unknown"}: ${e.cause?.message ?: ""}"
            null
        } catch (t: Throwable) {
            lastError = "$label 失败：${t.javaClass.simpleName}: ${t.message ?: ""}"
            null
        }
    }

    private fun keyOf(f: File): String =
        "${f.absolutePath}@${f.length()}@${f.lastModified()}"

    /**
     * 打开（或复用）一个 decompiler。失败返回 null，并把原因写进 [lastError]。
     * 调用方必须容忍这是"秒级到几十秒级"的操作，只在 IO 线程调。
     */
    private var lastError: String = ""

    fun errorMessage(): String = lastError

    /** 超过这个大小就别反编译了：jadx 会把整个 DEX 展开成对象图，手机上必然 OOM。 */
    private const val MAX_INPUT_BYTES = 120L * 1024 * 1024

    private fun open(path: String): JadxDecompiler? = synchronized(this) {
        val f = File(path)
        if (!f.isFile) {
            lastError = "文件不存在：$path"
            return null
        }
        // APK 的上限要看 dex 总和，不是包体积：一个 200MB 的包可能只有 30MB 代码，
        // 按包体积判定会让大包彻底用不了 Java 视图（jadx 本来就只解析 dex）。
        val codeBytes = codeBytesOf(f)
        if (codeBytes > MAX_INPUT_BYTES) {
            lastError = "代码区 ${codeBytes / 1024 / 1024}MB 超过反编译上限 ${MAX_INPUT_BYTES / 1024 / 1024}MB" +
                "（jadx 会把整个 DEX 展开进内存，再大就会 OOM）。建议先用搜索定位类，或改用 smali 视图。"
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

    /** APK 取 classes*.dex 的总大小；单个 dex 就是文件大小。 */
    private fun codeBytesOf(f: File): Long {
        if (!f.name.endsWith(".apk", true)) return f.length()
        val sum = runCatching {
            java.util.zip.ZipFile(f).use { zf ->
                zf.entries().asSequence()
                    .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                    .sumOf { it.size.coerceAtLeast(0) }
            }
        }.getOrDefault(0L)
        return if (sum > 0) sum else f.length()
    }

    /**
     * 预热：后台把 DEX 分析一遍，之后 [decompileClass] 就只剩生成代码的时间。
     * 返回值只是"是否就绪"，不产出内容。
     */
    fun warmUp(path: String): Boolean = open(path) != null

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
        // 单类反编译限时 25s：jadx 对少数病态方法会跑到几分钟，界面不能陪着等。
        // 注意要区分"超时/异常"与"正常返回 null（纯接口）"——后者不是错误。
        lastError = ""
        val rawCode = timed(25_000, "反编译 $full") { cls.code }
        if (lastError.isNotEmpty()) {
            return JSONObject().put("error", lastError).put("className", full)
        }
        val code = rawCode
        if (code.isNullOrBlank()) {
            return JSONObject().put("error", "反编译结果为空（可能是纯接口/注解类）").put("className", full)
        }
        val text = if (code.length > maxBytes) {
            // 截断点若落在代理对中间，会留下孤立 surrogate（界面显示成 �）
            var end = maxBytes
            if (end > 0 && Character.isHighSurrogate(code[end - 1])) end--
            code.substring(0, end) + "\n\n// …结果超过 ${maxBytes / 1024} KB，已截断"
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
