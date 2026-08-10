package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.*
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets

/**
 * 塔菲逆核: Android ARSC 资源表解析工具。
 *
 * 直接解析 Android compiled resources 的二进制 ARSC 格式，无外部依赖。
 * 支持: 包/类型/条目结构、三种字符串池、资源值解码。
 */
object ArscTool {

    // ── Chunk 类型常量 ──
    private const val CHUNK_NULL = 0x0000
    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_TABLE = 0x0002
    private const val CHUNK_XML = 0x0003
    private const val CHUNK_PACKAGE = 0x0200
    private const val CHUNK_TYPE = 0x0201
    private const val CHUNK_TYPE_SPEC = 0x0202
    private const val CHUNK_LIBRARY = 0x0203
    private const val CHUNK_OVERLAYABLE = 0x0204
    private const val CHUNK_OVERLAYABLE_POLICY = 0x0205
    private const val CHUNK_STAGED_ALIAS = 0x0206

    // SORT_ Flags for string pool
    private const val SORT_FLAG = 0x0001
    private const val UTF8_FLAG = 0x0100

    // 数据类型常量
    data class DataType(val code: Byte, val name: String)
    private val DATA_TYPES = mapOf(
        0.toByte() to "NULL",
        0x01.toByte() to "REFERENCE",
        0x02.toByte() to "ATTRIBUTE",
        0x03.toByte() to "STRING",
        0x04.toByte() to "FLOAT",
        0x05.toByte() to "DIMENSION",
        0x06.toByte() to "FRACTION",
        0x10.toByte() to "DYNAMIC_REFERENCE",
        0x11.toByte() to "DYNAMIC_ATTRIBUTE",
        0x1C.toByte() to "INT_DEC",
        0x1D.toByte() to "INT_HEX",
        0x1E.toByte() to "INT_BOOLEAN",
        0x1F.toByte() to "INT_COLOR_ARGB8",
        0x20.toByte() to "INT_COLOR_RGB8",
        0x21.toByte() to "INT_COLOR_ARGB4",
        0x22.toByte() to "INT_COLOR_RGB4",
    )
    private fun typeName(code: Byte): String = DATA_TYPES[code] ?: "0x%02X".format(code.toInt() and 0xFF)

    // ── 二进制读取工具 ──
    private class ArscReader(val data: ByteArray) {
        private val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var pos: Int get() = bb.position(); set(v) { bb.position(v) }

        fun u8(): Int = bb.get().toInt() and 0xFF
        fun u16(): Int = bb.getShort().toInt() and 0xFFFF
        fun u32(): Long = bb.getInt().toLong() and 0xFFFFFFFFL
        fun s32(): Int = bb.getInt()
        fun skip(n: Int) { bb.position(bb.position() + n) }
        fun bytes(n: Int): ByteArray { val a = ByteArray(n); bb.get(a); return a }

        fun stringUtf16(len: Int): String {
            // UTF-16LE, each char is 2 bytes, may not be null-terminated within the length
            val arr = ByteArray(len * 2)
            for (i in 0 until len) {
                val low = u8()
                val high = u8()
                arr[i * 2] = low.toByte()
                arr[i * 2 + 1] = high.toByte()
            }
            return String(arr, StandardCharsets.UTF_16LE).trimEnd('\u0000')
        }

        fun stringNul(len: Int): String {
            val arr = bytes(len)
            val end = arr.indexOf(0).let { if (it < 0) arr.size else it }
            return String(arr, 0, end, StandardCharsets.UTF_8)
        }
    }

    // ── 字符串池 ──
    private class StringPool(val name: String, val r: ArscReader, val chunkStart: Int) {
        data class Style(val start: Int, val end: Int, val name: String)

        val headerSize: Int
        val chunkSize: Int
        val stringCount: Int
        val styleCount: Int
        val flags: Int
        val stringsStart: Int
        val stylesStart: Int
        val offsets: IntArray
        val strings: List<String>
        val isUtf8: Boolean
        val isSorted: Boolean

        init {
            r.pos = chunkStart + 2 // skip type
            headerSize = r.u16()
            chunkSize = r.s32()
            stringCount = r.s32()
            styleCount = r.s32()
            flags = r.u16()
            stringsStart = r.s32()
            stylesStart = r.s32()
            isUtf8 = (flags and UTF8_FLAG) != 0
            isSorted = (flags and SORT_FLAG) != 0

            offsets = IntArray(stringCount) { r.s32() }

            strings = (0 until stringCount).map { idx ->
                val offset = offsets[idx]
                val base = chunkStart + stringsStart
                r.pos = base + offset
                if (isUtf8) {
                    // UTF-8: uint16_t characterCount, uint16_t byteLength, char data...
                    val charCount = readUtf8Len(r)
                    val byteLen = readUtf8Len(r)
                    val str = String(r.bytes(byteLen), StandardCharsets.UTF_8)
                    str
                } else {
                    // UTF-16: uint16_t characterCount, char data...
                    val charCount = r.u16()
                    try { r.stringUtf16(charCount) } catch (e: Exception) { "[decode error]" }
                }
            }
        }

        private fun readUtf8Len(r: ArscReader): Int {
            val b = r.u8()
            return if (b and 0x80 != 0) ((b and 0x7F) shl 8) or r.u8() else b
        }

        fun toJson(): JSONObject = JSONObject()
            .put("name", name)
            .put("count", stringCount)
            .put("isUtf8", isUtf8)
            .put("strings", JSONArray(strings))
    }

    // ── 类型定义 ──
    private data class TypeSpecInfo(
        val id: Int,
        val name: String,
        val entryCount: Int,
    )

    private data class TypeEntryInfo(
        val index: Int,
        val entryId: Long,  // resource ID
        val name: String,
        val valueStr: String,
        val valueType: String,
        val data: Long,
        val config: String,
        val isComplex: Boolean,
    )

    // ── ARSC 解析 ──
    private data class ArscResult(
        val packages: List<PackageInfo>,
        val stringPool: StringPool?,
    )

    private data class PackageInfo(
        val id: Int,
        val name: String,
        val typeStringPool: StringPool?,
        val keyStringPool: StringPool?,
        val typeSpecs: List<TypeSpecInfo>,
        val typeEntries: List<TypeEntryInfo>,
        val libraries: List<String>,
    )

    private fun parse(info: ArscParseInput): ArscResult {
        val r = ArscReader(info.data)
        val packages = mutableListOf<PackageInfo>()
        var globalPool: StringPool? = null

        r.pos = 0
        val tableType = r.u16()
        val tableHeaderSize = r.u16()
        val tableSize = r.s32()
        val packageCount = r.s32()

        var chunkEnd = tableSize

        // Walk top-level chunks
        r.pos = tableHeaderSize
        while (r.pos + 8 <= chunkEnd) {
            val chunkType = r.u16()
            val chunkHeaderSize = r.u16()
            val chunkSize = r.s32()
            val chunkStart = r.pos - 4

            when (chunkType) {
                CHUNK_STRING_POOL -> {
                    globalPool = StringPool("global", r, chunkStart)
                }
                CHUNK_PACKAGE -> {
                    r.pos = chunkStart + 2 // skip type
                    val pkgHeaderSize = r.u16()
                    val pkgSize = r.s32()
                    val pkgId = r.s32()
                    val pkgName = r.stringNul(128)

                    val pkg = PackageInfo(
                        id = pkgId,
                        name = pkgName,
                        typeStringPool = null,
                        keyStringPool = null,
                        typeSpecs = emptyList(),
                        typeEntries = emptyList(),
                        libraries = emptyList(),
                    )

                    // Read package sub-chunks
                    var pkgPos = chunkStart + pkgHeaderSize
                    val pkgEnd = chunkStart + pkgSize
                    var typePool: StringPool? = null
                    var keyPool: StringPool? = null
                    val typeSpecs = mutableListOf<TypeSpecInfo>()
                    val typeEntries = mutableListOf<TypeEntryInfo>()
                    val typeBlocks = mutableListOf<Pair<ByteArray, String>>() // raw type data + config

                    while (pkgPos + 8 <= pkgEnd) {
                        r.pos = pkgPos
                        val subType = r.u16()
                        val subHeaderSize = r.u16()
                        val subSize = r.s32()
                        val subStart = pkgPos

                        when (subType) {
                            CHUNK_STRING_POOL -> {
                                if (typePool == null) {
                                    typePool = StringPool("type_names", r, subStart)
                                } else {
                                    keyPool = StringPool("key_names", r, subStart)
                                }
                            }
                            CHUNK_TYPE_SPEC -> {
                                r.pos = subStart + 2
                                r.u16() // headerSize
                                r.s32() // size
                                val typeId = r.u8()
                                r.skip(1) // reserved
                                r.u16() // reserved
                                val entryCount = r.s32()
                                val typeName = typePool?.strings?.getOrNull(typeId - 1) ?: "type_$typeId"
                                typeSpecs.add(TypeSpecInfo(typeId, typeName, entryCount))
                            }
                            CHUNK_TYPE -> {
                                r.pos = subStart + 2
                                r.u16() // headerSize
                                r.s32() // size
                                val typeId = r.u8()
                                r.skip(1) // reserved
                                r.u16() // reserved
                                val entryCount = r.s32()
                                val entriesStart = r.s32()
                                val config = parseConfig(r)
                                val typeName = typePool?.strings?.getOrNull(typeId - 1) ?: "type_$typeId"

                                // Read entry offsets
                                r.pos = subStart + subHeaderSize
                                val entryOffsets = IntArray(entryCount) { r.s32() }

                                // Read each entry
                                for (ei in 0 until entryCount) {
                                    val offset = entryOffsets[ei]
                                    if (offset == 0xFFFFFFFF.toInt() || offset == 0) continue
                                    r.pos = subStart + entriesStart + offset

                                    val entrySize = r.u16()
                                    val entryFlags = r.u16()
                                    val keyIdx = r.s32()
                                    val isComplex = (entryFlags and 0x0001) != 0
                                    val entryName = keyPool?.strings?.getOrNull(keyIdx) ?: "entry_0x%X".format(keyIdx)

                                    val resId = (pkgId shl 24) or ((typeId and 0xFF) shl 16) or ei

                                    if (isComplex) {
                                        // Complex entry: HashMap of attribute -> value
                                        val parentId = r.s32()
                                        val mapCount = r.s32()
                                        val values = mutableListOf<String>()
                                        for (mi in 0 until mapCount) {
                                            val nameRef = r.s32()
                                            val dtype = r.u8().toByte()
                                            r.skip(3) // reserved
                                            val d = r.s32()
                                            val vStr = decodeValue(dtype, d, keyPool, typePool, globalPool)
                                            values.add("#0x%X=%s".format(nameRef.toLong() and 0xFFFFFFFFL, vStr))
                                        }
                                        typeEntries.add(TypeEntryInfo(
                                            ei, resId.toLong(), entryName,
                                            values.joinToString("; "), "COMPLEX", 0,
                                            config, true))
                                    } else {
                                        // Simple entry: direct value
                                        r.u16() // value size
                                        r.u8()  // reserved
                                        val dtype = r.u8().toByte()
                                        val d = r.s32()
                                        val vStr = decodeValue(dtype, d, keyPool, typePool, globalPool)
                                        typeEntries.add(TypeEntryInfo(
                                            ei, resId.toLong(), entryName,
                                            vStr, typeName(dtype), d.toLong(),
                                            config, false))
                                    }
                                }
                            }
                            CHUNK_LIBRARY -> {
                                r.pos = subStart + 2
                                r.u16() // headerSize
                                r.s32() // size
                                val libCount = r.s32()
                                val libs = (0 until libCount).map {
                                    val libId = r.s32()
                                    val libName = r.stringNul(128)
                                    libName
                                }
                                pkg.libraries.toMutableList().also { it.addAll(libs) }
                            }
                        }
                        pkgPos += subSize
                    }

                    packages.add(pkg.copy(
                        typeStringPool = typePool,
                        keyStringPool = keyPool,
                        typeSpecs = typeSpecs,
                        typeEntries = typeEntries,
                    ))
                }
            }
            r.pos = chunkStart + chunkSize
        }

        return ArscResult(packages, globalPool)
    }

    data class ArscParseInput(val data: ByteArray)

    private fun parseConfig(r: ArscReader): String {
        val start = r.pos
        val size = r.u32()
        if (size == 0L) return "default"
        // BlockReader config: uint32_t imsi, uint32_t locale, uint32_t screenType, etc.
        r.pos = start + 4  // skip embedded object
        // Actually let me read the config more carefully
        r.pos = start.toInt()
        val confSize = r.s32()
        if (confSize < 28) {
            r.skip((confSize - 4).coerceAtLeast(0))
            return "config[${confSize}b]"
        }
        // ResTable_config:
        val mcc = r.u16()
        val mnc = r.u16()
        val lang = arrayOf(r.u8(), r.u8()).let { String(it.map { c -> if (c == 0) ' ' else c.toChar() }.toCharArray()) }
        val country = arrayOf(r.u8(), r.u8()).let { String(it.map { c -> if (c == 0) ' ' else c.toChar() }.toCharArray()) }
        val orient = r.u8()
        val touchscreen = r.u8()
        val density = r.u16()
        val keyboard = r.u8()
        val navigation = r.u8()
        val inputFlags = r.u8()
        r.skip(1) // 0
        val screenWidth = r.u16()
        val screenHeight = r.u16()
        val sdkVersion = r.u16()
        val minorVersion = r.u16()
        r.skip((confSize - 28).coerceAtLeast(0))

        val parts = mutableListOf<String>()
        if (lang.trim().isNotBlank()) {
            val locale = if (country.trim().isNotBlank()) "${lang.trim()}-${country.trim()}" else lang.trim()
            parts.add(locale)
        }
        if (mcc != 0) parts.add("mcc$mcc")
        if (mnc != 0) parts.add("mnc$mnc")
        if (screenWidth != 0 || screenHeight != 0) parts.add("${screenWidth}x${screenHeight}")
        if (sdkVersion != 0) parts.add("v$sdkVersion")
        if (density != 0) {
            parts.add(when (density) {
                120 -> "ldpi"; 160 -> "mdpi"; 213 -> "tvdpi"
                240 -> "hdpi"; 320 -> "xhdpi"; 480 -> "xxhdpi"
                640 -> "xxxhdpi"; else -> "${density}dpi"
            })
        }
        if (orient == 1) parts.add("port")
        else if (orient == 2) parts.add("land")
        return parts.joinToString("-").ifBlank { "default" }
    }

    private fun decodeValue(dtype: Byte, data: Int, keyPool: StringPool?, typePool: StringPool?, globalPool: StringPool?): String {
        return when (dtype) {
            0.toByte() -> "@null"
            0x01.toByte() -> "@0x%08X".format(data)
            0x03.toByte() -> {
                if (data >= 0 && globalPool != null && data < globalPool.strings.size) globalPool.strings[data]
                else "(string#0x%08X)".format(data)
            }
            0x04.toByte() -> "%.2f".format(java.lang.Float.intBitsToFloat(data))
            0x05.toByte() -> decodeComplex(data, "dp")
            0x06.toByte() -> decodeComplex(data, "%")
            0x10.toByte() -> "@dynamic/0x%08X".format(data)
            0x11.toByte() -> "@dynamic_attr/0x%08X".format(data)
            0x1C.toByte() -> data.toString()
            0x1D.toByte() -> "0x%08X".format(data)
            0x1E.toByte() -> if (data != 0) "true" else "false"
            0x1F.toByte() -> "#%08X".format(data)
            0x20.toByte() -> "#%06X".format(data and 0xFFFFFF)
            0x21.toByte() -> "#%04X".format(data)
            0x22.toByte() -> "#%03X".format(data)
            else -> "0x%08X(type:0x%02X)".format(data, dtype.toInt() and 0xFF)
        }
    }

    private fun decodeComplex(data: Int, unit: String): String {
        val value = (data and 0xFFFFFF00.toInt()) shr 8
        val type = data and 0xFF
        val unitNames = arrayOf("px", "dp", "sp", "pt", "in", "mm", "", "", "", "", "", "", "", "", "", "", "%")
        val unitName = if (type < unitNames.size) unitNames[type] else "?$type"
        return "$value$unitName"
    }

    // ── MCP 工具 ──

    val analyze = EngineToolHandler(
        ToolMeta("taffy_arsc_analyze",
            "【ARSC 资源表分析】解析 Android 二进制资源文件 (.arsc / resources.arsc)。列出所有包(package)、资源类型(type)、资源条目(entry)及值。支持从 APK 内提取或直接解析 .arsc 文件。返回完整结构。",
            "Parse Android binary resource tables (.arsc / resources.arsc). Lists all packages, types, entries and their decoded values. Supports both standalone .arsc files and APK extraction.",
            "analyze", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("分析action", "parse(解析) | list_types(列出类型) | list_entries(列出条目) | search_strings(搜可读字符串)", "parse", "list_types", "list_entries", "search_strings")
                "path" str "resources.arsc 文件或 APK 的绝对路径"
                "apkPath" str "APK 文件路径（用于从 APK 提取 resources.arsc 并解析）"
                "packageId" int "筛选目标包 ID（action=list_types/list_entries, 可选）"
                "typeName" str "筛选类型名（如 string/drawable/layout, action=list_entries/search_strings, 可选）"
                "keyword" str "search_strings: 要搜的字符串/URL/文案(大小写不敏感)"
                "regex" str "search_strings: 可选正则(优先于 keyword), 如 https?://"
                "limit" int "最多返回条目（默认 500）"
            })
        }
    ) { _, a, s ->
        val path = a.str("path").ifBlank { a.str("apkPath") }
        if (path.isBlank()) return@EngineToolHandler err("INVALID_ARGUMENT", "请指定 path (arsc 文件或 APK)", "path", "")

        val file = java.io.File(path)
        if (!file.isFile) return@EngineToolHandler err("FILE_NOT_FOUND", "文件不存在", "path", path)

        // If APK, extract resources.arsc from it
        val arscBytes: ByteArray = if (path.lowercase().endsWith(".apk")) {
            runCatching {
                java.util.zip.ZipFile(file).use { zf ->
                    val entry = zf.getEntry("resources.arsc")
                        ?: return@EngineToolHandler err("NOT_FOUND", "APK 内未找到 resources.arsc", "path", path)
                    zf.getInputStream(entry).readAllBytes()
                }
            }.getOrElse { e ->
                return@EngineToolHandler err("EXTRACT_FAILED", "从 APK 提取 resources.arsc 失败: ${e.message}", "path", path)
            }
        } else {
            file.readBytes()
        }

        // Quick magic check
        if (arscBytes.size < 12 || (arscBytes[0].toInt() and 0xFF) != 0x02 || (arscBytes[1].toInt() and 0xFF) != 0x00) {
            return@EngineToolHandler err("NOT_ARSC", "不是有效的 ARSC 文件（魔数错误）", "path", path)
        }

        val action = a.str("action", "parse")

        val result = try {
            parse(ArscParseInput(arscBytes))
        } catch (e: Exception) {
            return@EngineToolHandler err("PARSE_ERROR", "ARSC 解析失败: ${e.message}", "path", path)
        }

        val limit = a.intValue("limit", 500).coerceIn(1, 10000)

        when (action) {
            "list_types" -> {
                val pkgId = a.intValue("packageId", 0)
                val types = JSONArray()
                for (pkg in result.packages) {
                    if (pkgId > 0 && pkg.id != pkgId) continue
                    for (spec in pkg.typeSpecs) {
                        types.put(JSONObject()
                            .put("package", "${pkg.name} (id=${pkg.id})")
                            .put("typeId", spec.id)
                            .put("typeName", spec.name)
                            .put("entryCount", spec.entryCount))
                    }
                }
                ok(JSONObject()
                    .put("packageCount", result.packages.size)
                    .put("typeCount", types.length())
                    .put("types", types))
            }

            "list_entries" -> {
                val pkgId = a.intValue("packageId", 0)
                val typeName = a.str("typeName")
                val entries = JSONArray()
                var count = 0
                for (pkg in result.packages) {
                    if (pkgId > 0 && pkg.id != pkgId) continue
                    for (entry in pkg.typeEntries) {
                        if (typeName.isNotBlank()) {
                            val spec = pkg.typeSpecs.firstOrNull { it.entryCount > entry.index && it.id == (entry.entryId shr 16).toInt() }
                            if (spec != null && spec.name != typeName) continue
                        }
                        if (count >= limit) break
                        entries.put(JSONObject()
                            .put("resourceId", "0x%08X".format(entry.entryId))
                            .put("package", pkg.name)
                            .put("name", entry.name)
                            .put("value", entry.valueStr)
                            .put("type", entry.valueType)
                            .put("config", entry.config)
                            .put("isComplex", entry.isComplex))
                        count++
                    }
                    if (count >= limit) break
                }
                ok(JSONObject()
                    .put("total", (result.packages.sumOf { p -> p.typeEntries.size }))
                    .put("returned", entries.length())
                    .put("entries", entries))
            }

            "search_strings" -> {
                // 对标 RzDroid 资源字符串搜索：扫所有条目, 返回含关键词的可读字符串值(含 URL/文案/接口名)。
                val keyword = a.optString("keyword", "").trim()
                val typeName = a.str("typeName")
                val regex = a.optString("regex", "").trim()
                if (keyword.isBlank() && regex.isBlank()) {
                    return@EngineToolHandler err("INVALID_ARGUMENT", "search_strings 需要 keyword 或 regex", "keyword", "")
                }
                var regexPat: Regex? = null
                if (regex.isNotBlank()) {
                    regexPat = runCatching { Regex(regex, RegexOption.IGNORE_CASE) }.getOrNull()
                        ?: return@EngineToolHandler err("INVALID_REGEX", "regex 不合法: $regex", "regex", regex)
                }
                val kw = keyword.lowercase()
                val hits = JSONArray()
                var matched = 0
                // typeId → 类型名 映射(resourceId 高16位含 typeId)
                val typeIdToName = HashMap<Int, String>()
                for (pkg in result.packages) {
                    for (spec in pkg.typeSpecs) typeIdToName[spec.id] = spec.name
                }
                outer@ for (pkg in result.packages) {
                    for (entry in pkg.typeEntries) {
                        val entryTypeId = ((entry.entryId shr 16) and 0xFF).toInt()
                        val entryTypeName = typeIdToName[entryTypeId] ?: entry.valueType
                        if (typeName.isNotBlank() && entryTypeName != typeName) continue
                        // 该条目的所属类型名(用 typeName 或按 resourceId 高位逆推)
                        val hay = entry.name + "\n" + entry.valueStr
                        val okHit = if (regexPat != null) {
                            regexPat.containsMatchIn(hay)
                        } else {
                            hay.lowercase().contains(kw)
                        }
                        if (!okHit) continue
                        if (matched >= limit) { matched++; break@outer }
                        hits.put(JSONObject()
                            .put("resourceId", "0x%08X".format(entry.entryId))
                            .put("name", entry.name)
                            .put("value", entry.valueStr)
                            .put("type", entryTypeName)
                            .put("config", entry.config)
                            .put("isComplex", entry.isComplex))
                        matched++
                    }
                }
                ok(JSONObject()
                    .put("action", "search_strings")
                    .put("keyword", if (regex.isNotBlank()) "/$regex/" else keyword)
                    .put("matched", matched)
                    .put("hits", hits))
            }

            else -> { // parse - full dump
                val packages = JSONArray()
                for (pkg in result.packages) {
                    val pkgJson = JSONObject()
                        .put("id", pkg.id)
                        .put("name", pkg.name)
                        .put("typeCount", pkg.typeSpecs.size)
                        .put("entryCount", pkg.typeEntries.size)

                    val types = JSONArray()
                    for (spec in pkg.typeSpecs) {
                        types.put(JSONObject()
                            .put("id", spec.id)
                            .put("name", spec.name)
                            .put("entryCount", spec.entryCount))
                    }
                    pkgJson.put("types", types)

                    if (pkg.typeEntries.isNotEmpty()) {
                        val entries = JSONArray()
                        var ec = 0
                        for (entry in pkg.typeEntries) {
                            if (ec >= limit) break
                            entries.put(JSONObject()
                                .put("resourceId", "0x%08X".format(entry.entryId))
                                .put("name", entry.name)
                                .put("value", entry.valueStr)
                                .put("valueType", entry.valueType)
                                .put("config", entry.config))
                            ec++
                        }
                        pkgJson.put("entries", entries)
                        pkgJson.put("entriesReturned", ec)
                    }
                    packages.put(pkgJson)
                }

                ok(JSONObject()
                    .put("file", path)
                    .put("fileSize", arscBytes.size)
                    .put("packageCount", result.packages.size)
                    .put("globalStringCount", result.stringPool?.stringCount ?: 0)
                    .put("packages", packages))
            }
        }
    }
}
