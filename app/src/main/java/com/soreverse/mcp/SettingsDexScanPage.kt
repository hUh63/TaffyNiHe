package com.soreverse.mcp

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.core.safeLaunch
import com.soreverse.mcp.mcp.ToolCatalog
import com.soreverse.mcp.mcp.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 加密 DEX 扫描页：从 so / assets / 内存 dump 里把被 XOR 或直接嵌入的 dex 找回来。
 *
 * 和 `taffy_dex_unpack` 的关系：那个负责"从运行中的进程 dump 内存"，
 * 这个负责"拿到了 blob 之后怎么把 dex 从里面认出来并还原"——两步串起来才是一条完整脱壳链。
 */
@Composable
internal fun SettingsDexScanPage(t: UiText) {
    val zh = t.zh
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var path by rememberSaveable { mutableStateOf("") }
    var modes by rememberSaveable { mutableStateOf("plain,xor1,xorn") }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    suspend fun callTool(args: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val h = ToolCatalog.byName["taffy_dex_xor_scan"] ?: return@runCatching null
            h.handle(ToolContext(context, SettingsStore(context), EngineProvider.get(context), null), args)
        }.getOrNull()
    }

    PageScroll {
        GlassGroup(
            title = if (zh) "扫描目标" else "Target",
            footer = if (zh) "被 XOR 加密的 dex 靠 dex 结构 + adler32 校验和 + SHA-1 签名三重判定，误报极少；校验和/签名不符但结构合法的会单独标出（脱壳后未修复的典型形态）。"
            else "Detection uses dex structure + adler32 + SHA-1, so false positives are rare; structure-only hits are flagged separately.",
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(if (zh) "输入文件（so / bin / dex / 内存 dump / apk）" else "Input file (so/bin/dex/dump/apk)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val parts = modes.split(',').toMutableList()
                    ToolChip(
                        label = if (zh) "明文" else "plain",
                        selected = parts.contains("plain"),
                        enabled = !busy,
                        onClick = {
                            if (parts.contains("plain")) parts.remove("plain") else parts.add("plain")
                            modes = parts.filter { it.isNotBlank() }.joinToString(",")
                        },
                    )
                    ToolChip(
                        label = "XOR 单字节",
                        selected = parts.contains("xor1"),
                        enabled = !busy,
                        onClick = {
                            if (parts.contains("xor1")) parts.remove("xor1") else parts.add("xor1")
                            modes = parts.filter { it.isNotBlank() }.joinToString(",")
                        },
                    )
                    ToolChip(
                        label = "XOR 多字节",
                        selected = parts.contains("xorn"),
                        enabled = !busy,
                        onClick = {
                            if (parts.contains("xorn")) parts.remove("xorn") else parts.add("xorn")
                            modes = parts.filter { it.isNotBlank() }.joinToString(",")
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolChip(
                        label = if (zh) "扫描" else "Scan",
                        icon = Icons.Filled.Search,
                        enabled = !busy && path.isNotBlank(),
                        onClick = {
                            scope.safeLaunch {
                                busy = true
                                result = callTool(JSONObject().put("action", "scan").put("path", path.trim()).put("modes", modes))
                                busy = false
                                message = ""
                            }
                        },
                    )
                    ToolChip(
                        label = if (zh) "扫描并还原" else "Scan & extract",
                        icon = Icons.Filled.Download,
                        accent = true,
                        enabled = !busy && path.isNotBlank(),
                        onClick = {
                            scope.safeLaunch {
                                busy = true
                                result = callTool(JSONObject().put("action", "extract").put("path", path.trim()).put("modes", modes))
                                busy = false
                                message = ""
                            }
                        },
                    )
                }
            }
        }

        val r = result
        if (r != null) {
            GlassGroup(title = if (zh) "结果" else "Result") {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (r.optBoolean("ok") != true) {
                        Text(
                            r.optJSONObject("error")?.optString("message") ?: (if (zh) "扫描失败" else "Scan failed"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        val hits = r.optJSONArray("hits") ?: JSONArray()
                        Text(
                            if (hits.length() == 0) (if (zh) "没有命中" else "No hits")
                            else (if (zh) "${hits.length()} 个候选（强校验通过 ${r.optInt("strongValidCount")}）"
                            else "${hits.length()} candidate(s), ${r.optInt("strongValidCount")} strongly valid"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        for (i in 0 until hits.length()) {
                            val h = hits.optJSONObject(i) ?: continue
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "${h.optString("mode")}  key=${h.optString("key")}  v${h.optString("version")}  " +
                                        "${h.optInt("size")} B  @${h.optString("offsetHex")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    (if (h.optBoolean("strongValid")) "✓ " else "△ ") + h.optString("note") +
                                        "  · " + h.optString("where"),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (h.optBoolean("strongValid")) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        val written = r.optJSONArray("written")
                        if (written != null && written.length() > 0) {
                            Text(
                                if (zh) "已写出 ${written.length()} 个 dex 到 ${r.optString("outDir")}" else "Wrote ${written.length()} dex to ${r.optString("outDir")}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            for (i in 0 until minOf(written.length(), 8)) {
                                MonoRow("", written.optString(i))
                            }
                        }
                        val summary = r.optString("summary")
                        if (summary.isNotBlank()) {
                            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val next = r.optString("next")
                        if (next.isNotBlank()) {
                            Text("→ $next", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        if (busy) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(14.dp).height(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(if (zh) "扫描中（大文件较慢）…" else "Scanning (large files take a while)…", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (message.isNotBlank()) {
            Text(message, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
    }
}
