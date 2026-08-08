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
 * 通过 logcat 命令采集系统日志,支持过滤/搜索/后台持久录制。
 * 需要非 root 也能读自己应用的日志; 读全系统日志需要 root 或 ADB 权限。
 *
 * 增强: 后台持久采集(start/stop/status), 录制到文件, 可回放搜索。
 */
object LogcatTools {

    /** 后台采集进程引用 */
    @Volatile private var captureProcess: Process? = null
    @Volatile private var captureFile: java.io.File? = null
    @Volatile private var capturePid: Int = 0
    @Volatile private var captureTag: String = ""
    @Volatile private var captureLevel: String = "V"

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

    /** 后台持久 logcat 采集 — 参考 NexusBridge LogFox 的 start/stop/status */
    val capture: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "logcat_capture",
            "【Logcat 后台录制】后台持久采集 logcat 日志到文件。action=start 启动后台采集(指定 tag/level/pid 过滤); action=stop 停止采集; action=status 查看采集状态; action=read 读取已采集的日志(支持关键字搜索); action=clear 清空已采集日志。参考 NexusBridge LogFox 的持久录制能力。",
            "Background persistent logcat capture. action=start (with tag/level/pid filter); stop; status; read (with keyword search); clear. Inspired by NexusBridge LogFox persistent recording.",
            "device", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("start | stop | status | read | clear", "start", "stop", "status", "read", "clear")
                "tag" str "start: 按 tag 过滤(如 ActivityManager)"
                "level".oneOf("start: 日志级别", "V", "D", "I", "W", "E", "F")
                "pid" int "start: 按 PID 过滤"
                "keyword" str "read: 搜索关键字(可选)"
                "lines" int "read: 返回行数(默认 500)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "status")

            return runCatching {
                when (action) {
                    "start" -> {
                        if (captureProcess != null && captureProcess!!.isAlive) {
                            return err("ALREADY_RUNNING", "后台采集已在运行, 先 stop 再 start", "action", "start")
                        }
                        captureTag = args.str("tag")
                        captureLevel = args.str("level", "V")
                        capturePid = args.intValue("pid", 0)
                        captureFile = java.io.File(ctx.context.filesDir, "logcat_capture.log")
                        captureFile?.delete()

                        val cmd = mutableListOf("logcat", "-v", "time")
                        if (capturePid > 0) cmd.add("--pid=$capturePid")
                        val filter = if (captureTag.isNotBlank()) {
                            "$captureTag:$captureLevel"
                        } else {
                            "*:$captureLevel"
                        }
                        cmd.add(filter)

                        captureProcess = ProcessBuilder(cmd)
                            .redirectErrorStream(true)
                            .redirectOutput(captureFile)
                            .start()
                        capturePid = 0 // Android Process 没有 pid() API, 用 status 查询时可用 /proc

                        ok(JSONObject()
                            .put("action", "start")
                            .put("running", true)
                            .put("pid", capturePid)
                            .put("file", captureFile?.absolutePath ?: "")
                            .put("tag", captureTag)
                            .put("level", captureLevel)
                            .put("hint", "后台采集已启动, 用 action=read 读取, action=stop 停止"))
                    }

                    "stop" -> {
                        captureProcess?.destroy()
                        captureProcess = null
                        val lineCount = captureFile?.readLines()?.size ?: 0
                        ok(JSONObject()
                            .put("action", "stop")
                            .put("stopped", true)
                            .put("totalLines", lineCount)
                            .put("file", captureFile?.absolutePath ?: ""))
                    }

                    "status" -> {
                        val running = captureProcess != null && captureProcess!!.isAlive
                        val lineCount = captureFile?.takeIf { it.exists() }?.readLines()?.size ?: 0
                        ok(JSONObject()
                            .put("action", "status")
                            .put("running", running)
                            .put("pid", capturePid)
                            .put("tag", captureTag)
                            .put("level", captureLevel)
                            .put("totalLines", lineCount)
                            .put("file", captureFile?.absolutePath ?: "")
                            .put("fileSize", captureFile?.length() ?: 0))
                    }

                    "read" -> {
                        val file = captureFile ?: return err("NOT_RUNNING", "没有采集文件, 先 start", "action", "read")
                        if (!file.exists()) return err("NO_FILE", "采集文件不存在", "action", "read")
                        val maxLines = args.intValue("lines", 500).coerceAtMost(5000)
                        val keyword = args.str("keyword")
                        val allLines = file.readLines()
                        val filtered = if (keyword.isNotBlank()) {
                            allLines.filter { it.contains(keyword, ignoreCase = true) }
                        } else {
                            allLines
                        }
                        val result = filtered.takeLast(maxLines)
                        ok(JSONObject()
                            .put("action", "read")
                            .put("totalLines", allLines.size)
                            .put("filteredLines", filtered.size)
                            .put("returnedLines", result.size)
                            .put("keyword", keyword)
                            .put("logs", result.joinToString("\n")))
                    }

                    "clear" -> {
                        captureFile?.takeIf { it.exists() }?.delete()
                        ok(JSONObject().put("action", "clear").put("cleared", true))
                    }

                    else -> err("UNKNOWN_ACTION", "未知 action: $action", "action", action)
                }
            }.getOrElse { e -> err("LOGCAT_CAPTURE_FAILED", "操作失败: ${e.message}", "action", action) }
        }
    }

    val ALL = listOf(collect, crash, capture)
}
