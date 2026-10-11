package com.soreverse.mcp.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkZipToolTest {

    private fun buildApk(apk: File, extraSo: File) {
        ZipOutputStream(apk.outputStream()).use { z ->
            fun put(n: String, b: ByteArray) {
                z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry()
            }
            put("AndroidManifest.xml", byteArrayOf(1, 2, 3))
            put("classes.dex", "DEXDATA".toByteArray())
            put("lib/arm64-v8a/liba.so", "OLD".toByteArray())
            put("lib/x86/libb.so", "OLDB".toByteArray())
            put("META-INF/MANIFEST.MF", "sig".toByteArray())
            put("META-INF/CERT.SF", "sig".toByteArray())
            put("META-INF/CERT.RSA", "sig".toByteArray())
            if (extraSo.exists()) put("assets/keep.bin", byteArrayOf(7))
        }
    }

    @Test
    fun replaceEntry_replaces_and_drops_signatures() {
        val dir = Files.createTempDirectory("apkzip").toFile()
        try {
            val apk = File(dir, "in.apk")
            buildApk(apk, File(dir, "none"))
            val src = File(dir, "new.so").apply { writeBytes("NEWBYTES".toByteArray()) }
            val out = File(dir, "out.apk")

            val r = ApkZipTool.replaceEntry(apk, out, "lib/arm64-v8a/liba.so", src.readBytes(), false)
            assertTrue("应替换成功", r.replaced)
            assertFalse("不应为新增", r.added)

            java.util.zip.ZipFile(out).use { z ->
                val e = z.getEntry("lib/arm64-v8a/liba.so")
                assertNotNull("目标条目应存在", e)
                assertEquals("NEWBYTES", String(z.getInputStream(e).readBytes()))
                val dex = z.getEntry("classes.dex")
                assertNotNull(dex)
                assertEquals("DEXDATA", String(z.getInputStream(dex).readBytes()))
                assertNotNull("其它条目应保留", z.getEntry("AndroidManifest.xml"))
                assertNull("旧 v1 签名 .RSA 应被丢弃", z.getEntry("META-INF/CERT.RSA"))
                assertNull("旧 v1 签名 .MF 应被丢弃", z.getEntry("META-INF/MANIFEST.MF"))
                assertNull("旧 v1 签名 .SF 应被丢弃", z.getEntry("META-INF/CERT.SF"))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun replaceEntry_addIfMissing() {
        val dir = Files.createTempDirectory("apkzip").toFile()
        try {
            val apk = File(dir, "in.apk")
            buildApk(apk, File(dir, "none"))
            val out = File(dir, "out.apk")

            val r = ApkZipTool.replaceEntry(apk, out, "lib/armeabi-v7a/libnew.so", byteArrayOf(9, 8, 7), true)
            assertTrue(r.replaced)
            assertTrue(r.added)

            java.util.zip.ZipFile(out).use { z ->
                val e = z.getEntry("lib/armeabi-v7a/libnew.so")
                assertNotNull("新增条目应存在", e)
                assertEquals(3, z.getInputStream(e).readBytes().size)
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
