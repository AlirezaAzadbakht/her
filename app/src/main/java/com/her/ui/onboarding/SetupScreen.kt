package com.her.ui.onboarding

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.her.data.secure.LlmSettings
import com.her.ui.components.HerButton
import com.her.ui.components.HerTextField
import com.her.ui.components.StaggeredText
import com.her.ui.components.sharedPresence
import com.her.ui.orbs.HerPresence
import com.her.ui.orbs.OrbState
import com.her.ui.theme.ConversationStyle
import com.her.ui.theme.HerMotion
import com.her.ui.theme.rememberEntrance
import com.her.ui.theme.rememberHerHaptics
import com.her.ui.theme.rise
import kotlinx.coroutines.delay

// HerViewModel.saveAndTest reports success with exactly this message.
private const val Connected = "Connected."

@Composable
fun NameOnboarding(
    userName: String?,
    onSaveUserName: (String) -> Unit,
    onSaveAssistantName: (String) -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(if (userName.isNullOrBlank()) 0 else 1) }
    var yours by rememberSaveable { mutableStateOf(userName.orEmpty()) }
    var hers by rememberSaveable { mutableStateOf("") }
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
    checking: Boolean = false,
    onSave: (LlmSettings) -> Unit,
) {
    var base by remember { mutableStateOf(initial.baseUrl) }
    var key by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHerHaptics()
    var attempted by remember { mutableStateOf(false) }
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
            "Before we talk, I need a place to think. An OpenAI-compatible endpoint is enough.",
            style = QuestionStyle,
            color = scheme.onSurfaceVariant,
            startDelayMillis = 240,
            stepMillis = 45,
        )
        Spacer(Modifier.height(36.dp))
        Column(Modifier.rise(form, 16.dp)) {
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
            AnimatedVisibility(
                visible = !checking && message != null && !connected,
                enter = expandVertically(HerMotion.enter()) + fadeIn(HerMotion.enter()),
                exit = shrinkVertically(HerMotion.exit()) + fadeOut(HerMotion.exit()),
            ) {
                Text(
                    message.orEmpty(),
                    color = if (failed) scheme.error else scheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
    }
}

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
