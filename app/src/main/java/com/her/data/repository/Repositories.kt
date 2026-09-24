package com.her.data.repository

import com.her.core.QuietHours
import com.her.core.ftsQuery
import com.her.core.lexicalOverlap
import com.her.core.newId
import com.her.data.db.ActivityLogEntity
import com.her.data.db.AgentQueueEntity
import com.her.data.db.AgentRunEntity
import com.her.data.db.AgentStateEntity
import com.her.data.db.ApiUsageEntity
import com.her.data.db.CalendarEventEntity
import com.her.data.db.ChatMessageEntity
import com.her.data.db.CommitmentEntity
import com.her.data.db.DebugEventEntity
import com.her.data.db.GoalEntity
import com.her.data.db.GroceryEntity
import com.her.data.db.HerDatabase
import com.her.data.db.ImportantDateEntity
import com.her.data.db.LongTermMemoryEntity
import com.her.data.db.MemoryRelationshipEntity
import com.her.data.db.OpenLoopEntity
import com.her.data.db.PendingConfirmationEntity
import com.her.data.db.PersonEntity
import com.her.data.db.ProjectEntity
import com.her.data.db.RecurringResponsibilityEntity
import com.her.data.db.ReminderEntity
import com.her.data.db.RoutineEntity
import com.her.data.db.ShortTermMemoryEntity
import com.her.data.db.SyncOpEntity
import com.her.data.db.TaskEntity
import com.her.data.db.UserProfileEntity
import com.her.data.db.UserUnderstandingEntity
import com.her.data.secure.AppSettingsStore
import com.her.domain.ActivityLogEntry
import com.her.domain.AgentQueueItem
import com.her.domain.AgentRun
import com.her.domain.AgentRunStatus
import com.her.domain.AgentRunType
import com.her.domain.AgentStateEntry
import com.her.domain.ApiUsageDay
import com.her.domain.CalendarEvent
import com.her.domain.ChatMessage
import com.her.domain.Commitment
import com.her.domain.ConfirmationKind
import com.her.domain.DebugEvent
import com.her.domain.Goal
import com.her.domain.GroceryItem
import com.her.domain.GroceryStatus
import com.her.domain.ImportantDate
import com.her.domain.LongTermMemory
import com.her.domain.MemoryRelationship
import com.her.domain.MemoryStatus
import com.her.domain.MessageRole
import com.her.domain.MessageStatus
import com.her.domain.OpenLoop
import com.her.domain.PendingConfirmation
import com.her.domain.Person
import com.her.domain.Project
import com.her.domain.RecurringResponsibility
import com.her.domain.Reminder
import com.her.domain.Routine
import com.her.domain.ShortTermMemory
import com.her.domain.SyncOp
import com.her.domain.SyncOpType
import com.her.domain.TaskItem
import com.her.domain.UserProfile
import com.her.domain.UserUnderstanding
import java.time.LocalDate
import com.her.core.SystemTimeProvider
import com.her.core.TimeProvider
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import com.her.domain.CalendarSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

class HerRepository(
    val db: HerDatabase,
    private val settings: AppSettingsStore,
    val clock: TimeProvider = SystemTimeProvider(),
) {
    private val chatDao get() = db.chatDao()
    private val memoryDao get() = db.memoryDao()
    val deviceId: String get() = settings.read().deviceId

    private fun nowMillis(): Long = clock.nowMillis()

    /** The profile timezone, so "today" means the user's day, not the device's. */
    suspend fun profileZone(): ZoneId =
        runCatching { ZoneId.of(getProfile().timezone ?: clock.zoneId().id) }.getOrDefault(clock.zoneId())

    suspend fun now(): ZonedDateTime = Instant.ofEpochMilli(nowMillis()).atZone(profileZone())

    suspend fun today(): LocalDate = now().toLocalDate()

    /** Quiet hours are read on the user's clock, not the device's. */
    suspend fun inQuietHours(hours: QuietHours): Boolean = hours.contains(now().toLocalTime())

    fun newChatMessage(
        role: MessageRole,
        content: String,
        status: MessageStatus,
        metadataJson: String? = null,
    ): ChatMessage {
        val now = nowMillis()
        return ChatMessage(
            id = newId(),
            role = role,
            content = content,
            createdAt = now,
            updatedAt = now,
            deviceId = deviceId,
            version = 1,
            deletedAt = null,
            status = status,
            metadataJson = metadataJson,
        )
    }

    fun observeLatestAssistant(): Flow<ChatMessage?> =
        chatDao.observeLatestByRole(MessageRole.ASSISTANT).map { it?.toDomain() }

    fun observePendingCount(): Flow<Int> = chatDao.observePendingCount()

    suspend fun pendingUserMessages(): List<ChatMessage> = chatDao.pending().map { it.toDomain() }

    suspend fun markSent(ids: List<String>) {
        if (ids.isEmpty()) return
        val at = nowMillis()
        chatDao.markStatus(ids, MessageStatus.SENT, at)
        ids.forEach { id ->
            chatDao.get(id)?.toDomain()?.let { msg ->
                enqueue("chat_messages", msg.id, SyncOpType.UPSERT, msg.toJson())
            }
        }
    }

    suspend fun recentMessages(limit: Int): List<ChatMessage> =
        chatDao.recent(limit).reversed().map { it.toDomain() }

    suspend fun getMessage(id: String): ChatMessage? = chatDao.get(id)?.toDomain()

    suspend fun searchChat(query: String, limit: Int): List<ChatMessage> {
        val match = ftsQuery(query) ?: return emptyList()
        val hits = runCatching { chatDao.search(match, limit * 4) }.getOrDefault(emptyList()).map { it.toDomain() }
        // Any word may match, so messages sharing more of the query come first; recency breaks ties.
        return hits
            .sortedWith(compareByDescending<ChatMessage> { lexicalOverlap(query, it.content) }.thenByDescending { it.createdAt })
            .take(limit)
    }

    suspend fun saveMessage(message: ChatMessage, enqueueSync: Boolean = true) {
        chatDao.upsert(message.toEntity())
        if (enqueueSync) enqueue("chat_messages", message.id, SyncOpType.UPSERT, message.toJson())
    }

    suspend fun upsertShort(memory: ShortTermMemory) {
        memoryDao.upsertShort(memory.toEntity())
        enqueue("short_term_memories", memory.id, SyncOpType.UPSERT, memory.toJson())
    }

    suspend fun upsertLong(memory: LongTermMemory) {
        memoryDao.upsertLong(memory.toEntity())
        enqueue("long_term_memories", memory.id, SyncOpType.UPSERT, memory.toJson())
    }

    suspend fun getShort(id: String) = memoryDao.getShort(id)?.toDomain()
    suspend fun getLong(id: String) = memoryDao.getLong(id)?.toDomain()
    suspend fun activeShort(): List<ShortTermMemory> = memoryDao.activeShort(nowMillis()).map { it.toDomain() }
    suspend fun activeLong(): List<LongTermMemory> = memoryDao.longByStatus(MemoryStatus.ACTIVE).map { it.toDomain() }
    suspend fun allLong(): List<LongTermMemory> = memoryDao.allLong().map { it.toDomain() }

    suspend fun searchShort(query: String, limit: Int): List<ShortTermMemory> {
        val match = ftsQuery(query) ?: return emptyList()
        return runCatching { memoryDao.searchShort(match, nowMillis(), limit) }.getOrDefault(emptyList()).map { it.toDomain() }
    }

    suspend fun searchLong(query: String, limit: Int): List<LongTermMemory> {
        val match = ftsQuery(query) ?: return emptyList()
        return runCatching { memoryDao.searchLong(match, limit) }.getOrDefault(emptyList()).map { it.toDomain() }
    }

    suspend fun deleteShort(id: String) {
        memoryDao.deleteShort(id, nowMillis())
        enqueue("short_term_memories", id, SyncOpType.DELETE, JSONObject().put("id", id).toString())
    }

    suspend fun deleteLong(id: String) {
        memoryDao.deleteLong(id, nowMillis())
        enqueue("long_term_memories", id, SyncOpType.DELETE, JSONObject().put("id", id).toString())
    }

    suspend fun getProfile(): UserProfile {
        val existing = db.profileDao().get(UserProfile.PROFILE_ID)
        if (existing != null) return existing.toDomain()
        val created = UserProfile(
            userName = null,
            assistantName = null,
            timezone = ZoneId.systemDefault().id,
            preferredLanguage = null,
            country = null,
            typicalWakeTime = null,
            typicalSleepTime = null,
            occupationOrStudyContext = null,
            firstUseDate = nowMillis(),
            updatedAt = nowMillis(),
            deviceId = deviceId,
            version = 1,
        )
        db.profileDao().upsert(created.toEntity())
        return created
    }

    suspend fun saveProfile(profile: UserProfile) {
        db.profileDao().upsert(profile.toEntity())
        enqueue("user_profile", profile.id, SyncOpType.UPSERT, profile.toJson())
    }

    fun observeProfile(): Flow<UserProfile> = flow {
        emit(getProfile())
        emitAll(
            db.profileDao().observe(UserProfile.PROFILE_ID).map { entity ->
                entity?.toDomain() ?: getProfile()
            },
        )
    }

    suspend fun activeUserUnderstandings(): List<UserUnderstanding> =
        db.userUnderstandingDao().byStatus(MemoryStatus.ACTIVE).map { it.toDomain() }

    suspend fun getUserUnderstanding(id: String) = db.userUnderstandingDao().get(id)?.toDomain()

    suspend fun activeUserUnderstandingByFacet(facet: String) =
        db.userUnderstandingDao().byFacet(facet, MemoryStatus.ACTIVE)?.toDomain()

    suspend fun saveUserUnderstanding(item: UserUnderstanding) {
        db.userUnderstandingDao().upsert(item.toEntity())
        enqueue("user_understandings", item.id, SyncOpType.UPSERT, item.toJson())
    }

    suspend fun people() = db.personDao().allActive().map { it.toDomain() }
    suspend fun getPerson(id: String) = db.personDao().get(id)?.toDomain()
    suspend fun searchPeople(q: String) = db.personDao().search("%$q%").map { it.toDomain() }
    suspend fun savePerson(person: Person) {
        db.personDao().upsert(person.toEntity())
        enqueue("people", person.id, SyncOpType.UPSERT, person.toJson())
    }

    suspend fun projects() = db.projectDao().allActive().map { it.toDomain() }
    suspend fun getProject(id: String) = db.projectDao().get(id)?.toDomain()
    suspend fun searchProjects(q: String) = db.projectDao().search("%$q%").map { it.toDomain() }
    suspend fun saveProject(project: Project) {
        db.projectDao().upsert(project.toEntity())
        enqueue("projects", project.id, SyncOpType.UPSERT, project.toJson())
    }

    suspend fun goals() = db.goalDao().allActive().map { it.toDomain() }
    suspend fun getGoal(id: String) = db.goalDao().get(id)?.toDomain()
    suspend fun saveGoal(goal: Goal) {
        db.goalDao().upsert(goal.toEntity())
        enqueue("goals", goal.id, SyncOpType.UPSERT, goal.toJson())
    }

    suspend fun tasks() = db.taskDao().allActive().map { it.toDomain() }
    suspend fun getTask(id: String) = db.taskDao().get(id)?.toDomain()
    suspend fun saveTask(task: TaskItem) {
        db.taskDao().upsert(task.toEntity())
        enqueue("tasks", task.id, SyncOpType.UPSERT, task.toJson())
    }

    suspend fun commitments() = db.commitmentDao().allActive().map { it.toDomain() }
    suspend fun getCommitment(id: String) = db.commitmentDao().get(id)?.toDomain()
    suspend fun saveCommitment(item: Commitment) {
        db.commitmentDao().upsert(item.toEntity())
        enqueue("commitments", item.id, SyncOpType.UPSERT, item.toJson())
    }

    suspend fun openLoops() = db.openLoopDao().allActive().map { it.toDomain() }
    suspend fun getOpenLoop(id: String) = db.openLoopDao().get(id)?.toDomain()
    suspend fun saveOpenLoop(item: OpenLoop) {
        db.openLoopDao().upsert(item.toEntity())
        enqueue("open_loops", item.id, SyncOpType.UPSERT, item.toJson())
    }

    suspend fun routines() = db.routineDao().allActive().map { it.toDomain() }
    suspend fun getRoutine(id: String) = db.routineDao().get(id)?.toDomain()
    suspend fun saveRoutine(item: Routine) {
        db.routineDao().upsert(item.toEntity())
        enqueue("routines", item.id, SyncOpType.UPSERT, item.toJson())
    }

    suspend fun groceries() = db.groceryDao().allActive().map { it.toDomain() }
    suspend fun getGrocery(id: String) = db.groceryDao().get(id)?.toDomain()
    suspend fun saveGrocery(item: GroceryItem) {
        db.groceryDao().upsert(item.toEntity())
        enqueue("groceries", item.id, SyncOpType.UPSERT, item.toJson())
    }

    suspend fun markGroceriesPurchased(exceptNames: List<String>) {
        val except = exceptNames.map { it.trim().lowercase() }.toSet()
        groceries().filter { it.status == GroceryStatus.ACTIVE }.forEach { item ->
            if (item.name.trim().lowercase() !in except) {
                saveGrocery(item.copy(status = GroceryStatus.PURCHASED, updatedAt = nowMillis(), version = item.version + 1))
            }
        }
    }

    suspend fun importantDates() = db.importantDateDao().allActive().map { it.toDomain() }
    suspend fun getImportantDate(id: String) = db.importantDateDao().get(id)?.toDomain()
    suspend fun saveImportantDate(item: ImportantDate) {
        db.importantDateDao().upsert(item.toEntity())
        enqueue("important_dates", item.id, SyncOpType.UPSERT, item.toJson())
    }

    suspend fun responsibilities() = db.responsibilityDao().allActive().map { it.toDomain() }
    suspend fun getResponsibility(id: String) = db.responsibilityDao().get(id)?.toDomain()
    suspend fun saveResponsibility(item: RecurringResponsibility) {
        db.responsibilityDao().upsert(item.toEntity())
        enqueue("recurring_responsibilities", item.id, SyncOpType.UPSERT, item.toJson())
    }

    suspend fun calendarInRange(from: Long, to: Long) = db.calendarDao().inRange(from, to).map { it.toDomain() }
    suspend fun getCalendarEvent(id: String) = db.calendarDao().get(id)?.toDomain()
    suspend fun getCalendarByExternalId(externalId: String) = db.calendarDao().getByExternalId(externalId)?.toDomain()
    // Device and Google calendar rows are local mirrors, refreshed on every read; only internal events sync.
    suspend fun saveCalendarEvent(item: CalendarEvent) {
        db.calendarDao().upsert(item.toEntity())
        if (item.source == CalendarSource.INTERNAL) {
            enqueue("calendar_events", item.id, SyncOpType.UPSERT, item.toJson())
        }
    }

    suspend fun deleteCalendarEvent(id: String) {
        val source = db.calendarDao().get(id)?.source
        db.calendarDao().softDelete(id, nowMillis())
        if (source == null || source == CalendarSource.INTERNAL) {
            enqueue("calendar_events", id, SyncOpType.DELETE, JSONObject().put("id", id).toString())
        }
    }

    suspend fun reminders() = db.reminderDao().allActive().map { it.toDomain() }
    suspend fun getReminder(id: String) = db.reminderDao().get(id)?.toDomain()

    /** Clock reminders this device armed that are due by now; other devices fire their own. */
    suspend fun dueTimeReminders(): List<Reminder> = db.reminderDao().dueTime(nowMillis(), deviceId).map { it.toDomain() }

    suspend fun saveReminder(item: Reminder) {
        db.reminderDao().upsert(item.toEntity())
        enqueue("reminders", item.id, SyncOpType.UPSERT, item.toJson())
    }

    suspend fun relationships() = db.relationshipDao().allActive().map { it.toDomain() }
    suspend fun relationshipsFor(type: String, id: String) = db.relationshipDao().forEntity(type, id).map { it.toDomain() }
    suspend fun saveRelationship(item: MemoryRelationship) {
        db.relationshipDao().upsert(item.toEntity())
        enqueue("memory_relationships", item.id, SyncOpType.UPSERT, item.toJson())
    }

    suspend fun agentState() = db.agentDao().stateEntries().map { it.toDomain() }
    suspend fun agentQueue() = db.agentDao().queueItems().map { it.toDomain() }
    suspend fun getAgentState(id: String) = db.agentDao().getState(id)?.toDomain()
    suspend fun getAgentQueue(id: String) = db.agentDao().getQueue(id)?.toDomain()
    suspend fun saveAgentState(item: AgentStateEntry) {
        db.agentDao().upsertState(item.toEntity())
        enqueue("agent_state", item.id, SyncOpType.UPSERT, item.toJson())
    }

    suspend fun saveAgentQueue(item: AgentQueueItem) {
        db.agentDao().upsertQueue(item.toEntity())
        enqueue("agent_queue", item.id, SyncOpType.UPSERT, item.toJson())
    }

    suspend fun logActivity(category: String, message: String, details: String? = null) {
        db.logDao().insertActivity(
            ActivityLogEntity(newId(), nowMillis(), category, message, details),
        )
    }

    suspend fun logDebug(kind: String, payload: String) {
        db.logDao().insertDebug(DebugEventEntity(newId(), nowMillis(), kind, payload.take(12_000)))
    }

    suspend fun saveRun(run: AgentRun) {
        db.logDao().upsertRun(run.toEntity())
    }

    fun observeActivity(limit: Int = 80) = db.logDao().observeActivity(limit).map { it.map { e -> e.toDomain() } }
    fun observeDebug(limit: Int = 40) = db.logDao().observeDebug(limit).map { it.map { e -> e.toDomain() } }
    fun observeRuns(limit: Int = 40) = db.logDao().observeRuns(limit).map { it.map { e -> e.toDomain() } }

    suspend fun recordUsage(
        type: AgentRunType,
        inputTokens: Int,
        outputTokens: Int,
        latencyMs: Long,
        error: Boolean,
    ) {
        val day = today().toString()
        val current = db.logDao().usage(day) ?: ApiUsageEntity(day, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val next = current.copy(
            requests = current.requests + 1,
            inputTokens = current.inputTokens + inputTokens,
            outputTokens = current.outputTokens + outputTokens,
            hourlyCalls = current.hourlyCalls + if (type == AgentRunType.HOURLY || type == AgentRunType.CATCH_UP) 1 else 0,
            nightlyCalls = current.nightlyCalls + if (type == AgentRunType.NIGHTLY) 1 else 0,
            briefingCalls = current.briefingCalls + if (type == AgentRunType.BRIEFING) 1 else 0,
            chatCalls = current.chatCalls + if (type == AgentRunType.CHAT) 1 else 0,
            totalLatencyMs = current.totalLatencyMs + latencyMs,
            errors = current.errors + if (error) 1 else 0,
        )
        db.logDao().upsertUsage(next)
    }

    fun observeUsageToday(): Flow<ApiUsageDay?> = flow {
        emitAll(db.logDao().observeUsage(today().toString()).map { it?.toDomain() })
    }

    suspend fun saveConfirmation(item: PendingConfirmation) {
        db.confirmationDao().upsert(
            PendingConfirmationEntity(item.id, item.kind, item.summary, item.payloadJson, item.createdAt),
        )
    }

    suspend fun getConfirmation(id: String): PendingConfirmation? =
        db.confirmationDao().get(id)?.let {
            PendingConfirmation(it.id, it.kind, it.summary, it.payloadJson, it.createdAt)
        }

    suspend fun deleteConfirmation(id: String) = db.confirmationDao().delete(id)

    suspend fun pendingSyncOps(): List<SyncOp> = db.syncDao().pending().map { it.toDomain() }
    suspend fun markUploaded(ids: List<String>) {
        if (ids.isNotEmpty()) db.syncDao().markUploaded(ids)
    }

    suspend fun lastSeq(): Long = db.syncDao().lastSeq(deviceId)

    /** Applies changes that came from another device without queueing them to be uploaded again. */
    suspend fun <T> applyingRemote(block: suspend () -> T): T = withContext(RemoteChanges()) { block() }

    private suspend fun enqueue(type: String, id: String, op: SyncOpType, payload: String) {
        if (currentCoroutineContext()[RemoteChanges] != null) return
        val seq = db.syncDao().lastSeq(deviceId) + 1
        db.syncDao().upsertOp(
            SyncOpEntity(
                id = newId(),
                entityType = type,
                entityId = id,
                opType = op,
                payloadJson = payload,
                createdAt = nowMillis(),
                deviceId = deviceId,
                seq = seq,
                uploaded = false,
            ),
        )
    }
}

/** Marks writes made while applying another device's changes, so they are not uploaded back. */
private class RemoteChanges : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RemoteChanges>
}

fun ChatMessageEntity.toDomain() = ChatMessage(id, role, content, createdAt, updatedAt, deviceId, version, deletedAt, status, metadataJson)
fun ChatMessage.toEntity() = ChatMessageEntity(id, role, content, createdAt, updatedAt, deviceId, version, deletedAt, status, metadataJson)
fun ChatMessage.toJson() = JSONObject()
    .put("id", id).put("role", role.name).put("content", content).put("createdAt", createdAt)
    .put("updatedAt", updatedAt).put("deviceId", deviceId).put("version", version)
    .put("deletedAt", deletedAt).put("status", status.name).put("metadataJson", metadataJson).toString()

fun ShortTermMemoryEntity.toDomain() = ShortTermMemory(id, content, type, confidence, importance, createdAt, updatedAt, expiresAt, sourceMessageId, source, metadataJson, deviceId, version, deletedAt)
fun ShortTermMemory.toEntity() = ShortTermMemoryEntity(id, content, type, confidence, importance, createdAt, updatedAt, expiresAt, sourceMessageId, source, metadataJson, deviceId, version, deletedAt)
fun ShortTermMemory.toJson() = JSONObject()
    .put("id", id).put("content", content).put("type", type).put("confidence", confidence)
    .put("importance", importance).put("createdAt", createdAt).put("updatedAt", updatedAt)
    .put("expiresAt", expiresAt).put("sourceMessageId", sourceMessageId).put("source", source.name)
    .put("metadataJson", metadataJson).put("deviceId", deviceId).put("version", version)
    .put("deletedAt", deletedAt).toString()

fun LongTermMemoryEntity.toDomain() = LongTermMemory(id, content, category, confidence, importance, createdAt, updatedAt, lastConfirmedAt, source, sourceMessageId, derivedFromJson, validFrom, validUntil, status, metadataJson, deviceId, version, deletedAt)
fun LongTermMemory.toEntity() = LongTermMemoryEntity(id, content, category, confidence, importance, createdAt, updatedAt, lastConfirmedAt, source, sourceMessageId, derivedFromJson, validFrom, validUntil, status, metadataJson, deviceId, version, deletedAt)
fun LongTermMemory.toJson() = JSONObject()
    .put("id", id).put("content", content).put("category", category).put("confidence", confidence)
    .put("importance", importance).put("createdAt", createdAt).put("updatedAt", updatedAt)
    .put("lastConfirmedAt", lastConfirmedAt).put("source", source.name).put("sourceMessageId", sourceMessageId)
    .put("derivedFromJson", derivedFromJson).put("validFrom", validFrom).put("validUntil", validUntil)
    .put("status", status.name).put("metadataJson", metadataJson).put("deviceId", deviceId)
    .put("version", version).put("deletedAt", deletedAt).toString()

fun UserProfileEntity.toDomain() = UserProfile(id, userName, assistantName, timezone, preferredLanguage, country, typicalWakeTime, typicalSleepTime, occupationOrStudyContext, firstUseDate, updatedAt, deviceId, version)
fun UserProfile.toEntity() = UserProfileEntity(id, userName, assistantName, timezone, preferredLanguage, country, typicalWakeTime, typicalSleepTime, occupationOrStudyContext, firstUseDate, updatedAt, deviceId, version)
fun UserProfile.toJson() = JSONObject()
    .put("id", id).put("userName", userName).put("assistantName", assistantName).put("timezone", timezone)
    .put("preferredLanguage", preferredLanguage).put("country", country).put("typicalWakeTime", typicalWakeTime)
    .put("typicalSleepTime", typicalSleepTime).put("occupationOrStudyContext", occupationOrStudyContext)
    .put("firstUseDate", firstUseDate).put("updatedAt", updatedAt).put("deviceId", deviceId).put("version", version)
    .toString()

fun UserUnderstandingEntity.toDomain() = UserUnderstanding(
    id, facet, content, confidence, importance, source, sourceMessageId, status,
    createdAt, updatedAt, deviceId, version, deletedAt,
)
fun UserUnderstanding.toEntity() = UserUnderstandingEntity(
    id, facet, content, confidence, importance, source, sourceMessageId, status,
    createdAt, updatedAt, deviceId, version, deletedAt,
)
fun UserUnderstanding.toJson() = JSONObject()
    .put("id", id).put("facet", facet).put("content", content).put("confidence", confidence)
    .put("importance", importance).put("source", source.name).put("sourceMessageId", sourceMessageId)
    .put("status", status.name).put("createdAt", createdAt).put("updatedAt", updatedAt)
    .put("deviceId", deviceId).put("version", version).put("deletedAt", deletedAt)
    .toString()

fun PersonEntity.toDomain() = Person(id, name, relationship, birthday, importantNotes, preferences, lastMentioned, confidence, createdAt, updatedAt, deviceId, version, deletedAt)
fun Person.toEntity() = PersonEntity(id, name, relationship, birthday, importantNotes, preferences, lastMentioned, confidence, createdAt, updatedAt, deviceId, version, deletedAt)
fun Person.toJson() = JSONObject()
    .put("id", id).put("name", name).put("relationship", relationship).put("birthday", birthday)
    .put("importantNotes", importantNotes).put("preferences", preferences).put("lastMentioned", lastMentioned)
    .put("confidence", confidence).put("createdAt", createdAt).put("updatedAt", updatedAt)
    .put("deviceId", deviceId).put("version", version).put("deletedAt", deletedAt).toString()

fun ProjectEntity.toDomain() = Project(id, name, description, status, summary, importance, createdAt, updatedAt, deviceId, version, deletedAt)
fun Project.toEntity() = ProjectEntity(id, name, description, status, summary, importance, createdAt, updatedAt, deviceId, version, deletedAt)
fun Project.toJson() = JSONObject()
    .put("id", id).put("name", name).put("description", description).put("status", status.name)
    .put("summary", summary).put("importance", importance).put("createdAt", createdAt)
    .put("updatedAt", updatedAt).put("deviceId", deviceId).put("version", version).put("deletedAt", deletedAt)
    .toString()

fun GoalEntity.toDomain() = Goal(id, title, description, status, priority, targetDate, relatedProjectId, progressSummary, createdAt, updatedAt, deviceId, version, deletedAt)
fun Goal.toEntity() = GoalEntity(id, title, description, status, priority, targetDate, relatedProjectId, progressSummary, createdAt, updatedAt, deviceId, version, deletedAt)
fun Goal.toJson() = JSONObject()
    .put("id", id).put("title", title).put("description", description).put("status", status.name)
    .put("priority", priority).put("targetDate", targetDate).put("relatedProjectId", relatedProjectId)
    .put("progressSummary", progressSummary).put("createdAt", createdAt).put("updatedAt", updatedAt)
    .put("deviceId", deviceId).put("version", version).put("deletedAt", deletedAt).toString()

fun TaskEntity.toDomain() = TaskItem(id, title, description, status, dueAt, relatedProjectId, relatedGoalId, createdAt, updatedAt, deviceId, version, deletedAt)
fun TaskItem.toEntity() = TaskEntity(id, title, description, status, dueAt, relatedProjectId, relatedGoalId, createdAt, updatedAt, deviceId, version, deletedAt)
fun TaskItem.toJson() = JSONObject()
    .put("id", id).put("title", title).put("description", description).put("status", status.name)
    .put("dueAt", dueAt).put("relatedProjectId", relatedProjectId).put("relatedGoalId", relatedGoalId)
    .put("createdAt", createdAt).put("updatedAt", updatedAt).put("deviceId", deviceId)
    .put("version", version).put("deletedAt", deletedAt).toString()

fun CommitmentEntity.toDomain() = Commitment(id, title, description, status, dueAt, promisedTo, relatedTaskId, createdAt, updatedAt, deviceId, version, deletedAt)
fun Commitment.toEntity() = CommitmentEntity(id, title, description, status, dueAt, promisedTo, relatedTaskId, createdAt, updatedAt, deviceId, version, deletedAt)
fun Commitment.toJson() = JSONObject()
    .put("id", id).put("title", title).put("description", description).put("status", status.name)
    .put("dueAt", dueAt).put("promisedTo", promisedTo).put("relatedTaskId", relatedTaskId)
    .put("createdAt", createdAt).put("updatedAt", updatedAt).put("deviceId", deviceId)
    .put("version", version).put("deletedAt", deletedAt).toString()

fun OpenLoopEntity.toDomain() = OpenLoop(id, description, status, importance, confidence, relatedEntityType, relatedEntityId, createdAt, updatedAt, deviceId, version, deletedAt)
fun OpenLoop.toEntity() = OpenLoopEntity(id, description, status, importance, confidence, relatedEntityType, relatedEntityId, createdAt, updatedAt, deviceId, version, deletedAt)
fun OpenLoop.toJson() = JSONObject()
    .put("id", id).put("description", description).put("status", status.name).put("importance", importance)
    .put("confidence", confidence).put("relatedEntityType", relatedEntityType).put("relatedEntityId", relatedEntityId)
    .put("createdAt", createdAt).put("updatedAt", updatedAt).put("deviceId", deviceId)
    .put("version", version).put("deletedAt", deletedAt).toString()

fun RoutineEntity.toDomain() = Routine(id, title, description, schedule, confidence, source, createdAt, updatedAt, deviceId, version, deletedAt)
fun Routine.toEntity() = RoutineEntity(id, title, description, schedule, confidence, source, createdAt, updatedAt, deviceId, version, deletedAt)
fun Routine.toJson() = JSONObject()
    .put("id", id).put("title", title).put("description", description).put("schedule", schedule)
    .put("confidence", confidence).put("source", source.name).put("createdAt", createdAt)
    .put("updatedAt", updatedAt).put("deviceId", deviceId).put("version", version).put("deletedAt", deletedAt)
    .toString()

fun GroceryEntity.toDomain() = GroceryItem(id, name, quantity, category, status, reason, recurrenceScore, notes, store, createdAt, updatedAt, deviceId, version, deletedAt)
fun GroceryItem.toEntity() = GroceryEntity(id, name, quantity, category, status, reason, recurrenceScore, notes, store, createdAt, updatedAt, deviceId, version, deletedAt)
fun GroceryItem.toJson() = JSONObject()
    .put("id", id).put("name", name).put("quantity", quantity).put("category", category)
    .put("status", status.name).put("reason", reason).put("recurrenceScore", recurrenceScore)
    .put("notes", notes).put("store", store).put("createdAt", createdAt).put("updatedAt", updatedAt)
    .put("deviceId", deviceId).put("version", version).put("deletedAt", deletedAt).toString()

fun ImportantDateEntity.toDomain() = ImportantDate(id, title, dateIso, recurrence, relatedPersonId, notes, importance, createdAt, updatedAt, deviceId, version, deletedAt)
fun ImportantDate.toEntity() = ImportantDateEntity(id, title, dateIso, recurrence, relatedPersonId, notes, importance, createdAt, updatedAt, deviceId, version, deletedAt)
fun ImportantDate.toJson() = JSONObject()
    .put("id", id).put("title", title).put("dateIso", dateIso).put("recurrence", recurrence)
    .put("relatedPersonId", relatedPersonId).put("notes", notes).put("importance", importance)
    .put("createdAt", createdAt).put("updatedAt", updatedAt).put("deviceId", deviceId)
    .put("version", version).put("deletedAt", deletedAt).toString()

fun RecurringResponsibilityEntity.toDomain() = RecurringResponsibility(id, title, cadence, nextDueAt, lastCompletedAt, notes, createdAt, updatedAt, deviceId, version, deletedAt)
fun RecurringResponsibility.toEntity() = RecurringResponsibilityEntity(id, title, cadence, nextDueAt, lastCompletedAt, notes, createdAt, updatedAt, deviceId, version, deletedAt)
fun RecurringResponsibility.toJson() = JSONObject()
    .put("id", id).put("title", title).put("cadence", cadence).put("nextDueAt", nextDueAt)
    .put("lastCompletedAt", lastCompletedAt).put("notes", notes).put("createdAt", createdAt)
    .put("updatedAt", updatedAt).put("deviceId", deviceId).put("version", version).put("deletedAt", deletedAt)
    .toString()

fun CalendarEventEntity.toDomain() = CalendarEvent(id, title, startAt, endAt, location, notes, externalId, source, calendarId, createdAt, updatedAt, deviceId, version, deletedAt)
fun CalendarEvent.toEntity() = CalendarEventEntity(id, title, startAt, endAt, location, notes, externalId, source, calendarId, createdAt, updatedAt, deviceId, version, deletedAt)
fun CalendarEvent.toJson() = JSONObject()
    .put("id", id).put("title", title).put("startAt", startAt).put("endAt", endAt).put("location", location)
    .put("notes", notes).put("externalId", externalId).put("source", source.name).put("calendarId", calendarId)
    .put("createdAt", createdAt).put("updatedAt", updatedAt).put("deviceId", deviceId)
    .put("version", version).put("deletedAt", deletedAt).toString()

fun ReminderEntity.toDomain() = Reminder(id, message, trigger, fireAt, repeat, personId, place, onlyIfEntityType, onlyIfEntityId, status, firedAt, sourceMessageId, createdAt, updatedAt, deviceId, version, deletedAt)
fun Reminder.toEntity() = ReminderEntity(id, message, trigger, fireAt, repeat, personId, place, onlyIfEntityType, onlyIfEntityId, status, firedAt, sourceMessageId, createdAt, updatedAt, deviceId, version, deletedAt)
fun Reminder.toJson() = JSONObject()
    .put("id", id).put("message", message).put("trigger", trigger.name).put("fireAt", fireAt)
    .put("repeat", repeat.name).put("personId", personId).put("place", place)
    .put("onlyIfEntityType", onlyIfEntityType).put("onlyIfEntityId", onlyIfEntityId)
    .put("status", status.name).put("firedAt", firedAt).put("sourceMessageId", sourceMessageId)
    .put("createdAt", createdAt).put("updatedAt", updatedAt).put("deviceId", deviceId)
    .put("version", version).put("deletedAt", deletedAt).toString()

fun MemoryRelationshipEntity.toDomain() = MemoryRelationship(id, sourceType, sourceId, relationshipType, targetType, targetId, createdAt, updatedAt, deviceId, version, deletedAt)
fun MemoryRelationship.toEntity() = MemoryRelationshipEntity(id, sourceType, sourceId, relationshipType, targetType, targetId, createdAt, updatedAt, deviceId, version, deletedAt)
fun MemoryRelationship.toJson() = JSONObject()
    .put("id", id).put("sourceType", sourceType).put("sourceId", sourceId).put("relationshipType", relationshipType)
    .put("targetType", targetType).put("targetId", targetId).put("createdAt", createdAt)
    .put("updatedAt", updatedAt).put("deviceId", deviceId).put("version", version).put("deletedAt", deletedAt)
    .toString()

fun AgentStateEntity.toDomain() = AgentStateEntry(id, kind, content, confidence, createdAt, updatedAt, deviceId, version, deletedAt)
fun AgentStateEntry.toEntity() = AgentStateEntity(id, kind, content, confidence, createdAt, updatedAt, deviceId, version, deletedAt)
fun AgentStateEntry.toJson() = JSONObject()
    .put("id", id).put("kind", kind).put("content", content).put("confidence", confidence)
    .put("createdAt", createdAt).put("updatedAt", updatedAt).put("deviceId", deviceId)
    .put("version", version).put("deletedAt", deletedAt).toString()

fun AgentQueueEntity.toDomain() = AgentQueueItem(id, description, status, priority, dueAt, relatedEntityType, relatedEntityId, createdAt, updatedAt, deviceId, version, deletedAt)
fun AgentQueueItem.toEntity() = AgentQueueEntity(id, description, status, priority, dueAt, relatedEntityType, relatedEntityId, createdAt, updatedAt, deviceId, version, deletedAt)
fun AgentQueueItem.toJson() = JSONObject()
    .put("id", id).put("description", description).put("status", status.name).put("priority", priority)
    .put("dueAt", dueAt).put("relatedEntityType", relatedEntityType).put("relatedEntityId", relatedEntityId)
    .put("createdAt", createdAt).put("updatedAt", updatedAt).put("deviceId", deviceId)
    .put("version", version).put("deletedAt", deletedAt).toString()

fun ActivityLogEntity.toDomain() = ActivityLogEntry(id, createdAt, category, message, detailsJson)
fun DebugEventEntity.toDomain() = DebugEvent(id, createdAt, kind, payload)
fun AgentRunEntity.toDomain() = AgentRun(id, type, startedAt, finishedAt, callsUsed, status, error, notes)
fun AgentRun.toEntity() = AgentRunEntity(id, type, startedAt, finishedAt, callsUsed, status, error, notes)
fun ApiUsageEntity.toDomain() = ApiUsageDay(day, requests, inputTokens, outputTokens, hourlyCalls, nightlyCalls, briefingCalls, chatCalls, totalLatencyMs, errors)
fun SyncOpEntity.toDomain() = SyncOp(id, entityType, entityId, opType, payloadJson, createdAt, deviceId, seq, uploaded)
