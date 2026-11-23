package com.her.agent.runner

import com.her.domain.DEFAULT_LIST
import org.json.JSONArray
import org.json.JSONObject

/** One write she made during a turn, shown quietly under her reply. */
data class Receipt(
    val tool: String,
    val label: String,
    val entityType: String,
    val entityId: String,
    val undoable: Boolean,
    val undone: Boolean = false,
    /** For a named list: each changed item's state before the turn, see [com.her.agent.tools.undoEntry]. */
    val undo: String? = null,
)

object Receipts {
    private const val KEY = "receipts"
    private const val LISTS_KEY = "lists"
    /** One receipt per named list per reply, undone through [com.her.agent.tools.ToolRegistry.undoList]. */
    const val LISTS = "lists"
    private val LIST_TOOLS = setOf("add_grocery", "update_grocery", "remove_grocery", "clear_purchased_groceries", "add_list_items")
    /** Tools whose list, when named, shows as a checklist under the reply. */
    val SHOWN_LIST_TOOLS = LIST_TOOLS + "show_list"

    private fun listLabel(name: String) = "updated the ${name.take(40)} list"

    /** Null for reads and for private bookkeeping (agent state, queue, understanding, emotional records) that is not meant to be announced. */
    fun describe(tool: String, arguments: String, payloadJson: String): Receipt? {
        val args = runCatching { JSONObject(arguments) }.getOrDefault(JSONObject())
        val payload = runCatching { JSONObject(payloadJson) }.getOrDefault(JSONObject())
        val id = payload.optString("id").ifBlank { args.optString("id") }
        if (id.startsWith("EMOTION_")) return null
        fun text(key: String) = args.optString(key).trim().take(60)
        val created = payload.optBoolean("created", true)
        // Groceries keep their plain name; any other list is named, as in "the packing list".
        val list = payload.optString("list").takeIf { it.isNotBlank() && it != DEFAULT_LIST }?.let { "the ${it.take(40)} list" } ?: DEFAULT_LIST
        val (entityType, label, undoable) = when (tool) {
            "remember" -> Triple(
                if (payload.optString("scope") == "long_term") "long_term_memories" else "short_term_memories",
                "remembered “${text("content")}”",
                true,
            )
            "add_grocery" -> Triple("groceries", "added ${text("name")} to $list", created)
            "create_task" -> Triple("tasks", "task: ${text("title")}", true)
            "log_expense" -> Triple("expenses", "spent ${args.opt("amount")} on ${text("what")}", true)
            "create_commitment" -> Triple("commitments", "promise: ${text("title")}", true)
            "create_goal" -> Triple("goals", "goal: ${text("title")}", true)
            "create_open_loop" -> Triple("open_loops", "waiting on: ${text("description")}", true)
            "create_routine" -> Triple("routines", "routine: ${text("title")}", true)
            "create_important_date" -> Triple("important_dates", "date: ${text("title")}", created)
            "create_recurring_responsibility" -> Triple("recurring_responsibilities", "repeats: ${text("title")}", true)
            "create_calendar_event" -> Triple("calendar_events", "calendar: ${text("title")}", true)
            "update_person" -> Triple("people", "noted about ${text("name").ifBlank { "someone" }}", false)
            "update_project" -> Triple("projects", "updated project ${payload.optString("name").take(60)}".trim(), false)
            "update_task" -> Triple("tasks", "updated task: ${payload.optString("title").take(60)}", false)
            "update_commitment" -> Triple("commitments", "updated a promise", false)
            "update_goal" -> Triple("goals", "updated a goal", false)
            "update_grocery", "remove_grocery", "clear_purchased_groceries" -> Triple("groceries", "updated $list", false)
            "add_list_items" -> Triple(
                "groceries",
                if (args.optString("replace").isNotBlank()) "broke a step down on $list" else "added ${args.optJSONArray("items")?.length() ?: 0} items to $list",
                false,
            )
            "update_calendar_event" -> Triple("calendar_events", "moved an event", false)
            "delete_calendar_event" -> Triple("calendar_events", "removed an event", false)
            "correct_memory", "update_memory", "forget_memory" -> Triple("memories", "updated what she remembers", false)
            "update_user_profile" -> Triple("user_profile", "updated your profile", false)
            "save_decision", "save_action_capacity", "accept_daily_plan", "save_life_thread", "save_thread_development", "save_waiting_on", "save_event_preparation", "save_work_session" -> Triple("companion_records", "saved ongoing support", created)
            "save_responsibility_schedule", "manage_occurrence" -> Triple("companion_records", "updated recurring responsibility", false)
            "save_checkin_preferences", "dismiss_companion_checkin" -> Triple("companion_records", "updated check-ins", false)
            "save_learning_path" -> Triple("learning_records", "saved learning path", false)
            "save_learning_lesson" -> Triple("learning_records", "saved learning lesson", false)
            "record_learning_answer" -> Triple("learning_records", "saved practice progress", false)
            "set_reminder" -> Triple("reminders", "reminder: ${text("message")}", true)
            "update_reminder" -> Triple("reminders", "changed reminder: ${payload.optString("message").take(60)}", false)
            "cancel_reminder" -> Triple("reminders", "cancelled reminder: ${payload.optString("message").take(60)}", false)
            "start_focus" -> Triple("focus", "focus: ${payload.optString("rounds")} × ${payload.optString("minutes")} min on ${payload.optString("label").take(40)}", false)
            "set_alarm" -> if (payload.optString("ringsIn") == "Her") {
                Triple("reminders", "alarm: ${payload.optString("clock")}", true)
            } else {
                Triple("alarms", "alarm: ${payload.optString("clock")}", false)
            }
            else -> return null
        }
        if (tool in LIST_TOOLS && list != DEFAULT_LIST) {
            val undo = payload.optJSONArray("undo")
            return Receipt(tool, label, LISTS, payload.optString("list"), undoable = undo != null && undo.length() > 0, undo = undo?.toString())
        }
        return Receipt(tool, label, entityType, id, undoable = undoable && id.isNotBlank())
    }

    /** Assistant-message metadata for these receipts and the named lists to show, or null when there is nothing. */
    fun toMetadata(receipts: List<Receipt>, lists: List<String> = emptyList()): String? {
        val merged = receipts.groupBy { if (it.entityType == LISTS) "$LISTS:${it.entityId}" else null }
            .flatMap { (key, group) -> if (key == null) group else listOf(mergeList(group)) }
        val unique = merged.distinctBy { if (it.undoable || it.entityType == LISTS) "${it.entityType}:${it.entityId}" else it.label }
        val shown = lists.distinct()
        if (unique.isEmpty() && shown.isEmpty()) return null
        return JSONObject().apply {
            if (unique.isNotEmpty()) put(KEY, JSONArray(unique.map { it.toJson() }))
            if (shown.isNotEmpty()) put(LISTS_KEY, JSONArray(shown))
        }.toString()
    }

    /** Named lists to show as checklists under this reply. */
    fun listsOf(metadataJson: String?): List<String> {
        if (metadataJson.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONObject(metadataJson).optJSONArray(LISTS_KEY) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
    }

    /**
     * One turn's edits to a list undo together. Per item, the state from before its first edit and
     * the version after its last, so undo restores the start of the turn and only if nothing changed since.
     */
    private fun mergeList(group: List<Receipt>): Receipt {
        if (group.size == 1) return group.single()
        val byId = linkedMapOf<String, JSONObject>()
        group.forEach { receipt ->
            val entries = receipt.undo?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return@forEach
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val id = entry.getString("id")
                byId[id] = byId[id]?.let { JSONObject(it.toString()).put("versionAfter", entry.getLong("versionAfter")) } ?: entry
            }
        }
        return group.first().copy(
            label = listLabel(group.first().entityId),
            undoable = group.all { it.undoable },
            undo = JSONArray(byId.values).toString(),
        )
    }

    fun fromMetadata(metadataJson: String?): List<Receipt> {
        if (metadataJson.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONObject(metadataJson).optJSONArray(KEY) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            Receipt(
                tool = obj.optString("tool"),
                label = obj.optString("label"),
                entityType = obj.optString("entityType"),
                entityId = obj.optString("entityId"),
                undoable = obj.optBoolean("undoable"),
                undone = obj.optBoolean("undone"),
                undo = obj.optString("undo").ifBlank { null },
            )
        }
    }

    /** Marks these receipts undone, keeping any other metadata on the message. */
    fun markUndone(metadataJson: String?, undone: List<Receipt>): String {
        val root = runCatching { JSONObject(metadataJson ?: "{}") }.getOrDefault(JSONObject())
        val keys = undone.map { "${it.entityType}:${it.entityId}" }.toSet()
        val updated = fromMetadata(metadataJson).map {
            if ("${it.entityType}:${it.entityId}" in keys) it.copy(undone = true) else it
        }
        return root.put(KEY, JSONArray(updated.map { it.toJson() })).toString()
    }

    private fun Receipt.toJson(): JSONObject = JSONObject()
        .put("tool", tool)
        .put("label", label)
        .put("entityType", entityType)
        .put("entityId", entityId)
        .put("undoable", undoable)
        .put("undone", undone)
        .apply { undo?.let { put("undo", it) } }
}

/** Tool writes already applied for a still-pending user batch, so a retry does not duplicate them. */
internal object TurnWrites {
    const val KEY = "turnWrites"

    data class Write(val name: String, val arguments: String, val payloadJson: String)

    fun load(metadataJson: String?): MutableList<Write> {
        if (metadataJson.isNullOrBlank()) return mutableListOf()
        val array = runCatching { JSONObject(metadataJson).optJSONArray(KEY) }.getOrNull() ?: return mutableListOf()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            Write(obj.optString("name"), obj.optString("arguments"), obj.optString("payload"))
        }.toMutableList()
    }

    fun save(metadataJson: String?, writes: List<Write>): String {
        val root = runCatching { JSONObject(metadataJson ?: "{}") }.getOrDefault(JSONObject())
        val array = JSONArray()
        writes.forEach { write ->
            array.put(JSONObject().put("name", write.name).put("arguments", write.arguments).put("payload", write.payloadJson))
        }
        return root.put(KEY, array).toString()
    }

    fun describe(writes: List<Write>): String =
        writes.joinToString("; ") { "${it.name} ${it.payloadJson.take(120)}" }
}
