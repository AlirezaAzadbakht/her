package com.her.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.her.HerApplication
import com.her.MainActivity
import com.her.R
import com.her.domain.Feeling
import com.her.domain.HerFeeling
import com.her.ui.markdown.markdownToPlain
import com.her.ui.memory.word
import com.her.ui.theme.feelingPalette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Her latest line, with a Write button that opens [QuickNoteActivity]. A one-line bar when short. */
class HerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.refreshAsync(this, context)

    companion object {
        fun lineFor(latest: String?): String? = latest?.let(::markdownToPlain)?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** How she feels: an orb in the feeling's colours and its word; her note too when there's room. */
class HerFeelingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.refreshAsync(this, context)
}

/** What the feeling widget shows, picked the same way as the Memory tab's "How she feels". */
data class WidgetFeeling(val main: Feeling, val second: Feeling?, val note: String?) {
    companion object {
        fun of(feeling: HerFeeling?, blend: List<Pair<Feeling, Double>>): WidgetFeeling {
            val main = feeling?.feeling ?: Feeling.CALM
            return WidgetFeeling(
                main = main,
                second = blend.map { it.first }.firstOrNull { it != main && it != Feeling.CALM },
                note = feeling?.note?.takeIf { it.isNotBlank() },
            )
        }
    }
}

object Widgets {
    fun refreshAsync(receiver: AppWidgetProvider, context: Context) {
        val graph = (context.applicationContext as? HerApplication)?.graph ?: return
        val pending = receiver.goAsync()
        graph.io.execute {
            try { runBlocking { refreshAll(context) } } finally { pending.finish() }
        }
    }

    /** Redraws every placed Her widget; reads nothing when none are on the home screen. */
    suspend fun refreshAll(context: Context) {
        val graph = (context.applicationContext as? HerApplication)?.graph ?: return
        val manager = AppWidgetManager.getInstance(context)
        val messageIds = manager.getAppWidgetIds(ComponentName(context, HerWidget::class.java))
        val feelingIds = manager.getAppWidgetIds(ComponentName(context, HerFeelingWidget::class.java))
        if (messageIds.isEmpty() && feelingIds.isEmpty()) return
        if (messageIds.isNotEmpty()) {
            val line = HerWidget.lineFor(graph.repo.observeLatestAssistant().first()?.content)
                ?: context.getString(R.string.widget_empty)
            manager.updateAppWidget(
                messageIds,
                responsive(
                    SizeF(110f, 40f) to messageRow(context, line),
                    SizeF(180f, 130f) to messageCard(context, line, maxLines = 5),
                    SizeF(180f, 250f) to messageCard(context, line, maxLines = 12),
                ),
            )
        }
        if (feelingIds.isNotEmpty()) {
            val feeling = WidgetFeeling.of(graph.repo.observeFeeling().first(), graph.repo.observeEmotionalBlend().first())
            val orb = orb(context, feeling.main)
            manager.updateAppWidget(
                feelingIds,
                responsive(
                    SizeF(50f, 50f) to feelingSmall(context, feeling, orb),
                    SizeF(140f, 110f) to feelingCard(context, feeling, orb),
                ),
            )
        }
    }

    /** Android 12+ picks the largest layout that fits; older launchers get the middle one. */
    private fun responsive(vararg sizes: Pair<SizeF, RemoteViews>): RemoteViews =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) RemoteViews(mapOf(*sizes)) else sizes[sizes.size / 2].second

    private fun messageRow(context: Context, line: String) = RemoteViews(context.packageName, R.layout.widget_her_row).apply {
        setTextViewText(R.id.widget_line, line.lineSequence().first())
        setOnClickPendingIntent(R.id.widget_line, openApp(context))
        setOnClickPendingIntent(R.id.widget_write, openWrite(context))
    }

    private fun messageCard(context: Context, line: String, maxLines: Int) =
        RemoteViews(context.packageName, R.layout.widget_her).apply {
            setTextViewText(R.id.widget_line, line)
            setInt(R.id.widget_line, "setMaxLines", maxLines)
            setOnClickPendingIntent(R.id.widget_line, openApp(context))
            setOnClickPendingIntent(R.id.widget_write, openWrite(context))
        }

    private fun feelingSmall(context: Context, feeling: WidgetFeeling, orb: Bitmap) =
        RemoteViews(context.packageName, R.layout.widget_feeling_small).apply {
            setImageViewBitmap(R.id.widget_orb, orb)
            setTextViewText(R.id.widget_feeling_word, context.getString(feeling.main.word()))
            setOnClickPendingIntent(R.id.widget_root, openApp(context))
        }

    private fun feelingCard(context: Context, feeling: WidgetFeeling, orb: Bitmap) =
        RemoteViews(context.packageName, R.layout.widget_feeling).apply {
            setImageViewBitmap(R.id.widget_orb, orb)
            setTextViewText(R.id.widget_feeling_word, context.getString(feeling.main.word()))
            if (feeling.second != null) {
                setViewVisibility(R.id.widget_feeling_second, View.VISIBLE)
                setTextViewText(
                    R.id.widget_feeling_second,
                    context.getString(R.string.feeling_second, context.getString(feeling.second.word()).lowercase()),
                )
            }
            setTextViewText(R.id.widget_feeling_note, feeling.note ?: context.getString(R.string.feeling_none))
            setOnClickPendingIntent(R.id.widget_root, openApp(context))
        }

    /** A soft lit sphere in the feeling's colours, matching the light or dark palette in use. */
    private fun orb(context: Context, feeling: Feeling, px: Int = 160): Bitmap {
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val (a, b, c) = feelingPalette(feeling, dark).map { it.toArgb() }
        val r = px / 2f
        return Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(r * 0.7f, r * 0.6f, r * 1.4f, intArrayOf(b, a, c), null, Shader.TileMode.CLAMP)
            })
        }
    }

    private fun openApp(context: Context) = activity(context, 0, Intent(context, MainActivity::class.java))
    private fun openWrite(context: Context) = activity(context, 1, Intent(context, QuickNoteActivity::class.java))

    private fun activity(context: Context, code: Int, intent: Intent) = PendingIntent.getActivity(
        context, code, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
