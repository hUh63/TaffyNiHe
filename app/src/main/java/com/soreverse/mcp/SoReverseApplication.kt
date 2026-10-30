package com.soreverse.mcp

import com.soreverse.mcp.core.AppLog
import com.soreverse.mcp.core.CompromisedCaBlocklist
import com.soreverse.mcp.core.CrashReporter
import com.soreverse.mcp.core.IntegrityGuard
import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.core.ToolStats
import com.soreverse.mcp.nativecore.RizinNativeEngine
import top.wkbin.taixu.TaiXuApplication

class SoReverseApplication : TaiXuApplication() {
    override fun onCreate() {
        // 太墟装配（Koin + Room + PRoot 运行时 + 前台服务 + 工作流调度）与塔菲逆向内核共用同一个
        // Application：先让太墟完成装配，再跑塔菲自己的初始化。
        // 太墟装配异常一律隔离——任一模块初始化失败都不能阻断塔菲的逆向工具链启动。
        runCatching { super.onCreate() }
            .onFailure { android.util.Log.e("SoReverseApplication", "Taixu bootstrap failed", it) }
        if (CrashReporter.isCrashProcess()) return
        AppLog.init(this)
        // TLS 拉黑补充表：泄漏公告按天走，内置表只能随发版更新。应用私有目录下放一份
        // tls-blocklist-extra.json（格式见 CompromisedCaBlocklist.loadExtra）就能补，
        // 读失败只是退化成只有内置表，不影响任何连接。
        runCatching {
            val extra = CompromisedCaBlocklist.loadExtra(java.io.File(filesDir, "tls-blocklist-extra.json"))
            if (extra > 0) AppLog.i("TLS 补充黑名单已加载: $extra 个公钥")
        }
        CrashReporter.install(this)
        val settings = SettingsStore(this)
        ToolStats.setPersistEnabled(settings.toolStatsPersist)
        ToolStats.attachContext(this)
        RizinNativeEngine.configureGhidra(this)
        PermissionManager.init(this)
        val integrity = IntegrityGuard.verify(this)
        // v1.3.55: 深度校验（ZIP 结构 + classes.dex CRC32 + v2/v3 真实验签 + 全量内容摘要）
        // 很重，不能放在启动主线程上；交给后台线程低频执行（首次 10 秒后，之后 2-5 分钟一次）。
        runCatching { IntegrityGuard.scheduleDeepRecheck(this) }
        if (!integrity.trusted) AppLog.e("Integrity check failed: ${integrity.reason}; expected=${integrity.expected}; actual=${integrity.actual.joinToString()}")
        AppLog.i("SOMCP initialized (toolStatsPersist=${settings.toolStatsPersist})")
        // 启动信息写入应用日志：无系统权限时 Logcat 查看器显示应用日志兜底，保证有内容可看
        runCatching {
            val ver = packageManager.getPackageInfo(packageName, 0)
            AppLog.i("版本: ${ver.versionName} (${ver.versionCode})")
        }
        AppLog.i("权限状态: Root=${if (PermissionManager.isRootAvailable()) "可用" else "不可用"} · Shizuku=${if (PermissionManager.isShizukuGranted()) "已授权" else "未授权"} · READ_LOGS=${if (PermissionManager.hasReadLogs(this)) "已授予" else "未授予"}")
        AppLog.i("Logcat 查看器: 无特权时显示应用日志；全系统日志需 Root/Shizuku 或 adb 授权 READ_LOGS")
        // 引擎资源回收点：壳跑在它自己的 Activity（top.wkbin.taixu.MainActivity）里，
        // 塔菲 MainActivity 启动壳之后就已经 finish —— 旧实现把释放写在塔菲 MainActivity.onDestroy，
        // 壳模式下那条路径永远不会执行，SO 副本 / SQLite 句柄 / 模拟器与 Frida 会话会一直被持有到进程被杀。
        runCatching { registerActivityLifecycleCallbacks(EngineReleaseCallbacks()) }
            .onFailure { AppLog.w("注册引擎释放回调失败: ${it.message}") }
    }
}

/**
 * 「最后一条 Activity 结束」时释放引擎持有的进程级资源。
 * 只在真的退出（isFinishing 且非配置变更）时释放；按 Home 键进后台不释放，回来还能接着用。
 */
private class EngineReleaseCallbacks : android.app.Application.ActivityLifecycleCallbacks {
    private var started = 0

    override fun onActivityStarted(activity: android.app.Activity) {
        started++
    }

    override fun onActivityStopped(activity: android.app.Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0 && activity.isFinishing && !activity.isChangingConfigurations) {
            // MCP 前台服务还在跑时不释放：用户退出界面但希望服务继续对外提供工具能力，
            // 一关引擎后续工具调用就全失败。（旧释放点在塔菲 MainActivity，壳模式下压根走不到，
            // 所以这个场景此前没被暴露出来。）
            if (runCatching { com.soreverse.mcp.service.McpForegroundService.isRunning() }.getOrDefault(false)) return
            runCatching { com.soreverse.mcp.core.EngineProvider.close(activity.applicationContext) }
        }
    }

    override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
    override fun onActivityResumed(activity: android.app.Activity) = Unit
    override fun onActivityPaused(activity: android.app.Activity) = Unit
    override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
    override fun onActivityDestroyed(activity: android.app.Activity) = Unit
}
