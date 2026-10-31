package io.github.bakeron.tapedo.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.draw.shadow
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
            .background(scheme.surfaceContainerHighest, trackShape)
            .border(1.dp, colors.rule, trackShape)
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

        // The knob - drawn under the labels so text stays on top.
        Box(
            Modifier
                .offset(x = knobX)
                .width(segment)
                .fillMaxHeight()
                .heightIn(min = 46.dp)
                .shadow(2.dp, RoundedCornerShape(4.dp))
                .background(colors.card, RoundedCornerShape(4.dp))
                // Full-contrast edge: the knob stands out from the track in both themes.
                .border(1.5.dp, scheme.outline, RoundedCornerShape(4.dp)),
        )

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
                    // Indicator LED: lit on the chosen option, a dark socket elsewhere.
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(if (isSelected) colors.record else colors.rule, CircleShape),
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
