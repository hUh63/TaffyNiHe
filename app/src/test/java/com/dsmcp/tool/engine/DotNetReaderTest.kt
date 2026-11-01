// SPDX-License-Identifier: GPL-3.0-or-later
//
// DotNetReader 回归测试。样本是 make_minimal_assembly.py 合成的**最小** PE + ECMA-335
// 程序集（1024 字节，只有 Module / TypeDef / MethodDef 各 1 行），不依赖任何 .NET 工具链。
//
// 这一层的价值在于：.NET 工具的只读能力原本完全依赖 libdsmcp_native.so，而这个库在
// 16 KiB page size 的设备上会被 bionic 拒绝加载。Kotlin 兜底如果解析错了，用户看到的是
// 「这个 dll 里没有类型」而不是报错 —— 所以正例断言必须精确到名字。
package com.dsmcp.tool.engine

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DotNetReaderTest {

    /** 合成的最小程序集：My.Namespace.MyType / .ctor。 */
    private fun minimalAssembly(): ByteArray = Base64.getDecoder().decode(
        "TVoAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAFBFAABMAQEAAAAA" +
            "AAAAAAAAAAAA4AACIQsBCAAAAgAAAAIAAAAAAAAAIAAAACAAAAAgAAAAQAAAABAAAAACAAAEAAAAAAAAAAQAAAAAAAAAAEAAAAAC" +
            "AAAAAAAAAwAAAAAAEAAAEAAAAAAQAAAQAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAA" +
            "SAAAAAAAAAAAAAAALnRleHQAAAAAAgAAACAAAAACAAAAAgAAAAAAAAAAAAAAAAAAIAAAYAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABIAAAAAgAFAEggAACz" +
            "AAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABCU0pCAQABAAAAAAAMAAAA" +
            "djQuMC4zMDMxOQAAAAACAD0AAABKAAAAI34AAIgAAAArAAAAI1N0cmluZ3MAAAAAAAIAAAFFAAAAAAAAAAAAAAAAAAAAAQAAAAEA" +
            "AAABAAAAAAABAAAAAAAAAAEAAAAeABEAAAABAAEAACEAAAAAhhglAAAAAQAAADxNaW5pbWFsTW9kdWxlPgBNeS5OYW1lc3BhY2UA" +
            "TXlUeXBlAC5jdG9yAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="
    )

    @Test
    fun detectsMinimalAssembly() {
        val info = DotNetReader.detect(minimalAssembly())
        assertTrue("应识别为 .NET 程序集", info.getBoolean("isDotNet"))
        assertTrue(info.getBoolean("hasMZHeader"))
        assertTrue(info.getBoolean("hasPEHeader"))
        assertTrue(info.getBoolean("peValid"))
        assertTrue(info.getBoolean("hasClrDataDir"))
        assertTrue(info.getBoolean("bsjbFound"))
        assertTrue(info.getBoolean("hasTablesStream"))
        assertTrue(info.getBoolean("hasStringsStream"))
        assertEquals(1, info.getInt("typeCount"))
        assertEquals(1, info.getInt("methodCount"))
        assertEquals("<MinimalModule>", info.getString("moduleName"))
        assertEquals("2.5", info.getString("runtimeVersion"))
    }

    @Test
    fun listsTypeWithNamespace() {
        val res = DotNetReader.listTypes(minimalAssembly())
        assertEquals(1, res.getInt("count"))
        val t = res.getJSONArray("types").getJSONObject(0)
        assertEquals("MyType", t.getString("name"))
        assertEquals("My.Namespace", t.getString("namespace"))
        assertEquals("My.Namespace.MyType", t.getString("fullName"))
        assertFalse(t.getBoolean("isNested"))
    }

    @Test
    fun listsMethodWithDeclaringType() {
        val res = DotNetReader.listMethods(minimalAssembly(), 0)
        assertEquals(1, res.getInt("count"))
        val m = res.getJSONArray("methods").getJSONObject(0)
        assertEquals(".ctor", m.getString("name"))
        assertEquals("My.Namespace.MyType", m.getString("declaringType"))
        assertEquals(0x2100L, m.getLong("rva"))
        // 类型过滤：命中 / 不命中各一次
        assertEquals(1, DotNetReader.listMethods(minimalAssembly(), 0x02000001).getInt("count"))
        assertEquals(0, DotNetReader.listMethods(minimalAssembly(), 0x02000002).getInt("count"))
    }

    @Test
    fun listsUserStringsWithoutThrowing() {
        // 这个最小样本没有 #US 流，应当返回空列表而不是抛异常
        val res = DotNetReader.listStrings(minimalAssembly(), 100)
        assertEquals(0, res.getInt("count"))
    }

    @Test
    fun rejectsNonPeInputCleanly() {
        val junk = ByteArray(64) { (it * 7).toByte() }
        val info = DotNetReader.detect(junk)
        assertFalse(info.getBoolean("isDotNet"))
        assertFalse(info.getBoolean("peValid"))
        val types = DotNetReader.listTypes(junk)
        assertTrue("非程序集要给出 error 字段", types.has("error"))
    }

    @Test
    fun truncatedAndCorruptedInputsDoNotThrow() {
        val full = minimalAssembly()
        // 逐个截断长度 + 若干随机破坏：解析器必须返回 null/error，不能抛任何 Throwable
        for (len in intArrayOf(0, 1, 2, 0x3C, 0x40, 0x100, 0x200, 0x248, full.size - 1)) {
            if (len > full.size) continue
            val part = full.copyOf(len)
            DotNetReader.detect(part)
            DotNetReader.listTypes(part)
            DotNetReader.listMethods(part, 0)
            DotNetReader.listStrings(part, 10)
            DotNetReader.dumpIl(part, 0x06000001)
        }
        val mutated = full.copyOf()
        for (p in intArrayOf(0x3C, 0x44, 0x58, 0x200, 0x248, 0x24C, 0x270)) {
            if (p >= mutated.size) continue
            mutated[p] = 0xFF.toByte()
            DotNetReader.detect(mutated)
            DotNetReader.listTypes(mutated)
            mutated[p] = full[p]
        }
    }
}
