package top.wkbin.taixu.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import top.wkbin.taixu.core.database.task.AgentTaskEntity
import top.wkbin.taixu.core.database.task.AgentTaskDao

@Database(
    entities = [
        ToolEntity::class,
        InstallLogEntity::class,
        InstallTaskEntity::class,
        RuntimeEntity::class,
        RuntimeDependencyRefEntity::class,
        HarnessSessionEntity::class,
        AiModelEntity::class,
        WorkspaceEntity::class,
        TerminalSessionEntity::class,
        AgentMemoryEntity::class,
        AgentPlanEntity::class,
        AgentScratchpadEntity::class,
        AgentSubagentEntity::class,
        AgentSubagentSettingsEntity::class,
        McpServerEntity::class,
        McpOAuthCredentialEntity::class,
        McpOAuthTransactionEntity::class,
        AgentSkillEntity::class,
        StorageMountBindingEntity::class,
        ToolSettingsEntity::class,
        AgentApprovalRequestEntity::class,
        AgentApprovalSettingsEntity::class,
        QuickPhraseEntity::class,
        HarnessEntryEntity::class,
        HarnessLaneEntity::class,
        HarnessOperationEntity::class,
        HarnessQueueItemEntity::class,
        HarnessUsageEntity::class,
        HarnessLaneResultEntity::class,
        AndroidAppEntity::class,
        BuildScriptEntity::class,
        ProjectBuildScriptBindingEntity::class,
        AgentTaskEntity::class,
        WorkflowEntity::class,
        WorkflowExecutionLogEntity::class,
        WorkflowScheduleEntity::class,
        BackupRestoreReceipt::class,
        BotEntity::class,
        BotMemberGroupEntity::class,
        BotMemberGroupMemberEntity::class,
        GroupChatEntity::class,
        GroupMemberEntity::class,
        GroupMessageEntity::class,
        GroupSeenEntity::class,
        GroupMessageSeqEntity::class,
        GroupRoundEntity::class,
        GroupLateReplyEntity::class,
        BotScheduleEntity::class,
        BotAssignmentEntity::class,
        BotWorkBatchEntity::class,
        BotGroupSessionEntity::class,
        BotScheduleDeliveryEntity::class,
        GroupDeliveryEntity::class,
        BotAssignmentRequestEntity::class,
    ],
    version = 74,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun toolDao(): ToolDao
    abstract fun installLogDao(): InstallLogDao
    abstract fun installTaskDao(): InstallTaskDao
    abstract fun runtimeDao(): RuntimeDao
    abstract fun harnessSessionDao(): HarnessSessionDao
    abstract fun aiModelDao(): AiModelDao
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun terminalSessionDao(): TerminalSessionDao
    abstract fun agentContextDao(): AgentContextDao
    abstract fun agentSubagentDao(): AgentSubagentDao
    abstract fun mcpServerDao(): McpServerDao
    abstract fun mcpOAuthCredentialDao(): McpOAuthCredentialDao
    abstract fun mcpOAuthTransactionDao(): McpOAuthTransactionDao
    abstract fun agentSkillDao(): AgentSkillDao
    abstract fun storageMountBindingDao(): StorageMountBindingDao
    abstract fun toolSettingsDao(): ToolSettingsDao
    abstract fun agentApprovalDao(): AgentApprovalDao
    abstract fun quickPhraseDao(): QuickPhraseDao
    abstract fun harnessRuntimeDao(): HarnessRuntimeDao
    abstract fun androidAppDao(): AndroidAppDao
    abstract fun buildScriptDao(): BuildScriptDao
    abstract fun agentTaskDao(): AgentTaskDao
    abstract fun workflowDao(): WorkflowDao
    abstract fun workflowScheduleDao(): WorkflowScheduleDao
    abstract fun personaBotDao(): PersonaBotDao
    abstract fun botMemberGroupDao(): BotMemberGroupDao
    abstract fun groupChatDao(): GroupChatDao
    abstract fun botScheduleDao(): BotScheduleDao
    abstract fun botScheduleDeliveryDao(): BotScheduleDeliveryDao
    abstract fun botAssignmentDao(): BotAssignmentDao
    abstract fun botGroupSessionDao(): BotGroupSessionDao
}

