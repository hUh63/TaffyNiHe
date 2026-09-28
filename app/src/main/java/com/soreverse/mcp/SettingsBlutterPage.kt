package com.soreverse.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/**
 * 设置 → Flutter / Blutter：直接以 `assets/blutter/runners.json`（APK 能力的唯一事实来源）渲染，
 * 而不是写死一段会过期的文案 —— 之前页面里那句「Flutter 3.44.x / Dart 3.12.2」已经和实际内置的
 * 4 套 Runner（Dart 3.11.5 / 3.12.2 / 3.13.0 / 3.13.1）脱节了。
 */
@Composable
internal fun SettingsBlutterPage(t: UiText) {
    val zh = t.zh
    val context = LocalContext.current.applicationContext
    val manifest = remember {
        runCatching {
            JSONObject(context.assets.open("blutter/runners.json").bufferedReader().use { it.readText() })
        }.getOrNull()
    }
    val runners = remember(manifest) {
        val arr = manifest?.optJSONArray("runners")
        (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }
    }
    val coverage = remember(manifest) {
        val arr = manifest?.optJSONArray("coverage")
        (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }
    }
    val unsupportedReasons = remember(coverage) {
        coverage.filter { !it.optBoolean("supported", false) }
            .groupingBy { it.optString("reason").ifBlank { "unclassified" } }
            .eachCount()
            .entries.sortedByDescending { it.value }
            .take(4)
    }

    PageScroll {
        GlassGroup(title = if (zh) "内置 Runner" else "Embedded runners") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (manifest == null) {
                    Text(
                        if (zh) "无法读取 runners.json —— 安装包可能不完整。" else "runners.json is unreadable — the install may be incomplete.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    return@Column
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (zh) "矩阵版本 ${manifest.optString("matrixVersion")}" else "matrix ${manifest.optString("matrixVersion")}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TypeChip(if (zh) "${runners.size} 套可用" else "${runners.size} runners")
                }
                MonoRow(if (zh) "清单架构版本" else "schema", manifest.optInt("schemaVersion").toString())
                MonoRow("protocol", manifest.optInt("protocolVersion").toString())
                manifest.optString("upstreamCommit").takeIf { it.isNotBlank() }?.let {
                    MonoRow("blutter", it.take(12))
                }
                manifest.optString("generatedAt").takeIf { it.isNotBlank() }?.let {
                    MonoRow(if (zh) "生成时间" else "generated", it.take(19).replace("T", " "))
                }
                Text(
                    if (zh) "APK 内置这些 Runner（随包安装即用），分析完全在手机本地完成，不需要 Python、网络、ADB 或远端服务。"
                    else "These runners ship inside the APK; analysis runs fully on-device without Python, network, ADB, or remote services.",
                    modifier = Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                GroupDivider()
                runners.forEachIndexed { index, runner ->
                    if (index > 0) GroupDivider()
                    val aliases = runner.optJSONArray("snapshotAliases")
                    val aliasText = (0 until (aliases?.length() ?: 0)).joinToString(", ") { aliases!!.optString(it).take(12) }
                    DataRow(
                        title = "Dart ${runner.optString("dartVersion")}",
                        subtitle = "${runner.optString("abi")} · ${if (runner.optBoolean("compressedPointers")) (if (zh) "压缩指针" else "compressed ptrs") else (if (zh) "普通指针" else "plain ptrs")}",
                        meta = (if (aliasText.isBlank()) "" else "snapshot $aliasText") +
                            (if (runner.optString("sha256").isBlank()) "" else "  ·  sha256 ${runner.optString("sha256").take(12)}"),
                    )
                }
            }
        }
        GlassGroup(title = if (zh) "版本覆盖" else "Version coverage") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val indexed = manifest?.optJSONArray("coverage")?.length() ?: 0
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolChip(if (zh) "索引 $indexed 个版本" else "$indexed indexed", onClick = {})
                    ToolChip(if (zh) "已打包 ${runners.size}" else "packed ${runners.size}", selected = true, onClick = {})
                }
                Text(
                    if (zh) "「已索引」不等于「已支持」：只有实测通过并打进 APK 的版本才会被执行，其余版本会明确返回不支持并附带原因，不会尝试错误解析。"
                    else "\"Indexed\" is not \"supported\": only runners verified and packed into the APK are executed. Other versions return an explicit unsupported result with a reason instead of a wrong parse.",
                    modifier = Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (unsupportedReasons.isNotEmpty()) {
                    GroupDivider()
                    unsupportedReasons.forEach { (reason, count) ->
                        MonoRow(reason, if (zh) "$count 个版本" else "$count versions")
                    }
                }
            }
        }
        GlassGroup(title = if (zh) "自动匹配" else "Auto matching") {
            Column(Modifier.padding(12.dp)) {
                Text(
                    if (zh) "打开 Flutter SO 时，按 snapshot hash + ABI + 压缩指针模式自动匹配 Runner，命中即离线输出库 / 类 / 函数 / 对象结构。Dart 3.13 起快照合并为单段（vm/isolate 合一），对应 Runner 已内置。分析入口在「分析页 → 分析域 → Flutter」。"
                    else "When a Flutter SO is opened, a runner is matched by snapshot hash + ABI + compressed-pointer mode, producing library/class/function/object structures offline. Dart 3.13+ single-snapshot format is included. Entry point: Analysis page → Analyze domain → Flutter.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
