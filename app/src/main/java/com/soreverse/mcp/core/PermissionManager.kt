package com.soreverse.mcp.core

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.rosan.dhizuku.api.Dhizuku
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 塔菲逆核: 高级权限统一管理。
 *
 * 三个特权通道（按能力从低到高）：
 *  - Shizuku : adb 级 shell 权限（uid 2000），无需 root；需安装 Shizuku 应用并授权
 *  - Root    : uid 0，Magisk/KernelSU 等 root 方案授权
 *  - Dhizuku : 设备所有者（device owner）权限，比 root 更高阶，可做设备级策略；
 *              需 Dhizuku 应用 + root 激活
 *
 * 设计：各功能（eDBG / eBPF / logcat 增强等）统一通过 [exec] 走可用通道，
 * 优先级 root → shizuku → dhizuku。Dhizuku 主要用于设备所有者级操作与状态展示。
 */
object PermissionManager {

    enum class Channel { NONE, SHIZUKU, ROOT, DHIZUKU }

    @Volatile private var shizukuBinderAlive = false
    @Volatile private var dhizukuReady = false

    /** Application context（UserService 绑定需要）。init 时保存。 */
    @Volatile private var appContext: Context? = null

    /** 已绑定的 Shizuku UserService（shell 权限执行通道）。 */
    @Volatile private var shizukuService: IShizukuService? = null

    /** UserService 绑定/启动失败的诊断信息（崩溃、版本不兼容等）。 */
    @Volatile private var shizukuServiceError: String = ""

    /** UserService 绑定失败后的冷却截止时间（毫秒）：避免反复触发 server 启动崩溃循环。 */
    @Volatile private var shizukuCooldownUntil: Long = 0L

    /** 最近一次 UserService 启动失败的时间（毫秒）。 */
    @Volatile private var shizukuLastFailAt: Long = 0L

    /** UserService 绑定超时/失败时记录原因；成功后清空。 */
    fun lastShizukuServiceError(): String = shizukuServiceError

    /** Shizuku server 版本号（0 = 未连接）。 */
    fun shizukuVersion(): Int = runCatching {
        if (Shizuku.pingBinder()) Shizuku.getVersion() else 0
    }.getOrDefault(0)

    /** 是否安装官方 Shizuku（moe.shizuku.privileged.api）。 */
    fun isShizukuAppInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true }
            .getOrDefault(false)

    /** 是否安装 Sizuku 等 af.shizuku 包名分支（Shizuku 社区修改版）。 */
    fun isSizukuInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo("af.shizuku.privileged.api", 0); true }
            .getOrDefault(false)

    /** Shizuku 通道诊断：服务/授权/版本/分支/UserService 错误。 */
    fun shizukuDiagnosis(context: Context): String {
        val sb = StringBuilder()
        if (!isShizukuServiceRunning()) sb.append("服务未运行")
        else {
            sb.append("服务运行中")
            if (isShizukuGranted()) sb.append("·已授权") else sb.append("·未授权")
            val ver = shizukuVersion()
            if (ver > 0) sb.append("·v").append(ver)
            if (isSizukuInstalled(context)) sb.append("·Sizuku(分支)")
            // 混装检测：官方 Shizuku + Sizuku 同时安装是 UserService 崩溃的常见根因
            val official = isShizukuAppInstalled(context)
            val sizuku = isSizukuInstalled(context)
            if (official && sizuku) sb.append("·⚠混装(官方+Sizuku)，UserService 启动可能冲突")
        }
        if (shizukuServiceError.isNotBlank()) sb.append("\nUserService: ").append(shizukuServiceError)
        return sb.toString()
    }

    /** 初始化：注册 Shizuku binder 监听、初始化 Dhizuku。在 Application.onCreate 调用一次。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        // Shizuku
        runCatching {
            shizukuBinderAlive = Shizuku.pingBinder()
            Shizuku.addBinderReceivedListenerSticky { shizukuBinderAlive = true }
            Shizuku.addBinderDeadListener { shizukuBinderAlive = false }
        }
        // Dhizuku（包名 com.rosan.dhizuku，iamr0s 维护的活跃分支）
        runCatching {
            dhizukuReady = Dhizuku.init(context) && Dhizuku.isPermissionGranted()
        }
    }

    // ── 通道状态 ──

    /** Shizuku 服务进程是否在运行（binder 存活）。 */
    fun isShizukuServiceRunning(): Boolean =
        runCatching { shizukuBinderAlive && Shizuku.pingBinder() }.getOrDefault(false)

    /** Shizuku 是否已授权给本应用（权限已授予，可执行 shell）。 */
    fun isShizukuGranted(): Boolean =
        runCatching {
            shizukuBinderAlive && Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    /** Root 是否可用。 */
    fun isRootAvailable(): Boolean = RootShell.isRootAvailable()

    /** Dhizuku 是否已激活（本应用为设备所有者代理）。 */
    fun isDhizukuAvailable(): Boolean = dhizukuReady

    /** 是否安装 Dhizuku 应用。 */
    fun isDhizukuAppInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo("com.rikka.dhizuku", 0); true }
            .getOrDefault(false)

    /** 是否已授予 READ_LOGS（adb 授予: pm grant <pkg> android.permission.READ_LOGS）。
     *  授予后 logcat 可读全系统日志，无需 Root/Shizuku（Android 8.0+ 生效）。 */
    fun hasReadLogs(context: Context): Boolean =
        runCatching {
            context.checkSelfPermission(android.Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    /** READ_LOGS 检测（无 context 版本，使用 init 时保存的 applicationContext）。 */
    fun hasReadLogs(): Boolean {
        val ctx = appContext ?: return false
        return hasReadLogs(ctx)
    }

    /** 当前可用最高通道。 */
    fun bestChannel(): Channel = when {
        isRootAvailable() -> Channel.ROOT
        isShizukuGranted() -> Channel.SHIZUKU
        isDhizukuAvailable() -> Channel.DHIZUKU
        else -> Channel.NONE
    }

    // ── 统一执行 ──

    /** 通过可用特权通道执行一条 shell 命令。优先级 root → shizuku → dhizuku。 */
    fun exec(command: String, timeoutSec: Long = 30): RootShell.Result {
        if (isRootAvailable()) return RootShell.exec(command, timeoutSec)
        if (isShizukuGranted()) return execShizuku(command, timeoutSec)
        if (dhizukuReady) return execDhizuku(command, timeoutSec)
        return RootShell.Result(-1, "", "No privileged channel available (root / shizuku / dhizuku)")
    }

    /** 指定通道执行。 */
    fun execVia(channel: Channel, command: String, timeoutSec: Long = 30): RootShell.Result = when (channel) {
        Channel.ROOT -> RootShell.exec(command, timeoutSec)
        Channel.SHIZUKU -> execShizuku(command, timeoutSec)
        Channel.DHIZUKU -> execDhizuku(command, timeoutSec)
        Channel.NONE -> RootShell.Result(-1, "", "Channel not selected")
    }

    /**
     * 通过特权通道启动一个持续运行的命令流（如 logcat 实时流），返回可读的 Process。
     * 优先级 root → shizuku → dhizuku；无可用通道返回 null（调用方应降级为普通进程）。
     */
    fun startPrivilegedStream(command: String, args: List<String>): Process? {
        val full = (listOf(command) + args).joinToString(" ")
        if (isRootAvailable()) {
            return runCatching {
                ProcessBuilder("su", "-c", full).redirectErrorStream(true).start()
            }.getOrNull()
        }
        if (isShizukuGranted()) {
            // Shizuku 13.x 已移除 Shizuku.newProcess，必须通过 bindUserService + UserService
            // 以 shell 权限启动命令（LogFox 同款方案）
            val service = ensureShizukuService() ?: return null
            return runCatching {
                val id = service.execute(full)
                if (id < 0) null else ShizukuProcess(service, id)
            }.getOrNull()
        }
        if (dhizukuReady) {
            return runCatching {
                Dhizuku.newProcess((arrayOf(command) + args.toTypedArray()), null, null)
            }.getOrNull()
        }
        return null
    }

    private fun execShizuku(command: String, timeoutSec: Long): RootShell.Result {
        // Shizuku 13.1.5 已移除 Shizuku.newProcess（官方推荐 UserService 模式），
        // 通过 bindUserService 拿到 shell 权限通道执行命令。
        val service = ensureShizukuService()
            ?: return RootShell.Result(-1, "", "Shizuku UserService not available")
        return runCatching {
            val r = service.executeNow("sh -c \"$command\"")
            val code = r.getOrNull(0)?.toIntOrNull() ?: -1
            RootShell.Result(code, r.getOrNull(1)?.trim() ?: "", r.getOrNull(2)?.trim() ?: "")
        }.getOrElse { RootShell.Result(-1, "", it.message ?: "Shizuku exec failed") }
    }

    private fun execDhizuku(command: String, timeoutSec: Long): RootShell.Result = runCatching {
        val proc = Dhizuku.newProcess(arrayOf("sh", "-c", command), null, null)
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        val code = proc.waitFor()
        RootShell.Result(code, out.trim(), err.trim())
    }.getOrElse { RootShell.Result(-1, "", it.message ?: "Dhizuku exec failed") }

    // ── Shizuku UserService 通道 ──

    /**
     * 绑定(或复用) Shizuku UserService。UserService 进程由 Shizuku 以 **shell 权限(uid 2000)**
     * 启动, 内部命令可读取全系统 logcat —— 这是无 root 时 Logcat 查看器显示日志的关键。
     * 同步阻塞等待绑定结果(限时 3 秒), 返回 null 表示通道不可用。
     */
    private fun ensureShizukuService(): IShizukuService? {
        if (!isShizukuGranted()) {
            shizukuServiceError = if (isShizukuServiceRunning()) "已连接但未授权" else "Shizuku 服务未运行"
            return null
        }
        // 冷却期：UserService 崩溃(如 Shizuku/Sizuku 混装)时避免反复触发 server 启动
        val now = System.currentTimeMillis()
        if (now < shizukuCooldownUntil) {
            // 缓存的服务若仍活着则直接复用（冷却只阻止重新 bind，不影响已建立通道）
            shizukuService?.let { svc ->
                if (svc.asBinder().pingBinder()) return svc
                shizukuService = null
            }
            shizukuServiceError = "UserService 启动失败进入冷却期（${(shizukuCooldownUntil - now) / 1000}s 后重试），" +
                "请检查是否混装了官方 Shizuku 与 Sizuku 分支"
            return null
        }
        shizukuService?.let { svc ->
            if (svc.asBinder().pingBinder()) return svc
            shizukuService = null
        }
        val context = appContext ?: return null
        synchronized(this) {
            shizukuService?.let { return it }
            var result: IShizukuService? = null
            val latch = CountDownLatch(1)
            val args = runCatching {
                Shizuku.UserServiceArgs(ComponentName(context, ShizukuUserService::class.java))
                    .daemon(false)
                    .processNameSuffix("service")
                    .debuggable(com.soreverse.mcp.BuildConfig.DEBUG)
                    .version(context.packageManager.getPackageInfo(context.packageName, 0).versionCode)
                    .tag("taffy")
            }.getOrNull()
            if (args == null) {
                shizukuServiceError = "UserServiceArgs 构造失败"
                return null
            }
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (binder != null && binder.pingBinder()) {
                        result = IShizukuService.Stub.asInterface(binder)
                        shizukuServiceError = ""
                    } else {
                        shizukuServiceError = "连接回调无 binder（UserService 可能崩溃）"
                        shizukuLastFailAt = System.currentTimeMillis()
                        shizukuCooldownUntil = shizukuLastFailAt + 30_000
                    }
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    shizukuService = null
                    shizukuServiceError = "UserService 断开"
                    shizukuLastFailAt = System.currentTimeMillis()
                    shizukuCooldownUntil = shizukuLastFailAt + 30_000
                }
            }
            runCatching { Shizuku.bindUserService(args, conn) }.onFailure {
                shizukuServiceError = "bindUserService 异常: ${it.message}"
                shizukuLastFailAt = System.currentTimeMillis()
                shizukuCooldownUntil = shizukuLastFailAt + 30_000
                latch.countDown()
            }
            // 注意: 本函数必须在 IO 线程调用（await 阻塞），严禁主线程调用
            runCatching { latch.await(2, TimeUnit.SECONDS) }
            if (result == null && shizukuServiceError.isBlank()) {
                // 超时：UserService 进程可能崩溃（如 Shizuku 分支 starter 缺失）
                val ver = shizukuVersion()
                shizukuServiceError = if (ver in 1 until 13) {
                    "Shizuku 版本过旧(v$ver)，UserService 需要 v13+，请升级 Shizuku"
                } else {
                    "UserService 启动超时（进程可能崩溃：Shizuku/Sizuku 版本不兼容或需重装）"
                }
                shizukuLastFailAt = System.currentTimeMillis()
                shizukuCooldownUntil = shizukuLastFailAt + 30_000
            }
            shizukuService = result
            return result
        }
    }

    /** Shizuku UserService 远程进程的本地 Process 包装（stdout/stderr 走管道）。 */
    class ShizukuProcess(
        private val service: IShizukuService,
        private val processId: Long,
    ) : Process() {
        private val out: InputStream by lazy { ParcelFileDescriptor.AutoCloseInputStream(service.processOutput(processId)) }
        private val err: InputStream by lazy { ParcelFileDescriptor.AutoCloseInputStream(service.processError(processId)) }
        private val inp: OutputStream by lazy { ParcelFileDescriptor.AutoCloseOutputStream(service.processInput(processId)) }

        override fun getOutputStream(): OutputStream = inp
        override fun getInputStream(): InputStream = out
        override fun getErrorStream(): InputStream = err
        override fun waitFor(): Int = -1
        override fun exitValue(): Int = -1

        override fun destroy() {
            runCatching { service.destroyProcess(processId) }
        }
    }

    // ── 自检 ──

    /** 各通道自检：返回每个通道的 uid / whoami 探测结果。 */
    fun selfTest(): JSONObject {
        val out = JSONObject()
        out.put("root", JSONObject()
            .put("available", isRootAvailable())
            .put("probe", if (isRootAvailable()) RootShell.exec("id").stdout else ""))
        out.put("shizuku", JSONObject()
            .put("serviceRunning", isShizukuServiceRunning())
            .put("granted", isShizukuGranted())
            .put("probe", if (isShizukuGranted()) execVia(Channel.SHIZUKU, "id").stdout else ""))
        out.put("dhizuku", JSONObject()
            .put("available", isDhizukuAvailable())
            .put("probe", if (isDhizukuAvailable()) execVia(Channel.DHIZUKU, "id").stdout else ""))
        out.put("bestChannel", bestChannel().name)
        return out
    }

    /** 使 root 检测缓存失效（授权后立即重新检测）。 */
    fun invalidateCaches() {
        RootShell.invalidateRootCache()
    }
}
