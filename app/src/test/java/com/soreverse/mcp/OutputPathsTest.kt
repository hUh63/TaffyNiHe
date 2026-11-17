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
}
