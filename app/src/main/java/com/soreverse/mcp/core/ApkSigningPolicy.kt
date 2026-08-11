package com.soreverse.mcp.core

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

/**
 * APK 签名策略：统一从设置读取签名配置并应用到签名流程。
 *
 *  - [schemeFlags]：签名方案 → v1/v2/v3 启用组合（对应设置页 7 种方案）；
 *  - [resolveSigner]：默认密钥（内置自签名）或自定义密钥（[SigningKeyStore]）；
 *  - [v1SignerName]：自定义 V1 签名数据文件名（META-INF 下）；
 *  - [copyV2V3Blocks]：把输入 APK 的 V2/V3 签名块复制到输出 APK
 *    （对应设置「不签名时保留 V2/V3 签名数据」，供重建/不签名流程调用）。
 */
object ApkSigningPolicy {

    /** 7 种签名方案 → (v1, v2, v3)。 */
    fun schemeFlags(scheme: String): Triple<Boolean, Boolean, Boolean> = when (scheme) {
        "v1" -> Triple(true, false, false)
        "v2" -> Triple(false, true, false)
        "v3" -> Triple(false, false, true)
        "v1v2" -> Triple(true, true, false)
        "v1v3" -> Triple(true, false, true)
        "v2v3" -> Triple(false, true, true)
        else -> Triple(true, true, true) // v1v2v3（默认）
    }

    fun schemeFlags(context: Context): Triple<Boolean, Boolean, Boolean> =
        schemeFlags(SettingsStore(context).apkSignScheme)

    /** 解析当前签名密钥（默认内置 / 自定义导入）。 */
    fun resolveSigner(context: Context): Pair<java.security.PrivateKey, java.security.cert.X509Certificate>? {
        val settings = SettingsStore(context)
        if (settings.apkSignKeySource == "custom") {
            val custom = SigningKeyStore.resolveActive(context)
            if (custom != null) return custom.first to custom.second
            // 自定义密钥缺失/失效：回退内置并给出提示（由调用方决定是否报错）
            AppLog.w("apkSign: custom keystore '${settings.apkSignKeystoreName}' unavailable, falling back to built-in")
        }
        return runCatching { ApkBuildSignerBridge.obtainInternal(context) }.getOrNull()
    }

    /**
     * V1 签名数据文件名（不含扩展名）。
     *  - 设置页开启「自定义 V1 签名数据文件名」且已填写名称 → 使用自定义名称；
     *  - 否则（开关关闭或留空）→ 自动从签名密钥派生：
     *    自定义密钥用其 alias，内置密钥用内置 alias（niehe）。
     */
    fun v1SignerName(context: Context): String {
        val s = SettingsStore(context)
        if (s.apkV1SignerEnabled) {
            val custom = s.apkV1SignerName.trim()
            if (custom.isNotBlank()) return custom
        }
        return if (s.apkSignKeySource == "custom") s.apkSignKeystoreAlias.trim().ifBlank { "CERT" }
        else "niehe"
    }

    /**
     * 把输入 APK 的 V2/V3 签名块（APK Signing Block）复制到输出 APK。
     * ZIP 级操作：提取 input 中央目录前的 signing block，原样插入 output 中央目录前并修正 EOCD。
     * @return 复制成功返回 block 字节数；输入无签名块或操作失败返回 0。
     */
    fun copyV2V3Blocks(input: File, output: File): Long {
        if (!input.isFile || !output.isFile) return 0L
        return runCatching {
            val block = readSigningBlock(input) ?: return 0L
            val out = RandomAccessFile(output, "rw")
            try {
                val eocd = locateEocd(out) ?: return 0L
                val cdOffset = readUInt32(out, eocd + 16)
                val cdSize = readUInt32(out, eocd + 12)
                // 把 signing block 插入中央目录之前
                insertBytes(out, cdOffset.toLong(), block)
                // 修正 EOCD 中央目录偏移
                writeUInt32(out, eocd + 16, cdOffset + block.size)
                cdSize // 返回中央目录大小（信息性）
            } finally {
                out.close()
            }
        }.getOrDefault(0L)
    }

    /** 读取输入 APK 的 APK Signing Block（含 head size 字段与尾部 size 字段，供原样插入）。 */
    private fun readSigningBlock(input: File): ByteArray? {
        val raf = RandomAccessFile(input, "r")
        try {
            val len = raf.length()
            if (len < 32) return null
            val eocd = locateEocd(raf) ?: return null
            val cdOffset = readUInt32(raf, eocd + 16)
            if (cdOffset < 32) return null
            // 签名块布局（紧邻中央目录之前）：
            //   [size(8B) | ID-value pairs... | magic "APK Sig Block 42"(16B) | size(8B)]
            // 尾部 size 位于 [cdOffset-8, cdOffset)，其值 = ID-value pairs 内容长度
            raf.seek(cdOffset.toLong() - 8)
            val sizeBuf = ByteArray(8)
            raf.readFully(sizeBuf)
            val contentSize = leLong(sizeBuf)
            val blockTotal = contentSize + 32 // head size(8) + content + magic(16) + tail size(8)
            if (contentSize <= 0 || blockTotal > cdOffset) return null
            val magic = ByteArray(16)
            raf.seek(cdOffset.toLong() - 24)
            raf.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != "APK Sig Block 42") return null
            val start = cdOffset - blockTotal
            if (start < 0) return null
            val buf = ByteArray(blockTotal.toInt())
            raf.seek(start)
            raf.readFully(buf)
            return buf
        } finally {
            raf.close()
        }
    }

    /** 定位 EOCD：从文件尾向前找 0x06054b50（容忍 comment 长度）。 */
    private fun locateEocd(raf: RandomAccessFile): Long? {
        val len = raf.length()
        val maxComment = 65535 + 22
        val searchLen = minOf(len, maxComment.toLong()).toInt()
        val buf = ByteArray(searchLen)
        raf.seek(len - searchLen)
        raf.readFully(buf)
        for (i in buf.size - 22 downTo 0) {
            if ((buf[i].toInt() and 0xFF) == 0x50 && (buf[i + 1].toInt() and 0xFF) == 0x4b &&
                (buf[i + 2].toInt() and 0xFF) == 0x05 && (buf[i + 3].toInt() and 0xFF) == 0x06
            ) {
                return len - searchLen + i
            }
        }
        return null
    }

    private fun readUInt32(raf: RandomAccessFile, pos: Long): Long {
        raf.seek(pos)
        val b = ByteArray(4)
        raf.readFully(b)
        return (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
            ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
    }

    private fun writeUInt32(raf: RandomAccessFile, pos: Long, value: Long) {
        raf.seek(pos)
        raf.write(intArrayOf(
            (value and 0xFF).toInt(),
            ((value shr 8) and 0xFF).toInt(),
            ((value shr 16) and 0xFF).toInt(),
            ((value shr 24) and 0xFF).toInt(),
        ).map { it.toByte() }.toByteArray())
    }

    private fun leLong(b: ByteArray): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    /** 在文件 offset 处插入字节（后续数据后移）。 */
    private fun insertBytes(raf: RandomAccessFile, offset: Long, bytes: ByteArray) {
        val tailLen = raf.length() - offset
        val tail = ByteArray(tailLen.toInt())
        raf.seek(offset)
        raf.readFully(tail)
        raf.seek(offset)
        raf.write(bytes)
        raf.write(tail)
        raf.setLength(raf.length())
    }
}

/** 内置签名密钥桥接（避免 core 包反向依赖 mcp 包的 ApkBuildTool）。 */
internal object ApkBuildSignerBridge {
    @Volatile var provider: ((Context) -> Pair<java.security.PrivateKey, java.security.cert.X509Certificate>?)? = null
    fun obtainInternal(context: Context): Pair<java.security.PrivateKey, java.security.cert.X509Certificate>? =
        provider?.invoke(context)
}
