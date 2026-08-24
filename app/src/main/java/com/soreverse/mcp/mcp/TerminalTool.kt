package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.core.RootShell
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject

/**
 * 塔菲逆核: 终端执行（借鉴「清风 QingFeng」的 terminal_exec / node_run / python_run）。
 *
 * 通过 root/Shizuku 调用设备上 Termux 内置的 Python3 / Node.js / BusyBox，
 * 执行脚本/命令并返回输出。不内置运行时（零 APK 膨胀）；无 Termux 或未 root 时明确提示。
 */
object TerminalTool {

    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"

    private val candidates = listOf(
        Triple("python3", "$TERMUX_PREFIX/bin/python3", "Python 3"),
        Triple("node", "$TERMUX_PREFIX/bin/node", "Node.js"),
        Triple("busybox", "$TERMUX_PREFIX/bin/busybox", "BusyBox"),
        Triple("bash", "$TERMUX_PREFIX/bin/bash", "Bash"),
        Triple("sh", "$TERMUX_PREFIX/bin/sh", "Shell"),
    )

    private fun termuxEnv(): String =
        "PREFIX=$TERMUX_PREFIX PATH=$TERMUX_PREFIX/bin:/system/bin TMPDIR=$TERMUX_PREFIX/tmp HOME=/data/data/com.termux/files/home"

    private fun isExecutable(path: String): Boolean =
        RootShell.exec("test -x \"$path\" && echo YES || echo NO", timeoutSec = 8).stdout.trim() == "YES"

    val terminal: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_terminal_exec",
            "【终端执行】通过 root/Shizuku 调用设备 Termux 里的 Python3/Node.js/BusyBox 执行脚本或命令（借鉴清风 terminal_exec）。action=detect 探测 Termux 环境及可用运行时；action=run 执行命令或脚本(script 参数传代码, 自动选运行时; 或传 command 直接执行)。无 Termux/未 root 时明确提示。",
            "Execute via Termux runtimes (python3/node/busybox) over root/Shizuku, borrowed from QingFeng terminal_exec. action=detect probes Termux + runtimes; action=run executes a command or inline script (auto-select runtime by shebang/script). Requires root/Shizuku + Termux installed.",
            "dynamic", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("detect (默认) | run", "detect", "run")
                "command" str "run: 直接执行的 shell 命令"
                "script" str "run: 脚本代码（首行 shebang 决定运行时，如 #!/usr/bin/env python3）"
                "runtime".oneOf("run: 指定运行时（默认 auto）", "auto", "python3", "node", "bash", "busybox")
                "timeoutSec" int "run: 超时秒数(默认 30, 最大 300)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            return runCatching {
                when (args.str("action", "detect").ifBlank { "detect" }) {
                    "detect" -> detect()
                    "run" -> run(ctx, args)
                    else -> err("BAD_ACTION", "未知 action", "action", args.str("action"))
                }
            }.getOrElse { e -> err("TERMINAL_FAILED", "终端操作失败: ${e.message ?: e.javaClass.simpleName}", "action", args.str("action")) }
        }

        private fun detect(): JSONObject {
            if (!RootShell.isRootAvailable() && !PermissionManager.isShizukuGranted()) {
                return err("NO_PRIVILEGE", "终端执行需要 root/Shizuku（无 root 无法访问 Termux 运行时）", "action", "detect")
            }
            val termuxOk = isExecutable("$TERMUX_PREFIX/bin/sh")
            val runtimes = JSONArray()
            for ((name, path, desc) in candidates) {
                if (isExecutable(path)) {
                    val ver = RootShell.exec("env ${termuxEnv()} \"$path\" --version 2>&1 | head -1", timeoutSec = 10).stdout.trim().take(120)
                    runtimes.put(JSONObject().put("name", name).put("path", path).put("desc", desc).put("version", ver))
                }
            }
            return ok(JSONObject()
                .put("action", "detect")
                .put("termuxInstalled", termuxOk)
                .put("termuxPrefix", TERMUX_PREFIX)
                .put("runtimes", runtimes)
                .put("hint", if (runtimes.length() == 0)
                    "未检测到 Termux 运行时。请安装 Termux 并执行: pkg install python nodejs busybox (或只用 detect 确认)"
                else "检测到 ${runtimes.length()} 个运行时，可用 action=run 执行。"))
        }

        private fun run(ctx: ToolContext, args: JSONObject): JSONObject {
            if (!RootShell.isRootAvailable() && !PermissionManager.isShizukuGranted()) {
                return err("NO_PRIVILEGE", "终端执行需要 root/Shizuku（无 root 无法访问 Termux 运行时）", "action", "run")
            }
            val command = args.str("command").ifBlank { "" }
            val script = args.str("script").ifBlank { "" }
            if (command.isBlank() && script.isBlank()) {
                return err("INVALID_ARGUMENT", "需要 command 或 script 参数", "command", "")
            }
            val timeout = args.intValue("timeoutSec", 30).coerceIn(1, 300)
            val runtime = args.str("runtime", "auto").ifBlank { "auto" }

            // 选择运行时
            val runtimeBin: String = when (runtime) {
                "python3" -> "$TERMUX_PREFIX/bin/python3"
                "node" -> "$TERMUX_PREFIX/bin/node"
                "bash" -> "$TERMUX_PREFIX/bin/bash"
                "busybox" -> "$TERMUX_PREFIX/bin/busybox"
                else -> {
                    // auto: 脚本 shebang 决定；否则按脚本内容猜测
                    val shebang = script.lineSequence().firstOrNull() ?: ""
                    when {
                        shebang.contains("python") -> "$TERMUX_PREFIX/bin/python3"
                        shebang.contains("node") -> "$TERMUX_PREFIX/bin/node"
                        shebang.contains("bash") || shebang.contains("sh") -> "$TERMUX_PREFIX/bin/bash"
                        else -> "$TERMUX_PREFIX/bin/sh"
                    }
                }
            }
            if (!isExecutable(runtimeBin)) {
                return err("RUNTIME_MISSING", "运行时不可用: $runtimeBin（Termux 中 pkg install python/nodejs 安装）", "runtime", runtime)
            }

            // 组装执行：脚本写入临时文件后运行（避免命令行长度/引号问题）
            val exec = if (script.isNotBlank()) {
                val tmp = "/data/local/tmp/taffy_term_${System.currentTimeMillis()}.${if (runtimeBin.endsWith("python3")) "py" else if (runtimeBin.endsWith("node")) "js" else "sh"}"
                // 写脚本（base64 传避免引号转义）
                val b64 = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
                val writeR = PermissionManager.exec("echo $b64 | base64 -d > $tmp && chmod 755 $tmp 2>/dev/null; env ${termuxEnv()} \"$runtimeBin\" $tmp 2>&1; rm -f $tmp", timeoutSec = timeout.toLong())
                writeR
            } else {
                PermissionManager.exec("env ${termuxEnv()} \"$runtimeBin\" -c \"$command\" 2>&1", timeoutSec = timeout.toLong())
            }

            val truncated = exec.stdout.length > 20000
            return ok(JSONObject()
                .put("action", "run")
                .put("runtime", runtimeBin.removePrefix(TERMUX_PREFIX + "/bin/"))
                .put("exitCode", exec.code)
                .put("output", exec.stdout.take(20000))
                .put("stderr", exec.stderr.take(3000))
                .put("truncated", truncated)
                .put("hint", if (exec.code == 0) "执行完成" else "执行失败(exit=${exec.code})，详见 stderr"))
        }
    }

    val ALL: List<ToolHandler> = listOf(terminal)
}
