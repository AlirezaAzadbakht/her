package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.core.FakeClock
import com.her.core.QuietHours
import com.her.data.db.HerDatabase
import com.her.data.repository.HerRepository
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
class QuietHoursProfileZoneTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java).allowMainThreadQueries().build()

    // 22:30 UTC is already 02:00 in Tehran.
    private val repo = HerRepository(
        db,
        AppSettingsStore(context, "quiet_zone_test_${System.nanoTime()}"),
        FakeClock(Instant.parse("2026-09-12T22:30:00Z").toEpochMilli()),
    )

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun quietHoursFollowTheProfileTimezone() = runBlocking {
        val hours = QuietHours(startMinutes = 23 * 60 + 30, endMinutes = 8 * 60)

        repo.saveProfile(repo.getProfile().copy(timezone = "UTC"))
        assertFalse("22:30 is before quiet hours start", repo.inQuietHours(hours))

        repo.saveProfile(repo.getProfile().copy(timezone = "Asia/Tehran"))
        assertTrue("02:00 on their clock is inside quiet hours", repo.inQuietHours(hours))
    }
}
