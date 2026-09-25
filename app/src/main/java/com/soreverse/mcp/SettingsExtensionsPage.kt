package com.soreverse.mcp

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soreverse.mcp.core.PythonRuntime
import com.soreverse.mcp.core.ReadLimits
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.core.WorkspacePolicy
import com.soreverse.mcp.core.XedExtensionConverter
import com.soreverse.mcp.core.readBytesCapped
import com.soreverse.mcp.core.readTextCapped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 官方插件市场源（GitHub raw；任何提供 index.json 的仓库均可作源）。 */
private const val DEFAULT_MARKET_URL = "https://raw.githubusercontent.com/hUh63/TaffyNiHe/main/plugins/index.json"

/** 页内小动作按钮（文字胶囊，风格与全局工具条一致）。 */
@Composable
private fun ExtBtn(
    label: String,
    enabled: Boolean = true,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val bgc = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.35f)
        accent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val fgc = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        accent -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(AppShape.sm))
            .background(bgc)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = AppText.label, color = fgc, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 设置 → 扩展系统：塔菲 Python 插件的完整生态（借鉴 Xed-Editor 扩展系统 + 本土化）。
 *
 * 图形化重构（Exbin 范式，与其余 11 页统一）：
 *  - 卡片列表替代长文本：插件/市场条目 = AppCard + DataRow（chip + 主副标题 + 等宽元信息）
 *  - 实时搜索 + 「显示/总数」计数（SearchCountBar）
 *  - 教程/运行输出 = TerminalPane（统一终端框，可选中复制）
 *  - 破坏性操作二次确认（ConfirmDialog）、耗时操作可取消遮罩（BusyOverlay）
 *  - 全部配色/圆角走 AppShape / MaterialTheme token，不再硬编码
 */
@Composable
internal fun SettingsExtensionsPage(t: UiText, onDest: (SettingsDest) -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val zh = t.zh

    val pluginsRoot = remember { File(context.filesDir, "plugins").apply { mkdirs() } }
    var tab by remember { mutableStateOf(0) }   // 0=插件 1=教程 2=市场
    var plugins by remember { mutableStateOf<List<File>>(emptyList()) }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var showNewDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }
    var newId by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var pluginQuery by remember { mutableStateOf("") }
    var marketQuery by remember { mutableStateOf("") }
    // 市场状态
    val marketPrefs = remember { context.getSharedPreferences("so_reverse_mcp", android.content.Context.MODE_PRIVATE) }
    var marketSource by remember {
        mutableStateOf(marketPrefs.getString("extension_market_url", DEFAULT_MARKET_URL) ?: DEFAULT_MARKET_URL)
    }
    var marketItems by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var marketLoading by remember { mutableStateOf(false) }
    var marketMsg by remember { mutableStateOf("") }
    var marketMsgOk by remember { mutableStateOf(true) }
    val scroll = rememberScrollState()

    fun appendOut(s: String) { output = (output + s).takeLast(30000) }

    fun refresh() {
        plugins = runCatching {
            pluginsRoot.listFiles { f -> f.isDirectory && File(f, "plugin.py").isFile }
                ?.sortedBy { it.name }.orEmpty()
        }.getOrDefault(emptyList())
    }
    LaunchedEffect(Unit) { refresh() }

    fun fetchMarket(url: String = marketSource.trim()) {
        if (url.isBlank()) { marketMsg = if (zh) "请填写市场源地址" else "Market URL required"; marketMsgOk = false; return }
        marketLoading = true
        marketMsg = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 10000; conn.readTimeout = 15000
                    conn.setRequestProperty("User-Agent", "TaffyNiHe-extensions")
                    conn.inputStream.use { it.readTextCapped(ReadLimits.NET_API_BYTES) }
                }
            }
            marketLoading = false
            r.onSuccess { text ->
                runCatching {
                    val arr = JSONArray(text)
                    val list = mutableListOf<JSONObject>()
                    for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                    marketItems = list
                    marketMsg = if (zh) "已拉取 ${list.size} 个插件" else "${list.size} plugins"
                    marketMsgOk = true
                    marketPrefs.edit().putString("extension_market_url", url).apply()
                }.onFailure { marketMsg = (if (zh) "清单解析失败: " else "Bad index: ") + it.message; marketMsgOk = false }
            }.onFailure { marketMsg = (if (zh) "拉取失败（检查网络/地址，需可访问 GitHub raw）: " else "Fetch failed: ") + it.message; marketMsgOk = false }
        }
    }

    fun installFromMarket(o: JSONObject) {
        val id = o.optString("id", "").ifBlank { o.optString("name", "plugin").replace(Regex("[^A-Za-z0-9_.-]"), "_") }
        val fileUrl = o.optString("file", "")
        if (fileUrl.isBlank()) { marketMsg = "插件缺少 file 字段"; marketMsgOk = false; return }
        marketLoading = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = java.net.URL(fileUrl).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 10000; conn.readTimeout = 60000
                    conn.setRequestProperty("User-Agent", "TaffyNiHe-extensions")
                    val bytes = conn.inputStream.use { it.readBytesCapped(ReadLimits.PLUGIN_PACKAGE_BYTES) }
                    val dir = File(pluginsRoot, id).apply { mkdirs() }
                    val isZip = bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
                    if (isZip) {
                        // zip 包插件：解包（plugin.py + meta.json + assets/ 等），内含 meta.json 时以包内为准
                        var innerMeta = false
                        java.util.zip.ZipInputStream(bytes.inputStream().buffered()).use { zis ->
                            var e = zis.nextEntry
                            while (e != null) {
                                val name = e.name.trimStart('/').removePrefix("plugin/").removePrefix("${id}/")
                                if (name.isBlank() || name.contains("..")) { zis.closeEntry(); e = zis.nextEntry; continue }
                                val out = File(dir, name)
                                if (e.isDirectory) out.mkdirs() else {
                                    out.parentFile?.mkdirs()
                                    out.outputStream().use { zis.copyTo(it) }
                                    if (name == "meta.json") innerMeta = true
                                }
                                zis.closeEntry(); e = zis.nextEntry
                            }
                        }
                        if (!File(dir, "plugin.py").isFile) throw IllegalStateException("zip 内缺少 plugin.py")
                        if (!innerMeta) {
                            File(dir, "meta.json").writeText(JSONObject()
                                .put("schema", 1).put("name", o.optString("name", id)).put("id", id)
                                .put("version", o.optString("version", "1.0"))
                                .put("author", o.optString("author", "market"))
                                .put("description", o.optString("description", ""))
                                .put("source", "market").put("entry", "plugin.py").toString(2))
                        }
                    } else {
                        // 单文件 .py 插件
                        File(dir, "plugin.py").writeText(bytes.decodeToString())
                        File(dir, "meta.json").writeText(JSONObject()
                            .put("schema", 1)
                            .put("name", o.optString("name", id)).put("id", id)
                            .put("version", o.optString("version", "1.0"))
                            .put("author", o.optString("author", "market"))
                            .put("description", o.optString("description", ""))
                            .put("source", "market").put("entry", "plugin.py").toString(2))
                    }
                    "ok"
                }
            }
            marketLoading = false
            r.onSuccess {
                marketMsg = if (zh) "已安装: $id（在「插件」tab 查看）" else "Installed: $id"
                marketMsgOk = true
                refresh()
            }.onFailure { marketMsg = (if (zh) "安装失败: " else "Install failed: ") + it.message; marketMsgOk = false }
        }
    }

    /** 导入分流: taffy 原生直装；Xed 的 apk/zip 走转换器。 */
    fun import(file: File) {
        val kind = XedExtensionConverter.detectKind(file)
        when (kind) {
            "taffy" -> {
                scope.launch {
                    val id = file.nameWithoutExtension.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                    val dir = File(pluginsRoot, id)
                    dir.mkdirs()
                    withContext(Dispatchers.IO) {
                        file.copyTo(File(dir, "plugin.py"), overwrite = true)
                        if (!File(dir, "meta.json").isFile) {
                            File(dir, "meta.json").writeText(
                                JSONObject().put("name", id).put("id", id).put("version", "1.0")
                                    .put("source", "taffy").toString(2),
                            )
                        }
                    }
                    refresh()
                    message = if (zh) "已导入塔菲插件: $id" else "Imported taffy plugin: $id"
                }
            }
            "xed", "unknown" -> {
                scope.launch {
                    val r = withContext(Dispatchers.IO) { XedExtensionConverter.convert(context, file, pluginsRoot) }
                    message = r.message
                    if (r.ok) refresh()
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val cached = withContext(Dispatchers.IO) {
                runCatching {
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "import.bin"
                    val f = File(context.cacheDir, "import_$name")
                    context.contentResolver.openInputStream(uri)?.use { it.copyTo(f.outputStream()) }
                    f
                }.getOrNull()
            }
            if (cached != null && cached.isFile) import(cached) else message = if (zh) "导入失败: 无法读取所选文件" else "Import failed"
        }
    }

    /** 运行插件: plugin_runner.py 注入 taffy_ext + TAFFY_* 环境。入口文件由 meta.json entry 决定（默认 plugin.py）。 */
    fun runPlugin(dir: File) {
        val runner = PythonRuntime.supportScript(context, "plugin_runner.py")
        val cli = PythonRuntime.supportScript(context, "taffy_cli.py")
        if (runner == null || cli == null) { appendOut("[运行器不可用]\n"); return }
        val entry = runCatching { JSONObject(File(dir, "meta.json").readTextCapped(ReadLimits.META_JSON_BYTES)).optString("entry", "plugin.py") }.getOrDefault("plugin.py")
        val pluginPy = File(dir, entry)
        if (!pluginPy.isFile) { appendOut("[入口文件不存在: $entry]\n"); return }
        running = true
        appendOut("──── 运行插件: ${dir.name} ────\n")
        scope.launch {
            val settings = SettingsStore(context)
            val ws = WorkspacePolicy.workDirPath(context) ?: ""
            // 沙箱: 禁外网（仅放行塔菲 MCP 端口）/ 禁子进程 / 写白名单=工作区+插件目录
            val sandboxWrite = listOf(ws, dir.absolutePath).filter { it.isNotBlank() }.joinToString(File.pathSeparator)
            val env = mapOf(
                "TAFFY_WORKSPACE" to ws,
                "TAFFY_PLUGIN_DIR" to dir.absolutePath,
                "TAFFY_SUPPORT" to File(cli).parentFile.absolutePath,
                "TAFFY_MCP_URL" to "http://127.0.0.1:${settings.port}/mcp",
                "TAFFY_TOKEN" to settings.accessToken,
                "TAFFY_SANDBOX" to "1",
                "TAFFY_SANDBOX_WRITE" to sandboxWrite,
                "TAFFY_SANDBOX_NET" to "127.0.0.1:${settings.port}",
            )
            val r = withContext(Dispatchers.IO) {
                PythonRuntime.run(context, "", args = listOf(runner, pluginPy.absolutePath), timeoutSec = 180, extraEnv = env)
            }
            running = false
            appendOut(r.output.ifBlank { "(无输出)\n" })
        }
    }

    fun createPlugin(id: String, name: String) {
        val clean = id.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "plugin_${System.currentTimeMillis()}" }
        val dir = File(pluginsRoot, clean)
        dir.mkdirs()
        File(dir, "plugin.py").writeText(
            """
meta = {
    "name": "$name",
    "version": "1.0",
    "author": "taffy user",
    "description": "塔菲逆核插件",
    "source": "taffy",
}

def run(ext):
    # 插件入口。ext 即 taffy_ext —— 塔菲宿主能力全在这里。
    ext.log("hello from $clean!")
    ext.log("工作区:", ext.workspace())
    ext.log("工作区文件:", ext.files()[:10])
    # ext.write("note.txt", "由插件写入")
    # result = ext.mcp("taffy_workspace", action="list")   # 调用塔菲 MCP 工具
    return "插件运行成功。编辑本文件开始你的扩展！"
""".trim() + "\n",
        )
        File(dir, "meta.json").writeText(
            JSONObject().put("schema", 1).put("name", name.ifBlank { clean }).put("id", clean).put("version", "1.0")
                .put("author", "taffy user").put("description", "塔菲逆核插件").put("source", "taffy")
                .put("entry", "plugin.py").toString(2),
        )
        File(dir, "README.md").writeText(
            """# $clean

${if (name.isBlank()) clean else name} —— 塔菲逆核插件。

## 结构（标准）
- plugin.py   入口（meta.json 的 entry 指向它）
- meta.json   元数据（schema/name/id/version/author/description/source/entry）
- README.md   本说明
- assets/     可选：插件自带资源

## 开发
在编辑器打开 plugin.py 写 run(ext)，扩展页点「运行」验证。
完整教程见扩展页「教程 / API」tab。
""".trim() + "\n",
        )
        refresh()
        message = if (zh) "已创建插件: $clean（点「编辑」开始写代码）" else "Created: $clean"
    }

    val filteredPlugins = remember(plugins, pluginQuery) {
        val q = pluginQuery.trim()
        if (q.isEmpty()) plugins else plugins.filter { it.name.contains(q, true) }
    }
    val filteredMarket = remember(marketItems, marketQuery) {
        val q = marketQuery.trim()
        if (q.isEmpty()) marketItems else marketItems.filter {
            it.optString("name").contains(q, true) || it.optString("id").contains(q, true) ||
                it.optString("description").contains(q, true)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Tab 条（FlowRow：窄屏不截断）
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = tab == 0,
                onClick = { tab = 0 },
                label = { Text("${if (zh) "插件" else "Plugins"} ${plugins.size}", fontSize = AppText.label) },
            )
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text(if (zh) "教程 / API" else "Guide / API", fontSize = AppText.label) })
            FilterChip(
                selected = tab == 2,
                onClick = { tab = 2; if (marketItems.isEmpty() && !marketLoading) fetchMarket() },
                label = { Text("${if (zh) "市场" else "Market"} ${if (marketItems.isEmpty()) "" else marketItems.size}", fontSize = AppText.label) },
            )
        }

        when (tab) {
            // ═══════════ 教程 / API ═══════════
            1 -> {
                InlineHint(
                    if (zh) "内置教程随 app 离线可用；长按可选中复制。"
                    else "Built-in guide, works offline. Long-press to select & copy.",
                )
                TerminalPane(
                    text = EXTENSION_GUIDE,
                    title = if (zh) "教程 / API 速查" else "Guide / API",
                    placeholder = "—",
                    maxHeight = 460.dp,
                )
            }

            // ═══════════ 市场 ═══════════
            2 -> {
                AppCard(title = if (zh) "市场源" else "Market source") {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = marketSource,
                            onValueChange = { marketSource = it },
                            label = { Text(if (zh) "index.json 地址（任何 GitHub 仓库均可作源）" else "index.json URL") },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ExtBtn(if (zh) "刷新市场" else "Refresh", enabled = !marketLoading, accent = true) { fetchMarket() }
                            ExtBtn(if (zh) "用默认源" else "Default") { marketSource = DEFAULT_MARKET_URL; fetchMarket() }
                        }
                    }
                }
                if (marketMsg.isNotEmpty()) {
                    InlineHint(marketMsg, tone = if (marketMsgOk) HintTone.Ok else HintTone.Error)
                }
                if (marketItems.isNotEmpty()) {
                    SearchCountBar(
                        query = marketQuery,
                        onQueryChange = { marketQuery = it },
                        shown = filteredMarket.size,
                        total = marketItems.size,
                        placeholder = if (zh) "搜索市场插件" else "Search market",
                    )
                }
                if (filteredMarket.isEmpty()) {
                    InlineHint(
                        if (marketLoading) (if (zh) "拉取中…" else "Loading…")
                        else if (zh) "市场为空或未拉取。任何 GitHub 仓库只要提供 index.json（数组：id/name/version/author/description/file）即可作为源——file 指向插件 .py 或 zip 的可下载地址。"
                        else "Provide an index.json array (id/name/version/author/description/file) on any GitHub repo.",
                    )
                } else {
                    filteredMarket.forEach { o ->
                        val mid = o.optString("id", o.optString("name"))
                        val installed = File(File(pluginsRoot, mid), "plugin.py").isFile
                        AppCard {
                            DataRow(
                                title = o.optString("name", mid),
                                subtitle = "by ${o.optString("author", "?").ifBlank { "?" }}",
                                meta = o.optString("description").ifBlank { null },
                                leading = { TypeChip("v${o.optString("version", "1.0")}", AppPalette.blue) },
                                trailingText = if (installed) (if (zh) "已安装" else "installed") else null,
                                onClick = { if (!installed && !marketLoading) installFromMarket(o) },
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ExtBtn(
                                    if (installed) (if (zh) "已安装" else "Installed") else (if (zh) "安装" else "Install"),
                                    enabled = !marketLoading && !installed,
                                    accent = !installed,
                                ) { installFromMarket(o) }
                                Spacer(Modifier.weight(1f))
                                Text(mid, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }

            // ═══════════ 插件 ═══════════
            else -> {
                // 操作行
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExtBtn(if (zh) "＋ 新建插件" else "＋ New plugin", accent = true) { showNewDialog = true }
                    ExtBtn(if (zh) "导入 .py / Xed .apk / .zip" else "Import") {
                        importLauncher.launch(arrayOf("*/*", "application/zip", "application/vnd.android.package-archive", "text/x-python"))
                    }
                    ExtBtn(if (zh) "刷新" else "Refresh") { refresh() }
                }
                if (message.isNotEmpty()) {
                    InlineHint(message, tone = HintTone.Ok)
                }

                if (plugins.isNotEmpty()) {
                    SearchCountBar(
                        query = pluginQuery,
                        onQueryChange = { pluginQuery = it },
                        shown = filteredPlugins.size,
                        total = plugins.size,
                        placeholder = if (zh) "搜索插件" else "Search plugins",
                    )
                }

                if (plugins.isEmpty()) {
                    InlineHint(
                        if (zh) "还没有插件 —— 点「新建插件」从模板开始，或「导入」已有的 .py / Xed 扩展 .apk"
                        else "No plugins yet — create from template or import .py / Xed .apk",
                    )
                } else if (filteredPlugins.isEmpty()) {
                    InlineHint(if (zh) "没有匹配的插件" else "No matching plugin")
                } else {
                    filteredPlugins.forEach { dir ->
                        val meta = runCatching { JSONObject(File(dir, "meta.json").readTextCapped(ReadLimits.META_JSON_BYTES)) }.getOrElse { JSONObject() }
                        val name = meta.optString("name", dir.name)
                        val version = meta.optString("version", "1.0")
                        val source = meta.optString("source", "taffy")
                        val desc = meta.optString("description", "")
                        val isXed = source == "xed"
                        AppCard {
                            DataRow(
                                title = name,
                                subtitle = "v$version · ${dir.name}",
                                meta = desc.ifBlank { null },
                                leading = {
                                    TypeChip(
                                        if (isXed) (if (zh) "Xed 转换" else "Xed") else "taffy",
                                        if (isXed) AppPalette.orange else AppPalette.green,
                                    )
                                },
                                onClick = {
                                    EditorBridge.pendingPath = File(dir, "plugin.py").absolutePath
                                    onDest(SettingsDest.Python)
                                },
                            )
                            FlowRow(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                ExtBtn(if (zh) "运行" else "Run", enabled = !running, accent = true) { runPlugin(dir) }
                                ExtBtn(if (zh) "编辑" else "Edit") {
                                    EditorBridge.pendingPath = File(dir, "plugin.py").absolutePath
                                    onDest(SettingsDest.Python)
                                }
                                ExtBtn(if (zh) "删除" else "Delete") { deleteTarget = dir }
                                if (isXed) {
                                    val promptFile = File(dir, "AI_CONVERT_PROMPT.txt")
                                    if (promptFile.isFile) {
                                        ExtBtn("🤖 " + (if (zh) "AI 全自动转换" else "AI convert")) {
                                            copyToClipboard(context, promptFile.readTextCapped(ReadLimits.META_JSON_BYTES))
                                            Toast.makeText(context, if (zh) "AI 转换 prompt 已复制——粘贴给 AI 即可自动生成完整 plugin.py" else "AI convert prompt copied", Toast.LENGTH_LONG).show()
                                        }
                                        ExtBtn(if (zh) "看转换报告" else "Report") {
                                            EditorBridge.pendingPath = File(dir, "CONVERT_INFO.md").absolutePath
                                            onDest(SettingsDest.Python)
                                        }
                                    }
                                }
                            }
                            if (isXed) {
                                Text(
                                    if (zh) "字节码逻辑无法自动翻译——用「AI 全自动转换」复制 prompt 给 AI，产出完整 plugin.py 后粘贴保存"
                                    else "Bytecode logic can't be auto-translated — use AI convert then paste the generated plugin.py",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppPalette.orange,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }

                // 运行输出（统一终端框）
                TerminalPane(
                    text = output,
                    title = if (zh) "运行输出" else "Output",
                    onClear = { output = "" },
                    placeholder = if (zh) "点插件的「运行」查看输出…" else "Run a plugin to see output…",
                    maxHeight = 300.dp,
                )
            }
        }
    }

    // ── 新建插件对话框 ──
    if (showNewDialog) {
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text(if (zh) "新建插件" else "New plugin", fontSize = AppText.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newId,
                        onValueChange = { newId = it },
                        label = { Text(if (zh) "插件 ID（英文/数字）" else "Plugin ID", fontSize = AppText.label) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(if (zh) "显示名称" else "Display name", fontSize = AppText.label) },
                        singleLine = true,
                    )
                    Text(
                        if (zh) "将生成标准结构：plugin.py + meta.json + README.md" else "Creates plugin.py + meta.json + README.md",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showNewDialog = false
                    createPlugin(newId, newName)
                    newId = ""; newName = ""
                }) { Text(if (zh) "创建" else "Create") }
            },
            dismissButton = { TextButton(onClick = { showNewDialog = false }) { Text(if (zh) "取消" else "Cancel") } },
        )
    }

    // ── 删除确认（统一二次确认）──
    deleteTarget?.let { target ->
        ConfirmDialog(
            title = if (zh) "删除插件" else "Delete plugin",
            message = if (zh) "确定删除「${target.name}」？此操作不可恢复。" else "Delete \"${target.name}\"? This cannot be undone.",
            confirmText = if (zh) "删除" else "Delete",
            destructive = true,
            onConfirm = {
                runCatching { target.deleteRecursively() }
                deleteTarget = null
                refresh()
                message = if (zh) "已删除" else "Deleted"
            },
            onDismiss = { deleteTarget = null },
        )
    }

    BusyOverlay(
        visible = running || marketLoading,
        message = if (marketLoading) (if (zh) "市场处理中…" else "Working…") else (if (zh) "运行插件…" else "Running plugin…"),
    )
}

/** 扩展教程（内置，随 app 离线可用）。 */
private const val EXTENSION_GUIDE = """═══ 塔菲逆核 · Python 扩展系统教程 ═══

【1. 插件是什么】
一个目录，内含 plugin.py（入口）+ meta.json（元数据）。
放在扩展系统里即可被列出、运行、编辑、导入导出。

【2. 插件最小结构】
meta = {"name": "示例", "version": "1.0",
        "author": "you", "description": "做什么",
        "source": "taffy"}

def run(ext):          # ← 入口函数，ext 是塔菲宿主 API
    ext.log("hello")
    return "运行结果"   # 显示在输出区

【3. taffy_ext API 速查】
ext.log(*args)              日志输出
ext.workspace()             工作区绝对路径
ext.plugin_dir()            本插件目录
ext.files(sub="")           列工作区文件
ext.read(path)              读工作区文件
ext.write(path, data)       写工作区文件
ext.mcp(tool, **kwargs)     调用塔菲任意 MCP 工具
ext.tools()                 列出全部 MCP 工具

【4. 常用 MCP 工具】
ext.mcp("taffy_workspace", action="list")      工作区
ext.mcp("taffy_terminal_exec", command="ls")   终端执行
ext.mcp("taffy_rz", ...)                        Rizin 逆向
ext.mcp("taffy_edbg_...")                       eDBG 动态调试
ext.mcp("taffy_capture_...")                    抓包
完整列表: ext.tools() 或「设置→服务配置→工具」

【5. 示例：给工作区所有 .py 加行号】
def run(ext):
    out = []
    for f in ext.files():
        if f.endswith(".py"):
            lines = ext.read(f).splitlines()
            ext.write(f, "".join(f"{i+1:4d} {ln}\n" for i, ln in enumerate(lines)))
            out.append(f)
    return "处理: " + ", ".join(out)

【6. 示例：调 Rizin 分析 SO】
def run(ext):
    return ext.mcp("taffy_rz", action="command",
                   command="afl", target="/sdcard/.../libdemo.so")

【7. Xed-Editor 扩展导入（含 AI 全自动转换）】
直接「导入」Xed 的 .apk 或商店 .zip —— 塔菲自动:
  ① 解包读 manifest.json（id/mainClass/版本）
  ② dexlib2 解析 classes.dex: 入口类、生命周期钩子、
     字符串常量表、对宿主 API 的调用面
  ③ assets/ 资源原样迁移到插件 xed_assets/
  ④ 生成可运行 plugin.py 骨架 + CONVERT_INFO.md 报告
     + AI_CONVERT_PROMPT.txt（AI 转换提示词）
字节码逻辑无法从静态解析直接翻译成 Python —— 用
插件卡的「🤖 AI 全自动转换」: 复制 prompt 粘给任意
AI（或塔菲 AI 深度分析），AI 按 taffy_ext 规范自动
产出完整 plugin.py，粘贴回编辑器保存即完成。
这就是"导入即转换，AI 补逻辑"的完整流程。

【8. 插件标准结构】
plugins/<id>/
  plugin.py    入口（meta.json entry 指向，可换名）
  meta.json    {"schema":1,"name","id","version",
                "author","description","source",
                "entry"}
  README.md    说明（新建时自动生成）
  assets/      可选资源（Xed 转换为 xed_assets/）
  AI_CONVERT_PROMPT.txt / CONVERT_INFO.md
               （仅 Xed 转换插件有）
runner 按 meta.json 的 entry 加载入口文件。

【9. AI 辅助写插件（不用 Xed 也能用 AI）】
把「教程/API」整段 + 你的需求描述发给 AI，让它按
规范输出 plugin.py；粘贴到新建插件里即可运行。
要点: 只用 ext API、入口 run(ext)、返回值即输出。

【10. 提示】
· 运行有 180s 超时；长任务建议分步
· 插件与 MCP 服务共享权限（工作区范围内读写）
· 导入的 .py 直接可用；发布给他人打包 zip 即可
"""
