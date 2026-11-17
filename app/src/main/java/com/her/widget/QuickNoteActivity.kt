package com.her.widget

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.her.HerApplication
import com.her.MainActivity
import com.her.R
import com.her.ui.components.HerButton
import com.her.ui.theme.HerTheme

/** The widget's Write button: one field over the home screen, queued like a notification reply. */
class QuickNoteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as HerApplication).graph
        // Before first-run setup there is nobody to send to; the app walks them through it.
        if (!graph.settings.read().apiConfiguredOnce) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        setContent {
            HerTheme {
                var draft by rememberSaveable { mutableStateOf("") }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                val scheme = MaterialTheme.colorScheme
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(scheme.background, RoundedCornerShape(28.dp))
                        .padding(24.dp),
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onBackground),
                        cursorBrush = SolidColor(scheme.primary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRequester(focus),
                        decorationBox = { inner ->
                            if (draft.isEmpty()) {
                                Text(getString(R.string.widget_write_hint), style = MaterialTheme.typography.bodyLarge, color = scheme.onBackground.copy(alpha = 0.5f))
                            }
                            inner()
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                    HerButton(
                        text = getString(R.string.widget_send),
                        enabled = draft.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val text = draft.trim()
                            graph.io.execute {
                                kotlinx.coroutines.runBlocking {
                                    graph.queueFromOutside(text, "widget", "Note from the widget queued.")
                                }
                            }
                            finish()
                        },
                    )
                }
            }
        }
    }
}
