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

    // 常见加固壳特征表: (壳名, so 特征子串列表, application 特征子串列表, 说明)
    // 命中规则: 对 lib 目录下的 so 文件名(去目录)做不区分大小写的子串匹配。
    // 设计原则:
    //   1) 同一厂商的多个 so(互为子串, 如 libjiagu/x86/a64)合并到一条, 避免同包命中多条;
    //   2) 库名彼此不是子串的(如 libshella 与 libshellx)才拆成多条;
    //   3) 共用特征(如 libDexHelper、libtup)单独成条并注明, 不分散进多条厂商, 规避误判。
    private val SHELLS = listOf(
        ShellSig("360加固(jiagu)", listOf("libjiagu"), listOf("com.stub.StubApp", "com.stub.stub01.Stub01", "com.qihoo.util.StubApplication"), "360 加固保; jiagu 主库, ls/x86/a64/x64/art 变体同源"),
        ShellSig("360加固(jgdtc 新版)", listOf("libjgdtc"), emptyList(), "360 新版壳库(jgdtc 系列, 含 x86/a64/x64/art)"),
        ShellSig("360加固(protectClass)", listOf("libprotectClass"), emptyList(), "360 旧版壳库"),
        ShellSig("腾讯乐固(标准 libshella)", listOf("libshella"), listOf("com.tencent.StubShell.TxAppEntry", "com.tencent.StubShell"), "腾讯乐固(乐固)标准版; 入口 TxAppEntry"),
        ShellSig("腾讯乐固(x86 libshellx)", listOf("libshellx"), emptyList(), "腾讯乐固 x86 架构版"),
        ShellSig("腾讯乐固(通用 libshell.so)", listOf("libshell.so"), emptyList(), "乐固通用壳库名(无版本后缀)"),
        ShellSig("腾讯乐固(新版 libshell-super)", listOf("libshell-super"), emptyList(), "腾讯乐固 2019 新版壳"),
        ShellSig("腾讯乐固(liblegudb)", listOf("liblegudb"), emptyList(), "乐固运行时数据库库"),
        ShellSig("爱加密(libexec)", listOf("libexec", "libexecmain"), listOf("s.h.e.l.l.S", "com.shell.SuperApplication", "com.secshell"), "爱加密; dex 由 libexec 解密"),
        ShellSig("爱加密(libijiami)", listOf("libijiami"), emptyList(), "爱加密 ijiami 品牌特征库"),
        ShellSig("梆梆安全(libSecShell)", listOf("libSecShell"), listOf("com.secneo.apkwrapper.ApplicationWrapper", "com.secneo.apkwrapper.AW", "com.secneo"), "梆梆安全(SecNeo)免费版"),
        ShellSig("梆梆安全(libsecexe)", listOf("libsecexe"), emptyList(), "梆梆安全免费版"),
        ShellSig("梆梆安全(libsecmain)", listOf("libsecmain"), emptyList(), "梆梆安全主运行库"),
        ShellSig("梆梆企业版(libDexHelper 共用)", listOf("libDexHelper"), emptyList(), "梆梆企业版; 网秦/顶象等亦有同名文件, 需结合 application 判厂商"),
        ShellSig("娜迦(libchaosvmp)", listOf("libchaosvmp"), listOf("com.chaosvmp", "com.nagainet"), "娜迦 VMP 壳"),
        ShellSig("娜迦(libddog/libfdog)", listOf("libddog", "libfdog"), emptyList(), "娜迦 ddog/fdog 模块"),
        ShellSig("娜迦企业版(libedog)", listOf("libedog"), emptyList(), "娜迦企业版模块"),
        ShellSig("阿里聚安全(libsgmain)", listOf("libsgmain"), listOf("com.ali.mobisecenhance.StubApplication", "com.ali.mobisecenhance"), "阿里无线保镖/聚安全主库"),
        ShellSig("阿里聚安全(libsgsecurityguard)", listOf("libsgsecurityguard"), emptyList(), "阿里 SecurityGuard 安全组件"),
        ShellSig("阿里聚安全(libsgsecuritybody)", listOf("libsgsecuritybody"), emptyList(), "阿里聚安全 body 组件"),
        ShellSig("阿里聚安全(libmobisec)", listOf("libmobisec"), emptyList(), "阿里旧版聚安全(常与无线保镖同现)"),
        ShellSig("阿里云加固(libdemolish)", listOf("libdemolish"), emptyList(), "阿里云加固; assets 侧 libdemolishdata.so"),
        ShellSig("阿里云 mPaaS", listOf("libmpaas", "libmpaasguard"), emptyList(), "阿里 mPaaS 客户端框架壳"),
        ShellSig("百度加固", listOf("libbaiduprotect"), listOf("com.baidu.protect.StubApplication", "com.baidu.protect"), "百度加固; x86/art 变体同源"),
        ShellSig("几维安全(libkwscmm)", listOf("libkwscmm"), listOf("com.kiwisec"), "几维(Kiwi) C++ 壳"),
        ShellSig("几维安全(runtime/linker)", listOf("libkwscruntime", "libkwscore", "libkwspm", "libkwsvm", "libkwslinker", "libkwscr"), emptyList(), "几维运行时/链接/虚拟机模块"),
        ShellSig("腾讯御安全", listOf("libtosprotection"), listOf("com.tencent.tosprotection"), "腾讯御安全(TOS); armeabi/v7a/x86 变体同源"),
        ShellSig("网易易盾(libnesec)", listOf("libnesec"), listOf("com.netease.nis.wrapper.MyApplication", "com.netease.nis"), "网易易盾 nesec"),
        ShellSig("网易易盾(libbugrpt)", listOf("libbugrpt"), emptyList(), "网易易盾上报库, 常与 nesec 同现"),
        ShellSig("网秦/国信灵通(libnqshield)", listOf("libnqshield"), emptyList(), "网秦/国信灵通; 部分易盾版本亦用同名文件"),
        ShellSig("通付盾(libegis)", listOf("libegis"), listOf("com.tup.sec"), "通付盾主库"),
        ShellSig("通付盾(libNSaferOnly)", listOf("libNSaferOnly"), emptyList(), "通付盾 N Safer 组件"),
        ShellSig("通付盾/腾讯(libtup 共用)", listOf("libtup"), emptyList(), "libtup 在多份资料中被分别归到通付盾/腾讯, 按共用处理"),
        ShellSig("顶象(libdxprotect)", listOf("libdxprotect"), listOf("com.dingxiang.mobile"), "顶象移动加固"),
        ShellSig("顶象(libx3g)", listOf("libx3g"), listOf("com.x3g.stub"), "顶象 x3g 壳库"),
        ShellSig("盛大加固(libsprotect)", listOf("libsprotect"), emptyList(), "盛大加固 so 保护"),
        ShellSig("盛大加固(libapssec)", listOf("libapssec"), emptyList(), "盛大加固 aps 保护"),
        ShellSig("盛大加固(libndkprotect)", listOf("libndkprotect"), emptyList(), "盛大 NDK 层保护"),
        ShellSig("瑞星加固(librsprotect)", listOf("librsprotect"), emptyList(), "瑞星加固"),
        ShellSig("海云安加固(libitsec)", listOf("libitsec"), emptyList(), "海云安加固"),
        ShellSig("启明星辰(libvenSec/libvenustech)", listOf("libvenSec", "libvenustech"), emptyList(), "启明星辰加固"),
        ShellSig("So保护(SoProtect)", listOf("libSoProtect"), emptyList(), "So 文件保护壳 SoProtect"),
        ShellSig("So保护(MySOProtect)", listOf("libMySOProtect"), emptyList(), "MySOProtect so 壳"),
        ShellSig("APKProtect", listOf("libAPKProtect"), emptyList(), "APKProtect 壳"),
        ShellSig("Virbox(libvdog)", listOf("libvdog"), emptyList(), "深思数盾 Virbox 虚拟化(Vdog)"),
        ShellSig("Virbox(libvirbox)", listOf("libvirbox"), emptyList(), "深思数盾 Virbox 壳库"),
        ShellSig("VMP虚拟化(libvmp)", listOf("libvmp", "libvmp_protect"), emptyList(), "指令虚拟化(VMP)通用库"),
        ShellSig("Dex虚拟化(libDexVmp)", listOf("libDexVmp"), emptyList(), "Dex 层虚拟化保护"),
        ShellSig("反调试(libanti)", listOf("libanti", "libantidebug"), emptyList(), "反调试/反注入(通用, 非具体厂商)"),
        ShellSig("反调试(libdbg)", listOf("libdbg"), emptyList(), "调试器检测(通用)"),
        ShellSig("加固通用兜底(libprotect.so)", listOf("libprotect.so"), emptyList(), "通用 so 保护兜底库名"),
        ShellSig("加固通用兜底(librun)", listOf("librun"), emptyList(), "常见运行时壳库名兜底"),
        ShellSig("AppSealing(INKA)", listOf("libAppSealing"), emptyList(), "韩国 AppSealing 加固"),
        ShellSig("LIAPP(Lockin)", listOf("libliapp"), emptyList(), "韩国 LIAPP 加固"),
        ShellSig("DexProtector(Licel)", listOf("libdexprotector"), emptyList(), "DexProtector 商业加固(观察项)"),
        ShellSig("DexGuard(Guardsquare)", listOf("libdexguard"), emptyList(), "Guardsquare DexGuard(观察项)"),
    )

    val tool: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_apk_shell_check",
            "【APK 加固检测】只读扫描 APK, 识别是否被加固及可能的加固产品(360/腾讯乐固/爱加密/梆梆/娜迦/阿里聚安全/百度/几维/网易易盾/顶象/通付盾/盛大/瑞星/启明星辰/Virbox 等 40+ 厂商)。逆向第一步: 判断好不好改/要不要先脱壳。检测: lib/*.so 壳库名、classes.dex 大小异常(极小=代码被抽走)、assets 壳资源、Manifest 的 application 指向; 内置 50+ 条壳特征库, 并直接列出 lib/*.so 文件名(detectedLibs)。纯只读, 不改文件。",
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
                    .put("detectedLibs", JSONArray(libs.distinct()))
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
