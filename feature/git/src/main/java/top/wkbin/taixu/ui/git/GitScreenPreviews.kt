package top.wkbin.taixu.ui.git

import androidx.compose.runtime.Composable
import top.wkbin.taixu.ui.components.FoldablePreviews
import top.wkbin.taixu.ui.components.RuntimePreview

@FoldablePreviews
@Composable
private fun GitScreenPreview() {
    RuntimePreview {
        GitScreen(projectName = "preview-project", onBack = { })
    }
}
