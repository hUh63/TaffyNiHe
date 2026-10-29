package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.str
import com.soreverse.mcp.engine.SoDiff
import org.json.JSONObject

/**
 * SO 结构对比（对齐「so 对比」小工具的能力）。
 *
 * 差分逻辑全在 [SoDiff]（纯 Kotlin，走 [com.soreverse.mcp.engine.ElfParser]），这里只做
 * 参数校验、读文件与错误包装。两个文件都按大小预检，绝不把 500 MiB 的 SO 整块读进来——
 * [ElfParser] 需要完整 ByteArray，没有流式解析路径。
 */
object SoDiffTools {

    /** 单个 ELF 的读取上限：对比一般是同一库的两个版本，超过这个量级应先裁决是不是选错文件。 */
    private const val MAX_ELF_BYTES = 192L * 1024 * 1024

    private val soDiff = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_so_diff",
            "【SO 结构对比】两个 ELF/SO 的结构级差异：文件大小、.text、导出符号、导入符号、" +
                "依赖库（DT_NEEDED）、符号版本、以及**函数级差异**（哪些函数新增/删除/大小变化/内容变化，" +
                "内容变化还带首个不同字节相对函数起点的偏移 firstDiff），并给出 identical / same-structure / " +
                "code-only / structure-changed / abi-mismatch 判定。" +
                "分工：本工具回答「差异落在哪个函数」，要定位到具体字节区间/hexdump 请用 taffy_byte_diff 或 taffy_read_hexdump。",
            "Structural diff of two ELF files: size, .text, exports, imports, DT_NEEDED, symbol " +
                "versions, plus per-function diff (added / removed / resized / content-changed with the " +
                "first differing byte offset inside the function). Verdict: identical / same-structure / " +
                "code-only / structure-changed / abi-mismatch. Tells you WHICH function changed; use " +
                "taffy_byte_diff for byte-range level diffing.",
            "analyze",
            ToolClass.EXTRA,
        ) {
            objectSchema(
                props {
                    "pathA" str "第一个 ELF/SO 的路径（工作区内路径或 content:// URI）"
                    "pathB" str "第二个 ELF/SO 的路径"
                    "limit" int "每个分类最多返回多少条（默认 200，上限 5000）"
                },
            )
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val pathA = args.str("pathA")
            val pathB = args.str("pathB")
            if (pathA.isBlank() || pathB.isBlank()) {
                return err(
                    "MISSING_ARGUMENT",
                    "Both pathA and pathB are required.",
                    "pathA",
                    pathA,
                )
            }
            val limit = args.intValue("limit", 200)
            val bytesA = runCatching { readElf(ctx, pathA) }.getOrElse {
                return err("READ_FAILED", "Cannot read pathA: ${it.message}", "pathA", pathA)
            }
            val bytesB = runCatching { readElf(ctx, pathB) }.getOrElse {
                return err("READ_FAILED", "Cannot read pathB: ${it.message}", "pathB", pathB)
            }
            return runCatching { SoDiff.diff(bytesA, bytesB, limit) }.getOrElse {
                return err(
                    "DIFF_FAILED",
                    "Not a valid ELF pair: ${it.message}",
                    "pathA",
                    pathA,
                )
            }
        }
    }

    private fun readElf(ctx: ToolContext, path: String): ByteArray {
        if (path.startsWith("content://")) {
            val uri = android.net.Uri.parse(path)
            val input = ctx.context.contentResolver.openInputStream(uri)
                ?: error("cannot open $path")
            input.use { return readCapped(it, MAX_ELF_BYTES) }
        }
        val f = java.io.File(path)
        require(f.isFile) { "not a file: $path" }
        val len = f.length()
        require(len > 0L) { "empty file: $path" }
        require(len <= MAX_ELF_BYTES) {
            "${len / 1024 / 1024} MiB exceeds the ${MAX_ELF_BYTES / 1024 / 1024} MiB cap: $path"
        }
        return f.readBytes()
    }

    private fun readCapped(input: java.io.InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size().toLong() + n > limit) error("file exceeds ${limit / 1024 / 1024} MiB")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    val ALL = listOf(soDiff)
}
