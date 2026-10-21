package top.wkbin.taixu.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "bot_member_groups")
data class BotMemberGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "bot_member_group_members",
    primaryKeys = ["memberGroupId", "botId"],
    foreignKeys = [
        ForeignKey(
            entity = BotMemberGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["memberGroupId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = BotEntity::class,
            parentColumns = ["id"],
            childColumns = ["botId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["memberGroupId"]), Index(value = ["botId"])],
)
data class BotMemberGroupMemberEntity(
    val memberGroupId: String,
    val botId: String,
    val displayOrder: Int,
    val addedAt: Long,
)

@Dao
interface BotMemberGroupDao {
    @Query("SELECT * FROM bot_member_groups ORDER BY createdAt ASC")
    fun observeGroups(): Flow<List<BotMemberGroupEntity>>

    @Query("SELECT * FROM bot_member_group_members ORDER BY displayOrder ASC")
    fun observeMemberships(): Flow<List<BotMemberGroupMemberEntity>>

    @Query("SELECT * FROM bot_member_group_members WHERE memberGroupId = :memberGroupId ORDER BY displayOrder ASC")
    suspend fun listMembers(memberGroupId: String): List<BotMemberGroupMemberEntity>

    @Upsert
    suspend fun upsertGroup(group: BotMemberGroupEntity)

    @Query("DELETE FROM bot_member_groups WHERE id = :id")
    suspend fun deleteById(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addMember(member: BotMemberGroupMemberEntity)

    @Query("DELETE FROM bot_member_group_members WHERE memberGroupId = :memberGroupId")
    suspend fun deleteMembers(memberGroupId: String)

    @Transaction
    suspend fun saveWithMembers(group: BotMemberGroupEntity, members: List<BotMemberGroupMemberEntity>) {
        upsertGroup(group)
        deleteMembers(group.id)
        members.forEach { addMember(it) }
    }
}
