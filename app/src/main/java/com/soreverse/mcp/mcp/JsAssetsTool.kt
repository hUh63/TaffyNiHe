package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * 塔菲逆核: APK 内 JS 资产抽取 + 混淆指纹。
 *
 * 混合/H5/RN 类 App 常把逻辑塞在 assets 里的 JS（含被 obfuscator.io / 自研字节码 VM
 * 处理过的代码）。本工具从 APK 中列出/抽取这些 JS，并对内容做"混淆指纹"打分,
 * 告诉你它是「明文」「单行压缩」还是「obfuscator.io」「自研字节码 VM」「加密整数表」,
 * 以及推荐的还原阶段顺序。
 *
 * 指纹启发式参考 jsrestore（单文件 JS 反混淆工具）的 detect() 判定:
 * 各类型独立打分取最高分为主判定, "单行压缩"只作兜底不参与打分（否则大文件会误判）。
 *
 * 纯 Java（ZipFile + Regex），无需 native 引擎，只读（extract 除外，需显式指定 saveDir）。
 */
object JsAssetsTool {

    private val JS_EXT = listOf(".js", ".mjs", ".cjs", ".jsx", ".jsbundle", ".bundle")

    /** 指纹计算的单个文件字节上限（超出则取前 N 字节做采样）。 */
    private const val DEFAULT_FP_BYTES = 4_000_000

    val tool: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_js_assets",
            "【APK 内 JS 资产】列出/抽取 APK 里的 JS（assets/**、RN bundle 等），并做「混淆指纹」评分：" +
                "判定明文 / 单行压缩 / obfuscator.io / 自研字节码 VM / 加密整数表 / eval 自解密包装 / 常量算术爆炸，" +
                "给出各类型得分与推荐的还原阶段顺序。指纹启发式参考 jsrestore 的 detect()。" +
                "action=list(默认 列文件) | fingerprint(逐文件打分) | extract(导出到 saveDir)。纯 Java，无需引擎。",
            "List / extract JS assets inside an APK (assets/**, RN bundles) and score their obfuscation fingerprint: " +
                "plain / minified / obfuscator.io / custom bytecode VM / encrypted int-table / eval wrapper / const explosion, " +
                "with per-type scores and a recommended restore pipeline. Heuristics ported from jsrestore's detect(). " +
                "action=list (default) | fingerprint | extract.",
            "search", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "action" str "list(默认) | fingerprint | extract"
                "path" str "APK / ZIP 路径"
                "keyword" str "文件名过滤（子串匹配，大小写不敏感）"
                "limit" str "返回上限（默认 200）"
                "minSize" str "最小文件字节数（默认 0）"
                "saveDir" str "extract: 输出目录（默认 filesDir/js-assets）"
                "maxFingerprintBytes" str "fingerprint: 单文件采样字节上限（默认 4000000）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少 path", "path", "")
            val f = File(path)
            if (!f.isFile) return err("FILE_NOT_FOUND", "文件不存在: $path", "path", path)
            val action = args.str("action", "list").lowercase()
            return when (action) {
                "fingerprint" -> fingerprint(f, path, args)
                "extract" -> extract(ctx, f, path, args)
                else -> listJs(f, path, args)
            }
        }
    }

    // ── list ────────────────────────────────────────────────────────────────

    private fun listJs(apk: File, path: String, args: JSONObject): JSONObject {
        val limit = (args.str("limit").toIntOrNull() ?: 200).coerceIn(1, 5000)
        val keyword = args.str("keyword")
        val minSize = args.str("minSize").toLongOrNull() ?: 0L
        return runCatching {
            val arr = JSONArray()
            var total = 0
            var totalBytes = 0L
            ZipFile(apk).use { zf ->
                val js = zf.entries().toList()
                    .filter { !it.isDirectory && isJsEntry(it.name) && it.size >= minSize }
                    .filter { keyword.isBlank() || it.name.contains(keyword, ignoreCase = true) }
                    .sortedByDescending { it.size }
                total = js.size
                totalBytes = js.sumOf { it.size }
                for (e in js) {
                    if (arr.length() >= limit) break
                    arr.put(JSONObject().put("name", e.name).put("size", e.size).put("sizeHuman", human(e.size)))
                }
            }
            ok(JSONObject()
                .put("tool", "taffy_js_assets")
                .put("action", "list")
                .put("path", path)
                .put("jsCount", total)
                .put("totalBytes", totalBytes)
                .put("totalHuman", human(totalBytes))
                .put("truncated", total > arr.length())
                .put("files", arr)
                .put("hint", if (total == 0)
                    "APK 内未发现 .js/.jsbundle/.bundle 资产；可能是纯原生 App，或 JS 被打包进 so/加密壳。"
                else "对可疑文件跑 action=fingerprint 看混淆类型，或 action=extract 导出后细看。"))
        }.getOrElse { e ->
            err("JS_ASSETS_FAILED", "读取 JS 资产失败: ${e.message ?: e.javaClass.simpleName}", "path", path)
        }
    }

    // ── fingerprint ─────────────────────────────────────────────────────────

    private fun fingerprint(apk: File, path: String, args: JSONObject): JSONObject {
        val limit = (args.str("limit").toIntOrNull() ?: 200).coerceIn(1, 2000)
        val keyword = args.str("keyword")
        val minSize = args.str("minSize").toLongOrNull() ?: 0L
        val maxBytes = (args.str("maxFingerprintBytes").toIntOrNull() ?: DEFAULT_FP_BYTES).coerceIn(10_000, 32_000_000)
        return runCatching {
            val arr = JSONArray()
            var scanned = 0
            ZipFile(apk).use { zf ->
                val js = zf.entries().toList()
                    .filter { !it.isDirectory && isJsEntry(it.name) && it.size >= minSize }
                    .filter { keyword.isBlank() || it.name.contains(keyword, ignoreCase = true) }
                    .sortedByDescending { it.size }
                for (e in js) {
                    if (scanned >= limit) break
                    val bytes = runCatching {
                        zf.getInputStream(e).use { ins ->
                            val buf = ByteArray(minOf(e.size, maxBytes.toLong()).toInt())
                            var off = 0
                            while (off < buf.size) {
                                val r = ins.read(buf, off, buf.size - off)
                                if (r < 0) break
                                off += r
                            }
                            if (off == buf.size) buf else buf.copyOf(off)
                        }
                    }.getOrNull() ?: continue
                    val text = String(bytes, Charsets.UTF_8)
                    val d = detect(text)
                    scanned++
                    val primary = primary(d)
                    arr.put(JSONObject()
                        .put("name", e.name)
                        .put("size", e.size)
                        .put("analyzedBytes", bytes.size)
                        .put("sampled", bytes.size.toLong() < e.size)
                        .put("avgLineLen", d.avgLine.toInt())
                        .put("primary", JSONObject()
                            .put("type", primary.type).put("name", primary.name).put("score", primary.score)
                            .put("pipeline", JSONArray(primary.pipeline)))
                        .put("allHits", JSONArray(d.hits.map { h ->
                            JSONObject().put("type", h.type).put("name", h.name).put("score", h.score)
                        }))
                        .put("features", JSONObject()
                            .put("bigInts", d.nBigInt)
                            .put("caseNumbers", d.nCaseNum)
                            .put("hexIdentifiers", d.nHexIdent)
                            .put("identDensity", (Math.round(d.identDensity * 10) / 10.0))
                            .put("hasRotate", d.hasRotate)
                            .put("hasWhileTrue", d.hasWhileTrue)
                            .put("hasSwitch", d.hasSwitch)
                            .put("hasTypedArray", d.hasTypedArr)
                            .put("hasEval", d.hasEval)
                            .put("hasCharCodeAt", d.hasCharCode)))
                }
            }
            ok(JSONObject()
                .put("tool", "taffy_js_assets")
                .put("action", "fingerprint")
                .put("path", path)
                .put("scanned", scanned)
                .put("files", arr)
                .put("hint", "primary.pipeline 是推荐的还原阶段顺序；其中 vm-unpack/isa/disasm/decompile 属样本定制阶段，需专门逆向该样本（与 jsrestore 一致，通用流水线不覆盖）。"))
        }.getOrElse { e ->
            err("JS_ASSETS_FAILED", "JS 指纹失败: ${e.message ?: e.javaClass.simpleName}", "path", path)
        }
    }

    // ── extract ─────────────────────────────────────────────────────────────

    private fun extract(ctx: ToolContext, apk: File, path: String, args: JSONObject): JSONObject {
        val limit = (args.str("limit").toIntOrNull() ?: 200).coerceIn(1, 5000)
        val keyword = args.str("keyword")
        val minSize = args.str("minSize").toLongOrNull() ?: 0L
        val target = File(args.str("saveDir").ifBlank { File(ctx.context.filesDir, "js-assets").absolutePath })
        return runCatching {
            if (!target.exists()) target.mkdirs()
            val written = JSONArray()
            ZipFile(apk).use { zf ->
                val js = zf.entries().toList()
                    .filter { !it.isDirectory && isJsEntry(it.name) && it.size >= minSize }
                    .filter { keyword.isBlank() || it.name.contains(keyword, ignoreCase = true) }
                    .sortedByDescending { it.size }
                for (e in js) {
                    if (written.length() >= limit) break
                    val safe = e.name.replace(Regex("^/+"), "").replace("/", "_")
                    val out = File(target, safe)
                    zf.getInputStream(e).use { ins -> out.outputStream().use { ins.copyTo(it) } }
                    written.put(JSONObject().put("name", e.name).put("savedTo", out.absolutePath).put("size", out.length()))
                }
            }
            ok(JSONObject()
                .put("tool", "taffy_js_assets")
                .put("action", "extract")
                .put("path", path)
                .put("saveDir", target.absolutePath)
                .put("count", written.length())
                .put("files", written))
        }.getOrElse { e ->
            err("JS_ASSETS_FAILED", "导出 JS 资产失败: ${e.message ?: e.javaClass.simpleName}", "path", path)
        }
    }

    // ── 混淆指纹（移植自 jsrestore.detect）────────────────────────────────────

    private data class Hit(val type: String, val name: String, val score: Int, val pipeline: List<String>)
    private class Detect(
        val hits: List<Hit>, val avgLine: Double, val bytes: Int, val lines: Int,
        val nBigInt: Int, val nCaseNum: Int, val nHexIdent: Int, val identDensity: Double,
        val hasRotate: Boolean, val hasWhileTrue: Boolean, val hasSwitch: Boolean,
        val hasTypedArr: Boolean, val hasEval: Boolean, val hasCharCode: Boolean,
    )

    private fun count(re: Regex, s: String): Int = re.findAll(s).count()

    private fun detect(src: String): Detect {
        val hits = ArrayList<Hit>()
        val bytes = src.length
        val lines = src.count { it == '\n' } + 1
        val avgLine = bytes.toDouble() / maxOf(lines, 1)

        val nBigInt = count(Regex("\\b\\d{4,}\\b"), src)
        val nCaseNum = count(Regex("case\\s+\\d+\\s*:"), src)
        val nHexIdent = count(Regex("_0x[0-9a-f]{4,6}", RegexOption.IGNORE_CASE), src)
        val hasRotate = Regex("push\\(\\s*\\w+\\.shift\\(\\)\\s*\\)").containsMatchIn(src)
        val hasWhileTrue = Regex("while\\s*\\(\\s*!!\\[\\]\\s*\\)|for\\s*\\(\\s*;\\s*;\\s*\\)").containsMatchIn(src)
        val hasSwitch = Regex("switch\\s*\\(").containsMatchIn(src)
        val hasTypedArr = Regex("Uint8Array|Int32Array|Uint32Array|new Array\\(256\\)").containsMatchIn(src)
        val hasEval = Regex("\\beval\\s*\\(|new Function\\s*\\(").containsMatchIn(src)
        val hasCharCode = src.contains("charCodeAt")
        val nIdentLike = count(Regex("\\b[A-Za-z_\\u0024][\\w\\u0024]{2,}\\b"), src)
        val identDensity = nIdentLike / maxOf(bytes / 1000.0, 1.0)

        // 1. 加密整数表 / 打包载荷
        if (nBigInt > 2000 && identDensity < 12 && !hasSwitch) {
            hits.add(Hit("int-table", "加密整数表（打包载荷，需先解出引导器）", 85,
                listOf("vm-unpack", "fold", "isa", "disasm", "decompile", "sanitize", "naming")))
        }

        // 2. obfuscator.io 家族
        var ioScore = 0
        if (nHexIdent > 50) ioScore += 30
        if (nHexIdent > 500) ioScore += 15
        if (hasRotate) ioScore += 25
        if (hasWhileTrue && hasSwitch) ioScore += 20
        if (hasCharCode) ioScore += 10
        if (Regex("var\\s+_0x[a-f0-9]{4}\\s*=\\s*\\[").containsMatchIn(src)) ioScore += 20
        if (ioScore >= 40) {
            hits.add(Hit("obfuscator-io", "obfuscator.io 混淆（字符串表 + RC4 + 控制流平坦化）", ioScore,
                listOf("strings", "controlflow", "fold", "inline", "sanitize", "naming")))
        }

        // 3. 自研字节码 VM（编译型）
        var vmScore = 0
        if (nCaseNum > 20) vmScore += 30
        if (hasSwitch && bytes > 100000) vmScore += 20
        if (hasTypedArr) vmScore += 15
        if (hasEval) vmScore += 10
        if (nBigInt > 500) vmScore += 10
        if (hasSwitch && Regex("switch\\s*\\(\\s*[\\w\\u0024]+\\s*\\[").containsMatchIn(src)) vmScore += 15
        if (vmScore >= 45) {
            hits.add(Hit("bytecode-vm", "自研字节码 VM（编译型保护）", vmScore,
                listOf("isa", "disasm", "decompile", "sanitize", "naming")))
        }

        // 4. eval 自解密包装
        if (hasEval && bytes < 300000 && Regex("^[\\s;]*(?:\\(function|!function|~function|\\[)").containsMatchIn(src)) {
            hits.add(Hit("eval-wrapped", "eval 自解密包装", 55, listOf("vm-unpack", "fold", "sanitize")))
        }

        // 5. 常量算术爆炸
        val nArith = count(Regex("0x[0-9a-f]+\\s*[-+*]\\s*-?0x[0-9a-f]+", RegexOption.IGNORE_CASE), src)
        if (nArith > 100) {
            hits.add(Hit("const-explosion", "常量算术爆炸", 50, listOf("fold", "sanitize", "naming")))
        }

        val sorted = hits.sortedByDescending { it.score }
        return Detect(sorted, avgLine, bytes, lines, nBigInt, nCaseNum, nHexIdent, identDensity,
            hasRotate, hasWhileTrue, hasSwitch, hasTypedArr, hasEval, hasCharCode)
    }

    private fun primary(d: Detect): Hit {
        val top = d.hits.firstOrNull()
        if (top != null && top.score >= 40) return top
        if (d.avgLine > 5000) {
            return Hit("minified", "单行压缩代码（无实质混淆）", 40, listOf("fold", "beautify", "naming"))
        }
        return Hit("plain", "明文/轻度混淆", 20, listOf("fold", "sanitize", "naming"))
    }

    private fun isJsEntry(name: String): Boolean {
        val n = name.lowercase()
        return JS_EXT.any { n.endsWith(it) }
    }

    private fun human(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.2f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    val ALL = listOf(tool)
}
