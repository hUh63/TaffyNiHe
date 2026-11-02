package com.soreverse.mcp.core

/**
 * smali 侧的静态辅助：寄存器分析与扩充、指令补全模板。
 *
 * 全部是纯字符串处理（不碰 Android API），所以可以直接写单元测试 —— 这类"改代码的助手"
 * 一旦算错就会把用户的 smali 改坏，必须先在 JVM 上验证。
 *
 * 寄存器语义（dalvik）：
 *  - `.registers N` 是**总**寄存器数，包含参数寄存器；
 *  - `.locals M` 只算局部，参数寄存器另算，总数为 M + 参数宽度；
 *  - 参数寄存器 pN 是按符号寻址的，实际槽位 = (总数 - 参数宽度) + N；
 *    所以**增大** .registers / .locals 不会改变 pN 的语义（只是多分了几只空闲寄存器），
 *    这也是"寄存器扩充"能安全落地的原因；减小则会破坏 pN 映射，本工具不允许。
 */
internal object DexSmaliAssist {

    /** 单个方法的寄存器使用情况。 */
    internal data class MethodRegs(
        /** `.method` 行原文（去掉前导空白）。 */
        val signature: String,
        /** `.method` 所在行号（1 起）。 */
        val startLine: Int,
        /** 声明行号（.registers / .locals），0 表示没声明。 */
        val declLine: Int,
        /** 使用的声明形式："registers" / "locals" / ""（缺失）。 */
        val declKind: String,
        /** 声明的数值（.registers 的总数 / .locals 的局部数）。 */
        val declared: Int,
        /** 参数宽度（long/double 占 2；非 static 还要加 this）。 */
        val paramRegs: Int,
        /** 实际可用的总寄存器数（miss 时为 -1）。 */
        val totalRegs: Int,
        /** 用到的最大 v 索引（-1 = 没用到）。 */
        val maxV: Int,
        /** 用到的最大 p 索引（-1 = 没用到）。 */
        val maxP: Int,
        /** 出现过的不同 v 寄存器个数。 */
        val vCount: Int,
        /** 越界使用，形如 "第 42 行 v9 ≥ 总数 8"。 */
        val overflow: List<String>,
    ) {
        val ok: Boolean get() = overflow.isEmpty() && totalRegs > 0
    }

    /** 补全模板：分类 → (显示名, 插入文本)。 */
    internal val templates: List<Pair<String, List<Pair<String, String>>>> = listOf(
        "常量 · const" to listOf(
            "const/4 v0, 0x0" to "const/4 v0, 0x0",
            "const v0, 0x0" to "const v0, 0x0",
            "const-string v0, \"\"" to "const-string v0, \"\"",
            "const-wide v0, 0x0L" to "const-wide v0, 0x0L",
            "const-class v0, Ljava/lang/Object;" to "const-class v0, Ljava/lang/Object;",
        ),
        "字段 · field" to listOf(
            "iget v0, v1, Lcls;->f:I" to "iget v0, v1, Lcom/example/Foo;->field:I",
            "iput v0, v1, Lcls;->f:I" to "iput v0, v1, Lcom/example/Foo;->field:I",
            "sget v0, Lcls;->f:I" to "sget v0, Lcom/example/Foo;->field:I",
            "sput v0, Lcls;->f:I" to "sput v0, Lcom/example/Foo;->field:I",
        ),
        "方法 · invoke" to listOf(
            "invoke-virtual {v0, v1}, Lcls;->m(II)V" to "invoke-virtual {v0, v1}, Lcom/example/Foo;->method(II)V",
            "invoke-static {}, Lcls;->m()V" to "invoke-static {}, Lcom/example/Foo;->method()V",
            "invoke-direct {p0}, Lcls;->m()V" to "invoke-direct {p0}, Lcom/example/Foo;->method()V",
            "invoke-super {p0}, Lcls;->m()V" to "invoke-super {p0}, Lcom/example/Foo;->method()V",
            "new-instance v0, Lcls;" to "new-instance v0, Lcom/example/Foo;",
        ),
        "跳转 · jump" to listOf(
            "if-eqz v0, :cond_0" to "if-eqz v0, :cond_0",
            "if-nez v0, :cond_0" to "if-nez v0, :cond_0",
            "goto :goto_0" to "goto :goto_0",
            ":cond_0 / :goto_0 标签" to ":cond_0",
        ),
        "返回 · return" to listOf(
            "return-void" to "return-void",
            "return v0" to "return v0",
            "return-object v0" to "return-object v0",
            "return-wide v0" to "return-wide v0",
        ),
    )

    // ------------------------------------------------------------------ 分析

    private val regUse = Regex("\\b([vp])(\\d+)\\b")

    /** 逐个方法分析寄存器使用。 */
    fun analyze(smali: String): List<MethodRegs> {
        val lines = smali.split('\n')
        val out = ArrayList<MethodRegs>()
        var i = 0
        while (i < lines.size) {
            val head = lines[i].trim()
            if (!head.startsWith(".method")) {
                i++
                continue
            }
            val startLine = i + 1
            val signature = head
            val isStatic = Regex("\\bstatic\\b").containsMatchIn(head)
            val paramRegs = Regex("\\(([^)]*)\\)").find(head)?.groupValues?.get(1)?.let {
                paramRegisterCount(it, isStatic)
            } ?: 0
            var declKind = ""
            var declared = 0
            var declLine = 0
            var maxV = -1
            var maxP = -1
            val vSeen = HashSet<Int>()
            val overflow = ArrayList<String>()
            var j = i + 1
            var total = -1
            while (j < lines.size) {
                val raw = lines[j]
                val t = raw.trim()
                if (t.startsWith(".end method")) break
                if (declKind.isEmpty()) {
                    Regex("^\\.(registers|locals)\\s+(\\d+)").find(t)?.let { m ->
                        declKind = m.groupValues[1]
                        declared = m.groupValues[2].toIntOrNull() ?: 0
                        declLine = j + 1
                        total = if (declKind == "registers") declared else declared + paramRegs
                    }
                }
                // 只扫指令行：以 : 开头的是标签，以 . 开头的是指令/元数据（.param 等也算，无害）
                if (!t.startsWith(":") && !t.startsWith(".")) {
                    for (m in regUse.findAll(raw)) {
                        val kind = m.groupValues[1]
                        val idx = m.groupValues[2].toIntOrNull() ?: continue
                        if (kind == "v") {
                            if (idx > maxV) maxV = idx
                            vSeen.add(idx)
                            if (total >= 0 && idx >= total) {
                                overflow.add("第 ${j + 1} 行 v$idx ≥ 总数 $total")
                            }
                        } else {
                            if (idx > maxP) maxP = idx
                            if (idx >= paramRegs) {
                                overflow.add("第 ${j + 1} 行 p$idx ≥ 参数寄存器 $paramRegs")
                            }
                        }
                    }
                }
                j++
            }
            out.add(
                MethodRegs(
                    signature = signature,
                    startLine = startLine,
                    declLine = declLine,
                    declKind = declKind,
                    declared = declared,
                    paramRegs = paramRegs,
                    totalRegs = total,
                    maxV = maxV,
                    maxP = maxP,
                    vCount = vSeen.size,
                    overflow = overflow,
                ),
            )
            i = j + 1
        }
        return out
    }

    /** 人类可读报告（界面直接显示）。 */
    fun report(smali: String): String {
        val list = analyze(smali)
        if (list.isEmpty()) return "这个方法文件里没有 .method —— 可能不是 smali，或编译产物被裁剪过。"
        val bad = list.count { !it.ok }
        val sb = StringBuilder()
        sb.append("共 ${list.size} 个方法")
        if (bad > 0) sb.append("，其中 $bad 个有问题") else sb.append("，寄存器使用都在声明范围内")
        sb.append('\n')
        for (m in list) {
            val name = m.signature.removePrefix(".method").trim()
            sb.append("\n• ").append(name).append('\n')
            if (m.declKind.isEmpty()) {
                sb.append("   没有 .registers / .locals 声明（第 ${m.startLine} 行起）\n")
            } else {
                sb.append("   声明 .${m.declKind} ${m.declared}")
                if (m.declKind == "locals") sb.append("（参数 ${m.paramRegs} → 总数 ${m.totalRegs}）")
                sb.append("  用到 v ${m.maxV.coerceAtLeast(0)}")
                sb.append(" / p ${m.maxP.coerceAtLeast(0)}")
                sb.append("  不同 v 寄存器 ${m.vCount} 个\n")
            }
            if (m.overflow.isNotEmpty()) {
                sb.append("   ⚠ 越界 ${m.overflow.size} 处：")
                sb.append(m.overflow.take(6).joinToString("；"))
                if (m.overflow.size > 6) sb.append("；…")
                sb.append('\n')
            }
        }
        return sb.toString().trimEnd()
    }

    // ------------------------------------------------------------- 寄存器扩充

    /**
     * 给光标所在方法扩充寄存器数量。只允许 [delta] > 0。
     *
     * @return (新文本, 说明)；找不到方法或没有声明行时返回 null。
     */
    fun expandRegisters(smali: String, caret: Int, delta: Int, preferLocals: Boolean = false): Pair<String, String>? {
        if (delta <= 0) return null
        // split 返回的是只读 List，后面要就地改声明行，必须转成 MutableList
        val lines = smali.split('\n').toMutableList()
        val caretLine = lineOfOffset(smali, caret) // 1 起
        // 找到包住光标的方法区间；没有就退化成第一个方法
        var start = -1
        var end = -1
        var i = 0
        while (i < lines.size) {
            if (lines[i].trimStart().startsWith(".method")) {
                val s = i
                var e = i
                while (e < lines.size && !lines[e].trimStart().startsWith(".end method")) e++
                if (caretLine in (s + 1)..(e + 1)) {
                    start = s
                    end = e
                    break
                }
                i = e
            }
            i++
        }
        if (start < 0) {
            i = 0
            while (i < lines.size) {
                if (lines[i].trimStart().startsWith(".method")) {
                    start = i
                    var e = i
                    while (e < lines.size && !lines[e].trimStart().startsWith(".end method")) e++
                    end = e
                    break
                }
                i++
            }
        }
        if (start < 0) return null

        val regRe = Regex("^(\\s*)\\.registers\\s+(\\d+)")
        val locRe = Regex("^(\\s*)\\.locals\\s+(\\d+)")
        for (k in start + 1 until end) {
            val line = lines[k]
            regRe.find(line)?.let { m ->
                val old = m.groupValues[2].toIntOrNull() ?: return null
                val newText = line.replaceFirst(Regex("\\.registers\\s+\\d+"), ".registers ${old + delta}")
                lines[k] = newText
                return lines.joinToString("\n") to
                    ".registers $old → ${old + delta}（第 ${k + 1} 行；p 寄存器按符号寻址，语义不变）"
            }
            locRe.find(line)?.let { m ->
                val old = m.groupValues[2].toIntOrNull() ?: return null
                lines[k] = line.replaceFirst(Regex("\\.locals\\s+\\d+"), ".locals ${old + delta}")
                return lines.joinToString("\n") to
                    ".locals $old → ${old + delta}（第 ${k + 1} 行；参数寄存器不受影响）"
            }
        }

        // 两种声明都没有：按偏好补一条。.locals 只能加在 .method 指令/元数据之后，这里直接插在
        // .method 下一行（baksmali 接受这个位置）。
        val kind = if (preferLocals) "locals" else "registers"
        val indent = lines[start].takeWhile { it.isWhitespace() }
        lines.add(start + 1, "$indent.$kind $delta")
        return lines.joinToString("\n") to
            "新增 .$kind $delta（原方法没有寄存器声明，已插在第 ${start + 2} 行）"
    }

    // ---------------------------------------------------------------- 工具函数

    /** 光标 offset → 行号（1 起）。 */
    internal fun lineOfOffset(text: String, offset: Int): Int {
        val end = offset.coerceIn(0, text.length)
        var n = 1
        for (k in 0 until end) if (text[k] == '\n') n++
        return n
    }

    /**
     * 方法签名的参数占用寄存器宽度：long/double 占 2，其余占 1；
     * 非 static 方法再加 1（this）。
     */
    internal fun paramRegisterCount(params: String, isStatic: Boolean): Int {
        var n = if (isStatic) 0 else 1
        var i = 0
        while (i < params.length) {
            when (params[i]) {
                'J', 'D' -> { n += 2; i++ }
                '[' -> {
                    // 数组：跳过所有维度，后面紧跟的宽类型仍算 1（数组是引用）
                    while (i < params.length && params[i] == '[') i++
                    if (i < params.length && params[i] == 'L') {
                        val semi = params.indexOf(';', i)
                        i = if (semi >= 0) semi + 1 else params.length
                    } else i++
                    n += 1
                }
                'L' -> {
                    val semi = params.indexOf(';', i)
                    i = if (semi >= 0) semi + 1 else params.length
                    n += 1
                }
                // 基本类型：Z B S C I F 各占 1（J/D 已在上面按 2 算）
                'Z', 'B', 'S', 'C', 'I', 'F' -> {
                    n += 1
                    i++
                }
                else -> i++
            }
        }
        return n
    }
}
