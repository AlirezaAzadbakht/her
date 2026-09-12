package com.her.ui.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.her.data.db.QueryResult
import com.her.data.db.SqlGuard
import com.her.data.db.TableInfo
import com.her.ui.HerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SqlNavigatorScreen(vm: HerViewModel) {
    val browser = vm.sqlBrowser
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var tables by remember { mutableStateOf<List<TableInfo>>(emptyList()) }
    var result by remember { mutableStateOf<QueryResult?>(null) }
    var lastSql by remember { mutableStateOf<String?>(null) }
    var limit by remember { mutableIntStateOf(50) }
    var expanded by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        tables = withContext(Dispatchers.IO) { browser.tables() }
    }

    fun run(sql: String, newLimit: Int = limit) {
        val trimmed = sql.trim()
        if (trimmed.isBlank()) return
        scope.launch {
            lastSql = trimmed
            limit = newLimit
            result = withContext(Dispatchers.IO) { browser.query(trimmed, rowCap = newLimit) }
            tables = withContext(Dispatchers.IO) { browser.tables() }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Memory",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp,
                letterSpacing = 1.4.sp,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            Text(
                "Read-only SQL against her local database.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onBackground,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                minLines = 2,
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text("SELECT …", color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    }
                    inner()
                },
            )
            Row {
                TextButton(onClick = { run(query) }) { Text("Run") }
            }
        }
        item {
            Text(
                "Tables",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(tables, key = { it.name }) { table ->
            Text(
                "${table.name} · ${table.rowCount}",
                color = MaterialTheme.colorScheme.onBackground,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val sql = "SELECT * FROM ${SqlGuard.quoteIdent(table.name)} ORDER BY rowid DESC LIMIT $limit"
                        query = sql
                        run(sql)
                    }
                    .padding(vertical = 6.dp),
            )
        }
        val current = result
        if (current != null) {
            item {
                Text(
                    if (current.error != null) "Error" else "Result · ${current.rows.size}${if (current.truncated) "+" else ""}",
                    color = if (current.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            if (current.error != null) {
                item {
                    Text(current.error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            } else {
                item {
                    ResultGrid(current, onCell = { expanded = it })
                }
                if (current.truncated && lastSql != null) {
                    item {
                        TextButton(onClick = { run(lastSql!!, limit + 50) }) {
                            Text("Load more")
                        }
                    }
                }
            }
        }
    }

    expanded?.let { value ->
        AlertDialog(
            onDismissRequest = { expanded = null },
            title = { Text("Value") },
            text = {
                Text(value, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            },
            confirmButton = {
                TextButton(onClick = { expanded = null }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun ResultGrid(result: QueryResult, onCell: (String) -> Unit) {
    val hScroll = rememberScrollState()
    Column(Modifier.horizontalScroll(hScroll)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            result.columns.forEach { col ->
                Text(
                    col,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.widthIn(min = 96.dp, max = 220.dp),
                )
            }
        }
        result.rows.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                row.forEach { cell ->
                    val shown = cell ?: "∅"
                    Text(
                        shown.take(48),
                        color = MaterialTheme.colorScheme.onBackground,
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
