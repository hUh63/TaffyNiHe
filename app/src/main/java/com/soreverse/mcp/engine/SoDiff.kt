package com.soreverse.mcp.engine

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 两个 ELF / SO 的**结构级**差分。
 *
 * 能力对齐「so 对比」这类小工具，但复用塔菲自己的 [ElfParser]，而不是再写一份解析器：
 * 解析失败走同一条报错路径，节区/符号的类型与 `taffy_analyze_elf`、`read_stats` 口径一致，
 * 不会出现「同一个文件在对比页能开、在分析页报 magic 错」。
 *
 * 对比维度：
 *  - 概览：文件大小、`.text` 大小、导出/导入数量、是否逐字节相同、ABI 是否一致；
 *  - 节区：新增 / 删除 / 变化（size、type、flags 三项）；
 *  - 导出符号：新增 / 删除 / 变化（size、type、bind）；
 *  - 导入符号：新增 / 删除；
 *  - 依赖库（DT_NEEDED）与符号版本（.gnu.version*）的新增 / 删除。
 *
 * 只做结构对比，不逐字节列差异——字节级看 `taffy_read_hexdump` 或字节差分视图。
 * 单边条目数超过 `limit` 时置 `truncated`，绝不静默截断。
 */
internal object SoDiff {
    private const val DEFAULT_LIMIT = 200

    /** 对比结果里每个分类最多返回多少条，超出时置 truncated。 */
    private fun clampLimit(limit: Int): Int = limit.coerceIn(1, 5000)

    fun diff(aData: ByteArray, bData: ByteArray, limit: Int = DEFAULT_LIMIT): JSONObject {
        val ea = ElfParser(aData).parse()
        val eb = ElfParser(bData).parse()
        val lim = clampLimit(limit)

        val hashA = sha256(aData)
        val hashB = sha256(bData)
        val textA = sectionSize(ea, ".text")
        val textB = sectionSize(eb, ".text")
        val exportsA = exportsOf(ea)
        val exportsB = exportsOf(eb)
        val importsA = importsOf(ea)
        val importsB = importsOf(eb)

        // ── 节区 ──
        val aSec = ea.sections.filter { it.name.isNotBlank() }.associateBy { it.name }
        val bSec = eb.sections.filter { it.name.isNotBlank() }.associateBy { it.name }
        val secAdded = mutableListOf<JSONObject>()
        val secRemoved = mutableListOf<JSONObject>()
        val secChanged = mutableListOf<JSONObject>()
        for ((name, sa) in aSec) {
            val sb = bSec[name]
            if (sb == null) {
                secRemoved += JSONObject().put("name", name).put("size", sa.size).put("type", sa.type)
            } else if (sa.size != sb.size || sa.type != sb.type || sa.flags != sb.flags) {
                secChanged += JSONObject()
                    .put("name", name)
                    .put("delta", sb.size - sa.size)
                    .put("sizeA", sa.size)
                    .put("sizeB", sb.size)
                    .put("flagsA", sa.flags)
                    .put("flagsB", sb.flags)
                    .put("typeA", sa.type)
                    .put("typeB", sb.type)
                    .put("flagsChanged", sa.flags != sb.flags)
                    .put("typeChanged", sa.type != sb.type)
            }
        }
        for ((name, sb) in bSec) {
            if (aSec[name] == null) {
                secAdded += JSONObject().put("name", name).put("size", sb.size).put("type", sb.type)
            }
        }
        val secChangedSorted = secChanged.sortedByDescending { kotlin.math.abs(it.optLong("delta")) }

        // ── 导出符号 ──
        val expAdded = mutableListOf<JSONObject>()
        val expRemoved = mutableListOf<JSONObject>()
        val expChanged = mutableListOf<JSONObject>()
        var expSame = 0
        for ((name, sb) in exportsB) {
            val sa = exportsA[name]
            if (sa == null) {
                expAdded += symbolJson(name, sb)
            } else if (sa.size != sb.size || sa.type != sb.type || sa.bind != sb.bind) {
                expChanged += symbolJson(name, sb)
                    .put("delta", sb.size - sa.size)
                    .put("sizeA", sa.size)
                    .put("typeA", sa.type)
                    .put("bindA", sa.bind)
            } else {
                expSame++
            }
        }
        for ((name, sa) in exportsA) {
            if (exportsB[name] == null) expRemoved += symbolJson(name, sa)
        }

        // ── 导入符号 ──
        val impAdded = mutableListOf<JSONObject>()
        val impRemoved = mutableListOf<JSONObject>()
        var impSame = 0
        for ((name, sb) in importsB) {
            if (importsA[name] == null) impAdded += symbolJson(name, sb) else impSame++
        }
        for ((name, sa) in importsA) {
            if (importsB[name] == null) impRemoved += symbolJson(name, sa)
        }

        // ── 依赖库 / 符号版本 ──
        val needA = ElfAudit.neededList(aData, ea)
        val needB = ElfAudit.neededList(bData, eb)
        val needAdded = needB.filter { it !in needA }
        val needRemoved = needA.filter { it !in needB }

        val verA = ea.symbolVersions.map { it.version }.filter { it.isNotBlank() }.toSet()
        val verB = eb.symbolVersions.map { it.version }.filter { it.isNotBlank() }.toSet()
        val verAdded = (verB - verA).sorted()
        val verRemoved = (verA - verB).sorted()

        val abiMatch = ea.bits == eb.bits && ea.machine == eb.machine && ea.littleEndian == eb.littleEndian
        val identical = hashA == hashB
        val textChanged = textDigest(aData, ea) != textDigest(bData, eb)

        val verdict = when {
            !abiMatch -> "abi-mismatch"
            identical -> "identical"
            expAdded.isEmpty() && expRemoved.isEmpty() && expChanged.isEmpty() &&
                impAdded.isEmpty() && impRemoved.isEmpty() && needAdded.isEmpty() && needRemoved.isEmpty() &&
                secAdded.isEmpty() && secRemoved.isEmpty() && secChangedSorted.isEmpty() -> "same-structure"
            textChanged && expAdded.isEmpty() && expRemoved.isEmpty() && expChanged.isEmpty() ->
                "code-only"
            else -> "structure-changed"
        }

        fun arr(list: List<JSONObject>): JSONArray {
            val out = JSONArray()
            list.take(lim).forEach { out.put(it) }
            return out
        }

        val truncated = secChangedSorted.size > lim || expAdded.size > lim || expRemoved.size > lim ||
            expChanged.size > lim || impAdded.size > lim || impRemoved.size > lim ||
            secAdded.size > lim || secRemoved.size > lim

        return JSONObject()
            .put("ok", true)
            .put("a", sideJson(aData, ea, hashA, exportsA.size, importsA.size))
            .put("b", sideJson(bData, eb, hashB, exportsB.size, importsB.size))
            .put(
                "summary",
                JSONObject()
                    .put("identical", identical)
                    .put("abiMatch", abiMatch)
                    .put("textChanged", textChanged)
                    .put("verdict", verdict)
                    .put("sizeDelta", bData.size.toLong() - aData.size.toLong())
                    .put("textDelta", (textB ?: 0L) - (textA ?: 0L))
                    .put("exportsDelta", exportsB.size - exportsA.size)
                    .put("importsDelta", importsB.size - importsA.size)
                    .put("sections", JSONObject()
                        .put("added", secAdded.size)
                        .put("removed", secRemoved.size)
                        .put("changed", secChangedSorted.size))
                    .put("exports", JSONObject()
                        .put("added", expAdded.size)
                        .put("removed", expRemoved.size)
                        .put("changed", expChanged.size)
                        .put("same", expSame))
                    .put("imports", JSONObject()
                        .put("added", impAdded.size)
                        .put("removed", impRemoved.size)
                        .put("same", impSame))
                    .put("needed", JSONObject()
                        .put("added", needAdded.size)
                        .put("removed", needRemoved.size))
                    .put("versions", JSONObject()
                        .put("added", verAdded.size)
                        .put("removed", verRemoved.size)),
            )
            .put("sections", JSONObject()
                .put("added", arr(secAdded))
                .put("removed", arr(secRemoved))
                .put("changed", arr(secChangedSorted)))
            .put("exports", JSONObject()
                .put("added", arr(expAdded.sortedByDescending { it.optLong("size") }))
                .put("removed", arr(expRemoved.sortedByDescending { it.optLong("size") }))
                .put("changed", arr(expChanged.sortedByDescending { it.optLong("delta") })))
            .put("imports", JSONObject()
                .put("added", arr(impAdded.sortedBy { it.optString("name") }))
                .put("removed", arr(impRemoved.sortedBy { it.optString("name") })))
            .put("needed", JSONObject()
                .put("added", JSONArray(needAdded))
                .put("removed", JSONArray(needRemoved)))
            .put("versions", JSONObject()
                .put("added", JSONArray(verAdded))
                .put("removed", JSONArray(verRemoved)))
            .put("limit", lim)
            .put("truncated", truncated)
    }

    private fun exportsOf(e: ElfFile): Map<String, SymbolInfo> {
        val dyn = e.dynSymbols.ifEmpty { e.symbols }
        return dyn.filter { it.exported && it.name.isNotBlank() }.associateBy { it.name }
    }

    private fun importsOf(e: ElfFile): Map<String, SymbolInfo> {
        val dyn = e.dynSymbols.ifEmpty { e.symbols }
        return dyn.filter { it.imported && it.name.isNotBlank() }.associateBy { it.name }
    }

    private fun sectionSize(e: ElfFile, name: String): Long? =
        e.sections.firstOrNull { it.name == name }?.size

    private fun textDigest(data: ByteArray, e: ElfFile): String? =
        sectionDigest(data, e, ".text")

    private fun sectionDigest(data: ByteArray, e: ElfFile, name: String): String? {
        val s = e.sections.firstOrNull { it.name == name } ?: return null
        val start = s.offset
        val size = s.size
        if (start < 0 || size <= 0 || start + size > data.size) return null
        val md = MessageDigest.getInstance("SHA-256")
        md.update(data, start.toInt(), size.toInt())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(data)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun symbolJson(name: String, s: SymbolInfo): JSONObject = JSONObject()
        .put("name", name)
        .put("size", s.size)
        .put("type", s.type)
        .put("bind", s.bind)
        .put("visibility", s.visibility)

    private fun sideJson(data: ByteArray, e: ElfFile, hash: String, exports: Int, imports: Int): JSONObject =
        JSONObject()
            .put("size", data.size.toLong())
            .put("sha256", hash)
            .put("architecture", e.architecture)
            .put("machine", e.machineName)
            .put("bits", e.bits)
            .put("endian", e.endian)
            .put("entry", "0x" + java.lang.Long.toHexString(e.entry))
            .put("sections", e.sections.size)
            .put("textSize", sectionSize(e, ".text") ?: 0L)
            .put("exports", exports)
            .put("imports", imports)
            .put("soname", ElfAudit.soname(data, e) ?: "")
}
