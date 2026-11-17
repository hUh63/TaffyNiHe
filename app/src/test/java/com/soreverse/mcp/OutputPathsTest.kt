package com.soreverse.mcp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * OutputPaths 的两步判定（不碰 Android API：Context 入口是薄壳，判定全在 isInsideCache / productRoot）。
 */
class OutputPathsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun isInsideCache_fileDirectlyInCache() {
        val cache = tmp.newFolder("cache")

        assertTrue(OutputPaths.isInsideCache(File(cache, "picked_apk.apk").parentFile, cache.canonicalPath))
    }

    @Test
    fun isInsideCache_nestedFolderUnderCache() {
        val cache = tmp.newFolder("cache")
        val nested = File(cache, "picked/deep").apply { mkdirs() }

        assertTrue(OutputPaths.isInsideCache(nested, cache.canonicalPath))
    }

    @Test
    fun isInsideCache_siblingNamedCacheExtraIsNotInside() {
        // 前缀判断不能只看 startsWith("cache")：/data/.../cache-extra 不在 cache 里
        val root = tmp.newFolder("app")
        val cache = File(root, "cache").apply { mkdirs() }
        val cacheExtra = File(root, "cache-extra").apply { mkdirs() }

        assertFalse(OutputPaths.isInsideCache(cacheExtra, cache.canonicalPath))
    }

    @Test
    fun isInsideCache_falseWithoutParentOrCache() {
        val cache = tmp.newFolder("cache")

        assertFalse(OutputPaths.isInsideCache(null, cache.canonicalPath))
        assertFalse(OutputPaths.isInsideCache(tmp.newFolder("ws"), null))
    }

    @Test
    fun productRoot_usesWorkspaceTaffyOut() {
        val ws = tmp.newFolder("workspace")
        val files = tmp.newFolder("files")

        val root = OutputPaths.productRoot(ws.canonicalPath, files)

        assertEquals(File(ws, OutputPaths.OUTPUT_ROOT).canonicalPath, root.canonicalPath)
    }

    @Test
    fun productRoot_fallsBackToFilesDirWhenNoWorkspace() {
        val files = tmp.newFolder("files")

        assertEquals(files.canonicalPath, OutputPaths.productRoot(null, files).canonicalPath)
        assertEquals(files.canonicalPath, OutputPaths.productRoot("   ", files).canonicalPath)
    }

    @Test
    fun pruneFeatureDir_keepsNewestAndDropsOlder() {
        val dir = tmp.newFolder("apk-sign")
        repeat(5) { i ->
            val f = File(dir, "p$i.apk").apply { writeText("x") }
            f.setLastModified(1_000_000L + i * 1000L)
        }

        val removed = OutputPaths.pruneFeatureDir(dir, keep = 2, minAgeMs = 0)

        assertEquals(3, removed)
        assertEquals(listOf("p3.apk", "p4.apk"), dir.listFiles()!!.map { it.name }.sorted())
    }

    @Test
    fun pruneFeatureDir_protectsFreshFiles() {
        // 刚写出的那几份即使超出 keep 也不删（可能是本次要交给用户的那份）
        val dir = tmp.newFolder("aes-dex")
        val fresh = File(dir, "new.dex").apply { writeText("x") }
        val old = File(dir, "old.dex").apply { writeText("x") }
        old.setLastModified(System.currentTimeMillis() - 60 * 60 * 1000L)

        val removed = OutputPaths.pruneFeatureDir(dir, keep = 1, minAgeMs = 10 * 60 * 1000L)

        assertEquals(1, removed)
        assertTrue(fresh.isFile)
        assertFalse(old.isFile)
    }

    @Test
    fun pruneFeatureDir_ignoresMissingOrBelowLimit() {
        assertEquals(0, OutputPaths.pruneFeatureDir(null))
        assertEquals(0, OutputPaths.pruneFeatureDir(File(tmp.root, "nope")))
        val dir = tmp.newFolder("dex-out")
        File(dir, "out.dex").writeText("x")
        assertEquals(0, OutputPaths.pruneFeatureDir(dir, keep = 3, minAgeMs = 0))
    }
}
