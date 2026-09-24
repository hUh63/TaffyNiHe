package com.soreverse.mcp.termemu

/**
 * ANSI/VT 转义序列过滤器（终端内核的一部分）。
 *
 * 我们的终端输出直接来自 `Process` 的字节流，里面常混有 ANSI 颜色、光标移动、OSC 标题等
 * 控制序列；Compose 的 `Text` 不能渲染它们，必须剥掉，否则满屏乱码「[1;32m」。
 *
 * 只做「剥离」，不做 VT 屏幕仿真（那需要完整的终端模拟器）。真要做 VT 屏幕（光标定位、
 * 滚动区、alternate screen），在此模块里继续加 `TerminalBuffer` 即可——模块边界已经切好，
 * 不会影响 app。
 */
object AnsiFilter {

    /**
     * 剥离 ANSI 转义序列，并按终端语义处理回车与退格：
     *  - `\r`：光标回到行首，**后续内容覆盖当前行**（进度条 `1%\r100%` → `100%`）
     *  - `\r\n`：视为换行
     *  - `\b`：删除当前行最后一个字符
     */
    fun strip(input: String): String {
        if (input.isEmpty()) return input
        val sb = StringBuilder(input.length)   // 已完成的行
        val cur = StringBuilder()              // 当前行（可被 \r 覆盖）
        var i = 0
        val n = input.length

        fun flush() {
            sb.append(cur).append('\n')
            cur.setLength(0)
        }

        while (i < n) {
            val c = input[i]
            when {
                c == '\u001B' -> {
                    if (i + 1 >= n) break
                    when (input[i + 1]) {
                        '[' -> {
                            // CSI: ESC [ 参数 中间 终止(0x40-0x7E)
                            i += 2
                            while (i < n && input[i] !in '@'..'~') i++
                            if (i < n) i++
                        }
                        ']' -> {
                            // OSC: ESC ] ... BEL 或 ESC \
                            i += 2
                            while (i < n) {
                                if (input[i] == '\u0007') { i++; break }
                                if (input[i] == '\u001B' && i + 1 < n && input[i + 1] == '\\') { i += 2; break }
                                i++
                            }
                        }
                        '(', ')', '*', '+', '-', '.', '/' -> i += 3   // 字符集选择
                        else -> i += 2                                // 其他两字符序列
                    }
                }
                c == '\r' -> {
                    if (i + 1 < n && input[i + 1] == '\n') { flush(); i += 2 }
                    else { cur.setLength(0); i++ }
                }
                c == '\n' -> { flush(); i++ }
                c == '\u0008' -> {
                    if (cur.isNotEmpty()) cur.setLength(cur.length - 1)
                    i++
                }
                c == '\u0007' -> i++   // BEL 丢弃
                else -> { cur.append(c); i++ }
            }
        }
        return sb.append(cur).toString()
    }

    /** 取最后一行非空内容（用于「输出预览条」）。 */
    fun lastNonEmptyLine(text: String): String =
        text.lineSequence().lastOrNull { it.isNotBlank() }?.trim().orEmpty()

    /** 归一行尾：\r\n → \n。 */
    fun normalizeNewlines(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')
}
