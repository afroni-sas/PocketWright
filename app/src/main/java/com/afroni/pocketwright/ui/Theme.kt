package com.afroni.pocketwright.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Running = Color(0xFF16A34A)
val Warning = Color(0xFFD97706)
val Failure = Color(0xFFDC2626)

private val Light = lightColorScheme(
    primary = Color(0xFF0E7C66),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFF3E8),
    onPrimaryContainer = Color(0xFF00382C),
    secondary = Color(0xFF4B635B),
    secondaryContainer = Color(0xFFE2EBE7),
    onSecondaryContainer = Color(0xFF1C2B26),
    background = Color(0xFFF5F6F8),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFE6E9EC),
    onSurfaceVariant = Color(0xFF596168),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F9FA),
    surfaceContainer = Color(0xFFF0F2F4),
    surfaceContainerHigh = Color(0xFFEAEDF0),
    surfaceContainerHighest = Color(0xFFE3E6EA),
    outline = Color(0xFF79828B),
    outlineVariant = Color(0xFFDCE0E5),
    error = Failure,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF5FDDBD),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF0B4F41),
    onPrimaryContainer = Color(0xFFBFF3E3),
    secondary = Color(0xFFB2CCC3),
    secondaryContainer = Color(0xFF2C3A35),
    onSecondaryContainer = Color(0xFFD7E5DF),
    background = Color(0xFF0E1012),
    onBackground = Color(0xFFE2E4E6),
    surface = Color(0xFF16191C),
    onSurface = Color(0xFFE2E4E6),
    surfaceVariant = Color(0xFF2B3136),
    onSurfaceVariant = Color(0xFFA9B1B8),
    surfaceContainerLowest = Color(0xFF0C0F11),
    surfaceContainerLow = Color(0xFF181C1F),
    surfaceContainer = Color(0xFF1E2327),
    surfaceContainerHigh = Color(0xFF262C31),
    surfaceContainerHighest = Color(0xFF30373C),
    outline = Color(0xFF8A939B),
    outlineVariant = Color(0xFF343B41),
    error = Color(0xFFF87171),
)

private val PwShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun PocketWrightTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, shapes = PwShapes, content = content)
}
