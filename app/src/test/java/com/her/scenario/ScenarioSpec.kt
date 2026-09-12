package com.her.scenario

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class ScenarioSpec(
    val file: File,
    val id: String,
    val title: String,
    val tags: List<String>,
    val attempts: Int,
    val pending: Boolean,
    val settings: ScenarioSettings,
    val seed: ScenarioSeed,
    val turns: List<ScenarioTurn>,
    val expect: ScenarioExpect,
)

data class ScenarioSettings(
    val chatToolCallLimit: Int?,
    val calendarEnabled: Boolean,
)

data class ScenarioSeed(
    val profile: Map<String, String>,
    val tools: List<SeedToolCall>,
    val systemCalendar: List<SeedCalendarEvent>,
)

data class SeedCalendarEvent(
    val title: String,
    val whenPhrase: String,
    val notes: String?,
)

data class SeedToolCall(
    val name: String,
    val argumentsJson: String,
)

sealed class ScenarioTurn {
    data class User(val text: String) : ScenarioTurn()
    data class Run(val kind: AutonomousRun) : ScenarioTurn()
}

enum class AutonomousRun {
    HOURLY,
    NIGHTLY,
    BRIEFING,
}

data class ScenarioExpect(
    val toolsCalled: List<String>,
    val toolsNotCalled: List<String>,
    val noToolErrors: Boolean,
    val rows: List<RowExpectation>,
    val reply: ReplyExpectation?,
)

data class RowExpectation(
    val table: String,
    val count: Int?,
    val where: Map<String, List<FieldMatcher>>,
)

data class ReplyExpectation(
    val mustMentionAny: List<String>,
    val judge: String?,
)

sealed class FieldMatcher {
    data class Equals(val value: Any) : FieldMatcher()
    data class EqualsIgnoreCase(val value: String) : FieldMatcher()
    data class Contains(val value: String) : FieldMatcher()
    data class ContainsAny(val values: List<String>) : FieldMatcher()
    data class ContainsAll(val values: List<String>) : FieldMatcher()
    data class Matches(val pattern: String) : FieldMatcher()
    data class OneOf(val values: List<String>) : FieldMatcher()
    data object NotEmpty : FieldMatcher()
    data class GreaterThan(val value: Double) : FieldMatcher()
    data class GreaterOrEqual(val value: Double) : FieldMatcher()
    data class LessThan(val value: Double) : FieldMatcher()
    data class LessOrEqual(val value: Double) : FieldMatcher()
    data class DateIs(val phrase: String) : FieldMatcher()
    data class TimeIs(val phrase: String) : FieldMatcher()
    data class WithinDays(val days: Int) : FieldMatcher()
}

object ScenarioTables {
    val ALL = setOf(
        "chat_messages",
        "people",
        "projects",
        "goals",
        "tasks",
        "commitments",
        "open_loops",
        "routines",
        "groceries",
        "important_dates",
        "recurring_responsibilities",
        "calendar_events",
        "system_calendar",
        "agent_queue",
        "agent_state",
        "memories_long",
        "memories_short",
        "profile",
    )
}

object ScenarioLoader {
    private val topKeys = setOf(
        "id", "title", "tags", "attempts", "pending", "settings", "seed", "turns", "expect",
    )
    private val settingsKeys = setOf("chatToolCallLimit", "calendarEnabled")
    private val seedKeys = setOf("profile", "tools", "system_calendar")
    private val seedCalendarKeys = setOf("title", "when", "notes")
    private val profileKeys = setOf(
        "userName", "assistantName", "timezone", "preferredLanguage", "country",
        "typicalWakeTime", "typicalSleepTime", "occupationOrStudyContext",
    )
    private val seedToolKeys = setOf("name", "arguments")
    private val turnKeys = setOf("user", "run")
    private val expectKeys = setOf("tools_called", "tools_not_called", "no_tool_errors", "rows", "reply")
    private val rowKeys = setOf("table", "count", "where")
    private val replyKeys = setOf("must_mention_any", "judge")
    private val matcherKeys = setOf(
        "equals", "equals_ignore_case", "contains", "contains_any", "contains_all",
        "matches", "one_of", "not_empty", "gt", "gte", "lt", "lte", "date_is", "time_is", "within_days",
    )
    private val runKinds = setOf("hourly", "nightly", "briefing")

    fun loadPool(dir: File = ScenarioPaths.scenariosDir()): List<ScenarioSpec> {
        if (!dir.isDirectory) {
            error("Scenario pool not found: ${dir.absolutePath}")
        }
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.sortedBy { it.name }
            .orEmpty()
        if (files.isEmpty()) {
            error("Scenario pool is empty: ${dir.absolutePath}")
        }
        val specs = files.map { parse(it) }
        val dupes = specs.groupingBy { it.id }.eachCount().filter { it.value > 1 }.keys
        if (dupes.isNotEmpty()) {
            error("Duplicate scenario ids: ${dupes.joinToString()}")
        }
        return specs
    }

    fun parse(file: File): ScenarioSpec {
        val root = try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            throw ScenarioParseException(file, "invalid JSON: ${e.message}")
        }
        return parse(file, root)
    }

    fun parse(file: File, root: JSONObject): ScenarioSpec {
        val path = file.name
        rejectUnknown(root, topKeys, path)
        val id = root.requiredString(file, "id", path)
        val title = root.requiredString(file, "title", path)
        val tags = root.optionalStringList("tags", "$path.tags")
        val attempts = root.optInt("attempts", 1)
        if (attempts < 1) throw ScenarioParseException(file, "attempts must be >= 1")
        val pending = root.optBoolean("pending", false)
        val settings = parseSettings(file, root.optJSONObject("settings"), "$path.settings")
        val seed = parseSeed(file, root.optJSONObject("seed"), "$path.seed")
        val turnsArray = root.optJSONArray("turns")
            ?: throw ScenarioParseException(file, "turns is required")
        if (turnsArray.length() == 0) {
            throw ScenarioParseException(file, "turns must not be empty")
        }
        val turns = (0 until turnsArray.length()).map { index ->
            parseTurn(file, turnsArray.requiredObject(index, "$path.turns[$index]"), "$path.turns[$index]")
        }
        val expectObj = root.optJSONObject("expect")
            ?: throw ScenarioParseException(file, "expect is required")
        val expect = parseExpect(file, expectObj, "$path.expect")
        return ScenarioSpec(
            file = file,
            id = id,
            title = title,
            tags = tags,
            attempts = attempts,
            pending = pending,
            settings = settings,
            seed = seed,
            turns = turns,
            expect = expect,
        )
    }

    private fun parseSettings(file: File, obj: JSONObject?, path: String): ScenarioSettings {
        if (obj == null) return ScenarioSettings(null, false)
        rejectUnknown(obj, settingsKeys, path, file)
        val limit = if (obj.has("chatToolCallLimit")) obj.optInt("chatToolCallLimit") else null
        if (limit != null && limit < 1) {
            throw ScenarioParseException(file, "$path.chatToolCallLimit must be >= 1")
        }
        return ScenarioSettings(limit, obj.optBoolean("calendarEnabled", false))
    }

    private fun parseSeed(file: File, obj: JSONObject?, path: String): ScenarioSeed {
        if (obj == null) return ScenarioSeed(emptyMap(), emptyList(), emptyList())
        rejectUnknown(obj, seedKeys, path, file)
        val profileObj = obj.optJSONObject("profile")
        val profile = if (profileObj == null) {
            emptyMap()
        } else {
            rejectUnknown(profileObj, profileKeys, "$path.profile", file)
            profileObj.keysList().associateWith { key -> profileObj.requiredString(file, key, "$path.profile") }
        }
        val toolsArray = obj.optJSONArray("tools")
        val tools = if (toolsArray == null) {
            emptyList()
        } else {
            (0 until toolsArray.length()).map { index ->
                val tool = toolsArray.requiredObject(index, "$path.tools[$index]")
                rejectUnknown(tool, seedToolKeys, "$path.tools[$index]", file)
                val name = tool.requiredString(file, "name", "$path.tools[$index]")
                val args = tool.opt("arguments")
                val argumentsJson = when {
                    args == null || args == JSONObject.NULL -> "{}"
                    args is JSONObject -> args.toString()
                    args is String -> args
                    else -> throw ScenarioParseException(file, "$path.tools[$index].arguments must be an object")
                }
                SeedToolCall(name, argumentsJson)
            }
        }
        val calendarArray = obj.optJSONArray("system_calendar")
        val systemCalendar = if (calendarArray == null) {
            emptyList()
        } else {
            (0 until calendarArray.length()).map { index ->
                val event = calendarArray.requiredObject(index, "$path.system_calendar[$index]")
                rejectUnknown(event, seedCalendarKeys, "$path.system_calendar[$index]", file)
                SeedCalendarEvent(
                    title = event.requiredString(file, "title", "$path.system_calendar[$index]"),
                    whenPhrase = event.requiredString(file, "when", "$path.system_calendar[$index]"),
                    notes = event.optString("notes").takeIf { event.has("notes") && it.isNotBlank() },
                )
            }
        }
        return ScenarioSeed(profile, tools, systemCalendar)
    }

    private fun parseTurn(file: File, obj: JSONObject, path: String): ScenarioTurn {
        rejectUnknown(obj, turnKeys, path, file)
        val hasUser = obj.has("user") && !obj.isNull("user")
        val hasRun = obj.has("run") && !obj.isNull("run")
        if (hasUser == hasRun) {
            throw ScenarioParseException(file, "$path must have exactly one of user or run")
        }
        return if (hasUser) {
            val text = obj.requiredString(file, "user", path)
            if (text.isBlank()) throw ScenarioParseException(file, "$path.user must not be blank")
            ScenarioTurn.User(text)
        } else {
            val raw = obj.requiredString(file, "run", path).lowercase()
            if (raw !in runKinds) {
                throw ScenarioParseException(file, "$path.run must be one of ${runKinds.joinToString()}")
            }
            ScenarioTurn.Run(AutonomousRun.valueOf(raw.uppercase()))
        }
    }

    private fun parseExpect(file: File, obj: JSONObject, path: String): ScenarioExpect {
        rejectUnknown(obj, expectKeys, path, file)
        val rowsArray = obj.optJSONArray("rows")
        val rows = if (rowsArray == null) {
            emptyList()
        } else {
            (0 until rowsArray.length()).map { index ->
                parseRow(file, rowsArray.requiredObject(index, "$path.rows[$index]"), "$path.rows[$index]")
            }
        }
        val replyObj = obj.optJSONObject("reply")
        val reply = if (replyObj == null) {
            null
        } else {
            rejectUnknown(replyObj, replyKeys, "$path.reply", file)
            val mentions = replyObj.optionalStringList("must_mention_any", "$path.reply.must_mention_any")
            val judge = replyObj.optString("judge").takeIf { replyObj.has("judge") && it.isNotBlank() }
            if (mentions.isEmpty() && judge == null) {
                throw ScenarioParseException(file, "$path.reply needs must_mention_any or judge")
            }
            ReplyExpectation(mentions, judge)
        }
        return ScenarioExpect(
            toolsCalled = obj.optionalStringList("tools_called", "$path.tools_called"),
            toolsNotCalled = obj.optionalStringList("tools_not_called", "$path.tools_not_called"),
            noToolErrors = obj.optBoolean("no_tool_errors", false),
            rows = rows,
            reply = reply,
        )
    }

    private fun parseRow(file: File, obj: JSONObject, path: String): RowExpectation {
        rejectUnknown(obj, rowKeys, path, file)
        val table = obj.requiredString(file, "table", path)
        if (table !in ScenarioTables.ALL) {
            throw ScenarioParseException(
                file,
                "$path.table '$table' is unknown. Allowed: ${ScenarioTables.ALL.sorted().joinToString()}",
            )
        }
        val count = if (obj.has("count")) obj.optInt("count") else null
        if (count != null && count < 0) {
            throw ScenarioParseException(file, "$path.count must be >= 0")
        }
        val whereObj = obj.optJSONObject("where")
        val where = if (whereObj == null) {
            emptyMap()
        } else {
            whereObj.keysList().associateWith { field ->
                parseMatchers(file, whereObj.get(field), "$path.where.$field")
            }
        }
        return RowExpectation(table, count, where)
    }

    private fun parseMatchers(file: File, raw: Any, path: String): List<FieldMatcher> {
        return when (raw) {
            is JSONObject -> {
                rejectUnknown(raw, matcherKeys, path, file)
                if (raw.length() == 0) {
                    throw ScenarioParseException(file, "$path must declare at least one matcher")
                }
                raw.keysList().map { key -> matcherFrom(file, key, raw.get(key), path) }
            }
            JSONObject.NULL -> throw ScenarioParseException(file, "$path must not be null")
            is JSONArray -> throw ScenarioParseException(file, "$path must be a matcher object or a scalar")
            else -> listOf(FieldMatcher.Equals(raw))
        }
    }

    private fun matcherFrom(file: File, key: String, raw: Any, path: String): FieldMatcher {
        val here = "$path.$key"
        return when (key) {
            "equals" -> FieldMatcher.Equals(scalar(file, raw, here))
            "equals_ignore_case" -> FieldMatcher.EqualsIgnoreCase(stringValue(file, raw, here))
            "contains" -> FieldMatcher.Contains(stringValue(file, raw, here))
            "contains_any" -> FieldMatcher.ContainsAny(stringList(file, raw, here))
            "contains_all" -> FieldMatcher.ContainsAll(stringList(file, raw, here))
            "matches" -> FieldMatcher.Matches(stringValue(file, raw, here))
            "one_of" -> FieldMatcher.OneOf(stringList(file, raw, here))
            "not_empty" -> {
                if (raw != true) throw ScenarioParseException(file, "$here must be true")
                FieldMatcher.NotEmpty
            }
            "gt" -> FieldMatcher.GreaterThan(numberValue(file, raw, here))
            "gte" -> FieldMatcher.GreaterOrEqual(numberValue(file, raw, here))
            "lt" -> FieldMatcher.LessThan(numberValue(file, raw, here))
            "lte" -> FieldMatcher.LessOrEqual(numberValue(file, raw, here))
            "date_is" -> FieldMatcher.DateIs(stringValue(file, raw, here))
            "time_is" -> FieldMatcher.TimeIs(stringValue(file, raw, here))
            "within_days" -> FieldMatcher.WithinDays(numberValue(file, raw, here).toInt())
            else -> throw ScenarioParseException(file, "unknown matcher '$key' at $path")
        }
    }

    private fun rejectUnknown(obj: JSONObject, allowed: Set<String>, path: String, file: File? = null) {
        val unknown = obj.keysList().filter { it !in allowed }
        if (unknown.isNotEmpty()) {
            throw ScenarioParseException(file, "$path has unknown key(s): ${unknown.joinToString()}")
        }
    }

    private fun JSONObject.requiredString(file: File, key: String, path: String): String {
        if (!has(key) || isNull(key)) throw ScenarioParseException(file, "$path.$key is required")
        val value = opt(key)
        if (value !is String || value.isBlank()) {
            throw ScenarioParseException(file, "$path.$key must be a non-empty string")
        }
        return value
    }

    private fun JSONObject.optionalStringList(key: String, path: String): List<String> {
        if (!has(key) || isNull(key)) return emptyList()
        return stringList(null, get(key), path)
    }

    private fun JSONArray.requiredObject(index: Int, path: String): JSONObject {
        return optJSONObject(index) ?: throw ScenarioParseException(null, "$path must be an object")
    }

    private fun JSONObject.keysList(): List<String> = keys().asSequence().toList()

    private fun scalar(file: File, raw: Any, path: String): Any {
        if (raw is JSONObject || raw is JSONArray || raw == JSONObject.NULL) {
            throw ScenarioParseException(file, "$path must be a string, number, or boolean")
        }
        return raw
    }

    private fun stringValue(file: File, raw: Any, path: String): String {
        if (raw !is String || raw.isBlank()) {
            throw ScenarioParseException(file, "$path must be a non-empty string")
        }
        return raw
    }

    private fun numberValue(file: File, raw: Any, path: String): Double {
        return when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull() ?: throw ScenarioParseException(file, "$path must be a number")
            else -> throw ScenarioParseException(file, "$path must be a number")
        }
    }

    private fun stringList(file: File?, raw: Any, path: String): List<String> {
        val array = raw as? JSONArray
            ?: throw ScenarioParseException(file, "$path must be an array of strings")
        if (array.length() == 0) {
            throw ScenarioParseException(file, "$path must not be empty")
        }
        return (0 until array.length()).map { index ->
            val value = array.opt(index)
            if (value !is String || value.isBlank()) {
                throw ScenarioParseException(file, "$path[$index] must be a non-empty string")
            }
            value
        }
    }
}

class ScenarioParseException(file: File?, message: String) : IllegalArgumentException(
    if (file == null) message else "${file.name}: $message",
)
