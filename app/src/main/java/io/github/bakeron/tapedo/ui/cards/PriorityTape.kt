package io.github.bakeron.tapedo.ui.cards

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import io.github.bakeron.tapedo.core.model.Priority
import io.github.bakeron.tapedo.ui.theme.TapedoTheme

/**
 * A piece of coloured tape stuck across the card's corner. Each priority has its own colour AND pattern -
 * high: dense stripes, medium: dots, low: plain - so it reads without colour vision too.
 * The card adds the priority in words for screen readers.
 */
@Composable
fun PriorityTape(priority: Priority, modifier: Modifier = Modifier) {
    val colors = TapedoTheme.colors
    val fill = when (priority) {
        Priority.HIGH -> colors.tapeHigh
        Priority.MEDIUM -> colors.tapeMedium
        Priority.LOW -> colors.tapeLow
    }
    val ink = colors.tapeInk.copy(alpha = 0.42f)
    Canvas(modifier.size(width = 64.dp, height = 20.dp).rotate(38f)) {
        val w = size.width
        val h = size.height
        val tooth = 2.5.dp.toPx()
        // Torn ends: a small zigzag on the left and right edge.
        val tape = Path().apply {
            moveTo(0f, 0f)
            lineTo(w, 0f)
            var y = 0f
            var out = true
            while (y < h) {
                y = (y + tooth).coerceAtMost(h)
                lineTo(if (out) w - tooth else w, y)
                out = !out
            }
            lineTo(0f, h)
            y = h
            out = true
            while (y > 0f) {
                y = (y - tooth).coerceAtLeast(0f)
                lineTo(if (out) tooth else 0f, y)
                out = !out
            }
            close()
        }
        drawPath(tape, fill.copy(alpha = 0.92f))
        clipPath(tape) {
            when (priority) {
                Priority.HIGH -> {
                    val gap = 6.dp.toPx()
                    var x = -h
                    while (x < w + h) {
                        drawLine(ink, Offset(x, h), Offset(x + h, 0f), strokeWidth = 2.dp.toPx())
                        x += gap
                    }
                }
                Priority.MEDIUM -> {
                    val gap = 6.dp.toPx()
                    var row = 0
                    var y = gap / 2
                    while (y < h) {
                        var x = if (row % 2 == 0) gap / 2 else gap
                        while (x < w) {
                            drawCircle(ink, radius = 1.3.dp.toPx(), center = Offset(x, y))
                            x += gap
                        }
                        y += gap
                        row++
                    }
                }
                Priority.LOW -> Unit
            }
        }
    }
}
