package io.frame.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Amber = Color(0xFFF5C842)
val RecRed = Color(0xFFE5484D)
val SafeGreen = Color(0xFF3DD68C)
val Warn = Color(0xFFFFB224)

private val Colors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1A1400),
    secondary = Color(0xFF8EC5FF),
    error = RecRed,
    background = Color(0xFF0B0C0E),
    onBackground = Color(0xFFECEDEE),
    surface = Color(0xFF141518),
    onSurface = Color(0xFFECEDEE),
    surfaceVariant = Color(0xFF202226),
    onSurfaceVariant = Color(0xFFB0B4BA),
    outline = Color(0xFF3A3D42),
)

/** Dark only: a recorder is often used in a car at night. */
@Composable
fun FrameTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
