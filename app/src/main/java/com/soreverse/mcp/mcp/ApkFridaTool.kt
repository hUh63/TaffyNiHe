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
 * 工作流: taffy_apk_decode → taffy_apk_frida_gadget(place) → taffy_apk_frida_gadget(patch_entry)
 *        → taffy_apk_rebuild(build) → taffy_apk_sign → 安装即自动加载 hook。
 *
 * 原理:
 *  1. lib/<abi>/libfrida-gadget.so   gadget 本体（伪装 so）
 *  2. lib/<abi>/libfrida-gadget.config.so  配置（伪装 so，script interaction 指向同目录 libjs.so）
 *  3. lib/<abi>/libjs.so             hook 脚本（伪装 so，安装后随 so 提取落盘，gadget 按相对路径加载）
 *  4. smali 注入 System.loadLibrary("frida-gadget") 到入口类（Application/launcher Activity）
 *  5. manifest 设 extractNativeLibs="true"（保证 so 落盘，相对路径生效）
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

    /** 放置 gadget + config + 脚本（并设 extractNativeLibs） */
    val place: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_apk_frida_gadget",
            "【Frida 内置打包·放置】向已 decode 的 APK 目录放置 frida-gadget 本体+配置+hook 脚本（伪装 so，装上即自动加载，免 root/免 adb）。action=place；之后用 patch_entry 注入入口加载调用，再 rebuild+sign。",
            "Place frida-gadget + config + hook script into a decoded APK dir (masqueraded as .so, auto-loaded on install, no root/adb). Then patch_entry, rebuild and sign.",
            "build", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "dir" str "已 decode 的 APK 目录路径"
                "gadget" str "frida-gadget.so 文件路径（工作区内；或 https 直链自动下载）"
                "abi" str "目标 ABI(默认 arm64-v8a)"
                "script" str "hook 脚本(.js)路径,可选;不传用内置演示脚本"
                "script_text" str "hook 脚本内容(内联),可选;优先级高于 script 文件"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val dirPath = args.str("dir")
            if (dirPath.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 dir", "dir", "")
            val dir = File(dirPath)
            if (!dir.isDirectory) return err("FILE_NOT_FOUND", "decode 目录不存在: $dirPath", "dir", dirPath)
            val abi = args.str("abi", "arm64-v8a")

            // gadget 来源：本地路径或 URL
            val gadgetSrc = args.str("gadget")
            if (gadgetSrc.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 gadget（frida-gadget.so 路径或下载直链）", "gadget", "")
            val libDir = File(dir, "lib/$abi").apply { mkdirs() }
            val gadgetFile = File(libDir, GADGET_LIB)
            var gadgetSize: Long
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

            // 脚本：script_text 优先，其次 script 文件，最后默认演示
            val scriptText = args.optString("script_text", "").ifBlank {
                val p = args.str("script")
                if (p.isNotBlank()) {
                    val f = File(p)
                    if (!f.isFile) return err("FILE_NOT_FOUND", "脚本文件不存在: $p", "script", p)
                    f.readText()
                } else DEFAULT_SCRIPT
            }

            // Frida gadget 配置（伪装 .so；script 相对路径 = gadget 同目录）
            val config = JSONObject()
                .put("interaction", JSONObject()
                    .put("type", "script")
                    .put("path", "./$SCRIPT_LIB")
                    .put("on_change", "reload"))
            File(libDir, CONFIG_LIB).writeText(config.toString(2))
            File(libDir, SCRIPT_LIB).writeText(scriptText)

            // manifest: extractNativeLibs="true"（保证 so 落盘到 /data/app/.../lib/，相对路径加载才生效）
            val manifest = File(dir, "AndroidManifest.xml")
            var manifestChanged = false
            if (manifest.isFile) {
                var xml = manifest.readText()
                if (!xml.contains("extractNativeLibs")) {
                    xml = if (Regex("<application[^>]*android:extractNativeLibs").containsMatchIn(xml)) xml
                    else xml.replaceFirst("<application", "<application\n    android:extractNativeLibs=\"true\"")
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
    }

    /** smali 注入 System.loadLibrary("frida-gadget") 到入口类 */
    val patchEntry: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_apk_frida_gadget",
            "【Frida 内置打包·入口注入】在入口类(Application 或 launcher Activity)的 onCreate/attachBaseContext/<clinit> 注入 System.loadLibrary(\"frida-gadget\")，实现安装即加载。place 之后执行，然后 rebuild+sign。",
            "Inject System.loadLibrary(\"frida-gadget\") into the entry class smali (Application or launcher Activity). Run after place, then rebuild+sign.",
            "build", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "dir" str "已 decode 的 APK 目录路径"
                "class" str "入口类全名(com.x.MainApp),可选;不传自动从 Manifest 解析 Application→launcher Activity"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val dirPath = args.str("dir")
            if (dirPath.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 dir", "dir", "")
            val dir = File(dirPath)
            if (!dir.isDirectory) return err("FILE_NOT_FOUND", "decode 目录不存在: $dirPath", "dir", dirPath)
            val manifest = File(dir, "AndroidManifest.xml")
            if (!manifest.isFile) return err("FILE_NOT_FOUND", "AndroidManifest.xml 不存在", "dir", dirPath)
            val xml = manifest.readText()

            // 入口类：参数 > application android:name > launcher activity
            var entryClass = args.str("class").replace('.', '/')
            if (entryClass.isBlank()) {
                val appTag = Regex("<application[^>]*>").find(xml)?.value ?: ""
                entryClass = Regex("android:name=\"([^\"]+)\"").findAll(appTag)
                    .map { it.groupValues[1] }.firstOrNull { it.contains(".") }?.replace('.', '/') ?: ""
            }
            if (entryClass.isBlank()) {
                // launcher activity: 含 MAIN action 的 <activity> 块
                val actBlock = Regex("<activity[^>]*>(?:(?!</activity>|<activity)[\\s\\S])*android.intent.action.MAIN[\\s\\S]*?</activity>|<activity[^>]*/>").findAll(xml)
                    .firstOrNull { it.value.contains("android.intent.action.MAIN") }?.value ?: ""
                entryClass = Regex("android:name=\"([^\"]+)\"").findAll(actBlock)
                    .map { it.groupValues[1] }.firstOrNull { it.contains(".") }?.replace('.', '/') ?: ""
            }
            if (entryClass.isBlank()) return err("NOT_FOUND", "无法从 Manifest 解析入口类（Application/launcher Activity 均未找到），请用 class 参数指定", "class", "")

            // 在 smali*/ 目录定位入口类
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
            // 方法优先级: onCreate(Bundle)V → onCreate()V → attachBaseContext → <init> → <clinit>
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
                        // 找方法体的 .locals / .registers / .prologue
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

            // .locals/.registers +1（保证 v0 可用）
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
    }

    /**
     * 延迟聚合：place/patchEntry 的 ToolHandler 构造移出 <clinit>，
     * 即使初始化异常也不会以 ExceptionInInitializerError 拖垮工具目录加载。
     */
    val ALL: List<ToolHandler> by lazy {
        listOf(
            runCatching { place }.getOrElse { e ->
                com.soreverse.mcp.core.AppLog.e("ApkFridaTool: place init failed", e)
                object : ToolHandler {
                    override val meta = ToolMeta("taffy_apk_frida_gadget", "Frida 内置打包加载失败,请重启应用", "Frida gadget tool failed to load", "build", ToolClass.EXTRA, heavy = false) {
                        objectSchema(props { })
                    }
                    override fun handle(ctx: ToolContext, args: JSONObject): JSONObject =
                        err("INIT_FAILED", "Frida 工具初始化失败: ${e.message}", "tool", "taffy_apk_frida_gadget")
                }
            },
            runCatching { patchEntry }.getOrElse { e ->
                com.soreverse.mcp.core.AppLog.e("ApkFridaTool: patchEntry init failed", e)
                object : ToolHandler {
                    override val meta = ToolMeta("taffy_apk_frida_gadget", "Frida 内置打包加载失败,请重启应用", "Frida gadget tool failed to load", "build", ToolClass.EXTRA, heavy = false) {
                        objectSchema(props { })
                    }
                    override fun handle(ctx: ToolContext, args: JSONObject): JSONObject =
                        err("INIT_FAILED", "Frida 工具初始化失败: ${e.message}", "tool", "taffy_apk_frida_gadget")
                }
            },
        )
    }
}
