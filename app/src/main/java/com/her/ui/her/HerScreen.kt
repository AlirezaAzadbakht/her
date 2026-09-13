package com.her.ui.her

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.her.agent.runner.TurnState
import com.her.ui.HerViewModel
import com.her.ui.markdown.ConversationMarkdown
import com.her.ui.theme.ConversationStyle
import com.her.ui.theme.herResponseFontFamily

@Composable
fun HerScreen(vm: HerViewModel) {
    val latest by vm.latestAssistant.collectAsState()
    val turn by vm.turnState.collectAsState()
    val error by vm.sendError.collectAsState()
    val share by vm.pendingShare.collectAsState()
    val pendingCount by vm.pendingCount.collectAsState()
    val online by vm.online.collectAsState()
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    val scroll = rememberScrollState()
    val resources = LocalContext.current.resources
    val responseStyle = remember {
        ConversationStyle.copy(fontFamily = herResponseFontFamily(resources))
    }

    val streamingText = (turn as? TurnState.Streaming)?.text.orEmpty()
    val displayText = if (streamingText.isNotBlank()) streamingText else latest?.content.orEmpty()
    val thinking = turn is TurnState.Thinking || (turn is TurnState.Streaming && streamingText.isBlank())
    val utteranceKey = when {
        turn is TurnState.Streaming && streamingText.isNotBlank() -> "stream"
        thinking -> "think"
        else -> latest?.id ?: "empty"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            ThinkingAnimation(
                visible = thinking,
                modifier = Modifier.fillMaxSize(),
            )
            AnimatedContent(
                targetState = utteranceKey,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "utterance",
                modifier = Modifier.fillMaxSize(),
            ) { key ->
                if (key != "think" && displayText.isNotBlank()) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = maxHeight)
                                .verticalScroll(scroll)
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            ConversationMarkdown(
                                content = displayText,
                                color = lerp(
                                    MaterialTheme.colorScheme.onBackground,
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                    0.35f,
                                ),
                                style = responseStyle,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        val sharedText = share
        if (!online && pendingCount > 0) {
            Text(
                "offline · $pendingCount waiting",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        } else if (!sharedText.isNullOrBlank()) {
            Text(
                "Shared: ${sharedText.take(120)}",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        fun sendDraft() {
            if (draft.text.isNotBlank()) {
                vm.send(draft.text)
                draft = TextFieldValue("")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
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
                textStyle = ConversationStyle.copy(color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendDraft() }),
                decorationBox = { inner ->
                    if (draft.text.isEmpty()) {
                        Text("Write something", style = ConversationStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 17.sp))
                    }
                    inner()
                },
            )
            TextButton(
                onClick = { sendDraft() },
                enabled = draft.text.isNotBlank(),
            ) {
                Text("Send", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
