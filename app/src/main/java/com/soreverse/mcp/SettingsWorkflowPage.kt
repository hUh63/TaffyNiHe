package com.soreverse.mcp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设置 → 逆向工作流图（DAG）：借鉴 taixu 的 DAG 可视工作流，
 * 把「选择工作区 → 扫描 → 静态/动态分析 → AI 深度 → 编辑 → 构建 → 签名」串成有向图，
 * 节点可点击直接跳到对应功能页，作为全流程导航。
 *
 * 纯 Compose 绘制（Canvas 画边 + 可点击节点盒），无第三方图表依赖。
 */
private data class WfNode(val id: String, val zh: String, val en: String, val layer: Int, val row: Int, val dest: SettingsDest?)
private data class WfEdge(val from: Int, val to: Int)

private val WF_NODES = listOf(
    WfNode("pick", "选择工作区 / APK", "Pick workspace / APK", 0, 0, SettingsDest.Workspace),
    WfNode("scan", "扫描 / 加载", "Scan / load", 1, 0, null),
    WfNode("capture", "抓包", "Capture", 1, 1, SettingsDest.Capture),
    WfNode("rizin", "静态分析 Rizin", "Static: Rizin", 2, 0, SettingsDest.Rizin),
    WfNode("edbg", "动态调试 eDBG", "Dynamic: eDBG", 2, 1, SettingsDest.Edbg),
    WfNode("dex", "DEX / APK 浏览", "DEX / APK explorer", 2, 2, SettingsDest.DexExplorer),
    WfNode("ai", "AI 深度分析", "AI deep analysis", 3, 1, null),
    WfNode("edit", "编辑 smali / asm / hex", "Edit smali / asm / hex", 4, 0, SettingsDest.Python),
    WfNode("manifest", "Manifest / 资源编辑", "Manifest / resource edit", 4, 2, SettingsDest.ApkEdit),
    WfNode("build", "构建 / 回写", "Build / write-back", 5, 0, null),
    WfNode("snapshot", "快照 / 回滚", "Snapshot / rollback", 5, 2, SettingsDest.Snapshots),
    WfNode("sign", "签名", "Sign", 6, 0, SettingsDest.ApkSign),
    WfNode("terminal", "终端 / 自动化", "Terminal / automation", 6, 1, SettingsDest.Terminal),
)

private val WF_EDGES = listOf(
    WfEdge(0, 1), WfEdge(0, 2),
    WfEdge(1, 3), WfEdge(1, 4), WfEdge(1, 5),
    WfEdge(3, 6), WfEdge(4, 6),
    WfEdge(3, 7), WfEdge(6, 7), WfEdge(5, 7),
    WfEdge(5, 8),
    WfEdge(7, 9), WfEdge(7, 10),
    WfEdge(9, 11),
    WfEdge(7, 12),
)

private val COL_W = 178.dp
private val ROW_H = 70.dp
private val NODE_W = 152.dp
private val NODE_H = 50.dp
private val PAD = 10.dp

@Composable
internal fun SettingsWorkflowPage(t: UiText, onDest: (SettingsDest) -> Unit) {
    val zh = t.zh
    val density = LocalDensity.current
    val maxLayer = WF_NODES.maxOf { it.layer }
    val maxRow = WF_NODES.maxOf { it.row }
    val canvasW = PAD * 2 + COL_W * maxLayer + NODE_W
    val canvasH = PAD * 2 + ROW_H * maxRow + NODE_H

    fun nodeX(n: WfNode) = PAD + COL_W * n.layer
    fun nodeY(n: WfNode) = PAD + ROW_H * n.row

    val lineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val nodeBg = MaterialTheme.colorScheme.surfaceVariant
    val nodeFg = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (zh) "逆向工作流图 (DAG)" else "Reverse workflow (DAG)", style = MaterialTheme.typography.titleSmall)
        Text(
            if (zh) "全流程导航：点击任意节点跳转到对应功能。虚线为安全网（快照回滚）。"
            else "End-to-end navigation: tap a node to jump. Dotted = safety net (snapshot rollback).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier.fillMaxWidth().height(canvasH + 20.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()),
        ) {
            Box(Modifier.size(canvasW, canvasH)) {
                Canvas(Modifier.fillMaxSize()) {
                    val nwPx = with(density) { NODE_W.toPx() }
                    val nhPx = with(density) { NODE_H.toPx() }
                    fun cx(dp: androidx.compose.ui.unit.Dp) = with(density) { dp.toPx() }
                    WF_EDGES.forEach { e ->
                        val s = WF_NODES[e.from]
                        val d = WF_NODES[e.to]
                        val sx = cx(nodeX(s)) + nwPx
                        val sy = cx(nodeY(s)) + nhPx / 2f
                        val dx = cx(nodeX(d))
                        val dy = cx(nodeY(d)) + nhPx / 2f
                        val mx = (sx + dx) / 2f
                        val p = Path().apply {
                            moveTo(sx, sy)
                            cubicTo(mx, sy, mx, dy, dx, dy)
                        }
                        drawPath(p, color = lineColor, style = Stroke(width = with(density) { 1.5.dp.toPx() }, cap = StrokeCap.Round))
                    }
                }
                WF_NODES.forEach { n ->
                    val isDest = n.dest != null
                    Box(
                        Modifier
                            .offset(x = nodeX(n), y = nodeY(n))
                            .size(NODE_W, NODE_H)
                            .clip(RoundedCornerShape(12.dp))
                            .background(nodeBg)
                            .clickable(enabled = isDest) { n.dest?.let(onDest) }
                            .padding(6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (zh) n.zh else n.en,
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                            color = if (isDest) accent else nodeFg,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
