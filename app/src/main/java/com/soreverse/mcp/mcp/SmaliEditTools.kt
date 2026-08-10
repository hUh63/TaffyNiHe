package com.soreverse.mcp.mcp

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * 塔菲逆核: Smali 增量编辑工具(参考 MT管理器的 edit_open/edit_text/edit_check/build 增量流程)。
 *
 * 塔菲逆核已有 taffy_baksmali_decode(全量解包) + taffy_smali_assemble(全量重编), 但缺少:
 *  - 增量提取: 只提取指定类的 smali, 不解包整个 DEX
 *  - 增量替换: 只改一个类, 自动重编该 DEX + 写回 APK
 *  - 多 DEX 自动定位: 自动找到类在哪个 classes*.dex 里
 *  - 类列表: 列出 APK 中所有类(按包名过滤)
 *
 * 工作流:
 *  1. taffy_smali_edit action=list_classes  → 看有哪些类
 *  2. taffy_smali_edit action=extract       → 提取目标类的 smali 代码
 *  3. (AI 修改 smali 代码)
 *  4. taffy_smali_edit action=replace       → 替换该类, 自动重编 DEX + 写回 APK
 */
object SmaliEditTools {

    /** APK/输入大小上限(512MB), 防大 APK 全局替换 OOM */
    private const val MAX_INPUT_BYTES = 512L * 1024L * 1024L


    /** Smali 增量编辑 */
    val smaliEdit: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_smali_edit",
            "【Smali 增量编辑】不改包整个 APK, 只提取/替换单个类或单条指令的 smali。action=list_classes 列出 APK 中的类(按包名过滤); action=extract 提取指定类的 smali 代码; action=replace 用新 smali 替换指定类(自动定位 DEX→重编→写回 APK); action=list_methods 列出类的方法签名; action=patch_instruction 仅替换方法内一条指令(如改 const 常量/return 返回值, 对标 MT 改一行代码); action=replace_string 方法内字符串常量替换(preserve/recompile 双模式, scope=method/class/global); action=inject_method_code 向方法体内注入一段 smali 代码片段(position=start 方法开头 / end return 之前); action=replace_method_body 整体替换一个方法的方法体。参考 MT管理器的 edit_open/edit_text 增量流程, 用 Google smali 库实现, 多 DEX 自动定位。注意: replace/patch_instruction/replace_string/inject_method_code/replace_method_body 会重编整个 DEX 再写回, 大 DEX 较慢。",
            "Incremental smali editing without full APK unpacking. action=list_classes lists classes (filter by package); extract gets smali for one class; replace writes new smali (auto-locates DEX→recompiles→writes back to APK); list_methods lists method signatures; patch_instruction replaces a single instruction inside a method (e.g. change a const literal / return value — MT-style 'change one line'); replace_string replaces a string constant in a method (preserve/recompile, scope=method/class/global); inject_method_code injects a smali fragment at method start/end; replace_method_body replaces a whole method body. Uses Google smali library, auto multi-DEX. Note: these recompile the whole DEX then write back, slow on large DEX.",
            "build", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("list_classes | extract | replace | list_methods | patch_instruction | replace_string | inject_method_code | replace_method_body", "list_classes", "extract", "replace", "list_methods", "patch_instruction", "replace_string", "inject_method_code", "replace_method_body")
                "path" str "APK 文件路径"
                "className" str "类全名(如 com.example.Foo), list_classes/extract/replace/list_methods/replace_string/inject_method_code/replace_method_body 用"
                "packagePrefix" str "list_classes: 包名前缀过滤(如 com.example), 加快速度"
                "smali" str "replace: 新的 smali 代码(完整 .smali 文件内容)"
                "method" str "patch_instruction/replace_string/inject_method_code/replace_method_body: 目标方法名(如 onCreate)。可用 \";\" 追加参数签名定位重载"
                "from" str "patch_instruction: 要替换的原始指令(需在方法体内唯一或取首次匹配)"
                "to" str "patch_instruction: 替换后的新指令"
                "occurrence" int "patch_instruction/replace_string: 替换第几处匹配(1 起, 默认 1)"
                "oldString" str "replace_string: 要替换的原始字符串常量(方法体内 const-string 的值)"
                "newString" str "replace_string: 替换后的新字符串"
                "mode".oneOf("replace_string 模式", "preserve(默认: 新串长度≤旧串, 不改结构心智) | recompile(允许变长重编)", "preserve", "recompile")
                "scope".oneOf("replace_string 范围", "method(默认: 仅目标方法内) | class(整个类文件) | global(整个 APK 所有 DEX)", "method", "class", "global")
                "smaliFragment" str "inject_method_code: 要注入的 smali 代码片段(不含 .method/.locals 头, 只给寄存器指令; 注意自行调整 .locals 计数)"
                "position".oneOf("inject_method_code 注入位置", "start(方法开头, .locals 后) | end(return 之前)", "start", "end")
                "newBody" str "replace_method_body: 新的完整方法体(含 .locals 及代码, 不包 .method 声明/结束)"
                "limit" int "list_classes: 最多返回条数(默认 100)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path(APK 路径)", "path", "")
            val file = File(path)
            if (!file.isFile) return err("FILE_NOT_FOUND", "APK 不存在: $path", "path", path)
            val action = args.str("action", "list_classes")

            return runCatching {
                when (action) {
                    "list_classes" -> listClasses(file, args)
                    "extract" -> extractSmali(file, args.str("className"))
                    "replace" -> replaceSmali(file, args.str("className"), args.str("smali"), ctx)
                    "list_methods" -> listMethods(file, args.str("className"))
                    "patch_instruction" -> patchInstruction(file, args, ctx)
                    "replace_string" -> replaceString(file, args, ctx)
                    "inject_method_code" -> injectMethodCode(file, args, ctx)
                    "replace_method_body" -> replaceMethodBody(file, args, ctx)
                    else -> err("UNKNOWN_ACTION", "未知 action: $action", "action", action)
                }
            }.getOrElse { e ->
                err("SMALI_EDIT_FAILED", "操作失败: ${e.message ?: e.javaClass.simpleName}", "action", action)
            }
        }

        /** 列出 APK 中所有 DEX 的类 */
        private fun listClasses(apk: File, args: JSONObject): JSONObject {
            val pkgPrefix = args.str("packagePrefix")
            val limit = args.intValue("limit", 100).coerceIn(1, 2000)
            val results = JSONArray()
            var total = 0

            ZipFile(apk).use { zf ->
                val dexEntries = zf.entries().toList().filter {
                    it.name.matches(Regex("classes\\d*\\.dex"))
                }
                for (dexEntry in dexEntries) {
                    // 提取 DEX 到临时文件
                    val tempDex = File.createTempFile("classes_", ".dex")
                    zf.getInputStream(dexEntry).use { it.copyTo(tempDex.outputStream()) }
                    try {
                        val dexFile = DexFileFactory.loadDexFile(tempDex, Opcodes.getDefault())
                        for (classDef in dexFile.classes) {
                            val name = classDef.type.substring(1, classDef.type.length - 1).replace('/', '.')
                            if (pkgPrefix.isNotBlank() && !name.startsWith(pkgPrefix)) continue
                            if (total >= limit) break
                            results.put(JSONObject()
                                .put("className", name)
                                .put("dex", dexEntry.name))
                            total++
                        }
                    } finally {
                        tempDex.delete()
                    }
                    if (total >= limit) break
                }
            }

            return ok(JSONObject()
                .put("action", "list_classes")
                .put("total", total)
                .put("results", results)
                .put("hint", "用 taffy_smali_edit action=extract 提取某个类的 smali 代码"))
        }

        /** 提取指定类的 smali 代码 */
        private fun extractSmali(apk: File, className: String): JSONObject {
            if (className.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 className", "className", "")
            val classDescriptor = "L${className.replace('.', '/')};"

            // 在所有 DEX 中查找该类
            val (dexEntryName, tempDex) = findClassInApk(apk, classDescriptor)
                ?: return err("CLASS_NOT_FOUND", "类 $className 不在 APK 的任何 DEX 中", "className", className)

            try {
                // baksmali 解包该 DEX 到临时目录
                val outDir = File.createTempFile("smali_out_", "").apply { delete(); mkdirs() }
                val options = com.android.tools.smali.baksmali.BaksmaliOptions()
                val dexFile = DexFileFactory.loadDexFile(tempDex, Opcodes.getDefault())
                com.android.tools.smali.baksmali.Baksmali.disassembleDexFile(dexFile, outDir, 4, options)

                // 找到对应的 .smali 文件
                val smaliFile = File(outDir, className.replace('.', '/') + ".smali")
                if (!smaliFile.isFile) return err("SMALI_NOT_FOUND", "类 $className 的 smali 文件未找到(可能内部类)", "className", className)

                val smaliText = smaliFile.readText()
                outDir.deleteRecursively()

                return ok(JSONObject()
                    .put("action", "extract")
                    .put("className", className)
                    .put("dex", dexEntryName)
                    .put("smali", smaliText)
                    .put("lineCount", smaliText.lines().size)
                    .put("hint", "修改 smali 后用 taffy_smali_edit action=replace 写回"))
            } finally {
                tempDex.delete()
            }
        }

        /** 替换指定类的 smali, 自动重编 DEX + 写回 APK */
        private fun replaceSmali(apk: File, className: String, smaliCode: String, ctx: ToolContext): JSONObject {
            if (className.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 className", "className", "")
            if (smaliCode.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 smali(新 smali 代码)", "smali", "")
            val classDescriptor = "L${className.replace('.', '/')};"

            // 1. 找到类在哪个 DEX
            val (dexEntryName, tempDex) = findClassInApk(apk, classDescriptor)
                ?: return err("CLASS_NOT_FOUND", "类 $className 不在 APK 的任何 DEX 中", "className", className)

            try {
                // 2. baksmali 解包整个 DEX 到临时目录
                val outDir = File.createTempFile("smali_replace_", "").apply { delete(); mkdirs() }
                val options = com.android.tools.smali.baksmali.BaksmaliOptions()
                val dexFile = DexFileFactory.loadDexFile(tempDex, Opcodes.getDefault())
                com.android.tools.smali.baksmali.Baksmali.disassembleDexFile(dexFile, outDir, 4, options)

                // 3. 替换目标 .smali 文件
                val smaliFile = File(outDir, className.replace('.', '/') + ".smali")
                val parentDir = smaliFile.parentFile
                if (parentDir != null && !parentDir.exists()) parentDir.mkdirs()
                smaliFile.writeText(smaliCode)

                // 4. smali → dex 重编
                val newDex = File.createTempFile("recompiled_", ".dex")
                val opts = SmaliOptions().apply {
                    outputDexFile = newDex.absolutePath
                    apiLevel = 34
                    jobs = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                }
                val success = Smali.assemble(opts, listOf(outDir.absolutePath))
                outDir.deleteRecursively()

                if (!success) return err("SMALI_ASSEMBLE_FAILED", "smali 重编失败(检查语法), 类: $className", "className", className)

                // 5. 写回 APK: 重建 ZIP, 替换目标 DEX
                val newDexBytes = newDex.readBytes()
                newDex.delete()
                tempDex.delete()

                val tempApk = File.createTempFile("apk_patched_", ".apk", apk.parentFile)
                ZipFile(apk).use { zf ->
                    val zos = java.util.zip.ZipOutputStream(tempApk.outputStream())
                    zf.entries().toList().forEach { e ->
                        zos.putNextEntry(java.util.zip.ZipEntry(e.name))
                        if (e.name == dexEntryName) {
                            zos.write(newDexBytes)
                        } else {
                            zf.getInputStream(e).use { it.copyTo(zos) }
                        }
                        zos.closeEntry()
                    }
                    zos.close()
                }

                // 备份原 APK + 替换
                val backup = File(apk.parentFile, "${apk.nameWithoutExtension}.bak.apk")
                apk.copyTo(backup, overwrite = true)
                tempApk.copyTo(apk, overwrite = true)
                tempApk.delete()

                return ok(JSONObject()
                    .put("action", "replace")
                    .put("className", className)
                    .put("success", true)
                    .put("newDexSize", newDexBytes.size)
                    .put("backupPath", backup.absolutePath)
                    .put("hint", "DEX 已重编并写回 APK, 签名已失效. 用 taffy_apk_sign 或 taffy_apk_rebuild 重新签名"))

            } finally {
                tempDex.delete()
            }
        }

        /** 列出类的方法签名 */
        private fun listMethods(apk: File, className: String): JSONObject {
            if (className.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 className", "className", "")
            val classDescriptor = "L${className.replace('.', '/')};"

            val (_, tempDex) = findClassInApk(apk, classDescriptor)
                ?: return err("CLASS_NOT_FOUND", "类 $className 不在 APK 的任何 DEX 中", "className", className)

            try {
                val dexFile = DexFileFactory.loadDexFile(tempDex, Opcodes.getDefault())
                val classDef = dexFile.classes.firstOrNull { it.type == classDescriptor }
                    ?: return err("CLASS_NOT_FOUND", "类定义未找到", "className", className)

                val methods = JSONArray()
                for (method in classDef.methods) {
                    methods.put(JSONObject()
                        .put("name", method.name)
                        .put("returnType", method.returnType)
                        .put("parameters", JSONArray(method.parameters.map { it.type }))
                        .put("accessFlags", method.accessFlags))
                }

                val fields = JSONArray()
                for (field in classDef.fields) {
                    fields.put(JSONObject()
                        .put("name", field.name)
                        .put("type", field.type)
                        .put("accessFlags", field.accessFlags))
                }

                return ok(JSONObject()
                    .put("action", "list_methods")
                    .put("className", className)
                    .put("superClass", classDef.superclass ?: "")
                    .put("interfaces", JSONArray((classDef.interfaces ?: listOf<String>()).toList()))
                    .put("methods", methods)
                    .put("fields", fields)
                    .put("hint", "用 taffy_smali_edit action=extract 提取完整 smali 代码"))
            } finally {
                tempDex.delete()
            }
        }

        /** 定位方法体内某条指令并替换为另一条(指令级补丁, 对标 MT 改代码)。 */
        private fun patchInstruction(apk: File, args: JSONObject, ctx: ToolContext): JSONObject {
            val className = args.str("className")
            val method = args.str("method")
            val from = args.str("from")
            val to = args.str("to")
            val occurrence = args.intValue("occurrence", 1).coerceAtLeast(1)
            if (className.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 className", "className", "")
            if (method.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 method(目标方法名)", "method", "")
            if (from.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 from(原始指令)", "from", "")
            if (to.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 to(新指令)", "to", "")
            val classDescriptor = "L${className.replace('.', '/')};"

            val (dexEntryName, tempDex) = findClassInApk(apk, classDescriptor)
                ?: return err("CLASS_NOT_FOUND", "类 $className 不在 APK 的任何 DEX 中", "className", className)

            try {
                // 1. baksmali 解包整个 DEX 到临时目录
                val outDir = File.createTempFile("smali_patch_", "").apply { delete(); mkdirs() }
                val options = com.android.tools.smali.baksmali.BaksmaliOptions()
                val dexFile = DexFileFactory.loadDexFile(tempDex, Opcodes.getDefault())
                com.android.tools.smali.baksmali.Baksmali.disassembleDexFile(dexFile, outDir, 4, options)

                // 2. 读取目标类 smali 并做方法内指令替换
                val smaliFile = File(outDir, className.replace('.', '/') + ".smali")
                if (!smaliFile.isFile) {
                    outDir.deleteRecursively()
                    return err("SMALI_NOT_FOUND", "类 $className 的 smali 未找到(可能内部类)", "className", className)
                }
                val smaliText = smaliFile.readText()
                val patched = patchMethodInstruction(smaliText, method, from, to, occurrence)
                if (patched == null) {
                    outDir.deleteRecursively()
                    return err("INSTRUCTION_NOT_FOUND", "方法 $method 中未找到指令: $from (第 $occurrence 处)", "from", from)
                }
                smaliFile.writeText(patched)

                // 3. smali → dex 重编
                val newDex = File.createTempFile("recompiled_", ".dex")
                val opts = SmaliOptions().apply {
                    outputDexFile = newDex.absolutePath
                    apiLevel = 34
                    jobs = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                }
                val success = Smali.assemble(opts, listOf(outDir.absolutePath))
                outDir.deleteRecursively()
                if (!success) return err("SMALI_ASSEMBLE_FAILED", "指令补丁后 smali 重编失败(检查 to 指令语法), 类: $className", "to", to)

                // 4. 写回 APK
                val newDexBytes = newDex.readBytes()
                newDex.delete()
                tempDex.delete()
                val out = writeCompiledDexBack(ctx, apk, classDescriptor, dexEntryName, newDexBytes, className)
                if (out != null) return out

                return ok(JSONObject()
                    .put("action", "patch_instruction")
                    .put("className", className)
                    .put("method", method)
                    .put("from", from)
                    .put("to", to)
                    .put("success", true)
                    .put("newDexSize", newDexBytes.size)
                    .put("hint", "指令已替换并写回 APK. 签名已失效, 用 taffy_apk_sign 或 taffy_apk_rebuild 重新签名"))
            } finally {
                tempDex.delete()
            }
        }

/** 在 smali 文本范围内替换字符串常量(const-string/jumbo 的值), 对标 taffy_native_patch_string。
         *  mode=preserve(默认): 新串长度≤旧串, 不改结构心智; 放不下时报错提示 recompile。
         *  mode=recompile:     允许变长, 重编该 DEX。
         *  scope=method(默认): 仅 className.method 内; class: 整个 className 类文件; global: 全部 DEX 所有类。 */
        private fun replaceString(apk: File, args: JSONObject, ctx: ToolContext): JSONObject {
            val oldString = args.str("oldString")
            val newString = args.str("newString")
            val mode = args.str("mode", "preserve").ifBlank { "preserve" }
            val scope = args.str("scope", "method").ifBlank { "method" }
            val className = args.str("className")
            val method = args.str("method")
            val occurrence = args.intValue("occurrence", 1).coerceAtLeast(1)
            if (oldString.isBlank()) return err("INVALID_ARGUMENT", "缺少 oldString(原始字符串)", "oldString", "")
            if (newString.isBlank()) return err("INVALID_ARGUMENT", "缺少 newString(新字符串)", "newString", "")
            if (scope != "global" && className.isBlank()) return err("INVALID_ARGUMENT", "scope=$scope 需要 className", "className", "")
            if (scope == "method" && method.isBlank()) return err("INVALID_ARGUMENT", "scope=method 需要 method", "method", "")
            if (mode == "preserve" && newString.length > oldString.length) {
                return err("STRING_TOO_LONG", "preserve 模式要求新串长度≤旧串(旧 ${oldString.length} / 新 ${newString.length})。改用 mode=recompile 允许变长", "newString", newString)
            }
            val classDescriptor = if (scope != "global") "L${className.replace('.', '/')};" else null
            val escapedOld = Regex.escape(oldString)
            val escapedNew = newString.replace("\\", "\\\\").replace("\"", "\\\"")
            var totalReplaced = 0
            var dexTouched = 0

            // 按 scope 扫描, 返回改动后的 DEX (map: dex条目名 -> 重写后的新 DEX 字节)。
            // null 表示该 DEX 无改动。
            if (scope == "global" && apk.length() > MAX_INPUT_BYTES) {
                return err("INPUT_TOO_LARGE", "APK 过大(${(apk.length() / 1024 / 1024)}MB, 上限 512MB), 全局替换可能 OOM。改用 scope=class/method", "path", apk.absolutePath)
            }
            val results = HashMap<String, ByteArray>()
            val opcodes = Opcodes.getDefault()

            // 收集需处理的 (dexEntryName, 需要改动的 smali 相对路径集合 或 null=全改)
            // 统一走: 反汇编 dex -> 逐个 smali 替换 -> 重编 -> 写回
            java.util.zip.ZipFile(apk).use { zf ->
                val dexEntries = zf.entries().toList().filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                for (dexEntry in dexEntries) {
                    val tempDex = File.createTempFile("rstr_dex_", ".dex")
                    zf.getInputStream(dexEntry).use { it.copyTo(tempDex.outputStream()) }
                    try {
                        val dexFile = DexFileFactory.loadDexFile(tempDex, opcodes)
                        // 决定本 DEX 是否需要处理
                        val targets: List<String>? = when (scope) {
                            "global" -> null // 全部类
                            else -> if (dexFile.classes.any { it.type == classDescriptor }) {
                                listOf(className.replace('.', '/') + ".smali")
                            } else null
                        }
                        if (targets == null) continue // 本 DEX 无目标
                        // 反汇编本 DEX
                        val outDir = File.createTempFile("rstr_smali_", "").apply { delete(); mkdirs() }
                        val bopts = com.android.tools.smali.baksmali.BaksmaliOptions()
                        com.android.tools.smali.baksmali.Baksmali.disassembleDexFile(dexFile, outDir, Runtime.getRuntime().availableProcessors().coerceIn(1, 4), bopts)

                        var dexChanged = false
                        val allSmali = if (scope == "global") outDir.walkTopDown().filter { it.isFile && it.extension == "smali" }.toList()
                            else targets.map { File(outDir, it) }.filter { it.isFile }
                        for (smaliFile in allSmali) {
                            val text = smaliFile.readText()
                            val patched = if (scope == "method") {
                                replaceMethodStringLiteral(text, method, oldString, newString, occurrence)
                            } else {
                                replaceAllStringLiteral(text, escapedOld, escapedNew)
                            }
                            if (patched != null) { smaliFile.writeText(patched); dexChanged = true }
                        }
                        if (!dexChanged) { outDir.deleteRecursively(); continue }

                        // 重编本 DEX
                        val newDex = File.createTempFile("rstr_out_", ".dex")
                        val opts = SmaliOptions().apply {
                            outputDexFile = newDex.absolutePath
                            apiLevel = 34
                            jobs = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                        }
                        val success = Smali.assemble(opts, listOf(outDir.absolutePath))
                        outDir.deleteRecursively()
                        if (!success) {
                            newDex.delete(); tempDex.delete()
                            return err("SMALI_ASSEMBLE_FAILED", "字符串替换后 smali 重编失败, dex: ${dexEntry.name}", "newString", newString)
                        }
                        results[dexEntry.name] = newDex.readBytes()
                        newDex.delete()
                        dexTouched++
                        totalReplaced++
                    } finally { tempDex.delete() }
                }
            }

            if (results.isEmpty()) {
                return err(if (scope != "global") "STRING_NOT_FOUND" else "NO_MATCH",
                    when (scope) {
                        "method" -> "方法 $method 中未找到字符串常量: $oldString (第 $occurrence 处)"
                        "class" -> "类 $className 中未找到字符串常量: $oldString"
                        else -> "整个 APK 未找到字符串常量: $oldString"
                    }, "oldString", oldString)
            }

            // 写回 APK(重建 ZIP, 替换有改动的 DEX); 改动前登记快照 + .bak
            val backup = File(apk.parentFile, "${apk.nameWithoutExtension}.bak.apk")
            if (!backup.isFile) EditSnapshotService.snapshot(ctx.context, "taffy_smali_edit", apk.absolutePath)
            val tempApk = File.createTempFile("apk_rstr_", ".apk", apk.parentFile)
            java.util.zip.ZipFile(apk).use { zf ->
                val zos = java.util.zip.ZipOutputStream(tempApk.outputStream())
                zf.entries().toList().forEach { e ->
                    zos.putNextEntry(java.util.zip.ZipEntry(e.name))
                    val nb = results[e.name]
                    if (nb != null) zos.write(nb) else zf.getInputStream(e).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                zos.close()
            }
            apk.copyTo(backup, overwrite = true)
            apk.delete()
            tempApk.copyTo(apk, overwrite = true)
            tempApk.delete()

            return ok(JSONObject()
                .put("action", "replace_string")
                .put("scope", scope)
                .put("mode", mode)
                .put("oldString", oldString)
                .put("newString", newString)
                .put("dexTouched", dexTouched)
                .put("replacements", totalReplaced)
                .put("success", true)
                .put("newSizes", results.mapValues { it.value.size }.let { java.util.LinkedHashMap(it) })
                .put("hint", "字符串常量已替换并写回 APK(${results.keys.joinToString(",")})。签名已失效, 用 taffy_apk_sign 重新签名"))
        }

        /** 在整个 smali 文本中替换所有 const-string 值为 oldStr 的(不分方法)。返回新文本, 无匹配返回 null。 */
        private fun replaceAllStringLiteral(smaliText: String, escapedOld: String, escapedNew: String): String? {
            val matchRe = Regex("""const-string(?:/jumbo)?\s+v\d+,\s*"${escapedOld}"\s*$""")
            val out = StringBuilder()
            var changed = false
            for (line in smaliText.lines()) {
                if (matchRe.containsMatchIn(line)) {
                    val indent = line.takeWhile { it == ' ' || it == '\t' }
                    val instr = line.trim().substringBefore("\"").trim()
                    out.append(indent).append(instr).append(" \"").append(escapedNew).append("\"\n")
                    changed = true
                } else out.append(line).append("\n")
            }
            return if (changed) out.toString() else null
        }

        /** 在 smali 文本的目标方法体内, 把第 occurrence 个 const-string "old" 替换为 "new"。返回新文本, 未找到返回 null。 */
        private fun replaceMethodStringLiteral(smaliText: String, method: String, oldStr: String, newStr: String, occurrence: Int): String? {
            val methodBase = method.substringBefore(';').trim()
            val methodStartRegex = if (method.indexOf(';') >= 0) {
                val sigSuffix = Regex.escape(method.substringAfter(';', "").trim())
                Regex("""^\.method\b.*\b${Regex.escape(methodBase)}\s*\([^)]*${sigSuffix}[^)]*\)\s*\S+""")
            } else {
                Regex("""^\.method\b.*\b${Regex.escape(methodBase)}\s*\([^)]*\)\s*\S+""")
            }
            val escapedOld = Regex.escape(oldStr)
            val lines = smaliText.lines()
            val sb = StringBuilder()
            var inTarget = false
            var replaced = 0
            var replacedAny = false
            val matchRe = Regex("""const-string(?:/jumbo)?\s+v\d+,\s*"${escapedOld}"\s*$""")
            for (line in lines) {
                val t = line.trim()
                when {
                    t.startsWith(".method") -> inTarget = methodStartRegex.containsMatchIn(line)
                    t == ".end method" -> inTarget = false
                    inTarget -> {
                        if (matchRe.containsMatchIn(line)) {
                            replaced++
                            if (replaced == occurrence) {
                                val indent = line.takeWhile { it == ' ' || it == '\t' }
                                val instr = line.trim().substringBefore("\"").trim()
                                sb.append(indent).append(instr).append(" \"").append(newStr.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n")
                                replacedAny = true
                            } else {
                                sb.append(line).append("\n")
                            }
                            continue
                        }
                    }
                }
                sb.append(line).append("\n")
            }
            return if (replacedAny) sb.toString() else null
        }

        /** 在 smali 文本中定位方法体，替换方法内的 from→to 指令(occurrence 起 1)。返回新文本，未找到返回 null。 */
        private fun patchMethodInstruction(smaliText: String, method: String, from: String, to: String, occurrence: Int): String? {
            val methodBase = method.substringBefore(';').trim() // 方法名(去掉可能的参数签名后缀)
            // smali 方法头: .method [访问标志...] name(params)returnType
            // 若调用方提供 method;paramsSuffix(如 onCreate;Landroid/os/Bundle;)则要求参数签名也匹配, 避免同名重载误伤。
            val methodStartRegex = if (method.indexOf(';') >= 0) {
                val sigSuffix = Regex.escape(method.substringAfter(';', "").trim())
                Regex("""^\.method\b.*\b${Regex.escape(methodBase)}\s*\([^)]*${sigSuffix}[^)]*\)\s*\S+""")
            } else {
                Regex("""^\.method\b.*\b${Regex.escape(methodBase)}\s*\([^)]*\)\s*\S+""")
            }
            val fromTrim = from.trim()
            val lines = smaliText.lines()
            val sb = StringBuilder()
            var inTarget = false
            var replaced = 0
            for (line in lines) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith(".method") -> {
                        inTarget = methodStartRegex.containsMatchIn(trimmed)
                        sb.append(line).append("\n")
                    }
                    trimmed == ".end method" -> {
                        inTarget = false
                        sb.append(line).append("\n")
                    }
                    inTarget && trimmed == fromTrim -> {
                        replaced++
                        if (replaced == occurrence) {
                            val indent = line.takeWhile { it == ' ' || it == '\t' }
                            sb.append(indent).append(to.trim()).append("\n")
                        } else {
                            sb.append(line).append("\n")
                        }
                    }
                    else -> sb.append(line).append("\n")
                }
            }
            return if (replaced >= occurrence) sb.toString() else null
        }

        /** 将重编后的 DEX 字节写回 APK(重建 ZIP 替换目标 DEX), 带备份。成功返回 null, 失败返回错误 JSON。 */
/** 向指定方法体注入 smali 代码片段(position=start 方法开头 .locals 后 / end 最后一个 return 前)。 */
        private fun injectMethodCode(apk: File, args: JSONObject, ctx: ToolContext): JSONObject {
            val className = args.str("className")
            val method = args.str("method")
            val fragment = args.str("smaliFragment")
            val position = args.str("position", "start").ifBlank { "start" }
            if (className.isBlank()) return err("INVALID_ARGUMENT", "缺少 className", "className", "")
            if (method.isBlank()) return err("INVALID_ARGUMENT", "缺少 method", "method", "")
            if (fragment.isBlank()) return err("INVALID_ARGUMENT", "缺少 smaliFragment", "smaliFragment", "")
            val classDescriptor = "L${className.replace('.', '/')};"
            val (dexEntryName, tempDex) = findClassInApk(apk, classDescriptor)
                ?: return err("CLASS_NOT_FOUND", "类 $className 不在 APK 的任何 DEX 中", "className", className)
            try {
                val outDir = File.createTempFile("smali_inj_", "").apply { delete(); mkdirs() }
                val options = com.android.tools.smali.baksmali.BaksmaliOptions()
                val dexFile = DexFileFactory.loadDexFile(tempDex, Opcodes.getDefault())
                com.android.tools.smali.baksmali.Baksmali.disassembleDexFile(dexFile, outDir, 4, options)
                val smaliFile = File(outDir, className.replace('.', '/') + ".smali")
                if (!smaliFile.isFile) {
                    outDir.deleteRecursively(); return err("SMALI_NOT_FOUND", "类 $className 的 smali 未找到", "className", className)
                }
                val patched = injectFragmentInMethod(smaliFile.readText(), method, fragment, position)
                if (patched == null) {
                    outDir.deleteRecursively(); return err("METHOD_NOT_FOUND", "方法 $method 未找到(或无法定位注入点)", "method", method)
                }
                smaliFile.writeText(patched)
                val newDex = File.createTempFile("recompiled_inj_", ".dex")
                val opts = SmaliOptions().apply {
                    outputDexFile = newDex.absolutePath; apiLevel = 34; jobs = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                }
                val success = Smali.assemble(opts, listOf(outDir.absolutePath))
                outDir.deleteRecursively()
                if (!success) { newDex.delete(); return err("SMALI_ASSEMBLE_FAILED", "注入后 smali 重编失败(检查 smaliFragment 语法与 .locals 寄存器数)", "smaliFragment", fragment) }
                val newDexBytes = newDex.readBytes(); newDex.delete(); tempDex.delete()
                val out = writeCompiledDexBack(ctx, apk, classDescriptor, dexEntryName, newDexBytes, className)
                if (out != null) return out
                return ok(JSONObject()
                    .put("action", "inject_method_code").put("className", className).put("method", method)
                    .put("position", position).put("success", true)
                    .put("hint", "代码已注入并写回 APK。签名已失效, 用 taffy_apk_sign 重签名"))
            } finally { tempDex.delete() }
        }

        /** 整体替换方法体(方法头保留, 只换 .locals 之后的实现)。 */
        private fun replaceMethodBody(apk: File, args: JSONObject, ctx: ToolContext): JSONObject {
            val className = args.str("className")
            val method = args.str("method")
            val newBody = args.str("newBody")
            if (className.isBlank()) return err("INVALID_ARGUMENT", "缺少 className", "className", "")
            if (method.isBlank()) return err("INVALID_ARGUMENT", "缺少 method", "method", "")
            if (newBody.isBlank()) return err("INVALID_ARGUMENT", "缺少 newBody(新方法体)", "newBody", "")
            val classDescriptor = "L${className.replace('.', '/')};"
            val (dexEntryName, tempDex) = findClassInApk(apk, classDescriptor)
                ?: return err("CLASS_NOT_FOUND", "类 $className 不在 APK 的任何 DEX 中", "className", className)
            try {
                val outDir = File.createTempFile("smali_rbody_", "").apply { delete(); mkdirs() }
                val options = com.android.tools.smali.baksmali.BaksmaliOptions()
                val dexFile = DexFileFactory.loadDexFile(tempDex, Opcodes.getDefault())
                com.android.tools.smali.baksmali.Baksmali.disassembleDexFile(dexFile, outDir, 4, options)
                val smaliFile = File(outDir, className.replace('.', '/') + ".smali")
                if (!smaliFile.isFile) {
                    outDir.deleteRecursively(); return err("SMALI_NOT_FOUND", "类 $className 的 smali 未找到", "className", className)
                }
                val patched = replaceBodyInMethod(smaliFile.readText(), method, newBody)
                if (patched == null) {
                    outDir.deleteRecursively(); return err("METHOD_NOT_FOUND", "方法 $method 未找到", "method", method)
                }
                smaliFile.writeText(patched)
                val newDex = File.createTempFile("recompiled_rb_", ".dex")
                val opts = SmaliOptions().apply {
                    outputDexFile = newDex.absolutePath; apiLevel = 34; jobs = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                }
                val success = Smali.assemble(opts, listOf(outDir.absolutePath))
                outDir.deleteRecursively()
                if (!success) { newDex.delete(); return err("SMALI_ASSEMBLE_FAILED", "方法体替换后 smali 重编失败(检查 newBody 语法/返回寄存器/签名)", "newBody", newBody) }
                val newDexBytes = newDex.readBytes(); newDex.delete(); tempDex.delete()
                val out = writeCompiledDexBack(ctx, apk, classDescriptor, dexEntryName, newDexBytes, className)
                if (out != null) return out
                return ok(JSONObject()
                    .put("action", "replace_method_body").put("className", className).put("method", method)
                    .put("success", true)
                    .put("hint", "方法体已整体替换并写回 APK。签名已失效, 用 taffy_apk_sign 重签名"))
            } finally { tempDex.delete() }
        }

        /** 方法头匹配正则 */
        private fun methodStartPattern(method: String): Regex {
            val methodBase = method.substringBefore(';').trim()
            return if (method.indexOf(';') >= 0) {
                val sigSuffix = Regex.escape(method.substringAfter(';', "").trim())
                Regex("""^\.method\b.*\b${Regex.escape(methodBase)}\s*\([^)]*${sigSuffix}[^)]*\)\s*\S+""")
            } else {
                Regex("""^\.method\b.*\b${Regex.escape(methodBase)}\s*\([^)]*\)\s*\S+""")
            }
        }

        /** 找到目标方法体行范围(从 .method 头行到 .end method 行, 含头行不含结尾注释)。返回 [startIndex, endIndexExclusive]。 */
        private fun findMethodRange(lines: List<String>, startRe: Regex): Pair<Int, Int>? {
            var i = 0
            while (i < lines.size) {
                val t = lines[i].trim()
                if (t.startsWith(".method") && startRe.containsMatchIn(lines[i])) {
                    var j = i + 1
                    while (j < lines.size && lines[j].trim() != ".end method") j++
                    if (j < lines.size) return i to j   // [i, j) 包 .method 头到 .end 前
                }
                i++
            }
            return null
        }

        /** 定位方法体内第一个有效位置(.locals 后的第一条真实指令)的绝对行号; 无 .locals 则返回方法体第一行。 */
        private fun bodyStartIndex(lines: List<String>, methodStartIdx: Int, endIdx: Int): Int {
            var afterLocals = -1
            for (k in methodStartIdx + 1 until endIdx) {
                val t = lines[k].trim()
                if (t.startsWith(".locals")) { afterLocals = k; break }
            }
            if (afterLocals >= 0) {
                // .locals 后第一条非 .directive 指令
                for (k in afterLocals + 1 until endIdx) {
                    val t = lines[k].trim()
                    if (t.isNotEmpty() && !t.startsWith(".")) return k
                }
                return afterLocals + 1
            }
            // 无 .locals: 第一条非 . 指令
            for (k in methodStartIdx + 1 until endIdx) {
                val t = lines[k].trim()
                if (t.isNotEmpty() && !t.startsWith(".") && !t.startsWith("#")) return k
            }
            return endIdx - 1
        }

        private fun injectFragmentInMethod(smaliText: String, method: String, fragment: String, position: String): String? {
            val startRe = methodStartPattern(method)
            val lines = smaliText.lines()
            val range = findMethodRange(lines, startRe) ?: return null
            val (mStart, mEnd) = range
            val frag = fragment.trim().trimEnd('\n', '\r')
            // 注入行号(在方法体范围内插入)
            val injectLine = if (position == "end") {
                // 最后一个 return 指令行
                var lastReturn = -1
                for (k in mStart + 1 until mEnd) if (lines[k].trim().startsWith("return")) lastReturn = k
                if (lastReturn < 0) return null
                lastReturn
            } else {
                bodyStartIndex(lines, mStart, mEnd)
            }
            // 重构: 在 injectLine 前插入 fragment
            val out = StringBuilder()
            var i = 0
            while (i < lines.size) {
                if (i == injectLine) {
                    out.append(frag.replace("\\n", "\n")).append("\n")
                    if (i < mEnd) { out.append(lines[i]).append("\n"); i++ } else i++
                    continue
                }
                out.append(lines[i]).append("\n")
                i++
            }
            return out.toString()
        }

        private fun replaceBodyInMethod(smaliText: String, method: String, newBody: String): String? {
            val startRe = methodStartPattern(method)
            val lines = smaliText.lines()
            val range = findMethodRange(lines, startRe) ?: return null
            val (mStart, mEnd) = range
            val bodyStart = bodyStartIndex(lines, mStart, mEnd)
            val body = newBody.trim().trimEnd('\n', '\r')
            // 互换: [bodyStart, mEnd) 替换为 body
            val out = StringBuilder()
            var i = 0
            while (i < lines.size) {
                if (i == bodyStart) {
                    out.append(body.replace("\\n", "\n")).append("\n")
                    i = mEnd + 1   // 跳过整个原方法体直到 .end method 之后
                    continue
                }
                out.append(lines[i]).append("\n")
                i++
            }
            return out.toString()
        }
        private fun writeCompiledDexBack(ctx: ToolContext, apk: File, classDescriptor: String, dexEntryName: String, newDexBytes: ByteArray, className: String): JSONObject? {
            val tempApk = File.createTempFile("apk_patched_", ".apk", apk.parentFile)
            try {
                ZipFile(apk).use { zf ->
                    val zos = java.util.zip.ZipOutputStream(tempApk.outputStream())
                    zf.entries().toList().forEach { e ->
                        zos.putNextEntry(java.util.zip.ZipEntry(e.name))
                        if (e.name == dexEntryName) {
                            zos.write(newDexBytes)
                        } else {
                            zf.getInputStream(e).use { it.copyTo(zos) }
                        }
                        zos.closeEntry()
                    }
                    zos.close()
                }
                // 改动前登记统一快照(供 taffy_edit_snapshot diff/rollback), 再留 .bak 双保险
                EditSnapshotService.snapshot(ctx.context, "taffy_smali_edit", apk.absolutePath)
                val backup = File(apk.parentFile, "${apk.nameWithoutExtension}.bak.apk")
                apk.copyTo(backup, overwrite = true)
                tempApk.copyTo(apk, overwrite = true)
                tempApk.delete()
                return null
            } catch (e: Throwable) {
                tempApk.delete()
                return err("APK_WRITE_FAILED", "写回 APK 失败: ${e.message}", "className", className)
            }
        }

        /** 在 APK 的所有 DEX 中查找指定类, 返回 (dex条目名, 临时DEX文件) */
        private fun findClassInApk(apk: File, classDescriptor: String): Pair<String, File>? {
            ZipFile(apk).use { zf ->
                val dexEntries = zf.entries().toList().filter {
                    it.name.matches(Regex("classes\\d*\\.dex"))
                }
                for (dexEntry in dexEntries) {
                    val tempDex = File.createTempFile("classes_", ".dex")
                    zf.getInputStream(dexEntry).use { it.copyTo(tempDex.outputStream()) }
                    try {
                        val dexFile = DexFileFactory.loadDexFile(tempDex, Opcodes.getDefault())
                        if (dexFile.classes.any { it.type == classDescriptor }) {
                            // 返回临时文件的副本(因为外层会 close ZipFile)
                            val copy = File.createTempFile("classes_copy_", ".dex")
                            tempDex.copyTo(copy, overwrite = true)
                            return dexEntry.name to copy
                        }
                    } finally {
                        tempDex.delete()
                    }
                }
            }
            return null
        }

    }

    val ALL = listOf(smaliEdit)
}
