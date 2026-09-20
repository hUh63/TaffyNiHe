package com.soreverse.mcp.engine

import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.nativecore.NativeEngine
import org.json.JSONArray
import org.json.JSONObject


internal fun EngineRuntime.editHex(workspaceId: String, editSessionId: String, locator: String, edits: JSONArray, dryRun: Boolean = false): JSONObject = guarded {
        val session = workspaces[workspaceId]?.edits?.get(editSessionId) ?: return@guarded err("EDIT_SESSION_NOT_FOUND", "Edit session not found")
        // 安全修复: 加锁防止并发请求交叉修改同一编辑会话。
        synchronized(session.lock) {
        val settings = SettingsStore(context)
        val strict = settings.editStrictValidation
        val maxPatch = settings.maxPatchBytes
        val elf = lief.parse(session.data)
        val sec = ElfSectionResolver.resolve(elf, locator) ?: return@guarded err("SECTION_NOT_FOUND", "Section '${LocatorParser.target(locator, "so_section")}' not found. Call analyze_elf (view=list, subView=sections) to see available sections. If names are duplicated, pass the full locator returned by analyze_elf.", "locator", locator, "availableSections" to elf.sections.mapIndexed { index, section -> EngineJson.sectionKey(section, index) })
        val sectionName = sec.name
        val previews = JSONArray()
        val pending = mutableListOf<Pair<Int, ByteArray>>()
        for (i in 0 until edits.length()) {
            val edit = edits.getJSONObject(i)
            val aliases = listOf("newHex", "hex", "bytes", "data", "rawHex")
                .mapNotNull { key -> edit.optString(key).trim().takeIf { it.isNotBlank() }?.let { key to it } }
                .toMutableList()
            val rawValue = edit.opt("rawValue")
            when (rawValue) {
                is String -> rawValue.trim().takeIf { it.isNotBlank() }?.let { aliases += "rawValue" to it }
                is JSONArray -> aliases += "rawValue" to (0 until rawValue.length()).joinToString("") { index -> "%02x".format(rawValue.optInt(index).coerceIn(0, 255)) }
            }
            val normalizedValues = aliases.map { it.second.replace(Regex("[\\s,]"), "").lowercase() }.distinct()
            if (normalizedValues.size > 1) return@guarded err("CONFLICTING_ARGUMENTS", "Hex aliases contain different values at edit index $i", "edits[$i]", JSONObject(aliases.toMap()))
            val rawHex = aliases.firstOrNull()?.second.orEmpty()
            if (rawHex.isBlank()) {
                return@guarded err("INVALID_ARGUMENT", "Missing newHex (aliases: hex/bytes/data/rawHex/rawValue) for hex edit at index $i", "edits[$i].newHex", null)
            }
            val cleaned = rawHex.replace(" ", "").replace("\t", "").replace("\n", "").replace(",", "")
            if (cleaned.isEmpty() || cleaned.length % 2 != 0 || !cleaned.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                return@guarded err("INVALID_HEX", "newHex must be even-length hex digits, got: $rawHex", "edits[$i].newHex", rawHex)
            }
            val patch = cleaned.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            if (patch.isEmpty()) {
                return@guarded err("INVALID_ARGUMENT", "Decoded patch is empty for hex edit at index $i", "edits[$i].newHex", rawHex)
            }
            if (patch.size > maxPatch) {
                return@guarded err("PATCH_TOO_LARGE", "Patch size ${patch.size} exceeds maxPatchBytes $maxPatch", "edits[$i].newHex", patch.size)
            }
            val relOff = edit.optInt("byteOffset", edit.optInt("offset", Int.MIN_VALUE))
            if (relOff == Int.MIN_VALUE) {
                return@guarded err("INVALID_ARGUMENT", "Missing byteOffset (alias: offset) for hex edit at index $i", "edits[$i].byteOffset", null)
            }
            if (strict && relOff < 0) {
                return@guarded err("OFFSET_OUT_OF_RANGE", "byteOffset must be >= 0, got $relOff", "edits[$i].byteOffset", relOff)
            }
            val off = sec.offset.toInt() + relOff
            if (off < 0 || off + patch.size > session.data.size) {
                return@guarded err("OFFSET_OUT_OF_RANGE", "Hex edit range [${hex(off.toLong())}, +${patch.size}) exceeds file bytes (${session.data.size})", "edits[$i].byteOffset", relOff)
            }
            if (off < sec.offset.toInt() || off + patch.size > sec.offset.toInt() + sec.size.toInt()) {
                return@guarded err("OFFSET_OUT_OF_RANGE", "Hex edit range falls outside section '$sectionName'", "edits[$i].byteOffset", relOff)
            }
            val old = session.data.copyOfRange(off, off + patch.size)
            val preview = JSONObject()
                .put("index", i)
                .put("fileOffset", hex(off.toLong()))
                .put("sectionOffset", hex(relOff.toLong()))
                .put("oldHex", PatchByteUtils.hexBytes(old))
                .put("newHex", PatchByteUtils.hexBytes(patch))
                .put("length", patch.size)
            if (dryRun) {
                previews.put(preview)
            }
            pending += off to patch
        }
        if (dryRun) {
            return@guarded ok(JSONObject()
                .put("dryRun", true)
                .put("preview", previews)
                .put("previewCount", previews.length())
                .put("targetVersion", sha256(session.data)))
        }
        val nextData = session.data.copyOf()
        val nextPatches = pending.map { (off, patch) ->
            val old = nextData.copyOfRange(off, off + patch.size)
            val record = PatchRecord(System.currentTimeMillis(), "hex", locator, off, PatchByteUtils.hexBytes(old), PatchByteUtils.hexBytes(patch))
            System.arraycopy(patch, 0, nextData, off, patch.size)
            record
        }
        if (nextPatches.isNotEmpty()) maybeAutoSnapshot(session, "hex", settings)
        System.arraycopy(nextData, 0, session.data, 0, session.data.size)
        if (nextPatches.isNotEmpty()) session.undone.clear()
        session.patches += nextPatches
        if (nextPatches.isNotEmpty()) {
            session.revision++
            pageStore.clear()
            searchCache.clear()
        }
        val res = JSONObject().put("newTargetVersion", sha256(session.data)).put("editCount", session.revision).put("patchCount", session.patches.size).put("applied", pending.size)
        maybeAutoPersist(workspaceId, session, settings)?.let { res.put("autoPersist", it) }
        ok(res)
        }
    }

internal fun EngineRuntime.editHexVa(workspaceId: String, editSessionId: String, va: Long, patch: ByteArray, dryRun: Boolean = false): JSONObject = guarded {
        val session = workspaces[workspaceId]?.edits?.get(editSessionId) ?: return@guarded err("EDIT_SESSION_NOT_FOUND", "Edit session not found")
        // 安全修复: 加锁防止并发请求交叉修改同一编辑会话。
        synchronized(session.lock) {
        if (patch.isEmpty()) return@guarded err("INVALID_ARGUMENT", "patchHex decoded to empty bytes", "patchHex", "")
        val settings = SettingsStore(context)
        if (patch.size > settings.maxPatchBytes) return@guarded err("PATCH_TOO_LARGE", "Patch size ${patch.size} exceeds maxPatchBytes ${settings.maxPatchBytes}", "patchHex", patch.size)
        val elf = lief.parse(session.data)
        val off = vaToOffset(elf, va)?.toInt() ?: return@guarded err("OFFSET_OUT_OF_RANGE", "Address ${hex(va)} cannot be mapped to a file offset", "va", hex(va))
        if (off < 0 || off + patch.size > session.data.size) return@guarded err("OFFSET_OUT_OF_RANGE", "Patch range [${hex(off.toLong())}, +${patch.size}) exceeds file bytes (${session.data.size})", "va", hex(va))
        val old = session.data.copyOfRange(off, off + patch.size)
        val section = sectionForOffset(elf, off.toLong())
        val preview = JSONObject()
            .put("fileOffset", hex(off.toLong()))
            .put("virtualAddress", hex(va))
            .put("section", section?.name ?: JSONObject.NULL)
            .put("sectionOffset", section?.let { hex(off.toLong() - it.offset) } ?: JSONObject.NULL)
            .put("oldHex", PatchByteUtils.hexBytes(old))
            .put("newHex", PatchByteUtils.hexBytes(patch))
            .put("length", patch.size)
        if (dryRun) {
            return@guarded ok(JSONObject()
                .put("dryRun", true)
                .put("preview", JSONArray().put(preview))
                .put("previewCount", 1)
                .put("targetVersion", sha256(session.data)))
        }
        maybeAutoSnapshot(session, "hex-va", settings)
        session.undone.clear()
        session.patches += PatchRecord(System.currentTimeMillis(), "hex-va", "va:${hex(va)}", off, PatchByteUtils.hexBytes(old), PatchByteUtils.hexBytes(patch))
        System.arraycopy(patch, 0, session.data, off, patch.size)
        session.revision++
        pageStore.clear()
        searchCache.clear()
        val res = JSONObject()
            .put("workspaceId", workspaceId)
            .put("editSessionId", editSessionId)
            .put("newTargetVersion", sha256(session.data))
            .put("editCount", session.revision)
            .put("patchCount", session.patches.size)
            .put("applied", 1)
            .put("patch", preview)
        maybeAutoPersist(workspaceId, session, settings)?.let { res.put("autoPersist", it) }
        ok(res)
        }
    }

internal fun EngineRuntime.editAsm(workspaceId: String, editSessionId: String, locator: String, edits: JSONArray, dryRun: Boolean = false): JSONObject = guarded {
        val session = workspaces[workspaceId]?.edits?.get(editSessionId) ?: return@guarded err("EDIT_SESSION_NOT_FOUND", "Edit session not found")
        // 安全修复: 加锁防止并发请求交叉修改同一编辑会话。
        synchronized(session.lock) {
        val settings = SettingsStore(context)
        val maxPatch = settings.maxPatchBytes
        val elf = lief.parse(session.data)
        val name = LocatorParser.target(locator, "so_function")
        val sym = (elf.symbols + elf.dynSymbols).firstOrNull { it.name == name }
        val startVa = resolveCodeAddress(session.data, elf, locator) ?: return@guarded err("FUNCTION_NOT_FOUND", "Function or address '$name' could not be resolved", "locator", locator, "acceptedForms" to acceptedLocatorForms())
        val base = vaToOffset(elf, startVa)?.toInt() ?: return@guarded err("OFFSET_OUT_OF_RANGE", "Function address ${hex(startVa)} cannot be mapped", "locator", locator)
        val thumb = elf.architecture == "arm32" && ((sym?.value ?: startVa) and 1L) == 1L
        val functionSize = rizinFunctionSize(session.data, elf, startVa) ?: sym?.let { functionByteSize(elf, it, base, session.data.size) } ?: functionByteSizeFromAddress(elf, startVa, base, session.data.size)
        val assembledNop = runCatching { NativeEngine.active().assemble("nop", elf.architecture, startVa, thumb) }
            .getOrElse { PatchByteUtils.architectureNop(elf.architecture, thumb) }
        val nop = if (assembledNop.isEmpty()) PatchByteUtils.architectureNop(elf.architecture, thumb) else assembledNop
        val previews = JSONArray()
        val nextData = session.data.copyOf()
        val nextPatches = mutableListOf<PatchRecord>()
        for (i in 0 until edits.length()) {
            val edit = edits.getJSONObject(i)
            val mode = edit.optString("mode", "replace_instructions")
            if (mode in setOf("insert_before", "insert_after", "prepend_function", "append_function", "write_function") && !edit.has("byteLength") && !edit.has("instructionCount")) {
                return@guarded err("UNSUPPORTED_OPERATION", "Insertion-style asm edits require an explicit byteLength or instructionCount because Android native build does not relocate downstream function bytes")
            }
            var range = asmEditRange(edit, startVa, thumb, elf.architecture, nop.size, functionSize)
            val patch = if (mode == "nop_out" || mode == "delete_instructions") {
                PatchByteUtils.repeatBytes(nop, range.second)
            } else {
                val asm = edit.optString("writeAsm", edit.optString("newAsm", edit.optString("asm", edit.optString("assembly", "")))).trim()
                if (asm.isBlank()) return@guarded err("ASM_SYNTAX_ERROR", "Missing writeAsm/newAsm/asm (alias: assembly) for asm edit at index $i")
                val encoded = runCatching { NativeEngine.active().assemble(asm, elf.architecture, startVa + range.first, thumb) }
                    .getOrElse { return@guarded err("ASM_SYNTAX_ERROR", it.message ?: "Assembler failed to encode: $asm") }
                if (encoded.isEmpty()) return@guarded err("ASM_SYNTAX_ERROR", "Assembler produced no bytes for: $asm")
                if (encoded.size > range.second && !edit.has("instructionCount") && !edit.has("byteLength")) {
                    val step = if (thumb) 2 else if (elf.architecture in setOf("arm32", "arm64")) 4 else nop.size.coerceAtLeast(1)
                    val needed = ((encoded.size + step - 1) / step) * step
                    if (range.first + needed <= functionSize) range = range.first to needed
                }
                if (encoded.size > range.second) return@guarded err("SIZE_MISMATCH", "Assembled code (${encoded.size}B) is larger than selected instruction range (${range.second}B). Set instructionCount/byteLength to cover multiple instructions, or split into single-instruction edits.", "edits[$i]", JSONObject().put("assembled", encoded.size).put("range", range.second))
                encoded + PatchByteUtils.repeatBytes(nop, range.second - encoded.size)
            }
            if (patch.size > maxPatch) {
                return@guarded err("PATCH_TOO_LARGE", "Patch size ${patch.size} exceeds maxPatchBytes $maxPatch", "edits[$i]", patch.size)
            }
            val writeOffset = base + range.first
            val old = nextData.copyOfRange(writeOffset, writeOffset + patch.size)
            val asmText = edit.optString("writeAsm", edit.optString("newAsm", edit.optString("asm", edit.optString("assembly", mode))))
            val preview = JSONObject()
                .put("index", i)
                .put("fileOffset", hex(writeOffset.toLong()))
                .put("virtualAddress", hex(startVa + range.first))
                .put("oldHex", PatchByteUtils.hexBytes(old))
                .put("newHex", PatchByteUtils.hexBytes(patch))
                .put("asm", asmText)
                .put("mode", mode)
                .put("length", patch.size)
            if (dryRun) {
                previews.put(preview)
                continue
            }
            nextPatches += PatchRecord(System.currentTimeMillis(), "asm", locator, writeOffset, PatchByteUtils.hexBytes(old), PatchByteUtils.hexBytes(patch), asmText)
            System.arraycopy(patch, 0, nextData, writeOffset, patch.size)
        }
        if (dryRun) {
            return@guarded ok(JSONObject()
                .put("dryRun", true)
                .put("preview", previews)
                .put("previewCount", previews.length())
                .put("targetVersion", sha256(session.data)))
        }
        if (nextPatches.isNotEmpty()) {
            maybeAutoSnapshot(session, "asm", settings)
            session.undone.clear()
            System.arraycopy(nextData, 0, session.data, 0, session.data.size)
            session.patches += nextPatches
            session.revision++
            pageStore.clear()
            searchCache.clear()
        }
        val resAsm = JSONObject().put("newTargetVersion", sha256(session.data)).put("editCount", session.revision).put("patchCount", session.patches.size).put("applied", nextPatches.size)
        if (nextPatches.isNotEmpty()) maybeAutoPersist(workspaceId, session, settings)?.let { resAsm.put("autoPersist", it) }
        ok(resAsm)
        }
    }

internal fun EngineRuntime.editSymbol(workspaceId: String, editSessionId: String, locator: String, edits: JSONArray, dryRun: Boolean = false): JSONObject = guarded {
        val session = workspaces[workspaceId]?.edits?.get(editSessionId) ?: return@guarded err("EDIT_SESSION_NOT_FOUND", "Edit session not found")
        // 安全修复: 加锁防止并发请求交叉修改同一编辑会话。
        synchronized(session.lock) {
        val settings = SettingsStore(context)
        val name = LocatorParser.target(locator, "so_symbol")
        val previews = JSONArray()
        val nextData = session.data.copyOf()
        val nextPatches = mutableListOf<PatchRecord>()
        var planDetail: JSONObject? = null
        var legacyAny = false
        for (i in 0 until edits.length()) {
            val edit = edits.getJSONObject(i)
            val op = edit.optString("op", "rename")
            if (op != "rename") {
                return@guarded err("UNSUPPORTED_OPERATION", "taffy_edit_symbol(edits[].op) 仅支持 rename；新增/移除符号请改用 op=add / op=remove 快捷方式（LIEF 实现）。", "edits[$i].op", op)
            }
            val newName = edit.optString("newName", name)
            if (newName.isBlank()) return@guarded err("INVALID_ARGUMENT", "newName 不能为空", "edits[$i].newName", newName)
            if (newName == name) return@guarded err("INVALID_ARGUMENT", "newName 与当前符号名相同，无需改名", "edits[$i].newName", newName)
            if (newName.contains('\u0000')) return@guarded err("INVALID_ARGUMENT", "newName 不能包含 NUL 字符", "edits[$i].newName", newName)
            // 变长改名：复用已有串 / 原地等长覆盖 / 原地追加到字符串表尾部空闲区（绝不改变文件大小）
            val plan = planSymbolRename(nextData, name, newName)
            var legacy = false
            val effective: List<RenamePatch>
            if (plan.ok) {
                effective = plan.patches
                planDetail = plan.detail
            } else if (plan.errorCode == "SYMBOL_NOT_FOUND" && newName.toByteArray().size <= name.toByteArray().size) {
                // 退化路径（保持旧行为）：符号表里找不到该名字时，退回「等长/更短」的全文件字节搜索。
                val oldBytes = name.toByteArray()
                val newBytes = newName.toByteArray()
                val pos = indexOf(nextData, oldBytes)
                if (pos < 0) return@guarded plan.error!!
                legacy = true
                legacyAny = true
                effective = listOf(RenamePatch(pos, oldBytes, ByteArray(oldBytes.size) { if (it < newBytes.size) newBytes[it] else 0 }, "legacy-byte-search"))
            } else {
                return@guarded plan.error!!
            }
            for ((pi, patch) in effective.withIndex()) {
                if (patch.fileOffset < 0 || patch.newBytes.size != patch.oldBytes.size || patch.fileOffset + patch.newBytes.size > nextData.size) {
                    return@guarded err("OFFSET_OUT_OF_RANGE", "符号改名补丁越界或长度不一致：offset=${hex(patch.fileOffset.toLong())} old=${patch.oldBytes.size} new=${patch.newBytes.size} file=${nextData.size}", "locator", locator)
                }
                val preview = JSONObject()
                    .put("index", i)
                    .put("patchIndex", pi)
                    .put("fileOffset", hex(patch.fileOffset.toLong()))
                    .put("oldHex", PatchByteUtils.hexBytes(patch.oldBytes))
                    .put("newHex", PatchByteUtils.hexBytes(patch.newBytes))
                    .put("asm", "rename $name -> $newName (${patch.note})")
                    .put("note", patch.note)
                    .put("length", patch.newBytes.size)
                if (dryRun) {
                    previews.put(preview)
                    continue
                }
                nextPatches += PatchRecord(System.currentTimeMillis(), "symbol", locator, patch.fileOffset, PatchByteUtils.hexBytes(patch.oldBytes), PatchByteUtils.hexBytes(patch.newBytes), "rename $name -> $newName (${patch.note})")
                System.arraycopy(patch.newBytes, 0, nextData, patch.fileOffset, patch.newBytes.size)
            }
            if (!dryRun && !legacy) {
                // 写后自检：重新规划必须因找不到旧名而失败，否则说明改名未生效。
                val verify = planSymbolRename(nextData, name, newName)
                if (verify.ok) {
                    return@guarded err("SYMBOL_VERIFY_FAILED", "改名后自检失败：符号 '$name' 仍可被定位，newName '$newName' 未生效", "edits[$i].newName", newName)
                }
            }
        }
        if (dryRun) {
            val planned = JSONObject()
                .put("dryRun", true)
                .put("preview", previews)
                .put("previewCount", previews.length())
                .put("targetVersion", sha256(session.data))
            planDetail?.let { planned.put("plan", it) }
            return@guarded ok(planned)
        }
        if (nextPatches.isNotEmpty()) {
            maybeAutoSnapshot(session, "symbol", settings)
            session.undone.clear()
            System.arraycopy(nextData, 0, session.data, 0, session.data.size)
            session.patches += nextPatches
            session.revision++
            pageStore.clear()
            searchCache.clear()
        }
        val resSym = JSONObject()
            .put("newTargetVersion", sha256(session.data))
            .put("editCount", session.revision)
            .put("patchCount", session.patches.size)
            .put("applied", nextPatches.size)
            .put("legacyByteSearch", legacyAny)
        planDetail?.let { resSym.put("plan", it) }
        if (nextPatches.isNotEmpty()) maybeAutoPersist(workspaceId, session, settings)?.let { resSym.put("autoPersist", it) }
        ok(resSym)
        }
    }

private fun EngineRuntime.asmEditRange(edit: JSONObject, startVa: Long, thumb: Boolean, architecture: String, fallbackInsnSize: Int, maxBytes: Int): Pair<Int, Int> {
        val step = if (architecture == "arm32" && thumb) 2 else fallbackInsnSize.coerceAtLeast(1)
        if (edit.has("address") && edit.optString("address").isNotBlank()) {
            val addrStr = edit.optString("address").trim().removePrefix("0x").removePrefix("0X")
            val addr = addrStr.toLongOrNull(16)
                ?: throw IllegalArgumentException("address must be a hex VA like 0x978, got ${edit.optString("address")}")
            val off = (addr - startVa).toInt()
            val length = when {
                edit.optInt("byteLength", 0) > 0 -> edit.optInt("byteLength", 0)
                edit.optInt("length", 0) > 0 -> edit.optInt("length", 0)
                edit.has("instructionCount") -> edit.optInt("instructionCount", 1).coerceAtLeast(1) * step
                edit.has("count") -> edit.optInt("count", 1).coerceAtLeast(1) * step
                else -> step
            }
            require(off >= 0 && length > 0 && off + length <= maxBytes) { "Assembly edit address range [${hex(off.toLong())}, +$length) exceeds function bytes ($maxBytes)" }
            return off to length
        }
        if (edit.has("instructionIndex")) {
            val idx = edit.optInt("instructionIndex", 0)
            val count = edit.optInt("instructionCount", edit.optInt("count", 1)).coerceAtLeast(1)
            val off = idx * step
            val length = when {
                edit.optInt("byteLength", 0) > 0 -> edit.optInt("byteLength", 0)
                edit.optInt("length", 0) > 0 -> edit.optInt("length", 0)
                else -> count * step
            }
            require(idx >= 0 && length > 0 && off + length <= maxBytes) { "Assembly edit instruction range exceeds function bytes" }
            return off to length
        }
        val explicitByteOffset = when {
            edit.has("byteOffset") && edit.optInt("byteOffset", 0) != 0 -> edit.optInt("byteOffset", 0)
            edit.has("offset") && edit.optInt("offset", 0) != 0 -> edit.optInt("offset", 0)
            edit.has("byteOffset") && !edit.has("instructionIndex") -> edit.optInt("byteOffset", 0)
            edit.has("offset") && !edit.has("instructionIndex") -> edit.optInt("offset", 0)
            else -> 0
        }
        val length = edit.optInt("byteLength", edit.optInt("length", 0)).takeIf { it > 0 } ?: edit.optInt("instructionCount", edit.optInt("count", 1)).coerceAtLeast(1) * step
        require(explicitByteOffset >= 0 && length > 0 && explicitByteOffset + length <= maxBytes) { "Assembly edit byte range exceeds function bytes" }
        return explicitByteOffset to length
    }
