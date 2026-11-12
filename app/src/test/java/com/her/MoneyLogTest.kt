package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import org.json.JSONObject
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
class MoneyLogTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
    private val settings = AppSettingsStore(context, "money_${System.nanoTime()}")
    private val repo = HerRepository(db, settings, FakeClock(Instant.parse("2026-10-07T10:00:00Z").toEpochMilli()))
    private val tools = ToolRegistry(repo, HybridRanker(repo), settings, CalendarDataSource(context), WebSearchClient())

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun call(name: String, args: String) = tools.execute(name, args).also { assertTrue(it.payloadJson, it.ok) }.let { JSONObject(it.payloadJson) }

    @Test
    fun totalsAddUpPerCurrencyAndUndoRemovesOne() = runBlocking {
        val lunch = call("log_expense", """{"amount":200,"what":"lunch","category":"Food"}""")
        call("log_expense", """{"amount":50,"what":"taxi","category":"transport"}""")
        call("log_expense", """{"amount":12.5,"what":"book","currency":"EUR"}""")

        val all = call("get_expenses", "{}")
        assertEquals(3, all.getInt("count"))
        assertEquals(250.0, all.getJSONObject("totalByCurrency").getDouble("unspecified"), 0.0)
        assertEquals(12.5, all.getJSONObject("totalByCurrency").getDouble("EUR"), 0.0)
        assertEquals(1, call("get_expenses", """{"category":"food"}""").getInt("count"))

        assertTrue(tools.undo("expenses", lunch.getString("id")))
        assertEquals(50.0, call("get_expenses", "{}").getJSONObject("totalByCurrency").getDouble("unspecified"), 0.0)
    }

    @Test
    fun aZeroOrNegativeAmountIsRefused() = runBlocking {
        assertFalse(tools.execute("log_expense", """{"amount":0,"what":"nothing"}""").ok)
        assertFalse(tools.execute("log_expense", """{"amount":-5,"what":"refund"}""").ok)
    }
}
