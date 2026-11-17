package com.soreverse.mcp.mcp

import com.reandroid.apk.ApkModule
import com.soreverse.mcp.core.TempFiles
import com.soreverse.mcp.core.VipEntryScanner
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * VIP / 会员入口分析：带明确问题去 dex 里取证，而不是让用户自己想搜什么词。
 *
 * 三件事分开给：
 *  - `scan`：按关键词组扫 dex，返回候选（类型/所在类/签名/命中的词/分数）；
 *  - `hook`：把候选里「方法」类命中生成 Frida hook 脚本（可直接 `frida -U -f <pkg> -l vip_hook.js`）；
 *  - `report`：导出 markdown 报告（按类别分组，带分数与签名），便于贴到分析记录里。
 *
 * 输出的分数只是**候选排序**，不是结论——真伪要靠交叉引用与运行时验证。
 */
object VipEntryTool {

    val vipEntry: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_vip_entry_scan",
            "【VIP/会员入口分析】带着明确问题扫 dex：会员标记 / 购买支付 / 期限试用 / 凭证 / 权限判断 / 免费限制 六组关键词，token 化匹配（payload 不会误命中 pay，isVip 会命中 vip），按可信度打分排序。action=scan 返回候选（类型/所在类/签名/命中词/分数）；hook 生成 Frida hook 脚本并落盘（自带重载解析，直接 frida -U -f <pkg> -l 脚本）；report 导出 markdown 报告。适合「不知道从哪下手」的会员功能逆向。",
            "VIP/membership entry analysis: scans dex for six weighted keyword groups (membership flags, purchase, expiry/trial, receipts, privilege checks, free-tier limits) with token-aware matching and scoring. action=scan returns ranked candidates; hook writes a Frida hook script (with overload resolution); report writes a markdown report.",
            "analyze", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("scan (default) | hook | minimal | report", "scan", "hook", "minimal", "report")
                "path" str "目标文件：apk / dex（必填）"
                "pkg" str "包名（hook 脚本里生成 frida 命令用；可空）"
                "maxFindings" int "最多返回多少条候选（默认 300）"
                "callers" bool "是否为方法命中补「被谁调用」（默认开；关了省一次全量指令遍历）"
                "outPath" str "hook/report 的输出路径（默认与输入同目录：<name>.vip_hook.js / <name>.vip_report.md）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path").trim()
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path", "path", "")
            val file = File(path)
            if (!file.isFile) return err("FILE_NOT_FOUND", "文件不存在: $path", "path", path)
            val action = args.str("action", "scan").ifBlank { "scan" }
            val maxFindings = args.intValue("maxFindings", 300).coerceIn(20, 2000)
            val withCallers = args.optBoolean("callers", true)

            return runCatching {
                val result = VipEntryScanner.scan(file, maxFindings, withCallers)
                when (action) {
                    "hook" -> {
                        val pkg = args.str("pkg").trim().ifBlank { guessPackage(file) }
                        val script = VipEntryScanner.buildHookScript(pkg, result.findings)
                        val out = outputFile(ctx, file, args.str("outPath"), ".vip_hook.js")
                        out.writeText(script)
                        if (args.str("outPath").isBlank()) TempFiles.pruneProducts(out.parentFile, ".vip_hook.js")
                        ok(
                            JSONObject()
                                .put("action", "hook")
                                .put("package", pkg)
                                .put("scriptPath", out.absolutePath)
                                .put("targets", result.findings.count { it.kind == "METHOD" })
                                .put("bytes", script.length)
                                .put("command", "frida -U -f " + pkg.ifBlank { "<pkg>" } + " -l " + out.name + " --no-pause")
                                .put("withCallers", withCallers)
                                .put("findings", findingsJson(result.findings, 40))
                                .put("next", "把脚本与 frida-server 推到设备后执行上面的命令；命中不理想时先看 report 里的分数分布"),
                        )
                    }
                    "minimal" -> {
                        // 只 hook 分数最高的 1 个方法：先验证方向对不对，比一次挂 40 个目标更容易读日志
                        val pkg = args.str("pkg").trim().ifBlank { guessPackage(file) }
                        val script = VipEntryScanner.buildMinimalHookScript(pkg, result.findings)
                        val out = outputFile(ctx, file, args.str("outPath"), ".vip_min.js")
                        out.writeText(script)
                        if (args.str("outPath").isBlank()) TempFiles.pruneProducts(out.parentFile, ".vip_min.js")
                        val top = result.findings
                            .filter { it.kind == "METHOD" && it.smaliSig.isNotBlank() }
                            .maxByOrNull { it.score }
                        ok(
                            JSONObject()
                                .put("action", "minimal")
                                .put("package", pkg)
                                .put("scriptPath", out.absolutePath)
                                .put("bytes", script.length)
                                .put("target", top?.smaliSig ?: "")
                                .put("targetJava", top?.javaSig ?: "")
                                .put("command", "frida -U -f " + pkg.ifBlank { "<pkg>" } + " -l " + out.name + " --no-pause")
                                .put("next", if (top == null) "没有方法类命中，先跑 action=scan" else "脚本只 hook 这 1 个方法并打印返回值与调用栈；确认是入口后再用 action=hook 生成全量脚本"),
                        )
                    }
                    "report" -> {
                        val pkg = args.str("pkg").trim().ifBlank { guessPackage(file) }
                        val md = VipEntryScanner.buildReport(pkg, result)
                        val out = outputFile(ctx, file, args.str("outPath"), ".vip_report.md")
                        out.writeText(md)
                        if (args.str("outPath").isBlank()) TempFiles.pruneProducts(out.parentFile, ".vip_report.md")
                        ok(
                            JSONObject()
                                .put("action", "report")
                                .put("package", pkg)
                                .put("reportPath", out.absolutePath)
                                .put("bytes", md.length)
                                .put("findings", result.findings.size)
                                .put("next", "报告按类别分组，先看分数最高的 10 条再决定要不要 hook"),
                        )
                    }
                    else -> ok(
                        JSONObject()
                            .put("action", "scan")
                            .put("path", file.absolutePath)
                            .put("dexCount", result.dexCount)
                            .put("classCount", result.classCount)
                            .put("stringCount", result.stringCount)
                            .put("elapsedMs", result.elapsedMs)
                            .put("count", result.findings.size)
                            .put("truncated", result.truncated)
                            .put("callersPartial", result.callersPartial)
                            .put("categories", categoryCounts(result.findings))
                            .put("findings", findingsJson(result.findings, maxFindings))
                            .put("next", nextHint(result.findings)),
                    )
                }
            }.getOrElse { err("SCAN_FAILED", "扫描失败: ${it.message ?: it.javaClass.simpleName}", "path", path) }
        }
    }

    /**
     * 从 APK 的 AndroidManifest 读包名（ARSCLib 解 AXML，不是正则）。
     *
     * 只用于生成 `frida -U -f <pkg>` 命令：dex 输入或解析失败时返回空串，让调用方自己传 pkg。
     */
    private fun guessPackage(file: File): String {
        val name = file.name.lowercase()
        if (!name.endsWith(".apk") && !name.endsWith(".zip")) return ""
        return runCatching {
            ApkModule.loadApkFile(file).androidManifest?.packageName?.trim() ?: ""
        }.getOrDefault("")
    }

    /**
     * 产物落盘位置。
     *
     * 默认不再写在输入文件旁边：输入通常是 cacheDir 里的临时副本（`vip_*`），
     * 生成的 hook 脚本 / 报告放那儿会被缓存回收清掉，用户也难找。
     * 改到 `filesDir/vip-scan/`（应用私有持久目录），与输入同名。
     */
    private fun outputFile(ctx: ToolContext, input: File, custom: String, suffix: String): File {
        if (custom.isNotBlank()) return File(custom)
        val base = input.name.substringBeforeLast('.', input.name)
        val dir = File(ctx.context.filesDir, "vip-scan").apply { runCatching { mkdirs() } }
        return File(dir, base + suffix)
    }

    private fun findingsJson(findings: List<VipEntryScanner.Finding>, limit: Int): JSONArray {
        val arr = JSONArray()
        findings.take(limit).forEach { f ->
            val o = JSONObject()
            o.put("kind", f.kind)
            o.put("category", f.category)
            o.put("owner", f.owner)
            o.put("name", f.name)
            if (f.smaliSig.isNotBlank()) o.put("smaliSig", f.smaliSig)
            if (f.javaSig.isNotBlank()) o.put("javaSig", f.javaSig)
            if (f.callers.isNotEmpty()) o.put("callers", JSONArray(f.callers))
            o.put("hits", JSONArray(f.hits))
            o.put("score", f.score)
            arr.put(o)
        }
        return arr
    }

    private fun categoryCounts(findings: List<VipEntryScanner.Finding>): JSONObject {
        val o = JSONObject()
        findings.groupBy { it.category }.forEach { (k, v) -> o.put(k, v.size) }
        return o
    }

    private fun nextHint(findings: List<VipEntryScanner.Finding>): String {
        if (findings.isEmpty()) return "没命中：这个应用可能没有会员体系，或方法名做了混淆（看 strings 组是否有线索）"
        val top = findings.maxByOrNull { it.score }
        val methodHits = findings.count { it.kind == "METHOD" }
        return "分数最高的是 ${top?.owner?.let { smaliToJava(it) } ?: top?.name}（${top?.score} 分）；" +
            "方法类命中 $methodHits 条，可直接用 action=hook 生成脚本验证"
    }

    private fun smaliToJava(type: String): String =
        runCatching { VipEntryScanner.smaliToJavaType(type) }.getOrDefault(type)

    val ALL = listOf(vipEntry)
}
