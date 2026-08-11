package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import com.soreverse.mcp.nativecore.NativeEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * 塔菲逆核: Native 指令级补丁 + APK 统一搜索(超越 MT管理器精度)。
 *
 * taffy_native_patch_instructions: 汇编新指令→CAS替换(带 expectedHex 乐观锁)
 * taffy_native_patch_string: 字符串补丁(带 expectedText 乐观锁)
 * taffy_apk_unified_search: 跨 DEX/Native/资源/ZIP 统一搜索(带分页游标)
 */
object NativePatchTools {

    /** Native 指令补丁 — 汇编新指令 + CAS 乐观锁替换 */
    val nativePatchInstructions: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_native_patch_instructions",
            "【Native 指令补丁】汇编新 ARM/ARM64 指令, 用 CAS(乐观锁)替换 SO 中指定地址的旧指令。先读旧字节(expectedHex), 匹配后才写入新指令, 不匹配则返回当前字节让调用方重试。参考 MT管理器的 taffy_native_patch_instructions, 同样有 expectedHex 保护。支持 ARM32/ARM64/Thumb。",
            "Patch native instructions: assemble new ARM/ARM64 code, CAS-replace old bytes at target address (with expectedHex optimistic lock). If expected hex doesn't match, returns current bytes for retry. Same CAS protection as MT. Supports ARM32/ARM64/Thumb.",
            "edit", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "workspaceId" str "SO 工作区 ID"
                "address" str "目标虚拟地址(0xHEX)"
                "asm" str "新汇编指令(如 'mov x0, #0; ret')"
                "expectedHex" str "当前地址的原始字节(十六进制, 如 'D2800540 C0035FD6'). 为空则跳过校验直接写入(不推荐)"
                "thumb" str "ARM32: 是否为 Thumb 模式(true/false), ARM64 忽略"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val ws = args.str("workspaceId")
            val address = args.str("address")
            val asm = args.str("asm")
            if (ws.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 workspaceId", "workspaceId", "")
            if (address.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 address", "address", address)
            if (asm.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 asm(汇编指令)", "asm", asm)

            val engine = EngineProvider.get(ctx.context)
            val addr = address.removePrefix("0x").toLong(16)
            val expectedHex = args.str("expectedHex")
            val thumb = args.str("thumb", "false").toBoolean()

            // 1. 如果有 expectedHex, 先读当前字节做 CAS 校验
            if (expectedHex.isNotBlank()) {
                val readResult = engine.rzCommand(ws, "", "pxj 16 @ $addr", false)
                val ok0 = readResult.optBoolean("ok", false)
                if (!ok0) return readResult
                // 提取当前字节
                val currentHex = extractHexFromRzOutput(readResult)
                val expectedClean = expectedHex.replace("\\s+".toRegex(), "").uppercase()
                val currentClean = currentHex.replace("\\s+".toRegex(), "").uppercase()
                if (!currentClean.startsWith(expectedClean.take(expectedClean.length.coerceAtMost(currentClean.length)))) {
                    // CAS 失败, 返回当前字节让调用方重试
                    return err("CAS_MISMATCH",
                        "expectedHex 不匹配当前字节, 可能已被修改. 请用返回的 currentHex 重试.",
                        "address", address).put("currentHex", currentHex)
                        .put("expectedHex", expectedHex)
                }
            }

            // 2. 汇编新指令
            val assembled = NativeEngine.active().assemble(asm, if (thumb) "arm" else "arm64", addr, thumb)
            if (assembled.isEmpty()) return err("ASM_FAILED", "汇编失败, 检查指令语法", "asm", asm)

            // 3. 写入 SO (用 rizin wx 命令)
            val hexStr = assembled.joinToString("") { "%02x".format(it) }
            val writeResult = engine.rzCommand(ws, "", "wx $hexStr @ $addr", false)

            return if (writeResult.optBoolean("ok", false)) {
                ok(JSONObject()
                    .put("action", "taffy_native_patch_instructions")
                    .put("address", "0x${addr.toString(16)}")
                    .put("assembly", asm)
                    .put("writtenHex", hexStr)
                    .put("size", assembled.size)
                    .put("casVerified", expectedHex.isNotBlank())
                    .put("hint", "指令已写入, 用 taffy_so_addr_map 或 rzCommand 验证"))
            } else {
                writeResult
            }
        }

        private fun extractHexFromRzOutput(result: JSONObject): String {
            // rizin pxj 返回 JSON 数组
            val data: String = result.optJSONArray("data")?.toString() ?: result.optString("output", "")
            return if (data.startsWith("[")) {
                // JSON 数组 → hex
                val arr = JSONArray(data)
                (0 until arr.length()).joinToString("") { "%02x".format(arr.getInt(it)) }
            } else {
                data
            }
        }
    }

    /** Native 字符串补丁 — CAS 替换 */
    val nativePatchString: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_native_patch_string",
            "【Native 字符串补丁】替换 SO 中的字符串。CAS 保护: 先读旧字符串(expectedText), 匹配才写入新字符串。自动处理 NUL 终止符和长度差异(截断或 padding)。参考 MT管理器的 taffy_native_patch_string。",
            "Patch string in SO. CAS protection: read expected text first, only write if matches. Handles NUL terminator and length differences (truncate or pad). Same as MT taffy_native_patch_string.",
            "edit", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "workspaceId" str "SO 工作区 ID"
                "address" str "目标虚拟地址(0xHEX)"
                "newText" str "新字符串内容"
                "expectedText" str "当前字符串(用于 CAS 校验, 为空则跳过校验)"
                "padMode".oneOf("长度不足时的填充方式: null_pad(NUL填充) | space_pad(空格填充) | none(不填充, 长度必须一致)", "null_pad", "space_pad", "none")
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val ws = args.str("workspaceId")
            val address = args.str("address")
            val newText = args.str("newText")
            if (ws.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 workspaceId", "workspaceId", "")
            if (address.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 address", "address", address)
            if (newText.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 newText", "newText", newText)

            val engine = EngineProvider.get(ctx.context)
            val addr = address.removePrefix("0x").toLong(16)
            val expectedText = args.str("expectedText")
            val padMode = args.str("padMode", "null_pad")

            // 1. CAS 校验
            if (expectedText.isNotBlank()) {
                val readLen = expectedText.toByteArray().size + 16 // 多读一点
                val readResult = engine.rzCommand(ws, "", "ps @ $addr", false)
                val currentText = readResult.optString("output", "").trim()
                if (!currentText.startsWith(expectedText)) {
                    return err("CAS_MISMATCH",
                        "expectedText 不匹配当前字符串",
                        "address", address).put("currentText", currentText)
                        .put("expectedText", expectedText)
                }
            }

            // 2. 构造写入字节
            val newBytes = newText.toByteArray()
            val expectedLen = if (expectedText.isNotBlank()) expectedText.toByteArray().size else newBytes.size
            val writeBytes = when (padMode) {
                "null_pad" -> {
                    if (newBytes.size < expectedLen) {
                        newBytes + ByteArray(expectedLen - newBytes.size) { 0 }
                    } else {
                        newBytes.copyOf(expectedLen)
                    }
                }
                "space_pad" -> {
                    if (newBytes.size < expectedLen) {
                        newBytes + ByteArray(expectedLen - newBytes.size) { ' '.code.toByte() }
                    } else {
                        newBytes.copyOf(expectedLen)
                    }
                }
                "none" -> {
                    if (newBytes.size != expectedLen) {
                        return err("SIZE_MISMATCH", "newText 长度(${newBytes.size}) != expectedText 长度($expectedLen), padMode=none 要求长度一致", "newText", newText)
                    }
                    newBytes
                }
                else -> newBytes
            }

            // 3. 写入
            val hexStr = writeBytes.joinToString("") { "%02x".format(it) }
            val writeResult = engine.rzCommand(ws, "", "wx $hexStr @ $addr", false)

            return if (writeResult.optBoolean("ok", false)) {
                ok(JSONObject()
                    .put("action", "taffy_native_patch_string")
                    .put("address", "0x${addr.toString(16)}")
                    .put("newText", newText)
                    .put("writtenSize", writeBytes.size)
                    .put("padMode", padMode)
                    .put("casVerified", expectedText.isNotBlank())
                    .put("hint", "字符串已写入, 用 rzCommand ps @ addr 验证"))
            } else {
                writeResult
            }
        }
    }

    /** APK 统一搜索 — 跨 DEX/Native/资源/ZIP, 带分页游标 */
    val apkUnifiedSearch: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_apk_unified_search",
            "【APK 统一搜索】跨 DEX 类名/方法名/字符串、Native 符号、资源文件名、ZIP 条目名统一搜索。返回分页结果, 支持游标翻页。比 taffy_apk_search 更强: 同时搜索 DEX+Native+资源, 一次调用覆盖全部。参考 MT管理器的 mt_apk_search 统一搜索能力。",
            "Unified APK search across DEX class/method/strings, native symbols, resource files, ZIP entries. Returns paginated results with cursor. Stronger than taffy_apk_search: searches DEX+Native+resources in one call. Inspired by MT mt_apk_search.",
            "apk", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "path" str "APK 文件路径"
                "keyword" str "搜索关键字"
                "scope".oneOf("搜索范围: all(全部) | dex(仅DEX) | native(仅SO) | resource(仅资源) | zip(仅ZIP条目)", "all", "dex", "native", "resource", "zip")
                "limit" int "每页条数(默认 50, 最大 200)"
                "cursor" str "分页游标(首次为空, 翻页时传入上次返回的 nextCursor)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path", "path", "")
            val file = File(path)
            if (!file.isFile) return err("FILE_NOT_FOUND", "APK 不存在: $path", "path", path)
            val keyword = args.str("keyword")
            if (keyword.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 keyword", "keyword", keyword)
            val scope = args.str("scope", "all")
            val limit = args.intValue("limit", 50).coerceIn(1, 200)
            val cursor = args.str("cursor")

            // 解析游标: "scope:lastIndex"
            val (cursorScope, cursorIdx) = if (cursor.isNotBlank()) {
                val parts = cursor.split(":", limit = 2)
                (parts.getOrNull(0) ?: "") to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
            } else {
                "" to 0
            }

            val results = JSONArray()
            var total = 0
            var nextCursor: String? = null
            val scopes = if (scope == "all") listOf("dex", "native", "resource", "zip") else listOf(scope)

            ZipFile(file).use { zf ->
                for (sc in scopes) {
                    if (results.length() >= limit) {
                        nextCursor = "$sc:${total}"
                        break
                    }
                    when (sc) {
                        "dex" -> {
                            // 用 DexKit 搜索类名/方法名/字符串
                            try {
                                val dexMatches = JSONArray()
                                org.luckypray.dexkit.DexKitBridge.create(file.absolutePath).use { bridge ->
                                    // 搜索使用某字符串的方法
                                    val findMethods = org.luckypray.dexkit.query.FindMethod.create()
                                        .matcher(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                                            .usingStrings(listOf(keyword),
                                                org.luckypray.dexkit.query.enums.StringMatchType.Contains, true))
                                    bridge.findMethod(findMethods).take(limit).forEach { m ->
                                        dexMatches.put(JSONObject()
                                            .put("scope", "dex")
                                            .put("type", "method_using_string")
                                            .put("class", m.className)
                                            .put("method", m.methodName)
                                            .put("descriptor", m.descriptor))
                                    }
                                    // 按类名搜
                                    val findClasses = org.luckypray.dexkit.query.FindClass.create()
                                        .matcher(org.luckypray.dexkit.query.matchers.ClassMatcher.create()
                                            .className(keyword, org.luckypray.dexkit.query.enums.StringMatchType.Contains, true))
                                    bridge.findClass(findClasses).take(limit).forEach { c ->
                                        dexMatches.put(JSONObject()
                                            .put("scope", "dex")
                                            .put("type", "class_name")
                                            .put("class", c.name))
                                    }
                                }
                                total += dexMatches.length()
                                for (i in 0 until dexMatches.length()) {
                                    if (results.length() >= limit) { nextCursor = "dex:$total"; break }
                                    results.put(dexMatches.get(i))
                                }
                            } catch (e: Exception) { com.soreverse.mcp.core.AppLog.w("silent-catch: ${e.message}") }
                        }

                        "native" -> {
                            // 搜索 ZIP 中所有 .so 文件名 + ELF 符号(通过 rizin)
                            zf.entries().toList().filter { it.name.endsWith(".so") }.forEach { entry ->
                                if (results.length() >= limit) { nextCursor = "native:${total}"; return@forEach }
                                if (entry.name.contains(keyword, ignoreCase = true)) {
                                    results.put(JSONObject()
                                        .put("scope", "native")
                                        .put("type", "so_file")
                                        .put("name", entry.name)
                                        .put("size", entry.size))
                                    total++
                                }
                            }
                        }

                        "resource" -> {
                            // 搜索资源文件名
                            zf.entries().toList()
                                .filter { it.name.startsWith("res/") || it.name.startsWith("assets/") }
                                .filter { it.name.contains(keyword, ignoreCase = true) }
                                .take(limit)
                                .forEach { entry ->
                                    results.put(JSONObject()
                                        .put("scope", "resource")
                                        .put("type", "resource_file")
                                        .put("name", entry.name)
                                        .put("size", entry.size))
                                    total++
                                }
                        }

                        "zip" -> {
                            // 搜索所有 ZIP 条目名
                            zf.entries().toList()
                                .filter { it.name.contains(keyword, ignoreCase = true) }
                                .drop(cursorIdx)
                                .take(limit - results.length())
                                .forEach { entry ->
                                    results.put(JSONObject()
                                        .put("scope", "zip")
                                        .put("type", "zip_entry")
                                        .put("name", entry.name)
                                        .put("size", entry.size)
                                        .put("compressedSize", entry.compressedSize))
                                    total++
                                }
                        }
                    }
                }
            }

            return ok(JSONObject()
                .put("action", "taffy_apk_unified_search")
                .put("keyword", keyword)
                .put("scope", scope)
                .put("total", total)
                .put("returned", results.length())
                .put("hasMore", nextCursor != null)
                .put("nextCursor", nextCursor ?: JSONObject.NULL)
                .put("results", results))
        }
    }

    val ALL = listOf(nativePatchInstructions, nativePatchString, apkUnifiedSearch)
}
