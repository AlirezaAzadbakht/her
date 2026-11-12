package com.her.agent.tools

import com.her.core.ToolValidationException
import com.her.core.jsonObjectOf
import com.her.core.requiredString
import org.json.JSONArray

internal const val CONTACTS_TOOL = "search_contacts"

internal fun ToolRegistry.registerContactTools() {
    register(
        CONTACTS_TOOL,
        "Look up a phone number or birthday in the phone's contacts by name. Read-only. To keep a birthday, call update_person with it.",
        objSchema("name" to str("Name or part of it"), required = listOf("name")),
    ) { a ->
        if (!settings.read().contactsEnabled) throw ToolValidationException("Contacts access is off. They can turn it on in Settings → Connections → Contacts access.")
        val found = contacts.search(a.requiredString("name").trim())
            ?: throw ToolValidationException("The contacts permission is not granted. They can allow it in Settings → Connections → Contacts access.")
        jsonObjectOf(
            "ok" to true,
            "contacts" to JSONArray(found.map { jsonObjectOf("name" to it.name, "phones" to JSONArray(it.phones), "birthday" to it.birthday) }),
        )
    }
}
