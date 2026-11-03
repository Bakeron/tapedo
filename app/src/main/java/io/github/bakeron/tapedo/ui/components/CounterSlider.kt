package io.github.bakeron.tapedo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.bakeron.tapedo.ui.theme.TapedoTheme
import kotlin.math.roundToInt

/** Scale mark drawn on the groove under an option. */
enum class Tick { NONE, MINOR, MAJOR }

/**
 * A slider drawn like the tape counter on a pocket recorder: a recessed groove with a scale
 * (see [tick]) and a red "record" dot as the knob. Each of [options] is one evenly spaced
 * position, so the values themselves may grow unevenly (e.g. 10 s steps, then minutes).
 * The value is typed above the dot, so it is readable without relying on colour or position.
 *
 * The choice is saved only when the finger lifts - dragging does not write settings 60 times.
 * For screen readers it is a normal slider that announces [label], e.g. "12 min".
 * Built on Material's Slider (custom thumb / track slots are still marked experimental)
 * to keep its keyboard and accessibility handling instead of a hand-made drag gesture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> CounterSlider(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    tick: (T) -> Tick = { Tick.NONE },
    /** What screen readers say - full words instead of the short label on the dial. */
    spokenLabel: @Composable (T) -> String = label,
) {
    val colors = TapedoTheme.colors
    val scheme = MaterialTheme.colorScheme
    var index by remember(selected, options) { mutableIntStateOf(options.indexOf(selected).coerceAtLeast(0)) }
    val text = label(options[index])
    val spoken = spokenLabel(options[index])
    val last = options.lastIndex
    val fraction = if (last == 0) 0f else index.toFloat() / last

    Slider(
        value = index.toFloat(),
        onValueChange = { index = it.roundToInt().coerceIn(0, last) },
        onValueChangeFinished = { if (options[index] != selected) onSelect(options[index]) },
        valueRange = 0f..last.toFloat(),
        steps = (last - 1).coerceAtLeast(0),
        // Room above the track for the counter text over the dot.
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 26.dp)
            .semantics { stateDescription = spoken },
        thumb = {
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                // Counter text in a small recessed display window (amber digits in Panel): zero-size in
                // layout, drawn above the dot - centred mid-way, sliding to stay inside the track near the ends.
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.displayInk,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .layout { measurable, _ ->
                            val p = measurable.measure(Constraints())
                            val half = 15.dp.roundToPx() // half of the 30.dp thumb box
                            val x = -half + ((half * 2 - p.width) * fraction).roundToInt()
                            layout(0, 0) { p.place(x, -p.height - 2.dp.roundToPx()) }
                        }
                        .background(colors.display, RoundedCornerShape(2.dp))
                        .border(1.dp, colors.controlEdge, RoundedCornerShape(2.dp))
                        .padding(horizontal = 5.dp),
                )
                Box(
                    Modifier
                        .size(24.dp)
                        .shadow(2.dp, CircleShape)
                        .background(colors.record, CircleShape)
                        .border(3.dp, colors.card, CircleShape),
                )
            }
        },
        track = {
            val groove = colors.track
            val edge = colors.controlEdge
            val wound = colors.record.copy(alpha = 0.35f)
            val tickColor = scheme.onSurfaceVariant
            Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(groove, cornerRadius = r)
                // "Wound tape" from the start to the dot.
                drawRoundRect(wound, size = Size(size.width * fraction, size.height), cornerRadius = r)
                drawRoundRect(edge, cornerRadius = r, style = Stroke(1.dp.toPx()))

                for (i in 1 until last) {
                    val h = when (tick(options[i])) {
                        Tick.NONE -> continue
                        Tick.MINOR -> size.height * 0.4f
                        Tick.MAJOR -> size.height * 0.7f
                    }
                    val x = size.width * i / last
                    val top = (size.height - h) / 2
                    drawLine(tickColor, Offset(x, top), Offset(x, top + h), strokeWidth = 1.dp.toPx())
                }
            }
        },
    )
}
