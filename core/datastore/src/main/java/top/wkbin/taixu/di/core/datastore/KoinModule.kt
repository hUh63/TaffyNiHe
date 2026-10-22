package top.wkbin.taixu.di.core.datastore

import org.koin.dsl.module
import top.wkbin.taixu.core.datastore.AgentPreferences
import top.wkbin.taixu.core.datastore.AgentServerPreferences
import top.wkbin.taixu.core.datastore.AppStatsPreferences
import top.wkbin.taixu.core.datastore.AppearancePreferences
import top.wkbin.taixu.core.datastore.BackupPreferenceStore
import top.wkbin.taixu.core.datastore.BackupPreferences
import top.wkbin.taixu.core.datastore.BrowserPreferences
import top.wkbin.taixu.core.datastore.FirstUseGuidePreferences
import top.wkbin.taixu.core.datastore.FtpPreferences
import top.wkbin.taixu.core.datastore.OnboardingPreferences
import top.wkbin.taixu.core.datastore.ProviderPreferences
import top.wkbin.taixu.core.datastore.RegistryPreferences
import top.wkbin.taixu.core.datastore.RuntimePreferences
import top.wkbin.taixu.core.datastore.SettingsDataStore
import top.wkbin.taixu.core.datastore.SshPreferences
import top.wkbin.taixu.core.datastore.TerminalPreferences
import top.wkbin.taixu.core.datastore.ToolPreferences
import top.wkbin.taixu.core.datastore.WorkshopPreferences

/** Dependency registrations owned by the core:datastore module. */
val coreDatastoreModule = module {
    single { top.wkbin.taixu.core.datastore.ChatBackgroundPreferences(context = get()) }
    single { top.wkbin.taixu.core.datastore.ChatBackgroundImageStore(context = get()) }
    single { top.wkbin.taixu.core.datastore.PluginPreferences(context = get()) }
    single { top.wkbin.taixu.core.datastore.ContainerBackendPreferences(context = get()) }
    single<top.wkbin.taixu.core.datastore.RemoteServerRepository> {
        top.wkbin.taixu.core.datastore.EncryptedRemoteServerRepository(get(), get())
    }
    single<BackupPreferenceStore> { BackupPreferences(context = get()) }
    single<AppearancePreferences> { AppearancePreferences(store = get()) }

    single<TerminalPreferences> { TerminalPreferences(store = get()) }

    single<RuntimePreferences> { RuntimePreferences(store = get()) }

    single<WorkshopPreferences> { WorkshopPreferences(store = get()) }

    single<SshPreferences> { SshPreferences(store = get()) }

    single<FtpPreferences> { FtpPreferences(store = get()) }

    single<AgentPreferences> { AgentPreferences(store = get()) }

    single<OnboardingPreferences> { OnboardingPreferences(store = get()) }

    single<ToolPreferences> { ToolPreferences(store = get()) }

    single<FirstUseGuidePreferences> { FirstUseGuidePreferences(store = get()) }

    single<RegistryPreferences> { RegistryPreferences(store = get()) }

    single<AppStatsPreferences> { AppStatsPreferences(store = get()) }

    single<ProviderPreferences> { ProviderPreferences(store = get()) }

    single<BrowserPreferences> { BrowserPreferences(store = get()) }

    single<AgentServerPreferences> { AgentServerPreferences(store = get()) }
    single { top.wkbin.taixu.core.datastore.McpClientPreferences(context = get()) }
    single { top.wkbin.taixu.core.datastore.McpToolPolicyPreferences(context = get()) }

    single<SettingsDataStore> { SettingsDataStore(context = get(), secretManager = get()) }
}
