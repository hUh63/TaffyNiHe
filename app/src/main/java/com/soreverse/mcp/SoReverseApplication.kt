package com.soreverse.mcp

import com.soreverse.mcp.core.AppLog
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
    }
}
