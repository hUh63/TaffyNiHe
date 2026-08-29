package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL

/**
 * 【Frida Gadget 内置打包】把 frida-gadget + hook 脚本完整内置进 APK
 * （免 root、免 adb、装上即 hook——LSPatch 同类效果，方案参考 MT 论坛置顶教程）。
 *
 * 工作流: taffy_apk_decode → taffy_apk_frida_gadget(action=place) → taffy_apk_frida_gadget(action=patch_entry)
 *        → taffy_apk_rebuild(build) → taffy_apk_sign → 安装即自动加载 hook。
 *
 * 原理:
 *  1. lib/<abi>/libfrida-gadget.so   gadget 本体（伪装 so）
 *  2. lib/<abi>/libfrida-gadget.config.so  配置（伪装 so，script interaction 指向同目录 libjs.so）
 *  3. lib/<abi>/libjs.so             hook 脚本（伪装 so，安装后随 so 提取落盘，gadget 按相对路径加载）
 *  4. smali 注入 System.loadLibrary("frida-gadget") 到入口类（Application/launcher Activity）
 *  5. manifest 设 extractNativeLibs="true"（保证 so 落盘，相对路径生效）
 *
 * 注意: 工具名必须全局唯一（ToolCatalogRegistry 校验），因此 place/patch_entry 合并为单 handler 的 action。
 */
object ApkFridaTool {

    private const val GADGET_LIB = "libfrida-gadget.so"
    private const val CONFIG_LIB = "libfrida-gadget.config.so"
    private const val SCRIPT_LIB = "libjs.so"

    private const val DEFAULT_SCRIPT = """// 塔菲逆核默认 Frida Gadget 演示脚本：打印每个 Activity 的 onCreate
Java.perform(function () {
    var Activity = Java.use("android.app.Activity");
    Activity.onCreate.overload("android.os.Bundle").implementation = function (bundle) {
        var name = this.getClass().getName();
        console.log("[taffy] " + name + " onCreate");
        this.onCreate(bundle);
    };
});
"""

    val fridaGadget: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_apk_frida_gadget",
            "【Frida 免 root 内置打包】把 frida-gadget+hook 脚本内置进 APK（装上即自动加载，免 root/免 adb）。action=place 放置 gadget/配置/脚本；action=patch_entry 在入口类 smali 注入 loadLibrary。之后 taffy_apk_rebuild(build) + taffy_apk_sign。",
            "Embed frida-gadget + hook script into an APK (auto-loads on install, no root/adb). action=place; action=patch_entry. Then rebuild + sign.",
            "build", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "action" str "place=放置 gadget/配置/脚本; patch_entry=入口类注入 loadLibrary"
                "dir" str "已 decode 的 APK 目录路径"
                "gadget" str "frida-gadget.so 文件路径（place 时必填；或 https 直链自动下载）"
                "abi" str "目标 ABI(默认 arm64-v8a, place 时用)"
                "script" str "hook 脚本(.js)路径,可选;不传用内置演示脚本(place 时用)"
                "script_text" str "hook 脚本内容(内联),可选;优先级高于 script 文件(place 时用)"
                "class" str "入口类全名(com.x.MainApp),可选;patch_entry 时不传自动从 Manifest 解析"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "place")
            val dirPath = args.str("dir")
            if (dirPath.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 dir", "dir", "")
            val dir = File(dirPath)
            if (!dir.isDirectory) return err("FILE_NOT_FOUND", "decode 目录不存在: $dirPath", "dir", dirPath)
            return when (action) {
                "place" -> placeLogic(dir, args)
                "patch_entry" -> patchEntryLogic(dir, args)
                else -> err("INVALID_ARGUMENT", "未知 action: $action（可选 place / patch_entry）", "action", action)
            }
        }
    }

    // ──────────────── action=place ────────────────

    private fun placeLogic(dir: File, args: JSONObject): JSONObject {
        val abi = args.str("abi", "arm64-v8a")
        val gadgetSrc = args.str("gadget")
        if (gadgetSrc.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 gadget（frida-gadget.so 路径或下载直链）", "gadget", "")
        val libDir = File(dir, "lib/$abi").apply { mkdirs() }
        val gadgetFile = File(libDir, GADGET_LIB)
        val gadgetSize: Long
        if (gadgetSrc.startsWith("http://") || gadgetSrc.startsWith("https://")) {
            runCatching {
                val conn = URL(gadgetSrc).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 120000
                conn.setRequestProperty("User-Agent", "TaffyNiHe")
                conn.inputStream.use { input -> gadgetFile.outputStream().use { input.copyTo(it) } }
            }.getOrElse { return err("DOWNLOAD_FAILED", "gadget 下载失败: ${it.message}", "url", gadgetSrc) }
            gadgetSize = gadgetFile.length()
        } else {
            val src = File(gadgetSrc)
            if (!src.isFile) return err("FILE_NOT_FOUND", "gadget 文件不存在: $gadgetSrc", "gadget", gadgetSrc)
            src.copyTo(gadgetFile, overwrite = true)
            gadgetSize = gadgetFile.length()
        }

        val scriptText = args.optString("script_text", "").ifBlank {
            val p = args.str("script")
            if (p.isNotBlank()) {
                val f = File(p)
                if (!f.isFile) return err("FILE_NOT_FOUND", "脚本文件不存在: $p", "script", p)
                f.readText()
            } else DEFAULT_SCRIPT
        }

        val config = JSONObject()
            .put("interaction", JSONObject()
                .put("type", "script")
                .put("path", "./$SCRIPT_LIB")
                .put("on_change", "reload"))
        File(libDir, CONFIG_LIB).writeText(config.toString(2))
        File(libDir, SCRIPT_LIB).writeText(scriptText)

        val manifest = File(dir, "AndroidManifest.xml")
        var manifestChanged = false
        if (manifest.isFile) {
            var xml = manifest.readText()
            if (!xml.contains("extractNativeLibs")) {
                xml = xml.replaceFirst("<application", "<application\n    android:extractNativeLibs=\"true\"")
                manifest.writeText(xml)
                manifestChanged = true
            } else if (xml.contains("android:extractNativeLibs=\"false\"")) {
                xml = xml.replace("android:extractNativeLibs=\"false\"", "android:extractNativeLibs=\"true\"")
                manifest.writeText(xml)
                manifestChanged = true
            }
        }

        return ok(JSONObject()
            .put("action", "place")
            .put("abi", abi)
            .put("files", JSONArray().apply {
                put(gadgetFile.absolutePath); put(File(libDir, CONFIG_LIB).absolutePath); put(File(libDir, SCRIPT_LIB).absolutePath)
            })
            .put("gadgetSize", gadgetSize)
            .put("scriptBytes", scriptText.toByteArray().size)
            .put("manifestExtractNativeLibs", manifestChanged)
            .put("next", "taffy_apk_frida_gadget(action=patch_entry, dir=...) 注入入口加载调用 → taffy_apk_rebuild(build) → taffy_apk_sign"))
    }

    // ──────────────── action=patch_entry ────────────────

    private fun patchEntryLogic(dir: File, args: JSONObject): JSONObject {
        val manifest = File(dir, "AndroidManifest.xml")
        if (!manifest.isFile) return err("FILE_NOT_FOUND", "AndroidManifest.xml 不存在", "dir", dir.absolutePath)
        val xml = manifest.readText()

        var entryClass = args.str("class").replace('.', '/')
        if (entryClass.isBlank()) {
            val appTag = Regex("<application[^>]*>").find(xml)?.value ?: ""
            entryClass = Regex("android:name=\"([^\"]+)\"").findAll(appTag)
                .map { it.groupValues[1] }.firstOrNull { it.contains(".") }?.replace('.', '/') ?: ""
        }
        if (entryClass.isBlank()) {
            val actBlock = Regex("<activity[^>]*>(?:(?!</activity>|<activity)[\\s\\S])*android.intent.action.MAIN[\\s\\S]*?</activity>|<activity[^>]*/>").findAll(xml)
                .firstOrNull { it.value.contains("android.intent.action.MAIN") }?.value ?: ""
            entryClass = Regex("android:name=\"([^\"]+)\"").findAll(actBlock)
                .map { it.groupValues[1] }.firstOrNull { it.contains(".") }?.replace('.', '/') ?: ""
        }
        if (entryClass.isBlank()) return err("NOT_FOUND", "无法从 Manifest 解析入口类（Application/launcher Activity 均未找到），请用 class 参数指定", "class", "")

        val smaliRoots = dir.listFiles { f -> f.isDirectory && f.name.startsWith("smali") }?.sortedBy { it.name } ?: emptyList()
        val smaliFile = smaliRoots.firstNotNullOfOrNull { root ->
            val cand = File(root, "$entryClass.smali")
            if (cand.isFile) cand else null
        } ?: return err("NOT_FOUND", "入口类 smali 未找到: $entryClass（多 dex 下也扫过 smali*）", "class", entryClass)

        if (smaliFile.readText().contains("frida-gadget")) {
            return ok(JSONObject().put("action", "patch_entry").put("already", true)
                .put("file", smaliFile.absolutePath)
                .put("hint", "该类已注入过 frida-gadget，无需重复"))
        }

        val lines = smaliFile.readText().lines().toMutableList()
        val methodPatterns = listOf(
            Regex("^\\.method\\s+.*onCreate\\(Landroid/os/Bundle;\\)V"),
            Regex("^\\.method\\s+.*onCreate\\(\\)V"),
            Regex("^\\.method\\s+.*attachBaseContext\\(Landroid/content/Context;\\)V"),
            Regex("^\\.method\\s+.*<init>\\(\\)V"),
            Regex("^\\.method\\s+.*<clinit>\\(\\)V"),
        )
        var insertAt = -1
        var localsLine = -1
        var prologueLine = -1
        outer@ for (mp in methodPatterns) {
            for (i in lines.indices) {
                if (mp.containsMatchIn(lines[i])) {
                    var j = i + 1
                    while (j < lines.size && !lines[j].startsWith(".method")) {
                        val l = lines[j].trim()
                        if (l.startsWith(".locals")) { localsLine = j; break }
                        if (l.startsWith(".registers")) { localsLine = j; break }
                        if (l.startsWith(".prologue")) { prologueLine = j; break }
                        j++
                    }
                    insertAt = i
                    break@outer
                }
            }
        }
        if (insertAt < 0) return err("NOT_FOUND", "入口类中未找到可注入的方法（onCreate/attachBaseContext/<init>/<clinit>）", "class", entryClass)

        if (localsLine >= 0) {
            val m = Regex("(\\.locals\\s+)(\\d+)").find(lines[localsLine])
                ?: Regex("(\\.registers\\s+)(\\d+)").find(lines[localsLine])
            if (m != null) {
                lines[localsLine] = lines[localsLine].replaceRange(m.range, "${m.groupValues[1]}${m.groupValues[2].toInt() + 1}")
            }
        }
        val inject = listOf(
            "    const-string v0, \"frida-gadget\"",
            "",
            "    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V",
            "",
        )
        val at = if (prologueLine >= 0) prologueLine + 1 else (if (localsLine >= 0) localsLine + 1 else insertAt + 1)
        lines.addAll(at, inject)
        smaliFile.writeText(lines.joinToString("\n"))

        return ok(JSONObject()
            .put("action", "patch_entry")
            .put("entryClass", entryClass.replace('/', '.'))
            .put("smaliFile", smaliFile.absolutePath)
            .put("method", lines[insertAt].trim())
            .put("injected", "System.loadLibrary(\"frida-gadget\")")
            .put("hint", "入口已注入 → taffy_apk_rebuild(build) 回编 → taffy_apk_sign 签名 → 安装即自动加载 hook"))
    }

    val ALL = listOf(fridaGadget)
}
