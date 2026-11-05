package com.her.data.backup

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import com.her.core.QuietHours
import com.her.data.db.DB_VERSION
import com.her.data.db.HerDatabase
import com.her.data.secure.AppSettings
import com.her.data.secure.AppSettingsStore
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/**
 * A backup is a zip of the Room file and the settings worth carrying to another phone.
 * API keys never go in it. Import stages the file beside the live one; [applyStaged] swaps it in on the next start.
 */
object Backup {
    private const val DB = "her.db"
    private const val SETTINGS = "settings.json"
    private const val STAGED_DB = "restore.db"
    private const val STAGED_SETTINGS = "restore-settings.json"

    fun write(context: Context, db: HerDatabase, settings: AppSettings, out: OutputStream) {
        // Fold the WAL into the main file, then hold a write transaction so nothing changes it while it is copied.
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        ZipOutputStream(out).use { zip ->
            // ponytail: writers wait while the file streams to `out`; copy to a temp file first if slow destinations stall chat.
            db.runInTransaction {
                zip.putNextEntry(ZipEntry(DB))
                context.getDatabasePath(DB).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry(SETTINGS))
            zip.write(settings.toBackupJson().toString().toByteArray())
            zip.closeEntry()
        }
    }

    /** Unpacks a backup and proves Room can open it; throws with a message for the person if it cannot. */
    fun stage(context: Context, input: InputStream) {
        val staged = context.getDatabasePath(STAGED_DB)
        val stagedSettings = File(context.filesDir, STAGED_SETTINGS)
        clearStaged(context)
        try {
            var foundDb = false
            // Only the two known names are read, so an entry can never write outside these files.
            ZipInputStream(input).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    when (entry.name) {
                        DB -> {
                            staged.parentFile?.mkdirs()
                            staged.outputStream().use { zip.copyTo(it) }
                            foundDb = true
                        }
                        SETTINGS -> stagedSettings.writeText(JSONObject(zip.readBytes().decodeToString()).toString())
                    }
                }
            }
            if (!foundDb) throw BackupException("This file isn't a Her backup.")
            // Check the raw file before Room sees it: Room would quietly replace a corrupt file with an empty database.
            val version = runCatching {
                SQLiteDatabase.openDatabase(staged.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
                    raw.rawQuery("PRAGMA quick_check", null).use { c -> check(c.moveToFirst() && c.getString(0) == "ok") }
                    raw.version
                }
            }.getOrElse { throw BackupException("This backup is damaged.") }
            if (version > DB_VERSION) throw BackupException("This backup is from a newer version of Her. Update the app first.")
            if (version < 1) throw BackupException("This file isn't a Her backup.")
            // Runs the same migrations and schema check the app will on its next start.
            HerDatabase.build(context, STAGED_DB).apply {
                try { openHelper.writableDatabase } finally { close() }
            }
        } catch (e: Exception) {
            clearStaged(context)
            throw e as? BackupException ?: BackupException("This file isn't a Her backup.", e)
        }
    }

    /** Swaps a staged backup in. Call before the database is opened. */
    fun applyStaged(context: Context, settings: AppSettingsStore) {
        val staged = context.getDatabasePath(STAGED_DB)
        if (staged.exists()) {
            val live = context.getDatabasePath(DB)
            SQLiteDatabase.deleteDatabase(live)
            if (!staged.renameTo(live)) staged.copyTo(live, overwrite = true)
        }
        val stagedSettings = File(context.filesDir, STAGED_SETTINGS)
        if (stagedSettings.exists()) {
            runCatching { settings.update { it.withBackup(JSONObject(stagedSettings.readText())) } }
        }
        clearStaged(context)
    }

    /** Starts the app fresh, so the next launch opens the staged backup. */
    fun restart(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        context.startActivity(Intent.makeRestartActivityTask(launch.component))
        Runtime.getRuntime().exit(0)
    }

    private fun clearStaged(context: Context) {
        SQLiteDatabase.deleteDatabase(context.getDatabasePath(STAGED_DB))
        File(context.filesDir, STAGED_SETTINGS).delete()
    }
}

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Preferences that make sense on another phone. Keys and this phone's permissions and run bookkeeping stay out. */
fun AppSettings.toBackupJson(): JSONObject = JSONObject()
    // Reminders and schedules ring only on the device that owns them, so the restored phone takes over this id.
    .put("deviceId", deviceId)
    .put("quietStart", quietHours.startMinutes)
    .put("quietEnd", quietHours.endMinutes)
    .put("hourlyDuringQuietHours", hourlyDuringQuietHours)
    .put("developerMode", developerMode)
    .put("webSearchEnabled", webSearchEnabled)
    .put("webSearchEndpoint", webSearchEndpoint)
    .put("embeddingsEnabled", embeddingsEnabled)
    .put("embeddingModel", embeddingModel)
    .put("chatToolCallLimit", chatToolCallLimit)
    .put("memoryTabEnabled", memoryTabEnabled)
    .put("showReceipts", showReceipts)
    .put("backgroundModel", backgroundModel)
    .put("backgroundTokenCap", backgroundTokenCap)

fun AppSettings.withBackup(json: JSONObject): AppSettings = copy(
    deviceId = json.optString("deviceId").ifBlank { deviceId },
    quietHours = QuietHours(json.optInt("quietStart", quietHours.startMinutes), json.optInt("quietEnd", quietHours.endMinutes)),
    hourlyDuringQuietHours = json.optBoolean("hourlyDuringQuietHours", hourlyDuringQuietHours),
    developerMode = json.optBoolean("developerMode", developerMode),
    webSearchEnabled = json.optBoolean("webSearchEnabled", webSearchEnabled),
    webSearchEndpoint = json.optString("webSearchEndpoint", webSearchEndpoint),
    embeddingsEnabled = json.optBoolean("embeddingsEnabled", embeddingsEnabled),
    embeddingModel = json.optString("embeddingModel", embeddingModel),
    chatToolCallLimit = json.optInt("chatToolCallLimit", chatToolCallLimit),
    memoryTabEnabled = json.optBoolean("memoryTabEnabled", memoryTabEnabled),
    showReceipts = json.optBoolean("showReceipts", showReceipts),
    backgroundModel = json.optString("backgroundModel", backgroundModel),
    backgroundTokenCap = json.optLong("backgroundTokenCap", backgroundTokenCap),
)
