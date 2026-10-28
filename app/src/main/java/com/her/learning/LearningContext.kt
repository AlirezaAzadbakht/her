package com.her.learning

import com.her.data.repository.HerRepository
import java.time.LocalTime

suspend fun learningContext(repo: HerRepository): List<String> {
    val rows = repo.learningRecords()
    val now = repo.now()
    return rows.filter { it.kind == "PATH" && it.data().optString("status") != "ARCHIVED" }
        .sortedByDescending { it.updatedAt }.take(12).map { path ->
            val data = path.data()
            val children = rows.filter { it.pathId == path.id }
            val lessons = children.filter { it.kind == "LESSON" }.sortedBy { it.data().optInt("order") }
            val completed = lessons.count { it.data().optString("status") == "COMPLETED" }
            val reviews = lessons.filter { lesson ->
                val attempts = children.filter { it.kind == "ATTEMPT" && it.data().optString("lessonId") == lesson.id }
                LearningProgress.review(attempts).dueAt?.let { it <= repo.clock.nowMillis() } == true
            }
            val latestExercise = children.filter { it.kind == "EXERCISE" }.maxWithOrNull(compareBy({ it.createdAt }, { it.id }))
            val latestAnswer = children.filter { it.kind == "ATTEMPT" && it.data().optString("exerciseId") == latestExercise?.id }
                .maxWithOrNull(compareBy({ it.createdAt }, { it.id }))
            val nudgeDue = data.optString("status") == "ACTIVE" && data.optBoolean("remindersEnabled") &&
                children.none { it.id == "study-nudge:${path.id}:${now.toLocalDate()}" } &&
                runCatching { now.toLocalTime() >= LocalTime.parse(data.getString("reminderTime")) }.getOrDefault(false)
            buildString {
                append("${path.id} v${path.version} | ${data.optString("title").take(120)} [${data.optString("status")}] ")
                append("goal=${data.optString("goal").take(240)} level=${data.optString("level").take(120)} language=${data.optString("language").take(80)} ")
                append("daily=${data.optInt("minutesPerDay")}min; covered=$completed/${lessons.size}; ")
                append("reviews due=${reviews.joinToString { it.id }}; study nudge eligible=$nudgeDue. ")
                append("Roadmap: ${lessons.take(20).joinToString { "${it.id}: ${it.data().optString("title").take(120)} [${it.data().optString("status")}]" }}. ")
                if (data.optString("status") == "ACTIVE") latestExercise?.let {
                    append("Latest exercise ${it.id}: ${it.data().optString("prompt").take(1800)}; ")
                    append(if (latestAnswer == null) "awaiting answer." else "last result=${latestAnswer.data().optString("result")}; feedback=${latestAnswer.data().optString("feedback").take(600)}")
                }
                append(" Use get_learning(pathId) for full content/history and versions; do not guess omitted records.")
            }
        }
}

object LearningInstructions {
    val SYSTEM = """

Learning together, entirely in this conversation:
- Teach any requested subject at an appropriate level. A quick factual question needs an answer, not automatic course creation. For sustained learning, clarify only missing goal, experience, language or daily time that materially changes the plan. Offer a small placement exercise if helpful; let them skip it.
- Propose a concise roadmap with outcomes in chat. After they accept, save_learning_path and save_learning_lesson for its stages. Keep the initial roadmap within the tool-call budget (usually 3-6 stages); add smaller lessons as needed. Never claim a roadmap is saved before the tools succeed. Do not create a separate screen or ask the user to manage tool IDs.
- Read get_learning before resuming, editing, grading, switching subjects or reporting progress. Multiple paths can coexist. If 'continue' is ambiguous, ask which subject. Use stable IDs for retries and expectedVersion for edits. Adjust lesson content, order, pace and goal conversationally, preserving previous attempts. Pausing or archiving a path disables its study nudges.
- Teach in short sessions: explain one concept, show an example, then ask one exercise and wait. Use multiple choice, recall, translation, worked problems or practical assignments as appropriate. Generate detailed content on demand and save it in the lesson. Match their language, including Persian when requested. Use readable Markdown for roadmaps/code when useful.
- Save the exercise with set_learning_exercise BEFORE asking it. EXACT is for unambiguous accepted answers, OPEN for reasoning and practical work with an explicit rubric. Do not claim to execute code or assess spoken pronunciation. Do not reveal the answer before they try unless they ask.
- Only record a real answer to that exercise, using its user message ID from get_learning. For EXACT use the app's returned result in feedback. For OPEN compare against the saved rubric; explain mistakes kindly, and mark UNCERTAIN when you cannot reliably evaluate. Hints, requests for explanation, and unrelated chat are not answers. Never invent attempts or award progress merely for sending a lesson.
- After an incorrect/partial answer, explain or hint and allow another attempt; simplify the next exercise if needed. After success, increase difficulty gradually. Keep completion separate from demonstrated understanding. Spaced review dates are calculated by the app; offer due reviews during study. Use fresh exercises to review the same lesson. 'PRACTICED' is evidence of repeated success, not proof of comprehensive mastery.
- The interface displays only your latest message. Include the relevant exercise again with a hint or explanation so the user can still answer. Unrelated conversations must not erase or advance a lesson. On returning, briefly restore context and continue the pending exercise. Show progress or the roadmap when asked, without requiring navigation.
- Study reminders are optional: enable only after the user requests/agrees, and agree a local study time. Use save_learning_path remindersEnabled/reminderTime. Routine study nudges use send_learning_reminder ONLY, never send_user_message or independent repeating reminders. They run approximately in background passes, respect quiet hours, and are limited to one per path per day. Never guilt the learner for missing a day. Disable reminders or pause when asked.
- On autonomous passes, do not generate lessons, quizzes, answers or progress. If a study nudge is eligible, send_learning_reminder may offer a brief invitation to continue; otherwise stay silent about learning. Never treat review due dates as permission to notify someone who has not opted in.
- For changing factual material, use web_search when enabled and explain uncertainty when sources are unavailable. Do not invent citations or guarantee correctness of generated teaching material.
""".trimIndent()
}
