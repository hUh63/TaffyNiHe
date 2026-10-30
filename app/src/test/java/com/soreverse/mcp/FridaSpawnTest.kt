package com.soreverse.mcp

import com.soreverse.mcp.engine.DBusMessage
import com.soreverse.mcp.engine.Gvariant
import com.soreverse.mcp.engine.SpawnOpt
import com.soreverse.mcp.engine.bodyUint
import com.soreverse.mcp.engine.isSpawnMode
import com.soreverse.mcp.engine.spawnBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frida spawn 的 body 字节编码。
 *
 * 所有期望值都来自 GLib 的权威产物（`g_dbus_message_to_blob()` 取 body 段），不是手推的。
 * 生成方式见文件末尾注释——GLib 版本变化或有人改 Gvariant.W 时，重跑那段即可重新对账。
 *
 * 这里钉死的三个坑（都真实踩过）：
 *  1. `a{sv}` 数据起点要 **8 对齐**：漏了它，短包名（"x"）会编码成 12 字节而正确值是 16；
 *  2. 变长值（`s` / `as`）前面有 framing 槽，`s` 报**不含 NUL** 的长度、`as` 报**含各元素 NUL**
 *     的总字节数；
 *  3. 定长值（`u`）**没有** framing 槽，多写 4 个 0 会让后面的 dict entry 整体错位。
 */
class FridaSpawnTest {

    private fun hex(s: String): ByteArray =
        s.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    private fun assertBytes(expectedHex: String, actual: ByteArray, label: String) {
        val want = expectedHex.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }
        assertEquals("$label size", want.size, actual.size)
        assertEquals(label, want, actual.toList())
    }

    @Test
    fun spawnModeAcceptsCommonSpellings() {
        assertTrue(isSpawnMode("spawn"))
        assertTrue(isSpawnMode("SPAWN"))
        assertTrue(isSpawnMode(" spawn "))
        assertTrue(isSpawnMode("spawn-suspended"))
        assertTrue(isSpawnMode("spawn_suspended"))
        assertFalse(isSpawnMode("attach"))
        assertFalse(isSpawnMode(""))
        assertFalse(isSpawnMode("attached"))
    }

    /** 空 options：不同长度的 program 名会对齐到不同的总长，这条最容易写错。 */
    @Test
    fun emptyOptionsMatchGlibForEveryNameLength() {
        assertBytes(
            "01 00 00 00 61 00 00 00 00 00 00 00 00 00 00 00 ",
            spawnBody("a"),
            "len1",
        )
        assertBytes(
            "02 00 00 00 61 62 00 00 00 00 00 00 00 00 00 00 ",
            spawnBody("ab"),
            "len2",
        )
        assertBytes(
            "03 00 00 00 61 62 63 00 00 00 00 00 00 00 00 00 ",
            spawnBody("abc"),
            "len3",
        )
        assertBytes(
            "04 00 00 00 61 62 63 64 00 00 00 00 00 00 00 00 ",
            spawnBody("abcd"),
            "len4",
        )
        assertBytes(
            "07 00 00 00 61 62 63 64 65 66 67 00 00 00 00 00 ",
            spawnBody("abcdefg"),
            "len7",
        )
        assertBytes(
            "05 00 00 00 63 6f 6d 2e 61 00 00 00 00 00 00 00 ",
            spawnBody("com.a"),
            "com.a",
        )
        assertBytes(
            "08 00 00 00 63 6f 6d 2e 65 78 61 6d 00 00 00 00 00 00 00 00 " +
                    "00 00 00 00 ",
            spawnBody("com.exam"),
            "len8",
        )
        assertBytes(
            "0f 00 00 00 63 6f 6d 2e 65 78 61 6d 70 6c 65 2e 61 70 70 00 " +
                    "00 00 00 00 ",
            spawnBody("com.example.app"),
            "len15",
        )
    }

    @Test
    fun stringOptionMatchesGlib() {
        assertBytes(
            "05 00 00 00 63 6f 6d 2e 61 00 00 00 14 00 00 00 03 00 00 00 " +
                    "63 77 64 00 01 73 00 00 03 00 00 00 61 62 63 00 ",
            spawnBody("com.a", listOf("cwd" to SpawnOpt.Str("abc"))),
            "cwd=abc",
        )
        assertBytes(
            "05 00 00 00 63 6f 6d 2e 61 00 00 00 15 00 00 00 03 00 00 00 " +
                    "63 77 64 00 01 73 00 00 04 00 00 00 61 62 63 64 00 ",
            spawnBody("com.a", listOf("cwd" to SpawnOpt.Str("abcd"))),
            "cwd=abcd",
        )
        assertBytes(
            "05 00 00 00 63 6f 6d 2e 61 00 00 00 11 00 00 00 03 00 00 00 " +
                    "63 77 64 00 01 73 00 00 00 00 00 00 00 ",
            spawnBody("com.a", listOf("cwd" to SpawnOpt.Str(""))),
            "cwd=empty",
        )
    }

    @Test
    fun uintOptionMatchesGlib() {
        assertBytes(
            "05 00 00 00 63 6f 6d 2e 61 00 00 00 14 00 00 00 05 00 00 00 " +
                    "73 74 64 69 6f 00 01 75 00 00 00 00 01 00 00 00 ",
            spawnBody("com.a", listOf("stdio" to SpawnOpt.U32(1))),
            "stdio=1",
        )
    }

    @Test
    fun stringArrayOptionMatchesGlib() {
        assertBytes(
            "05 00 00 00 63 6f 6d 2e 61 00 00 00 14 00 00 00 04 00 00 00 " +
                    "61 72 67 76 00 02 61 73 00 00 00 00 00 00 00 00 ",
            spawnBody("com.a", listOf("argv" to SpawnOpt.StrArray(emptyList()))),
            "argv=[]",
        )
        assertBytes(
            "05 00 00 00 63 6f 6d 2e 61 00 00 00 1c 00 00 00 04 00 00 00 " +
                    "65 6e 76 70 00 02 61 73 00 00 00 00 08 00 00 00 03 00 00 00 " +
                    "41 3d 31 00 ",
            spawnBody("com.a", listOf("envp" to SpawnOpt.StrArray(listOf("A=1")))),
            "envp=[A=1]",
        )
    }

    @Test
    fun multipleOptionsMatchGlib() {
        assertBytes(
            "05 00 00 00 63 6f 6d 2e 61 00 00 00 2c 00 00 00 05 00 00 00 " +
                    "73 74 64 69 6f 00 01 75 00 00 00 00 00 00 00 00 00 00 00 00 " +
                    "03 00 00 00 63 77 64 00 01 73 00 00 03 00 00 00 61 62 63 00 ",
            spawnBody(
                    "com.a",
                    listOf("stdio" to SpawnOpt.U32(0), "cwd" to SpawnOpt.Str("abc")),
                ),
            "stdio+cwd",
        )
        assertBytes(
            "05 00 00 00 63 6f 6d 2e 61 00 00 00 50 00 00 00 04 00 00 00 " +
                    "61 72 67 76 00 02 61 73 00 00 00 00 17 00 00 00 05 00 00 00 " +
                    "63 6f 6d 2e 61 00 00 00 06 00 00 00 2d 2d 66 6c 61 67 00 00 " +
                    "00 00 00 00 03 00 00 00 63 77 64 00 01 73 00 00 0f 00 00 00 " +
                    "2f 64 61 74 61 2f 6c 6f 63 61 6c 2f 74 6d 70 00 ",
            spawnBody(
                    "com.a",
                    listOf(
                        "argv" to SpawnOpt.StrArray(listOf("com.a", "--flag")),
                        "cwd" to SpawnOpt.Str("/data/local/tmp"),
                    ),
                ),
            "argv+cwd",
        )
    }

    /**
     * HostSpawnOptions 的完整字段集（argv / envp / env / cwd / stdio / aux）。
     *
     * env 与 envp 的区别在 frida 侧：envp 是**替换**整个环境，env 是**追加**；
     * aux 是唯一的嵌套结构（`a{sv}`），它的 variant 值还要再套一层 framing，
     * 所以单独钉四个 case（字符串 / 整数 / 字符串数组 / 空字典）加一个六字段全发。
     */
    @Test
    fun spawnOptionsCoverAllSixHostFields() {
        assertBytes(
            "05 00 00 00 63 6f 6d 2e 61 00 00 00 18 00 00 00 03 00 00 00 65 6e " +
            "76 00 02 61 73 00 08 00 00 00 03 00 00 00 41 3d 31 00 ",
            spawnBody(
                    "com.a",
                    listOf("env" to SpawnOpt.StrArray(listOf("A=1"))),
                ),
            "env",
        )
        assertBytes(
            "05 00 00 00 63 6f 6d 2e 61 00 00 00 2a 00 00 00 03 00 00 00 61 75 " +
            "78 00 05 61 7b 73 76 7d 00 00 12 00 00 00 00 00 00 00 01 00 00 00 " +
            "6b 00 01 73 00 00 00 00 01 00 00 00 76 00 ",
            spawnBody(
                    "com.a",
                    listOf("aux" to SpawnOpt.Dict(mapOf("k" to SpawnOpt.Str("v")))),
                ),
            "aux{k:v}",
        )
        assertBytes(
            "05 00 00 00 63 6f 6d 2e 61 00 00 00 28 00 00 00 03 00 00 00 61 75 " +
            "78 00 05 61 7b 73 76 7d 00 00 10 00 00 00 00 00 00 00 01 00 00 00 " +
            "6e 00 01 75 00 00 00 00 07 00 00 00 ",
            spawnBody(
                    "com.a",
                    listOf("aux" to SpawnOpt.Dict(mapOf("n" to SpawnOpt.U32(7)))),
                ),
            "aux{n:7}",
        )
        assertBytes(
            "05 00 00 00 63 6f 6d 2e 61 00 00 00 3a 00 00 00 03 00 00 00 61 75 " +
            "78 00 05 61 7b 73 76 7d 00 00 22 00 00 00 00 00 00 00 04 00 00 00 " +
            "6c 69 73 74 00 02 61 73 00 00 00 00 0e 00 00 00 01 00 00 00 61 00 " +
            "00 00 01 00 00 00 62 00 ",
            spawnBody(
                    "com.a",
                    listOf(
                        "aux" to SpawnOpt.Dict(
                            mapOf("list" to SpawnOpt.StrArray(listOf("a", "b")))
                        )
                    ),
                ),
            "aux{list:[a,b]}",
        )
        assertBytes(
            "05 00 00 00 63 6f 6d 2e 61 00 00 00 18 00 00 00 03 00 00 00 61 75 " +
            "78 00 05 61 7b 73 76 7d 00 00 00 00 00 00 00 00 00 00 ",
            spawnBody(
                    "com.a",
                    listOf("aux" to SpawnOpt.Dict(emptyMap())),
                ),
            "aux{}",
        )
        assertBytes(
            "05 00 00 00 63 6f 6d 2e 61 00 00 00 c2 00 00 00 04 00 00 00 61 72 " +
            "67 76 00 02 61 73 00 00 00 00 10 00 00 00 03 00 00 00 61 70 70 00 " +
            "03 00 00 00 2d 2d 78 00 00 00 00 00 04 00 00 00 65 6e 76 70 00 02 " +
            "61 73 00 00 00 00 0f 00 00 00 0a 00 00 00 50 41 54 48 3d 2f 73 62 " +
            "69 6e 00 00 00 00 00 00 03 00 00 00 65 6e 76 00 02 61 73 00 08 00 " +
            "00 00 03 00 00 00 41 3d 31 00 03 00 00 00 63 77 64 00 01 73 00 00 " +
            "04 00 00 00 2f 74 6d 70 00 00 00 00 05 00 00 00 73 74 64 69 6f 00 " +
            "01 75 00 00 00 00 00 00 00 00 00 00 00 00 03 00 00 00 61 75 78 00 " +
            "05 61 7b 73 76 7d 00 00 12 00 00 00 00 00 00 00 01 00 00 00 6b 00 " +
            "01 73 00 00 00 00 01 00 00 00 76 00 ",
            spawnBody(
                    "com.a",
                    listOf(
                        "argv" to SpawnOpt.StrArray(listOf("app", "--x")),
                        "envp" to SpawnOpt.StrArray(listOf("PATH=/sbin")),
                        "env" to SpawnOpt.StrArray(listOf("A=1")),
                        "cwd" to SpawnOpt.Str("/tmp"),
                        "stdio" to SpawnOpt.U32(0),
                        "aux" to SpawnOpt.Dict(mapOf("k" to SpawnOpt.Str("v"))),
                    ),
                ),
            "all6",
        )
    }

    /**
     * aux 的嵌套字典：`a{sv}` 的变长值本身又是一个 `a{sv}`，要在 variant 里再套一层
     * framing + 8 对齐。早先嵌套对象是 json 序列化当字符串带的（frida 侧收到的是文本），
     * 这三个 case 钉住「按真实类型写」的字节形状。
     */
    @Test
    fun nestedAuxEncodesRecursively() {
        assertBytes(
            "05 00 00 00 63 6f 6d 2e 61 00 00 00 42 00 00 00 03 00 00 00 61 75 " +
            "78 00 05 61 7b 73 76 7d 00 00 2a 00 00 00 00 00 00 00 03 00 00 00 " +
            "63 66 67 00 05 61 7b 73 76 7d 00 00 12 00 00 00 00 00 00 00 01 00 " +
            "00 00 6b 00 01 73 00 00 00 00 01 00 00 00 76 00 ",
            spawnBody(
                    "com.a",
                    listOf("aux" to SpawnOpt.Dict(mapOf("cfg" to SpawnOpt.Dict(mapOf("k" to SpawnOpt.Str("v")))))),
                ),
            "nested aux",
        )
        assertBytes(
            "05 00 00 00 63 6f 6d 2e 61 00 00 00 5a 00 00 00 03 00 00 00 61 75 " +
            "78 00 05 61 7b 73 76 7d 00 00 42 00 00 00 00 00 00 00 04 00 00 00 " +
            "64 65 65 70 00 05 61 7b 73 76 7d 00 2a 00 00 00 00 00 00 00 03 00 " +
            "00 00 63 66 67 00 05 61 7b 73 76 7d 00 00 12 00 00 00 00 00 00 00 " +
            "01 00 00 00 6b 00 01 73 00 00 00 00 01 00 00 00 76 00 ",
            spawnBody(
                    "com.a",
                    listOf(
                        "aux" to SpawnOpt.Dict(
                            mapOf(
                                "deep" to SpawnOpt.Dict(
                                    mapOf("cfg" to SpawnOpt.Dict(mapOf("k" to SpawnOpt.Str("v"))))
                                )
                            )
                        )
                    ),
                ),
            "two-level aux",
        )
        assertBytes(
            "05 00 00 00 63 6f 6d 2e 61 00 00 00 6a 00 00 00 03 00 00 00 61 75 " +
            "78 00 05 61 7b 73 76 7d 00 00 52 00 00 00 00 00 00 00 03 00 00 00 " +
            "6d 69 78 00 05 61 7b 73 76 7d 00 00 3a 00 00 00 00 00 00 00 01 00 " +
            "00 00 6e 00 01 75 00 00 00 00 07 00 00 00 01 00 00 00 6c 00 02 61 " +
            "73 00 00 00 06 00 00 00 01 00 00 00 61 00 00 00 01 00 00 00 73 00 " +
            "01 73 00 00 00 00 01 00 00 00 78 00 ",
            spawnBody(
                    "com.a",
                    listOf(
                        "aux" to SpawnOpt.Dict(
                            mapOf(
                                "mix" to SpawnOpt.Dict(
                                    mapOf(
                                        "n" to SpawnOpt.U32(7),
                                        "l" to SpawnOpt.StrArray(listOf("a")),
                                        "s" to SpawnOpt.Str("x"),
                                    )
                                )
                            )
                        )
                    ),
                ),
            "mixed aux",
        )
    }

    @Test
    fun bodyUintReadsLittleEndianPid() {
        // 1337 = 0x539
        val msg = DBusMessage(2, 0, 1, emptyMap(), byteArrayOf(0x39, 0x05, 0, 0))
        assertEquals(1337, bodyUint(msg))
    }

    @Test
    fun bodyUintRejectsShortBody() {
        val msg = DBusMessage(2, 0, 1, emptyMap(), byteArrayOf(1, 2))
        assertEquals(-1, bodyUint(msg))
    }

    @Test
    fun gvariantStringKeepsFourByteAlignment() {
        val w = Gvariant.W()
        w.u32(7)
        w.str("ab")
        assertEquals(4 + 4 + 2 + 1, w.b.size())
        val b = w.b.toByteArray()
        assertEquals(2, b[4].toInt())
        assertEquals('a'.code.toByte(), b[8])
        assertEquals(0, b[10].toInt())
    }
}

/*
 * golden 怎么重新生成（在装了 libglib2.0-dev 的机器上）：
 *
 *   GDBusMessage *m = g_dbus_message_new_method_call("re.frida.HostSession17",
 *       "/re/frida/HostSession", "re.frida.HostSession17", "Spawn");
 *   GVariantBuilder o; g_variant_builder_init(&o, G_VARIANT_TYPE("a{sv}"));
 *   g_variant_builder_add(&o, "{sv}", "cwd", g_variant_new_string("abc"));
 *   g_dbus_message_set_body(m, g_variant_new("(s@a{sv})", "com.a", g_variant_builder_end(&o)));
 *   guchar *blob = g_dbus_message_to_blob(m, &size, G_DBUS_CAPABILITY_FLAGS_NONE, NULL);
 *   // body 起点 = align8(16 + header_fields_len)，长度 = 位于 blob+4 的 u32
 */
