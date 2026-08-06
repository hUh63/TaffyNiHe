package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
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
        override val meta = ToolMeta(
            "dex_search",
            "【DexKit 反混淆查找】混淆 App 里靠特征反查被混淆的真实类/方法(C++ 高性能)。action=method_by_string 查\"用了某字符串的方法\"(逆向定位关键逻辑最常用,如搜 sign/pay/vip 找到签名/支付/会员相关方法); class_by_string 查用了某字符串的类; method_by_name 按方法名查; class_by_name 按类名查。输入 APK 路径,返回匹配的类名/方法签名。",
            "DexKit anti-obfuscation search (high-performance C++). Find obfuscated classes/methods by feature. action=method_by_string (find methods using a given string — most useful for locating key logic like sign/pay/vip); class_by_string; method_by_name; class_by_name. Input an APK path, returns matched class names / method descriptors.",
            "decompile", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf(
                    "method_by_string(查用了某字符串的方法) | class_by_string | method_by_name | class_by_name",
                    "method_by_string", "class_by_string", "method_by_name", "class_by_name",
                )
                "path" str "APK 文件绝对路径"
                "filePath" str "path 的别名"
                "keyword" str "要查的字符串/名字(按 action 决定含义)"
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
                .put("tool", "dex_search")
                .put("action", action)
                .put("keyword", keyword)
                .put("total", total)
                .put("returned", arr.length())
                .put("results", arr)
                .put("hint", "拿到真实类名/方法后,可用 jadx_decompile 反编译该类看代码,或 frida hook 该方法"))
    }
}
