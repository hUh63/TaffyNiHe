package com.soreverse.mcp.core

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 统一「带上限读取」工具 —— 防止 readText()/readBytes()/copyTo() 无界读入导致 OOM / DoS。
 *
 * 用于读取 App 自身产出的结果、审计、报告、状态等 JSON 文件（这些文件由工具/引擎写出，
 * 体量随输入增长，读回时必须封顶），以及网络响应体读取。上限刻意放宽，确保不影响工具正常功能。
 */
object ReadLimits {
    // ── 本地文件 ──
    /** 结果 / 报告类 JSON（与 ApkAnalyzer.MAX_INPUT_BYTES 对齐）。 */
    const val RESULT_JSON_BYTES: Long = 512L * 1024L * 1024L

    /** 元数据 / 状态类 JSON。 */
    const val META_JSON_BYTES: Long = 64L * 1024L * 1024L

    // ── 网络响应体 ──
    /** 网络 API / JSON 响应（编辑器 AI、短链服务、扩展市场清单等）。 */
    const val NET_API_BYTES: Long = 8L * 1024L * 1024L

    /** 网络下载的插件包 / 扩展。 */
    const val PLUGIN_PACKAGE_BYTES: Long = 64L * 1024L * 1024L

    /** 网络下载的二进制（frida-gadget 等 so）。 */
    const val BINARY_DOWNLOAD_BYTES: Long = 256L * 1024L * 1024L
}

/**
 * 有上限地把文件读为文本；超过 [limitBytes] 抛 [IllegalArgumentException]
 * （调用方通常包在 runCatching 中，超限即视为读取失败，不会崩溃）。
 */
fun File.readTextCapped(limitBytes: Long = ReadLimits.RESULT_JSON_BYTES): String {
    val len = length()
    require(len <= limitBytes) {
        "FILE_TOO_LARGE: ${name} = ${len / 1024 / 1024}MiB > ${limitBytes / 1024 / 1024}MiB limit"
    }
    return readText()
}

/** 有上限地把文件读为字节；超过 [limitBytes] 抛 [IllegalArgumentException]。 */
fun File.readBytesCapped(limitBytes: Long = ReadLimits.RESULT_JSON_BYTES): ByteArray {
    val len = length()
    require(len <= limitBytes) {
        "FILE_TOO_LARGE: ${name} = ${len / 1024 / 1024}MiB > ${limitBytes / 1024 / 1024}MiB limit"
    }
    return readBytes()
}

/** 有上限地流式读取（边读边计数）；超过 [limitBytes] 抛 [IllegalArgumentException]。 */
fun InputStream.readBytesCapped(limitBytes: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = read(buf)
        if (n < 0) break
        total += n
        require(total <= limitBytes) { "STREAM_TOO_LARGE: > ${limitBytes / 1024 / 1024}MiB limit" }
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** 有上限地流式读取为文本；超过 [limitBytes] 抛 [IllegalArgumentException]。 */
fun InputStream.readTextCapped(limitBytes: Long, charset: java.nio.charset.Charset = Charsets.UTF_8): String =
    String(readBytesCapped(limitBytes), charset)

/** 有上限地流式拷贝到 [out]；超过 [limitBytes] 抛 [IllegalArgumentException]。返回拷贝字节数。 */
fun InputStream.copyToCapped(out: OutputStream, limitBytes: Long): Long {
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = read(buf)
        if (n < 0) break
        total += n
        require(total <= limitBytes) { "STREAM_TOO_LARGE: > ${limitBytes / 1024 / 1024}MiB limit" }
        out.write(buf, 0, n)
    }
    return total
}
