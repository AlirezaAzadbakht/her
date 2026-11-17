package com.her.agent.tools

import com.her.core.ToolValidationException
import com.her.core.jsonObjectOf
import com.her.core.requiredString
import org.json.JSONArray

internal const val CONTACTS_TOOL = "search_contacts"
internal const val COMPOSE_TOOL = "compose_message"

private val PHONE_NUMBER = Regex("""^\+?[\d\s().-]{3,}$""")

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
    register(
        COMPOSE_TOOL,
        "Open a text to someone in the phone's messaging app, filled in for them to send themselves. Her never sends it. " +
            "to is a phone number, or a name looked up in contacts. If several contacts or numbers match, it returns them: ask which one.",
        objSchema("to" to str("Phone number or contact name"), "text" to str("The message, in their words"), required = listOf("to", "text")),
    ) { a ->
        val to = a.requiredString("to").trim()
        val text = a.requiredString("text").trim()
        val phone = if (PHONE_NUMBER.matches(to)) {
            to
        } else {
            if (!settings.read().contactsEnabled) throw ToolValidationException("Contacts access is off, so ask for the number, or they can turn it on in Settings → Connections → Contacts access.")
            val found = contacts.search(to)
                ?: throw ToolValidationException("The contacts permission is not granted. Ask for the number, or they can allow it in Settings → Connections → Contacts access.")
            val exact = found.filter { it.name.equals(to, ignoreCase = true) }
            val match = (exact.ifEmpty { found }).singleOrNull()
            when {
                found.isEmpty() -> throw ToolValidationException("No contact matches \"$to\". Ask for the number.")
                match != null && match.phones.isEmpty() -> throw ToolValidationException("${match.name} has no phone number in contacts. Ask for the number.")
                match == null || match.phones.size != 1 -> return@register jsonObjectOf(
                    "ok" to false,
                    "error" to "More than one number fits. Ask which one, then call again with that number.",
                    "candidates" to JSONArray(found.filter { it.phones.isNotEmpty() }.map { jsonObjectOf("name" to it.name, "phones" to JSONArray(it.phones)) }),
                )
                else -> match.phones.single()
            }
        }
        composer.compose(phone, text).getOrElse { throw ToolValidationException(it.message ?: "Could not open the messaging app") }
        jsonObjectOf("ok" to true, "opened" to "messaging app", "to" to phone, "sent" to false)
    }
}
