package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.MethodReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.StringReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.io.File

/**
 * 塔菲逆核: DexKit 反混淆查找(C++ 高性能 dex 解析, 带 arm64 native so)。
 *
 * 混淆后的 App 类名/方法名全是 a/b/c, jadx 硬看难定位。DexKit 靠"特征"反查:
 *  - 哪个方法/类 用了某个字符串(如 "sign"/"vip"/"pay") ← 逆向最常用
 *  - 按方法名/类名(支持 Contains/Equals)查
 * 直接对 APK 文件 DexKitBridge.create(apkPath) 分析, 用完 close 释放。
 */
object DexKitTool {

    val search: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_dex_search",
            "【DexKit 反混淆查找】混淆 App 里靠特征反查被混淆的真实类/方法(C++ 高性能)。action=method_by_string 查\"用了某字符串的方法\"(逆向定位关键逻辑最常用,如搜 sign/pay/vip 找到签名/支付/会员相关方法); class_by_string 查用了某字符串的类; method_by_name 按方法名查; class_by_name 按类名查。输入 APK 路径,返回匹配的类名/方法签名。",
            "DexKit anti-obfuscation search (high-performance C++). Find obfuscated classes/methods by feature. action=method_by_string (find methods using a given string — most useful for locating key logic like sign/pay/vip); class_by_string; method_by_name; class_by_name. Input an APK path, returns matched class names / method descriptors.",
            "decompile", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf(
                    "method_by_string(查用了某字符串的方法) | class_by_string | method_by_name | class_by_name | method_strings(列某方法引用的字符串/常量)",
                    "method_by_string", "class_by_string", "method_by_name", "class_by_name", "method_strings",
                )
                "path" str "APK 文件绝对路径"
                "filePath" str "path 的别名"
                "keyword" str "要查的字符串/名字(按 action 决定含义)"
                "className" str "method_strings: 目标类全名(如 com.example.Foo)"
                "method" str "method_strings: 目标方法名(可用 \";\" 追加参数签名定位重载)"
                "matchType".oneOf("字符串匹配方式", "Contains", "Equals", "StartsWith", "EndsWith")
                "ignoreCase" bool "是否忽略大小写(默认 false)"
                "packagePrefix" str "限定搜索包名前缀(可选,加快速度,如 com.xxx)"
                "limit" int "最多返回条数(默认 100)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val inputPath = args.str("path").ifBlank { args.str("filePath") }
            if (inputPath.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path(APK 路径)", "path", "")
            val input = File(inputPath)
            if (!input.isFile) return err("FILE_NOT_FOUND", "文件不存在: $inputPath", "path", inputPath)

            val keyword = args.str("keyword")
            if (keyword.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 keyword(要查的字符串/名字)", "keyword", "")

            val action = args.str("action", "method_by_string").ifBlank { "method_by_string" }
            // method_strings 用 dexlib2 遍历方法指令, 不走 DexKitBridge(C++ 全量加载) 以提速
            if (action == "method_strings") {
                return methodStrings(input, args)
            }
            val matchType = when (args.str("matchType", "Contains")) {
                "Equals" -> StringMatchType.Equals
                "StartsWith" -> StringMatchType.StartsWith
                "EndsWith" -> StringMatchType.EndsWith
                else -> StringMatchType.Contains
            }
            val ignoreCase = args.optBoolean("ignoreCase", false)
            val pkgPrefix = args.str("packagePrefix")
            val limit = args.intValue("limit", 100).coerceIn(1, 2000)

            return runCatching {
                // DexKit 加载 APK 是耗时操作，用完即 close 释放 native 资源
                DexKitBridge.create(input.absolutePath).use { bridge ->
                    when (action) {
                        "method_by_string" -> {
                            val find = FindMethod.create().apply {
                                if (pkgPrefix.isNotBlank()) searchPackages(pkgPrefix)
                                matcher(
                                    MethodMatcher.create()
                                        .usingStrings(listOf(keyword), matchType, ignoreCase),
                                )
                            }
                            val results = bridge.findMethod(find)
                            val arr = JSONArray()
                            results.take(limit).forEach { m ->
                                arr.put(JSONObject()
                                    .put("class", m.className)
                                    .put("method", m.methodName)
                                    .put("descriptor", m.descriptor)
                                    .put("returnType", m.returnTypeName)
                                    .put("params", JSONArray(m.paramTypeNames)))
                            }
                            resultJson(action, keyword, results.size, arr)
                        }

                        "class_by_string" -> {
                            val find = FindClass.create().apply {
                                if (pkgPrefix.isNotBlank()) searchPackages(pkgPrefix)
                                matcher(
                                    ClassMatcher.create()
                                        .usingStrings(listOf(keyword), matchType, ignoreCase),
                                )
                            }
                            val results = bridge.findClass(find)
                            val arr = JSONArray()
                            results.take(limit).forEach { c ->
                                arr.put(JSONObject()
                                    .put("class", c.name)
                                    .put("simpleName", c.simpleName)
                                    .put("sourceFile", c.sourceFile ?: ""))
                            }
                            resultJson(action, keyword, results.size, arr)
                        }

                        "method_by_name" -> {
                            val find = FindMethod.create().apply {
                                if (pkgPrefix.isNotBlank()) searchPackages(pkgPrefix)
                                matcher(
                                    MethodMatcher.create()
                                        .name(keyword, matchType, ignoreCase),
                                )
                            }
                            val results = bridge.findMethod(find)
                            val arr = JSONArray()
                            results.take(limit).forEach { m ->
                                arr.put(JSONObject()
                                    .put("class", m.className)
                                    .put("method", m.methodName)
                                    .put("descriptor", m.descriptor))
                            }
                            resultJson(action, keyword, results.size, arr)
                        }

                        "class_by_name" -> {
                            val find = FindClass.create().apply {
                                if (pkgPrefix.isNotBlank()) searchPackages(pkgPrefix)
                                matcher(
                                    ClassMatcher.create()
                                        .className(keyword, matchType, ignoreCase),
                                )
                            }
                            val results = bridge.findClass(find)
                            val arr = JSONArray()
                            results.take(limit).forEach { c ->
                                arr.put(JSONObject()
                                    .put("class", c.name)
                                    .put("simpleName", c.simpleName)
                                    .put("sourceFile", c.sourceFile ?: ""))
                            }
                            resultJson(action, keyword, results.size, arr)
                        }

                        else -> err("UNKNOWN_ACTION", "未知 action: $action", "action", action)
                    }
                }
            }.getOrElse { e ->
                err("DEXKIT_FAILED", "DexKit 查找失败: ${e.message ?: e.javaClass.simpleName}", "path", inputPath)
            }
        }

        private fun resultJson(action: String, keyword: String, total: Int, arr: JSONArray): JSONObject =
            ok(JSONObject()
                .put("tool", "taffy_dex_search")
                .put("action", action)
                .put("keyword", keyword)
                .put("total", total)
                .put("returned", arr.length())
                .put("results", arr)
                .put("hint", "拿到真实类名/方法后,可用 taffy_jadx_decompile 反编译该类看代码,或 frida hook 该方法"))

        /** 列出指定方法体内引用的所有字符串/常量/调用的方法(dexlib2 指令遍历)。配合 patch_instruction 使用。 */
        private fun methodStrings(apk: File, args: JSONObject): JSONObject {
            val className = args.str("className")
            val method = args.str("method")
            if (method.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 method", "method", "")
            val methodBase = method.substringBefore(';').trim()
            val classDescriptor = "L${className.replace('.', '/')};"
            val limit = args.intValue("limit", 200).coerceIn(1, 2000)

            val strings = LinkedHashSet<String>()
            val calls = LinkedHashSet<String>()
            val constants = LinkedHashSet<String>()

            var foundMethod = false
            runCatching {
                java.util.zip.ZipFile(apk).use { zf ->
                    val dexEntries = zf.entries().toList().filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                    for (dexEntry in dexEntries) {
                        val tempDex = File.createTempFile("classes_", ".dex")
                        zf.getInputStream(dexEntry).use { it.copyTo(tempDex.outputStream()) }
                        try {
                            val dexFile = DexFileFactory.loadDexFile(tempDex, Opcodes.getDefault())
                            val classDef = dexFile.classes.firstOrNull { it.type == classDescriptor } ?: continue
                            for (m in classDef.methods) {
                                if (m.name != methodBase) continue
                                // 若 method 含签名, 校验参数类型
                                if (method.indexOf(';') >= 0) {
                                    val sigMatch = method.substringAfter(';').trim()
                                    if (sigMatch.isNotBlank()) {
                                        val paramTypes = m.parameters.map { it.type }.joinToString("")
                                        if (!paramTypes.contains(sigMatch.trim())) continue
                                    }
                                }
                                foundMethod = true
                                val impl = m.implementation ?: continue
                                for (insn in impl.instructions) {
                                    when (insn) {
                                        is StringReferenceInstruction -> strings.add(insn.string)
                                        is MethodReferenceInstruction -> calls.add(insn.reference.definingClass + "->" + insn.reference.name + insn.reference.parameterTypes.joinToString("") { it })
                                        is NarrowLiteralInstruction -> {
                                            val v = insn.narrowLiteral
                                            if (v != 0) constants.add("0x" + java.lang.Integer.toHexString(v) + " ($v)")
                                        }
                                        is WideLiteralInstruction -> {
                                            val v = insn.wideLiteral
                                            if (v != 0L) constants.add("0x" + java.lang.Long.toHexString(v) + " ($v)")
                                        }
                                        else -> {}
                                    }
                                }
                            }
                        } finally {
                            tempDex.delete()
                        }
                        if (foundMethod) break
                    }
                }
            }

            if (!foundMethod) return err("METHOD_NOT_FOUND", "类 $className 中未找到方法 $method", "method", method)

            return ok(JSONObject()
                .put("tool", "taffy_dex_search")
                .put("action", "method_strings")
                .put("className", className)
                .put("method", method)
                .put("stringCount", strings.size)
                .put("strings", JSONArray(strings.take(limit * 2)))
                .put("constantCount", constants.size)
                .put("constants", JSONArray(constants.take(limit * 2)))
                .put("callCount", calls.size)
                .put("calls", JSONArray(calls.take(limit * 2)))
                .put("hint", "method_strings 列出方法引用的字符串/常量/调用, 配合 taffy_smali_edit action=patch_instruction 精准改指令"))
        }
    }
}
