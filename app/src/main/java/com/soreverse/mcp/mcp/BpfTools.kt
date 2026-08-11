package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONObject

/**
 * 塔菲逆核: eBPF dex —— 内核级 DEX 加载/访问追踪。
 *
 * 真机上完整 eBPF（编译 BPF 程序 + bpftool + BTF）不可行，
 * 这里用与 eBPF 同源的 Linux 内核追踪机制（tracefs tracepoint/kprobe）
 * 实现等效效果：root 下挂 sys_enter_openat 追踪，观察哪个进程在何时
 * 访问 .dex/.apk/.so 文件——即"内核级观察 DEX 被加载"。
 *
 * 需要 Root（或 Shizuku 无法写 tracefs，Android 上 tracefs 挂载点通常
 * 需要 root 才能写）。
 */
object BpfTools {

    /** 内核追踪文件系统路径（Android 12+ 为 /sys/kernel/tracing，旧版在 debugfs）。 */
    private fun tracefs(): String? {
        val candidates = arrayOf("/sys/kernel/tracing", "/sys/kernel/debug/tracing")
        for (c in candidates) {
            val r = PermissionManager.exec("ls $c/events >/dev/null 2>&1 && echo OK")
            if (r.success && r.stdout.contains("OK")) return c
        }
        return null
    }

    private fun env(): JSONObject {
        val root = PermissionManager.isRootAvailable()
        val tr = tracefs()
        val kallsyms = PermissionManager.exec("cat /proc/kallsyms 2>/dev/null | head -1").stdout.isNotBlank()
        val kernel = PermissionManager.exec("uname -r").stdout.trim()
        val bpfSupport = PermissionManager.exec("ls /sys/fs/bpf 2>/dev/null | head -3").stdout
        return JSONObject()
            .put("root", root)
            .put("channel", PermissionManager.bestChannel().name)
            .put("tracefs", tr ?: "")
            .put("kallsyms", kallsyms)
            .put("kernel", kernel)
            .put("bpfFs", bpfSupport)
            .put("note", "Android 真机无法直接编译运行 eBPF 程序；本工具用内核 tracepoint/kprobe（与 eBPF 同源）实现 DEX 访问追踪")
    }

    val bpfDex: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_ebpf_dex",
            "【eBPF dex 追踪】内核级观察 DEX 加载/访问。action=probe 检测内核追踪能力(root/tracefs/kallsyms); action=watch 开启内核追踪(sys_enter_openat tracepoint), 采集最近的 .dex/.apk/.so 访问记录(进程+路径+时间); action=stop 停止追踪并输出统计; action=kprobe 尝试挂载 kprobe 到指定内核符号(实验)。需 Root 权限。",
            "Kernel-level DEX access tracing. probe kernel tracing capability; watch sys_enter_openat tracepoint collecting recent .dex/.apk/.so access (process+path+time); stop tracing; kprobe experimental. Requires Root.",
            "device", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("probe | watch | stop | kprobe", "probe", "watch", "stop", "kprobe")
                "seconds" int "watch: 采集时长(秒, 默认 3, 最大 10)"
                "pattern" str "watch: 文件名过滤正则(默认 .*\\.(dex|apk|so).*)"
                "symbol" str "kprobe: 内核符号名(实验)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "probe")
            return runCatching {
                when (action) {
                    "probe" -> ok(env())

                    "watch" -> {
                        if (!PermissionManager.isRootAvailable()) {
                            return err("NO_ROOT", "内核追踪需要 Root 权限，请在 设置→诊断与关于→权限管理 中授权", "action", "watch")
                        }
                        val tr = tracefs() ?: return err("NO_TRACEFS", "内核 tracefs 不可用（内核未挂载或未启用追踪）", "action", "watch")
                        val seconds = args.intValue("seconds", 3).coerceIn(1, 10)
                        val pattern = args.str("pattern", ".*\\.(dex|apk|so).*")
                        val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
                            ?: return err("BAD_REGEX", "正则无效: $pattern", "action", "watch")
                        // 清空旧 trace，开启 sys_enter_openat tracepoint
                        val setup = PermissionManager.exec(
                            "echo > $tr/trace; echo 1 > $tr/events/syscalls/sys_enter_openat/enable; echo 1 > $tr/events/syscalls/sys_enter_open/enable 2>/dev/null; echo 1 > $tr/tracing_on",
                            timeoutSec = 8,
                        )
                        if (!setup.success) return err("TRACE_SETUP_FAILED", "开启 tracepoint 失败: ${setup.stderr}", "action", "watch")
                        // 采集 seconds 秒
                        Thread.sleep(seconds * 1000L)
                        // 读取并关闭
                        val read = PermissionManager.exec(
                            "cat $tr/trace; echo 0 > $tr/events/syscalls/sys_enter_openat/enable; echo 0 > $tr/events/syscalls/sys_enter_open/enable 2>/dev/null; echo 0 > $tr/tracing_on",
                            timeoutSec = 15,
                        )
                        val lines = read.stdout.lines()
                        // 解析 trace 行：过滤包含目标文件名的行
                        val hits = mutableListOf<JSONObject>()
                        var pending: JSONObject? = null
                        for (ln in lines) {
                            if (ln.contains("openat") || ln.contains("sys_enter_open")) {
                                pending = JSONObject().put("raw", ln.trim())
                            } else if (pending != null && regex.containsMatchIn(ln)) {
                                // 上一行 openat + 本行文件名参数
                                pending.put("file", ln.trim())
                                pending.put("matched", true)
                                hits.add(pending)
                                pending = null
                            } else {
                                pending = null
                            }
                        }
                        ok(JSONObject()
                            .put("action", "watch")
                            .put("tracefs", tr)
                            .put("durationSec", seconds)
                            .put("totalTraceLines", lines.size)
                            .put("hits", hits.take(50))
                            .put("hint", "命中为 openat 调用 + 紧随其后的文件名参数行"))
                    }

                    "stop" -> {
                        val tr = tracefs()
                        if (tr != null) {
                            PermissionManager.exec("echo 0 > $tr/events/syscalls/sys_enter_openat/enable; echo 0 > $tr/events/syscalls/sys_enter_open/enable 2>/dev/null; echo 0 > $tr/tracing_on", timeoutSec = 8)
                        }
                        ok(JSONObject().put("action", "stop").put("stopped", true))
                    }

                    "kprobe" -> {
                        if (!PermissionManager.isRootAvailable()) return err("NO_ROOT", "需要 Root 权限", "action", "kprobe")
                        val tr = tracefs() ?: return err("NO_TRACEFS", "tracefs 不可用", "action", "kprobe")
                        val symbol = args.str("symbol")
                        if (symbol.isBlank()) return err("INVALID_ARGUMENT", "需要 symbol 参数", "action", "kprobe")
                        val probe = PermissionManager.exec(
                            "echo 'p:taffy_$symbol $symbol' > $tr/kprobe_events 2>&1 && echo 1 > $tr/events/kprobes/taffy_$symbol/enable && echo OK",
                            timeoutSec = 8,
                        )
                        val okProbe = probe.stdout.contains("OK")
                        if (okProbe) PermissionManager.exec("echo 0 > $tr/events/kprobes/taffy_$symbol/enable; echo > $tr/kprobe_events", timeoutSec = 8)
                        ok(JSONObject()
                            .put("action", "kprobe")
                            .put("symbol", symbol)
                            .put("ok", okProbe)
                            .put("output", probe.stdout.ifBlank { probe.stderr }))
                    }

                    else -> err("UNKNOWN_ACTION", "未知 action: $action", "action", action)
                }
            }.getOrElse { e -> err("BPF_FAILED", "操作失败: ${e.message}", "action", action) }
        }
    }

    val ALL = listOf(bpfDex)
}
