package com.her

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.agent.tools.ToolRegistry
import com.her.companion.checkinBlock
import com.her.companion.companionContext
import com.her.core.FakeClock
import com.her.data.calendar.CalendarDataSource
import com.her.data.db.HerDatabase
import com.her.data.drive.MergeEngine
import com.her.data.drive.SyncCodec
import com.her.data.remote.WebSearchClient
import com.her.data.repository.HerRepository
import com.her.data.retrieval.HybridRanker
import com.her.data.secure.AppSettingsStore
import com.her.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class CompanionTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
    private val settings = AppSettingsStore(context, "companion_${System.nanoTime()}")
    private val clock = FakeClock(Instant.parse("2026-10-17T10:00:00Z").toEpochMilli(), tickMs = 0)
    private val repo = HerRepository(db, settings, clock)
    private val tools = ToolRegistry(repo, HybridRanker(repo), settings, CalendarDataSource(context), WebSearchClient())
    @After fun close() = db.close()
    private suspend fun call(name: String, args: JSONObject): JSONObject {
        val result = tools.execute(name, args.toString())
        assertTrue(result.payloadJson, result.ok)
        return JSONObject(result.payloadJson)
    }
    private suspend fun evidence(text: String = "Please help with my job search"): JSONObject {
        val message = repo.newChatMessage(MessageRole.USER, text, MessageStatus.SENT)
        repo.saveMessage(message)
        return JSONObject().put("sourceMessageId", message.id).put("evidence", text).put("basis", "EXPLICIT")
    }
    private suspend fun thread(id: String = "job") = call("save_life_thread", evidence().put("id", id).put("title", "Job search").put("summary", "Waiting for interview feedback"))
    private suspend fun prefs(vararg ids: String): JSONObject {
        repo.saveProfile(repo.getProfile().copy(timezone = "UTC"))
        return call("save_checkin_preferences", evidence("Check in about these topics at ten, once a day").put("id", "preferences")
        .put("localTime", "10:00").put("topicIds", JSONArray(ids.toList())).put("maxPerDay", 3))
    }
    private suspend fun nudge(id: String, phase: String = "CHECKIN") = call("send_companion_checkin", JSONObject().put("id", id).put("phase", phase).put("content", "Want to pick this up?"))
    private fun tomorrow() { clock.advance(24 * 3_600_000L) }

    @Test fun longGapRetrievalCorrectionClosureAndReopening() = runBlocking {
        val id = thread().getString("id")
        call("save_thread_development", evidence("I chose the smaller company").put("id", "decision").put("threadId", id).put("summary", "Chose smaller company"))
        clock.advance(90 * 86_400_000L)
        assertTrue(companionContext(repo, settings, "job search").joinToString().contains(id))
        val read = call("get_life_context", JSONObject().put("id", id))
        assertEquals(2, read.getJSONArray("records").length())
        call("save_life_thread", evidence("Actually I chose the larger company, and this is settled").put("id", id).put("expectedVersion", 1).put("summary", "Chose larger company").put("status", "CLOSED"))
        assertEquals("CLOSED", repo.getCompanionRecord(id)!!.data().getString("status"))
        assertEquals("inactive thread", run {
            prefs(id); tomorrow(); checkinBlock(repo, settings, repo.getCompanionRecord(id)!!, "CHECKIN")
        })
        call("save_life_thread", evidence("Reopen my search").put("id", id).put("expectedVersion", 2).put("status", "ACTIVE"))
        assertEquals("ACTIVE", repo.getCompanionRecord(id)!!.data().getString("status"))
    }

    @Test fun idempotentWritesRejectStaleEditsInvalidTypesAndInventedEvidence() = runBlocking {
        val args = evidence().put("id", "job").put("title", "Search").put("summary", "Considering roles")
        val first = call("save_life_thread", args)
        assertEquals(1L, call("save_life_thread", args).getLong("version"))
        assertFalse(tools.execute("save_life_thread", args.put("summary", "Changed").toString()).ok)
        assertFalse(tools.execute("save_life_thread", args.put("expectedVersion", 1).put("evidence", "invented").toString()).ok)
        assertFalse(tools.execute("save_checkin_preferences", evidence().put("id", "preferences").put("socialEnabled", "true").toString()).ok)
        assertFalse(tools.execute("save_life_thread", evidence().put("id", "bad").put("title", "X").put("summary", "Y").put("relatedIds", JSONArray().put("missing")).toString()).ok)
        assertEquals(1L, repo.getCompanionRecord(first.getString("id"))!!.version)
    }

    @Test fun expectedResponseUsesOpenLoopAndResolutionDoesNotCompleteUserTask() = runBlocking {
        val person = call("update_person", JSONObject().put("name", "Sara")).getString("id")
        val loop = call("create_open_loop", JSONObject().put("description", "Sara owes feedback")).getString("id")
        val task = call("create_task", JSONObject().put("title", "Review Sara's feedback")).getString("id")
        val waiting = call("save_waiting_on", evidence("Sara will send feedback tomorrow").put("id", "feedback").put("openLoopId", loop).put("personId", person)
            .put("expectedOutcome", "Feedback").put("followUpAt", clock.peek()).put("followUpEnabled", true).put("relatedIds", JSONArray().put(task))).getString("id")
        prefs(waiting); tomorrow()
        assertTrue(nudge(waiting).getBoolean("queued"))
        assertFalse(nudge(waiting).getBoolean("queued"))
        call("save_waiting_on", evidence("Sara replied with the feedback").put("id", waiting).put("expectedVersion", 1).put("status", "RESOLVED"))
        assertEquals(OpenLoopStatus.CLOSED, repo.getOpenLoop(loop)!!.status)
        assertEquals(TaskStatus.OPEN, repo.getTask(task)!!.status)
        tomorrow(); assertFalse(nudge(waiting).getBoolean("queued"))
    }

    @Test fun checkinsRespectOptInQuietPauseRecentInteractionDismissalAndResume() = runBlocking {
        val id = thread().getString("id")
        assertFalse(nudge(id).getBoolean("queued"))
        prefs(id)
        assertFalse(nudge(id).getBoolean("queued")) // recent chat
        clock.set(Instant.parse("2026-10-18T23:45:00Z").toEpochMilli())
        assertEquals("quiet hours", nudge(id).getString("reason"))
        clock.set(Instant.parse("2026-10-19T10:00:00Z").toEpochMilli())
        call("save_checkin_preferences", evidence("Pause check-ins").put("id", "preferences").put("expectedVersion", 1).put("status", "PAUSED"))
        tomorrow(); assertFalse(nudge(id).getBoolean("queued"))
        call("save_checkin_preferences", evidence("Resume check-ins").put("id", "preferences").put("expectedVersion", 2).put("status", "ACTIVE"))
        tomorrow(); assertTrue(nudge(id).getBoolean("queued"))
        call("dismiss_companion_checkin", JSONObject().put("id", id))
        tomorrow(); assertEquals("dismissed or forgotten", nudge(id).getString("reason"))
        call("save_life_thread", evidence("Check in on my search again").put("id", id).put("expectedVersion", 2).put("dismissed", false))
        tomorrow(); assertTrue(nudge(id).getBoolean("queued"))
        assertFalse(nudge("companion:preferences").getBoolean("queued"))
    }

    @Test fun rescheduledAndCancelledMeetingCannotSendStaleOrDuplicatePreparation() = runBlocking {
        val start = clock.peek() + 25 * 3_600_000L
        val event = CalendarEvent("event", "Interview", start, start + 3_600_000L, null, null, null, CalendarSource.INTERNAL, null, clock.peek(), clock.peek(), repo.deviceId, 1, null)
        repo.saveCalendarEvent(event)
        val prep = call("save_event_preparation", evidence("Help me prepare for the interview").put("id", "event").put("eventId", event.id).put("summary", "Ask about team expectations").put("postEventEnabled", true)).getString("id")
        prefs(prep); tomorrow()
        assertTrue(nudge(prep, "PREPARE").getBoolean("queued"))
        assertFalse(nudge(prep, "PREPARE").getBoolean("queued"))
        repo.saveCalendarEvent(event.copy(startAt = start + 86_400_000L, endAt = start + 90_000_000L, version = 2))
        tomorrow()
        assertEquals("event changed; refresh preparation", nudge(prep, "PREPARE").getString("reason"))
        // Same preparation id, new event revision; earlier delivery doesn't suppress the new day.
        call("save_event_preparation", evidence("The interview moved to tomorrow").put("id", prep).put("expectedVersion", 1).put("summary", "Ask about team expectations"))
        clock.advance(2 * 3_600_000L)
        assertTrue(nudge(prep, "OUTCOME").getBoolean("queued"))
        repo.deleteCalendarEvent(event.id)
        assertFalse(nudge(prep, "PREPARE").getBoolean("queued"))
    }

    @Test fun unfinishedSessionResumesDraftWithoutSendingOrCompletingObligation() = runBlocking {
        val task = call("create_task", JSONObject().put("title", "Reply to Sara")).getString("id")
        val id = call("save_work_session", evidence("I have ten minutes and low energy; help draft a reply").put("id", "reply").put("obligationId", task)
            .put("summary", "Prepared opening").put("minutesAvailable", 10).put("energy", "low").put("status", "DRAFT")
            .put("draft", "Hi Sara, thanks for the update.").put("nextStep", "Add availability")).getString("id")
        clock.advance(30 * 86_400_000L)
        val saved = call("get_life_context", JSONObject().put("id", id)).getJSONArray("records").getJSONObject(0)
        assertTrue(saved.getString("content").contains("Add availability"))
        assertEquals(TaskStatus.OPEN, repo.getTask(task)!!.status)
        assertFalse(tools.execute("save_work_session", evidence("Looks good").put("id", id).put("expectedVersion", 1).put("status", "SENT").toString()).ok)
        assertTrue(repo.recentMessages(20).all { it.role == MessageRole.USER })
    }

    @Test fun forgettingCascadesAndSyncTombstoneWinsAgainstNewerStaleCopy() = runBlocking {
        val id = thread().getString("id")
        call("save_thread_development", evidence("An interview is scheduled").put("id", "interview").put("threadId", id).put("summary", "Interview scheduled"))
        val original = repo.getCompanionRecord(id)!!
        val db2 = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
        val repo2 = HerRepository(db2, AppSettingsStore(context, "other_${System.nanoTime()}"), clock)
        repo2.applyingRemote { assertTrue(SyncCodec.persist(repo2, "companion_records", JSONObject(original.toJson()))) }
        assertEquals(original, repo2.getCompanionRecord(id))
        assertTrue(repo2.pendingSyncOps().isEmpty())
        call("forget_memory", JSONObject().put("id", id))
        assertTrue(repo.companionRecords().isEmpty())
        assertEquals("{}", repo.getCompanionRecord("DEVELOPMENT:interview")!!.content)
        val tombstone = SyncCodec.current(repo, "companion_records", id)!!
        val op = repo.pendingSyncOps().last { it.entityType == "companion_records" && it.entityId == id }
        val stale = JSONObject(original.toJson()).put("updatedAt", clock.peek() + 9999)
        assertFalse(MergeEngine.merge(tombstone, stale, op).isNull("deletedAt"))
        assertFalse(MergeEngine.merge(stale, tombstone, op).isNull("deletedAt"))
        db2.close()
    }

    @Test fun readsArePagedAndContextIsBoundedEvenWithManyOldSituations() = runBlocking {
        repeat(18) { index ->
            call("save_life_thread", evidence("Remember situation $index").put("id", "s$index").put("title", "Situation $index")
                .put("summary", "detail ".repeat(250)))
        }
        var offset = 0
        val seen = mutableSetOf<String>()
        do {
            val page = call("get_life_context", JSONObject().put("offset", offset))
            assertTrue(page.toString().length < 8000)
            val records = page.getJSONArray("records")
            for (i in 0 until records.length()) assertTrue(seen.add(records.getJSONObject(i).getString("id")))
            offset = if (page.isNull("nextOffset")) -1 else page.getInt("nextOffset")
        } while (offset >= 0)
        assertEquals(18, seen.size)
        assertTrue(companionContext(repo, settings, "situation").joinToString().length < 6000)
    }

    @Test fun sensitiveTopicsAndOtherDevicesCannotNudgeAndReceiptUndoCascades() = runBlocking {
        val id = call("save_life_thread", evidence("Track this private decision").put("id", "private").put("title", "Private decision")
            .put("summary", "Considering a move").put("sensitive", true)).getString("id")
        prefs(id); tomorrow()
        assertEquals("sensitive topic", nudge(id).getString("reason"))
        val row = repo.getCompanionRecord(id)!!
        repo.saveCompanionRecord(row.copy(deviceId = "other", content = row.data().put("sensitive", false).toString()))
        assertEquals("owned by another device", nudge(id).getString("reason"))
        val receipt = com.her.agent.runner.Receipts.describe("save_life_thread", "{}", JSONObject().put("id", id).put("created", true).toString())!!
        assertTrue(receipt.undoable)
        assertTrue(tools.undo(receipt.entityType, receipt.entityId))
        assertNotNull(repo.getCompanionRecord(id)!!.deletedAt)
    }

    @Test fun remoteChildArrivingAfterForgottenParentStaysForgotten() = runBlocking {
        val id = thread().getString("id")
        val child = com.her.data.db.CompanionRecord("DEVELOPMENT:late", id, "DEVELOPMENT", "{\"summary\":\"private\"}", clock.peek(), clock.peek(), "remote", 1)
        repo.forgetCompanion(id)
        repo.applyingRemote { SyncCodec.persist(repo, "companion_records", JSONObject(child.toJson())) }
        assertNotNull(repo.getCompanionRecord(child.id)!!.deletedAt)
        assertEquals("{}", repo.getCompanionRecord(child.id)!!.content)
    }

    @Test fun orchestratorDeliversOnlyApprovedTextAndKeepsLatestMessageConsistent() = runBlocking {
        val id = thread().getString("id")
        prefs(id); tomorrow()
        val shown = mutableListOf<String>()
        fun orchestrator() = com.her.agent.runner.AgentOrchestrator(repo,
            object : com.her.data.remote.LlmClient() {
                var round = 0
                override suspend fun complete(settings: com.her.data.secure.LlmSettings, messages: List<LlmMessage>, tools: List<ToolSpec>,
                    client: okhttp3.OkHttpClient, webSearch: com.her.data.remote.WebSearchRequest?): LlmResponse {
                    val message = if (round++ == 0) LlmMessage(role = "assistant", toolCalls = listOf(LlmToolCall("check", "send_companion_checkin",
                        JSONObject().put("id", id).put("phase", "CHECKIN").put("content", "Want to revisit the offers?").toString())))
                    else LlmMessage(role = "assistant", content = "Unapproved fallback text")
                    return LlmResponse(message, LlmUsage(1, 1, 1), "{}")
                }
            }, { com.her.data.secure.LlmSettings("http://test", "key", "model") }, settings,
            com.her.agent.prompt.ContextBuilder(repo, HybridRanker(repo), settings, CalendarDataSource(context)), tools,
            object : com.her.notify.Notifier(context) { override fun show(text: String, briefing: Boolean) { shown.add(text) } },
            com.her.notify.NotificationPolicy(settings))
        assertFalse(orchestrator().runBriefing().failed)
        assertEquals(listOf("Want to revisit the offers?"), shown)
        assertEquals(shown.single(), repo.recentMessages(20).last { it.role == MessageRole.ASSISTANT }.content)
        // A second pass cannot replace suppressed tool output with final prose.
        clock.advance(3_600_000L)
        assertFalse(orchestrator().runHourly().failed)
        assertEquals(1, shown.size)
    }

    @Test fun socialConsentDailyLimitsAndNotificationFeedbackArePersisted() = runBlocking {
        repo.saveProfile(repo.getProfile().copy(timezone = "UTC"))
        call("save_checkin_preferences", evidence("Send a social hello at ten each day").put("id", "preferences").put("socialEnabled", true)
            .put("localTime", "10:00").put("maxPerDay", 1))
        tomorrow()
        val notice = nudge("companion:preferences")
        assertTrue(notice.getBoolean("queued"))
        assertFalse(nudge("companion:preferences").getBoolean("queued"))
        val key = notice.getString("key")
        assertTrue(com.her.companion.recordCompanionNotificationResponse(repo, key, false))
        assertEquals("snoozed", nudge("companion:preferences").getString("reason"))
        tomorrow()
        assertTrue(com.her.companion.recordCompanionNotificationResponse(repo, key, true))
        assertEquals("dismissed or forgotten", nudge("companion:preferences").getString("reason"))
    }

    @Test fun preferencesCanRestartOnlyFromNewUserEvidenceAfterForgetting() = runBlocking {
        prefs()
        val before = repo.getCompanionRecord("companion:preferences")!!
        repo.forgetCompanion(before.id)
        val tombstone = repo.getCompanionRecord(before.id)!!
        assertFalse(tools.execute("save_checkin_preferences", before.data().put("id", "preferences").put("socialEnabled", true).toString()).ok)
        clock.advance(1000)
        call("save_checkin_preferences", evidence("Enable social check-ins again").put("id", "preferences").put("socialEnabled", true))
        val after = repo.getCompanionRecord(before.id)!!
        assertNull(after.deletedAt)
        val op = repo.pendingSyncOps().last { it.entityType == "companion_records" }
        assertNull(com.her.data.db.CompanionRecord.fromJson(MergeEngine.merge(JSONObject(tombstone.toJson()), JSONObject(after.toJson()), op)).deletedAt)
        assertNull(com.her.data.db.CompanionRecord.fromJson(MergeEngine.merge(JSONObject(after.toJson()), JSONObject(tombstone.toJson()), op)).deletedAt)
    }

    @Test fun statusEditRetainsEarlierTentativeFieldProvenance() = runBlocking {
        val initial = evidence("Maybe the smaller team would be better").put("basis", "TENTATIVE")
            .put("id", "choice").put("title", "Job choice").put("summary", "Smaller team may be better")
        val source = initial.getString("sourceMessageId")
        val id = call("save_life_thread", initial).getString("id")
        call("save_life_thread", evidence("Close this topic").put("id", id).put("expectedVersion", 1).put("status", "CLOSED"))
        val data = repo.getCompanionRecord(id)!!.data()
        val summarySource = data.getJSONObject("fieldSources").getString("summary")
        assertEquals("TENTATIVE", data.getJSONObject("sourceQuotes").getJSONObject(summarySource).getString("basis"))
        assertTrue(repo.getCompanionRecord(id)!!.sourceIds().contains(source))
        repo.forgetCompanion(source)
        assertNotNull(repo.getCompanionRecord(id)!!.deletedAt)
    }

    @Test fun migrationPreservesExistingDataAndNewRecordsSurviveReopen() = runBlocking {
        val name = "companion_migration_${System.nanoTime()}"
        var disk = Room.databaseBuilder(context, HerDatabase::class.java, name).allowMainThreadQueries().build()
        disk.openHelper.writableDatabase.execSQL("INSERT INTO learning_records VALUES ('keep','keep','PATH','{}',1,1,'test',1,NULL)")
        disk.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE companion_records"); it.version = 6
        }
        disk = Room.databaseBuilder(context, HerDatabase::class.java, name).addMigrations(HerDatabase.MIGRATION_6_7, HerDatabase.MIGRATION_7_8).allowMainThreadQueries().build()
        assertNotNull(disk.learningDao().get("keep"))
        val id = thread().getString("id")
        disk.companionDao().upsert(repo.getCompanionRecord(id)!!)
        disk.close()
        disk = Room.databaseBuilder(context, HerDatabase::class.java, name).allowMainThreadQueries().build()
        assertEquals("Job search", disk.companionDao().get(id)!!.data().getString("title"))
        disk.close(); context.deleteDatabase(name); Unit
    }
}
