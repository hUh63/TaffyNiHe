package com.soreverse.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.mcp.ToolCatalog
import com.soreverse.mcp.mcp.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Flutter / Dart AOT 分析视图（内置 Blutter Runner，全本地）。
 *
 * 后端早就在（`taffy_flutter_blutter` + 4 套 arm64 Runner + AIDL 隔离进程），
 * 但之前只在 MCP 工具清单里可见，分析页没有任何入口 —— 这里把它做成第一类视图：
 *
 * 1. **识别**：按 APK 指纹（libapp/libflutter 的 snapshot hash + engine revision + 压缩指针模式）
 *    判断 Flutter/Dart 版本，并显示命中的内置 Runner；不支持时给出明确原因，而不是让分析静默失败。
 * 2. **分析**：提交作业 → 轮询阶段 → 结果按 库 / 类 / 函数 / 对象 分页浏览（含警告与产物清单）。
 * 3. **取消**：随时终止隔离进程。
 */
@Composable
internal fun FlutterView(
    state: WorkspaceState,
    tools: ToolPagesState,
    zh: Boolean,
    context: android.content.Context,
) {
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()

    var path by remember { mutableStateOf("") }
    var inspecting by remember { mutableStateOf(false) }
    var analyzing by remember { mutableStateOf(false) }
    var inspect by remember { mutableStateOf<JSONObject?>(null) }
    var jobId by remember { mutableStateOf("") }
    var jobStatus by remember { mutableStateOf("") }
    var jobStage by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var kind by remember { mutableStateOf("classes") }
    var shown by remember { mutableStateOf(80) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }

    fun callBlutter(args: JSONObject): JSONObject {
        val handler = ToolCatalog.byName["taffy_flutter_blutter"]
            ?: return JSONObject().put("ok", false).put("error", JSONObject().put("code", "TOOL_MISSING").put("message", "taffy_flutter_blutter 未注册"))
        val ctx = ToolContext(
            context = appContext,
            settings = SettingsStore(appContext),
            engine = EngineProvider.get(appContext),
            binaryEngine = runCatching { EngineProvider.getBinaryEngine(appContext) }.getOrNull(),
        )
        return runCatching { handler.handle(ctx, args) }
            .getOrElse { JSONObject().put("ok", false).put("error", JSONObject().put("code", "UI_EXCEPTION").put("message", it.message ?: it.javaClass.simpleName)) }
    }

    /** 错误对象取文案：优先 error.message，其次 error 字符串。 */
    fun errorText(r: JSONObject?): String {
        if (r == null) return if (zh) "没有响应" else "no response"
        val obj = r.optJSONObject("error")
        obj?.optString("message")?.takeIf { it.isNotBlank() && it != "null" }?.let { return it }
        obj?.optString("code")?.takeIf { it.isNotBlank() && it != "null" }?.let { return it }
        r.optString("error").takeIf { it.isNotBlank() && it != "null" }?.let { return it }
        return if (zh) "未提供原因" else "no reason given"
    }

    /** 自动猜输入：任务主文件 → （content:// 时）引擎已拷到缓存的副本。 */
    fun guessInput(): String {
        val taskPath = state.currentTask()?.mainPath.orEmpty()
        if (taskPath.isNotBlank() && !taskPath.startsWith("content://")) return taskPath
        if (taskPath.startsWith("content://")) {
            val segment = android.net.Uri.parse(taskPath).lastPathSegment?.substringAfterLast('/')?.substringBefore('?')
            if (!segment.isNullOrBlank()) {
                val dir = appContext.cacheDir
                // 引擎打开 content:// 时会复制成 picked_<name>，直接复用那份本地副本（blutter 需要真实文件）
                val hit = dir.listFiles()?.firstOrNull { it.isFile && it.name.startsWith("picked_") && it.name.contains(segment.substringBeforeLast('.')) && it.length() > 0 }
                if (hit != null) return hit.absolutePath
            }
        }
        return ""
    }

    LaunchedEffect(state.currentTask()?.id) {
        if (path.isBlank()) path = guessInput()
    }

    /** inspect/analyze 只接受 APK，或含 libapp.so/libflutter.so 的目录。 */
    fun looksApkOrDir(p: String): Boolean {
        val t = p.trim()
        if (t.isBlank()) return false
        if (t.endsWith(".apk", true)) return true
        if (t.endsWith("/")) return true
        return !t.substringAfterLast('/').contains('.')   // 无扩展名 → 视为目录
    }

    fun doInspect() {
        val target = path.trim()
        if (target.isBlank()) {
            error = if (zh) "请先指定 APK（或含 libapp.so + libflutter.so 的目录）" else "Provide an APK (or a directory with libapp.so + libflutter.so)"
            return
        }
        if (!looksApkOrDir(target)) {
            error = if (zh) "「${target.substringAfterLast('/')}」不是 APK。Flutter 分析需要 APK，或含 libapp.so + libflutter.so 的目录；可点「工作区 APK」选择当前已打开的 APK。"
                else "'${target.substringAfterLast('/')}' is not an APK. Provide an APK, or a directory with libapp.so + libflutter.so."
            return
        }
        inspecting = true; error = ""; notice = ""; inspect = null; result = null; jobId = ""; jobStatus = ""; jobStage = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) { callBlutter(JSONObject().put("action", "inspect").put("path", target)) }
            inspecting = false
            if (!r.optBoolean("ok", false)) error = errorText(r) else inspect = r
        }
    }

    fun doAnalyze() {
        val target = path.trim()
        if (target.isBlank()) {
            error = if (zh) "请先指定 APK" else "Provide an APK first"
            return
        }
        if (!looksApkOrDir(target)) {
            error = if (zh) "「${target.substringAfterLast('/')}」不是 APK，无法分析；请选择 APK 或含 libapp.so + libflutter.so 的目录。"
                else "'${target.substringAfterLast('/')}' is not an APK."
            return
        }
        analyzing = true; error = ""; notice = ""; result = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { callBlutter(JSONObject().put("action", "analyze").put("path", target)) }
            analyzing = false
            val id = r.optString("jobId").takeIf { it.isNotBlank() && it != "null" }
            if (id == null) {
                error = errorText(r)
                return@launch
            }
            jobId = id
            jobStatus = r.optString("status").ifBlank { "running" }
            jobStage = "queued"
            notice = when {
                jobStatus == "succeeded" -> if (zh) "命中缓存，直接复用上次结果" else "cache hit, reused previous result"
                else -> ""
            }
            // 轮询到终态（隔离进程一次只跑一个作业，正常在秒级到分钟级）
            var rounds = 0
            while (rounds < 600 && jobStatus !in setOf("succeeded", "failed", "cancelled", "interrupted")) {
                delay(1500)
                rounds++
                val st = withContext(Dispatchers.IO) { callBlutter(JSONObject().put("action", "status").put("jobId", jobId)) }
                if (st.optBoolean("ok", false)) {
                    jobStatus = st.optString("status").ifBlank { jobStatus }
                    jobStage = st.optString("stage").ifBlank { jobStage }
                    if (jobStatus in setOf("failed", "cancelled", "interrupted")) error = errorText(st)
                }
            }
            if (jobStatus == "succeeded") {
                val res = withContext(Dispatchers.IO) { callBlutter(JSONObject().put("action", "result").put("jobId", jobId).put("kind", kind).put("limit", 1000)) }
                if (res.optBoolean("ok", false)) result = res else error = errorText(res)
            }
        }
    }

    fun doCancel() {
        if (jobId.isBlank()) return
        scope.launch {
            withContext(Dispatchers.IO) { callBlutter(JSONObject().put("action", "cancel").put("jobId", jobId)) }
            jobStatus = "cancelled"
            jobStage = "cancelled"
            notice = if (zh) "已请求取消" else "cancel requested"
        }
    }

    val running = analyzing || jobStatus == "running" || jobStatus == "queued"
    val inspectAnalysis = inspect?.optJSONObject("selectedAnalysis")
    val flutter = (inspectAnalysis ?: inspect)?.optJSONObject("flutter")
    val summary = result?.optJSONObject("summary")

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── 输入 ──
        item {
            GlassGroup {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (zh) "Flutter / Dart AOT 分析" else "Flutter / Dart AOT analysis",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (zh) "全本地运行，不上传 libapp.so / libflutter.so，也不需要 Python、ADB 或网络。"
                        else "Runs fully on-device: no upload of libapp.so / libflutter.so, no Python, ADB or network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it; error = "" },
                        singleLine = true,
                        label = { Text(if (zh) "APK 路径（或含 libapp.so + libflutter.so 的目录）" else "APK path (or libapp/libflutter directory)") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ToolChip(if (zh) "当前文件" else "Current", onClick = {
                            val g = guessInput()
                            path = g
                            error = when {
                                g.isBlank() -> if (zh) "当前任务没有可用的本地文件路径" else "no local file for current task"
                                !looksApkOrDir(g) -> if (zh) "当前文件不是 APK（${g.substringAfterLast('/')}）；Flutter 分析需要 APK 或含 libapp.so/libflutter.so 的目录" else "current file is not an APK"
                                else -> ""
                            }
                        })
                        ToolChip(if (zh) "工作区 APK" else "Workspace APK", onClick = {
                            scope.launch {
                                val handler = ToolCatalog.byName["taffy_so_close"]
                                val ctx = ToolContext(appContext, SettingsStore(appContext), EngineProvider.get(appContext), runCatching { EngineProvider.getBinaryEngine(appContext) }.getOrNull())
                                val r = withContext(Dispatchers.IO) { runCatching { handler?.handle(ctx, JSONObject().put("action", "list")) }.getOrNull() }
                                val list = r?.optJSONArray("items")
                                var found = ""
                                for (i in 0 until (list?.length() ?: 0)) {
                                    val ws = list!!.optJSONObject(i) ?: continue
                                    if (tools.sharedWorkspaceId.isNotBlank() && ws.optString("workspaceId") != tools.sharedWorkspaceId) continue
                                    val apk = ws.optString("apkPath").takeIf { p -> p.isNotBlank() && p != "null" }
                                    val p = ws.optString("path")
                                    if (apk != null && File(apk).isFile) { found = apk; break }
                                    if (p.endsWith(".apk", true) && File(p).isFile) { found = p; break }
                                }
                                if (found.isNotBlank()) path = found
                                else error = if (zh) "当前工作区没有关联的 APK（可直接打开 APK 或输入路径）" else "No APK linked to the current workspace"
                            }
                        })
                        ToolChip(if (zh) "识别" else "Inspect", accent = true, enabled = !inspecting && !running, onClick = { doInspect() })
                        ToolChip(if (zh) "分析" else "Analyze", accent = true, enabled = !running && !inspecting, onClick = { doAnalyze() })
                        if (running) {
                            ToolChip(if (zh) "取消" else "Cancel", onClick = { doCancel() })
                        }
                        if (inspecting || analyzing) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    }
                    if (state.currentTask() != null) {
                        Text(
                            if (zh) "当前任务：${state.currentTask()?.mainName.orEmpty()}" else "Task: ${state.currentTask()?.mainName.orEmpty()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (error.isNotBlank()) item { InlineHint(error, tone = HintTone.Error) }
        if (notice.isNotBlank()) item { InlineHint(notice, tone = HintTone.Ok) }

        // ── 识别结果 ──
        inspect?.let { ins ->
            item {
                GlassGroup {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (zh) "识别结果" else "Inspection", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            val detected = flutter?.optBoolean("detected", false) ?: (inspectAnalysis != null)
                            TypeChip(
                                if (detected) (if (zh) "Flutter AOT" else "Flutter AOT") else (if (zh) "未检测到" else "not detected"),
                                color = if (detected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        }
                        val abi = inspectAnalysis?.optString("abi")?.takeIf { it.isNotBlank() && it != "null" }
                            ?: ins.optJSONObject("selected")?.optString("abi")?.takeIf { it.isNotBlank() && it != "null" }
                        MonoRow(if (zh) "Dart 版本" else "Dart", flutter?.optString("dartVersion")?.let { if (it == "null") "" else it }.orEmpty().ifBlank { "—" })
                        MonoRow("ABI", abi ?: "—")
                        flutter?.optString("snapshotHash")?.takeIf { it.isNotBlank() && it != "null" }?.let { MonoRow("snapshot", it) }
                        flutter?.optJSONArray("engineIds")?.let { ids ->
                            if (ids.length() > 0) MonoRow("engine", ids.optString(0).take(16))
                        }
                        flutter?.let { MonoRow(if (zh) "压缩指针" else "compressed ptrs", if (it.optBoolean("compressedPointers", false)) (if (zh) "是" else "yes") else (if (zh) "否" else "no")) }
                        flutter?.let { MonoRow(if (zh) "指纹置信度" else "confidence", "%.0f%%".format(it.optDouble("confidence", 0.0) * 100)) }
                        flutter?.optJSONArray("flags")?.let { flags ->
                            if (flags.length() > 0) MonoRow("flags", (0 until flags.length()).joinToString(", ") { i -> flags.optString(i) })
                        }
                        val libapp = inspectAnalysis?.optJSONObject("libapp")
                        val libflutter = inspectAnalysis?.optJSONObject("libflutter")
                        if (libapp != null || libflutter != null) {
                            Spacer(Modifier.size(2.dp))
                            libapp?.let { MonoRow("libapp.so", "${formatBytesShort(it.optLong("size"))} · ${it.optString("sha256").take(12)}") }
                            libflutter?.let { MonoRow("libflutter.so", "${formatBytesShort(it.optLong("size"))} · ${it.optString("sha256").take(12)}") }
                        }
                        ins.optJSONObject("flutter")?.optJSONArray("supportedAbis")?.let { abis ->
                            if (abis.length() > 0) MonoRow(if (zh) "APK 内 ABI" else "ABIs in APK", (0 until abis.length()).joinToString(", ") { i -> abis.optString(i) })
                        }
                    }
                }
            }
        }

        // ── 作业进度 ──
        if (jobId.isNotBlank()) {
            item {
                GlassGroup {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (zh) "分析作业" else "Job", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            TypeChip(jobStatus.ifBlank { "—" }, color = when (jobStatus) {
                                "succeeded" -> AppPalette.green
                                "failed", "cancelled", "interrupted" -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            })
                        }
                        MonoRow("jobId", jobId)
                        MonoRow(if (zh) "阶段" else "stage", jobStage.ifBlank { "—" })
                        if (running) {
                            Spacer(Modifier.size(2.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }

        // ── 结果 ──
        result?.let { res ->
            item {
                GlassGroup {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (zh) "结果" else "Result", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            res.optJSONObject("runner")?.optString("dartVersion")?.takeIf { it.isNotBlank() && it != "null" }?.let { TypeChip("Dart $it") }
                        }
                        if (summary != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    "libraries" to (if (zh) "库" else "libs"),
                                    "classes" to (if (zh) "类" else "classes"),
                                    "functions" to (if (zh) "函数" else "funcs"),
                                    "objects" to (if (zh) "对象" else "objects"),
                                ).forEach { (k, label) ->
                                    ToolChip("$label ${summary.optInt(k, 0)}", selected = kind == k, onClick = { kind = k; shown = 80 })
                                }
                            }
                        }
                    }
                }
            }
            val page = res.optJSONObject(kind)
            val entityItems = page?.optJSONArray("items") ?: JSONArray()
            val total = page?.optInt("total", entityItems.length()) ?: entityItems.length()
            if (entityItems.length() == 0) {
                item { InlineHint(if (zh) "该类别下没有实体（总数 0）" else "No entities in this category", tone = HintTone.Neutral) }
            } else {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (zh) "显示 ${minOf(shown, entityItems.length())} / $total" else "${minOf(shown, entityItems.length())} / $total",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (shown < entityItems.length()) {
                            ToolChip(if (zh) "加载更多" else "More", onClick = { shown += 100 })
                        }
                    }
                }
                items(minOf(shown, entityItems.length())) { index ->
                    val e = entityItems.optJSONObject(index)
                    if (e != null) FlutterEntityRow(e)
                }
            }
            val warnings = res.optJSONArray("warnings")
            if (warnings != null && warnings.length() > 0) {
                item {
                    GlassGroup(title = if (zh) "警告" else "Warnings") {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (i in 0 until warnings.length()) {
                                val w = warnings.optJSONObject(i) ?: continue
                                InlineHint("${w.optString("code")}: ${w.optString("message")}", tone = HintTone.Error)
                            }
                        }
                    }
                }
            }
            val artifacts = res.optJSONArray("artifacts")
            if (artifacts != null && artifacts.length() > 0) {
                item {
                    GlassGroup(title = if (zh) "产物" else "Artifacts") {
                        Column(Modifier.fillMaxWidth()) {
                            for (i in 0 until artifacts.length()) {
                                val a = artifacts.optJSONObject(i) ?: continue
                                if (i > 0) GroupDivider()
                                DataRow(
                                    title = a.optString("relativePath"),
                                    subtitle = "${a.optString("kind")} · ${a.optString("mediaType")}",
                                    meta = "${formatBytesShort(a.optLong("size"))} · ${a.optString("sha256").take(12)}",
                                )
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** 一个 Flutter 实体（库 / 类 / 函数 / 对象）：名称 + 归属 + 地址/大小，属性拍平成一行。 */
@Composable
private fun FlutterEntityRow(entity: JSONObject) {
    val name = entity.optString("name").ifBlank { entity.optString("id") }
    val belong = listOfNotNull(
        entity.optString("classId").takeIf { it.isNotBlank() && it != "null" },
        entity.optString("libraryId").takeIf { it.isNotBlank() && it != "null" },
    ).joinToString("  ·  ")
    val address = entity.optString("address").takeIf { it.isNotBlank() && it != "null" }
    val size = if (entity.has("size") && !entity.isNull("size")) entity.optLong("size").takeIf { it > 0 } else null
    val attrs = entity.optJSONObject("attributes")
    val attrText = attrs?.let { a ->
        a.keys().asSequence().take(4).mapNotNull { k -> a.opt(k)?.takeIf { it != JSONObject.NULL }?.let { "$k=$it" } }.joinToString(" ")
    }.orEmpty()
    val meta = listOfNotNull(address, size?.let { "$it B" }, attrText.takeIf { it.isNotBlank() }).joinToString("  ·  ").ifBlank { null }
    DataRow(
        title = name,
        subtitle = belong.takeIf { it.isNotBlank() },
        meta = meta,
        trailingText = entity.optString("kind").takeIf { it.isNotBlank() && it != "null" },
    )
}

private fun formatBytesShort(n: Long): String = when {
    n >= (1L shl 20) -> "%.1f MB".format(n / 1048576.0)
    n >= 1024L -> "%.1f KB".format(n / 1024.0)
    else -> "$n B"
}
