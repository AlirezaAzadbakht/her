package com.her

import android.app.Application
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.her.data.secure.LlmSettings
import com.her.ui.onboarding.qrTexts
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

    @Test
    fun findsSettingsBesideTheStoreLinkOnASavedCard() {
        // Like Her Cloud's gift card: a big settings code and a small store-link code on a purple card.
        val settings = """{"base_url":"https://api.her-project.ir/v1","model_name":"her-model","api_key":"sk-her-abc"}"""
        val width = 1080
        val height = 1440
        val pixels = IntArray(width * height) { 0xFF3B2B5A.toInt() }
        fun draw(text: String, left: Int, top: Int, size: Int) {
            val code = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
            for (y in 0 until size) for (x in 0 until size) {
                pixels[(top + y) * width + left + x] = if (code[x, y]) 0xFF000000.toInt() else -1
            }
        }
        draw(settings, left = 180, top = 360, size = 720)
        draw("https://cafebazaar.ir/app/com.her", left = 120, top = 1140, size = 230)

        val texts = qrTexts(pixels, width, height)

        assertEquals(2, texts.size)
        assertEquals(
            LlmSettings("https://api.her-project.ir/v1", "sk-her-abc", "her-model"),
            texts.firstNotNullOfOrNull(LlmSettings::fromJson),
        )
        assertEquals(emptyList<String>(), qrTexts(IntArray(100 * 100) { -1 }, 100, 100))
    }
}
