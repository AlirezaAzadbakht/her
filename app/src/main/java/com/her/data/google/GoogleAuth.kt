package com.her.data.google

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.her.data.secure.AppSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GoogleAuthService(
    private val context: Context,
    private val settings: AppSettingsStore,
) {
    data class Outcome(
        val accessToken: String? = null,
        val pendingIntent: PendingIntent? = null,
        val message: String,
    )

    suspend fun authorize(drive: Boolean, calendar: Boolean): Outcome {
        val clientId = settings.read().googleClientId.trim()
        if (clientId.isBlank()) {
            return Outcome(message = "Add a Google OAuth client ID in Settings before connecting Google services.")
        }
        val scopes = buildList {
            if (drive) add(Scope(DRIVE_APPDATA))
            if (calendar) add(Scope(CALENDAR))
        }
        if (scopes.isEmpty()) return Outcome(message = "No Google scopes requested.")
        return try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(scopes)
                .requestOfflineAccess(clientId)
                .build()
            val result: AuthorizationResult = withContext(Dispatchers.IO) {
                Tasks.await(Identity.getAuthorizationClient(context).authorize(request))
            }
            if (result.hasResolution()) {
                Outcome(pendingIntent = result.pendingIntent, message = "Google needs your permission.")
            } else {
                Outcome(accessToken = result.accessToken, message = "Connected.")
            }
        } catch (e: Exception) {
            Outcome(message = e.message ?: "Google authorization failed.")
        }
    }

    companion object {
        const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
        const val CALENDAR = "https://www.googleapis.com/auth/calendar.readonly"
    }
}
