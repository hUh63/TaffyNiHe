package com.soreverse.mcp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soreverse.mcp.core.ApkMcpBridge
import com.soreverse.mcp.core.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 桥接设置页（设置 → MCP 桥接）。
 *
 * - 每桥接一张卡片：名称 / MCP 地址 / 工具名前缀 / 工具数 / 连接失败提示
 * - 卡片按钮：管理工具 / 编辑 / 删除
 * - 添加/编辑弹窗：名称 / MCP URL / Bearer token(可选) / 工具名前缀
 * - 管理工具子页：按工具分卡片，名称 + 描述 + 启用开关
 */
@Composable
internal fun SettingsApkBridgePage(t: UiText, settings: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bridge = remember { activeBridge(context.applicationContext) }
    var configs by remember { mutableStateOf(settings.apkMcpConfigs) }
    var apkAutoProbe by remember { mutableStateOf(settings.apkMcpAutoProbe) }
    var apkMerge by remember { mutableStateOf(settings.apkMcpMergeTools) }
    var snapshot by remember { mutableStateOf<JSONObject?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<SettingsStore.BridgeConfig?>(null) }
    var toolsTarget by remember { mutableStateOf<String?>(null) }
    val zh = t.zh

    fun refreshSnapshot() {
        scope.launch {
            snapshot = withContext(Dispatchers.IO) {
                bridge.probe()
                bridge.snapshotJson()
            }
        }
    }

    fun bridgeStateByName(name: String): JSONObject? {
        val bridges = snapshot?.optJSONArray("bridges") ?: return null
        for (i in 0 until bridges.length()) {
            val b = bridges.optJSONObject(i) ?: continue
            if (b.optString("name") == name) return b
        }
        return null
    }

    // 进入或选项变化时刷新一次
    androidx.compose.runtime.LaunchedEffect(Unit) { refreshSnapshot() }

    PageScroll {
        // ── 桥接列表 ──
        GlassGroup(
            title = if (zh) "桥接列表" else "Bridge List",
            footer = if (zh) "支持同时连接多个 MCP 桥接服务，每个桥接独立管理" else "Multiple concurrent MCP bridges, each managed independently",
        ) {
            if (configs.isEmpty()) {
                Text(
                    if (zh) "尚未添加任何桥接" else "No bridges configured",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            configs.forEachIndexed { index, config ->
                if (index > 0) GroupDivider()
                BridgeCard(
                    config = config,
                    state = bridgeStateByName(config.name),
                    zh = zh,
                    onManageTools = { toolsTarget = config.name },
                    onEdit = { editTarget = config },
                    onDelete = {
                        bridge.removeBridge(config.name)
                        configs = settings.apkMcpConfigs
                        snapshot = null
                        refreshSnapshot()
                    },
                )
            }
            GroupDivider()
            Row(Modifier.fillMaxWidth().padding(14.dp)) {
                PrimaryActionButton(
                    if (zh) "添加桥接" else "Add Bridge",
                    { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    leading = Icons.Default.Add,
                )
            }
        }

        // ── 选项 ──
        GlassGroup(title = if (zh) "选项" else "Options") {
            ToggleRow(if (zh) "持续自动探测" else "Continuous auto-probe", apkAutoProbe) { apkAutoProbe = it; settings.apkMcpAutoProbe = it }
            GroupDivider()
            ToggleRow(if (zh) "合并工具到 tools/list" else "Merge tools into tools/list", apkMerge) { apkMerge = it; settings.apkMcpMergeTools = it }
        }

        // ── 探测全部 ──
        GlassGroup {
            Row(Modifier.padding(14.dp)) {
                PrimaryActionButton(
                    if (zh) "探测全部" else "Probe All",
                    { refreshSnapshot() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            snapshot?.let { snap ->
                val bridges = snap.optJSONArray("bridges") ?: JSONArray()
                val onlineCount = snap.optInt("onlineCount")
                val text = if (zh) "$onlineCount/${bridges.length()} 个桥接在线" else "$onlineCount/${bridges.length()} bridge(s) online"
                Text(
                    text,
                    color = if (onlineCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
        }
    }

    // ── 添加桥接弹窗 ──
    if (showAddDialog) {
        BridgeEditDialog(
            title = if (zh) "添加桥接" else "Add Bridge",
            initial = null,
            zh = zh,
            existingNames = configs.map { it.name }.toSet(),
            onDismiss = { showAddDialog = false },
            onSave = { name, url, token, prefix ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        bridge.probeUrl(name, url, token, prefix)
                    }
                    configs = settings.apkMcpConfigs
                    showAddDialog = false
                    refreshSnapshot()
                }
            },
        )
    }

    // ── 编辑桥接弹窗 ──
    editTarget?.let { target ->
        BridgeEditDialog(
            title = if (zh) "编辑桥接" else "Edit Bridge",
            initial = target,
            zh = zh,
            existingNames = (configs.map { it.name }.toSet() - target.name),
            onDismiss = { editTarget = null },
            onSave = { name, url, token, prefix ->
                val newList = configs.map {
                    if (it.name == target.name) SettingsStore.BridgeConfig(name, url, token, prefix) else it
                }
                settings.apkMcpConfigs = newList
                configs = settings.apkMcpConfigs
                editTarget = null
                refreshSnapshot()
            },
        )
    }

    // ── 工具管理子页 ──
    toolsTarget?.let { name ->
        val st = bridgeStateByName(name)
        val toolObjs = st?.optJSONArray("tools") ?: JSONArray()
        BridgeToolsManagerPage(
            bridgeName = name,
            prefix = st?.optString("toolPrefix", "") ?: "",
            tools = toolObjs,
            zh = zh,
            onBack = { toolsTarget = null },
        )
    }
}

@Composable
private fun BridgeCard(
    config: SettingsStore.BridgeConfig,
    state: JSONObject?,
    zh: Boolean,
    onManageTools: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val online = state?.optBoolean("online") == true
    val toolCount = state?.optInt("toolCount") ?: 0
    val lastError = state?.optString("lastError") ?: ""
    val prefix = config.prefix.ifBlank { state?.optString("toolPrefix", "") ?: "" }
    val latencyMs = state?.optLong("lastLatencyMs") ?: 0L

    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(
                if (online) MaterialTheme.colorScheme.primary
                else if (state != null) MaterialTheme.colorScheme.error
                else Color.Gray,
            ))
            Spacer(Modifier.width(8.dp))
            Text(
                config.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (online) "$toolCount ${if (zh) "工具" else "tools"}"
                else if (state != null && lastError.isNotBlank()) (if (zh) "失败" else "Failed")
                else (if (zh) "未探测" else "Unprobed"),
                style = MaterialTheme.typography.bodySmall,
                color = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            config.url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (prefix.isNotBlank()) {
            Text(
                (if (zh) "工具前缀：" else "Tool prefix: ") + prefix,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (online && latencyMs > 0) {
            Text(
                "${latencyMs}ms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!online && lastError.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                lastError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onManageTools) {
                Icon(Icons.Default.Build, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (zh) "管理工具" else "Manage tools", fontSize = 13.sp)
            }
            TextButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (zh) "编辑" else "Edit", fontSize = 13.sp)
            }
            TextButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(4.dp))
                Text(if (zh) "删除" else "Delete", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun BridgeEditDialog(
    title: String,
    initial: SettingsStore.BridgeConfig?,
    zh: Boolean,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, token: String, prefix: String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var token by remember { mutableStateOf(initial?.token ?: "") }
    var prefix by remember { mutableStateOf(initial?.prefix ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(if (zh) "名称" else "Name") },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                    isError = name.isBlank() || name in existingNames,
                    supportingText = if (name.isBlank()) {
                        { Text(if (zh) "名称不能为空" else "Name required", color = MaterialTheme.colorScheme.error) }
                    } else if (name in existingNames) {
                        { Text(if (zh) "名称已存在" else "Name exists", color = MaterialTheme.colorScheme.error) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text(if (zh) "MCP URL" else "MCP URL") },
                    placeholder = { Text("http://192.168.x.x:8787/mcp") },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                    isError = url.isBlank(),
                    supportingText = if (url.isBlank()) {
                        { Text(if (zh) "MCP URL 不能为空" else "URL required", color = MaterialTheme.colorScheme.error) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text(if (zh) "Bearer token（可选）" else "Bearer token (optional)") },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = prefix, onValueChange = { prefix = it },
                    label = { Text(if (zh) "工具名前缀" else "Tool name prefix") },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (zh) "桥接服务的工具将以此前缀暴露（如 MCP1_read_file），可自行修改。"
                    else "Bridged tools are exposed with this prefix (e.g. MCP1_read_file). Editable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && name !in existingNames && url.isNotBlank(),
                onClick = { onSave(name.trim(), url.trim(), token.trim(), prefix.trim()) },
            ) { Text(if (zh) "保存" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (zh) "取消" else "Cancel") }
        },
    )
}

/**
 * 单桥接的 MCP 工具管理子页：每工具一张卡片，名称 + 描述 + 启用开关。
 */
@Composable
private fun BridgeToolsManagerPage(
    bridgeName: String,
    prefix: String,
    tools: JSONArray,
    zh: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    // 每个工具的启用状态持久化在 SharedPreferences（key = "$bridgeName::$toolName"）
    val disabledMap = remember {
        runCatching {
            val raw = settings.toolDisableMapRaw
            JSONObject(raw)
        }.getOrDefault(JSONObject())
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.Close, contentDescription = if (zh) "返回" else "Back")
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    bridgeName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    (if (zh) "工具前缀：" else "Prefix: ") + prefix.ifBlank { "(自动)" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (zh) "工具总数：${tools.length()}" else "Total tools: ${tools.length()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (tools.length() == 0) {
                Text(
                    if (zh) "暂无可用工具（请先探测成功该桥接）" else "No tools available. Probe the bridge first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }
            for (i in 0 until tools.length()) {
                val tool = tools.optJSONObject(i) ?: continue
                val toolName = tool.optString("name")
                val description = tool.optString("description", "")
                val enabled = !disabledMap.optBoolean("$bridgeName::$toolName", false)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                toolName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            if (description.isNotBlank()) {
                                Text(
                                    description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { isOn ->
                                disabledMap.put("$bridgeName::$toolName", !isOn)
                                settings.toolDisableMapRaw = disabledMap.toString()
                            },
                        )
                    }
                }
            }
        }
    }
}