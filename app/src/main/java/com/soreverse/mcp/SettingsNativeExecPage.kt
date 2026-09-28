package com.soreverse.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.mcp.ToolCatalog
import com.soreverse.mcp.mcp.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 设置 → 本地可执行（execve + jniLibs 通道）。
 *
 * 这里把「随 APK 打包、安装后在 nativeLibraryDir 具可执行权限」的二进制直接列出来跑：
 * 免 root、免 proot、免 rootfs。用于 frida-server / cloudflared 这类自带二进制，
 * 也可用来验证自己塞进 `jniLibs/<abi>/lib<name>.so` 的 Android PIE 可执行文件。
 *
 * 图形化约定：候选是一张张数据行（ELF 判定 + 可运行性 + 权限），执行输出走统一终端框，
 * 后台进程单独成组（动作按钮内联），不提供「原始 JSON」兜底出口。
 */
@Composable
internal fun SettingsNativeExecPage(t: UiText) {
    val zh = t.zh
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    var payload by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var outputTitle by remember { mutableStateOf("") }
    var procs by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var target by remember { mutableStateOf<JSONObject?>(null) }
    var argText by remember { mutableStateOf("") }
    var timeoutText by remember { mutableStateOf("30") }
    var pendingKill by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun callTool(args: JSONObject): JSONObject? {
        val handler = ToolCatalog.byName["taffy_native_exec"] ?: return null
        val ctx = ToolContext(
            context = context,
            settings = SettingsStore(context),
            engine = EngineProvider.get(context),
            binaryEngine = runCatching { EngineProvider.getBinaryEngine(context) }.getOrNull(),
        )
        return runCatching { handler.handle(ctx, args) }.getOrNull()
    }

    fun errText(r: JSONObject?): String {
        val obj = r?.optJSONObject("error")
        return obj?.optString("message")?.takeIf { it.isNotBlank() && it != "null" }
            ?: obj?.optString("code")?.takeIf { it.isNotBlank() && it != "null" }
            ?: (if (zh) "操作失败" else "operation failed")
    }

    fun refresh() {
        loading = true
        error = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) { callTool(JSONObject().put("action", "list")) }
            loading = false
            if (r == null) { error = if (zh) "工具 taffy_native_exec 未注册" else "taffy_native_exec is not registered"; return@launch }
            if (!r.optBoolean("ok", false)) { error = errText(r); return@launch }
            payload = r
        }
        scope.launch {
            val r = withContext(Dispatchers.IO) { callTool(JSONObject().put("action", "ps")) }
            val arr = r?.optJSONArray("processes")
            procs = (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun runTarget(json: JSONObject, background: Boolean) {
        val name = json.optString("name")
        val argv = argText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val argsJson = JSONArray().also { arr -> argv.forEach { arr.put(it) } }.toString()
        val timeout = timeoutText.trim().toLongOrNull()?.coerceIn(1L, 900L) ?: 30L
        target = null
        busy = true
        outputTitle = if (background) (if (zh) "$name（后台）" else "$name (daemon)") else "$name"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                callTool(
                    JSONObject()
                        .put("action", if (background) "daemon" else "run")
                        .put("name", name)
                        .put("argsJson", argsJson)
                        .put("timeoutSec", timeout)
                )
            }
            busy = false
            if (r == null) { output = if (zh) "[UI] 工具未注册" else "[UI] tool not registered"; return@launch }
            if (background) {
                if (r.optBoolean("ok", false)) {
                    output = "pid=${r.optLong("pid")}\nlog=${r.optString("logFile")}"
                    val ps = withContext(Dispatchers.IO) { callTool(JSONObject().put("action", "ps")) }
                    val arr = ps?.optJSONArray("processes")
                    procs = (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }
                } else {
                    output = errText(r)
                }
            } else {
                val res = r.optJSONObject("result") ?: r
                val sb = StringBuilder()
                sb.append("exit=").append(res.optInt("exitCode", -1))
                if (res.optBoolean("timedOut", false)) sb.append("  (timeout)")
                if (res.optBoolean("truncated", false)) sb.append("  (output truncated)")
                sb.append('\n')
                val out = res.optString("stdout"); val err = res.optString("stderr")
                if (out.isNotBlank()) sb.append(out).append('\n')
                if (err.isNotBlank()) sb.append("[stderr]\n").append(err)
                if (out.isBlank() && err.isBlank()) sb.append(if (zh) "（无输出）" else "(no output)")
                output = sb.toString()
            }
        }
    }

    val candidates = remember(payload) {
        val arr = payload?.optJSONArray("items")
        (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }
    }
    val executableCount = candidates.count { it.optBoolean("runnable", false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            GlassGroup(title = if (zh) "原生可执行通道" else "Native executable channel") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (zh) "Android 10 起应用私有目录禁止 execve，但 APK 安装解压出的 nativeLibraryDir 仍可执行。把 Android PIE 可执行文件命名成 lib<name>.so 放进 jniLibs/<abi>/，安装后即可直接运行 —— 不需要 root、proot 或 rootfs。"
                        else "Since Android 10 the app's private directories forbid execve, but nativeLibraryDir (extracted from the APK) is still executable. Ship a PIE binary as jniLibs/<abi>/lib<name>.so and run it after install — no root, proot or rootfs required.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MonoRow("nativeLibraryDir", payload?.optString("nativeLibraryDir")?.ifBlank { "—" } ?: "—")
                    MonoRow(if (zh) "设备 ABI" else "device ABI", payload?.optString("abi")?.ifBlank { "—" } ?: "—")
                    MonoRow("extractNativeLibs", if (payload?.optBoolean("extractNativeLibs", false) == true) (if (zh) "已开启" else "on") else (if (zh) "关闭" else "off"))
                    Spacer(Modifier.size(2.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ToolChip(if (zh) "刷新" else "Refresh", enabled = !loading, onClick = { refresh() })
                        ToolChip(if (zh) "刷新进程" else "Processes", onClick = {
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { callTool(JSONObject().put("action", "ps")) }
                                val arr = r?.optJSONArray("processes")
                                procs = (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }
                                outputTitle = if (zh) "进程" else "processes"
                                output = procs.joinToString("\n") { "pid=${it.optLong("pid")} ${it.optString("name")} alive=${it.optBoolean("alive")}" }
                            }
                        })
                        if (loading || busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }

        if (error.isNotBlank()) item { InlineHint(error, tone = HintTone.Error) }

        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (zh) "候选二进制" else "Candidates",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (zh) "${candidates.size} 个 · 可运行 $executableCount" else "${candidates.size} · runnable $executableCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (candidates.isEmpty() && !loading) {
            item { InlineHint(if (zh) "nativeLibraryDir 里没有 lib*.so 候选（或该设备未解压 native 库）" else "No lib*.so candidates in nativeLibraryDir", tone = HintTone.Neutral) }
        }

        items(candidates) { json ->
            val runnable = json.optBoolean("runnable", false)
            DataRow(
                title = json.optString("name"),
                subtitle = buildString {
                    append(json.optString("kind"))
                    val bits = json.optInt("bits", 0)
                    if (bits > 0) append(" · ").append(bits).append("-bit")
                    append(" · machine=").append(json.optInt("machine", 0))
                    if (!json.optBoolean("abiMatch", true)) append(if (zh) " · ABI 不匹配" else " · ABI mismatch")
                    if (!json.optBoolean("executable", false)) append(if (zh) " · 非可执行位" else " · no exec bit")
                },
                meta = "${formatBytesShort(json.optLong("size"))}  ·  ${json.optString("path")}",
                trailingText = if (runnable) (if (zh) "运行" else "Run") else (if (zh) "库" else "lib"),
                onClick = {
                    if (runnable) {
                        target = json
                        argText = ""
                        timeoutText = "30"
                    } else {
                        outputTitle = json.optString("name")
                        output = if (zh) "该文件不是可执行的 ELF（判定：${json.optString("kind")}），只能作为共享库使用。"
                        else "Not an executable ELF (verdict: ${json.optString("kind")}); it is only usable as a shared library."
                    }
                },
            )
        }

        item {
            GlassGroup(title = if (zh) "执行输出" else "Output") {
                Column(Modifier.padding(12.dp)) {
                    TerminalPane(
                        text = output,
                        title = outputTitle.ifBlank { if (zh) "尚未执行" else "nothing yet" },
                        onClear = { output = ""; outputTitle = "" },
                        placeholder = if (zh) "运行一个候选文件后，输出会显示在这里" else "Run a candidate to see its output here",
                        maxHeight = 360.dp,
                    )
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (zh) "后台进程" else "Managed processes",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text("${procs.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (procs.isEmpty()) {
            item { InlineHint(if (zh) "没有受管的后台进程" else "No managed background processes", tone = HintTone.Neutral) }
        }

        items(procs) { p ->
            val pid = p.optLong("pid")
            GlassGroup {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "${p.optString("name")}  ·  pid=$pid",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TypeChip(
                            if (p.optBoolean("alive", false)) (if (zh) "运行中" else "alive") else (if (zh) "已退出" else "exited"),
                            color = if (p.optBoolean("alive", false)) AppPalette.green else MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        p.optString("command"),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (zh) "运行 ${p.optLong("uptimeSec")} 秒" else "uptime ${p.optLong("uptimeSec")}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ToolChip(if (zh) "日志" else "Log", onClick = {
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { callTool(JSONObject().put("action", "log").put("pid", pid.toInt())) }
                                outputTitle = "${p.optString("name")} · pid=$pid"
                                output = r?.optString("text").orEmpty().ifBlank { if (zh) "（日志为空）" else "(empty log)" }
                            }
                        })
                        ToolChip(if (zh) "终止" else "Kill", onClick = { pendingKill = p })
                    }
                }
            }
        }

        item { Spacer(Modifier.size(8.dp)) }
    }

    // 运行参数
    target?.let { json ->
        Dialog(onDismissRequest = { target = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            androidx.compose.material3.Surface(
                shape = RoundedCornerShape(AppShape.xl),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (zh) "运行 ${json.optString("name")}" else "Run ${json.optString("name")}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        json.optString("path"),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = argText,
                        onValueChange = { argText = it },
                        singleLine = true,
                        label = { Text(if (zh) "参数（空格分隔，不经 shell）" else "Arguments (space separated, no shell)") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = timeoutText,
                        onValueChange = { timeoutText = it.filter { c -> c.isDigit() } },
                        singleLine = true,
                        label = { Text(if (zh) "超时（秒，前台执行）" else "Timeout (sec, foreground)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { runTarget(json, false) }) { Text(if (zh) "前台执行" else "Run") }
                        TextButton(onClick = { runTarget(json, true) }) { Text(if (zh) "后台运行" else "Daemon") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { target = null }) { Text(if (zh) "取消" else "Cancel") }
                    }
                }
            }
        }
    }

    pendingKill?.let { p ->
        ConfirmDialog(
            title = if (zh) "终止进程？" else "Terminate process?",
            message = if (zh) "将终止 ${p.optString("name")}（pid=${p.optLong("pid")}）。" else "This terminates ${p.optString("name")} (pid=${p.optLong("pid")}).",
            confirmText = if (zh) "终止" else "Kill",
            onConfirm = {
                val pid = p.optLong("pid")
                pendingKill = null
                scope.launch {
                    withContext(Dispatchers.IO) { callTool(JSONObject().put("action", "kill").put("pid", pid.toInt())) }
                    val r = withContext(Dispatchers.IO) { callTool(JSONObject().put("action", "ps")) }
                    val arr = r?.optJSONArray("processes")
                    procs = (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }
                }
            },
            onDismiss = { pendingKill = null },
        )
    }
}

private fun formatBytesShort(n: Long): String = when {
    n >= (1L shl 20) -> "%.1f MB".format(n / 1048576.0)
    n >= 1024L -> "%.1f KB".format(n / 1024.0)
    else -> "$n B"
}
