package top.wkbin.taixu.ui.developer

import androidx.compose.runtime.Composable
import top.wkbin.taixu.ui.components.FoldablePreviews
import top.wkbin.taixu.ui.components.RuntimePreview

@FoldablePreviews
@Composable
private fun DeveloperScreenPreview() {
    RuntimePreview {
        LocalDeveloperScreen(onBack = { })
    }
}

@FoldablePreviews
@Composable
private fun RemoteDeveloperScreenPreview() {
    RuntimePreview {
        RemoteDeveloperScreen(serverId = "preview", onBack = { })
    }
}
