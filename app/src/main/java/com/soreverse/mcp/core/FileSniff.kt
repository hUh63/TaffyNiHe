package com.soreverse.mcp.core

import java.io.File

/**
 * 二进制嗅探：判断一段字节是否适合当「文本」打开，并尽力识别格式。
 *
 * 背景（源自太墟 `core/common/files/BinaryFileSniffer`）：编辑器/文本预览直接把文件
 * `readBytes().decodeToString()`，一个 3 MiB 的 .so 会变成几百万个替换字符塞进
 * BasicTextField —— 界面卡死、内容全是乱码、保存还会把原文件写坏。体积闸门只能挡住
 * 「太大」，挡不住「不太大但根本不是文本」。这里补上格式判断。
 *
 * 判定刻意保守：只拦明显是二进制的内容（魔数、NUL、控制字符占比过高），宁可漏判也不
 * 误伤 Markdown / JSON / 中文 UTF-8。纯 Kotlin，无 Android 依赖，可单测。
 */
object FileSniff {

    /** 只嗅探文件头部这么多字节；再往后读对判断没有帮助却会拖慢大文件。 */
    const val SAMPLE_BYTES = 8 * 1024

    /** 控制字符（除 \t \n \r \f \b）占比超过此百分比即视为二进制。 */
    private const val CONTROL_RATIO_PERCENT = 10

    enum class Kind(val labelZh: String, val labelEn: String) {
        TEXT("文本", "text"),
        ELF("ELF 二进制（.so / 可执行文件）", "ELF binary (.so / executable)"),
        ZIP("ZIP 压缩包（APK 同属此类）", "ZIP archive (APK included)"),
        DEX("Dalvik dex", "Dalvik dex"),
        PE("Windows PE 可执行文件", "Windows PE binary"),
        MACHO("Mach-O 可执行文件", "Mach-O binary"),
        IMAGE("图片", "image"),
        PDF("PDF 文档", "PDF document"),
        SQLITE("SQLite 数据库", "SQLite database"),
        MEDIA("音视频 / 字体等二进制", "media / font binary"),
        BINARY("二进制文件", "binary file"),
    }

    private class Magic(val prefix: ByteArray, val kind: Kind)

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private val MAGICS: List<Magic> = listOf(
        Magic(bytes(0x7F, 0x45, 0x4C, 0x46), Kind.ELF),
        Magic(bytes(0x64, 0x65, 0x78, 0x0A), Kind.DEX),
        Magic(bytes(0x50, 0x4B, 0x03, 0x04), Kind.ZIP),
        Magic(bytes(0x50, 0x4B, 0x05, 0x06), Kind.ZIP),
        Magic(bytes(0x50, 0x4B, 0x07, 0x08), Kind.ZIP),
        Magic(bytes(0x4D, 0x5A), Kind.PE),
        Magic(bytes(0xCE, 0xFA, 0xED, 0xFE), Kind.MACHO),
        Magic(bytes(0xCF, 0xFA, 0xED, 0xFE), Kind.MACHO),
        Magic(bytes(0xCA, 0xFE, 0xBA, 0xBE), Kind.MACHO),
        Magic(bytes(0x89, 0x50, 0x4E, 0x47), Kind.IMAGE),
        Magic(bytes(0xFF, 0xD8, 0xFF), Kind.IMAGE),
        Magic(bytes(0x47, 0x49, 0x46, 0x38), Kind.IMAGE),
        Magic(bytes(0x25, 0x50, 0x44, 0x46), Kind.PDF),
        Magic(bytes(0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66), Kind.SQLITE),
        Magic(bytes(0x1F, 0x8B), Kind.MEDIA), // gzip
        Magic(bytes(0x42, 0x5A, 0x68), Kind.MEDIA), // bzip2
        Magic(bytes(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00), Kind.MEDIA), // xz
        Magic(bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C), Kind.MEDIA), // 7z
        Magic(bytes(0x52, 0x61, 0x72, 0x21), Kind.MEDIA), // RAR
        Magic(bytes(0x4F, 0x67, 0x67, 0x53), Kind.MEDIA), // Ogg
        Magic(bytes(0x66, 0x4C, 0x61, 0x43), Kind.MEDIA), // FLAC
        Magic(bytes(0x52, 0x49, 0x46, 0x46), Kind.MEDIA), // RIFF
        Magic(bytes(0x1A, 0x45, 0xDF, 0xA3), Kind.MEDIA), // Matroska / webm
        Magic(bytes(0x77, 0x4F, 0x46, 0x46), Kind.MEDIA), // wOFF
        Magic(bytes(0x4F, 0x54, 0x54, 0x4F), Kind.MEDIA), // OTF
        Magic(bytes(0x00, 0x61, 0x73, 0x6D), Kind.BINARY), // wasm
    )

    private val UTF8_BOM = bytes(0xEF, 0xBB, 0xBF)
    private val UTF16_LE_BOM = bytes(0xFF, 0xFE)
    private val UTF16_BE_BOM = bytes(0xFE, 0xFF)

    /**
     * @param data 头部字节（通常取 [SAMPLE_BYTES]）
     * @param length 实际有效长度，允许传入超出头部大小的数组
     */
    fun sniff(data: ByteArray, length: Int = data.size): Kind {
        val size = length.coerceIn(0, data.size)
        if (size == 0) return Kind.TEXT

        // 带 BOM 的是文本（UTF-8 / UTF-16），魔数表里的 0xFF 0xD8 之类不能把 UTF-16 BOM 抢走。
        if (startsWith(data, size, UTF8_BOM) || startsWith(data, size, UTF16_LE_BOM) || startsWith(data, size, UTF16_BE_BOM)) {
            return Kind.TEXT
        }
        for (m in MAGICS) {
            if (startsWith(data, size, m.prefix)) return m.kind
        }

        var control = 0
        var total = 0
        for (i in 0 until size) {
            val b = data[i].toInt() and 0xFF
            if (b == 0) return Kind.BINARY
            if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D && b != 0x0C && b != 0x08) control++
            if (b != 0x7F) total++
        }
        if (total > 0 && control * 100 / total > CONTROL_RATIO_PERCENT) return Kind.BINARY
        return Kind.TEXT
    }

    fun looksBinary(data: ByteArray, length: Int = data.size): Boolean = sniff(data, length) != Kind.TEXT

    /** 只读文件头部做判断；读失败按文本处理（让后续读取报出真实错误，而不是在这里吞掉）。 */
    fun sniffFile(file: File, sampleBytes: Int = SAMPLE_BYTES): Kind = runCatching {
        val sample = ByteArray(sampleBytes.coerceAtLeast(1))
        val n = file.inputStream().use { ins -> ins.read(sample) }
        if (n <= 0) Kind.TEXT else sniff(sample, n)
    }.getOrDefault(Kind.TEXT)

    /** 给用户看的建议：这类文件应该用哪个页面打开。 */
    fun hint(kind: Kind, zh: Boolean): String = when (kind) {
        Kind.TEXT -> if (zh) "文本文件" else "text file"
        Kind.ELF -> if (zh) "请用「分析」页打开（反汇编 / 伪 C / 节区 / 符号）" else "open it in Analyze (disasm / pseudo-C / sections)"
        Kind.ZIP -> if (zh) "请用「APK 编辑」或「DEX 浏览器」打开" else "open it in APK editing or DEX browser"
        Kind.DEX -> if (zh) "请用「DEX 浏览器」打开" else "open it in the DEX browser"
        Kind.PE, Kind.MACHO -> if (zh) "请用「分析」页的 PE / Mach-O 视图打开" else "open it with the PE / Mach-O views in Analyze"
        Kind.IMAGE, Kind.PDF, Kind.MEDIA -> if (zh) "这是媒体文件，编辑器只能处理文本" else "media file; the editor only handles text"
        Kind.SQLITE -> if (zh) "这是数据库文件，编辑器只能处理文本" else "database file; the editor only handles text"
        Kind.BINARY -> if (zh) "请用地址查看器的 Hex 模式打开" else "use the address viewer's hex mode"
    }

    private fun startsWith(data: ByteArray, size: Int, prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (data[i] != prefix[i]) return false
        return true
    }
}
