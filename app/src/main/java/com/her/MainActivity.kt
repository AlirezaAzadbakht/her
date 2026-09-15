package com.her

import android.content.Intent
import android.os.Bundle
import android.view.animation.PathInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.her.ui.HerApp
import com.her.ui.HerViewModel
import com.her.ui.theme.HerTheme

class MainActivity : ComponentActivity() {
    private val vm: HerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Hold the splash until the profile row is known, so the first frame is the right stage.
        splash.setKeepOnScreenCondition { vm.profile.value == null }
        splash.setOnExitAnimationListener { provider ->
            // Hand off to the loading orb: the icon swells away while the ground dissolves.
            provider.iconView.animate()
                .scaleX(1.2f)
                .scaleY(1.2f)
                .alpha(0f)
                .setDuration(SPLASH_EXIT_MS)
                .setInterpolator(EmphasizedAccelerate)
                .start()
            provider.view.animate()
                .alpha(0f)
                .setDuration(SPLASH_EXIT_MS)
                .setInterpolator(EmphasizedDecelerate)
                .withEndAction { provider.remove() }
                .start()
        }
        enableEdgeToEdge()
        handleShare(intent)
        setContent {
            HerTheme {
                HerApp(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isNotBlank()) vm.consumeShare(text)
    }

    private companion object {
        const val SPLASH_EXIT_MS = 320L
        val EmphasizedAccelerate = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
        val EmphasizedDecelerate = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    }
}
