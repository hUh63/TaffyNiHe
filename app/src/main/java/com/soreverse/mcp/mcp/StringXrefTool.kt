package com.soreverse.mcp.mcp

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * 塔菲逆核: 字符串 -> 引用者 交叉引用（DEX 侧）。
 *
 * 逆向最常见的一步: 在 strings 里看到一个 URL / 密钥 / 错误提示 / 算法常量,
 * 想知道「哪个方法用了它」。本工具遍历 DEX 的所有方法指令, 找出引用该字符串
 * 常量(const-string)的方法, 直接给出方法签名与命中片段。
 *
 * 与 taffy_string_scan(扫敏感字符串) / taffy_dex_xref(按地址查引用) 互补:
 * scan 找「有哪些字符串」, xref 反查「这个字符串被谁用」。
 * 纯 Java(dexlib2), 无需 native 引擎。
 */
object StringXrefTool {

    val tool: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_string_xref",
            "【字符串→引用者】输入关键字, 遍历 DEX 的 const-string, 返回「哪些方法引用了它」" +
                "(方法签名 + 命中字符串), 快速定位算法/URL/密钥的使用点。支持 APK 或单个 dex。" +
                "纯 dexlib2, 无需引擎, 只读。与 taffy_string_scan(找字符串)、taffy_dex_xref(按地址查引用)互补。",
            "String -> referencing methods (DEX). Given a keyword, scans const-string references across all " +
                "methods and returns which methods reference it (with signatures). Locates algorithm/URL/key usage. " +
                "Pure dexlib2, read-only. Complements taffy_string_scan / taffy_dex_xref.",
            "search", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "path" str "APK 或 .dex 文件路径"
                "keyword" str "要反查的字符串(子串匹配, 大小写不敏感)"
                "limit" str "最多返回多少个引用方法(默认 50)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            val keyword = args.str("keyword")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少 path", "path", "")
            if (keyword.isBlank()) return err("INVALID_ARGUMENT", "缺少 keyword", "keyword", "")
            val limit = (args.str("limit").toIntOrNull() ?: 50).coerceIn(1, 500)
            val f = File(path)
            if (!f.isFile) return err("FILE_NOT_FOUND", "文件不存在: $path", "path", path)

            return runCatching {
                val dexFiles: List<Pair<String, DexFile>> = if (path.lowercase().endsWith(".dex")) {
                    listOf(f.name to DexFileFactory.loadDexFile(f, Opcodes.getDefault()))
                } else {
                    loadAllDexFromApk(f)
                }
                val hits = JSONArray()
                var scannedMethods = 0
                var truncated = false
                outer@ for ((dexName, dex) in dexFiles) {
                    for (cls in dex.classes) {
                        for (m in cls.methods) {
                            val impl = m.implementation ?: continue
                            scannedMethods++
                            var hit: String? = null
                            for (insn in impl.instructions) {
                                if (insn is ReferenceInstruction) {
                                    val ref = insn.reference
                                    if (ref is StringReference && ref.string.contains(keyword, ignoreCase = true)) {
                                        hit = ref.string
                                        break
                                    }
                                }
                            }
                            if (hit != null) {
                                if (hits.length() >= limit) { truncated = true; break@outer }
                                hits.put(JSONObject()
                                    .put("dex", dexName)
                                    .put("method", descriptor(m))
                                    .put("string", hit))
                            }
                        }
                    }
                }
                ok(JSONObject()
                    .put("tool", "taffy_string_xref")
                    .put("path", path)
                    .put("keyword", keyword)
                    .put("dexCount", dexFiles.size)
                    .put("scannedMethods", scannedMethods)
                    .put("hitCount", hits.length())
                    .put("truncated", truncated)
                    .put("references", hits)
                    .put("hint", if (hits.length() == 0)
                        "没有方法引用该字符串(可能来自资源/原生 so, 或为运行时拼接)。可换 taffy_string_scan 或对 so 用 taffy_so_xref。"
                    else "这些方法就是该字符串的使用点；配合 taffy_dex_method_code 看它们的字节码/逻辑。"))
            }.getOrElse { e ->
                err("STRING_XREF_FAILED", "字符串反查失败: ${e.message ?: e.javaClass.simpleName}", "path", path)
            }
        }
    }

    private fun loadAllDexFromApk(apk: File): List<Pair<String, DexFile>> {
        val out = mutableListOf<Pair<String, DexFile>>()
        ZipFile(apk).use { zf ->
            zf.entries().toList()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .sortedBy { it.name }
                .forEach { e ->
                    runCatching {
                        val tmp = File.createTempFile("taffy-xref-", ".dex")
                        zf.getInputStream(e).use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
                        val dex = DexFileFactory.loadDexFile(tmp, Opcodes.getDefault())
                        tmp.delete()
                        out.add(e.name to dex)
                    }
                }
        }
        return out
    }

    private fun descriptor(m: Method): String =
        "${m.definingClass}->${m.name}(${m.parameterTypes.joinToString("") { it.toString() }})${m.returnType}"

    val ALL = listOf(tool)
}
