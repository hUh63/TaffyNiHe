package com.soreverse.mcp.core

import java.io.InputStream
import java.io.InputStreamReader

/**
 * 逐块读取子进程输出并**按字符**解码。
 *
 * 之前各处都是 `proc.inputStream.read(ByteArray(4096))` + `String(buf, 0, n, Charsets.UTF_8)`：
 * 4 KiB 的字节边界一旦正好落在某个 3 字节汉字中间，两块各解出半个字符，终端与控制台里的
 * 中文就变成一串 U+FFFD 乱码（终端页、编辑器控制台、Python REPL、本地可执行日志都有这个问题）。
 * [InputStreamReader] 内部持有 CharsetDecoder，会跨块保留未完成的字节序列。
 *
 * @param shouldContinue 每次读之前检查（对应原来 `while (sessionProc === proc)` 这种会话存活判断）
 * @param onText 收到的文本块（可能为空串，调用方自行拼接）
 */
internal fun readChildText(
    input: InputStream,
    shouldContinue: () -> Boolean,
    onText: (String) -> Unit,
) {
    val reader = InputStreamReader(input, Charsets.UTF_8)
    val buf = CharArray(2048)
    while (shouldContinue()) {
        val n = try {
            reader.read(buf)
        } catch (_: Exception) {
            -1
        }
        if (n < 0) break
        onText(String(buf, 0, n))
    }
}
