package com.her.ui.her

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.her.agent.runner.Receipts
import com.her.agent.runner.TurnState
import com.her.ui.HerViewModel
import com.her.ui.components.GlassPill
import com.her.ui.components.sharedPresence
import com.her.ui.markdown.ConversationMarkdown
import com.her.ui.orbs.HerPresence
import com.her.ui.orbs.OrbSize
import com.her.ui.orbs.OrbState
import com.her.ui.orbs.PresenceMood
import com.her.ui.orbs.ThinkingOrb
import com.her.ui.orbs.presenceFor
import com.her.ui.theme.ConversationStyle
import com.her.ui.theme.Her
import com.her.ui.theme.HerMotion
import com.her.ui.theme.herResponseFontFamily
import com.her.ui.theme.rememberHerHaptics
import com.her.ui.theme.rememberShake
import com.her.ui.theme.shake
import kotlinx.coroutines.delay

private const val PinSlackPx = 80
private val SmallOrb = 28.dp
private val BigOrb = 150.dp

private sealed interface HerStatus {
    data class Offline(val count: Int) : HerStatus
    data class Shared(val text: String) : HerStatus
}

@Composable
fun HerScreen(vm: HerViewModel) {
    val latest by vm.latestAssistant.collectAsState()
    val turn by vm.turnState.collectAsState()
    val error by vm.sendError.collectAsState()
    val share by vm.pendingShare.collectAsState()
    val pendingCount by vm.pendingCount.collectAsState()
    val online by vm.online.collectAsState()
    val appSettings by vm.settings.collectAsState()
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    val scroll = rememberScrollState()
    val resources = LocalContext.current.resources
    val responseStyle = remember {
        ConversationStyle.copy(fontFamily = herResponseFontFamily(resources))
    }
    val haptics = rememberHerHaptics()
    val scheme = MaterialTheme.colorScheme
    var heldStream by remember { mutableStateOf("") }
    var pinToBottom by remember { mutableStateOf(true) }

    val streamingText = (turn as? TurnState.Streaming)?.text.orEmpty()
    val nextHeld = when {
        streamingText.isNotBlank() -> streamingText
        turn is TurnState.Thinking -> ""
        latest?.content == heldStream -> ""
        else -> heldStream
    }
    SideEffect {
        if (heldStream != nextHeld) heldStream = nextHeld
    }

    val awaitingFirstToken = turn is TurnState.Thinking ||
        (turn is TurnState.Streaming && streamingText.isBlank())
    val displayText = when {
        streamingText.isNotBlank() -> streamingText
        nextHeld.isNotBlank() -> nextHeld
        awaitingFirstToken -> ""
        else -> latest?.content.orEmpty()
    }
    val showCaret = displayText.isNotBlank() &&
        (turn is TurnState.Streaming || nextHeld.isNotBlank())
    val follow = showCaret
    var shown by remember { mutableStateOf(displayText) }
    LaunchedEffect(displayText, showCaret) {
        if (!showCaret) {
            shown = displayText
            return@LaunchedEffect
        }
        if (!displayText.startsWith(shown)) {
            shown = displayText.substring(0, StreamReveal.prefixLength(shown, displayText))
        }
        while (shown.length < displayText.length) {
            val behind = displayText.length - shown.length
            shown = StreamReveal.advance(shown, displayText, StreamReveal.catchUpUnits(behind))
            delay(StreamReveal.catchUpDelayMs(behind))
        }
    }

    LaunchedEffect(turn) {
        if (turn !is TurnState.Idle) {
            pinToBottom = true
            scroll.scrollTo(0)
        }
    }
    LaunchedEffect(scroll, turn) {
        var lastMax = 0
        snapshotFlow { scroll.value to scroll.maxValue }.collect { (value, max) ->
            val grew = max > lastMax
            lastMax = max
            val nearBottom = max - value <= PinSlackPx
            pinToBottom = when {
                nearBottom -> true
                grew -> pinToBottom
                else -> false
            }
        }
    }
    LaunchedEffect(shown, follow, scroll.maxValue, pinToBottom) {
        if (follow && pinToBottom) scroll.scrollTo(scroll.maxValue)
    }

    // The newest graphemes ink in while streaming, then settle to full once the reply is whole.
    val tail = remember { Animatable(0f) }
    LaunchedEffect(showCaret) {
        if (showCaret) tail.snapTo(1f) else tail.animateTo(0f, tween(HerMotion.Emphasized, easing = HerMotion.StandardEasing))
    }

    // One soft tick when her first words land in a turn.
    var firstWordsArmed by remember { mutableStateOf(false) }
    LaunchedEffect(awaitingFirstToken) { if (awaitingFirstToken) firstWordsArmed = true }
    LaunchedEffect(firstWordsArmed, shown.isNotBlank()) {
        if (firstWordsArmed && shown.isNotBlank()) {
            haptics.soft()
            firstWordsArmed = false
        }
    }

    var sendPulse by remember { mutableIntStateOf(0) }
    var pulsing by remember { mutableStateOf(false) }
    LaunchedEffect(sendPulse) {
        if (sendPulse == 0) return@LaunchedEffect
        pulsing = true
        delay(600)
        pulsing = false
    }
    val bump = if (pulsing) 0.2f else 0f

    // Keep the last words on screen while they drift out, so the exit has something to show.
    var lastText by remember { mutableStateOf(shown) }
    SideEffect { if (shown.isNotBlank()) lastText = shown }
    val replyText = if (shown.isNotBlank()) shown else lastText
    val hasText = shown.isNotBlank()

    val mood = presenceFor(turn, revealing = showCaret, draftNotEmpty = draft.text.isNotBlank(), hasError = error != null)
    val ink = lerp(scheme.onBackground, scheme.onSurfaceVariant, 0.35f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp),
    ) {
        PresenceSlot(visible = hasText, mood = mood, bump = bump)
        ReplyStage(
            hasText = hasText,
            thinking = awaitingFirstToken,
            bump = bump,
            scroll = scroll,
            text = replyText,
            ink = ink,
            style = responseStyle,
            showCaret = showCaret,
            tailStrength = tail.value,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        val message = latest
        val receipts = remember(message?.id, message?.metadataJson) { Receipts.fromMetadata(message?.metadataJson) }
        val showReceipts = message != null && appSettings.showReceipts && receipts.isNotEmpty() &&
            turn is TurnState.Idle && nextHeld.isBlank()
        var lastReceipts by remember { mutableStateOf(receipts to message?.id) }
        SideEffect { if (showReceipts) lastReceipts = receipts to message?.id }
        AnimatedVisibility(
            visible = showReceipts,
            enter = expandVertically(HerMotion.enter()) + fadeIn(HerMotion.enter(delayMillis = 80)),
            exit = shrinkVertically(HerMotion.exit()) + fadeOut(HerMotion.exit()),
        ) {
            val (items, id) = if (showReceipts) receipts to message?.id else lastReceipts
            ReceiptLine(items, onUndo = { picked -> if (id != null) vm.undoReceipts(id, picked) })
        }

        val sharedText = share
        val status: HerStatus? = when {
            !online && pendingCount > 0 -> HerStatus.Offline(pendingCount)
            !sharedText.isNullOrBlank() -> HerStatus.Shared(sharedText.take(120))
            else -> null
        }
        AnimatedContent(
            targetState = status,
            transitionSpec = {
                (fadeIn(HerMotion.enter()) + slideInVertically(HerMotion.enter()) { it / 2 }) togetherWith
                    fadeOut(HerMotion.exit()) using SizeTransform(clip = false)
            },
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth(),
            label = "her-status",
        ) { current ->
            if (current != null) {
                Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
                    StatusChip(current)
                }
            }
        }

        var errorShake by remember { mutableIntStateOf(0) }
        LaunchedEffect(error) {
            if (error != null) {
                haptics.reject()
                errorShake += 1
            }
        }
        val shake = rememberShake(errorShake)
        var lastError by remember { mutableStateOf("") }
        SideEffect { error?.let { lastError = it } }
        AnimatedVisibility(
            visible = error != null,
            enter = expandVertically(HerMotion.enter()) + fadeIn(HerMotion.enter()),
            exit = shrinkVertically(HerMotion.exit()) + fadeOut(HerMotion.exit()),
        ) {
            Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
                Text(
                    error ?: lastError,
                    color = scheme.error,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .shake(shake)
                        .clip(CircleShape)
                        .border(1.dp, scheme.error.copy(alpha = 0.35f), CircleShape)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }

        var ghostText by remember { mutableStateOf("") }
        var ghostKey by remember { mutableIntStateOf(0) }
        val ghost = remember { Animatable(1f) }
        LaunchedEffect(ghostKey) {
            if (ghostKey == 0) return@LaunchedEffect
            ghost.snapTo(0f)
            ghost.animateTo(1f, tween(420, easing = HerMotion.EmphasizedDecelerate))
        }

        fun sendDraft() {
            val text = draft.text
            if (text.isNotBlank()) {
                vm.send(text)
                ghostText = text
                ghostKey += 1
                sendPulse += 1
                draft = TextFieldValue("")
                haptics.confirm()
            }
        }

        val fieldStyle = ConversationStyle.copy(color = scheme.onBackground, fontSize = 17.sp)
        val composerFocus = remember { MutableInteractionSource() }
        val composerFocused by composerFocus.collectIsFocusedAsState()
        Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp)) {
            GlassPill(Modifier.fillMaxWidth(), focused = composerFocused) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp)
                        .padding(start = 20.dp, end = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 10.dp)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    (event.key == Key.Enter || event.key == Key.NumPadEnter)
                                ) {
                                    sendDraft()
                                    true
                                } else {
                                    false
                                }
                            },
                        singleLine = true,
                        textStyle = fieldStyle,
                        cursorBrush = SolidColor(scheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendDraft() }),
                        interactionSource = composerFocus,
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (draft.text.isEmpty()) {
                                    Text("Write something", style = fieldStyle.copy(color = scheme.onSurfaceVariant))
                                }
                                inner()
                            }
                        },
                    )
                    SendButton(visible = draft.text.isNotBlank(), onClick = { sendDraft() })
                }
            }
            if (ghost.value < 1f && ghostText.isNotEmpty()) {
                Text(
                    ghostText,
                    style = fieldStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 20.dp, end = 60.dp)
                        .graphicsLayer {
                            val p = ghost.value
                            translationY = -24.dp.toPx() * p
                            alpha = 1f - p
                        },
                )
            }
        }
    }
}

/** The small orb above her reply; it takes over from the big one once words arrive. */
@Composable
private fun PresenceSlot(visible: Boolean, mood: PresenceMood, bump: Float) {
    val state = when (mood) {
        PresenceMood.Speaking -> OrbState.Composing
        PresenceMood.Listening -> OrbState.Listening
        else -> OrbState.Breathing
    }
    val energy = when (mood) {
        PresenceMood.Speaking -> 0.7f
        PresenceMood.Thinking -> 0.8f
        PresenceMood.Listening -> 0.5f
        PresenceMood.Idle -> 0.2f
        PresenceMood.Error -> 0.08f
    } + bump
    Box(
        modifier = Modifier.fillMaxWidth().height(SmallOrb * 2),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(HerMotion.enter(HerMotion.Emphasized, delayMillis = 140)) +
                scaleIn(HerMotion.enter(HerMotion.Emphasized, delayMillis = 140), initialScale = 1.8f),
            exit = fadeOut(HerMotion.exit()) + scaleOut(HerMotion.exit(), targetScale = 0.6f),
        ) {
            HerPresence(
                state = state,
                orbSize = SmallOrb,
                size = OrbSize.Px20,
                energy = energy,
                paused = mood == PresenceMood.Idle || mood == PresenceMood.Error,
                modifier = if (visible) Modifier.sharedPresence() else Modifier,
            )
        }
    }
}

/** The stage: a big thinking orb while she has no words yet, then her reply, centred. */
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun ReplyStage(
    hasText: Boolean,
    thinking: Boolean,
    bump: Float,
    scroll: ScrollState,
    text: String,
    ink: Color,
    style: TextStyle,
    showCaret: Boolean,
    tailStrength: Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        AnimatedVisibility(
            visible = !hasText,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(HerMotion.enter(HerMotion.Emphasized)) +
                scaleIn(HerMotion.enter(HerMotion.Emphasized), initialScale = 0.6f),
            exit = fadeOut(HerMotion.exit(HerMotion.Standard)) +
                scaleOut(HerMotion.exit(HerMotion.Standard), targetScale = 0.25f) +
                slideOutVertically(HerMotion.exit(HerMotion.Standard)) { -it },
        ) {
            HerPresence(
                state = OrbState.Breathing,
                orbSize = BigOrb,
                energy = (if (thinking) 0.85f else 0.25f) + bump,
                paused = !thinking,
                modifier = if (!hasText) Modifier.sharedPresence() else Modifier,
            )
        }
        AnimatedVisibility(
            visible = hasText,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(HerMotion.enter(HerMotion.Emphasized)) +
                slideInVertically(HerMotion.enter(HerMotion.Emphasized)) { it / 24 },
            exit = fadeOut(HerMotion.exit(HerMotion.Standard)) +
                slideOutVertically(HerMotion.exit(HerMotion.Standard)) { -it / 20 },
        ) {
            // The outgoing reply softens out of focus on API 31+.
            val blur by transition.animateFloat(
                transitionSpec = { tween(HerMotion.Standard) },
                label = "reply-blur",
            ) { if (it == EnterExitState.Visible) 0f else 8f }
            BoxWithConstraints(Modifier.fillMaxSize().blur(blur.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = maxHeight)
                        .verticalScroll(scroll)
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ConversationMarkdown(
                        content = text,
                        color = ink,
                        style = style,
                        textAlign = TextAlign.Center,
                        showCaret = showCaret,
                        tailStrength = tailStrength,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: HerStatus) {
    val colors = Her.colors
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(colors.glassFill)
            .border(1.dp, colors.glassStroke, CircleShape)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (status) {
            is HerStatus.Offline -> {
                ThinkingOrb(OrbState.Weaving, size = OrbSize.Px20, displaySize = 16.dp)
                Text("offline · ${status.count} waiting", color = scheme.onSurfaceVariant, fontSize = 13.sp)
            }
            is HerStatus.Shared -> Text(
                "Shared: ${status.text}",
                color = scheme.primary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A warm round send button that springs in once there is something to send. */
@Composable
private fun SendButton(visible: Boolean, onClick: () -> Unit) {
    val colors = Her.colors
    val arrow = MaterialTheme.colorScheme.background
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "send-scale",
    )
    Box(
        modifier = Modifier
            .size(40.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = scale.coerceIn(0f, 1f)
            }
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(colors.glowCoral, colors.glowAmber)))
            .clickable(enabled = visible, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Send" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val stroke = 2.dp.toPx()
            val cx = size.width / 2
            val tip = Offset(cx, size.height * 0.14f)
            drawLine(arrow, Offset(cx, size.height * 0.86f), tip, stroke, StrokeCap.Round)
            drawLine(arrow, tip, Offset(size.width * 0.2f, size.height * 0.5f), stroke, StrokeCap.Round)
            drawLine(arrow, tip, Offset(size.width * 0.8f, size.height * 0.5f), stroke, StrokeCap.Round)
        }
    }
}
