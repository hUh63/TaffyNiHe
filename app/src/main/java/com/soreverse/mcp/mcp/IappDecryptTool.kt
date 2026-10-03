package com.soreverse.mcp.mcp

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.reandroid.apk.ApkModule
import com.soreverse.mcp.core.IappCrypto
import com.soreverse.mcp.core.IappDecrypt
import com.soreverse.mcp.core.bool
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.toHex
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.bouncycastle.cms.CMSSignedData
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.zip.ZipFile

/**
 * 塔菲逆核：内置「iApp v3 解密」MCP 工具（移植自 aiysss/iapp-decrypt）。
 *
 * iApp v3 打包的 APK 会把源码加密进 `assets/lib.so`（两层：外层 AES-CBC 容器 + 内层成员）。
 * 单一网关 [taffy_iapp_decrypt]：
 *  - action=extract：从 APK 自动提取解密所需参数（lib.so、libygsiyu.so→sok、签名 DER、清单包名/版本/应用名、dex 常量 dek）。
 *  - action=decrypt：给定（或自动提取的）参数解密 lib.so，导出全部内层源码成员。
 */
object IappDecryptTool {

    private val SO_PATHS = listOf(
        "lib/x86/libygsiyu.so", "lib/arm64-v8a/libygsiyu.so",
        "lib/x86_64/libygsiyu.so", "lib/armeabi-v7a/libygsiyu.so",
    )
    private val LIB_SO_PATHS = listOf("assets/lib.so", "assets/lib/lib.so", "lib.so")

    val tool: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_iapp_decrypt",
            "【iApp v3 解密】解密 iApp 打包 APK 的 assets/lib.so（外层 AES-CBC 容器 + 内层成员源码）。" +
                "action=extract 从 APK 自动提取参数（lib.so/原生 so→sok/签名 DER/清单包名版本应用名/dex 常量 dek）；" +
                "action=decrypt 用参数（可自动补全）解密并导出 mian.iyu 等全部内层源码。算法族：current/legacy4/transitional。",
            "Decrypt iApp v3 packed assets/lib.so (AES-CBC container + inner member sources). " +
                "action=extract auto-extracts params (lib.so, native so -> sok, signature DER, manifest package/version/label, dex const dek); " +
                "action=decrypt decrypts and exports all inner sources. Families: current/legacy4/transitional.",
            "decompile", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("extract (default) | decrypt", "extract", "decrypt")
                "path" str "APK 文件绝对路径"
                "filePath" str "path 的别名"
                "soPath" str "lib.so 绝对路径（action=decrypt；缺省时从 APK 的 assets/lib.so 取）"
                "outputDir" str "输出目录（缺省写到应用私有目录 iapp-out/<name>）"
                "packageName" str "应用包名（decrypt，缺省自动从 APK 提取）"
                "versionName" str "版本名（decrypt，缺省自动提取）"
                "versionCode" str "版本号（decrypt，缺省自动提取）"
                "appName" str "应用名(标签)（decrypt，缺省自动提取）"
                "sok" str "原生 so 密钥（decrypt，缺省自动提取）"
                "dek" str "dex 常量密钥（decrypt，缺省自动提取）"
                "entryFile" str "入口成员（默认 mian.iyu）"
                "signKey" str "签名明文密钥（legacy4 用）"
                "signB64" str "签名 DER 的 base64（transitional/签名绑定候选用）"
                "pwdKey" str "密码密钥（legacy4 用）"
                "mode".oneOf("auto (default) | current | legacy | legacy4 | transitional", "auto", "current", "legacy", "legacy4", "transitional")
                "maxFiles" int "最多导出成员数（默认 300）"
                "includeContent" bool "是否在结果里内联成员文本（默认 false，只给路径/大小）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "extract").ifBlank { "extract" }
            val apkPath = args.str("path").ifBlank { args.str("filePath") }
            val soPath = args.str("soPath")
            return runCatching {
                when (action) {
                    "decrypt" -> doDecrypt(ctx, args, apkPath, soPath)
                    else -> doExtract(ctx, args, apkPath)
                }
            }.getOrElse { e -> err("IAPP_FAILED", "iApp 处理失败: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    // ── extract ──────────────────────────────────────────────────
    private fun doExtract(ctx: ToolContext, args: JSONObject, apkPath: String): JSONObject {
        if (apkPath.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path(APK 路径)", "path", "")
        val apk = File(apkPath)
        if (!apk.isFile) return err("FILE_NOT_FOUND", "文件不存在: $apkPath", "path", apkPath)

        val out = resolveOutDir(ctx, args, apk.nameWithoutExtension)
        val libSoName: String?
        val libSo: ByteArray?
        val nativeSoName: String?
        val nativeSo: ByteArray?
        val signDer: ByteArray?

        ZipFile(apk).use { zip ->
            libSoName = findEntry(zip, LIB_SO_PATHS, { it.endsWith("/lib.so") || it == "lib.so" })
            libSo = libSoName?.let { zip.getInputStream(zip.getEntry(it)).use { s -> s.readBytes() } }
            nativeSoName = findEntry(zip, SO_PATHS, { it.endsWith("libygsiyu.so") })
            nativeSo = nativeSoName?.let { zip.getInputStream(zip.getEntry(it)).use { s -> s.readBytes() } }
            signDer = readSignatureDer(zip)
        }
        if (libSo == null) return err("IAPP_NO_LIB_SO", "APK 中未找到 assets/lib.so（可能不是 iApp v3 打包包）", "path", apkPath)

        val sok = nativeSo?.let { IappDecrypt.extractSokFromSo(it) }
        var pkg = ""; var ver = ""; var code = ""; var label = ""
        runCatching {
            val module = ApkModule.loadApkFile(apk)
            val mf = module.androidManifest
            pkg = runCatching { mf.packageName }.getOrNull() ?: ""
            ver = runCatching { mf.versionName }.getOrNull() ?: ""
            code = runCatching { mf.versionCode }.getOrNull()?.toString() ?: ""
            label = resolveLabel(module)
        }
        val dek = extractDek(ctx, apk)

        libSoName?.let { writeOut(File(out, "lib.so"), libSo) }
        if (nativeSo != null && nativeSoName != null) writeOut(File(out, nativeSoName.substringAfterLast('/')), nativeSo)

        val result = JSONObject()
            .put("tool", "taffy_iapp_decrypt").put("action", "extract")
            .put("apk", apkPath)
            .put("outputDir", out.absolutePath)
            .put("libSo", JSONObject().put("entry", libSoName).put("size", libSo.size).put("sha256", sha256(libSo)))
            .put("nativeSo", JSONObject().put("entry", nativeSoName ?: JSONObject.NULL).put("size", nativeSo?.size ?: 0))
            .put("sok", sok ?: JSONObject.NULL)
            .put("detectedMode", if (sok != null && sok.length <= 4) "legacy4" else "current")
            .put("manifest", JSONObject().put("packageName", pkg).put("versionName", ver).put("versionCode", code).put("appName", label))
            .put("dek", dek ?: JSONObject.NULL)
            .put("signKey", if (signDer != null) IappCrypto.md5(signDer).toHex() else JSONObject.NULL)
            .put("signB64", if (signDer != null) Base64.getEncoder().encodeToString(signDer) else JSONObject.NULL)
            .put("hint", "把上述参数填入 action=decrypt（packageName/versionName/versionCode/appName/sok/dek 为必需）")
        return ok(result)
    }

    // ── decrypt ──────────────────────────────────────────────────
    private fun doDecrypt(ctx: ToolContext, args: JSONObject, apkPath: String, soPath: String): JSONObject {
        val apk = if (apkPath.isNotBlank()) File(apkPath) else null
        val libSo = when {
            soPath.isNotBlank() -> File(soPath).takeIf { it.isFile }?.readBytes()
            apk != null && apk.isFile -> ZipFile(apk).use { zip ->
                findEntry(zip, LIB_SO_PATHS, { it.endsWith("/lib.so") || it == "lib.so" })
                    ?.let { zip.getInputStream(zip.getEntry(it)).use { s -> s.readBytes() } }
            }
            else -> null
        } ?: return err("IAPP_NO_LIB_SO", "找不到 lib.so：请提供 soPath 或含 assets/lib.so 的 APK path", "soPath", soPath)

        // 自动补全参数
        var pkg = args.str("packageName")
        var ver = args.str("versionName")
        var code = args.str("versionCode")
        var label = args.str("appName")
        var sok = args.str("sok")
        var dek = args.str("dek")
        var signB64 = args.str("signB64")
        val signKey = args.str("signKey")
        if (apk != null && apk.isFile && (pkg.isBlank() || ver.isBlank() || code.isBlank() || label.isBlank() || sok.isBlank() || dek.isBlank() || signB64.isBlank())) {
            runCatching {
                val module = ApkModule.loadApkFile(apk)
                val mf = module.androidManifest
                if (pkg.isBlank()) pkg = runCatching { mf.packageName }.getOrNull() ?: ""
                if (ver.isBlank()) ver = runCatching { mf.versionName }.getOrNull() ?: ""
                if (code.isBlank()) code = runCatching { mf.versionCode }.getOrNull()?.toString() ?: ""
                if (label.isBlank()) label = resolveLabel(module)
            }
            if (sok.isBlank() || signB64.isBlank()) {
                ZipFile(apk).use { zip ->
                    if (sok.isBlank()) {
                        val nso = findEntry(zip, SO_PATHS, { it.endsWith("libygsiyu.so") })
                        if (nso != null) IappDecrypt.extractSokFromSo(zip.getInputStream(zip.getEntry(nso)).use { it.readBytes() })?.let { sok = it }
                    }
                    if (signB64.isBlank()) readSignatureDer(zip)?.let { signB64 = Base64.getEncoder().encodeToString(it) }
                }
            }
            if (dek.isBlank()) dek = extractDek(ctx, apk) ?: ""
        }

        val config = IappDecrypt.IappConfig(
            packageName = pkg, versionName = ver, versionCode = code, appName = label,
            sok = sok, dek = dek, entryFile = args.str("entryFile", "mian.iyu").ifBlank { "mian.iyu" },
            signKey = signKey, signB64 = signB64, pwdKey = args.str("pwdKey"),
        )
        val mode = args.str("mode", "auto").ifBlank { "auto" }
        val maxFiles = args.intValue("maxFiles", 300).coerceIn(1, 5000)
        val result = IappDecrypt.decrypt(libSo, config, mode)

        val out = resolveOutDir(ctx, args, (apk?.nameWithoutExtension ?: "iapp"))
        val filesArr = JSONArray()
        val inline = args.bool("includeContent", false)
        var written = 0
        for ((name, content) in result.files) {
            if (written >= maxFiles) break
            val safe = safeMemberPath(name) ?: continue
            val f = File(out, safe)
            writeOut(f, content)
            val item = JSONObject().put("name", name).put("path", f.absolutePath).put("size", content.size)
            if (inline && content.size <= 65536) item.put("content", String(content, Charsets.UTF_8))
            filesArr.put(item)
            written++
        }

        val traceObj = JSONObject()
        for ((k, v) in result.trace) traceObj.put(k, v)
        val failedArr = JSONArray()
        result.failed.forEach { failedArr.put(it) }

        return ok(JSONObject()
            .put("tool", "taffy_iapp_decrypt").put("action", "decrypt")
            .put("outputDir", out.absolutePath)
            .put("fileCount", result.files.size)
            .put("written", written)
            .put("containerSize", result.container.size)
            .put("files", filesArr)
            .put("failed", failedArr)
            .put("strategy", traceObj))
    }

    // ── 辅助 ─────────────────────────────────────────────────────
    private fun resolveOutDir(ctx: ToolContext, args: JSONObject, nameHint: String): File {
        val explicit = args.str("outputDir")
        val dir = if (explicit.isNotBlank()) File(explicit)
        else File(File(ctx.context.filesDir, "iapp-out"), nameHint.replace(Regex("[^A-Za-z0-9_.-]"), "_"))
        dir.mkdirs()
        return dir
    }

    private fun writeOut(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    private fun safeMemberPath(name: String): String? {
        val n = name.replace('\\', '/').trim().trimStart('/')
        if (n.isEmpty() || n.split('/').any { it == ".." }) return null
        return n
    }

    private fun findEntry(zip: ZipFile, preferred: List<String>, predicate: (String) -> Boolean): String? {
        for (p in preferred) if (zip.getEntry(p) != null) return p
        val names = zip.entries().asSequence().map { it.name }.toList()
        return names.firstOrNull(predicate)
    }

    private fun readSignatureDer(zip: ZipFile): ByteArray? {
        val candidates = ArrayList<String>()
        candidates.add("META-INF/CERT.RSA"); candidates.add("META-INF/ANDROIDX.RSA")
        for (e in zip.entries()) {
            val upper = e.name.uppercase()
            if (upper.startsWith("META-INF/") && upper.endsWith(".RSA") && e.name !in candidates) candidates.add(e.name)
        }
        for (path in candidates) {
            val entry = zip.getEntry(path) ?: continue
            val blob = zip.getInputStream(entry).use { it.readBytes() }
            val der = try { CMSSignedData(blob).certificates.getMatches(null).firstOrNull()?.encoded } catch (e: Exception) { null }
            if (der != null) return der
        }
        return null
    }

    private fun resolveLabel(module: ApkModule): String {
        val mf = module.androidManifest
        val literal = runCatching { mf.applicationLabelString }.getOrNull()
        if (!literal.isNullOrBlank()) return literal
        val ref = runCatching { mf.applicationLabelReference }.getOrNull() ?: return ""
        return runCatching {
            module.tableBlock.resolveReference(ref).firstOrNull()?.valueAsString ?: ""
        }.getOrDefault("")
    }

    private fun extractDek(ctx: ToolContext, apk: File): String? {
        val dexEntry: String?
        ZipFile(apk).use { zip ->
            dexEntry = zip.entries().asSequence().map { it.name }
                .firstOrNull { it.endsWith(".dex") && !it.contains('/') } ?: zip.entries().asSequence().map { it.name }.firstOrNull { it.endsWith(".dex") }
            if (dexEntry == null) return null
            val tmp = File.createTempFile("iapp-", ".dex", ctx.context.cacheDir)
            try {
                zip.getInputStream(zip.getEntry(dexEntry!!)).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val dexFile = DexFileFactory.loadDexFile(tmp, Opcodes.getDefault())
                for (cls in dexFile.classes) {
                    if (cls.type != "Lcom/iapp/app/f;") continue
                    for (m in cls.methods) {
                        if (m.name != "b" || m.parameterTypes.isNotEmpty() || m.returnType != "I") continue
                        val impl = m.implementation ?: continue
                        var lit: Int? = null
                        for (insn in impl.instructions) {
                            if (insn is NarrowLiteralInstruction) lit = insn.narrowLiteral
                        }
                        if (lit != null) return lit.toString()
                    }
                }
            } catch (e: Exception) {
                // ignore, best effort
            } finally {
                tmp.delete()
            }
        }
        return null
    }

    private fun sha256(bytes: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val sb = StringBuilder()
        for (b in md.digest(bytes)) sb.append(String.format("%02x", b.toInt() and 0xFF))
        return sb.toString()
    }

    val ALL = listOf(tool)
}
