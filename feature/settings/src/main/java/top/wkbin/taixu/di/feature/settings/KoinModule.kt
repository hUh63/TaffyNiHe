package top.wkbin.taixu.di.feature.settings

import org.koin.dsl.module
import top.wkbin.taixu.ui.settings.AppManagementViewModel
import top.wkbin.taixu.ui.settings.CcSwitchViewModel
import top.wkbin.taixu.ui.settings.FtpSettingsViewModel
import top.wkbin.taixu.ui.settings.FeatureHubViewModel
import top.wkbin.taixu.ui.settings.FirstResponseTimeoutViewModel
import top.wkbin.taixu.ui.settings.LocalLlmViewModel
import top.wkbin.taixu.ui.settings.PhoneAgentSettingsViewModel
import top.wkbin.taixu.ui.settings.SettingsViewModel
import top.wkbin.taixu.ui.settings.SponsorListRepository
import top.wkbin.taixu.ui.settings.SponsorListViewModel
import top.wkbin.taixu.ui.settings.SshSettingsViewModel
import top.wkbin.taixu.ui.settings.StorageUsageViewModel
import top.wkbin.taixu.ui.settings.McpCatalogViewModel
import top.wkbin.taixu.ui.settings.ToolCenterViewModel
import top.wkbin.taixu.ui.settings.ToolDetailViewModel
import top.wkbin.taixu.ui.settings.stats.StatsRepository
import top.wkbin.taixu.ui.settings.stats.StatsViewModel
import org.koin.core.module.dsl.viewModel
import top.wkbin.taixu.ui.settings.LocalBackupViewModel

/** Dependency registrations owned by the feature:settings module. */
val featureSettingsModule = module {
    single { top.wkbin.taixu.ui.settings.PackagedWebPluginSources(get(), get(), get(), get()) }
    viewModel { top.wkbin.taixu.ui.settings.WebPluginsViewModel(repository = get()) }
    viewModel { top.wkbin.taixu.ui.settings.ThemePaletteViewModel(get()) }
    viewModel { top.wkbin.taixu.ui.settings.ChatBackgroundSettingsViewModel(get(), get()) }
    viewModel { top.wkbin.taixu.ui.settings.PackagedPluginsViewModel(get(), get(), get()) }
    viewModel { top.wkbin.taixu.ui.settings.McpClientSettingsViewModel(get()) }
    viewModel { top.wkbin.taixu.ui.settings.McpToolPolicyViewModel(get(), get()) }
    viewModel { top.wkbin.taixu.ui.settings.RemoteServerViewModel(get(), get()) }
    viewModel { LocalBackupViewModel(context = get(), service = get()) }
    viewModel<AppManagementViewModel> { AppManagementViewModel(repository = get(), appManager = get()) }
    viewModel { PhoneAgentSettingsViewModel(preferences = get(), connectionTester = get()) }
    viewModel { FirstResponseTimeoutViewModel(preferences = get()) }

    viewModel<CcSwitchViewModel> {
        CcSwitchViewModel(
            toolManager = get(),
            ccSwitchClient = get(),
            localAccess = get(),
            aiModelDao = get(),
            providerRepository = get(),
            toolSettingsRepository = get(),
            linuxRuntime = get(),
            dependencyManager = get(),
            logger = get(),
        )
    }

    viewModel<FtpSettingsViewModel> {
        FtpSettingsViewModel(
            context = get(),
            linuxRuntime = get(),
            preferences = get(),
            sshPreferences = get(),
            manager = get(),
        )
    }

    viewModel<LocalLlmViewModel> {
        LocalLlmViewModel(
            context = get(),
            localLlmManager = get(),
            toolManager = get(),
            linuxRuntime = get(),
            aiModelRepository = get(),
        )
    }

    viewModel<SettingsViewModel> {
        SettingsViewModel(
            application = get(),
            logger = get(),
            appearancePreferences = get(),
            terminalPreferences = get(),
            runtimePreferences = get(),
            agentPreferences = get(),
            firstUseGuidePreferences = get(),
            providerRepository = get(),
            aiModelDao = get(),
            modelDiscovery = get(),
            providerCatalogRepository = get(),
            connectionTester = get(),
            privilegeManager = get(),
            mcpManager = get(),
            linuxRuntime = get(),
            pathManager = get(),
            linuxEnvironmentManager = get(),
            appUpdateManager = get(),
            subagentRepository = get(),
            agentSkillRepository = get(),
            mcpServerRepository = get(),
            mcpOAuthCoordinator = get(),
            mcpOAuthCredentials = get(),
            storageMountBindingRepository = get(),
            approvalRepository = get(),
            sessionDao = get(),
            toolManager = get(),
            quickPhraseRepository = get(),
            profileWriter = get(),
            profileBackupCodec = get(),
            webChatBridgeServer = get(),
            browserPrefs = get(),
            agentServerPreferences = get(),
            translationManager = get(),
            skillInstallationManager = getOrNull(),
            clawHubClient = getOrNull(),
        )
    }

    viewModel<FeatureHubViewModel> {
        FeatureHubViewModel(
            webChatBridgeServer = get(),
            workflowRepository = get(),
            scheduleRepository = get(),
            logger = get(),
        )
    }

    single<SponsorListRepository> { SponsorListRepository(httpClient = get()) }

    viewModel<SponsorListViewModel> { SponsorListViewModel(repository = get()) }

    viewModel<SshSettingsViewModel> {
        SshSettingsViewModel(
            context = get(),
            linuxRuntime = get(),
            preferences = get(),
            manager = get(),
        )
    }

    viewModel<StorageUsageViewModel> { StorageUsageViewModel(storageManager = get()) }

    viewModel<McpCatalogViewModel> {
        McpCatalogViewModel(
            mcpServerRepository = get(),
            toolManager = get(),
            updater = get(),
            remoteServers = get(),
            logger = get(),
        )
    }

    viewModel<ToolCenterViewModel> {
        ToolCenterViewModel(
            toolManager = get(),
            linuxRuntime = get(),
            firstUseGuidePreferences = get(),
            logger = get(),
            remoteServers = get(),
            mcpServerRepository = get(),
        )
    }

    viewModel<ToolDetailViewModel> {
        ToolDetailViewModel(
            toolManager = get(),
            settingsDataStore = get(),
            providerRepository = get(),
            aiModelDao = get(),
            toolSettingsRepository = get(),
            linuxRuntime = get(),
            logger = get(),
        )
    }

    single<StatsRepository> {
        StatsRepository(
            runtimeRepository = get(),
            sessionRepository = get(),
            aiModelRepository = get(),
            appStatsPreferences = get(),
        )
    }

    viewModel<StatsViewModel> { StatsViewModel(statsRepository = get()) }
}
