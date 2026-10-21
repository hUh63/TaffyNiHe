package top.wkbin.taixu.ui.terminal

import top.wkbin.taixu.plugin.api.PluginDescriptor
import top.wkbin.taixu.plugin.api.PluginExtension
import top.wkbin.taixu.plugin.api.PluginExtensionPoint
import top.wkbin.taixu.plugin.api.PluginExtensionRef
import top.wkbin.taixu.plugin.ui.NativePagePlugin
import top.wkbin.taixu.plugin.ui.NativePluginPage
import top.wkbin.taixu.plugin.ui.NativePluginEntry
import top.wkbin.taixu.plugin.ui.PluginEntryLocation
import androidx.compose.ui.res.stringResource
import top.wkbin.taixu.feature.terminal.R

/** Compiled-in page contribution. PTY resources remain owned by the existing runtime. */
object TerminalPlugin {
    val page = PluginExtensionRef("taixu.terminal", "main")
    const val PROJECT_ARGUMENT = "project"

    fun create(): NativePagePlugin = NativePagePlugin(
        descriptor = PluginDescriptor(
            id = page.pluginId,
            version = "1.0.0",
            extensions = listOf(PluginExtension(page.extensionId, PluginExtensionPoint.PAGE)),
        ),
        title = { stringResource(R.string.terminal_plugin_title) },
        icon = top.wkbin.taixu.ui.components.RuntimeIconName.Terminal,
        description = { stringResource(R.string.terminal_plugin_description) },
        entries = listOf(
            NativePluginEntry("open", "main", PluginEntryLocation.MENU) { stringResource(R.string.terminal_plugin_title) },
            NativePluginEntry("settings", "main", PluginEntryLocation.SETTINGS) { stringResource(R.string.terminal_plugin_title) },
        ),
        pages = listOf(
            NativePluginPage(page.extensionId) { request ->
                TerminalScreen(
                    onBack = request.onBack,
                    project = request.arguments[PROJECT_ARGUMENT].orEmpty(),
                    showBackButton = !request.embedded,
                    embedded = request.embedded,
                )
            },
        ),
    )
}
