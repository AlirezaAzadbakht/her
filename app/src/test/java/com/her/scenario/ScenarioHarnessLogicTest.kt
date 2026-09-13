package com.her.scenario

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.core.FakeClock
import com.her.data.db.HerDatabase
import com.her.data.remote.LlmClient
import com.her.data.remote.WebSearchRequest
import com.her.data.repository.HerRepository
import com.her.data.secure.AppSettingsStore
import com.her.data.secure.LlmSettings
import com.her.domain.LlmMessage
import com.her.domain.LlmResponse
import com.her.domain.LlmUsage
import com.her.domain.ToolSpec
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ScenarioHarnessLogicTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: HerDatabase
    private lateinit var repo: HerRepository
    private val clock = FakeClock(Instant.parse("2026-09-12T11:30:00Z").toEpochMilli())
    private val llmSettings = LlmSettings(baseUrl = "http://test", apiKey = "key", model = "model")

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = HerRepository(db, AppSettingsStore(context, "harness_logic_${System.nanoTime()}"), clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun parsesTimeTurnsAndRicherExpectations() {
        val spec = parse(
            """
            {
              "id": "rent-reminder",
              "title": "Remind about rent after a few days",
              "turns": [
                { "user": "Rent is due Friday." },
                { "advance": "3 days" },
                { "at": "tomorrow at 9am" },
                { "run": "hourly" }
              ],
              "expect": {
                "tools_called": ["remember", { "name": "create_task", "args": { "title": { "contains": "rent" } } }],
                "tool_order": ["remember", "create_task"],
                "max_tool_calls": 5,
                "max_llm_calls": 4,
                "max_input_tokens": 20000,
                "notifications": { "count": 1, "contains_any": ["rent"] },
                "reply": { "must_not_mention": ["as an AI"], "language": "english" }
              }
            }
            """,
        )
        assertEquals(ScenarioTurn.Advance(3L * 24 * 60 * 60 * 1000, "3 days"), spec.turns[1])
        assertEquals(ScenarioTurn.At("tomorrow at 9am"), spec.turns[2])
        assertEquals(listOf("remember", "create_task"), spec.expect.toolsCalled)
        assertEquals("create_task", spec.expect.toolCallArgs.single().name)
        assertEquals(listOf("remember", "create_task"), spec.expect.toolOrder)
        assertEquals(4, spec.expect.maxLlmCalls)
        assertEquals(20000L, spec.expect.maxInputTokens)
        assertEquals(NotificationExpectation(1, listOf("rent")), spec.expect.notifications)
        assertEquals("english", spec.expect.reply?.language)
    }

    @Test
    fun rejectsVagueDurationsAndLanguages() {
        listOf(
            """{"id":"bad","title":"Bad","turns":[{"advance":"soon"}],"expect":{}}""",
            """{"id":"bad","title":"Bad","turns":[{"user":"hi"}],"expect":{"reply":{"language":"klingon"}}}""",
            """{"id":"bad","title":"Bad","turns":[{"user":"hi","run":"hourly"}],"expect":{}}""",
        ).forEach { json ->
            try {
                parse(json)
                fail("expected a parse error for $json")
            } catch (_: ScenarioParseException) {
            }
        }
    }

    @Test
    fun checksToolArgsOrderLimitsNotificationsAndLanguage() = runBlocking {
        val spec = parse(
            """
            {
              "id": "rent-reminder",
              "title": "t",
              "turns": [{ "user": "Rent is due Friday." }],
              "expect": {
                "tools_called": [{ "name": "create_task", "args": { "title": { "contains": "rent" } } }],
                "tool_order": ["remember", "create_task"],
                "max_tool_calls": 2,
                "max_llm_calls": 3,
                "notifications": { "count": 1, "contains_any": ["rent"] },
                "reply": { "must_not_mention": ["as an AI"], "language": "english" }
              }
            }
            """,
        )
        val good = outcome(llmCalls = 3)
        val checks = Checks.evaluate(spec, repo, good)
        assertTrue(checks.filterNot { it.passed }.joinToString { "${it.name}: ${it.detail}" }, checks.all { it.passed })

        val tooChatty = Checks.evaluate(spec, repo, outcome(llmCalls = 4))
        assertFalse(tooChatty.single { it.name == "max_llm_calls" }.passed)

        val wrongArgs = Checks.evaluate(
            spec,
            repo,
            good.copy(toolCalls = listOf(call("remember", "{}"), call("create_task", """{"title":"Call mom"}"""))),
        )
        assertFalse(wrongArgs.single { it.name.startsWith("tools_called[0]") }.passed)
    }

    @Test
    fun detectsReplyLanguage() {
        assertEquals("persian", Checks.detectLanguage("باشه، فردا ساعت ۱۰ یادت می‌اندازم."))
        assertEquals("english", Checks.detectLanguage("Sure, I'll remind you at 10."))
    }

    @Test
    fun repositoryTodayFollowsTheClockAndProfileZone() = runBlocking {
        repo.saveProfile(repo.getProfile().copy(timezone = "Asia/Tehran"))
        assertEquals("2026-09-12", repo.today().toString())
        clock.advance(ScenarioLoader.parseDuration("12 hours")!!)
        assertEquals("2026-09-13", repo.today().toString())
    }

    @Test
    fun cassetteRecordsThenReplaysAndCatchesDrift() = runBlocking {
        val tools = listOf(ToolSpec("remember", "Store a fact", """{"type":"object","properties":{}}"""))
        val messages = listOf(
            LlmMessage(role = "system", content = "now is 10:00"),
            LlmMessage(role = "user", content = "Remember my sister is Sara."),
        )
        val live = object : LlmClient() {
            override suspend fun complete(
                settings: LlmSettings,
                messages: List<LlmMessage>,
                tools: List<ToolSpec>,
                client: OkHttpClient,
                webSearch: WebSearchRequest?,
            ): LlmResponse = LlmResponse(LlmMessage(role = "assistant", content = "Got it."), LlmUsage(40, 3, 5), "{}")
        }
        val recording = ScenarioLlmClient(ScenarioMode.RECORD, live, CassetteAttempt(startMillis = 42L))
        recording.complete(llmSettings, messages, tools)
        val dir = tmp.newFolder("cassettes")
        Cassette("sister", "model", mutableListOf(recording.tape)).save(dir)

        val loaded = Cassette.load("sister", dir)!!
        assertEquals(42L, loaded.attempts.single().startMillis)
        val replay = ScenarioLlmClient(ScenarioMode.REPLAY, null, loaded.attempts.single())
        val shifted = listOf(messages[0].copy(content = "now is 11:00"), messages[1])
        assertEquals("Got it.", replay.complete(llmSettings, shifted, tools).message.content)
        assertEquals(40L, replay.inputTokens)

        val drifted = ScenarioLlmClient(ScenarioMode.REPLAY, null, loaded.attempts.single())
        try {
            drifted.complete(llmSettings, listOf(messages[0], messages[1].copy(content = "Remember my brother is Ali.")), tools)
            fail("a changed user turn must not replay")
        } catch (e: CassetteMismatchException) {
            assertTrue(e.message!!.contains("drifted"))
        }
    }

    @Test
    fun fingerprintTracksToolSchemasButNotToolPayloads() {
        val tools = listOf(ToolSpec("remember", "d", """{"type":"object"}"""))
        val base = listOf(LlmMessage(role = "user", content = "hi"), LlmMessage(role = "tool", content = "{\"id\":\"a\"}", name = "remember"))
        val otherPayload = listOf(base[0], base[1].copy(content = "{\"id\":\"b\"}"))
        assertEquals(ScenarioLlmClient.fingerprint(base, tools), ScenarioLlmClient.fingerprint(otherPayload, tools))
        val otherSchema = listOf(ToolSpec("remember", "d", """{"type":"object","required":["content"]}"""))
        assertNotEquals(ScenarioLlmClient.fingerprint(base, tools), ScenarioLlmClient.fingerprint(base, otherSchema))
    }

    private fun parse(json: String): ScenarioSpec {
        val raw = json.trimIndent()
        val id = Regex(""""id"\s*:\s*"([^"]+)"""").find(raw)?.groupValues?.get(1) ?: "bad"
        val file = tmp.newFile("$id-${System.nanoTime()}.json")
        file.writeText(raw)
        return ScenarioLoader.parse(file)
    }

    private fun call(name: String, args: String) = RecordedToolCall(name, args, true, "{\"ok\":true}")

    private fun outcome(llmCalls: Int) = HarnessOutcome(
        toolCalls = listOf(call("remember", """{"content":"rent due Friday"}"""), call("create_task", """{"title":"Pay rent"}""")),
        notifications = listOf(RecordedNotification("Rent is due tomorrow.", briefing = false)),
        messages = emptyList(),
        lastReply = "I'll remind you about rent on Thursday.",
        systemBundle = "",
        tableDump = "",
        inputTokens = 1200,
        outputTokens = 80,
        llmCalls = llmCalls,
        error = null,
    )
}
