package com.her.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.her.data.secure.LlmSettings
import com.her.ui.components.AmbientBackground
import com.her.ui.components.GlassPill
import com.her.ui.components.LocalSharedTransitionScope
import com.her.ui.components.LocalStageVisibilityScope
import com.her.ui.components.sharedPresence
import com.her.ui.her.HerScreen
import com.her.ui.memory.SqlNavigatorScreen
import com.her.ui.onboarding.NameOnboarding
import com.her.ui.onboarding.RequestFirstRunPermissions
import com.her.ui.onboarding.SetupScreen
import com.her.ui.orbs.HerPresence
import com.her.ui.orbs.OrbState
import com.her.ui.settings.SettingsScreen
import com.her.ui.theme.Her
import com.her.ui.theme.HerFontFamily
import com.her.ui.theme.HerMotion
import com.her.ui.theme.rememberHerHaptics
import kotlinx.coroutines.launch

enum class Dest { Her, Memory, Settings }

private enum class Stage { Loading, Names, Setup, Main }

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalAnimationApi::class)
@Composable
fun HerApp(vm: HerViewModel) {
    val settings by vm.settings.collectAsState()
    val profile by vm.profile.collectAsState()
    val connectionMessage by vm.connectionMessage.collectAsState()
    val checkingConnection by vm.checkingConnection.collectAsState()
    val named = !profile?.userName.isNullOrBlank() && !profile?.assistantName.isNullOrBlank()
    val stage = when {
        // Wait for the profile row so first-run name steps do not flash the LLM form.
        profile == null -> Stage.Loading
        !named -> Stage.Names
        !settings.apiConfiguredOnce -> Stage.Setup
        else -> Stage.Main
    }
    var dest by rememberSaveable { mutableStateOf(Dest.Her) }

    LaunchedEffect(settings.memoryTabEnabled) {
        if (!settings.memoryTabEnabled && dest == Dest.Memory) dest = Dest.Her
    }

    AmbientBackground(Modifier.fillMaxSize()) {
        SharedTransitionLayout(Modifier.fillMaxSize().statusBarsPadding()) {
            AnimatedContent(
                targetState = stage,
                transitionSpec = { stageTransition() },
                label = "stage",
            ) { current ->
                // Leaving stages soften out of focus on API 31+; blur is a no-op below that.
                val blur by transition.animateFloat(
                    transitionSpec = { tween(HerMotion.Standard, easing = HerMotion.StandardEasing) },
                    label = "stage-blur",
                ) { if (it == EnterExitState.Visible) 0f else 12f }
                CompositionLocalProvider(
                    LocalSharedTransitionScope provides this@SharedTransitionLayout,
                    LocalStageVisibilityScope provides this,
                ) {
                    Box(Modifier.fillMaxSize().blur(blur.dp)) {
                        when (current) {
                            Stage.Loading -> LoadingStage()
                            Stage.Names -> NameOnboarding(
                                userName = profile?.userName,
                                onSaveUserName = { vm.saveUserName(it) },
                                onSaveAssistantName = { vm.saveAssistantName(it) },
                            )
                            Stage.Setup -> SetupScreen(
                                initial = vm.llmSettings(),
                                message = connectionMessage,
                                checking = checkingConnection,
                                onSave = { next: LlmSettings -> vm.saveAndTest(next) },
                            )
                            Stage.Main -> MainStage(vm, dest) { dest = it }
                        }
                    }
                }
            }
        }
    }
}

private fun AnimatedContentTransitionScope<Stage>.stageTransition(): ContentTransform =
    (fadeIn(HerMotion.enter(HerMotion.Emphasized, delayMillis = 90)) +
        scaleIn(HerMotion.enter(HerMotion.Emphasized, delayMillis = 90), initialScale = 0.97f)) togetherWith
        fadeOut(tween(HerMotion.Standard, easing = HerMotion.EmphasizedAccelerate))

@Composable
private fun LoadingStage() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        HerPresence(
            state = OrbState.Breathing,
            orbSize = 96.dp,
            energy = 0.35f,
            modifier = Modifier.sharedPresence(),
        )
    }
}

@Composable
private fun MainStage(vm: HerViewModel, dest: Dest, onDest: (Dest) -> Unit) {
    val settings by vm.settings.collectAsState()
    RequestFirstRunPermissions(asked = settings.runtimePermissionsAsked) { calendarGranted ->
        vm.updateSettings { current ->
            current.copy(
                runtimePermissionsAsked = true,
                calendarEnabled = current.calendarEnabled || calendarGranted,
            )
        }
    }
    val tabs = buildList {
        add(Dest.Her)
        if (settings.memoryTabEnabled) add(Dest.Memory)
        add(Dest.Settings)
    }

    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Column(Modifier.fillMaxSize().imePadding()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AnimatedContent(
                targetState = dest,
                transitionSpec = { tabTransition() },
                label = "dest",
            ) { current ->
                when (current) {
                    Dest.Her -> HerScreen(vm)
                    Dest.Memory -> SqlNavigatorScreen(vm)
                    Dest.Settings -> SettingsScreen(vm)
                }
            }
        }
        AnimatedVisibility(
            visible = !imeVisible,
            enter = slideInVertically(HerMotion.enter()) { it } + fadeIn(HerMotion.enter()),
            exit = slideOutVertically(HerMotion.exit()) { it } + fadeOut(HerMotion.exit()),
        ) {
            FloatingNav(dest, tabs, onDest)
        }
    }
}

/** Tabs slide in from the side they sit on, so the motion matches the nav. */
private fun AnimatedContentTransitionScope<Dest>.tabTransition(): ContentTransform {
    val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
    return (
        fadeIn(HerMotion.enter(HerMotion.Emphasized)) +
            slideInHorizontally(HerMotion.enter(HerMotion.Emphasized)) { width -> direction * width * 6 / 100 } +
            scaleIn(HerMotion.enter(HerMotion.Emphasized), initialScale = 0.98f)
        ) togetherWith (
        fadeOut(tween(HerMotion.Quick)) +
            slideOutHorizontally(HerMotion.exit(HerMotion.Standard)) { width -> -direction * width * 6 / 100 }
        )
}

@Composable
private fun FloatingNav(dest: Dest, tabs: List<Dest>, onSelect: (Dest) -> Unit) {
    val colors = Her.colors
    val scheme = MaterialTheme.colorScheme
    val haptics = rememberHerHaptics()
    val bounds = remember { mutableStateMapOf<Dest, Pair<Float, Float>>() }
    val indicatorLeft = remember { Animatable(0f) }
    val indicatorWidth = remember { Animatable(0f) }
    val target = bounds[dest]
    LaunchedEffect(target) {
        val (left, width) = target ?: return@LaunchedEffect
        if (indicatorWidth.value == 0f) {
            indicatorLeft.snapTo(left)
            indicatorWidth.snapTo(width)
        } else {
            launch { indicatorLeft.animateTo(left, HerMotion.snappy()) }
            indicatorWidth.animateTo(width, HerMotion.snappy())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        GlassPill(shape = CircleShape) {
            Box(
                Modifier
                    .padding(4.dp)
                    .drawBehind {
                        val width = indicatorWidth.value
                        if (width > 0f) {
                            drawRoundRect(
                                brush = Brush.horizontalGradient(
                                    listOf(colors.glowCoral.copy(alpha = 0.26f), colors.glowAmber.copy(alpha = 0.18f)),
                                ),
                                topLeft = Offset(indicatorLeft.value, 0f),
                                size = Size(width, size.height),
                                cornerRadius = CornerRadius(size.height / 2),
                            )
                        }
                    },
            ) {
                Row {
                    tabs.forEach { item ->
                        val selected = item == dest
                        val color by animateColorAsState(
                            if (selected) scheme.primary else scheme.onSurfaceVariant,
                            tween(HerMotion.Standard),
                            label = "nav-color",
                        )
                        val scale by animateFloatAsState(if (selected) 1f else 0.96f, HerMotion.snappy(), label = "nav-scale")
                        Text(
                            text = item.name,
                            modifier = Modifier
                                .onGloballyPositioned { coords ->
                                    bounds[item] = coords.positionInParent().x to coords.size.width.toFloat()
                                }
                                .clip(CircleShape)
                                .clickable(interactionSource = null, indication = null, role = Role.Tab) {
                                    if (item != dest) {
                                        haptics.tick()
                                        onSelect(item)
                                    }
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                },
                            color = color,
                            fontFamily = HerFontFamily,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            fontSize = 14.sp,
                            letterSpacing = 1.2.sp,
                        )
                    }
                }
            }
        }
    }
}
