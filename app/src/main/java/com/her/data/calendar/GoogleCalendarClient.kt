package com.her.data.calendar

import com.her.core.newId
import com.her.core.nowMillis
import com.her.data.google.GoogleAuthService
import com.her.domain.CalendarEvent
import com.her.domain.CalendarSource
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

open class GoogleCalendarClient(
    private val auth: GoogleAuthService? = null,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    open suspend fun available(): Boolean = !token().isNullOrBlank()

    open suspend fun eventsBetween(from: Long, to: Long): Result<List<CalendarEvent>> {
        val access = token() ?: return Result.success(emptyList())
        return try {
            Result.success(fetchAll(access, from, to))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    protected open suspend fun token(): String? =
        auth?.authorize(drive = false, calendar = true)?.accessToken?.takeIf { it.isNotBlank() }

    private fun fetchAll(token: String, from: Long, to: Long): List<CalendarEvent> {
        val now = nowMillis()
        return listCalendars(token).flatMap { calendarId ->
            listEvents(token, calendarId, from, to).mapNotNull { parseEvent(it, calendarId, now) }
        }.sortedBy { it.startAt }
    }

    private fun listCalendars(token: String): List<String> {
        val body = get(
            token,
            "https://www.googleapis.com/calendar/v3/users/me/calendarList" +
                "?fields=items(id,selected,hidden)&maxResults=100",
        )
        val items = body.optJSONArray("items") ?: return emptyList()
        val ids = mutableListOf<String>()
        for (i in 0 until items.length()) {
            if (ids.size >= 10) break
            val item = items.optJSONObject(i) ?: continue
            if (item.optBoolean("hidden", false)) continue
            if (!item.optBoolean("selected", true)) continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            ids += id
        }
        return ids
    }

    private fun listEvents(token: String, calendarId: String, from: Long, to: Long): List<JSONObject> {
        val encoded = URLEncoder.encode(calendarId, StandardCharsets.UTF_8.name())
        val timeMin = URLEncoder.encode(Instant.ofEpochMilli(from).toString(), StandardCharsets.UTF_8.name())
        val timeMax = URLEncoder.encode(Instant.ofEpochMilli(to).toString(), StandardCharsets.UTF_8.name())
        val body = get(
            token,
            "https://www.googleapis.com/calendar/v3/calendars/$encoded/events" +
                "?timeMin=$timeMin&timeMax=$timeMax&singleEvents=true&orderBy=startTime&maxResults=250",
        )
        val items = body.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.let { add(it) }
            }
        }
    }

    private fun get(token: String, url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Google Calendar request failed (${response.code})")
            return if (body.isBlank()) JSONObject() else JSONObject(body)
        }
    }

    companion object {
        fun parseEvent(obj: JSONObject, calendarId: String, now: Long): CalendarEvent? {
            val eventId = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
            val title = obj.optString("summary").ifBlank { "(untitled)" }
            val start = parseGoogleTime(obj.optJSONObject("start")) ?: return null
            val end = parseGoogleTime(obj.optJSONObject("end"))
            return CalendarEvent(
                id = newId(),
                title = title,
                startAt = start,
                endAt = end,
                location = obj.optString("location").takeIf { it.isNotBlank() },
                notes = obj.optString("description").takeIf { it.isNotBlank() },
                externalId = "google:$calendarId:$eventId",
                source = CalendarSource.GOOGLE,
                calendarId = calendarId,
                createdAt = now,
                updatedAt = now,
                deviceId = "google",
                version = 1,
                deletedAt = null,
            )
        }

        fun parseGoogleTime(obj: JSONObject?): Long? {
            if (obj == null) return null
            return parseGoogleTime(
                obj.optString("dateTime").takeIf { it.isNotBlank() },
                obj.optString("date").takeIf { it.isNotBlank() },
            )
        }

        fun parseGoogleTime(dateTime: String?, date: String?): Long? {
            if (!dateTime.isNullOrBlank()) {
                return runCatching { OffsetDateTime.parse(dateTime).toInstant().toEpochMilli() }.getOrNull()
                    ?: runCatching { Instant.parse(dateTime).toEpochMilli() }.getOrNull()
                    ?: runCatching { java.time.ZonedDateTime.parse(dateTime).toInstant().toEpochMilli() }.getOrNull()
            }
            if (date.isNullOrBlank()) return null
            return runCatching {
                LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }.getOrNull()
        }
    }
}
