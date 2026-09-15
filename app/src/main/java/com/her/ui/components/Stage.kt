package com.her.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** The app-wide shared transition, so her presence can travel between stages. */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The visibility scope of the current stage (loading, names, setup, main). */
val LocalStageVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

private const val PresenceKey = "her-presence"

@OptIn(ExperimentalSharedTransitionApi::class)
private val PresenceBounds = BoundsTransform { _, _ ->
    spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessLow)
}

/** Marks her presence so it glides from one stage's slot to the next instead of cutting. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedPresence(): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalStageVisibilityScope.current ?: return this
    return with(shared) {
        this@sharedPresence.sharedBounds(
            sharedContentState = rememberSharedContentState(PresenceKey),
            animatedVisibilityScope = visibility,
            boundsTransform = PresenceBounds,
        )
    }
}
