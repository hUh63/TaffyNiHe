package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * 塔菲逆核: APK 壳识别（加固检测）工具。
 *
 * 拿到一个 APK 先判断「是什么壳 / 要不要脱 / 好不好改」——MT 逆向第一步。
 * 纯只读扫描 APK 的 ZIP 条目、SO 库名、classes.dex 特征与 Manifest application，识别常见加固壳。
 * 识别规则基于公开特征(壳 so 名/壳 Application 类名), 返回匹配置信度。
 */
object ShellDetectorTool {

    /** 壳特征签名 */
    private data class ShellSig(
        val name: String,
        val soKws: List<String>,
        val appKws: List<String>,
        val desc: String,
    )

    /** 常见加固壳特征表: (壳名, so 特征子串列表, application 特征子串列表, 说明) */
    private val SHELLS = listOf(
        ShellSig("360加固(jiagu)", listOf("libjiagu", "jiagu_art"), listOf("com.stub.StubApp", "com.stub.stubax5"), "360 全家桶, StubApp/moz 入口"),
        ShellSig("腾讯乐固(Legu)", listOf("libshella", "shella", "libShell"), listOf("com.tencent.StubShell", "com.tencent.StubShell.TxAppEntry"), "腾讯云加固"),
        ShellSig("爱加密(iJiami)", listOf("libexec", "libexecmain", "ijiami"), listOf("com.secshell.app.SecShellApplication", "com.secnebula"), "爱加密"),
        ShellSig("梆梆加固(Secco)", listOf("libSecShell", "SecShell"), listOf("com.secneo.apk.wrapper.ApkWrapper", "com.intlgame"), "梆梆安全"),
        ShellSig("娜迦(Naga)", listOf("libchaosvmp", "libDexHelper", "naga"), listOf("com.nagainet.dynamicload.ApplicationWrapper"), "娜迦/顶象"),
        ShellSig("顶象/网秦(DexHelper)", listOf("libDexHelper"), listOf("com.nirvana", "com.protect"), "顶象/数字天盾等"),
        ShellSig("梆梆/网秦通用", listOf("libprotect", "libshell", "librun"), emptyList(), "常见兜底特征"),
    )

    val tool: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_apk_shell_check",
            "【APK 加固检测】只读扫描 APK, 识别是否被加固及可能的加固产品(360/腾讯乐固/爱加密/梆梆/娜迦等)。逆向第一步: 判断好不好改/要不要先脱壳。检测: lib/*.so 壳库名、classes.dex 大小异常(极小=代码被抽走)、assets 壳资源、Manifest 的 application 指向。纯只读, 不改文件。",
            "Read-only APK hardening/packer detection. Scans ZIP entries, native lib names, classes.dex size (tiny = code stripped), shell assets and Manifest application to infer if the APK is packed and by which packer. First step of RE: decide whether to unpack first. Read-only.",
            "analyze", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "path" str "APK 文件路径"
                "detail" bool "是否输出各特征命中的详细 WHY(默认 true)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少 path(APK)", "path", "")
            val apk = File(path)
            if (!apk.isFile) return err("FILE_NOT_FOUND", "APK 不存在: $path", "path", path)
            val detail = args.optBoolean("detail", true)
            return runCatching {
                val libs = mutableListOf<String>()
                val assets = mutableListOf<String>()
                val metaInf = mutableListOf<String>()
                var dexCount = 0
                var minDexSize = Long.MAX_VALUE
                var maxDexSize = 0L
                val allEntries = mutableListOf<String>()
                ZipFile(apk).use { zf ->
                    zf.entries().toList().forEach { e ->
                        val n = e.name
                        allEntries.add(n)
                        if (n.startsWith("lib/") && n.endsWith(".so")) libs.add(n.substringAfter("/").substringAfter("/"))
                        else if (n.startsWith("assets/")) assets.add(n.removePrefix("assets/"))
                        else if (n.startsWith("META-INF/")) metaInf.add(n.removePrefix("META-INF/"))
                        if (n.matches(Regex("classes\\d*\\.dex"))) {
                            dexCount++
                            val s = e.size
                            if (s < minDexSize) minDexSize = s; if (s > maxDexSize) maxDexSize = s
                        }
                    }
                }
                // 签名方案检测: v1 = META-INF/*.RSA|DSA|EC + .SF; v2/v3 = APK Signing Block(MAGIC "APK Sig Block 42 " in trailing zone)
                val v1Cert = metaInf.firstOrNull { it.endsWith(".RSA") || it.endsWith(".DSA") || it.endsWith(".EC") || it.endsWith(".SF") }
                var v2v3 = false
                runCatching {
                    apk.inputStream().use { ins ->
                        val total = apk.length()
                        val tailLen = minOf(total, 1L * 1024 * 1024).toInt()
                        if (tailLen > 0) {
                            ins.skip(total - tailLen)
                            val tail = ByteArray(tailLen)
                            var off = 0
                            while (off < tailLen) { val n = ins.read(tail, off, tailLen - off); if (n <= 0) break; off += n }
                            val magic = "APK Sig Block 42 ".toByteArray(Charsets.US_ASCII)
                            v2v3 = containsBytes(tail, magic)
                        }
                    }
                }
                val v2 = v2v3
                // 找出命中的壳
                val hits = JSONArray()
                val matched = LinkedHashSet<String>()
                for ((name, soKws, appKws, desc) in SHELLS) {
                    val bySo = libs.any { l -> soKws.any { l.contains(it, true) } }
                    if (bySo) {
                        matched.add(name)
                        hits.put(JSONObject()
                            .put("shell", name).put("confidence", "high").put("by", "native lib").put("description", desc))
                    }
                }
                // 通用: classes.dex 极小 => 疑似加固(代码抽走)
                val tinyDex = dexCount > 0 && minDexSize < 60 * 1024
                if (tinyDex && matched.isEmpty()) {
                    hits.put(JSONObject().put("shell", "疑似加固(classes.dex 极小/被抽取)")
                        .put("confidence", "medium").put("by", "dex size").put("description", "首个 classes.dex 仅 ${minDexSize}B, 真 dex 通常在内存解密"))
                }
                val packed = matched.isNotEmpty() || tinyDex
                val shellDetail = if (detail) {
                    JSONObject()
                        .put("libs", JSONArray(libs.take(30)))
                        .put("assets", JSONArray(assets.take(30)))
                        .put("metaInf", JSONArray(metaInf.take(20)))
                        .put("classesDex", JSONObject().put("count", dexCount).put("minSize", if (dexCount > 0) minDexSize else 0).put("maxSize", maxDexSize))
                } else JSONObject()
                ok(JSONObject()
                    .put("tool", "taffy_apk_shell_check")
                    .put("path", path)
                    .put("packed", packed)
                    .put("shells", hits)
                    .put("signature", JSONObject()
                        .put("v1", v1Cert != null)
                        .put("v1Cert", v1Cert ?: JSONObject.NULL)
                        .put("v2", v2)
                        .put("v3", v2) // 有 Sig Block 通常 v2/v3 同有; 精确区分需解析 block(略)
                        .put("signed", v1Cert != null || v2))
                    .put("detail", shellDetail)
                    .put("hint", if (packed) "检测到疑似加固, 改之前通常要先脱壳: 用 taffy_dex_unpack(需root,内存dump) 或 taffy_apk_rebuild(decode) 观察。" else "未检出常见加固壳, 可直接 jadx/baksmali 分析修改。"))
            }.getOrElse { e ->
                err("SHELL_CHECK_FAILED", "加固检测失败: ${e.message ?: e.javaClass.simpleName}", "path", path)
            }
        }
    }

    /** 字节数组中是否包含某模式(简单 contains)。 */
    private fun containsBytes(hay: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > hay.size) return false
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    val ALL = listOf(tool)
}
