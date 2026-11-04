package com.her

import com.her.data.remote.LlmClient
import com.her.data.secure.LlmSettings
import com.her.domain.LlmMessage
import com.her.domain.ToolSpec
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class ReasoningEffortRetryTest {
    private val ok = """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""
    private val rejected = """{"error":{"message":"Function tools with reasoning_effort are not supported","param":"reasoning_effort"}}"""
    private val tool = ToolSpec("get_current_time", "Time", """{"type":"object","properties":{}}""")

    @Test
    fun completeHidesInlineThinking() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"<think>plan</think>Good morning."}}]}"""))
        server.start()
        try {
            val settings = LlmSettings(server.url("/v1").toString().trimEnd('/'), "sk-test", "model")
            val reply = LlmClient().complete(settings, listOf(LlmMessage(role = "user", content = "hi"))).message.content
            assertEquals("Good morning.", reply)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun retriesWithEffortNoneAndKeepsIt() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(400).setBody(rejected))
        server.enqueue(MockResponse().setBody(ok))
        server.enqueue(MockResponse().setBody(ok))
        server.enqueue(MockResponse().setBody(ok))
        server.start()
        try {
            val llm = LlmClient()
            val settings = LlmSettings(server.url("/v1").toString().trimEnd('/'), "sk-test", "model")
            val messages = listOf(LlmMessage(role = "user", content = "hi"))
            assertEquals("ok", llm.complete(settings, messages, listOf(tool)).message.content)
            assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("reasoning_effort"))
            assertEquals("none", JSONObject(server.takeRequest().body.readUtf8()).getString("reasoning_effort"))
            llm.complete(settings, messages, listOf(tool))
            assertEquals("none", JSONObject(server.takeRequest().body.readUtf8()).getString("reasoning_effort"))
            llm.complete(settings, messages)
            assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("reasoning_effort"))
        } finally {
            server.shutdown()
        }
    }
}
