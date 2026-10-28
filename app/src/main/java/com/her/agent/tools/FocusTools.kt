package com.her.agent.tools

import com.her.core.ToolValidationException
import com.her.core.formatNaturalDate
import com.her.core.jsonObjectOf
import com.her.core.optStringOrNull
import java.time.Instant
import org.json.JSONObject

internal fun ToolRegistry.registerFocusTools() {
    register(
        "start_focus",
        "Start a pomodoro focus timer: work, a short break, and again, for a number of rounds. It rings each change on its own with a countdown in the notification. Replaces a session that is already running.",
        objSchema(
            "task" to str("Task id or title they are focusing on, if one matches"),
            "label" to str("What they are focusing on, when it is not a task"),
            "minutes" to num("Minutes of work per round, 1–120 (default 25)"),
            "breakMinutes" to num("Minutes of break between rounds, 1–60 (default 5)"),
            "rounds" to num("Work rounds, 1–8 (default 4)"),
            "breakMessage" to str("What to say when a break starts, in their language"),
            "backMessage" to str("What to say when a break ends, in their language"),
            "doneMessage" to str("What to say when the last round ends, in their language"),
        ),
    ) { args ->
        val timer = focus ?: throw ToolValidationException("Focus sessions are not available here")
        val task = args.optStringOrNull("task")?.let { requireTask(it) }
        val label = args.optStringOrNull("label") ?: task?.title ?: "focus"
        val minutes = args.whole("minutes", 25, 1..120)
        val breakMinutes = args.whole("breakMinutes", 5, 1..60)
        val rounds = args.whole("rounds", 4, 1..8)
        val session = timer.start(
            label = label,
            taskId = task?.id,
            workMin = minutes,
            breakMin = breakMinutes,
            rounds = rounds,
            breakMessage = args.optStringOrNull("breakMessage"),
            backMessage = args.optStringOrNull("backMessage"),
            doneMessage = args.optStringOrNull("doneMessage"),
        )
        jsonObjectOf(
            "ok" to true,
            "label" to label,
            "rounds" to rounds,
            "minutes" to minutes,
            "breakMinutes" to breakMinutes,
            "firstBreakAt" to formatNaturalDate(Instant.ofEpochMilli(session.phaseEndsAt).atZone(nowZoned().zone)),
            // Without exact alarms Android may ring a phase change a few minutes late.
            "timing" to if (timer.exactAllowed()) "exact" else "approximate",
        )
    }

    register(
        "stop_focus",
        "Stop the running focus session. Finished rounds are still remembered.",
        objSchema(),
    ) {
        val timer = focus ?: throw ToolValidationException("Focus sessions are not available here")
        val stopped = timer.stop() ?: throw ToolValidationException("No focus session is running")
        jsonObjectOf("ok" to true, "label" to stopped.label, "roundsDone" to stopped.completedRounds)
    }
}

private fun JSONObject.whole(key: String, default: Int, range: IntRange): Int {
    if (!has(key) || isNull(key)) return default
    val value = optDouble(key).takeIf { !it.isNaN() }?.toInt()
        ?: throw ToolValidationException("$key must be a number")
    if (value !in range) throw ToolValidationException("$key must be between ${range.first} and ${range.last}")
    return value
}
