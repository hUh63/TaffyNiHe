package com.soreverse.mcp.engine

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.iface.value.ByteEncodedValue
import com.android.tools.smali.dexlib2.iface.value.CharEncodedValue
import com.android.tools.smali.dexlib2.iface.value.EncodedValue
import com.android.tools.smali.dexlib2.iface.value.IntEncodedValue
import com.android.tools.smali.dexlib2.iface.value.LongEncodedValue
import com.android.tools.smali.dexlib2.iface.value.ShortEncodedValue
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import java.io.File
import java.util.zip.ZipFile

/**
 * DEX 索引搜索 —— 一次遍历回答「谁引用了什么」。
 *
 * 与已有工具的分工：
 * - `taffy_dex_xref` 只回答**单个方法**的 to/from，必须先给出 className+methodName；
 * - `taffy_dex_search`(DexKit) 走 native，只按「特征字符串」反查，heavy；
 * - 这里是第三条路：纯 dexlib2 全量扫描，一次遍历覆盖 13 种查询类型，
 *   支持包排除、分页、大小写/正则，不依赖 native，也不产生反编译产物。
 *
 * 设计取舍：
 * - 返回值是 data class 而非 JSONObject —— 单测跑在 mockable android.jar 上，
 *   那里的 org.json 不完整（长链式 put 会抛 JSONException），纯 Kotlin 结构才能测。
 * - `matched`（真实命中总数）与 `hits`（当前页）分开：分页时仍要显示总数，
 *   所以扫描**不在 limit 处提前终止**，只是不往列表里追加。
 */
internal object DexIndex {

    /** 支持的查询类型（与前端下拉严格一致，顺序即 UI 顺序）。 */
    internal val TYPES = listOf(
        "class", "method", "field", "string", "int", "code",
        "new_instance", "field_get", "field_put", "field_any",
        "method_call", "method_override", "class_use",
    )

    internal val TYPE_LABELS_ZH = linkedMapOf(
        "class" to "类名", "method" to "方法名", "field" to "字段名",
        "string" to "字符串", "int" to "整数", "code" to "代码",
        "new_instance" to "实例化", "field_get" to "字段读", "field_put" to "字段写",
        "field_any" to "字段引用", "method_call" to "方法调用",
        "method_override" to "方法重写", "class_use" to "类使用",
    )

    /** 需要关键字才能查询的类型；引用型允许空关键字（= 列全部，靠 limit 截断）。 */
    internal val KEYWORD_REQUIRED = setOf("class", "method", "field", "string", "int", "code")

    internal data class Hit(
        val kind: String,
        val className: String,
        val member: String,
        val detail: String,
        val dex: String,
    )

    internal data class Result(
        val hits: List<Hit>,
        val matched: Int,
        val offset: Int,
        val limit: Int,
        val scannedClasses: Int,
        val scannedMethods: Int,
        val scannedInstructions: Long,
        val dexCount: Int,
        val truncated: Boolean,
        val error: String? = null,
    ) {
        val hasMore: Boolean get() = offset + hits.size < matched
    }

    private val DEX_ENTRY = Regex("""^classes\d*\.dex$""")
    private val DEX_ENTRY_KEY = Regex("""classes(\d*)\.dex""")

    // ---------------------------------------------------------------- 公共入口

    /**
     * @param path      APK / DEX 文件
     * @param type      见 [TYPES]
     * @param keyword   类名/方法名/字段名/smali 片段/字符串/整数（int 支持 0x 与负号）
     * @param tmpDir    APK 内 dex 解压用的临时目录（Android 上必须传 cacheDir；
     *                  不传则退到 java.io.tmpdir）
     */
    fun search(
        path: String,
        type: String,
        keyword: String,
        excludePackages: List<String> = emptyList(),
        caseSensitive: Boolean = false,
        regex: Boolean = false,
        limit: Int = 200,
        offset: Int = 0,
        tmpDir: File? = null,
    ): Result {
        val kind = normalizeType(type)
        val cap = limit.coerceIn(1, 2000)
        val skip = offset.coerceAtLeast(0)

        if (kind !in TYPES) return fail(cap, "未知查询类型: $type")
        val file = File(path)
        if (!file.isFile) return fail(cap, "文件不存在: $path")
        if (kind in KEYWORD_REQUIRED && keyword.isBlank()) {
            return fail(cap, "类型「${TYPE_LABELS_ZH[kind] ?: kind}」需要 keyword")
        }

        val matcher = try {
            KeywordMatcher(keyword, caseSensitive, regex)
        } catch (e: Throwable) {
            return fail(cap, "关键字无效: ${e.message ?: e.javaClass.simpleName}")
        }
        val literal: Long? = if (kind == "int") parseLiteral(keyword) else null
        if (kind == "int" && literal == null) {
            return fail(cap, "整数关键字无法解析: $keyword（支持 123 / -1 / 0x7f0a0001 / #0x1）")
        }

        val excluded = excludePackages.map { it.trim() }.filter { it.isNotEmpty() }
        var classCount = 0
        var methodCount = 0
        var insnCount = 0L
        var matched = 0
        val hits = ArrayList<Hit>(minOf(cap, 256))
        val dexes = ArrayList<Pair<String, DexFile>>()
        val temps = ArrayList<File>()

        try {
            loadDexes(file, dexes, temps, tmpDir)
            if (dexes.isEmpty()) return fail(cap, "未在文件中找到 dex")

            // method_override 需要祖先链，先建一次全类表
            val classMap: Map<String, ClassDef> = if (kind == "method_override") {
                val m = HashMap<String, ClassDef>(1024)
                for ((_, dex) in dexes) for (cd in dex.classes) m[cd.type] = cd
                m
            } else emptyMap()

            fun emit(h: Hit) {
                matched++
                if (matched - 1 >= skip && hits.size < cap) hits.add(h)
            }

            for ((dexName, dex) in dexes) {
                for (cd in dex.classes) {
                    if (isExcluded(cd.type, excluded)) continue
                    classCount++
                    val clsName = typeToName(cd.type)

                    when (kind) {
                        "class" -> if (matcher.matches(clsName)) {
                            emit(Hit(kind, clsName, typeToName(cd.superclass ?: ""),
                                "access=0x${Integer.toHexString(cd.accessFlags)}", dexName))
                        }
                        "string" -> cd.sourceFile?.let { src ->
                            if (src.isNotEmpty() && matcher.matches(src)) {
                                emit(Hit(kind, clsName, ".source", src, dexName))
                            }
                        }
                    }

                    for (f in cd.fields) {
                        when (kind) {
                            "field" -> if (matcher.matches(f.name)) {
                                emit(Hit(kind, clsName, "${readableType(f.type)} ${f.name}",
                                    "声明 · access=0x${Integer.toHexString(f.accessFlags)}", dexName))
                            }
                            "string" -> {
                                val s = (f.initialValue as? StringEncodedValue)?.value
                                if (!s.isNullOrEmpty() && matcher.matches(s)) {
                                    emit(Hit(kind, clsName, f.name, "\"${escape(s)}\"", dexName))
                                }
                            }
                            "int" -> {
                                val lv = literalOf(f.initialValue)
                                if (lv != null && lv == literal) {
                                    emit(Hit(kind, clsName, f.name,
                                        "${formatLiteral(lv)}（字段初值）", dexName))
                                }
                            }
                        }
                    }

                    for (m in cd.methods) {
                        methodCount++
                        when (kind) {
                            "method" -> if (matcher.matches(m.name)) {
                                emit(Hit(kind, clsName, methodSig(m), "声明", dexName))
                            }
                            "method_override" -> if (isOverride(m, classMap)) {
                                emit(Hit(kind, clsName, methodSig(m), "重写了父类/接口方法", dexName))
                            }
                        }
                        val impl = m.implementation ?: continue
                        for (insn in impl.instructions) {
                            insnCount++
                            val h = matchInstruction(kind, insn, matcher, literal, clsName, m, dexName)
                            if (h != null) emit(h)
                        }
                    }
                }
            }

            return Result(
                hits = hits, matched = matched, offset = skip, limit = cap,
                scannedClasses = classCount, scannedMethods = methodCount,
                scannedInstructions = insnCount, dexCount = dexes.size,
                truncated = matched > skip + hits.size,
            )
        } catch (e: Throwable) {
            // dexlib2 越界会抛 Error 而非 Exception，两者一起兜；OOM 不吞
            if (e is OutOfMemoryError) throw e
            return Result(hits, matched, skip, cap, classCount, methodCount, insnCount,
                dexes.size, matched > skip + hits.size,
                "扫描失败: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            dexes.clear()
            // 临时 dex 必须在**扫描结束之后**删：DirectDexFile 会持有文件句柄
            for (t in temps) runCatching { t.delete() }
        }
    }

    private fun fail(cap: Int, msg: String) =
        Result(emptyList(), 0, 0, cap, 0, 0, 0L, 0, false, msg)

    // ---------------------------------------------------------------- 指令匹配

    private fun matchInstruction(
        kind: String,
        insn: Instruction,
        m: KeywordMatcher,
        literal: Long?,
        clsName: String,
        method: Method,
        dexName: String,
    ): Hit? {
        val op = insn.opcode.name
        val sig = methodSig(method)

        when (kind) {
            "code" -> {
                val line = smaliLine(insn)
                return if (line.isNotEmpty() && m.matches(line)) {
                    Hit(kind, clsName, sig, line, dexName)
                } else null
            }
            "string" -> {
                val s = ((insn as? ReferenceInstruction)?.reference as? StringReference)?.string
                    ?: return null
                return if (s.isNotEmpty() && m.matches(s)) {
                    Hit(kind, clsName, sig, "\"${escape(s)}\"", dexName)
                } else null
            }
            "int" -> {
                val v = literalOf(insn) ?: return null
                return if (v == literal) Hit(kind, clsName, sig, "$op ${formatLiteral(v)}", dexName) else null
            }
            "new_instance" -> {
                if (op != "NEW_INSTANCE") return null
                val t = ((insn as? ReferenceInstruction)?.reference as? TypeReference)?.type ?: return null
                return Hit(kind, clsName, sig, "new ${typeToName(t)}", dexName)
            }
            "class_use" -> {
                if (op == "NEW_INSTANCE") return null   // 归 new_instance，避免重复
                val t = ((insn as? ReferenceInstruction)?.reference as? TypeReference)?.type ?: return null
                return Hit(kind, clsName, sig, "${op.lowercase().replace('_', '-')} ${typeToName(t)}", dexName)
            }
            "method_call" -> {
                if (!op.startsWith("INVOKE_")) return null
                val r = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: return null
                return Hit(kind, clsName, sig,
                    "${invokeKind(op)} ${typeToName(r.definingClass)}->${r.name}${referenceProto(r)}", dexName)
            }
            "field_get", "field_put", "field_any" -> {
                val isGet = op.startsWith("IGET_") || op.startsWith("SGET_")
                val isPut = op.startsWith("IPUT_") || op.startsWith("SPUT_")
                if (kind == "field_get" && !isGet) return null
                if (kind == "field_put" && !isPut) return null
                val r = (insn as? ReferenceInstruction)?.reference as? FieldReference ?: return null
                return Hit(kind, clsName, sig,
                    "${op.lowercase().replace('_', '-')} ${typeToName(r.definingClass)}->" +
                        "${r.name}:${readableType(r.type)}", dexName)
            }
        }
        return null
    }

    // ---------------------------------------------------------------- 可单测的纯函数

    internal fun normalizeType(raw: String): String {
        val t = raw.trim().lowercase().replace('-', '_')
        if (t.isEmpty()) return "class"
        TYPE_LABELS_ZH.entries.firstOrNull { it.value == raw.trim() }?.let { return it.key }
        return when (t) {
            "classname", "class_name", "type" -> "class"
            "methodname", "method_name" -> "method"
            "fieldname", "field_name" -> "field"
            "str", "strings" -> "string"
            "int_literal", "integer", "number" -> "int"
            "src", "source", "smali" -> "code"
            "instantiate", "new", "newinstance" -> "new_instance"
            "get", "field_read", "read" -> "field_get"
            "put", "field_write", "write" -> "field_put"
            "field_ref", "fieldref" -> "field_any"
            "call", "invoke", "method_ref" -> "method_call"
            "override", "overrides" -> "method_override"
            "use", "class_ref", "usages" -> "class_use"
            else -> t
        }
    }

    /** 十进制 / 负数 / 0x 十六进制 / smali 的 #0x。 */
    internal fun parseLiteral(raw: String): Long? {
        val s = raw.trim().replace("_", "").replace(" ", "")
        if (s.isEmpty()) return null
        val neg = s.startsWith("-")
        val body = if (neg) s.substring(1) else s
        val v = when {
            body.startsWith("0x") || body.startsWith("0X") -> body.substring(2).toLongOrNull(16)
            body.startsWith("#") -> body.substring(1).removePrefix("0x").removePrefix("0X").toLongOrNull(16)
            else -> body.toLongOrNull(10)
        } ?: return null
        return if (neg) -v else v
    }

    internal fun literalOf(v: EncodedValue?): Long? = when (v) {
        is IntEncodedValue -> v.value.toLong()
        is LongEncodedValue -> v.value
        is ShortEncodedValue -> v.value.toLong()
        is ByteEncodedValue -> v.value.toLong()
        is CharEncodedValue -> v.value.code.toLong()
        else -> null
    }

    internal fun literalOf(insn: Instruction): Long? = when (insn) {
        is WideLiteralInstruction -> insn.wideLiteral
        is NarrowLiteralInstruction -> insn.narrowLiteral.toLong()
        else -> null
    }

    /**
     * 一条指令的近似 smali 行（只拼操作码 + 引用 + 字面量，不含寄存器）。
     * 故意不做完整反汇编：全量 baksmali 太慢，而"代码"搜索只需要能搜到。
     */
    internal fun smaliLine(insn: Instruction): String {
        val sb = StringBuilder(insn.opcode.name.lowercase().replace('_', '-'))
        when (val ref = (insn as? ReferenceInstruction)?.reference) {
            is StringReference -> sb.append(" \"").append(escape(ref.string)).append('"')
            is MethodReference -> sb.append(' ').append(typeToName(ref.definingClass))
                .append("->").append(ref.name).append(referenceProto(ref))
            is FieldReference -> sb.append(' ').append(typeToName(ref.definingClass))
                .append("->").append(ref.name).append(':').append(readableType(ref.type))
            is TypeReference -> sb.append(' ').append(typeToName(ref.type))
            else -> {}
        }
        literalOf(insn)?.let { sb.append(' ').append(formatLiteral(it)) }
        return sb.toString()
    }

    internal fun isExcluded(classDescriptor: String, packages: List<String>): Boolean {
        if (packages.isEmpty()) return false
        val dotted = typeToName(classDescriptor)
        return packages.any { p ->
            val pkg = p.replace('/', '.').removeSuffix(".*").removeSuffix(".").trim('.')
            pkg.isNotEmpty() && (dotted == pkg || dotted.startsWith("$pkg."))
        }
    }

    internal fun isDexEntryName(name: String): Boolean = DEX_ENTRY.matches(name)

    /** classes.dex → 1，classes2.dex → 2，保证按数字而非字典序。 */
    internal fun dexEntrySortKey(name: String): Int {
        val m = DEX_ENTRY_KEY.find(name) ?: return Int.MAX_VALUE
        val n = m.groupValues[1]
        return if (n.isEmpty()) 1 else (n.toIntOrNull() ?: Int.MAX_VALUE)
    }

    internal fun typeToName(t: String?): String {
        if (t == null || t.isEmpty()) return ""
        var s = t
        while (s.startsWith("[")) s = s.substring(1)
        return if (s.startsWith("L") && s.endsWith(";")) s.substring(1, s.length - 1).replace('/', '.') else s
    }

    internal fun readableType(t: String?): String {
        if (t == null) return "?"
        var dim = 0
        var s = t
        while (s.startsWith("[")) { dim++; s = s.substring(1) }
        val base = when (s) {
            "V" -> "void"; "Z" -> "boolean"; "B" -> "byte"; "S" -> "short"
            "C" -> "char"; "I" -> "int"; "J" -> "long"; "F" -> "float"; "D" -> "double"
            else -> if (s.startsWith("L") && s.endsWith(";")) s.substring(1, s.length - 1).replace('/', '.') else s
        }
        return if (dim == 0) base else base + "[]".repeat(dim)
    }

    internal fun methodSig(m: Method): String =
        "${m.name}(${m.parameterTypes.joinToString("")})${m.returnType}"

    internal fun formatLiteral(v: Long): String =
        if (v < 0) "$v" else if (v <= 0xFFFF) "$v(0x${v.toString(16)})" else "$v"

    internal fun escape(s: String): String {
        val sb = StringBuilder(minOf(s.length, 400))
        for (c in s) {
            if (sb.length >= 400) { sb.append('…'); break }
            when (c) {
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '"' -> sb.append("\\\"")
                else -> if (c.code < 0x20) sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else sb.append(c)
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- 内部辅助

    private fun isOverride(m: Method, classMap: Map<String, ClassDef>): Boolean {
        val name = m.name
        if (name == "<init>" || name == "<clinit>") return false
        val ret = m.returnType
        val params = m.parameterTypes.joinToString("")

        fun ClassDef.declaresIt(): Boolean = methods.any {
            it.name == name && it.returnType == ret && it.parameterTypes.joinToString("") == params
        }

        // 直接接口
        classMap[m.definingClass]?.let { own ->
            for (itf in own.interfaces) {
                val icd = classMap[itf] ?: continue
                if (icd.declaresIt()) return true
            }
        }
        // 祖先链（含祖先的接口）
        var cur = m.definingClass
        var guard = 0
        var parent = classMap[cur]?.superclass
        while (parent != null && guard++ < 32) {
            val pcd = classMap[parent]
            if (pcd == null) break
            if (pcd.declaresIt()) return true
            for (itf in pcd.interfaces) {
                if (classMap[itf]?.declaresIt() == true) return true
            }
            cur = parent
            parent = classMap[cur]?.superclass
        }
        return false
    }

    private fun referenceProto(r: MethodReference): String =
        "(${r.parameterTypes.joinToString("")})${r.returnType}"

    private fun invokeKind(op: String): String = op.lowercase().replace('_', '-')

    private fun loadDexes(
        file: File,
        out: MutableList<Pair<String, DexFile>>,
        temps: MutableList<File>,
        tmpDir: File?,
    ) {
        val name = file.name.lowercase()
        if (name.endsWith(".apk") || name.endsWith(".zip") || name.endsWith(".jar")) {
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { isDexEntryName(it.name.substringAfterLast('/')) }
                    .sortedBy { dexEntrySortKey(it.name) }
                    .toList()
                if (entries.isEmpty()) return
                val root = tmpDir ?: File(System.getProperty("java.io.tmpdir") ?: ".", "dexindex")
                root.mkdirs()
                for (e in entries) {
                    val tmp = File(root, "dexidx_${e.name.replace('/', '_')}_${System.nanoTime()}")
                    zip.getInputStream(e).use { input ->
                        tmp.outputStream().use { o -> input.copyTo(o, 1 shl 16) }
                    }
                    temps.add(tmp)
                    out.add(e.name to DexFileFactory.loadDexFile(tmp, Opcodes.getDefault()))
                }
            }
        } else {
            out.add(file.name to DexFileFactory.loadDexFile(file, Opcodes.getDefault()))
        }
    }

    /**
     * 关键字匹配器：正则 / 大小写敏感 / 子串，正则优先。
     * 正则在构造时编译一次，避免在几十万条指令的循环里反复编译。
     */
    internal class KeywordMatcher(keyword: String, val caseSensitive: Boolean, regex: Boolean) {
        private val needle = keyword
        private val lowered = keyword.lowercase()
        private val re: Regex? = if (regex) {
            Regex(keyword, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
        } else null

        fun matches(hay: String): Boolean {
            if (hay.isEmpty()) return false
            re?.let { return it.containsMatchIn(hay) }
            if (needle.isEmpty()) return false
            return if (caseSensitive) hay.contains(needle) else hay.contains(lowered, true)
        }
    }
}
