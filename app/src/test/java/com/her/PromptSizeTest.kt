package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.agent.prompt.ContextBuilder
import com.her.agent.tools.ToolRegistry
import com.her.core.FakeClock
import com.her.data.calendar.CalendarDataSource
import com.her.data.db.HerDatabase
import com.her.data.remote.WebSearchClient
import com.her.data.repository.HerRepository
import com.her.data.retrieval.HybridRanker
import com.her.data.secure.AppSettingsStore
import com.her.domain.AgentRunType
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class PromptSizeTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
    private val settings = AppSettingsStore(context, "prompt_size_${System.nanoTime()}")
    private val repo = HerRepository(db, settings, FakeClock(Instant.parse("2026-10-07T10:00:00Z").toEpochMilli()))
    private val tools = ToolRegistry(repo, HybridRanker(repo), settings, CalendarDataSource(context), WebSearchClient())
    private val contextBuilder = ContextBuilder(repo, HybridRanker(repo), settings, CalendarDataSource(context))

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun chatPromptStaysSmallAndStaticInstructionsLead() = runBlocking {
        val specs = tools.specsFor(AgentRunType.CHAT)
        val toolChars = specs.sumOf { it.name.length + it.description.length + it.parametersJson.length }
        val built = contextBuilder.build("hello")
        val system = built.messages.first().content.orEmpty()
        println("promptSize tools=${specs.size} toolChars=$toolChars systemChars=${system.length} bundleChars=${built.systemBundle.length}")
        assertEquals(93, specs.size)
        assertTrue("tool schemas grew to $toolChars", toolChars <= TOOL_CHARS)
        assertTrue("system prompt grew to ${system.length}", system.length <= SYSTEM_CHARS)
        assertTrue("context bundle grew to ${built.systemBundle.length}", built.systemBundle.length <= BUNDLE_CHARS)
        assertTrue(system.contains("Use save_emotional_thread"))
        assertFalse(built.systemBundle.contains("Use save_emotional_thread"))
        val clockAt = built.systemBundle.indexOf("Current local time")
        assertTrue(clockAt > 0)
        assertFalse(built.systemBundle.substring(0, clockAt).contains("Your feeling"))
    }

    private companion object {
        // Just above the trimmed sizes (tools 38159, system 27410, empty bundle 776).
        const val TOOL_CHARS = 39_000
        const val SYSTEM_CHARS = 28_000
        const val BUNDLE_CHARS = 1_100
    }
}
