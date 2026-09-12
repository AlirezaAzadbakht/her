package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.agent.prompt.ContextBuilder
import com.her.agent.runner.AgentOrchestrator
import com.her.agent.tools.ToolRegistry
import com.her.data.calendar.CalendarDataSource
import com.her.data.db.HerDatabase
import com.her.data.remote.LlmClient
import com.her.data.remote.WebSearchClient
import com.her.data.repository.HerRepository
import com.her.data.retrieval.HybridRanker
import com.her.data.secure.AppSettingsStore
import com.her.data.secure.LlmSettings
import com.her.domain.LlmMessage
import com.her.domain.LlmResponse
import com.her.domain.LlmUsage
import com.her.domain.MessageRole
import com.her.domain.MessageStatus
import com.her.domain.ToolSpec
import com.her.notify.NotificationPolicy
import com.her.notify.Notifier
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class OutboxTest {
    private lateinit var db: HerDatabase
    private lateinit var repo: HerRepository
    private lateinit var orchestrator: AgentOrchestrator
    private lateinit var llm: RecordingLlm

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val settings = AppSettingsStore(context)
        repo = HerRepository(db, settings)
        llm = RecordingLlm()
        val ranker = HybridRanker(repo)
        orchestrator = AgentOrchestrator(
            repo = repo,
            llm = llm,
            llmSettings = { LlmSettings("http://example.invalid", "test", "test") },
            settings = settings,
            contextBuilder = ContextBuilder(repo, ranker, settings, CalendarDataSource(context)),
            tools = ToolRegistry(repo, ranker, settings, CalendarDataSource(context), WebSearchClient()),
            notifier = Notifier(context),
            policy = NotificationPolicy(settings),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun pendingBatchBecomesOneTurn() = runBlocking {
        orchestrator.enqueueUserMessage("We're out of coffee.")
        orchestrator.enqueueUserMessage("Also rice.")
        orchestrator.enqueueUserMessage("Amir's birthday is June 4.")

        val pending = repo.pendingUserMessages()
        assertEquals(3, pending.size)
        assertTrue(pending.all { it.status == MessageStatus.PENDING })

        val result = orchestrator.processOutbox()
        assertTrue(result != null && !result.failed)
        assertEquals("Heard all three.", result?.assistantText)

        val after = repo.pendingUserMessages()
        assertTrue(after.isEmpty())
        val users = repo.recentMessages(20).filter { it.role == MessageRole.USER }
        assertEquals(3, users.size)
        assertTrue(users.all { it.status == MessageStatus.SENT })

        val joined = llm.lastUserTexts.joinToString("\n\n")
        assertTrue(joined.contains("coffee"))
        assertTrue(joined.contains("rice"))
        assertTrue(joined.contains("Amir"))
        assertEquals(1, llm.streamCalls)
    }

    private class RecordingLlm : LlmClient() {
        var streamCalls = 0
        val lastUserTexts = mutableListOf<String>()

        override suspend fun stream(
            settings: LlmSettings,
            messages: List<LlmMessage>,
            tools: List<ToolSpec>,
            onDelta: (String) -> Unit,
        ): LlmResponse {
            streamCalls += 1
            lastUserTexts += messages.mapNotNull { it.content }
            onDelta("Heard all three.")
            return LlmResponse(
                message = LlmMessage(role = "assistant", content = "Heard all three."),
                usage = LlmUsage(1, 1, 1),
                rawJson = "{}",
            )
        }
    }
}
