package com.soreverse.mcp.core

import android.content.Context
import java.io.File

/**
 * 安全修复: 路径穿越防护工具。
 *
 * 验证文件路径参数不会通过 ../ 等方式逃逸出允许的工作区，
 * 防止 MCP 工具读取/写入设备上的任意文件（如 /data/system/password.key）。
 *
 * 允许范围由 [WorkspacePolicy] 统一决定：
 *  - 未开隧道：应用私有目录 + 系统临时目录 + 设置页服务配置的工作目录；
 *  - 开隧道后：额外放行 /sdcard 与 /storage/emulated/0。
 */
object PathSafety {

    /** 允许的根目录列表（路径参数必须解析到其中之一）。 */
    fun appDataRoots(context: Context): List<String> = WorkspacePolicy.allowedRoots(context)

    /**
     * 验证路径是否安全（不包含路径穿越，且在允许的目录范围内）。
     *
     * @param path 用户提供的文件路径
     * @param workDirPath 可选的工作目录路径，如果提供则也允许该目录下的文件
     * @return true 如果路径安全
     */
    fun isSafe(context: Context, path: String, workDirPath: String? = null): Boolean {
        if (path.isBlank()) return false
        return WorkspacePolicy.validatePath(context, path, workDirPath) == null
    }

    /**
     * 验证路径，如果不安全则返回错误消息。
     *
     * @param path 用户提供的文件路径
     * @param workDirPath 可选的工作目录路径
     * @return null 如果路径安全，否则返回错误消息字符串
     */
    fun validate(context: Context, path: String, workDirPath: String? = null): String? =
        WorkspacePolicy.validatePath(context, path, workDirPath)
}
