package com.her

import android.app.Application
import com.her.data.remote.StreamAccumulator
import com.her.data.remote.sseData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class StreamAccumulatorTest {
    @Test
    fun accumulatesContentDeltas() {
        val acc = StreamAccumulator()
        val a = acc.accept("""{"choices":[{"delta":{"role":"assistant","content":"Hello"}}]}""")
        val b = acc.accept("""{"choices":[{"delta":{"content":" there"}}]}""")
        val done = acc.accept("[DONE]")
        assertEquals("Hello", a.contentDelta)
        assertEquals(" there", b.contentDelta)
        assertTrue(a.continueStreaming)
        assertFalse(done.continueStreaming)
        val response = acc.toResponse(12)
        assertEquals("Hello there", response.message.content)
        assertEquals("assistant", response.message.role)
        assertEquals(12, response.usage.latencyMs)
    }

    @Test
    fun concatenatesSplitToolArguments() {
        val acc = StreamAccumulator()
        acc.accept(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"remember","arguments":"{\"con"}}]}}]}""",
        )
        acc.accept(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"tent\":\"hi\"}"}}]}}]}""",
        )
        val response = acc.toResponse(1)
        val call = response.message.toolCalls!!.single()
        assertEquals("call_1", call.id)
        assertEquals("remember", call.name)
        assertEquals("""{"content":"hi"}""", call.arguments)
    }

    @Test
    fun readsUsageAndIgnoresComments() {
        val acc = StreamAccumulator()
        assertNull(sseData(": keep-alive"))
        assertEquals("{}", sseData("data: {}"))
        acc.accept("""{"usage":{"prompt_tokens":9,"completion_tokens":4}}""")
        val response = acc.toResponse(3)
        assertEquals(9, response.usage.inputTokens)
        assertEquals(4, response.usage.outputTokens)
    }
}
