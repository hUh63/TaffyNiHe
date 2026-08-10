package com.soreverse.mcp.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * 「全部文件访问」权限（MANAGE_EXTERNAL_STORAGE / 所有文件访问权限）辅助工具。
 *
 * 供设置页「服务配置」区块的"全部文件访问"开关使用：
 *  - [hasAllFilesAccess]：实时检查系统权限是否已授予（Android 10 及以下恒为 true）；
 *  - [allFilesAccessIntent]：跳转系统「所有文件访问权限」授权页的 Intent；
 *  - [ensureAllFilesAccess]：检查权限，未授予时回调 [onMissing]（UI 在此弹出提示弹窗，
 *    引导用户点击"去授权"并跳转 [allFilesAccessIntent]）。
 *
 * 注意：MANAGE_EXTERNAL_STORAGE 属于特殊权限，不能走运行时权限弹窗，
 * 只能由系统「所有文件访问」设置页授权，因此 UI 需要自定义弹窗提示。
 */
object StoragePermissionHelper {

    /** 是否已授予「所有文件访问」权限。 */
    fun hasAllFilesAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return Environment.isExternalStorageManager()
    }

    /** 跳转到本应用「所有文件访问」授权页的 Intent。 */
    fun allFilesAccessIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )

    /**
     * 检查权限：已授予则返回 true；未授予则回调 [onMissing] 并返回 false。
     * UI 在 onMissing 中弹出提示弹窗（"需要授予所有文件访问权限才能访问 /sdcard"），
     * 用户确认后 startActivity(allFilesAccessIntent(context))。
     */
    fun ensureAllFilesAccess(context: Context, onMissing: () -> Unit): Boolean {
        if (hasAllFilesAccess(context)) return true
        onMissing()
        return false
    }
}
