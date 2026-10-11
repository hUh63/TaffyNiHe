package com.soreverse.mcp.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ElfInjectorTest {

    @Test
    fun alignUp_works() {
        assertEquals(0x5000L, ElfInjector.alignUp(0x4018L, 0x1000L))
        assertEquals(0x1000L, ElfInjector.alignUp(0x1000L, 0x1000L))
        assertEquals(0x4000L, ElfInjector.alignUp(0x3001L, 0x4000L))
    }

    @Test
    fun trampoline_x86_64_is_E9_rel32() {
        // rel = 0x2000 - (0x1000+5) = 0xFFB
        assertArrayEquals(
            byteArrayOf(0xE9.toByte(), 0xFB.toByte(), 0x0F, 0x00, 0x00),
            ElfInjector.encodeTrampoline(0x1000L, 0x2000L, "x86_64"),
        )
    }

    @Test
    fun trampoline_arm64_is_B_imm26() {
        // imm26 = (0x1000) >> 2 = 0x400 -> 0x14000400
        assertArrayEquals(
            byteArrayOf(0x00, 0x04, 0x00, 0x14),
            ElfInjector.encodeTrampoline(0x1000L, 0x2000L, "arm64"),
        )
    }

    @Test
    fun trampoline_arm_is_B_imm24() {
        // d = 0x2000-(0x1000+8) = 0xFF8 ; imm24 = 0x3FE -> 0xEA0003FE
        assertArrayEquals(
            byteArrayOf(0xFE.toByte(), 0x03, 0x00, 0xEA.toByte()),
            ElfInjector.encodeTrampoline(0x1000L, 0x2000L, "arm32"),
        )
    }

    @Test
    fun trampoline_thumb_is_BW() {
        // off = 0x2000-(0x1000+4) = 0xFFC -> hw1=0xF000 hw2=0xBFFE
        assertArrayEquals(
            byteArrayOf(0x00, 0xF0.toByte(), 0xFE.toByte(), 0xBF.toByte()),
            ElfInjector.encodeTrampoline(0x1000L, 0x2000L, "thumb"),
        )
    }

    /** 端到端：真编译一个 .so -> 注入新代码 + 入口 trampoline -> readelf 校验 + dlopen 真执行。 */
    @Test
    fun endToEnd_inject_and_execute() {
        val osArch = System.getProperty("os.arch") ?: ""
        assumeTrue("仅 x86_64 主机可跑", osArch == "amd64" || osArch == "x86_64")
        val gcc = which("gcc")
        assumeTrue("需要 gcc（CI/开发机）", gcc != null)

        val dir = Files.createTempDirectory("elfinject").toFile()
        try {
            File(dir, "a.c").writeText("int f(int x) { return x + 1; }\nint g(int y) { return f(y) * 2; }\n")
            run(listOf(gcc!!, "-shared", "-fPIC", "-O0", "-o", "a.so", "a.c"), dir)

            val src = File(dir, "a.so").readBytes()
            assertTrue("产物应为 ELF64", ElfInjector.elfClass(src) == ElfInjector.ELFCLASS64)
            val fVa = ElfInjector.findSymbolVa(src, "f")
            assumeTrue("未找到符号 f", fVa != null)

            // x86_64: mov eax,42 ; ret
            val code = byteArrayOf(0xB8.toByte(), 42, 0, 0, 0, 0xC3.toByte())
            val res = ElfInjector.inject(src, code, fVa!!, "x86_64")

            assertTrue("注入后应变大", res.elf.size > src.size)
            assertEquals("新段 VA 应在原所有段之后", 0L, res.codeVa and (res.pageAlign - 1))
            assertTrue("新 offset 应与新 VA 同余", (res.codeOffset.toLong() % res.pageAlign) == (res.codeVa % res.pageAlign))
            assertEquals("trampoline 应为 5 字节", 5, res.trampolineLength)
            // 结构自洽：搬表后仍能从新 ELF 解析出原符号
            assertEquals("搬表后符号表应仍可解析", fVa, ElfInjector.findSymbolVa(res.elf, "f"))

            File(dir, "b.so").writeBytes(res.elf)

            // readelf 校验：新增了可执行 LOAD
            val readelf = run(listOf("readelf", "-lW", "b.so"), dir)
            val reCount = Regex("R E").findAll(readelf).count()
            assertTrue("readelf 应显示至少 2 个 R E 段（原 .text + 新段），实际=$reCount\n$readelf", reCount >= 2)
            assertTrue("新段 VA 应出现在程序头里: 0x${java.lang.Long.toHexString(res.codeVa)}\n$readelf",
                readelf.contains("0x" + java.lang.Long.toHexString(res.codeVa)))

            // dlopen 真执行
            File(dir, "h.c").writeText(
                "#include <dlfcn.h>\n#include <stdio.h>\n" +
                    "int main(){void*h=dlopen(\"./b.so\",RTLD_NOW);if(!h){printf(\"ERR:%s\\n\",dlerror());return 1;}" +
                    "int(*f)(int)=(int(*)(int))dlsym(h,\"f\");if(!f){printf(\"ERR:no f\\n\");return 2;}" +
                    "printf(\"%d\\n\",f(1));return 0;}\n",
            )
            run(listOf(gcc, "-o", "h", "h.c", "-ldl"), dir)
            val stdout = run(listOf("./h"), dir).trim()
            assertEquals("注入代码应被执行并返回 42，实际输出=$stdout", "42", stdout)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun which(cmd: String): String? = try {
        val p = ProcessBuilder("sh", "-c", "command -v $cmd").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        if (out.isBlank()) null else out.lines().first()
    } catch (e: Exception) {
        null
    }

    private fun run(cmd: List<String>, dir: File): String {
        val p = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }
}
