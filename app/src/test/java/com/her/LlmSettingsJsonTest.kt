package com.her

import android.app.Application
import com.her.data.secure.LlmSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class LlmSettingsJsonTest {
    @Test
    fun readsHerCloudSettings() {
        // Exactly what the Her Cloud setup page copies and puts in its QR code.
        val copied = """
            {
              "base_url": "https://api.her-project.ir/v1",
              "model_name": "her-model",
              "api_key": "sk-her-abc"
            }
        """
        assertEquals(
            LlmSettings("https://api.her-project.ir/v1", "sk-her-abc", "her-model"),
            LlmSettings.fromJson(copied),
        )
    }

    @Test
    fun rejectsAnythingIncomplete() {
        assertNull(LlmSettings.fromJson("""{"base_url":"https://x/v1","model_name":"m"}"""))
        assertNull(LlmSettings.fromJson("""{"base_url":"https://x/v1","model_name":"m","api_key":" "}"""))
        assertNull(LlmSettings.fromJson("sk-her-abc"))
        assertNull(LlmSettings.fromJson(""))
    }
}
