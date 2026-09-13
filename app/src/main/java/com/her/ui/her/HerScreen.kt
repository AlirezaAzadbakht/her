package com.her.ui.her

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.delay

private const val PinSlackPx = 80

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
    val thinking = shown.isBlank() && (awaitingFirstToken || showCaret)
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

    val ink = lerp(
        MaterialTheme.colorScheme.onBackground,
        MaterialTheme.colorScheme.onSurfaceVariant,
        0.35f,
    )

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
            if (shown.isNotBlank()) {
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
                            content = shown,
                            color = ink,
                            style = responseStyle,
                            textAlign = TextAlign.Center,
                            showCaret = showCaret,
                            modifier = Modifier.fillMaxWidth(),
                        )
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
