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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.her.data.secure.LlmSettings
import com.her.ui.her.HerScreen
import com.her.ui.memory.MemoryScreen
import com.her.ui.onboarding.SetupScreen
import com.her.ui.settings.SettingsScreen

enum class Dest { Her, Memory, Settings }

@Composable
fun HerApp(vm: HerViewModel) {
    val configured = vm.llmSettings().isConfigured || vm.settings.value.apiConfiguredOnce
    var dest by rememberSaveable { mutableStateOf(Dest.Her) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        if (!configured) {
            SetupScreen(
                initial = vm.llmSettings(),
                message = vm.connectionMessage.value,
                onSave = { settings: LlmSettings ->
                    vm.saveLlm(settings)
                    vm.testConnection()
                },
            )
            return
        }

        Column(Modifier.fillMaxSize()) {
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
                        Dest.Memory -> MemoryScreen(vm)
                        Dest.Settings -> SettingsScreen(vm)
                    }
                }
            }
            BottomNav(dest) { dest = it }
        }
    }
}

@Composable
private fun BottomNav(dest: Dest, onSelect: (Dest) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dest.entries.forEach { item ->
            val selected = item == dest
            Text(
                text = item.name,
                modifier = Modifier.clickable { onSelect(item) }.padding(8.dp),
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.SansSerif,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                fontSize = 14.sp,
                letterSpacing = 1.2.sp,
            )
        }
    }
}
