package io.github.bakeron.tapedo.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.bakeron.tapedo.ui.theme.TapedoTheme

enum class PaperButtonStyle {
    /** Inked key - the main action of a screen. */
    FILLED,

    /** Pencil outline - secondary actions. */
    OUTLINED,

    /** Red outline - throws something away. */
    DANGER,

    /** Light outline on a dark inked strip (e.g. "Undo" on the undo tape). */
    ON_INK,
}

/**
 * A rectangular key with small worn corners, like the buttons on the recorder -
 * instead of Material's pill buttons. The outline has full contrast so it reads as a button.
 */
@Composable
fun PaperButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PaperButtonStyle = PaperButtonStyle.OUTLINED,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val record = TapedoTheme.colors.record
    val shape = RoundedCornerShape(3.dp)
    val (fill, edge, ink) = when (style) {
        PaperButtonStyle.FILLED -> Triple(scheme.primary, scheme.primary, scheme.onPrimary)
        PaperButtonStyle.OUTLINED -> Triple(Color.Transparent, scheme.outline, scheme.onSurface)
        PaperButtonStyle.DANGER -> Triple(Color.Transparent, record, record)
        PaperButtonStyle.ON_INK -> Triple(Color.Transparent, scheme.onPrimary, scheme.onPrimary)
    }
    // Turning on and off fades, never jumps.
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.45f,
        animationSpec = if (TapedoTheme.reduceMotion) snap() else tween(200),
        label = "key enabled",
    )
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(fill.copy(alpha = fill.alpha * alpha), shape)
            .border(1.5.dp, edge.copy(alpha = alpha), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        // A key that changes its name ("Show" / "Hide") fades the old name out, then the new one in.
        SwapText(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = ink.copy(alpha = alpha),
            textAlign = TextAlign.Center,
        )
    }
}
