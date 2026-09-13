package com.her.agent.tools

import org.json.JSONArray
import org.json.JSONObject

internal const val HOUR_MS = 60 * 60 * 1000L

internal class Field(val type: String, val description: String, val values: List<String> = emptyList())

internal fun str(desc: String = "") = Field("string", desc)
internal fun num(desc: String = "") = Field("number", desc)
internal fun bool(desc: String = "") = Field("boolean", desc)
internal fun arr(desc: String = "") = Field("array", desc)
internal fun oneOf(desc: String, vararg values: String) = Field("string", desc, values.toList())
internal inline fun <reified T : Enum<T>> enumField(desc: String = "") = Field("string", desc, enumValues<T>().map { it.name })

internal const val WHEN_HINT = "ISO-8601, epoch millis, Jalali, or a natural phrase such as 'Friday at 5pm'"

internal fun objSchema(vararg fields: Pair<String, Field>, required: List<String> = emptyList()): String {
    val props = JSONObject()
    fields.forEach { (name, field) ->
        val schema = JSONObject().put("type", field.type)
        if (field.description.isNotBlank()) schema.put("description", field.description)
        if (field.type == "array") schema.put("items", JSONObject().put("type", "string"))
        if (field.values.isNotEmpty()) schema.put("enum", JSONArray(field.values))
        props.put(name, schema)
    }
    return JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required)).toString()
}
