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
    var aesKey by rememberSaveable { mutableStateOf("") }
    var aesIv by rememberSaveable { mutableStateOf("") }
    var aesResult by remember { mutableStateOf<JSONObject?>(null) }

    suspend fun callTool(name: String, args: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val h = ToolCatalog.byName[name] ?: return@runCatching null
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
                                result = callTool("taffy_dex_xor_scan", JSONObject().put("action", "scan").put("path", path.trim()).put("modes", modes))
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
                                result = callTool("taffy_dex_xor_scan", JSONObject().put("action", "extract").put("path", path.trim()).put("modes", modes))
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

        // ───────── AES 加密 dex 的定向解密 ─────────
        GlassGroup(
            title = if (zh) "AES 加密解密" else "AES decrypt",
            footer = if (zh) "对付用 AES 加密 dex 的壳：先 sbox 看数据里有没有 AES 表（判断标准/自定义表），再 keys 挑候选 key，或直接 auto 穷举。解出的 dex 会自动重算头部校验和与签名。"
            else "For AES-encrypted dex: sbox detects AES tables, keys lists candidates, auto brute-forces key×IV×mode. Extracted dex gets header checksums recomputed.",
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolChip(
                        label = "SBOX",
                        enabled = !busy && path.isNotBlank(),
                        onClick = {
                            scope.safeLaunch {
                                busy = true
                                aesResult = callTool("taffy_dex_aes", JSONObject().put("action", "sbox").put("path", path.trim()))
                                busy = false
                            }
                        },
                    )
                    ToolChip(
                        label = if (zh) "候选 key" else "Keys",
                        enabled = !busy && path.isNotBlank(),
                        onClick = {
                            scope.safeLaunch {
                                busy = true
                                aesResult = callTool("taffy_dex_aes", JSONObject().put("action", "keys").put("path", path.trim()))
                                busy = false
                            }
                        },
                    )
                    ToolChip(
                        label = if (zh) "自动穷举" else "Auto",
                        accent = true,
                        enabled = !busy && path.isNotBlank(),
                        onClick = {
                            scope.safeLaunch {
                                busy = true
                                aesResult = callTool("taffy_dex_aes", JSONObject().put("action", "auto").put("path", path.trim()))
                                busy = false
                            }
                        },
                    )
                    ToolChip(
                        label = if (zh) "修头" else "Fix header",
                        enabled = !busy && path.isNotBlank(),
                        onClick = {
                            scope.safeLaunch {
                                busy = true
                                aesResult = callTool("taffy_dex_xor_scan", JSONObject().put("action", "fix").put("path", path.trim()))
                                busy = false
                            }
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = aesKey,
                        onValueChange = { aesKey = it },
                        label = { Text(if (zh) "key（hex 16/24/32 字节）" else "key (hex)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = aesIv,
                        onValueChange = { aesIv = it },
                        label = { Text("IV") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                ToolChip(
                    label = if (zh) "用这对 key/iv 解密" else "Decrypt with key/iv",
                    accent = true,
                    enabled = !busy && path.isNotBlank() && aesKey.isNotBlank(),
                    onClick = {
                        scope.safeLaunch {
                            busy = true
                            aesResult = callTool("taffy_dex_aes", JSONObject().put("action", "decrypt")
                                .put("path", path.trim()).put("key", aesKey.trim()).put("iv", aesIv.trim()))
                            busy = false
                        }
                    },
                )
                val ar = aesResult
                if (ar != null) {
                    if (ar.optBoolean("ok") != true) {
                        Text(
                            ar.optJSONObject("error")?.optString("message") ?: (if (zh) "操作失败" else "Failed"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        val note = ar.optString("note")
                        if (note.isNotBlank()) {
                            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val table = ar.optJSONObject("aesTable")
                        if (table != null) {
                            Text(
                                "S-box=${table.optBoolean("hasSbox")}  Inv=${table.optBoolean("hasInvSbox")}  Rcon=${table.optBoolean("hasRcon")}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        val cands = ar.optJSONArray("candidates")
                        if (cands != null) {
                            for (i in 0 until minOf(cands.length(), 10)) {
                                val c = cands.optJSONObject(i) ?: continue
                                Text(
                                    "${c.optString("key")}  (${c.optInt("length")}B @${c.optString("offsetHex")})",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                        val hits = ar.optJSONArray("hits") ?: ar.optJSONArray("written")
                        if (hits != null && hits.length() > 0) {
                            Text(
                                if (zh) "命中 ${hits.length()} 个" else "${hits.length()} hit(s)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            for (i in 0 until minOf(hits.length(), 8)) {
                                val h = hits.optJSONObject(i) ?: continue
                                Text(
                                    (h.optString("mode") + "  key=" + h.optString("key").take(16) + "  " +
                                        h.optString("iv").take(12) + "  " + h.optString("outPath")).trim(),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                        val changes = ar.optJSONArray("changes")
                        if (changes != null && changes.length() > 0) {
                            val sb = StringBuilder()
                            for (i in 0 until changes.length()) {
                                if (sb.isNotEmpty()) sb.append("；")
                                sb.append(changes.optString(i))
                            }
                            Text((if (zh) "修头：" else "Header fix: ") + sb, style = MaterialTheme.typography.labelMedium)
                        }
                        val outPath = ar.optString("outPath")
                        if (outPath.isNotBlank()) {
                            MonoRow(if (zh) "输出" else "Output", outPath)
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
