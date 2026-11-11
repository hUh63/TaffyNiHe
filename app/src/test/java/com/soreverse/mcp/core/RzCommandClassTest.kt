package com.soreverse.mcp.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 命令链分类：决定「能不能缓存」与「要不要清缓存」。
 *
 * 这两件事错了会直接变成用户可见的错数据：
 *  - 把 `aaaa` 当只读 → 分析跑完不清缓存，列表还是空的；
 *  - 把 `s …; af; …` 当只读 → 结果被缓存住，改了字节也不刷新。
 */
class RzCommandClassTest {

    @Test
    fun analysisCommandsAreMutating() {
        assertEquals(RzCmdKind.MUTATING, classifyRzCommand("aaaa"))
        assertEquals(RzCmdKind.MUTATING, classifyRzCommand("aaa"))
        assertEquals(RzCmdKind.MUTATING, classifyRzCommand("aac"))
        assertEquals(RzCmdKind.MUTATING, classifyRzCommand("af"))
        assertEquals(RzCmdKind.MUTATING, classifyRzCommand("af+ 0x1000"))
        assertEquals(RzCmdKind.MUTATING, classifyRzCommand("afn new_name"))
        assertEquals(RzCmdKind.MUTATING, classifyRzCommand("axc 0x10 0x20"))
    }

    @Test
    fun mutatingSegmentAnywhereInChainDisqualifiesCache() {
        // 旧实现只看第一段：这条被当成只读缓存了，而 af 改了分析数据库。
        assertEquals(RzCmdKind.MUTATING, classifyRzCommand("s 0x1234; af; pdfj"))
        assertEquals(RzCmdKind.MUTATING, classifyRzCommand("e scr.color=0; s 0x10; af; axtj"))
        assertEquals(RzCmdKind.MUTATING, classifyRzCommand("s 0x10; aac; agCj"))
    }

    @Test
    fun configThatChangesAnalysisIsMutating() {
        assertEquals(RzCmdKind.MUTATING, classifyRzCommand("e asm.arch=arm"))
        assertEquals(RzCmdKind.MUTATING, classifyRzCommand("e analysis.timeout=5"))
        assertEquals(RzCmdKind.MUTATING, classifyRzCommand("e graph.layout=1"))
        assertEquals(RzCmdKind.MUTATING, classifyRzCommand("e bin.demangle=false"))
    }

    @Test
    fun pureReadChainsAreCacheable() {
        assertEquals(RzCmdKind.READ_ONLY, classifyRzCommand("pdfj"))
        assertEquals(RzCmdKind.READ_ONLY, classifyRzCommand("s 0x1234; axtj"))
        assertEquals(RzCmdKind.READ_ONLY, classifyRzCommand("s 0x1234; axfj"))
        assertEquals(RzCmdKind.READ_ONLY, classifyRzCommand("e scr.color=0; s 0x1234; pDj 4000"))
        assertEquals(RzCmdKind.READ_ONLY, classifyRzCommand("agf json; s 0x2000; pdfj"))
        assertEquals(RzCmdKind.READ_ONLY, classifyRzCommand("aflj; izj; isj"))
        assertEquals(RzCmdKind.READ_ONLY, classifyRzCommand("s 0x10; afvj"))
        assertEquals(RzCmdKind.READ_ONLY, classifyRzCommand("p8 4096 @ .text"))
        assertEquals(RzCmdKind.READ_ONLY, classifyRzCommand("iSj"))
    }

    @Test
    fun unknownCommandsAreNeitherCachedNorInvalidating() {
        assertEquals(RzCmdKind.UNKNOWN, classifyRzCommand("wx 9090 @ 0x100"))
        assertEquals(RzCmdKind.UNKNOWN, classifyRzCommand("?v"))
        assertEquals(RzCmdKind.UNKNOWN, classifyRzCommand(""))
        assertEquals(RzCmdKind.UNKNOWN, classifyRzCommand("   ;  ; "))
    }

    @Test
    fun helpersMatchClassify() {
        assertEquals(true, isRzReadOnlyCommand("s 0x10; pdfj"))
        assertEquals(false, isRzReadOnlyCommand("aaaa"))
        assertEquals(true, mutatesRzAnalysis("aaaa"))
        assertEquals(false, mutatesRzAnalysis("pdfj"))
        assertEquals(false, mutatesRzAnalysis("wx 9090 @ 0x100"))
    }
}
