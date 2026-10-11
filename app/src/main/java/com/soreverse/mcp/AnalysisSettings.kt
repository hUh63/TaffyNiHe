package com.soreverse.mcp

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 分析页「设置」页 —— 完全对齐 Explorer So `ui/main/SettingsFragment` 的两节：
 * 「反汇编分析」（引用字符串 / 解析函数名 / 首页卡片闪动 / 签名还原 IR 增强 / 反编译引擎）
 * 与「流程图设置」（显示小地图 / 长按拖拽基本块 / 流程图引擎 / 基本块内容 / 背景形状）。
 *
 * 持久化键与语义对齐 Exbin `util/SettingsPrefs`（FILE = taffy_analysis_settings）。
 */
internal object AnalysisPrefs {
    private const val FILE = "taffy_analysis_settings"
    private const val K_STRING_REF = "enable_string_ref"
    private const val K_FUNC_NAME = "enable_func_name"
    private const val K_HOME_BLINK = "home_card_blink"
    private const val K_SIG_IR = "sig_ir_enhance"
    private const val K_DECOMP = "decomp_engine"
    private const val K_MINIMAP = "flowchart_minimap"
    private const val K_DRAG = "flowchart_drag"
    private const val K_BLOCK = "flowchart_block_content"
    private const val K_BG = "flowchart_bg"
    private const val K_ENGINE = "flowchart_engine"

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 应用启动/进入分析页时把已保存的设置注入运行时状态。 */
    fun load(ctx: Context, tools: ToolPagesState) {
        val sp = p(ctx)
        val ui = tools.cfgUi
        ui.stringRef = sp.getBoolean(K_STRING_REF, ui.stringRef)
        ui.funcName = sp.getBoolean(K_FUNC_NAME, ui.funcName)
        ui.homeBlink = sp.getBoolean(K_HOME_BLINK, ui.homeBlink)
        ui.sigIrEnhance = sp.getBoolean(K_SIG_IR, ui.sigIrEnhance)
        ui.decompEngine = sp.getString(K_DECOMP, ui.decompEngine) ?: ui.decompEngine
        ui.minimap = sp.getBoolean(K_MINIMAP, ui.minimap)
        ui.dragNode = sp.getBoolean(K_DRAG, ui.dragNode)
        ui.contentMode = sp.getString(K_BLOCK, ui.contentMode) ?: ui.contentMode
        ui.bgStyle = sp.getString(K_BG, ui.bgStyle) ?: ui.bgStyle
        ui.layoutMode = sp.getString(K_ENGINE, ui.layoutMode) ?: ui.layoutMode
        tools.disasmAnnotate = ui.stringRef
    }

    fun save(ctx: Context, tools: ToolPagesState) {
        val ui = tools.cfgUi
        p(ctx).edit()
            .putBoolean(K_STRING_REF, ui.stringRef)
            .putBoolean(K_FUNC_NAME, ui.funcName)
            .putBoolean(K_HOME_BLINK, ui.homeBlink)
            .putBoolean(K_SIG_IR, ui.sigIrEnhance)
            .putString(K_DECOMP, ui.decompEngine)
            .putBoolean(K_MINIMAP, ui.minimap)
            .putBoolean(K_DRAG, ui.dragNode)
            .putString(K_BLOCK, ui.contentMode)
            .putString(K_BG, ui.bgStyle)
            .putString(K_ENGINE, ui.layoutMode)
            .apply()
        tools.disasmAnnotate = ui.stringRef
    }
}

@Composable
internal fun AnalysisSettingsView(tools: ToolPagesState, zh: Boolean, context: Context) {
    val cs = MaterialTheme.colorScheme
    val ui = tools.cfgUi
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (zh) "设置" else "Settings",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = cs.onSurface,
            modifier = Modifier.padding(start = 2.dp),
        )

        // ── 反汇编分析（Exbin「反汇编分析选项」）──
        AppCard(title = if (zh) "反汇编分析" else "Disassembly") {
            SectionHint(if (zh) "控制反汇编阶段的智能分析功能。关闭后解析更快，结果中不再显示相关内容。" else "Smart analysis during disassembly.")
            ToggleRow(if (zh) "引用字符串" else "String refs", ui.stringRef) { ui.stringRef = it; AnalysisPrefs.save(context, tools) }
            GroupDivider()
            ToggleRow(if (zh) "解析函数名 (bl 目标)" else "Resolve fn names", ui.funcName) { ui.funcName = it; AnalysisPrefs.save(context, tools) }
            GroupDivider()
            ToggleRow(if (zh) "首页卡片闪动" else "Home card blink", ui.homeBlink) { ui.homeBlink = it; AnalysisPrefs.save(context, tools) }
            GroupDivider()
            ToggleRow(if (zh) "签名还原 IR 增强" else "Sig IR enhance", ui.sigIrEnhance) { ui.sigIrEnhance = it; AnalysisPrefs.save(context, tools) }
            GroupDivider()
            SegSetting(
                label = if (zh) "反编译引擎" else "Decompiler engine",
                hint = if (zh) "伪 C 页使用的反编译引擎：r2dec plus 为纯 Java r2dec 移植；Decompiler 为 C++ native 管线；Ghidra 为官方引擎。切换后下次打开函数详情生效。" else "engine used by pseudo-C",
                options = listOf("r2dec plus" to "r2dec+", "Decompiler" to "native", "Ghidra" to "ghidra"),
                selected = ui.decompEngine,
            ) { ui.decompEngine = it; AnalysisPrefs.save(context, tools) }
        }

        // ── 流程图设置（Exbin「流程图选项」）──
        AppCard(title = if (zh) "流程图设置" else "Flowchart") {
            SectionHint(if (zh) "控制流程图的显示与交互行为。修改后下次查看流程图生效。" else "Flowchart display & interaction.")
            ToggleRow(if (zh) "显示小地图" else "Minimap", ui.minimap) { ui.minimap = it; AnalysisPrefs.save(context, tools) }
            GroupDivider()
            ToggleRow(if (zh) "长按拖拽基本块" else "Drag blocks", ui.dragNode) { ui.dragNode = it; AnalysisPrefs.save(context, tools) }
            GroupDivider()
            SegSetting(
                label = if (zh) "流程图引擎" else "Layout engine",
                hint = if (zh) "选择布局计算引擎。纯 Java ELK 速度最快且结果稳定；ELK.js 需 WebView 渲染（未内置）。" else "layout engine",
                options = listOf("标准" to "dagre", "ELK.js" to "elkjs", "ELK Java" to "elk"),
                selected = ui.layoutMode,
            ) { ui.layoutMode = it; AnalysisPrefs.save(context, tools) }
            GroupDivider()
            SegSetting(
                label = if (zh) "基本块内容" else "Block content",
                hint = if (zh) "基本块内显示汇编指令 / 反编译代码 / 摘要。" else "block content",
                options = listOf("汇编指令" to "asm", "反编译代码" to "pseudo", "摘要" to "summary"),
                selected = ui.contentMode,
            ) { ui.contentMode = it; AnalysisPrefs.save(context, tools) }
            GroupDivider()
            SegSetting(
                label = if (zh) "背景形状" else "Background",
                hint = if (zh) "基本块始终由布局引擎摆放，此选项仅控制背景图案。" else "background pattern",
                options = listOf("无" to "none", "格子" to "grid", "蛛网" to "cobweb", "雷达" to "radar", "蜂巢" to "honeycomb"),
                selected = ui.bgStyle,
            ) { ui.bgStyle = it; AnalysisPrefs.save(context, tools) }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun SegSetting(
    label: String,
    hint: String,
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
        Text(hint, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, fontSize = AppText.label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (l, v) -> ToolChip(l, selected = selected == v) { onPick(v) } }
        }
    }
}
