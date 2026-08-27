package com.soreverse.mcp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Rizin 引擎页：引擎说明 + 工具路由 + 命令速查 + 实际 rz 命令执行
 * （选择 SO 文件打开工作区，输入 rz 命令执行查看输出）。
 */
@Composable
internal fun RizinPage(t: UiText) {
    val zh = t.zh
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context) }
    val engine = remember { EngineProvider.get(context) }

    var workspaceId by remember { mutableStateOf("") }
    var soName by remember { mutableStateOf("") }
    var cmdInput by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            withContext(Dispatchers.IO) {
                runCatching {
                    val input = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (input != null) {
                        val f = File(context.cacheDir, "rz_open_${System.currentTimeMillis()}.so")
                        f.writeBytes(input)
                        val r = engine.open(f.absolutePath, true)
                        workspaceId = r.optString("workspaceId", r.optString("id"))
                        soName = f.name
                        output = if (workspaceId.isNotBlank()) {
                            if (zh) "已打开: ${f.name}\nworkspace: $workspaceId" else "Opened: ${f.name}\nworkspace: $workspaceId"
                        } else {
                            if (zh) "打开失败: ${r.optString("error", "未知错误")}" else "Open failed: ${r.optString("error", "unknown")}"
                        }
                    }
                }.onFailure { e -> output = "Error: ${e.message}" }
            }
            busy = false
            // 打开成功后自动展示文件头信息（iI）（不调 runCmd：局部函数必须先声明后使用）
            if (workspaceId.isNotBlank() && output.startsWith(if (zh) "已打开" else "Opened")) {
                val info = withContext(Dispatchers.IO) {
                    val r = engine.rzCommand(workspaceId, "", "iI", false)
                    r.optString("stdout", r.toString(2)).ifBlank { r.toString(2) }
                }
                output = info
            }
        }
    }

    fun runCmd(cmd: String) {
        val c = cmd.trim()
        if (c.isEmpty() || workspaceId.isBlank()) return
        scope.launch {
            busy = true
            output = withContext(Dispatchers.IO) {
                val r = engine.rzCommand(workspaceId, "", c, false)
                r.optString("stdout", r.toString(2)).ifBlank { r.toString(2) }
            }
            busy = false
        }
    }

    val rootTools = listOf(
        "taffy_so_open" to (if (zh) "打开 SO/ELF（Rizin 分析会话）" else "Open SO/ELF (Rizin session)"),
        "taffy_analyze_functions" to (if (zh) "函数列表与符号" else "Functions & symbols"),
        "taffy_analyze_cfg" to (if (zh) "控制流图" else "Control flow graph"),
        "taffy_analyze_xrefs" to (if (zh) "交叉引用" else "Cross references"),
        "taffy_read_disasm" to (if (zh) "反汇编" else "Disassembly"),
        "taffy_rz" to (if (zh) "Rizin 原生命令 / ESIL 模拟 / 反编译(rizin-ghidra)" else "Native rz / ESIL / decompile"),
        "taffy_build_so" to (if (zh) "回写并签名构建 SO" else "Rebuild & sign SO"),
    )
    val quickCommands = listOf("afl", "pdf @main", "izz", "iI", "px 64 @main")
    // 分组快捷命令（分析 / 反汇编 / 信息）
    val quickGroups = listOf(
        (if (zh) "分析" else "Analyze") to listOf("afl" to "函数列表", "izz" to "字符串", "axt @main" to "交叉引用"),
        (if (zh) "反汇编" else "Disasm") to listOf("pdf @main" to "反汇编 main", "pdf @entry0" to "反汇编入口", "px 64 @main" to "main 前 64 字节"),
        (if (zh) "信息" else "Info") to listOf("iI" to "文件头", "iS" to "节区", "ii" to "导入", "iE" to "导出"),
    )

    Column(
        Modifier.fillMaxSize().padding(10.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ── 引擎状态 ──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Memory, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(if (zh) "Rizin 引擎（内置）" else "Rizin engine (built-in)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("OK", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            if (zh) "塔菲逆核内置 Rizin（radare2 系）引擎，所有 taffy_so_* / taffy_analyze_* / taffy_rz 工具均由它驱动，完全离线。"
            else "TaffyNiHe embeds a Rizin (radare2-family) engine; all taffy_so_* / taffy_analyze_* / taffy_rz tools are powered by it, fully offline.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── 命令执行（选择 SO 打开工作区，执行 rz 命令）──
        Text(if (zh) "rz 命令执行" else "rz command runner", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (workspaceId.isBlank()) (if (zh) "未打开文件" else "No file opened") else soName,
                modifier = Modifier.weight(1f).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)).padding(8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (workspaceId.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            TextButton(onClick = { fileLauncher.launch(arrayOf("application/octet-stream", "application/x-sharedlib", "*/*")) }, enabled = !busy) {
                Text(if (zh) "选择 SO" else "Pick SO")
            }
        }
        if (workspaceId.isNotBlank()) {
            // 分组快捷命令
            quickGroups.forEach { (groupName, cmds) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(groupName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 4.dp))
                    cmds.forEach { (c, label) ->
                        FilterChip(selected = false, onClick = { runCmd(c) }, label = { Text(label, fontSize = 10.sp) }, enabled = !busy)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = cmdInput,
                    onValueChange = { cmdInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (zh) "rz 命令，如: afl / pdf @main / izz" else "rz cmd, e.g. afl / pdf @main / izz", maxLines = 1) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                )
                IconButton(onClick = { runCmd(cmdInput) }, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, tint = MaterialTheme.colorScheme.primary)
                }
                // 清空输出
                IconButton(onClick = { output = "" }, enabled = output.isNotBlank()) {
                    Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            if (output.isNotBlank()) {
                SelectionContainer {
                    Text(
                        output,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(androidx.compose.ui.graphics.Color(0xFF111111)).padding(10.dp),
                    )
                }
            }
        }

        // ── 内置工具路由 ──
        Text(if (zh) "内置 rz 工具（MCP 路由）" else "Built-in rz tools (MCP routing)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))) {
            rootTools.forEachIndexed { i, (name, desc) ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(name, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(190.dp))
                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ── 常用 rz 命令速查 ──
        Text(if (zh) "常用 rz 命令速查" else "Quick rz commands", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        SelectionContainer {
            Text(
                quickCommands.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)).padding(12.dp),
            )
        }

        Text(
            if (zh) "提示：SO 分析统一走 taffy_so_open → analyze_* / edit_* → taffy_build_so 流程；taffy_rz (action=decompile) 使用 rizin-ghidra 插件把函数反编译为类 C 伪代码；DEX/APK 反编译用 taffy_jadx_decompile。"
            else "Tip: use taffy_so_open → analyze_* / edit_* → taffy_build_so for SO analysis; taffy_rz (action=decompile) emits pseudo-C via rizin-ghidra; use taffy_jadx_decompile for DEX/APK.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}
