package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

/**
 * 塔菲逆核: 反调试 / 反注入 / 环境检测 特征扫描。
 *
 * 动态分析前的「体检」工具：告诉逆向者在 frida/unidbg 调试前, 目标里有哪些反调试、
 * 反注入(Xposed/Substrate)、反 Frida、root/模拟器检测点, 从而决定要不要先绕过。
 * 与 taffy_apk_shell_check(判断壳/要不要脱)互补: 壳检测=要不要脱, 本工具=动调会卡在哪。
 *
 * 纯只读: 遍历 APK 内 classes*.dex / lib/*.so(及任意文件) 或单个 SO, 在字节层面
 * 以大小写不敏感方式匹配公开的反调试/反注入特征字符串。不改任何文件。
 */
object AntiDebugTool {

    /** (显示名, 分类, 特征子串, 严重度) */
    private data class Sig(val label: String, val category: String, val needle: String, val severity: String)

    private val SIGS = listOf(
        // —— 反调试 ——
        Sig("ptrace 自附加", "anti-debug", "ptrace", "high"),
        Sig("TracerPid 检测", "anti-debug", "TracerPid", "high"),
        Sig("/proc/self/status 读取", "anti-debug", "/proc/self/status", "high"),
        Sig("/proc/self/task 线程检测", "anti-debug", "/proc/self/task", "medium"),
        Sig("waitForDebugger", "anti-debug", "waitForDebugger", "high"),
        Sig("isDebuggerConnected", "anti-debug", "isDebuggerConnected", "high"),
        Sig("android.os.Debug 调用", "anti-debug", "android.os.Debug", "medium"),
        Sig("getppid 父进程检测", "anti-debug", "getppid", "low"),
        // —— 反 Frida / 反注入 ——
        Sig("Frida 关键字", "anti-frida", "frida", "high"),
        Sig("Frida gum 线程", "anti-frida", "gum-js-loop", "high"),
        Sig("Frida 默认端口 27042", "anti-frida", "27042", "medium"),
        Sig("Frida 默认端口 27043", "anti-frida", "27043", "medium"),
        Sig("Frida 注入器 linjector", "anti-frida", "linjector", "high"),
        Sig("frida-server", "anti-frida", "frida-server", "high"),
        Sig("Xposed 检测", "anti-inject", "Xposed", "high"),
        Sig("Xposed 包名", "anti-inject", "de.robv.android.xposed", "high"),
        Sig("Substrate 注入", "anti-inject", "Substrate", "medium"),
        Sig("Cydia Substrate", "anti-inject", "Cydia", "medium"),
        Sig("/proc/self/maps 扫描(反注入常用)", "anti-inject", "/proc/self/maps", "medium"),
        // —— root / 环境 / 完整性 ——
        Sig("Magisk 检测", "root-check", "magisk", "high"),
        Sig("su 二进制路径", "root-check", "/system/bin/su", "medium"),
        Sig("su 二进制路径(xbin)", "root-check", "/system/xbin/su", "medium"),
        Sig("busybox", "root-check", "busybox", "low"),
        Sig("ro.debuggable 属性", "root-check", "ro.debuggable", "medium"),
        Sig("ro.secure 属性", "root-check", "ro.secure", "low"),
        Sig("superuser", "root-check", "superuser", "low"),
        // —— 模拟器检测 ——
        Sig("QEMU 模拟器属性", "emulator-check", "ro.kernel.qemu", "medium"),
        Sig("goldfish 模拟器", "emulator-check", "goldfish", "medium"),
        Sig("Genymotion 模拟器", "emulator-check", "genymotion", "medium"),
        Sig("vbox86 模拟器", "emulator-check", "vbox86", "medium"),
    )

    /** 单文件最大扫描字节: 超过则只扫前 N 字节, 防止超大 so 触发 OOM。 */
    private const val MAX_SCAN_BYTES = 96L * 1024 * 1024

    val tool: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_anti_debug_scan",
            "【反调试/反注入扫描】动态分析前的「体检」: 只读扫描 APK(classes*.dex / lib/*.so)或单个 SO, " +
                "识别反调试(ptrace/TracerPid/waitForDebugger)、反 Frida(frida/gum-js-loop/27042)、" +
                "反注入(Xposed/Substrate)、root/模拟器检测等特征。告诉你 frida/unidbg 调试可能被哪些手段拦截, " +
                "以及从哪里入手绕过。纯只读, 不改文件。与 taffy_apk_shell_check(壳检测)互补。",
            "Read-only pre-dynamic-analysis recon: scans an APK (classes*.dex / lib/*.so) or a single SO for " +
                "anti-debug (ptrace/TracerPid/waitForDebugger), anti-Frida (frida/gum-js-loop/27042), " +
                "anti-inject (Xposed/Substrate) and root/emulator-detection fingerprints. Tells you where " +
                "frida/unidbg debugging will be blocked. Read-only. Complements taffy_apk_shell_check.",
            "analyze", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "path" str "APK / SO / ZIP 文件路径"
                "detail" bool "是否返回每个特征命中的来源文件名(默认 true)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少 path", "path", "")
            val f = File(path)
            if (!f.isFile) return err("FILE_NOT_FOUND", "文件不存在: $path", "path", path)
            val detail = args.optBoolean("detail", true)
            return runCatching {
                val hits = LinkedHashMap<Sig, MutableSet<String>>()
                var entries = 0
                var scannedBytes = 0L

                fun scanBytes(name: String, data: ByteArray) {
                    if (data.isEmpty()) return
                    scannedBytes += data.size
                    for (s in SIGS) {
                        if (hits[s]?.contains(name) == true) continue
                        if (containsAsciiIgnoreCase(data, s.needle)) {
                            hits.getOrPut(s) { linkedSetOf() }.add(name)
                        }
                    }
                }

                val lower = path.lowercase()
                if (lower.endsWith(".apk") || lower.endsWith(".zip") || lower.endsWith(".jar") || lower.endsWith(".aab")) {
                    ZipFile(f).use { zf ->
                        zf.entries().toList().forEach { e ->
                            if (e.isDirectory || e.size <= 0L || e.size > MAX_SCAN_BYTES) return@forEach
                            val n = e.name
                            val interesting = n.endsWith(".dex") || n.endsWith(".so") ||
                                n.endsWith(".bin") || n.contains("assets/") || n.endsWith(".xml")
                            if (!interesting) return@forEach
                            entries++
                            val data = zf.getInputStream(e).use { it.readBytes() }
                            scanBytes(n, data)
                        }
                    }
                } else {
                    entries = 1
                    val len = minOf(f.length(), MAX_SCAN_BYTES)
                    val data = ByteArray(len.toInt())
                    FileInputStream(f).use { ins ->
                        var off = 0
                        while (off < data.size) {
                            val r = ins.read(data, off, data.size - off)
                            if (r <= 0) break
                            off += r
                        }
                    }
                    scanBytes(f.name, data)
                }

                val findings = JSONArray()
                for ((s, files) in hits) {
                    findings.put(
                        JSONObject()
                            .put("feature", s.label)
                            .put("category", s.category)
                            .put("severity", s.severity)
                            .put("hitFiles", files.size)
                            .put("files", if (detail) JSONArray(files.take(20).toList()) else JSONArray())
                    )
                }
                val categories = JSONObject()
                for (cat in listOf("anti-debug", "anti-frida", "anti-inject", "root-check", "emulator-check")) {
                    val c = hits.keys.count { it.category == cat }
                    if (c > 0) categories.put(cat, c)
                }
                val highCount = hits.keys.count { it.severity == "high" }
                val detected = hits.isNotEmpty()
                val hint = when {
                    !detected -> "未检出常见反调试/反注入特征; 动态调试(frida/unidbg)预计不会被这类手段拦截。"
                    highCount > 0 -> "检出 $highCount 项高危反调试/反注入特征。建议先静态定位这些检测点(可配合 taffy_string_xref/taffy_analyze_elf), 再决定 frida 反检测(hide/gadget 改名)或 unidbg 纯模拟(不注入进程)。"
                    else -> "检出若干低/中危环境检测特征, 通常不影响 frida 注入, 但可能触发 root/模拟器分支。"
                }
                ok(
                    JSONObject()
                        .put("tool", "taffy_anti_debug_scan")
                        .put("path", path)
                        .put("detected", detected)
                        .put("highSeverityCount", highCount)
                        .put("findings", findings)
                        .put("categories", categories)
                        .put("scanned", JSONObject().put("entries", entries).put("bytes", scannedBytes))
                        .put("hint", hint)
                )
            }.getOrElse { e ->
                err("ANTI_DEBUG_SCAN_FAILED", "扫描失败: ${e.message ?: e.javaClass.simpleName}", "path", path)
            }
        }
    }

    /** 大小写不敏感的 ASCII 子串包含判断(在 dex/so 的字符串常量区做启发式匹配)。 */
    private fun containsAsciiIgnoreCase(hay: ByteArray, needle: String): Boolean {
        val n = needle.lowercase().toByteArray(Charsets.US_ASCII)
        if (n.isEmpty() || n.size > hay.size) return false
        outer@ for (i in 0..hay.size - n.size) {
            for (j in n.indices) {
                var h = hay[i + j].toInt() and 0xFF
                if (h in 65..90) h += 32
                if (h != (n[j].toInt() and 0xFF)) continue@outer
            }
            return true
        }
        return false
    }

    val ALL = listOf(tool)
}
