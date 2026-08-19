package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.bool
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 塔菲逆核: APK 签名校验绕过(去签名校验, 对标 kstools/ApkSignatureKiller 与 MT/NP 管理器的"去签名校验")。
 *
 * 静态 smali patch 方案: 对 taffy_apk_rebuild(decode, dex=true) 或 taffy_smali_batch(init)
 * 输出的 smali 目录做文本级检测 + 方法体 stub 替换, 让目标 app 的签名校验方法永远返回"通过"。
 * 不直接重编 DEX —— 由后续 taffy_apk_rebuild(build) / taffy_smali_batch(rebuild) 统一回编,
 * 再 taffy_apk_sign 重签名即可安装运行(绕过重打包后的签名校验)。
 *
 * action=detect  扫描签名校验方法(读 PackageInfo.signatures/signingInfo、getSignatures、
 *                 getPackageInfo+GET_SIGNATURES 等特征), 报告文件/方法/模式/行号。
 * action=patch   对检测到的校验方法做 stub 替换(patch 前自动备份到 .sign_kill_backup/)。
 * action=restore 从 .sign_kill_backup/ 恢复被 patch 的 smali 文件。
 */
object ApkSignKillTool {

    /** 签名校验检测模式: 名称 -> 正则(在方法体文本中匹配) */
    private data class Pattern(val name: String, val desc: String, val regex: Regex)

    /** 解析出的单个 .method 块: 方法签名行、方法体文本、起始行号 */
    private data class MethodBlock(val sigLine: String, val body: String, val startLine: Int)

    private val patterns = listOf(
        Pattern("signatures_field", "读取 PackageInfo.signatures 字段", Regex("""Landroid/content/pm/PackageInfo;->signatures:""")),
        Pattern("signing_info_field", "读取 PackageInfo.signingInfo 字段", Regex("""Landroid/content/pm/PackageInfo;->signingInfo:""")),
        Pattern("get_signatures", "调用 getSignatures()", Regex(""";->getSignatures\(\)""")),
        Pattern("get_signing_certs", "调用 getSigningCertificates()", Regex(""";->getSigningCertificates\(\)""")),
        Pattern("get_apk_signers", "调用 getApkContentsSigners()", Regex(""";->getApkContentsSigners\(\)""")),
        Pattern("get_cert_history", "调用 getSigningCertificateHistory()", Regex(""";->getSigningCertificateHistory\(\)""")),
        Pattern("sig_to_byte_array", "Signature.toByteArray() 哈希比对", Regex("""Landroid/content/pm/Signature;->toByteArray\(\)""")),
        Pattern("sig_to_chars", "Signature.toCharsString() 比对", Regex("""Landroid/content/pm/Signature;->toCharsString\(\)""")),
        Pattern("sig_hash_code", "Signature.hashCode() 比对", Regex("""Landroid/content/pm/Signature;->hashCode\(\)""")),
    )

    /** getPackageInfo + 签名 flags 强信号(需要方法体内同时出现) */
    private val getPackageInfoRe = Regex("""Landroid/content/pm/PackageManager;->getPackageInfo\(""")
    private val signFlagsRe = Regex("""0x40\b|0x8000000\b|0x80000000\b""")

    /** native .so 签名校验特征(ASCII 子串 -> 说明, 扫描 lib 目录下 so 二进制) */
    private val nativePatterns = listOf(
        "Landroid/content/pm/PackageManager;" to "JNI PackageManager 类型描述符",
        "Landroid/content/pm/PackageInfo;" to "JNI PackageInfo 类型描述符",
        "Landroid/content/pm/Signature;" to "JNI Signature 类型描述符",
        "getPackageInfo" to "JNI getPackageInfo 调用",
        "getSignatures" to "getSignatures 调用",
        "getSigningCertificates" to "getSigningCertificates 调用",
        "getApkContentsSigners" to "getApkContentsSigners 调用",
        "signingInfo" to "signingInfo 字段访问",
        "checkSignature" to "checkSignature 校验函数",
        "verifySignature" to "verifySignature 校验函数",
        "isSignatureValid" to "isSignatureValid 校验函数",
        "get_signature" to "get_signature 校验函数",
    )

    val kill: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_apk_sign_kill",
            "【APK 签名校验绕过】对已 decode 的 APK 目录做签名校验绕过(去签名校验, 对标 kstools/ApkSignatureKiller)。action=detect 扫描签名校验方法(PackageInfo.signatures/signingInfo、getSignatures、getPackageInfo+GET_SIGNATURES、Signature 哈希比对等特征), 报告文件/方法/模式; action=patch 把检测到的校验方法体替换成\"永远返回通过\"的 stub(patch 前自动备份到 .sign_kill_backup/); action=restore 从备份恢复。patch 后走 taffy_apk_rebuild(build) 回编 + taffy_apk_sign 重签名即可绕过重打包签名校验。",
            "Bypass APK signature verification (signature kill, like kstools/ApkSignatureKiller). action=detect scans signature-check methods (PackageInfo.signatures/signingInfo, getSignatures, getPackageInfo+GET_SIGNATURES, Signature hash compare); action=patch rewrites those method bodies into always-pass stubs (auto-backup to .sign_kill_backup/); action=restore reverts from backup. After patch, run taffy_apk_rebuild(build) + taffy_apk_sign to install without triggering repack signature checks.",
            "build", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "action".oneOf("detect (default) | patch | restore", "detect", "patch", "restore")
                "path" str "已 decode 的 APK 目录路径(taffy_apk_rebuild decode 或 taffy_smali_batch init 的输出)"
                "file" str "patch/restore: 只处理指定 smali 文件(相对路径或绝对路径, 可选, 缺省处理全部)"
                "dryRun" bool "patch: true 只报告将改动的方法而不实际写入(可选)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "detect").ifBlank { "detect" }
            val path = args.str("path").ifBlank { args.str("dir").ifBlank { args.str("filePath") } }
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path(decode 出的 APK 目录)", "path", "")
            val root = File(path)
            if (!root.isDirectory) return err("NOT_DIR", "path 不是目录或不存在: $path", "path", path)

            return runCatching {
                when (action) {
                    "detect" -> detect(root)
                    "patch" -> patch(root, args)
                    "restore" -> restore(root, args)
                    else -> err("BAD_ACTION", "未知 action: $action", "action", action)
                }
            }.getOrElse { e ->
                err("INTERNAL_ERROR", "处理失败: ${e.message}", "path", path)
            }
        }

        // ── 工具方法 ──

        private fun smaliFiles(root: File): List<File> =
            root.walkTopDown()
                .filter { it.isFile && it.extension == "smali" }
                .toList()

        /** 解析单个 smali 文件里所有 .method 块, 返回 (方法签名行, 方法体文本, 起始行号) */
        private fun parseMethods(text: String): List<MethodBlock> {
            val lines = text.split("\n")
            val result = mutableListOf<MethodBlock>()
            var i = 0
            while (i < lines.size) {
                val t = lines[i].trim()
                if (t.startsWith(".method")) {
                    val start = i
                    val sig = lines[i]
                    val bodyLines = mutableListOf<String>()
                    i++
                    var closed = false
                    while (i < lines.size) {
                        if (lines[i].trim() == ".end method") { closed = true; break }
                        bodyLines.add(lines[i])
                        i++
                    }
                    if (closed) {
                        result.add(MethodBlock(sig, bodyLines.joinToString("\n"), start + 1))
                    }
                }
                i++
            }
            return result
        }

        /** 判断方法体是否命中签名校验模式, 返回命中的模式名列表(空=非校验方法) */
        private fun matchPatterns(body: String): List<String> {
            val hits = patterns.filter { it.regex.containsMatchIn(body) }.map { it.name }.toMutableList()
            // 强信号: getPackageInfo + 签名 flags
            if (getPackageInfoRe.containsMatchIn(body) && signFlagsRe.containsMatchIn(body)) {
                hits.add("getPackageInfo_signFlags")
            }
            return hits
        }

        /** 解析 .method 签名行返回类型(最后一个 ')' 之后的部分) */
        private fun returnType(sigLine: String): String {
            val close = sigLine.lastIndexOf(')')
            if (close < 0) return "V"
            return sigLine.substring(close + 1).trim()
        }

        /** 生成 stub 方法体(替换 .method 与 .end method 之间) */
        private fun buildStub(ret: String): String {
            return when (ret) {
                "V" -> ".locals 0\n\n    return-void"
                "J" -> ".locals 2\n\n    const-wide/16 v0, 0x0\n\n    return-wide v0"
                "D" -> ".locals 2\n\n    const-wide/16 v0, 0x0\n\n    return-wide v0"
                "F" -> ".locals 1\n\n    const/4 v0, 0x0\n\n    return v0"
                "Z" -> ".locals 1\n\n    const/4 v0, 0x1\n\n    return v0"
                "B", "S", "C", "I" -> ".locals 1\n\n    const/4 v0, 0x0\n\n    return v0"
                else -> ".locals 1\n\n    const/4 v0, 0x0\n\n    return-object v0" // L...; / [...
            }
        }

        /** 把方法体 stub 替换进整个文件文本 */
        private fun applyStub(text: String, block: MethodBlock): String {
            val lines = text.split("\n")
            val start = block.startLine - 1
            // 找 .end method 行
            var end = start + 1
            while (end < lines.size && lines[end].trim() != ".end method") end++
            val stubLines = buildStub(returnType(block.sigLine)).split("\n")
            // 保留 .method 签名行 + stub 体 + .end method
            val replaced = listOf(block.sigLine) + stubLines + listOf(".end method")
            return (lines.subList(0, start) + replaced + lines.subList(end + 1, lines.size)).joinToString("\n")
        }

        private fun detect(root: File): JSONObject {
            val out = JSONArray()
            var methodCount = 0
            for (f in smaliFiles(root)) {
                val text = f.readText()
                for (mb in parseMethods(text)) {
                    val hits = matchPatterns(mb.body)
                    if (hits.isNotEmpty()) {
                        methodCount++
                        out.put(JSONObject()
                            .put("file", f.relativeTo(root).path)
                            .put("method", mb.sigLine.trim())
                            .put("line", mb.startLine)
                            .put("patterns", JSONArray(hits))
                            .put("returnType", returnType(mb.sigLine)))
                    }
                }
            }
            // native .so 签名校验特征检测(只检测不绕过: native 校验需动态 hook 或手动 patch)
            val nativeHits = JSONArray()
            for (so in soFiles(root)) {
                val hits = scanNativeSo(so)
                if (hits.isNotEmpty()) {
                    nativeHits.put(JSONObject()
                        .put("file", so.relativeTo(root).path)
                        .put("size", so.length())
                        .put("patterns", JSONArray(hits)))
                }
            }
            return ok(JSONObject()
                .put("action", "detect")
                .put("found", methodCount)
                .put("methods", out)
                .put("nativeFound", nativeHits.length())
                .put("nativeHits", nativeHits)
                .put("hint", "smali 层校验方法可用 action=patch 静态绕过; native 层校验(见 nativeHits)需用 frida hook(taffy_frida_control)或手动 patch .so。回编+重签名后即可绕过重打包签名校验。"))
        }

        /** 找 decode 目录下的 native .so(通常 lib/<abi>/*.so 或顶层 *.so) */
        private fun soFiles(root: File): List<File> =
            root.walkTopDown()
                .filter { it.isFile && it.extension == "so" }
                .take(200)
                .toList()

        /** 在 .so 二进制里搜索签名校验 ASCII 特征 */
        private fun scanNativeSo(so: File): List<String> {
            val bytes = runCatching { so.readBytes() }.getOrNull() ?: return emptyList()
            if (bytes.isEmpty()) return emptyList()
            val ascii = String(bytes, Charsets.ISO_8859_1)
            return nativePatterns.filter { (needle, _) -> ascii.contains(needle) }.map { (needle, desc) -> "$needle ($desc)" }
        }

        private fun patch(root: File, args: JSONObject): JSONObject {
            val dryRun = args.bool("dryRun", false)
            val onlyFile = args.str("file").ifBlank { "" }
            val files = if (onlyFile.isBlank()) smaliFiles(root)
            else {
                val f = File(onlyFile)
                listOf(if (f.isAbsolute) f else File(root, onlyFile)).filter { it.isFile && it.extension == "smali" }
            }

            val backupRoot = File(root, ".sign_kill_backup")
            val patched = JSONArray()
            var total = 0

            for (f in files) {
                val text = f.readText()
                val blocks = parseMethods(text)
                val targets = blocks.filter { matchPatterns(it.body).isNotEmpty() }
                if (targets.isEmpty()) continue

                var working = text
                // 按逆序替换, 避免行号偏移
                for (block in targets.sortedByDescending { it.startLine }) {
                    if (dryRun) {
                        patched.put(JSONObject()
                            .put("file", f.relativeTo(root).path)
                            .put("method", block.sigLine.trim())
                            .put("returnType", returnType(block.sigLine)))
                        total++
                        continue
                    }
                    // 备份原文件(只备份一次)
                    val rel = f.relativeTo(root)
                    val backup = File(backupRoot, rel.path)
                    backup.parentFile?.mkdirs()
                    if (!backup.exists()) backup.writeText(text)
                    working = applyStub(working, block)
                    patched.put(JSONObject()
                        .put("file", rel.path)
                        .put("method", block.sigLine.trim())
                        .put("returnType", returnType(block.sigLine)))
                    total++
                }
                if (!dryRun) f.writeText(working)
            }

            return ok(JSONObject()
                .put("action", "patch")
                .put("dryRun", dryRun)
                .put("patched", total)
                .put("methods", patched)
                .put("backup", if (dryRun) "" else File(root, ".sign_kill_backup").absolutePath)
                .put("hint", if (dryRun) "仅预览。去掉 dryRun=true 执行真实 patch。" else "patch 完成, 原文件已备份到 .sign_kill_backup/。接下来用 taffy_apk_rebuild(build) 回编 + taffy_apk_sign 重签名。"))
        }

        private fun restore(root: File, args: JSONObject): JSONObject {
            val backupRoot = File(root, ".sign_kill_backup")
            if (!backupRoot.isDirectory) return err("NO_BACKUP", "没有找到备份目录 .sign_kill_backup/", "path", root.path)
            val onlyFile = args.str("file").ifBlank { "" }
            val backups = if (onlyFile.isBlank()) {
                backupRoot.walkTopDown().filter { it.isFile && it.extension == "smali" }.toList()
            } else {
                val f = File(onlyFile)
                listOf(File(backupRoot, if (f.isAbsolute) f.name else onlyFile)).filter { it.isFile }
            }

            var restored = 0
            for (b in backups) {
                val rel = b.relativeTo(backupRoot).path
                val target = File(root, rel)
                target.parentFile?.mkdirs()
                b.copyTo(target, overwrite = true)
                restored++
            }
            return ok(JSONObject()
                .put("action", "restore")
                .put("restored", restored)
                .put("hint", "已从 .sign_kill_backup/ 恢复 $restored 个 smali 文件。"))
        }
    }

    val ALL: List<ToolHandler> = listOf(kill)
}
