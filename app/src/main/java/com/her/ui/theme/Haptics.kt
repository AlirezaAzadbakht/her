package com.her.ui.theme

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/** Named haptic moments; the platform decides strength and honours the user's haptics setting. */
class HerHaptics(private val view: View) {
    fun confirm() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK,
    )

    fun reject() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS,
    )

    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)

    fun soft() = perform(HapticFeedbackConstants.TEXT_HANDLE_MOVE)

    fun toggle(on: Boolean) = perform(
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
            else -> HapticFeedbackConstants.CONTEXT_CLICK
        },
    )

    private fun perform(constant: Int) {
        view.performHapticFeedback(constant)
    }
}

@Composable
fun rememberHerHaptics(): HerHaptics {
    val view = LocalView.current
    return remember(view) { HerHaptics(view) }
}
