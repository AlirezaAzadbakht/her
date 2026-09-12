package com.her.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.her.data.secure.LlmSettings
import com.her.ui.theme.ConversationStyle

@Composable
fun NameOnboarding(
    userName: String?,
    onSaveUserName: (String) -> Unit,
    onSaveAssistantName: (String) -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(if (userName.isNullOrBlank()) 0 else 1) }
    var yours by rememberSaveable { mutableStateOf(userName.orEmpty()) }
    var hers by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 28.dp, vertical = 48.dp),
    ) {
        if (step == 0) {
            Text("Hi.", style = ConversationStyle, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(16.dp))
            Text(
                "What should I call you?",
                style = ConversationStyle.copy(fontSize = 18.sp, lineHeight = 26.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(36.dp))
            QuietField("Your name", yours, keyboard = KeyboardType.Text) { yours = it }
            Spacer(Modifier.height(20.dp))
            TextButton(
                onClick = {
                    onSaveUserName(yours)
                    step = 1
                },
                enabled = yours.isNotBlank(),
            ) {
                Text("Continue", color = MaterialTheme.colorScheme.primary)
            }
        } else {
            val greeting = yours.trim().ifBlank { userName.orEmpty().trim() }
            Text(
                if (greeting.isBlank()) "Thank you." else "Thank you, $greeting.",
                style = ConversationStyle,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "And what should I be called?",
                style = ConversationStyle.copy(fontSize = 18.sp, lineHeight = 26.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(36.dp))
            QuietField("My name", hers, keyboard = KeyboardType.Text) { hers = it }
            Spacer(Modifier.height(20.dp))
            TextButton(
                onClick = { onSaveAssistantName(hers) },
                enabled = hers.isNotBlank(),
            ) {
                Text("Continue", color = MaterialTheme.colorScheme.primary)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 28.dp, vertical = 48.dp),
    ) {
        Text("Hi.", style = ConversationStyle, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(16.dp))
        Text(
            "Before we talk, I need a place to think. An OpenAI-compatible endpoint is enough.",
            style = ConversationStyle.copy(fontSize = 18.sp, lineHeight = 26.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(36.dp))
        QuietField("Base URL", base) { base = it }
        QuietField("API key", key, secret = true) { key = it }
        QuietField("Model", model) { model = it }
        Spacer(Modifier.height(20.dp))
        TextButton(
            onClick = { onSave(LlmSettings(base, key, model)) },
            enabled = !checking && base.isNotBlank() && key.isNotBlank() && model.isNotBlank(),
        ) {
            Text(if (checking) "Checking…" else "Continue", color = MaterialTheme.colorScheme.primary)
        }
        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun QuietField(
    label: String,
    value: String,
    secret: Boolean = false,
    keyboard: KeyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri,
    onChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onBackground),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
