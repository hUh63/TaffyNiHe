package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import com.soreverse.mcp.core.intValue
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 塔菲逆核: Logcat 日志采集工具(参考 NexusBridge 的 LogFox MCP)。
 * 通过 logcat 命令采集系统日志,支持过滤/搜索/录制。
 * 需要非 root 也能读自己应用的日志; 读全系统日志需要 root 或 ADB 权限。
 */
object LogcatTools {

    /** 采集 logcat 日志 */
    val collect: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "logcat_collect",
            "【Logcat 采集】采集系统 logcat 日志。action=recent 获取最近 N 行(默认200); action=search 按关键字搜索; action=dump 一次性 dump 全部日志(小心输出过大)。支持按 tag/level/pid 过滤。需 root 或 ADB 才能读全系统日志,否则只能读自己应用的。",
            "Collect system logcat logs. action=recent gets last N lines; action=search filters by keyword; action=dump gets all. Supports tag/level/pid filtering. Root or ADB needed for system-wide logs.",
            "device", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("recent | search | dump", "recent", "search", "dump")
                "lines" int "recent: 获取行数(默认200, 最大2000)"
                "keyword" str "search: 搜索关键字"
                "tag" str "按 tag 过滤(如 ActivityManager)"
                "level".oneOf("日志级别过滤", "V", "D", "I", "W", "E", "F")
                "pid" int "按 PID 过滤"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "recent")
            val maxLines = args.intValue("lines", 200).coerceAtMost(2000)
            val tag = args.str("tag")
            val level = args.str("level", "V")
            val pid = args.intValue("pid", 0)

            val cmd = mutableListOf("logcat", "-d")
            if (pid > 0) cmd.add("--pid=$pid")
            val filter = if (tag.isNotBlank()) {
                "$tag:${if (level.isNotBlank()) level else "V"}"
            } else {
                "*:${if (level.isNotBlank()) level else "V"}"
            }
            cmd.add(filter)

            return runCatching {
                val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
                val output = StringBuilder()
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    val allLines = mutableListOf<String>()
                    var line = reader.readLine()
                    while (line != null) {
                        allLines.add(line)
                        line = reader.readLine()
                    }
                    proc.waitFor()
                    val keyword = args.str("keyword")
                    val filtered = if (keyword.isNotBlank()) {
                        allLines.filter { it.contains(keyword, ignoreCase = true) }
                    } else {
                        allLines
                    }
                    val result = when (action) {
                        "recent" -> filtered.takeLast(maxLines)
                        "search" -> filtered.take(maxLines)
                        "dump" -> filtered.take(maxLines * 5)
                        else -> filtered.takeLast(maxLines)
                    }
                    result.forEach { output.appendLine(it) }
                    ok(JSONObject()
                        .put("action", action)
                        .put("totalLines", filtered.size)
                        .put("returnedLines", result.size)
                        .put("truncated", filtered.size > result.size)
                        .put("logs", output.toString()))
                }
            }.getOrElse { e -> err("LOGCAT_FAILED", "logcat 采集失败: ${e.message}", "action", action) }
        }
    }

    /** 采集崩溃日志 */
    val crash: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "logcat_crash",
            "【崩溃日志】采集最近的崩溃日志(JAVA/ANR/NATIVE CRASH)。自动过滤 crash/fatal/ANR/died 关键字。",
            "Collect recent crash logs (JAVA/ANR/NATIVE CRASH). Auto-filters crash/fatal/ANR/died keywords.",
            "device", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "lines" int "获取行数(默认100, 最大500)"
                "keyword" str "额外过滤关键字(可选)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val maxLines = args.intValue("lines", 100).coerceAtMost(500)
            val extraKeyword = args.str("keyword")
            return runCatching {
                val cmd = mutableListOf("logcat", "-d", "*:E")
                val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
                val output = StringBuilder()
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    val crashKeywords = mutableListOf("CRASH", "FATAL", "ANR", "died", "AndroidRuntime", "SIGSEGV", "SIGABRT", "tombstone", "backtrace")
                    if (extraKeyword.isNotBlank()) crashKeywords.add(extraKeyword)
                    var line = reader.readLine()
                    val matches = mutableListOf<String>()
                    while (line != null) {
                        if (crashKeywords.any { line.contains(it, ignoreCase = true) }) matches.add(line)
                        line = reader.readLine()
                    }
                    proc.waitFor()
                    val result = matches.takeLast(maxLines)
                    result.forEach { output.appendLine(it) }
                    ok(JSONObject()
                        .put("totalCrashLines", matches.size)
                        .put("returnedLines", result.size)
                        .put("crashes", output.toString()))
                }
            }.getOrElse { e -> err("LOGCAT_FAILED", "崩溃日志采集失败: ${e.message}", null, null) }
        }
    }

    val ALL = listOf(collect, crash)
}
