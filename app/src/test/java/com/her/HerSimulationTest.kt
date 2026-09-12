package com.her

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.her.agent.tools.ToolRegistry
import com.her.core.newId
import com.her.data.calendar.CalendarDataSource
import com.her.data.db.HerDatabase
import com.her.data.remote.WebSearchClient
import com.her.data.repository.HerRepository
import com.her.data.retrieval.HybridRanker
import com.her.data.secure.AppSettingsStore
import com.her.domain.ChatMessage
import com.her.domain.GroceryStatus
import com.her.domain.MessageRole
import com.her.domain.MessageStatus
import com.her.domain.QueueStatus
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class HerSimulationTest {
    private lateinit var db: HerDatabase
    private lateinit var repo: HerRepository
    private lateinit var tools: ToolRegistry
    private val toolLog = mutableListOf<String>()

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, HerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val settings = AppSettingsStore(context)
        repo = HerRepository(db, settings)
        tools = ToolRegistry(
            repo = repo,
            ranker = HybridRanker(repo),
            settings = settings,
            calendar = CalendarDataSource(context),
            webSearch = WebSearchClient(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun simulateFirstDays() = runBlocking {
        assistant("Hi. Before we really start, what should I call you?")
        queue("Learn what they want to call me, then gather timezone and what matters right now — slowly.")

        user("Call me Alireza.")
        call("update_user_profile", """{"userName":"Alireza","timezone":"Asia/Tehran"}""")
        assistant("Alireza. And what would you like to call me?")

        user("Her is fine.")
        call("update_user_profile", """{"assistantName":"Her"}""")
        call("complete_agent_queue_item", """{"id":"${openQueueId()}"}""")
        assistant("Alright. I'll stay Her. Whenever something's on your mind — a project, a date, something you need from the store — just say it. I'll keep track.")

        user("We're out of coffee. Also rice.")
        call("add_grocery", """{"name":"coffee","reason":"out"}""")
        call("add_grocery", """{"name":"rice","reason":"out"}""")
        assistant("I'll put coffee and rice on the list.")

        user("My brother Amir's birthday is June 4.")
        call("update_person", """{"name":"Amir","relationship":"brother","birthday":"June 4"}""")
        assistant("I've got Amir's birthday as June 4. I'll keep that.")

        user("I really want the first usable version of Her finished before December. For now I think the memory search should combine semantic similarity and importance.")
        call("update_project", """{"name":"Her Android App","description":"Persistent personal assistant","importance":0.95}""")
        call("create_goal", """{"title":"Finish first usable version of Her","targetDate":"2026-12-01","priority":0.9}""")
        call("remember", """{"content":"Memory search should combine semantic similarity and importance.","scope":"long_term","type":"project","confidence":0.95,"importance":0.7,"source":"USER_EXPLICIT"}""")
        assistant("That's a good north star — first usable version before December. I tied the search idea to the Her project. Semantic plus importance feels right; confidence and recency can sit underneath so old low-stakes facts don't crowd you.")

        user("I'll finish the memory retrieval work tomorrow before touching the UI. Remind me I have a dentist appointment tomorrow at two-thirty.")
        call("create_commitment", """{"title":"Finish memory retrieval before UI","dueAt":"tomorrow"}""")
        call("create_calendar_event", """{"title":"Dentist","when":"tomorrow","notes":"2:30"}""")
        assistant("I'll hold you to the retrieval work tomorrow, and I've got the dentist at two-thirty.")

        user("Today has been horrible. I'm exhausted.")
        call("remember", """{"content":"User is exhausted and had a difficult day today.","scope":"short_term","type":"emotional","confidence":0.9,"importance":0.6,"source":"USER_EXPLICIT"}""")
        assistant("I'm sorry it was that kind of day. Nothing else has to get solved tonight. The dentist and the retrieval work can wait until morning.")

        user("I bought everything except eggs. We still need eggs.")
        call("add_grocery", """{"name":"eggs","reason":"still need"}""")
        call("clear_purchased_groceries", """{"except":["eggs"]}""")
        assistant("Coffee and rice are off the list. Eggs stay.")

        assistant(
            "Good morning. Today's pretty light. You've got your dentist appointment at two-thirty, and yesterday you said you wanted to finish the memory retrieval work before touching the UI, so I'd probably make that the main thing this morning. You're also out of eggs, and Amir's birthday is June 4 if you want to think ahead.",
        )
        repo.logActivity("hourly", "No notification sent.")
        repo.logActivity("briefing", "Morning message written into the conversation.")

        val messages = repo.recentMessages(40)
        val groceries = repo.groceries()
        val people = repo.people()
        val goals = repo.goals()
        val commitments = repo.commitments()
        val profile = repo.getProfile()

        assertTrue(profile.userName == "Alireza")
        assertTrue(people.any { it.name == "Amir" })
        assertTrue(goals.any { it.title.contains("Her") })
        assertTrue(commitments.any { it.title.contains("memory retrieval") })
        assertTrue(groceries.any { it.name == "eggs" && it.status == GroceryStatus.ACTIVE })
        assertTrue(groceries.filter { it.status == GroceryStatus.ACTIVE }.none { it.name == "coffee" })
        assertTrue(messages.count { it.role == MessageRole.ASSISTANT } >= 4)

        val html = renderPhone(messages, groceries, people, goals, commitments, profile.userName)
        val out = File("/home/alireza/git-projects/her/app/build/reports/her-simulation.html").apply { parentFile.mkdirs() }
        out.writeText(html)
        File("/home/alireza/git-projects/her/app/build/reports/her-simulation.txt").writeText(transcript(messages))
        println(transcript(messages))
        println("Simulation written to ${out.absolutePath}")
        println("TOOLS:\n${toolLog.joinToString("\n")}")
    }

    private suspend fun user(text: String) {
        val now = System.currentTimeMillis()
        repo.saveMessage(
            ChatMessage(newId(), MessageRole.USER, text, now, now, repo.deviceId, 1, null, MessageStatus.SENT, null),
        )
    }

    private suspend fun assistant(text: String) {
        val now = System.currentTimeMillis()
        repo.saveMessage(
            ChatMessage(newId(), MessageRole.ASSISTANT, text, now, now, repo.deviceId, 1, null, MessageStatus.SENT, null),
        )
    }

    private suspend fun call(name: String, args: String) {
        val result = tools.execute(name, args)
        toolLog += "$name $args -> ${result.payloadJson}"
        assertTrue("$name failed: ${result.payloadJson}", result.ok || JSONObject(result.payloadJson).optBoolean("ok", false))
    }

    private suspend fun queue(description: String) {
        call("add_agent_queue_item", JSONObject().put("description", description).put("priority", 0.8).toString())
    }

    private suspend fun openQueueId(): String =
        repo.agentQueue().first { it.status == QueueStatus.OPEN }.id

    private fun transcript(messages: List<ChatMessage>): String = buildString {
        appendLine("HER SIMULATION")
        appendLine()
        messages.forEach { msg ->
            appendLine(if (msg.role == MessageRole.USER) "YOU" else "HER")
            appendLine(msg.content)
            appendLine()
        }
    }

    private fun renderPhone(
        messages: List<ChatMessage>,
        groceries: List<com.her.domain.GroceryItem>,
        people: List<com.her.domain.Person>,
        goals: List<com.her.domain.Goal>,
        commitments: List<com.her.domain.Commitment>,
        userName: String?,
    ): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val bubbles = messages.joinToString("\n") { msg ->
            val who = if (msg.role == MessageRole.USER) "you" else "her"
            val cls = if (msg.role == MessageRole.USER) "user" else "her"
            """<div class="msg $cls"><p>${esc(msg.content)}</p><span>$who</span></div>"""
        }
        val memory = buildString {
            fun sec(title: String, rows: List<String>) {
                if (rows.isEmpty()) return
                append("<h3>$title</h3>")
                rows.forEach { append("<p>${esc(it)}</p>") }
            }
            sec("Groceries", groceries.filter { it.status == GroceryStatus.ACTIVE }.map { it.name })
            sec("People", people.map { "${it.name} · ${it.relationship ?: ""} ${it.birthday ?: ""}" })
            sec("Goals", goals.map { it.title })
            sec("Commitments", commitments.map { it.title })
        }
        return """
<!doctype html>
<html><head><meta charset="utf-8"><title>Her simulation</title>
<style>
  :root { color-scheme: dark; }
  body { margin:0; background:#0d0b0a; color:#f4ede4; font-family: Georgia, serif; }
  .wrap { display:flex; gap:40px; justify-content:center; padding:40px 24px 80px; flex-wrap:wrap; }
  .phone { width:380px; background:#1a1410; border-radius:28px; padding:22px 22px 0; min-height:720px;
           display:flex; flex-direction:column; border:1px solid #3a2d24; }
  h1 { font-family: system-ui; font-size:12px; letter-spacing:2px; font-weight:500; color:#e8a87c; margin:0 0 18px; }
  .thread { flex:1; display:flex; flex-direction:column; gap:22px; overflow:auto; padding-bottom:16px; }
  .msg p { margin:0; font-size:17px; line-height:1.5; }
  .msg.user p { color:#b9a99a; }
  .msg span { display:block; margin-top:6px; font-family:system-ui; font-size:10px; letter-spacing:1px; color:#6d5c50; }
  nav { display:flex; justify-content:space-between; font-family:system-ui; font-size:13px; letter-spacing:1.2px;
        color:#b9a99a; padding:16px 4px 20px; }
  nav .on { color:#e8a87c; }
  .composer { border-top:1px solid #3a2d24; color:#6d5c50; font-size:16px; padding:14px 0 10px; }
  .memory p { font-size:15px; line-height:1.45; margin:0 0 8px; }
  h3 { font-family:system-ui; font-size:11px; letter-spacing:1.4px; color:#b9a99a; font-weight:500; margin:18px 0 8px; }
  .note { max-width:420px; color:#b9a99a; font-family:system-ui; font-size:14px; line-height:1.5; }
</style></head>
<body>
<div class="wrap">
  <div class="phone">
    <h1>HER</h1>
    <div class="thread">$bubbles</div>
    <div class="composer">Write something</div>
    <nav><span class="on">Her</span><span>Memory</span><span>Settings</span></nav>
  </div>
  <div class="phone">
    <h1>MEMORY</h1>
    <div class="thread">$memory</div>
    <nav><span>Her</span><span class="on">Memory</span><span>Settings</span></nav>
  </div>
  <div class="note">
    <p>Simulated first-day conversation for ${esc(userName ?: "you")}.</p>
    <p>Messages and structured records were written through the real Room repositories and tool registry. The model replies were scripted because no live API key is configured in this environment.</p>
  </div>
</div>
</body></html>
        """.trimIndent()
    }
}
