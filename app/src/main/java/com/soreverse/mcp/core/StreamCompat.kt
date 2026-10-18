package com.soreverse.mcp.core

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Android 上 `InputStream.readNBytes(int)` 是 Java 9 API，直到 **API 33** 才提供。
 * minSdk 26 且未启用 core library desugaring，直接调用会在 Android 8–12 上抛
 * `NoSuchMethodError`（LinkageError，`catch (Exception)` 也抓不住）。
 */
internal object StreamCompat {

    /** 读取至多 [limit] 字节；不足则返回实际读到的内容（与 readNBytes 语义一致）。 */
    fun readCapped(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val chunk = ByteArray(8192)
        while (out.size() < limit) {
            val n = input.read(chunk, 0, minOf(chunk.size, limit - out.size()))
            if (n < 0) break
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }
}
