package top.wkbin.taixu.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import top.wkbin.taixu.plugin.api.PluginExtensionRef
import top.wkbin.taixu.plugin.runtime.PluginController
import top.wkbin.taixu.plugin.ui.LocalPluginController
import top.wkbin.taixu.plugin.ui.PluginEntryLinks
import top.wkbin.taixu.plugin.ui.PluginEntryLocation
import top.wkbin.taixu.ui.browser.BrowserPluginOwner
import top.wkbin.taixu.ui.browser.BrowserViewModel
import top.wkbin.taixu.ui.components.LocalAppNavigation
import top.wkbin.taixu.ui.components.LocalPluginMenuContent
import top.wkbin.taixu.ui.components.LocalOpenPluginManager
import top.wkbin.taixu.ui.components.LocalRuntimeWidthClass

@Composable
internal fun PluginNavigationShell(
    platform: NativePluginPlatform, controller: PluginController, browserViewModel: BrowserViewModel,
    onOpen: (PluginExtensionRef) -> Unit, onOpenManager: () -> Unit, onOpenNavigation: () -> Unit,
    selectedPage: PluginExtensionRef? = null, content: @Composable () -> Unit,
) {
    LaunchedEffect(controller) { controller.initialize() }
    CompositionLocalProvider(
        LocalPluginController provides controller,
        // Rail or docked sidebar already exposes navigation; top bars drop their menu button.
        LocalAppNavigation provides onOpenNavigation.takeUnless { LocalRuntimeWidthClass.current.hasPersistentNavigation },
        top.wkbin.taixu.ui.components.LocalPluginDestinationActive provides (selectedPage != null),
        LocalPluginMenuContent provides {
            PluginEntryLinks(platform.plugins, controller, PluginEntryLocation.MENU, onOpen, selectedPage)
        },
        LocalOpenPluginManager provides onOpenManager,
    ) { BrowserPluginOwner(browserViewModel, content) }
}
