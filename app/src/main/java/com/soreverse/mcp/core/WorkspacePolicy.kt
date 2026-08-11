package com.soreverse.mcp.core

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 统一 MCP 工具工作区策略。
 *
 * 规则：
 *  - 未开启任何隧道（Cloudflare / Bore 均未运行）：MCP 工具的本地文件路径访问
 *    被限制在「设置页服务配置下的工作目录」([SettingsStore.defaultWorkDirPath]，
 *    优先取 SAF treeUri 解析出的真实路径) + 应用私有目录 + 系统临时目录。
 *  - 只要开启任一隧道：额外放行 /sdcard 与 /storage/emulated/0（全盘共享存储）。
 *
 * 本策略在 [mcp.McpHttpServer.callToolPayload] 统一预检所有工具的路径类参数，
 * 拦截落在允许根之外的路径，返回 PATH_OUTSIDE_WORKSPACE 工具错误。
 */
object WorkspacePolicy {

    /**
     * MCP 工具参数中会被当作「本地文件系统路径」校验的键。
     * 与各工具 schema 中路径参数的命名保持一致（path / filePath / soPath / workDir 等）。
     * 注意：url（http(s) 下载源）、content/hex/base64（内容体）、name/symbolName（标识符）
     * 等非路径键不在其中，不会被误判。
     */
    val PATH_KEYS: Set<String> = setOf(
        "path", "filePath", "soPath", "inputPath", "outputPath", "outPath",
        "workDir", "workDirPath", "dir", "dirPath", "sourcePath", "destPath",
        "targetPath", "archivePath", "extractDir", "outputDir", "inputFile",
        "outputFile", "sourceFile", "destFile", "smaliDir", "localPath",
        "srcPath", "dstPath", "inFile", "outFile",
    )

    /** 是否已有任一隧道在运行（Cloudflare 隧道 或 Bore 隧道）。 */
    fun isTunnelActive(context: Context): Boolean {
        val cf = CloudflareTunnelManager.activeInstance
        if (cf != null && cf.status().state == CloudflareTunnelManager.State.RUNNING) return true
        return runCatching { BoreTunnelService.isRunning(context.applicationContext) }.getOrDefault(false)
    }

    /** 反射获取应用级 Context（供无 Context 参数的旧式调用方使用），失败返回 null。 */
    fun appContext(): Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication").invoke(null) as? Context
    }.getOrNull()

    /**
     * 设置页服务配置下的工作目录路径。
     *  - 已通过 SAF 选择目录（treeUri）：解析其真实路径；
     *  - 未选择目录：仅当用户显式启用默认提示目录（[SettingsStore.useDefaultWorkDir]）时才返回
     *    [SettingsStore.defaultWorkDirPath]（仅提示性路径），否则返回 null —— 未选择工作目录即无工作区。
     */
    fun workDirPath(context: Context): String? {
        val settings = SettingsStore(context.applicationContext)
        val fromTree = runCatching { EngineProvider.resolveWorkDirPath(context, settings.treeUri) }.getOrNull()
        if (!fromTree.isNullOrBlank()) return fromTree
        return if (settings.useDefaultWorkDir) settings.defaultWorkDirPath.takeIf { it.isNotBlank() } else null
    }

    /**
     * 当前允许的根目录列表。
     *  - 始终允许：应用私有目录（dataDir/cacheDir/filesDir）、/data/local/tmp、系统临时目录；
     *  - 工作目录：无隧道时唯一的文件工作区根；
     *  - 隧道开启时：追加 /storage/emulated/0、/sdcard。
     */
    fun allowedRoots(context: Context): List<String> {
        val roots = mutableListOf<String>()
        runCatching {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? Context
            if (app != null) {
                roots += app.dataDir.absolutePath
                roots += app.cacheDir.absolutePath
                roots += app.filesDir.absolutePath
                roots += "/data/user/0/${app.packageName}"
                roots += "/data/data/${app.packageName}"
            }
        }
        roots += "/data/local/tmp"
        System.getProperty("java.io.tmpdir", "/tmp")?.let { roots += it }
        workDirPath(context)?.let {
            runCatching { roots += File(it).canonicalPath }
        }
        if (isTunnelActive(context)) {
            roots += "/storage/emulated/0"
            roots += "/sdcard"
        }
        return roots
    }

    /**
     * 校验单个路径是否落在允许的工作区内。
     * @param path 工具传入的路径（可为绝对路径，或相对于工作目录的路径）
     * @param baseDir 用于解析相对路径的基准目录（工作目录）
     * @return null 表示允许；否则返回错误消息
     */
    fun validatePath(context: Context, path: String, baseDir: String? = null): String? {
        if (path.isBlank()) return "Path is blank"
        if (path.contains("../") || path.contains("..\\") || path.contains("/..")) {
            return "Path contains directory traversal sequence (../): $path"
        }
        val resolved = if (path.startsWith("/")) path
        else {
            val base = baseDir ?: workDirPath(context)
            if (base.isNullOrBlank()) return "Path is relative but no work directory is configured: $path"
            File(base, path).path
        }
        val canonical = runCatching { File(resolved).canonicalPath }.getOrNull()
            ?: return "Cannot resolve path: $path"
        val roots = allowedRoots(context)
        val inside = roots.any { root ->
            canonical == root || canonical.startsWith("$root/")
        }
        if (!inside) {
            return "Path is outside the allowed workspace: $path (resolved: $canonical). " +
                "Without an active tunnel the workspace is restricted to the work directory configured in Settings > Service. " +
                "Start a tunnel to allow access to /sdcard and /storage/emulated/0."
        }
        return null
    }

    /**
     * 对工具参数中所有路径类键做统一预检。
     * @return null 表示全部通过；否则返回可直接返回给调用方的 err JSONObject
     */
    fun validateArgs(context: Context, args: JSONObject, baseDir: String? = null): JSONObject? {
        for (key in PATH_KEYS) {
            if (!args.has(key)) continue
            val v = args.optString(key)
            if (v.isBlank()) continue
            // URI 形式（content:// / file://）由 SAF/FileProvider 授权控制，不属于文件系统工作区策略范围
            if (v.startsWith("content://") || v.startsWith("file://")) continue
            val error = validatePath(context, v, baseDir)
            if (error != null) {
                return err("PATH_OUTSIDE_WORKSPACE", error, key, v)
            }
        }
        return null
    }
}
