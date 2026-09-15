package com.her.ui.settings

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.her.core.QuietHours
import com.her.data.secure.LlmSettings
import com.her.ui.HerViewModel
import com.her.ui.components.HerButton
import com.her.ui.components.HerButtonTone
import com.her.ui.components.HerSwitch
import com.her.ui.components.HerTextField
import com.her.ui.components.SectionCard
import com.her.ui.onboarding.rememberCalendarPermissionRequester
import com.her.ui.theme.ConversationStyle
import com.her.ui.theme.Her
import com.her.ui.theme.HerMotion
import com.her.ui.theme.rememberEntrance
import com.her.ui.theme.rememberHerHaptics
import com.her.ui.theme.rise
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong
import kotlinx.coroutines.delay

private const val GITHUB_URL = "https://github.com/AlirezaAzadbakht/her"
private const val ORBS_URL = "https://github.com/Jakubantalik/Libraries.dev/tree/main/packages/thinking-orbs"

// HerViewModel.saveAndTest reports success with exactly this message.
private const val Connected = "Connected."

// Sections rise in on first open; after this window they stay put when scrolled back into view.
private const val IntroWindowMs = 1400L

private enum class QuietEdge { Start, End }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: HerViewModel) {
    val app by vm.settings.collectAsState()
    val usage by vm.usage.collectAsState()
    val activity by vm.activity.collectAsState()
    val debug by vm.debugEvents.collectAsState()
    val runs by vm.runs.collectAsState()
    val connection by vm.connectionMessage.collectAsState()
    val checking by vm.checkingConnection.collectAsState()
    val google by vm.googleMessage.collectAsState()
    val googleConsent by vm.googleConsent.collectAsState()
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHerHaptics()
    var connectingGoogle by remember { mutableStateOf(false) }
    val googleConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        vm.finishGoogleConnect()
    }
    LaunchedEffect(googleConsent) {
        val pending = googleConsent ?: return@LaunchedEffect
        connectingGoogle = false
        vm.clearGoogleConsent()
        googleConsentLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
    }
    LaunchedEffect(google) { if (google != null) connectingGoogle = false }

    val llm = remember { vm.llmSettings() }
    var base by remember { mutableStateOf(llm.baseUrl) }
    var key by remember { mutableStateOf(llm.apiKey) }
    var model by remember { mutableStateOf(llm.model) }
    var googleClient by remember { mutableStateOf(app.googleClientId) }
    var searchUrl by remember { mutableStateOf(app.webSearchEndpoint) }
    var searchKey by remember { mutableStateOf(app.webSearchApiKey) }
    var embeddingModel by remember { mutableStateOf(app.embeddingModel) }
    var advanced by remember { mutableStateOf(false) }
    var editingQuiet by remember { mutableStateOf<QuietEdge?>(null) }

    var testedHere by remember { mutableStateOf(false) }
    var connectedCheck by remember { mutableStateOf(false) }
    var keyShake by remember { mutableIntStateOf(0) }
    LaunchedEffect(checking) {
        if (checking) {
            connectedCheck = false
            return@LaunchedEffect
        }
        if (!testedHere) return@LaunchedEffect
        testedHere = false
        if (vm.connectionMessage.value == Connected) {
            haptics.confirm()
            connectedCheck = true
            delay(1600)
            connectedCheck = false
        } else {
            haptics.reject()
            keyShake += 1
        }
    }

    val requestCalendar = rememberCalendarPermissionRequester {
        vm.updateSettings { it.copy(calendarEnabled = true) }
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var unrestrictedBattery by remember {
        mutableStateOf(isIgnoringBatteryOptimizations(context))
    }
    var exactReminders by remember { mutableStateOf(canScheduleExactAlarms(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                unrestrictedBattery = isIgnoringBatteryOptimizations(context)
                exactReminders = canScheduleExactAlarms(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var intro by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(IntroWindowMs)
        intro = false
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item(key = "title") {
            Text(
                "Settings",
                style = ConversationStyle.copy(fontSize = 30.sp, lineHeight = 38.sp),
                color = scheme.onBackground,
                modifier = Modifier.animateItem().introRise(0, intro),
            )
        }
        item(key = "mind") {
            SectionCard("Her mind", Modifier.animateItem().introRise(1, intro)) {
                HerTextField("Base URL", base, shakeTrigger = keyShake) { base = it }
                HerTextField("API key", key, secret = true, shakeTrigger = keyShake) { key = it }
                HerTextField("Model", model, shakeTrigger = keyShake) { model = it }
                HerButton(
                    "Save & test",
                    onClick = {
                        testedHere = true
                        vm.saveAndTest(LlmSettings(base, key, model))
                    },
                    loading = checking,
                    loadingText = "Checking…",
                    success = connectedCheck,
                )
                AnimatedVisibility(
                    visible = !checking && !connectedCheck && connection != null,
                    enter = expandVertically(HerMotion.enter()) + fadeIn(HerMotion.enter()),
                    exit = shrinkVertically(HerMotion.exit()) + fadeOut(HerMotion.exit()),
                ) {
                    Hint(connection.orEmpty(), Modifier.padding(top = 6.dp))
                }
            }
            if (!exactReminders && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Text(
                    "Reminders can arrive a few minutes late until exact alarms are allowed.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                TextButton(onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(Uri.parse("package:${context.packageName}"))
                    runCatching { context.startActivity(intent) }
                }) { Text("Allow exact reminders") }
            }
        }
        item(key = "quiet") {
            SectionCard("Quiet hours", Modifier.animateItem().introRise(2, intro)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TimeChip("From", app.quietHours.startMinutes, Modifier.weight(1f)) { editingQuiet = QuietEdge.Start }
                    TimeChip("Until", app.quietHours.endMinutes, Modifier.weight(1f)) { editingQuiet = QuietEdge.End }
                }
                ToggleRow("Hourly during quiet hours", app.hourlyDuringQuietHours) {
                    vm.updateSettings { it.copy(hourlyDuringQuietHours = !it.hourlyDuringQuietHours) }
                }
                if (unrestrictedBattery) {
                    Hint("Background work is unrestricted.")
                } else {
                    Hint("Hourly and nightly need to run when the app is closed.")
                    HerButton(
                        "Allow background work",
                        tone = HerButtonTone.Quiet,
                        onClick = {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:${context.packageName}"))
                            runCatching { context.startActivity(intent) }
                        },
                    )
                }
            }
        }
        item(key = "connections") {
            SectionCard("Connections", Modifier.animateItem().introRise(3, intro)) {
                ToggleRow("Calendar access", app.calendarEnabled) {
                    if (app.calendarEnabled) {
                        vm.updateSettings { it.copy(calendarEnabled = false) }
                    } else {
                        requestCalendar()
                    }
                }
                ToggleRow(
                    "Drive sync",
                    app.driveEnabled,
                    description = "Keeps her records in step across your devices through a private Google Drive app folder. API keys never sync.",
                ) { vm.updateSettings { it.copy(driveEnabled = !it.driveEnabled) } }
                ToggleRow(
                    "Web search",
                    app.webSearchEnabled,
                    description = "Uses the same Base URL, API key, and model.",
                ) { vm.updateSettings { it.copy(webSearchEnabled = !it.webSearchEnabled) } }
                ToggleRow(
                    "Embeddings",
                    app.embeddingsEnabled,
                    description = "Finds memories by meaning, not only shared words.",
                ) { vm.updateSettings { it.copy(embeddingsEnabled = !it.embeddingsEnabled) } }
                AnimatedVisibility(
                    visible = app.embeddingsEnabled,
                    enter = expandVertically(HerMotion.enter()) + fadeIn(HerMotion.enter()),
                    exit = shrinkVertically(HerMotion.exit()) + fadeOut(HerMotion.exit()),
                ) {
                    Column(Modifier.padding(top = 8.dp)) {
                        HerTextField("Embedding model", embeddingModel) {
                            embeddingModel = it
                            vm.updateSettings { s -> s.copy(embeddingModel = it) }
                        }
                        Hint("Uses the same Base URL and API key; memories are embedded as she searches them.")
                    }
                }
                Spacer(Modifier.height(12.dp))
                HerTextField("Google OAuth client ID", googleClient) {
                    googleClient = it
                    vm.updateSettings { s -> s.copy(googleClientId = it) }
                }
                HerTextField("Web search endpoint (optional override)", searchUrl) {
                    searchUrl = it
                    vm.updateSettings { s -> s.copy(webSearchEndpoint = it) }
                }
                HerTextField("Web search API key (optional)", searchKey, secret = true) {
                    searchKey = it
                    vm.updateSettings { s -> s.copy(webSearchApiKey = it) }
                }
                HerButton(
                    "Connect Google",
                    onClick = {
                        // Clear the old answer so the same answer again still ends the wait.
                        vm.googleMessage.value = null
                        connectingGoogle = true
                        vm.connectGoogle()
                    },
                    loading = connectingGoogle,
                    loadingText = "Connecting…",
                )
                AnimatedVisibility(
                    visible = google != null,
                    enter = expandVertically(HerMotion.enter()) + fadeIn(HerMotion.enter()),
                    exit = shrinkVertically(HerMotion.exit()) + fadeOut(HerMotion.exit()),
                ) {
                    Hint(google.orEmpty(), Modifier.padding(top = 6.dp))
                }
            }
        }
        item(key = "today") {
            SectionCard("Today", Modifier.animateItem().introRise(4, intro)) {
                val u = usage
                if (u == null) {
                    Hint("No requests yet.")
                } else {
                    Row(Modifier.fillMaxWidth()) {
                        Stat("requests", u.requests, Modifier.weight(1f))
                        Stat("tokens in", u.inputTokens, Modifier.weight(1f))
                        Stat("tokens out", u.outputTokens, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Stat("errors", u.errors, Modifier.weight(1f))
                        Stat("avg ms", if (u.requests == 0) 0 else u.totalLatencyMs / u.requests, Modifier.weight(1f))
                        Spacer(Modifier.weight(1f))
                    }
                    Hint("chat ${u.chatCalls} · hourly ${u.hourlyCalls} · nightly ${u.nightlyCalls}")
                }
            }
        }
        item(key = "advanced-toggle") {
            AdvancedToggle(advanced, Modifier.animateItem().introRise(5, intro)) { advanced = !advanced }
        }
        if (advanced) {
            item(key = "experimental") {
                SectionCard("Experimental", Modifier.animateItem()) {
                    ToggleRow("Memory navigator (SQL)", app.memoryTabEnabled) {
                        vm.updateSettings { it.copy(memoryTabEnabled = !it.memoryTabEnabled) }
                    }
                    ToggleRow("Show what she saved", app.showReceipts) {
                        vm.updateSettings { it.copy(showReceipts = !it.showReceipts) }
                    }
                }
            }
            item(key = "developer") {
                SectionCard("Developer", Modifier.animateItem()) {
                    ToggleRow("Developer mode", app.developerMode) {
                        vm.updateSettings { it.copy(developerMode = !it.developerMode) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        HerButton("Run hourly", tone = HerButtonTone.Quiet, onClick = { vm.runHourly() })
                        HerButton("Run nightly", tone = HerButtonTone.Quiet, onClick = { vm.runNightly() })
                        HerButton("Sync now", tone = HerButtonTone.Quiet, onClick = { vm.runSync() })
                    }
                }
            }
            item(key = "activity") {
                SectionCard("Activity", Modifier.animateItem()) {
                    activity.take(30).forEach { entry -> LogCard("${entry.category}: ${entry.message}") }
                }
            }
            item(key = "runs") {
                SectionCard("Agent runs", Modifier.animateItem()) {
                    runs.take(20).forEach { run -> LogCard("${run.type} ${run.status} calls=${run.callsUsed} ${run.error ?: ""}") }
                }
            }
            item(key = "debug") {
                SectionCard("Debug (keys redacted)", Modifier.animateItem()) {
                    debug.take(20).forEach { event -> LogCard("${event.kind}\n${event.payload.take(500)}", muted = true) }
                }
            }
        }
        item(key = "about") { AboutLine(Modifier.animateItem()) }
    }

    editingQuiet?.let { edge ->
        val start = edge == QuietEdge.Start
        QuietTimeDialog(
            title = if (start) "Quiet from" else "Quiet until",
            minutes = if (start) app.quietHours.startMinutes else app.quietHours.endMinutes,
            onDismiss = { editingQuiet = null },
            onConfirm = { minutes ->
                vm.updateSettings { current ->
                    val hours = current.quietHours
                    current.copy(
                        quietHours = if (start) QuietHours(minutes, hours.endMinutes) else QuietHours(hours.startMinutes, minutes),
                    )
                }
                haptics.confirm()
                editingQuiet = null
            },
        )
    }
}

@Composable
private fun Modifier.introRise(index: Int, playing: Boolean): Modifier {
    val progress: State<Float> = if (playing) {
        rememberEntrance(delayMillis = index * 60)
    } else {
        remember { mutableFloatStateOf(1f) }
    }
    return rise(progress, 18.dp)
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        modifier = modifier,
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    description: String? = null,
    onToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHerHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(interactionSource = null, indication = null) {
                haptics.toggle(!checked)
                onToggle()
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(label, color = scheme.onBackground, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Hint(description, Modifier.padding(top = 2.dp))
        }
        HerSwitch(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun TimeChip(label: String, minutes: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = Her.colors
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(scheme.onSurfaceVariant.copy(alpha = 0.08f))
            .border(1.dp, colors.glassStroke, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(label.uppercase(), color = scheme.onSurfaceVariant, fontSize = 11.sp, letterSpacing = 1.4.sp)
        Text(
            formatMinutes(minutes),
            color = scheme.onBackground,
            style = ConversationStyle.copy(fontSize = 22.sp, lineHeight = 30.sp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietTimeDialog(title: String, minutes: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = { TimePicker(state = state) },
    )
}

/** A number that counts up from zero the first time it is shown, then glides to new values. */
@Composable
private fun Stat(label: String, value: Number, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val animated = remember { Animatable(0f) }
    LaunchedEffect(value.toFloat()) {
        animated.animateTo(value.toFloat(), tween(900, easing = HerMotion.EmphasizedDecelerate))
    }
    Column(modifier.padding(vertical = 6.dp)) {
        Text(
            "%,d".format(animated.value.roundToLong()),
            color = scheme.onBackground,
            style = ConversationStyle.copy(fontSize = 22.sp, lineHeight = 30.sp),
        )
        Text(label, color = scheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun AdvancedToggle(open: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val rotation by animateFloatAsState(if (open) 180f else 0f, HerMotion.snappy(), label = "advanced-chevron")
    Row(
        modifier = modifier
            .clip(CircleShape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (open) "Hide advanced" else "Advanced", color = scheme.primary, style = MaterialTheme.typography.labelLarge)
        Canvas(Modifier.size(12.dp).graphicsLayer { rotationZ = rotation }) {
            val stroke = 1.6.dp.toPx()
            val tip = Offset(size.width * 0.5f, size.height * 0.7f)
            drawLine(scheme.primary, Offset(size.width * 0.15f, size.height * 0.35f), tip, stroke, StrokeCap.Round)
            drawLine(scheme.primary, Offset(size.width * 0.85f, size.height * 0.35f), tip, stroke, StrokeCap.Round)
        }
    }
}

@Composable
private fun LogCard(text: String, muted: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text,
        color = if (muted) scheme.onSurfaceVariant else scheme.onBackground,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.onSurfaceVariant.copy(alpha = 0.06f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun AboutLine(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Built for everyone 🌱 · Open on ", color = scheme.onSurfaceVariant, fontSize = 12.sp)
            Text(
                "GitHub",
                color = scheme.primary,
                fontSize = 12.sp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { uriHandler.openUri(GITHUB_URL) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Loading orbs from ", color = scheme.onSurfaceVariant, fontSize = 12.sp)
            Text(
                "thinking-orbs",
                color = scheme.primary,
                fontSize = 12.sp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { uriHandler.openUri(ORBS_URL) },
            )
        }
    }
}

private fun formatMinutes(minutes: Int): String =
    LocalTime.of(minutes / 60, minutes % 60).format(DateTimeFormatter.ofPattern("HH:mm"))

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java) ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun canScheduleExactAlarms(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

private fun parseMinutes(value: String): Int? {
    val parts = value.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}
