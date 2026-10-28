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
import com.her.domain.Feeling
import java.time.Instant
import kotlinx.coroutines.flow.first
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
class FeelingTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
    private val settings = AppSettingsStore(context, "feeling_${System.nanoTime()}")
    private val repo = HerRepository(db, settings, FakeClock(Instant.parse("2026-10-07T10:00:00Z").toEpochMilli()))
    private val ranker = HybridRanker(repo)
    private val calendar = CalendarDataSource(context)
    private val tools = ToolRegistry(repo, ranker, settings, calendar, WebSearchClient())
    private val contextBuilder = ContextBuilder(repo, ranker, settings, calendar)

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun newestFeelingIsCurrentAndInContext() = runBlocking {
        assertTrue(tools.execute("set_feeling", """{"feeling":"calm"}""").ok)
        assertTrue(tools.execute("set_feeling", """{"feeling":"TENDER","intensity":0.8,"note":"they shared hard news"}""").ok)

        assertEquals(listOf(Feeling.TENDER, Feeling.CALM), repo.recentFeelings().map { it.feeling })
        assertEquals(Feeling.TENDER, repo.observeFeeling().first()?.feeling)
        val bundle = contextBuilder.build().systemBundle
        assertTrue(bundle.contains("Your feeling: tender (0.8) — they shared hard news"))
        assertTrue(bundle.contains("Before that: calm"))
    }

    @Test
    fun unknownFeelingIsRejected() = runBlocking {
        assertFalse(tools.execute("set_feeling", """{"feeling":"ecstatic"}""").ok)
        assertTrue(repo.recentFeelings().isEmpty())
        assertFalse(contextBuilder.build().systemBundle.contains("Your feeling"))
    }
}
