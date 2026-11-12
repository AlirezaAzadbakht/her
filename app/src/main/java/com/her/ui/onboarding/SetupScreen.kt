package com.her.ui.onboarding

import android.content.ClipboardManager
import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.her.data.secure.LlmSettings
import com.her.ui.components.HerButton
import com.her.ui.components.HerButtonTone
import com.her.ui.components.HerTextField
import com.her.ui.components.NoteWithDetails
import com.her.ui.components.StaggeredText
import com.her.ui.components.sharedPresence
import com.her.ui.orbs.HerPresence
import com.her.ui.orbs.OrbState
import com.her.ui.theme.ConversationStyle
import com.her.ui.theme.Her
import com.her.ui.theme.HerMotion
import com.her.ui.theme.rememberEntrance
import com.her.ui.theme.rememberHerHaptics
import com.her.ui.theme.rise
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// HerViewModel.saveAndTest reports success with exactly this message.
private const val Connected = "Connected."

internal const val HerCloudUrl = "https://cloud.her-project.ir/"

@Composable
fun NameOnboarding(
    userName: String?,
    onSaveUserName: (String) -> Unit,
    onSaveAssistantName: (String) -> Unit,
    restoring: Boolean = false,
    restoreMessage: String? = null,
    onRestore: () -> Unit = {},
) {
    var step by rememberSaveable { mutableStateOf(if (userName.isNullOrBlank()) 0 else 1) }
    var yours by rememberSaveable { mutableStateOf(userName.orEmpty()) }
    var hers by rememberSaveable { mutableStateOf("") }
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHerHaptics()
    var pulse by remember { mutableStateOf(false) }
    LaunchedEffect(step) {
        if (step == 0) return@LaunchedEffect
        pulse = true
        delay(700)
        pulse = false
    }

    OnboardingColumn {
        HerPresence(
            state = OrbState.Shaping,
            orbSize = 88.dp,
            energy = if (pulse) 0.95f else 0.45f,
            modifier = Modifier.align(Alignment.CenterHorizontally).sharedPresence(),
        )
        Spacer(Modifier.height(20.dp))
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                (slideInHorizontally(HerMotion.enter(HerMotion.Emphasized)) { it / 5 } + fadeIn(HerMotion.enter(HerMotion.Emphasized))) togetherWith
                    (slideOutHorizontally(HerMotion.exit(HerMotion.Standard)) { -it / 5 } + fadeOut(HerMotion.exit()))
            },
            label = "name-step",
        ) { current ->
            if (current == 0) {
                OnboardingStep(greeting = "Hi.", question = "What should I call you?") {
                    HerTextField("Your name", yours, keyboard = KeyboardType.Text) { yours = it }
                    Spacer(Modifier.height(8.dp))
                    HerButton(
                        "Continue",
                        onClick = {
                            onSaveUserName(yours)
                            step = 1
                        },
                        enabled = yours.isNotBlank(),
                    )
                    Spacer(Modifier.height(8.dp))
                    HerButton(
                        "Restore from a backup",
                        onClick = onRestore,
                        tone = HerButtonTone.Quiet,
                        loading = restoring,
                        loadingText = "Restoring…",
                    )
                    AnimatedVisibility(
                        visible = restoreMessage != null,
                        enter = expandVertically(HerMotion.enter()) + fadeIn(HerMotion.enter()),
                        exit = shrinkVertically(HerMotion.exit()) + fadeOut(HerMotion.exit()),
                    ) {
                        Text(
                            restoreMessage.orEmpty(),
                            color = scheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 14.dp),
                        )
                    }
                }
            } else {
                val greeting = yours.trim().ifBlank { userName.orEmpty().trim() }
                OnboardingStep(
                    greeting = if (greeting.isBlank()) "Thank you." else "Thank you, $greeting.",
                    question = "And what should I be called?",
                ) {
                    HerTextField("My name", hers, keyboard = KeyboardType.Text) { hers = it }
                    Spacer(Modifier.height(8.dp))
                    HerButton(
                        "Continue",
                        onClick = {
                            haptics.confirm()
                            onSaveAssistantName(hers)
                        },
                        enabled = hers.isNotBlank(),
                    )
                }
            }
        }
    }
}

@Composable
fun SetupScreen(
    initial: LlmSettings,
    message: String?,
    details: String? = null,
    checking: Boolean = false,
    onSave: (LlmSettings) -> Unit,
) {
    var base by remember { mutableStateOf(initial.baseUrl) }
    var key by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHerHaptics()
    var attempted by remember { mutableStateOf(false) }
    var ownOpen by rememberSaveable { mutableStateOf(false) }
    var shake by remember { mutableIntStateOf(0) }
    LaunchedEffect(checking) {
        if (checking || !attempted) return@LaunchedEffect
        if (message == Connected) {
            haptics.confirm()
        } else {
            haptics.reject()
            shake += 1
        }
    }
    val connected = !checking && message == Connected
    val failed = attempted && !checking && message != null && !connected
    val form = rememberEntrance(delayMillis = 700)

    OnboardingColumn {
        HerPresence(
            state = if (checking) OrbState.Connecting else OrbState.Shaping,
            orbSize = 88.dp,
            energy = when {
                connected -> 1f
                checking -> 0.85f
                else -> 0.45f
            },
            modifier = Modifier.align(Alignment.CenterHorizontally).sharedPresence(),
        )
        Spacer(Modifier.height(20.dp))
        StaggeredText("Hi.", style = GreetingStyle, color = scheme.onBackground)
        Spacer(Modifier.height(12.dp))
        StaggeredText(
            "Before we talk, I need a place to think.",
            style = QuestionStyle,
            color = scheme.onSurfaceVariant,
            startDelayMillis = 240,
            stepMillis = 45,
        )
        Spacer(Modifier.height(36.dp))
        Column(Modifier.rise(form, 16.dp)) {
            HerCloudConnect(checking = checking) { cloud ->
                base = cloud.baseUrl
                key = cloud.apiKey
                model = cloud.model
                attempted = true
                onSave(cloud)
            }
            Spacer(Modifier.height(20.dp))
            // A failed check opens it, so the fields that need fixing are in view.
            Disclosure("Use my own API endpoint", open = ownOpen || failed, onToggle = { ownOpen = !ownOpen }) {
                HerTextField("Base URL", base, error = failed, shakeTrigger = shake) { base = it }
                HerTextField("API key", key, secret = true, error = failed, shakeTrigger = shake) { key = it }
                HerTextField("Model", model, error = failed, shakeTrigger = shake) { model = it }
                Spacer(Modifier.height(8.dp))
                HerButton(
                    "Continue",
                    onClick = {
                        attempted = true
                        onSave(LlmSettings(base, key, model))
                    },
                    enabled = base.isNotBlank() && key.isNotBlank() && model.isNotBlank(),
                    loading = checking,
                    loadingText = "Checking…",
                    success = connected,
                )
            }
            AnimatedVisibility(
                visible = !checking && message != null && !connected,
                enter = expandVertically(HerMotion.enter()) + fadeIn(HerMotion.enter()),
                exit = shrinkVertically(HerMotion.exit()) + fadeOut(HerMotion.exit()),
            ) {
                NoteWithDetails(
                    message.orEmpty(),
                    details,
                    color = if (failed) scheme.error else scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
    }
}

/** The quick way in: scan, load or paste the settings Her Cloud gives out, or go there to buy credit. */
@Composable
fun HerCloudConnect(checking: Boolean = false, onImport: (LlmSettings) -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scheme = MaterialTheme.colorScheme
    var invalid by remember { mutableStateOf(false) }
    val accept = { text: String? ->
        val settings = text?.let(LlmSettings::fromJson)
        invalid = settings == null
        if (settings != null) onImport(settings)
    }
    // No contents means they backed out of the scanner.
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(accept) }
    val scope = rememberCoroutineScope()
    // A saved card may also carry a store-link code; take the one that is settings. No uri means they backed out.
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val texts = withContext(Dispatchers.Default) { qrTextsInImage(context, uri) }
            accept(texts.firstOrNull { LlmSettings.fromJson(it) != null })
        }
    }
    Column {
        ScanQrButton(checking = checking) {
            scanner.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Point at the QR code on Her Cloud")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HerButton(
                "Load QR image",
                tone = HerButtonTone.Outline,
                enabled = !checking,
                modifier = Modifier.weight(1f),
                onClick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
            )
            HerButton(
                "Paste settings",
                tone = HerButtonTone.Outline,
                enabled = !checking,
                modifier = Modifier.weight(1f),
                onClick = {
                    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                    accept(clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString())
                },
            )
        }
        HerButton(
            "No code yet? Get credit on Her Cloud",
            tone = HerButtonTone.Quiet,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            onClick = { uriHandler.openUri(HerCloudUrl) },
        )
        AnimatedVisibility(
            visible = invalid,
            enter = expandVertically(HerMotion.enter()) + fadeIn(HerMotion.enter()),
            exit = shrinkVertically(HerMotion.exit()) + fadeOut(HerMotion.exit()),
        ) {
            Text(
                "That isn't Her Cloud settings. Get them again from Her Cloud.",
                color = scheme.error,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** The one thing to tap first: a wide warm card with a QR mark. */
@Composable
private fun ScanQrButton(checking: Boolean, onClick: () -> Unit) {
    val colors = Her.colors
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(colors.glowCoral.copy(alpha = 0.26f), colors.glowAmber.copy(alpha = 0.16f))))
            .border(
                1.5.dp,
                Brush.horizontalGradient(listOf(colors.glowCoral.copy(alpha = 0.8f), colors.glowAmber.copy(alpha = 0.55f), colors.glowRose.copy(alpha = 0.7f))),
                shape,
            )
            .clickable(enabled = !checking, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Canvas(Modifier.size(44.dp)) {
            val ink = scheme.onBackground
            val cell = size.width / 7f
            val corner = CornerRadius(cell * 0.6f)
            // Three finder squares, as on a real code.
            listOf(Offset(0f, 0f), Offset(cell * 4, 0f), Offset(0f, cell * 4)).forEach { at ->
                drawRoundRect(ink, at + Offset(cell * 0.3f, cell * 0.3f), Size(cell * 2.4f, cell * 2.4f), corner, Stroke(cell * 0.6f))
                drawRoundRect(ink, at + Offset(cell * 1.05f, cell * 1.05f), Size(cell * 0.9f, cell * 0.9f), CornerRadius(cell * 0.2f))
            }
            listOf(4f to 4f, 6f to 4f, 5f to 5f, 4f to 6f, 6f to 6f).forEach { (x, y) ->
                drawRoundRect(ink, Offset(cell * x, cell * y), Size(cell * 0.9f, cell * 0.9f), CornerRadius(cell * 0.2f))
            }
        }
        Column {
            Text(
                if (checking) "Checking…" else "Scan QR code",
                color = scheme.onBackground,
                style = MaterialTheme.typography.titleMedium,
            )
            Text("From your Her Cloud card", color = scheme.onSurfaceVariant, fontSize = 14.sp)
        }
    }
}

/** A row that folds [content] away until it is tapped. */
@Composable
fun Disclosure(
    title: String,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    val rotation by animateFloatAsState(if (open) 180f else 0f, HerMotion.snappy(), label = "disclosure-chevron")
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, Her.colors.glassStroke, shape)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Canvas(Modifier.size(12.dp).graphicsLayer { rotationZ = rotation }) {
                val stroke = 1.6.dp.toPx()
                val tip = Offset(size.width * 0.5f, size.height * 0.7f)
                drawLine(scheme.onSurfaceVariant, Offset(size.width * 0.15f, size.height * 0.35f), tip, stroke, StrokeCap.Round)
                drawLine(scheme.onSurfaceVariant, Offset(size.width * 0.85f, size.height * 0.35f), tip, stroke, StrokeCap.Round)
            }
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(HerMotion.enter()) + fadeIn(HerMotion.enter()),
            exit = shrinkVertically(HerMotion.exit()) + fadeOut(HerMotion.exit()),
        ) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), content = content)
        }
    }
}

/** Every QR code's text in an image, or none if it has none. */
internal fun qrTexts(pixels: IntArray, width: Int, height: Int): List<String> = runCatching {
    val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
    QRCodeMultiReader().decodeMultiple(bitmap, mapOf(DecodeHintType.TRY_HARDER to true)).map { it.text }
}.getOrDefault(emptyList())

// Big camera photos are scaled down to this long side before looking for codes.
private const val MaxQrImageSide = 2048

private fun qrTextsInImage(context: Context, uri: Uri): List<String> = runCatching {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val scale = MaxQrImageSide.toFloat() / maxOf(info.size.width, info.size.height)
        if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
    }
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    qrTexts(pixels, bitmap.width, bitmap.height)
}.getOrDefault(emptyList())

private val GreetingStyle = ConversationStyle.copy(fontSize = 30.sp, lineHeight = 40.sp)
private val QuestionStyle = ConversationStyle.copy(fontSize = 18.sp, lineHeight = 26.sp)

@Composable
private fun OnboardingColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 32.dp),
        content = content,
    )
}

/** Greeting and question ink in word by word; the form eases up once they have been said. */
@Composable
private fun OnboardingStep(
    greeting: String,
    question: String,
    form: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var greeted by remember(greeting) { mutableStateOf(false) }
    val reveal by animateFloatAsState(
        targetValue = if (greeted) 1f else 0f,
        animationSpec = tween(HerMotion.Emphasized, easing = HerMotion.EmphasizedDecelerate),
        label = "form-reveal",
    )
    Column {
        StaggeredText(greeting, style = GreetingStyle, color = scheme.onBackground)
        Spacer(Modifier.height(12.dp))
        StaggeredText(
            question,
            style = QuestionStyle,
            color = scheme.onSurfaceVariant,
            startDelayMillis = 240,
            stepMillis = 60,
            onFinished = { greeted = true },
        )
        Spacer(Modifier.height(36.dp))
        Column(
            modifier = Modifier.graphicsLayer {
                alpha = reveal
                translationY = (1f - reveal) * 16.dp.toPx()
            },
            content = form,
        )
    }
}
