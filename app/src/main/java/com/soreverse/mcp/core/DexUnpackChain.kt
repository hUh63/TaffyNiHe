package com.soreverse.mcp.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * 脱壳链编排：把「dump 目录 → 扫描 → 解密 → 修头 → 落盘 → 复核」串成一步。
 *
 * 为什么需要它：这三件事此前是三支工具（`taffy_dex_xor_scan` / `taffy_dex_aes` / `taffy_dex_unpack`），
 * 每个都要手动喂路径、看输出、再决定下一步喂什么。脱壳失败时最难的是**判断卡在哪一段**——
 * 是没 dump 到、扫不到、解不开、还是解开了但头坏了解析器不认。这里把每一段的结论都写进
 * [run] 的返回值（`stages` + `failures`），失败时能直接看出是哪一段断的。
 *
 * 纯逻辑、只用 java.io，不依赖 Android，可以被单测直接跑。
 */
object DexUnpackChain {

    /** 单文件读取上限：脱壳 dump 常见几十 MB，超过这个量级的多半是超大映射或误选文件。 */
    const val DEFAULT_MAX_READ = 128L * 1024 * 1024

    /** 目录里最多处理多少个文件（dump 目录可能几百个碎片，防止一次跑太久）。 */
    const val DEFAULT_MAX_FILES = 64

    data class Options(
        /** 扫描模式：plain / xor1 / xorn。 */
        val modes: Set<String> = setOf("plain", "xor1", "xorn"),
        /** 对 XOR 扫不到的文件再试 AES 穷举（慢，默认关）。 */
        val doAes: Boolean = false,
        /** 落盘前重算 dex 头（checksum / signature / file_size）。 */
        val doFix: Boolean = true,
        /** 单文件读取上限（字节）。 */
        val maxReadBytes: Long = DEFAULT_MAX_READ,
        /** 单文件最多取多少个候选。 */
        val maxHitsPerFile: Int = 8,
        /** 最多处理多少个文件。 */
        val maxFiles: Int = DEFAULT_MAX_FILES,
        /** AES 穷举时最多试多少个候选 key。 */
        val maxAesKeys: Int = 12,
        /** 写出后是否重新扫一遍做复核（几乎不花时间，换来可信的 verdict）。 */
        val verify: Boolean = true,
    ) {
        companion object {
            /** "plain,xor1,xorn" → set；全空或非法 → 默认三模式。 */
            fun parseModes(raw: String): Set<String> {
                val parts = raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                val allowed = parts.filter { it == "plain" || it == "xor1" || it == "xorn" }.toSet()
                return allowed.ifEmpty { setOf("plain", "xor1", "xorn") }
            }
        }
    }

    /**
     * 处理 [inputDir] 下的文件，产物写到 [outDir]。
     *
     * [outDir] 若落在 [inputDir] 内部会自动跳过（否则第二轮会把自己的产物当输入，越滚越大）。
     */
    fun run(inputDir: File, outDir: File, opts: Options = Options()): JSONObject {
        val stages = JSONArray()
        val failures = JSONArray()
        val recovered = JSONArray()
        fun fail(source: String, stage: String, reason: String) {
            failures.put(JSONObject().put("source", source).put("stage", stage).put("reason", reason))
        }
        fun stage(name: String, status: String, detail: String) {
            stages.put(JSONObject().put("name", name).put("status", status).put("detail", detail))
        }

        if (!inputDir.exists() || !inputDir.isDirectory) {
            stage("list", "failed", if (inputDir.exists()) "不是目录" else "目录不存在")
            fail(inputDir.absolutePath, "list", "输入目录不可用")
            return buildResult(stages, failures, recovered, outDir, 0, 0, 0, 0, opts)
        }

        // ── 1. 枚举 ──────────────────────────────────────────────
        val outPath = runCatching { outDir.canonicalPath }.getOrNull()
        val allFiles = ArrayList<File>()
        var tooLarge = 0
        var listed = 0
        runCatching {
            inputDir.walkTopDown()
                .onEnter { dir ->
                    // 不进入输出目录（它通常就在输入目录里）
                    val cp = runCatching { dir.canonicalPath }.getOrNull()
                    outPath == null || cp != outPath
                }
                .filter { it.isFile }
                .forEach { f ->
                    listed++
                    if (f.length() > opts.maxReadBytes) tooLarge++
                    else allFiles.add(f)
                }
        }.onFailure { fail(inputDir.absolutePath, "list", it.message ?: "枚举失败") }

        val files = allFiles.take(opts.maxFiles.coerceAtLeast(1))
        val truncated = allFiles.size - files.size
        stage(
            "list",
            when {
                listed == 0 -> "failed"
                truncated > 0 || tooLarge > 0 -> "partial"
                else -> "ok"
            },
            buildString {
                append("$listed 个文件")
                if (tooLarge > 0) append("，跳过 $tooLarge 个超大文件")
                if (truncated > 0) append("，超出上限只处理 ${files.size} 个")
            }.ifBlank { "0 个文件" },
        )

        if (files.isEmpty()) {
            return buildResult(stages, failures, recovered, outDir, 0, 0, 0, 0, opts)
        }

        // ── 2. 扫描 + 3. 解密 + 4. 修头 + 5. 落盘 ────────────────
        outDir.mkdirs()
        var saveSeq = 0
        var scanned = 0
        var totalHits = 0
        var xorHits = 0
        var aesHits = 0
        var fixedCount = 0
        var verified = 0
        var writeFails = 0

        for (file in files) {
            val bytes = runCatching {
                file.inputStream().use { readUpTo(it, opts.maxReadBytes) }
            }.getOrNull()
            if (bytes == null || bytes.size < 112) {
                fail(file.name, "read", if (bytes == null) "读取失败" else "文件过小（<112 字节）")
                continue
            }
            scanned++

            var hits: List<XorDexScanner.Found> = emptyList()
            // 同一偏移只保留一条：同一段数据被多种模式命中时解出来是同一份 dex，
            // 不去重会重复落盘（并且把「命中数」报大）。
            runCatching { hits = XorDexScanner.scan(bytes, opts.modes, opts.maxHitsPerFile).distinctBy { it.offset } }
                .onFailure { fail(file.name, "scan", it.message ?: "扫描异常") }
            totalHits += hits.size
            xorHits += hits.size

            for (hit in hits) {
                val dex = recoverBytes(bytes, hit)
                if (dex == null) {
                    fail(file.name, "recover", "候选 @0x${Integer.toHexString(hit.offset)}（${hit.mode}）无法还原：${hit.note.ifBlank { "数据不足" }}")
                    continue
                }
                if (appendRecovered(dex, hit.mode, hit.strongValid, hit.offset, file, outDir, opts, "$saveSeq", recovered, failures, file.name)) {
                    saveSeq++
                    if (opts.doFix) fixedCount++
                    verified++
                } else {
                    writeFails++
                }
            }

            // XOR 一个都没命中时才试 AES：AES 穷举贵，不该无脑对每个文件跑
            if (opts.doAes && hits.isEmpty()) {
                val aes = runCatching { AesDexDecryptor.autoDecrypt(bytes, opts.maxAesKeys) }
                    .onFailure { fail(file.name, "aes", it.message ?: "AES 穷举异常") }
                    .getOrDefault(emptyList())
                if (aes.isEmpty()) {
                    val tables = runCatching { AesDexDecryptor.findTables(bytes) }.getOrNull()
                    if (tables != null && !tables.sboxIsStandard) {
                        fail(file.name, "aes", "数据里有非标准 AES 表（自定义 S-box），静态解不开：需先 dump 运行时内存再扫")
                    }
                }
                for (h in aes) {
                    aesHits++
                    totalHits++
                    val mode = "aes-" + h.mode
                    if (appendRecovered(h.bytes, mode, h.entry.strongValid, h.entry.offset, file, outDir, opts, "$saveSeq", recovered, failures, file.name)) {
                        saveSeq++
                        if (opts.doFix) fixedCount++
                        verified++
                    } else {
                        writeFails++
                    }
                }
            }
        }

        stage(
            "scan",
            when {
                scanned == 0 -> "failed"
                totalHits == 0 -> "failed"
                else -> "ok"
            },
            if (totalHits == 0) "扫了 $scanned 个文件，没有命中（明文/XOR/AES 都没有 dex 特征）"
            else "扫了 $scanned 个文件，命中 $totalHits 处（XOR/明文 $xorHits，AES $aesHits）",
        )

        stage(
            "recover",
            when {
                saveSeq == 0 -> "failed"
                writeFails > 0 -> "partial"
                else -> "ok"
            },
            if (saveSeq == 0) "没有写出任何 dex" else "写出 $saveSeq 个 dex 到 ${outDir.name}/",
        )

        stage(
            "fix",
            if (!opts.doFix) "skipped" else if (saveSeq == 0) "skipped" else "ok",
            if (!opts.doFix) "按要求跳过修头" else "对 $fixedCount 个产物重算了 checksum/signature/file_size",
        )

        if (opts.verify) {
            // 复核：把写出的文件当输入再扫一遍 plain，只信强校验通过的
            var strong = 0
            var structural = 0
            for (i in 0 until recovered.length()) {
                val item = recovered.optJSONObject(i) ?: continue
                val f = File(item.optString("file"))
                val data = runCatching { f.inputStream().use { readUpTo(it, opts.maxReadBytes) } }.getOrNull() ?: continue
                val again = runCatching { XorDexScanner.scan(data, setOf("plain"), 1) }.getOrDefault(emptyList())
                    .firstOrNull { it.offset == 0 }
                if (again != null && again.strongValid) strong++ else structural++
                item.put("verified", again?.strongValid == true)
            }
            verified = strong
            stage("verify", if (structural == 0) "ok" else "partial", "强校验通过 $strong 个，仅结构合法 $structural 个")
        } else {
            // 没做复核时 verifiedCount 不该等于写入数（那会把「没检查」报成「全过」）
            verified = 0
            stage("verify", "skipped", "按要求跳过复核")
        }

        // ── 结论 ────────────────────────────────────────────────
        val next = when {
            saveSeq == 0 && !opts.doAes -> "本地没有任何 dex 特征：先 taffy_dex_unpack 从运行中的进程 dump 内存（需 root），或打开 aes=true 再跑一遍"
            saveSeq == 0 -> "解不开：若 sbox 报非标准表说明 S-box 被换过，只能 dump 运行时内存（壳解密后的 dex 在内存里是标准表）；若表是标准的，试 taffy_dex_aes action=keys 手工挑 key"
            opts.verify && verified == 0 -> "解出来了但校验全不过：多半是被截断的 dump，检查 dump 的读取上限或换更完整的内存区间"
            else -> "可直接喂给 taffy_jadx_decompile / taffy_baksmali_decode 分析"
        }
        return buildResult(stages, failures, recovered, outDir, scanned, totalHits, saveSeq, verified, opts).put("next", next)
    }

    /**
     * 把候选还原成 dex 字节。
     *
     * 与 [XorDexScanner.extract] 的差别只在截断场景：dump 里 file_size 常大于实际可读数据，
     * extract 会返回 null，但这段数据其实是有用的（头被写坏而已），这里退化为「按 key 解完剩余全部字节」
     * 再交给 [DexFixer] 按实际长度重算头。
     */
    fun recoverBytes(data: ByteArray, hit: XorDexScanner.Found): ByteArray? {
        XorDexScanner.extract(data, hit)?.let { return it }
        if (hit.offset < 0 || hit.offset >= data.size || data.size - hit.offset < 112) return null
        val key = XorDexScanner.hexToBytes(hit.keyHex)
        val len = data.size - hit.offset
        val out = ByteArray(len)
        if (key.isEmpty()) {
            System.arraycopy(data, hit.offset, out, 0, len)
        } else {
            for (i in 0 until len) {
                out[i] = (data[hit.offset + i].toInt() xor key[i % key.size].toInt()).toByte()
            }
        }
        return out
    }

    // ───────────────────────── 内部 ─────────────────────────

    /** 修头 + 落盘 + 记一条 recovered；返回是否真的写出。 */
    private fun appendRecovered(
        dex: ByteArray,
        mode: String,
        strongValid: Boolean,
        offset: Int,
        source: File,
        outDir: File,
        opts: Options,
        seq: String,
        recovered: JSONArray,
        failures: JSONArray,
        sourceName: String,
    ): Boolean {
        var data = dex
        var fixed = false
        var changes = JSONArray()
        var fixNote = ""
        if (opts.doFix) {
            val (out, report) = runCatching { DexFixer.fix(dex) }.getOrNull()
                ?: (dex to DexFixer.Report(false, dex.size, dex.size, false, false, 0L, "", emptyList(), "修头异常"))
            data = out
            fixed = report.fixed
            fixNote = report.note
            report.changes.forEach { changes.put(it) }
            if (!report.fixed) {
                failures.put(
                    JSONObject().put("source", sourceName).put("stage", "fix")
                        .put("reason", "$mode @0x${Integer.toHexString(offset)} 修头失败：${report.note}")
                )
            }
        }
        val name = "${sanitize(source.name)}.$seq.$mode.dex"
        val target = File(outDir, name)
        val written = runCatching {
            FileOutputStream(target).use { it.write(data) }
            true
        }.getOrDefault(false)
        if (!written) {
            failures.put(JSONObject().put("source", sourceName).put("stage", "recover").put("reason", "写入失败: $name"))
            return false
        }
        val item = JSONObject()
        item.put("source", sourceName)
        item.put("file", target.absolutePath)
        item.put("name", name)
        item.put("size", data.size)
        item.put("mode", mode)
        item.put("offset", offset)
        item.put("offsetHex", "0x" + Integer.toHexString(offset))
        item.put("strongValidInSource", strongValid)
        item.put("fixed", fixed)
        item.put("changes", changes)
        if (fixNote.isNotBlank()) item.put("fixNote", fixNote)
        recovered.put(item)
        return true
    }

    private fun buildResult(
        stages: JSONArray,
        failures: JSONArray,
        recovered: JSONArray,
        outDir: File,
        scanned: Int,
        hits: Int,
        written: Int,
        verified: Int,
        opts: Options,
    ): JSONObject {
        val o = JSONObject()
        o.put("ok", true)
        o.put("outDir", outDir.absolutePath)
        o.put("scannedFiles", scanned)
        o.put("hitCount", hits)
        o.put("writtenCount", written)
        o.put("verifiedCount", verified)
        o.put("verifyRan", opts.verify)
        o.put("stages", stages)
        o.put("recovered", recovered)
        o.put("failures", failures)
        val opt = JSONObject()
        opt.put("modes", opts.modes.joinToString(","))
        opt.put("aes", opts.doAes)
        opt.put("fix", opts.doFix)
        opt.put("maxFiles", opts.maxFiles)
        opt.put("maxReadBytes", opts.maxReadBytes)
        o.put("options", opt)
        return o
    }

    /** 文件名消毒：只留字母数字与 `._-`，并限长（dump 名字里可能带路径残留）。 */
    internal fun sanitize(name: String): String {
        val base = name.substringBeforeLast('.', name)
        val sb = StringBuilder()
        for (c in base) {
            sb.append(if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_')
            if (sb.length >= 48) break
        }
        return sb.toString().ifBlank { "dump" }
    }

    /** 有界读取（`readNBytes` 是 API 33 才有，minSdk 26 用不了）。 */
    internal fun readUpTo(input: InputStream, max: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
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
}
