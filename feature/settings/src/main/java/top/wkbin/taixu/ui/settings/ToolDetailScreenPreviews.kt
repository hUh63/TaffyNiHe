package top.wkbin.taixu.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import top.wkbin.taixu.ui.components.FoldablePreviews
import top.wkbin.taixu.ui.components.RuntimePreview

@FoldablePreviews
@Preview(name = "深层设置 · 窄屏大字体", widthDp = 320, heightDp = 720, fontScale = 1.3f, locale = "zh")
@Preview(name = "Detail · Dark", widthDp = 360, heightDp = 800, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, locale = "en")
@Composable
private fun ToolDetailContentPreview() {
    RuntimePreview {
        LocalToolDetailContent(toolId = "preview-project", onBack = { }, onLaunchTerminal = { _ -> })
    }
}
