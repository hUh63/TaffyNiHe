package com.soreverse.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * EditorCheckpoint / FileStampUtil 纯 JVM 单测。
 *
 * 只用 java.io + 临时目录，**不碰 Android Context、不碰 org.json**：
 * store 的核心逻辑都落在 `pushAt / listAt / readAt / clearAt`（入参是 File），
 * Context 版只是 `root(context, path)` 包一层。
 */
class EditorCheckpointTest {

    private fun tempDir(): File = Files.createTempDirectory("ckpt").toFile()

    @Test
    fun push_then_list_and_read() {
        val dir = tempDir()
        val meta = EditorCheckpointStore.pushAt(dir, "hello\nworld", "手动保存")
        assertNotNull(meta)
        assertEquals(2, meta!!.lines)
        assertEquals("手动保存", meta.label)
        val list = EditorCheckpointStore.listAt(dir)
        assertEquals(1, list.size)
        assertEquals(meta.id, list[0].id)
        assertEquals("hello\nworld", EditorCheckpointStore.readAt(dir, meta.id))
    }

    @Test
    fun push_dedupes_identical_content() {
        val dir = tempDir()
        assertNotNull(EditorCheckpointStore.pushAt(dir, "same", "a"))
        assertNull(EditorCheckpointStore.pushAt(dir, "same", "b"))
        assertEquals(1, EditorCheckpointStore.listAt(dir).size)
    }

    @Test
    fun push_newContent_isKept() {
        val dir = tempDir()
        assertNotNull(EditorCheckpointStore.pushAt(dir, "v1", "a"))
        assertNotNull(EditorCheckpointStore.pushAt(dir, "v2", "b"))
        assertEquals(2, EditorCheckpointStore.listAt(dir).size)
    }

    @Test
    fun ring_keepsOnlyMaxCount_oldestDropped() {
        val dir = tempDir()
        val total = EditorCheckpointStore.MAX_COUNT + 6
        var last: CheckpointMeta? = null
        for (i in 1..total) {
            last = EditorCheckpointStore.pushAt(dir, "content-$i", "c$i")
        }
        val list = EditorCheckpointStore.listAt(dir)
        assertEquals(EditorCheckpointStore.MAX_COUNT, list.size)
        // 最新一份在最前
        assertEquals(last!!.id, list[0].id)
        // 正文文件 + index.txt：恰好 MAX_COUNT 份正文
        assertEquals(EditorCheckpointStore.MAX_COUNT + 1, dir.listFiles().orEmpty().count { it.name.endsWith(".txt") })
        assertNull(EditorCheckpointStore.readAt(dir, list.minOf { it.id } - 1))
    }

    @Test
    fun ring_alsoCappedByBytes() {
        val dir = tempDir()
        // 每份 ~700KB，4MB 上限 → 最多 5~6 份
        val chunk = "x".repeat(700 * 1024)
        for (i in 1..12) {
            EditorCheckpointStore.pushAt(dir, chunk + i, "big$i")
        }
        val list = EditorCheckpointStore.listAt(dir)
        val bytes = list.sumOf { it.sizeBytes }
        assertTrue("count=${list.size} bytes=$bytes", list.size < EditorCheckpointStore.MAX_COUNT)
        assertTrue("bytes=$bytes", bytes <= EditorCheckpointStore.MAX_BYTES)
    }

    @Test
    fun clear_removesEverything() {
        val dir = tempDir()
        EditorCheckpointStore.pushAt(dir, "a", "x")
        EditorCheckpointStore.clearAt(dir)
        assertTrue(EditorCheckpointStore.listAt(dir).isEmpty())
        assertFalse(dir.exists())
    }

    @Test
    fun list_missingDir_isEmptyNotCrash() {
        assertTrue(EditorCheckpointStore.listAt(File(tempDir(), "nope")).isEmpty())
    }

    @Test
    fun keyOf_isSha256Prefix_ofPathNotRawName() {
        val a = EditorCheckpointStore.keyOf("/data/local/tmp/a.kt")
        val b = EditorCheckpointStore.keyOf("/data/local/tmp/b.kt")
        assertEquals(16, a.length)
        assertTrue(a != b)
        // 十六进制（目录名合法）
        assertTrue(a.matches(Regex("^[0-9a-f]{16}$")))
    }

    // ── FileStampUtil ──

    @Test
    fun stamp_missingFile_isNull() {
        assertNull(FileStampUtil.stamp(File(tempDir(), "gone.txt")))
    }

    @Test
    fun stamp_reportsSizeAndHash() {
        val f = File(tempDir(), "a.txt")
        f.writeText("abc")
        val st = FileStampUtil.stamp(f)
        assertNotNull(st)
        assertEquals(3L, st!!.size)
        assertEquals(64, st.sha256.length)
    }

    @Test
    fun changed_unknownKnown_isNeverConflict() {
        // known == null 视为未知（新建文件 / SAF 打开），不判冲突
        assertFalse(FileStampUtil.changed(null, FileStamp(1L, 1L, "x")))
        assertFalse(FileStampUtil.changed(null, null))
    }

    @Test
    fun changed_vanishedFile_isConflict() {
        assertTrue(FileStampUtil.changed(FileStamp(1L, 1L, "x"), null))
    }

    @Test
    fun changed_sameStamp_noConflict() {
        val f = File(tempDir(), "a.txt")
        f.writeText("abc")
        val st = FileStampUtil.stamp(f)
        assertFalse(FileStampUtil.changed(st, FileStampUtil.stamp(f)))
    }

    @Test
    fun changed_externalEdit_isConflict() {
        val f = File(tempDir(), "a.txt")
        f.writeText("abc")
        val st = FileStampUtil.stamp(f)
        // 模拟外部进程改内容（大小也变）
        f.writeText("abcdefg")
        assertTrue(FileStampUtil.changed(st, FileStampUtil.stamp(f)))
    }

    @Test
    fun changed_sameSizeDifferentContent_isConflictByHash() {
        val f = File(tempDir(), "a.txt")
        f.writeText("aaaa")
        val st = FileStampUtil.stamp(f)
        f.writeText("bbbb")
        val now = FileStampUtil.stamp(f)
        // mtime 有 1s 级精度时同大小同秒可能相等，但 hash 必须不同 → 判冲突
        assertTrue(FileStampUtil.changed(st, now))
    }

    @Test
    fun changed_bothHashesEmpty_fallsBackToMeta() {
        val a = FileStamp(1L, 10L, "")
        assertFalse(FileStampUtil.changed(a, FileStamp(1L, 10L, "")))
        assertTrue(FileStampUtil.changed(a, FileStamp(2L, 10L, "")))
        assertTrue(FileStampUtil.changed(a, FileStamp(1L, 11L, "")))
    }
}
