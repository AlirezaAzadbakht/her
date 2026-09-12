package com.her.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

object RuntimePermissions {
    fun calendar(): Array<String> = arrayOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    fun firstRun(): Array<String> = buildList {
        addAll(calendar())
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    fun hasCalendar(context: Context): Boolean =
        calendar().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

    fun calendarGranted(result: Map<String, Boolean>): Boolean =
        calendar().all { result[it] == true }
}

@Composable
fun RequestFirstRunPermissions(
    asked: Boolean,
    onFinished: (calendarGranted: Boolean) -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        onFinished(RuntimePermissions.calendarGranted(result))
    }
    LaunchedEffect(asked) {
        if (asked) return@LaunchedEffect
        launcher.launch(RuntimePermissions.firstRun())
    }
}

@Composable
fun rememberCalendarPermissionRequester(
    onGranted: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (RuntimePermissions.calendarGranted(result)) onGranted()
    }
    return {
        if (RuntimePermissions.hasCalendar(context)) {
            onGranted()
        } else {
            launcher.launch(RuntimePermissions.calendar())
        }
    }
}
