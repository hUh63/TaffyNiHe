package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Socket

/**
 * 塔菲逆核: eDBG —— 进程级调试工具。
 *
 * 实现方式：通过 Root/Shizuku 通道启动 gdbserver 附加到目标进程，
 * 再用 GDB 远程串行协议（RSP）mini 客户端直接读写寄存器/内存、
 * 下断点/单步/继续。无需电脑上的 gdb 客户端即可完成基础 native 调试。
 *
 * gdbserver 二进制要求：放置在 /data/local/tmp/gdbserver（arm64），
 * 或设备自带。对应 ABI 的 gdbserver 可从 Android NDK 或第三方构建获取。
 */
object DbgTools {

    /** 当前 gdbserver 会话（端口 + PID）。 */
    @Volatile private var dbgPort: Int = 0
    @Volatile private var dbgPid: Int = 0
    @Volatile private var dbgProcess: Process? = null

    private val TRACEFS = arrayOf("/sys/kernel/tracing", "/sys/kernel/debug/tracing")

    /** 探测调试环境：权限通道、gdbserver 是否存在、目标 ABI。 */
    private fun probeEnv(): JSONObject {
        val root = PermissionManager.isRootAvailable()
        val shizuku = PermissionManager.isShizukuGranted()
        val gdbserverExists = PermissionManager.exec("ls -l /data/local/tmp/gdbserver 2>/dev/null; ls -l /system/bin/gdbserver 2>/dev/null").stdout.isNotBlank()
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        return JSONObject()
            .put("root", root)
            .put("shizuku", shizuku)
            .put("channel", PermissionManager.bestChannel().name)
            .put("gdbserver", gdbserverExists)
            .put("abi", abi)
            .put("gdbserverHint", if (gdbserverExists) "" else "将 arm64 gdbserver 推送到 /data/local/tmp/gdbserver 并 chmod 755（需要 root/shizuku 权限）")
    }

    val debug: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_dbg",
            "【eDBG 进程调试】GDB 远程协议调试器。action=probe 探测环境(权限通道/gdbserver/ABI); action=attach 附加到进程(需 gdbserver 位于 /data/local/tmp/gdbserver, Root/Shizuku); action=detach 分离; action=info 读取进程 maps/线程/状态; action=regs 读寄存器; action=mem 读内存(addr,len 十六进制); action=break 下软件断点(addr); action=continue 继续执行; action=step 单步。",
            "eDBG process debugger over GDB RSP. probe env; attach to pid (gdbserver at /data/local/tmp/gdbserver, Root/Shizuku); detach; info maps/threads; regs; mem read; break; continue; step.",
            "device", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("probe | attach | detach | info | regs | mem | break | continue | step", "probe", "attach", "detach", "info", "regs", "mem", "break", "continue", "step")
                "pid" int "attach/info: 目标进程 PID"
                "port" int "attach: gdbserver 端口(默认 5039)"
                "addr" str "mem/break: 十六进制地址(如 0x7a3b4c000)"
                "len" int "mem: 读取字节数(默认 64, 最大 512)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "probe")
            return runCatching {
                when (action) {
                    "probe" -> ok(probeEnv())

                    "attach" -> {
                        val pid = args.intValue("pid", 0)
                        if (pid <= 0) return err("INVALID_ARGUMENT", "需要 pid 参数", "action", "attach")
                        val env = probeEnv()
                        if (!env.optBoolean("root") && !env.optBoolean("shizuku")) {
                            return err("NO_PRIVILEGE", "附加调试需要 Root 或 Shizuku 权限，请在 设置→诊断与关于→权限管理 中授权", "action", "attach")
                        }
                        if (!env.optBoolean("gdbserver")) {
                            return err("NO_GDBSERVER", "设备上没有 gdbserver，请将 arm64 gdbserver 推送到 /data/local/tmp/gdbserver 并 chmod 755", "hint", env.optString("gdbserverHint"))
                        }
                        if (dbgProcess != null && dbgProcess!!.isAlive) {
                            return err("ALREADY_ATTACHED", "已有调试会话(PID=$dbgPid port=$dbgPort)，先 detach", "pid", pid)
                        }
                        val port = args.intValue("port", 5039)
                        // 目标进程是否可调试（同一 uid 或 root）
                        val check = PermissionManager.exec("cat /proc/$pid/status 2>/dev/null | grep -E '^(Name|State|TracerPid)' | head -5")
                        if (check.code != 0) return err("PROCESS_NOT_FOUND", "无法读取进程 $pid 信息", "pid", pid)
                        val status = check.stdout
                        // root 下启动 gdbserver：目标为同 uid 应用时直接 attach；否则可能需要 ptrace 能力
                        val launch = PermissionManager.exec(
                            "nohup /data/local/tmp/gdbserver :$port --attach $pid >/data/local/tmp/gdbserver.log 2>&1 &",
                            timeoutSec = 10,
                        )
                        if (!launch.success) {
                            return err("ATTACH_FAILED", "gdbserver 启动失败: ${launch.stderr.ifBlank { launch.stdout }}", "pid", pid)
                        }
                        dbgPort = port; dbgPid = pid
                        // 等待端口就绪
                        var ready = false
                        repeat(10) {
                            val r = PermissionManager.exec("ss -ltn 2>/dev/null | grep ':$port ' || netstat -ltn 2>/dev/null | grep ':$port '")
                            if (r.stdout.contains(port.toString())) { ready = true; return@repeat }
                            Thread.sleep(500)
                        }
                        ok(JSONObject()
                            .put("action", "attach")
                            .put("attached", ready)
                            .put("pid", pid)
                            .put("port", port)
                            .put("processStatus", status)
                            .put("hint", if (ready) "gdbserver 已监听 :$port，可用 regs/mem/break/continue/step 调试" else "gdbserver 已启动但端口未确认，稍后重试 info"))
                    }

                    "detach" -> {
                        val r = PermissionManager.exec("pkill -f 'gdbserver' 2>/dev/null; true")
                        dbgProcess?.destroy()
                        dbgProcess = null
                        dbgPort = 0; dbgPid = 0
                        ok(JSONObject().put("action", "detach").put("detached", true).put("killResult", r.stdout.ifBlank { r.stderr }))
                    }

                    "info" -> {
                        val pid = args.intValue("pid", dbgPid)
                        if (pid <= 0) return err("INVALID_ARGUMENT", "需要 pid 参数", "action", "info")
                        val st = PermissionManager.exec("cat /proc/$pid/status 2>/dev/null | head -20")
                        val threads = PermissionManager.exec("ls /proc/$pid/task 2>/dev/null | wc -l")
                        val maps = PermissionManager.exec("grep -E 'r-xp|rw-p' /proc/$pid/maps 2>/dev/null | head -30")
                        ok(JSONObject()
                            .put("action", "info")
                            .put("pid", pid)
                            .put("status", st.stdout)
                            .put("threadCount", threads.stdout.trim().ifBlank { "0" })
                            .put("maps", maps.stdout))
                    }

                    "regs", "mem", "break", "continue", "step" -> {
                        if (dbgPort == 0) return err("NOT_ATTACHED", "先 attach 到目标进程", "action", action)
                        return runRsp(action, args)
                    }

                    else -> err("UNKNOWN_ACTION", "未知 action: $action", "action", action)
                }
            }.getOrElse { e -> err("DBG_FAILED", "调试操作失败: ${e.message}", "action", action) }
        }
    }

    /** 通过 RSP 协议操作已附加的 gdbserver。 */
    private fun runRsp(action: String, args: JSONObject): JSONObject = runCatching {
        RspClient("127.0.0.1", dbgPort).use { rsp ->
            when (action) {
                "regs" -> {
                    val reply = rsp.send("g") // 读全部寄存器
                    val regsHex = decodeHex(reply)
                    ok(JSONObject().put("action", "regs").put("registerBytes", regsHex).put("length", regsHex.size))
                }
                "mem" -> {
                    val addr = args.str("addr").removePrefix("0x").removePrefix("0X")
                    val len = args.intValue("len", 64).coerceIn(1, 512)
                    if (addr.isBlank()) return err("INVALID_ARGUMENT", "需要 addr 参数", "action", "mem")
                    val reply = rsp.send("m$addr,${len.toString(16)}")
                    val bytes = decodeHex(reply)
                    val hex = bytes.joinToString(" ") { "%02x".format(it) }
                    val ascii = bytes.map { if (it in 32..126) it.toInt().toChar() else '.' }.joinToString("")
                    ok(JSONObject().put("action", "mem").put("addr", "0x$addr").put("size", bytes.size).put("hex", hex).put("ascii", ascii))
                }
                "break" -> {
                    val addr = args.str("addr").removePrefix("0x").removePrefix("0X")
                    if (addr.isBlank()) return err("INVALID_ARGUMENT", "需要 addr 参数", "action", "break")
                    val reply = rsp.send("Z0,$addr,4")
                    ok(JSONObject().put("action", "break").put("addr", "0x$addr").put("ok", reply == "OK"))
                }
                "continue" -> {
                    val reply = rsp.send("c")
                    ok(JSONObject().put("action", "continue").put("status", reply.ifBlank { "running" }))
                }
                "step" -> {
                    val reply = rsp.send("s")
                    ok(JSONObject().put("action", "step").put("status", reply.ifBlank { "stepped" }))
                }
                else -> err("UNKNOWN_ACTION", "未知操作", "action", action)
            }
        }
    }.getOrElse { e -> err("RSP_FAILED", "RSP 通信失败: ${e.message}", "action", action) }

    /** 解析 RSP 十六进制响应为字节数组。 */
    private fun decodeHex(s: String): ByteArray {
        val clean = s.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        if (clean.length % 2 != 0) return ByteArray(0)
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** GDB 远程串行协议 mini 客户端。 */
    private class RspClient(host: String, port: Int) : AutoCloseable {
        private val socket = Socket(host, port)
        private val input = BufferedInputStream(socket.getInputStream())
        private val output = BufferedOutputStream(socket.getOutputStream())

        fun send(payload: String): String {
            val checksum = payload.fold(0) { acc, ch -> (acc + ch.code) % 256 }
            val packet = "$$payload#%02x".format(checksum)
            output.write(packet.toByteArray(Charsets.US_ASCII))
            output.flush()
            // 读 ack（+/-）
            val ack = input.read()
            if (ack == '-'.code) return "E_NAK"
            // 读响应包 $...#
            val sb = StringBuilder()
            var c = input.read()
            while (c != -1 && c != '#'.code) {
                if (c != '$'.code) sb.append(c.toChar())
                c = input.read()
            }
            // 读校验和两位
            input.read(); input.read()
            return sb.toString()
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    val ALL = listOf(debug)
}
