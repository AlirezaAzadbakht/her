package com.her.agent.runner

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
)

object Receipts {
    private const val KEY = "receipts"

    /** Null for reads and for private bookkeeping (agent state, queue, understanding) that is not meant to be announced. */
    fun describe(tool: String, arguments: String, payloadJson: String): Receipt? {
        val args = runCatching { JSONObject(arguments) }.getOrDefault(JSONObject())
        val payload = runCatching { JSONObject(payloadJson) }.getOrDefault(JSONObject())
        val id = payload.optString("id").ifBlank { args.optString("id") }
        fun text(key: String) = args.optString(key).trim().take(60)
        val created = payload.optBoolean("created", true)
        val (entityType, label, undoable) = when (tool) {
            "remember" -> Triple(
                if (payload.optString("scope") == "long_term") "long_term_memories" else "short_term_memories",
                "remembered “${text("content")}”",
                true,
            )
            "add_grocery" -> Triple("groceries", "added ${text("name")} to groceries", created)
            "create_task" -> Triple("tasks", "task: ${text("title")}", true)
            "create_commitment" -> Triple("commitments", "promise: ${text("title")}", true)
            "create_goal" -> Triple("goals", "goal: ${text("title")}", true)
            "create_open_loop" -> Triple("open_loops", "waiting on: ${text("description")}", true)
            "create_routine" -> Triple("routines", "routine: ${text("title")}", true)
            "create_important_date" -> Triple("important_dates", "date: ${text("title")}", created)
            "create_recurring_responsibility" -> Triple("recurring_responsibilities", "repeats: ${text("title")}", true)
            "create_calendar_event" -> Triple("calendar_events", "calendar: ${text("title")}", true)
            "update_person" -> Triple("people", "noted about ${text("name").ifBlank { "someone" }}", false)
            "update_project" -> Triple("projects", "updated project ${text("name")}".trim(), false)
            "update_task" -> Triple("tasks", "updated a task", false)
            "update_commitment" -> Triple("commitments", "updated a promise", false)
            "update_goal" -> Triple("goals", "updated a goal", false)
            "update_grocery", "remove_grocery", "clear_purchased_groceries" -> Triple("groceries", "updated groceries", false)
            "update_calendar_event" -> Triple("calendar_events", "moved an event", false)
            "delete_calendar_event" -> Triple("calendar_events", "removed an event", false)
            "update_memory", "forget_memory" -> Triple("memories", "updated what she remembers", false)
            "update_user_profile" -> Triple("user_profile", "updated your profile", false)
            else -> return null
        }
        return Receipt(tool, label, entityType, id, undoable = undoable && id.isNotBlank())
    }

    /** Assistant-message metadata for these receipts, or null when there is nothing to show. */
    fun toMetadata(receipts: List<Receipt>): String? {
        val unique = receipts.distinctBy { if (it.undoable) "${it.entityType}:${it.entityId}" else it.label }
        if (unique.isEmpty()) return null
        return JSONObject().put(KEY, JSONArray(unique.map { it.toJson() })).toString()
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
}
