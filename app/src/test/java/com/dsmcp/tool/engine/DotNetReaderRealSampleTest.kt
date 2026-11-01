// SPDX-License-Identifier: GPL-3.0-or-later
//
// 用**真实** .NET 程序集验证 DotNetReader（含写操作）。
//
// 样本：System.Buffers 4.4.1 的 lib/netstandard2.0/System.Buffers.dll
//      （21,376 字节，MIT 许可，来自 nuget.org 的 System.Buffers 4.5.1 包）
// 之前只有手算的最小程序集（1024 字节、三张表各一行），它能证明"表流能走通"，
// 但证明不了 case 表宽度、coded index、#US 堆边界这些真实世界才会踩到的地方。
//
// 期望值全部由独立的 Python 实现（另一份解析器）在同一文件上算出，
// 不是从被测代码里抄的。
package com.dsmcp.tool.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DotNetReaderRealSampleTest {

    private fun sample(): ByteArray {
        val stream = javaClass.classLoader?.getResourceAsStream("dotnet/System.Buffers.dll")
        assertNotNull("测试样本缺失：src/test/resources/dotnet/System.Buffers.dll", stream)
        return stream!!.readBytes()
    }

    @Test
    fun detectsRealAssemblyVersion() {
        val d = sample()
        val detect = DotNetReader.detect(d)
        assertTrue("应当识别为 .NET 程序集", detect.optBoolean("isDotNet"))
        assertEquals(21376, detect.optInt("fileSize"))
        val types = DotNetReader.listTypes(d)
        assertEquals("TypeDef 行数", 10, types.optInt("count"))
    }

    @Test
    fun listsTypesWithNamespaceAndNesting() {
        val types = DotNetReader.listTypes(sample()).getJSONArray("types")
        val all = (0 until types.length()).map { types.getJSONObject(it).optString("fullName") }
        assertTrue(all.contains("System.Buffers.ArrayPool`1"))
        assertTrue(all.contains("System.SR"))
        // 第 5 行（1-based）就是 ArrayPool`1
        assertEquals("System.Buffers.ArrayPool`1", types.getJSONObject(4).optString("fullName"))
    }

    @Test
    fun listsMethodsWithRvaAndFileOffset() {
        val methods = DotNetReader.listMethods(sample(), 0).getJSONArray("methods")
        assertEquals("MethodDef 行数", 35, methods.length())
        val first = methods.getJSONObject(0)
        assertEquals("get_ResourceManager", first.optString("name"))
        assertEquals("0x2050", String.format("0x%x", first.optInt("rva")))
        // RVA 0x2050 落在 .text（VA 0x2000 / raw 0x200）→ 文件偏移 0x250
        assertEquals(0x250, first.optInt("fileOffset"))
    }

    @Test
    fun listsUserStringsWithOffsets() {
        val strings = DotNetReader.listStrings(sample(), 100).getJSONArray("strings")
        assertTrue(strings.length() >= 6)
        val byOffset = HashMap<String, String>()
        for (i in 0 until strings.length()) {
            val o = strings.getJSONObject(i)
            byOffset[o.optString("offset")] = o.optString("value")
        }
        assertEquals(", ", byOffset["0x1"])
        assertEquals("ArgumentException_BufferNotFromPool", byOffset["0x7"])
        assertEquals("maxArrayLength", byOffset["0x4f"])
    }

    @Test
    fun dumpsIlForRealMethod() {
        val dump = DotNetReader.dumpIl(sample(), 0x06000001)
        assertEquals("get_ResourceManager", dump.optString("method"))
        assertTrue("应当有 IL 字节", dump.optString("ilHex").isNotEmpty() || dump.optInt("codeSize") > 0)
    }

    @Test
    fun resolvesTokens() {
        val d = sample()
        val type = DotNetReader.resolveToken(d, 0x02000005)
        assertEquals("TypeDef", type.optString("table"))
        assertEquals("System.Buffers.ArrayPool`1", type.optString("resolved"))

        val method = DotNetReader.resolveToken(d, 0x06000001)
        assertEquals("MethodDef", method.optString("table"))
        assertEquals("System.SR::get_ResourceManager", method.optString("resolved"))

        val us = DotNetReader.resolveToken(d, 0x70000007)
        assertEquals("String", us.optString("table"))
        assertEquals("ArgumentException_BufferNotFromPool", us.optString("resolved"))

        val bad = DotNetReader.resolveToken(d, 0x0600FFFF)
        assertTrue("越界行号要给错误", bad.has("error"))
    }

    // ── 写操作 ──

    @Test
    fun editIlWritesInPlaceAndKeepsSize() {
        val d = sample()
        val m = DotNetReader.open(d)!!
        val body = DotNetReader.ilBody(d, m, 1)!!
        // 该方法用的是 tiny 头（首字节 0x6A → 0x6A>>2 = 26 字节 IL），代码紧跟在 1 字节头后
        assertEquals(0x02, d[body.fileOffset].toInt() and 0x03)
        val codeAt = body.fileOffset + 1
        val original = d[codeAt].toInt() and 0xff
        val replacement = original xor 0xFF

        val patched = DotNetReader.editIl(d, 0x06000001, 0, byteArrayOf(replacement.toByte()))
        assertNotNull("应当改写成功", patched)
        assertEquals("文件长度不能变", d.size, patched!!.size)
        var diff = 0
        for (i in d.indices) if (d[i] != patched[i]) diff++
        assertEquals("只应改动 1 个字节", 1, diff)
        assertEquals(replacement, patched[codeAt].toInt() and 0xff)
        assertEquals("原数组不能被就地改写", original, d[codeAt].toInt() and 0xff)
    }

    @Test
    fun editIlRejectsOutOfRange() {
        val d = sample()
        assertNull("超出 codeSize 要拒绝", DotNetReader.editIl(d, 0x06000001, 0x7FFF, byteArrayOf(0x00)))
        assertNull("非方法令牌要拒绝", DotNetReader.editIl(d, 0x02000005, 0, byteArrayOf(0x00)))
        assertNull("空补丁要拒绝", DotNetReader.editIl(d, 0x06000001, 0, ByteArray(0)))
    }

    @Test
    fun editStringShortensInPlace() {
        val d = sample()
        val patched = DotNetReader.editString(d, 0x07, "Zz")
        assertNotNull("等长改写应当成功", patched)
        assertEquals("文件长度不能变", d.size, patched!!.size)
        val m2 = DotNetReader.open(patched)!!
        assertEquals("Zz", m2.userStringAt(0x07))
        // 后续条目偏移不受影响
        assertEquals("maxArrayLength", m2.userStringAt(0x4f))
        val strings = DotNetReader.listStrings(patched, 100).getJSONArray("strings")
        val all = (0 until strings.length()).map { strings.getJSONObject(it).optString("value") }
        assertTrue(all.contains("maxArrayLength"))
    }

    @Test
    fun editStringRejectsLongerString() {
        val d = sample()
        // 0x1 处是 ", "（blob 只有 5 字节：4 字节 UTF-16 + 终结字节），任何长一点的串都放不下
        assertNull(
            "新串比原串长时必须拒绝（会让后续 ldstr 偏移失效）",
            DotNetReader.editString(d, 0x01, "这是一个非常非常非常长的字符串，长度远超原值，必须被拒绝掉"),
        )
        assertNull("偏移越界要拒绝", DotNetReader.editString(d, 0xFFFF, "x"))
        assertNull("不是条目起点要拒绝", DotNetReader.editString(d, 0x03, "x"))
    }

    @Test
    fun editStringHandlesUtf16Surrogates() {
        val d = sample()
        // emoji 在 UTF-16 里是代理对，长度按字节算要正确
        val patched = DotNetReader.editString(d, 0x07, "🙂x")
        assertNotNull(patched)
        val m2 = DotNetReader.open(patched!!)!!
        assertEquals("🙂x", m2.userStringAt(0x07))
    }
}
