package top.wkbin.taixu.di

import android.content.Context
import androidx.room.Room
import java.io.File
import top.wkbin.taixu.core.database.AppDatabase
import top.wkbin.taixu.core.database.MIGRATION_27_28
import top.wkbin.taixu.core.database.MIGRATION_28_29
import top.wkbin.taixu.core.database.MIGRATION_30_31
import top.wkbin.taixu.core.database.MIGRATION_31_32
import top.wkbin.taixu.core.database.MIGRATION_33_34
import top.wkbin.taixu.core.database.MIGRATION_34_35
import top.wkbin.taixu.core.database.MIGRATION_35_36
import top.wkbin.taixu.core.database.MIGRATION_36_37
import top.wkbin.taixu.core.database.MIGRATION_37_38
import top.wkbin.taixu.core.database.MIGRATION_38_39
import top.wkbin.taixu.core.database.MIGRATION_39_40
import top.wkbin.taixu.core.database.MIGRATION_40_41
import top.wkbin.taixu.core.database.MIGRATION_41_42
import top.wkbin.taixu.core.database.MIGRATION_42_43
import top.wkbin.taixu.core.database.MIGRATION_43_44
import top.wkbin.taixu.core.database.MIGRATION_44_45
import top.wkbin.taixu.core.database.MIGRATION_45_46
import top.wkbin.taixu.core.database.MIGRATION_46_47
import top.wkbin.taixu.core.database.MIGRATION_47_48
import top.wkbin.taixu.core.database.MIGRATION_48_49
import top.wkbin.taixu.core.database.MIGRATION_49_50
import top.wkbin.taixu.core.database.MIGRATION_50_51
import top.wkbin.taixu.core.database.MIGRATION_51_52
import top.wkbin.taixu.core.database.MIGRATION_52_53
import top.wkbin.taixu.core.database.MIGRATION_53_54
import top.wkbin.taixu.core.database.MIGRATION_54_55
import top.wkbin.taixu.core.database.MIGRATION_55_56
import top.wkbin.taixu.core.database.MIGRATION_56_57
import top.wkbin.taixu.core.database.MIGRATION_57_58
import top.wkbin.taixu.core.database.MIGRATION_58_59
import top.wkbin.taixu.core.database.MIGRATION_62_63
import top.wkbin.taixu.core.database.MIGRATION_63_64
import top.wkbin.taixu.core.database.MIGRATION_64_65
import top.wkbin.taixu.core.database.MIGRATION_65_66
import top.wkbin.taixu.core.database.MIGRATION_66_67
import top.wkbin.taixu.core.database.MIGRATION_67_68
import top.wkbin.taixu.core.database.MIGRATION_68_69
import top.wkbin.taixu.core.database.MIGRATION_69_70
import top.wkbin.taixu.core.database.MIGRATION_71_72
import top.wkbin.taixu.core.database.MIGRATION_73_74
import top.wkbin.taixu.core.database.MIGRATION_72_73
import top.wkbin.taixu.core.database.MIGRATION_70_71
import top.wkbin.taixu.core.database.MIGRATION_61_62
import top.wkbin.taixu.core.database.MIGRATION_60_61
import top.wkbin.taixu.core.database.MIGRATION_59_60
import top.wkbin.taixu.core.database.BotAssignmentDao
import top.wkbin.taixu.core.database.BotGroupSessionDao
import top.wkbin.taixu.core.database.BotMemberGroupDao
import top.wkbin.taixu.core.database.BotScheduleDeliveryDao
import top.wkbin.taixu.core.database.BotScheduleDao
import top.wkbin.taixu.core.database.GroupChatDao
import top.wkbin.taixu.core.database.PersonaBotDao
import top.wkbin.taixu.core.database.WorkflowDao
import top.wkbin.taixu.core.database.WorkflowScheduleDao
import top.wkbin.taixu.core.database.BuildScriptDao
import top.wkbin.taixu.core.database.task.AgentTaskDao
import top.wkbin.taixu.core.database.ToolDao
import top.wkbin.taixu.core.database.InstallLogDao
import top.wkbin.taixu.core.database.InstallTaskDao
import top.wkbin.taixu.core.database.RuntimeDao
import top.wkbin.taixu.core.database.HarnessSessionDao
import top.wkbin.taixu.core.database.AiModelDao
import top.wkbin.taixu.core.database.WorkspaceDao
import top.wkbin.taixu.core.database.TerminalSessionDao
import top.wkbin.taixu.core.database.AgentSubagentDao
import top.wkbin.taixu.core.database.AgentSkillDao
import top.wkbin.taixu.core.database.McpServerDao
import top.wkbin.taixu.core.database.McpOAuthCredentialDao
import top.wkbin.taixu.core.database.McpOAuthTransactionDao
import top.wkbin.taixu.core.database.StorageMountBindingDao
import top.wkbin.taixu.core.database.ToolSettingsDao
import top.wkbin.taixu.core.database.AgentApprovalDao
import top.wkbin.taixu.core.database.QuickPhraseDao
import top.wkbin.taixu.core.database.HarnessRuntimeDao
import top.wkbin.taixu.core.database.AndroidAppDao
import top.wkbin.taixu.harness.WorkspaceFileAccess
import top.wkbin.taixu.harness.checkpoint.CheckpointStore
import top.wkbin.taixu.harness.checkpoint.FileCheckpointPersistence
import top.wkbin.taixu.core.database.AgentContextDao
import top.wkbin.taixu.core.database.RoomWorkflowScheduleStore
import top.wkbin.taixu.core.database.WorkflowScheduleStore
import top.wkbin.taixu.runtime.RuntimePathManager
import top.wkbin.taixu.core.tools.RuntimeManager
import top.wkbin.taixu.core.tools.RuntimeManagerImpl
import top.wkbin.taixu.core.tools.DependencyManager
import top.wkbin.taixu.core.tools.DependencyManagerImpl
import top.wkbin.taixu.runtime.shell.ProcessRegistry
import top.wkbin.taixu.runtime.shell.ProcessRegistryImpl
import top.wkbin.taixu.core.network.HttpClientProvider
import top.wkbin.taixu.core.network.FileDownloader
import top.wkbin.taixu.core.network.ResumableFileDownloader
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.LinuxRuntimeImpl
import top.wkbin.taixu.runtime.shell.ProcessShellExecutor
import top.wkbin.taixu.runtime.shell.ShellExecutor
import top.wkbin.taixu.runtime.pty.PtyManager
import top.wkbin.taixu.runtime.pty.NativePtyManager
import top.wkbin.taixu.runtime.service.LocalServiceLauncher
import top.wkbin.taixu.runtime.service.LocalServiceLauncherImpl
import top.wkbin.taixu.service.AgentForegroundLauncherImpl
import top.wkbin.taixu.harness.AgentForegroundLauncher
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient


object AppModule {

    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // 请求/存储时省略 null 字段：未配置的 reasoning_content 不会发给非推理模型
        explicitNulls = false
    }

    fun provideDatabase(context: Context): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, "taixu.db")
            .addMigrations(MIGRATION_27_28, MIGRATION_28_29, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38, MIGRATION_38_39, MIGRATION_39_40, MIGRATION_40_41, MIGRATION_41_42, MIGRATION_42_43, MIGRATION_43_44, MIGRATION_44_45, MIGRATION_45_46, MIGRATION_46_47, MIGRATION_47_48, MIGRATION_48_49, MIGRATION_49_50, MIGRATION_50_51, MIGRATION_51_52, MIGRATION_52_53, MIGRATION_53_54, MIGRATION_54_55, MIGRATION_55_56, MIGRATION_56_57, MIGRATION_57_58, MIGRATION_58_59, MIGRATION_59_60, MIGRATION_60_61, MIGRATION_61_62, MIGRATION_62_63, MIGRATION_63_64, MIGRATION_64_65, MIGRATION_65_66, MIGRATION_66_67, MIGRATION_67_68, MIGRATION_68_69, MIGRATION_69_70, MIGRATION_70_71, MIGRATION_71_72, MIGRATION_72_73, MIGRATION_73_74)
            .build()
    }

    fun provideToolDao(database: AppDatabase): ToolDao = database.toolDao()

    fun provideInstallLogDao(database: AppDatabase): InstallLogDao = database.installLogDao()

    fun provideInstallTaskDao(database: AppDatabase): InstallTaskDao = database.installTaskDao()

    fun provideWorkflowDao(database: AppDatabase): WorkflowDao = database.workflowDao()

    fun provideWorkflowScheduleDao(database: AppDatabase): WorkflowScheduleDao = database.workflowScheduleDao()

    fun provideWorkflowScheduleStore(store: RoomWorkflowScheduleStore): WorkflowScheduleStore = store

    fun providePersonaBotDao(database: AppDatabase): PersonaBotDao = database.personaBotDao()

    fun provideBotMemberGroupDao(database: AppDatabase): BotMemberGroupDao = database.botMemberGroupDao()

    fun provideGroupChatDao(database: AppDatabase): GroupChatDao = database.groupChatDao()

    fun provideBotScheduleDeliveryDao(database: AppDatabase): BotScheduleDeliveryDao = database.botScheduleDeliveryDao()

    fun provideBotScheduleDao(database: AppDatabase): BotScheduleDao = database.botScheduleDao()

    fun provideBotAssignmentDao(database: AppDatabase): BotAssignmentDao = database.botAssignmentDao()

    fun provideBotGroupSessionDao(database: AppDatabase): BotGroupSessionDao = database.botGroupSessionDao()

    fun provideRuntimeDao(database: AppDatabase): RuntimeDao = database.runtimeDao()

    fun provideHarnessSessionDao(database: AppDatabase): HarnessSessionDao = database.harnessSessionDao()

    fun provideAiModelDao(database: AppDatabase): AiModelDao = database.aiModelDao()

    fun provideWorkspaceDao(database: AppDatabase): WorkspaceDao = database.workspaceDao()

    fun provideTerminalSessionDao(database: AppDatabase): TerminalSessionDao = database.terminalSessionDao()

    fun provideAgentContextDao(database: AppDatabase): AgentContextDao = database.agentContextDao()

    fun provideAgentSubagentDao(database: AppDatabase): AgentSubagentDao = database.agentSubagentDao()

    fun provideMcpServerDao(database: AppDatabase): McpServerDao = database.mcpServerDao()

    fun provideMcpOAuthCredentialDao(database: AppDatabase): McpOAuthCredentialDao = database.mcpOAuthCredentialDao()

    fun provideMcpOAuthTransactionDao(database: AppDatabase): McpOAuthTransactionDao = database.mcpOAuthTransactionDao()

    fun provideAgentSkillDao(database: AppDatabase): AgentSkillDao = database.agentSkillDao()

    fun provideStorageMountBindingDao(database: AppDatabase): StorageMountBindingDao = database.storageMountBindingDao()

    fun provideToolSettingsDao(database: AppDatabase): ToolSettingsDao = database.toolSettingsDao()

    fun provideAgentApprovalDao(database: AppDatabase): AgentApprovalDao = database.agentApprovalDao()

    fun provideQuickPhraseDao(database: AppDatabase): QuickPhraseDao = database.quickPhraseDao()

    fun provideHarnessRuntimeDao(database: AppDatabase): HarnessRuntimeDao = database.harnessRuntimeDao()

    fun provideAndroidAppDao(database: AppDatabase): AndroidAppDao = database.androidAppDao()

    fun provideBuildScriptDao(database: AppDatabase): BuildScriptDao = database.buildScriptDao()

    fun provideAgentTaskDao(database: AppDatabase): AgentTaskDao = database.agentTaskDao()

    fun provideWorkspaceFileAccess(pathManager: RuntimePathManager): WorkspaceFileAccess =
        WorkspaceFileAccess(pathManager.workspaceDir)

    /** checkpoint 快照落盘到应用私有目录（linux-runtime/checkpoints/<sessionId>/），模型不可见。 */
    fun provideCheckpointStore(
        pathManager: RuntimePathManager,
    ): CheckpointStore =
        CheckpointStore().apply {
            persistence = FileCheckpointPersistence(
                File(pathManager.baseDir, "checkpoints"),
            )
        }

    fun provideRuntimeManager(impl: RuntimeManagerImpl): RuntimeManager = impl

    fun provideDependencyManager(impl: DependencyManagerImpl): DependencyManager = impl

    fun provideProcessRegistry(impl: ProcessRegistryImpl): ProcessRegistry = impl

    fun provideOkHttpClient(provider: HttpClientProvider): OkHttpClient = provider.create()

    fun provideKtorHttpClient(
        provider: HttpClientProvider,
        okHttpClient: OkHttpClient,
    ): HttpClient = provider.createKtorClient(okHttpClient)

    fun provideFileDownloader(impl: ResumableFileDownloader): FileDownloader = impl

    fun provideShellExecutor(impl: ProcessShellExecutor): ShellExecutor = impl

    fun providePtyManager(impl: NativePtyManager): PtyManager = impl

    fun provideLinuxRuntime(impl: LinuxRuntimeImpl): LinuxRuntime = impl

    fun provideLocalServiceLauncher(impl: LocalServiceLauncherImpl): LocalServiceLauncher = impl

    fun provideAgentForegroundLauncher(impl: AgentForegroundLauncherImpl): AgentForegroundLauncher = impl
}


