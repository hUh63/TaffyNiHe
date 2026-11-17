package com.soreverse.mcp.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class SafeFileIoTest {

    private val tmp: File = Files.createTempDirectory("taffy-safeio").toFile()

    @After
    fun cleanup() {
        runCatching { tmp.deleteRecursively() }
    }

    @Test
    fun copiesUntilEof() {
        val src = ByteArray(5000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val r = SafeFileIo.copy(ByteArrayInputStream(src), out, maxBytes = 8192)
        assertEquals(5000L, r.bytesCopied)
        assertFalse(r.truncated)
        assertTrue(r.reachedEof)
        assertTrue(src.contentEquals(out.toByteArray()))
    }

    @Test
    fun abortsWhenOverLimit() {
        val src = ByteArray(4096) { 1 }
        val out = ByteArrayOutputStream()
        var thrown = false
        try {
            SafeFileIo.copy(ByteArrayInputStream(src), out, maxBytes = 1024)
        } catch (e: PayloadTooLargeException) {
            thrown = true
            assertEquals(1024L, e.maxBytes)
        }
        assertTrue("expected PayloadTooLargeException", thrown)
    }

    @Test
    fun truncatesWhenAsked() {
        val src = ByteArray(4096) { 2 }
        val out = ByteArrayOutputStream()
        val r = SafeFileIo.copy(
            ByteArrayInputStream(src), out, maxBytes = 1024, policy = SafeFileIo.OverflowPolicy.TRUNCATE,
        )
        assertEquals(1024L, r.bytesCopied)
        assertTrue(r.truncated)
        assertFalse(r.reachedEof)
        assertEquals(1024, out.size())
    }

    @Test
    fun exactLimitIsNotTruncated() {
        val src = ByteArray(1024) { 3 }
        val out = ByteArrayOutputStream()
        val r = SafeFileIo.copy(ByteArrayInputStream(src), out, maxBytes = 1024)
        assertEquals(1024L, r.bytesCopied)
        assertFalse(r.truncated)
        assertTrue(r.reachedEof)
    }

    @Test
    fun deleteTreeNoFollowRemovesRegularTree() {
        val root = File(tmp, "tree")
        File(root, "a/b").mkdirs()
        File(root, "a/b/c.txt").writeText("x")
        File(root, "d.txt").writeText("y")
        assertEquals(0, SafeFileIo.deleteTreeNoFollow(root))
        assertFalse(root.exists())
    }

    @Test
    fun deleteDoesNotFollowSymlinkOutOfTree() {
        val outside = File(tmp, "outside").apply { mkdirs() }
        File(outside, "keep.txt").writeText("keep")
        val root = File(tmp, "root").apply { mkdirs() }
        val link = File(root, "link")
        val created = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        if (!created) return // 某些文件系统不允许建链接：跳过而不是失败

        assertEquals(0, SafeFileIo.deleteTreeNoFollow(root))
        assertFalse(root.exists())
        assertTrue("外部目录不能被删除", outside.exists())
        assertTrue("外部文件不能被删除", File(outside, "keep.txt").exists())
    }

    @Test
    fun childrenNoFollowSkipsSymlinks() {
        val root = File(tmp, "list").apply { mkdirs() }
        File(root, "real.txt").writeText("a")
        File(tmp, "target").mkdirs()
        val created = runCatching { Files.createSymbolicLink(File(root, "alias").toPath(), File(tmp, "target").toPath()) }.isSuccess
        val names = SafeFileIo.childrenNoFollow(root).map { it.name }.toSet()
        assertTrue(names.contains("real.txt"))
        if (created) assertFalse("符号链接应被排除", names.contains("alias"))
    }

    @Test
    fun copyTreeNoFollowSkipsLinks() {
        val src = File(tmp, "src").apply { mkdirs() }
        File(src, "sub").mkdirs()
        File(src, "sub/a.txt").writeText("hello")
        File(tmp, "ext").mkdirs()
        File(tmp, "ext/secret.txt").writeText("secret")
        val created = runCatching { Files.createSymbolicLink(File(src, "link").toPath(), File(tmp, "ext").toPath()) }.isSuccess

        val dst = File(tmp, "dst")
        val skipped = mutableListOf<String>()
        val (copied, skip) = SafeFileIo.copyTreeNoFollow(src, dst, skipped)
        assertEquals(1, copied)
        assertTrue(File(dst, "sub/a.txt").exists())
        assertFalse(File(dst, "ext/secret.txt").exists())
        if (created) {
            assertTrue(skip >= 1)
            assertFalse(File(dst, "link").exists())
        }
    }
}
