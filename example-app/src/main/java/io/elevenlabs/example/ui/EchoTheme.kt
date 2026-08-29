package io.elevenlabs.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.airbnb.android.showkase.annotation.ShowkaseColor

/**
 * Echo design tokens. Warm cream paper, ink type, and the ElevenLabs ember accent.
 * StartScreen is the first surface migrated onto this theme.
 */
object EchoColors {
    @ShowkaseColor(name = "Cream", group = "Echo")
    val Cream = Color(0xFFF7F3EC)

    @ShowkaseColor(name = "Ink", group = "Echo")
    val Ink = Color(0xFF1A1814)

    @ShowkaseColor(name = "Ember", group = "Echo")
    val Ember = Color(0xFFFF6A00)

    @ShowkaseColor(name = "Ember Pressed", group = "Echo")
    val EmberPressed = Color(0xFFE85A00)

    @ShowkaseColor(name = "Stone", group = "Echo")
    val Stone = Color(0xFFE8E2D6)

    @ShowkaseColor(name = "Muted", group = "Echo")
    val Muted = Color(0xFF6B6560)

    @ShowkaseColor(name = "Outline", group = "Echo")
    val Outline = Color(0xFFD9D2C4)

    @ShowkaseColor(name = "On Ember", group = "Echo")
    val OnEmber = Color(0xFFFFFBF6)

    @ShowkaseColor(name = "Error", group = "Echo")
    val Error = Color(0xFFB42318)
}

private val EchoColorScheme = lightColorScheme(
    primary = EchoColors.Ember,
    onPrimary = EchoColors.OnEmber,
    secondary = EchoColors.Ink,
    onSecondary = EchoColors.OnEmber,
    background = EchoColors.Cream,
    onBackground = EchoColors.Ink,
    surface = EchoColors.Cream,
    onSurface = EchoColors.Ink,
    surfaceVariant = EchoColors.Stone,
    onSurfaceVariant = EchoColors.Muted,
    outline = EchoColors.Outline,
    error = EchoColors.Error,
    onError = EchoColors.OnEmber,
)

/**
 * Echo Compose theme: cream paper background, ink type, ember primary actions.
 */
@Composable
fun EchoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EchoColorScheme,
        content = content,
    )
}
