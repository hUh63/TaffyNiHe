package com.soreverse.mcp.core

/**
 * 把 rizin 一次调用里多条命令的拼接输出，按 JSON 值切开。
 *
 * 为什么需要：native 的 `rzCommand` 每调用一次都要**新建 RzCore + rz_core_analysis_all()**
 * —— 一个 6.7MB 的 SO 要 3~10 秒。而打开一个函数的控制流、展开一个尾跳要连发 3~25 条命令。
 * rizin 命令支持 `;` 串联，一次调用就能跑完，输出按顺序拼在一起；这里负责把它切回一个个 JSON。
 *
 * ```
 * {…agf json…}
 * […pdfj…]
 * ```
 * → `["{…}", "[…]"]`
 *
 * 非 JSON 开头的内容（rizin 的报错/提示文本，如 `Cannot seek`）会被跳过，不混进结果。
 */
internal fun splitJsonChunks(raw: String): List<String> {
    val out = ArrayList<String>(4)
    var i = 0
    val n = raw.length
    while (i < n) {
        while (i < n && raw[i].isWhitespace()) i++
        if (i >= n) break
        val c0 = raw[i]
        if (c0 != '{' && c0 != '[') {
            // 不是 JSON：跳到行尾（典型是 `ERROR: ...` 之类）
            while (i < n && raw[i] != '\n') i++
            continue
        }
        val start = i
        var depth = 0
        var inStr = false
        var esc = false
        var closed = false
        while (i < n) {
            val c = raw[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        if (depth == 0) {
                            i++
                            closed = true
                        }
                    }
                }
            }
            if (closed) break
            i++
        }
        if (!closed) break          // 输出被截断，丢掉不完整的尾巴
        out.add(raw.substring(start, i))
    }
    return out
}
