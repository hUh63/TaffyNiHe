package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.DexRenameEngine
import com.soreverse.mcp.core.WorkspacePolicy
import com.soreverse.mcp.core.ZipRepack
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * DEX 重命名 / 反混淆：[DexRenameEngine] 的 MCP 入口。
 *
 * 三种用法：
 * 1. 手写改名：`renames=[{"kind":"class","from":"com.a.B","to":"com.x.Y"}, {"kind":"method",...}]`
 * 2. 导入 ProGuard mapping：`mappingPath=...`（jadx / R8 导出的那种）
 * 3. 先 `export_mapping` 拿到符号骨架，人工填新名，再 `import`
 *
 * 一律先 `plan`（dryRun，返回冲突与预估），确认后再 `apply` 落盘。
 */
object DexRenameTool {

    private const val ZH = """【DEX 重命名 / 反混淆】批量改类名、方法名、字段名或整个包名，也可以直接导入 ProGuard mapping。
用 dexlib2 的 rewriter 递归改写：类定义、指令里的 invoke/field 引用、注解、签名、调试信息全部跟着变，
不是文本替换。映射 key 按「所属类 + 名称 + 签名」精确匹配，不会把无关的同名方法一起改。
建议先 action=plan（dryRun，返回将改多少项 + 冲突列表），确认无误再 action=apply；
apply 输出到工作区 renamed/ 目录（不改原 APK，避免破坏 zipalign 与签名）。"""

    private const val EN = """Rename classes, methods, fields or a whole package inside DEX, or import a ProGuard mapping.
Uses dexlib2's rewriter, so class defs, invoke/field references, annotations, signatures and debug
info are all rewritten - not a text replace. Mapping keys are matched by owner + name + descriptor,
so unrelated same-named methods are untouched. Run action=plan (dry run with conflict report) before
action=apply; output goes to the workspace renamed/ dir (the original APK is never modified)."""

    val tool: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_dex_rename", ZH, EN, "decompile", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf(
                    "list(列类) | plan(试算) | apply(落盘) | export_mapping(导出骨架) | import_mapping(解析并试算)",
                    "plan", "list", "apply", "export_mapping", "import_mapping",
                )
                "path" str "APK 或 DEX 文件路径"
                "renames" str """改名列表，JSON 数组。例：
[{"kind":"class","from":"com.a.B","to":"com.x.Y"},
 {"kind":"method","from":"com.a.B","name":"foo","descriptor":"(I)V","to":"bar"},
 {"kind":"field","from":"com.a.B","name":"mCount","to":"mSize"},
 {"kind":"package","from":"com.old","to":"com.new"}]"""
                "mappingPath" str "ProGuard mapping 文件路径（与 renames 可同时用，先应用 mapping）"
                "limit" int "list / export_mapping 的上限（默认 list 5000、export 400）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "plan")
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path", "path", "")
            val file = File(path)
            if (!file.isFile) return err("FILE_NOT_FOUND", "文件不存在: $path", "path", path)

            return when (action) {
                "list" -> doList(file, args)
                "export_mapping" -> doExport(file, args)
                "plan", "import_mapping" -> doPlan(ctx, file, args, apply = false)
                "apply" -> doPlan(ctx, file, args, apply = true)
                else -> err("INVALID_ARGUMENT", "未知 action: $action", "action", action)
            }
        }

        private fun doList(file: File, args: JSONObject): JSONObject {
            val limit = args.intValue("limit", 5000).coerceIn(1, 20000)
            val classes = DexRenameEngine.listClasses(file, limit)
            val arr = JSONArray()
            classes.forEach { (type, methods, fields) ->
                arr.put(
                    JSONObject()
                        .put("name", DexRenameEngine.descriptorToName(type))
                        .put("descriptor", type)
                        .put("methods", methods)
                        .put("fields", fields),
                )
            }
            return ok(
                JSONObject()
                    .put("tool", "taffy_dex_rename")
                    .put("action", "list")
                    .put("count", classes.size)
                    .put("classes", arr),
            )
        }

        private fun doExport(file: File, args: JSONObject): JSONObject {
            val limit = args.intValue("limit", 400).coerceIn(1, 3000)
            val text = runCatching { DexRenameEngine.exportSkeleton(file, limit) }
                .getOrElse { e ->
                    return err("EXPORT_FAILED", "导出失败: ${e.message ?: e.javaClass.simpleName}", "path", file.path)
                }
            return ok(
                JSONObject()
                    .put("tool", "taffy_dex_rename")
                    .put("action", "export_mapping")
                    .put("count", text.lineSequence().count { it.startsWith("    ") })
                    .put("hint", "把每行右边改成新名字后，用 action=import_mapping + mappingPath 应用")
                    .put("mapping", text),
            )
        }

        private fun doPlan(ctx: ToolContext, file: File, args: JSONObject, apply: Boolean): JSONObject {
            val typeMap = LinkedHashMap<String, String>()
            val methodMap = LinkedHashMap<String, String>()
            val fieldMap = LinkedHashMap<String, String>()
            val warnings = ArrayList<String>()

            // 1) 先吃 mapping 文件（用户手写的 renames 优先级更高，后写覆盖）
            val mappingPath = args.str("mappingPath")
            if (mappingPath.isNotBlank()) {
                val mf = File(mappingPath)
                if (!mf.isFile) return err("FILE_NOT_FOUND", "mapping 不存在: $mappingPath", "mappingPath", mappingPath)
                val parsed = runCatching { DexRenameEngine.parseProguardMapping(mf.readText()) }
                    .getOrElse { e ->
                        return err("MAPPING_PARSE_FAILED", "解析失败: ${e.message}", "mappingPath", mappingPath)
                    }
                typeMap.putAll(parsed.plan.typeMap)
                methodMap.putAll(parsed.plan.methodMap)
                fieldMap.putAll(parsed.plan.fieldMap)
                warnings.addAll(parsed.warnings)
            }

            // 2) 再吃 renames 数组
            val renamesRaw = args.str("renames")
            if (renamesRaw.isNotBlank()) {
                val arr = runCatching { JSONArray(renamesRaw) }.getOrElse {
                    return err("INVALID_ARGUMENT", "renames 不是合法 JSON 数组", "renames", renamesRaw.take(120))
                }
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    when (o.str("kind")) {
                        "class" -> {
                            val from = toDescriptor(o.str("from"))
                            val to = toDescriptor(o.str("to"))
                            if (from.startsWith("L") && to.startsWith("L")) typeMap[from] = to
                            else warnings.add("类名不合法: ${o.str("from")} -> ${o.str("to")}")
                        }
                        "method" -> {
                            val owner = toDescriptor(o.str("from"))
                            val name = o.str("name")
                            val desc = o.str("descriptor").trim()
                            val to = o.str("to")
                            if (owner.startsWith("L") && name.isNotBlank() && to.isNotBlank()) {
                                // 不写 descriptor = 按名字匹配该类下所有同名方法（: * 通配）
                                val key = if (desc.isBlank()) "$owner->$name:*" else "$owner->$name$desc"
                                methodMap[key] = to
                            } else warnings.add("方法改名缺参数: $o")
                        }
                        "field" -> {
                            val owner = toDescriptor(o.str("from"))
                            val name = o.str("name")
                            val to = o.str("to")
                            if (owner.startsWith("L") && name.isNotBlank() && to.isNotBlank()) {
                                fieldMap["$owner->$name:*"] = to
                            } else warnings.add("字段改名缺参数: $o")
                        }
                        "package" -> {
                            val fromPkg = o.str("from").trim('.').replace('.', '/')
                            val toPkg = o.str("to").trim('.').replace('.', '/')
                            if (fromPkg.isBlank() || toPkg.isBlank()) {
                                warnings.add("包名不合法: $o"); continue
                            }
                            val hits = DexRenameEngine.listClasses(file, 20000)
                                .filter { it.first.startsWith("L$fromPkg/") }
                            if (hits.isEmpty()) warnings.add("包内没有类: ${o.str("from")}")
                            hits.forEach { (type, _, _) ->
                                typeMap[type] = "L$toPkg/" + type.removePrefix("L$fromPkg/")
                            }
                        }
                        else -> warnings.add("未知 kind: ${o.str("kind")}")
                    }
                }
            }

            val plan = DexRenameEngine.Plan(typeMap, methodMap, fieldMap)
            if (plan.isEmpty) {
                return err(
                    "EMPTY_PLAN", "没有任何改名条目（给 renames 或 mappingPath）",
                    "renames", renamesRaw.take(120),
                )
            }

            val conflicts = runCatching { DexRenameEngine.checkConflicts(file, plan) }
                .getOrElse { e ->
                    return err("SCAN_FAILED", "读取 DEX 失败: ${e.message ?: e.javaClass.simpleName}", "path", file.path)
                }

            val base = JSONObject()
                .put("tool", "taffy_dex_rename")
                .put("action", if (apply) "apply" else "plan")
                .put("classes", plan.typeMap.size)
                .put("methods", plan.methodMap.size)
                .put("fields", plan.fieldMap.size)
                .put("conflicts", JSONArray(conflicts))
                .put("warnings", JSONArray(warnings.distinct().take(50)))

            if (conflicts.isNotEmpty() && apply) {
                return err(
                    "RENAME_CONFLICT",
                    "存在 ${conflicts.size} 处命名冲突，先修正再 apply（前 3 条: ${conflicts.take(3).joinToString("；")}）",
                    "renames", renamesRaw.take(120),
                )
            }
            if (!apply) {
                base.put("dryRun", true)
                if (conflicts.isNotEmpty()) base.put("warning", "有 ${conflicts.size} 处冲突，apply 会被拒绝")
                return ok(base)
            }

            val workDir = WorkspacePolicy.workDirPath(ctx.context)
                ?: return err("NO_WORKSPACE", "工作区不可用，先在设置里选择工作目录", "path", file.path)
            val outDir = File(workDir, "renamed")
            val result = runCatching { DexRenameEngine.apply(file, plan, outDir) }
                .getOrElse { e ->
                    return err(
                        "RENAME_FAILED",
                        "重写失败: ${e.message ?: e.javaClass.simpleName}",
                        "path", file.path,
                    )
                }
            val outs = JSONArray()
            result.outFiles.forEach { outs.put(it.absolutePath) }

            // 输入是 APK 时顺手产出一个替换好 dex 的新 APK：否则只给一堆 dex，用户没法装回去。
            // 用 ZipRepack.copyAll 替换 entry（保留 method/extra 与 resources.arsc、so 的对齐），
            // 它返回真正被替换的条目集合，避免"报告说改了、产物其实原样"。
            var outApkPath: String? = null
            var replacedEntries: Set<String> = emptySet()
            if (file.name.endsWith(".apk")) {
                val target = File(outDir, file.nameWithoutExtension + "-renamed.apk")
                replacedEntries = runCatching {
                    ZipRepack.copyAll(
                        input = file,
                        output = target,
                        replace = { entry -> result.dexByEntry[entry]?.readBytes() },
                    )
                }.getOrElse { emptySet() }
                if (replacedEntries.isNotEmpty()) outApkPath = target.absolutePath
            }

            val resp = base.put("dryRun", false)
                .put("outFiles", outs)
                    .put("totalClasses", result.classes)
                    .put("renamedClasses", result.renamedClasses)
                    .put("renamedMethods", result.renamedMethods)
                    .put("renamedFields", result.renamedFields)
                    .put("hint", "输出目录: ${outDir.absolutePath}")
            if (outApkPath != null) {
                resp.put("outApk", outApkPath)
                resp.put("replacedEntries", JSONArray(replacedEntries.toList()))
                resp.put(
                    "signingWarning",
                    "APK 已被修改，原签名失效，安装前用 taffy_apk_sign 重新签名（v1/v2/v3）",
                )
            }
            return ok(resp)
        }

        /** `com.a.B` → `Lcom/a/B;`；已经是描述符的原样返回。 */
        private fun toDescriptor(name: String): String {
            val t = name.trim()
            if (t.startsWith("L") && t.endsWith(";")) return t
            return DexRenameEngine.javaTypeToDescriptor(t)
        }
    }

    val ALL = listOf(tool)
}
