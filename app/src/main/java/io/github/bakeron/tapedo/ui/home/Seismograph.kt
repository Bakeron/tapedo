package io.github.bakeron.tapedo.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import io.github.bakeron.tapedo.ui.theme.TapedoTheme

/**
 * The voice line: a strip of graph paper with a graphite pen scribbling a zigzag as loud as the voice,
 * newest sample at the red pen tip on the right. Purely decorative for screen readers -
 * the counter and the "listening" text carry the information.
 *
 * @param levels voice levels 0..1, oldest first
 * @param sampleCount total samples so far - the zigzag direction follows it, so the line does not flicker as it scrolls
 */
@Composable
fun Seismograph(levels: List<Float>, sampleCount: Long, capacity: Int, modifier: Modifier = Modifier) {
    val colors = TapedoTheme.colors
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier
            .fillMaxWidth()
            .height(120.dp)
            .clearAndSetSemantics { },
    ) {
        drawRoundRect(colors.card, cornerRadius = CornerRadius(4.dp.toPx()))
        drawRoundRect(colors.cardEdge, cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx()))

        // Graph paper: faint grid, the middle line a little stronger.
        val step = 12.dp.toPx()
        var y = size.height / 2 % step
        while (y < size.height) {
            drawLine(colors.rule, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.5.dp.toPx())
            y += step
        }
        var x = 0f
        while (x < size.width) {
            drawLine(colors.rule, Offset(x, 0f), Offset(x, size.height), strokeWidth = 0.5.dp.toPx())
            x += step
        }
        val mid = size.height / 2
        drawLine(colors.cardEdge, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.dp.toPx())

        if (levels.isEmpty()) return@Canvas
        val pad = 10.dp.toPx()
        val dx = (size.width - pad * 2) / capacity
        val amp = size.height / 2 - pad
        val firstIndex = sampleCount - levels.size
        val path = Path()
        levels.forEachIndexed { i, level ->
            // Right-aligned: the newest sample sits at the pen tip.
            val px = size.width - pad - (levels.size - 1 - i) * dx
            val sign = if ((firstIndex + i) % 2 == 0L) -1f else 1f
            val py = mid + sign * level * amp
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, ink, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        val tipY = mid + (if ((sampleCount - 1) % 2 == 0L) -1f else 1f) * levels.last() * amp
        drawCircle(colors.record, radius = 4.dp.toPx(), center = Offset(size.width - pad, tipY))
    }
}
