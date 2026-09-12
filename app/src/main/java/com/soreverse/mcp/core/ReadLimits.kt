package com.soreverse.mcp.core

import java.io.File

/**
 * 统一「带上限读取」工具 —— 防止 readText()/readBytes() 无界读入导致 OOM / DoS。
 *
 * 用于读取 App 自身产出的结果、审计、报告、状态等 JSON 文件（这些文件由工具/引擎写出，
 * 体量随输入增长，读回时必须封顶）。上限刻意放宽，确保不影响工具正常功能。
 *
 *  - [RESULT_JSON_BYTES] 512 MiB：blutter 结果、分析报告、审计文件等「结果/报告」类；
 *  - [META_JSON_BYTES]    64 MiB：快照 meta、工作区/任务状态、插件 meta 等「元数据」类。
 */
object ReadLimits {
    /** 结果 / 报告类 JSON（与 ApkAnalyzer.MAX_INPUT_BYTES 对齐）。 */
    const val RESULT_JSON_BYTES: Long = 512L * 1024L * 1024L

    /** 元数据 / 状态类 JSON。 */
    const val META_JSON_BYTES: Long = 64L * 1024L * 1024L
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
