package top.wkbin.taixu.plugin.ui

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import top.wkbin.taixu.plugin.api.PluginCatalog
import top.wkbin.taixu.plugin.api.PluginDescriptor
import top.wkbin.taixu.plugin.api.PluginExtension
import top.wkbin.taixu.plugin.api.PluginExtensionPoint
import top.wkbin.taixu.plugin.runtime.PluginController
import top.wkbin.taixu.plugin.runtime.PluginLease
import top.wkbin.taixu.plugin.runtime.PluginPhase
import top.wkbin.taixu.plugin.runtime.PluginStatus
import top.wkbin.taixu.ui.components.FoldablePreviews
import top.wkbin.taixu.ui.components.RuntimePreview

@FoldablePreviews
@Composable
private fun PluginManagerScreenPreview() {
    val plugin = NativePagePlugin(
        descriptor = PluginDescriptor(
            id = "preview.sample",
            version = "1",
            extensions = listOf(PluginExtension("home", PluginExtensionPoint.PAGE)),
        ),
        pages = listOf(NativePluginPage("home") { }),
        title = { "示例插件" },
        description = { "折叠屏预览用的插件卡片" },
        entries = listOf(NativePluginEntry("open", "home", PluginEntryLocation.MENU) { "打开" }),
    )
    RuntimePreview {
        PluginManagerScreen(
            plugins = listOf(plugin),
            controller = PreviewPluginController(listOf(plugin)),
            onOpen = { },
            onBack = { },
        )
    }
}

private class PreviewPluginController(plugins: List<NativePagePlugin>) : PluginController {
    override val catalog: PluginCatalog = PluginCatalog.create(plugins.map { it.descriptor })
    override val states: StateFlow<Map<String, PluginStatus>> = MutableStateFlow(
        plugins.associate { it.descriptor.id to PluginStatus(phase = PluginPhase.RUNNING) },
    )
    override val initialized: StateFlow<Boolean> = MutableStateFlow(true)
    override val preferenceFailure: StateFlow<Boolean> = MutableStateFlow(false)
    override suspend fun initialize() = Unit
    override suspend fun setEnabled(id: String, enabled: Boolean) = Unit
    override fun acquire(id: String): PluginLease? = null
    override suspend fun shutdown(): Boolean = true
}
