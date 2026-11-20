package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.agent.tools.ToolRegistry
import com.her.core.FakeClock
import com.her.data.calendar.CalendarDataSource
import com.her.data.contacts.ContactsSource
import com.her.data.contacts.MessageComposer
import com.her.data.contacts.PhoneContact
import com.her.data.db.HerDatabase
import com.her.data.remote.WebSearchClient
import com.her.data.repository.HerRepository
import com.her.data.retrieval.HybridRanker
import com.her.data.secure.AppSettingsStore
import com.her.domain.AgentRunType
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ComposeMessageTest {
    private lateinit var db: HerDatabase
    private lateinit var settings: AppSettingsStore
    private lateinit var tools: ToolRegistry
    private val opened = mutableListOf<Pair<String, String>>()
    private val shared = mutableListOf<Pair<String, String?>>()
    private val book = listOf(
        PhoneContact("Mom", listOf("+98 912 000 0001"), null),
        PhoneContact("Reza Karimi", listOf("0912 111 1111"), null),
        PhoneContact("Reza Ahmadi", listOf("0912 222 2222"), null),
    )

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
        settings = AppSettingsStore(context, "compose_test_${System.nanoTime()}")
        val repo = HerRepository(db, settings, FakeClock(Instant.parse("2026-09-12T10:00:00Z").toEpochMilli()))
        tools = ToolRegistry(
            repo,
            HybridRanker(repo),
            settings,
            CalendarDataSource(context),
            WebSearchClient(),
            contacts = ContactsSource { q -> book.filter { it.name.contains(q, ignoreCase = true) } },
            composer = object : MessageComposer {
                override fun compose(phone: String, text: String): Result<Unit> { opened += phone to text; return Result.success(Unit) }
                override fun share(text: String, subject: String?): Result<Unit> { shared += text to subject; return Result.success(Unit) }
            },
        )
    }

    @After
    fun tearDown() = db.close()

    private fun compose(to: String) = runBlocking {
        tools.execute("compose_message", JSONObject().put("to", to).put("text", "Happy birthday").toString())
    }

    @Test
    fun aNumberOpensEvenWithContactsOff() {
        assertTrue(compose("+98 912 333 3333").ok)
        assertEquals("+98 912 333 3333" to "Happy birthday", opened.single())
    }

    @Test
    fun aNameNeedsContactsAccess() {
        assertFalse(compose("Mom").ok)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun oneMatchOpensWithItsNumber() {
        settings.update { it.copy(contactsEnabled = true) }
        assertTrue(compose("mom").ok)
        assertEquals("+98 912 000 0001", opened.single().first)
    }

    @Test
    fun twoMatchesAskWhichOne() {
        settings.update { it.copy(contactsEnabled = true) }
        val result = compose("Reza")
        assertFalse(result.ok)
        assertEquals(2, JSONObject(result.payloadJson).getJSONArray("candidates").length())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun backgroundPassesCannotOpenATextApp() {
        tools.runType = AgentRunType.HOURLY
        assertFalse(compose("+98 912 333 3333").ok)
        assertTrue(opened.isEmpty())
    }

    private fun share(args: JSONObject) = runBlocking { tools.execute("share_text", args.toString()) }

    @Test
    fun shareOpensTheSheetWithTextAndSubject() {
        assertTrue(share(JSONObject().put("text", "See you at 6").put("subject", "Dinner")).ok)
        assertTrue(share(JSONObject().put("text", "On my way")).ok)
        assertEquals(listOf("See you at 6" to "Dinner", "On my way" to null), shared)
    }

    @Test
    fun backgroundPassesCannotShare() {
        tools.runType = AgentRunType.NIGHTLY
        assertFalse(share(JSONObject().put("text", "On my way")).ok)
        assertTrue(shared.isEmpty())
    }
}
