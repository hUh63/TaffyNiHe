package top.wkbin.taixu.di.feature.navigation

import org.koin.dsl.module
import top.wkbin.taixu.plugin.ui.PluginPages
import top.wkbin.taixu.plugin.runtime.PluginController
import top.wkbin.taixu.ui.navigation.NativePluginPlatform
import top.wkbin.taixu.di.feature.browser.featureBrowserModule
import top.wkbin.taixu.di.feature.bot.featureBotModule
import top.wkbin.taixu.di.feature.chat.featureChatModule
import top.wkbin.taixu.di.feature.custom.iteration.featureCustomIterationModule
import top.wkbin.taixu.di.feature.developer.featureDeveloperModule
import top.wkbin.taixu.di.feature.git.featureGitModule
import top.wkbin.taixu.di.feature.home.featureHomeModule
import top.wkbin.taixu.di.feature.settings.featureSettingsModule
import top.wkbin.taixu.di.feature.terminal.featureTerminalModule
import top.wkbin.taixu.di.feature.workflow.featureWorkflowModule
import top.wkbin.taixu.di.feature.workspace.featureWorkspaceModule

/** Feature composition stays at the navigation boundary. */
val navigationModule = module {
    single { NativePluginPlatform(prootSource = { context ->
        val linux = get<top.wkbin.taixu.runtime.LinuxRuntime>()
        val servers = get<top.wkbin.taixu.core.datastore.RemoteServerRepository>()
        val source = top.wkbin.taixu.runtime.service.ProotWebAssetService(linux,
            top.wkbin.taixu.plugin.ui.web.WebPluginDemo.assets(context), { servers.activeId.value == null })
        top.wkbin.taixu.plugin.ui.web.WebPluginAssetLease(emptyMap(), available = { source.isAvailable },
            release = { source.close() }, load = { source.snapshot() })
    }) }
    single<PluginPages> { get<NativePluginPlatform>().pages }
    single<PluginController> { get<NativePluginPlatform>().controller(get()) }
    includes(
        featureBrowserModule,
        featureBotModule,
        featureChatModule,
        featureCustomIterationModule,
        featureDeveloperModule,
        featureGitModule,
        featureHomeModule,
        featureSettingsModule,
        featureTerminalModule,
        featureWorkflowModule,
        featureWorkspaceModule,
    )
}
