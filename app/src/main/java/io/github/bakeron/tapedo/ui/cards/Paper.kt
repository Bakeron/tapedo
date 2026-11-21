package io.github.bakeron.tapedo.ui.cards

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.bakeron.tapedo.ui.theme.TapedoTheme

/**
 * Text written on the lines of an index card: a faint rule under every line of text.
 * The rules follow the text's own layout (not a dp grid), so they stay under the words at any text size.
 */
@Composable
fun RuledText(text: String, style: TextStyle, color: Color, rule: Color, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    RuledText(AnnotatedString(text), style, color, rule, modifier, maxLines)

/** [RuledText] with styled words (e.g. option names in bold). */
@Composable
fun RuledText(text: AnnotatedString, style: TextStyle, color: Color, rule: Color, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout = it },
        modifier = modifier.fillMaxWidth().drawBehind {
            val text = layout ?: return@drawBehind
            val stroke = 1.dp.toPx()
            for (line in 0 until text.lineCount) {
                val y = (text.getLineBaseline(line) + text.getLineBottom(line)) / 2f
                drawLine(rule, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
            }
        },
    )
}

/** Squared paper (kratka) for lists. */
fun Modifier.gridPaper(color: Color, cell: Dp): Modifier = drawBehind {
    val step = cell.toPx()
    val stroke = 0.8.dp.toPx()
    var x = step
    while (x < size.width) {
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
        x += step
    }
    var y = step
    while (y < size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
        y += step
    }
}

/**
 * A hand-drawn pencil line through each line of a text, drawn left to right when [struck] turns on.
 * Pass the text's layout from `onTextLayout`. With "reduce motion" the line appears at once.
 */
@Composable
fun Modifier.handStrike(struck: Boolean, layout: () -> TextLayoutResult?, color: Color): Modifier {
    val progress by animateFloatAsState(
        targetValue = if (struck) 1f else 0f,
        animationSpec = if (TapedoTheme.reduceMotion) snap() else tween(durationMillis = 260),
        label = "strike",
    )
    return drawWithContent {
        drawContent()
        val text = layout() ?: return@drawWithContent
        if (progress <= 0f) return@drawWithContent
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (line in 0 until text.lineCount) {
            val left = text.getLineLeft(line) - 2.dp.toPx()
            val right = text.getLineRight(line) + 2.dp.toPx()
            val mid = (text.getLineTop(line) + text.getLineBottom(line)) / 2f
            val end = left + (right - left) * progress
            // A slightly rising, wavy stroke - a pen, not a ruler.
            val path = Path().apply {
                moveTo(left, mid + 1.5.dp.toPx())
                val steps = 6
                for (i in 1..steps) {
                    val x = left + (end - left) * i / steps
                    val wave = if (i % 2 == 0) 0.8f else -0.8f
                    val rise = 1.5f - 3f * i / steps
                    lineTo(x, mid + (rise + wave).dp.toPx())
                }
            }
            drawPath(path, color, style = stroke)
        }
    }
}

/** A tick box drawn like a pencil square; ticked = a hand-drawn check mark (shape, not only colour). */
@Composable
fun PencilBox(checked: Boolean, ink: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val w = size.width
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val box = Path().apply {
            moveTo(w * 0.08f, w * 0.12f)
            lineTo(w * 0.92f, w * 0.08f)
            lineTo(w * 0.9f, w * 0.92f)
            lineTo(w * 0.1f, w * 0.9f)
            close()
        }
        drawPath(box, ink, style = stroke)
        if (checked) {
            val tick = Path().apply {
                moveTo(w * 0.22f, w * 0.52f)
                lineTo(w * 0.44f, w * 0.74f)
                lineTo(w * 0.86f, w * 0.18f)
            }
            drawPath(tick, ink, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
