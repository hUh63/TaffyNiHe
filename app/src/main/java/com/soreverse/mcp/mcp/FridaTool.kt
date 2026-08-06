package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.FridaManager
import com.soreverse.mcp.core.RootShell
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject

/**
 * 塔菲逆核: Frida 动态插桩工具(需 root)。
 *
 * 第一版提供 frida-server 生命周期管理 + 进程/应用枚举。
 * frida-server 以 libfrida_server.so 内置打包,root 启动后监听 127.0.0.1:27042,
 * 可配合 frida CLI / 玄星里的 frida 客户端做 hook / spawn / 脚本注入。
 */
object FridaTool {

    val control: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "frida_control",
            "【Frida 动态插桩】管理内置 frida-server(需 root)。action=status 查状态; start 启动; stop 停止; ps 列出运行中的进程/应用(用于确定 hook 目标)。启动后 frida-server 监听 127.0.0.1:27042,可用 frida CLI 连接做 hook/spawn/脚本注入。",
            "Manage the built-in frida-server (requires root). action=status/start/stop/ps. Once started, frida-server listens on 127.0.0.1:27042 for hook/spawn/script injection.",
            "dynamic", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("status (default) | start | stop | ps", "status", "start", "stop", "ps")
                "filter" str "进程名过滤关键词(action=ps 可选)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            return when (args.str("action", "status").ifBlank { "status" }) {
                "start" -> {
                    val (okStart, msg) = FridaManager.start(ctx.context)
                    if (okStart) ok(JSONObject().put("action", "start").put("message", msg).put("port", FridaManager.DEFAULT_PORT))
                    else err("FRIDA_START_FAILED", msg)
                }
                "stop" -> {
                    val (okStop, msg) = FridaManager.stop()
                    if (okStop) ok(JSONObject().put("action", "stop").put("message", msg))
                    else err("FRIDA_STOP_FAILED", msg)
                }
                "ps" -> {
                    if (!RootShell.isRootAvailable()) return err("NO_ROOT", "未检测到 root,无法枚举进程")
                    val filter = args.str("filter")
                    // 安全修复: 对 filter 参数进行清洗，防止命令注入。
                    val cmd = if (filter.isBlank()) "ps -A -o PID,NAME 2>/dev/null || ps -A" else {
                        val safeFilter = RootShell.sanitizeShellArg(filter)
                        "ps -A -o PID,NAME 2>/dev/null | grep -i '$safeFilter'"
                    }
                    val r = RootShell.exec(cmd, timeoutSec = 12)
                    if (!r.success) return err("PS_FAILED", r.stderr.ifBlank { "进程枚举失败" })
                    val lines = r.stdout.lines().filter { it.isNotBlank() }.take(400)
                    ok(JSONObject()
                        .put("action", "ps")
                        .put("count", lines.size)
                        .put("processes", JSONArray(lines)))
                }
                else -> { // status
                    val s = FridaManager.status(ctx.context)
                    ok(JSONObject()
                        .put("action", "status")
                        .put("rootAvailable", s.rootAvailable)
                        .put("binaryPresent", s.binaryPresent)
                        .put("running", s.running)
                        .put("port", s.port)
                        .put("message", s.message))
                }
            }
        }
    }
}
