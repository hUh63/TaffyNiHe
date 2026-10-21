package top.wkbin.taixu.plugin.ui.web

import androidx.compose.runtime.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import top.wkbin.taixu.plugin.api.PluginExtensionRef
import top.wkbin.taixu.plugin.runtime.PluginRuntime
import top.wkbin.taixu.plugin.runtime.PluginSelectionStore
import top.wkbin.taixu.plugin.ui.LocalPluginController
import top.wkbin.taixu.plugin.ui.NativePluginPageRegistry
import top.wkbin.taixu.plugin.ui.PluginPageHost

/** A newly opened immutable Web package gets its own PAGE generation and fresh document grants. */
@Composable
fun WebPluginHost(plugin: WebPagePlugin, onBack: () -> Unit) {
    val contribution = remember(plugin) { plugin.create() }
    val pages = remember(contribution) { NativePluginPageRegistry(listOf(contribution)) }
    val controller = remember(pages) {
        PluginRuntime(pages.catalog, mapOf(plugin.id to contribution.activate), object : PluginSelectionStore {
            override suspend fun disabledPlugins() = emptySet<String>()
            override suspend fun setEnabled(id: String, enabled: Boolean) = Unit
        })
    }
    LaunchedEffect(controller) {
        try { controller.initialize(); awaitCancellation() }
        finally { withContext(NonCancellable) { controller.shutdown() } }
    }
    CompositionLocalProvider(LocalPluginController provides controller) {
        PluginPageHost(pages, PluginExtensionRef(plugin.id, "main"), onBack = onBack)
    }
}
