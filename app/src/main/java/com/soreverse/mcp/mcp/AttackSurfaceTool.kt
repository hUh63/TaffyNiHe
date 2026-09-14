package com.soreverse.mcp.mcp

import com.reandroid.apk.ApkModule
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 塔菲逆核: APK 组件攻击面分析。
 *
 * 用 ARSCLib 解码 AndroidManifest(AXML 级, 非正则猜), 输出对外暴露的攻击面:
 * exported 组件(activity/service/receiver/provider)、intent-filter 的 action、
 * uses-permission、以及 application 层的危险开关(debuggable / allowBackup /
 * usesCleartextTraffic / networkSecurityConfig)。
 *
 * 用途: 逆向/安全评估时快速回答「这个 App 对外暴露了哪些入口、可能被谁唤起、
 * 有哪些危险配置」——MT 那类工具的「攻击面」面板对位能力。纯只读。
 */
object AttackSurfaceTool {

    /** (组件类型, XML 标签) */
    private val COMPONENT_TAGS = listOf("activity", "activity-alias", "service", "receiver", "provider")

    val tool: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "taffy_attack_surface",
            "【APK 组件攻击面】用 ARSCLib 解码 AndroidManifest, 列出对外暴露的攻击面: exported 组件" +
                "(activity/service/receiver/provider)、各自的 intent-filter action、uses-permission、" +
                "以及 application 级危险开关(debuggable/allowBackup/usesCleartextTraffic/networkSecurityConfig)。" +
                "回答「这个 App 暴露了哪些入口、可能被谁唤起、有哪些危险配置」。纯只读。",
            "APK component attack-surface analysis via ARSCLib manifest decode: exported activities/services/" +
                "receivers/providers, their intent-filter actions, uses-permissions, and dangerous application " +
                "flags (debuggable/allowBackup/usesCleartextTraffic/networkSecurityConfig). Read-only.",
            "analyze", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "path" str "APK 文件路径"
                "detail" bool "是否返回每个组件的 intent-filter action 明细(默认 true)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少 path", "path", "")
            val f = File(path)
            if (!f.isFile) return err("FILE_NOT_FOUND", "APK 不存在: $path", "path", path)
            val detail = args.optBoolean("detail", true)
            return runCatching {
                val apk = ApkModule.loadApkFile(f)
                val xml = apk.androidManifest.toString()

                val pkg = Regex("""package\s*=\s*"([^"]+)""").find(xml)?.groupValues?.get(1).orEmpty()

                // --- exported 组件 ---
                val exported = JSONArray()
                var exportedCount = 0
                for (tag in COMPONENT_TAGS) {
                    val re = Regex("<" + tag + """\b[^>]*>""")
                    for (m in re.findAll(xml)) {
                        val openTag = m.value
                        val name = Regex("""android:name\s*=\s*"([^"]+)""").find(openTag)?.groupValues?.get(1).orEmpty()
                        val exp = Regex("""android:exported\s*=\s*"([^"]+)""").find(openTag)?.groupValues?.get(1)
                        // exported 未显式声明时: 含 intent-filter 的组件在旧 API 上默认 true
                        val isExported = when (exp?.lowercase()) {
                            "true" -> true
                            "false" -> false
                            else -> false
                        }
                        if (!isExported) continue
                        exportedCount++
                        val obj = JSONObject()
                            .put("type", tag)
                            .put("name", name.ifBlank { "(unnamed)" })
                            .put("exported", true)
                        if (detail) {
                            // 该组件后续 intent-filter 段
                            val start = m.range.last
                            val tail = xml.substring(start, minOf(xml.length, start + 1200))
                            val actions = Regex("""<action\b[^>]*android:name\s*=\s*"([^"]+)""")
                                .findAll(tail).map { it.groupValues[1] }.take(8).toList()
                            if (actions.isNotEmpty()) obj.put("actions", JSONArray(actions))
                        }
                        exported.put(obj)
                    }
                }

                // --- permissions ---
                val perms = JSONArray()
                Regex("""<uses-permission\b[^>]*android:name\s*=\s*"([^"]+)""")
                    .findAll(xml).forEach { perms.put(it.groupValues[1]) }

                // --- application 危险开关 ---
                val appTag = Regex("""<application\b[^>]*>""").find(xml)?.value.orEmpty()
                fun attr(key: String): String? =
                    Regex("""android:$key\s*=\s*"([^"]+)""").find(appTag)?.groupValues?.get(1)
                val debuggable = attr("debuggable")?.toBoolean() ?: false
                val allowBackup = attr("allowBackup")?.toBoolean() ?: true
                val cleartext = attr("usesCleartextTraffic")?.toBoolean() ?: false
                val nsc = attr("networkSecurityConfig")

                val risks = JSONArray()
                if (debuggable) risks.put("application 可调试 (android:debuggable=true) — 可 attach 调试/动态分析")
                if (allowBackup) risks.put("允许备份 (android:allowBackup=true 或缺省) — 可能被 adb backup 提取私有数据")
                if (cleartext) risks.put("允许明文流量 (usesCleartextTraffic=true) — 可被中间人")
                if (nsc.isNullOrBlank()) risks.put("未配置 networkSecurityConfig — 无域名级证书固定")
                if (exportedCount > 0) risks.put("存在 $exportedCount 个 exported 组件 — 可被外部 App 唤起, 需逐一核对权限/校验")

                ok(
                    JSONObject()
                        .put("tool", "taffy_attack_surface")
                        .put("path", path)
                        .put("package", pkg)
                        .put("exportedCount", exportedCount)
                        .put("exported", exported)
                        .put("permissions", perms)
                        .put("flags", JSONObject()
                            .put("debuggable", debuggable)
                            .put("allowBackup", allowBackup)
                            .put("usesCleartextTraffic", cleartext)
                            .put("networkSecurityConfig", nsc ?: JSONObject.NULL))
                        .put("risks", risks)
                        .put("hint", if (risks.length() == 0)
                            "未发现明显危险配置；仍建议核对 exported 组件的 IPC 参数校验(可配 taffy_analyze_elf/taffy_dex_xref 追实现)。"
                        else "关注上面列出的暴露面：导出组件是常见 IPC 攻击入口，先看它接收的 extras/URI 是否被校验。")
                )
            }.getOrElse { e ->
                err("ATTACK_SURFACE_FAILED", "攻击面分析失败: ${e.message ?: e.javaClass.simpleName}", "path", path)
            }
        }
    }

    val ALL = listOf(tool)
}
