package com.soreverse.mcp

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soreverse.mcp.core.safeLaunch
import com.soreverse.mcp.engine.SoDiff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * SO 结构对比（分析页视图，view key = `sodiff`）。
 *
 * 与「字节差分」互补：那边比两段 hex 的每个字节，这边比两个 ELF 的**结构**
 * （节区 / 导出 / 导入 / 依赖 / 符号版本）并给一个判定。列表按变化类型分色，
 * 不提供 JSON / 文本兜底出口——每个页签都是为它自己的数据类型设计的。
 */
@Composable
internal fun SoDiffView(zh: Boolean, context: android.content.Context) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var sideA by remember { mutableStateOf<DiffSide?>(null) }
    var sideB by remember { mutableStateOf<DiffSide?>(null) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }

    fun load(uri: Uri, asA: Boolean) {
        busy = true
        error = null
        pending = if (asA) "A" else "B"
        scope.safeLaunch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "elf"
                    var size = -1L
                    resolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (ni >= 0) c.getString(ni)?.let { name = it }
                            val si = c.getColumnIndex(OpenableColumns.SIZE)
                            if (si >= 0) size = c.getLong(si)
                        }
                    }
                    if (size > MAX_ELF_BYTES) error("${size / 1024 / 1024} MiB")
                    val bytes = resolver.openInputStream(uri)?.use { readCapped(it, MAX_ELF_BYTES) }
                        ?: error("无法打开：$name")
                    DiffSide(name, uri.toString(), size.takeIf { it > 0 } ?: bytes.size.toLong(), bytes)
                }
            }
            busy = false
            pending = null
            loaded.onSuccess { side ->
                if (asA) sideA = side else sideB = side
                result = null
            }.onFailure {
                error = if (zh) "读取失败：${it.message}" else "Read failed: ${it.message}"
            }
        }
    }

    val pickA = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load(uri, true)
    }
    val pickB = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load(uri, false)
    }

    fun runDiff() {
        val a = sideA ?: return
        val b = sideB ?: return
        busy = true
        error = null
        scope.safeLaunch {
            val out = withContext(Dispatchers.IO) {
                runCatching { SoDiff.diff(a.bytes, b.bytes) }
            }
            busy = false
            out.onSuccess { result = it; tab = 0 }
                .onFailure { error = (if (zh) "对比失败：" else "Diff failed: ") + (it.message ?: "") }
        }
    }

    ToolPageScaffold(
        title = if (zh) "SO 结构对比" else "SO structure diff",
        subtitle = if (zh) "节区 · 导出 · 导入 · 依赖 · 版本" else "Sections · exports · imports · needed · versions",
        actions = {
            ToolChip(
                if (zh) "交换 A/B" else "Swap",
                enabled = sideA != null && sideB != null && !busy,
            ) {
                val t = sideA; sideA = sideB; sideB = t
                result = null
            }
            ToolChip(
                if (zh) "清空" else "Clear",
                enabled = (sideA != null || sideB != null) && !busy,
            ) {
                sideA = null; sideB = null; result = null; error = null
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SidePickCard(
                    label = "A",
                    side = sideA,
                    pending = pending == "A",
                    zh = zh,
                    onPick = { pickA.launch(arrayOf("*/*")) },
                )
                SidePickCard(
                    label = "B",
                    side = sideB,
                    pending = pending == "B",
                    zh = zh,
                    onPick = { pickB.launch(arrayOf("*/*")) },
                )
                Button(
                    onClick = { runDiff() },
                    enabled = sideA != null && sideB != null && !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        if (busy) (if (zh) "处理中…" else "Working…") else (if (zh) "开始对比" else "Diff"),
                        fontSize = 14.sp,
                    )
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error)
                }
            }
            val r = result
            if (r != null) {
                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f))
                SummaryStrip(r, zh)
                val labels = listOf(
                    if (zh) "节区" else "Sections",
                    if (zh) "导出" else "Exports",
                    if (zh) "导入" else "Imports",
                    if (zh) "依赖" else "Needed",
                    if (zh) "函数" else "Functions",
                )
                TabRow(selectedTabIndex = tab, containerColor = cs.background) {
                    labels.forEachIndexed { i, label ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label, fontSize = 13.sp) })
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    singleLine = true,
                    placeholder = { Text(if (zh) "搜索名称…" else "Filter by name…", fontSize = 13.sp) },
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions.Default,
                )
                val rows = remember(r, tab, query) { buildRows(r, tab, query) }
                if (rows.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (zh) "这一项没有差异" else "No differences here",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(rows) { row -> DiffRow(row) }
                    }
                }
            } else if (!busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (zh) "选两个 ELF／SO，比它们的结构（不比字节）" else "Pick two ELFs and compare structure (not bytes)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }
}

private const val MAX_ELF_BYTES = 192L * 1024 * 1024

private data class DiffSide(val name: String, val path: String, val size: Long, val bytes: ByteArray)

private data class DiffRowUi(
    val kind: Char,          // '+' 新增 / '-' 删除 / '~' 变化
    val name: String,
    val detail: String,
    val right: String,
)

private fun readCapped(input: java.io.InputStream, limit: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        if (out.size().toLong() + n > limit) error("超过 ${limit / 1024 / 1024} MiB")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

private fun fmtBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> String.format("%.1f KB", n / 1024.0)
    else -> String.format("%.2f MB", n / 1048576.0)
}

private fun fmtDelta(n: Long): String = when {
    n == 0L -> "±0"
    else -> (if (n > 0) "+" else "") + n
}

@Composable
private fun SidePickCard(
    label: String,
    side: DiffSide?,
    pending: Boolean,
    zh: Boolean,
    onPick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = cs.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(26.dp).background(cs.primary.copy(alpha = 0.16f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = cs.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        pending -> if (zh) "读取中…" else "Reading…"
                        side != null -> side.name
                        else -> if (zh) "选择 ELF／SO" else "Pick an ELF"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (side != null || pending) cs.onSurface else cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                side?.let {
                    Text(
                        fmtBytes(it.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onPick, enabled = !pending) {
                Text(if (side == null) (if (zh) "选择" else "Pick") else (if (zh) "重选" else "Replace"), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SummaryStrip(r: JSONObject, zh: Boolean) {
    val cs = MaterialTheme.colorScheme
    val s = r.optJSONObject("summary") ?: JSONObject()
    val verdict = s.optString("verdict")
    val (verdictText, verdictColor) = when (verdict) {
        "identical" -> (if (zh) "完全相同" else "Identical") to cs.primary
        "same-structure" -> (if (zh) "结构相同 · 内容有变" else "Same structure, content differs") to cs.tertiary
        "code-only" -> (if (zh) "仅代码变化" else "Code-only change") to cs.tertiary
        "abi-mismatch" -> (if (zh) "ABI 不同，不可直接比较" else "ABI mismatch") to cs.error
        else -> (if (zh) "结构有变化" else "Structure changed") to cs.onSurface
    }
    Surface(color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(verdictText, style = MaterialTheme.typography.titleSmall, color = verdictColor)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MiniStat(if (zh) "大小" else "size", fmtDelta(s.optLong("sizeDelta")))
                MiniStat(".text", fmtDelta(s.optLong("textDelta")))
                MiniStat(if (zh) "导出" else "exp", fmtDelta(s.optLong("exportsDelta")))
                MiniStat(if (zh) "导入" else "imp", fmtDelta(s.optLong("importsDelta")))
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(8.dp), color = cs.secondaryContainer.copy(alpha = 0.7f)) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, fontSize = 11.sp, color = cs.onSecondaryContainer.copy(alpha = 0.75f))
            Text(
                value,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun DiffRow(row: DiffRowUi) {
    val cs = MaterialTheme.colorScheme
    val color = when (row.kind) {
        '+' -> cs.primary
        '-' -> cs.error
        else -> cs.tertiary
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            row.kind.toString(),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = color,
            modifier = Modifier.width(12.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                row.name,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.detail.isNotBlank()) {
                Text(
                    row.detail,
                    fontSize = 10.sp,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (row.right.isNotBlank()) {
            Text(
                row.right,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = if (row.kind == '~') cs.tertiary else cs.onSurfaceVariant,
            )
        }
    }
}

private fun buildRows(r: JSONObject, tab: Int, query: String): List<DiffRowUi> {
    val q = query.trim()
    fun match(name: String) = q.isEmpty() || name.contains(q, ignoreCase = true)
    val out = mutableListOf<DiffRowUi>()

    fun addSection(arr: org.json.JSONArray?, kind: Char, detailOf: (JSONObject) -> String, rightOf: (JSONObject) -> String) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name")
            if (!match(name)) continue
            out += DiffRowUi(kind, name, detailOf(o), rightOf(o))
        }
    }

    when (tab) {
        0 -> {
            val sec = r.optJSONObject("sections") ?: JSONObject()
            addSection(sec.optJSONArray("changed"), '~',
                { o ->
                    val flags = if (o.optBoolean("flagsChanged")) "flags ${o.optLong("flagsA")}→${o.optLong("flagsB")} " else ""
                    val type = if (o.optBoolean("typeChanged")) "type ${o.optLong("typeA")}→${o.optLong("typeB")}" else ""
                    (flags + type).trim().ifBlank { "大小 ${fmtBytes(o.optLong("sizeA"))} → ${fmtBytes(o.optLong("sizeB"))}" }
                },
                { o -> fmtDelta(o.optLong("delta")) })
            addSection(sec.optJSONArray("added"), '+', { o -> "大小 ${fmtBytes(o.optLong("size"))}" }, { "" })
            addSection(sec.optJSONArray("removed"), '-', { o -> "大小 ${fmtBytes(o.optLong("size"))}" }, { "" })
        }
        1 -> {
            val e = r.optJSONObject("exports") ?: JSONObject()
            addSection(e.optJSONArray("changed"), '~',
                { o -> "${o.optString("typeA")}/${o.optString("bindA")} → ${o.optString("type")}/${o.optString("bind")}" },
                { o -> fmtDelta(o.optLong("delta")) })
            addSection(e.optJSONArray("added"), '+', { o -> "${o.optString("type")} · ${o.optString("bind")}" }, { o -> "${o.optLong("size")} B" })
            addSection(e.optJSONArray("removed"), '-', { o -> "${o.optString("type")} · ${o.optString("bind")}" }, { o -> "${o.optLong("size")} B" })
        }
        2 -> {
            val im = r.optJSONObject("imports") ?: JSONObject()
            addSection(im.optJSONArray("added"), '+', { o -> "${o.optString("type")} · ${o.optString("bind")}" }, { "" })
            addSection(im.optJSONArray("removed"), '-', { o -> "${o.optString("type")} · ${o.optString("bind")}" }, { "" })
        }
        3 -> {
            val need = r.optJSONObject("needed") ?: JSONObject()
            listOf("added" to '+' as Char, "removed" to '-' as Char).forEach { (k, kind) ->
                val arr = need.optJSONArray(k) ?: return@forEach
                for (i in 0 until arr.length()) {
                    val n = arr.optString(i)
                    if (match(n)) out += DiffRowUi(kind, n, if (kind == '+') "新增依赖" else "移除依赖", "")
                }
            }
            val ver = r.optJSONObject("versions") ?: JSONObject()
            listOf("added" to '+' as Char, "removed" to '-' as Char).forEach { (k, kind) ->
                val arr = ver.optJSONArray(k) ?: return@forEach
                for (i in 0 until arr.length()) {
                    val n = arr.optString(i)
                    if (match(n)) out += DiffRowUi(kind, n, "符号版本", "")
                }
            }
        }
        else -> {
            // 函数级差分：这是 taffy_so_diff 与 taffy_byte_diff 的分界
            // —— 这里给「哪个函数变了、从第几字节开始变」，字节级定位交给 HEX/差分视图。
            val fn = r.optJSONObject("functions") ?: JSONObject()
            // 一侧被 strip 时逐函数比较没有意义，直接说明，而不是显示一个空页签
            val fs = r.optJSONObject("summary")?.optJSONObject("functions")
            if (fs != null && !fs.optBoolean("comparable", true)) {
                val note = fs.optString("note")
                if (note.isNotBlank()) out += DiffRowUi('~', note, "函数级不可比", "")
            }
            addSection(fn.optJSONArray("changed"), '~',
                { o ->
                    val d = o.optLong("firstDiff", -1L)
                    val from = if (d >= 0L) "首差 +0x${java.lang.Long.toHexString(d)}" else "内容不同"
                    "$from · ${o.optLong("sizeA")} B"
                },
                { o -> fmtDelta(o.optLong("delta")) })
            addSection(fn.optJSONArray("resized"), '~',
                { o -> "大小 ${o.optLong("sizeA")} → ${o.optLong("sizeB")} B" },
                { o -> fmtDelta(o.optLong("delta")) })
            addSection(fn.optJSONArray("added"), '+', { o -> "${o.optLong("sizeB")} B" }, { "" })
            addSection(fn.optJSONArray("removed"), '-', { o -> "${o.optLong("sizeA")} B" }, { "" })
        }
    }
    return out
}
