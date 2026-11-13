package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.AesDexDecryptor
import com.soreverse.mcp.core.DexFixer
import com.soreverse.mcp.core.XorDexScanner
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * AES 加密 DEX 的定向解密。
 *
 * 对的是加固壳里最难缠的一类：dex 不是简单 XOR，而是 AES 加密（还能把 S-box 一起搬走/换掉）。
 * 三个步骤分开给，也可以一键 auto：
 *  - `sbox`：先看数据里有没有标准 AES 表 → 判断"标准 AES 能解"还是"自定义表要先 dump"；
 *  - `keys`：从数据里挑出像 key 的块（16/24/32 字节、高熵、不重复）；
 *  - `decrypt`：给定 key(+iv/mode/unpad) 直接解，解完自动扫 dex 并可修头落盘；
 *  - `auto`：候选 key × IV 变体 × CBC/ECB 穷举，命中就报 key/iv/mode。
 *
 * 解出来的 dex 头若不自洽（脱壳常态），调用 DexFixer 重算 checksum/signature。
 */
object DexAesDecryptTool {

    /** decrypt 会整段 AES 解密（峰值约 2~3 倍），上限比扫描类工具低一档。 */
    private const val MAX_READ = 128L * 1024 * 1024

    val dexAes: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_dex_aes",
            "【AES 加密 DEX 解密】对付用 AES 加密 dex 的壳：action=sbox 查数据里有没有 AES 表（判断标准/自定义表）；keys 列候选 key；decrypt 用指定 key(+iv/mode/unpad) 解密并在结果里扫 dex（可修头落盘）；auto 用候选 key × IV × CBC/ECB 穷举，命中直接报 key/iv/mode。解出的 dex 自动重算 adler32+SHA-1 头。",
            "Decrypt AES-encrypted DEX: action=sbox (detect AES tables incl. custom S-box), keys (key candidates), decrypt (given key/iv/mode/unpad then scan+fix dex), auto (brute-force candidates × IV × CBC/ECB).",
            "analyze", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("sbox (default) | keys | decrypt | auto", "sbox", "keys", "decrypt", "auto")
                "path" str "输入文件：so / bin / 加密段 / 内存 dump（必填）"
                "key" str "decrypt 用：AES key（hex，16/24/32 字节）"
                "iv" str "decrypt 用：IV（hex 16 字节，可空=全 0）"
                "mode".oneOf("decrypt 用：cbc (默认) | ecb", "cbc", "ecb")
                "unpad" str "decrypt 用：true/false 解完去 PKCS7 填充（默认 true）"
                "xor" str "decrypt 用：解完再 XOR 的 key（hex，可空）"
                "maxKeys" int "keys/auto 用：最多试多少个候选 key（默认 12）"
                "outDir" str "decrypt/auto 用：解出的 dex 落盘目录（默认与输入同级的 aes-dex/）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path", "path", "")
            val file = File(path)
            if (!file.isFile) return err("FILE_NOT_FOUND", "文件不存在: $path", "path", path)
            val action = args.str("action", "sbox").ifBlank { "sbox" }

            return runCatching {
                when (action) {
                    "sbox" -> {
                        val data = read(file)
                        val t = AesDexDecryptor.findTables(data)
                        val headers = DexFixer.findDexHeaders(data)
                        ok(JSONObject()
                            .put("action", "sbox")
                            .put("path", file.absolutePath)
                            .put("aesTable", JSONObject()
                                .put("hasSbox", t.hasSbox)
                                .put("hasInvSbox", t.hasInvSbox)
                                .put("hasRcon", t.hasRcon)
                                .put("sboxOffset", t.sboxOffset)
                                .put("invSboxOffset", t.invSboxOffset)
                                .put("rconOffset", t.rconOffset)
                                .put("sboxIsStandard", t.sboxIsStandard))
                            .put("note", t.note)
                            .put("plainDexHeaders", JSONArray().apply { for (h in headers.take(16)) put("0x" + Integer.toHexString(h)) })
                            .put("next", if (t.hasSbox) "标准 AES 表在，key 就藏在附近：先 action=keys 挑 key，再 decrypt"
                            else "没有标准表：可能是自定义表/AES 变体，先 taffy_dex_unpack dump 运行时内存再扫"))
                    }

                    "keys" -> {
                        val data = read(file)
                        val cands = AesDexDecryptor.keyCandidates(data, args.intValue("maxKeys", 12).coerceIn(1, 64))
                        val arr = JSONArray()
                        for (c in cands) {
                            arr.put(JSONObject()
                                .put("offset", c.offset)
                                .put("offsetHex", "0x" + Integer.toHexString(c.offset))
                                .put("length", c.length)
                                .put("key", c.hex)
                                .put("distinctBytes", c.distinctBytes)
                                .put("repeated", c.repeated))
                        }
                        ok(JSONObject()
                            .put("action", "keys")
                            .put("count", arr.length())
                            .put("candidates", arr)
                            .put("next", "action=decrypt key=<候选> 或 action=auto 让它自己试"))
                    }

                    "decrypt" -> {
                        val keyHex = args.str("key")
                        if (keyHex.isBlank()) return err("INVALID_ARGUMENT", "decrypt 需要 key（hex）", "key", "")
                        val key = XorDexScanner.hexToBytes(keyHex)
                        if (key.size !in intArrayOf(16, 24, 32)) {
                            return err("INVALID_ARGUMENT", "key 必须是 16/24/32 字节的 hex（当前 ${key.size} 字节）", "key", keyHex)
                        }
                        val iv = XorDexScanner.hexToBytes(args.str("iv"))
                        if (iv.isNotEmpty() && iv.size != 16) {
                            return err("INVALID_ARGUMENT", "iv 必须是 16 字节 hex", "iv", args.str("iv"))
                        }
                        val mode = args.str("mode", "cbc").ifBlank { "cbc" }
                        val unpad = args.str("unpad", "true").ifBlank { "true" }.toBoolean()
                        val xorKey = XorDexScanner.hexToBytes(args.str("xor"))
                        val data = read(file)
                        val decrypted: ByteArray? = if (mode == "ecb") {
                            AesDexDecryptor.decryptEcb(data, key, unpad)
                        } else {
                            AesDexDecryptor.decryptCbc(data, key, iv, unpad)
                        }
                        var plain: ByteArray = decrypted
                            ?: return err("DECRYPT_FAILED", "AES-$mode 解密失败（key/iv 不对，或数据长度不合法）", "key", keyHex)
                        if (xorKey.isNotEmpty()) {
                            plain = ByteArray(plain.size) { i -> (plain[i].toInt() xor xorKey[i % xorKey.size].toInt()).toByte() }
                        }
                        val found = XorDexScanner.scan(plain, setOf("plain"), 4)
                        val result = JSONObject()
                            .put("action", "decrypt")
                            .put("mode", mode)
                            .put("key", XorDexScanner.hex(key))
                            .put("iv", if (iv.isEmpty()) "00000000000000000000000000000000" else XorDexScanner.hex(iv))
                            .put("decryptedBytes", plain.size)
                            .put("dexCount", found.size)
                        if (found.isEmpty()) {
                            result.put("note", "解出来了但没扫到合法 dex：key 对但数据不是 dex（可能是 so/bin），或还需再解一层（XOR/另一层 AES）")
                        } else {
                            val outDir = File(args.str("outDir").ifBlank { File(file.absoluteFile.parentFile, "aes-dex").absolutePath })
                            val written = JSONArray()
                            for (f in found) {
                                val bytes = XorDexScanner.extract(plain, f) ?: continue
                                outDir.mkdirs()
                                val out = File(outDir, "${file.nameWithoutExtension}.key${XorDexScanner.hex(key).take(8)}.off${Integer.toHexString(f.offset)}.dex")
                                val (fixedBytes, report) = DexFixer.fix(bytes)
                                FileOutputStream(out).use { it.write(fixedBytes) }
                                written.put(JSONObject()
                                    .put("path", out.absolutePath)
                                    .put("offset", f.offset)
                                    .put("size", fixedBytes.size)
                                    .put("strongValid", f.strongValid)
                                    .put("headerFixed", report.fixed)
                                    .put("changes", JSONArray().apply { for (c in report.changes) put(c) }))
                            }
                            result.put("outDir", outDir.absolutePath)
                            result.put("written", written)
                        }
                        ok(result)
                    }

                    "auto" -> {
                        val data = read(file)
                        val hits = AesDexDecryptor.autoDecrypt(data, args.intValue("maxKeys", 12).coerceIn(1, 24))
                        val arr = JSONArray()
                        val outDir = File(args.str("outDir").ifBlank { File(file.absoluteFile.parentFile, "aes-dex").absolutePath })
                        for (h in hits) {
                            outDir.mkdirs()
                            val out = File(outDir, "${file.nameWithoutExtension}.auto.off${Integer.toHexString(h.entry.offset)}.dex")
                            val (fixedBytes, report) = DexFixer.fix(h.bytes)
                            FileOutputStream(out).use { it.write(fixedBytes) }
                            arr.put(JSONObject()
                                .put("key", h.keyHex)
                                .put("iv", h.ivHex)
                                .put("mode", h.mode)
                                .put("dexOffset", h.entry.offset)
                                .put("dexSize", fixedBytes.size)
                                .put("strongValid", h.entry.strongValid)
                                .put("headerFixed", report.fixed)
                                .put("outPath", out.absolutePath))
                        }
                        val result = JSONObject()
                            .put("action", "auto")
                            .put("tried", "候选 key × {零IV, key当IV, key后16字节} × {cbc, ecb}")
                            .put("hitCount", arr.length())
                            .put("hits", arr)
                        if (arr.length() == 0) {
                            result.put("note", "穷举没命中：key 可能不在数据里（运行时才算出/在 so 里），先用 taffy_dex_unpack 拿内存 dump 再扫")
                        }
                        ok(result)
                    }

                    else -> err("INVALID_ARGUMENT", "未知 action: $action", "action", action)
                }
            }.getOrElse { err("DECRYPT_FAILED", "处理失败: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    private fun read(file: File): ByteArray = file.inputStream().use { readUpTo(it, MAX_READ) }

    private fun readUpTo(input: InputStream, max: Long): ByteArray {
        val out = ByteArrayOutputStream(1 shl 16)
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

    val ALL = listOf(dexAes)
}
