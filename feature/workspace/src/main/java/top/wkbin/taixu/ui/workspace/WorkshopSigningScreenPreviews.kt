package top.wkbin.taixu.ui.workspace

import androidx.compose.runtime.Composable
import top.wkbin.taixu.ui.components.FoldablePreviews
import top.wkbin.taixu.ui.components.RuntimePreview

@FoldablePreviews
@Composable
private fun WorkshopSigningScreenPreview() {
    RuntimePreview {
        WorkshopSigningScreen(onBack = { })
    }
}
