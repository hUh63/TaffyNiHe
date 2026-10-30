package com.soreverse.mcp.mcp

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import com.soreverse.mcp.core.ZipRepack
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import com.soreverse.mcp.engine.DexRenameApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * 塔菲逆核：dex 符号重命名（类 / 包 / 字段 / 方法 / 参数 / 局部变量）。
 *
 * 移植自 Dex Editor（net.dexedit.dex.DexRenameApi 及其 4 个 Renamer），只换掉了
 * dexlib2 的包名与调用约定 —— 重写规则（含内部类 `Outer$Inner`、包前缀、重载精确匹配）
 * 保持原样。这一族能力原先在塔菲里是缺的：其它 dex 工具都是**读**（索引/交叉引用/反编译）
 * 或**改字节**（smali 替换/字符串替换），没有「按符号重命名并级联所有引用」的入口。
 *
 * 与 smali 路线的分工：
 *  - `taffy_smali_edit`：给你 smali 源码，你改完它重编 —— 适合改逻辑；
 *  - `taffy_dex_rename`：只给旧名/新名，由 dexlib2 的 Rewriter 遍历全部类型引用 ——
 *    适合混淆前后对账、把 `a.a.a` 改成可读名字、改包名搬家。不会碰字符串常量里的反射类名。
 */
object DexRenameTool {

    private val dexRename: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_dex_rename",
            "【dex 符号重命名】按符号名重写 dex 并级联全部类型引用（字段类型/方法签名/指令/注解/枚举/数组）。" +
                "action=class 重命名单个类（含内部类 Outer\$Inner）及其引用；action=package 整个包前缀搬家（含子包）；" +
                "action=field/method 重命名字段或方法（方法按返回类型+参数类型精确匹配重载）；" +
                "action=param/local 改方法参数名与局部变量名（只影响调试信息，需目标带 .locals/参数名信息）。" +
                "对 APK 会自动遍历全部 classesN.dex 并在一次重打包内写回（保留条目压缩方式与对齐）；" +
                "dryRun=true 只统计命中数不落盘。注意：不会改字符串常量里的反射类名，改完若目标用了反射需要自行处理。",
            "Rename dex symbols and cascade every type reference (field types, method signatures, instructions, " +
                "annotations, enums, arrays). action=class renames one class (incl. inner Outer\$Inner) and its references; " +
                "action=package moves a whole package prefix (with subpackages); action=field/method rename a member " +
                "(methods matched by return type + parameter types for overloads); action=param/local rename debug names. " +
                "For APKs all classesN.dex are rewritten in a single repack that preserves compression/alignment; " +
                "dryRun=true only reports the hit count. String constants holding reflective class names are NOT rewritten.",
            "apk", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "path" str "APK 或 DEX 文件路径"
                "action".oneOf(
                    "class(重命名类) | package(整包搬家) | field(字段) | method(方法) | param(参数名) | local(局部变量名)",
                    "class", "package", "field", "method", "param", "local",
                )
                "owner" str "属主类（点分，如 com.example.Foo）—— field/method/param/local 必填"
                "oldName" str "旧类名/旧包名/旧成员名（class 传点分旧类名、package 传点分旧包名）"
                "newName" str "新名（class 传点分新类名、package 传点分新包名）"
                "type" str "字段类型或方法返回类型（可读形式：int / java.lang.String / int[]）"
                "params" str "参数类型，逗号分隔的可读形式（用于精确匹配重载）"
                "index" int "参数下标（从 0 起，不含 this）"
                "register" int "局部变量寄存器号"
                "output" str "输出路径（默认原地写回，并留 .bak.apk / .bak.dex 备份）"
                "dryRun" bool "只统计命中数，不写盘（默认 false）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path", "path", "")
            val file = File(path)
            if (!file.isFile) return err("FILE_NOT_FOUND", "文件不存在: $path", "path", path)
            val action = args.str("action", "class").lowercase()
            val newName = args.str("newName")
            if (newName.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 newName", "newName", "")
            val dryRun = args.optBoolean("dryRun", false)
            val isApk = file.name.endsWith(".apk", true) || file.name.endsWith(".zip", true)

            val slots = if (isApk) readApkDexes(file) else listOf(DexSlot(null, file.readBytes()))
            if (slots.isEmpty()) return err("NO_DEX", "没有找到任何 dex（APK 里没有 classes*.dex）", "path", path)

            val perDex = JSONArray()
            val replaced = LinkedHashMap<String, ByteArray>()
            var total = 0
            for (slot in slots) {
                val temp = File.createTempFile("rename_in_", ".dex")
                try {
                    temp.writeBytes(slot.bytes)
                    val loaded = DexFileFactory.loadDexFile(temp, Opcodes.getDefault())
                    val result = runCatching { applyRename(loaded, action, args, newName) }
                        .getOrElse { e ->
                            return err("RENAME_FAILED", "重命名失败（${e.javaClass.simpleName}）: ${e.message}", "action", action)
                        }
                    total += result.second
                    perDex.put(
                        JSONObject()
                            .put("dex", slot.entryName ?: file.name)
                            .put("classes", loaded.classes.count())
                            .put("renamedCount", result.second)
                    )
                    if (result.second > 0 && !dryRun) {
                        replaced[slot.entryName ?: file.name] = writeDex(result.first)
                    }
                } finally {
                    temp.delete()
                }
            }

            val changed = JSONArray()
            for (name in replaced.keys) changed.put(name)
            val payload = JSONObject()
                .put("action", action)
                .put("dryRun", dryRun)
                .put("totalRenamed", total)
                .put("dexes", perDex)
                .put("changedDex", changed)
            if (total == 0) {
                payload.put(
                    "hint",
                    "没有任何命中：确认 oldName/owner 用点分全名（如 com.example.Foo），" +
                        "方法还要给对返回类型与参数类型（重载按签名精确匹配）；局部变量/参数名需要目标带调试信息。"
                )
                return ok(payload)
            }
            if (dryRun) return ok(payload.put("hint", "dryRun 模式：只统计，未写盘"))

            val target = File(args.str("output").ifBlank { file.absolutePath })
            val backup = File(target.parentFile ?: file.parentFile, "${target.nameWithoutExtension}.bak.${if (isApk) "apk" else "dex"}")
            runCatching {
                if (!backup.exists()) file.copyTo(backup, overwrite = false)
            }

            if (!isApk) {
                target.writeBytes(replaced.values.first())
                return ok(payload.put("path", target.absolutePath).put("backup", backup.absolutePath))
            }

            // APK：一次重打包替换全部受影响的 dex 条目（ZipRepack 保留 method/extra 与对齐）
            val temp = File.createTempFile("apk_renamed_", ".apk", target.parentFile)
            return try {
                val applied = ZipRepack.copyAll(file, temp) { name -> replaced[name] }
                val missing = replaced.keys.filter { it !in applied }
                if (missing.isNotEmpty()) {
                    temp.delete()
                    return err(
                        "PATCH_NOT_APPLIED",
                        "有 dex 条目没写进产物: ${missing.joinToString()}",
                        "entry", missing.first()
                    )
                }
                ZipRepack.replaceAtomically(temp, target)
                temp.delete()
                ok(payload.put("path", target.absolutePath).put("backup", backup.absolutePath))
            } catch (e: Throwable) {
                temp.delete()
                err("APK_WRITE_FAILED", "写回 APK 失败: ${e.message}", "path", target.absolutePath)
            }
        }
    }

    private class DexSlot(val entryName: String?, val bytes: ByteArray)

    private fun readApkDexes(apk: File): List<DexSlot> {
        val out = ArrayList<DexSlot>()
        ZipFile(apk).use { zf ->
            val entries = zf.entries().toList()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .sortedBy { it.name }
            for (entry in entries) {
                out += DexSlot(entry.name, zf.getInputStream(entry).use { it.readBytes() })
            }
        }
        return out
    }

    /** 把一次重命名结果落成 (新 DexFile, 命中数)。 */
    private fun applyRename(
        src: DexFile,
        action: String,
        args: JSONObject,
        newName: String,
    ): Pair<DexFile, Int> {
        val owner = args.str("owner")
        val oldName = args.str("oldName")
        val type = args.str("type")
        val paramTypes = splitTypes(args.str("params"))
        val result = when (action) {
            "class" -> {
                val oldDotted = oldName.ifBlank { args.str("old") }
                if (oldDotted.isBlank()) throw IllegalArgumentException("class 需要 oldName（点分旧类名）")
                DexRenameApi.renameClass(src, oldDotted, newName, null)
            }
            "package" -> {
                if (oldName.isBlank()) throw IllegalArgumentException("package 需要 oldName（点分旧包名）")
                DexRenameApi.renamePackage(src, oldName, newName, null)
            }
            "field" -> {
                if (owner.isBlank() || oldName.isBlank()) throw IllegalArgumentException("field 需要 owner 和 oldName")
                DexRenameApi.renameField(src, owner, oldName, type, newName, null)
            }
            "method" -> {
                if (owner.isBlank() || oldName.isBlank()) throw IllegalArgumentException("method 需要 owner 和 oldName")
                DexRenameApi.renameMethod(src, owner, oldName, type.ifBlank { "void" }, paramTypes, newName, null)
            }
            "param" -> {
                if (owner.isBlank() || oldName.isBlank()) throw IllegalArgumentException("param 需要 owner 和 oldName（方法名）")
                val index = args.intValue("index", -1)
                if (index < 0) throw IllegalArgumentException("param 需要 index（参数下标，从 0 起，不含 this）")
                DexRenameApi.renameParam(src, owner, oldName, type.ifBlank { "void" }, paramTypes, index, newName)
            }
            "local" -> {
                if (owner.isBlank() || oldName.isBlank()) throw IllegalArgumentException("local 需要 owner 和 oldName（方法名）")
                val register = args.intValue("register", -1)
                if (register < 0) throw IllegalArgumentException("local 需要 register（局部变量寄存器号）")
                DexRenameApi.renameLocal(src, owner, oldName, type.ifBlank { "void" }, paramTypes, register, newName)
            }
            else -> throw IllegalArgumentException("未知 action: $action")
        }
        return result.dexFile to result.renamedCount
    }

    private fun splitTypes(raw: String): Array<String>? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyArray()
        return trimmed.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()
    }

    /** 把重命名后的 DexFile 序列化回 dex 字节（dexlib2 的 DexPool 写出）。 */
    private fun writeDex(dexFile: DexFile): ByteArray {
        val pool = DexPool(dexFile.opcodes ?: Opcodes.getDefault())
        for (cls in dexFile.classes) pool.internClass(cls)
        val store = MemoryDataStore()
        pool.writeTo(store)
        return store.data
    }

    val ALL = listOf(dexRename)
}
