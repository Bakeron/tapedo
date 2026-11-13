package io.github.bakeron.tapedo.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.bakeron.tapedo.ui.theme.TapedoTheme

/**
 * A row of options drawn like the slide switch on an old pocket recorder: a recessed track
 * and a raised knob that slides to the chosen option, with a small indicator LED on it.
 *
 * The choice is shown three ways, not by colour alone: knob position, LED, bold label.
 * For screen readers it is a radio group.
 */
@Composable
fun <T> SlideSelector(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = options.indexOf(selected).coerceAtLeast(0)
    val colors = TapedoTheme.colors
    val scheme = MaterialTheme.colorScheme
    val trackShape = RoundedCornerShape(5.dp)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .background(colors.track, trackShape)
            .border(1.dp, colors.controlEdge, trackShape)
            .padding(3.dp),
    ) {
        val segment = maxWidth / options.size
        val knobX by animateDpAsState(
            targetValue = segment * index,
            animationSpec = if (TapedoTheme.reduceMotion) snap() else spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "knob",
        )

        // The knob - drawn under the labels so text stays on top. It takes the track's real height:
        // in a scrolling screen fillMaxHeight alone has no limit, so a two-line label stuck out of the knob.
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .offset(x = knobX)
                    .width(segment)
                    .fillMaxHeight()
                    .shadow(2.dp, RoundedCornerShape(4.dp))
                    .background(colors.knob, RoundedCornerShape(4.dp))
                    // Light catching the top edge - the knob reads as raised also on the dark panel.
                    .drawBehind {
                        val inset = 3.dp.toPx()
                        drawLine(
                            colors.knobLight,
                            Offset(inset, 1.5.dp.toPx()),
                            Offset(size.width - inset, 1.5.dp.toPx()),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    // Full-contrast edge: the knob stands out from the track in both themes.
                    .border(1.5.dp, scheme.outline, RoundedCornerShape(4.dp)),
            )
        }

        Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).selectableGroup()) {
            options.forEachIndexed { i, option ->
                val isSelected = i == index
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp)
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option) })
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Indicator LED: lit on the chosen option, a dark socket elsewhere. It dims and lights up
                    // while the knob slides over, not in one jump (author 10.11.2026).
                    val led by animateColorAsState(
                        targetValue = if (isSelected) colors.record else colors.ledOff,
                        animationSpec = if (TapedoTheme.reduceMotion) snap() else tween(LED_FADE_MS),
                        label = "led",
                    )
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(led, CircleShape),
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) scheme.onSurface else scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

private const val LED_FADE_MS = 350
