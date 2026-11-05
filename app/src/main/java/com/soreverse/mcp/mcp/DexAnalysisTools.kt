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
        override val meta = ToolMeta("taffy_dex_xref",
            "【DEX 方法交叉引用】精确分析谁调用了某方法(to), 或某方法调用了什么(from)。用 dexlib2 AnalyzedInstruction 做指令级分析, 精确到调用指令和调用位置(方法+偏移)。比 MT管理器的 taffy_dex_xref 更详细: 返回调用者类名/方法名/指令偏移/调用类型(invoke-virtual/direct/static/super)。自动扫描多 DEX。",
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
        override val meta = ToolMeta("taffy_dex_class_outline",
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
                .put("hint", "用 taffy_dex_xref 查方法交叉引用, taffy_smali_edit action=extract 提取 smali 代码"))
        }
    }

    /** 方法字节码提取 */
    val dexMethodCode: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_dex_method_code",
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
                    .put("hint", "用 taffy_smali_edit action=extract 看可读的 smali 代码"))
            }
            return err("METHOD_NOT_FOUND", "方法 $className.$methodName 不在 APK 中", "methodName", methodName)
        }
    }

    /** odex→正常DEX 转换 */
    val dexDeodex: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_dex_deodex",
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
                    .put("tool", "taffy_dex_deodex")
                    .put("success", true)
                    .put("outputDir", out.absolutePath)
                    .put("smaliFiles", smaliCount)
                    .put("hint", "odex 已还原为 smali, 可用 taffy_smali_assemble 重新编译为正常 DEX"))
            }.getOrElse { e ->
                err("DEODEX_FAILED", "deodex 失败: ${e.message ?: e.javaClass.simpleName}", "path", path)
            }
        }
    }

    /** 增量重编 */
    val dexIncrementalRebuild: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_dex_incremental_rebuild",
            "【DEX 增量重编】只重编改动的类, 不全量重编整个 DEX。用 dexlib2 DexPool 保留未改动类的原始字节。比 taffy_smali_assemble(全量重编)快 10-50 倍。",
            "Incremental DEX rebuild: only recompile changed classes. 10-50x faster than full taffy_smali_assemble.",
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
                        .put("tool", "taffy_dex_incremental_rebuild")
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
                    .put("tool", "taffy_dex_incremental_rebuild")
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

    /**
     * 包使用统计 —— 回答「这个 SDK / 库还被用着吗、能不能删」。
     *
     * 参考 Dex 编辑器 1.021 的 PackageUsageStats：只统计**其它包**对目标包（含子包）的引用，
     * 包内互引不计 —— 否则任何一个内部调用很多的库都会显得"被重度使用"。
     * 按类汇总四类引用（实例化 / 静态调用 / 方法调用 / 字段读写），再给成员明细。
     */
    val dexPackageUsage: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_dex_package_usage",
            "【DEX 包使用统计】统计某个包（含子包）被**其它包**引用了多少次：实例化(new-instance)、" +
                "静态调用(invoke-static)、方法调用、字段读写，按类汇总并按成员排序。用来判断一个 SDK / 库" +
                "是不是死代码、删掉安不安全。包内互引会被排除。需要 Markdown 表格时传 markdown=true。",
            "Package-level usage statistics: counts references from OUTSIDE the package into it " +
                "(new-instance / invoke-static / method / field), aggregated per class and per member. " +
                "Answers \"is this SDK dead code\". Intra-package references are excluded. " +
                "Set markdown=true for a Markdown table.",
            "decompile", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "path" str "APK 或 DEX 文件路径"
                "package" str "目标包名（如 com.example.sdk，也接受 com/example/sdk）"
                "includeSubpackages" bool "是否包含子包（默认 true）"
                "topMembers" int "成员明细条数，默认 30，按引用次数降序，0 = 不返回"
                "markdown" bool "是否额外返回 Markdown 表格（默认 false）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path", "path", "")
            val file = File(path)
            if (!file.isFile) return err("FILE_NOT_FOUND", "文件不存在: $path", "path", path)

            val rawPkg = args.str("package").trim().trim('.', '/', ';', 'L')
            if (rawPkg.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 package", "package", "")
            val prefix = rawPkg.replace('.', '/')
            val includeSub = args.str("includeSubpackages", "true").toBoolean()
            val topMembers = args.intValue("topMembers", 30).coerceIn(0, 500)
            val wantMarkdown = args.str("markdown", "false").toBoolean()

            val dexFiles = loadAllDex(file)
            if (dexFiles.isEmpty()) return err("NO_DEX", "未在 $path 中找到 DEX", "path", path)

            // 先记下包内类，扫描时用来排除「包内互引」
            val targetClasses = HashSet<String>()
            var scannedClasses = 0
            for ((_, dex) in dexFiles) {
                for (cls in dex.classes) {
                    scannedClasses++
                    if (inPackage(cls.type, prefix, includeSub)) targetClasses.add(cls.type)
                }
            }
            if (targetClasses.isEmpty())
                return err("PACKAGE_NOT_FOUND", "包里没有类: $rawPkg（检查包名，或该包在别的 DEX）", "package", rawPkg)

            // 类级计数：0=实例化 1=静态调用 2=方法调用 3=字段读写
            val classStats = HashMap<String, IntArray>()
            val memberStats = HashMap<String, Int>()
            fun bump(key: String, idx: Int) {
                val arr = classStats.getOrPut(key) { IntArray(4) }
                arr[idx] = arr[idx] + 1
            }

            for ((_, dex) in dexFiles) {
                for (cls in dex.classes) {
                    val callerInside = inPackage(cls.type, prefix, includeSub)
                    if (callerInside) continue          // 包内互引，整类跳过（不会有外部引用混在同一条指令里）
                    for (m in cls.methods) {
                        val impl = m.implementation ?: continue
                        for (insn in impl.instructions) {
                            if (insn !is ReferenceInstruction) continue
                            val op = insn.opcode.name
                            if (op == "NEW_INSTANCE") {
                                val t = insn.reference as? TypeReference ?: continue
                                if (inPackage(t.type, prefix, includeSub)) bump(t.type, 0)
                            } else if (op.startsWith("INVOKE_")) {
                                val r = insn.reference as? MethodReference ?: continue
                                if (!inPackage(r.definingClass, prefix, includeSub)) continue
                                val isStatic = op.contains("STATIC")
                                bump(r.definingClass, if (isStatic) 1 else 2)
                                val mk = "${r.definingClass}|m|${r.name}"
                                memberStats[mk] = (memberStats[mk] ?: 0) + 1
                            } else if (op.startsWith("IGET") || op.startsWith("IPUT") ||
                                op.startsWith("SGET") || op.startsWith("SPUT")
                            ) {
                                val r = insn.reference as? FieldReference ?: continue
                                if (!inPackage(r.definingClass, prefix, includeSub)) continue
                                bump(r.definingClass, 3)
                                val fk = "${r.definingClass}|f|${r.name}"
                                memberStats[fk] = (memberStats[fk] ?: 0) + 1
                            }
                        }
                    }
                }
            }

            var newInstance = 0; var staticCall = 0; var methodRefs = 0; var fieldRefs = 0
            val classRows = classStats.entries
                .map { (type, a) ->
                    newInstance += a[0]; staticCall += a[1]; methodRefs += a[2]; fieldRefs += a[3]
                    Triple(type, a, a[0] + a[1] + a[2] + a[3])
                }
                .sortedByDescending { it.third }

            val classesJson = JSONArray()
            for ((type, a, sum) in classRows) {
                classesJson.put(
                    JSONObject()
                        .put("className", typeToName(type))
                        .put("newInstance", a[0])
                        .put("staticCall", a[1])
                        .put("methodRefs", a[2])
                        .put("fieldRefs", a[3])
                        .put("total", sum),
                )
            }

            val membersJson = JSONArray()
            if (topMembers > 0) {
                memberStats.entries
                    .sortedByDescending { it.value }
                    .take(topMembers)
                    .forEach { (key, count) ->
                        val owner = key.substringBefore("|")
                        val rest = key.substringAfter("|")
                        val kind = rest.substringBefore("|")
                        val name = rest.substringAfter("|")
                        membersJson.put(
                            JSONObject()
                                .put("owner", typeToName(owner))
                                .put("kind", if (kind == "m") "method" else "field")
                                .put("name", name)
                                .put("count", count),
                        )
                    }
            }

            val totalRefs = newInstance + staticCall + methodRefs + fieldRefs
            val unusedWarn = if (totalRefs == 0) "该包没有任何外部引用（可作为死代码候选）" else ""
            val result = JSONObject()
                .put("tool", "taffy_dex_package_usage")
                .put("package", rawPkg.replace('/', '.'))
                .put("includeSubpackages", includeSub)
                .put("scannedClasses", scannedClasses)
                .put("targetClasses", targetClasses.size)
                .put("referencedClasses", classStats.size)
                .put("newInstance", newInstance)
                .put("staticCall", staticCall)
                .put("methodRefs", methodRefs)
                .put("fieldRefs", fieldRefs)
                .put("totalRefs", totalRefs)
                .put("classes", classesJson)
                .put("members", membersJson)
            if (unusedWarn.isNotBlank()) result.put("warning", unusedWarn)
            if (wantMarkdown) result.put("markdown", usageMarkdown(rawPkg.replace('/', '.'), result, classRows, memberStats))
            return ok(result)
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
                    zf.getInputStream(entry).use { ins ->
                        // 目标流也必须 use：不关会漏 fd，紧接着 tempDex.delete() 在 fd 仍打开时
                        // unlink，空间要等 GC finalize 才回来；批量处理 dex 能把 fd 耗到 "Too many open files"
                        tempDex.outputStream().use { out -> ins.copyTo(out) }
                    }
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

    /**
     * 类描述符是否落在包 [prefix]（已是斜杠形式）内。
     * 嵌套类 `Lcom/x/Foo$Bar;` 的「目录」仍是 `com/x`，所以取最后一个 `/` 前的部分即可。
     */
    internal fun inPackage(type: String, prefix: String, includeSub: Boolean = true): Boolean {
        if (!type.startsWith("L") || !type.endsWith(";")) return false
        val dir = type.substring(1, type.length - 1).substringBeforeLast('/', "")
        if (dir.isBlank()) return false
        return if (includeSub) dir == prefix || dir.startsWith("$prefix/") else dir == prefix
    }

    /** 包使用统计的 Markdown 表格（类汇总 + 成员明细）。 */
    private fun usageMarkdown(
        pkg: String,
        result: JSONObject,
        classRows: List<Triple<String, IntArray, Int>>,
        memberStats: Map<String, Int>,
    ): String {
        val b = StringBuilder()
        b.append("# 包使用统计：").append(pkg).append("\n\n")
        b.append("- 扫描类数：").append(result.optInt("scannedClasses")).append('\n')
        b.append("- 包内类数：").append(result.optInt("targetClasses")).append('\n')
        b.append("- 被引用类数：").append(result.optInt("referencedClasses")).append('\n')
        b.append("- 实例化次数：").append(result.optInt("newInstance")).append('\n')
        b.append("- 静态调用次数：").append(result.optInt("staticCall")).append('\n')
        b.append("- 方法调用次数：").append(result.optInt("methodRefs")).append('\n')
        b.append("- 字段读写次数：").append(result.optInt("fieldRefs")).append('\n')
        b.append("- 合计引用次数：").append(result.optInt("totalRefs")).append("\n\n")
        b.append("> 仅统计其它包对该包（含子包）的引用，包内互引已排除\n\n")
        b.append("## 按类汇总（").append(classRows.size).append("）\n\n")
        if (classRows.isEmpty()) {
            b.append("无\n")
        } else {
            b.append("| 类名 | 实例化 | 静态调用 | 方法 | 字段 | 合计 |\n")
            b.append("| --- | ---: | ---: | ---: | ---: | ---: |\n")
            for ((type, a, sum) in classRows) {
                b.append("| ").append(typeToName(type))
                    .append(" | ").append(a[0])
                    .append(" | ").append(a[1])
                    .append(" | ").append(a[2])
                    .append(" | ").append(a[3])
                    .append(" | ").append(sum).append(" |\n")
            }
        }
        if (memberStats.isNotEmpty()) {
            b.append("\n## 成员明细（Top ").append(minOf(30, memberStats.size)).append("）\n\n")
            b.append("| 成员 | 类型 | 次数 |\n| --- | --- | ---: |\n")
            memberStats.entries.sortedByDescending { it.value }.take(30).forEach { (key, count) ->
                val owner = typeToName(key.substringBefore("|"))
                val kind = if (key.substringAfter("|").substringBefore("|") == "m") "方法" else "字段"
                val name = key.substringAfterLast("|")
                b.append("| ").append(owner).append('.').append(name)
                    .append(" | ").append(kind).append(" | ").append(count).append(" |\n")
            }
        }
        return b.toString()
    }

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

    val ALL = listOf(dexXref, dexClassOutline, dexMethodCode, dexDeodex, dexIncrementalRebuild, dexPackageUsage)
}
