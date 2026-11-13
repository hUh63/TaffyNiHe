package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.DexUnpackChain
import com.soreverse.mcp.core.RootShell
import com.soreverse.mcp.core.UnpackManager
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一键脱壳链：`dump → 扫描 → 解密 → 修头 → 落盘 → 复核`。
 *
 * 单支工具各自都只负责一段（[UnpackTool] 只 dump、[DexXorScanTool] 只扫解、[DexAesDecryptTool] 只解 AES），
 * 真正难的是**串起来并判断断在哪一段**。这里把每一段的结论都写进 `stages`，失败写进 `failures`，
 * 所以「脱不出来」不再是一个笼统的失败，而是一个可定位的段落。
 */
object DexUnpackChainTool {

    val unpackChain: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_dex_unpack_chain",
            "【一键脱壳链】把脱壳的整条链跑完：可选先从运行中的进程 dump 内存（需 root）、扫出明文/XOR/AES 藏的 dex、重算头、落盘、并复核每个产物。返回 stages（list/scan/recover/fix/verify 各段状态）+ recovered（产物路径/模式/偏移）+ failures（卡在哪一段、为什么）+ next（下一步建议）。适合「一次拿到能直接喂给反编译器的 dex」，也适合判断脱壳到底断在哪一步。",
            "One-shot unpack chain: optional memory dump (root) → scan for plain/XOR/AES hidden dex → recompute headers → write to disk → verify each output. Returns per-stage status, recovered list, failures with the failing stage, and a next-step hint.",
            "dynamic", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("run (default) | plan（只枚举目录不处理）", "run", "plan")
                "package" str "目标包名：给了就先从该进程内存 dump dex（需 root，且要先把 App 打开）"
                "pkg" str "package 的别名"
                "dir" str "已有 dump 目录（不给 package 时必填；给了 package 则默认 filesDir/dexdump/<pkg>）"
                "outDir" str "产物目录（默认 <dir>/dex-recovered）"
                "modes" str "扫描模式，逗号分隔：plain,xor1,xorn（默认全开）"
                "aes" str "true/false：XOR 扫不到时再试 AES 穷举（慢，默认 false）"
                "fix" str "true/false：落盘前重算 dex 头（默认 true）"
                "maxFiles" int "最多处理多少个文件（默认 64）"
                "minDexSize" int "dump 时有效 dex 最小字节（默认 112）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action", "run").ifBlank { "run" }
            val pkg = args.str("package").ifBlank { args.str("pkg") }.trim()
            var dirPath = args.str("dir").trim()
            val dumpStage = JSONObject()

            // ── 第 0 段：dump（可选） ──────────────────────────────
            if (dirPath.isBlank()) {
                if (pkg.isBlank()) {
                    return err(
                        "INVALID_ARGUMENT",
                        "需要 package（先从进程内存 dump）或 dir（已有 dump 目录）",
                        "dir", "",
                        "hint" to "已有 dump 就直接传 dir；没有就先 taffy_dex_unpack action=pslist 找包名",
                    )
                }
                if (!RootShell.isRootAvailable()) {
                    return err(
                        "NO_ROOT",
                        "内存 dump 需要 root；没有 root 就传 dir 指向已拿到的 dump / so 目录",
                        "package", pkg,
                    )
                }
                val safe = pkg.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                val dumpDir = File(ctx.context.filesDir, "dexdump/$safe")
                val result = UnpackManager.dumpDex(pkg, dumpDir, args.intValue("minDexSize", 0x70))
                dumpStage.put("name", "dump")
                dumpStage.put("status", if (result.success) "ok" else "failed")
                dumpStage.put("detail", result.message)
                dumpStage.put("pid", result.pid)
                dumpStage.put("dexCount", result.dexFiles.size)
                if (!result.success) {
                    return err(
                        "UNPACK_NO_DEX", result.message,
                        "package", pkg,
                        "hint" to "先把目标 App 打开停留几秒，让壳把 dex 解密进内存后再跑",
                        "stages" to JSONArray().put(dumpStage),
                    )
                }
                dirPath = dumpDir.absolutePath
            }
            val inputDir = File(dirPath)
            if (!inputDir.isDirectory) {
                return err("DIR_NOT_FOUND", "目录不存在: $dirPath", "dir", dirPath)
            }

            val outDirPath = args.str("outDir").trim().ifBlank { File(inputDir, "dex-recovered").absolutePath }
            val opts = DexUnpackChain.Options(
                modes = DexUnpackChain.Options.parseModes(args.str("modes")),
                doAes = args.str("aes", "false").trim().equals("true", true),
                doFix = args.str("fix", "true").trim().let { it.isEmpty() || it.equals("true", true) },
                maxFiles = args.intValue("maxFiles", DexUnpackChain.DEFAULT_MAX_FILES).coerceIn(1, 512),
            )

            if (action == "plan") {
                val names = JSONArray()
                var n = 0
                runCatching {
                    inputDir.listFiles()?.forEach { f ->
                        if (f.isFile) {
                            n++
                            if (names.length() < 64) names.put(JSONObject().put("name", f.name).put("size", f.length()))
                        }
                    }
                }
                return ok(
                    JSONObject()
                        .put("action", "plan")
                        .put("dir", inputDir.absolutePath)
                        .put("fileCount", n)
                        .put("files", names)
                        .put("outDir", outDirPath)
                        .put("options", JSONObject().put("modes", opts.modes.joinToString(",")).put("aes", opts.doAes).put("fix", opts.doFix)),
                )
            }

            val result = DexUnpackChain.run(inputDir, File(outDirPath), opts)
            // dump 段在前，避免看到的 stages 从 list 开始好像没有 dump
            if (dumpStage.length() > 0) {
                val merged = JSONArray().put(dumpStage)
                val existing = result.optJSONArray("stages") ?: JSONArray()
                for (i in 0 until existing.length()) merged.put(existing.opt(i))
                result.put("stages", merged)
            }
            result.put("dir", inputDir.absolutePath)
            if (pkg.isNotBlank()) result.put("package", pkg)
            return ok(result)
        }
    }

    val ALL = listOf(unpackChain)
}
