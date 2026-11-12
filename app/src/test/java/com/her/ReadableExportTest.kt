package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.agent.tools.ToolRegistry
import com.her.core.FakeClock
import com.her.data.backup.ReadableExport
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
class ReadableExportTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
    private val settings = AppSettingsStore(context, "export_${System.nanoTime()}")
    private val repo = HerRepository(db, settings, FakeClock(Instant.parse("2026-10-07T10:00:00Z").toEpochMilli()))
    private val tools = ToolRegistry(repo, HybridRanker(repo), settings, CalendarDataSource(context), WebSearchClient())

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun exportListsWhatWasSavedAndSkipsEmptySections() = runBlocking {
        assertTrue(tools.execute("create_task", """{"title":"Renew passport"}""").ok)
        assertTrue(tools.execute("add_grocery", """{"name":"rice"}""").ok)
        assertTrue(tools.execute("add_grocery", """{"name":"tent","list":"packing"}""").ok)

        val text = ReadableExport.markdown(repo)
        assertTrue(text, text.contains("## Tasks\n\n- [OPEN] Renew passport"))
        assertTrue(text, text.contains("## List: groceries\n\n- [ACTIVE] rice"))
        assertTrue(text, text.contains("## List: packing\n\n- [ACTIVE] tent"))
        assertFalse(text.contains("## People"))
    }
}
