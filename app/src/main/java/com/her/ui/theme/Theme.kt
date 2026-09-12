package com.her.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val WarmNight = Color(0xFF1A1410)
val WarmSurface = Color(0xFF241C16)
val WarmInk = Color(0xFFF4EDE4)
val WarmMute = Color(0xFFB9A99A)
val WarmAccent = Color(0xFFE8A87C)
val WarmUser = Color(0xFFD8C4B0)
val WarmLine = Color(0x33E8A87C)

private val DarkColors = darkColorScheme(
    primary = WarmAccent,
    onPrimary = WarmNight,
    background = WarmNight,
    onBackground = WarmInk,
    surface = WarmSurface,
    onSurface = WarmInk,
    surfaceVariant = Color(0xFF2C231C),
    onSurfaceVariant = WarmMute,
    outline = WarmLine,
    secondary = WarmMute,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF8A4B28),
    onPrimary = Color(0xFFFFF8F1),
    background = Color(0xFFF7F0E8),
    onBackground = Color(0xFF2A211B),
    surface = Color(0xFFFFF8F1),
    onSurface = Color(0xFF2A211B),
    surfaceVariant = Color(0xFFEFE4D6),
    onSurfaceVariant = Color(0xFF6B5B4E),
    outline = Color(0x338A4B28),
    secondary = Color(0xFF6B5B4E),
)

val ConversationStyle = TextStyle(
    fontFamily = FontFamily.Serif,
    fontWeight = FontWeight.Normal,
    fontSize = 19.sp,
    lineHeight = 28.sp,
)

@Composable
fun HerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
