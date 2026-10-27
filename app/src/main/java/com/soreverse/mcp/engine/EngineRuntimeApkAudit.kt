// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 借鉴 SOMCP v1.0.23 T1 审计批（PR #179）的 APK 侧四个只读工具适配：
// apk_sig_verify / apk_cert_info / apk_zip_anomaly / apk_abi_coverage。
//
// 与 analyzeApk 共用同一套输入闸门（自身 Artifact 拦截、体积上限、内存压力、
// ZIP magic），但额外拒绝绝对路径与 `..` 段：这几个工具接受的是「相对工作目录
// 的路径」，放开绝对路径会让沙箱策略形同虚设。
package com.soreverse.mcp.engine

import com.soreverse.mcp.core.MemoryPressure
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import java.io.File
import org.json.JSONObject

/** 读取 APK 字节；任何闸门失败都返回 (空数组, 错误信封)。 */
internal fun EngineRuntime.loadApkAuditInput(path: String): Pair<ByteArray, JSONObject?> {
    if (path.isBlank()) {
        return ByteArray(0) to err("INVALID_ARGUMENT", "APK path is required", "path", path)
    }
    val normalized = path.replace('\\', '/')
    val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
    if (normalized.startsWith("/") || segments.any { it == ".." }) {
        return ByteArray(0) to err("PATH_NOT_ALLOWED", "APK path must be relative and stay inside its root", "path", path)
    }
    val local = File(path)
    if (local.isFile && com.soreverse.mcp.nativecore.NativeProbe.isOwnApk(local.absolutePath)) {
        return ByteArray(0) to err(
            "SELF_ANALYSIS_FORBIDDEN",
            "塔菲逆核不能分析自身 APK（签名匹配），请选择其他 APK",
            "path",
            path,
        )
    }
    if (local.isFile && local.length() > ApkAnalyzer.MAX_INPUT_BYTES) {
        return ByteArray(0) to err(
            "APK_LIMIT_EXCEEDED",
            "APK exceeds ${ApkAnalyzer.MAX_INPUT_BYTES / 1024 / 1024} MiB input limit",
            "path",
            path,
        )
    }
    val bytes = try {
        if (local.isFile) {
            MemoryPressure.guardAllocation(context, local.length(), "APK")?.let {
                return ByteArray(0) to err("MEMORY_PRESSURE", it, "path", path)
            }
            local.readBytes()
        } else {
            val dir = workDir
                ?: return ByteArray(0) to err(
                    "WORK_DIRECTORY_NOT_SELECTED",
                    "APK path is not a local file and no work directory is selected",
                    "path",
                    path,
                )
            dir.readFile(path, ApkAnalyzer.MAX_INPUT_BYTES)
        }
    } catch (error: ApkAnalysisLimitException) {
        return ByteArray(0) to err("APK_LIMIT_EXCEEDED", error.message ?: "APK exceeds analysis limits", "path", path)
    }
    if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte()) {
        return ByteArray(0) to err("APK_INVALID", "Input is not a ZIP/APK file", "path", path)
    }
    return bytes to null
}

private fun apkAuditEnvelope(path: String, bytes: ByteArray, payload: JSONObject): JSONObject = ok(
    JSONObject()
        .put("path", path)
        .put("size", bytes.size)
        .put("sha256", sha256(bytes))
        .put("audit", payload),
)

internal fun EngineRuntime.apkSigVerify(path: String): JSONObject = guarded {
    val (bytes, error) = loadApkAuditInput(path)
    if (error != null) return@guarded error
    apkAuditEnvelope(path, bytes, ApkSigTools.verify(bytes))
}

internal fun EngineRuntime.apkCertInfo(path: String): JSONObject = guarded {
    val (bytes, error) = loadApkAuditInput(path)
    if (error != null) return@guarded error
    apkAuditEnvelope(path, bytes, ApkSigTools.certInfo(bytes))
}

internal fun EngineRuntime.apkZipAnomaly(path: String): JSONObject = guarded {
    val (bytes, error) = loadApkAuditInput(path)
    if (error != null) return@guarded error
    apkAuditEnvelope(path, bytes, ApkSigTools.zipAnomalies(bytes))
}

internal fun EngineRuntime.apkAbiCoverage(path: String): JSONObject = guarded {
    val (bytes, error) = loadApkAuditInput(path)
    if (error != null) return@guarded error
    apkAuditEnvelope(path, bytes, ApkSigTools.abiCoverage(bytes))
}
