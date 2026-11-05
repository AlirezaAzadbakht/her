package com.her

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.her.data.backup.Backup
import com.her.data.backup.BackupException
import com.her.data.db.HerDatabase
import com.her.data.secure.AppSettingsStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class BackupTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        context.deleteDatabase("her.db")
        context.deleteDatabase("restore.db")
    }

    @Test
    fun backupRestoresDataAndSettingsButNeverKeys() {
        val source = AppSettingsStore(context, "backup_source")
        source.update { it.copy(webSearchApiKey = "secret-key", backgroundModel = "small-model", chatToolCallLimit = 7) }
        val zip = ByteArrayOutputStream()
        HerDatabase.build(context).apply {
            openHelper.writableDatabase.execSQL(
                "INSERT INTO tasks VALUES ('t1','Buy a keyboard',NULL,'OPEN',NULL,NULL,NULL,1,1,'phone',1,NULL)",
            )
            // Room refuses transactions on the main thread, which is where Robolectric runs tests.
            runBlocking(Dispatchers.IO) { Backup.write(context, this@apply, source.read(), zip) }
            close()
        }
        assertFalse(zip.toString(Charsets.ISO_8859_1.name()).contains("secret-key"))
        context.deleteDatabase("her.db")

        val target = AppSettingsStore(context, "backup_target")
        target.update { it.copy(webSearchApiKey = "this-phone-key") }
        Backup.stage(context, ByteArrayInputStream(zip.toByteArray()))
        Backup.applyStaged(context, target)

        val restored = HerDatabase.build(context)
        restored.openHelper.readableDatabase.query("SELECT title FROM tasks WHERE id = 't1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Buy a keyboard", it.getString(0))
        }
        restored.close()
        val settings = target.read()
        assertEquals("small-model", settings.backgroundModel)
        assertEquals(7, settings.chatToolCallLimit)
        assertEquals(source.read().deviceId, settings.deviceId)
        assertEquals("this-phone-key", settings.webSearchApiKey)
        assertFalse(context.getDatabasePath("restore.db").exists())
    }

    @Test
    fun rejectsFilesThatAreNotBackupsAndLeavesNothingStaged() {
        val notZip = ByteArrayInputStream("hello".toByteArray())
        assertThrows(BackupException::class.java) { Backup.stage(context, notZip) }
        val fakeDb = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("her.db"))
                zip.write("not a database".toByteArray())
                zip.closeEntry()
            }
        }
        val error = assertThrows(BackupException::class.java) { Backup.stage(context, ByteArrayInputStream(fakeDb.toByteArray())) }
        assertNotEquals("", error.message)
        assertFalse(context.getDatabasePath("restore.db").exists())
        // Nothing staged means nothing replaces the live database on the next start.
        Backup.applyStaged(context, AppSettingsStore(context, "backup_untouched"))
        assertFalse(context.getDatabasePath("her.db").exists())
    }

    @Test
    fun migrationNineToTenKeepsTasksAndDropsGoogleLeftovers() {
        val name = "migration_9_${System.nanoTime()}.db"
        HerDatabase.build(context, name).apply { openHelper.writableDatabase; close() }
        // Put back what version 9 had: the Google Tasks id, the Drive change log, and a Google calendar mirror.
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("ALTER TABLE tasks ADD COLUMN googleTaskId TEXT")
            it.execSQL("CREATE TABLE sync_ops (id TEXT NOT NULL PRIMARY KEY)")
            it.execSQL("CREATE TABLE sync_cursors (remoteDeviceId TEXT NOT NULL PRIMARY KEY)")
            it.execSQL("INSERT INTO tasks VALUES ('t1','Reply to Sara',NULL,'OPEN',NULL,NULL,NULL,1,1,'phone',1,NULL,'g-1')")
            it.execSQL("INSERT INTO calendar_events VALUES ('c1','Design review',1,2,NULL,NULL,'google:x','GOOGLE',NULL,1,1,'phone',1,NULL)")
            it.execSQL("INSERT INTO calendar_events VALUES ('c2','Dentist',1,2,NULL,NULL,NULL,'INTERNAL',NULL,1,1,'phone',1,NULL)")
            it.version = 9
        }
        val migrated = HerDatabase.build(context, name)
        val sql = migrated.openHelper.readableDatabase
        sql.query("SELECT title FROM tasks").use { assertTrue(it.moveToFirst()); assertEquals("Reply to Sara", it.getString(0)) }
        sql.query("SELECT id FROM calendar_events").use { assertTrue(it.moveToFirst()); assertEquals("c2", it.getString(0)); assertFalse(it.moveToNext()) }
        sql.query("SELECT name FROM sqlite_master WHERE name LIKE 'sync_%'").use { assertFalse(it.moveToFirst()) }
        migrated.close()
        context.deleteDatabase(name)
    }
}
