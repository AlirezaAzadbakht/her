package com.her.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.fonts.Font as PlatformFont
import android.graphics.fonts.FontFamily as PlatformFontFamily
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.her.R
import com.her.ui.orbs.ProvideOrbClock

private val Night = Color(0xFF140E0B)
private val NightSurface = Color(0xFF1E1611)
private val NightSurfaceHigh = Color(0xFF2A1F18)
private val NightInk = Color(0xFFF4EDE4)
private val NightMute = Color(0xFFB9A99A)
private val NightGlow = Color(0xFFF6A77A)

private val Dawn = Color(0xFFFBF3EA)
private val DawnSurface = Color(0xFFFFF9F3)
private val DawnSurfaceHigh = Color(0xFFF2E6D8)
private val DawnInk = Color(0xFF2A1E17)
private val DawnMute = Color(0xFF7A6A5D)
private val Terracotta = Color(0xFFC0643A)

/** The warm-glow extras Material's scheme has no slot for. */
@Immutable
data class HerColors(
    val glowCoral: Color,
    val glowAmber: Color,
    val glowRose: Color,
    val halo: Color,
    val glassFill: Color,
    val glassStroke: Color,
    val grainAlpha: Float,
    val ambientAlpha: Float,
    val orbInkFar: Color,
    val orbInkNear: Color,
) {
    val glow: List<Color> get() = listOf(glowCoral, glowAmber, glowRose)
}

private val NightHer = HerColors(
    glowCoral = Color(0xFFFF8A65),
    glowAmber = Color(0xFFF6B26B),
    glowRose = Color(0xFFE57A8A),
    halo = Color(0xFFFF9E6E),
    glassFill = Color(0x12FFE6D0),
    glassStroke = Color(0x26FFD2AE),
    grainAlpha = 0.05f,
    ambientAlpha = 0.10f,
    orbInkFar = Color(0xFF5A3F31),
    orbInkNear = Color(0xFFFFE9D2),
)

private val DawnHer = HerColors(
    glowCoral = Color(0xFFE2724A),
    glowAmber = Color(0xFFD48A3A),
    glowRose = Color(0xFFC45A70),
    halo = Color(0xFFF0A07A),
    glassFill = Color(0x8CFFFFFF),
    glassStroke = Color(0x248A4B28),
    grainAlpha = 0.035f,
    ambientAlpha = 0.12f,
    orbInkFar = Color(0xFFE3CDB9),
    orbInkNear = Color(0xFF6E3219),
)

val LocalHerColors = staticCompositionLocalOf { NightHer }

object Her {
    val colors: HerColors
        @Composable
        @ReadOnlyComposable
        get() = LocalHerColors.current
}

val HerFontFamily = FontFamily(
    Font(R.font.vazirmatn, FontWeight.Light),
    Font(R.font.vazirmatn, FontWeight.Normal),
    Font(R.font.vazirmatn, FontWeight.Medium),
    Font(R.font.vazirmatn, FontWeight.SemiBold),
    Font(R.font.vazirmatn, FontWeight.Bold),
)

/** SuperChiby for Latin in her replies; Vazirmatn fills Arabic and Persian glyphs. */
fun herResponseFontFamily(resources: Resources): FontFamily {
    val latin = PlatformFontFamily.Builder(
        PlatformFont.Builder(resources, R.font.superchiby).build(),
    ).build()
    val arabic = PlatformFontFamily.Builder(
        PlatformFont.Builder(resources, R.font.vazirmatn).build(),
    ).build()
    return FontFamily(
        Typeface.CustomFallbackBuilder(latin)
            .addCustomFallback(arabic)
            .setSystemFallback("sans-serif")
            .build(),
    )
}

private val DarkColors = darkColorScheme(
    primary = NightGlow,
    onPrimary = Night,
    primaryContainer = Color(0xFF3A2519),
    onPrimaryContainer = Color(0xFFFFDCC5),
    secondary = NightMute,
    onSecondary = Night,
    secondaryContainer = NightSurfaceHigh,
    onSecondaryContainer = NightInk,
    tertiary = Color(0xFFE57A8A),
    onTertiary = Night,
    background = Night,
    onBackground = NightInk,
    surface = NightSurface,
    onSurface = NightInk,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = NightMute,
    surfaceContainerLowest = Color(0xFF100B08),
    surfaceContainerLow = Color(0xFF1A130F),
    surfaceContainer = NightSurface,
    surfaceContainerHigh = Color(0xFF261C16),
    surfaceContainerHighest = Color(0xFF30241C),
    outline = Color(0x40F6B26B),
    outlineVariant = Color(0x1FF6B26B),
    error = Color(0xFFF2877A),
    onError = Night,
    scrim = Color(0xCC000000),
)

private val LightColors = lightColorScheme(
    primary = Terracotta,
    onPrimary = DawnSurface,
    primaryContainer = Color(0xFFF7DCCB),
    onPrimaryContainer = Color(0xFF4A2210),
    secondary = DawnMute,
    onSecondary = DawnSurface,
    secondaryContainer = DawnSurfaceHigh,
    onSecondaryContainer = DawnInk,
    tertiary = Color(0xFFB0506A),
    onTertiary = DawnSurface,
    background = Dawn,
    onBackground = DawnInk,
    surface = DawnSurface,
    onSurface = DawnInk,
    surfaceVariant = DawnSurfaceHigh,
    onSurfaceVariant = DawnMute,
    surfaceContainerLowest = Color(0xFFFFFCF8),
    surfaceContainerLow = Color(0xFFFCF5EE),
    surfaceContainer = Color(0xFFF7EDE3),
    surfaceContainerHigh = DawnSurfaceHigh,
    surfaceContainerHighest = Color(0xFFECDFD0),
    outline = Color(0x40C0643A),
    outlineVariant = Color(0x1FC0643A),
    error = Color(0xFFB4412F),
    onError = Color.White,
    scrim = Color(0x99000000),
)

val ConversationStyle = TextStyle(
    fontFamily = HerFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 19.sp,
    lineHeight = 30.sp,
)

private val HerTypography = Typography().let { base ->
    fun TextStyle.her() = copy(fontFamily = HerFontFamily)
    base.copy(
        displayLarge = base.displayLarge.her(),
        displayMedium = base.displayMedium.her(),
        displaySmall = base.displaySmall.her(),
        headlineLarge = base.headlineLarge.her(),
        headlineMedium = base.headlineMedium.her(),
        headlineSmall = base.headlineSmall.her(),
        titleLarge = base.titleLarge.her(),
        titleMedium = base.titleMedium.her(),
        titleSmall = base.titleSmall.her(),
        bodyLarge = base.bodyLarge.her(),
        bodyMedium = base.bodyMedium.her(),
        bodySmall = base.bodySmall.her(),
        labelLarge = base.labelLarge.her(),
        labelMedium = base.labelMedium.her(),
        labelSmall = base.labelSmall.her(),
    )
}

@Composable
fun HerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val reducedMotion = rememberReducedMotion()
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(
        LocalHerColors provides if (dark) NightHer else DawnHer,
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = HerTypography,
        ) {
            ProvideOrbClock(content)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
