package com.her.agent.tools

import com.her.core.jsonObjectOf
import com.her.core.requiredString
import com.her.agent.prompt.Identity
import org.json.JSONObject

internal fun ToolRegistry.registerMessagingTools() {
    register("web_search", "Search the public web for current external facts. Do not use for personal memory.", objSchema("query" to str(), required = listOf("query"))) { args ->
        val profile = repo.getProfile()
        JSONObject(
            webSearch.search(
                query = args.requiredString("query"),
                settings = settings.read(),
                country = profile.country,
                timezone = profile.timezone,
            ),
        )
    }
    register("send_user_message", "Deliver a proactive message into the conversation. Use rarely.", objSchema("content" to str(), required = listOf("content"))) { args ->
        val text = args.requiredString("content").trim()
        if (Identity.isSilence(text)) {
            return@register jsonObjectOf("ok" to true, "decision" to Identity.NO_NOTIFICATION)
        }
        onUserMessage(text)
        jsonObjectOf("ok" to true)
    }
}
