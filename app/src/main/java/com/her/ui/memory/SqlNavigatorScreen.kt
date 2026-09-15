package com.her.ui.memory

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.her.data.db.QueryResult
import com.her.data.db.SqlGuard
import com.her.data.db.TableInfo
import com.her.ui.HerViewModel
import com.her.ui.components.HerButton
import com.her.ui.components.HerButtonTone
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

// Most reads finish in a few milliseconds; loaders only appear for work slow enough to notice.
private const val LoaderGraceMs = 150L
private const val StaggeredRows = 20

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SqlNavigatorScreen(vm: HerViewModel) {
    val browser = vm.sqlBrowser
    val scope = rememberCoroutineScope()
    val haptics = rememberHerHaptics()
    val colors = Her.colors
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var tables by remember { mutableStateOf<List<TableInfo>>(emptyList()) }
    var tablesLoaded by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<QueryResult?>(null) }
    var lastSql by remember { mutableStateOf<String?>(null) }
    var limit by remember { mutableIntStateOf(50) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var errorShake by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        tables = withContext(Dispatchers.IO) { browser.tables() }
        tablesLoaded = true
    }
    val showSkeleton by afterGrace(!tablesLoaded)
    val showRunningOrb by afterGrace(running)

    fun run(sql: String, newLimit: Int = limit) {
        val trimmed = sql.trim()
        if (trimmed.isBlank() || running) return
        scope.launch {
            running = true
            try {
                lastSql = trimmed
                limit = newLimit
                val next = withContext(Dispatchers.IO) { browser.query(trimmed, rowCap = newLimit) }
                result = next
                if (next.error != null) {
                    haptics.reject()
                    errorShake += 1
                }
                tables = withContext(Dispatchers.IO) { browser.tables() }
            } finally {
                running = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "title") {
            Column(Modifier.padding(bottom = 10.dp)) {
                Text(
                    "Memory",
                    style = ConversationStyle.copy(fontSize = 30.sp, lineHeight = 38.sp),
                    color = scheme.onBackground,
                )
                Text(
                    "Read-only SQL against her local database.",
                    color = scheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
        }
        item(key = "query") {
            val shape = RoundedCornerShape(20.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(colors.glassFill)
                    .border(1.dp, colors.glassStroke, shape)
                    .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 8.dp),
            ) {
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    HerButton(
                        "Run",
                        onClick = { run(query) },
                        enabled = query.isNotBlank(),
                        loading = running,
                        loadingOrb = OrbState.Searching,
                    )
                }
            }
        }
        item(key = "tables-label") { Label("Tables", Modifier.padding(top = 12.dp)) }
        if (!tablesLoaded) {
            item(key = "tables-skeleton") {
                if (showSkeleton) SkeletonRows(5, Modifier.padding(vertical = 8.dp))
            }
        } else {
            items(tables, key = { "table-${it.name}" }) { table ->
                TableRow(table, Modifier.animateItem()) {
                    val sql = "SELECT * FROM ${SqlGuard.quoteIdent(table.name)} ORDER BY rowid DESC LIMIT $limit"
                    query = sql
                    run(sql)
                }
            }
        }
        if (showRunningOrb) {
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
                    if (current.error != null) "Error" else "Result · ${current.rows.size}${if (current.truncated) "+" else ""}",
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
                if (current.truncated && lastSql != null) {
                    item(key = "load-more") {
                        HerButton(
                            "Load more",
                            onClick = { lastSql?.let { run(it, limit + 50) } },
                            loading = running,
                            loadingOrb = OrbState.Searching,
                            tone = HerButtonTone.Quiet,
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
                Label("Value")
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
