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
        val orb = orb(context, feeling.main)
        if (messageIds.isNotEmpty()) {
            val line = HerWidget.lineFor(graph.repo.observeLatestAssistant().first()?.content)
                ?: context.getString(R.string.widget_empty)
            val replying = graph.orchestrator.turnState.value != TurnState.Idle
            manager.updateAppWidget(
                messageIds,
                responsive(
                    SizeF(110f, 40f) to messageRow(context, line, orb, replying),
                    SizeF(180f, 130f) to messageCard(context, line, orb, replying, maxLines = 5),
                    SizeF(180f, 250f) to messageCard(context, line, orb, replying, maxLines = 12),
                ),
            )
        }
        if (feelingIds.isNotEmpty()) {
            // The middle entry (square) is what launchers before Android 12 get.
            manager.updateAppWidget(
                feelingIds,
                responsive(
                    SizeF(50f, 50f) to feelingView(context, R.layout.widget_feeling_small, feeling, orb),
                    SizeF(150f, 50f) to feelingView(context, R.layout.widget_feeling, feeling, orb, withNote = false),
                    SizeF(100f, 100f) to feelingView(context, R.layout.widget_feeling_square, feeling, orb),
                    SizeF(180f, 130f) to feelingView(context, R.layout.widget_feeling, feeling, orb),
                    SizeF(130f, 200f) to feelingView(context, R.layout.widget_feeling_tall, feeling, orb),
                ),
            )
        }
    }

    /** Android 12+ picks the largest layout that fits; older launchers get the middle one. */
    private fun responsive(vararg sizes: Pair<SizeF, RemoteViews>): RemoteViews =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) RemoteViews(mapOf(*sizes)) else sizes[sizes.size / 2].second

    /** The row always shows her orb; its ring turns only while she replies. */
    private fun messageRow(context: Context, line: String, orb: Bitmap, replying: Boolean) =
        RemoteViews(context.packageName, R.layout.widget_her_row).apply {
            setImageViewBitmap(R.id.widget_orb, orb)
            setViewVisibility(R.id.widget_ring, if (replying) View.VISIBLE else View.GONE)
            setTextViewText(R.id.widget_line, line.lineSequence().first())
            setOnClickPendingIntent(R.id.widget_line, openApp(context))
            setOnClickPendingIntent(R.id.widget_write, openWrite(context))
        }

    /** Like the chat stage: her orb while she has no words yet, then her line in the middle. */
    private fun messageCard(context: Context, line: String, orb: Bitmap, replying: Boolean, maxLines: Int) =
        RemoteViews(context.packageName, R.layout.widget_her).apply {
            setImageViewBitmap(R.id.widget_orb, orb)
            setViewVisibility(R.id.widget_thinking, if (replying) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.widget_line, if (replying) View.GONE else View.VISIBLE)
            setTextViewText(R.id.widget_line, line)
            setInt(R.id.widget_line, "setMaxLines", maxLines)
            setOnClickPendingIntent(R.id.widget_line, openApp(context))
            setOnClickPendingIntent(R.id.widget_thinking, openApp(context))
            setOnClickPendingIntent(R.id.widget_write, openWrite(context))
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

    /** A soft lit sphere in the feeling's colours inside a faint halo, matching the light or dark palette in use. */
    private fun orb(context: Context, feeling: Feeling, px: Int = 256): Bitmap {
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val (a, b, c) = feelingPalette(feeling, dark).map { it.toArgb() }
        val r = px / 2f
        // The sphere sits inside the turning ring (widget_ring's dots are at 0.83 of the radius).
        val s = r * 0.62f
        return Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it)
            canvas.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(r, r, r, intArrayOf(alpha(a, 0x55), alpha(a, 0x22), alpha(a, 0)), floatArrayOf(0.5f, 0.75f, 1f), Shader.TileMode.CLAMP)
            })
            canvas.drawCircle(r, r, s, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(r - s * 0.3f, r - s * 0.4f, s * 1.4f, intArrayOf(b, a, c), null, Shader.TileMode.CLAMP)
            })
        }
    }

    private fun alpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a shl 24)

    private fun openApp(context: Context) = activity(context, 0, Intent(context, MainActivity::class.java))
    private fun openWrite(context: Context) = activity(context, 1, Intent(context, QuickNoteActivity::class.java))

    private fun activity(context: Context, code: Int, intent: Intent) = PendingIntent.getActivity(
        context, code, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
