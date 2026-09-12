package com.her.ui.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.her.core.nowMillis
import com.her.domain.MemoryStatus
import com.her.domain.UserProfile
import com.her.ui.HerViewModel

@Composable
fun MemoryScreen(vm: HerViewModel) {
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirmWipe by remember { mutableStateOf(false) }
    val profile by vm.profile.collectAsState()
    val longMem by vm.longMemories.collectAsState()
    val shortMem by vm.shortMemories.collectAsState()
    val people by vm.people.collectAsState()
    val projects by vm.projects.collectAsState()
    val goals by vm.goals.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val commitments by vm.commitments.collectAsState()
    val loops by vm.openLoops.collectAsState()
    val routines by vm.routines.collectAsState()
    val groceries by vm.groceries.collectAsState()
    val dates by vm.dates.collectAsState()

    fun match(text: String) = query.isBlank() || text.contains(query, ignoreCase = true)
    val now = nowMillis()

    val information = buildList {
        addAll(profileRows(profile).filter { match(it.title + " " + (it.detail ?: "")) })
        addAll(
            longMem.filter { it.status == MemoryStatus.ACTIVE && it.deletedAt == null && match(it.content) }.map {
                MemoryRowData(
                    id = it.id,
                    title = it.content,
                    detail = listOfNotNull(
                        it.category.takeIf { c -> c.isNotBlank() && c != "general" },
                        "Source ${it.source.name}",
                    ).joinToString(" · ").ifBlank { null },
                    kind = "long",
                )
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "Memory",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onBackground),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search what she knows", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    inner()
                },
            )
        }
        section(
            title = "Information",
            rows = information,
            showWhenEmpty = true,
            emptyHint = "Facts she keeps about you will appear here — names, household details, preferences.",
            onEdit = { if (it.kind == "long") editing = it.id to it.title },
            onDelete = { if (it.kind == "long") vm.deleteLong(it.id) },
        )
        section(
            title = "Right now",
            rows = shortMem.filter { (it.expiresAt == null || it.expiresAt > now) && it.deletedAt == null && match(it.content) }.map {
                MemoryRowData(it.id, it.content, "Source ${it.source.name}", "short")
            },
            onDelete = { vm.deleteShort(it.id) },
        )
        section("People", people.filter { match(it.name + (it.importantNotes ?: "")) }.map { MemoryRowData(it.id, "${it.name} · ${it.relationship ?: ""} ${it.birthday ?: ""}".trim(), it.importantNotes, "person") })
        section("Projects", projects.filter { match(it.name) }.map { MemoryRowData(it.id, "${it.name} — ${it.summary ?: it.description ?: ""}", null, "project") })
        section("Goals", goals.filter { match(it.title) }.map { MemoryRowData(it.id, it.title, it.progressSummary, "goal") })
        section("Tasks", tasks.filter { match(it.title) }.map { MemoryRowData(it.id, it.title, it.status.name, "task") })
        section("Commitments", commitments.filter { match(it.title) }.map { MemoryRowData(it.id, it.title, it.status.name, "commitment") })
        section("Open loops", loops.filter { match(it.description) }.map { MemoryRowData(it.id, it.description, it.status.name, "loop") })
        section("Routines", routines.filter { match(it.title) }.map { MemoryRowData(it.id, "${it.title} (${it.schedule ?: "?"})", "confidence ${it.confidence}", "routine") })
        section("Groceries", groceries.filter { match(it.name) }.map { MemoryRowData(it.id, "${it.name} · ${it.status}", it.reason, "grocery") })
        section("Important dates", dates.filter { match(it.title) }.map { MemoryRowData(it.id, "${it.dateIso} ${it.title}", it.notes, "date") })
        item {
            TextButton(onClick = { confirmWipe = true }) {
                Text("Forget everything…", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    editing?.let { (id, content) ->
        var draft by remember(id) { mutableStateOf(content) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit") },
            text = {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onBackground),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateLongContent(id, draft)
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Forget everything?") },
            text = { Text("This removes stored memories. The conversation stays unless you delete those messages too.") },
            confirmButton = {
                TextButton(onClick = {
                    longMem.forEach { vm.deleteLong(it.id) }
                    shortMem.forEach { vm.deleteShort(it.id) }
                    confirmWipe = false
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Keep") } },
        )
    }
}

private fun profileRows(profile: UserProfile?): List<MemoryRowData> {
    if (profile == null) return emptyList()
    return listOfNotNull(
        profile.userName?.takeIf { it.isNotBlank() }?.let { MemoryRowData("profile-name", it, "Your name", "profile") },
        profile.assistantName?.takeIf { it.isNotBlank() }?.let { MemoryRowData("profile-her", "You call her $it", "What you named her", "profile") },
        profile.timezone?.takeIf { it.isNotBlank() }?.let { MemoryRowData("profile-tz", it, "Timezone", "profile") },
        profile.preferredLanguage?.takeIf { it.isNotBlank() }?.let { MemoryRowData("profile-lang", it, "Language", "profile") },
        profile.country?.takeIf { it.isNotBlank() }?.let { MemoryRowData("profile-country", it, "Country", "profile") },
        profile.occupationOrStudyContext?.takeIf { it.isNotBlank() }?.let { MemoryRowData("profile-work", it, "Work or study", "profile") },
        listOfNotNull(profile.typicalWakeTime, profile.typicalSleepTime).takeIf { it.isNotEmpty() }?.let {
            MemoryRowData("profile-hours", it.joinToString(" / "), "Wake / sleep", "profile")
        },
    )
}

private data class MemoryRowData(
    val id: String,
    val title: String,
    val detail: String?,
    val kind: String,
)

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    rows: List<MemoryRowData>,
    showWhenEmpty: Boolean = false,
    emptyHint: String? = null,
    onEdit: ((MemoryRowData) -> Unit)? = null,
    onDelete: ((MemoryRowData) -> Unit)? = null,
) {
    if (rows.isEmpty() && !showWhenEmpty) return
    item {
        Text(
            title,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.SansSerif,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
        )
    }
    if (rows.isEmpty()) {
        item {
            Text(
                emptyHint ?: "",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                fontSize = 15.sp,
                lineHeight = 22.sp,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        return
    }
    items(rows, key = { it.id + it.kind }) { row ->
        MemoryRow(row, onEdit, onDelete)
    }
}

@Composable
private fun MemoryRow(
    row: MemoryRowData,
    onEdit: ((MemoryRowData) -> Unit)?,
    onDelete: ((MemoryRowData) -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(vertical = 6.dp),
    ) {
        Text(row.title, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, lineHeight = 22.sp)
        row.detail?.takeIf { it.isNotBlank() && (open || row.kind == "profile") }?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (open) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (row.kind == "long") {
                    Text("Edit", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { onEdit?.invoke(row) })
                }
                if (row.kind == "long" || row.kind == "short") {
                    Text("Delete", color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable { onDelete?.invoke(row) })
                }
            }
        }
    }
}
