package com.soreverse.mcp.core

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 塔菲逆核: DEX 脱壳(内存 dump)。原理同 frida-dexdump —— 加固壳最终要把真实 dex
 * 解密加载进内存交给 ART,我们在 root 下扫描目标进程内存,把内存里的 dex
 * (magic = "dex\n0xx")整块 dump 出来,绕过磁盘上被加密/抽取的壳。
 *
 * 架构(职责分离,保证正确性):
 *  - root shell 只干最少的事: 读 /proc/<pid>/maps、用 dd 抠内存段并 base64 输出。
 *    数据走 su 的 stdout 管道回来,不落临时文件,规避 SELinux 读权限坑。
 *  - dex magic 扫描 / header 解析 / 完整 dex 截取,全部在 Kotlin 里做(可控可测)。
 *
 * 局限(如实说明):
 *  - 一代整体壳 / 二代内存加载(含 InMemoryDexClassLoader): dump 效果好。
 *  - 三代指令抽取(方法体被抽走): dump 出的 dex 方法体是 nop,需主动调用/修复,本工具不做修复。
 *  - 需目标 App 先启动且执行到 dex 已加载(建议先手动打开目标 App 再脱)。
 */
object UnpackManager {

    // dex 文件魔数: 'd''e''x''\n' = 64 65 78 0A,后跟 3 位版本 + 0x00。
    private val DEX_MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0A)

    /** 单个内存段最大扫描字节(超过则跳过,避免 base64 管道内存爆掉)。 */
    private const val MAX_REGION_BYTES = 64L * 1024 * 1024

    /** dex 合理大小上限(100MB)。 */
    private const val MAX_DEX_BYTES = 100L * 1024 * 1024

    /** 解析包名 → 运行中的 pid 列表(可能多进程)。 */
    fun findPids(pkg: String): List<Int> {
        // 安全修复: 对包名进行清洗，防止命令注入。
        // 原实现直接将 pkg 拼入 pidof '$pkg' 和 grep '$pkg'，
        // 攻击者可通过单引号逃逸执行任意 root 命令。
        val safePkg = RootShell.sanitizeShellArg(pkg)
        val r = RootShell.exec("pidof '$safePkg' 2>/dev/null", timeoutSec = 10)
        val pids = LinkedHashSet<Int>()
        r.stdout.split(Regex("\\s+")).forEach { tok ->
            tok.trim().toIntOrNull()?.let { if (it > 0) pids.add(it) }
        }
        if (pids.isEmpty()) {
            // 退路: ps 按名字找
            val r2 = RootShell.exec("ps -A -o PID,NAME 2>/dev/null | grep '$safePkg'", timeoutSec = 10)
            r2.stdout.lineSequence().forEach { line ->
                line.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()?.let { if (it > 0) pids.add(it) }
            }
        }
        return pids.toList()
    }

    /** /proc/pid/maps 里一个候选内存段。 */
    private data class Region(val start: Long, val end: Long, val path: String) {
        val size: Long get() = end - start
    }

    /** 读 maps,筛出可能藏解密 dex 的可读段(匿名 / dalvik / ashmem,排除磁盘文件映射)。 */
    private fun candidateRegions(pid: Int): List<Region> {
        val r = RootShell.exec("cat /proc/$pid/maps 2>/dev/null", timeoutSec = 15)
        if (!r.success) return emptyList()
        val out = ArrayList<Region>()
        r.stdout.lineSequence().forEach { line ->
            // 形如: 12c00000-12e00000 rw-p 00000000 00:00 0  [anon:dalvik-main space]
            val parts = line.trim().split(Regex("\\s+"), limit = 6)
            if (parts.size < 5) return@forEach
            val range = parts[0]
            val perms = parts[1]
            if (perms.isEmpty() || perms[0] != 'r') return@forEach
            val path = parts.getOrNull(5)?.trim().orEmpty()
            // 排除磁盘文件映射(那些 dex/so/oat 磁盘上已有,不必从内存扒);
            // 只留匿名段或 dalvik/ashmem 命名段(解密 dex 的典型落点)。
            val keep = path.isEmpty() ||
                path.startsWith("[anon") ||
                path.contains("dalvik", ignoreCase = true) ||
                path.contains("ashmem", ignoreCase = true) ||
                path.contains("InMemory", ignoreCase = true)
            if (!keep) return@forEach
            val dash = range.indexOf('-')
            if (dash <= 0) return@forEach
            val start = range.substring(0, dash).toLongOrNull(16) ?: return@forEach
            val end = range.substring(dash + 1).toLongOrNull(16) ?: return@forEach
            if (end > start) out.add(Region(start, end, path))
        }
        return out
    }

    data class DumpResult(
        val success: Boolean,
        val pkg: String,
        val pid: Int,
        val outputDir: String,
        val dexFiles: List<Pair<String, Long>>,
        val scannedRegions: Int,
        val skippedLarge: Int,
        val message: String,
    )

    /**
     * 从目标进程内存脱出 dex。
     * @param pkg    目标包名
     * @param outDir dump 输出目录(应用私有 filesDir 下)
     * @param minDexSize 有效 dex 最小字节(过滤碎片),默认 0x70(dex header 长度)
     */
    fun dumpDex(pkg: String, outDir: File, minDexSize: Int = 0x70): DumpResult {
        if (!RootShell.isRootAvailable()) {
            return DumpResult(false, pkg, -1, outDir.absolutePath, emptyList(), 0, 0, "未检测到 root,无法读取进程内存")
        }
        val pids = findPids(pkg)
        if (pids.isEmpty()) {
            return DumpResult(false, pkg, -1, outDir.absolutePath, emptyList(), 0, 0,
                "未找到运行中的进程: $pkg。请先手动打开目标 App(壳需先把 dex 解密进内存)再脱壳。")
        }
        val pid = pids.first()
        if (outDir.exists()) outDir.listFiles()?.forEach { if (it.extension == "dex") it.delete() }
        outDir.mkdirs()

        val regions = candidateRegions(pid)
        if (regions.isEmpty()) {
            return DumpResult(false, pkg, pid, outDir.absolutePath, emptyList(), 0, 0,
                "读不到候选内存段(可能 SELinux 限制或进程已退出)")
        }

        var idx = 0
        var scanned = 0
        var skippedLarge = 0
        val seen = HashSet<String>() // 去重: size + 头部

        for (region in regions) {
            if (region.size > MAX_REGION_BYTES) { skippedLarge++; continue }
            val bytes = readRegion(pid, region) ?: continue
            scanned++
            // 在段内扫描所有 dex magic 出现处
            var searchFrom = 0
            while (true) {
                val at = indexOf(bytes, DEX_MAGIC, searchFrom)
                if (at < 0) break
                searchFrom = at + 4
                val dex = extractDex(bytes, at, minDexSize) ?: continue
                // 去重键: 大小 + 头部 32 字节
                val key = dex.size.toString() + ":" + bytesHex(dex, 0, 32)
                if (!seen.add(key)) continue
                File(outDir, "dump_${idx}.dex").writeBytes(dex)
                idx++
            }
        }

        val dexFiles = outDir.listFiles { f -> f.isFile && f.extension == "dex" }
            ?.sortedBy { it.name }
            ?.map { it.name to it.length() }
            ?: emptyList()

        val ok = dexFiles.isNotEmpty()
        val msg = if (ok) {
            "从进程 $pid 内存脱出 ${dexFiles.size} 个 dex(扫描 $scanned 段)"
        } else {
            "未 dump 到 dex(扫描 $scanned 段)。可能: 壳未把 dex 解密进内存(等 App 完全启动再试) / 三代抽取壳 / SELinux 限制。"
        }
        return DumpResult(ok, pkg, pid, outDir.absolutePath, dexFiles, scanned, skippedLarge, msg)
    }

    /** root 用 dd 抠出内存段并 base64,Kotlin 解码。走 su stdout 管道,不落临时文件。 */
    private fun readRegion(pid: Int, region: Region): ByteArray? {
        val page = 4096L
        val skip = region.start / page
        val count = (region.size + page - 1) / page
        val cmd = "dd if=/proc/$pid/mem bs=$page skip=$skip count=$count 2>/dev/null | base64"
        val r = RootShell.exec(cmd, timeoutSec = 60)
        if (!r.success || r.stdout.isBlank()) return null
        return runCatching { Base64.decode(r.stdout, Base64.DEFAULT) }.getOrNull()
    }

    /** 从 buf[at] 处的 dex header 读 file_size(偏移 32,小端 4 字节),截出完整 dex。 */
    private fun extractDex(buf: ByteArray, at: Int, minDexSize: Int): ByteArray? {
        // header 需要至少 0x70 字节
        if (at + 0x70 > buf.size) return null
        // magic 第 4 字节已是 0x0A(indexOf 保证),再看版本号 3 位是数字
        val v0 = buf[at + 4].toInt(); val v1 = buf[at + 5].toInt(); val v2 = buf[at + 6].toInt()
        val versionOk = v0 in 0x30..0x39 && v1 in 0x30..0x39 && v2 in 0x30..0x39
        if (!versionOk) return null
        val sizeOff = at + 32
        val fileSize = (buf[sizeOff].toLong() and 0xFF) or
            ((buf[sizeOff + 1].toLong() and 0xFF) shl 8) or
            ((buf[sizeOff + 2].toLong() and 0xFF) shl 16) or
            ((buf[sizeOff + 3].toLong() and 0xFF) shl 24)
        if (fileSize < minDexSize || fileSize > MAX_DEX_BYTES) return null
        if (at + fileSize > buf.size) return null // 段内放不下完整 dex(可能跨段,少见),跳过
        return buf.copyOfRange(at, (at + fileSize).toInt())
    }

    /** 在 haystack 里从 from 起找 needle,返回索引或 -1。 */
    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return -1
        val last = haystack.size - needle.size
        var i = from.coerceAtLeast(0)
        while (i <= last) {
            var j = 0
            while (j < needle.size && haystack[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return -1
    }

    private fun bytesHex(b: ByteArray, from: Int, len: Int): String {
        val sb = StringBuilder()
        val end = (from + len).coerceAtMost(b.size)
        for (i in from until end) sb.append("%02x".format(b[i].toInt() and 0xFF))
        return sb.toString()
    }

    fun DumpResult.toJson(): JSONObject {
        val arr = JSONArray()
        dexFiles.forEach { (name, size) ->
            arr.put(JSONObject().put("name", name).put("size", size))
        }
        return JSONObject()
            .put("success", success)
            .put("package", pkg)
            .put("pid", pid)
            .put("outputDir", outputDir)
            .put("dexCount", dexFiles.size)
            .put("dexFiles", arr)
            .put("scannedRegions", scannedRegions)
            .put("skippedLargeRegions", skippedLarge)
            .put("message", message)
    }
}
