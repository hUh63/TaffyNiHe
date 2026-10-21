package top.wkbin.taixu.di.app

import org.koin.dsl.module
import org.koin.androidx.workmanager.dsl.worker
import top.wkbin.taixu.bot.BotSchedulePlanner
import top.wkbin.taixu.bot.BotScheduleWorker
import top.wkbin.taixu.workflow.WorkflowScheduleWorker
import top.wkbin.taixu.core.database.repository.BotScheduleRepository
import top.wkbin.taixu.harness.bot.schedule.BotScheduleWakeup
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import top.wkbin.taixu.core.database.AgentApprovalDao
import top.wkbin.taixu.core.database.AgentContextDao
import top.wkbin.taixu.core.database.AgentSkillDao
import top.wkbin.taixu.core.database.AgentSubagentDao
import top.wkbin.taixu.core.database.AiModelDao
import top.wkbin.taixu.core.database.AndroidAppDao
import top.wkbin.taixu.core.database.AppDatabase
import top.wkbin.taixu.core.database.BotAssignmentDao
import top.wkbin.taixu.core.database.BotGroupSessionDao
import top.wkbin.taixu.core.database.BotMemberGroupDao
import top.wkbin.taixu.core.database.BotScheduleDao
import top.wkbin.taixu.core.database.BotScheduleDeliveryDao
import top.wkbin.taixu.di.AppModule.provideBotScheduleDeliveryDao
import top.wkbin.taixu.core.database.BuildScriptDao
import top.wkbin.taixu.core.database.GroupChatDao
import top.wkbin.taixu.core.database.HarnessRuntimeDao
import top.wkbin.taixu.core.database.HarnessSessionDao
import top.wkbin.taixu.core.database.InstallLogDao
import top.wkbin.taixu.core.database.InstallTaskDao
import top.wkbin.taixu.core.database.McpOAuthCredentialDao
import top.wkbin.taixu.core.database.McpOAuthTransactionDao
import top.wkbin.taixu.core.database.McpServerDao
import top.wkbin.taixu.core.database.PersonaBotDao
import top.wkbin.taixu.core.database.QuickPhraseDao
import top.wkbin.taixu.core.database.RuntimeDao
import top.wkbin.taixu.core.database.StorageMountBindingDao
import top.wkbin.taixu.core.database.TerminalSessionDao
import top.wkbin.taixu.core.database.ToolDao
import top.wkbin.taixu.core.database.ToolSettingsDao
import top.wkbin.taixu.core.database.WorkflowDao
import top.wkbin.taixu.core.database.WorkflowScheduleDao
import top.wkbin.taixu.core.database.WorkflowScheduleStore
import top.wkbin.taixu.core.database.WorkspaceDao
import top.wkbin.taixu.core.database.task.AgentTaskDao
import top.wkbin.taixu.core.network.FileDownloader
import top.wkbin.taixu.core.tools.DependencyManager
import top.wkbin.taixu.core.tools.RuntimeManager
import top.wkbin.taixu.core.tools.ToolRuntimeAdapter
import top.wkbin.taixu.di.AppModule.provideAgentApprovalDao
import top.wkbin.taixu.di.AppModule.provideAgentContextDao
import top.wkbin.taixu.di.AppModule.provideAgentForegroundLauncher
import top.wkbin.taixu.di.AppModule.provideAgentSkillDao
import top.wkbin.taixu.di.AppModule.provideAgentSubagentDao
import top.wkbin.taixu.di.AppModule.provideAgentTaskDao
import top.wkbin.taixu.di.AppModule.provideAiModelDao
import top.wkbin.taixu.di.AppModule.provideAndroidAppDao
import top.wkbin.taixu.di.AppModule.provideBotAssignmentDao
import top.wkbin.taixu.di.AppModule.provideBotGroupSessionDao
import top.wkbin.taixu.di.AppModule.provideBotScheduleDao
import top.wkbin.taixu.di.AppModule.provideBuildScriptDao
import top.wkbin.taixu.di.AppModule.provideGroupChatDao
import top.wkbin.taixu.di.AppModule.provideBotMemberGroupDao
import top.wkbin.taixu.di.AppModule.providePersonaBotDao
import top.wkbin.taixu.di.AppModule.provideCheckpointStore
import top.wkbin.taixu.di.AppModule.provideDatabase
import top.wkbin.taixu.di.AppModule.provideDependencyManager
import top.wkbin.taixu.di.AppModule.provideFileDownloader
import top.wkbin.taixu.di.AppModule.provideHarnessRuntimeDao
import top.wkbin.taixu.di.AppModule.provideHarnessSessionDao
import top.wkbin.taixu.di.AppModule.provideInstallLogDao
import top.wkbin.taixu.di.AppModule.provideInstallTaskDao
import top.wkbin.taixu.di.AppModule.provideJson
import top.wkbin.taixu.di.AppModule.provideKtorHttpClient
import top.wkbin.taixu.di.AppModule.provideLinuxRuntime
import top.wkbin.taixu.di.AppModule.provideLocalServiceLauncher
import top.wkbin.taixu.di.AppModule.provideMcpOAuthCredentialDao
import top.wkbin.taixu.di.AppModule.provideMcpOAuthTransactionDao
import top.wkbin.taixu.di.AppModule.provideMcpServerDao
import top.wkbin.taixu.di.AppModule.provideOkHttpClient
import top.wkbin.taixu.di.AppModule.provideProcessRegistry
import top.wkbin.taixu.di.AppModule.providePtyManager
import top.wkbin.taixu.di.AppModule.provideQuickPhraseDao
import top.wkbin.taixu.di.AppModule.provideRuntimeDao
import top.wkbin.taixu.di.AppModule.provideRuntimeManager
import top.wkbin.taixu.di.AppModule.provideShellExecutor
import top.wkbin.taixu.di.AppModule.provideStorageMountBindingDao
import top.wkbin.taixu.di.AppModule.provideTerminalSessionDao
import top.wkbin.taixu.di.AppModule.provideToolDao
import top.wkbin.taixu.di.AppModule.provideToolSettingsDao
import top.wkbin.taixu.di.AppModule.provideWorkflowDao
import top.wkbin.taixu.di.AppModule.provideWorkflowScheduleDao
import top.wkbin.taixu.di.AppModule.provideWorkflowScheduleStore
import top.wkbin.taixu.di.AppModule.provideWorkspaceDao
import top.wkbin.taixu.di.AppModule.provideWorkspaceFileAccess
import top.wkbin.taixu.harness.AgentForegroundLauncher
import top.wkbin.taixu.harness.WorkspaceFileAccess
import top.wkbin.taixu.harness.agent.AgentMcpForegroundLauncher
import top.wkbin.taixu.harness.checkpoint.CheckpointStore
import top.wkbin.taixu.harness.workflow.WorkflowScheduleDispatcher
import top.wkbin.taixu.lifecycle.RuntimeLifecycleSupervisor
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.pty.PtyManager
import top.wkbin.taixu.runtime.service.LocalServiceLauncher
import top.wkbin.taixu.runtime.service.RuntimeServiceController
import top.wkbin.taixu.runtime.shell.ProcessRegistry
import top.wkbin.taixu.runtime.shell.ShellExecutor
import top.wkbin.taixu.runtime.tools.CodexToolInstaller
import top.wkbin.taixu.runtime.tools.HelloToolInstaller
import top.wkbin.taixu.runtime.webchat.WebChatAgentGateway
import top.wkbin.taixu.service.AgentForegroundLauncherImpl
import top.wkbin.taixu.service.AgentMcpForegroundLauncherImpl
import top.wkbin.taixu.service.adb.AdbNotificationManager
import top.wkbin.taixu.webchat.TaiXuWebChatAgentGateway
import top.wkbin.taixu.workflow.AppForegroundTracker
import top.wkbin.taixu.workflow.WorkManagerScheduleDispatcher
import top.wkbin.taixu.workflow.WorkManagerScheduleDispatcher.ScheduleDispatcherModule.provideDispatcher
import top.wkbin.taixu.workflow.WorkflowApprovalNotifier
import top.wkbin.taixu.workflow.WorkflowRunUiController
import org.koin.core.qualifier.named

/** Dependency registrations owned by the app module. */
val appModule = module {
    worker { params ->
        WorkflowScheduleWorker(get(), params.get(), get(), get())
    }

    worker { params ->
        BotScheduleWorker(get(), params.get(), get(), get())
    }

    single { BotSchedulePlanner(context = get(), scheduleRepository = get<BotScheduleRepository>(), runner = get()) }
    single<BotScheduleWakeup> { get<BotSchedulePlanner>() }

    single<Json> { provideJson() }

    single<AppDatabase> { provideDatabase(context = get()) }

    single<ToolDao> { provideToolDao(database = get()) }

    single<InstallLogDao> { provideInstallLogDao(database = get()) }

    single<InstallTaskDao> { provideInstallTaskDao(database = get()) }

    single<WorkflowDao> { provideWorkflowDao(database = get()) }

    single<WorkflowScheduleDao> { provideWorkflowScheduleDao(database = get()) }

    single<WorkflowScheduleStore> { provideWorkflowScheduleStore(store = get()) }

    single<PersonaBotDao> { providePersonaBotDao(database = get()) }

    single<BotMemberGroupDao> { provideBotMemberGroupDao(database = get()) }

    single<GroupChatDao> { provideGroupChatDao(database = get()) }

    single<BotScheduleDao> { provideBotScheduleDao(database = get()) }
    single<BotScheduleDeliveryDao> { provideBotScheduleDeliveryDao(database = get()) }

    single<BotAssignmentDao> { provideBotAssignmentDao(database = get()) }

    single<BotGroupSessionDao> { provideBotGroupSessionDao(database = get()) }

    single<RuntimeDao> { provideRuntimeDao(database = get()) }

    single<HarnessSessionDao> { provideHarnessSessionDao(database = get()) }

    single<AiModelDao> { provideAiModelDao(database = get()) }

    single<WorkspaceDao> { provideWorkspaceDao(database = get()) }

    single<TerminalSessionDao> { provideTerminalSessionDao(database = get()) }

    single<AgentContextDao> { provideAgentContextDao(database = get()) }

    single<AgentSubagentDao> { provideAgentSubagentDao(database = get()) }

    single<McpServerDao> { provideMcpServerDao(database = get()) }

    single<McpOAuthCredentialDao> { provideMcpOAuthCredentialDao(database = get()) }

    single<McpOAuthTransactionDao> { provideMcpOAuthTransactionDao(database = get()) }

    single<AgentSkillDao> { provideAgentSkillDao(database = get()) }

    single<StorageMountBindingDao> { provideStorageMountBindingDao(database = get()) }

    single<ToolSettingsDao> { provideToolSettingsDao(database = get()) }

    single<AgentApprovalDao> { provideAgentApprovalDao(database = get()) }

    single<QuickPhraseDao> { provideQuickPhraseDao(database = get()) }

    single<HarnessRuntimeDao> { provideHarnessRuntimeDao(database = get()) }

    single<AndroidAppDao> { provideAndroidAppDao(database = get()) }

    single<BuildScriptDao> { provideBuildScriptDao(database = get()) }

    single<AgentTaskDao> { provideAgentTaskDao(database = get()) }

    single<WorkspaceFileAccess> { provideWorkspaceFileAccess(pathManager = get()) }

    single<CheckpointStore> { provideCheckpointStore(pathManager = get()) }

    single<RuntimeManager> { provideRuntimeManager(impl = get()) }

    single<DependencyManager> { provideDependencyManager(impl = get()) }

    single<ProcessRegistry> { provideProcessRegistry(impl = get()) }

    single<OkHttpClient> { provideOkHttpClient(provider = get()) }

    single<HttpClient> { provideKtorHttpClient(provider = get(), okHttpClient = get()) }

    single<FileDownloader> { provideFileDownloader(impl = get()) }

    single<ShellExecutor> { provideShellExecutor(impl = get()) }

    single<PtyManager> { providePtyManager(impl = get()) }

    single<LinuxRuntime> { provideLinuxRuntime(impl = get()) }

    single<LocalServiceLauncher> { provideLocalServiceLauncher(impl = get()) }

    single<AgentForegroundLauncher> { provideAgentForegroundLauncher(impl = get()) }

    single<AgentMcpForegroundLauncher> { AgentMcpForegroundLauncherImpl(context = get()) }

    single<WorkflowScheduleDispatcher> { provideDispatcher(context = get()) }

    factory<WebChatAgentGateway> { get<TaiXuWebChatAgentGateway>() }

    single<RuntimeServiceController> { RuntimeServiceController(context = get()) }

    single<RuntimeLifecycleSupervisor> { RuntimeLifecycleSupervisor(context = get()) }

    factory<AgentForegroundLauncherImpl> { AgentForegroundLauncherImpl(context = get()) }

    single<AdbNotificationManager> {
        AdbNotificationManager(
            context = get(),
            embeddedAdbManager = get(),
            preferences = get(),
        )
    }

    single<TaiXuWebChatAgentGateway> {
        TaiXuWebChatAgentGateway(
            harnessLoop = get(),
            sessions = get(),
            models = get(),
            approvals = get(),
            tasks = get(),
        )
    }

    single<AppForegroundTracker> { AppForegroundTracker() }

    single<WorkflowApprovalNotifier> { WorkflowApprovalNotifier(runManager = get(), foregroundTracker = get()) }

    single<WorkflowRunUiController> {
        WorkflowRunUiController(
            runManager = get(),
            hud = get(),
            appContext = get(),
        )
    }

    factory<WorkManagerScheduleDispatcher> { WorkManagerScheduleDispatcher(context = get()) }

    single<Set<ToolRuntimeAdapter>>(named("toolAdapters")) {
        setOf(get<HelloToolInstaller>(), get<CodexToolInstaller>())
    }
}
