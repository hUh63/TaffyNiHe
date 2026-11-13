package com.soreverse.mcp

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.McpAiBridge
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.core.safeLaunch
import com.soreverse.mcp.mcp.ToolCatalog
import com.soreverse.mcp.mcp.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 客户端页：管外部 MCP 端点、看它的工具、调它的工具。
 *
 * 塔菲本身就是 MCP 服务端，这里补的是反方向：把 PC / 另一台设备 / 局域网里的
 * MCP 服务接进来用（比如 PC 上的 frida 脚本服务、另一台机器上的自建工具集）。
 */
@Composable
internal fun SettingsMcpClientPage(t: UiText) {
    val zh = t.zh
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var endpoints by remember { mutableStateOf<JSONObject?>(null) }
    var tools by remember { mutableStateOf<JSONArray?>(null) }
    var callResult by remember { mutableStateOf("") }
    var newName by rememberSaveable { mutableStateOf("") }
    var newUrl by rememberSaveable { mutableStateOf("") }
    var newHeaders by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }
    var toolName by rememberSaveable { mutableStateOf("") }
    var toolArgs by rememberSaveable { mutableStateOf("{}") }
    var aiExpose by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    suspend fun callTool(args: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val h = ToolCatalog.byName["taffy_mcp_client"] ?: return@runCatching null
            h.handle(ToolContext(context, SettingsStore(context), EngineProvider.get(context), null), args)
        }.getOrNull()
    }

    fun refresh() {
        scope.safeLaunch {
            busy = "list"
            endpoints = callTool(JSONObject().put("action", "endpoints"))
            busy = ""
        }
    }

    remember(Unit) {
        aiExpose = McpAiBridge.isEnabled(context)
        refresh()
        true
    }

    fun errText(r: JSONObject?): String =
        r?.optJSONObject("error")?.optString("message") ?: (if (zh) "操作失败" else "Failed")

    PageScroll {
        // ───────── 已保存端点 ─────────
        GlassGroup(
            title = if (zh) "已保存端点" else "Saved endpoints",
            footer = if (zh) "点一行测连通；启停与删除在下方操作区。端点数据与设置页共用一份。"
            else "Tap a row to test; enable/remove below. Shared with settings storage.",
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val data = endpoints
                if (data == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(if (zh) "读取中…" else "Loading…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    val list = data.optJSONArray("endpoints") ?: JSONArray()
                    if (list.length() == 0) {
                        Text(
                            if (zh) "还没有端点。下面填一个 URL 保存即可（例如 http://192.168.1.5:8971/mcp）。"
                            else "No endpoints yet. Add a URL below (e.g. http://192.168.1.5:8971/mcp).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    for (i in 0 until list.length()) {
                        val ep = list.optJSONObject(i) ?: continue
                        val name = ep.optString("name")
                        val url = ep.optString("url")
                        val en = ep.optBoolean("enabled")
                        DataRow(
                            title = name + if (en) "" else if (zh) "（已停用）" else " (disabled)",
                            subtitle = url,
                            trailingText = ep.optString("id"),
                            onClick = {
                                if (busy.isEmpty()) scope.safeLaunch {
                                    busy = "test"
                                    val r = callTool(JSONObject().put("action", "test").put("endpoint", ep.optString("id")))
                                    busy = ""
                                    message = if (r?.optBoolean("ok") == true) {
                                        (if (zh) "连通 " else "OK ") + r.optLong("latencyMs") + " ms"
                                    } else errText(r)
                                }
                            },
                        )
                    }
                }
            }
        }

        // ───────── 让 AI 直接调用远端工具 ─────────
        GlassGroup(
            title = if (zh) "让 AI 直接调用远端工具" else "Expose remote tools to AI",
            footer = if (zh) "打开后，已启用端点的工具会以 mcp_<端点>_<工具> 的名字出现在 AI 的工具清单里，模型可自己选、按远端 inputSchema 填参数。默认关闭：外部端点由你自己配，混进工具清单会放大模型的行动面。"
            else "When on, tools from enabled endpoints appear to the model as mcp_<endpoint>_<tool>. Off by default: external endpoints widen the model's action surface.",
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (aiExpose) (if (zh) "已开启" else "Enabled") else (if (zh) "已关闭" else "Disabled"),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    ToolChip(
                        label = if (aiExpose) (if (zh) "关闭" else "Turn off") else (if (zh) "开启" else "Turn on"),
                        accent = !aiExpose,
                        enabled = busy.isEmpty(),
                        onClick = {
                            aiExpose = !aiExpose
                            McpAiBridge.setEnabled(context, aiExpose)
                            message = if (aiExpose) {
                                if (zh) "已开启：下次 AI 会话会带上远端工具（最多 40 个）" else "Enabled: remote tools will appear in the next AI session"
                            } else {
                                if (zh) "已关闭" else "Disabled"
                            }
                        },
                    )
                }
                Text(
                    if (zh) "端点增删改后无需手动刷新，工具清单有 60 秒缓存；要立即生效可关掉再打开一次。"
                    else "Tool list is cached for 60s; toggle off/on to refresh immediately.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ───────── 添加端点 ─────────
        GlassGroup(title = if (zh) "添加端点" else "Add endpoint") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(if (zh) "显示名（可空）" else "Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = newUrl,
                    onValueChange = { newUrl = it },
                    label = { Text(if (zh) "URL，如 http://192.168.1.5:8971/mcp" else "URL e.g. http://192.168.1.5:8971/mcp") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = newHeaders,
                    onValueChange = { newHeaders = it },
                    label = { Text(if (zh) "自定义请求头（每行 K: V，可放 Bearer token）" else "Headers (one 'K: V' per line)") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolChip(
                        label = if (zh) "保存" else "Save",
                        accent = true,
                        enabled = busy.isEmpty() && newUrl.isNotBlank(),
                        onClick = {
                            scope.safeLaunch {
                                busy = "save"
                                val r = callTool(JSONObject().put("action", "save")
                                    .put("name", newName.trim())
                                    .put("url", newUrl.trim())
                                    .put("headers", newHeaders.trim()))
                                busy = ""
                                message = if (r?.optBoolean("ok") == true) (if (zh) "已保存" else "Saved") else errText(r)
                                if (r?.optBoolean("ok") == true) {
                                    newName = ""
                                    newUrl = ""
                                    newHeaders = ""
                                    refresh()
                                }
                            }
                        },
                    )
                    ToolChip(
                        label = if (zh) "测试上面的 URL" else "Test URL",
                        enabled = busy.isEmpty() && newUrl.isNotBlank(),
                        onClick = {
                            scope.safeLaunch {
                                busy = "test2"
                                val r = callTool(JSONObject().put("action", "test").put("endpoint", newUrl.trim()))
                                busy = ""
                                message = if (r?.optBoolean("ok") == true) {
                                    (if (zh) "连通 " else "OK ") + r.optLong("latencyMs") + " ms"
                                } else if (r?.optString("ok") != "true") {
                                    errText(r) + (if (zh) "（未保存的端点请先用它自己的 URL 测；先保存再测更稳）" else "")
                                } else message
                            }
                        },
                    )
                }
                if (target.isNotBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToolChip(
                            label = if (zh) "停用" else "Disable",
                            enabled = busy.isEmpty(),
                            onClick = {
                                scope.safeLaunch {
                                    busy = "enable"
                                    val r = callTool(JSONObject().put("action", "enable").put("id", target.trim()).put("enabled", "false"))
                                    busy = ""
                                    message = if (r?.optBoolean("ok") == true) (if (zh) "已停用" else "Disabled") else errText(r)
                                    refresh()
                                }
                            },
                        )
                        ToolChip(
                            label = if (zh) "删除" else "Remove",
                            enabled = busy.isEmpty(),
                            onClick = {
                                scope.safeLaunch {
                                    busy = "remove"
                                    val r = callTool(JSONObject().put("action", "remove").put("id", target.trim()))
                                    busy = ""
                                    message = if (r?.optBoolean("ok") == true) (if (zh) "已删除" else "Removed") else errText(r)
                                    refresh()
                                }
                            },
                        )
                    }
                }
            }
        }

        // ───────── 远端工具 ─────────
        GlassGroup(
            title = if (zh) "远端工具" else "Remote tools",
            footer = if (zh) "需要先保存端点。列表来自远端 tools/list，参数名取自它的 inputSchema。"
            else "Save an endpoint first. Tools come from the remote tools/list.",
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text(if (zh) "端点 id（留空=第一个启用的）" else "Endpoint id (blank = first enabled)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ToolChip(
                    label = if (zh) "拉取工具列表" else "List tools",
                    icon = Icons.Filled.Search,
                    enabled = busy.isEmpty(),
                    onClick = {
                        scope.safeLaunch {
                            busy = "tools"
                            val r = callTool(JSONObject().put("action", "tools").put("endpoint", target.trim()))
                            busy = ""
                            tools = r?.optJSONArray("tools")
                            if (r?.optBoolean("ok") != true) message = errText(r)
                        }
                    },
                )
                val list = tools
                if (list != null) {
                    Text(
                        if (zh) "${list.length()} 个工具（点一个填入下方工具名）" else "${list.length()} tool(s) — tap to fill the name below",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (i in 0 until list.length()) {
                        val tl = list.optJSONObject(i) ?: continue
                        val nm = tl.optString("name")
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                nm,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.clickable { toolName = nm },
                            )
                            Text(
                                tl.optString("description"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val args = tl.optJSONArray("arguments")
                            if (args != null && args.length() > 0) {
                                val sb = StringBuilder()
                                for (j in 0 until args.length()) {
                                    if (sb.isNotEmpty()) sb.append(", ")
                                    sb.append(args.optString(j))
                                }
                                Text("args: " + sb, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        // ───────── 调用 ─────────
        GlassGroup(title = if (zh) "调用远端工具" else "Call remote tool") {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = toolName,
                    onValueChange = { toolName = it },
                    label = { Text(if (zh) "工具名" else "Tool name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = toolArgs,
                    onValueChange = { toolArgs = it },
                    label = { Text(if (zh) "参数 JSON" else "Arguments JSON") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                ToolChip(
                    label = if (zh) "调用" else "Call",
                    accent = true,
                    enabled = busy.isEmpty() && toolName.isNotBlank(),
                    onClick = {
                        scope.safeLaunch {
                            busy = "call"
                            val r = callTool(JSONObject().put("action", "call")
                                .put("endpoint", target.trim())
                                .put("tool", toolName.trim())
                                .put("arguments", toolArgs.trim().ifBlank { "{}" }))
                            busy = ""
                            callResult = if (r?.optBoolean("ok") == true) {
                                r.optString("text")
                            } else {
                                errText(r)
                            }
                        }
                    },
                )
                if (callResult.isNotBlank()) {
                    Text(
                        callResult.take(20_000),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        if (message.isNotBlank()) {
            Text(
                message,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (busy.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(14.dp).height(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    when (busy) {
                        "list" -> if (zh) "读取端点…" else "Loading endpoints…"
                        "test", "test2" -> if (zh) "测试连通…" else "Testing…"
                        "tools" -> if (zh) "拉取工具列表…" else "Listing tools…"
                        "call" -> if (zh) "调用中…" else "Calling…"
                        else -> if (zh) "处理中…" else "Working…"
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
