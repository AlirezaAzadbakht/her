package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.agent.tools.ToolRegistry
import com.her.companion.*
import com.her.core.FakeClock
import com.her.data.calendar.CalendarDataSource
import com.her.data.db.*
import com.her.data.drive.SyncCodec
import com.her.data.remote.WebSearchClient
import com.her.data.repository.HerRepository
import com.her.data.retrieval.HybridRanker
import com.her.data.secure.AppSettingsStore
import com.her.domain.*
import com.her.notify.Notifier
import com.her.reminders.ReminderDelivery
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
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class PersonalSupportTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
    private val settings = AppSettingsStore(context, "support_${System.nanoTime()}")
    private val clock = FakeClock(Instant.parse("2026-10-17T10:00:00Z").toEpochMilli(), tickMs = 0)
    private val repo = HerRepository(db, settings, clock)
    private val tools = ToolRegistry(repo, HybridRanker(repo), settings, CalendarDataSource(context), WebSearchClient())
    @After fun close() = db.close()
    private suspend fun call(name: String, args: JSONObject): JSONObject {
        val result = tools.execute(name, args.toString())
        assertTrue(result.payloadJson, result.ok)
        return JSONObject(result.payloadJson)
    }
    private suspend fun evidence(text: String): JSONObject {
        val message = repo.newChatMessage(MessageRole.USER, text, MessageStatus.SENT)
        repo.saveMessage(message)
        return JSONObject().put("sourceMessageId", message.id).put("evidence", text).put("basis", "EXPLICIT")
    }
    private suspend fun task(title: String) = call("create_task", JSONObject().put("title", title)).getString("id")
    private suspend fun memory(content: String) = call("remember", JSONObject().put("content", content).put("scope", "long_term").put("source", "USER_EXPLICIT")).getString("id")
    private suspend fun capacity(id: String, minutes: Int, dependencies: List<String> = emptyList()) = call("save_action_capacity", evidence("It takes $minutes minutes").put("id", id).put("obligationId", id).put("summary", "User estimate").put("minutes", minutes).put("dependencies", JSONArray(dependencies)))
    private suspend fun schedule(reminder: String? = null): String {
        repo.saveProfile(repo.getProfile().copy(timezone = "UTC"))
        val routine = call("create_routine", JSONObject().put("title", "Household chore")).getString("id")
        val args = evidence("Every week at noon; optional chore").put("id", routine).put("responsibilityId", routine).put("summary", "Household chore")
            .put("anchorLocal", "2026-10-17T12:00:00").put("zone", "UTC").put("zonePolicy", "PROFILE").put("repeat", "WEEKLY").put("obligationType", "OPTIONAL")
        if (reminder != null) args.put("reminderId", reminder)
        return call("save_responsibility_schedule", args).getString("id")
    }
    private suspend fun occurrence(id: String, at: Long, action: String, until: Long? = null): JSONObject {
        val a = evidence("Please $action this occurrence").put("id", id).put("scheduledAt", at).put("action", action)
        a.remove("basis"); if (until != null) a.put("until", until)
        return call("manage_occurrence", a)
    }
    private suspend fun reminder() = call("set_reminder", JSONObject().put("message", "Household chore").put("when", "2026-10-17T12:00:00Z").put("repeat", "WEEKLY")).getString("id")

    @Test fun correctionPreservesHistoryInvalidatesSummaryAndSurvivesStaleSync() = runBlocking {
        val id = memory("I work at OldCo")
        val old = repo.getLong(id)!!
        val thread = call("save_life_thread", evidence("My work is at OldCo").put("id", "work").put("title", "Work").put("summary", "Works at OldCo").put("relatedIds", JSONArray().put(id))).getString("id")
        val correction = evidence("I now work at NewCo").put("id", id).put("replacementId", "job-change").put("expectedVersion", 1).put("content", "I work at NewCo")
        correction.remove("basis")
        val result = call("correct_memory", correction)
        assertFalse(call("correct_memory", correction).getBoolean("created"))
        assertEquals(MemoryStatus.HISTORICAL, repo.getLong(id)!!.status)
        assertTrue(repo.getCompanionRecord(thread)!!.data().getBoolean("stale"))
        assertEquals(listOf("I work at NewCo"), repo.activeLong().map { it.content })
        repo.upsertLong(old.copy(updatedAt = clock.peek() + 1000, version = 50))
        assertEquals(MemoryStatus.HISTORICAL, repo.getLong(id)!!.status)
        assertEquals("OldCo", call("inspect_understanding", JSONObject().put("id", id)).getJSONArray("records").getJSONObject(0).getString("content").substringAfter("at "))
        assertNotNull(repo.getLong(result.getString("id")))
        repo.upsertLong(old.copy(id = "late-derived", content = "Still at OldCo", derivedFromJson = JSONArray().put(id).toString()))
        assertEquals(MemoryStatus.HISTORICAL, repo.getLong("late-derived")!!.status)
        repo.deleteLong(result.getString("id"))
        repo.upsertLong(old.copy(updatedAt = clock.peek() + 5000))
        assertEquals(MemoryStatus.HISTORICAL, repo.getLong(id)!!.status)
    }

    @Test fun forgettingSuppressesLateDerivedRecordsAndEmbeddingCacheWithoutSyncEcho() = runBlocking {
        val id = memory("Private employment fact")
        val original = repo.getLong(id)!!
        val derived = original.copy(id = "derived", content = "Employment summary", derivedFromJson = JSONArray().put(id).toString())
        repo.upsertLong(derived)
        db.embeddingDao().upsertAll(listOf(MemoryEmbeddingEntity(id, "model", "hash", 1, byteArrayOf(1), clock.peek())))
        call("forget_memory", JSONObject().put("id", id))
        assertTrue(repo.activeLong().isEmpty())
        assertEquals(0, db.embeddingDao().count())
        repo.upsertLong(original.copy(updatedAt = clock.peek() + 9999))
        repo.upsertLong(derived.copy(id = "late-summary"))
        assertTrue(repo.activeLong().isEmpty())
        val count = repo.pendingSyncOps().size
        repo.applyingRemote { SyncCodec.persist(repo, "long_term_memories", JSONObject().put("id", "unknown").put("deletedAt", clock.peek())) }
        assertTrue(repo.knowledgeForgotten("unknown"))
        assertEquals(count, repo.pendingSyncOps().size)
    }

    @Test fun planningFitsMeetingAndDependenciesAndNeverChangesCommitments() = runBlocking {
        val short = task("Reply")
        val long = task("Deep work")
        val blocked = task("Submit after reply")
        capacity(short, 10); capacity(long, 60); capacity(blocked, 5, listOf(short))
        val now = clock.peek()
        repo.saveCalendarEvent(CalendarEvent("meeting", "Meeting", now + 20 * 60_000, now + 60 * 60_000, null, null, null, CalendarSource.INTERNAL, null, now, now, repo.deviceId, 1, null))
        val proposal = call("suggest_daily_plan", JSONObject().put("minutes", 90))
        assertEquals(20, proposal.getInt("availableMinutes"))
        assertEquals(1, proposal.getJSONArray("actions").length())
        assertEquals(short, proposal.getJSONArray("actions").getJSONObject(0).getString("id"))
        assertTrue(repo.companionRecords().none { it.kind == "PLAN" })
        val accept = evidence("Yes, I will work on the reply for ten minutes").put("id", "today").put("minutes", 20).put("actionIds", JSONArray().put(short)); accept.remove("basis")
        call("accept_daily_plan", accept)
        assertFalse(call("accept_daily_plan", accept).getBoolean("created"))
        call("suggest_daily_plan", JSONObject().put("minutes", 5))
        assertEquals(TaskStatus.OPEN, repo.getTask(short)!!.status)
        assertEquals(now + 20 * 60_000, repo.getCalendarEvent("meeting")!!.startAt)
        assertEquals(1L, repo.getCalendarEvent("meeting")!!.version)
    }

    @Test fun missingEstimatesAndLowEnergyDoNotBecomeAssumedCapacity() = runBlocking {
        val unknown = task("Unknown work")
        val high = task("Hard work")
        call("save_action_capacity", evidence("Ten minutes and high energy").put("id", high).put("obligationId", high).put("summary", "Hard").put("minutes", 10).put("energy", "HIGH"))
        val plan = call("suggest_daily_plan", JSONObject().put("minutes", 20).put("energy", "LOW"))
        assertEquals(0, plan.getJSONArray("actions").length())
        assertEquals(unknown, plan.getJSONArray("unknownEstimates").getJSONObject(0).getString("id"))
    }

    @Test fun skippingThisWeekKeepsNextWeekAndLateCompletionDoesNotConsumeFuture() = runBlocking {
        val reminder = reminder(); val schedule = schedule(reminder)
        val at = Instant.parse("2026-10-17T12:00:00Z").toEpochMilli()
        occurrence(schedule, at, "SKIPPED")
        val next = repo.getReminder(reminder)!!.fireAt
        assertEquals(at + 7 * 86_400_000L, next)
        occurrence(schedule, at, "SKIPPED")
        clock.set(at + 86_400_000L)
        occurrence(schedule, at, "COMPLETED")
        assertEquals(next, repo.getReminder(reminder)!!.fireAt)
        assertEquals("ACTIVE", repo.getCompanionRecord(schedule)!!.data().getString("status"))
    }

    @Test fun postponedReminderFiresOnceAndResumesOriginalCadence() = runBlocking {
        val reminder = reminder(); val schedule = schedule(reminder)
        val at = Instant.parse("2026-10-17T12:00:00Z").toEpochMilli()
        occurrence(schedule, at, "POSTPONED", at + 3_600_000L)
        val shown = mutableListOf<String>()
        val delivery = ReminderDelivery(repo, object : Notifier(context) { override fun showReminder(id: String, text: String) { shown += text } })
        clock.set(at); delivery.fireDue(); assertTrue(shown.isEmpty())
        clock.set(at + 3_600_000L); delivery.fireDue(); delivery.fireDue()
        assertEquals(1, shown.size)
        assertEquals(at + 7 * 86_400_000L, repo.getReminder(reminder)!!.fireAt)
        occurrence(schedule, at, "COMPLETED")
        assertEquals(at + 7 * 86_400_000L, repo.getReminder(reminder)!!.fireAt)
    }

    @Test fun partialSyncOfPostponementSuppressesOldAlarmAndForgettingCancelsBoundReminder() = runBlocking {
        val reminderId = reminder(); val id = schedule(reminderId)
        val oldReminder = repo.getReminder(reminderId)!!
        val at = oldReminder.fireAt!!
        occurrence(id, at, "POSTPONED", at + 3_600_000L)
        // Simulate occurrence arriving on the owner before the updated reminder row.
        repo.saveReminder(oldReminder)
        val shown = mutableListOf<String>()
        val delivery = ReminderDelivery(repo, object : Notifier(context) { override fun showReminder(id: String, text: String) { shown += text } })
        clock.set(at); delivery.fireDue()
        assertTrue(shown.isEmpty())
        assertEquals(at + 3_600_000L, repo.getReminder(reminderId)!!.fireAt)
        call("forget_memory", JSONObject().put("id", id))
        clock.set(at + 3_600_000L); delivery.fireDue()
        assertTrue(shown.isEmpty())
        assertEquals(ReminderStatus.CANCELLED, repo.getReminder(reminderId)!!.status)
    }

    @Test fun pauseUntilAndResumeKeepRoutineWithoutSecondCheckin() = runBlocking {
        val reminder = reminder(); val id = schedule(reminder)
        val until = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()
        call("save_responsibility_schedule", evidence("Pause until next month").put("id", id).put("expectedVersion", 1).put("status", "PAUSED").put("pauseUntil", until))
        assertTrue(repo.getReminder(reminder)!!.fireAt!! >= until)
        assertNotNull(checkinBlock(repo, settings, repo.getCompanionRecord(id)!!, "CHECKIN"))
        call("save_responsibility_schedule", evidence("Resume now").put("id", id).put("expectedVersion", 2).put("status", "ACTIVE").put("pauseUntil", JSONObject.NULL))
        assertEquals(Instant.parse("2026-10-17T12:00:00Z").toEpochMilli(), repo.getReminder(reminder)!!.fireAt)
    }

    @Test fun recurrenceUsesLocalAnchorAcrossDstMonthEndsAndTimezoneChanges() = runBlocking {
        val id = schedule()
        val row = repo.getCompanionRecord(id)!!
        val daily = row.copy(content = row.data().put("anchorLocal", "2026-03-07T02:30:00").put("zone", "America/New_York").put("zonePolicy", "FIXED").put("repeat", "DAILY").toString())
        val ny = ZoneId.of("America/New_York")
        assertEquals(3, Instant.ofEpochMilli(scheduleOccurrence(daily, 1, ny)).atZone(ny).hour)
        assertEquals(2, Instant.ofEpochMilli(scheduleOccurrence(daily, 2, ny)).atZone(ny).hour)
        val monthly = row.copy(content = row.data().put("anchorLocal", "2026-01-31T12:00:00").put("repeat", "MONTHLY").toString())
        assertEquals(31, Instant.ofEpochMilli(scheduleOccurrence(monthly, 2, ZoneId.of("UTC"))).atZone(ZoneId.of("UTC")).dayOfMonth)
        val tehran = ZoneId.of("Asia/Tehran")
        assertEquals(12, Instant.ofEpochMilli(scheduleOccurrence(row, 1, tehran)).atZone(tehran).hour)
        val overlap = daily.copy(content = daily.data().put("anchorLocal", "2026-11-01T01:30:00").toString())
        assertEquals("2026-11-01T05:30:00Z", Instant.ofEpochMilli(scheduleOccurrence(overlap, 0, ny)).toString())
    }

    @Test fun tentativeDecisionCannotBecomeFinalWithoutExplicitEvidenceAndKeepsActualRationale() = runBlocking {
        val args = evidence("Maybe the smaller apartment; the rent is lower").put("id", "apartment").put("summary", "Considering apartment").put("reasons", "the rent is lower").put("basis", "TENTATIVE")
        val id = call("save_decision", args).getString("id")
        assertEquals("EXPLORING", repo.getCompanionRecord(id)!!.data().getString("status"))
        assertFalse(tools.execute("save_decision", args.put("expectedVersion", 1).put("status", "DECIDED").toString()).ok)
        call("save_decision", evidence("I chose the apartment for lower rent and a shorter commute").put("id", id).put("expectedVersion", 1).put("status", "DECIDED").put("reasons", "lower rent and a shorter commute"))
        call("save_decision", evidence("My workplace changed").put("id", id).put("expectedVersion", 2).put("outcome", "My workplace changed"))
        val decision = repo.getCompanionRecord(id)!!
        assertEquals("lower rent and a shorter commute", decision.data().getString("reasons"))
        assertTrue(decision.data().getJSONObject("sourceQuotes").toString().contains("I chose"))
        assertNotNull(checkinBlock(repo, settings, decision, "CHECKIN"))
        val copy = CompanionRecord.fromJson(JSONObject(decision.toJson()))
        assertEquals(decision, copy)
    }

    @Test fun timezoneEditReschedulesPendingReminderAndSkippedOccurrenceDoesNotNotify() = runBlocking {
        val reminder = reminder(); val id = schedule(reminder)
        val at = repo.getReminder(reminder)!!.fireAt!!
        occurrence(id, at, "SKIPPED")
        val shown = mutableListOf<String>()
        val delivery = ReminderDelivery(repo, object : Notifier(context) { override fun showReminder(id: String, text: String) { shown += text } })
        clock.set(at + 60_000); delivery.fireDue()
        assertTrue(shown.isEmpty())
        repo.saveProfile(repo.getProfile().copy(timezone = "Asia/Tehran"))
        assertEquals(12, Instant.ofEpochMilli(repo.getReminder(reminder)!!.fireAt!!).atZone(ZoneId.of("Asia/Tehran")).hour)
    }

    @Test fun skippedLocalOccurrenceSurvivesTimezoneChangeBeforeItWasDue() = runBlocking {
        val reminder = reminder(); val id = schedule(reminder)
        val at = repo.getReminder(reminder)!!.fireAt!!
        occurrence(id, at, "SKIPPED")
        // Move the clock back before either timezone's occurrence, then change zone.
        clock.set(Instant.parse("2026-10-17T06:00:00Z").toEpochMilli())
        repo.saveProfile(repo.getProfile().copy(timezone = "Asia/Tehran"))
        val due = Instant.ofEpochMilli(repo.getReminder(reminder)!!.fireAt!!).atZone(ZoneId.of("Asia/Tehran"))
        assertEquals(24, due.dayOfMonth)
        assertEquals(12, due.hour)
    }

    @Test fun inFlightEmbeddingCannotRestoreForgottenMemory() = runBlocking {
        val id = memory("Private fact")
        val ranker = HybridRanker(repo, object : com.her.data.retrieval.EmbeddingProvider {
            override fun model() = "test"
            override suspend fun embed(texts: List<String>): List<FloatArray> {
                repo.deleteLong(id)
                return texts.map { floatArrayOf(1f) }
            }
        })
        assertTrue(ranker.search("Private fact").isEmpty())
        assertEquals(0, db.embeddingDao().count())
    }

    @Test fun decisionRejectsInventedRationaleAndCreationUndoUsesExistingCascade() = runBlocking {
        val a = evidence("I chose the smaller apartment for lower rent").put("id", "apartment").put("summary", "Chose apartment").put("status", "DECIDED").put("reasons", "Better neighbors")
        assertFalse(tools.execute("save_decision", a.toString()).ok)
        val result = call("save_decision", a.put("reasons", "lower rent"))
        val receipt = com.her.agent.runner.Receipts.describe("save_decision", a.toString(), result.toString())!!
        assertTrue(receipt.undoable)
        assertTrue(tools.undo(receipt.entityType, receipt.entityId))
        assertNotNull(repo.getCompanionRecord(result.getString("id"))!!.deletedAt)
    }

    @Test fun scheduleAndAcceptedPlanRoundTripThroughSyncWithoutEcho() = runBlocking {
        val scheduleId = schedule()
        val taskId = task("Reply"); capacity(taskId, 10)
        val a = evidence("I accept the reply plan").put("id", "accepted").put("minutes", 20).put("actionIds", JSONArray().put(taskId)); a.remove("basis")
        val planId = call("accept_daily_plan", a).getString("id")
        val count = repo.pendingSyncOps().size
        for (id in listOf(scheduleId, planId)) {
            val row = repo.getCompanionRecord(id)!!
            repo.applyingRemote { assertTrue(SyncCodec.persist(repo, "companion_records", JSONObject(row.toJson()))) }
            assertEquals(row, repo.getCompanionRecord(id))
        }
        assertEquals(count, repo.pendingSyncOps().size)
    }

    @Test fun personBriefingExcludesSensitiveUnrelatedMemoriesAndLabelsStaleness() = runBlocking {
        val person = call("update_person", JSONObject().put("name", "Reza")).getString("id")
        memory("Sensitive health detail mentioning Reza")
        val args = evidence("Reza and I are organizing a trip").put("id", "trip").put("title", "Trip").put("summary", "Waiting for itinerary").put("relatedIds", JSONArray().put(person))
        call("save_life_thread", args)
        call("save_life_thread", evidence("Sensitive topic with Reza").put("id", "private").put("title", "Private").put("summary", "Private detail").put("relatedIds", JSONArray().put(person)).put("sensitive", true))
        clock.advance(40L * 86_400_000L)
        val briefing = call("get_situation_briefing", JSONObject().put("personId", person))
        val items = briefing.getJSONArray("items")
        assertEquals(1, items.length())
        assertTrue(items.getJSONObject(0).getBoolean("stale"))
        assertFalse(briefing.toString().contains("health"))
        assertFalse(briefing.toString().contains("Private detail"))
        assertFalse(briefing.getBoolean("automaticScheduleCreated"))
    }
}
