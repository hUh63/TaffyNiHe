// 塔菲逆核: ELF 变长符号改名规划器（对标 Exbin 的 findNameInStrtab + 节区尺寸回写）。
//
// 目标：把符号改成「更长」的新名字。核心约束——本引擎的编辑会话（EditSession.data）是
// 固定大小的字节数组（PatchRecord/快照/回滚/hexdump/反汇编全部依赖偏移稳定，
// System.arraycopy(session.data) 贯穿全流程），因此**不允许改变文件大小**。
// 于是可以在不重排文件的前提下达成变长改名的三条路径：
//   A) 复用：新名已作为完整 C 串（或某个已有字符串的后缀）存在于同一字符串表 → 只改 st_name 偏移；
//   B) 等长/更短：原地覆盖原串并补 NUL；
//   C) 追加：字符串表之后紧跟的空闲（全零）填充区足够放下新串 → 原地追加 + 同步 sh_size/st_name
//      （若字符串表带 SHF_ALLOC，即 .dynstr，则同时要求追加区落在某个 PT_LOAD 的文件范围内，
//        并回写 .dynamic 中的 DT_STRSZ）；
//   都不满足 → 明确返回 UNSUPPORTED_LAYOUT 并说明原因与替代方案，绝不写假实现。
package com.soreverse.mcp.engine

import com.soreverse.mcp.core.err
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

private const val SHT_SYMTAB = 2L
private const val SHT_STRTAB = 3L
private const val SHT_DYNAMIC = 6L
private const val SHT_NOBITS = 8L
private const val SHT_DYNSYM = 11L
private const val SHF_ALLOC = 0x2L
private const val PT_LOAD = 1L
private const val DT_STRSZ = 10L

/** 一条改名补丁：offset 处把 oldBytes 换成 newBytes（长度必须一致，保证文件大小不变）。 */
internal class RenamePatch(
    val fileOffset: Int,
    val oldBytes: ByteArray,
    val newBytes: ByteArray,
    val note: String,
)

/** 改名规划结果：patches 为空且 error 为空表示「无需改动」。 */
internal class SymbolRenamePlan(
    val patches: List<RenamePatch>,
    val detail: JSONObject,
    val error: JSONObject? = null,
) {
    val ok: Boolean get() = error == null
    val errorCode: String get() = error?.optJSONObject("error")?.optString("code").orEmpty()
}

/** 符号表中一条命中记录：st_name 字段的文件偏移 + 当前 st_name 值。 */
internal class SymEntryRef(val fieldOffset: Int, val stName: Long)

internal class RawElfSection(
    val index: Int,
    val headerOffset: Int,
    val type: Long,
    val flags: Long,
    val offset: Int,
    val size: Int,
    val link: Int,
    val entsize: Long,
) {
    val alloc: Boolean get() = (flags and SHF_ALLOC) != 0L
}

internal class RawElfSegment(val type: Long, val offset: Int, val fileSize: Int)

/**
 * 只读的 ELF 视图：直接读原始字节，用来定位节区表、字符串表、符号表、程序头与 .dynamic。
 * 不依赖 LIEF/rizin，保证改名规划在任何构建环境下都可执行。
 */
internal class RawElfView private constructor(val data: ByteArray) {
    var bits = 64
        private set
    var little = true
        private set
    var sectionHeaderOffset = 0
        private set
    var sectionHeaderEntrySize = 0
        private set
    var sectionCount = 0
        private set
    var sections: List<RawElfSection> = emptyList()
        private set
    var segments: List<RawElfSegment> = emptyList()
        private set

    fun u8(offset: Int): Int = if (offset in data.indices) data[offset].toInt() and 0xff else 0

    fun u16(offset: Int): Int = if (little) {
        u8(offset) or (u8(offset + 1) shl 8)
    } else {
        (u8(offset) shl 8) or u8(offset + 1)
    }

    fun u32(offset: Int): Long = if (little) {
        u8(offset).toLong() or (u8(offset + 1).toLong() shl 8) or (u8(offset + 2).toLong() shl 16) or (u8(offset + 3).toLong() shl 24)
    } else {
        (u8(offset).toLong() shl 24) or (u8(offset + 1).toLong() shl 16) or (u8(offset + 2).toLong() shl 8) or u8(offset + 3).toLong()
    }

    fun u64(offset: Int): Long = if (little) u32(offset) or (u32(offset + 4) shl 32) else (u32(offset) shl 32) or u32(offset + 4)

    fun cstr(offset: Int): String {
        if (offset < 0 || offset >= data.size) return ""
        var end = offset
        while (end < data.size && data[end].toInt() != 0) end++
        return String(data, offset, end - offset, StandardCharsets.UTF_8)
    }

    fun bytesAt(offset: Int, length: Int): ByteArray {
        val out = ByteArray(length)
        for (k in 0 until length) out[k] = u8(offset + k).toByte()
        return out
    }

    fun zeroed(offset: Int, length: Int): Boolean {
        if (offset < 0 || length < 0 || offset + length > data.size) return false
        for (i in offset until offset + length) if (data[i].toInt() != 0) return false
        return true
    }

    fun encode(value: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        for (k in 0 until length) {
            val shift = 8 * (if (little) k else length - 1 - k)
            out[k] = ((value shr shift) and 0xff).toByte()
        }
        return out
    }

    fun shOffsetField(sec: RawElfSection): Int = sec.headerOffset + if (bits == 64) 24 else 16
    fun shSizeField(sec: RawElfSection): Int = sec.headerOffset + if (bits == 64) 32 else 20
    fun wordSize(): Int = if (bits == 64) 8 else 4

    companion object {
        fun parse(data: ByteArray): RawElfView? {
            if (data.size < 64) return null
            if (data[0].toInt() != 0x7f || data[1].toInt() != 'E'.code || data[2].toInt() != 'L'.code || data[3].toInt() != 'F'.code) return null
            val view = RawElfView(data)
            view.bits = if (data[4].toInt() == 2) 64 else 32
            view.little = data[5].toInt() != 2
            val shoff = if (view.bits == 64) view.u64(40) else view.u32(32)
            val shentsize = if (view.bits == 64) view.u16(58) else view.u16(46)
            val shnum = if (view.bits == 64) view.u16(60) else view.u16(48)
            val phoff = if (view.bits == 64) view.u64(32) else view.u32(28)
            val phentsize = if (view.bits == 64) view.u16(54) else view.u16(42)
            val phnum = if (view.bits == 64) view.u16(56) else view.u16(44)
            if (shoff <= 0L || shentsize <= 0 || shnum <= 0 || shoff > Int.MAX_VALUE) return null
            view.sectionHeaderOffset = shoff.toInt()
            view.sectionHeaderEntrySize = shentsize
            view.sectionCount = shnum
            val list = ArrayList<RawElfSection>(shnum)
            for (i in 0 until shnum) {
                val ho = view.sectionHeaderOffset + i * shentsize
                if (ho < 0 || ho + shentsize > data.size) break
                list += RawElfSection(
                    index = i,
                    headerOffset = ho,
                    type = view.u32(ho + 4),
                    flags = if (view.bits == 64) view.u64(ho + 8) else view.u32(ho + 8),
                    offset = if (view.bits == 64) view.u64(ho + 24).toInt() else view.u32(ho + 16).toInt(),
                    size = if (view.bits == 64) view.u64(ho + 32).toInt() else view.u32(ho + 20).toInt(),
                    link = if (view.bits == 64) view.u32(ho + 40).toInt() else view.u32(ho + 24).toInt(),
                    entsize = if (view.bits == 64) view.u64(ho + 56) else view.u32(ho + 36),
                )
            }
            view.sections = list
            if (phoff > 0L && phoff <= Int.MAX_VALUE && phentsize > 0 && phnum > 0) {
                val segs = ArrayList<RawElfSegment>(phnum)
                for (i in 0 until phnum) {
                    val po = phoff.toInt() + i * phentsize
                    if (po < 0 || po + phentsize > data.size) break
                    segs += RawElfSegment(
                        type = view.u32(po),
                        offset = if (view.bits == 64) view.u64(po + 8).toInt() else view.u32(po + 4).toInt(),
                        fileSize = if (view.bits == 64) view.u64(po + 32).toInt() else view.u32(po + 16).toInt(),
                    )
                }
                view.segments = segs
            }
            return view
        }
    }
}

/** 在字符串表内查找可复用的名字：newName 必须是表内某个完整 C 串，或某已有串的后缀。 */
private fun EngineRuntime.findReusableName(view: RawElfView, strtab: RawElfSection, needle: ByteArray): Int {
    if (needle.isEmpty() || strtab.size <= needle.size) return -1
    val table = view.bytesAt(strtab.offset, strtab.size)
    var idx = indexOf(table, needle, 0)
    while (idx >= 0) {
        val terminated = idx + needle.size < table.size && table[idx + needle.size].toInt() == 0
        // st_name = 0 在 ELF 中保留给「无名字」，因此要求偏移 > 0
        if (terminated && idx > 0) return idx
        idx = indexOf(table, needle, idx + 1)
    }
    return -1
}

/** 找到 .dynamic 中 DT_STRSZ 的 d_un 字段偏移（用于 SHF_ALLOC 字符串表扩容后同步运行时尺寸）。 */
private fun findDynamicStrszField(view: RawElfView, dynamic: RawElfSection): Int {
    val word = if (view.bits == 64) 8 else 4
    val entrySize = word * 2
    if (dynamic.size < entrySize) return -1
    val count = dynamic.size / entrySize
    for (i in 0 until count) {
        val entry = dynamic.offset + i * entrySize
        val tag = if (word == 8) view.u64(entry) else view.u32(entry)
        if (tag == 0L) break
        if (tag == DT_STRSZ) return entry + word
    }
    return -1
}

private fun sectionNameOf(view: RawElfView, index: Int): String {
    val shstrndx = if (view.bits == 64) view.u16(62) else view.u16(50)
    val shstr = view.sections.getOrNull(shstrndx) ?: return "#$index"
    val sec = view.sections.getOrNull(index) ?: return "#$index"
    val nameOffset = view.u32(sec.headerOffset).toInt()
    val name = view.cstr(shstr.offset + nameOffset)
    return name.ifBlank { "#$index" }
}

/**
 * 规划一次符号改名（纯计算，不改字节）。返回 patches 供调用方走既有 PatchRecord 机制应用。
 */
internal fun EngineRuntime.planSymbolRename(data: ByteArray, oldName: String, newName: String): SymbolRenamePlan {
    val emptyDetail = JSONObject()
    val oldBytes = oldName.toByteArray(StandardCharsets.UTF_8)
    val newBytes = newName.toByteArray(StandardCharsets.UTF_8)
    if (oldBytes.isEmpty() || newBytes.isEmpty()) {
        return SymbolRenamePlan(emptyList(), emptyDetail, err("INVALID_ARGUMENT", "oldName/newName must not be empty"))
    }
    val view = RawElfView.parse(data)
        ?: return SymbolRenamePlan(
            emptyList(),
            emptyDetail,
            err("NOT_ELF_INPUT", "编辑会话字节不是可解析的 ELF（缺少节区表），无法做符号表级改名"),
        )
    // 1) 在所有符号表（.symtab / .dynsym）中定位 oldName，并按目标字符串表分组
    val groups = LinkedHashMap<Int, MutableList<SymEntryRef>>()
    val groupKind = HashMap<Int, Boolean>()
    for (sec in view.sections) {
        if (sec.type != SHT_SYMTAB && sec.type != SHT_DYNSYM) continue
        if (sec.index == 0 || sec.size <= 0) continue
        val strtab = view.sections.getOrNull(sec.link) ?: continue
        if (strtab.type != SHT_STRTAB || strtab.size <= 0) continue
        val entrySize = if (sec.entsize > 0) sec.entsize.toInt() else if (view.bits == 64) 24 else 16
        if (entrySize < 4) continue
        val count = sec.size / entrySize
        for (i in 0 until count) {
            val entryOffset = sec.offset + i * entrySize
            if (entryOffset < 0 || entryOffset + 4 > data.size) break
            val stName = view.u32(entryOffset)
            if (view.cstr(strtab.offset + stName.toInt()) == oldName) {
                groups.getOrPut(strtab.index) { mutableListOf() } += SymEntryRef(entryOffset, stName)
                groupKind[strtab.index] = sec.type == SHT_DYNSYM
            }
        }
    }
    if (groups.isEmpty()) {
        return SymbolRenamePlan(
            emptyList(),
            emptyDetail,
            err(
                "SYMBOL_NOT_FOUND",
                "符号 '$oldName' 未出现在任何符号表（.symtab/.dynsym）中；该名字可能已被 strip，或它只是普通字符串。",
                "locator",
                oldName,
                "hint" to "可用 taffy_analyze_elf(view=symbols) 确认符号名；纯字符串替换请用 taffy_edit_hex。",
            ),
        )
    }
    val patches = mutableListOf<RenamePatch>()
    val groupsJson = JSONArray()
    var reusedAny = false
    var extendedAny = false
    for ((strtabIndex, refs) in groups) {
        val strtab = view.sections[strtabIndex]
        val strtabName = sectionNameOf(view, strtabIndex)
        val tail = strtab.offset + strtab.size
        if (strtab.offset <= 0 || strtab.size <= 0 || tail > data.size) {
            return SymbolRenamePlan(
                emptyList(),
                emptyDetail,
                err("UNSUPPORTED_LAYOUT", "字符串表 $strtabName（index=$strtabIndex）范围异常（offset=${hex(strtab.offset.toLong())} size=${strtab.size}），拒绝改名"),
            )
        }
        val kind = if (groupKind[strtabIndex] == true) "dynstr" else "strtab"

        // A) 复用表内已存在的同名串 / 同名后缀串：只改 st_name，零布局改动
        val reusable = findReusableName(view, strtab, newBytes)
        if (reusable > 0) {
            reusedAny = true
            refs.forEach { ref ->
                patches += RenamePatch(
                    ref.fieldOffset,
                    view.bytesAt(ref.fieldOffset, 4),
                    view.encode(reusable.toLong(), 4),
                    "st_name -> ${hex(reusable.toLong())} (reuse existing string in $strtabName)",
                )
            }
            groupsJson.put(
                JSONObject()
                    .put("strtab", strtabName)
                    .put("strtabIndex", strtabIndex)
                    .put("kind", kind)
                    .put("mode", "reuse")
                    .put("reusedStringOffset", hex(reusable.toLong()))
                    .put("symbolEntryCount", refs.size),
            )
            continue
        }

        // B) 等长或更短：原地覆盖 + NUL 填充（文件大小与节区布局都不变）
        if (newBytes.size <= oldBytes.size) {
            val padded = ByteArray(oldBytes.size) { if (it < newBytes.size) newBytes[it] else 0 }
            val patchedPositions = HashSet<Int>()
            var inPlaceAdded = 0
            refs.forEach { ref ->
                val pos = strtab.offset + ref.stName.toInt()
                if (!patchedPositions.add(pos)) return@forEach
                if (pos < 0 || pos + oldBytes.size > data.size) return@forEach
                patches += RenamePatch(pos, view.bytesAt(pos, oldBytes.size), padded, "in-place rename ($strtabName)")
                inPlaceAdded++
            }
            if (inPlaceAdded > 0) {
                groupsJson.put(
                    JSONObject()
                        .put("strtab", strtabName)
                        .put("strtabIndex", strtabIndex)
                        .put("kind", kind)
                        .put("mode", "in_place")
                        .put("symbolEntryCount", refs.size),
                )
                continue
            }
        }

        // C) 更长：只能在字符串表之后的空闲（全零）填充区里原地追加，绝不改变文件大小
        val need = newBytes.size + 1
        val nextOccupied = minOf(
            view.sections
                .filter { it.index != strtabIndex && it.size > 0 && it.type != SHT_NOBITS && it.offset >= tail }
                .minOfOrNull { it.offset } ?: Int.MAX_VALUE,
            if (view.sectionHeaderOffset >= tail) view.sectionHeaderOffset else Int.MAX_VALUE,
            data.size,
        )
        val window = nextOccupied - tail
        val shTableNote = if (view.sectionHeaderOffset >= tail) "或节区头表" else ""
        if (window < need) {
            return SymbolRenamePlan(
                emptyList(),
                emptyDetail,
                err(
                    "UNSUPPORTED_LAYOUT",
                    "变长改名失败：字符串表 $strtabName（$kind）之后只有 $window 字节空间（需要 $need 字节），其后紧跟其它节区数据$shTableNote。" +
                        "变长追加需要整表重排或扩展文件，而编辑会话按固定文件大小工作（PatchRecord/快照/回滚依赖偏移稳定），故本工具拒绝执行。",
                    "edits[].newName",
                    newName,
                    "windowBytes" to window,
                    "neededBytes" to need,
                    "alternatives" to "① 等长改名（newName 不长于原名）；② 改成该字符串表内已存在的同名/后缀字符串（可复用，零布局改动）；③ 用 taffy_build_so 导出后用外部 strip/llvm-objcopy 重排符号表，再 taffy_so_open 重新打开。",
                ),
            )
        }
        if (!view.zeroed(tail, need)) {
            return SymbolRenamePlan(
                emptyList(),
                emptyDetail,
                err(
                    "UNSUPPORTED_LAYOUT",
                    "变长改名失败：$strtabName 之后的 $need 字节并非全零填充（疑似其它数据/签名块），拒绝覆写。",
                    "edits[].newName",
                    newName,
                ),
            )
        }
        if (strtab.alloc) {
            val mapped = view.segments.any { it.type == PT_LOAD && it.offset <= tail && tail + need <= it.offset + it.fileSize }
            if (!mapped) {
                return SymbolRenamePlan(
                    emptyList(),
                    emptyDetail,
                    err(
                        "UNSUPPORTED_LAYOUT",
                        "变长改名失败：$strtabName 带 SHF_ALLOC（运行时经 DT_STRTAB 寻址），但其后的追加区不在任何 PT_LOAD 映射范围内，加长后运行时读不到该字符串。",
                        "edits[].newName",
                        newName,
                    ),
                )
            }
            val dynamic = view.sections.firstOrNull { it.type == SHT_DYNAMIC && it.size > 0 }
            val strszField = dynamic?.let { findDynamicStrszField(view, it) } ?: -1
            if (strszField < 0) {
                return SymbolRenamePlan(
                    emptyList(),
                    emptyDetail,
                    err(
                        "UNSUPPORTED_LAYOUT",
                        "变长改名失败：$strtabName 带 SHF_ALLOC，但 .dynamic 中未找到 DT_STRSZ，无法同步运行时字符串表尺寸。",
                        "edits[].newName",
                        newName,
                    ),
                )
            }
            val word = view.wordSize()
            patches += RenamePatch(
                strszField,
                view.bytesAt(strszField, word),
                view.encode((strtab.size + need).toLong(), word),
                "DT_STRSZ -> ${strtab.size + need}",
            )
        }
        val payload = ByteArray(need)
        System.arraycopy(newBytes, 0, payload, 0, newBytes.size)
        patches += RenamePatch(tail, view.bytesAt(tail, need), payload, "append string at end of $strtabName")
        patches += RenamePatch(
            view.shSizeField(strtab),
            view.bytesAt(view.shSizeField(strtab), view.wordSize()),
            view.encode((strtab.size + need).toLong(), view.wordSize()),
            "sh_size($strtabName) -> ${strtab.size + need}",
        )
        refs.forEach { ref ->
            patches += RenamePatch(
                ref.fieldOffset,
                view.bytesAt(ref.fieldOffset, 4),
                view.encode(strtab.size.toLong(), 4),
                "st_name -> ${hex(strtab.size.toLong())} (appended string)",
            )
        }
        extendedAny = true
        groupsJson.put(
            JSONObject()
                .put("strtab", strtabName)
                .put("strtabIndex", strtabIndex)
                .put("kind", kind)
                .put("mode", "append_in_place")
                .put("newStringOffset", hex(strtab.size.toLong()))
                .put("newStrtabSize", strtab.size + need)
                .put("windowBytes", window)
                .put("symbolEntryCount", refs.size),
        )
    }
    val detail = JSONObject()
        .put("oldName", oldName)
        .put("newName", newName)
        .put("reusedExistingString", reusedAny)
        .put("appendedInPlace", extendedAny)
        .put("groups", groupsJson)
        .put("patchCount", patches.size)
        .put("fileSizeUnchanged", true)
    return SymbolRenamePlan(patches, detail)
}
