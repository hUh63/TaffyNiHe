package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.DexJavaDecompiler
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONObject
import java.io.File

/**
 * DEX → Java 反编译（jadx）的 MCP 网关。
 *
 * 三个 action：classes（列可反编译的类）/ class（反编译一个类）/ search（按关键字找类）。
 * 与 taffy_smali_edit 配套：smali 用来看指令级、Java 用来看逻辑，两边都支持单 .dex 与 APK。
 */
object DexJavaTool {

    val tool: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_dex_to_java",
            "【DEX 转 Java】用 jadx 把 .dex / APK 里的类反编译成 Java 源码。action=classes 列出可反编译的类；action=class 反编译指定类（className 支持 a.b.C 或 a/b/C）；action=search 按关键字过滤类名。首次调用会加载并分析整个 DEX（几秒到几十秒），同一文件后续调用走缓存。",
            "DEX to Java via jadx. action=classes lists decompilable classes; action=class decompiles one class; action=search filters class names by keyword. First call loads and analyses the whole DEX (seconds to tens of seconds); later calls on the same file hit a cache.",
            "analyze", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "path" str "DEX 或 APK 文件路径"
                "action".oneOf("动作", "classes", "class", "search")
                "className" str "class/search: 目标类名（a.b.C 或 a/b/C）"
                "keyword" str "search: 类名关键字"
                "limit" int "classes/search: 最多返回条数（默认 200，上限 2000）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path", "path", "")
            val file = File(path)
            if (!file.isFile) return err("FILE_NOT_FOUND", "文件不存在：$path", "path", path)
            if (!file.name.endsWith(".dex", true) && !file.name.endsWith(".apk", true)) {
                return err("UNSUPPORTED_INPUT", "只支持 .dex 或 .apk：${file.name}", "path", path)
            }
            if (file.length() > 512L * 1024 * 1024) {
                return err("FILE_TOO_LARGE", "文件超过 512MB：${file.length()} 字节", "path", path)
            }
            val action = args.str("action", "classes")
            return runCatching {
                when (action) {
                    "class" -> {
                        val r = DexJavaDecompiler.decompileClass(path, args.str("className"))
                        if (r.has("error")) err("JAVA_DECOMPILE_FAILED", r.optString("error"), "path", path)
                        else ok(r)
                    }
                    "search" -> {
                        val r = DexJavaDecompiler.listClasses(
                            path,
                            query = args.str("keyword"),
                            limit = args.intValue("limit", 200),
                        )
                        if (r.has("error")) err("JAVA_DECOMPILE_FAILED", r.optString("error"), "path", path)
                        else ok(r)
                    }
                    "classes" -> {
                        val r = DexJavaDecompiler.listClasses(
                            path,
                            query = "",
                            limit = args.intValue("limit", 200),
                        )
                        if (r.has("error")) err("JAVA_DECOMPILE_FAILED", r.optString("error"), "path", path)
                        else ok(r)
                    }
                    else -> err("UNKNOWN_ACTION", "未知 action：$action", "action", action)
                }
            }.getOrElse { t ->
                err(
                    "JAVA_DECOMPILE_FAILED",
                    "反编译失败：${t.javaClass.simpleName}: ${t.message ?: ""}",
                    "path", path,
                )
            }
        }
    }

    val ALL: List<ToolHandler> = listOf(tool)
}
