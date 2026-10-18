package com.soreverse.mcp

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soreverse.mcp.core.EngineProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 多智能体编排页。
 *
 * 界面按「一次运行的生命周期」组织，而不是把 JSON 摊开：
 *  - 计划区：节点与依赖一目了然（谁在等谁）
 *  - 运行区：每个节点一张卡，角色 / 状态 / 耗时 / 工具调用 / 实时输出尾部
 *  - 结论区：Markdown 渲染，可复制
 *  - 历史区：切到列表回看以前的运行（run.json 落盘）
 *
 * 刻意不做「原始 JSON」出口：排查看执行过程，而不是读字段。
 */
@Composable
internal fun AgentRunView(
    workspaceId: String,
    zh: Boolean,
    context: android.content.Context,
) {
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    var goal by remember {
        mutableStateOf(
            if (zh) "查清这个目标：有没有加固与反调试、字符串是否加密、关键函数与调用关系"
            else "Profile this target: protector and anti-debug, string encryption, key functions"
        )
    }
    var roles by remember { mutableStateOf(setOf<String>()) }
    var maxAgents by remember { mutableStateOf(5) }

    // runId/status 用 rememberSaveable：编排是后台 run，离开页面回来不该显示成"还没开始"，
    // 更不该丢掉「中止」入口（旧实现切一次 tab 就无法中止，run 变成孤儿继续烧 token）。
    var runId by rememberSaveable { mutableStateOf("") }
    var statusJson by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    var historyJson by remember { mutableStateOf("") }

    val snapshot = remember(statusJson) { runCatching { JSONObject(statusJson) }.getOrNull() }
    val status = snapshot?.optString("status").orEmpty()
    val nodes = snapshot?.optJSONObject("nodes")
    val conclusion = snapshot?.optString("conclusion").orEmpty()
        .ifBlank { snapshot?.optJSONObject("record")?.optString("conclusion").orEmpty() }

    suspend fun callTool(name: String, args: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val h = com.soreverse.mcp.mcp.ToolCatalog.byName[name] ?: return@runCatching null
            val tcx = com.soreverse.mcp.mcp.ToolContext(
                context,
                com.soreverse.mcp.core.SettingsStore(context),
                EngineProvider.get(context),
                null,
            )
            h.handle(tcx, args)
        }.getOrNull()
    }

    /** 轮询到结束：1.2s 一次，最多 30 分钟（编排本身有 wallClock 上限）。 */
    fun poll(id: String) {
        scope.launch {
            var guard = 0
            var failures = 0
            while (guard < 1500) {
                delay(1200)
                val s = callTool("taffy_agent_run", JSONObject().put("action", "status").put("runId", id))
                if (s == null) {
                    // 原来 s == null 时继续空转 1500 次（30 分钟），页面什么都不显示，
                    // 用户无法判断是没开始、在跑还是已经挂了。
                    failures++
                    if (failures >= 3) {
                        error = if (zh) "状态查询连续失败：AI 未配置或引擎异常" else "Status polling failed (AI not configured?)"
                        break
                    }
                    guard++
                    continue
                }
                failures = 0
                statusJson = s.toString()
                val st = s.optString("status").orEmpty()
                if (st.isNotBlank() && st != "running") break
                guard++
            }
        }
    }

    fun start() {
        if (busy) return
        busy = true
        error = ""
        statusJson = ""
        scope.launch {
            val args = JSONObject()
                .put("action", "run")
                .put("goal", goal.trim())
                .put("maxSubAgents", maxAgents)
                .put("zh", zh)
            if (workspaceId.isNotBlank()) args.put("workspaceId", workspaceId)
            if (roles.isNotEmpty()) args.put("roles", roles.joinToString(","))
            val r = callTool("taffy_agent_run", args)
            busy = false
            if (r == null || !r.optBoolean("ok", false)) {
                error = r?.optJSONObject("error")?.optString("message").orEmpty()
                    .ifBlank { if (zh) "启动失败：AI 未配置或引擎异常" else "failed to start" }
                return@launch
            }
            val id = r.optString("runId")
            runId = id
            // 立刻写一个 running 占位：否则 poll 第一次 delay(1200) 之前 status 为空，
            // 按钮重新可点，用户再点一次会起第二个 run。
            statusJson = JSONObject().put("status", "running").put("runId", id).toString()
            poll(id)
        }
    }

    // 重新进入页面时，如果记得的 run 还在跑，继续轮询把它接回来
    LaunchedEffect(Unit) {
        if (runId.isNotBlank() && statusJson.contains("\"running\"")) poll(runId)
    }

    /** 从落盘记录续跑：已完成的节点不会重跑，中断的节点带着消息历史继续。 */
    fun resumeRun(id: String) {
        if (id.isBlank()) return
        busy = true
        error = ""
        scope.launch {
            val r = callTool(
                "taffy_agent_run",
                JSONObject().put("action", "resume").put("runId", id).put("zh", zh),
            )
            busy = false
            if (r == null || !r.optBoolean("ok", false)) {
                error = r?.optJSONObject("error")?.optString("message").orEmpty()
                    .ifBlank { if (zh) "续跑失败" else "resume failed" }
                return@launch
            }
            runId = id
            poll(id)
        }
    }

    fun cancel() {
        val id = runId
        if (id.isBlank()) return
        scope.launch {
            callTool("taffy_agent_run", JSONObject().put("action", "cancel").put("runId", id))
        }
    }

    fun loadHistory() {
        showHistory = true
        scope.launch {
            val r = callTool("taffy_agent_run", JSONObject().put("action", "list").put("limit", 20))
            historyJson = r?.optJSONArray("runs")?.toString().orEmpty()
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ── 目标输入 ──
        OutlinedTextField(
            value = goal,
            onValueChange = { goal = it },
            label = { Text(if (zh) "这次要查清什么" else "Goal") },
            modifier = Modifier.fillMaxWidth(),
            enabled = status != "running",
            maxLines = 3,
        )
        Spacer(Modifier.height(6.dp))

        // ── 角色选择 ──
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AgentRolesUi.forEach { (id, labelZh, labelEn) ->
                FilterChip(
                    selected = roles.contains(id),
                    onClick = {
                        roles = if (roles.contains(id)) roles - id else roles + id
                    },
                    enabled = status != "running",
                    label = { Text(if (zh) labelZh else labelEn) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        // ── 动作行 ──
        // 最多同时出现 5 个动作（开始/中止/继续上次/历史/预算），窄屏会被挤出屏幕且无法点到，
        // 这里允许横向滚动，保证每个动作都可达。
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { start() },
                enabled = !busy && goal.isNotBlank() && status != "running",
            ) {
                Text(if (zh) "开始编排" else "Start")
            }
            if (status == "running") {
                OutlinedButton(onClick = { cancel() }) {
                    Text(if (zh) "中止" else "Cancel")
                }
            }
            if (runId.isNotBlank() && status.isNotBlank() && status != "running") {
                OutlinedButton(onClick = { resumeRun(runId) }, enabled = !busy) {
                    Text(if (zh) "继续上次" else "Resume")
                }
            }
            TextButton(onClick = { loadHistory() }) {
                Text(if (zh) "历史记录" else "History")
            }
            TextButton(onClick = {
                maxAgents = when (maxAgents) {
                    3 -> 5
                    5 -> 8
                    else -> 3
                }
            }) {
                Text(if (zh) "预算 ≤$maxAgents" else "budget ≤$maxAgents")
            }
        }

        Spacer(Modifier.height(8.dp))

        when {
            workspaceId.isBlank() && status.isBlank() -> NoticeCard(
                if (zh) "还没有打开目标" else "No target opened",
                if (zh) "先在顶栏选择 APK/SO；或直接开始，编排器会让你补路径。" else "Pick a target first.",
            )
            error.isNotBlank() -> NoticeCard(if (zh) "启动失败" else "Failed", error, cs.error)
            showHistory -> HistoryPane(historyJson, zh) { runId2 ->
                showHistory = false
                runId = runId2
                scope.launch {
                    val s = callTool("taffy_agent_run", JSONObject().put("action", "get").put("runId", runId2))
                    statusJson = s?.toString().orEmpty()
                }
            }
            status.isBlank() && !busy -> NoticeCard(
                if (zh) "还没开始" else "Not started",
                if (zh) "写清目标、勾选想跑的角色，点「开始编排」。每个子代理只会拿到自己那组工具。" else
                    "Describe the goal and start.",
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // 运行状态条
                item {
                    Surface(
                        color = cs.surfaceContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    statusLabel(status, zh),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = when (status) {
                                        "failed" -> cs.error
                                        "completed" -> cs.primary
                                        else -> cs.onSurface
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                if (status == "running") {
                                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                }
                            }
                            val budget = snapshot?.optJSONObject("budget")
                            if (budget != null) {
                                Text(
                                    "${if (zh) "子代理" else "agents"} ${budget.optInt("agentsUsed")}/${budget.optInt("maxSubAgents")} · " +
                                        "${if (zh) "工具调用" else "tools"} ${budget.optInt("toolsUsed")}/${budget.optInt("maxToolCalls")} · " +
                                        "${(budget.optLong("elapsedMs") / 1000)}s",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                            if (status == "running") {
                                LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
                            }
                        }
                    }
                }

                // 结论
                if (conclusion.isNotBlank()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    if (zh) "结论" else "Conclusion",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = cs.primary,
                                )
                                Spacer(Modifier.height(4.dp))
                                RikkaMarkdown(conclusion)
                            }
                        }
                    }
                }

                // 节点
                val ids = nodes?.keys()?.asSequence()?.toList().orEmpty()
                items(ids) { id ->
                    val n = nodes?.optJSONObject(id)
                    if (n != null) NodeCard(id, n, zh)
                }
            }
        }
    }
}

private val AgentRolesUi = listOf(
    Triple("recon", "侦察", "Recon"),
    Triple("strings", "字符串", "Strings"),
    Triple("code", "代码", "Code"),
    Triple("flow", "控制流", "Flow"),
    Triple("packer", "加固脱壳", "Packer"),
    Triple("report", "汇总", "Report"),
)

private fun statusLabel(status: String, zh: Boolean): String = when (status) {
    "running" -> if (zh) "运行中" else "running"
    "completed" -> if (zh) "已完成" else "completed"
    "failed" -> if (zh) "失败" else "failed"
    "cancelled" -> if (zh) "已中止" else "cancelled"
    else -> status
}

@Composable
private fun NoticeCard(title: String, detail: String, tone: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = tone)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NodeCard(nodeId: String, node: JSONObject, zh: Boolean) {
    val cs = MaterialTheme.colorScheme
    val status = node.optString("status").ifBlank { "pending" }
    val text = node.optString("text")
    val tools = node.optJSONArray("tools") ?: JSONArray()
    val tone = when (status) {
        "running" -> cs.primary
        "ok" -> cs.tertiary
        "failed", "timeout" -> cs.error
        "skipped_budget", "cancelled", "skipped_dependency" -> cs.onSurfaceVariant
        else -> cs.onSurfaceVariant
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${node.optString("role")} · $nodeId",
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (status == "running") {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                } else {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(status, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
            val task = node.optString("task")
            if (task.isNotBlank()) {
                Text(
                    task,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (tools.length() > 0) {
                val names = ArrayList<String>()
                for (i in 0 until tools.length()) {
                    val t = tools.optJSONObject(i) ?: continue
                    names.add(t.optString("name") + if (t.optBoolean("ok")) "" else "✗")
                }
                Text(
                    names.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = tone,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (text.isNotBlank()) {
                HorizontalDivider(color = cs.outlineVariant)
                Text(
                    if (status == "running") text.takeLast(400) else {
                        val lines = text.trim().lines()
                        if (lines.size > 8) lines.takeLast(8).joinToString("\n") else text
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = cs.onSurface,
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val ms = node.optLong("ms")
            if (ms > 0) {
                Text(
                    "${ms} ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HistoryPane(historyJson: String, zh: Boolean, onPick: (String) -> Unit) {
    val arr = remember(historyJson) { runCatching { JSONArray(historyJson) }.getOrNull() }
    if (arr == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (zh) "加载中…" else "loading…", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    if (arr.length() == 0) {
        NoticeCard(if (zh) "还没有历史运行" else "No runs yet", if (zh) "跑一次编排后会在这里留档（run.json + 结论）。" else "Run an orchestration first.")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(count = arr.length()) { i ->
            val o = arr.optJSONObject(i) ?: return@items
            Card(
                onClick = { onPick(o.optString("runId")) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            o.optString("runId"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            o.optString("status"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Text(
                        o.optString("goal"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (zh) "点开可回看，也可对未完成的运行继续" else "tap to review; resume unfinished runs",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
