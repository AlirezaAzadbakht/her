package com.her.reminders

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import com.her.data.secure.AppSettingsStore
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Where the phone is right now, for saving a place by being there. */
interface CurrentLocation {
    /** Why a place can't be saved right now ("disabled", "location_permission", "location_off"), or null when it can. */
    fun problem(): String?

    /** Whether alerts can fire with Her closed, which needs "Allow all the time". */
    fun backgroundAllowed(): Boolean

    /** The current position, or null if none came within the timeout. */
    suspend fun fix(): Pair<Double, Double>?

    companion object {
        val NONE: CurrentLocation = object : CurrentLocation {
            override fun problem() = "location_permission"
            override fun backgroundAllowed() = false
            override suspend fun fix(): Pair<Double, Double>? = null
        }
    }
}

class AndroidLocation(private val context: Context, private val settings: AppSettingsStore) : CurrentLocation {
    private val manager: LocationManager? = context.getSystemService(LocationManager::class.java)

    override fun problem(): String? = when {
        !settings.read().placeRemindersEnabled -> "disabled"
        !granted(Manifest.permission.ACCESS_FINE_LOCATION) -> "location_permission"
        manager == null || !LocationManagerCompat.isLocationEnabled(manager) -> "location_off"
        else -> null
    }

    override fun backgroundAllowed(): Boolean = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    /**
     * Alerts may be registered: the setting is on and location is allowed all the time. Whether location is switched
     * on doesn't matter here: while it is off, Android keeps the alerts inactive and doesn't poll.
     */
    fun watching(): Boolean = problem().let { it == null || it == "location_off" } && backgroundAllowed()

    override suspend fun fix(): Pair<Double, Double>? {
        val manager = manager ?: return null
        if (problem() != null) return null
        val provider = listOf(FUSED, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { it in manager.allProviders && manager.isProviderEnabled(it) } ?: return null
        return withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                try {
                    LocationManagerCompat.getCurrentLocation(manager, provider, cancel, ContextCompat.getMainExecutor(context)) { loc ->
                        if (cont.isActive) cont.resume(loc?.let { it.latitude to it.longitude })
                    }
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        val FUSED = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else "fused"
        const val FIX_TIMEOUT_MS = 30_000L
    }
}
