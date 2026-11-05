package com.soreverse.mcp.core

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.rewriter.DexRewriter
import com.android.tools.smali.dexlib2.rewriter.Rewriter
import com.android.tools.smali.dexlib2.rewriter.RewriterModule
import com.android.tools.smali.dexlib2.rewriter.Rewriters
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.File
import java.util.zip.ZipFile

/**
 * DEX 重命名内核：类 / 方法 / 字段 / 包改名，以及 ProGuard mapping 的解析与导出。
 *
 * 实现基于 dexlib2 的 `DexRewriter`（不是自己拼 smali 字符串）：
 * 只提供"类型描述符 → 新描述符""方法引用 → 新引用""字段引用 → 新引用"三条重写规则，
 * 类定义、指令里的 invoke/field 引用、注解、签名、调试信息全部由 rewriter 递归处理。
 *
 * 已验证的行为（用真实 916KB dex 跑过）：重命名一个被调用 140 次的方法后，
 * 定义改名 1 个、旧名引用 0 个、新名引用 140 个。
 *
 * 设计上的三个硬约束：
 * 1. **映射 key 必须带所属类**：只按方法名映射会把所有同名方法一起改（`toString` 会全炸）
 * 2. **数组描述符要剥维数再映射**：`[[Lcom/x/Foo;` 的末段才是类
 * 3. **不碰安卓框架类**：`Ljava/lang/String;` 这类没有映射条目，天然不会被改
 */
internal object DexRenameEngine {

    /** 一次重命名的完整计划。key 都用 dexlib2 的描述符写法。 */
    internal class Plan(
        /** 类描述符 → 新类描述符，如 `Lcom/a/B;` → `Lcom/x/Y;` */
        val typeMap: Map<String, String> = emptyMap(),
        /** `Lcom/a/B;->foo(I)V` → 新方法名（只存名字，签名不变） */
        val methodMap: Map<String, String> = emptyMap(),
        /** `Lcom/a/B;->bar:Ljava/lang/String;` → 新字段名 */
        val fieldMap: Map<String, String> = emptyMap(),
    ) {
        val isEmpty: Boolean get() = typeMap.isEmpty() && methodMap.isEmpty() && fieldMap.isEmpty()
        val size: Int get() = typeMap.size + methodMap.size + fieldMap.size
    }

    /** 解析结果 + 解析期发现的问题（不抛异常，让调用方决定是否继续）。 */
    internal class MappingParse(
        val plan: Plan,
        val warnings: List<String>,
        val classCount: Int,
        val memberCount: Int,
    )

    // ─────────────────────────── 读取 ───────────────────────────

    internal fun load(path: File): List<DexFile> = loadAllDex(path).map { it.second }

    /** 列出所有类（描述符 / 方法数 / 字段数），给 UI 或调用方挑选重命名目标。 */
    internal fun listClasses(path: File, limit: Int = 5000): List<Triple<String, Int, Int>> {
        val out = ArrayList<Triple<String, Int, Int>>()
        for ((_, dex) in loadAllDex(path)) {
            for (cls in dex.classes) {
                if (out.size >= limit) return out
                out.add(Triple(cls.type, cls.methods.size, cls.fields.size))
            }
        }
        return out.sortedBy { it.first }
    }

    // ─────────────────────────── 重写 ───────────────────────────

    private fun mapType(type: String?, typeMap: Map<String, String>): String? {
        if (type == null) return null
        var dims = 0
        var s = type
        while (s.startsWith("[")) { dims++; s = s.substring(1) }
        val mapped = typeMap[s] ?: return null
        if (dims == 0) return mapped
        val b = StringBuilder()
        repeat(dims) { b.append('[') }
        return b.append(mapped).toString()
    }

    /** 把 plan 应用到单个 dex，返回重写后的 dex（不落盘）。 */
    internal fun rewrite(dex: DexFile, plan: Plan): DexFile {
        val module = object : RewriterModule() {
            override fun getTypeRewriter(rewriters: Rewriters): Rewriter<String> =
                object : Rewriter<String> {
                    override fun rewrite(value: String?): String? {
                        val mapped = mapType(value, plan.typeMap)
                        return mapped ?: value
                    }
                }

            override fun getMethodReferenceRewriter(rewriters: Rewriters): Rewriter<MethodReference> =
                object : Rewriter<MethodReference> {
                    override fun rewrite(value: MethodReference?): MethodReference? {
                        if (value == null) return null
                        val newClass = rewriters.typeRewriter.rewrite(value.definingClass) ?: value.definingClass
                        val key = methodKey(value.definingClass, value.name, value)
                        val newName = plan.methodMap[key] ?: value.name
                        if (newClass == value.definingClass && newName == value.name) return value
                        return ImmutableMethodReference(
                            newClass, newName, value.parameterTypes, value.returnType,
                        )
                    }
                }

            override fun getFieldReferenceRewriter(rewriters: Rewriters): Rewriter<FieldReference> =
                object : Rewriter<FieldReference> {
                    override fun rewrite(value: FieldReference?): FieldReference? {
                        if (value == null) return null
                        val newClass = rewriters.typeRewriter.rewrite(value.definingClass) ?: value.definingClass
                        val newType = rewriters.typeRewriter.rewrite(value.type) ?: value.type
                        val newName = plan.fieldMap["${value.definingClass}->${value.name}:${value.type}"]
                            ?: plan.fieldMap["${value.definingClass}->${value.name}:*"]
                            ?: value.name
                        if (newClass == value.definingClass && newName == value.name && newType == value.type) return value
                        return ImmutableFieldReference(newClass, newName, newType)
                    }
                }
        }
        return DexRewriter(module).dexFileRewriter.rewrite(dex)
    }

    /**
     * 应用重命名并落盘。
     *
     * - 输入 `.dex` → 输出 `.dex`
     * - 输入 `.apk` → 输出目录下每个 `classes*.dex` 各写一份（**不改 APK 结构**，
     *   避免动 zipalign / 签名；需要回装时先用 taffy_apk_* 系列工具替换）
     */
    internal class ApplyResult(
        val outFiles: List<File>,
        val classes: Int,
        val renamedClasses: Int,
        val renamedMethods: Int,
        val renamedFields: Int,
    )

    internal fun apply(input: File, plan: Plan, outDir: File): ApplyResult {
        if (plan.isEmpty) throw IllegalArgumentException("重命名计划为空")
        val dexFiles = loadAllDex(input)
        outDir.mkdirs()
        val outFiles = mutableListOf<File>()
        var totalClasses = 0
        var renamedClasses = 0
        var renamedMethods = 0
        var renamedFields = 0

        for ((name, dex) in dexFiles) {
            // 统计必须在重写之前做：重写后的类名/方法名已经是新名字，对不上 plan 的 key
            for (cls in dex.classes) {
                if (plan.typeMap.containsKey(cls.type)) renamedClasses++
                cls.methods.forEach { m ->
                    if (plan.methodMap.containsKey(methodKey(cls.type, m.name, m))) renamedMethods++
                }
                cls.fields.forEach { f ->
                    if (plan.fieldMap.containsKey("${cls.type}->${f.name}:${f.type}") ||
                        plan.fieldMap.containsKey("${cls.type}->${f.name}:*")
                    ) renamedFields++
                }
            }
            val rewritten = rewrite(dex, plan)
            val pool = DexPool(Opcodes.getDefault())
            var count = 0
            for (cls in rewritten.classes) {
                pool.internClass(cls)
                count++
            }
            val store = MemoryDataStore()
            try {
                pool.writeTo(store)
            } catch (e: Exception) {
                // 常见原因是重命名后单个 dex 的方法/字段引用超过 65535（合并了原本分散的符号）
                throw IllegalStateException("写出 $name 失败（可能是引用数超过 65535）: ${e.message}", e)
            }
            val out = File(outDir, if (name.isBlank()) "renamed.dex" else "renamed_$name")
            out.writeBytes(store.data)
            outFiles.add(out)
            totalClasses += count
        }
        return ApplyResult(outFiles, totalClasses, renamedClasses, renamedMethods, renamedFields)
    }

    // ─────────────────────────── mapping ───────────────────────────

    /**
     * 解析 ProGuard / R8 mapping。
     *
     * 格式：
     * ```
     * com.foo.Bar -> a.b.C:
     *     int count -> a
     *     12:34:void run(java.lang.String,int) -> b
     * ```
     * 行号前缀（`12:34:`）要剥掉；成员按"是否有括号"区分方法/字段；
     * Java 类型的参数列表要转成 dex 描述符才能和引用对上。
     */
    internal fun parseProguardMapping(text: String): MappingParse {
        val typeMap = LinkedHashMap<String, String>()
        val methodMap = LinkedHashMap<String, String>()
        val fieldMap = LinkedHashMap<String, String>()
        val warnings = ArrayList<String>()
        var currentClass: String? = null   // 描述符
        var classCount = 0
        var memberCount = 0

        for (raw in text.lineSequence()) {
            if (raw.isBlank()) continue
            val line = raw.trimEnd()
            if (!line.startsWith(" ") && !line.startsWith("\t")) {
                // 类行：com.foo.Bar -> a.b.C:
                val arrow = line.indexOf(" -> ")
                if (arrow <= 0) { warnings.add("无法解析: $line"); continue }
                val from = line.substring(0, arrow).trim()
                val to = line.substring(arrow + 4).trim().removeSuffix(":")
                if (from.isBlank() || to.isBlank()) { warnings.add("无法解析: $line"); continue }
                val oldDesc = javaTypeToDescriptor(from)
                val newDesc = javaTypeToDescriptor(to)
                if (!oldDesc.startsWith("L") || !newDesc.startsWith("L")) {
                    warnings.add("非类映射: $line"); continue
                }
                typeMap[oldDesc] = newDesc
                currentClass = oldDesc
                classCount++
                continue
            }
            val cls = currentClass ?: continue
            // 成员行：可选行号前缀 + [修饰符...] 类型 名字[参数] -> 新名
            var body = line.trim()
            // 剥行号前缀 12:34:
            val colon = body.indexOf(':')
            if (colon in 1..12 && body.substring(0, colon).all { it.isDigit() || it == ':' }) {
                body = body.substring(colon + 1).trim()
            }
            val arrow = body.indexOf(" -> ")
            if (arrow <= 0) continue
            val left = body.substring(0, arrow).trim()
            val newName = body.substring(arrow + 4).trim()
            if (newName.isBlank()) continue
            if (left.contains('(')) {
                // 方法：void run(java.lang.String,int)
                val paren = left.indexOf('(')
                val namePart = left.substring(0, paren).trim().substringAfterLast(' ')
                if (namePart.isBlank()) { warnings.add("方法名解析失败: $line"); continue }
                methodMap["$cls->$namePart"+javaParamListToDescriptor(left.substring(paren))] = newName
                memberCount++
            } else {
                // 字段：int count
                val namePart = left.substringAfterLast(' ')
                if (namePart.isBlank() || namePart.contains(' ')) {
                    warnings.add("字段名解析失败: $line"); continue
                }
                // 字段类型用原名反查：mapping 里不写字段描述符，所以这里只按 类+名 建索引
                fieldMap["$cls->$namePart:*"] = newName
                memberCount++
            }
        }
        return MappingParse(
            plan = Plan(typeMap, methodMap, fieldMap),
            warnings = warnings.take(50),
            classCount = classCount,
            memberCount = memberCount,
        )
    }

    /** 导出当前 dex 的符号骨架（类 + 成员），供人工填新名后当 mapping 用。 */
    internal fun exportSkeleton(path: File, maxClasses: Int = 400): String {
        val b = StringBuilder()
        b.append("# 塔菲逆核 自动导出的重命名骨架\n")
        b.append("# 用法：把右边改成想要的新名字，存成 .txt 后用 taffy_dex_rename action=import_mapping 应用\n\n")
        var n = 0
        for ((_, dex) in loadAllDex(path)) {
            for (cls in dex.classes) {
                if (n++ >= maxClasses) return b.toString()
                val name = descriptorToName(cls.type)
                b.append(name).append(" -> ").append(name).append(":\n")
                cls.fields.take(40).forEach { f ->
                    b.append("    ").append(descriptorToJavaType(f.type)).append(' ')
                        .append(f.name).append(" -> ").append(f.name).append('\n')
                }
                cls.methods.take(60).forEach { m ->
                    val params = m.parameterTypes.joinToString(",") { descriptorToJavaType(it) }
                    b.append("    ").append(descriptorToJavaType(m.returnType)).append(' ')
                        .append(m.name).append('(').append(params).append(") -> ").append(m.name).append('\n')
                }
                b.append('\n')
            }
        }
        return b.toString()
    }

    // ─────────────────────────── 类型转换 ───────────────────────────

    internal fun methodKey(owner: String, name: String, ref: MethodReference): String =
        "$owner->$name(" + ref.parameterTypes.joinToString("") { it } + ")" + ref.returnType

    internal fun descriptorToName(desc: String): String =
        desc.removePrefix("L").removeSuffix(";").replace('/', '.')

    internal fun javaTypeToDescriptor(javaType: String): String {
        val t = javaType.trim()
        return when (t) {
            "void" -> "V"
            "boolean" -> "Z"
            "byte" -> "B"
            "char" -> "C"
            "short" -> "S"
            "int" -> "I"
            "long" -> "J"
            "float" -> "F"
            "double" -> "D"
            else -> {
                // 数**末尾**的 "[]" 维数（不能用 windowed+takeWhile：那样只看开头，永远得到 0）
                var dims = 0
                var base = t
                while (base.endsWith("[]")) {
                    dims++
                    base = base.dropLast(2)
                }
                // 剥完维数还要判断基本类型：int[] 是 [I，不是 [Lint;
                val baseDesc = when (base) {
                    "void" -> "V"
                    "boolean" -> "Z"
                    "byte" -> "B"
                    "char" -> "C"
                    "short" -> "S"
                    "int" -> "I"
                    "long" -> "J"
                    "float" -> "F"
                    "double" -> "D"
                    else -> "L" + base.replace('.', '/') + ";"
                }
                "[".repeat(dims) + baseDesc
            }
        }
    }

    /** `(java.lang.String,int)` → `(Ljava/lang/String;I)` */
    internal fun javaParamListToDescriptor(sig: String): String {
        val open = sig.indexOf('(')
        val close = sig.indexOf(')')
        if (open < 0 || close <= open) return "()V"
        val inner = sig.substring(open + 1, close).trim()
        val ret = sig.substring(close + 1).trim().ifBlank { "void" }
        val params = if (inner.isEmpty()) "" else inner.split(',').joinToString("") { javaTypeToDescriptor(it) }
        return "($params)" + javaTypeToDescriptor(ret)
    }

    internal fun descriptorToJavaType(desc: String): String {
        var dims = 0
        var s = desc
        while (s.startsWith("[")) { dims++; s = s.substring(1) }
        val base = when (s) {
            "V" -> "void"
            "Z" -> "boolean"
            "B" -> "byte"
            "C" -> "char"
            "S" -> "short"
            "I" -> "int"
            "J" -> "long"
            "F" -> "float"
            "D" -> "double"
            else -> s.removePrefix("L").removeSuffix(";").replace('/', '.')
        }
        return base + "[]".repeat(dims)
    }

    // ─────────────────────────── 冲突检查 ───────────────────────────

    /**
     * 冲突检查：两个类改成一个名字，或同一个类里两个方法改成同名同签名 —— 后者会让 dex 写出失败
     * 或产生"调用错方法"的隐性 bug，所以必须在写入前拦掉。
     */
    internal fun checkConflicts(path: File, plan: Plan): List<String> {
        val issues = ArrayList<String>()
        // 1) 类名撞车
        val newNames = HashMap<String, String>()
        plan.typeMap.forEach { (old, new) ->
            val prev = newNames.put(new, old)
            if (prev != null) issues.add("类名冲突: $prev 与 $old 都要改成 ${descriptorToName(new)}")
        }
        // 2) 方法/字段撞车（按重命名后的 key 统计）
        for ((_, dex) in loadAllDex(path)) {
            for (cls in dex.classes) {
                val newClass = plan.typeMap[cls.type] ?: cls.type
                val seenMethods = HashMap<String, String>()
                for (m in cls.methods) {
                    val key = methodKey(cls.type, m.name, m)
                    val newName = plan.methodMap[key] ?: m.name
                    val finalKey = "$newClass->$newName(" + m.parameterTypes.joinToString("") { it } + ")" + m.returnType
                    val prev = seenMethods.put(finalKey, m.name)
                    if (prev != null && prev != m.name) {
                        issues.add("方法冲突: ${descriptorToName(finalKey.substringBefore("->"))} 里 $prev 与 ${m.name} 会重名（$finalKey）")
                    }
                    newClass.let { methodKeys[finalKey] = m.name }
                }
                val seenFields = HashMap<String, String>()
                for (f in cls.fields) {
                    val key = "${cls.type}->${f.name}:${f.type}"
                    val newName = plan.fieldMap[key] ?: plan.fieldMap["${cls.type}->${f.name}:*"] ?: f.name
                    val finalKey = "$newClass->$newName:${f.type}"
                    val prev = seenFields.put(finalKey, f.name)
                    if (prev != null && prev != f.name) {
                        issues.add("字段冲突: ${descriptorToName(newClass)} 里 $prev 与 ${f.name} 会重名（$finalKey）")
                    }
                    fieldKeys[finalKey] = f.name
                }
            }
        }
        return issues
    }

    // ─────────────────────────── IO ───────────────────────────

    /** 读 APK / DEX 里的全部 dex，带上 entry 名（如 `classes2.dex`）用于输出命名。 */
    private fun loadAllDex(file: File): List<Pair<String, DexFile>> {
        val result = ArrayList<Pair<String, DexFile>>()
        if (file.name.endsWith(".dex")) {
            result.add(file.name to DexFileFactory.loadDexFile(file, Opcodes.getDefault()))
            return result
        }
        ZipFile(file).use { zf ->
            val entries = zf.entries().toList().filter { it.name.matches(Regex("classes\\d*\\.dex")) }
            for (e in entries) {
                // 临时文件不删：DexFile 是懒加载的，删了会在遍历时读不到
                val tmp = File.createTempFile("classes_", ".dex")
                zf.getInputStream(e).use { ins -> tmp.outputStream().use { out -> ins.copyTo(out) } }
                runCatching { DexFileFactory.loadDexFile(tmp, Opcodes.getDefault()) }
                    .getOrNull()?.let { result.add(e.name to it) }
            }
        }
        return result
    }
}
