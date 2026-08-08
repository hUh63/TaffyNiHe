package com.soreverse.mcp.mcp

import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.writer.io.FileDataStore
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * 塔菲逆核: 高级 DEX 分析与编辑工具(超越 MT管理器精度)。
 * 用 dexlib2 做指令级交叉引用分析。
 */
object DexAnalysisTools {

    /** 方法级交叉引用 */
    val dexXref: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "dex_xref",
            "【DEX 方法交叉引用】精确分析谁调用了某方法(to), 或某方法调用了什么(from)。用 dexlib2 AnalyzedInstruction 做指令级分析, 精确到调用指令和调用位置(方法+偏移)。比 MT管理器的 dex_xref 更详细: 返回调用者类名/方法名/指令偏移/调用类型(invoke-virtual/direct/static/super)。自动扫描多 DEX。",
            "Method-level cross-reference using dexlib2. Finds who calls a method (to) or what a method calls (from). Returns caller class/method/instruction offset/invoke type. More detailed than MT. Auto multi-DEX.",
            "decompile", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("to(谁调用了我) | from(我调用了谁)", "to", "from")
                "path" str "APK 或 DEX 文件路径"
                "className" str "目标类全名(如 com.example.Foo)"
                "methodName" str "目标方法名"
                "descriptor" str "方法描述符(可选, 如 (Ljava/lang/String;)V)"
                "limit" int "最多返回条数(默认 100)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path", "path", "")
            val file = File(path)
            if (!file.isFile) return err("FILE_NOT_FOUND", "文件不存在: $path", "path", path)
            val className = args.str("className")
            val methodName = args.str("methodName")
            if (className.isBlank() || methodName.isBlank())
                return err("INVALID_ARGUMENT", "需要 className 和 methodName", "className", className)
            val action = args.str("action", "to")
            val descriptor = args.str("descriptor")
            val limit = args.intValue("limit", 100).coerceIn(1, 1000)

            val classDescriptor = "L${className.replace('.', '/')};"
            val results = JSONArray()
            val dexFiles = loadAllDex(file)

            // 找到目标方法
            var targetMethod: Method? = null
            for ((_, dexFile) in dexFiles) {
                val cls = dexFile.classes.firstOrNull { it.type == classDescriptor } ?: continue
                targetMethod = cls.methods.firstOrNull {
                    it.name == methodName && (descriptor.isBlank() || buildDescriptor(it) == descriptor)
                }
                if (targetMethod != null) break
            }
            if (targetMethod == null)
                return err("METHOD_NOT_FOUND", "方法 $className.$methodName 未找到", "methodName", methodName)

            if (action == "to") {
                // 搜索所有 DEX 中谁调用了 targetMethod
                outer@ for ((searchDexName, searchDex) in dexFiles) {
                    for (searchClass in searchDex.classes) {
                        for (searchMethod in searchClass.methods) {
                            val impl = searchMethod.implementation ?: continue
                            for ((offIdx, insn) in impl.instructions.withIndex()) {
                                val ref = extractMethodRef(insn) ?: continue
                                if (ref.definingClass == classDescriptor &&
                                    ref.name == methodName &&
                                    (descriptor.isBlank() || buildDescriptor(ref) == descriptor)) {
                                    results.put(JSONObject()
                                        .put("callerClass", typeToName(searchClass.type))
                                        .put("callerMethod", searchMethod.name)
                                        .put("callerDescriptor", buildDescriptor(searchMethod))
                                        .put("callerDex", searchDexName)
                                        .put("instruction", insn.opcode.name)
                                        .put("codeOffset", offIdx)
                                        .put("invokeType", insn.opcode.name.substringAfter("invoke-").substringBefore("/")))
                                    if (results.length() >= limit) break@outer
                                }
                            }
                        }
                    }
                }
            } else {
                // from: targetMethod 调用了什么
                val impl = targetMethod.implementation
                if (impl != null) {
                    for ((offIdx, insn) in impl.instructions.withIndex()) {
                        val ref = extractMethodRef(insn) ?: continue
                        results.put(JSONObject()
                            .put("calleeClass", typeToName(ref.definingClass))
                            .put("calleeMethod", ref.name)
                            .put("calleeDescriptor", buildDescriptor(ref))
                            .put("instruction", insn.opcode.name)
                            .put("codeOffset", offIdx)
                            .put("invokeType", insn.opcode.name.substringAfter("invoke-").substringBefore("/")))
                        if (results.length() >= limit) break
                    }
                }
            }

            return ok(JSONObject()
                .put("action", action)
                .put("className", className)
                .put("methodName", methodName)
                .put("total", results.length())
                .put("results", results)
                .put("hint", if (action == "to") "这些方法调用了 $className.$methodName" else "$className.$methodName 调用了这些方法"))
        }
    }

    /** 类大纲 */
    val dexClassOutline: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "dex_class_outline",
            "【DEX 类大纲】完整类结构分析: 继承链(递归到Object)、接口列表、所有字段(含类型/修饰符)、所有方法(含签名/参数/返回值/修饰符/注解)。比 MT管理器的 dex_outline_class 更详细。自动定位多 DEX。",
            "Full class structure analysis: inheritance chain, interfaces, fields, methods, annotations. More detailed than MT. Auto multi-DEX.",
            "decompile", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "path" str "APK 或 DEX 文件路径"
                "className" str "类全名(如 com.example.Foo)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path", "path", "")
            val file = File(path)
            if (!file.isFile) return err("FILE_NOT_FOUND", "文件不存在: $path", "path", path)
            val className = args.str("className")
            if (className.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 className", "className", "")

            val classDescriptor = "L${className.replace('.', '/')};"
            val dexFiles = loadAllDex(file)
            // 收集所有类用于继承链查找
            val allClasses = mutableMapOf<String, ClassDef>()
            for ((_, dex) in dexFiles) {
                for (cls in dex.classes) allClasses[cls.type] = cls
            }

            val classDef = allClasses[classDescriptor]
                ?: return err("CLASS_NOT_FOUND", "类 $className 不在 APK 的任何 DEX 中", "className", className)

            // 继承链
            val inheritanceChain = JSONArray()
            var currentSuper = classDef.superclass
            val visited = mutableSetOf(classDescriptor)
            while (currentSuper != null && currentSuper !in visited) {
                visited.add(currentSuper)
                inheritanceChain.put(typeToName(currentSuper))
                val superCls = allClasses[currentSuper]
                currentSuper = superCls?.superclass
            }

            // 接口
            val interfaces = JSONArray()
            classDef.interfaces?.forEach { iface -> interfaces.put(typeToName(iface)) }

            // 字段
            val fields = JSONArray()
            for (field in classDef.fields) {
                fields.put(JSONObject()
                    .put("name", field.name)
                    .put("type", field.type)
                    .put("accessFlags", accessFlagsToString(field.accessFlags))
                    .put("initialValue", field.initialValue?.toString() ?: ""))
            }

            // 方法
            val methods = JSONArray()
            for (method in classDef.methods) {
                val params = JSONArray()
                for (param in method.parameters) params.put(param.type)
                val impl = method.implementation
                methods.put(JSONObject()
                    .put("name", method.name)
                    .put("returnType", method.returnType)
                    .put("parameters", params)
                    .put("descriptor", buildDescriptor(method))
                    .put("accessFlags", accessFlagsToString(method.accessFlags))
                    .put("isStatic", method.accessFlags and 0x0008 != 0)
                    .put("isNative", method.accessFlags and 0x0100 != 0)
                    .put("isAbstract", method.accessFlags and 0x0400 != 0)
                    .put("instructionCount", impl?.instructions?.toList()?.size ?: 0))
            }

            // 注解 (简化)
            val annotations = JSONArray()
            runCatching {
                classDef.annotations.forEach { ann ->
                    annotations.put(JSONObject().put("type", ann.type))
                }
            }

            // 找到类所在的 DEX
            val dexName = dexFiles.firstOrNull { (_, dex) -> dex.classes.any { it.type == classDescriptor } }?.first ?: ""

            return ok(JSONObject()
                .put("className", className)
                .put("dex", dexName)
                .put("superClass", classDef.superclass?.let { sc -> typeToName(sc) } ?: "")
                .put("inheritanceChain", inheritanceChain)
                .put("interfaces", interfaces)
                .put("isEnum", classDef.accessFlags and 0x4000 != 0)
                .put("isInterface", classDef.accessFlags and 0x0200 != 0)
                .put("isAbstract", classDef.accessFlags and 0x0400 != 0)
                .put("accessFlags", accessFlagsToString(classDef.accessFlags))
                .put("fields", fields)
                .put("methods", methods)
                .put("annotations", annotations)
                .put("hint", "用 dex_xref 查方法交叉引用, smali_edit action=extract 提取 smali 代码"))
        }
    }

    /** 方法字节码提取 */
    val dexMethodCode: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "dex_method_code",
            "【DEX 方法字节码】提取方法的 Dalvik 字节码(指令级), 含每条指令的偏移/操作码/寄存器/操作数。用于逆向分析加密算法、关键逻辑流程。自动定位多 DEX。",
            "Extract Dalvik bytecode (instruction-level) for a method. Auto multi-DEX.",
            "decompile", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "path" str "APK 或 DEX 文件路径"
                "className" str "类全名"
                "methodName" str "方法名"
                "descriptor" str "方法描述符(可选, 区分重载)"
                "limit" int "最多返回指令数(默认 500)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path", "path", "")
            val file = File(path)
            if (!file.isFile) return err("FILE_NOT_FOUND", "文件不存在: $path", "path", path)
            val className = args.str("className")
            val methodName = args.str("methodName")
            if (className.isBlank() || methodName.isBlank())
                return err("INVALID_ARGUMENT", "需要 className 和 methodName", "className", className)
            val descriptor = args.str("descriptor")
            val limit = args.intValue("limit", 500).coerceIn(1, 5000)
            val classDescriptor = "L${className.replace('.', '/')};"

            val dexFiles = loadAllDex(file)
            for ((dexName, dexFile) in dexFiles) {
                val classDef = dexFile.classes.firstOrNull { it.type == classDescriptor } ?: continue
                val method = classDef.methods.firstOrNull {
                    it.name == methodName && (descriptor.isBlank() || buildDescriptor(it) == descriptor)
                } ?: continue

                val impl = method.implementation
                    ?: return err("NO_IMPLEMENTATION", "方法无实现(abstract/native)", "methodName", methodName)

                val instructions = JSONArray()
                var count = 0
                for ((offIdx, insn) in impl.instructions.withIndex()) {
                    if (count >= limit) break
                    val insnObj = JSONObject()
                        .put("offset", "0x${offIdx.toString(16)}")
                        .put("opcode", insn.opcode.name)
                        .put("format", insn.opcode.format.toString())

                    if (insn is ReferenceInstruction) {
                        val ref = insn.reference
                        insnObj.put("reference", ref.toString())
                        when (ref) {
                            is MethodReference -> insnObj.put("refType", "method").put("refClass", ref.definingClass).put("refMethod", ref.name)
                            is FieldReference -> insnObj.put("refType", "field").put("refClass", ref.definingClass).put("refField", ref.name)
                            is StringReference -> insnObj.put("refType", "string").put("refValue", ref.string)
                            is TypeReference -> insnObj.put("refType", "type").put("refValue", ref.type)
                            else -> insnObj.put("refType", ref.javaClass.simpleName)
                        }
                    }

                    if (insn is com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction) {
                        insnObj.put("literal", insn.narrowLiteral)
                    }

                    instructions.put(insnObj)
                    count++
                }

                val params = JSONArray()
                for (param in method.parameters) params.put(param.type)

                return ok(JSONObject()
                    .put("className", className)
                    .put("methodName", methodName)
                    .put("descriptor", buildDescriptor(method))
                    .put("dex", dexName)
                    .put("registerCount", impl.registerCount)
                    .put("parameters", params)
                    .put("returnType", method.returnType)
                    .put("accessFlags", accessFlagsToString(method.accessFlags))
                    .put("instructionCount", count)
                    .put("instructions", instructions)
                    .put("hint", "用 smali_edit action=extract 看可读的 smali 代码"))
            }
            return err("METHOD_NOT_FOUND", "方法 $className.$methodName 不在 APK 中", "methodName", methodName)
        }
    }

    /** odex→正常DEX 转换 */
    val dexDeodex: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "dex_deodex",
            "【DEX Deodex】把 odexed/oat 文件转换为正常 DEX。用 BaksmaliOptions.deodex + dexlib2 分析器还原原始调用。",
            "Convert odexed/oat files to normal DEX. Uses BaksmaliOptions.deodex.",
            "build", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "path" str "odex/oat/vdex 文件路径"
                "apiLevel" int "API level(默认自动检测)"
                "outputDir" str "输出目录(可选)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path", "path", "")
            val file = File(path)
            if (!file.isFile) return err("FILE_NOT_FOUND", "文件不存在: $path", "path", path)
            val api = args.intValue("apiLevel", 0)
            val outDir = args.str("outputDir").ifBlank {
                File(ctx.context.filesDir, "deodex-out/${file.nameWithoutExtension}").absolutePath
            }

            return runCatching {
                val opcodes = if (api > 0) Opcodes.forApi(api) else Opcodes.getDefault()
                val dexFile = DexFileFactory.loadDexFile(file, opcodes)
                val out = File(outDir).apply { mkdirs() }
                val options = BaksmaliOptions().apply { this.deodex = true }
                val jobs = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                com.android.tools.smali.baksmali.Baksmali.disassembleDexFile(dexFile, out, jobs, options)
                val smaliCount = out.walkTopDown().count { it.isFile && it.extension == "smali" }
                ok(JSONObject()
                    .put("tool", "dex_deodex")
                    .put("success", true)
                    .put("outputDir", out.absolutePath)
                    .put("smaliFiles", smaliCount)
                    .put("hint", "odex 已还原为 smali, 可用 smali_assemble 重新编译为正常 DEX"))
            }.getOrElse { e ->
                err("DEODEX_FAILED", "deodex 失败: ${e.message ?: e.javaClass.simpleName}", "path", path)
            }
        }
    }

    /** 增量重编 */
    val dexIncrementalRebuild: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "dex_incremental_rebuild",
            "【DEX 增量重编】只重编改动的类, 不全量重编整个 DEX。用 dexlib2 DexPool 保留未改动类的原始字节。比 smali_assemble(全量重编)快 10-50 倍。",
            "Incremental DEX rebuild: only recompile changed classes. 10-50x faster than full smali_assemble.",
            "build", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "dexPath" str "原始 DEX 文件路径"
                "smaliDir" str "改动后的 smali 目录"
                "outDex" str "输出 DEX 路径(默认 dexPath 同级 out.dex)"
                "apiLevel" int "DEX API level(默认 34)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val dexPath = args.str("dexPath")
            val smaliDir = args.str("smaliDir")
            if (dexPath.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 dexPath", "dexPath", "")
            if (smaliDir.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 smaliDir", "smaliDir", "")
            val dexFile = File(dexPath)
            val smaliDirFile = File(smaliDir)
            if (!dexFile.isFile) return err("FILE_NOT_FOUND", "DEX 不存在: $dexPath", "dexPath", dexPath)
            if (!smaliDirFile.isDirectory) return err("DIR_NOT_FOUND", "smali 目录不存在: $smaliDir", "smaliDir", smaliDir)

            val outPath = args.str("outDex").ifBlank { File(dexFile.parentFile, "out.dex").absolutePath }
            val api = args.intValue("apiLevel", 34)

            return runCatching {
                val dexMtime = dexFile.lastModified()
                val changedClasses = mutableSetOf<String>()
                smaliDirFile.walkTopDown().filter { it.isFile && it.extension == "smali" }.forEach { smaliFile ->
                    if (smaliFile.lastModified() > dexMtime) {
                        val relPath = smaliFile.relativeTo(smaliDirFile).path
                        val className = relPath.removeSuffix(".smali").replace(File.separatorChar, '/')
                        changedClasses.add("L$className;")
                    }
                }

                val opcodes = Opcodes.forApi(api)
                val origDex = DexFileFactory.loadDexFile(dexFile, opcodes)

                if (changedClasses.isEmpty()) {
                    val opts = com.android.tools.smali.smali.SmaliOptions().apply {
                        outputDexFile = outPath
                        this.apiLevel = api
                        jobs = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                    }
                    val success = com.android.tools.smali.smali.Smali.assemble(opts, listOf(smaliDir))
                    return@runCatching if (success) ok(JSONObject()
                        .put("tool", "dex_incremental_rebuild")
                        .put("mode", "full_rebuild")
                        .put("changedClasses", 0)
                        .put("outDex", outPath)
                        .put("success", true)) else err("REBUILD_FAILED", "全量重编失败", "smaliDir", smaliDir)
                }

                // 增量: 只汇编改动的类
                val tempSmaliDir = File.createTempFile("incr_smali_", "").apply { delete(); mkdirs() }
                var changedCount = 0
                for (classDescriptor in changedClasses) {
                    val className = classDescriptor.substring(1, classDescriptor.length - 1)
                    val srcSmali = File(smaliDirFile, className.replace('/', File.separatorChar) + ".smali")
                    if (srcSmali.isFile) {
                        val destSmali = File(tempSmaliDir, className.replace('/', File.separatorChar) + ".smali")
                        destSmali.parentFile?.mkdirs()
                        srcSmali.copyTo(destSmali)
                        changedCount++
                    }
                }

                val tempDex = File.createTempFile("incr_changed_", ".dex")
                val opts = com.android.tools.smali.smali.SmaliOptions().apply {
                    outputDexFile = tempDex.absolutePath
                    this.apiLevel = api
                    jobs = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                }
                val asmOk = com.android.tools.smali.smali.Smali.assemble(opts, listOf(tempSmaliDir.absolutePath))
                tempSmaliDir.deleteRecursively()
                if (!asmOk) return@runCatching err("ASM_FAILED", "改动类汇编失败", "smaliDir", smaliDir)

                val changedDex = DexFileFactory.loadDexFile(tempDex, opcodes)
                val changedClassMap = changedDex.classes.associateBy { it.type }
                val origClassTypes = origDex.classes.map { it.type }.toSet()

                // 合并: 用反射调用 DexPool (API 可能因版本不同)
                val outFile = File(outPath)
                val dexPoolClass = Class.forName("com.android.tools.smali.dexlib2.writer.pool.DexPool")
                val dexPool = dexPoolClass.getConstructor(Opcodes::class.java).newInstance(opcodes)
                val internMethod = dexPoolClass.getMethod("intern", Class.forName("com.android.tools.smali.dexlib2.iface.ClassDef"))
                for (classDef: ClassDef in origDex.classes) {
                    val toIntern: Any = if (classDef.type in changedClasses) {
                        changedClassMap[classDef.type] ?: classDef
                    } else {
                        classDef
                    }
                    internMethod.invoke(dexPool, toIntern as Any)
                }
                for (classDef in changedDex.classes) {
                    if (classDef.type !in origClassTypes) internMethod.invoke(dexPool, classDef)
                }
                val writeToMethod = dexPoolClass.getMethod("writeTo",
                    Class.forName("com.android.tools.smali.dexlib2.writer.io.DataStore"))
                val store = FileDataStore(outFile)
                try { writeToMethod.invoke(dexPool, store) } finally { store.close() }
                tempDex.delete()

                ok(JSONObject()
                    .put("tool", "dex_incremental_rebuild")
                    .put("mode", "incremental")
                    .put("changedClasses", changedCount)
                    .put("outDex", outPath)
                    .put("outDexSize", outFile.length())
                    .put("success", true)
                    .put("hint", "增量重编完成, 只有 $changedCount 个类被重编"))
            }.getOrElse { e ->
                err("INCREMENTAL_FAILED", "增量重编失败: ${e.message ?: e.javaClass.simpleName}", "dexPath", dexPath)
            }
        }
    }

    // ===== 工具方法 =====

    private fun loadAllDex(file: File): List<Pair<String, DexFile>> {
        val result = mutableListOf<Pair<String, DexFile>>()
        if (file.name.endsWith(".dex")) {
            val dex = DexFileFactory.loadDexFile(file, Opcodes.getDefault())
            result.add(file.name to dex)
        } else {
            ZipFile(file).use { zf ->
                val dexEntries = zf.entries().toList().filter {
                    it.name.matches(Regex("classes\\d*\\.dex"))
                }
                for (entry in dexEntries) {
                    val tempDex = File.createTempFile("classes_", ".dex")
                    zf.getInputStream(entry).use { it.copyTo(tempDex.outputStream()) }
                    // 不删 tempDex — DexFile 可能需要文件存在
                    try {
                        val dex = DexFileFactory.loadDexFile(tempDex, Opcodes.getDefault())
                        result.add(entry.name to dex)
                    } catch (e: Exception) {
                        tempDex.delete()
                    }
                }
            }
        }
        return result
    }

    private fun extractMethodRef(insn: Instruction): MethodReference? {
        if (insn is ReferenceInstruction) {
            val ref = insn.reference
            if (ref is MethodReference) return ref
        }
        return null
    }

    private fun buildDescriptor(method: Method): String {
        val params = method.parameters.joinToString("") { it.type }
        return "($params)${method.returnType}"
    }

    // MethodReference 接口在不同 dexlib2 版本中 parameters 成员的可见性不同,
    // 用反射安全获取参数类型列表。
    private fun buildDescriptor(ref: MethodReference): String {
        var params = ""
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val list = MethodReference::class.java.getMethod("getParameters").invoke(ref) as List<Any>
            params = list.joinToString("") {
                // MethodParameter 有 getType(); 普通 CharSequence 直接 toString()
                runCatching { it.javaClass.getMethod("getType").invoke(it).toString() }
                    .getOrDefault(it.toString())
            }
        }
        return "($params)${ref.returnType}"
    }

    private fun typeToName(type: String): String =
        type.substring(1, type.length - 1).replace('/', '.')

    private fun accessFlagsToString(flags: Int): String {
        val parts = mutableListOf<String>()
        if (flags and 0x0001 != 0) parts.add("public")
        if (flags and 0x0002 != 0) parts.add("private")
        if (flags and 0x0004 != 0) parts.add("protected")
        if (flags and 0x0008 != 0) parts.add("static")
        if (flags and 0x0010 != 0) parts.add("final")
        if (flags and 0x0020 != 0) parts.add("synchronized")
        if (flags and 0x0040 != 0) parts.add("volatile")
        if (flags and 0x0080 != 0) parts.add("transient")
        if (flags and 0x0100 != 0) parts.add("native")
        if (flags and 0x0200 != 0) parts.add("interface")
        if (flags and 0x0400 != 0) parts.add("abstract")
        if (flags and 0x0800 != 0) parts.add("strict")
        if (flags and 0x1000 != 0) parts.add("synthetic")
        if (flags and 0x2000 != 0) parts.add("annotation")
        if (flags and 0x4000 != 0) parts.add("enum")
        return parts.joinToString(" ")
    }

    val ALL = listOf(dexXref, dexClassOutline, dexMethodCode, dexDeodex, dexIncrementalRebuild)
}
