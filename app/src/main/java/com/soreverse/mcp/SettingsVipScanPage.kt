package com.soreverse.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
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
 * 会员 / VIP 入口分析页。
 *
 * 解决的是"知道要看会员判断，但不知道从哪下手"：不用自己想关键词，按六组会员业务词扫 dex，
 * 按可信度排序给出候选，然后可以直接生成 Frida hook 脚本验证。
 *
 * 分数只是排序，不是结论——页面底部固定写这句，避免把"高分"当"结论"。
 */
@Composable
internal fun SettingsVipScanPage(t: UiText) {
    val zh = t.zh
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var path by rememberSaveable { mutableStateOf("") }
    var pkg by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var hookPath by remember { mutableStateOf("") }
    var reportPath by remember { mutableStateOf("") }

    suspend fun callTool(args: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val h = ToolCatalog.byName["taffy_vip_entry_scan"] ?: return@runCatching null
            h.handle(ToolContext(context, SettingsStore(context), EngineProvider.get(context), null), args)
        }.getOrNull()
    }

    fun runAction(action: String) {
        if (busy || path.isBlank()) return
        scope.safeLaunch {
            busy = true
            status = when (action) {
                "scan" -> if (zh) "正在扫描 dex…" else "Scanning dex…"
                "hook" -> if (zh) "正在生成 hook 脚本…" else "Generating hook script…"
                else -> if (zh) "正在导出报告…" else "Exporting report…"
            }
            val args = JSONObject().put("action", action).put("path", path.trim()).put("pkg", pkg.trim())
            val r = callTool(args)
            busy = false
            if (r == null || r.optBoolean("ok") != true) {
                status = r?.optJSONObject("error")?.optString("message") ?: (if (zh) "执行失败" else "Failed")
                return@safeLaunch
            }
            when (action) {
                "hook" -> {
                    hookPath = r.optString("scriptPath")
                    status = if (zh) "已生成 ${r.optInt("targets")} 个 hook 目标：$hookPath" else "Generated ${r.optInt("targets")} targets: $hookPath"
                }
                "report" -> {
                    reportPath = r.optString("reportPath")
                    status = if (zh) "报告已导出：$reportPath" else "Report written: $reportPath"
                }
                else -> {
                    result = r
                    status = if (zh) "命中 ${r.optInt("count")} 条（类 ${r.optInt("classCount")} 个，${r.optInt("elapsedMs")} ms）"
                    else "${r.optInt("count")} hits (${r.optInt("classCount")} classes, ${r.optInt("elapsedMs")} ms)"
                }
            }
        }
    }

    PageScroll {
        GlassGroup(
            title = if (zh) "会员 / VIP 入口分析" else "VIP / membership entry",
            footer = if (zh) "按六组会员业务词（会员标记 / 购买支付 / 期限试用 / 凭证 / 权限判断 / 免费限制）扫 dex，token 化匹配（payload 不会误命中 pay），跨组命中和 is/has/check 前缀会加权。分数只是候选排序，真伪要靠交叉引用与运行时 hook 验证。"
            else "Scans dex against six weighted keyword groups with token-aware matching. Scores rank candidates only — verify with xrefs and runtime hooks.",
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(if (zh) "目标文件（apk / dex）" else "Target (apk / dex)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pkg,
                    onValueChange = { pkg = it },
                    label = { Text(if (zh) "包名（生成 frida 命令用，可空）" else "Package (for the frida command, optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolChip(
                        label = if (zh) "扫描" else "Scan",
                        icon = Icons.Filled.Search,
                        accent = true,
                        enabled = !busy && path.isNotBlank(),
                        onClick = { runAction("scan") },
                    )
                    ToolChip(
                        label = if (zh) "生成 hook" else "Hook script",
                        icon = Icons.Filled.Terminal,
                        enabled = !busy && path.isNotBlank(),
                        onClick = { runAction("hook") },
                    )
                    ToolChip(
                        label = if (zh) "导出报告" else "Report",
                        icon = Icons.Filled.Description,
                        enabled = !busy && path.isNotBlank(),
                        onClick = { runAction("report") },
                    )
                    if (busy) CircularProgressIndicator(modifier = Modifier.width(20.dp), strokeWidth = 2.dp)
                }
                if (status.isNotBlank()) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (hookPath.isNotBlank()) {
                    Text(
                        if (zh) "frida -U -f ${pkg.ifBlank { "<pkg>" }} -l $hookPath --no-pause"
                        else "frida -U -f ${pkg.ifBlank { "<pkg>" }} -l $hookPath --no-pause",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        val r = result
        if (r != null) {
            GlassGroup(title = if (zh) "候选（按分数排序）" else "Candidates (ranked)") {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val cats = r.optJSONObject("categories")
                    if (cats != null) {
                        val names = cats.keys()
                        val parts = ArrayList<String>()
                        while (names.hasNext()) {
                            val k = names.next()
                            parts.add("$k ${cats.optInt(k)}")
                        }
                        if (parts.isNotEmpty()) {
                            Text(
                                parts.joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (r.optBoolean("truncated")) {
                        Text(
                            if (zh) "命中已达上限，结果被截断" else "Truncated at the limit",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    val arr = r.optJSONArray("findings") ?: JSONArray()
                    if (arr.length() == 0) {
                        Text(
                            if (zh) "没有命中：可能没有会员体系，或方法名被混淆（看 STRING 组是否还有线索）"
                            else "No hits: no membership system, or names are obfuscated",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    for (i in 0 until minOf(arr.length(), 80)) {
                        val f = arr.optJSONObject(i) ?: continue
                        val owner = f.optString("owner")
                        val name = f.optString("name")
                        val kind = f.optString("kind")
                        val score = f.optInt("score")
                        Text(
                            "$score · $kind · ${if (owner.isNotBlank()) owner else name}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val sub = f.optString("javaSig").ifBlank { f.optString("smaliSig") }
                        if (sub.isNotBlank()) {
                            Text(
                                sub,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (arr.length() > 80) {
                        Text(
                            if (zh) "仅显示前 80 条（导出报告看全部）" else "Showing first 80 — export the report for all",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (zh) "分数由「关键词组权重 + 跨组命中 + is/has/check 前缀 + native」累加，只是候选排序。"
                        else "Score = keyword weight + cross-group hits + is/has/check prefix + native. Ranking only.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
