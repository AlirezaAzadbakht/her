package com.her.data.secure

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.her.core.QuietHours
import com.her.core.newId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LlmSettings(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

class SecureSettingsStore(context: Context) {
    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            "her_secure_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun read(): LlmSettings = LlmSettings(
        baseUrl = prefs.getString(KEY_BASE, "") ?: "",
        apiKey = prefs.getString(KEY_API, "") ?: "",
        model = prefs.getString(KEY_MODEL, "") ?: "",
    )

    fun write(settings: LlmSettings) {
        prefs.edit {
            putString(KEY_BASE, settings.baseUrl.trim().trimEnd('/'))
            putString(KEY_API, settings.apiKey.trim())
            putString(KEY_MODEL, settings.model.trim())
        }
    }

    companion object {
        private const val KEY_BASE = "llm_base_url"
        private const val KEY_API = "llm_api_key"
        private const val KEY_MODEL = "llm_model"
    }
}

data class AppSettings(
    val deviceId: String,
    val quietHours: QuietHours,
    val developerMode: Boolean,
    val webSearchEnabled: Boolean,
    val embeddingsEnabled: Boolean,
    val googleClientId: String,
    val webSearchEndpoint: String,
    val webSearchApiKey: String,
    val calendarEnabled: Boolean,
    val driveEnabled: Boolean,
    val chatToolCallLimit: Int,
    val lastHourlyRunAt: Long,
    val lastNightlyDate: String,
    val lastBriefingDate: String,
    val onboardingSeeded: Boolean,
    val apiConfiguredOnce: Boolean,
    val lastNotificationKey: String,
    val lastNotificationAt: Long,
    val memoryTabEnabled: Boolean,
    val runtimePermissionsAsked: Boolean,
    val hourlyDuringQuietHours: Boolean = false,
    val showReceipts: Boolean = true,
)

class AppSettingsStore(context: Context, prefsName: String = "her_app_settings") {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    fun read(): AppSettings {
        val deviceId = prefs.getString(KEY_DEVICE, null) ?: newId().also {
            prefs.edit { putString(KEY_DEVICE, it) }
        }
        return AppSettings(
            deviceId = deviceId,
            quietHours = QuietHours(
                startMinutes = prefs.getInt(KEY_QH_START, 23 * 60 + 30),
                endMinutes = prefs.getInt(KEY_QH_END, 8 * 60),
            ),
            developerMode = prefs.getBoolean(KEY_DEV, false),
            webSearchEnabled = prefs.getBoolean(KEY_WEB, false),
            embeddingsEnabled = prefs.getBoolean(KEY_EMBED, false),
            googleClientId = prefs.getString(KEY_GOOGLE, "") ?: "",
            webSearchEndpoint = prefs.getString(KEY_WEB_URL, "") ?: "",
            webSearchApiKey = prefs.getString(KEY_WEB_KEY, "") ?: "",
            calendarEnabled = prefs.getBoolean(KEY_CAL, false),
            driveEnabled = prefs.getBoolean(KEY_DRIVE, false),
            chatToolCallLimit = prefs.getInt(KEY_CHAT_LIMIT, 12),
            lastHourlyRunAt = prefs.getLong(KEY_HOURLY, 0L),
            lastNightlyDate = prefs.getString(KEY_NIGHTLY, "") ?: "",
            lastBriefingDate = prefs.getString(KEY_BRIEFING, "") ?: "",
            onboardingSeeded = prefs.getBoolean(KEY_ONBOARD, false),
            apiConfiguredOnce = prefs.getBoolean(KEY_API_ONCE, false),
            lastNotificationKey = prefs.getString(KEY_NOTIF, "") ?: "",
            lastNotificationAt = prefs.getLong(KEY_NOTIF_AT, 0L),
            memoryTabEnabled = prefs.getBoolean(KEY_MEMORY_TAB, true),
            runtimePermissionsAsked = prefs.getBoolean(KEY_PERM_ASKED, false),
            hourlyDuringQuietHours = prefs.getBoolean(KEY_HOURLY_QUIET, false),
            showReceipts = prefs.getBoolean(KEY_RECEIPTS, true),
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(read())
        prefs.edit {
            putString(KEY_DEVICE, next.deviceId)
            putInt(KEY_QH_START, next.quietHours.startMinutes)
            putInt(KEY_QH_END, next.quietHours.endMinutes)
            putBoolean(KEY_DEV, next.developerMode)
            putBoolean(KEY_WEB, next.webSearchEnabled)
            putBoolean(KEY_EMBED, next.embeddingsEnabled)
            putString(KEY_GOOGLE, next.googleClientId)
            putString(KEY_WEB_URL, next.webSearchEndpoint)
            putString(KEY_WEB_KEY, next.webSearchApiKey)
            putBoolean(KEY_CAL, next.calendarEnabled)
            putBoolean(KEY_DRIVE, next.driveEnabled)
            putInt(KEY_CHAT_LIMIT, next.chatToolCallLimit)
            putLong(KEY_HOURLY, next.lastHourlyRunAt)
            putString(KEY_NIGHTLY, next.lastNightlyDate)
            putString(KEY_BRIEFING, next.lastBriefingDate)
            putBoolean(KEY_ONBOARD, next.onboardingSeeded)
            putBoolean(KEY_API_ONCE, next.apiConfiguredOnce)
            putString(KEY_NOTIF, next.lastNotificationKey)
            putLong(KEY_NOTIF_AT, next.lastNotificationAt)
            putBoolean(KEY_MEMORY_TAB, next.memoryTabEnabled)
            putBoolean(KEY_PERM_ASKED, next.runtimePermissionsAsked)
            putBoolean(KEY_HOURLY_QUIET, next.hourlyDuringQuietHours)
            putBoolean(KEY_RECEIPTS, next.showReceipts)
        }
        _state.value = next
    }

    fun readScheduleFingerprint(): String = prefs.getString(KEY_SCHEDULE, "") ?: ""

    fun writeScheduleFingerprint(value: String) {
        prefs.edit { putString(KEY_SCHEDULE, value) }
    }

    companion object {
        private const val KEY_DEVICE = "device_id"
        private const val KEY_QH_START = "quiet_start"
        private const val KEY_QH_END = "quiet_end"
        private const val KEY_DEV = "developer_mode"
        private const val KEY_WEB = "web_search"
        private const val KEY_EMBED = "embeddings"
        private const val KEY_GOOGLE = "google_client_id"
        private const val KEY_WEB_URL = "web_search_url"
        private const val KEY_WEB_KEY = "web_search_key"
        private const val KEY_CAL = "calendar_enabled"
        private const val KEY_DRIVE = "drive_enabled"
        private const val KEY_CHAT_LIMIT = "chat_tool_limit"
        private const val KEY_HOURLY = "last_hourly"
        private const val KEY_NIGHTLY = "last_nightly"
        private const val KEY_BRIEFING = "last_briefing"
        private const val KEY_ONBOARD = "onboarding_seeded"
        private const val KEY_API_ONCE = "api_configured_once"
        private const val KEY_NOTIF = "last_notif_key"
        private const val KEY_NOTIF_AT = "last_notif_at"
        private const val KEY_MEMORY_TAB = "memory_tab_enabled"
        private const val KEY_PERM_ASKED = "runtime_permissions_asked"
        private const val KEY_HOURLY_QUIET = "hourly_during_quiet_hours"
        private const val KEY_RECEIPTS = "show_receipts"
        private const val KEY_SCHEDULE = "schedule_fingerprint"
    }
}
