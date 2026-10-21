package top.wkbin.taixu.ui.iteration

import androidx.compose.runtime.Composable
import top.wkbin.taixu.ui.components.FoldablePreviews
import top.wkbin.taixu.ui.components.RuntimePreview

@FoldablePreviews
@Composable
private fun CustomIterationScreenPreview() {
    RuntimePreview {
        CustomIterationScreen(onBack = { }, onNavigateToChat = { _ -> })
    }
}
