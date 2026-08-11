package com.soreverse.mcp.mcp

import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import com.soreverse.mcp.core.ApkSigningPolicy
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * 塔菲逆核: smali 目录级「全量反编译 → 批量改 → 重打包签名」批处理工具（对标 MT 全量改包手感）。
 *
 * 与 taffy_smali_edit(单类增量)互补: 这里做整体大改。
 *  - init:     把 APK 全部 DEX 反汇编成 smali 目录(或只解单 DEX), 登记快照, 返回可读写的工作目录
 *  - rebuild:  把改后的 smali 目录重编回 DEX 写回 APK(自动备份原 APK), 可选 v1/v2/v3 签名
 *  - diff:     对比改动前后差异(字节级)
 *  - rollback: 用初始化时的快照还原原始 APK/DEX
 * 自带通用快照服务: 每次 init 前自动登记, 避免改错整包重来。
 */
object SmaliBatchTool {

    /** APK/输入大小上限(512MB) */
    private const val MAX_INPUT_BYTES = 512L * 1024L * 1024L

    /** APK 里识别 DEX 的条目名 */
    private fun isDexEntry(name: String) = name.matches(Regex("classes\\d*\\.dex"))

    val batch: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_smali_batch",
            "【Smali 全量批处理】把 APK 整体反编译到 smali 目录→批量改→重打包签名, 对标 MT 全量改包。action=init 把 APK 的全部 classes*.dex 反汇编成 smali 目录(登记快照并返回工作目录, 之后可用 taffy_file_* 任意改 smali 文件); action=rebuild 把所有 smali 目录重编回 DEX、写回原 APK(自动备份 .bak 并可选签名); action=diff 查看某快照改动前后差异; action=rollback 用快照还原原 APK。适合大范围跨多类修改; 单类小改仍用 taffy_smali_edit(更快)。",
            "Batch smali flow for whole-APK edits like MT: init disassembles all classes*.dex into a smali tree (snapshots original, returns work dir to edit freely via taffy_file_*); rebuild reassembles every smali dir back into its DEX, writes into the original APK (auto .bak + optional resign); diff shows changes vs a snapshot; rollback restores the original APK. Best for broad multi-class edits; for one class stick to taffy_smali_edit.",
            "build", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("批处理action",
                    "init(全量解包到smali目录) | rebuild(重编回DEX并写回APK) | rename_class(重命名类,去混淆) | diff_tree(对比目录改动) | diff(对比差异) | rollback(回滚) | list_snapshots(列快照)",
                    "init", "rebuild", "rename_class", "diff_tree", "diff", "rollback", "list_snapshots")
                "path" str "APK 文件路径(init) 或 DEX 文件路径(init 单 dex, 用 isDex 标记)"
                "isDex" bool "init: true 表示 path 是单个 .dex 而非 APK(可选)"
                "workDir" str "rebuild/rename_class: 工作目录(init 返回的)"
                "oldClass" str "rename_class: 要重命名的原始类全名(如 com.example.A)"
                "newClass" str "rename_class: 重命名后的类全名(如 com.example.MainActivity)"
                "apiLevel" int "smali/dex api level(默认 34)"
                "sign" bool "rebuild: 是否自动重签名(默认 false, 签名用内置密钥)"
                "signOutput" str "sign=true 时输出签名 APK 路径(默认 <原>-signed.apk)"
                "snapshotId" str "diff/rollback: 快照 ID"
                "decompiledPlan" bool "init: 仅输出将解出哪些 DEX 的计划而不实际解压(可选)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            return when (args.str("action", "init")) {
                "init" -> initWork(ctx, args)
                "rebuild" -> rebuildWork(ctx, args)
                "rename_class" -> renameClass(ctx, args)
                "diff_tree" -> diffTree(ctx, args)
                "diff" -> snapshotDiff(ctx, args)
                "rollback" -> snapshotRollback(ctx, args)
                "list_snapshots" -> snapshotList(ctx, args)
                else -> err("UNKNOWN_ACTION", "未知 action: ${args.str("action")}", "action", args.str("action"))
            }
        }

        /** init: 全量反编译 APK 的 DEX 到 smali 目录(或单 DEX), 登记快照, 返回工作目录。 */
        private fun initWork(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少 path(APK/DEX)", "path", "")
            val input = File(path)
            if (!input.isFile) return err("FILE_NOT_FOUND", "文件不存在: $path", "path", path)
            if (input.length() > MAX_INPUT_BYTES) {
                return err("INPUT_TOO_LARGE", "输入过大(${(input.length() / 1024 / 1024)}MB, 上限 512MB), 可能 OOM。请拆小或直接编辑单类", "path", path)
            }
            val isDex = args.optBoolean("isDex", false)
            val api = args.intValue("apiLevel", 34)
            val opcodes = if (api > 0) Opcodes.forApi(api) else Opcodes.getDefault()

            // 登记快照(原始文件)
            val snap = EditSnapshotService.snapshot(ctx.context, "taffy_smali_batch", input.absolutePath)
            val workRoot = File(ctx.context.filesDir, "smali-batch/${input.nameWithoutExtension}-${System.currentTimeMillis()}")
            workRoot.mkdirs()

            return runCatching {
                if (isDex || input.extension.equals("dex", true)) {
                    // 单 DEX 形态
                    val dexName = input.name
                    val smaliDir = File(workRoot, "smali")
                    smaliDir.mkdirs()
                    val dexFile = DexFileFactory.loadDexFile(input, opcodes)
                    val bopts = com.android.tools.smali.baksmali.BaksmaliOptions()
                    val okB = Baksmali.disassembleDexFile(dexFile, smaliDir, Runtime.getRuntime().availableProcessors().coerceIn(1, 4), bopts)
                    if (!okB) return@runCatching err("DISASSEMBLE_FAILED", "DEX 反汇编失败", "path", path)
                    val count = smaliDir.walkTopDown().count { it.isFile && it.extension == "smali" }
                    ok(JSONObject()
                        .put("action", "init")
                        .put("mode", "single.dex")
                        .put("dex", dexName)
                        .put("smaliDir", smaliDir.absolutePath)
                        .put("smaliFiles", count)
                        .put("manifestPath", writeTreeManifest(workRoot))
                        .put("workDir", workRoot.absolutePath)
                        .put("snapshotId", snap?.first ?: JSONObject.NULL)
                        .put("hint", "改完 smali 后调用 rebuild(action=rebuild, workDir=workDir, path=原$path)"))
                } else {
                    // APK 形态: 解所有 classes*.dex
                    if (args.optBoolean("decompiledPlan", false)) {
                        val dexPlan = JSONArray()
                        ZipFile(input).use { zf ->
                            zf.entries().toList().filter { isDexEntry(it.name) }.forEach { dexPlan.put(it.name) }
                        }
                        return@runCatching ok(JSONObject().put("action", "init").put("dry", true)
                            .put("dexEntries", dexPlan).put("hint", "以上 DEX 将被反编译"))
                    }
                    val dexMap = LinkedHashMap<String, File>() // dex条目名 -> smali目录
                    ZipFile(input).use { zf ->
                        val dexEntries = zf.entries().toList().filter { isDexEntry(it.name) }
                        if (dexEntries.isEmpty()) return@runCatching err("NO_DEX", "APK 内无 classes*.dex", "path", path)
                        for (de in dexEntries) {
                            // 单个 DEX 解压后过大也防 OOM
                            if (de.size > MAX_INPUT_BYTES / 2) {
                                return@runCatching err("DEX_TOO_LARGE", "DEX ${de.name} 解压后过大(${(de.size / 1024 / 1024)}MB), 可能 OOM", "path", path)
                            }
                            val tempDex = File.createTempFile("smb_", ".dex")
                            zf.getInputStream(de).use { it.copyTo(tempDex.outputStream()) }
                            try {
                                val dexFile = DexFileFactory.loadDexFile(tempDex, opcodes)
                                val smaliDir = File(workRoot, de.name.replace(".dex", "_smali"))
                                smaliDir.mkdirs()
                                val bopts = com.android.tools.smali.baksmali.BaksmaliOptions()
                                val okB = Baksmali.disassembleDexFile(dexFile, smaliDir, Runtime.getRuntime().availableProcessors().coerceIn(1, 4), bopts)
                                if (!okB) return@runCatching err("DISASSEMBLE_FAILED", "DEX 反汇编失败: ${de.name}", "path", path)
                                dexMap[de.name] = smaliDir
                            } finally { tempDex.delete() }
                        }
                    }
                    val plans = JSONArray()
                    dexMap.forEach { (name, dir) ->
                        val count = dir.walkTopDown().count { it.isFile && it.extension == "smali" }
                        plans.put(JSONObject().put("dex", name).put("smaliDir", dir.absolutePath).put("smaliFiles", count))
                    }
                    ok(JSONObject()
                        .put("action", "init")
                        .put("mode", "apk")
                        .put("apk", input.absolutePath)
                        .put("workDir", workRoot.absolutePath)
                        .put("dexCount", dexMap.size)
                        .put("snapshotId", snap?.first ?: JSONObject.NULL)
                        .put("dexes", plans)
                        .put("manifestPath", writeTreeManifest(workRoot))
                        .put("hint", "已把全部 DEX 反汇编到 workDir 下各 *_smali 目录, 可任意改; 改完调用 rebuild"))
                }
            }.getOrElse { e ->
                workRoot.deleteRecursively()
                err("SMALI_BATCH_INIT_FAILED", "init 失败: ${e.message ?: e.javaClass.simpleName}", "path", path)
            }
        }

        /** rebuild: 把所有 smali 目录重编回 DEX, 写回原 APK(备份原 APK), 可选签名。 */
        private fun rebuildWork(ctx: ToolContext, args: JSONObject): JSONObject {
            val workDirPath = args.str("workDir")
            val apkPath = args.str("path")
            if (workDirPath.isBlank()) return err("INVALID_ARGUMENT", "缺少 workDir(init 返回的)", "workDir", "")
            val workDir = File(workDirPath)
            if (!workDir.isDirectory) return err("DIR_NOT_FOUND", "工作目录不存在: $workDirPath", "workDir", workDirPath)
            val api = args.intValue("apiLevel", 34)
            val opcodes = if (api > 0) Opcodes.forApi(api) else Opcodes.getDefault()
            val wantSign = if (args.has("sign")) args.optBoolean("sign", false)
                else SettingsStore(ctx.context).apkAutoSign // 未显式指定时遵循设置页「修改APK后自动签名」

            return runCatching {
                // 判断是 single.dex 还是 apk 形态: 根据 smali 目录命名
                val smaliDirs = workDir.listFiles { f -> f.isDirectory && (f.name == "smali" || f.name.endsWith("_smali")) }?.toList() ?: emptyList()
                if (smaliDirs.isEmpty()) return@runCatching err("NO_SMALI", "工作目录无 smali 子目录", "workDir", workDirPath)
                if (workDir.listFiles()?.any { it.name == "smali" } == true && smaliDirs.size == 1) {
                    // single.dex 形态
                    val smaliDir = File(workDir, "smali")
                    val dexName = File(apkPath).name
                    val newDex = File.createTempFile("smb_out_", ".dex")
                    val opts = SmaliOptions().apply {
                        outputDexFile = newDex.absolutePath
                        this.apiLevel = api
                        jobs = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                    }
                    val okA = Smali.assemble(opts, listOf(smaliDir.absolutePath))
                    if (!okA) return@runCatching err("ASSEMBLE_FAILED", "smali 汇编失败(检查语法)", "workDir", workDirPath)
                    val bytes = newDex.readBytes(); newDex.delete()
                    val backup = File(File(apkPath).parentFile, "${File(apkPath).nameWithoutExtension}.bak.dex")
                    File(apkPath).copyTo(backup, overwrite = true)
                    File(apkPath).writeBytes(bytes)
                    ok(JSONObject()
                        .put("action", "rebuild")
                        .put("mode", "single.dex")
                        .put("restored", true)
                        .put("backupPath", backup.absolutePath)
                        .put("apiLevel", api)
                        .put("sign", false)
                        .put("hint", "DEX 已重建写回 $apkPath, 原文件备份在 $backup"))
                } else {
                    // apk 形态
                    if (apkPath.isBlank() || !File(apkPath).isFile) return@runCatching err("INVALID_ARGUMENT", "apk 形态需要 path 指向原 APK", "path", apkPath)
                    val newDexBytes = HashMap<String, ByteArray>()
                    var totalFiles = 0
                    for (smaliDir in smaliDirs) {
                        val dirName = smaliDir.name
                        // dirName 形如 classesN_smali -> dex 名 classesN.dex ; "smali" 这类不会到这分支
                        val dexName = if (dirName.endsWith("_smali")) dirName.removeSuffix("_smali") + ".dex" else dirName + ".dex"
                        val tempDex = File.createTempFile("smb_out_", ".dex")
                        val opts = SmaliOptions().apply {
                            outputDexFile = tempDex.absolutePath
                            this.apiLevel = api
                            jobs = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                        }
                        val okA = Smali.assemble(opts, listOf(smaliDir.absolutePath))
                        if (!okA) return@runCatching err("ASSEMBLE_FAILED", "smali 汇编失败: $dirName", "workDir", smaliDir.absolutePath)
                        newDexBytes[dexName] = tempDex.readBytes()
                        tempDex.delete()
                        totalFiles += smaliDir.walkTopDown().count { it.isFile && it.extension == "smali" }
                    }
                    val apkFile = File(apkPath)
                    val backup = File(apkFile.parentFile, "${apkFile.nameWithoutExtension}.bak.apk")
                    apkFile.copyTo(backup, overwrite = true)
                    // 重建 ZIP: 替换目标 DEX, 其余条目原样
                    val tempApk = File.createTempFile("apk_batch_", ".apk", apkFile.parentFile)
                    ZipFile(apkFile).use { zf ->
                        val zos = java.util.zip.ZipOutputStream(tempApk.outputStream())
                        var replaced = 0
                        zf.entries().toList().forEach { e ->
                            zos.putNextEntry(java.util.zip.ZipEntry(e.name))
                            val nb = newDexBytes[e.name]
                            if (nb != null) { zos.write(nb); replaced++ } else
                                zf.getInputStream(e).use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                        zos.close()
                        if (replaced != newDexBytes.size) return@runCatching err("ZIP_MISMATCH", "APK 内 DEX 条目数与重编数不一致", "path", apkPath)
                    }
                    apkFile.delete()
                    tempApk.copyTo(apkFile, overwrite = true)
                    tempApk.delete()

                    val result = JSONObject()
                        .put("action", "rebuild")
                        .put("mode", "apk")
                        .put("backupPath", backup.absolutePath)
                        .put("dexReplaced", newDexBytes.size)
                        .put("smaliFiles", totalFiles)
                        .put("sign", false)
                        .put("hint", "DEX 已重编写回 $apkPath, 原 APK 备份在 $backup, 签名已失效")
                    if (wantSign) {
                        val signed = signApk(ctx, apkFile, args.str("signOutput").takeIf { it.isNotBlank() })
                        if (signed != null) result.put("signed", true).put("signedApk", signed)
                        else result.put("signed", false).put("signError", "签名失败(可手动用 taffy_apk_sign)")
                    } else if (SettingsStore(ctx.context).apkKeepV2V3WhenNoSign) {
                        // 设置「不签名时保留 V2/V3 签名数据」：从原 APK 备份复制签名块到重编产物
                        val copied = ApkSigningPolicy.copyV2V3Blocks(backup, apkFile)
                        result.put("keepV2V3", copied > 0)
                    }
                    ok(result)
                }
            }.getOrElse { e ->
                err("SMALI_BATCH_REBUILD_FAILED", "rebuild 失败: ${e.message ?: e.javaClass.simpleName}", "workDir", workDirPath)
            }
        }

/** 重命名单个类(去混淆): 精确替换全目录 smali 里的类引用 + 移动类文件。只改解包目录, 不重编; 之后用 rebuild 回编校验。 */
        private fun renameClass(ctx: ToolContext, args: JSONObject): JSONObject {
            val workDirPath = args.str("workDir")
            val oldClass = args.str("oldClass")
            val newClass = args.str("newClass")
            if (workDirPath.isBlank()) return err("INVALID_ARGUMENT", "缺少 workDir", "workDir", "")
            if (oldClass.isBlank() || newClass.isBlank()) return err("INVALID_ARGUMENT", "缺少 oldClass 或 newClass", "oldClass", oldClass)
            val workDir = File(workDirPath)
            if (!workDir.isDirectory) return err("DIR_NOT_FOUND", "工作目录不存在: $workDirPath", "workDir", workDirPath)
            if (oldClass == newClass) return err("NOOP", "oldClass 与 newClass 相同", "newClass", newClass)

            val slOld = oldClass.replace('.', '/')
            val slNew = newClass.replace('.', '/')
            // 精确引用替换(先长后短, 避免内嵌类被纯; 前缀提前吞)
            val refOldInner = "L$slOld\$"
            val refNewInner = "L$slNew\$"
            val refOldSelf = "L$slOld;"
            val refNewSelf = "L$slNew;"
            val oldRelDir = slOld.substringBeforeLast('/', slOld)
            val newRelDir = slNew.substringBeforeLast('/', slNew)
            val oldSimple = slOld.substringAfterLast('/')
            val newSimple = slNew.substringAfterLast('/')

            return runCatching {
                var totalFiles = 0
                var totalRefs = 0
                var moved = 0
                var smaliDirs = workDir.listFiles { f -> f.isDirectory && f.name.endsWith("_smali") }?.toList() ?: emptyList()
                if (smaliDirs.isEmpty()) smaliDirs = workDir.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()

                for (smaliDir in smaliDirs) {
                    // 1. 全目录 smali 文本替换引用
                    val allSmali = smaliDir.walkTopDown().filter { it.isFile && it.extension == "smali" }.toList()
                    for (f in allSmali) {
                        val text = f.readText()
                        val replaced = text.replace(refOldInner, refNewInner).replace(refOldSelf, refNewSelf)
                        if (replaced != text) {
                            val refs = countOccurrences(text, refOldInner) + countOccurrences(text, refOldSelf)
                            f.writeText(replaced)
                            totalFiles++; totalRefs += refs
                        }
                    }
                    // 2. 移动类文件(自身 + 内嵌类)
                    val oldSelf = File(smaliDir, "$slOld.smali")
                    val targetMap = ArrayList<Pair<File, File>>()
                    if (oldSelf.isFile) {
                        targetMap.add(oldSelf to File(smaliDir, "$slNew.smali"))
                    }
                    // 内嵌类: oldRelDir 下 oldSimple$*.smali
                    val oldParentDir = File(smaliDir, oldRelDir)
                    if (oldParentDir.isDirectory) {
                        oldParentDir.listFiles { f -> f.isFile && f.name.startsWith("$oldSimple\$") && f.name.endsWith(".smali") }
                            ?.forEach { f ->
                                val inner = f.name.removePrefix("$oldSimple\$").removeSuffix(".smali") // 保留多级内嵌, 如 B$C
                                targetMap.add(f to File(File(smaliDir, newRelDir), "$newSimple\$$inner.smali"))
                            }
                    }
                    for ((src, dst) in targetMap) {
                        dst.parentFile?.mkdirs()
                        src.copyTo(dst, overwrite = true)
                        src.delete()
                        moved++
                    }
                }
                ok(JSONObject()
                    .put("action", "rename_class")
                    .put("oldClass", oldClass)
                    .put("newClass", newClass)
                    .put("filesChanged", totalFiles)
                    .put("refsChanged", totalRefs)
                    .put("filesMoved", moved)
                    .put("hint", "类引用已重命名并移动文件。用 action=rebuild 重编回 DEX 校验语法/引用一致性后再签名。若重编报错(引用断裂), 用 action=rollback 还原。"))
            }.getOrElse { e ->
                err("RENAME_CLASS_FAILED", "重命名失败: ${e.message ?: e.javaClass.simpleName}", "oldClass", oldClass)
            }
        }

/** 生成工作目录 tree 快照(所有 *_smali 下 .smali 文件的相对路径+SHA), 存 workDir/manifest.json。 */
        private fun writeTreeManifest(workRoot: File): String {
            return runCatching {
                val manifest = JSONObject()
                    .put("tool", "taffy_smali_batch")
                    .put("createdAt", System.currentTimeMillis())
                val files = JSONArray()
                val smaliDirs = workRoot.listFiles { f -> f.isDirectory && f.name.endsWith("_smali") }?.toList() ?: emptyList()
                for (dir in smaliDirs) {
                    dir.walkTopDown().filter { it.isFile && it.extension == "smali" }.forEach { f ->
                        files.put(JSONObject()
                            .put("path", f.relativeTo(workRoot).path)
                            .put("sha256", EditSnapshotService.sha256(f))
                            .put("size", f.length()))
                    }
                }
                manifest.put("files", files)
                val mf = File(workRoot, "manifest.json")
                mf.writeText(manifest.toString(), Charsets.UTF_8)
                mf.absolutePath
            }.getOrElse { "" }
        }

        /** 对比工作目录当前 .smali 文件树与 init 时的清单, 报告新增/删除/修改。 */
        private fun diffTree(ctx: ToolContext, args: JSONObject): JSONObject {
            val workDirPath = args.str("workDir")
            if (workDirPath.isBlank()) return err("INVALID_ARGUMENT", "缺少 workDir", "workDir", "")
            val workDir = File(workDirPath)
            if (!workDir.isDirectory) return err("DIR_NOT_FOUND", "工作目录不存在: $workDirPath", "workDir", workDirPath)
            val mf = File(workDir, "manifest.json")
            if (!mf.isFile) return err("NO_MANIFEST", "无 init 清单(manifest.json)。先 action=init 建立基线再 diff", "workDir", workDirPath)
            return runCatching {
                val manifest = JSONObject(mf.readText())
                val baseline = HashMap<String, JSONObject>()
                val arr = manifest.optJSONArray("files") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    baseline[o.optString("path")] = o
                }
                // 当前文件树
                val current = HashMap<String, Long>()
                workDir.walkTopDown().filter { it.isFile && it.extension == "smali" }.forEach { f ->
                    current[f.relativeTo(workDir).path] = f.length()
                }
                val added = JSONArray()   // 新增(不在基线)
                val removed = JSONArray() // 删除(基线有但当前无)
                val modified = JSONArray() // 修改(大小不同)
                val unchanged = JSONArray()
                for ((path, size) in current.entries.sortedBy { it.key }) {
                    val b = baseline[path]
                    if (b == null) added.put(path)
                    else if (b.optLong("size") != size) modified.put(path)
                    else unchanged.put(path)
                }
                for (path in baseline.keys.filter { !current.containsKey(it) }.sorted()) removed.put(path)
                ok(JSONObject()
                    .put("action", "diff_tree")
                    .put("workDir", workDirPath)
                    .put("baselineFiles", baseline.size)
                    .put("currentFiles", current.size)
                    .put("added", added)
                    .put("removed", removed)
                    .put("modified", modified)
                    .put("unchangedCount", unchanged.length())
                    .put("hint", "added/removed/modified 为相对 init 基线的新增/删除/修改文件。改完用 action=rebuild 回编。")
                    .put("previousHint", "与 taffy_smali_batch 解出的初始状态对比, 方便确认改动的文件范围"))
            }.getOrElse { e ->
                err("DIFF_TREE_FAILED", "目录 diff 失败: ${e.message ?: e.javaClass.simpleName}", "workDir", workDirPath)
            }
        }
        private fun countOccurrences(haystack: String, needle: String): Int {
            if (needle.isEmpty()) return 0
            var count = 0; var idx = 0
            while (idx <= haystack.length - needle.length) {
                val at = haystack.indexOf(needle, idx)
                if (at < 0) break
                count++; idx = at + needle.length
            }
            return count
        }
        private fun snapshotDiff(ctx: ToolContext, args: JSONObject): JSONObject {
            val id = args.str("snapshotId")
            if (id.isBlank()) return err("INVALID_ARGUMENT", "缺少 snapshotId", "snapshotId", "")
            return ok(JSONObject().put("diff", EditSnapshotService.diff(ctx.context, id, "taffy_smali_batch")))
        }

        private fun snapshotRollback(ctx: ToolContext, args: JSONObject): JSONObject {
            val id = args.str("snapshotId")
            if (id.isBlank()) return err("INVALID_ARGUMENT", "缺少 snapshotId", "snapshotId", "")
            return ok(JSONObject().put("rollback", EditSnapshotService.rollback(ctx.context, id, "taffy_smali_batch", args.str("targetPath").takeIf { it.isNotBlank() })))
        }

        private fun snapshotList(ctx: ToolContext, args: JSONObject): JSONObject {
            return ok(JSONObject().put("tool", "taffy_smali_batch").put("snapshots", EditSnapshotService.list(ctx.context, "taffy_smali_batch")))
        }

        /** 按签名策略（密钥来源/方案/V1 文件名）签名, 输出签名 APK。成功返回签名后路径, 失败 null。 */
        private fun signApk(ctx: ToolContext, apk: File, outPath: String?): String? {
            return try {
                val signer = ApkSigningPolicy.resolveSigner(ctx.context) ?: return null
                val dest = if (outPath != null) File(outPath)
                    else File(apk.parentFile, "${apk.nameWithoutExtension}-signed.apk")
                val (v1, v2, v3) = ApkSigningPolicy.schemeFlags(ctx.context)
                val v1Name = ApkSigningPolicy.v1SignerName(ctx.context)
                // 构造器第一个参数即 V1 签名者名（META-INF/<name>.RSA/.SF）；再尝试独立 setter（apksig 新版本）
                val cfgBuilder = com.android.apksig.ApkSigner.SignerConfig.Builder(v1Name, signer.first, listOf(signer.second))
                runCatching {
                    cfgBuilder.javaClass.getMethod("setV1SignerName", String::class.java).invoke(cfgBuilder, v1Name)
                }
                com.android.apksig.ApkSigner.Builder(listOf(cfgBuilder.build()))
                    .setInputApk(apk)
                    .setOutputApk(dest)
                    .setV1SigningEnabled(v1)
                    .setV2SigningEnabled(v2)
                    .setV3SigningEnabled(v3)
                    .build()
                    .sign()
                dest.absolutePath
            } catch (e: Exception) { null }
        }
    }

    val ALL = listOf(batch)
}
