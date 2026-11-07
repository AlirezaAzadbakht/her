package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.core.FakeClock
import com.her.data.db.HerDatabase
import com.her.data.db.TRAITS
import com.her.data.db.seedTraits
import com.her.data.repository.HerRepository
import com.her.data.secure.AppSettingsStore
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

    @After
    fun tearDown() {
        db.close()
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
}
