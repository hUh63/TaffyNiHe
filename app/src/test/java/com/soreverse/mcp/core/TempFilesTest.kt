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
    fun sweepDir_keepNamesProtectsTheFileInUse() {
        // DEX 浏览器场景：选单个 .dex 时传进来的就是 cacheDir 里的 dexx_input_* 副本，
        // 清理不能把它自己删掉，否则紧接着解析就报「文件不存在」。
        val root = tmp.newFolder("cache4")
        val inUse = File(root, "dexx_input_100_classes.dex").apply {
            writeBytes(ByteArray(3))
            setLastModified(1_000L)
        }
        val leftover = File(root, "dexx_ab12_classes.dex").apply {
            writeBytes(ByteArray(5))
            setLastModified(2_000L)
        }

        val (removed, freed) = TempFiles.sweepDir(
            root, listOf("dexx_"), keepRecent = 0, keepNames = setOf(inUse.name), minAgeMs = 0,
        )

        assertEquals(1, removed)
        assertEquals(5L, freed)
        assertTrue(inUse.exists())
        assertFalse(leftover.exists())
    }

    @Test
    fun sweepDir_minAgeProtectsFreshFiles() {
        val root = tmp.newFolder("cache5")
        val fresh = File(root, "picked_fresh.apk").apply {
            writeBytes(ByteArray(2))
            setLastModified(System.currentTimeMillis())
        }
        val stale = File(root, "picked_stale.apk").apply {
            writeBytes(ByteArray(7))
            setLastModified(System.currentTimeMillis() - 3_600_000L)
        }

        val (removed, freed) = TempFiles.sweepDir(
            root, listOf("picked_"), keepRecent = 0, minAgeMs = 600_000L,
        )

        assertEquals(1, removed)
        assertEquals(7L, freed)
        assertTrue(fresh.exists())
        assertFalse(stale.exists())
    }

    @Test
    fun sweepCopies_keepsOnlyTheNamedFile() {
        val root = tmp.newFolder("cache6")
        val keep = File(root, "dexx_input_9_a.dex").apply {
            writeBytes(ByteArray(1))
            setLastModified(1_000L)
        }
        val other1 = File(root, "dexx_input_8_b.dex").apply {
            writeBytes(ByteArray(1))
            setLastModified(2_000L)
        }
        val other2 = File(root, "dexx_ff_c.dex").apply {
            writeBytes(ByteArray(1))
            setLastModified(3_000L)
        }

        val (removed, _) = TempFiles.sweepCopies(root, "dexx_", keep.name)

        assertEquals(2, removed)
        assertTrue(keep.exists())
        assertFalse(other1.exists())
        assertFalse(other2.exists())
    }

    @Test
    fun sweepDir_rootIsAFileIsNoop() {
        // cacheDir 理论上不会是文件，但 sweepCache 会把任何拿到的路径丢进来：
        // 这里保证不会因为「拿错路径」反而去删别的东西或抛异常。
        val notADir = File(tmp.root, "cache-file.txt").apply { writeText("x") }

        val (removed, freed) = TempFiles.sweepDir(notADir, listOf("picked_"), keepRecent = 0)

        assertEquals(0, removed)
        assertEquals(0L, freed)
        assertTrue(notADir.exists())
    }

    @Test
    fun sweepDir_emptyPrefixListTouchesNothing() {
        val root = tmp.newFolder("cache7")
        val f = File(root, "picked_a.apk").apply {
            writeBytes(ByteArray(4))
            setLastModified(1_000L)
        }

        val (removed, _) = TempFiles.sweepDir(root, emptyList(), keepRecent = 0)

        assertEquals(0, removed)
        assertTrue(f.exists())
    }

    @Test
    fun sweepDir_negativeKeepRecentBehavesLikeZero() {
        val root = tmp.newFolder("cache8")
        val f = File(root, "vip_a.apk").apply {
            writeBytes(ByteArray(6))
            setLastModified(1_000L)
        }

        val (removed, freed) = TempFiles.sweepDir(root, listOf("vip_"), keepRecent = -3)

        assertEquals(1, removed)
        assertEquals(6L, freed)
        assertFalse(f.exists())
    }

    @Test
    fun sweepDir_missingRootIsNoop() {
        val (removed, freed) = TempFiles.sweepDir(File(tmp.root, "nope"), listOf("picked_"), 1)
        assertEquals(0, removed)
        assertEquals(0L, freed)
    }
}
