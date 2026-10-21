package top.wkbin.taixu.ui.browser

import androidx.compose.runtime.Composable
import top.wkbin.taixu.ui.components.FoldablePreviews
import top.wkbin.taixu.ui.components.RuntimePreview

@FoldablePreviews
@Composable
private fun BrowserScreenPreview() {
    RuntimePreview {
        BrowserScreen(onBack = { })
    }
}
