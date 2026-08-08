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
 * 塔菲逆核已有 baksmali_decode(全量解包) + smali_assemble(全量重编), 但缺少:
 *  - 增量提取: 只提取指定类的 smali, 不解包整个 DEX
 *  - 增量替换: 只改一个类, 自动重编该 DEX + 写回 APK
 *  - 多 DEX 自动定位: 自动找到类在哪个 classes*.dex 里
 *  - 类列表: 列出 APK 中所有类(按包名过滤)
 *
 * 工作流:
 *  1. smali_edit action=list_classes  → 看有哪些类
 *  2. smali_edit action=extract       → 提取目标类的 smali 代码
 *  3. (AI 修改 smali 代码)
 *  4. smali_edit action=replace       → 替换该类, 自动重编 DEX + 写回 APK
 */
object SmaliEditTools {

    /** Smali 增量编辑 */
    val smaliEdit: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "smali_edit",
            "【Smali 增量编辑】不改包整个 APK, 只提取/替换单个类的 smali。action=list_classes 列出 APK 中的类(按包名过滤); action=extract 提取指定类的 smali 代码; action=replace 用新 smali 替换指定类(自动定位 DEX→重编→写回 APK); action=list_methods 列出类的方法签名。参考 MT管理器的 edit_open/edit_text 增量流程, 但用 Google smali 库实现。多 DEX 自动定位。",
            "Incremental smali editing without full APK unpacking. action=list_classes lists classes (filter by package); extract gets smali for one class; replace writes new smali (auto-locates DEX→recompiles→writes back to APK); list_methods lists method signatures. Inspired by MT's edit_open/edit_text, uses Google smali library. Auto multi-DEX.",
            "build", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("list_classes | extract | replace | list_methods", "list_classes", "extract", "replace", "list_methods")
                "path" str "APK 文件路径"
                "className" str "类全名(如 com.example.Foo), list_classes/extract/replace/list_methods 用"
                "packagePrefix" str "list_classes: 包名前缀过滤(如 com.example), 加快速度"
                "smali" str "replace: 新的 smali 代码(完整 .smali 文件内容)"
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
                .put("hint", "用 smali_edit action=extract 提取某个类的 smali 代码"))
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
                    .put("hint", "修改 smali 后用 smali_edit action=replace 写回"))
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
            val (_, tempDex) = findClassInApk(apk, classDescriptor)
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

                val dexEntryName = findDexEntryName(apk, classDescriptor)
                    ?: return err("CLASS_NOT_FOUND", "替换后找不到类所在的 DEX 条目", "className", className)

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
                    .put("hint", "DEX 已重编并写回 APK, 签名已失效. 用 apk_sign 或 apk_rebuild 重新签名"))

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
                    .put("hint", "用 smali_edit action=extract 提取完整 smali 代码"))
            } finally {
                tempDex.delete()
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

        /** 只查找类在哪个 DEX 条目, 不返回文件 */
        private fun findDexEntryName(apk: File, classDescriptor: String): String? {
            ZipFile(apk).use { zf ->
                val dexEntries = zf.entries().toList().filter {
                    it.name.matches(Regex("classes\\d*\\.dex"))
                }
                for (dexEntry in dexEntries) {
                    val tempDex = File.createTempFile("chk_", ".dex")
                    zf.getInputStream(dexEntry).use { it.copyTo(tempDex.outputStream()) }
                    try {
                        val dexFile = DexFileFactory.loadDexFile(tempDex, Opcodes.getDefault())
                        if (dexFile.classes.any { it.type == classDescriptor }) {
                            return dexEntry.name
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
