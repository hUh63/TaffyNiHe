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
    fun isDhizukuAvailable(): Boolean = dhizukuReady    /** 是否安装了 Shizuku 应用（未安装时引导下载）。 */
    fun isShizukuAppInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true }
            .getOrDefault(false)

    /** 是否安装了 Dhizuku 应用。 */
    fun isDhizukuAppInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo("com.rikka.dhizuku", 0); true }
            .getOrDefault(false)

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
        if (!isShizukuGranted()) return null
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
            }.getOrNull() ?: return null
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (binder != null && binder.pingBinder()) {
                        result = IShizukuService.Stub.asInterface(binder)
                    }
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    shizukuService = null
                }
            }
            runCatching { Shizuku.bindUserService(args, conn) }.onFailure { latch.countDown() }
            runCatching { latch.await(3, TimeUnit.SECONDS) }
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
