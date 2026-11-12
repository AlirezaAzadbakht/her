package com.her.data.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.content.ContextCompat

data class PhoneContact(val name: String, val phones: List<String>, val birthday: String?)

/** Read-only view of the phone's contacts. Null means there is no permission to look. */
fun interface ContactsSource {
    fun search(name: String): List<PhoneContact>?

    companion object {
        val NONE = ContactsSource { null }
    }
}

class AndroidContacts(private val context: Context) : ContactsSource {
    override fun search(name: String): List<PhoneContact>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val found = linkedMapOf<Long, Triple<String, MutableList<String>, String?>>()
        // One pass over the phone and birthday rows of contacts whose name matches.
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.DISPLAY_NAME, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1, ContactsContract.Data.DATA2),
            "${ContactsContract.Data.DISPLAY_NAME} LIKE ? AND ${ContactsContract.Data.MIMETYPE} IN (?, ?)",
            arrayOf("%$name%", Phone.CONTENT_ITEM_TYPE, Event.CONTENT_ITEM_TYPE),
            "${ContactsContract.Data.DISPLAY_NAME} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val current = found.getOrPut(id) { Triple(c.getString(1).orEmpty(), mutableListOf(), null) }
                val value = c.getString(3) ?: continue
                if (c.getString(2) == Phone.CONTENT_ITEM_TYPE) {
                    if (value !in current.second) current.second += value
                } else if (c.getInt(4) == Event.TYPE_BIRTHDAY) {
                    found[id] = current.copy(third = value)
                }
            }
        }
        return found.values.take(MAX_RESULTS).map { PhoneContact(it.first, it.second, it.third) }
    }

    private companion object {
        const val MAX_RESULTS = 8
    }
}
