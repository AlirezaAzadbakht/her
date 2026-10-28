package com.her.data.google

import com.her.data.repository.HerRepository
import com.her.data.secure.AppSettingsStore
import com.her.domain.TaskItem
import com.her.domain.TaskStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Mirrors her tasks and a "Her" list in Google Tasks, both ways. No model calls. Google keeps dates, not times,
 * so a task edited there comes back due at the end of that day.
 */
class GoogleTasksSync(
    private val repo: HerRepository,
    private val client: GoogleTasksClient,
    private val settings: AppSettingsStore,
) {
    suspend fun sync() {
        val app = settings.read()
        if (!app.googleTasksEnabled) return
        val token = client.token() ?: return
        val started = repo.clock.nowMillis()
        val listId = client.herListId(token)
        pull(token, listId, app.lastTasksSyncAt)
        // ponytail: rows pull just changed are pushed back once next time, an idempotent PATCH.
        push(token, listId, app.lastTasksSyncAt)
        settings.update { it.copy(lastTasksSyncAt = started) }
        repo.logActivity("tasks", "Synced with Google Tasks.")
    }

    private suspend fun pull(token: String, listId: String, since: Long) {
        client.changedSince(token, listId, since).forEach { remote ->
            val id = remote.id ?: return@forEach
            val local = repo.taskByGoogleId(id) ?: repo.getTask(pulledId(id))
            if (local == null) {
                if (!remote.deleted && !remote.completed) repo.saveTask(newTask(remote, id))
                return@forEach
            }
            // The newer side wins; an older remote change is overwritten by the push below.
            if (remote.updatedAt <= local.updatedAt) return@forEach
            val status = when {
                remote.deleted -> TaskStatus.DROPPED
                remote.completed -> TaskStatus.DONE
                local.status == TaskStatus.DONE -> TaskStatus.OPEN
                else -> local.status
            }
            repo.saveTask(
                local.copy(
                    title = remote.title.ifBlank { local.title },
                    description = remote.notes ?: local.description,
                    status = status,
                    dueAt = if (remote.due == local.dueAt?.let { localDate(it) }) local.dueAt else remote.due?.let { endOfDay(it) },
                    googleTaskId = id,
                    updatedAt = repo.clock.nowMillis(),
                    version = local.version + 1,
                ),
            )
        }
    }

    private suspend fun push(token: String, listId: String, since: Long) {
        repo.tasksChangedSince(since).forEach { task ->
            val googleId = task.googleTaskId
            when {
                // Only the device that made a task sends it the first time, so two phones do not both add it.
                googleId == null -> if (task.status == TaskStatus.OPEN && task.deletedAt == null && task.deviceId == repo.deviceId) {
                    val created = client.insert(token, listId, remote(task))
                    repo.saveTask(task.copy(googleTaskId = created, updatedAt = repo.clock.nowMillis(), version = task.version + 1))
                }
                task.deletedAt != null || task.status == TaskStatus.DROPPED -> client.delete(token, listId, googleId)
                else -> client.patch(token, listId, googleId, remote(task))
            }
        }
    }

    private suspend fun remote(task: TaskItem) = RemoteTask(
        id = task.googleTaskId,
        title = task.title,
        notes = task.description,
        due = task.dueAt?.let { localDate(it) },
        completed = task.status == TaskStatus.DONE,
    )

    private suspend fun newTask(remote: RemoteTask, googleId: String): TaskItem {
        val now = repo.clock.nowMillis()
        return TaskItem(
            id = pulledId(googleId),
            title = remote.title.ifBlank { "(untitled)" },
            description = remote.notes,
            status = TaskStatus.OPEN,
            dueAt = remote.due?.let { endOfDay(it) },
            relatedProjectId = null,
            relatedGoalId = null,
            createdAt = now,
            updatedAt = now,
            deviceId = repo.deviceId,
            version = 1,
            deletedAt = null,
            googleTaskId = googleId,
        )
    }

    private suspend fun localDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(repo.profileZone()).toLocalDate()

    private suspend fun endOfDay(date: LocalDate): Long =
        date.atTime(LocalTime.of(23, 59)).atZone(repo.profileZone()).toInstant().toEpochMilli()

    /** Same id on every device that pulls the task, so they converge on one row. */
    private fun pulledId(googleId: String) = "gtask-$googleId"
}
