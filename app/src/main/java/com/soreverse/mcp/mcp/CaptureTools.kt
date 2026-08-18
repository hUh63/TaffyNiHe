package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONObject
import java.io.File

/**
 * 塔菲逆核: 抓包 / 网络流量采集工具。
 *
 * 通过特权通道(Root/Shizuku/Dhizuku)执行网络抓取:
 *  - 接口/连接/流量/DNS 信息采集(无特权时部分降级为 /proc 只读)
 *  - tcpdump 抓包(需 root 或 Shizuku + tcpdump 二进制), pcap 落盘 /data/local/tmp
 *  - 抓包完成后可 pull 到应用私有目录供分享/分析
 */
object CaptureTools {

    // ── tcpdump 抓包会话状态 ──
    @Volatile private var sniffProcess: Process? = null
    @Volatile private var sniffPcapPath: String = ""
    @Volatile private var sniffStartedAt: Long = 0L

    private fun hasPriv(): Boolean =
        PermissionManager.isRootAvailable() || PermissionManager.isShizukuGranted() || PermissionManager.isDhizukuAvailable()

    /** 通过特权通道执行命令; 无特权时尝试普通进程。 */
    private fun runRaw(cmd: String): Pair<Int, String> {
        if (hasPriv()) {
            val r = PermissionManager.exec(cmd, timeoutSec = 15)
            if (r.code == 0 && r.stdout.isNotBlank()) return r.code to r.stdout
            if (r.stderr.isNotBlank() && r.stdout.isBlank()) return r.code to (r.stderr + "\n" + r.stdout)
        }
        return runCatching {
            val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor() to out
        }.getOrDefault(-1 to "exec failed")
    }

    /** 读取 /proc/net/dev 流量统计。 */
    private fun procNetDev(): String {
        return runCatching { File("/proc/net/dev").readText() }.getOrDefault("")
    }

    val capture = object : ToolHandler {
        override val meta = ToolMeta("taffy_capture",
            "【抓包】网络抓包与流量采集。action=info 网络接口信息; action=conn 当前网络连接(需特权); action=traffic 流量统计(/proc/net/dev); action=dns DNS 配置; action=sniff_start 启动 tcpdump 抓包(需 root/Shizuku+tcpdump, 输出 /data/local/tmp/*.pcap); action=sniff_stop 停止抓包; action=sniff_status 抓包状态; action=sniff_pull 将 pcap 复制到应用私有目录返回路径。",
            "Network capture. action=info interfaces; action=conn connections (privileged); action=traffic counters; action=dns config; action=sniff_start tcpdump (root/Shizuku+tcpdump); action=sniff_stop; action=sniff_status; action=sniff_pull copies pcap to app-private dir.",
            "device", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("capture action", "info", "conn", "traffic", "dns", "sniff_start", "sniff_stop", "sniff_status", "sniff_pull")
                "interface" str "sniff_start: 抓包网卡(默认 any)"
                "filter" str "sniff_start: tcpdump 过滤表达式(如 tcp port 443)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "info")
            return when (action) {
                "info" -> {
                    val ip = runRaw("ip addr show 2>/dev/null || ifconfig 2>/dev/null")
                    ok(JSONObject().put("action", action)
                        .put("privileged", hasPriv())
                        .put("interfaces", ip.second.take(4000)))
                }
                "conn" -> {
                    if (!hasPriv()) return err("NO_PRIV", "当前无 Root/Shizuku 权限，无法读取系统网络连接。")
                    val out = runRaw("ss -tunap 2>/dev/null || netstat -tunap 2>/dev/null || cat /proc/net/tcp /proc/net/tcp6 2>/dev/null")
                    ok(JSONObject().put("action", action).put("connections", out.second.take(8000)))
                }
                "traffic" -> {
                    ok(JSONObject().put("action", action).put("stats", procNetDev().take(6000)))
                }
                "dns" -> {
                    val getprop = runRaw("getprop | grep -i dns")
                    val resolv = runCatching { File("/etc/resolv.conf").readText() }.getOrDefault("")
                    ok(JSONObject().put("action", action)
                        .put("dnsProps", getprop.second.take(2000))
                        .put("resolvConf", resolv.take(2000)))
                }
                "sniff_start" -> {
                    if (sniffProcess != null) return err("SNIFF_BUSY", "已在抓包中: $sniffPcapPath，请先 sniff_stop")
                    if (!hasPriv()) return err("NO_PRIV", "抓包需要 Root 或 Shizuku 权限。")
                    // 检测 tcpdump
                    val probe = runRaw("which tcpdump 2>/dev/null || ls /system/xbin/tcpdump /data/local/tmp/tcpdump 2>/dev/null")
                    if (probe.second.isBlank()) return err("NO_TCPDUMP", "未找到 tcpdump 二进制。Root 环境可: adb push tcpdump /data/local/tmp && chmod 755 /data/local/tmp/tcpdump")
                    val iface = args.str("interface", "any")
                    val filter = args.str("filter")
                    // 命令注入防护：iface/filter 拼入 tcpdump 命令行，必须校验安全字符
                    if (!com.soreverse.mcp.core.RootShell.isSafeArg(iface)) {
                        return err("BAD_INTERFACE", "interface 含非法字符（仅允许字母数字._/- 和空格）", "interface", iface)
                    }
                    if (filter.isNotBlank() && !com.soreverse.mcp.core.RootShell.isSafeArg(filter)) {
                        return err("BAD_FILTER", "filter 含非法字符（仅允许字母数字._/- 和空格，不支持 shell 元字符）", "filter", filter)
                    }
                    val name = "taffy_capture_${System.currentTimeMillis()}.pcap"
                    val path = "/data/local/tmp/$name"
                    val cmd = buildString {
                        append("tcpdump -i ").append(iface).append(" -w ").append(path)
                        if (filter.isNotBlank()) append(" ").append(filter)
                    }
                    val p = PermissionManager.startPrivilegedStream("sh", listOf("-c", cmd))
                    if (p == null) return err("SNIFF_START_FAILED", "tcpdump 启动失败。")
                    sniffProcess = p
                    sniffPcapPath = path
                    sniffStartedAt = System.currentTimeMillis()
                    ok(JSONObject().put("action", action)
                        .put("status", "sniffing")
                        .put("pcapPath", path)
                        .put("interface", iface)
                        .put("filter", filter))
                }
                "sniff_stop" -> {
                    val had = sniffProcess != null
                    runCatching { sniffProcess?.destroy() }
                    sniffProcess = null
                    ok(JSONObject().put("action", action)
                        .put("status", if (had) "stopped" else "not-running")
                        .put("pcapPath", sniffPcapPath))
                }
                "sniff_status" -> {
                    ok(JSONObject().put("action", action)
                        .put("running", sniffProcess != null)
                        .put("pcapPath", sniffPcapPath)
                        .put("startedAt", if (sniffStartedAt > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(sniffStartedAt)) else "")
                        .put("privileged", hasPriv()))
                }
                "sniff_pull" -> {
                    if (sniffPcapPath.isBlank()) return err("NO_PCAP", "没有可用的抓包文件")
                    val appCtx = ctx.context ?: return err("NO_CONTEXT", "no context")
                    val dst = File(appCtx.filesDir, File(sniffPcapPath).name)
                    val r = runRaw("cp $sniffPcapPath ${dst.absolutePath} && chmod 644 ${dst.absolutePath}")
                    ok(JSONObject().put("action", action)
                        .put("ok", r.first == 0)
                        .put("pcapPath", if (r.first == 0) dst.absolutePath else "")
                        .put("size", if (dst.exists()) dst.length() else 0))
                }
                else -> err("BAD_ACTION", "未知 action: $action")
            }
        }
    }

    val ALL: List<ToolHandler> = listOf(capture)
}
