package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.agent.prompt.ContextBuilder
import com.her.agent.tools.ToolRegistry
import com.her.core.FakeClock
import com.her.data.calendar.CalendarDataSource
import com.her.data.db.HerDatabase
import com.her.data.db.TRAITS
import com.her.data.db.seedTraits
import com.her.data.remote.WebSearchClient
import com.her.data.retrieval.HybridRanker
import com.her.data.repository.HerRepository
import com.her.data.secure.AppSettingsStore
import com.her.domain.AgentRunType
import com.her.domain.MessageRole
import com.her.domain.MessageStatus
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class PersonaTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()
    private val settings = AppSettingsStore(context, "persona_${System.nanoTime()}")
    private val clock = FakeClock(Instant.parse("2026-11-07T10:00:00Z").toEpochMilli())
    private val repo = HerRepository(db, settings, clock)
    private val tools = ToolRegistry(repo, HybridRanker(repo), settings, CalendarDataSource(context), WebSearchClient())

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun say(text: String, role: MessageRole = MessageRole.USER): String {
        clock.advance(1_000)
        return repo.newChatMessage(role, text, MessageStatus.SENT).also { repo.saveMessage(it) }.id
    }

    private suspend fun trait(name: String) = repo.persona().first { it.trait == name }

    private suspend fun adjust(type: AgentRunType, args: String): Boolean {
        tools.runType = type
        return tools.execute("adjust_trait", args).ok
    }

    private suspend fun name(her: String) {
        val profile = repo.getProfile()
        repo.saveProfile(profile.copy(assistantName = her, version = profile.version + 1))
    }

    @Test
    fun seedComesFromTheNameAndIsSavedOnlyOnceSheHasOne() = runBlocking {
        assertEquals(seedTraits("Samantha"), seedTraits("  samantha "))
        assertNotEquals(seedTraits("Samantha"), seedTraits("Ava"))
        assertTrue(seedTraits("Ava").values.all { it in 0.35..0.85 })

        assertEquals(TRAITS, repo.persona().map { it.trait })
        assertTrue("nothing saved before she has a name", db.personaDao().all().isEmpty())

        name("Samantha")
        val seeded = repo.persona()
        assertEquals(seedTraits("Samantha"), seeded.associate { it.trait to it.value })
        name("Ava")
        assertEquals("a rename keeps who she is", seeded, repo.persona())
    }

    @Test
    fun nightlyStepWalksTowardTheTargetAndClearsIt() = runBlocking {
        name("Samantha")
        val start = repo.persona().first { it.trait == "playfulness" }
        repo.savePersonaTrait(start.copy(target = start.value + 0.12))
        repo.stepPersonaTargets()
        assertEquals(start.value + 0.05, repo.persona().first { it.trait == "playfulness" }.value, 1e-9)
        repo.stepPersonaTargets()
        repo.stepPersonaTargets()
        val done = repo.persona().first { it.trait == "playfulness" }
        assertEquals(start.value + 0.12, done.value, 1e-9)
        assertNull(done.target)

        repo.resetPersona()
        assertEquals("forgetting returns her to who she was", seedTraits("Samantha"), repo.persona().associate { it.trait to it.value })
    }

    @Test
    fun theirRequestSetsWhereSheIsHeadingAndNightDriftStaysSmall() = runBlocking {
        name("Samantha")
        val start = trait("playfulness")
        val reply = say("Sure.", MessageRole.ASSISTANT)
        assertFalse("her own words do not change her", adjust(AgentRunType.CHAT, """{"trait":"playfulness","change":0.2,"reason":"x","sourceMessageId":"$reply"}"""))
        val asked = say("Joke around with me more, be more playful.")
        assertTrue(adjust(AgentRunType.CHAT, """{"trait":"playfulness","change":0.2,"reason":"they want more jokes","sourceMessageId":"$asked"}"""))
        assertEquals("a request lands gradually", start.value, trait("playfulness").value, 1e-9)
        assertEquals(start.value + 0.2, trait("playfulness").target!!, 1e-9)
        assertFalse("too big a change", adjust(AgentRunType.CHAT, """{"trait":"warmth","change":0.5,"reason":"x","sourceMessageId":"$asked"}"""))

        clock.advance(13 * 3_600_000L)
        assertFalse("she leaves what they asked for", adjust(AgentRunType.NIGHTLY, """{"trait":"playfulness","change":-0.03,"reason":"x"}"""))
        val direct = trait("directness").value
        assertTrue(adjust(AgentRunType.NIGHTLY, """{"trait":"directness","change":0.2,"reason":"they wanted it straight"}"""))
        assertEquals("nightly drift is clamped", direct + 0.03, trait("directness").value, 1e-9)
        assertFalse("once a night", adjust(AgentRunType.NIGHTLY, """{"trait":"directness","change":0.01,"reason":"again"}"""))
        assertFalse(adjust(AgentRunType.HOURLY, """{"trait":"patience","change":0.01,"reason":"x"}"""))

        repo.savePersonaTrait(trait("directness").copy(value = direct + 0.1))
        val bundle = ContextBuilder(repo, HybridRanker(repo), settings, CalendarDataSource(context)).build("hi").systemBundle
        assertTrue(bundle.contains("Your personality now"))
        assertTrue(bundle.contains("working on being more playful"))
        assertTrue(bundle.contains("grown more direct"))
    }

    @Test
    fun forgettingEverythingResetsHerToHerName() = runBlocking {
        name("Samantha")
        repo.savePersonaTrait(trait("warmth").copy(value = 0.9, reason = "they opened up"))
        tools.runType = AgentRunType.CHAT
        val confirmId = JSONObject(tools.execute("forget_memory", """{"everything":true}""").payloadJson).getString("confirmId")
        say("Yes, forget everything.")
        assertTrue(tools.execute("forget_memory", """{"everything":true,"confirmId":"$confirmId"}""").ok)
        assertEquals(seedTraits("Samantha").getValue("warmth"), trait("warmth").value, 1e-9)
        assertNull(trait("warmth").reason)
    }
}
