package com.soreverse.mcp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 设置 → 逆向工作流图（DAG）：把「选择工作区 → 扫描 → 静态/动态分析 → AI 深度 → 编辑 →
 * 构建 → 签名」串成有向图，节点可点击跳转对应功能页，作为全流程导航。
 *
 * 图形化要点（对标 Explorer So 的图视图）：带箭头有向边、安全网（快照回滚）用虚线区分、
 * 不可跳转节点降透明度弱化、配图例。纯 Compose 绘制，无第三方图表依赖。
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

    val lineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    val nodeBg = MaterialTheme.colorScheme.surfaceVariant
    val nodeFg = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary

    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlassGroup(title = if (zh) "图例" else "Legend") {
            LegendRow(if (zh) "实线 = 主流程" else "Solid = main flow", lineColor, false)
            LegendRow(if (zh) "虚线 = 安全网（快照回滚）" else "Dashed = safety net (snapshot)", lineColor, true)
            LegendRow(if (zh) "高亮卡片 = 可点击跳转" else "Highlighted = tappable", accent, false)
        }

        Box(
            Modifier.fillMaxWidth().weight(1f)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()),
        ) {
            Box(Modifier.size(canvasW, canvasH)) {
                Canvas(Modifier.fillMaxSize()) {
                    val nwPx = NODE_W.toPx()
                    val nhPx = NODE_H.toPx()
                    val strokeW = 1.6.dp.toPx()
                    val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
                    WF_EDGES.forEach { e ->
                        val s = WF_NODES[e.from]
                        val d = WF_NODES[e.to]
                        val sx = nodeX(s).toPx() + nwPx
                        val sy = nodeY(s).toPx() + nhPx / 2f
                        val dx = nodeX(d).toPx()
                        val dy = nodeY(d).toPx() + nhPx / 2f
                        val mx = (sx + dx) / 2f
                        val safety = d.id == "snapshot"
                        val p = Path().apply { moveTo(sx, sy); cubicTo(mx, sy, mx, dy, dx, dy) }
                        drawPath(
                            p,
                            color = lineColor,
                            style = Stroke(width = strokeW, cap = StrokeCap.Round, pathEffect = if (safety) dash else null),
                        )
                        // 箭头（指向目标节点）
                        val ah = 8.dp.toPx()
                        val arrow = Path().apply {
                            moveTo(dx, dy)
                            lineTo(dx - ah, dy - ah * 0.55f)
                            lineTo(dx - ah, dy + ah * 0.55f)
                            close()
                        }
                        drawPath(arrow, color = lineColor)
                    }
                }
                WF_NODES.forEach { n ->
                    val isDest = n.dest != null
                    Box(
                        Modifier
                            .offset(x = nodeX(n), y = nodeY(n))
                            .size(NODE_W, NODE_H)
                            .clip(RoundedCornerShape(AppShape.md))
                            .background(if (isDest) accent.copy(alpha = 0.12f) else nodeBg.copy(alpha = 0.5f))
                            .then(if (isDest) Modifier.clickable { n.dest?.let(onDest) } else Modifier)
                            .padding(6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (zh) n.zh else n.en,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isDest) accent else nodeFg.copy(alpha = 0.55f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendRow(text: String, color: androidx.compose.ui.graphics.Color, dashed: Boolean) {
    val density = LocalDensity.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(Modifier.size(28.dp, 12.dp)) {
            val y = size.height / 2f
            drawLine(
                color = color,
                start = androidx.compose.ui.geometry.Offset(0f, y),
                end = androidx.compose.ui.geometry.Offset(size.width, y),
                strokeWidth = with(density) { 1.6.dp.toPx() },
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 5f)) else null,
            )
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
