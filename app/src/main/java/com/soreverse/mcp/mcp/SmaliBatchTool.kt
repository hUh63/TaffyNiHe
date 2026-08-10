package com.soreverse.mcp.mcp

import com.android.tools.smali.baksmali.Baksmali
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
                    "init(全量解包到smali目录) | rebuild(重编回DEX并写回APK) | diff(对比差异) | rollback(回滚) | list_snapshots(列快照)",
                    "init", "rebuild", "diff", "rollback", "list_snapshots")
                "path" str "APK 文件路径(init) 或 DEX 文件路径(init 单 dex, 用 isDex 标记)"
                "isDex" bool "init: true 表示 path 是单个 .dex 而非 APK(可选)"
                "workDir" str "rebuild: 工作目录(init 返回的)"
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
            val wantSign = args.optBoolean("sign", false)

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
                    }
                    ok(result)
                }
            }.getOrElse { e ->
                err("SMALI_BATCH_REBUILD_FAILED", "rebuild 失败: ${e.message ?: e.javaClass.simpleName}", "workDir", workDirPath)
            }
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

        /** 复用内置签名密钥, 输出签名 APK。成功返回签名后路径, 失败 null。 */
        private fun signApk(ctx: ToolContext, apk: File, outPath: String?): String? {
            return try {
                val signer = ApkBuildTool.obtainInternalSigner(ctx.context)
                val dest = if (outPath != null) File(outPath)
                    else File(apk.parentFile, "${apk.nameWithoutExtension}-signed.apk")
                val config = com.android.apksig.ApkSigner.SignerConfig.Builder("NIEHE", signer.first, listOf(signer.second)).build()
                com.android.apksig.ApkSigner.Builder(listOf(config))
                    .setInputApk(apk)
                    .setOutputApk(dest)
                    .build()
                    .sign()
                dest.absolutePath
            } catch (e: Exception) { null }
        }
    }

    val ALL = listOf(batch)
}
