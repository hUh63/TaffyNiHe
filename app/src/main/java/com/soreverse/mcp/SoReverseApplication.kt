package com.soreverse.mcp

import android.app.Application
import com.soreverse.mcp.core.AppLog
import com.soreverse.mcp.core.CrashReporter
import com.soreverse.mcp.core.IntegrityGuard
import com.soreverse.mcp.core.PermissionManager
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.core.ToolStats
import com.soreverse.mcp.nativecore.RizinNativeEngine

class SoReverseApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (CrashReporter.isCrashProcess()) return
        AppLog.init(this)
        CrashReporter.install(this)
        val settings = SettingsStore(this)
        ToolStats.setPersistEnabled(settings.toolStatsPersist)
        ToolStats.attachContext(this)
        RizinNativeEngine.configureGhidra(this)
        PermissionManager.init(this)
        val integrity = IntegrityGuard.verify(this)
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
