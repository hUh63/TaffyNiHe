package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.BinaryManager
import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONObject

/**
 * 塔菲逆核: eDBG —— 官方 eBPF 调试器封装（ShinoLeah/eDBG）。
 *
 * eDBG 基于 eBPF + 文件偏移断点，不 ptrace、不附加进程，基本无视反调试。
 * 本工具负责：下载部署二进制 → 启动调试会话（Root）→ 通过命名管道交互
 * （断点/单步/继续/内存/寄存器等，与 eDBG CLI 命令一致）。
 *
 * 环境要求：ARM64 + Root + 内核 5.10+（推荐 KernelSU）。
 */
object EdbgTools {

    private const val BIN = "/data/local/tmp/eDBG"
    private const val FIFO = "/data/local/tmp/edbg_in"
    private const val OUT = "/data/local/tmp/edbg_out"

    @Volatile private var sessionRunning = false
    @Volatile private var sessionPkg = ""

    val edbg: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_edbg",
            "【eDBG 调试】官方 eBPF 调试器（ShinoLeah/eDBG，无视反调试）。action=probe 检测环境(二进制/内核5.10+/Root); action=install 下载并部署二进制到 /data/local/tmp; action=launch 启动调试会话(-p 目标包名, 可选 -l 库名 -b 初始断点偏移); action=cmd 发送调试命令(b 断点/hbreak/watch/continue/step/finish/until/examine/write/dump/info); action=stop 结束会话。基于文件+偏移断点, 需先用 -b 断下才能操作。",
            "Official eBPF debugger (ShinoLeah/eDBG, anti-anti-debug). probe env; install binary; launch session (-p package, -l lib, -b break offset); cmd (b/hbreak/watch/c/r/s/finish/until/x/write/dump/info); stop. Root + kernel 5.10+.",
            "device", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("probe | install | launch | cmd | stop", "probe", "install", "launch", "cmd", "stop")
                "package" str "launch: 目标应用包名"
                "lib" str "launch: 目标动态库名(可选)"
                "break" str "launch: 初始断点偏移(如 0x123456, 必填以先断下程序)"
                "cmd" str "cmd: eDBG 调试命令(如 'b lib.so+0x1234' / 'c' / 's' / 'x 0x1234')"
                "timeoutSec" int "cmd: 等待输出秒数(默认 2, 最大 15)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "probe")
            return runCatching {
                when (action) {
                    "probe" -> ok(probeEnv(ctx))

                    "install" -> {
                        if (!PermissionManager.isRootAvailable()) {
                            return err("NO_ROOT", "部署二进制需要 Root 权限（eDBG 必须运行在 Root 环境）", "action", "install")
                        }
                        if (!BinaryManager.kernelMeets("5.10")) {
                            return err("KERNEL_TOO_OLD", "eDBG 需要内核 5.10+，当前 ${BinaryManager.kernelVersion()}", "action", "install")
                        }
                        if (!BinaryManager.isDownloaded(ctx.context, BinaryManager.EDBG)) {
                            val dl = BinaryManager.download(ctx.context, BinaryManager.EDBG)
                            if (!dl.optBoolean("ok")) return err("DOWNLOAD_FAILED", dl.optString("error"), "action", "install")
                        }
                        val dep = BinaryManager.deploy(ctx.context, BinaryManager.EDBG)
                        if (!dep.optBoolean("ok")) return err("DEPLOY_FAILED", dep.optString("error"), "action", "install")
                        ok(JSONObject().put("action", "install").put("installed", true).put("path", BIN))
                    }

                    "launch" -> {
                        if (sessionRunning) return err("ALREADY_RUNNING", "已有 eDBG 会话(PKG=$sessionPkg)，先 stop", "action", "launch")
                        val pkg = args.str("package")
                        if (pkg.isBlank()) return err("INVALID_ARGUMENT", "需要 package 参数", "action", "launch")
                        if (!isDeployed()) return err("NOT_INSTALLED", "eDBG 未部署，先执行 install", "action", "launch")
                        val lib = args.str("lib")
                        val brk = args.str("break")
                        val bArg = if (brk.isNotBlank()) " -b $brk" else " -b 0x0"
                        val lArg = if (lib.isNotBlank()) " -l $lib" else ""
                        // 清理旧会话，建 fifo，后台启动
                        val setup = PermissionManager.exec(
                            "pkill -f '$BIN' 2>/dev/null; rm -f $FIFO $OUT; mkfifo $FIFO; " +
                                "nohup $BIN -p $pkg$lArg$bArg < $FIFO > $OUT 2>&1 &",
                            timeoutSec = 10,
                        )
                        Thread.sleep(1500)
                        val head = PermissionManager.exec("head -c 2000 $OUT 2>/dev/null", timeoutSec = 5)
                        sessionRunning = true
                        sessionPkg = pkg
                        ok(JSONObject()
                            .put("action", "launch")
                            .put("package", pkg)
                            .put("lib", lib)
                            .put("breakOffset", brk)
                            .put("started", true)
                            .put("output", head.stdout.takeLast(1500)))
                    }

                    "cmd" -> {
                        if (!sessionRunning) return err("NOT_RUNNING", "没有活跃的 eDBG 会话，先 launch", "action", "cmd")
                        val cmd = args.str("cmd")
                        if (cmd.isBlank()) return err("INVALID_ARGUMENT", "需要 cmd 参数", "action", "cmd")
                        val timeout = args.intValue("timeoutSec", 2).coerceIn(1, 15)
                        // 发命令到 fifo（写 fifo 会阻塞直到有读取者，用后台写入避免卡死）
                        val before = PermissionManager.exec("wc -c < $OUT 2>/dev/null", timeoutSec = 5).stdout.trim().toLongOrNull() ?: 0
                        val write = PermissionManager.exec(
                            "(echo '$cmd' > $FIFO) &",
                            timeoutSec = 5,
                        )
                        Thread.sleep(timeout * 1000L)
                        val after = PermissionManager.exec("tail -c +${before + 1} $OUT 2>/dev/null | head -c 6000", timeoutSec = 8)
                        ok(JSONObject()
                            .put("action", "cmd")
                            .put("command", cmd)
                            .put("output", after.stdout.trim().ifBlank { "(无新输出，命令可能仍在执行或已结束)" })
                            .put("running", sessionRunning))
                    }

                    "stop" -> {
                        PermissionManager.exec("pkill -f '$BIN' 2>/dev/null; rm -f $FIFO", timeoutSec = 10)
                        sessionRunning = false
                        sessionPkg = ""
                        ok(JSONObject().put("action", "stop").put("stopped", true))
                    }

                    else -> err("UNKNOWN_ACTION", "未知 action: $action", "action", action)
                }
            }.getOrElse { e -> err("EDBG_FAILED", "eDBG 操作失败: ${e.message}", "action", action) }
        }
    }

    private fun isDeployed(): Boolean = BinaryManager.isDeployed(BinaryManager.EDBG)

    private fun probeEnv(ctx: ToolContext): JSONObject {
        val root = PermissionManager.isRootAvailable()
        val shizuku = PermissionManager.isShizukuGranted()
        val canUse = root && isDeployed() && BinaryManager.kernelMeets("5.10")
        val reason = when {
            !root -> "eDBG 基于 eBPF，需要 Root 权限（Shizuku 无 eBPF 加载能力）。"
            !isDeployed() -> "eDBG 二进制未部署，先执行 install。"
            !BinaryManager.kernelMeets("5.10") -> "内核版本 < 5.10，eDBG 不兼容。"
            else -> ""
        }
        val fallback = JSONArray().put(JSONObject()
            .put("category", "SO 静态分析（无需 root）")
            .put("tools", "taffy_so_open + analyze_* (functions/cfg/xrefs/strings) + taffy_read_disasm")
            .put("description", "基于内置 Rizin 后端的本地 ELF 静态分析，不需要任何特权。")
        ).put(JSONObject()
            .put("category", "DEX 静态分析（无需 root）")
            .put("tools", "taffy_dex_inspect / DexAnalysisTools 系列")
            .put("description", "本地 dex 文件结构化检查与反编译，不需要 root 或 eBPF。")
        ).put(JSONObject()
            .put("category", "动态插桩替代（Frida，需 app debuggable 或 Shizuku 配合）")
            .put("tools", "Frida 工具页 + taffy_analyze_guide")
            .put("description", "Frida-gadget 模式可调试 debuggable 应用；非 debuggable 仍需 root。")
        )
        return BinaryManager.status(ctx.context, BinaryManager.EDBG)
            .put("root", root)
            .put("shizuku", shizuku)
            .put("canUse", canUse)
            .put("reason", reason)
            .put("fallbackTools", fallback)
            .put("sessionRunning", sessionRunning)
            .put("sessionPackage", sessionPkg)
    }

    val ALL = listOf(edbg)
}
