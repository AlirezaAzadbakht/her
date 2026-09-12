package com.her

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ToolSchemaTest {
    @Test
    fun requiredFieldsAreDetected() {
        val properties = JSONObject()
        val querySchema = JSONObject()
        querySchema.put("type", "string")
        properties.put("query", querySchema)
        val required = JSONArray()
        required.put("query")
        val schema = JSONObject()
        schema.put("type", "object")
        schema.put("properties", properties)
        schema.put("required", required)
        val args = JSONObject()
        assertTrue(missingRequired(schema, args))
        args.put("query", "coffee")
        assertFalse(missingRequired(schema, args))
    }

    private fun missingRequired(schema: JSONObject, args: JSONObject): Boolean {
        val required = schema.getJSONArray("required")
        for (i in 0 until required.length()) {
            val key = required.getString(i)
            if (!args.has(key) || args.optString(key).isBlank()) return true
        }
        return false
    }
}
