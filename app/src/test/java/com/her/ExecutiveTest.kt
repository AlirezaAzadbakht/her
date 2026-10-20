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
class ExecutiveTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
    private val settings = AppSettingsStore(context, "executive_${System.nanoTime()}")
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
    private suspend fun delegation(name: String, extra: JSONObject = JSONObject()): String {
        val person = call("update_person", JSONObject().put("name", name)).getString("id")
        val loop = call("create_open_loop", JSONObject().put("description", "$name delivery")).getString("id")
        val a = evidence("$name owns delivery. Waiting for approval. Need pricing decision. Progress reported.")
            .put("id", name).put("openLoopId", loop).put("personId", person).put("expectedOutcome", "$name delivery").put("delegated", true)
        extra.keys().forEach { a.put(it, extra.get(it)) }
        return call("save_waiting_on", a).getString("id")
    }
    private suspend fun briefing() = call("get_executive_briefing", JSONObject())

    @Test fun joinedVerbatimEvidenceRestoresOmittedContextButRejectsInventedSentences() {
        val source = "Sara owns launch. Track both. Launch depends on migration."
        assertEquals(source, delegationEvidence(source, "Sara owns launch. Launch depends on migration."))
        assertEquals("Migration complete; decision resolved. Launch open.", delegationEvidence("Migration complete; decision resolved. Launch open.", "Migration complete; Launch open."))
        assertTrue(runCatching { delegationEvidence(source, "Sara owns launch. Migration is finished.") }.isFailure)
        assertTrue(runCatching { delegationEvidence(source, "Launch depends on migration. Sara owns launch.") }.isFailure)
    }

    @Test fun naturalDeadlineOverridesModelArithmeticAndUsesSourceDate() = runBlocking {
        repo.saveProfile(repo.getProfile().copy(timezone = "UTC"))
        val id = delegation("Sara")
        val a = evidence("Due tomorrow at 4pm").put("id", id).put("expectedVersion", 1).put("deadlineText", "tomorrow at 4pm").put("deadline", 123)
        clock.advance(3L * 86_400_000)
        call("save_waiting_on", a)
        val expected = Instant.parse("2026-10-18T16:00:00Z").toEpochMilli()
        assertEquals(expected, repo.getCompanionRecord(id)!!.data().getLong("deadline"))
        call("save_waiting_on", evidence("Priority high").put("id", id).put("expectedVersion", 2).put("priority", 2).put("deadlineText", "tomorrow at 4pm").put("deadline", 123))
        assertEquals(expected, repo.getCompanionRecord(id)!!.data().getLong("deadline"))
        call("save_waiting_on", evidence("Priority normal").put("id", id).put("expectedVersion", 3).put("priority", 1).put("deadlineText", "").put("deadline", 0))
        assertEquals(expected, repo.getCompanionRecord(id)!!.data().getLong("deadline"))
    }

    @Test fun emptyOptionalFieldsAreUnknownAndPunctuationIsRejected() = runBlocking {
        val id = delegation("Sara", JSONObject().put("progress", "").put("blocker", "").put("decisionNeeded", ""))
        val item = briefing().getJSONArray("items").getJSONObject(0)
        assertEquals("[\"NO_REPORTED_UPDATE\"]", item.getJSONArray("reasons").toString())
        assertTrue(item.isNull("progressReportedAt"))
        assertFalse(tools.execute("save_waiting_on", evidence("No update.").put("id", id).put("expectedVersion", 1).put("blocker", ".").toString()).ok)
        call("save_waiting_on", evidence("Waiting for approval").put("id", id).put("expectedVersion", 1).put("blocker", "Waiting for approval"))
        call("save_waiting_on", evidence("Approval arrived").put("id", id).put("expectedVersion", 2).put("blocker", ""))
        assertTrue(briefing().getJSONArray("items").getJSONObject(0).isNull("blocker"))
    }

    @Test fun briefingRanksDecisionsBeforeOverdueAndNeverInfersFailure() = runBlocking {
        delegation("Overdue", JSONObject().put("deadline", clock.peek() - 1000))
        val decision = delegation("Sara", JSONObject().put("decisionNeeded", "Need pricing decision").put("priority", 3))
        val items = briefing().getJSONArray("items")
        assertEquals(decision, items.getJSONObject(0).getString("id"))
        assertTrue(items.getJSONObject(1).getJSONArray("reasons").toString().contains("OVERDUE"))
        assertTrue(items.getJSONObject(0).isNull("progress"))
        assertTrue(briefing().getString("coverage").contains("overdue is not failed"))
        assertFalse(briefing().getBoolean("automaticScheduleCreated"))
    }

    @Test fun decisionCanLinkExistingOpenLoopButNotMissingRecords() = runBlocking {
        val loop = call("create_open_loop", JSONObject().put("description", "Migration")).getString("id")
        val a = evidence("I chose the existing vendor").put("id", "vendor").put("summary", "Vendor choice").put("status", "DECIDED").put("relatedIds", JSONArray().put(loop))
        val id = call("save_decision", a).getString("id")
        assertEquals(loop, repo.getCompanionRecord(id)!!.data().getJSONArray("relatedIds").getString(0))
        assertFalse(tools.execute("save_decision", a.put("id", "invalid").put("relatedIds", JSONArray().put("missing")).toString()).ok)
    }

    @Test fun blankWaitingIdDerivesFromLoopAndReusesRecordOnUpdates() = runBlocking {
        val person = call("update_person", JSONObject().put("name", "Sara")).getString("id")
        val loop = call("create_open_loop", JSONObject().put("description", "Launch")).getString("id")
        val a = evidence("Sara owns launch").put("id", "").put("openLoopId", loop).put("personId", person).put("expectedOutcome", "Launch").put("delegated", true)
        val result = call("save_waiting_on", a)
        assertEquals("WAITING:$loop", result.getString("id"))
        assertFalse(call("save_waiting_on", a).getBoolean("created"))
        call("save_waiting_on", evidence("Need approval").put("id", "").put("openLoopId", loop).put("expectedVersion", 1).put("blocker", "Need approval"))
        assertEquals(1, repo.companionRecords().count { it.kind == "WAITING" })
        assertEquals(2L, repo.getCompanionRecord(result.getString("id"))!!.version)
    }

    @Test fun dependencyCanReferToExistingLoopBeforeWaitingExtensionAndStillRejectsCycles() = runBlocking {
        val loop = call("create_open_loop", JSONObject().put("description", "Migration")).getString("id")
        val launch = delegation("Launch", JSONObject().put("dependencies", JSONArray().put(loop)))
        assertTrue(briefing().toString().contains("UNKNOWN"))
        val person = call("update_person", JSONObject().put("name", "Omid")).getString("id")
        val args = evidence("Migration waits for launch").put("id", "migration").put("openLoopId", loop).put("personId", person).put("expectedOutcome", "Migration").put("delegated", true).put("dependencies", JSONArray().put(launch))
        assertFalse(tools.execute("save_waiting_on", args.toString()).ok)
        call("save_waiting_on", args.put("dependencies", JSONArray()).put("status", "RESOLVED"))
        assertFalse(briefing().toString().contains("DEPENDENCY_RISK"))
    }

    @Test fun dependenciesRejectMissingAndCyclesAndRiskClearsOnResolution() = runBlocking {
        val prerequisite = delegation("Migration", JSONObject().put("blocker", "Waiting for approval"))
        val launch = delegation("Launch", JSONObject().put("dependencies", JSONArray().put(repo.getCompanionRecord(prerequisite)!!.parentId)))
        fun update(id: String, dep: String) = JSONObject().put("id", id).put("expectedVersion", 1).put("dependencies", JSONArray().put(dep))
        val bad = evidence("Migration depends on launch")
        update(prerequisite, launch).keys().forEach { bad.put(it, update(prerequisite, launch).get(it)) }
        assertFalse(tools.execute("save_waiting_on", bad.toString()).ok)
        bad.put("dependencies", JSONArray().put("WAITING:missing"))
        assertFalse(tools.execute("save_waiting_on", bad.toString()).ok)
        assertTrue(briefing().toString().contains("DEPENDENCY_RISK"))
        call("save_waiting_on", evidence("Migration finished").put("id", prerequisite).put("expectedVersion", 1).put("status", "RESOLVED"))
        assertFalse(briefing().toString().contains("DEPENDENCY_RISK"))
        assertEquals(OpenLoopStatus.CLOSED, repo.getOpenLoop(repo.getCompanionRecord(prerequisite)!!.parentId)!!.status)
        assertEquals("OPEN", repo.getCompanionRecord(launch)!!.data().getString("status"))
    }

    @Test fun evidenceTimestampSurvivesUnrelatedEditsAndNullClearsBlocker() = runBlocking {
        val id = delegation("Sara", JSONObject().put("progress", "Progress reported.").put("blocker", "Waiting for approval"))
        val first = clock.peek()
        clock.advance(8L * 86_400_000)
        call("save_waiting_on", evidence("Approval arrived; priority high").put("id", id).put("expectedVersion", 1).put("blocker", JSONObject.NULL).put("priority", 2).put("progress", "Progress reported."))
        val item = briefing().getJSONArray("items").getJSONObject(0)
        assertEquals(first, item.getLong("progressReportedAt"))
        assertTrue(item.isNull("blocker"))
        assertEquals("Sara delivery", repo.getOpenLoop(repo.getCompanionRecord(id)!!.parentId)!!.description)
        assertTrue(item.getJSONArray("reasons").toString().contains("UPDATE_OLDER_THAN_7_DAYS"))
        val invented = evidence("Nothing new").put("id", id).put("expectedVersion", 2).put("progress", "Finished everything")
        assertFalse(tools.execute("save_waiting_on", invented.toString()).ok)
        assertEquals(2L, repo.getCompanionRecord(id)!!.version)
    }

    @Test fun recordsSyncAndForgetAndBriefingPagesWithoutWrites() = runBlocking {
        repeat(7) { delegation("Owner$it") }
        val before = repo.pendingSyncOps().size
        val first = briefing()
        val next = call("get_executive_briefing", JSONObject().put("offset", first.getInt("nextOffset")))
        assertEquals(7, first.getJSONArray("items").length() + next.getJSONArray("items").length())
        assertEquals(before, repo.pendingSyncOps().size)
        val id = first.getJSONArray("items").getJSONObject(0).getString("id")
        val row = repo.getCompanionRecord(id)!!
        repo.applyingRemote { assertTrue(SyncCodec.persist(repo, "companion_records", JSONObject(row.toJson()))) }
        assertEquals(row, repo.getCompanionRecord(id))
        call("forget_memory", JSONObject().put("id", id))
        assertEquals(6, briefing().getInt("activeDelegations"))
        assertFalse(tools.execute("get_executive_briefing", "{\"offset\":-1}").ok)
    }

    @Test fun freshProgressWithoutExceptionsIsQuietAndRetryIsIdempotent() = runBlocking {
        val id = delegation("Sara", JSONObject().put("progress", "Progress reported."))
        assertEquals(0, briefing().getJSONArray("items").length())
        val a = evidence("Need pricing decision").put("id", id).put("expectedVersion", 1).put("decisionNeeded", "Need pricing decision")
        call("save_waiting_on", a)
        assertFalse(call("save_waiting_on", a).getBoolean("created"))
        assertEquals(2L, repo.getCompanionRecord(id)!!.version)
        assertFalse(tools.execute("save_waiting_on", evidence("A correction").put("id", id).put("expectedVersion", 1).put("priority", 2).toString()).ok)
    }
}
