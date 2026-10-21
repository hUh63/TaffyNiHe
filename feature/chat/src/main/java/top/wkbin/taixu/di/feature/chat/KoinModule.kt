package top.wkbin.taixu.di.feature.chat

import org.koin.dsl.module
import top.wkbin.taixu.ui.chat.ChatViewModel
import top.wkbin.taixu.ui.chat.ToolchainViewModel
import org.koin.core.module.dsl.viewModel
import top.wkbin.taixu.ui.chat.ChatBackgroundViewModel

/** Dependency registrations owned by the feature:chat module. */
val featureChatModule = module {
    viewModel { ChatBackgroundViewModel(get()) }
    // 检测走 ToolchainInspector；补齐走进程级 ToolchainRepairer（不跟 ViewModel 取消）
    viewModel<ToolchainViewModel> {
        ToolchainViewModel(
            inspector = get(),
            repairer = get(),
            toolManager = get(),
            linuxRuntime = get(),
        )
    }

    viewModel<ChatViewModel> {
        ChatViewModel(
            context = get(),
            savedStateHandle = get(),
            harnessLoop = get(),
            requestDiagnostics = get(),
            systemPromptBuilder = get(),
            sessionDao = get(),
            aiModelDao = get(),
            workspaceManager = get(),
            settingsDataStore = get(),
            linuxRuntime = get(),
            terminalSessionManager = get(),
            mcpManager = get(),
            agentSkillRepository = get(),
            mcpServerRepository = get(),
            approvalRepository = get(),
            agentContextDao = get(),
            compactionManager = get(),
            sessionModelSwitcher = get(),
            quickPhraseRepository = get(),
            laneManager = get(),
            eventBus = get(),
            proactiveWorkflowAdvisor = get(),
            modelDiscovery = get(),
            providerCatalog = get(),
            providerRepository = get(),
            profileWriter = get(),
            privilegeManager = get(),
            pathManager = get(),
            workflowRepository = get(),
            translationManager = getOrNull(),
            globalNavigationBus = getOrNull(),
            workspaceCatalogSource = get(),
        )
    }
}
