package top.wkbin.taixu.core.database.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import top.wkbin.taixu.core.database.BotMemberGroupDao
import top.wkbin.taixu.core.database.BotMemberGroupEntity
import top.wkbin.taixu.core.database.BotMemberGroupMemberEntity
import top.wkbin.taixu.core.model.bot.BotMemberGroup

interface BotMemberGroupRepository {
    fun observeGroups(): Flow<List<BotMemberGroup>>
    suspend fun saveGroup(group: BotMemberGroup)
    suspend fun deleteGroup(id: String)
}

class RoomBotMemberGroupRepository(
    private val dao: BotMemberGroupDao,
) : BotMemberGroupRepository {
    override fun observeGroups(): Flow<List<BotMemberGroup>> =
        combine(dao.observeGroups(), dao.observeMemberships()) { groups, memberships ->
            val byGroup = memberships.groupBy { it.memberGroupId }
            groups.map { group ->
                BotMemberGroup(
                    id = group.id,
                    name = group.name,
                    createdAt = group.createdAt,
                    memberIds = byGroup[group.id].orEmpty()
                        .sortedBy { it.displayOrder }
                        .map { it.botId },
                )
            }
        }

    override suspend fun saveGroup(group: BotMemberGroup) {
        val now = System.currentTimeMillis()
        dao.saveWithMembers(
            group = BotMemberGroupEntity(group.id, group.name, group.createdAt),
            members = group.memberIds.mapIndexed { index, botId ->
                BotMemberGroupMemberEntity(group.id, botId, index, now)
            },
        )
    }

    override suspend fun deleteGroup(id: String) = dao.deleteById(id)
}
