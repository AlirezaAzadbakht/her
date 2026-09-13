package com.her

import android.app.Application
import com.her.data.remote.StreamAccumulator
import com.her.data.remote.parseUsage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class UsageParsingTest {
    @Test
    fun streamReadsCachedPromptTokens() {
        val acc = StreamAccumulator()
        acc.accept("""{"usage":{"prompt_tokens":900,"completion_tokens":40,"prompt_tokens_details":{"cached_tokens":768}}}""")
        val usage = acc.toResponse(5).usage
        assertEquals(900, usage.inputTokens)
        assertEquals(40, usage.outputTokens)
        assertEquals(768, usage.cachedInputTokens)
        assertEquals(5, usage.latencyMs)
    }

    @Test
    fun missingDetailsMeansNoCachedTokens() {
        val usage = parseUsage(JSONObject("""{"prompt_tokens":10,"completion_tokens":2}"""), 0)
        assertEquals(10, usage.inputTokens)
        assertEquals(0, usage.cachedInputTokens)
    }
}
