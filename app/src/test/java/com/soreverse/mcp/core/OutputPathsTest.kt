package com.soreverse.mcp.core

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** OutputPaths 的判定逻辑（不碰 Android API：Context 入口是薄壳，核心在 resolveDir）。 */
class OutputPathsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun resolveDir_inputInsideCacheGoesToFilesDir() {
        val cache = tmp.newFolder("cache")
        val files = tmp.newFolder("files")
        val input = File(cache, "picked_apk.apk")

        val dir = OutputPaths.resolveDir(input.parentFile, cache.canonicalPath, files, "apk-sign")

        assertEquals(File(files, "apk-sign").canonicalPath, dir.canonicalPath)
    }

    @Test
    fun resolveDir_inputInWorkspaceStaysNextToIt() {
        val workspace = tmp.newFolder("workspace")
        val cache = tmp.newFolder("cache2")
        val files = tmp.newFolder("files2")
        val input = File(workspace, "app.apk")

        val dir = OutputPaths.resolveDir(input.parentFile, cache.canonicalPath, files, "apk-sign")

        assertEquals(workspace.canonicalPath, dir.canonicalPath)
    }

    @Test
    fun resolveDir_noParentGoesToFilesDir() {
        val files = tmp.newFolder("files3")

        val dir = OutputPaths.resolveDir(null, null, files, "dex-out")

        assertEquals(File(files, "dex-out").canonicalPath, dir.canonicalPath)
    }

    @Test
    fun resolveDir_siblingOfCacheIsNotTreatedAsInside() {
        // 前缀判断不能只看 startsWith("cache")：/data/.../cache-extra 不在 cache 里
        val root = tmp.newFolder("app")
        val cache = File(root, "cache").apply { mkdirs() }
        val cacheExtra = File(root, "cache-extra").apply { mkdirs() }
        val files = File(root, "files").apply { mkdirs() }
        val input = File(cacheExtra, "a.apk")

        val dir = OutputPaths.resolveDir(input.parentFile, cache.canonicalPath, files, "dex-out")

        assertEquals(cacheExtra.canonicalPath, dir.canonicalPath)
    }
}
