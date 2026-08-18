package com.soreverse.mcp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.soreverse.mcp.core.AppLog
import com.soreverse.mcp.core.IntegrityGuard
import com.soreverse.mcp.core.SettingsStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != "android.intent.action.QUICKBOOT_POWERON" &&
            intent?.action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return
        if (!IntegrityGuard.isTrusted(context.applicationContext)) {
            AppLog.e("Boot autostart blocked by integrity guard")
            return
        }
        // Logcat「开机恢复」：恢复上次的后台采集（独立于 bootAutoStart，采集进程不需要前台服务）
        restoreLogcatCapture(context.applicationContext)

        val settings = SettingsStore(context)
        if (!settings.bootAutoStart) {
            AppLog.i("Boot completed: bootAutoStart is off, skipping autostart")
            return
        }
        try {
            McpForegroundService.start(context)
            AppLog.i("Boot completed: started McpForegroundService (bootAutoStart=on)")
        } catch (e: Exception) {
            AppLog.e("Boot autostart failed", e)
        }
    }

    private fun restoreLogcatCapture(context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences("logcat_settings", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("boot_restore", false)) {
                AppLog.i("BootReceiver: boot_restore off, skip logcat capture restore")
                return
            }
            Thread {
                runCatching {
                    val ctx = com.soreverse.mcp.mcp.ToolContext(
                        context,
                        com.soreverse.mcp.core.SettingsStore(context),
                        com.soreverse.mcp.core.EngineProvider.get(context),
                    )
                    val res = com.soreverse.mcp.mcp.LogcatTools.capture.handle(
                        ctx, org.json.JSONObject().put("action", "start"),
                    )
                    AppLog.i("BootReceiver: logcat capture restored: ${res.optJSONObject("data")?.optString("file", "")}")
                }.onFailure { AppLog.e("BootReceiver: logcat capture restore failed", it) }
            }.start()
        }
    }
}
