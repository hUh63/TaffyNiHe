package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import com.soreverse.mcp.core.EngineProvider
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.util.zip.ZipFile

/**
 * 塔菲逆核: 进阶逆向辅助工具(参考 MT管理器的 native_xref / native_map_address / native_function_cfg
 * / patch_bytes / taffy_apk_search 等高级工具, 包装塔菲逆核已有的 rizin 引擎能力)。
 *
 * 这些工具把 rizin 的底层能力暴露为 AI 友好的高层 MCP 工具, 补齐 MT管理器有但塔菲逆核缺少的:
 *  - taffy_so_xref: 交叉引用(谁调用了某地址/函数) — 包装 rzXrefs
 *  - taffy_so_cfg: 函数控制流图 — 通过 rzCommand 执行 agf
 *  - taffy_so_addr_map: VA↔FileOffset 地址映射 — 通过 rzCommand
 *  - taffy_so_search_bytes: 字节模式搜索 — 包装 rzSearchBytes
 *  - taffy_apk_search: APK 统一搜索(跨 dex/native/资源/ZIP条目)
 *  - taffy_apk_patch_bytes: APK 内 ZIP 条目级原始字节读写
 *  - taffy_apk_resource_list: 列出 APK 内资源文件
 */
object AdvancedTools {

    /** 交叉引用: 查谁引用了某地址/函数, 或某地址引用了谁 */
    val soXref: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_so_xref",
            "【SO 交叉引用】查找谁调用了某函数/地址(to), 或某函数/地址调用了什么(from)。输入 workspaceId + locator(函数名/VA/符号), direction=to 查[被谁调用](逆向最常用, 定位关键函数的调用者), direction=from 查[调用了谁]。包装 rizin rzXrefs。",
            "Cross-reference search. direction=to finds who calls a function/address (most useful for locating callers); direction=from finds what it calls. Wraps rizin rzXrefs.",
            "analyze", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "workspaceId" str "SO 工作区 ID(engine.open 返回)"
                "locator" str "目标定位: 函数名/符号/虚拟地址(如 0x1234)"
                "direction".oneOf("to=被谁调用(默认) | from=调用了谁", "to", "from")
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val ws = args.str("workspaceId")
            val locator = args.str("locator")
            if (ws.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 workspaceId", "workspaceId", "")
            if (locator.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 locator(函数名/VA/符号)", "locator", "")
            val direction = args.str("direction", "to")
            val engine = EngineProvider.get(ctx.context)
            return engine.rzXrefs(ws, "", locator, direction)
        }
    }

    /** 函数控制流图 */
    val soCfg: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_so_cfg",
            "【SO 控制流图】生成指定函数的控制流图(CFG), 展示基本块和跳转关系。输入 workspaceId + locator(函数名/VA)。通过 rizin agf 命令生成, 返回图的 JSON 描述(节点=基本块, 边=跳转)。用于分析函数分支逻辑、循环结构。",
            "Control flow graph for a function. Generates CFG via rizin agf command. Returns JSON with nodes (basic blocks) and edges (jumps). Useful for analyzing branching and loop structure.",
            "analyze", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "workspaceId" str "SO 工作区 ID"
                "locator" str "目标函数: 函数名/符号/虚拟地址"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val ws = args.str("workspaceId")
            val locator = args.str("locator")
            if (ws.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 workspaceId", "workspaceId", "")
            if (locator.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 locator(函数名/VA)", "locator", "")
            val engine = EngineProvider.get(ctx.context)
            // rizin: seek to locator, then agfj (graph json)
            val cmd = "s $locator; agfj"
            return engine.rzCommand(ws, "", cmd, false)
        }
    }

    /** VA↔FileOffset 地址映射 */
    val soAddrMap: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_so_addr_map",
            "【地址映射】虚拟地址(VA)↔文件偏移(FileOffset)互转。action=va_to_offset: VA→偏移; action=offset_to_va: 偏移→VA; action=sections: 列出所有节区及其地址范围。逆向时定位 IDA/Ghidra 中的地址对应 SO 文件的哪个位置。通过 rizin 命令实现。",
            "VA↔FileOffset address mapping. action=va_to_offset, offset_to_va, or sections. Helps locate IDA/Ghidra addresses in the SO file. Uses rizin commands.",
            "analyze", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "workspaceId" str "SO 工作区 ID"
                "action".oneOf("va_to_offset | offset_to_va | sections", "va_to_offset", "offset_to_va", "sections")
                "address" str "va_to_offset: 虚拟地址(0xHEX); offset_to_va: 文件偏移(0xHEX或十进制)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val ws = args.str("workspaceId")
            if (ws.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 workspaceId", "workspaceId", "")
            val action = args.str("action", "sections")
            val address = args.str("address")
            val engine = EngineProvider.get(ctx.context)
            val cmd = when (action) {
                "va_to_offset" -> {
                    if (address.isBlank()) return err("INVALID_ARGUMENT", "va_to_offset 需要 address 参数", "address", "")
                    "s $address; s"
                }
                "offset_to_va" -> {
                    if (address.isBlank()) return err("INVALID_ARGUMENT", "offset_to_va 需要 address 参数", "address", "")
                    "s $address@$$; s"
                }
                "sections" -> "iSj"
                else -> "iSj"
            }
            return engine.rzCommand(ws, "", cmd, false)
        }
    }

    /** 字节模式搜索 */
    val soSearchBytes: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_so_search_bytes",
            "【SO 字节搜索】在 SO 文件中搜索十六进制字节模式(支持通配符 ??)。如搜索 90 31 FF 6B 找到指令位置, 搜索 48 8B ?? ?? 找到寄存器加载。返回匹配的虚拟地址和偏移。包装 rizin rzSearchBytes。",
            "Hex byte pattern search in SO file (supports ?? wildcards). Returns matched virtual addresses and offsets. Wraps rizin rzSearchBytes.",
            "search", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "workspaceId" str "SO 工作区 ID"
                "pattern" str "十六进制字节模式, 空格分隔, ?? 为通配(如 '90 31 ?? 6B')"
                "fromVa" int "起始虚拟地址(可选, 0=从头)"
                "toVa" int "结束虚拟地址(可选, 0=到尾)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val ws = args.str("workspaceId")
            val pattern = args.str("pattern")
            if (ws.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 workspaceId", "workspaceId", "")
            if (pattern.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 pattern(十六进制模式)", "pattern", "")
            val fromVa = args.intValue("fromVa", 0).toLong()
            val toVa = args.intValue("toVa", 0).toLong()
            val engine = EngineProvider.get(ctx.context)
            return engine.rzSearchBytes(ws, "", pattern, fromVa, toVa)
        }
    }

    /** APK 统一搜索: 跨 ZIP条目/DEX/资源 搜索 */
    val apkSearch: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_apk_search",
            "【APK 统一搜索】在 APK 内统一搜索: action=zip_entries 按 ZIP 条目名搜(如 classes、AndroidManifest); action=file_names 搜文件路径; action=classes_dex 搜 DEX 中的类名; action=strings 搜 APK 内所有 DEX 的字符串(用 DexKit)。返回匹配条目列表。参考 MT管理器 mt_apk_search。",
            "Unified APK search. action=zip_entries (search ZIP entry names); file_names; classes_dex (class names in DEX); strings (DEX strings via DexKit). Returns matched entries. Inspired by MT mt_apk_search.",
            "apk", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("zip_entries | file_names | classes_dex | strings", "zip_entries", "file_names", "classes_dex", "strings")
                "path" str "APK 文件路径"
                "keyword" str "搜索关键字"
                "limit" int "最多返回条数(默认 50)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path(APK 路径)", "path", "")
            val file = File(path)
            if (!file.isFile) return err("FILE_NOT_FOUND", "APK 不存在: $path", "path", path)
            val action = args.str("action", "zip_entries")
            val keyword = args.str("keyword")
            if (keyword.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 keyword", "keyword", "")
            val limit = args.intValue("limit", 50).coerceIn(1, 500)

            return runCatching {
                ZipFile(file).use { zf ->
                    when (action) {
                        "zip_entries", "file_names" -> {
                            val matches = JSONArray()
                            var count = 0
                            zf.entries().toList()
                                .filter { it.name.contains(keyword, ignoreCase = true) }
                                .take(limit)
                                .forEach { e ->
                                    matches.put(JSONObject()
                                        .put("name", e.name)
                                        .put("size", e.size)
                                        .put("compressedSize", e.compressedSize)
                                        .put("isDirectory", e.isDirectory))
                                    count++
                                }
                            ok(JSONObject()
                                .put("action", action)
                                .put("keyword", keyword)
                                .put("total", count)
                                .put("results", matches))
                        }
                        "classes_dex" -> {
                            // 列出所有 classes*.dex 文件
                            val dexFiles = zf.entries().toList().filter {
                                it.name.matches(Regex("classes\\d*\\.dex"))
                            }
                            val matches = JSONArray()
                            dexFiles.forEach { dex ->
                                matches.put(JSONObject()
                                    .put("dex", dex.name)
                                    .put("size", dex.size)
                                    .put("hint", "用 taffy_dex_search 或 taffy_jadx_decompile 进一步分析此 DEX"))
                            }
                            ok(JSONObject()
                                .put("action", "classes_dex")
                                .put("dexFiles", matches)
                                .put("hint", "找到 ${dexFiles.size} 个 DEX 文件, 用 taffy_dex_search 搜索类名/方法/字符串"))
                        }
                        "strings" -> {
                            // 用 DexKit 搜索字符串
                            val results = JSONArray()
                            DexKitBridge.create(file.absolutePath).use { bridge ->
                                val find = org.luckypray.dexkit.query.FindMethod.create()
                                    .matcher(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                                        .usingStrings(listOf(keyword),
                                            org.luckypray.dexkit.query.enums.StringMatchType.Contains, true))
                                val methods = bridge.findMethod(find)
                                methods.take(limit).forEach { m ->
                                    results.put(JSONObject()
                                        .put("class", m.className)
                                        .put("method", m.methodName)
                                        .put("descriptor", m.descriptor))
                                }
                            }
                            ok(JSONObject()
                                .put("action", "strings")
                                .put("keyword", keyword)
                                .put("total", results.length())
                                .put("results", results))
                        }
                        else -> err("UNKNOWN_ACTION", "未知 action: $action", "action", action)
                    }
                }
            }.getOrElse { e ->
                err("APK_SEARCH_FAILED", "APK 搜索失败: ${e.message}", "path", path)
            }
        }
    }

    /** APK 内 ZIP 条目级字节读写(补丁) */
    val apkPatchBytes: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_apk_patch_bytes",
            "【APK 字节补丁(CAS)】读取或写入 APK 内指定 ZIP 条目的原始字节。action=read: 读取指定条目的字节(支持 offset+length); action=write: 写入字节到指定条目(带 CAS 乐观锁: 先校验 expectedHex 匹配当前字节才写入, 不匹配返回当前字节让调用方重试); action=verify: 验证指定偏移的字节是否匹配。参考 MT管理器 mt_apk_read_bytes / mt_apk_patch_bytes 的 CAS 保护机制。",
            "Read/write raw bytes of a ZIP entry inside APK with CAS protection. action=read; write (with expectedHex optimistic lock); verify. Inspired by MT mt_apk_read_bytes / mt_apk_patch_bytes CAS.",
            "apk", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "action".oneOf("read | write | verify", "read", "write", "verify")
                "path" str "APK 文件路径"
                "entry" str "ZIP 条目名(如 classes.dex, lib/arm64-v8a/libnative.so)"
                "offset" int "字节偏移(默认0)"
                "length" int "读取长度(默认全部, 最大65536)"
                "hex" str "write: 写入的十六进制字节(如 '90 31 FF 6B')"
                "expectedHex" str "write: CAS 乐观锁 — 当前偏移处的原始字节(十六进制), 为空则跳过校验直接写入(不推荐)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path", "path", "")
            val file = File(path)
            if (!file.isFile) return err("FILE_NOT_FOUND", "APK 不存在: $path", "path", path)
            val action = args.str("action", "read")
            val entryName = args.str("entry")
            if (entryName.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 entry(ZIP条目名)", "entry", "")

            return runCatching {
                when (action) {
                    "read" -> {
                        val offset = args.intValue("offset", 0).coerceAtLeast(0)
                        val maxLen = args.intValue("length", 0).coerceAtMost(65536)
                        ZipFile(file).use { zf ->
                            val entry = zf.getEntry(entryName)
                                ?: return err("ENTRY_NOT_FOUND", "ZIP 条目不存在: $entryName", "entry", entryName)
                            zf.getInputStream(entry).use { stream ->
                                val allBytes = stream.readBytes()
                                val end = if (maxLen > 0) minOf(offset + maxLen, allBytes.size) else allBytes.size
                                val slice = if (offset < allBytes.size) allBytes.sliceArray(offset until end) else ByteArray(0)
                                val hex = slice.joinToString(" ") { "%02X".format(it) }
                                ok(JSONObject()
                                    .put("action", "read")
                                    .put("entry", entryName)
                                    .put("entrySize", allBytes.size)
                                    .put("offset", offset)
                                    .put("returnedBytes", slice.size)
                                    .put("hex", hex)
                                    .put("ascii", slice.joinToString("") {
                                        if (it in 32..126) it.toInt().toChar().toString() else "."
                                    })
                                    .put("hint", "写入前用 expectedHex 传回当前 hex 做 CAS 校验"))
                            }
                        }
                    }

                    "verify" -> {
                        val offset = args.intValue("offset", 0).coerceAtLeast(0)
                        val expectedHex = args.str("expectedHex")
                        if (expectedHex.isBlank()) return err("INVALID_ARGUMENT", "verify 需要 expectedHex", "expectedHex", expectedHex)
                        ZipFile(file).use { zf ->
                            val entry = zf.getEntry(entryName)
                                ?: return err("ENTRY_NOT_FOUND", "ZIP 条目不存在: $entryName", "entry", entryName)
                            zf.getInputStream(entry).use { stream ->
                                val allBytes = stream.readBytes()
                                val expectedBytes = expectedHex.split("\\s+".toRegex()).filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
                                val actualBytes = if (offset < allBytes.size) allBytes.sliceArray(offset until minOf(offset + expectedBytes.size, allBytes.size)) else ByteArray(0)
                                val match = actualBytes.contentEquals(expectedBytes)
                                ok(JSONObject()
                                    .put("action", "verify")
                                    .put("entry", entryName)
                                    .put("offset", offset)
                                    .put("match", match)
                                    .put("expectedHex", expectedBytes.joinToString(" ") { "%02X".format(it) })
                                    .put("actualHex", actualBytes.joinToString(" ") { "%02X".format(it) }))
                            }
                        }
                    }

                    "write" -> {
                        val hexStr = args.str("hex")
                        if (hexStr.isBlank()) return err("INVALID_ARGUMENT", "write 需要 hex 参数", "hex", hexStr)
                        val offset = args.intValue("offset", 0).coerceAtLeast(0)
                        val expectedHex = args.str("expectedHex")
                        val writeBytes = hexStr.split("\\s+".toRegex()).filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

                        ZipFile(file).use { zf ->
                            val entry = zf.getEntry(entryName)
                                ?: return err("ENTRY_NOT_FOUND", "ZIP 条目不存在: $entryName", "entry", entryName)

                            // CAS 校验
                            if (expectedHex.isNotBlank()) {
                                zf.getInputStream(entry).use { stream ->
                                    val allBytes = stream.readBytes()
                                    val expectedBytes = expectedHex.split("\\s+".toRegex()).filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
                                    val actualBytes = if (offset < allBytes.size) allBytes.sliceArray(offset until minOf(offset + expectedBytes.size, allBytes.size)) else ByteArray(0)
                                    if (!actualBytes.contentEquals(expectedBytes)) {
                                        return err("CAS_MISMATCH",
                                            "expectedHex 不匹配当前字节, 可能已被修改. 请用返回的 actualHex 重试.",
                                            "entry", entryName)
                                            .put("expectedHex", expectedHex)
                                            .put("actualHex", actualBytes.joinToString(" ") { "%02X".format(it) })
                                            .put("offset", offset)
                                    }
                                }
                            }

                            // 写入: 重建 ZIP
                            val origSize = entry.size
                            zf.getInputStream(entry).use { stream ->
                                val origBytes = stream.readBytes()
                                val newBytes = origBytes.copyOf()
                                if (offset + writeBytes.size > newBytes.size) {
                                    return err("OUT_OF_BOUNDS",
                                        "写入范围超出条目大小(offset=${offset}+writeSize=${writeBytes.size} > entrySize=${newBytes.size})",
                                        "entry", entryName)
                                }
                                System.arraycopy(writeBytes, 0, newBytes, offset, writeBytes.size)

                                val tempFile = File.createTempFile("patch_", ".apk", file.parentFile)
                                file.copyTo(File(file.parentFile, "${file.nameWithoutExtension}.bak.apk"), overwrite = true)
                                java.util.zip.ZipOutputStream(tempFile.outputStream()).use { zos ->
                                    zf.entries().toList().forEach { e ->
                                        zos.putNextEntry(java.util.zip.ZipEntry(e.name))
                                        if (e.name == entryName) zos.write(newBytes)
                                        else zf.getInputStream(e).use { it.copyTo(zos) }
                                        zos.closeEntry()
                                    }
                                }
                                tempFile.copyTo(file, overwrite = true)
                                tempFile.delete()
                                ok(JSONObject()
                                    .put("action", "write")
                                    .put("entry", entryName)
                                    .put("offset", offset)
                                    .put("bytesWritten", writeBytes.size)
                                    .put("casVerified", expectedHex.isNotBlank())
                                    .put("hint", "字节已写入, APK 签名已失效, 用 taffy_apk_rebuild(build) 重新签名. 用 taffy_apk_patch_bytes(read) 验证写入结果"))
                            }
                        }
                    }

                    else -> err("UNKNOWN_ACTION", "未知 action: $action", "action", action)
                }
            }.getOrElse { e ->
                err("PATCH_FAILED", "字节补丁失败: ${e.message}", "path", path)
            }
        }
    }

    /** 列出 APK 内的资源文件 */
    val apkResourceList: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_apk_resource_list",
            "【APK 资源列表】列出 APK 内的资源文件。action=all 列出所有 res/ 和 assets/ 下的文件; action=drawable 列出图标/图片; action=layout 列出布局; action=values 列出值资源(strings/styles/themes); action=native 列出 lib/ 下的 SO 文件。参考 MT管理器的资源浏览能力。",
            "List resource files inside APK. action=all (all res/assets files); drawable; layout; values; native (lib/*.so). Inspired by MT resource browser.",
            "apk", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "path" str "APK 文件路径"
                "action".oneOf("all | drawable | layout | values | native", "all", "drawable", "layout", "values", "native")
                "keyword" str "过滤关键字(可选)"
                "limit" int "最多返回条数(默认 200)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path", "path", "")
            val file = File(path)
            if (!file.isFile) return err("FILE_NOT_FOUND", "APK 不存在: $path", "path", path)
            val action = args.str("action", "all")
            val keyword = args.str("keyword")
            val limit = args.intValue("limit", 200).coerceIn(1, 2000)

            return runCatching {
                ZipFile(file).use { zf ->
                    val prefix = when (action) {
                        "drawable" -> listOf("res/drawable", "res/mipmap")
                        "layout" -> listOf("res/layout")
                        "values" -> listOf("res/values")
                        "native" -> listOf("lib/")
                        else -> listOf("res/", "assets/")
                    }
                    val matches = JSONArray()
                    var count = 0
                    zf.entries().toList()
                        .filter { entry -> prefix.any { entry.name.startsWith(it) } }
                        .filter { keyword.isBlank() || it.name.contains(keyword, ignoreCase = true) }
                        .take(limit)
                        .forEach { e ->
                            matches.put(JSONObject()
                                .put("name", e.name)
                                .put("size", e.size)
                                .put("compressedSize", e.compressedSize)
                                .put("method", if (e.method == 0) "STORED" else "DEFLATE"))
                            count++
                        }
                    val category = when (action) {
                        "drawable" -> "图标/图片"
                        "layout" -> "布局文件"
                        "values" -> "值资源(strings/styles/themes)"
                        "native" -> "原生库(SO)"
                        else -> "全部资源"
                    }
                    ok(JSONObject()
                        .put("action", action)
                        .put("category", category)
                        .put("total", count)
                        .put("results", matches)
                        .put("hint", if (action == "native") "用 taffy_so_open 打开 SO 进行逆向分析" else "用 taffy_apk_patch_bytes(read) 读取文件内容"))
                }
            }.getOrElse { e ->
                err("RESOURCE_LIST_FAILED", "资源列表失败: ${e.message}", "path", path)
            }
        }
    }

    val ALL = listOf(soXref, soCfg, soAddrMap, soSearchBytes, apkSearch, apkPatchBytes, apkResourceList)
}
