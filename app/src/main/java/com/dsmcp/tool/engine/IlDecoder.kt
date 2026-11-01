// SPDX-License-Identifier: GPL-3.0-or-later
//
// CIL（IL）字节码解码器。opcode 表照 ECMA-335 III 抄，操作数宽度按规范给。
// 只做"读"，用来在 libdsmcp_native.so 不可用时替代 native 的 dotnet_dump_il / disasm。
package com.dsmcp.tool.engine

import org.json.JSONArray
import org.json.JSONObject

internal object IlDecoder {

    // 操作数类型
    private const val OP_NONE = 0
    private const val OP_I1 = 1      // ShortInlineI
    private const val OP_I2 = 2      // InlineVar（2 字节）
    private const val OP_I4 = 3      // InlineI / InlineBrTarget
    private const val OP_I8 = 4      // InlineI8
    private const val OP_R4 = 5      // ShortInlineR
    private const val OP_R8 = 6      // InlineR
    private const val OP_VAR1 = 7    // ShortInlineVar（1 字节）
    private const val OP_TOKEN = 8   // InlineString/Sig/Method/Field/Type/Tok
    private const val OP_SWITCH = 9

    /** 单字节 opcode 名（0x00..0xFF；0xFE 是前缀，单独处理）。 */
    private val ONE: Array<String?> = arrayOfNulls(256)

    /** 双字节 opcode 名（0xFE00..0xFE1E）。 */
    private val TWO: Array<String?> = arrayOfNulls(32)

    private val OP1 = IntArray(256)
    private val OP2 = IntArray(32)

    private fun def1(code: Int, name: String, op: Int = OP_NONE) {
        ONE[code] = name; OP1[code] = op
    }

    private fun def2(code: Int, name: String, op: Int = OP_NONE) {
        TWO[code] = name; OP2[code] = op
    }

    init {
        def1(0x00, "nop"); def1(0x01, "break")
        def1(0x02, "ldarg.0"); def1(0x03, "ldarg.1"); def1(0x04, "ldarg.2"); def1(0x05, "ldarg.3")
        def1(0x06, "ldloc.0"); def1(0x07, "ldloc.1"); def1(0x08, "ldloc.2"); def1(0x09, "ldloc.3")
        def1(0x0A, "stloc.0"); def1(0x0B, "stloc.1"); def1(0x0C, "stloc.2"); def1(0x0D, "stloc.3")
        def1(0x0E, "ldarg.s", OP_VAR1); def1(0x0F, "ldarga.s", OP_VAR1)
        def1(0x10, "starg.s", OP_VAR1)
        def1(0x11, "ldloc.s", OP_VAR1); def1(0x12, "ldloca.s", OP_VAR1); def1(0x13, "stloc.s", OP_VAR1)
        def1(0x14, "ldnull")
        def1(0x15, "ldc.i4.m1")
        for (i in 0..8) def1(0x16 + i, "ldc.i4.$i")
        def1(0x1F, "ldc.i4.s", OP_I1)
        def1(0x20, "ldc.i4", OP_I4)
        def1(0x21, "ldc.i8", OP_I8)
        def1(0x22, "ldc.r4", OP_R4)
        def1(0x23, "ldc.r8", OP_R8)
        def1(0x25, "dup"); def1(0x26, "pop")
        def1(0x27, "jmp", OP_TOKEN)
        def1(0x28, "call", OP_TOKEN)
        def1(0x29, "calli", OP_TOKEN)
        def1(0x2A, "ret")
        def1(0x2B, "br.s", OP_I1); def1(0x2C, "brfalse.s", OP_I1); def1(0x2D, "brtrue.s", OP_I1)
        def1(0x2E, "beq.s", OP_I1); def1(0x2F, "bge.s", OP_I1); def1(0x30, "bgt.s", OP_I1)
        def1(0x31, "ble.s", OP_I1); def1(0x32, "blt.s", OP_I1); def1(0x33, "bne.un.s", OP_I1)
        def1(0x34, "bge.un.s", OP_I1); def1(0x35, "bgt.un.s", OP_I1); def1(0x36, "ble.un.s", OP_I1)
        def1(0x37, "blt.un.s", OP_I1)
        def1(0x38, "br", OP_I4); def1(0x39, "brfalse", OP_I4); def1(0x3A, "brtrue", OP_I4)
        def1(0x3B, "beq", OP_I4); def1(0x3C, "bge", OP_I4); def1(0x3D, "bgt", OP_I4)
        def1(0x3E, "ble", OP_I4); def1(0x3F, "blt", OP_I4); def1(0x40, "bne.un", OP_I4)
        def1(0x41, "bge.un", OP_I4); def1(0x42, "bgt.un", OP_I4); def1(0x43, "ble.un", OP_I4)
        def1(0x44, "blt.un", OP_I4)
        def1(0x45, "switch", OP_SWITCH)
        val simple46 = arrayOf(
            "ldind.i1", "ldind.u1", "ldind.i2", "ldind.u2", "ldind.i4", "ldind.u4",
            "ldind.i8", "ldind.i", "ldind.r4", "ldind.r8", "ldind.ref",
            "stind.ref", "stind.i1", "stind.i2", "stind.i4", "stind.i8", "stind.r4", "stind.r8",
            "add", "sub", "mul", "div", "div.un", "rem", "rem.un", "and", "or", "xor",
            "shl", "shr", "shr.un", "neg", "not",
            "conv.i1", "conv.i2", "conv.i4", "conv.i8", "conv.r4", "conv.r8", "conv.u4", "conv.u8",
        )
        for (i in simple46.indices) def1(0x46 + i, simple46[i])
        def1(0x6F, "callvirt", OP_TOKEN)
        def1(0x70, "cpobj", OP_TOKEN)
        def1(0x71, "ldobj", OP_TOKEN)
        def1(0x72, "ldstr", OP_TOKEN)
        def1(0x73, "newobj", OP_TOKEN)
        def1(0x74, "castclass", OP_TOKEN)
        def1(0x75, "isinst", OP_TOKEN)
        def1(0x76, "conv.r.un")
        def1(0x79, "unbox", OP_TOKEN)
        def1(0x7A, "throw")
        def1(0x7B, "ldfld", OP_TOKEN)
        def1(0x7C, "ldflda", OP_TOKEN)
        def1(0x7D, "stfld", OP_TOKEN)
        def1(0x7E, "ldsfld", OP_TOKEN)
        def1(0x7F, "ldsflda", OP_TOKEN)
        def1(0x80, "stsfld", OP_TOKEN)
        def1(0x81, "stobj", OP_TOKEN)
        val simple82 = arrayOf(
            "conv.ovf.i1.un", "conv.ovf.i2.un", "conv.ovf.i4.un", "conv.ovf.i8.un",
            "conv.ovf.u1.un", "conv.ovf.u2.un", "conv.ovf.u4.un", "conv.ovf.u8.un",
            "conv.ovf.i.un", "conv.ovf.u.un",
        )
        for (i in simple82.indices) def1(0x82 + i, simple82[i])
        def1(0x8C, "box", OP_TOKEN)
        def1(0x8D, "newarr", OP_TOKEN)
        def1(0x8E, "ldlen")
        def1(0x8F, "ldelema", OP_TOKEN)
        val simple90 = arrayOf(
            "ldelem.i1", "ldelem.u1", "ldelem.i2", "ldelem.u2", "ldelem.i4", "ldelem.u4",
            "ldelem.i8", "ldelem.i", "ldelem.r4", "ldelem.r8", "ldelem.ref",
            "stelem.i", "stelem.i1", "stelem.i2", "stelem.i4", "stelem.i8",
            "stelem.r4", "stelem.r8", "stelem.ref",
        )
        for (i in simple90.indices) def1(0x90 + i, simple90[i])
        def1(0xA3, "ldelem.any", OP_TOKEN)
        def1(0xA4, "stelem.any", OP_TOKEN)
        def1(0xA5, "unbox.any", OP_TOKEN)
        val simpleB3 = arrayOf(
            "conv.ovf.i1", "conv.ovf.u1", "conv.ovf.i2", "conv.ovf.u2",
            "conv.ovf.i4", "conv.ovf.u4", "conv.ovf.i8", "conv.ovf.u8",
        )
        for (i in simpleB3.indices) def1(0xB3 + i, simpleB3[i])
        def1(0xC2, "refanyval", OP_TOKEN)
        def1(0xC3, "ckfinite")
        def1(0xC6, "mkrefany", OP_TOKEN)
        def1(0xD0, "ldtoken", OP_TOKEN)
        def1(0xD1, "conv.u2"); def1(0xD2, "conv.u1"); def1(0xD3, "conv.i")
        def1(0xD4, "conv.ovf.i"); def1(0xD5, "conv.ovf.u")
        def1(0xD6, "add.ovf"); def1(0xD7, "add.ovf.un")
        def1(0xD8, "mul.ovf"); def1(0xD9, "mul.ovf.un")
        def1(0xDA, "sub.ovf"); def1(0xDB, "sub.ovf.un")
        def1(0xDC, "endfinally")
        def1(0xDD, "leave", OP_I4)
        def1(0xDE, "leave.s", OP_I1)
        def1(0xDF, "stind.i")
        def1(0xE0, "conv.u")

        def2(0x00, "arglist")
        def2(0x01, "ceq"); def2(0x02, "cgt"); def2(0x03, "cgt.un")
        def2(0x04, "clt"); def2(0x05, "clt.un")
        def2(0x06, "ldftn", OP_TOKEN)
        def2(0x07, "ldvirtftn", OP_TOKEN)
        def2(0x09, "ldarg", OP_I2)
        def2(0x0A, "ldarga", OP_I2)
        def2(0x0B, "starg", OP_I2)
        def2(0x0C, "ldloc", OP_I2)
        def2(0x0D, "ldloca", OP_I2)
        def2(0x0E, "stloc", OP_I2)
        def2(0x0F, "localloc")
        def2(0x11, "endfilter")
        def2(0x12, "unaligned.", OP_I1)
        def2(0x13, "volatile.")
        def2(0x14, "tail.")
        def2(0x15, "initobj", OP_TOKEN)
        def2(0x16, "constrained.", OP_TOKEN)
        def2(0x17, "cpblk")
        def2(0x18, "initblk")
        def2(0x19, "no.", OP_I1)
        def2(0x1A, "rethrow")
        def2(0x1C, "sizeof", OP_TOKEN)
        def2(0x1D, "refanytype")
        def2(0x1E, "readonly.")
    }

    private fun u8(b: ByteArray, o: Int): Int = if (o < 0 || o >= b.size) 0 else b[o].toInt() and 0xff

    private fun i8(b: ByteArray, o: Int): Int {
        val v = u8(b, o)
        return if (v >= 0x80) v - 0x100 else v
    }

    private fun u16(b: ByteArray, o: Int): Int =
        if (o < 0 || o + 2 > b.size) 0 else u8(b, o) or (u8(b, o + 1) shl 8)

    private fun i32(b: ByteArray, o: Int): Int =
        if (o < 0 || o + 4 > b.size) 0
        else u8(b, o) or (u8(b, o + 1) shl 8) or (u8(b, o + 2) shl 16) or (u8(b, o + 3) shl 24)

    private fun i64(b: ByteArray, o: Int): Long =
        if (o < 0 || o + 8 > b.size) 0L
        else (i32(b, o).toLong() and 0xFFFFFFFFL) or (i32(b, o + 4).toLong() shl 32)

    private fun f32(b: ByteArray, o: Int): Float =
        if (o < 0 || o + 4 > b.size) 0f else java.lang.Float.intBitsToFloat(i32(b, o))

    private fun f64(b: ByteArray, o: Int): Double =
        if (o < 0 || o + 8 > b.size) 0.0 else java.lang.Double.longBitsToDouble(i64(b, o))

    private fun hexLabel(v: Int): String = "IL_" + Integer.toHexString(v).padStart(4, '0')

    /**
     * 解码 [code]（方法体），返回每个指令的 JSON（offset / 操作码 / 操作数文本）。
     *
     * 遇到未知 opcode 时按 `.byte` 继续单步前进 —— 被混淆过的 IL 里这种情况很常见，
     * 不能因此整段放弃。
     */
    fun decode(code: ByteArray, baseOffset: Int): JSONArray {
        val out = JSONArray()
        var p = 0
        var guard = 0
        while (p < code.size && guard < 200000) {
            guard++
            val at = p
            val b0 = u8(code, p)
            p += 1
            var name: String?
            var opKind: Int
            if (b0 == 0xFE) {
                val b1 = u8(code, p)
                p += 1
                name = if (b1 < TWO.size) TWO[b1] else null
                opKind = if (b1 < OP2.size) OP2[b1] else OP_NONE
            } else {
                name = ONE[b0]
                opKind = OP1[b0]
            }
            val o = JSONObject()
            o.put("offset", at)
            o.put("offsetLabel", hexLabel(at + baseOffset))
            if (name == null) {
                o.put("opcode", ".byte")
                o.put("operand", String.format("0x%02x", b0))
                out.put(o)
                continue
            }
            o.put("opcode", name)
            var operand = ""
            when (opKind) {
                OP_I1 -> {
                    operand = i8(code, p).toString()
                    p += 1
                }
                OP_I2 -> {
                    operand = u16(code, p).toString()
                    p += 2
                }
                OP_I4 -> {
                    val v = i32(code, p)
                    // 分支目标用 IL_ 标签表示，可读性远好于裸数字
                    operand = if (name.startsWith("br") || name == "leave") {
                        "${hexLabel(p + 4 + v)} (0x${(p + 4 + v).toString(16)})"
                    } else v.toString()
                    p += 4
                }
                OP_I8 -> {
                    operand = i64(code, p).toString()
                    p += 8
                }
                OP_R4 -> {
                    operand = f32(code, p).toString()
                    p += 4
                }
                OP_R8 -> {
                    operand = f64(code, p).toString()
                    p += 8
                }
                OP_VAR1 -> {
                    operand = u8(code, p).toString()
                    p += 1
                }
                OP_TOKEN -> {
                    val tok = i32(code, p)
                    operand = String.format("0x%08x", tok)
                    p += 4
                }
                OP_SWITCH -> {
                    val n = i32(code, p)
                    p += 4
                    if (n < 0 || n > 4096 || p + n * 4 > code.size) {
                        o.put("opcode", name)
                        o.put("operand", "switch (截断: count=$n)")
                        out.put(o)
                        break
                    }
                    val targets = StringBuilder()
                    val baseAt = p + n * 4
                    for (i in 0 until n) {
                        val rel = i32(code, p + i * 4)
                        if (i > 0) targets.append(", ")
                        targets.append(hexLabel(baseAt + rel))
                    }
                    operand = "$n -> [$targets]"
                    p += n * 4
                }
                else -> { /* 无操作数 */ }
            }
            if (operand.isNotEmpty()) o.put("operand", operand)
            out.put(o)
        }
        return out
    }
}
