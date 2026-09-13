package com.her.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.her.core.QuietHours
import com.her.data.secure.LlmSettings
import com.her.ui.HerViewModel
import com.her.ui.onboarding.QuietField
import com.her.ui.onboarding.rememberCalendarPermissionRequester
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen(vm: HerViewModel) {
    val app by vm.settings.collectAsState()
    val usage by vm.usage.collectAsState()
    val activity by vm.activity.collectAsState()
    val debug by vm.debugEvents.collectAsState()
    val runs by vm.runs.collectAsState()
    val connection by vm.connectionMessage.collectAsState()
    val google by vm.googleMessage.collectAsState()
    val googleConsent by vm.googleConsent.collectAsState()
    val googleConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        vm.finishGoogleConnect()
    }
    LaunchedEffect(googleConsent) {
        val pending = googleConsent ?: return@LaunchedEffect
        vm.clearGoogleConsent()
        googleConsentLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
    }
    val llm = vm.llmSettings()
    var base by remember { mutableStateOf(llm.baseUrl) }
    var key by remember { mutableStateOf(llm.apiKey) }
    var model by remember { mutableStateOf(llm.model) }
    var quietStart by remember { mutableStateOf(formatMinutes(app.quietHours.startMinutes)) }
    var quietEnd by remember { mutableStateOf(formatMinutes(app.quietHours.endMinutes)) }
    var googleClient by remember { mutableStateOf(app.googleClientId) }
    var searchUrl by remember { mutableStateOf(app.webSearchEndpoint) }
    var advanced by remember { mutableStateOf(false) }
    val requestCalendar = rememberCalendarPermissionRequester {
        vm.updateSettings { it.copy(calendarEnabled = true) }
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var unrestrictedBattery by remember {
        mutableStateOf(isIgnoringBatteryOptimizations(context))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                unrestrictedBattery = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Settings", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, letterSpacing = 1.4.sp, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            QuietField("Base URL", base) { base = it }
            QuietField("API key", key, secret = true) { key = it }
            QuietField("Model", model) { model = it }
            Row {
                TextButton(onClick = {
                    vm.saveAndTest(LlmSettings(base, key, model))
                }) { Text("Save & test") }
            }
            connection?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        item {
            QuietField("Quiet hours start (HH:mm)", quietStart) { quietStart = it }
            QuietField("Quiet hours end (HH:mm)", quietEnd) { quietEnd = it }
            TextButton(onClick = {
                val start = parseMinutes(quietStart) ?: return@TextButton
                val end = parseMinutes(quietEnd) ?: return@TextButton
                vm.updateSettings { it.copy(quietHours = QuietHours(start, end)) }
            }) { Text("Save quiet hours") }
            Toggle("Hourly during quiet hours", app.hourlyDuringQuietHours) {
                vm.updateSettings { it.copy(hourlyDuringQuietHours = !it.hourlyDuringQuietHours) }
            }
            if (unrestrictedBattery) {
                Text(
                    "Background work is unrestricted.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            } else {
                Text(
                    "Hourly and nightly need to run when the app is closed.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                TextButton(onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${context.packageName}"))
                    runCatching { context.startActivity(intent) }
                }) { Text("Allow background work") }
            }
        }
        item {
            Toggle("Calendar access", app.calendarEnabled) {
                if (app.calendarEnabled) {
                    vm.updateSettings { it.copy(calendarEnabled = false) }
                } else {
                    requestCalendar()
                }
            }
        }
        item { Toggle("Drive sync", app.driveEnabled) { vm.updateSettings { it.copy(driveEnabled = !it.driveEnabled) } } }
        item { Toggle("Web search", app.webSearchEnabled) { vm.updateSettings { it.copy(webSearchEnabled = !it.webSearchEnabled) } } }
        item { Toggle("Embeddings (optional)", app.embeddingsEnabled) { vm.updateSettings { it.copy(embeddingsEnabled = !it.embeddingsEnabled) } } }
        item {
            QuietField("Google OAuth client ID", googleClient) {
                googleClient = it
                vm.updateSettings { s -> s.copy(googleClientId = it) }
            }
            QuietField("Web search endpoint (optional)", searchUrl) {
                searchUrl = it
                vm.updateSettings { s -> s.copy(webSearchEndpoint = it) }
            }
            TextButton(onClick = { vm.connectGoogle() }) { Text("Connect Google") }
            google?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        item {
            Text("API today", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, letterSpacing = 1.2.sp)
            val u = usage
            if (u == null) {
                Text("No requests yet.", color = MaterialTheme.colorScheme.onBackground)
            } else {
                Text(
                    "${u.requests} requests · ${u.inputTokens} in / ${u.outputTokens} out · chat ${u.chatCalls} · hourly ${u.hourlyCalls} · nightly ${u.nightlyCalls} · errors ${u.errors} · avg ${if (u.requests == 0) 0 else u.totalLatencyMs / u.requests} ms",
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        item { AboutLine() }
        item {
            TextButton(onClick = { advanced = !advanced }) {
                Text(if (advanced) "Hide advanced" else "Advanced")
            }
        }
        if (advanced) {
            item {
                Text("Experimental", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, letterSpacing = 1.2.sp)
                Toggle("Memory navigator (SQL)", app.memoryTabEnabled) {
                    vm.updateSettings { it.copy(memoryTabEnabled = !it.memoryTabEnabled) }
                }
            }
            item {
                Text("Developer", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, letterSpacing = 1.2.sp)
                Toggle("Developer mode", app.developerMode) { vm.updateSettings { it.copy(developerMode = !it.developerMode) } }
                Row {
                    TextButton(onClick = { vm.runHourly() }) { Text("Run hourly") }
                    TextButton(onClick = { vm.runNightly() }) { Text("Run nightly") }
                    TextButton(onClick = { vm.runSync() }) { Text("Sync now") }
                }
            }
            item {
                Text("Activity", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, letterSpacing = 1.2.sp)
            }
            items(activity.take(30), key = { it.id }) { entry ->
                Text("${entry.category}: ${entry.message}", color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp)
            }
            item { Text("Agent runs", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, letterSpacing = 1.2.sp) }
            items(runs.take(20), key = { it.id }) { run ->
                Text("${run.type} ${run.status} calls=${run.callsUsed} ${run.error ?: ""}", color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp)
            }
            item { Text("Debug (keys redacted)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, letterSpacing = 1.2.sp) }
            items(debug.take(20), key = { it.id }) { event ->
                Text("${event.kind}\n${event.payload.take(500)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}

private const val GITHUB_URL = "https://github.com/AlirezaAzadbakht/her"

@Composable
private fun AboutLine() {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Built for everyone 🌱 · Open on ",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        Text(
            "GitHub",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable { uriHandler.openUri(GITHUB_URL) },
        )
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onBackground)
        Switch(checked = checked, onCheckedChange = { onClick() })
    }
}

private fun formatMinutes(minutes: Int): String =
    LocalTime.of(minutes / 60, minutes % 60).format(DateTimeFormatter.ofPattern("HH:mm"))

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java) ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun parseMinutes(value: String): Int? {
    val parts = value.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}
