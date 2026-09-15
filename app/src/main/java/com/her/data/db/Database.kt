package com.her.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.her.domain.AgentRunStatus
import com.her.domain.AgentRunType
import com.her.domain.CalendarSource
import com.her.domain.CommitmentStatus
import com.her.domain.ConfirmationKind
import com.her.domain.GoalStatus
import com.her.domain.GroceryStatus
import com.her.domain.MemorySource
import com.her.domain.MemoryStatus
import com.her.domain.MessageRole
import com.her.domain.MessageStatus
import com.her.domain.OpenLoopStatus
import com.her.domain.ProjectStatus
import com.her.domain.QueueStatus
import com.her.domain.ReminderRepeat
import com.her.domain.ReminderStatus
import com.her.domain.ReminderTrigger
import com.her.domain.SyncOpType
import com.her.domain.TaskStatus

class HerConverters {
    @TypeConverter fun messageRole(v: MessageRole?): String? = v?.name
    @TypeConverter fun toMessageRole(v: String?): MessageRole? = v?.let { MessageRole.valueOf(it) }
    @TypeConverter fun messageStatus(v: MessageStatus?): String? = v?.name
    @TypeConverter fun toMessageStatus(v: String?): MessageStatus? = v?.let { MessageStatus.valueOf(it) }
    @TypeConverter fun memorySource(v: MemorySource?): String? = v?.name
    @TypeConverter fun toMemorySource(v: String?): MemorySource? = v?.let { MemorySource.valueOf(it) }
    @TypeConverter fun memoryStatus(v: MemoryStatus?): String? = v?.name
    @TypeConverter fun toMemoryStatus(v: String?): MemoryStatus? = v?.let { MemoryStatus.valueOf(it) }
    @TypeConverter fun projectStatus(v: ProjectStatus?): String? = v?.name
    @TypeConverter fun toProjectStatus(v: String?): ProjectStatus? = v?.let { ProjectStatus.valueOf(it) }
    @TypeConverter fun goalStatus(v: GoalStatus?): String? = v?.name
    @TypeConverter fun toGoalStatus(v: String?): GoalStatus? = v?.let { GoalStatus.valueOf(it) }
    @TypeConverter fun taskStatus(v: TaskStatus?): String? = v?.name
    @TypeConverter fun toTaskStatus(v: String?): TaskStatus? = v?.let { TaskStatus.valueOf(it) }
    @TypeConverter fun commitmentStatus(v: CommitmentStatus?): String? = v?.name
    @TypeConverter fun toCommitmentStatus(v: String?): CommitmentStatus? = v?.let { CommitmentStatus.valueOf(it) }
    @TypeConverter fun openLoopStatus(v: OpenLoopStatus?): String? = v?.name
    @TypeConverter fun toOpenLoopStatus(v: String?): OpenLoopStatus? = v?.let { OpenLoopStatus.valueOf(it) }
    @TypeConverter fun groceryStatus(v: GroceryStatus?): String? = v?.name
    @TypeConverter fun toGroceryStatus(v: String?): GroceryStatus? = v?.let { GroceryStatus.valueOf(it) }
    @TypeConverter fun queueStatus(v: QueueStatus?): String? = v?.name
    @TypeConverter fun toQueueStatus(v: String?): QueueStatus? = v?.let { QueueStatus.valueOf(it) }
    @TypeConverter fun calendarSource(v: CalendarSource?): String? = v?.name
    @TypeConverter fun toCalendarSource(v: String?): CalendarSource? = v?.let { CalendarSource.valueOf(it) }
    @TypeConverter fun agentRunType(v: AgentRunType?): String? = v?.name
    @TypeConverter fun toAgentRunType(v: String?): AgentRunType? = v?.let { AgentRunType.valueOf(it) }
    @TypeConverter fun agentRunStatus(v: AgentRunStatus?): String? = v?.name
    @TypeConverter fun toAgentRunStatus(v: String?): AgentRunStatus? = v?.let { AgentRunStatus.valueOf(it) }
    @TypeConverter fun syncOpType(v: SyncOpType?): String? = v?.name
    @TypeConverter fun toSyncOpType(v: String?): SyncOpType? = v?.let { SyncOpType.valueOf(it) }
    @TypeConverter fun confirmationKind(v: ConfirmationKind?): String? = v?.name
    @TypeConverter fun toConfirmationKind(v: String?): ConfirmationKind? = v?.let { ConfirmationKind.valueOf(it) }
    @TypeConverter fun reminderTrigger(v: ReminderTrigger?): String? = v?.name
    @TypeConverter fun toReminderTrigger(v: String?): ReminderTrigger? = v?.let { ReminderTrigger.valueOf(it) }
    @TypeConverter fun reminderStatus(v: ReminderStatus?): String? = v?.name
    @TypeConverter fun toReminderStatus(v: String?): ReminderStatus? = v?.let { ReminderStatus.valueOf(it) }
    @TypeConverter fun reminderRepeat(v: ReminderRepeat?): String? = v?.name
    @TypeConverter fun toReminderRepeat(v: String?): ReminderRepeat? = v?.let { ReminderRepeat.valueOf(it) }
}

@Database(
    entities = [
        ChatMessageEntity::class,
        ChatMessageFtsEntity::class,
        ShortTermMemoryEntity::class,
        ShortTermMemoryFtsEntity::class,
        LongTermMemoryEntity::class,
        LongTermMemoryFtsEntity::class,
        UserProfileEntity::class,
        UserUnderstandingEntity::class,
        PersonEntity::class,
        ProjectEntity::class,
        GoalEntity::class,
        TaskEntity::class,
        CommitmentEntity::class,
        OpenLoopEntity::class,
        RoutineEntity::class,
        GroceryEntity::class,
        ImportantDateEntity::class,
        RecurringResponsibilityEntity::class,
        CalendarEventEntity::class,
        MemoryRelationshipEntity::class,
        AgentStateEntity::class,
        AgentQueueEntity::class,
        ActivityLogEntity::class,
        AgentRunEntity::class,
        SyncOpEntity::class,
        SyncCursorEntity::class,
        ApiUsageEntity::class,
        DebugEventEntity::class,
        PendingConfirmationEntity::class,
        MemoryEmbeddingEntity::class,
        ReminderEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
@TypeConverters(HerConverters::class)
abstract class HerDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun memoryDao(): MemoryDao
    abstract fun profileDao(): ProfileDao
    abstract fun userUnderstandingDao(): UserUnderstandingDao
    abstract fun personDao(): PersonDao
    abstract fun projectDao(): ProjectDao
    abstract fun goalDao(): GoalDao
    abstract fun taskDao(): TaskDao
    abstract fun commitmentDao(): CommitmentDao
    abstract fun openLoopDao(): OpenLoopDao
    abstract fun routineDao(): RoutineDao
    abstract fun groceryDao(): GroceryDao
    abstract fun importantDateDao(): ImportantDateDao
    abstract fun responsibilityDao(): ResponsibilityDao
    abstract fun calendarDao(): CalendarDao
    abstract fun relationshipDao(): RelationshipDao
    abstract fun agentDao(): AgentDao
    abstract fun logDao(): LogDao
    abstract fun syncDao(): SyncDao
    abstract fun confirmationDao(): ConfirmationDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun reminderDao(): ReminderDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `user_understandings` (
                        `id` TEXT NOT NULL,
                        `facet` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `confidence` REAL NOT NULL,
                        `importance` REAL NOT NULL,
                        `source` TEXT NOT NULL,
                        `sourceMessageId` TEXT,
                        `status` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `version` INTEGER NOT NULL,
                        `deletedAt` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_embeddings` (
                        `memoryId` TEXT NOT NULL,
                        `model` TEXT NOT NULL,
                        `contentHash` TEXT NOT NULL,
                        `dimensions` INTEGER NOT NULL,
                        `vector` BLOB NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`memoryId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reminders` (
                        `id` TEXT NOT NULL,
                        `message` TEXT NOT NULL,
                        `trigger` TEXT NOT NULL,
                        `fireAt` INTEGER,
                        `repeat` TEXT NOT NULL,
                        `personId` TEXT,
                        `place` TEXT,
                        `onlyIfEntityType` TEXT,
                        `onlyIfEntityId` TEXT,
                        `status` TEXT NOT NULL,
                        `firedAt` INTEGER,
                        `sourceMessageId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `version` INTEGER NOT NULL,
                        `deletedAt` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
