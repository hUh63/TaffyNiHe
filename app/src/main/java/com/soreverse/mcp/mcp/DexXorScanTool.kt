package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.XorDexScanner
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * 加密 / XOR 隐藏的 DEX 扫描与还原。
 *
 * 壳把 dex 藏起来时常见三种形态，这里一次扫全：
 *  - plain：明文嵌在 so / assets / 内存 dump 里；
 *  - xor1：单字节 XOR（256 种 key 全覆盖）；
 *  - xorN：2..8 字节重复 key（用 `dex\n035\0` 反推 key 再验证）。
 *
 * 判定标准不是"看起来像"，而是 dex 结构 + adler32 校验和 + sha1 签名的组合校验，
 * 所以误报极少；校验和/签名不符但结构合法的会单独标出来（脱壳后未 fixDex 的典型形态）。
 */
object DexXorScanTool {

    private const val MAX_READ = 512L * 1024 * 1024
    private const val MAX_ENTRY = 96L * 1024 * 1024

    val dexXorScan: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_dex_xor_scan",
            "【加密 DEX 扫描/还原】从 so / assets / 内存 dump / 任意二进制里扫出被 XOR 或直接嵌入的 dex：action=scan 只报告候选（offset/模式/key/版本/大小/双校验结果）；action=extract 顺带把命中的 dex 落盘。modes 支持 plain / xor1（单字节 256 种）/ xorN（2..8 字节重复 key，用 dex 魔数反推）。path 也支持 APK/zip（逐个条目扫）。",
            "Scan/extract XOR-encrypted or embedded DEX from any binary (so/assets/memory dump/apk): action=scan|extract, modes=plain,xor1,xorN. Validated by dex structure + adler32 checksum + SHA-1 signature.",
            "analyze", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("scan (default) | extract", "scan", "extract")
                "path" str "输入文件：so / bin / dex / 内存 dump / apk（必填）"
                "modes" str "扫描模式，逗号分隔：plain,xor1,xorn（默认全开）"
                "maxHits" int "最多返回多少个候选（默认 32）"
                "outDir" str "action=extract 的输出目录（默认与输入同级的 dex-recovered/）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path", "path", "")
            val file = File(path)
            if (!file.isFile) return err("FILE_NOT_FOUND", "文件不存在: $path", "path", path)
            val action = args.str("action", "scan").ifBlank { "scan" }
            val modes = parseModes(args.str("modes", "plain,xor1,xorn"))
            if (modes.isEmpty()) return err("INVALID_ARGUMENT", "modes 至少要有一个：plain / xor1 / xorn", "modes", args.str("modes"))
            val maxHits = args.intValue("maxHits", 32).coerceIn(1, 500)
            val outDir = if (action == "extract") {
                val d = args.str("outDir")
                if (d.isBlank()) File(file.absoluteFile.parentFile, "dex-recovered") else File(d)
            } else null

            return runCatching {
                val hits = ArrayList<Pair<String, XorDexScanner.Found>>()
                if (file.name.lowercase().endsWith(".apk") || file.name.lowercase().endsWith(".zip")) {
                    ZipFile(file).use { z ->
                        for (entry in z.entries()) {
                            if (entry.isDirectory || entry.size <= 0 || entry.size > MAX_ENTRY) continue
                            if (hits.size >= maxHits) break
                            val bytes = runCatching { z.getInputStream(entry).use { readUpTo(it, MAX_ENTRY) } }.getOrNull() ?: continue
                            for (f in XorDexScanner.scan(bytes, modes, maxHits - hits.size)) hits.add(entry.name to f)
                        }
                    }
                } else {
                    val bytes = runCatching { file.inputStream().use { readUpTo(it, MAX_READ) } }.getOrNull()
                        ?: return err("READ_FAILED", "读取失败: $path", "path", path)
                    for (f in XorDexScanner.scan(bytes, modes, maxHits)) hits.add(file.name to f)
                }

                val hitJson = JSONArray()
                val written = JSONArray()
                for ((where, f) in hits) {
                    hitJson.put(JSONObject()
                        .put("where", where)
                        .put("offset", f.offset)
                        .put("offsetHex", "0x" + java.lang.Long.toHexString(f.offset.toLong()))
                        .put("mode", f.mode)
                        .put("key", f.keyHex.ifBlank { "(none)" })
                        .put("version", f.version)
                        .put("size", f.size)
                        .put("strongValid", f.strongValid)
                        .put("note", f.note))
                    if (outDir != null) {
                        val bytes = if (file.name.lowercase().endsWith(".apk") || file.name.lowercase().endsWith(".zip")) {
                            ZipFile(file).use { z ->
                                val e = z.getEntry(where) ?: return@use null
                                val raw = z.getInputStream(e).use { readUpTo(it, MAX_ENTRY) }
                                XorDexScanner.extract(raw, f)
                            }
                        } else {
                            val raw = file.inputStream().use { readUpTo(it, MAX_READ) }
                            XorDexScanner.extract(raw, f)
                        }
                        if (bytes != null) {
                            outDir.mkdirs()
                            val safe = where.replace(Regex("[^A-Za-z0-9._-]"), "_")
                            val out = File(outDir, "$safe.off${Integer.toHexString(f.offset)}.dex")
                            FileOutputStream(out).use { it.write(bytes) }
                            written.put(out.absolutePath)
                        }
                    }
                }

                val result = JSONObject()
                    .put("action", action)
                    .put("path", file.absolutePath)
                    .put("modes", JSONArray().apply { for (m in modes) put(m) })
                    .put("hitCount", hitJson.length())
                    .put("hits", hitJson)
                if (outDir != null) {
                    result.put("outDir", outDir.absolutePath)
                    result.put("written", written)
                }
                if (hitJson.length() == 0) {
                    result.put("summary", "没有命中。若确认是加固壳，先 taffy_dex_unpack（内存 dump）拿 dump，再对本文件扫；" +
                        "命中但 key 不对时可先用 taffy_guard_scan 看是哪家壳")
                } else {
                    val strong = (0 until hitJson.length()).count { hitJson.getJSONObject(it).optBoolean("strongValid") }
                    result.put("strongValidCount", strong)
                    result.put("next", if (action == "extract") "已落盘，可直接 taffy_dex_analyze / jadx 打开" else "改 action=extract 可把 dex 写出来")
                }
                ok(result)
            }.getOrElse { err("SCAN_FAILED", "扫描失败: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    private fun parseModes(raw: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (part in raw.split(',', ' ', '|', ';')) {
            when (part.trim().lowercase()) {
                "plain", "raw" -> out.add("plain")
                "xor1", "xor", "single" -> out.add("xor1")
                "xorn", "xormulti", "multi" -> out.add("xorn")
            }
        }
        return out
    }

    private fun readUpTo(input: java.io.InputStream, max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream(1 shl 16)
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (total < max) {
            val want = minOf(buf.size.toLong(), max - total).toInt()
            val n = input.read(buf, 0, want)
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    val ALL = listOf(dexXorScan)
}
