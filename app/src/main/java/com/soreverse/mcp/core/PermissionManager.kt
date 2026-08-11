package com.soreverse.mcp.core

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import rikka.dhizuku.Dhizuku
import rikka.shizuku.Shizuku

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

    /** 初始化：注册 Shizuku binder 监听、初始化 Dhizuku。在 Application.onCreate 调用一次。 */
    fun init(context: Context) {
        // Shizuku
        runCatching {
            shizukuBinderAlive = Shizuku.pingBinder()
            Shizuku.addBinderReceivedListenerSticky { shizukuBinderAlive = true }
            Shizuku.addBinderDeadListener { shizukuBinderAlive = false }
        }
        // Dhizuku
        runCatching {
            Dhizuku.init(context)
            dhizukuReady = Dhizuku.isOwner()
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

    /** 是否安装了 Shizuku 应用（未安装时引导下载）。 */
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

    private fun execShizuku(command: String, timeoutSec: Long): RootShell.Result = runCatching {
        val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        val code = process.waitFor()
        RootShell.Result(code, out.trim(), err.trim())
    }.getOrElse { RootShell.Result(-1, "", it.message ?: "Shizuku exec failed") }

    private fun execDhizuku(command: String, timeoutSec: Long): RootShell.Result =
        // Dhizuku 官方 API 仅提供 init/isOwner/getBinder，不提供进程执行；
        // 激活 Dhizuku 本身需要 root，因此 Dhizuku 通道复用 root 执行能力。
        RootShell.exec(command, timeoutSec)

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
