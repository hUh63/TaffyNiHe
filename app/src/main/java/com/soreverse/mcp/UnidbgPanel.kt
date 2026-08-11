package com.soreverse.mcp

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.soreverse.mcp.core.EngineProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Unidbg 模拟执行操作面板 —— 「⚡模拟」卫星的直接落地界面。
 *
 * 用户在这里：选择 .so → 自动打开 workspace → 填目标函数与参数 → 模拟执行 → 查看返回/寄存器/trace。
 * 独立于 AnalyzeTab 状态：选文件即建 workspace，用后返回关闭。
 *
 * @param t      主界面语言对象（复用 MainActivity 的 textFor）
 * @param context Android context（用于 EngineProvider 初始化）
 * @param onClose 关闭面板
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun UnidbgPanel(
    t: UiText,
    context: Context,
    onClose: () -> Unit,
    state: ToolPagesState? = null,
) {
    val scope = rememberCoroutineScope()
    val zh = t.zh

    var workspaceId by remember { mutableStateOf(state?.sharedWorkspaceId ?: "") }
    var soName by remember { mutableStateOf(state?.sharedSoName ?: "") }
    var symbol by remember { mutableStateOf("") }
    var argsText by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    var backend by remember { mutableStateOf("") }
    var engineTag by remember { mutableStateOf<String?>(null) }

    val pickSo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                running = true
                error = ""
                result = null
                backend = ""
                val r = withContext(Dispatchers.IO) {
                    runCatching { EngineProvider.get(context).open(uri.toString(), temporary = false) }.getOrNull()
                }
                running = false
                if (r == null) {
                    error = if (zh) "打开 SO 失败（引擎未初始化？）" else "Open SO failed (engine not initialized?)"
                } else {
                    val ok = r.optBoolean("ok", false)
                    val wid = r.optString("workspaceId")
                    val name = r.optString("soFileName").ifBlank {
                        r.optString("fileName").ifBlank { (uri.lastPathSegment ?: "so") }
                    }
                    if (ok && wid.isNotBlank()) {
                        workspaceId = wid
                        soName = name
                        engineTag = r.optString("backend").takeIf { it.isNotBlank() }
                        state?.sharedWorkspaceId = wid
                        state?.sharedSoName = name
                    } else {
                        error = r.optString("error").ifBlank { if (zh) "打开 SO 失败" else "Open SO failed" }
                    }
                }
            }
        }
    }

    // ---------- 参数解析 ----------
    fun parseArgs(line: String): JSONArray {
        val arr = JSONArray()
        if (line.isBlank()) return arr
        line.split(',', ' ', '\n').filter { it.isNotBlank() }.forEach { raw ->
            val token = raw.trim()
            when {
                token.matches(Regex("0[xX][0-9a-fA-F]+")) -> arr.put(token.substring(2).toLong(16))
                token.matches(Regex("-?\\d+")) -> arr.put(token.toLong())
                token.startsWith("\"") && token.endsWith("\"") && token.length >= 2 -> arr.put(token.substring(1, token.length - 1))
                else -> arr.put(token)
            }
        }
        return arr
    }

    fun summarize(json: JSONObject): String {
        val sb = StringBuilder()
        val keys = json.keys().asSequence().toList()
        for (k in keys) {
            if (k == "ok") continue
            when (val v = json.opt(k)) {
                is JSONObject -> sb.append("$k: ${v.optString("message", v.toString())}\n")
                is JSONArray -> sb.append("$k: ${v.length()} items\n")
                else -> sb.append("$k: $v\n")
            }
        }
        return sb.toString().trimEnd()
    }

    fun runEmulate() {
        val sym = symbol.trim()
        if (sym.isEmpty()) { error = if (zh) "请输入要调用的函数符号（如 JNI_OnLoad）" else "Enter a symbol (e.g. JNI_OnLoad)"; return }
        scope.launch {
            running = true
            error = ""
            result = null
            val json = withContext(Dispatchers.IO) {
                runCatching { EngineProvider.get(context).emulate(workspaceId, "", sym, parseArgs(argsText), true) }.getOrNull()
            }
            running = false
            if (json == null) { error = if (zh) "模拟执行异常（引擎未初始化？）" else "Emulate failed (engine unavailable)"; return@launch }
            if (json.optBoolean("ok", false)) {
                backend = listOf(json.optString("backend"), json.optString("emulator"))
                    .filter { it.isNotBlank() }.distinct().joinToString(" / ")
                result = summarize(json)
            } else {
                val err = json.optJSONObject("error")
                val msg = err?.optString("message").orEmpty().ifBlank { json.optString("error", "") }
                error = (if (msg.isBlank()) (if (zh) "模拟执行失败" else "Emulation failed") else msg)
            }
        }
    }

    // ---------- UI ----------
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (zh) "Unidbg 模拟执行" else "Unidbg Emulate") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 状态条
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dotColor = when {
                    running -> MaterialTheme.colorScheme.primary
                    workspaceId.isNotBlank() -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.outline
                }
                Box(Modifier.size(10.dp).background(dotColor, shape = MaterialTheme.shapes.small))
                Spacer(Modifier.size(8.dp))
                Text(
                    when {
                        running -> if (zh) "引擎工作中…" else "Engine busy…"
                        workspaceId.isNotBlank() -> if (zh) "SO 已加载：$soName" else "SO loaded: $soName"
                        else -> if (zh) "尚未选择 SO" else "No SO selected"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (engineTag != null && engineTag!!.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text("· $engineTag", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // 选择 SO
            Button(
                onClick = { pickSo.launch(arrayOf("application/octet-stream", "*/*")) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !running
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(if (zh) "选择 SO 文件 (.so)" else "Pick SO file (.so)")
            }

            // 目标函数
            OutlinedTextField(
                value = symbol,
                onValueChange = { symbol = it },
                label = { Text(if (zh) "目标函数（符号名）" else "Symbol to call") },
                placeholder = { Text(if (zh) "如 JNI_OnLoad / Java_com_xxx" else "e.g. JNI_OnLoad") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = workspaceId.isNotBlank() && !running
            )

            // 参数
            OutlinedTextField(
                value = argsText,
                onValueChange = { argsText = it },
                label = { Text(if (zh) "参数（逗号或空格分隔）" else "Args (comma/space separated)") },
                placeholder = { Text("0x1, 0x2  (支持 0x/整数/字符串)") },
                modifier = Modifier.fillMaxWidth(),
                enabled = workspaceId.isNotBlank() && !running
            )

            // 执行按钮
            Button(
                onClick = { runEmulate() },
                modifier = Modifier.fillMaxWidth(),
                enabled = workspaceId.isNotBlank() && symbol.isNotBlank() && !running
            ) {
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.size(8.dp))
                Text(if (running) (if (zh) "执行中…" else "Running…") else (if (zh) "模拟执行" else "Emulate"))
            }

            backend.takeIf { it.isNotBlank() }?.let {
                Text("backend: $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }

            // 错误
            if (error.isNotBlank()) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp).padding(top = 1.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }

            // 结果
            result?.let {
                Column {
                    Text(if (zh) "结果" else "Result", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.size(6.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium)
                            .padding(10.dp)
                    )
                }
            }

            Spacer(Modifier.size(8.dp))
            Text(
                if (zh) "提示：0x 前缀→十六进制、纯数字→十进制 Long，其余按字符串。trace 已开启，适合小函数。" else "Tip: 0x→hex, digits→Long, else String. Trace on — good for small functions.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
