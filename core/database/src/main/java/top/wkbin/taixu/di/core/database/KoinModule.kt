package top.wkbin.taixu.di.core.database

import org.koin.dsl.module
import android.content.Context
import java.io.File
import top.wkbin.taixu.core.database.EncryptedRequestDiagnosticsRepository
import top.wkbin.taixu.core.database.RequestDiagnosticsRepository
import top.wkbin.taixu.core.security.SecretManager
import top.wkbin.taixu.core.database.AgencyAgentCatalogLoader
import top.wkbin.taixu.core.database.AgentApprovalRepository
import top.wkbin.taixu.core.database.AgentContextRepository
import top.wkbin.taixu.core.database.AgentSkillRepository
import top.wkbin.taixu.core.database.AgentSubagentRepository
import top.wkbin.taixu.core.database.AiModelRepository
import top.wkbin.taixu.core.database.AndroidAppRepository
import top.wkbin.taixu.core.database.repository.BotAssignmentRepository
import top.wkbin.taixu.core.database.repository.BotGroupSessionRepository
import top.wkbin.taixu.core.database.repository.BotMemberGroupRepository
import top.wkbin.taixu.core.database.BuildScriptRepository
import top.wkbin.taixu.core.database.DatabaseBackupRepository
import top.wkbin.taixu.core.database.RoomDatabaseBackupRepository
import top.wkbin.taixu.core.database.HarnessBlobStore
import top.wkbin.taixu.core.database.HarnessRuntimeRepository
import top.wkbin.taixu.core.database.HarnessSessionRepository
import top.wkbin.taixu.core.database.McpOAuthCredentialRepository
import top.wkbin.taixu.core.database.McpServerRepository
import top.wkbin.taixu.core.database.QuickPhraseRepository
import top.wkbin.taixu.core.database.RoomAgentContextRepository
import top.wkbin.taixu.core.database.RoomAiModelRepository
import top.wkbin.taixu.core.database.RoomAndroidAppRepository
import top.wkbin.taixu.core.database.RoomBuildScriptRepository
import top.wkbin.taixu.core.database.RoomBotAssignmentRepository
import top.wkbin.taixu.core.database.RoomBotGroupSessionRepository
import top.wkbin.taixu.core.database.RoomHarnessRuntimeRepository
import top.wkbin.taixu.core.database.RoomHarnessSessionRepository
import top.wkbin.taixu.core.database.RoomQuickPhraseRepository
import top.wkbin.taixu.core.database.RoomTerminalSessionRepository
import top.wkbin.taixu.core.database.RoomWorkflowRepository
import top.wkbin.taixu.core.database.RoomWorkflowScheduleStore
import top.wkbin.taixu.core.database.RoomWorkspaceRepository
import top.wkbin.taixu.core.database.StorageMountBindingRepository
import top.wkbin.taixu.core.database.TerminalSessionRepository
import top.wkbin.taixu.core.database.ToolSettingsRepository
import top.wkbin.taixu.core.database.WorkflowRepository
import top.wkbin.taixu.core.database.WorkspaceRepository
import top.wkbin.taixu.core.database.repository.BotScheduleRepository
import top.wkbin.taixu.core.database.repository.GroupChatRepository
import top.wkbin.taixu.core.database.repository.PersonaBotRepository
import top.wkbin.taixu.core.database.repository.RoomBotMemberGroupRepository
import top.wkbin.taixu.core.database.repository.RoomBotScheduleRepository
import top.wkbin.taixu.core.database.repository.RoomGroupChatRepository
import top.wkbin.taixu.core.database.repository.RoomPersonaBotRepository
import top.wkbin.taixu.core.database.task.AgentTaskRepository
import top.wkbin.taixu.core.database.task.RoomAgentTaskRepository

/** Dependency registrations owned by the core:database module. */
val coreDatabaseModule = module {
    single<RequestDiagnosticsRepository> {
        EncryptedRequestDiagnosticsRepository(
            location = { File(get<Context>().noBackupFilesDir, "request-diagnostics/requests.enc") },
            encrypt = { get<SecretManager>().encrypt(it) },
            decrypt = { get<SecretManager>().decrypt(it) },
        )
    }
    single<DatabaseBackupRepository> { RoomDatabaseBackupRepository(database = get()) }
    single<AgencyAgentCatalogLoader> { AgencyAgentCatalogLoader(context = get(), json = get()) }

    single<AgentApprovalRepository> { AgentApprovalRepository(dao = get()) }

    single<AgentSkillRepository> { AgentSkillRepository(dao = get()) }

    single<AgentSubagentRepository> { AgentSubagentRepository(dao = get(), catalogLoader = get()) }

    single<HarnessBlobStore> { HarnessBlobStore(context = get()) }

    single<RoomHarnessRuntimeRepository> { RoomHarnessRuntimeRepository(dao = get(), blobStore = get()) }

    single<McpOAuthCredentialRepository> {
        McpOAuthCredentialRepository(
            credentials = get(),
            transactions = get(),
            secretManager = get(),
        )
    }

    single<McpServerRepository> { McpServerRepository(dao = get(), secretManager = get()) }

    single<RoomBuildScriptRepository> { RoomBuildScriptRepository(dao = get()) }

    single<RoomAndroidAppRepository> { RoomAndroidAppRepository(dao = get()) }

    single<RoomAiModelRepository> { RoomAiModelRepository(dao = get()) }

    single<RoomHarnessSessionRepository> { RoomHarnessSessionRepository(dao = get()) }

    single<RoomWorkspaceRepository> { RoomWorkspaceRepository(dao = get()) }

    single<RoomTerminalSessionRepository> { RoomTerminalSessionRepository(dao = get()) }

    single<RoomAgentContextRepository> { RoomAgentContextRepository(dao = get()) }

    single<RoomQuickPhraseRepository> { RoomQuickPhraseRepository(dao = get()) }

    factory<AiModelRepository> { get<RoomAiModelRepository>() }

    factory<HarnessSessionRepository> { get<RoomHarnessSessionRepository>() }

    factory<WorkspaceRepository> { get<RoomWorkspaceRepository>() }

    single<RoomPersonaBotRepository> { RoomPersonaBotRepository(dao = get(), groupSessionDao = get()) }

    factory<PersonaBotRepository> { get<RoomPersonaBotRepository>() }

    single<RoomGroupChatRepository> { RoomGroupChatRepository(dao = get(), json = get()) }

    factory<GroupChatRepository> { get<RoomGroupChatRepository>() }

    single<RoomBotMemberGroupRepository> { RoomBotMemberGroupRepository(dao = get()) }

    factory<BotMemberGroupRepository> { get<RoomBotMemberGroupRepository>() }

    single<RoomBotScheduleRepository> { RoomBotScheduleRepository(dao = get(), deliveries = get()) }

    factory<BotScheduleRepository> { get<RoomBotScheduleRepository>() }

    single<RoomBotAssignmentRepository> { RoomBotAssignmentRepository(dao = get(), json = get()) }

    factory<BotAssignmentRepository> { get<RoomBotAssignmentRepository>() }

    single<RoomBotGroupSessionRepository> { RoomBotGroupSessionRepository(dao = get()) }

    factory<BotGroupSessionRepository> { get<RoomBotGroupSessionRepository>() }

    factory<TerminalSessionRepository> { get<RoomTerminalSessionRepository>() }

    factory<AgentContextRepository> { get<RoomAgentContextRepository>() }

    factory<AndroidAppRepository> { get<RoomAndroidAppRepository>() }

    factory<QuickPhraseRepository> { get<RoomQuickPhraseRepository>() }

    factory<HarnessRuntimeRepository> { get<RoomHarnessRuntimeRepository>() }

    factory<BuildScriptRepository> { get<RoomBuildScriptRepository>() }

    single<StorageMountBindingRepository> { StorageMountBindingRepository(dao = get()) }

    single<ToolSettingsRepository> { ToolSettingsRepository(dao = get()) }

    single<RoomWorkflowScheduleStore> { RoomWorkflowScheduleStore(dao = get()) }

    single<RoomWorkflowRepository> { RoomWorkflowRepository(dao = get(), json = get()) }

    factory<WorkflowRepository> { get<RoomWorkflowRepository>() }

    single<RoomAgentTaskRepository> { RoomAgentTaskRepository(dao = get()) }

    factory<AgentTaskRepository> { get<RoomAgentTaskRepository>() }
}
