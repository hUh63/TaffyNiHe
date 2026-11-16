package com.soreverse.mcp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** TempFiles 纯文件系统部分（不碰 Android API，用临时目录跑）。 */
class TempFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun sweepDir_keepsNewestPerPrefixAndIgnoresOthers() {
        val root = tmp.newFolder("cache")
        val old = File(root, "picked_a.apk").apply {
            writeBytes(ByteArray(10))
            setLastModified(1_000L)
        }
        val newest = File(root, "picked_b.apk").apply {
            writeBytes(ByteArray(20))
            setLastModified(9_000L)
        }
        // 不在名单里的不能被删：tunnel_creds.json 删了隧道就没了
        val outsider = File(root, "tunnel_creds.json").apply {
            writeText("x")
            setLastModified(1L)
        }

        val (removed, freed) = TempFiles.sweepDir(root, listOf("picked_"), keepRecent = 1)

        assertEquals(1, removed)
        assertEquals(10L, freed)
        assertFalse(old.exists())
        assertTrue(newest.exists())
        assertTrue(outsider.exists())
    }

    @Test
    fun sweepDir_zeroKeepRemovesAll() {
        val root = tmp.newFolder("cache2")
        File(root, "vip_a.apk").writeBytes(ByteArray(4))
        File(root, "vip_b.apk").writeBytes(ByteArray(6))

        val (removed, freed) = TempFiles.sweepDir(root, listOf("vip_"), keepRecent = 0)

        assertEquals(2, removed)
        assertEquals(10L, freed)
        assertEquals(0, root.listFiles()?.size)
    }

    @Test
    fun sweepDir_removesDirectoryRecursively() {
        val root = tmp.newFolder("cache3")
        val dir = File(root, "dexinspect_work").apply { mkdirs() }
        File(dir, "a.dex").writeBytes(ByteArray(5))
        File(dir, "nested").mkdirs()
        File(File(dir, "nested"), "b.dex").writeBytes(ByteArray(7))

        val (removed, freed) = TempFiles.sweepDir(root, listOf("dexinspect_"), keepRecent = 0)

        assertEquals(1, removed)
        assertEquals(12L, freed)
        assertFalse(dir.exists())
    }

    @Test
    fun sweepDir_missingRootIsNoop() {
        val (removed, freed) = TempFiles.sweepDir(File(tmp.root, "nope"), listOf("picked_"), 1)
        assertEquals(0, removed)
        assertEquals(0L, freed)
    }
}
