package com.her.notify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.her.reminders.ReminderDelivery
import com.her.ui.components.HerButton
import com.her.ui.components.HerButtonTone
import com.her.ui.theme.HerTheme

/**
 * The ringing alarm over the lock screen. It only shows the words and the two buttons; stopping and snoozing
 * go through the same broadcast as the notification's buttons, so there is one code path for both.
 */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        if (id.isBlank()) return finish()
        setContent {
            HerTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(text, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            HerButton("Stop", onClick = { act(Notifier.ACTION_REMINDER_DONE, id) })
                            HerButton(
                                "Snooze ${ReminderDelivery.SNOOZE_MINUTES} min",
                                tone = HerButtonTone.Quiet,
                                onClick = { act(Notifier.ACTION_REMINDER_SNOOZE, id) },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun act(action: String, id: String) {
        sendBroadcast(Intent(this, NotificationActionReceiver::class.java).setAction(action).setData(Uri.parse("her-reminder://$id")))
        finish()
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_TEXT = "text"

        fun intent(context: Context, id: String, text: String): Intent =
            Intent(context, AlarmActivity::class.java)
                .setData(Uri.parse("her-reminder://$id"))
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_TEXT, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}
