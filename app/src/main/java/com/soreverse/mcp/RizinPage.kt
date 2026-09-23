package com.soreverse.mcp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.engine.NativeSoEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Rizin 引擎页（图形化分析工作台，对标 Explorer So）。
 *
 * 结构：文件卡 → 分区 Tab（概览/函数/字符串/反汇编/命令）→ 卡片列表 + 实时搜索 + 点击联动。
 * 底层仍是内置 Rizin 引擎（NativeSoEngine），但与旧版“命令输入框 + 文本输出”不同：
 * 数据以结构化卡片呈现，点任意函数即反汇编；rz 原生命令收敛到“命令”Tab 作为高级入口。
 */
private enum class RzTab(val zhName: String, val enName: String) {
    Overview("概览", "Overview"),
    Functions("函数", "Functions"),
    Strings("字符串", "Strings"),
    Disasm("反汇编", "Disasm"),
    Command("命令", "Command"),
}

private fun jsonList(arr: JSONArray?): List<JSONObject> {
    val a = arr ?: return emptyList()
    val out = ArrayList<JSONObject>(a.length())
    for (i in 0 until a.length()) a.optJSONObject(i)?.let { out += it }
    return out
}

private fun rzFunctionsOf(engine: NativeSoEngine, ws: String, limit: Int): Pair<List<JSONObject>, String> {
    val r = engine.rzFunctions(ws, "", limit)
    if (r.has("error")) return emptyList<JSONObject>() to r.optString("error")
    return jsonList(r.optJSONArray("functions")) to r.optString("architecture")
}

private fun rzStringsOf(engine: NativeSoEngine, ws: String, limit: Int): List<JSONObject> =
    jsonList(engine.strings(ws, "", "", "", limit).optJSONArray("items"))

private fun rzOverviewRows(engine: NativeSoEngine, ws: String, zh: Boolean): List<Pair<String, String>> {
    val r = engine.readElf(ws, "", "")
    if (r.has("error")) return listOf((if (zh) "错误" else "Error") to r.optString("error"))
    val rows = ArrayList<Pair<String, String>>()
    val ident = r.optJSONObject("ident")
    rows += (if (zh) "类别" else "Class") to (ident?.optString("class") ?: "-")
    rows += (if (zh) "字节序" else "Endian") to (ident?.optString("data") ?: "-")
    rows += (if (zh) "类型" else "Type") to r.optString("type", "-")
    rows += (if (zh) "架构" else "Machine") to r.optString("machine", "-")
    rows += (if (zh) "入口点" else "Entry") to r.optString("entryPoint", "-")
    rows += (if (zh) "程序头" else "Program headers") to (r.optJSONArray("programHeaders")?.length() ?: 0).toString()
    rows += (if (zh) "节区" else "Sections") to (r.optJSONArray("sectionHeaders")?.length() ?: 0).toString()
    rows += (if (zh) "动态项" else "Dynamic entries") to (r.optJSONArray("dynamicEntries")?.length() ?: 0).toString()
    return rows
}

private fun rzDisasmOf(engine: NativeSoEngine, ws: String, locator: String, limit: Int): Triple<String, String, String> {
    val r = engine.disasm(ws, "", locator, limit)
    if (r.has("error")) return Triple("", "", r.optString("error"))
    val text = r.optJSONObject("textWindow")?.optString("text", "") ?: ""
    val pseudo = r.optString("pseudocode", "")
    val title = r.optString("functionName", locator)
    return Triple(text, pseudo, title)
}

@Composable
internal fun RizinPage(t: UiText) {
    val zh = t.zh
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { EngineProvider.get(context) }

    var workspaceId by remember { mutableStateOf("") }
    var soName by remember { mutableStateOf("") }
    var arch by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(RzTab.Overview) }
    var busy by remember { mutableStateOf(false) }
    var busyMsg by remember { mutableStateOf("") }
    var errMsg by remember { mutableStateOf("") }

    var overviewRows by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var functions by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var funcQuery by remember { mutableStateOf("") }
    var strings by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var strQuery by remember { mutableStateOf("") }
    var disasmText by remember { mutableStateOf("") }
    var pseudoText by remember { mutableStateOf("") }
    var disasmTitle by remember { mutableStateOf("") }
    var cmdInput by remember { mutableStateOf("") }
    var cmdOutput by remember { mutableStateOf("") }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            busyMsg = if (zh) "正在打开并分析…" else "Opening & analyzing…"
            errMsg = ""
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val input = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("read failed")
                    val f = File(context.cacheDir, "rz_open_${System.currentTimeMillis()}.so")
                    f.writeBytes(input)
                    val r = engine.open(f.absolutePath, true)
                    val ws = r.optString("workspaceId", r.optString("id"))
                    if (ws.isBlank()) throw IllegalStateException(r.optString("error", "open failed"))
                    ws to f.name
                }
            }
            res.onSuccess { pair ->
                val ws = pair.first
                workspaceId = ws
                soName = pair.second
                overviewRows = withContext(Dispatchers.IO) { rzOverviewRows(engine, ws, zh) }
                val fn = withContext(Dispatchers.IO) { rzFunctionsOf(engine, ws, 1000) }
                functions = fn.first
                arch = fn.second
                tab = RzTab.Overview
            }.onFailure { e -> errMsg = e.message ?: "error" }
            busy = false
        }
    }

    fun runCommand(cmd: String) {
        val c = cmd.trim()
        if (c.isEmpty() || workspaceId.isBlank()) return
        scope.launch {
            busy = true
            busyMsg = "rz $c"
            cmdOutput = withContext(Dispatchers.IO) {
                val r = engine.rzCommand(workspaceId, "", c, false)
                r.optString("stdout", r.toString(2)).ifBlank { r.toString(2) }
            }
            busy = false
        }
    }

    fun openDisasm(locator: String) {
        if (locator.isBlank() || workspaceId.isBlank()) return
        scope.launch {
            busy = true
            busyMsg = if (zh) "反汇编 $locator" else "Disassembling $locator"
            val r = withContext(Dispatchers.IO) { rzDisasmOf(engine, workspaceId, locator, 500) }
            disasmText = r.first
            pseudoText = r.second
            disasmTitle = r.third
            if (r.first.isBlank() && r.third.isNotBlank() && r.third != locator) errMsg = r.third
            tab = RzTab.Disasm
            busy = false
        }
    }

    fun loadStringsIfNeeded() {
        if (strings.isNotEmpty() || workspaceId.isBlank()) return
        scope.launch {
            busy = true
            busyMsg = if (zh) "提取字符串…" else "Extracting strings…"
            strings = withContext(Dispatchers.IO) { rzStringsOf(engine, workspaceId, 1000) }
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlassGroup {
            DataRow(
                title = if (workspaceId.isBlank()) (if (zh) "未打开文件" else "No file opened") else soName,
                subtitle = if (workspaceId.isBlank()) {
                    if (zh) "选择一个 .so / ELF 开始图形化分析" else "Pick a .so / ELF to start"
                } else {
                    buildString {
                        append(if (zh) "架构 " else "arch ")
                        append(arch.ifBlank { "-" })
                        append("  ·  ")
                        append(if (zh) "${functions.size} 个函数" else "${functions.size} funcs")
                    }
                },
                meta = if (workspaceId.isBlank()) null else "workspace: $workspaceId",
                leading = {
                    Icon(
                        Icons.Default.Memory,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                },
                trailingText = if (zh) "选择 SO" else "Pick",
                onClick = { fileLauncher.launch(arrayOf("application/octet-stream", "application/x-sharedlib", "*/*")) },
            )
        }

        if (errMsg.isNotBlank()) {
            GlassGroup {
                InlineHint(errMsg, tone = HintTone.Error)
            }
        }

        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RzTab.values().forEach { tt ->
                val enabled = workspaceId.isNotBlank() || tt == RzTab.Overview
                FilterChip(
                    selected = tab == tt,
                    onClick = {
                        tab = tt
                        if (tt == RzTab.Strings) loadStringsIfNeeded()
                    },
                    enabled = enabled,
                    label = { Text(if (zh) tt.zhName else tt.enName) },
                )
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                RzTab.Overview -> RzOverviewTab(zh, overviewRows, workspaceId.isNotBlank(), busy)
                RzTab.Functions -> RzListTab(
                    zh = zh,
                    query = funcQuery,
                    onQuery = { funcQuery = it },
                    placeholder = if (zh) "筛选函数名 / 地址" else "Filter name / address",
                    rows = functions.filter {
                        val q = funcQuery.trim().lowercase()
                        q.isEmpty() ||
                            it.optString("name").lowercase().contains(q) ||
                            it.optString("startAddr").lowercase().contains(q)
                    },
                    emptyHint = if (workspaceId.isBlank()) {
                        if (zh) "先打开一个 SO 文件" else "Open a file first"
                    } else {
                        if (zh) "无匹配函数" else "No matching function"
                    },
                    onClick = { openDisasm(it.optString("locator")) },
                )
                RzTab.Strings -> RzListTab(
                    zh = zh,
                    query = strQuery,
                    onQuery = { strQuery = it },
                    placeholder = if (zh) "筛选字符串 / 偏移" else "Filter string / offset",
                    rows = strings.filter {
                        val q = strQuery.trim().lowercase()
                        q.isEmpty() ||
                            it.optString("value").lowercase().contains(q) ||
                            it.optString("offset").lowercase().contains(q)
                    },
                    emptyHint = if (workspaceId.isBlank()) {
                        if (zh) "先打开一个 SO 文件" else "Open a file first"
                    } else {
                        if (zh) "无匹配字符串" else "No matching string"
                    },
                    onClick = null,
                    titleOf = { it.optString("value") },
                    subtitleOf = { it.optString("section") },
                    metaOf = { it.optString("offset") + "  ·  " + it.optString("encoding") },
                )
                RzTab.Disasm -> RzDisasmTab(
                    zh = zh,
                    title = disasmTitle.ifBlank { if (zh) "反汇编" else "Disassembly" },
                    text = disasmText,
                    pseudo = pseudoText,
                )
                RzTab.Command -> RzCommandTab(
                    zh = zh,
                    value = cmdInput,
                    onValue = { cmdInput = it },
                    onRun = { runCommand(cmdInput) },
                    output = cmdOutput,
                    onClear = { cmdOutput = "" },
                    enabled = workspaceId.isNotBlank() && !busy,
                )
            }
        }
    }

    BusyOverlay(visible = busy, message = busyMsg.ifBlank { if (zh) "处理中…" else "Working…" })
}

@Composable
private fun RzOverviewTab(zh: Boolean, rows: List<Pair<String, String>>, hasFile: Boolean, busy: Boolean) {
    if (!hasFile) {
        InlineHint(if (zh) "选择文件后显示 ELF 概览" else "Pick a file to see ELF overview")
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassGroup(title = if (zh) "ELF 概览" else "ELF Overview") {
            if (rows.isEmpty()) {
                InlineHint(if (busy) (if (zh) "分析中…" else "Analyzing…") else (if (zh) "无数据" else "No data"))
            } else {
                rows.forEach { (k, v) -> MonoRow(k, v) }
            }
        }
    }
}

@Composable
private fun RzListTab(
    zh: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    placeholder: String,
    rows: List<JSONObject>,
    emptyHint: String,
    onClick: ((JSONObject) -> Unit)?,
    titleOf: (JSONObject) -> String = { it.optString("name") },
    subtitleOf: (JSONObject) -> String = { it.optString("startAddr") },
    metaOf: (JSONObject) -> String = { item ->
        buildString {
            append(item.optString("startAddr"))
            append(" – ")
            append(item.optString("endAddr"))
            append("  ·  ")
            append(item.optLong("size"))
            val kind = item.optString("kind")
            if (kind.isNotBlank()) {
                append("  ·  ")
                append(kind)
            }
        }
    },
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SearchCountBar(query = query, onQueryChange = onQuery, shown = rows.size, total = rows.size, placeholder = placeholder)
        if (rows.isEmpty()) {
            InlineHint(emptyHint)
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(rows, key = { it.optString("locator").ifBlank { titleOf(it) } }) { item ->
                    GlassGroup {
                        DataRow(
                            title = titleOf(item),
                            subtitle = subtitleOf(item),
                            meta = metaOf(item),
                            onClick = if (onClick != null) ({ onClick.invoke(item) }) else null,
                            onLongClick = if (onClick != null) ({ onClick.invoke(item) }) else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RzDisasmTab(zh: Boolean, title: String, text: String, pseudo: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassGroup(title = title) {
            if (text.isBlank()) {
                InlineHint(if (zh) "点函数列表中的任意函数查看反汇编" else "Tap a function to disassemble")
            } else {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        if (pseudo.isNotBlank()) {
            GlassGroup(title = if (zh) "伪 C" else "Pseudo C") {
                Text(
                    pseudo,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun RzCommandTab(
    zh: Boolean,
    value: String,
    onValue: (String) -> Unit,
    onRun: () -> Unit,
    output: String,
    onClear: () -> Unit,
    enabled: Boolean,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (zh) "高级：直接执行 rz 原生命令" else "Advanced: raw rz commands",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value,
                onValueChange = onValue,
                modifier = Modifier.weight(1f),
                placeholder = { Text("afl / pdf @main / izz", maxLines = 1, style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onRun() }),
            )
            IconButton(onClick = onRun, enabled = enabled) {
                Icon(Icons.AutoMirrored.Filled.Send, "发送命令", tint = MaterialTheme.colorScheme.primary)
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("afl", "izz", "iI", "iS", "ii").forEach { c ->
                FilterChip(selected = false, onClick = { onValue(c) }, label = { Text(c, fontFamily = FontFamily.Monospace) }, enabled = enabled)
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            TerminalPane(
                text = output,
                title = if (zh) "输出" else "Output",
                onClear = onClear,
                placeholder = if (zh) "执行结果将显示在这里" else "Result appears here",
            )
        }
    }
}
