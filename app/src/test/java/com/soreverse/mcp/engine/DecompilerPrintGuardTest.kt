package com.soreverse.mcp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `pdg`/`pdgo` 最后一步是 rz_core_annotated_code_print()，它无条件解引用进程级 console
 * context（rizin readme 的 issue #37）。命令网关不能成为进这个崩溃的第二条路。
 *
 * 网关经 rz_core_cmd_str 执行，会按 ';' 与换行切段，所以整串前缀检查不够；而
 * JSON/语句打印器不读该 context，必须保留（拒绝它们等于删能力而不是修 bug）。
 */
class DecompilerPrintGuardTest {

    @Test
    fun refusesPlainAnnotatedPrints() {
        assertEquals("pdg", annotatedPrintSegment("pdg"))
        assertEquals("pdg @ 0x1040", annotatedPrintSegment("pdg @ 0x1040"))
        assertEquals("pdgo", annotatedPrintSegment("pdgo"))
        assertEquals("pdgo @ 0x1040", annotatedPrintSegment("pdgo @ 0x1040"))
        assertEquals("pdgd", annotatedPrintSegment("pdgd"))
        assertEquals("pdgx", annotatedPrintSegment("pdgx"))
    }

    @Test
    fun refusesAfterEverySeparatorRizinSplits() {
        assertEquals("pdg", annotatedPrintSegment("s 0x1040;pdg"))
        assertEquals("pdg", annotatedPrintSegment("s 0x1040; pdg"))
        assertEquals("pdg", annotatedPrintSegment("s 0x1040\npdg"))
        assertEquals("pdg", annotatedPrintSegment("\n  pdg @ 0x1040"))
    }

    @Test
    fun allowsJsonStatementAndSleighPrinters() {
        assertNull(annotatedPrintSegment("pdgj"))
        assertNull(annotatedPrintSegment("pdgj @ 0x1040"))
        assertNull(annotatedPrintSegment("pdg*"))
        assertNull(annotatedPrintSegment("pdgs"))
        assertNull(annotatedPrintSegment("pdgs @ 0x1040"))
    }

    @Test
    fun allowsNonDecompilerCommands() {
        assertNull(annotatedPrintSegment("pd 32"))
        assertNull(annotatedPrintSegment("pdf @ 0x1040"))
        assertNull(annotatedPrintSegment("afl"))
        assertNull(annotatedPrintSegment("s 0x1040;axtj"))
        assertNull(annotatedPrintSegment(""))
        assertNull(annotatedPrintSegment(";"))
    }

    @Test
    fun ignoresTheWordWhenItIsNotTheCommand() {
        assertNull(annotatedPrintSegment("izz~pdg"))
        assertNull(annotatedPrintSegment("CCu \"call pdg here\" @ 0x1040"))
        // 决定的是命令词本身：pdg~foo 与 pdg? 仍然会先打印再被过滤/回答，所以照样拒绝。
        assertEquals("pdg~foo", annotatedPrintSegment("pdg~foo"))
        assertEquals("pdg?", annotatedPrintSegment("pdg?"))
        // pdgz 不是 rizin 命令；未知的 pdg 前缀词按失败关闭处理。
        assertEquals("pdgz", annotatedPrintSegment("pdgz"))
    }
}
