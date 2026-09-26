package com.soreverse.mcp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 编辑器独立页面（顶层 [MainTab.Editor]）。
 *
 * 自带顶栏 + 全屏编辑区；编辑能力整体复用 [SettingsEditorPage]
 * （多标签 / SAF 打开 / 自动保存与备份 / 最近文件 / 命令行工具条 / 软键 / 行标记 / REPL 控制台 / AI 帮助）。
 * 通过 [EditorBridge.pendingPath] 接收外部页面（扩展页、工作流页）传来的待编辑文件。
 */
@Composable
internal fun EditorScreen(t: UiText) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = if (t.zh) "编辑器" else "Editor",
            subtitle = if (t.zh) "多标签 · 语法高亮 · 行标记 · 控制台"
            else "Multi-tab · syntax highlight · diff marks · console",
            showBack = false,
        )
        Box(Modifier.fillMaxSize().padding(top = 2.dp)) {
            SettingsEditorPage(t)
        }
    }
}
