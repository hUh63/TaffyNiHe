package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Frida Gadget 一键注入闭环。
 *
 * 原来这条链要手动跑 5 步（decode → 放 gadget → 配 config/脚本 → 注入入口 → 回编 → 重签），
 * 中间任何一步参数记错就要重来。这里把它们串成一个调用，并且**每一步都留痕**：
 * 失败时返回已完成到哪一步、卡在哪一步、原因为何，可以只补那一步。
 *
 * 用的都是现成工具（不重复实现）：taffy_apk_rebuild(decode/build) + taffy_apk_frida_gadget(place/patch_entry)
 * + taffy_apk_sign。gadget 从 taffy_frida_gadget_kit 的本地库里挑。
 */
object GadgetOneShotTool {

    val oneShot: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_gadget_one_shot",
            "【Gadget 一键注入】一条调用跑完整条链：解 APK(dex→smali) → 从 gadget 版本库挑 so → 放 gadget/config/脚本 → Manifest 改 extractNativeLibs → 入口类注入 loadLibrary → 回编 → 重签。返回每一步的结果与最终 APK 路径；失败会指出卡在哪一步（其余步骤已做的不会丢）。",
            "One-shot Frida Gadget injection: decode → pick gadget from library → place gadget/config/script → patch manifest → patch entry smali → rebuild → sign. Returns per-step results and the output APK; failures report the exact step.",
            "build", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "apk" str "目标 APK 路径（必填，工作区内）"
                "abi" str "目标 ABI（默认 arm64-v8a）"
                "version" str "gadget 版本（默认用本地库里该 ABI 的最新版本；可填 latest 或具体版本号）"
                "script" str "hook 脚本 .js 路径（可选）"
                "scriptText" str "hook 脚本内容（可选，优先级高于 script）"
                "entryClass" str "入口类全名（可选；不填自动从 Manifest 解析 Application/launcher）"
                "output" str "输出 APK 路径（可选；默认用回编产物的自动签名结果）"
                "keepDecoded" str "true/false：保留 decode 目录方便二次修改（默认 true）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val apkPath = args.str("apk").ifBlank { args.str("path") }
            if (apkPath.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 apk", "apk", "")
            val apk = File(apkPath)
            if (!apk.isFile) return err("FILE_NOT_FOUND", "APK 不存在: $apkPath", "apk", apkPath)
            val abi = args.str("abi", "arm64-v8a").ifBlank { "arm64-v8a" }
            val validAbis = setOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86", "x86_64")
            if (abi !in validAbis) return err("INVALID_ARGUMENT", "abi 必须是 ${validAbis.joinToString()} 之一", "abi", abi)

            val steps = JSONArray()
            fun record(name: String, r: JSONObject?, note: String = "") {
                val o = JSONObject()
                    .put("step", name)
                    .put("ok", r?.optBoolean("ok") == true)
                if (r != null) {
                    val out = r.optString("output")
                    if (out.isNotBlank()) o.put("output", out)
                    val signed = r.optString("signedApk")
                    if (signed.isNotBlank()) o.put("signedApk", signed)
                    val e = r.optJSONObject("error")
                    if (e != null) o.put("error", e.optString("message"))
                }
                if (note.isNotBlank()) o.put("note", note)
                steps.put(o)
            }

            fun tool(name: String): ToolHandler? = ToolCatalog.byName[name]

            // ── 1. 从 gadget 版本库挑 so ──
            val lib = findGadget(ctx, abi, args.str("version"))
            if (lib == null) {
                record("pick_gadget", null, "本地库没有 $abi 的 gadget")
                return err(
                    "GADGET_NOT_FOUND",
                    "本地 gadget 库里没有 $abi 的版本。先 taffy_frida_gadget_kit(action=download abis=[\"$abi\"]) 或 import 一个 so。",
                    "version", args.str("version"),
                    "steps" to steps,
                )
            }
            record("pick_gadget", ok(JSONObject().put("output", lib.absolutePath)),
                "版本 " + lib.parentFile.parentFile.name)

            // ── 2. decode（要 smali，才能注入入口） ──
            val rebuild = tool("taffy_apk_rebuild")
                ?: return err("TOOL_MISSING", "缺少工具 taffy_apk_rebuild")
            val decodeRes = rebuild.handle(ctx, JSONObject()
                .put("action", "decode")
                .put("path", apk.absolutePath)
                .put("dex", "true")
                .put("force", "true"))
            record("decode", decodeRes)
            if (decodeRes.optBoolean("ok") != true) {
                return err("DECODE_FAILED", "解包失败：" + decodeRes.optJSONObject("error")?.optString("message"),
                    "apk", apkPath, "steps" to steps)
            }
            val decodedDir = decodeRes.optString("output")
            if (decodedDir.isBlank() || !File(decodedDir).isDirectory) {
                return err("DECODE_FAILED", "解包没有返回目录路径", "apk", apkPath, "steps" to steps)
            }

            // ── 3. 放 gadget + config + 脚本 + 改 extractNativeLibs ──
            val fridaTool = tool("taffy_apk_frida_gadget")
                ?: return err("TOOL_MISSING", "缺少工具 taffy_apk_frida_gadget")
            val placeArgs = JSONObject()
                .put("action", "place")
                .put("dir", decodedDir)
                .put("gadget", lib.absolutePath)
                .put("abi", abi)
            if (args.str("script").isNotBlank()) placeArgs.put("script", args.str("script"))
            if (args.str("scriptText").isNotBlank()) placeArgs.put("script_text", args.str("scriptText"))
            val placeRes = fridaTool.handle(ctx, placeArgs)
            record("place_gadget", placeRes)
            if (placeRes.optBoolean("ok") != true) {
                return err("PLACE_FAILED", "放置 gadget 失败：" + placeRes.optJSONObject("error")?.optString("message"),
                    "abi", abi, "steps" to steps)
            }

            // ── 4. 入口类注入 loadLibrary ──
            val patchArgs = JSONObject().put("action", "patch_entry").put("dir", decodedDir)
            if (args.str("entryClass").isNotBlank()) patchArgs.put("class", args.str("entryClass"))
            val patchRes = fridaTool.handle(ctx, patchArgs)
            record("patch_entry", patchRes)
            if (patchRes.optBoolean("ok") != true) {
                return err("PATCH_FAILED", "入口注入失败：" + patchRes.optJSONObject("error")?.optString("message") +
                    "（若入口类被壳替换，可用 entryClass 指定真正的 Application）",
                    "dir", decodedDir, "steps" to steps)
            }

            // ── 5. 回编（taffy_apk_rebuild build 会自动签名） ──
            val buildRes = rebuild.handle(ctx, JSONObject()
                .put("action", "build")
                .put("path", decodedDir)
                .put("force", "true"))
            record("build", buildRes)
            if (buildRes.optBoolean("ok") != true) {
                return err("BUILD_FAILED", "回编失败：" + buildRes.optJSONObject("error")?.optString("message"),
                    "dir", decodedDir, "steps" to steps)
            }
            var outApk = buildRes.optString("signedApk")
            if (outApk.isBlank()) outApk = buildRes.optString("output")

            // ── 6. 没自动签名就手动签 ──
            if (buildRes.optString("signedApk").isBlank()) {
                val signTool = tool("taffy_apk_sign")
                if (signTool != null) {
                    val signArgs = JSONObject().put("inputApk", outApk)
                    if (args.str("output").isNotBlank()) signArgs.put("outputApk", args.str("output"))
                    val signRes = signTool.handle(ctx, signArgs)
                    record("sign", signRes)
                    if (signRes.optBoolean("ok") == true) {
                        outApk = signRes.optString("outputApk").ifBlank {
                            signRes.optString("output").ifBlank { outApk }
                        }
                    } else {
                        return err("SIGN_FAILED", "签名失败：" + signRes.optJSONObject("error")?.optString("message"),
                            "inputApk", outApk, "steps" to steps)
                    }
                } else {
                    record("sign", null, "缺少 taffy_apk_sign，未签名")
                }
            } else {
                record("sign", buildRes, "回编阶段已自动签名")
            }

            if (args.str("keepDecoded").ifBlank { "true" }.toBoolean() == false) {
                runCatching { File(decodedDir).deleteRecursively() }
            }

            return ok(JSONObject()
                .put("apk", apk.absolutePath)
                .put("abi", abi)
                .put("gadget", lib.absolutePath)
                .put("decodedDir", decodedDir)
                .put("outApk", outApk)
                .put("steps", steps)
                .put("next", "adb install -r \"$outApk\" 后启动目标即可自动加载 hook；" +
                    "若要改脚本，重跑本工具（gadget 版本与 ABI 会被复用）"))
        }
    }

    /**
     * 从本地库挑 gadget：显式版本优先；否则挑该 ABI 的"最高版本"（按目录名里的数字段比较，
     * 不依赖字符串排序——"17.9.10" 用字符串排序会排在 "17.10.0" 后面）。
     */
    private fun findGadget(ctx: ToolContext, abi: String, versionRaw: String): File? {
        val root = File(ctx.context.filesDir, "frida-gadgets")
        val gadgetName = "libfrida-gadget.so"
        val version = versionRaw.trim()
        if (version.isNotEmpty() && version != "latest") {
            val f = File(File(File(root, version), abi), gadgetName)
            return if (f.isFile) f else null
        }
        val candidates = root.listFiles()?.filter { it.isDirectory }?.mapNotNull { v ->
            val f = File(File(v, abi), gadgetName)
            if (f.isFile) f else null
        } ?: return null
        return candidates.maxWithOrNull(compareBy { versionScore(it.parentFile.parentFile.name) })
    }

    /** 把 "17.26.2" 变成可比较的数值（17*1e6 + 26*1e3 + 2），取不到数字就 0。 */
    private fun versionScore(v: String): Long {
        var score = 0L
        var i = 0
        for (part in v.split('.', '-', '_')) {
            val n = part.filter { it.isDigit() }.take(3).toLongOrNull() ?: 0L
            val weight = when (i) { 0 -> 1_000_000L; 1 -> 1_000L; 2 -> 1L; else -> 0L }
            score += n * weight
            i++
            if (i > 2) break
        }
        return score
    }

    val ALL = listOf(oneShot)
}
