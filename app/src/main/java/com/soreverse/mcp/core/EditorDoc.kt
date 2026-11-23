package com.soreverse.mcp.core

/**
 * 编辑器文本的「影子副本」。
 *
 * 换用 WebView + CodeMirror 之后，文本的真实状态在 JS 侧（CM 的 rope 结构），
 * 但业务层（保存、搜索、格式化、AI 分析、差异比对）需要同步读全文 ——
 * 如果每次按键都向 JS 异步要一次全文，会既慢又乱序。
 *
 * 所以这里保留一份精确镜像：JS 每次变更回传最小 diff（from/to/insert），
 * 这里顺序应用。读操作同步、无锁竞争以外的成本，且不参与 Compose 重组
 * （节流后的快照才进 state，见 [CodeMirrorHandle]）。
 *
 * 注意：这个类不是线程安全的「多写」模型 —— 写入只会来自 JS 桥的后台线程，
 * 读取来自主线程，因此用 synchronized 串行化即可。
 */
internal class EditorDoc(initial: String = "") {

    private var value: String = initial

    @Synchronized
    fun set(text: String) {
        value = text
    }

    @Synchronized
    fun text(): String = value

    @Synchronized
    fun length(): Int = value.length

    /**
     * 应用一段替换。坐标是「应用这一刻」的偏移，多段变更由调用方按顺序给。
     * 越界的 from/to 会被夹到合法范围 —— JS 与 Kotlin 之间的时序里可能出现
     * 极短窗口的不一致（例如程序化写入与用户输入交错），夹紧比抛异常安全。
     */
    @Synchronized
    fun apply(from: Int, to: Int, insert: String) {
        val len = value.length
        val a = from.coerceIn(0, len)
        val b = to.coerceIn(a, len)
        value = buildString(len - (b - a) + insert.length) {
            append(value, 0, a)
            append(insert)
            append(value, b, len)
        }
    }

    @Synchronized
    fun replaceAll(text: String) {
        value = text
    }
}

/**
 * 求两段文本的最小替换区间：返回 prefix 长度、suffix 长度与中段新内容。
 * 给「程序化编辑」用 —— 只把真正变化的片段发给 WebView，
 * 而不是把整篇 1MB 文本再推一遍。
 */
internal data class TextPatch(val prefix: Int, val suffix: Int, val insert: String) {
    /** 把补丁应用到旧文本上，便于单测与自检。 */
    fun applyTo(old: String): String =
        old.substring(0, prefix.coerceIn(0, old.length)) + insert +
            old.substring((old.length - suffix).coerceIn(0, old.length))
}

internal object TextDiff {

    fun minPatch(old: String, new: String): TextPatch {
        if (old == new) return TextPatch(old.length, 0, "")
        val oLen = old.length
        val nLen = new.length
        val max = minOf(oLen, nLen)
        var p = 0
        while (p < max && old[p] == new[p]) p++
        var s = 0
        // 前后缀不能重叠：s 的边界是 max - p
        while (s < max - p && old[oLen - 1 - s] == new[nLen - 1 - s]) s++
        return TextPatch(p, s, new.substring(p, nLen - s))
    }
}
