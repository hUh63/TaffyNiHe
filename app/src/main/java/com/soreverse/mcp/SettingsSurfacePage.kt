package com.soreverse.mcp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.soreverse.mcp.core.AiGatewayServer
import com.soreverse.mcp.core.HeavyGate
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.core.WorkspacePolicy
import org.json.JSONObject

/**
 * 设置 → 暴露面（surface）：把「这台设备上现在有哪些入口能被外面碰到、各自需要什么条件」
 * 摆在一页里。
 *
 * 为什么需要它：MCP 服务、本地 AI 网关、隧道、重型工具许可四件事分散在四个设置页里，每个都
 * 只说自己的开关，没人回答那个真正的问题——「我现在到底暴露了多少」。这页只读汇总，不改配置，
 * 风险用文字讲清楚，最后给三条收紧建议。
 *
 * 数据来源都是现成的：SettingsStore 的配置项、AiGatewayServer.snapshot()、HeavyGate.stats()、
 * WorkspacePolicy.isTunnelActive()，本页不重复实现任何探测逻辑。
 */
@Composable
internal fun SettingsSurfacePage(t: UiText, settings: SettingsStore, onBack: () -> Unit) {
    val zh = t.zh
    val context = LocalContext.current.applicationContext
    // 只在这页刷新：快照类数据（网关计数、租约）随时在变，进页读一次 + 手动刷新就够，
    // 不做定时轮询——那会在设置页后台白烧 CPU。
    var tick by remember { mutableIntStateOf(0) }
    // 清零后的反馈（这一页别的操作都只读，只有一个写动作要给出结果回执）
    var clearMsg by remember { mutableStateOf("") }
    val gateway = remember(tick) {
        runCatching { AiGatewayServer.snapshot() }.getOrNull() ?: JSONObject()
    }
    val heavy = remember(tick) {
        runCatching { HeavyGate.stats() }.getOrNull() ?: JSONObject()
    }
    val tunnelActive = remember(tick) {
        runCatching { WorkspacePolicy.isTunnelActive(context) }.getOrDefault(false)
    }

    val bindAll = settings.bindHost == "0.0.0.0"
    val auth = settings.authEnabled
    val tokenSet = runCatching { settings.accessToken.isNotBlank() }.getOrDefault(false)
    val gwTokenSet = runCatching { settings.aiGatewayToken.isNotBlank() }.getOrDefault(false)
    val gwPort = settings.aiGatewayPort
    val gwRunning = gateway.optBoolean("running", false)

    // 0 = 正常 / 1 = 需要注意 / 2 = 明确风险
    val mcpLevel = when {
        bindAll && !auth -> 2
        bindAll -> 1
        else -> 0
    }
    val tunnelLevel = if (tunnelActive) 1 else 0
    val dangers = listOf(mcpLevel, tunnelLevel).count { it == 2 }
    val warns = listOf(mcpLevel, tunnelLevel).count { it == 1 }

    SettingsScaffold(
        title = if (zh) "暴露面" else "Exposure",
        onBack = onBack,
    ) {
        item("head") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (zh) {
                        when {
                            dangers > 0 -> "当前有 $dangers 项明确风险、$warns 项需要注意"
                            warns > 0 -> "当前没有明确风险，有 $warns 项需要注意"
                            else -> "当前没有对外暴露的入口"
                        }
                    } else {
                        when {
                            dangers > 0 -> "$dangers risky item(s), $warns to watch"
                            warns > 0 -> "No hard risk, $warns item(s) to watch"
                            else -> "Nothing is exposed right now"
                        }
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (zh) "汇总页：配置只读，唯一能改的是清空网关用量计数。数据来自当前设置项与服务状态。"
                    else "Summary only: config is read-only; the one action here clears gateway usage counters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { tick++ }) {
                    Text(if (zh) "刷新状态" else "Refresh")
                }
            }
        }

        item("mcp") {
            SurfaceCard {
                Text(
                    if (zh) "MCP 服务" else "MCP server",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.width(2.dp))
                SurfaceRow(if (zh) "端口" else "Port", settings.port.toString(), 0)
                SurfaceRow(
                    if (zh) "绑定地址" else "Bind",
                    if (bindAll) "0.0.0.0" else "127.0.0.1",
                    if (bindAll) 1 else 0,
                )
                SurfaceRow(
                    if (zh) "鉴权" else "Auth",
                    when {
                        auth && tokenSet -> if (zh) "开启（Bearer）" else "On (Bearer)"
                        auth -> if (zh) "开启但未设置 token" else "On, no token"
                        else -> if (zh) "关闭" else "Off"
                    },
                    if (auth && tokenSet) 0 else if (bindAll) 2 else 1,
                )
                SurfaceNote(
                    when {
                        bindAll && !auth ->
                            if (zh) "同网段的任何设备都能不带凭证调用全部工具。"
                            else "Anyone on the same network can call every tool without credentials."
                        bindAll ->
                            if (zh) "同网段可达，但需要 Bearer token。"
                            else "Reachable on the LAN, requires a Bearer token."
                        else ->
                            if (zh) "只监听本机回环，外部无法连接。"
                            else "Loopback only; not reachable from outside."
                    }
                )
            }
        }

        item("gateway") {
            SurfaceCard {
                Text(
                    if (zh) "本地 AI 网关" else "Local AI gateway",
                    style = MaterialTheme.typography.titleSmall,
                )
                SurfaceRow(
                    if (zh) "状态" else "State",
                    when {
                        !gwRunning -> if (zh) "未运行" else "Stopped"
                        else -> if (zh) "运行中" else "Running"
                    },
                    if (gwRunning && !gwTokenSet) 1 else 0,
                )
                SurfaceRow(if (zh) "端口" else "Port", if (gwPort > 0) gwPort.toString() else "-", 0)
                SurfaceRow(
                    if (zh) "令牌" else "Token",
                    if (gwTokenSet) (if (zh) "已设置" else "set") else (if (zh) "未设置" else "none"),
                    if (gwRunning && !gwTokenSet) 1 else 0,
                )
                SurfaceRow(
                    if (zh) "累计请求" else "Requests",
                    gateway.optLong("requests", 0L).toString(),
                    0,
                )
                SurfaceRow(
                    if (zh) "并发上限" else "Concurrency",
                    runCatching { AiGatewayServer.concurrencyLimit() }.getOrDefault(0).toString(),
                    0,
                )
                SurfaceNote(
                    if (zh) "只监听 127.0.0.1，外部设备连不进来；适合让本机其他 App 或 Termux 里的工具复用通道。"
                    else "Binds 127.0.0.1 only. Meant for other apps or Termux tooling on this device."
                )
            }
        }

        item("usage") {
            SurfaceCard {
                Text(
                    if (zh) "网关按通道用量" else "Gateway usage by channel",
                    style = MaterialTheme.typography.titleSmall,
                )
                val ch = gateway.optJSONObject("channels")
                var keys: List<String> = emptyList()
                if (ch != null) {
                    val it = ch.keys()
                    val acc = ArrayList<String>()
                    while (it.hasNext()) acc.add(it.next())
                    keys = acc
                }
                if (keys.isEmpty()) {
                    SurfaceNote(
                        if (zh) "还没有请求。网关启动并收到请求后，这里按通道分别显示次数与 token（重名通道按 id 分开计）。"
                        else "No requests yet. Once the gateway receives traffic, each channel is shown separately.",
                    )
                } else {
                    val maxTok = keys.maxOfOrNull { k -> ch?.optJSONObject(k)?.optLong("totalTokens", 0L) ?: 0L }?.coerceAtLeast(1L) ?: 1L
                    for (k in keys) {
                        val o = ch?.optJSONObject(k) ?: continue
                        val name = o.optString("name").ifBlank { k.take(6) }
                        val tok = o.optLong("totalTokens", 0L)
                        val req = o.optLong("requests", 0L)
                        Column(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.width(96.dp),
                                    maxLines = 1,
                                )
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(MaterialTheme.colorScheme.surface),
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth((tok.toFloat() / maxTok.toFloat()).coerceIn(0.02f, 1f))
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    req.toString() + (if (zh) " 次 · " else " req · ") + tok + " tok",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    SurfaceNote(
                        if (zh) "条形按 token 占比；「次」是请求数。数据来自网关内存计数，重启服务后清零。"
                        else "Bars are relative token usage; counts reset when the gateway restarts.",
                    )
                }
                // 计数只增不减会让「这次改动到底省了多少 token」无法回答，
                // 所以给一个显式清零入口（内存计数，不写盘）。
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    if (clearMsg.isNotBlank()) {
                        Text(
                            clearMsg,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    TextButton(onClick = {
                        runCatching { AiGatewayServer.resetStats() }
                            .onSuccess {
                                clearMsg = if (zh) "计数已清零" else "Counters cleared"
                                tick++
                            }
                            .onFailure { clearMsg = it.message ?: "failed" }
                    }) {
                        Text(if (zh) "清零计数" else "Clear counters")
                    }
                }
            }
        }

        item("tunnel") {
            SurfaceCard {
                Text(
                    if (zh) "隧道（公网可达）" else "Tunnel (public)",
                    style = MaterialTheme.typography.titleSmall,
                )
                SurfaceRow(
                    if (zh) "状态" else "State",
                    if (tunnelActive) (if (zh) "运行中" else "running") else (if (zh) "未运行" else "stopped"),
                    tunnelLevel,
                )
                SurfaceRow(
                    if (zh) "类型" else "Kind",
                    settings.tunnelType.ifBlank { settings.tunnelMode.ifBlank { "-" } },
                    0,
                )
                SurfaceRow(
                    if (zh) "指向端口" else "Target port",
                    settings.tunnelTargetPort.toString(),
                    0,
                )
                SurfaceNote(
                    if (tunnelActive) {
                        if (zh) "公网能通过临时域名访问到本机服务；用完请关掉。"
                        else "A temporary public URL points at this device. Turn it off when done."
                    } else {
                        if (zh) "没有隧道在跑，MCP 端口不会出现在公网上。"
                        else "No tunnel running; the MCP port is not on the public internet."
                    }
                )
            }
        }

        item("heavy") {
            SurfaceCard {
                Text(
                    if (zh) "重型工具许可" else "Heavy-tool permits",
                    style = MaterialTheme.typography.titleSmall,
                )
                SurfaceRow(
                    if (zh) "许可数" else "Permits",
                    heavy.optInt("permits", 0).toString(),
                    0,
                )
                SurfaceRow(
                    if (zh) "在飞任务" else "In flight",
                    heavy.optInt("inFlight", 0).toString(),
                    if (heavy.optInt("inFlight", 0) > 0 && heavy.optBoolean("poisoned", false)) 2 else 0,
                )
                SurfaceRow(
                    if (zh) "泄漏/中毒" else "Leaked/poisoned",
                    heavy.optInt("leaked", 0).toString() + " / " + heavy.optBoolean("poisoned", false),
                    if (heavy.optBoolean("poisoned", false)) 2 else 0,
                )
                SurfaceRow(
                    if (zh) "最长占用" else "Oldest lease",
                    heavy.optLong("oldestLeaseSec", 0L).let { if (it <= 0) "-" else "${it}s" },
                    if (heavy.optLong("oldestLeaseSec", 0L) > 1800) 1 else 0,
                )
                SurfaceNote(
                    if (zh) "重型工具（原生分析、批量反编译）串行执行；租约到期会自动释放，卡死可到「服务配置 → 重建引擎」清理。"
                    else "Heavy tools run serially; leases expire automatically. Use Service config → rebuild engine if stuck."
                )
            }
        }

        item("advice") {
            SurfaceCard {
                Text(
                    if (zh) "怎么收紧" else "How to tighten",
                    style = MaterialTheme.typography.titleSmall,
                )
                AdviceLine(
                    if (zh) "只在手机内用：把绑定地址改成 127.0.0.1（服务配置页）"
                    else "Phone-only use: set bind to 127.0.0.1"
                )
                AdviceLine(
                    if (zh) "要跨设备用：开启鉴权并把 token 填进客户端 URL / Header"
                    else "Cross-device: enable auth and put the token in the client"
                )
                AdviceLine(
                    if (zh) "不用时关掉隧道与浮动窗，公网入口随之消失"
                    else "Turn off tunnel and floating window when not in use"
                )
            }
        }
    }
}

@Composable
private fun SurfaceCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppShape.md))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

/** 一行「项目 → 值」，level 决定值用正常色还是警示色。 */
@Composable
private fun SurfaceRow(label: String, value: String, level: Int) {
    val color = when (level) {
        2 -> MaterialTheme.colorScheme.error
        1 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = color,
        )
    }
}

@Composable
private fun SurfaceNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AdviceLine(text: String) {
    Row(Modifier.fillMaxWidth()) {
        Text("·  ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}
