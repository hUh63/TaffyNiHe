package com.soreverse.mcp.mcp

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * 塔菲逆核: 框架 API 参照解析（用 android.jar / framework.jar 标注 DEX 调用）。
 *
 * 逆向时看到一个裸签名, 常常想知道「这是不是公开 API」。本工具把一份参照 jar
 * (android.jar / framework.jar, 内含框架类的 .class) 解析成
 * 「类内部名 -> 已声明方法(名字+描述符)」的索引, 然后:
 *   - 把目标 DEX 里对框架类(Landroid/ Ljava/ Ljavax/ Lorg/ Ldalvik/)的调用逐个核对;
 *   - 标出「不在参照 jar 中」的成员 —— 即隐藏 API / 非 SDK 接口 / 反射目标;
 *   - 可检索参照 jar 里的类与方法签名(check / members)。
 *
 * 与 taffy_call_graph(方法调用图) 互补: call_graph 给"谁调谁", 本工具给
 * "这个调用是不是公开 API"。资源 ID -> 资源名 请用 taffy_resource_xref。
 *
 * 纯 dexlib2 + ZIP/常量池解析, 无需 native 引擎, 只读。
 */
object ApiResolveTool {

    /** 默认视作"框架"的类型前缀（DEX 类型描述符形式）。 */
    private val DEFAULT_PREFIXES = listOf("Landroid/", "Ljava/", "Ljavax/", "Lorg/", "Ldalvik/")

    /** 单次扫描的方法数上限, 防止超大 APK 卡死。 */
    private const val MAX_SCAN_METHODS = 300_000

    /** 参照 jar 解析的类数上限。 */
    private const val MAX_JAR_CLASSES = 40_000

    val tool: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_api_resolve",
            "【框架 API 参照】用一份 android.jar/framework.jar 标注 DEX 的框架调用：把裸 smali 签名还原为可读的 API 签名，" +
                "并标出「不在该 jar 中」的调用（隐藏 API / 非 SDK 接口 / 反射目标）。" +
                "action=scan(默认) 扫描目标 dex/apk 的框架调用并逐个核对；action=check 在参照 jar 中检索类/方法；" +
                "action=members 列出匹配类的全部成员签名。纯 dexlib2 + ZIP/常量池解析，只读，无需 native 引擎。" +
                "资源 ID→名字 请用 taffy_resource_xref；调用图请用 taffy_call_graph。",
            "Resolve framework API calls in a DEX/APK against a reference android.jar/framework.jar. Rewrites raw smali " +
                "signatures into readable API signatures and flags calls absent from the jar (hidden / non-SDK APIs). " +
                "action=scan (default, scan target and verify each framework call) | check (search the jar) | " +
                "members (list members of matching classes). Pure dexlib2 + ZIP parsing, read-only, no native engine.",
            "analyze", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "action" str "scan(默认) | check | members"
                "path" str "目标 APK 或 .dex 路径（scan 必需）"
                "framework" str "参照 jar 路径（android.jar / framework.jar）；scan 用于核对，check/members 必需"
                "query" str "check/members 的类名或方法名关键字（子串匹配，大小写不敏感）"
                "prefix" str "只统计这些前缀的框架类（逗号分隔；默认 Android/Java/Javax/Org/Dalvik）"
                "limit" str "返回上限（默认 200）"
                "includeAvailable" str "scan 是否同时列出「已解析成功」的调用（true/false，默认 false 只列未解析）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "scan").lowercase()
            return when (action) {
                "check", "members" -> checkOrMembers(args)
                else -> scan(args)
            }
        }
    }

    // ── action=scan ──────────────────────────────────────────────────────────

    private fun scan(args: JSONObject): JSONObject {
        val path = args.str("path")
        if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少 path", "path", "")
        val f = File(path)
        if (!f.isFile) return err("FILE_NOT_FOUND", "文件不存在: $path", "path", path)

        val limit = (args.str("limit").toIntOrNull() ?: 200).coerceIn(1, 5000)
        val includeAvailable = args.str("includeAvailable").equals("true", ignoreCase = true)
        val frameworkPath = args.str("framework").trim()
        val prefixes = args.str("prefix").split(',')
            .map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { DEFAULT_PREFIXES }

        return runCatching {
            // 1) 参照索引
            var fwClasses = 0
            var fwMembers = 0
            var index: Map<String, Set<String>> = emptyMap()
            if (frameworkPath.isNotBlank()) {
                val jf = File(frameworkPath)
                if (!jf.isFile) return@runCatching err(
                    "FILE_NOT_FOUND", "参照 jar 不存在: $frameworkPath", "framework", frameworkPath)
                index = buildJarIndex(jf)
                fwClasses = index.size
                fwMembers = index.values.sumOf { it.size }
            }

            // 2) 扫描目标 DEX 的框架调用
            val refs = LinkedHashMap<String, MutableSet<String>>()
            var scannedMethods = 0
            var truncated = false
            val dexFiles = if (path.lowercase().endsWith(".dex")) {
                listOf(f.name to DexFileFactory.loadDexFile(f, Opcodes.getDefault()))
            } else {
                loadAllDexFromApk(f)
            }
            outer@ for ((_, dex) in dexFiles) {
                for (cls in dex.classes) {
                    for (m in cls.methods) {
                        val impl = m.implementation ?: continue
                        scannedMethods++
                        if (scannedMethods > MAX_SCAN_METHODS) { truncated = true; break@outer }
                        for (insn in impl.instructions) {
                            if (insn !is ReferenceInstruction) continue
                            val ref = insn.reference
                            if (ref is MethodReference) {
                                val dc = ref.definingClass
                                if (prefixes.any { dc.startsWith(it) }) {
                                    refs.getOrPut(internalName(dc)) { linkedSetOf() }.add(keyOf(ref))
                                }
                            }
                        }
                    }
                }
            }

            // 3) 逐个核对
            val groups = JSONArray()
            val unresolved = JSONArray()
            var resolved = 0
            var unresolvedCount = 0
            for ((cls_, members) in refs) {
                val declared = index[cls_]
                val classKnown = index.isNotEmpty() && declared != null
                val arr = JSONArray()
                for (mem in members) {
                    val avail: Boolean? = when {
                        index.isEmpty() -> null
                        declared == null -> false
                        else -> declared.contains(mem)
                    }
                    if (avail == true) resolved++ else unresolvedCount++
                    if (avail != true && unresolved.length() < limit) unresolved.put("$cls_->$mem")
                    if (avail == true && !includeAvailable) continue
                    if (arr.length() < limit) {
                        arr.put(JSONObject().put("member", mem).put("available", avail ?: JSONObject.NULL))
                    }
                }
                if (arr.length() > 0) {
                    groups.put(JSONObject().put("class", cls_).put("classKnown", classKnown).put("members", arr))
                }
                if (groups.length() >= limit) break
            }

            val note = when {
                frameworkPath.isBlank() -> "未提供 framework，仅列出框架调用（available=null）；给出 android.jar 路径即可标注公开/隐藏 API。"
                unresolvedCount == 0 -> "全部框架调用都能在参照 jar 中找到（公开 API）。"
                else -> "有 $unresolvedCount 个调用不在参照 jar 中：可能是隐藏/非 SDK 接口、反射目标，或参照 jar 未覆盖的库。"
            }
            ok(JSONObject()
                .put("tool", "taffy_api_resolve")
                .put("action", "scan")
                .put("path", path)
                .put("framework", frameworkPath)
                .put("frameworkClasses", fwClasses)
                .put("frameworkMembers", fwMembers)
                .put("scannedMethods", scannedMethods)
                .put("distinctFrameworkClasses", refs.size)
                .put("distinctFrameworkMembers", refs.values.sumOf { it.size })
                .put("resolvedCount", resolved)
                .put("unresolvedCount", unresolvedCount)
                .put("truncated", truncated)
                .put("unresolved", unresolved)
                .put("groups", groups)
                .put("note", note))
        }.getOrElse { e ->
            err("API_RESOLVE_FAILED", "框架 API 解析失败: ${e.message ?: e.javaClass.simpleName}", "path", path)
        }
    }

    // ── action=check / members ───────────────────────────────────────────────

    private fun checkOrMembers(args: JSONObject): JSONObject {
        val fw = args.str("framework").trim()
        if (fw.isBlank()) return err("INVALID_ARGUMENT", "缺少 framework（参照 jar 路径）", "framework", "")
        val jf = File(fw)
        if (!jf.isFile) return err("FILE_NOT_FOUND", "参照 jar 不存在: $fw", "framework", fw)
        val query = args.str("query").trim()
        if (query.isBlank()) return err("INVALID_ARGUMENT", "缺少 query（类名/方法名关键字）", "query", "")
        val limit = (args.str("limit").toIntOrNull() ?: 200).coerceIn(1, 5000)

        return runCatching {
            val index = buildJarIndex(jf)
            val matches = JSONArray()
            var memberMatches = 0
            for ((cls_, members) in index) {
                val classHit = cls_.contains(query, ignoreCase = true)
                val hitMembers = members.filter { it.contains(query, ignoreCase = true) }
                if (!classHit && hitMembers.isEmpty()) continue
                val shown = (if (classHit) members.toList() else hitMembers).take(limit)
                memberMatches += hitMembers.size
                if (matches.length() < limit) {
                    matches.put(JSONObject()
                        .put("class", cls_)
                        .put("classMatched", classHit)
                        .put("memberCount", members.size)
                        .put("members", JSONArray(shown)))
                }
                if (matches.length() >= limit) break
            }
            ok(JSONObject()
                .put("tool", "taffy_api_resolve")
                .put("action", args.str("action", "check"))
                .put("framework", fw)
                .put("classes", index.size)
                .put("matchedClasses", matches.length())
                .put("memberMatches", memberMatches)
                .put("matches", matches))
        }.getOrElse { e ->
            err("API_RESOLVE_FAILED", "解析参照 jar 失败: ${e.message ?: e.javaClass.simpleName}", "framework", fw)
        }
    }

    // ── 参照 jar 索引 ────────────────────────────────────────────────────────

    /** 解析 jar 内所有 .class，得到「类内部名 -> 已声明方法(名字+描述符)」。 */
    private fun buildJarIndex(jar: File): Map<String, Set<String>> {
        val out = HashMap<String, MutableSet<String>>()
        ZipFile(jar).use { zf ->
            var n = 0
            for (e in zf.entries()) {
                if (e.isDirectory || !e.name.endsWith(".class")) continue
                if (n >= MAX_JAR_CLASSES) break
                val bytes = runCatching { zf.getInputStream(e).use { it.readBytes() } }.getOrNull() ?: continue
                val parsed = runCatching { parseClassMethods(bytes) }.getOrNull() ?: continue
                out.getOrPut(parsed.first) { HashSet() }.addAll(parsed.second)
                n++
            }
        }
        return out
    }

    /**
     * 极简 .class 解析：只提取 this_class 内部名 与 所有方法的 (name, descriptor)。
     * 大端字节序；常量池按 tag 步进，字段/方法属性表整体跳过。
     */
    private fun parseClassMethods(data: ByteArray): Pair<String, List<String>>? {
        if (data.size < 10) return null
        if ((data[0].toInt() and 0xFF) != 0xCA || (data[1].toInt() and 0xFF) != 0xFE ||
            (data[2].toInt() and 0xFF) != 0xBA || (data[3].toInt() and 0xFF) != 0xBE) return null
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        bb.position(8)
        val cpCount = bb.short.toInt() and 0xFFFF
        val utf8 = arrayOfNulls<String>(cpCount)
        val classRef = IntArray(cpCount) { -1 } // Class_info -> name_index
        var i = 1
        while (i < cpCount) {
            when (val tag = bb.get().toInt() and 0xFF) {
                1 -> { // Utf8
                    val len = bb.short.toInt() and 0xFFFF
                    val bytes = ByteArray(len)
                    bb.get(bytes)
                    utf8[i] = decodeClassUtf8(bytes)
                }
                3, 4 -> bb.position(bb.position() + 4)
                5, 6 -> { bb.position(bb.position() + 8); i++ } // 占两个槽位
                7, 8, 16, 19, 20 -> {
                    val idx = bb.short.toInt() and 0xFFFF
                    if (tag == 7) classRef[i] = idx
                }
                9, 10, 11, 12, 17, 18 -> bb.position(bb.position() + 4)
                15 -> bb.position(bb.position() + 3)
                else -> return null
            }
            i++
        }
        bb.short                       // access_flags
        val thisClass = bb.short.toInt() and 0xFFFF
        bb.short                       // super_class
        val ifaceCount = bb.short.toInt() and 0xFFFF
        bb.position(bb.position() + 2 * ifaceCount)
        val fieldsCount = bb.short.toInt() and 0xFFFF
        repeat(fieldsCount) { skipMember(bb) }
        val methodsCount = bb.short.toInt() and 0xFFFF

        val nameIdx = classRef.getOrNull(thisClass)?.takeIf { it > 0 }?.let { utf8.getOrNull(it) } ?: return null
        val members = ArrayList<String>(methodsCount)
        repeat(methodsCount) {
            bb.short // access_flags
            val ni = bb.short.toInt() and 0xFFFF
            val di = bb.short.toInt() and 0xFFFF
            val attrCount = bb.short.toInt() and 0xFFFF
            repeat(attrCount) {
                bb.short                      // attribute_name_index
                val attrLen = bb.int
                bb.position(bb.position() + attrLen)
            }
            val n = utf8.getOrNull(ni)
            val d = utf8.getOrNull(di)
            if (n != null && d != null) members.add(n + d)
        }
        return nameIdx to members
    }

    private fun skipMember(bb: ByteBuffer) {
        bb.short // access
        bb.short // name_index
        bb.short // descriptor_index
        val attrCount = bb.short.toInt() and 0xFFFF
        repeat(attrCount) {
            bb.short
            val attrLen = bb.int
            bb.position(bb.position() + attrLen)
        }
    }

    /** modified UTF-8（class 常量池）：0xC0 0x80 表示 NUL，其余按 UTF-8 解。 */
    private fun decodeClassUtf8(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b == 0xC0 && i + 1 < bytes.size && (bytes[i + 1].toInt() and 0xFF) == 0x80 -> { sb.append('\u0000'); i += 2 }
                b < 0x80 -> { sb.append(b.toChar()); i += 1 }
                b and 0xE0 == 0xC0 && i + 1 < bytes.size -> {
                    sb.append((((b and 0x1F) shl 6) or (bytes[i + 1].toInt() and 0x3F)).toChar()); i += 2
                }
                b and 0xF0 == 0xE0 && i + 2 < bytes.size -> {
                    val cp = ((b and 0x0F) shl 12) or ((bytes[i + 1].toInt() and 0x3F) shl 6) or (bytes[i + 2].toInt() and 0x3F)
                    sb.append(cp.toChar()); i += 3
                }
                else -> { i += 1 }
            }
        }
        return sb.toString()
    }

    // ── DEX 辅助 ─────────────────────────────────────────────────────────────

    private fun loadAllDexFromApk(apk: File): List<Pair<String, DexFile>> {
        val out = mutableListOf<Pair<String, DexFile>>()
        ZipFile(apk).use { zf ->
            zf.entries().toList()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .sortedBy { it.name }
                .forEach { e ->
                    runCatching {
                        val tmp = File.createTempFile("taffy-api-", ".dex")
                        zf.getInputStream(e).use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
                        val dex = DexFileFactory.loadDexFile(tmp, Opcodes.getDefault())
                        tmp.delete()
                        out.add(e.name to dex)
                    }
                }
        }
        return out
    }

    /** DEX 类型描述符 -> 类内部名（Landroid/app/Activity; -> android/app/Activity）。 */
    private fun internalName(dexType: String): String =
        if (dexType.startsWith("L") && dexType.endsWith(";") && dexType.length > 2)
            dexType.substring(1, dexType.length - 1) else dexType

    /** 与方法描述符一致的 "名字(参数)返回" 键。 */
    private fun keyOf(ref: MethodReference): String =
        "${ref.name}(${ref.parameterTypes.joinToString("") { it.toString() }})${ref.returnType}"

    val ALL = listOf(tool)
}
