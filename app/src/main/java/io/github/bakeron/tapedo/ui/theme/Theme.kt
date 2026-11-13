package io.github.bakeron.tapedo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.bakeron.tapedo.core.settings.ThemeMode

/** True when the user (in Tapedo or system-wide) asked for less motion - animations snap instead. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** "Crooked paper" is on: cards, tapes and stamps lie at a slight angle. Off by default (author 10.11.2026). */
val LocalTilted = staticCompositionLocalOf { false }

private val LightScheme = lightColorScheme(
    primary = Paper.graphite,
    onPrimary = Paper.card,
    secondary = Paper.record,
    onSecondary = Paper.card,
    tertiary = Paper.stamp,
    background = Paper.desk,
    onBackground = Paper.graphite,
    surface = Paper.desk,
    onSurface = Paper.graphite,
    surfaceVariant = Paper.card,
    onSurfaceVariant = Paper.pencil,
    surfaceContainer = Paper.card,
    surfaceContainerHigh = Paper.card,
    surfaceContainerHighest = Paper.cardEdge,
    outline = Paper.pencil,
    outlineVariant = Paper.rule,
    error = Paper.record,
)

private val DarkScheme = darkColorScheme(
    primary = Panel.amber,
    onPrimary = Panel.desk,
    secondary = Panel.record,
    onSecondary = Panel.desk,
    tertiary = Panel.stamp,
    background = Panel.desk,
    onBackground = Panel.ink,
    surface = Panel.desk,
    onSurface = Panel.ink,
    surfaceVariant = Panel.card,
    onSurfaceVariant = Panel.dimInk,
    surfaceContainer = Panel.card,
    surfaceContainerHigh = Panel.card,
    surfaceContainerHighest = Panel.cardEdge,
    outline = Panel.dimInk,
    outlineVariant = Panel.rule,
    error = Panel.record,
)

// Paper cards have small, slightly worn corners - not pill shapes.
private val TapedoShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(10.dp),
)

/** Resolves "as in system" to the actual light / dark choice. */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun TapedoTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    reduceMotion: Boolean = false,
    tilted: Boolean = false,
    /** Tapedo's own text size, multiplied with the phone's font scale (sp only - layout in dp stays). */
    textScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val dark = mode.isDark()
    val scheme = if (dark) DarkScheme else LightScheme
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalTapedoColors provides if (dark) DarkTapedoColors else LightTapedoColors,
        LocalReduceMotion provides reduceMotion,
        LocalTilted provides tilted,
        LocalDensity provides Density(density.density, density.fontScale * textScale),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = TapedoTypography,
            shapes = TapedoShapes,
        ) {
            // MaterialTheme alone leaves the default text / icon color black (only Surface sets it),
            // which made titles invisible on the dark Panel theme.
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}

/** Shortcut: `TapedoTheme.colors.record` next to `MaterialTheme.colorScheme`. */
object TapedoTheme {
    val colors: TapedoColors
        @Composable @ReadOnlyComposable get() = LocalTapedoColors.current

    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current

    /** [degrees] with "Crooked paper" on, straight (0) otherwise - every paper angle goes through here. */
    @Composable @ReadOnlyComposable
    fun tilt(degrees: Float): Float = if (LocalTilted.current) degrees else 0f
}
