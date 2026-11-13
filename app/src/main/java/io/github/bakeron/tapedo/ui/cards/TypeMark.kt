package io.github.bakeron.tapedo.ui.cards

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.ui.theme.TapedoTheme

/**
 * The small drawn sign of a card type - the same sign on the strips, the dividers and the "What are you
 * recording?" tapes, so one sign means one kind everywhere. Drawn with a pen, not emoji or Material icons.
 * Decoration only: the type is always written next to it, so screen readers skip it.
 */
@Composable
fun TypeMark(type: NoteType, color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val w = this.size.width
        val line = Stroke(width = (w / 10f).coerceAtLeast(1.4.dp.toPx()), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (type) {
            NoteType.SHOPPING -> {
                // Basket: a tapered body with two slats and a handle arc over it.
                val body = Path().apply {
                    moveTo(w * 0.08f, w * 0.42f)
                    lineTo(w * 0.92f, w * 0.42f)
                    lineTo(w * 0.80f, w * 0.90f)
                    lineTo(w * 0.20f, w * 0.90f)
                    close()
                }
                drawPath(body, color, style = line)
                drawLine(color, Offset(w * 0.40f, w * 0.52f), Offset(w * 0.42f, w * 0.80f), line.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.60f, w * 0.52f), Offset(w * 0.58f, w * 0.80f), line.width, StrokeCap.Round)
                drawArc(color, 180f, 180f, false, Offset(w * 0.24f, w * 0.10f), Size(w * 0.52f, w * 0.60f), style = line)
            }
            NoteType.CHECKLIST -> {
                // Three lines, each with its tick box.
                for (i in 0..2) {
                    val y = w * (0.20f + 0.30f * i)
                    val box = w * 0.18f
                    drawRect(color, Offset(w * 0.04f, y - box / 2), Size(box, box), style = Stroke(line.width * 0.8f))
                    drawLine(color, Offset(w * 0.36f, y), Offset(w * 0.96f, y), line.width, StrokeCap.Round)
                }
            }
            NoteType.TASK -> {
                // One bold check mark - a single thing to tick off.
                val tick = Path().apply {
                    moveTo(w * 0.10f, w * 0.52f)
                    lineTo(w * 0.40f, w * 0.82f)
                    lineTo(w * 0.92f, w * 0.18f)
                }
                drawPath(tick, color, style = Stroke(line.width * 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            NoteType.EVENT -> {
                // A tear-off calendar page: solid top band, two rings, the day as a dot.
                val page = Rect(w * 0.08f, w * 0.18f, w * 0.92f, w * 0.94f)
                drawRoundRect(color, page.topLeft, page.size, CornerRadius(w * 0.08f), style = line)
                drawRect(color, page.topLeft, Size(page.width, w * 0.20f))
                drawLine(color, Offset(w * 0.30f, w * 0.06f), Offset(w * 0.30f, w * 0.28f), line.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.70f, w * 0.06f), Offset(w * 0.70f, w * 0.28f), line.width, StrokeCap.Round)
                drawRect(color, Offset(w * 0.56f, w * 0.60f), Size(w * 0.18f, w * 0.18f))
            }
            NoteType.NOTE -> {
                // Lines of text, the last one short.
                val ends = floatArrayOf(0.94f, 0.94f, 0.94f, 0.56f)
                for (i in ends.indices) {
                    val y = w * (0.16f + 0.23f * i)
                    drawLine(color, Offset(w * 0.06f, y), Offset(w * ends[i], y), line.width, StrokeCap.Round)
                }
            }
            NoteType.IDEA -> {
                // Light bulb: glass, a narrower neck and the screw cap.
                drawCircle(color, radius = w * 0.30f, center = Offset(w * 0.5f, w * 0.36f), style = line)
                drawLine(color, Offset(w * 0.38f, w * 0.72f), Offset(w * 0.62f, w * 0.72f), line.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.40f, w * 0.86f), Offset(w * 0.60f, w * 0.86f), line.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.42f, w * 0.62f), Offset(w * 0.40f, w * 0.72f), line.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.58f, w * 0.62f), Offset(w * 0.60f, w * 0.72f), line.width, StrokeCap.Round)
            }
        }
    }
}

/** The "Done" divider's sign: a filing box with its lid on - finished cards are put away, not thrown out. */
@Composable
fun DoneMark(color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val w = this.size.width
        val line = Stroke(width = (w / 10f).coerceAtLeast(1.4.dp.toPx()), cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Lid a little wider than the box, the box below it, a handle slot on the front.
        drawRoundRect(color, Offset(w * 0.04f, w * 0.14f), Size(w * 0.92f, w * 0.22f), CornerRadius(w * 0.04f), style = line)
        drawRect(color, Offset(w * 0.12f, w * 0.36f), Size(w * 0.76f, w * 0.54f), style = line)
        drawLine(color, Offset(w * 0.38f, w * 0.56f), Offset(w * 0.62f, w * 0.56f), line.width, StrokeCap.Round)
    }
}

/**
 * The type sign on a short piece of label-maker tape, stuck over the left edge of a strip in the card box -
 * the same dark tape with light print as the section labels, so it reads as a label, not as a button.
 */
@Composable
fun TypeTapeMark(type: NoteType, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .rotate(TapedoTheme.tilt(-3f))
            .background(scheme.primary, RoundedCornerShape(1.dp))
            .padding(horizontal = 5.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        TypeMark(type, scheme.onPrimary, size = 18.dp)
    }
}
