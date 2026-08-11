package com.soreverse.mcp.core

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 塔菲逆核: 官方逆向工具二进制管理器。
 *
 * 管理从 GitHub Release 下载的独立可执行工具：
 *  - eDBG          (ShinoLeah/eDBG)         eBPF 调试器, 需 Root + 内核 5.10+
 *  - eBPFDexDumper (chinleez/eBPFDexDumper-rs) eBPF DEX dump, 需 Root
 *
 * 流程: 下载到应用 filesDir/tools/ → 校验 → 经 Root/Shizuku 通道部署到
 * /data/local/tmp + chmod 755（应用沙箱无法直接执行该路径，须由特权通道部署）。
 */
object BinaryManager {

    data class ToolBinary(
        val key: String,
        val fileName: String,
        val remoteName: String,
        val repo: String,
        val releaseTag: String,
        val sizeMb: Double,
        val minKernel: String? = null,
        val zh: String,
        /** 内置在 APK assets 中的路径（非空则优先使用内置，无需下载）。 */
        val bundledAsset: String? = null,
    )

    val EDBG = ToolBinary(
        key = "edbg", fileName = "eDBG", remoteName = "eDBG_v2.3.0",
        repo = "ShinoLeah/eDBG", releaseTag = "v2.3.0", sizeMb = 11.38,
        minKernel = "5.10",
        zh = "eDBG（eBPF 调试器，无视反调试，支持 MCP）",
        bundledAsset = "binaries/eDBG",
    )
    val DEXDUMP = ToolBinary(
        key = "dexdumper", fileName = "eBPFDexDumper", remoteName = "eBPFDexDumper_android_arm64",
        repo = "chinleez/eBPFDexDumper-rs", releaseTag = "v0.2.3", sizeMb = 4.78,
        minKernel = null,
        zh = "eBPFDexDumper（从 ART 运行时抓取真实 DEX）",
    )

    val ALL = listOf(EDBG, DEXDUMP)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 应用内本地文件路径。 */
    fun localFile(context: Context, tool: ToolBinary): File =
        File(File(context.filesDir, "tools"), tool.fileName)

    /** 设备端部署路径。 */
    fun devicePath(tool: ToolBinary): String = "/data/local/tmp/${tool.fileName}"

    /** 是否内置在 APK assets 中。 */
    fun isBundled(tool: ToolBinary): Boolean = !tool.bundledAsset.isNullOrBlank()

    /** 从 APK assets 提取内置二进制到应用目录（无内置返回 false）。 */
    fun extractBundled(context: Context, tool: ToolBinary): Boolean {
        val asset = tool.bundledAsset ?: return false
        val dir = File(context.filesDir, "tools")
        dir.mkdirs()
        val target = localFile(context, tool)
        return runCatching {
            context.assets.open(asset).use { input ->
                val tmp = File(dir, "${tool.fileName}.part")
                tmp.outputStream().use { output -> input.copyTo(output) }
                tmp.renameTo(target)
            }
            target.length() > 1024 * 1024
        }.getOrDefault(false)
    }

    /** 是否已下载到应用目录（内置已提取或下载完成）。 */
    fun isDownloaded(context: Context, tool: ToolBinary): Boolean {
        val f = localFile(context, tool)
        return f.exists() && f.length() > 1024 * 1024
    }

    /** 是否已部署到设备（存在且可执行）。 */
    fun isDeployed(tool: ToolBinary): Boolean {
        val r = PermissionManager.exec("ls -l ${devicePath(tool)} 2>/dev/null")
        return r.success && r.stdout.isNotBlank()
    }

    /** 内核版本号（如 5.15.123），用于判断 eDBG 兼容性。 */
    fun kernelVersion(): String = PermissionManager.exec("uname -r", timeoutSec = 8).stdout.trim()

    /** 内核版本是否满足最低要求（如 5.10）。 */
    fun kernelMeets(minVersion: String?): Boolean {
        if (minVersion.isNullOrBlank()) return true
        val kv = kernelVersion().takeIf { it.isNotBlank() } ?: return false
        val ver = kv.split(".").firstOrNull()?.toIntOrNull() ?: return false
        val min = minVersion.split(".").firstOrNull()?.toIntOrNull() ?: return false
        return ver >= min
    }

    /** 获取二进制到应用目录：优先从 APK 内置 assets 提取，否则从 GitHub Release 下载。 */
    fun download(context: Context, tool: ToolBinary): JSONObject {
        // 内置优先
        if (isBundled(tool)) {
            if (extractBundled(context, tool)) {
                AppLog.i("BinaryManager: ${tool.fileName} extracted from bundled assets")
                val f = localFile(context, tool)
                return JSONObject()
                    .put("ok", true)
                    .put("tool", tool.key)
                    .put("bundled", true)
                    .put("sizeMb", f.length() / 1024.0 / 1024.0)
                    .put("path", f.absolutePath)
            }
        }
        val dir = File(context.filesDir, "tools")
        dir.mkdirs()
        val target = localFile(context, tool)
        val url = "https://github.com/${tool.repo}/releases/download/${tool.releaseTag}/${tool.remoteName}"
        for (candidate in DownloadMirrorPolicy.candidates(url)) {
            try {
                val req = Request.Builder().url(candidate).header("User-Agent", "TaffyNiHe").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) continue
                    val tmp = File(dir, "${tool.fileName}.part")
                    resp.body?.byteStream()?.use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (tmp.length() < 1024 * 1024) {
                        tmp.delete()
                        continue
                    }
                    tmp.renameTo(target)
                    AppLog.i("BinaryManager: ${tool.fileName} downloaded (${target.length() / 1024 / 1024} MB) from $candidate")
                    return JSONObject()
                        .put("ok", true)
                        .put("tool", tool.key)
                        .put("sizeMb", target.length() / 1024.0 / 1024.0)
                        .put("path", target.absolutePath)
                }
            } catch (e: Exception) {
                AppLog.w("BinaryManager: download ${tool.fileName} failed from $candidate: ${e.message}")
            }
        }
        return JSONObject()
            .put("ok", false)
            .put("error", "获取失败（内置缺失且下载失败）")
            .put("tool", tool.key)
    }

    /** 部署到 /data/local/tmp 并加可执行权限（需 Root/Shizuku）。 */
    fun deploy(context: Context, tool: ToolBinary): JSONObject {
        if (!isDownloaded(context, tool)) {
            return JSONObject().put("ok", false).put("error", "尚未下载，先执行下载")
        }
        val local = localFile(context, tool).absolutePath
        val remote = devicePath(tool)
        val r = PermissionManager.exec(
            "cp '$local' $remote && chmod 755 $remote && ls -l $remote",
            timeoutSec = 60,
        )
        if (r.success && r.stdout.contains(tool.fileName)) {
            AppLog.i("BinaryManager: ${tool.fileName} deployed to $remote")
            return JSONObject().put("ok", true).put("tool", tool.key).put("path", remote)
        }
        return JSONObject()
            .put("ok", false)
            .put("error", "部署失败: ${r.stderr.ifBlank { r.stdout }}")
            .put("tool", tool.key)
    }

    /** 删除本地与设备端文件。 */
    fun uninstall(context: Context, tool: ToolBinary): JSONObject {
        localFile(context, tool).delete()
        PermissionManager.exec("rm -f ${devicePath(tool)}", timeoutSec = 10)
        return JSONObject().put("ok", true).put("tool", tool.key).put("removed", true)
    }

    /** 汇总状态（供权限管理页/工具自检展示）。 */
    fun status(context: Context, tool: ToolBinary): JSONObject = JSONObject()
        .put("tool", tool.key)
        .put("name", tool.zh)
        .put("downloaded", isDownloaded(context, tool))
        .put("deployed", isDeployed(tool))
        .put("kernel", kernelVersion())
        .put("kernelOk", kernelMeets(tool.minKernel))
        .put("minKernel", tool.minKernel ?: "-")
        .put("sizeMb", tool.sizeMb)
        .put("downloadUrl", "https://github.com/${tool.repo}/releases/download/${tool.releaseTag}/${tool.remoteName}")
}
