package com.her.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.her.HerApplication
import com.her.MainActivity
import com.her.R
import com.her.ui.markdown.markdownToPlain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Her's latest line on the home screen, with a Write button that opens [QuickNoteActivity]. */
class HerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val graph = (context.applicationContext as? HerApplication)?.graph ?: return
        val pending = goAsync()
        graph.io.execute {
            try {
                val latest = runBlocking { graph.repo.observeLatestAssistant().first() }?.content
                manager.updateAppWidget(ids, views(context, latest))
            } finally { pending.finish() }
        }
    }

    companion object {
        /** Redraws every placed widget; does nothing when none are on the home screen. */
        fun refresh(context: Context, latest: String?) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HerWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context, latest))
        }

        fun lineFor(latest: String?): String? = latest?.let(::markdownToPlain)?.trim()?.takeIf { it.isNotEmpty() }

        private fun views(context: Context, latest: String?) = RemoteViews(context.packageName, R.layout.widget_her).apply {
            setTextViewText(R.id.widget_line, lineFor(latest) ?: context.getString(R.string.widget_empty))
            setOnClickPendingIntent(R.id.widget_line, activity(context, 0, Intent(context, MainActivity::class.java)))
            setOnClickPendingIntent(R.id.widget_write, activity(context, 1, Intent(context, QuickNoteActivity::class.java)))
        }

        private fun activity(context: Context, code: Int, intent: Intent) = PendingIntent.getActivity(
            context, code, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
