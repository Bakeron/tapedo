package io.github.bakeron.tapedo.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bakeron.tapedo.ui.theme.TapedoTheme

/**
 * An on / off choice drawn as a toggle lever on a small metal plate - flipped up is on, down is off -
 * so a yes / no setting does not look like the multi-option slide switches. The state shows by the
 * lever's direction, the lit LED and the written word next to it, not by colour alone.
 * For screen readers it is a switch.
 */
@Composable
fun ToggleLever(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    stateLabel: String,
    modifier: Modifier = Modifier,
) {
    val colors = TapedoTheme.colors
    val scheme = MaterialTheme.colorScheme
    // -1 = flipped down (off), 1 = up (on).
    val tilt by animateFloatAsState(
        targetValue = if (checked) 1f else -1f,
        animationSpec = if (TapedoTheme.reduceMotion) snap() else spring(),
        label = "lever",
    )
    Row(
        modifier
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Canvas(Modifier.size(width = 44.dp, height = 56.dp).clearAndSetSemantics { }) {
            val plate = Size(size.width - 8.dp.toPx(), size.height - 4.dp.toPx())
            val plateTop = Offset(4.dp.toPx(), 2.dp.toPx())
            drawRoundRect(colors.track, plateTop, plate, CornerRadius(4.dp.toPx()))
            drawRoundRect(colors.controlEdge, plateTop, plate, CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx()))
            // Two screws holding the plate.
            val screw = 2.dp.toPx()
            drawCircle(colors.controlEdge, screw, Offset(size.width / 2, plateTop.y + 5.dp.toPx()))
            drawCircle(colors.controlEdge, screw, Offset(size.width / 2, plateTop.y + plate.height - 5.dp.toPx()))

            val center = Offset(size.width / 2, size.height / 2)
            // The round collar the lever comes out of.
            drawCircle(colors.knob, 8.dp.toPx(), center)
            drawCircle(scheme.outline, 8.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
            // The lever, seen from the front: it points up or down, foreshortened.
            val tip = Offset(center.x, center.y - tilt * 16.dp.toPx())
            drawLine(scheme.onSurface, center, tip, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(scheme.onSurface, 5.dp.toPx(), tip)
            drawCircle(colors.knobLight, 1.5.dp.toPx(), Offset(tip.x - 1.5.dp.toPx(), tip.y - 1.5.dp.toPx()))
        }
        // LED next to the word: lit when on.
        Canvas(Modifier.size(8.dp).clearAndSetSemantics { }) {
            drawCircle(if (checked) colors.record else colors.rule)
        }
        Text(
            stateLabel,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = scheme.onSurface,
        )
    }
}
