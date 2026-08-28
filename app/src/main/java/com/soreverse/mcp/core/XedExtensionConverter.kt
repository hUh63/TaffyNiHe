package com.soreverse.mcp.core

import android.content.Context
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Xed-Editor 扩展 → 塔菲逆核插件 转换器。
 *
 * Xed 扩展格式（官方文档）: APK，根目录 manifest.json（id/mainClass/minAppVersion/targetAppVersion），
 * 入口类继承 com.rk.extension.ExtensionAPI，生命周期 onLoad/onDispose/onInstalled/onUninstalled/
 * beforeUpdate/afterUpdate/onActivity*；assets/ 为扩展自带资源。
 *
 * 转换策略（诚实边界）:
 *  - 完全自动迁移: 元数据（manifest）、assets 资源、入口类结构（生命周期钩子清单）、
 *    字符串常量表、对宿主 ExtensionAPI 的调用面（host_api_calls）
 *  - 字节码逻辑无法语义翻译成 Python：生成带逐钩子 TODO 的可运行骨架 + 完整转换报告（CONVERT_INFO.md），
 *    用户按报告补齐 run(ext) 逻辑即可
 */
object XedExtensionConverter {

    data class Result(val ok: Boolean, val pluginDir: File?, val message: String)

    /** 输入分流: .py=塔菲原生 / .zip=可能为 Xed store 包或塔菲插件包 / .apk=Xed 扩展。 */
    fun detectKind(file: File): String {
        val n = file.name.lowercase()
        return when {
            n.endsWith(".py") -> "taffy"
            n.endsWith(".apk") -> "xed"
            n.endsWith(".zip") -> {
                // zip 内含 classes.dex 或 manifest.json → Xed；含 plugin.py/meta.json → 塔菲
                var kind = "unknown"
                runCatching {
                    ZipInputStream(file.inputStream().buffered()).use { zis ->
                        var e = zis.nextEntry
                        while (e != null && kind == "unknown") {
                            val en = e.name.lowercase()
                            if (en.endsWith("classes.dex") || en == "manifest.json" || en.endsWith("/manifest.json")) kind = "xed"
                            else if (en == "plugin.py" || en == "meta.json" || en.endsWith("/plugin.py")) kind = "taffy"
                            zis.closeEntry(); e = zis.nextEntry
                        }
                    }
                }
                kind
            }
            else -> "unknown"
        }
    }

    /** 转换 Xed 扩展（apk/zip）为塔菲插件，写入 pluginsRoot/<id>/。 */
    fun convert(context: Context, input: File, pluginsRoot: File): Result {
        val work = File(context.cacheDir, "xed_conv_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            // 1) 解包
            val unpacked = unpack(input, work) ?: return Result(false, null, "无法解包: ${input.name}")
            // 2) manifest.json（apk 根或解包根）
            val manifest = findFile(unpacked, "manifest.json")
                ?: return Result(false, null, "未找到 manifest.json —— 不是标准的 Xed-Editor 扩展")
            val mJson = runCatching { JSONObject(manifest.readText()) }.getOrElse {
                return Result(false, null, "manifest.json 解析失败: ${it.message}")
            }
            val id = mJson.optString("id", "").ifBlank { input.nameWithoutExtension.replace(Regex("[^A-Za-z0-9_.-]"), "_") }
            val mainClass = mJson.optString("mainClass", "").replace('.', '/')
            // 3) dex 解析
            val dex = findFile(unpacked, "classes.dex")
                ?: return Result(false, null, "扩展包内无 classes.dex")
            val df = runCatching { DexFileFactory.loadDexFile(dex, Opcodes.getDefault()) }.getOrElse {
                return Result(false, null, "dex 解析失败: ${it.message}")
            }
            // 入口类: manifest.mainClass 优先，否则扫描继承 ExtensionAPI 的类
            var entry: ClassDef? = null
            val classes = df.classes.toList()
            if (mainClass.isNotBlank()) entry = classes.firstOrNull { it.type == "L$mainClass;" }
            if (entry == null) entry = classes.firstOrNull { it.superclass == "Lcom/rk/extension/ExtensionAPI;" }
            if (entry == null) return Result(false, null, "未找到 ExtensionAPI 入口类（mainClass=$mainClass）")

            val lifecycleHooks = mutableListOf<String>()
            val hostApiCalls = LinkedHashSet<String>()
            val strings = LinkedHashSet<String>()
            val methodSigs = mutableListOf<String>()
            for (m in entry.methods) {
                methodSigs.add("${m.name}(${m.parameterTypes.joinToString(",")}) -> ${m.returnType}")
                if (isLifecycle(m.name)) lifecycleHooks.add(m.name)
                val impl = m.implementation ?: continue
                for (ins in impl.instructions) {
                    val ref = (ins as? ReferenceInstruction)?.reference ?: continue
                    when (ref) {
                        is StringReference -> if (ref.string.length in 3..300) strings.add(ref.string)
                        is MethodReference -> if (ref.definingClass.startsWith("Lcom/rk/extension/")) hostApiCalls.add(ref.name)
                    }
                }
            }
            // 全 dex 有意义字符串补充（限 200 条）
            runCatching {
                df.stringReferences.map { it.string }
                    .filter { s -> s.length in 4..300 && !s.startsWith("L") && !s.startsWith("[") && !s.contains("kotlin") && !s.contains(".class") }
                    .take(200).forEach { strings.add(it) }
            }
            // 4) assets 迁移
            val pluginDir = File(pluginsRoot, id)
            if (pluginDir.exists()) pluginDir.deleteRecursively()
            pluginDir.mkdirs()
            val assetsSrc = findDir(unpacked, "assets")
            val assetList = mutableListOf<String>()
            if (assetsSrc != null) {
                assetsSrc.copyRecursively(File(pluginDir, "xed_assets"), overwrite = true)
                assetsSrc.walkTopDown().filter { it.isFile }.forEach { assetList.add("xed_assets/" + it.relativeTo(assetsSrc).path) }
            }
            // 5) 生成 plugin.py
            val py = buildPluginPy(id, mJson, entry.type, lifecycleHooks, hostApiCalls, strings, assetList, methodSigs)
            File(pluginDir, "plugin.py").writeText(py)
            // 6) meta.json
            val meta = JSONObject().apply {
                put("name", mJson.optString("name", id))
                put("id", id)
                put("version", mJson.optString("version", "1.0"))
                put("author", mJson.optString("author", "unknown"))
                put("description", mJson.optString("description", "").ifBlank { "由 Xed-Editor 扩展转换生成" } + "（Xed 转换）")
                put("source", "xed")
                put("original_id", id)
            }
            File(pluginDir, "meta.json").writeText(meta.toString(2))
            // 7) 转换报告
            File(pluginDir, "CONVERT_INFO.md").writeText(
                buildReport(id, entry.type, lifecycleHooks, methodSigs, hostApiCalls, strings, assetList),
            )
            return Result(true, pluginDir, "转换成功: $id（${lifecycleHooks.size} 个生命周期钩子 / ${strings.size} 条字符串 / ${assetList.size} 个资源）")
        } catch (e: Exception) {
            AppLog.e("XedExtensionConverter failed", e)
            return Result(false, null, "转换异常: ${e.message}")
        } finally {
            work.deleteRecursively()
        }
    }

    private fun isLifecycle(name: String): Boolean =
        name in setOf("onLoad", "onDispose", "onInstalled", "onUninstalled", "beforeUpdate", "afterUpdate") || name.startsWith("onActivity")

    private fun buildPluginPy(
        id: String,
        m: JSONObject,
        entryType: String,
        lifecycle: List<String>,
        apiCalls: Set<String>,
        strings: Set<String>,
        assets: List<String>,
        methodSigs: List<String>,
    ): String {
        val hooks = lifecycle.joinToString("\n") { "    # TODO($it): 对应 Xed 入口类 $it() 的逻辑" }
            .ifBlank { "    # (入口类未实现任何生命周期钩子)" }
        val pyStrings = strings.take(60).joinToString("\n") { "    ${it.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}," }
        val pyAssets = assets.joinToString("\n") { "    \"$it\"," }
        return """
# ============================================================
# 塔菲逆核插件 —— 由 Xed-Editor 扩展自动转换生成
# 源扩展: ${m.optString("id", id)} (${m.optString("name", "")})
# 入口类: $entryType
# 说明: Xed 扩展是 JVM 字节码，塔菲用 Python 插件 —— 逻辑无法自动
#       语义翻译，本文件为可运行骨架。打开同目录 CONVERT_INFO.md
#       查看完整方法清单/字符串表/资源清单，在 TODO 处补齐逻辑。
# ============================================================
meta = {
    "name": "${(m.optString("name", id)).replace("\"", "'")}",
    "version": "${m.optString("version", "1.0")}",
    "author": "${(m.optString("author", "unknown")).replace("\"", "'")}",
    "description": "由 Xed-Editor 扩展转换（${m.optString("id", id)}）",
    "source": "xed",
}

XED_INFO = {
    "entry_class": "$entryType",
    "lifecycle_hooks": ${lifecycle.joinToString(", ", "[", "]") { "\"$it\"" }},
    "host_api_calls": ${apiCalls.take(40).joinToString(", ", "[", "]") { "\"$it\"" }},
    "assets": [
$pyAssets
    ],
    "strings_sample": [
$pyStrings
    ],
}


def run(ext):
    """Xed 转换插件入口。ext 为塔菲扩展 API（taffy_ext）。"""
    ext.log("Xed 转换插件已启用:", meta["name"], "v" + meta["version"])
    ext.log("入口类:", XED_INFO["entry_class"])
    ext.log("宿主 API 调用面:", ", ".join(XED_INFO["host_api_calls"]) or "(无)")
    ext.log("迁移资源:", ", ".join(XED_INFO["assets"]) or "(无)")
$hooks
    # 在此补齐转换后的业务逻辑，可用 ext.mcp(...) 调用塔菲全部 MCP 工具
    return "Xed 转换插件运行完成（骨架模式）。详见 CONVERT_INFO.md。"
""".trim() + "\n"
    }

    private fun buildReport(
        id: String,
        entryType: String,
        lifecycle: List<String>,
        methodSigs: List<String>,
        apiCalls: Set<String>,
        strings: Set<String>,
        assets: List<String>,
    ): String = """
# Xed 扩展转换报告: $id

## 入口类
`$entryType`

## 生命周期钩子（已生成 TODO）
${lifecycle.joinToString("\n") { "- $it" }.ifBlank { "- （无）" }}

## 入口类全部方法
```
${methodSigs.joinToString("\n")}
```

## 对 Xed 宿主 ExtensionAPI 的调用面（转换时需映射到 taffy_ext 对应能力）
${apiCalls.joinToString("\n") { "- $it" }.ifBlank { "- （未检测到）" }}

## 迁移的资源（xed_assets/）
${assets.joinToString("\n") { "- $it" }.ifBlank { "- （无）" }}

## 字符串常量表（前 200 条，用于还原配置/命令/路径）
```
${strings.take(200).joinToString("\n")}
```

## 补齐指引
1. 打开 plugin.py，在各 `TODO(钩子)` 处按上表方法清单还原逻辑
2. Xed 宿主 API → taffy_ext 映射参考:
   - 文件读写 → ext.read / ext.write / ext.files
   - 终端/命令 → ext.mcp("taffy_terminal_exec", ...)
   - 逆向工具(rizin/eDBG/抓包) → ext.mcp("taffy_rz" / "taffy_edbg_*" / "taffy_capture_*", ...)
   - 全部工具: ext.tools() 列出
3. 保存后在扩展页点「运行」验证
""".trim()

    // ─────────────────────────── zip 工具 ───────────────────────────

    private fun unpack(file: File, dest: File): File? = runCatching {
        ZipInputStream(file.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val out = File(dest, e.name)
                if (e.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o -> zis.copyTo(o) }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        dest
    }.getOrNull()

    private fun findFile(root: File, name: String): File? =
        root.walkTopDown().firstOrNull { it.isFile && it.name == name }

    private fun findDir(root: File, name: String): File? =
        root.walkTopDown().firstOrNull { it.isDirectory && it.name == name }
}
