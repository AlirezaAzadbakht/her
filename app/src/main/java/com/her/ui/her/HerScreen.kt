package com.her.ui.her

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.her.domain.ChatMessage
import com.her.domain.MessageRole
import com.her.ui.HerViewModel
import com.her.ui.markdown.ConversationMarkdown
import com.her.ui.theme.ConversationStyle

@Composable
fun HerScreen(vm: HerViewModel) {
    val messages by vm.messages.collectAsState()
    val sending by vm.sending.collectAsState()
    val error by vm.sendError.collectAsState()
    val share by vm.pendingShare.collectAsState()
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 22.dp),
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item { Spacer(Modifier.height(12.dp)) }
            items(messages, key = { it.id }) { message ->
                MessageLine(message, onDelete = { vm.deleteMessage(it) }, onForget = { vm.forgetFromMessage(it) })
            }
            if (sending) {
                item {
                    Text("…", style = ConversationStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        val sharedText = share
        if (!sharedText.isNullOrBlank()) {
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
            if (draft.text.isNotBlank() && !sending) {
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
                enabled = draft.text.isNotBlank() && !sending,
            ) {
                Text("Send", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun MessageLine(
    message: ChatMessage,
    onDelete: (String) -> Unit,
    onForget: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val user = message.role == MessageRole.USER
    Column(Modifier.fillMaxWidth()) {
        ConversationMarkdown(
            content = message.content,
            color = if (user) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = if (user) "you" else "her",
            color = MaterialTheme.colorScheme.outline,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 6.dp).clickable { menu = true },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Delete message") }, onClick = { menu = false; onDelete(message.id) })
            DropdownMenuItem(text = { Text("Forget information from this") }, onClick = { menu = false; onForget(message.id) })
        }
    }
}

