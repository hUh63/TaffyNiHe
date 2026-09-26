package com.soreverse.mcp

import androidx.compose.runtime.Composable

/**
 * 编辑器独立页面（顶层 [MainTab.Editor]）。
 *
 * 全屏承载 [SettingsEditorPage]：不再叠一层页面级标题栏 / 内边距，
 * 把纵向空间全部交给编辑区（顶栏由编辑器自身的文件标签条 + 命令条承担）。
 *
 * 本页在 [MainActivity] 中**常驻挂载**（切走时移出可视区而非销毁），
 * 因此打开的文件、未保存内容、撤销栈、光标/滚动位置在切 tab 后仍然保留；
 * 通过 [EditorBridge.pendingPath] 接收外部页面（扩展页、工作流页）传来的待编辑文件。
 */
@Composable
internal fun EditorScreen(t: UiText) {
    SettingsEditorPage(t)
}
