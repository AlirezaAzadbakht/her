package com.her.ui.memory

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.her.R
import com.her.core.nowMillis
import com.her.data.db.PersonaTrait
import com.her.data.db.QueryResult
import com.her.data.db.RecentMemory
import com.her.data.db.SqlGuard
import com.her.data.db.TableInfo
import com.her.domain.Feeling
import com.her.domain.HerFeeling
import com.her.ui.HerViewModel
import com.her.ui.components.HerButton
import com.her.ui.components.SkeletonRows
import com.her.ui.orbs.OrbSize
import com.her.ui.orbs.OrbState
import com.her.ui.orbs.ThinkingOrb
import com.her.ui.theme.ConversationStyle
import com.her.ui.theme.Her
import com.her.ui.theme.HerMotion
import com.her.ui.theme.rememberEntrance
import com.her.ui.theme.rememberHerHaptics
import com.her.ui.theme.rememberShake
import com.her.ui.theme.rise
import com.her.ui.theme.shake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// Most reads finish in a few milliseconds; loaders only appear for work slow enough to notice.
private const val LoaderGraceMs = 150L
private const val StaggeredRows = 20
private const val TablesPerPage = 6
private const val RowsPerPage = 50

// Lately, her feeling, who she is, the table page and the query card are one item each, so results always start here.
private const val ResultIndex = 5

/** What the person typed and ran, kept by the view model so it survives switching tabs. */
class MemoryScreenState {
    var query by mutableStateOf("")
    var tablePage by mutableIntStateOf(0)
    var result by mutableStateOf<QueryResult?>(null)
    var lastSql by mutableStateOf<String?>(null)
    var resultPage by mutableIntStateOf(0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SqlNavigatorScreen(vm: HerViewModel) {
    val kept = vm.memoryState
    val browser = vm.sqlBrowser
    val scope = rememberCoroutineScope()
    val haptics = rememberHerHaptics()
    val scheme = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val feeling by vm.feeling.collectAsState()
    val blend by vm.feelingBlend.collectAsState()
    val persona by vm.persona.collectAsState()
    var recent by remember { mutableStateOf<List<RecentMemory>?>(null) }
    var query by kept::query
    var tables by remember { mutableStateOf<List<TableInfo>>(emptyList()) }
    var tablesLoaded by remember { mutableStateOf(false) }
    var tablePage by kept::tablePage
    var result by kept::result
    var lastSql by kept::lastSql
    var resultPage by kept::resultPage
    var reveal by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var errorShake by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        recent = withContext(Dispatchers.IO) { browser.recentMemories() }
        tables = withContext(Dispatchers.IO) { browser.tables() }
        tablesLoaded = true
    }
    // Runs after the result is composed, so its item exists to scroll to.
    LaunchedEffect(reveal) {
        if (reveal > 0) listState.animateScrollToItem(ResultIndex)
    }
    val showSkeleton by afterGrace(!tablesLoaded)
    val showRunningOrb by afterGrace(running)

    fun run(sql: String, page: Int = 0) {
        val trimmed = sql.trim()
        if (trimmed.isBlank() || running) return
        scope.launch {
            running = true
            try {
                lastSql = trimmed
                resultPage = page
                val next = withContext(Dispatchers.IO) { browser.query(trimmed, rowCap = RowsPerPage, offset = page * RowsPerPage) }
                result = next
                if (next.error != null) {
                    haptics.reject()
                    errorShake += 1
                }
                reveal += 1
                tables = withContext(Dispatchers.IO) { browser.tables() }
            } finally {
                running = false
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "recent") {
            Column {
                Label(stringResource(R.string.lately), Modifier.padding(bottom = 8.dp))
                val items = recent
                when {
                    items == null -> Spacer(Modifier.height(48.dp))
                    items.isEmpty() -> Text(
                        stringResource(R.string.lately_empty),
                        color = scheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    else -> Column(Modifier.glassCard(RoundedCornerShape(20.dp)).padding(vertical = 4.dp)) {
                        val now = nowMillis()
                        items.forEach { memory -> RecentRow(memory, now) { expanded = memory.content } }
                    }
                }
            }
        }
        item(key = "feeling") {
            FeelingNow(feeling, blend, Modifier.padding(top = 20.dp, bottom = 12.dp))
        }
        item(key = "persona") {
            PersonaWeb(persona, Modifier.padding(top = 8.dp, bottom = 12.dp))
        }
        item(key = "tables") {
            Column {
                Label(stringResource(R.string.database), Modifier.padding(top = 12.dp, bottom = 4.dp))
                if (!tablesLoaded) {
                    if (showSkeleton) SkeletonRows(TablesPerPage, Modifier.padding(vertical = 8.dp))
                } else {
                    val pages = tables.chunked(TablesPerPage)
                    val page = tablePage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                    pages.getOrNull(page)?.forEach { table ->
                        TableRow(table) {
                            val sql = "SELECT * FROM ${SqlGuard.quoteIdent(table.name)} ORDER BY rowid DESC"
                            query = sql
                            run(sql)
                        }
                    }
                    if (pages.size > 1) {
                        Pager(
                            "${page + 1} / ${pages.size}",
                            hasPrev = page > 0,
                            hasNext = page < pages.size - 1,
                            onPrev = { tablePage = page - 1 },
                            onNext = { tablePage = page + 1 },
                        )
                    }
                }
            }
        }
        item(key = "query") {
            Column(
                Modifier
                    .glassCard(RoundedCornerShape(20.dp))
                    .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 8.dp),
            ) {
                // SQL reads left to right whatever the app's language.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = scheme.onBackground,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        ),
                        cursorBrush = SolidColor(scheme.primary),
                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                        minLines = 3,
                        decorationBox = { inner ->
                            Box {
                                if (query.isEmpty()) {
                                    Text("SELECT …", color = scheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                }
                                inner()
                            }
                        },
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    HerButton(
                        stringResource(R.string.run),
                        onClick = { run(query) },
                        enabled = query.isNotBlank(),
                        loading = running,
                        loadingOrb = OrbState.Searching,
                    )
                }
            }
        }
        // Once a result is showing, the Run button's orb carries progress, so the result stays at ResultIndex.
        if (showRunningOrb && result == null) {
            item(key = "running") {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp).animateItem(), contentAlignment = Alignment.Center) {
                    ThinkingOrb(OrbState.Searching, size = OrbSize.Px64, displaySize = 72.dp)
                }
            }
        }
        val current = result
        if (current != null) {
            item(key = "result-label") {
                Label(
                    if (current.error != null) stringResource(R.string.error) else stringResource(R.string.result_page, resultPage + 1),
                    Modifier.padding(top = 16.dp).animateItem(),
                    color = if (current.error != null) scheme.error else null,
                )
            }
            if (current.error != null) {
                item(key = "result-error") {
                    val shake = rememberShake(errorShake)
                    val shape = RoundedCornerShape(14.dp)
                    Text(
                        current.error,
                        color = scheme.error,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .animateItem()
                            .shake(shake)
                            .fillMaxWidth()
                            .clip(shape)
                            .border(1.dp, scheme.error.copy(alpha = 0.35f), shape)
                            .padding(12.dp),
                    )
                }
            } else {
                item(key = "result-grid") {
                    ResultGrid(current, Modifier.animateItem(), onCell = { expanded = it })
                }
                if (resultPage > 0 || current.truncated) {
                    item(key = "result-pager") {
                        Pager(
                            "${resultPage + 1}",
                            hasPrev = resultPage > 0 && !running,
                            hasNext = current.truncated && !running,
                            onPrev = { lastSql?.let { run(it, resultPage - 1) } },
                            onNext = { lastSql?.let { run(it, resultPage + 1) } },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    expanded?.let { value ->
        ModalBottomSheet(
            onDismissRequest = { expanded = null },
            containerColor = scheme.surfaceContainerHigh,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp)
                    .padding(bottom = 28.dp),
            ) {
                Label(stringResource(R.string.value))
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(value, color = scheme.onBackground, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }
        }
    }
}

/** True only once [active] has held for the grace period, so fast work never flashes a loader. */
@Composable
private fun afterGrace(active: Boolean): State<Boolean> = produceState(initialValue = false, active) {
    if (active) {
        delay(LoaderGraceMs)
        value = true
    } else {
        value = false
    }
}

@Composable
private fun Modifier.glassCard(shape: Shape): Modifier {
    val colors = Her.colors
    return fillMaxWidth().clip(shape).background(colors.glassFill).border(1.dp, colors.glassStroke, shape)
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text.uppercase(),
        color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        letterSpacing = 1.6.sp,
        modifier = modifier,
    )
}

@Composable
private fun RecentRow(memory: RecentMemory, now: Long, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val verb = when {
        memory.updatedAt - memory.createdAt > DateUtils.MINUTE_IN_MILLIS -> stringResource(R.string.memory_updated)
        memory.kind == "learnt" -> stringResource(R.string.memory_learnt)
        memory.kind == "noted" -> stringResource(R.string.memory_noted)
        else -> stringResource(R.string.memory_remembered)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(verb, color = scheme.primary, fontSize = 11.sp, letterSpacing = 0.8.sp, modifier = Modifier.weight(1f))
            Text(
                DateUtils.getRelativeTimeSpanString(memory.updatedAt, now, DateUtils.MINUTE_IN_MILLIS).toString(),
                color = scheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
        Text(
            memory.content,
            color = scheme.onBackground,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun FeelingNow(feeling: HerFeeling?, blend: List<Pair<Feeling, Double>>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val main = feeling?.feeling ?: Feeling.CALM
    val second = blend.map { it.first }.firstOrNull { it != main && it != Feeling.CALM }
    Column(modifier.fillMaxWidth()) {
        Label(stringResource(R.string.how_she_feels))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            ThinkingOrb(OrbState.Breathing, size = OrbSize.Px20, displaySize = 30.dp, paused = true)
            Crossfade(main, label = "feeling") { shown ->
                Text(stringResource(shown.word()), style = ConversationStyle.copy(fontSize = 40.sp, lineHeight = 48.sp), color = scheme.onBackground)
            }
        }
        if (second != null) {
            Text(stringResource(R.string.feeling_second, stringResource(second.word()).lowercase()), color = scheme.onSurfaceVariant, fontSize = 14.sp)
        }
        Text(
            feeling?.note?.takeIf { it.isNotBlank() } ?: stringResource(R.string.feeling_none),
            color = scheme.onBackground.copy(alpha = 0.8f),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@StringRes
private fun Feeling.word(): Int = when (this) {
    Feeling.CALM -> R.string.feeling_calm
    Feeling.CURIOUS -> R.string.feeling_curious
    Feeling.PLAYFUL -> R.string.feeling_playful
    Feeling.TENDER -> R.string.feeling_tender
    Feeling.GLAD -> R.string.feeling_glad
    Feeling.CONCERNED -> R.string.feeling_concerned
    Feeling.WISTFUL -> R.string.feeling_wistful
    Feeling.HOPEFUL -> R.string.feeling_hopeful
    Feeling.PENSIVE -> R.string.feeling_pensive
    Feeling.RELIEVED -> R.string.feeling_relieved
}

/** A trait's name in the app's language; one she grows later that has no translation shows as stored. */
@Composable
private fun traitName(trait: String): String = when (trait) {
    "warmth" -> stringResource(R.string.trait_warmth)
    "playfulness" -> stringResource(R.string.trait_playfulness)
    "curiosity" -> stringResource(R.string.trait_curiosity)
    "directness" -> stringResource(R.string.trait_directness)
    "patience" -> stringResource(R.string.trait_patience)
    "expressiveness" -> stringResource(R.string.trait_expressiveness)
    else -> trait
}

/** Her six traits as a web: the dashed outline is who her name made her, the fill is who she is now. */
@Composable
private fun PersonaWeb(traits: List<PersonaTrait>, modifier: Modifier = Modifier) {
    if (traits.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = scheme.onSurfaceVariant, fontSize = 11.sp)
    val names = traits.map { traitName(it.trait) }
    val described = traits.mapIndexed { i, it -> stringResource(R.string.trait_percent, names[i], (it.value * 100).roundToInt()) }
        .joinToString()
    val working = traits.mapIndexedNotNull { i, it ->
        val target = it.target ?: return@mapIndexedNotNull null
        stringResource(if (target > it.value) R.string.persona_more else R.string.persona_less, names[i])
    }.joinToString(" ")
    Column(modifier.fillMaxWidth()) {
        Label(stringResource(R.string.who_she_is))
        Canvas(
            Modifier.padding(top = 8.dp).fillMaxWidth().height(240.dp).semantics {
                contentDescription = described
            },
        ) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = minOf(size.width, size.height) / 2 - 28.dp.toPx()
            fun point(i: Int, r: Float): Offset {
                val angle = -PI / 2 + 2 * PI * i / traits.size
                return center + Offset((cos(angle) * r).toFloat(), (sin(angle) * r).toFloat())
            }
            fun polygon(values: List<Double>) = Path().apply {
                values.forEachIndexed { i, v -> point(i, radius * v.toFloat()).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                close()
            }
            val grid = scheme.onSurfaceVariant.copy(alpha = 0.18f)
            for (ring in 1..3) drawPath(polygon(List(traits.size) { ring / 3.0 }), grid, style = Stroke(1.dp.toPx()))
            traits.indices.forEach { drawLine(grid, center, point(it, radius), 1.dp.toPx()) }
            drawPath(
                polygon(traits.map { it.seed }),
                scheme.onSurfaceVariant.copy(alpha = 0.5f),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))),
            )
            val now = polygon(traits.map { it.value })
            drawPath(now, scheme.primary.copy(alpha = 0.22f))
            drawPath(now, scheme.primary, style = Stroke(2.dp.toPx()))
            traits.forEachIndexed { i, trait ->
                val label = measurer.measure(names[i], labelStyle)
                drawText(label, topLeft = point(i, radius + 16.dp.toPx()) - Offset(label.size.width / 2f, label.size.height / 2f))
            }
        }
        val grown = traits.any { it.value != it.seed }
        Text(
            "${stringResource(if (grown) R.string.persona_grown else R.string.persona_seed)} $working".trim(),
            color = scheme.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Pager(
    label: String,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ‹ and › are mirrored characters: in RTL the text renderer flips them along with the row.
        PageArrow("‹", stringResource(R.string.previous_page), hasPrev, onPrev)
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            // "1 / 6" is all neutral characters, which an RTL line would lay out backwards.
            style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        PageArrow("›", stringResource(R.string.next_page), hasNext, onNext)
    }
}

@Composable
private fun PageArrow(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = Her.colors
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (enabled) colors.glassFill else Color.Transparent)
            .border(1.dp, if (enabled) colors.glassStroke else Color.Transparent, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = if (enabled) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.4f), fontSize = 20.sp)
    }
}

@Composable
private fun TableRow(table: TableInfo, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = Her.colors
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            table.name,
            color = scheme.onBackground,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${table.rowCount}",
            color = scheme.primary,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier
                .clip(CircleShape)
                .background(colors.glassFill)
                .border(1.dp, colors.glassStroke, CircleShape)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun ResultGrid(result: QueryResult, modifier: Modifier = Modifier, onCell: (String) -> Unit) {
    val hScroll = rememberScrollState()
    val scheme = MaterialTheme.colorScheme
    val zebra = scheme.onSurfaceVariant.copy(alpha = 0.06f)
    Column(modifier.horizontalScroll(hScroll)) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            result.columns.forEach { col ->
                Text(
                    col,
                    color = scheme.primary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.widthIn(min = 96.dp, max = 220.dp),
                )
            }
        }
        if (result.rows.isEmpty()) {
            Text(
                stringResource(R.string.no_rows),
                color = scheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        result.rows.forEachIndexed { index, row ->
            val entrance = if (index < StaggeredRows) {
                rememberEntrance(key = result, delayMillis = index * 25, durationMillis = HerMotion.Standard)
            } else {
                null
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .then(if (entrance != null) Modifier.rise(entrance, 6.dp) else Modifier)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (index % 2 == 0) zebra else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                row.forEach { cell ->
                    Text(
                        (cell ?: "∅").take(48),
                        color = if (cell == null) scheme.onSurfaceVariant else scheme.onBackground,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .widthIn(min = 96.dp, max = 220.dp)
                            .clickable { onCell(cell ?: "null") },
                    )
                }
            }
        }
    }
}
