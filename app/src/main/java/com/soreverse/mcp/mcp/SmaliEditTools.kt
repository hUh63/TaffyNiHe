package com.soreverse.mcp.mcp

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import com.soreverse.mcp.core.ZipRepack
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

    /** 安全加固：装载前大小守卫（超限返回 err，null 表示放行）。 */
    private fun sizeGuard(f: java.io.File?): JSONObject? {
        if (f == null || !f.isFile) return null
        if (f.length() > MAX_INPUT_BYTES) {
            return err("FILE_TOO_LARGE", "文件 ${f.length()} 字节超过处理上限 512MB: ${f.name}")
        }
        return null
    }


    /** Smali 增量编辑 */
    val smaliEdit: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_smali_edit",
            "【Smali 增量编辑】不改包整个 APK, 只提取/替换单个类或单条指令的 smali。action=list_classes 列出 APK 中的类(按包名过滤); action=extract 提取指定类的 smali 代码; action=replace 用新 smali 替换指定类(自动定位 DEX→重编→写回 APK); action=list_methods 列出类的方法签名; action=patch_instruction 仅替换方法内一条指令(如改 const 常量/return 返回值, 对标 MT 改一行代码)。参考 MT管理器的 edit_open/edit_text 增量流程, 用 Google smali 库实现, 多 DEX 自动定位。注意: replace/patch_instruction 会重编整个 DEX 再写回, 大 DEX 较慢。",
            "Incremental smali editing without full APK unpacking. action=list_classes lists classes (filter by package); extract gets smali for one class; replace writes new smali (auto-locates DEX→recompiles→writes back to APK); list_methods lists method signatures; patch_instruction replaces a single instruction inside a method (e.g. change a const literal / return value — MT-style 'change one line'). Uses Google smali library, auto multi-DEX. Note: replace/patch_instruction recompile the whole DEX then write back, slow on large DEX.",
            "build", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("list_classes | extract | replace | list_methods | patch_instruction | replace_string", "list_classes", "extract", "replace", "list_methods", "patch_instruction", "replace_string")
                "path" str "APK 文件路径"
                "className" str "类全名(如 com.example.Foo), list_classes/extract/replace/list_methods/replace_string 用"
                "packagePrefix" str "list_classes: 包名前缀过滤(如 com.example), 加快速度"
                "smali" str "replace: 新的 smali 代码(完整 .smali 文件内容)"
                "method" str "patch_instruction/replace_string: 目标方法名(如 onCreate)。可用 \";\" 追加参数签名定位重载"
                "from" str "patch_instruction: 要替换的原始指令(需在方法体内唯一或取首次匹配)"
                "to" str "patch_instruction: 替换后的新指令"
                "occurrence" int "patch_instruction/replace_string: 替换第几处匹配(1 起, 默认 1)"
                "oldString" str "replace_string: 要替换的原始字符串常量(方法体内 const-string 的值)"
                "newString" str "replace_string: 替换后的新字符串"
                "mode".oneOf("replace_string 模式", "preserve(默认: 新串长度≤旧串, 不改结构心智) | recompile(允许变长重编)", "preserve", "recompile")
                "scope".oneOf("replace_string 范围", "method(默认: 仅目标方法内) | class(整个类文件) | global(整个 APK 所有 DEX)", "method", "class", "global")
                "limit" int "list_classes: 最多返回条数(默认 100)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            // 清理历史任务残留：smali 流程有多条提前 return 分支，逐个补 finally 容易漏，
            // 改为进入时按前缀回收过期目录（baksmali 工作目录 + 临时 dex）。
            com.soreverse.mcp.core.TempJanitor.sweep(ctx.context, listOf("smali_patch_", "smali_replace_", "class"))
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

                sizeGuard(smaliFile)?.let { return it }
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
                sizeGuard(newDex)?.let { return it }
                val newDexBytes = newDex.readBytes()
                newDex.delete()
                tempDex.delete()

                val tempApk = File.createTempFile("apk_patched_", ".apk", apk.parentFile)
                // 保留原条目压缩方式/extra 并对齐（否则改一个 dex 会让 resources.arsc / lib/*.so 被重新压缩，
                // 产物直接装不上，而工具还返回成功）
                ZipRepack.copyAll(apk, tempApk) { name -> if (name == dexEntryName) newDexBytes else null }
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
