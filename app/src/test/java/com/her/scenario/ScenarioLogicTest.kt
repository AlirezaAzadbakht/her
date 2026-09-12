package com.her.scenario

import android.app.Application
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ScenarioLogicTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val now = ZonedDateTime.of(
        LocalDate.of(2026, 9, 12),
        LocalTime.of(15, 0),
        ZoneId.of("Asia/Tehran"),
    )

    @Test
    fun parsesCalendarScenarioShape() {
        val file = tmp.newFile("calendar-set-meeting.json")
        file.writeText(
            """
            {
              "id": "calendar-set-meeting",
              "title": "Set a meeting in the calendar",
              "tags": ["calendar"],
              "attempts": 2,
              "settings": { "chatToolCallLimit": 12 },
              "seed": {
                "profile": { "userName": "Alireza", "timezone": "Asia/Tehran" },
                "tools": [{ "name": "update_person", "arguments": { "name": "Sara" } }]
              },
              "turns": [{ "user": "Set a meeting next Tuesday at 10am." }],
              "expect": {
                "tools_called": ["create_calendar_event"],
                "rows": [{
                  "table": "calendar_events",
                  "count": 1,
                  "where": { "title": { "contains_any": ["meeting"] }, "startAt": { "date_is": "next tuesday" } }
                }],
                "reply": { "must_mention_any": ["tuesday"], "judge": "Scheduled." }
              }
            }
            """.trimIndent(),
        )
        val spec = ScenarioLoader.parse(file)
        assertEquals("calendar-set-meeting", spec.id)
        assertEquals(2, spec.attempts)
        assertEquals("Sara", org.json.JSONObject(spec.seed.tools.single().argumentsJson).getString("name"))
        assertEquals(listOf("create_calendar_event"), spec.expect.toolsCalled)
        assertTrue(spec.turns.single() is ScenarioTurn.User)
    }

    @Test
    fun parsesSystemCalendarSeed() {
        val file = tmp.newFile("system-calendar-create-event.json")
        file.writeText(
            """
            {
              "id": "system-calendar-create-event",
              "title": "Write to the device calendar",
              "settings": { "calendarEnabled": true },
              "seed": {
                "profile": { "userName": "Alireza", "timezone": "Asia/Tehran" },
                "system_calendar": [{ "title": "Dentist", "when": "tomorrow at 10am" }]
              },
              "turns": [{ "user": "What's tomorrow?" }],
              "expect": {
                "rows": [{
                  "table": "system_calendar",
                  "count": 1,
                  "where": { "title": { "contains": "dentist" } }
                }]
              }
            }
            """.trimIndent(),
        )
        val spec = ScenarioLoader.parse(file)
        assertTrue(spec.settings.calendarEnabled)
        assertEquals("Dentist", spec.seed.systemCalendar.single().title)
        assertEquals("tomorrow at 10am", spec.seed.systemCalendar.single().whenPhrase)
        assertEquals("system_calendar", spec.expect.rows.single().table)
    }

    @Test(expected = ScenarioParseException::class)
    fun rejectsUnknownKey() {
        val file = tmp.newFile("bad.json")
        file.writeText("""{"id":"bad","title":"Bad","turns":[{"user":"hi"}],"expect":{},"nope":true}""")
        ScenarioLoader.parse(file)
    }

    @Test
    fun dateAndTimeMatchersUseRelativeParser() {
        val tuesday = Checks.parseDatePhrase("next tuesday", now)
        assertEquals(LocalDate.of(2026, 9, 15), tuesday)
        assertEquals(LocalTime.of(10, 0), Checks.parseTimePhrase("10:00"))
        assertEquals(LocalTime.of(10, 0), Checks.parseTimePhrase("10am"))
        val start = now.withYear(2026).withMonth(9).withDayOfMonth(15).withHour(10).withMinute(0)
            .toInstant().toEpochMilli()
        assertTrue(
            Checks.matches(
                start,
                FieldMatcher.DateIs("next tuesday"),
                now,
            ),
        )
        assertTrue(Checks.matches(start, FieldMatcher.TimeIs("10:00"), now))
        assertFalse(Checks.matches(start, FieldMatcher.TimeIs("11:00"), now))
    }

    @Test
    fun envParserReadsQuotedValues() {
        val file = File(tmp.newFolder(), ".env")
        file.writeText(
            """
            LLM_API_BASE_URL=https://example.test/v1
            LLM_MODEL_IDENTIFIER="gpt-test"
            LLM_API_SECRET_KEY='sk-test'
            """.trimIndent(),
        )
        val values = ScenarioEnv.parseDotEnv(file)
        assertEquals("https://example.test/v1", values["LLM_API_BASE_URL"])
        assertEquals("gpt-test", values["LLM_MODEL_IDENTIFIER"])
        assertEquals("sk-test", values["LLM_API_SECRET_KEY"])
    }

    @Test
    fun judgeParsesWrappedJson() {
        val result = Judge.parse("Sure.\n{\"pass\": true, \"reason\": \"ok\"}\n")
        assertTrue(result.passed)
        assertEquals("ok", result.reason)
    }
}
