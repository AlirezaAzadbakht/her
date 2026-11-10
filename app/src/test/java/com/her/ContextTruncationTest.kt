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
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ContextTruncationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
    private val settings = AppSettingsStore(context, "truncation_${System.nanoTime()}")
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
    fun aCutListSaysHowManyRowsAreMissing() = runBlocking {
        repeat(3) { assertTrue(tools.execute("create_task", """{"title":"Task $it"}""").ok) }
        assertFalse(contextBuilder.build().systemBundle.contains("more not shown"))

        repeat(11) { assertTrue(tools.execute("create_task", """{"title":"Extra $it"}""").ok) }
        val tasks = contextBuilder.build().systemBundle.substringAfter("\nTasks:\n").substringBefore("\n\n")
        assertTrue(tasks, tasks.contains("… and 2 more not shown"))
    }
}
