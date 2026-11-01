// SPDX-License-Identifier: GPL-3.0-or-later
//
// 纯 Kotlin 的 PE / ECMA-335 metadata 读取器。
//
// 为什么需要它：PE / .NET 分析原本完全依赖 libdsmcp_native.so，而那个库是上游
// 更早的预编译产物（SOMCP 现在的 cpp/ 里已经没有它的源码），PT_LOAD p_align 只有
// 0x1000 —— Android 15 起 16 KiB page size 的设备会直接拒绝加载，于是 .NET 工具在
// 那类设备上整块不可用。这里把**只读**能力用 Kotlin 重做，native 不可用时自动接管。
//
// 结构完全按 ECMA-335 规范：
//   PE → OptionalHeader.DataDirectory[14] (CLR runtime header) → COR20 header
//      → metadata root ("BSJB") → streams (#~ / #Strings / #US / #GUID / #Blob)
//      → 表流(#~)：Valid 位图给出哪些表存在，按表号顺序连续存放
//
// 表流最麻烦的地方是行宽：每一列可能是定长、可能是 heap 索引（宽度由 HeapSizes 位
// 决定）、也可能是表索引或 coded index（宽度由目标表行数是否超过 0xFFFF / tag 位数
// 决定）。要能"跳过"前面的表就必须把 45 张表的 schema 全部写出来 —— 这也是本文件
// 大半体积的来源。
package com.dsmcp.tool.engine

import org.json.JSONArray
import org.json.JSONObject

internal object DotNetReader {

    // ── 二进制读取（全部带边界检查：输入是完全不可信的 .dll） ──

    private fun u8(b: ByteArray, o: Int): Int =
        if (o < 0 || o >= b.size) 0 else b[o].toInt() and 0xff

    private fun u16(b: ByteArray, o: Int): Int =
        if (o < 0 || o + 2 > b.size) 0 else (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

    private fun u32(b: ByteArray, o: Int): Long =
        if (o < 0 || o + 4 > b.size) 0L
        else (b[o].toLong() and 0xff) or ((b[o + 1].toLong() and 0xff) shl 8) or
            ((b[o + 2].toLong() and 0xff) shl 16) or ((b[o + 3].toLong() and 0xff) shl 24)

    private fun u64(b: ByteArray, o: Int): Long =
        if (o < 0 || o + 8 > b.size) 0L else u32(b, o) or (u32(b, o + 4) shl 32)

    /** 读一个宽度为 [w]（2 或 4）字节的无符号值。 */
    private fun uN(b: ByteArray, o: Int, w: Int): Long = if (w == 4) u32(b, o) else u16(b, o).toLong()

    // ── PE 结构 ──

    private const val PE_SIG = 0x00004550
    private const val BSJB = 0x424A5342
    private const val DIR_CLR = 14

    internal class Section(val name: String, val va: Long, val span: Long, val rawPtr: Long, val rawSize: Long)

    internal class PeInfo(
        val is64: Boolean,
        val machine: Int,
        val sections: List<Section>,
        val clrRva: Long,
        val clrSize: Long,
    )

    /** 解析 PE 头；不是合法 PE 时返回 null。 */
    internal fun parsePe(d: ByteArray): PeInfo? {
        if (d.size < 0x40) return null
        if (u16(d, 0) != 0x5A4D) return null                       // 'MZ'
        val peOff = u32(d, 0x3C).toInt()
        if (peOff < 0 || peOff + 24 > d.size) return null
        if (u32(d, peOff).toInt() != PE_SIG) return null            // 'PE\0\0'
        val coff = peOff + 4
        val machine = u16(d, coff)
        val nsec = u16(d, coff + 2)
        val optSize = u16(d, coff + 16)
        if (nsec > 96 || optSize < 68) return null
        val opt = coff + 20
        if (opt + optSize > d.size) return null
        val magic = u16(d, opt)
        val is64 = when (magic) {
            0x20B -> true
            0x10B -> false
            else -> return null
        }
        val ddBase = opt + if (is64) 112 else 96
        var clrRva = 0L
        var clrSize = 0L
        if (ddBase + (DIR_CLR + 1) * 8 <= opt + optSize) {
            clrRva = u32(d, ddBase + DIR_CLR * 8)
            clrSize = u32(d, ddBase + DIR_CLR * 8 + 4)
        }
        val sh = opt + optSize
        if (sh + nsec * 40 > d.size) return null
        val sections = ArrayList<Section>(nsec)
        for (i in 0 until nsec) {
            val o = sh + i * 40
            val name = buildString {
                for (k in 0 until 8) {
                    val c = u8(d, o + k)
                    if (c == 0) break
                    append(c.toChar())
                }
            }
            val vsize = u32(d, o + 8)
            val va = u32(d, o + 12)
            val rsize = u32(d, o + 16)
            val raddr = u32(d, o + 20)
            sections += Section(name, va, maxOf(vsize, rsize), raddr, rsize)
        }
        return PeInfo(is64, machine, sections, clrRva, clrSize)
    }

    /** RVA → 文件偏移；不在任何节内（或落在未初始化尾部）返回 -1。 */
    internal fun rvaToOffset(pe: PeInfo, rva: Long): Int {
        if (rva <= 0L) return -1
        for (s in pe.sections) {
            if (rva < s.va || rva >= s.va + s.span) continue
            val delta = rva - s.va
            if (s.rawSize in 1..delta) return -1
            val off = s.rawPtr + delta
            if (off < 0L || off > Int.MAX_VALUE.toLong()) return -1
            return off.toInt()
        }
        return -1
    }

    // ── 表 schema（ECMA-335 II.22） ──
    //
    // 列类型编码：
    //   "u1"/"u2"/"u4"  定长整数
    //   "str"/"guid"/"blob"  heap 索引（宽度由 HeapSizes 位决定）
    //   "t:Table"       表索引（目标表行数 > 0xFFFF 时用 4 字节）
    //   "c:Code"        coded index（宽度由 tag 位与目标表最大行数决定）

    private class Col(val name: String, val kind: String)

    private val SCHEMA: Map<Int, Pair<String, List<Col>>> = buildMap {
        fun t(idx: Int, name: String, vararg cols: Pair<String, String>) {
            put(idx, name to cols.map { Col(it.first, it.second) })
        }
        t(0x00, "Module", "Generation" to "u2", "Name" to "str", "Mvid" to "guid", "EncId" to "guid", "EncBaseId" to "guid")
        t(0x01, "TypeRef", "ResolutionScope" to "c:ResolutionScope", "TypeName" to "str", "TypeNamespace" to "str")
        t(0x02, "TypeDef", "Flags" to "u4", "TypeName" to "str", "TypeNamespace" to "str", "Extends" to "c:TypeDefOrRef", "FieldList" to "t:Field", "MethodList" to "t:MethodDef")
        t(0x03, "FieldPtr", "Field" to "t:Field")
        t(0x04, "Field", "Flags" to "u2", "Name" to "str", "Signature" to "blob")
        t(0x05, "MethodPtr", "Method" to "t:MethodDef")
        t(0x06, "MethodDef", "RVA" to "u4", "ImplFlags" to "u2", "Flags" to "u2", "Name" to "str", "Signature" to "blob", "ParamList" to "t:Param")
        t(0x07, "ParamPtr", "Param" to "t:Param")
        t(0x08, "Param", "Flags" to "u2", "Sequence" to "u2", "Name" to "str")
        t(0x09, "InterfaceImpl", "Class" to "t:TypeDef", "Interface" to "c:TypeDefOrRef")
        t(0x0A, "MemberRef", "Class" to "c:MemberRefParent", "Name" to "str", "Signature" to "blob")
        t(0x0B, "Constant", "Type" to "u1", "Padding" to "u1", "Parent" to "c:HasConstant", "Value" to "blob")
        t(0x0C, "CustomAttribute", "Parent" to "c:HasCustomAttribute", "Type" to "c:CustomAttributeType", "Value" to "blob")
        t(0x0D, "FieldMarshal", "Parent" to "c:HasFieldMarshal", "NativeType" to "blob")
        t(0x0E, "DeclSecurity", "Action" to "u2", "Parent" to "c:HasDeclSecurity", "PermissionSet" to "blob")
        t(0x0F, "ClassLayout", "PackingSize" to "u2", "ClassSize" to "u4", "Parent" to "t:TypeDef")
        t(0x10, "FieldLayout", "Offset" to "u4", "Field" to "t:Field")
        t(0x11, "StandAloneSig", "Signature" to "blob")
        t(0x12, "EventMap", "Parent" to "t:TypeDef", "EventList" to "t:Event")
        t(0x13, "EventPtr", "Event" to "t:Event")
        t(0x14, "Event", "EventFlags" to "u2", "Name" to "str", "EventType" to "c:TypeDefOrRef")
        t(0x15, "PropertyMap", "Parent" to "t:TypeDef", "PropertyList" to "t:Property")
        t(0x16, "PropertyPtr", "Property" to "t:Property")
        t(0x17, "Property", "Flags" to "u2", "Name" to "str", "Type" to "blob")
        t(0x18, "MethodSemantics", "Semantics" to "u2", "Method" to "t:MethodDef", "Association" to "c:HasSemantics")
        t(0x19, "MethodImpl", "Class" to "t:TypeDef", "MethodBody" to "c:MethodDefOrRef", "MethodDeclaration" to "c:MethodDefOrRef")
        t(0x1A, "ModuleRef", "Name" to "str")
        t(0x1B, "TypeSpec", "Signature" to "blob")
        t(0x1C, "ImplMap", "MappingFlags" to "u2", "MemberForwarded" to "c:MemberForwarded", "ImportName" to "str", "ImportScope" to "t:ModuleRef")
        t(0x1D, "FieldRVA", "RVA" to "u4", "Field" to "t:Field")
        t(0x1E, "EncLog", "Token" to "u4", "FuncCode" to "u4")
        t(0x1F, "EncMap", "Token" to "u4")
        t(0x20, "Assembly", "HashAlgId" to "u4", "MajorVersion" to "u2", "MinorVersion" to "u2", "BuildNumber" to "u2", "RevisionNumber" to "u2", "Flags" to "u4", "PublicKey" to "blob", "Name" to "str", "Culture" to "str")
        t(0x21, "AssemblyProcessor", "Processor" to "u4")
        t(0x22, "AssemblyOS", "OSPlatformID" to "u4", "OSMajorVersion" to "u4", "OSMinorVersion" to "u4")
        t(0x23, "AssemblyRef", "MajorVersion" to "u2", "MinorVersion" to "u2", "BuildNumber" to "u2", "RevisionNumber" to "u2", "Flags" to "u4", "PublicKeyOrToken" to "blob", "Name" to "str", "Culture" to "str", "HashValue" to "blob")
        t(0x24, "AssemblyRefProcessor", "Processor" to "u4", "AssemblyRef" to "t:AssemblyRef")
        t(0x25, "AssemblyRefOS", "OSPlatformId" to "u4", "OSMajorVersion" to "u4", "OSMinorVersion" to "u4", "AssemblyRef" to "t:AssemblyRef")
        t(0x26, "File", "Flags" to "u4", "Name" to "str", "HashValue" to "blob")
        t(0x27, "ExportedType", "Flags" to "u4", "TypeDefId" to "u4", "TypeName" to "str", "TypeNamespace" to "str", "Implementation" to "c:Implementation")
        t(0x28, "ManifestResource", "Offset" to "u4", "Flags" to "u4", "Name" to "str", "Implementation" to "c:Implementation")
        t(0x29, "NestedClass", "NestedClass" to "t:TypeDef", "EnclosingClass" to "t:TypeDef")
        t(0x2A, "GenericParam", "Number" to "u2", "Flags" to "u2", "Owner" to "c:TypeOrMethodDef", "Name" to "str")
        t(0x2B, "MethodSpec", "Method" to "c:MethodDefOrRef", "Instantiation" to "blob")
        t(0x2C, "GenericParamConstraint", "Owner" to "t:GenericParam", "Constraint" to "c:TypeDefOrRef")
    }

    private val TABLE_INDEX: Map<String, Int> = SCHEMA.entries.associate { (k, v) -> v.first to k }

    /** coded index：tag 位数 + 目标表顺序（null 表示该 tag 值保留不用）。 */
    private val CODES: Map<String, Pair<Int, List<String?>>> = mapOf(
        "TypeDefOrRef" to (2 to listOf("TypeDef", "TypeRef", "TypeSpec")),
        "HasConstant" to (2 to listOf("Field", "Param", "Property")),
        "HasCustomAttribute" to (5 to listOf(
            "MethodDef", "Field", "TypeRef", "TypeDef", "Param", "InterfaceImpl", "MemberRef",
            "Module", "DeclSecurity", "Property", "Event", "StandAloneSig", "ModuleRef",
            "TypeSpec", "Assembly", "AssemblyRef", "File", "ExportedType", "ManifestResource",
            "GenericParam", "GenericParamConstraint", "MethodSpec",
        )),
        "HasFieldMarshal" to (1 to listOf("Field", "Param")),
        "HasDeclSecurity" to (2 to listOf("TypeDef", "MethodDef", "Assembly")),
        "MemberRefParent" to (3 to listOf("TypeDef", "TypeRef", "ModuleRef", "MethodDef", "TypeSpec")),
        "HasSemantics" to (1 to listOf("Event", "Property")),
        "MethodDefOrRef" to (1 to listOf("MethodDef", "MemberRef")),
        "MemberForwarded" to (1 to listOf("Field", "MethodDef")),
        "Implementation" to (2 to listOf("File", "AssemblyRef", "ExportedType")),
        "CustomAttributeType" to (3 to listOf(null, null, "MethodDef", "MemberRef", null)),
        "ResolutionScope" to (2 to listOf("Module", "ModuleRef", "AssemblyRef", "TypeRef")),
        "TypeOrMethodDef" to (1 to listOf("TypeDef", "MethodDef")),
    )

    // ── metadata ──

    internal class Meta(
        private val d: ByteArray,
        val pe: PeInfo,
        private val rootOff: Int,
    ) {
        /** metadata root（"BSJB"）在文件里的偏移。 */
        val rootOffset: Int get() = rootOff

        val streams = HashMap<String, Pair<Int, Int>>()
        /** 表号 → 行数（只包含 Valid 位为 1 的表）。 */
        val rows = IntArray(64)
        val heapSizes: Int
        private val widths = IntArray(64)
        private val offsets = IntArray(64)
        private val validMask: Long
        val stringBase: Int
        val stringSize: Int
        val usBase: Int
        val usSize: Int
        val blobBase: Int
        val blobSize: Int
        val guidBase: Int
        val guidSize: Int
        val tableBase: Int

        init {
            if (u32(d, rootOff) != BSJB.toLong()) throw IllegalArgumentException("metadata root 签名不是 BSJB")
            val verLen = u32(d, rootOff + 12).toInt()
            if (verLen < 0 || verLen > 4096) throw IllegalArgumentException("metadata 版本串长度异常")
            var p = rootOff + 16 + verLen
            val nStreams = u16(d, p + 2)
            p += 4
            for (i in 0 until minOf(nStreams, 16)) {
                val so = u32(d, p).toInt()
                val ss = u32(d, p + 4).toInt()
                p += 8
                var end = p
                while (end < d.size && d[end] != 0.toByte() && end - p < 32) end++
                val name = String(d, p, end - p, Charsets.ISO_8859_1)
                p = (end + 1 + 3) and 3.inv()
                if (so >= 0 && ss >= 0) streams[name] = (rootOff + so) to ss
            }
            val tbl = streams["#~"] ?: streams["#-"]
                ?: throw IllegalArgumentException("没有 #~ 表流")
            tableBase = tbl.first
            heapSizes = u8(d, tableBase + 6)
            validMask = u64(d, tableBase + 8)
            p = tableBase + 24
            for (i in 0 until 64) {
                if ((validMask ushr i) and 1L == 0L) continue
                rows[i] = u32(d, p).toInt().coerceAtLeast(0)
                p += 4
            }
            // 表流里出现 schema 之外的表（例如 PDB 才有的 0x30+ 表）时宁可整体放弃，
            // 也不能按 0 宽度跳过去 —— 那样后面每张表的偏移都是错的，读出来的是垃圾数据
            // 却看起来像正常结果。
            for (i in SCHEMA.keys) widths[i] = rowWidth(i)
            for (i in 0 until 64) {
                if (rows[i] > 0 && widths[i] <= 0) {
                    throw IllegalArgumentException("表流包含未支持的表 0x" + Integer.toHexString(i))
                }
            }
            var q = p
            for (i in 0 until 64) {
                offsets[i] = q
                val r = rows[i]
                if (r > 0 && widths[i] > 0) {
                    val add = r.toLong() * widths[i]
                    q += if (add > Int.MAX_VALUE) 0 else add.toInt()
                }
            }
            val s = streams["#Strings"] ?: (0 to 0)
            stringBase = s.first; stringSize = s.second
            val us = streams["#US"] ?: (0 to 0)
            usBase = us.first; usSize = us.second
            val bl = streams["#Blob"] ?: (0 to 0)
            blobBase = bl.first; blobSize = bl.second
            val gd = streams["#GUID"] ?: (0 to 0)
            guidBase = gd.first; guidSize = gd.second
        }

        private fun tableIdxSize(name: String): Int {
            val t = TABLE_INDEX[name] ?: return 2
            return if (rows[t] > 0xFFFF) 4 else 2
        }

        private fun codedIdxSize(code: String): Int {
            val spec = CODES[code] ?: return 2
            val bits = spec.first
            var max = 0
            for (t in spec.second) {
                if (t == null) continue
                val idx = TABLE_INDEX[t] ?: continue
                if (rows[idx] > max) max = rows[idx]
            }
            return if ((max ushr (16 - bits)) > 0) 4 else 2
        }

        fun colSize(kind: String): Int = when {
            kind.startsWith("c:") -> codedIdxSize(kind.substring(2))
            kind.startsWith("t:") -> tableIdxSize(kind.substring(2))
            kind == "str" -> if (heapSizes and 0x01 != 0) 4 else 2
            kind == "guid" -> if (heapSizes and 0x02 != 0) 4 else 2
            kind == "blob" -> if (heapSizes and 0x04 != 0) 4 else 2
            kind == "u1" -> 1
            kind == "u2" -> 2
            kind == "u4" -> 4
            else -> 2
        }

        private fun rowWidth(table: Int): Int {
            val sch = SCHEMA[table] ?: return 0
            var w = 0
            for (c in sch.second) w += colSize(c.kind)
            return w
        }

        fun rowCount(table: Int): Int = rows[table]

        /** 读某张表某一行的某个列（按列名）。[row] 为 **1-based** 行号。 */
        fun read(table: Int, row: Int, col: String): Long {
            val sch = SCHEMA[table] ?: return 0L
            if (row <= 0 || row > rows[table]) return 0L
            var o = offsets[table] + (row - 1) * widths[table]
            for (c in sch.second) {
                val sz = colSize(c.kind)
                if (c.name == col) {
                    return when (c.kind) {
                        "u1" -> u8(d, o).toLong()
                        "u2" -> u16(d, o).toLong()
                        "u4" -> u32(d, o)
                        else -> uN(d, o, sz)
                    }
                }
                o += sz
            }
            return 0L
        }

        /** 把 coded index 拆成 (表号, 行号 1-based)；无法解析时返回 null。 */
        fun decodeCoded(code: String, raw: Long): Pair<Int, Int>? {
            val spec = CODES[code] ?: return null
            val bits = spec.first
            val tag = (raw and ((1L shl bits) - 1L)).toInt()
            val list = spec.second
            if (tag < 0 || tag >= list.size) return null
            val tname = list[tag] ?: return null
            val t = TABLE_INDEX[tname] ?: return null
            val row = (raw ushr bits).toInt()
            if (row <= 0 || row > rows[t]) return null
            return t to row
        }

        fun stringAt(off: Long): String {
            val o = off.toInt()
            if (o < 0 || o >= stringSize) return ""
            var e = o
            val limit = minOf(stringSize, d.size - stringBase)
            while (e < limit && d[stringBase + e] != 0.toByte()) e++
            return String(d, stringBase + o, e - o, Charsets.UTF_8)
        }

        /** blob 堆：首字节是压缩长度（0x80 / 0xC0 前缀表示 2/4 字节）。 */
        fun blobAt(off: Long): ByteArray {
            val o = off.toInt()
            if (o < 0 || o >= blobSize) return ByteArray(0)
            var p = blobBase + o
            val end = minOf(d.size, blobBase + blobSize)
            if (p >= end) return ByteArray(0)
            var b0 = d[p].toInt() and 0xff
            var len: Int
            when {
                b0 and 0x80 == 0 -> { len = b0; p += 1 }
                b0 and 0xC0 == 0x80 -> {
                    if (p + 1 >= end) return ByteArray(0)
                    len = ((b0 and 0x3F) shl 8) or (d[p + 1].toInt() and 0xff); p += 2
                }
                else -> {
                    if (p + 3 >= end) return ByteArray(0)
                    len = ((b0 and 0x1F) shl 24) or ((d[p + 1].toInt() and 0xff) shl 16) or
                        ((d[p + 2].toInt() and 0xff) shl 8) or (d[p + 3].toInt() and 0xff)
                    p += 4
                }
            }
            if (len < 0) return ByteArray(0)
            if (p + len > end) return ByteArray(0)
            return d.copyOfRange(p, p + len)
        }

        /** #US 堆里的一串（首字节是长度，末尾 1 字节是 "是否含非 ASCII" 标志）。 */
        fun userStringAt(off: Int): String {
            if (off < 0 || off >= usSize) return ""
            val base = usBase + off
            val end = minOf(d.size, usBase + usSize)
            if (base >= end) return ""
            var b0 = d[base].toInt() and 0xff
            var p = base
            var len: Int
            when {
                b0 and 0x80 == 0 -> { len = b0; p += 1 }
                b0 and 0xC0 == 0x80 -> {
                    if (p + 1 >= end) return ""
                    len = ((b0 and 0x3F) shl 8) or (d[p + 1].toInt() and 0xff); p += 2
                }
                else -> {
                    if (p + 3 >= end) return ""
                    len = ((b0 and 0x1F) shl 24) or ((d[p + 1].toInt() and 0xff) shl 16) or
                        ((d[p + 2].toInt() and 0xff) shl 8) or (d[p + 3].toInt() and 0xff)
                    p += 4
                }
            }
            if (len < 0 || p + len > end) return ""
            // 末尾 1 字节是标志位，不参与解码
            val payload = if (len >= 1) len - 1 else 0
            if (p + payload > end) return ""
            return String(d, p, payload, Charsets.UTF_16LE)
        }
    }

    /** 打开一个 .NET 程序集；不是有效程序集时返回 null（不抛异常）。 */
    internal fun open(data: ByteArray): Meta? = try {
        val pe = parsePe(data) ?: return null
        if (pe.clrRva == 0L) return null
        val cor = rvaToOffset(pe, pe.clrRva)
        if (cor < 0 || cor + 72 > data.size) return null
        val mdRva = u32(data, cor + 8)
        val mdSize = u32(data, cor + 12).toInt()
        if (mdRva == 0L || mdSize <= 0) return null
        val mdOff = rvaToOffset(pe, mdRva)
        if (mdOff < 0) return null
        Meta(data, pe, mdOff)
    } catch (_: Throwable) {
        null
    }

    // ── 对外 API（输出字段名自解释；这些工具目前只经 MCP 暴露给 AI 客户端） ──

    fun detect(data: ByteArray): JSONObject {
        val out = JSONObject()
        out.put("fileSize", data.size.toLong())
        val hasMz = data.size >= 2 && u16(data, 0) == 0x5A4D
        out.put("hasMZHeader", hasMz)
        val pe = parsePe(data)
        out.put("hasPEHeader", pe != null)
        if (pe == null) {
            out.put("peValid", false).put("isDotNet", false).put("engine", "kotlin")
            return out
        }
        out.put("peValid", true)
        out.put("machine", String.format("0x%04x", pe.machine))
        out.put("is64", pe.is64)
        out.put("sections", pe.sections.size)
        out.put("hasClrDataDir", pe.clrRva != 0L)
        out.put("clrRva", pe.clrRva)
        out.put("clrSize", pe.clrSize)
        val meta = open(data)
        out.put("bsjbFound", meta != null)
        out.put("isDotNet", meta != null)
        out.put("engine", "kotlin")
        if (meta == null) return out
        val cor = rvaToOffset(pe, pe.clrRva)
        if (cor >= 0) {
            out.put("runtimeVersion", "${u16(data, cor + 4)}.${u16(data, cor + 6)}")
            out.put("corFlags", String.format("0x%08x", u32(data, cor + 16)))
            out.put("entryPointToken", String.format("0x%08x", u32(data, cor + 20)))
        }
        val nAsm = meta.rowCount(0x20)
        if (nAsm > 0) {
            out.put("assemblyName", meta.stringAt(meta.read(0x20, 1, "Name")))
            out.put("assemblyVersion",
                "${meta.read(0x20, 1, "MajorVersion")}.${meta.read(0x20, 1, "MinorVersion")}." +
                    "${meta.read(0x20, 1, "BuildNumber")}.${meta.read(0x20, 1, "RevisionNumber")}")
        }
        out.put("moduleName", if (meta.rowCount(0x00) > 0) meta.stringAt(meta.read(0x00, 1, "Name")) else "")
        out.put("typeCount", meta.rowCount(0x02))
        out.put("methodCount", meta.rowCount(0x06))
        out.put("fieldCount", meta.rowCount(0x04))
        out.put("memberRefCount", meta.rowCount(0x0A))
        out.put("assemblyRefCount", meta.rowCount(0x23))
        val streams = JSONObject()
        for ((k, v) in meta.streams) streams.put(k, v.second)
        out.put("streams", streams)
        // 与 native 实现的字段对齐，避免上层按名字取不到
        out.put("hasStringsStream", meta.streams.containsKey("#Strings"))
        out.put("hasUsStream", meta.streams.containsKey("#US"))
        out.put("hasBlobStream", meta.streams.containsKey("#Blob"))
        out.put("hasTablesStream", meta.streams.containsKey("#~") || meta.streams.containsKey("#-"))
        out.put("bsjbOffset", meta.rootOffset.toLong())
        return out
    }

    /** TypeDef 的完整名（含外层类型前缀）。 */
    private fun fullTypeName(m: Meta, row: Int): String {
        val ns = m.stringAt(m.read(0x02, row, "TypeNamespace"))
        val name = m.stringAt(m.read(0x02, row, "TypeName"))
        return if (ns.isEmpty()) name else "$ns.$name"
    }

    /** 建 TypeDef 行号 → 外层类型行号（NestedClass 表）。 */
    private fun nestedMap(m: Meta): Map<Int, Int> {
        val n = m.rowCount(0x29)
        if (n == 0) return emptyMap()
        val map = HashMap<Int, Int>(n * 2)
        for (i in 0 until n) {
            val nested = m.read(0x29, i + 1, "NestedClass").toInt()
            val enclosing = m.read(0x29, i + 1, "EnclosingClass").toInt()
            if (nested > 0 && enclosing > 0) map[nested] = enclosing
        }
        return map
    }

    fun listTypes(data: ByteArray): JSONObject {
        val m = open(data) ?: return JSONObject().put("error", "不是 .NET 程序集（找不到 BSJB metadata）")
        val n = m.rowCount(0x02)
        val nested = nestedMap(m)
        val arr = JSONArray()
        for (row in 1..n) {
            val ns = m.stringAt(m.read(0x02, row, "TypeNamespace"))
            val name = m.stringAt(m.read(0x02, row, "TypeName"))
            if (name.isEmpty()) continue
            val o = JSONObject()
            val token = 0x02000000 or row
            o.put("token", String.format("0x%08x", token))
            o.put("typeToken", token)
            o.put("name", name)
            o.put("typeName", name)
            o.put("namespace", ns)
            o.put("fullName", if (ns.isEmpty()) name else "$ns.$name")
            o.put("flags", String.format("0x%08x", m.read(0x02, row, "Flags")))
            o.put("isNested", nested.containsKey(row))
            nested[row]?.let { o.put("enclosingType", fullTypeName(m, it)) }
            arr.put(o)
        }
        return JSONObject().put("types", arr).put("count", arr.length()).put("engine", "kotlin")
    }

    fun listMethods(data: ByteArray, typeToken: Int): JSONObject {
        val m = open(data) ?: return JSONObject().put("error", "不是 .NET 程序集（找不到 BSJB metadata）")
        val nTypes = m.rowCount(0x02)
        val nMethods = m.rowCount(0x06)
        // TypeDef.MethodList 是「起始行号」，下一个类型的起始行号减一即本类型的方法上界
        val owner = IntArray(nMethods + 2) { -1 }
        for (row in 1..nTypes) {
            val start = m.read(0x02, row, "MethodList").toInt()
            val next = if (row < nTypes) m.read(0x02, row + 1, "MethodList").toInt() else nMethods + 1
            var i = if (start < 1) 1 else start
            while (i < next && i <= nMethods) {
                owner[i] = row
                i++
            }
        }
        val filterRow = if (typeToken ushr 24 == 0x02) typeToken and 0x00FFFFFF else 0
        val arr = JSONArray()
        for (row in 1..nMethods) {
            if (filterRow > 0 && owner[row] != filterRow) continue
            val name = m.stringAt(m.read(0x06, row, "Name"))
            if (name.isEmpty()) continue
            val o = JSONObject()
            val token = 0x06000000 or row
            o.put("token", String.format("0x%08x", token))
            o.put("methodToken", token)
            o.put("name", name)
            val rva = m.read(0x06, row, "RVA")
            o.put("rva", rva)
            o.put("rvaHex", String.format("0x%x", rva))
            o.put("flags", String.format("0x%04x", m.read(0x06, row, "Flags")))
            o.put("implFlags", String.format("0x%04x", m.read(0x06, row, "ImplFlags")))
            val ownerRow = owner[row]
            if (ownerRow > 0) {
                o.put("declaringType", fullTypeName(m, ownerRow))
                o.put("declaringTypeToken", 0x02000000 or ownerRow)
            }
            // 方法在文件里的偏移（RVA → file offset），方便直接 hexdump IL
            val off = rvaToOffset(m.pe, rva)
            o.put("fileOffset", if (off >= 0) off else JSONObject.NULL)
            arr.put(o)
        }
        return JSONObject().put("methods", arr).put("count", arr.length()).put("engine", "kotlin")
    }

    fun listStrings(data: ByteArray, maxCount: Int): JSONObject {
        val m = open(data) ?: return JSONObject().put("error", "不是 .NET 程序集（找不到 BSJB metadata）")
        val arr = JSONArray()
        val limit = maxCount.coerceIn(1, 10000)
        var off = 0
        var index = 0
        val cap = maxOf(0, m.usSize)
        while (off < cap && index < limit) {
            if (data.size <= m.usBase + off) break
            val b0 = data[m.usBase + off].toInt() and 0xff
            if (b0 == 0) { off += 1; continue }        // 空串/对齐占位
            val start = off
            val s = m.userStringAt(off)
            val o = JSONObject()
            o.put("offset", String.format("0x%x", start))
            o.put("value", s)
            o.put("length", s.length)
            arr.put(o)
            index++
            // 跳过这条（长度前缀 + 内容 + 标志字节）
            var len: Int
            var adv = 1
            when {
                b0 and 0x80 == 0 -> len = b0
                b0 and 0xC0 == 0x80 -> { len = ((b0 and 0x3F) shl 8) or (u8(data, m.usBase + off + 1)); adv = 2 }
                else -> {
                    len = ((b0 and 0x1F) shl 24) or (u8(data, m.usBase + off + 1) shl 16) or
                        (u8(data, m.usBase + off + 2) shl 8) or u8(data, m.usBase + off + 3)
                    adv = 4
                }
            }
            if (len < 0) break
            off += adv + len
        }
        return JSONObject().put("strings", arr).put("count", arr.length()).put("engine", "kotlin")
    }

    /** 读出方法的 IL 体（tiny / fat header 两种）。 */
    internal class IlBody(val code: ByteArray, val maxStack: Int, val localVarSigTok: Int, val initLocals: Boolean, val fileOffset: Int)

    internal fun ilBody(data: ByteArray, m: Meta, methodRow: Int): IlBody? {
        val rva = m.read(0x06, methodRow, "RVA")
        if (rva == 0L) return null                              // abstract / P/Invoke
        val off = rvaToOffset(m.pe, rva)
        if (off < 0 || off >= data.size) return null
        val b0 = u8(data, off)
        if (b0 and 0x03 == 0x02) {                               // tiny
            val size = b0 ushr 2
            if (size <= 0 || off + 1 + size > data.size) return null
            return IlBody(data.copyOfRange(off + 1, off + 1 + size), 8, 0, false, off)
        }
        if (b0 and 0x03 == 0x03) {                               // fat
            if (off + 12 > data.size) return null
            val flags = u16(data, off)
            val hdrSize = (flags ushr 12) * 4
            if (hdrSize < 12) return null
            val maxStack = u16(data, off + 2)
            val codeSize = u32(data, off + 4).toInt()
            val localSig = u32(data, off + 8).toInt()
            if (codeSize <= 0 || off + hdrSize + codeSize > data.size) return null
            return IlBody(
                data.copyOfRange(off + hdrSize, off + hdrSize + codeSize),
                maxStack, localSig, (flags and 0x10) != 0, off,
            )
        }
        return null
    }

    fun dumpIl(data: ByteArray, methodToken: Int): JSONObject {
        val m = open(data) ?: return JSONObject().put("error", "不是 .NET 程序集（找不到 BSJB metadata）")
        val row = methodToken and 0x00FFFFFF
        if (methodToken ushr 24 != 0x06 || row <= 0 || row > m.rowCount(0x06)) {
            return JSONObject().put("error", "方法令牌无效：0x${methodToken.toString(16)}")
        }
        val name = m.stringAt(m.read(0x06, row, "Name"))
        val body = ilBody(data, m, row)
            ?: return JSONObject().put("method", name).put("error", "该方法没有 IL 体（abstract 或 P/Invoke）")
        val hex = StringBuilder(body.code.size * 3)
        for (i in body.code.indices) {
            if (i > 0) hex.append(' ')
            val v = body.code[i].toInt() and 0xff
            hex.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0F])
        }
        val insns = IlDecoder.decode(body.code, 0)
        return JSONObject()
            .put("method", name)
            .put("methodToken", methodToken)
            .put("maxStack", body.maxStack)
            .put("initLocals", body.initLocals)
            .put("localVarSigTok", String.format("0x%08x", body.localVarSigTok))
            .put("codeSize", body.code.size)
            .put("fileOffset", body.fileOffset)
            .put("ilHex", hex.toString())
            .put("instructions", insns)
            .put("engine", "kotlin")
    }

    fun disasm(data: ByteArray, methodToken: Int, pseudo: Boolean): JSONObject {
        val dumped = dumpIl(data, methodToken)
        if (dumped.has("error")) return dumped
        val insns = dumped.optJSONArray("instructions") ?: JSONArray()
        val sb = StringBuilder()
        sb.append("// ").append(dumped.optString("method")).append("  (IL, ")
            .append(dumped.optInt("codeSize")).append(" bytes, maxStack ")
            .append(dumped.optInt("maxStack")).append(")\n")
        for (i in 0 until insns.length()) {
            val o = insns.optJSONObject(i) ?: continue
            sb.append(o.optString("offsetLabel")).append("  ")
                .append(o.optString("opcode").padEnd(14))
                .append(o.optString("operand")).append('\n')
        }
        return JSONObject()
            .put("method", dumped.optString("method"))
            .put("methodToken", methodToken)
            .put("pseudo", pseudo)
            .put("text", sb.toString())
            .put("instructionCount", insns.length())
            .put("engine", "kotlin")
    }
}
