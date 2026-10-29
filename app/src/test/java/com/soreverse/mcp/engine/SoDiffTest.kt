// SPDX-License-Identifier: AGPL-3.0-only
//
// SoDiff: structural diff of two ELF files. Pinned here because the whole point
// of the tool is the *verdict* (identical / same-structure / structure-changed),
// and a wrong verdict is worse than no diff at all: "same-structure" on a file
// that grew a section would tell a user their two builds are equivalent.
package com.soreverse.mcp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoDiffTest {
    private val base = SynthElf.build()

    @Test
    fun identicalInputsAreIdentical() {
        val r = SoDiff.diff(base, base)
        val s = r.getJSONObject("summary")
        assertTrue(s.getBoolean("identical"))
        assertTrue(s.getBoolean("abiMatch"))
        assertEquals("identical", s.getString("verdict"))
        assertEquals(0L, s.getLong("sizeDelta"))
        assertEquals(0, s.getJSONObject("sections").getJSONArray("changed").length())
    }

    @Test
    fun contentOnlyByteFlipKeepsStructureVerdict() {
        // Flip one byte inside .rodata: file hash changes, structure does not.
        val elf = ElfParser(base).parse()
        val rodata = elf.sections.first { it.name == ".rodata" }
        val mutated = base.copyOf()
        val at = rodata.offset.toInt() + 1
        mutated[at] = (mutated[at].toInt() xor 0xFF).toByte()

        val r = SoDiff.diff(base, mutated)
        val s = r.getJSONObject("summary")
        assertFalse(s.getBoolean("identical"))
        assertEquals("same-structure", s.getString("verdict"))
        assertFalse(s.getBoolean("textChanged"))
    }

    @Test
    fun extraSectionIsStructureChanged() {
        val r = SoDiff.diff(base, SynthElf.build(withWxSection = true))
        val s = r.getJSONObject("summary")
        assertFalse(s.getBoolean("identical"))
        assertEquals("structure-changed", s.getString("verdict"))
        assertEquals(1, s.getJSONObject("sections").getInt("added"))
        assertEquals(".wx_evil", r.getJSONObject("sections").getJSONArray("added").getJSONObject(0).getString("name"))
    }

    @Test
    fun neededAndSonameAreReportedPerSide() {
        val r = SoDiff.diff(base, SynthElf.build(includeCrypto = true))
        assertEquals("libtest.so", r.getJSONObject("a").getString("soname"))
        assertEquals("libtest.so", r.getJSONObject("b").getString("soname"))
        assertTrue(r.getJSONObject("a").getLong("size") > 0L)
        assertEquals(0, r.getJSONObject("needed").getJSONArray("added").length())
        assertEquals(0, r.getJSONObject("needed").getJSONArray("removed").length())
    }

    @Test
    fun limitIsClampedAndFlagged() {
        val r = SoDiff.diff(base, SynthElf.build(withWxSection = true), limit = 0)
        assertEquals(1, r.getInt("limit"))
        // 只有 1 条新增节，正好落在 limit 内。
        assertFalse(r.getBoolean("truncated"))
    }
}
