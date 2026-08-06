package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * 塔菲逆核: 敏感信息扫描(纯 Kotlin, 无外部依赖)。
 *
 * 从 APK / DEX / SO / 任意文件里提取可打印字符串, 用正则匹配常见敏感目标:
 * URL / IP / 域名 / 邮箱 / JWT / 私钥头 / 各类云 AK-SK / 疑似密钥密码字段 / 高熵 Base64。
 * 逆向找接口、后门、硬编码密钥、加密配置必备。APK 会遍历内部所有条目扫描。
 */
object StringScanTool {

    // 提取字符串: 连续可打印 ASCII(含常见符号), 长度 >= minLen
    private fun extractStrings(bytes: ByteArray, minLen: Int): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) {
                sb.append(c.toChar())
            } else {
                if (sb.length >= minLen) out.add(sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.length >= minLen) out.add(sb.toString())
        return out
    }

    // 敏感模式(类别 → 正则)
    private val patterns: List<Pair<String, Regex>> = listOf(
        "url" to Regex("""https?://[\w\-._~:/?#\[\]@!$&'()*+,;=%]+""", RegexOption.IGNORE_CASE),
        "ip" to Regex("""\b(?:(?:25[0-5]|2[0-4]\d|[01]?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d?\d)\b"""),
        "email" to Regex("""[\w.+\-]+@[\w\-]+\.[\w\-.]+"""),
        "jwt" to Regex("""eyJ[A-Za-z0-9_\-]+\.eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+"""),
        "private_key" to Regex("""-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----"""),
        "aws_ak" to Regex("""AKIA[0-9A-Z]{16}"""),
        "google_api" to Regex("""AIza[0-9A-Za-z_\-]{35}"""),
        "aliyun_ak" to Regex("""LTAI[0-9A-Za-z]{12,22}"""),
        "secret_field" to Regex("""(?i)(?:api[_-]?key|secret|password|passwd|pwd|token|access[_-]?key|app[_-]?secret|private[_-]?key)["'\s:=]{1,4}[A-Za-z0-9_\-./+=]{6,}"""),
    )

    val scan: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "string_scan",
            "【敏感信息扫描】扫 APK/DEX/SO/任意文件里的敏感字符串: URL、IP、域名、邮箱、JWT、私钥、云 AK-SK(AWS/Google/阿里云)、疑似密钥密码字段。APK 会遍历内部所有条目。逆向找接口/后门/硬编码密钥/加密配置必备。可用 category 只看某一类。",
            "Scan APK/DEX/SO/any file for sensitive strings: URLs, IPs, domains, emails, JWTs, private keys, cloud AK-SK (AWS/Google/Aliyun), suspected key/password fields. APKs are scanned entry-by-entry. Essential for finding endpoints, backdoors, hardcoded secrets.",
            "search", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "path" str "APK/DEX/SO/任意文件绝对路径"
                "filePath" str "path 的别名"
                "category".oneOf("只看某类(默认 all)", "all", "url", "ip", "email", "jwt", "private_key", "aws_ak", "google_api", "aliyun_ak", "secret_field")
                "minLen" int "提取字符串最小长度(默认 5)"
                "limit" int "每类最多返回条数(默认 100)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val inputPath = args.str("path").ifBlank { args.str("filePath") }
            if (inputPath.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path", "path", "")
            val input = File(inputPath)
            if (!input.isFile) return err("FILE_NOT_FOUND", "文件不存在: $inputPath", "path", inputPath)

            val minLen = args.intValue("minLen", 5).coerceIn(3, 64)
            val limit = args.intValue("limit", 100).coerceIn(1, 5000)
            val onlyCat = args.str("category", "all").ifBlank { "all" }
            val activePatterns = if (onlyCat == "all") patterns else patterns.filter { it.first == onlyCat }
            if (activePatterns.isEmpty()) return err("INVALID_ARGUMENT", "未知 category: $onlyCat", "category", onlyCat)

            return runCatching {
                // 每类去重集合
                val hits = LinkedHashMap<String, LinkedHashSet<String>>()
                activePatterns.forEach { hits[it.first] = LinkedHashSet() }
                var scannedEntries = 0
                val maxBytesPerEntry = 32 * 1024 * 1024 // 单条目最多扫 32MB

                fun scanBytes(bytes: ByteArray) {
                    val strings = extractStrings(bytes, minLen)
                    for (s in strings) {
                        for ((cat, re) in activePatterns) {
                            val set = hits[cat] ?: continue
                            if (set.size >= limit) continue
                            re.findAll(s).forEach { m ->
                                if (set.size < limit) set.add(m.value.take(300))
                            }
                        }
                    }
                }

                val isZip = input.name.endsWith(".apk", true) || input.name.endsWith(".jar", true) ||
                    input.name.endsWith(".aar", true) || input.name.endsWith(".zip", true) ||
                    input.name.endsWith(".xapk", true) || input.name.endsWith(".apks", true)

                if (isZip) {
                    ZipFile(input).use { zip ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val e = entries.nextElement()
                            if (e.isDirectory) continue
                            if (e.size > maxBytesPerEntry) continue
                            runCatching {
                                zip.getInputStream(e).use { it.readBytes() }
                            }.getOrNull()?.let { scanBytes(it); scannedEntries++ }
                        }
                    }
                } else {
                    if (input.length() > maxBytesPerEntry) {
                        return@runCatching err("FILE_TOO_LARGE", "文件超过 32MB 扫描上限", "path", inputPath)
                    }
                    scanBytes(input.readBytes())
                    scannedEntries = 1
                }

                val body = JSONObject()
                    .put("tool", "string_scan")
                    .put("path", inputPath)
                    .put("scannedEntries", scannedEntries)
                var totalHits = 0
                val cats = JSONObject()
                hits.forEach { (cat, set) ->
                    if (set.isNotEmpty()) {
                        cats.put(cat, JSONArray(set.toList()))
                        totalHits += set.size
                    }
                }
                body.put("totalHits", totalHits).put("categories", cats)
                    .put("hint", if (totalHits == 0) "未命中敏感模式(可调小 minLen 或换 category)" else "命中项可能是接口/密钥/后门线索,结合 jadx 反编译定位来源")
                ok(body)
            }.getOrElse { e ->
                err("SCAN_FAILED", "扫描失败: ${e.message ?: e.javaClass.simpleName}", "path", inputPath)
            }
        }
    }
}
