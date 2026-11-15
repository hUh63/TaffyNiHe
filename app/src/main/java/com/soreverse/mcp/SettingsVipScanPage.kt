package com.soreverse.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import java.io.File
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.FridaManager
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
    val clipboard = LocalClipboardManager.current

    var path by rememberSaveable { mutableStateOf("") }
    var pkg by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var hookPath by remember { mutableStateOf("") }
    var reportPath by remember { mutableStateOf("") }
    // frida-server 状态：生成 hook 脚本后最常问的就是「现在能直接跑吗」。
    // 这里只读状态，不替用户启动（启动涉及 root 授权，应由用户在 Frida 页显式操作）。
    var frida by remember { mutableStateOf<FridaManager.Status?>(null) }
    LaunchedEffect(Unit) {
        frida = runCatching { FridaManager.status(context.applicationContext) }.getOrNull()
    }

    // 目标文件选择：dexlib2 需要真实路径，content:// 不能直接喂给扫描器，
    // 所以选完先复制到 cacheDir 再交给工具（大 APK 会占一份临时空间，扫描完可手动清）。
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.safeLaunch {
            busy = true
            status = if (zh) "正在导入文件…" else "Importing…"
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    val name = (uri.lastPathSegment ?: "picked").substringAfterLast('/').ifBlank { "picked" }
                    // 先清掉上次导入的副本：APK 动辄几百 MB，反复选文件会把 cache 撑爆
                    runCatching {
                        context.cacheDir.listFiles { f -> f.isFile && f.name.startsWith("vip_") }
                            ?.forEach { it.delete() }
                    }
                    // 超过 2GB 的包没必要导进来（dexlib2 也扫不动），先查大小再决定
                    val size = runCatching {
                        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                    }.getOrNull() ?: -1L
                    if (size > 2L * 1024 * 1024 * 1024) {
                        throw IllegalStateException(
                            if (zh) "文件过大（>" + (size / 1048576) + "MB），请先用其他工具裁剪"
                            else "File too large: " + (size / 1048576) + "MB"
                        )
                    }
                    val out = File(context.cacheDir, "vip_" + name)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("openInputStream returned null")
                    out.absolutePath
                }
            }
            busy = false
            copied.onSuccess {
                path = it
                status = if (zh) "已导入：$it" else "Imported: $it"
            }.onFailure {
                status = (if (zh) "导入失败：" else "Import failed: ") + (it.message ?: it.javaClass.simpleName)
            }
        }
    }

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
                        label = if (zh) "选择文件" else "Pick file",
                        icon = Icons.Filled.FolderOpen,
                        enabled = !busy,
                        onClick = { picker.launch(arrayOf("*/*")) },
                    )
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
                    val cmd = "frida -U -f " + pkg.ifBlank { "<pkg>" } + " -l " + hookPath + " --no-pause"
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            cmd,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        ToolChip(
                            label = if (zh) "复制命令" else "Copy cmd",
                            icon = Icons.Filled.Description,
                            onClick = {
                                clipboard.setText(AnnotatedString(cmd))
                                status = if (zh) "命令已复制，在电脑上粘贴执行" else "Command copied"
                            },
                        )
                    }
                    val st = frida
                    Text(
                        when {
                            st == null -> if (zh) "frida-server 状态未知" else "frida-server state unknown"
                            !st.rootAvailable -> if (zh) "未检测到 root：请在已 root 的设备上运行 frida-server" else "No root detected"
                            st.running -> (if (zh) "frida-server 运行中 · 端口 " else "frida-server running · port ") + st.port
                            else -> if (zh) "frida-server 未运行（到 Frida 页启动后再执行命令）" else "frida-server not running"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (st?.running == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        val r = result
        if (r != null) {
            VipCandidates(t, r)
        }
    }
}

/**
 * 候选列表：类别筛选 + 调用者 + 点击复制签名。
 *
 * 之前的实现是「一坨 Text 逐行打印」，三条真实需求都满足不了：
 * 想知道哪一类命中最多（要能按类别筛）、想看清「谁在调用它」（这是命名之外唯一的硬证据）、
 * 想把签名拿去搜/贴到别处（要能一键复制）。
 */
@Composable
private fun VipCandidates(t: UiText, r: JSONObject) {
    val zh = t.zh
    val clipboard = LocalClipboardManager.current
    val arr = r.optJSONArray("findings") ?: JSONArray()
    val all = remember(r) { (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }
    var cat by rememberSaveable { mutableStateOf("") }
    val cats = remember(all) {
        all.map { it.optString("category") }.filter { it.isNotBlank() }.distinct()
    }
    val shown = remember(all, cat) {
        if (cat.isBlank()) all else all.filter { it.optString("category") == cat }
    }
    var copied by remember { mutableStateOf("") }
    fun copy(value: String) {
        if (value.isBlank()) return
        clipboard.setText(AnnotatedString(value))
        copied = value
    }

    GlassGroup(title = if (zh) "候选（按分数排序）" else "Candidates (ranked)") {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = cat.isBlank(),
                    onClick = { cat = "" },
                    label = { Text(if (zh) "全部 " + all.size else "All " + all.size) },
                )
                cats.forEach { c ->
                    val n = all.count { it.optString("category") == c }
                    FilterChip(
                        selected = cat == c,
                        onClick = { cat = if (cat == c) "" else c },
                        label = { Text(c + " " + n) },
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
            if (r.optBoolean("callersPartial")) {
                Text(
                    if (zh) "调用者分析超出时间预算，部分命中没有调用者信息" else "Caller analysis timed out; some hits have no callers",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (shown.isEmpty()) {
                Text(
                    if (zh) "没有命中：可能没有会员体系，或方法名被混淆（看 STRING 组是否还有线索）"
                    else "No hits: no membership system, or names are obfuscated",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (copied.isNotBlank()) {
                Text(
                    (if (zh) "已复制：" else "Copied: ") + copied,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            for (f in shown.take(80)) {
                VipCandidateRow(f, zh) { copy(it) }
            }
            if (shown.size > 80) {
                Text(
                    if (zh) "仅显示前 80 条（导出报告看全部）" else "Showing first 80 — export the report for all",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (zh) "点任意一条复制其签名；分数由「关键词组权重 + 跨组命中 + is/has/check 前缀 + native」累加，只是候选排序。"
                else "Tap a row to copy its signature. Score = keyword weight + cross-group hits + is/has/check prefix + native. Ranking only.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun VipCandidateRow(
    f: JSONObject,
    zh: Boolean,
    onCopy: (String) -> Unit,
) {
    val kind = f.optString("kind")
    val score = f.optInt("score")
    val owner = f.optString("owner")
    val name = f.optString("name")
    val smaliSig = f.optString("smaliSig")
    val javaSig = f.optString("javaSig")
    val hits = f.optJSONArray("hits")
    val callers = f.optJSONArray("callers")
    val copyText = smaliSig.ifBlank { if (owner.isNotBlank()) owner else name }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppShape.sm))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(
                onClick = { onCopy(copyText) },
                onLongClick = { onCopy(owner.ifBlank { copyText }) },
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                score.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                kind,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                javaSig.ifBlank { if (owner.isNotBlank()) owner else name },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (hits != null && hits.length() > 0) {
            Text(
                (0 until hits.length()).joinToString(" ") { hits.optString(it) },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (callers != null && callers.length() > 0) {
            Text(
                if (zh) "← " + (0 until callers.length()).joinToString(", ") { shortMember(callers.optString(it)) }
                else "← " + (0 until callers.length()).joinToString(", ") { shortMember(callers.optString(it)) },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** `Lcom/x/Y;->z(I)V` → `com.x.Y.z`，只用于列表副标题，完整签名点一下复制即可。 */
private fun shortMember(sig: String): String {
    if (sig.isBlank()) return ""
    val cls = sig.substringBefore(";->").trimStart('L').replace('/', '.')
    val rest = sig.substringAfter(";->", "")
    if (rest.isBlank()) return cls
    return cls + "." + rest.substringBefore("(")
}
