package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.NativeExec
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 塔菲逆核: 原生可执行通道（execve + jniLibs）。
 *
 * Android 10 起应用私有目录不允许 `execve`（SELinux + W^X），但 APK 安装解压出的
 * nativeLibraryDir 仍可执行。把 Android PIE 可执行文件命名成 `lib<name>.so` 放进
 * `jniLibs/<abi>/`，安装后即可直接执行 —— 免 root、免 proot、免 rootfs。
 *
 * 本工具是这条通道的出口：列目录、判定 ELF、前台执行、常驻后台、进程管理、读日志。
 * 只允许 nativeLibraryDir 内 `lib*.so` 形式的文件，参数不经 shell（直接 argv）。
 */
object NativeExecTool {

    private val tool: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_native_exec",
            "【原生可执行通道】在设备上直接 execve 随 APK 打包的可执行文件（jniLibs 伪装成 lib*.so，安装后位于 nativeLibraryDir，无需 root/proot/rootfs）。action=list 列出全部候选并给出 ELF 判定与可运行性；action=probe 单文件详情；action=run 前台执行（参数、超时、输出上限）；action=daemon 常驻后台（日志落盘，返回 pid）；action=ps 列出受管进程；action=kill 终止；action=log 读常驻进程日志尾部。参数直接作为 argv 传递，不经 shell。",
            "Execute binaries shipped inside the APK via the jniLibs channel (a PIE executable renamed lib*.so lands in nativeLibraryDir after install; no root, proot or rootfs needed). action=list inventories candidates with ELF verdicts; probe shows one file; run executes in the foreground; daemon starts a long-running process with a log file and returns its pid; ps/kill manage managed processes; log tails a daemon log. Arguments are passed as argv, never through a shell.",
            "runtime",
            ToolClass.EXTRA,
            heavy = false,
        ) {
            objectSchema(props {
                "action".oneOf("list (默认) | probe | run | daemon | ps | kill | log", "list", "probe", "run", "daemon", "ps", "kill", "log")
                "name" str "可执行文件名（nativeLibraryDir 内，形如 libcloudflared.so）"
                "args" str "空格分隔的参数（run/daemon）"
                "argsJson" str "JSON 数组形式的参数，优先于 args（可含空格/引号）"
                "stdin" str "run: 写入子进程 stdin 后关闭"
                "timeoutSec" int "run: 超时秒数（默认 30，最大 900）"
                "maxOutputBytes" int "run: stdout/stderr 各自上限（默认 256KB，最大 1MB）"
                "logFile" str "daemon: 日志文件绝对路径（默认 cacheDir/native-exec-<name>.log）"
                "pid" int "kill/log: 进程 id"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject = runCatching {
            when (val action = args.str("action", "list").ifBlank { "list" }) {
                "list" -> NativeExec.list(ctx.context)
                "probe" -> NativeExec.probe(ctx.context, args.str("name"))
                "run" -> run(ctx, args)
                "daemon" -> daemon(ctx, args)
                "ps" -> ok(JSONObject().put("action", "ps").put("processes", NativeExec.processes()))
                "kill" -> NativeExec.kill(args.intValue("pid", -1).toLong())
                "log" -> NativeExec.tailLog(args.intValue("pid", -1).toLong())
                else -> err("BAD_ACTION", "未知 action", "action", action)
            }
        }.getOrElse { e ->
            err("NATIVE_EXEC_FAILED", "原生可执行通道操作失败: ${e.message ?: e.javaClass.simpleName}", "action", args.str("action"))
        }

        /** 参数解析：argsJson 优先（真正的 argv），否则按空白切分 args。 */
        private fun parseArgs(args: JSONObject): List<String> {
            val json = args.str("argsJson").trim()
            if (json.isNotEmpty()) {
                val arr = runCatching { JSONArray(json) }.getOrNull() ?: return listOf(json)
                return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
            }
            val raw = args.str("args").trim()
            return if (raw.isEmpty()) emptyList() else raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
        }

        private fun run(ctx: ToolContext, args: JSONObject): JSONObject {
            val name = args.str("name")
            if (name.isBlank()) return err("ARGUMENT_REQUIRED", "run 需要 name（nativeLibraryDir 内的 lib*.so）", "name", name)
            val result = NativeExec.run(
                context = ctx.context,
                name = name,
                args = parseArgs(args),
                stdin = args.str("stdin").takeIf { it.isNotEmpty() },
                timeoutSec = args.intValue("timeoutSec", 30).toLong(),
                maxOutputBytes = args.intValue("maxOutputBytes", 256 * 1024),
            )
            val payload = JSONObject()
                .put("action", "run")
                .put("name", name)
                .put("exitCode", result.code)
                .put("stdout", result.stdout)
                .put("stderr", result.stderr)
                .put("truncated", result.truncated)
                .put("timedOut", result.timedOut)
            if (result.pid > 0) payload.put("pid", result.pid)
            // 执行失败（非零退出/超时/前置校验失败）时给出结构化错误，但保留原始输出便于用户判断
            if (result.code != 0) {
                val error = JSONObject()
                    .put("code", if (result.timedOut) "EXEC_TIMEOUT" else "EXIT_NONZERO")
                    .put("message", if (result.timedOut) "进程超时被强制终止" else "进程以非零状态退出：${result.code}")
                    .put("severity", "error")
                    .put("recoverable", true)
                    .put("stderrExcerpt", result.stderr.take(2000))
                return JSONObject().put("ok", false).put("error", error).put("result", payload).put("nextActions", JSONArray())
            }
            return ok(payload)
        }

        private fun daemon(ctx: ToolContext, args: JSONObject): JSONObject {
            val name = args.str("name")
            if (name.isBlank()) return err("ARGUMENT_REQUIRED", "daemon 需要 name（nativeLibraryDir 内的 lib*.so）", "name", name)
            val log = args.str("logFile").takeIf { it.isNotBlank() }?.let { File(it) }
            return NativeExec.startDaemon(ctx.context, name, parseArgs(args), log).put("action", "daemon")
        }
    }

    val ALL = listOf(tool)
}
