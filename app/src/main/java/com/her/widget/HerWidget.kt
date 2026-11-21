package com.her.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.her.HerApplication
import com.her.MainActivity
import com.her.agent.runner.TurnState
import com.her.R
import com.her.domain.Feeling
import com.her.domain.HerFeeling
import com.her.ui.markdown.markdownToPlain
import com.her.ui.memory.word
import com.her.ui.theme.feelingPalette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Her latest line centred, or her orb while she replies, with a Write button that opens [QuickNoteActivity]. A one-line bar when short. */
class HerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.refreshAsync(this, context)

    companion object {
        fun lineFor(latest: String?): String? = latest?.let(::markdownToPlain)?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** How she feels: an orb in the feeling's colours, then its word, the second feeling and her note as room allows. */
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
        val feeling = WidgetFeeling.of(graph.repo.observeFeeling().first(), graph.repo.observeEmotionalBlend().first())
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val palette = feelingPalette(feeling.main, dark)
        val orb = WidgetOrb.still(palette, dark, px = 320)
        if (messageIds.isNotEmpty()) {
            val line = HerWidget.lineFor(graph.repo.observeLatestAssistant().first()?.content)
                ?: context.getString(R.string.widget_empty)
            val replying = graph.orchestrator.turnState.value != TurnState.Idle
            // Drawn only while she replies; every size below shares these bitmaps.
            val frames = if (replying) WidgetOrb.frames(palette, dark, px = 240) else emptyList()
            manager.updateAppWidget(
                messageIds,
                responsive(
                    SizeF(110f, 40f) to messageRow(context, line, orb, frames),
                    SizeF(180f, 130f) to messageCard(context, line, frames, maxLines = 5),
                    SizeF(180f, 250f) to messageCard(context, line, frames, maxLines = 12),
                ),
            )
        }
        if (feelingIds.isNotEmpty()) {
            // The same orb as the Memory tab's "How she feels".
            val feelingOrb = WidgetOrb.feeling(dark, px = 320)
            // The middle entry (square) is what launchers before Android 12 get.
            manager.updateAppWidget(
                feelingIds,
                responsive(
                    SizeF(50f, 50f) to feelingView(context, R.layout.widget_feeling_small, feeling, feelingOrb),
                    SizeF(150f, 50f) to feelingView(context, R.layout.widget_feeling, feeling, feelingOrb, withNote = false),
                    SizeF(100f, 100f) to feelingView(context, R.layout.widget_feeling_square, feeling, feelingOrb),
                    SizeF(180f, 130f) to feelingView(context, R.layout.widget_feeling, feeling, feelingOrb),
                    SizeF(130f, 200f) to feelingView(context, R.layout.widget_feeling_tall, feeling, feelingOrb),
                ),
            )
        }
    }

    /** Android 12+ picks the largest layout that fits; older launchers get the middle one. */
    private fun responsive(vararg sizes: Pair<SizeF, RemoteViews>): RemoteViews =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) RemoteViews(mapOf(*sizes)) else sizes[sizes.size / 2].second

    /** The row always shows her orb; it moves while she replies. */
    private fun messageRow(context: Context, line: String, orb: Bitmap, frames: List<Bitmap>) =
        RemoteViews(context.packageName, R.layout.widget_her_row).apply {
            setImageViewBitmap(R.id.widget_orb, orb)
            playOrb(context, frames)
            setTextViewText(R.id.widget_line, line.lineSequence().first())
            setOnClickPendingIntent(R.id.widget_line, openApp(context))
            setOnClickPendingIntent(R.id.widget_write, openWrite(context))
        }

    /** Like the chat stage: her moving orb while she has no words yet, then her line in the middle. */
    private fun messageCard(context: Context, line: String, frames: List<Bitmap>, maxLines: Int) =
        RemoteViews(context.packageName, R.layout.widget_her).apply {
            val replying = frames.isNotEmpty()
            setViewVisibility(R.id.widget_thinking, if (replying) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.widget_line, if (replying) View.GONE else View.VISIBLE)
            playOrb(context, frames)
            setTextViewText(R.id.widget_line, line)
            setInt(R.id.widget_line, "setMaxLines", maxLines)
            setOnClickPendingIntent(R.id.widget_line, openApp(context))
            setOnClickPendingIntent(R.id.widget_thinking, openApp(context))
            setOnClickPendingIntent(R.id.widget_write, openWrite(context))
        }

    /** Fills the flipper with [frames] and shows it over the still orb; no frames leaves the still one. */
    private fun RemoteViews.playOrb(context: Context, frames: List<Bitmap>) {
        val playing = frames.isNotEmpty()
        setViewVisibility(R.id.widget_orb, if (playing) View.GONE else View.VISIBLE)
        setViewVisibility(R.id.widget_frames, if (playing) View.VISIBLE else View.GONE)
        if (!playing) return
        for (frame in frames) {
            addView(R.id.widget_frames, RemoteViews(context.packageName, R.layout.widget_orb_frame).apply {
                setImageViewBitmap(R.id.widget_frame, frame)
            })
        }
    }

    /** Fills whichever feeling layout fits; each has the orb, and only some the word, second feeling and note. */
    private fun feelingView(context: Context, layout: Int, feeling: WidgetFeeling, orb: Bitmap, withNote: Boolean = true) =
        RemoteViews(context.packageName, layout).apply {
            val word = context.getString(feeling.main.word())
            setImageViewBitmap(R.id.widget_orb, orb)
            setContentDescription(R.id.widget_root, word)
            setOnClickPendingIntent(R.id.widget_root, openApp(context))
            if (layout == R.layout.widget_feeling_small) return@apply
            setTextViewText(R.id.widget_feeling_word, word)
            if (feeling.second != null) {
                setViewVisibility(R.id.widget_feeling_second, View.VISIBLE)
                setTextViewText(
                    R.id.widget_feeling_second,
                    context.getString(R.string.feeling_second, context.getString(feeling.second.word()).lowercase()),
                )
            }
            // A strip too short for her note drops it rather than cutting a line in half.
            if (layout != R.layout.widget_feeling_square) {
                setViewVisibility(R.id.widget_feeling_note, if (withNote) View.VISIBLE else View.GONE)
                setTextViewText(R.id.widget_feeling_note, feeling.note ?: context.getString(R.string.feeling_none))
            }
        }

    private fun openApp(context: Context) = activity(context, 0, Intent(context, MainActivity::class.java))
    private fun openWrite(context: Context) = activity(context, 1, Intent(context, QuickNoteActivity::class.java))

    private fun activity(context: Context, code: Int, intent: Intent) = PendingIntent.getActivity(
        context, code, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
