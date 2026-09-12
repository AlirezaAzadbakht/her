package com.her.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.her.data.secure.LlmSettings
import com.her.ui.theme.HerFontFamily
import com.her.ui.her.HerScreen
import com.her.ui.memory.SqlNavigatorScreen
import com.her.ui.onboarding.NameOnboarding
import com.her.ui.onboarding.RequestFirstRunPermissions
import com.her.ui.onboarding.SetupScreen
import com.her.ui.settings.SettingsScreen

enum class Dest { Her, Memory, Settings }

@Composable
fun HerApp(vm: HerViewModel) {
    val settings by vm.settings.collectAsState()
    val profile by vm.profile.collectAsState()
    val connectionMessage by vm.connectionMessage.collectAsState()
    val checkingConnection by vm.checkingConnection.collectAsState()
    val named = !profile?.userName.isNullOrBlank() && !profile?.assistantName.isNullOrBlank()
    val configured = settings.apiConfiguredOnce
    var dest by rememberSaveable { mutableStateOf(Dest.Her) }

    LaunchedEffect(settings.memoryTabEnabled) {
        if (!settings.memoryTabEnabled && dest == Dest.Memory) dest = Dest.Her
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        if (profile == null) {
            // Wait for the profile row so first-run name steps do not flash the LLM form.
        } else if (!named) {
            NameOnboarding(
                userName = profile?.userName,
                onSaveUserName = { vm.saveUserName(it) },
                onSaveAssistantName = { vm.saveAssistantName(it) },
            )
        } else if (!configured) {
            SetupScreen(
                initial = vm.llmSettings(),
                message = connectionMessage,
                checking = checkingConnection,
                onSave = { next: LlmSettings -> vm.saveAndTest(next) },
            )
        } else {
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
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "dest",
                    ) { current ->
                        when (current) {
                            Dest.Her -> HerScreen(vm)
                            Dest.Memory -> SqlNavigatorScreen(vm)
                            Dest.Settings -> SettingsScreen(vm)
                        }
                    }
                }
                if (!imeVisible) {
                    BottomNav(dest, tabs) { dest = it }
                }
            }
        }
    }
}

@Composable
private fun BottomNav(dest: Dest, tabs: List<Dest>, onSelect: (Dest) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { item ->
            val selected = item == dest
            Text(
                text = item.name,
                modifier = Modifier.clickable { onSelect(item) }.padding(8.dp),
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = HerFontFamily,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                fontSize = 14.sp,
                letterSpacing = 1.2.sp,
            )
        }
    }
}
