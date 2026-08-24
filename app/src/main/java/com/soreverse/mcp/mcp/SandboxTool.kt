package com.soreverse.mcp.mcp

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.core.RootShell
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 塔菲逆核: 动态分析沙箱（借鉴「清风 QingFeng」的 apk_sandbox_test，保留无 root 降级）。
 *
 * 闭环: install(安装) → launch(启动) → watch(进程存活看门狗) → logs/crash(日志/崩溃) → stop/uninstall(清理)。
 * 双通道: root/Shizuku 用 shell 命令(pm/am/ps)，无 root 用 Android 系统 API 降级
 * （PackageInstaller 安装/卸载、startActivity 启动、ActivityManager 进程监控）。
 */
object SandboxTool {

    /** 常见 activity 启动意图解析：默认用 launcher intent */
    private fun launchIntentFor(ctx: Context, pkg: String): Intent? =
        runCatching { ctx.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()

    private fun amStartCmd(pkg: String, activity: String?): String {
        val target = if (activity.isNullOrBlank()) pkg else "$pkg/$activity"
        return "am start -n $target 2>&1"
    }

    private fun runningProcessNames(ctx: Context): Set<String> = runCatching {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.runningAppProcesses?.mapNotNull { it.processName }?.toSet() ?: emptySet()
    }.getOrDefault(emptySet())

    val sandbox: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_sandbox",
            "【动态分析沙箱】安装→启动→看门狗→日志/崩溃→清理 的完整闭环（借鉴清风 apk_sandbox_test，无 root 可降级）。action=install 安装 APK(root pm install / 无 root PackageInstaller)；launch 启动应用(root am start 可指定 activity / 无 root launcher intent)；watch 进程存活看门狗(轮询 ps 或 ActivityManager)；logs 抓日志(root 全系统 logcat / 无 root 应用日志)；crash 崩溃收集；stop 停止(root am force-stop / 无 root killBackgroundProcesses)；uninstall 卸载。",
            "Dynamic analysis sandbox: install→launch→watchdog→logs/crash→cleanup loop (borrowed from QingFeng apk_sandbox_test, degrades without root). install(root pm install / PackageInstaller); launch(root am start with optional activity / launcher intent); watch process-alive polling (ps or ActivityManager); logs(root logcat / app logs); crash collection; stop(root am force-stop / killBackgroundProcesses); uninstall.",
            "dynamic", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("install | launch | watch | logs | crash | stop | uninstall",
                    "install", "launch", "watch", "logs", "crash", "stop", "uninstall")
                "apkPath" str "install: APK 文件绝对路径"
                "packageName" str "launch/watch/stop/uninstall: 应用包名"
                "activity" str "launch: 指定启动 Activity(可选，缺省用 launcher intent)"
                "duration" int "watch: 监控时长(秒，默认 10，最大 120)"
                "interval" int "watch: 轮询间隔(秒，默认 2，最小 1)"
                "lines" int "logs/crash: 抓取行数(默认 200)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            return runCatching {
                when (args.str("action").ifBlank { "install" }) {
                    "install" -> install(ctx, args)
                    "launch" -> launch(ctx, args)
                    "watch" -> watch(ctx, args)
                    "logs" -> logs(ctx, args)
                    "crash" -> crash(ctx, args)
                    "stop" -> stop(ctx, args)
                    "uninstall" -> uninstall(ctx, args)
                    else -> err("BAD_ACTION", "未知 action", "action", args.str("action"))
                }
            }.getOrElse { e ->
                err("SANDBOX_FAILED", "沙箱操作失败: ${e.message ?: e.javaClass.simpleName}", "action", args.str("action"))
            }
        }

        // ── install ──
        private fun install(ctx: ToolContext, args: JSONObject): JSONObject {
            val apkPath = args.str("apkPath")
            if (apkPath.isBlank()) return err("INVALID_ARGUMENT", "缺少 apkPath", "apkPath", "")
            val apk = File(apkPath)
            if (!apk.isFile) return err("FILE_NOT_FOUND", "APK 不存在: $apkPath", "apkPath", apkPath)
            // 通道1: root/Shizuku
            if (RootShell.isRootAvailable() || PermissionManager.isShizukuGranted()) {
                val r = PermissionManager.exec("pm install -r \"$apkPath\" 2>&1", timeoutSec = 120)
                val okFlag = r.stdout.contains("Success", true) || r.code == 0
                return ok(JSONObject()
                    .put("action", "install").put("mode", "privileged").put("success", okFlag)
                    .put("output", (r.stdout + "\n" + r.stderr).take(600))
                    .put("hint", if (okFlag) "安装成功" else "安装失败，详见 output"))
            }
            // 通道2: 无 root → PackageInstaller(系统 API, 会弹确认框)
            return runCatching {
                val pm = ctx.context.packageManager
                val installer = pm.packageInstaller
                val params = android.content.pm.PackageInstaller.SessionParams(
                    android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val sessionId = installer.createSession(params)
                val session = installer.openSession(sessionId)
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                }
                val pi = android.app.PendingIntent.getBroadcast(ctx.context, sessionId,
                    Intent(ctx.context, SandboxInstallReceiver::class.java).putExtra("packageName", ""), 0)
                session.commit(pi.intentSender)
                ok(JSONObject()
                    .put("action", "install").put("mode", "package-installer")
                    .put("sessionId", sessionId)
                    .put("hint", "已通过 PackageInstaller 提交安装（无 root 模式，请在弹出的系统界面确认）"))
            }.getOrElse { e -> err("INSTALL_FAILED", "PackageInstaller 安装失败: ${e.message}", "apkPath", apkPath) }
        }

        // ── launch ──
        private fun launch(ctx: ToolContext, args: JSONObject): JSONObject {
            val pkg = args.str("packageName")
            if (pkg.isBlank()) return err("INVALID_ARGUMENT", "缺少 packageName", "packageName", "")
            val activity = args.str("activity").ifBlank { null }
            // 通道1: root
            if (RootShell.isRootAvailable() || PermissionManager.isShizukuGranted()) {
                val r = PermissionManager.exec(amStartCmd(pkg, activity), timeoutSec = 20)
                return ok(JSONObject()
                    .put("action", "launch").put("mode", "privileged").put("packageName", pkg)
                    .put("activity", activity ?: "(launcher)")
                    .put("output", (r.stdout + "\n" + r.stderr).take(400)))
            }
            // 通道2: 无 root → launcher intent
            val intent = launchIntentFor(ctx.context, pkg)
                ?: return err("NO_LAUNCHER", "未找到 $pkg 的 launcher intent（无 root 且无法解析启动入口）", "packageName", pkg)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.context.startActivity(intent)
            return ok(JSONObject()
                .put("action", "launch").put("mode", "app").put("packageName", pkg)
                .put("activity", intent.component?.className ?: "(launcher)")
                .put("hint", "已通过 launcher intent 启动（无 root 模式）"))
        }

        // ── watch 看门狗 ──
        private fun watch(ctx: ToolContext, args: JSONObject): JSONObject {
            val pkg = args.str("packageName")
            if (pkg.isBlank()) return err("INVALID_ARGUMENT", "缺少 packageName", "packageName", "")
            val duration = args.intValue("duration", 10).coerceIn(1, 120)
            val interval = args.intValue("interval", 2).coerceIn(1, 30)
            val privileged = RootShell.isRootAvailable() || PermissionManager.isShizukuGranted()
            val samples = JSONArray()
            var aliveCount = 0
            val deadline = System.currentTimeMillis() + duration * 1000L
            var lastAlive = false
            while (System.currentTimeMillis() < deadline) {
                val alive = if (privileged) {
                    val r = PermissionManager.exec("ps -A 2>/dev/null | grep -c \"$pkg\"", timeoutSec = 10)
                    r.stdout.trim().toIntOrNull()?.let { it > 0 } ?: false
                } else {
                    runningProcessNames(ctx.context).any { it == pkg || it.startsWith("$pkg:") }
                }
                if (alive) aliveCount++
                samples.put(JSONObject().put("t", System.currentTimeMillis()).put("alive", alive))
                lastAlive = alive
                Thread.sleep(interval * 1000L)
            }
            val ratio = if (samples.length() > 0) (aliveCount * 100 / samples.length()) else 0
            return ok(JSONObject()
                .put("action", "watch").put("packageName", pkg)
                .put("mode", if (privileged) "privileged" else "app")
                .put("durationSec", duration).put("intervalSec", interval)
                .put("samples", samples.length()).put("aliveSamples", aliveCount)
                .put("aliveRatioPercent", ratio)
                .put("endedAlive", lastAlive)
                .put("hint", if (ratio >= 50) "进程持续存活(存活率 $ratio%)" else "进程频繁退出/未运行(存活率 $ratio%)，疑似崩溃或被杀"))
        }

        // ── logs ──
        private fun logs(ctx: ToolContext, args: JSONObject): JSONObject {
            val lines = args.intValue("lines", 200).coerceIn(10, 2000)
            val pkg = args.str("packageName").ifBlank { null }
            val privileged = RootShell.isRootAvailable() || PermissionManager.isShizukuGranted()
            if (privileged) {
                val filter = if (pkg != null) " --pid=\$(pidof $pkg 2>/dev/null)" else ""
                val r = PermissionManager.exec("logcat -d -t $lines$filter 2>&1", timeoutSec = 15)
                return ok(JSONObject()
                    .put("action", "logs").put("mode", "privileged").put("packageName", pkg ?: JSONObject.NULL)
                    .put("lines", r.stdout.lines().size)
                    .put("log", r.stdout.take(20000)))
            }
            // 无 root: 应用自身日志(AppLog 快照) + 提示
            val appLog = com.soreverse.mcp.core.AppLog.snapshot().takeLast(lines)
            return ok(JSONObject()
                .put("action", "logs").put("mode", "app")
                .put("packageName", pkg ?: JSONObject.NULL)
                .put("lines", appLog.size)
                .put("log", appLog.joinToString("\n").take(20000))
                .put("hint", "无 root 仅能看到本应用日志；全系统日志需 root/Shizuku 或 READ_LOGS(adb: pm grant)"))
        }

        // ── crash ──
        private fun crash(ctx: ToolContext, args: JSONObject): JSONObject {
            val lines = args.intValue("lines", 200).coerceIn(10, 2000)
            if (RootShell.isRootAvailable() || PermissionManager.isShizukuGranted()) {
                val r = PermissionManager.exec("logcat -d -t $lines -v brief 2>&1", timeoutSec = 15)
                val crashKeys = listOf("FATAL EXCEPTION", "ANR in ", "Process: ", "SIGSEGV", "SIGABRT", "*** *** ***", "AndroidRuntime")
                val hits = r.stdout.lines().filter { line -> crashKeys.any { line.contains(it, ignoreCase = true) } }
                return ok(JSONObject()
                    .put("action", "crash").put("mode", "privileged")
                    .put("crashCount", hits.size)
                    .put("crashes", JSONArray(hits.take(50)))
                    .put("hint", if (hits.isEmpty()) "未检测到崩溃/ANR" else "检测到 ${hits.size} 条崩溃/ANR 相关日志"))
            }
            return ok(JSONObject()
                .put("action", "crash").put("mode", "app").put("crashCount", 0)
                .put("hint", "无 root 无法扫描系统崩溃日志；可用 root/Shizuku 或 adb grant READ_LOGS"))
        }

        // ── stop ──
        private fun stop(ctx: ToolContext, args: JSONObject): JSONObject {
            val pkg = args.str("packageName")
            if (pkg.isBlank()) return err("INVALID_ARGUMENT", "缺少 packageName", "packageName", "")
            if (RootShell.isRootAvailable() || PermissionManager.isShizukuGranted()) {
                val r = PermissionManager.exec("am force-stop $pkg 2>&1", timeoutSec = 15)
                return ok(JSONObject()
                    .put("action", "stop").put("mode", "privileged").put("packageName", pkg)
                    .put("output", (r.stdout + "\n" + r.stderr).take(300)))
            }
            return runCatching {
                val am = ctx.context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.killBackgroundProcesses(pkg)
                ok(JSONObject().put("action", "stop").put("mode", "app").put("packageName", pkg)
                    .put("note", "已尝试 killBackgroundProcesses（无 root 无法真正 force-stop）"))
            }.getOrElse { e -> err("STOP_FAILED", "停止失败: ${e.message}", "packageName", pkg) }
        }

        // ── uninstall ──
        private fun uninstall(ctx: ToolContext, args: JSONObject): JSONObject {
            val pkg = args.str("packageName")
            if (pkg.isBlank()) return err("INVALID_ARGUMENT", "缺少 packageName", "packageName", "")
            if (RootShell.isRootAvailable() || PermissionManager.isShizukuGranted()) {
                val r = PermissionManager.exec("pm uninstall $pkg 2>&1", timeoutSec = 30)
                return ok(JSONObject()
                    .put("action", "uninstall").put("mode", "privileged").put("packageName", pkg)
                    .put("success", r.stdout.contains("Success", true) || r.code == 0)
                    .put("output", (r.stdout + "\n" + r.stderr).take(300)))
            }
            return runCatching {
                val pm = ctx.context.packageManager
                val pi = android.app.PendingIntent.getBroadcast(ctx.context, 0,
                    Intent(ctx.context, SandboxInstallReceiver::class.java), 0)
                pm.packageInstaller.uninstall(pkg, pi.intentSender)
                ok(JSONObject().put("action", "uninstall").put("mode", "package-installer")
                    .put("packageName", pkg)
                    .put("hint", "已提交卸载请求（无 root 模式，请在弹出的系统界面确认）"))
            }.getOrElse { e -> err("UNINSTALL_FAILED", "卸载失败: ${e.message}", "packageName", pkg) }
        }
    }

    val ALL: List<ToolHandler> = listOf(sandbox)
}

/** PackageInstaller 回调接收器（无 root 安装/卸载结果）。 */
class SandboxInstallReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val status = intent?.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999)
        val pkg = intent?.getStringExtra(android.content.pm.PackageInstaller.EXTRA_PACKAGE_NAME) ?: ""
        com.soreverse.mcp.core.AppLog.i("SandboxInstallReceiver: pkg=$pkg status=$status")
    }
}
