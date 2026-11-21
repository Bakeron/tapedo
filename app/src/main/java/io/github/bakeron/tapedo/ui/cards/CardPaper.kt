package io.github.bakeron.tapedo.ui.cards

import androidx.compose.animation.AnimatedContent
import io.github.bakeron.tapedo.ui.components.fadeSwap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.bakeron.tapedo.R
import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.ui.theme.TapedoTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The paper a card is written on - each kind its own (author 08.11.2026: five kinds looked like two),
 * so the kind shows before a word is read: shopping a till receipt, a list squared paper, a task a punched
 * index card, an event a tear-off calendar page, a note lined paper, an idea a small pinned slip.
 * Used by the open card, the cards after recording and the editor, so changing the type changes the paper at once.
 *
 * @param clickable applied inside the paper's shape (the whole sheet is the touch target).
 */
@Composable
fun CardPaper(
    note: Note,
    modifier: Modifier = Modifier,
    clickable: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = TapedoTheme.colors
    val scheme = MaterialTheme.colorScheme
    val type = note.type
    val shape = if (type == NoteType.SHOPPING) ReceiptShape else RoundedCornerShape(3.dp)
    val desk = scheme.background
    Column(
        modifier
            .fillMaxWidth()
            .shadow(1.5.dp, shape)
            .background(colors.card, shape)
            .border(1.dp, colors.cardEdge, shape)
            .then(clickable)
            .then(
                when (type) {
                    NoteType.CHECKLIST -> Modifier.gridPaper(colors.rule.copy(alpha = 0.55f), 18.dp)
                    NoteType.TASK -> Modifier.punchHole(desk, colors.rule)
                    NoteType.IDEA -> Modifier.pin(colors.record)
                    else -> Modifier
                },
            ),
    ) {
        if (type == NoteType.EVENT) CalendarHead(note)
        Column(
            Modifier.padding(
                start = 16.dp,
                end = 16.dp,
                // Room under the punched hole / the pin; the receipt's teeth need room at the bottom.
                top = when (type) {
                    NoteType.TASK -> 24.dp
                    NoteType.IDEA -> 22.dp
                    else -> 12.dp
                },
                bottom = if (type == NoteType.SHOPPING) 20.dp else 12.dp,
            ),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/** How wide each paper lies: a till receipt and an idea slip are narrower than an index card. */
fun paperWidth(type: NoteType): Float = when (type) {
    NoteType.SHOPPING -> 0.86f
    NoteType.IDEA -> 0.82f
    else -> 1f
}

/** A till receipt: straight top, torn-off zigzag bottom. */
private object ReceiptShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val tooth = with(density) { 6.dp.toPx() }
        val teeth = (size.width / (tooth * 2f)).toInt().coerceAtLeast(1)
        val step = size.width / teeth
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height - tooth)
            var x = size.width
            repeat(teeth) {
                lineTo(x - step / 2f, size.height)
                x -= step
                lineTo(x, size.height - tooth)
            }
            close()
        }
        return Outline.Generic(path)
    }
}

/** A binder hole at the top with a paper reinforcement ring - the desk shows through it. */
private fun Modifier.punchHole(desk: Color, ring: Color): Modifier = drawBehind {
    val center = Offset(size.width / 2f, 12.dp.toPx())
    drawCircle(ring, radius = 9.dp.toPx(), center = center, style = Stroke(2.dp.toPx()))
    drawCircle(desk, radius = 5.dp.toPx(), center = center)
}

/** A push pin at the top, drawn over the text: head, a glint of light and its small shadow on the paper. */
private fun Modifier.pin(head: Color): Modifier = drawWithContent {
    drawContent()
    val center = Offset(size.width / 2f, 11.dp.toPx())
    val r = 7.dp.toPx()
    drawCircle(Color.Black.copy(alpha = 0.22f), radius = r, center = center + Offset(2.dp.toPx(), 3.dp.toPx()))
    drawCircle(head, radius = r, center = center)
    drawCircle(Color.White.copy(alpha = 0.45f), radius = r * 0.32f, center = center - Offset(r * 0.32f, r * 0.32f))
}

/**
 * The top of a tear-off calendar page: a red band with the big day number, the weekday and month
 * (and the time), then the perforation the page was torn along. Read out as one date for screen readers.
 */
@Composable
private fun CalendarHead(note: Note) {
    val colors = TapedoTheme.colors
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val locale = appLocale()
    val today = rememberToday()
    // The desk colour on the red band: about 5:1 in both themes.
    val ink = scheme.background
    val time = note.timeMinutes?.let { clockTime(it) }
    val spoken = listOfNotNull(note.dueEpochDay?.let { dueText(context, it, today, locale) }, time)
        .joinToString(" ")
        .ifEmpty { stringResource(typeNameRes(note.type)) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.record, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
            // Right side free for the priority tape on the corner.
            .padding(start = 16.dp, end = 44.dp, top = 8.dp, bottom = 8.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Removing the date or time redraws the head: old fades out, new fades in (author 21.11.2026).
        val still = TapedoTheme.reduceMotion
        AnimatedContent(
            targetState = note.dueEpochDay to time,
            transitionSpec = { fadeSwap(still).let { if (still) it using null else it } },
            contentAlignment = Alignment.CenterStart,
            label = "calendar head",
        ) { (shownDay, shownTime) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val day = shownDay?.let { LocalDate.ofEpochDay(it) }
                if (day != null) {
                    Text(
                        day.dayOfMonth.toString(),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = ink,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            day.format(DateTimeFormatter.ofPattern("EEEE", locale)).uppercase(locale),
                            style = MaterialTheme.typography.labelLarge,
                            color = ink,
                        )
                        Text(
                            listOfNotNull(day.format(DateTimeFormatter.ofPattern("LLLL", locale)), shownTime).joinToString("  ·  "),
                            style = MaterialTheme.typography.labelMedium,
                            color = ink,
                        )
                    }
                } else {
                    Text(
                        shownTime ?: stringResource(typeNameRes(note.type)).uppercase(locale),
                        style = if (shownTime != null) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.labelLarge,
                        color = ink,
                    )
                }
            }
        }
    }
    // Perforation: a row of small holes along the tear line.
    val desk = scheme.background
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        val gap = 7.dp.toPx()
        var x = gap / 2f
        while (x < size.width) {
            drawCircle(desk, radius = 1.6.dp.toPx(), center = Offset(x, size.height / 2f))
            x += gap
        }
    }
}

/**
 * Empty ruled lines at the end of a lined note - the paper goes on after the text. Spaced by the text's
 * own line height (in sp), so they keep matching the written lines at any text size.
 */
@Composable
fun BlankLines(count: Int, style: TextStyle, rule: Color, modifier: Modifier = Modifier) {
    val step = with(LocalDensity.current) { style.lineHeight.toDp() }
    Spacer(
        modifier
            .fillMaxWidth()
            .height(step * count)
            .drawBehind {
                val stroke = 1.dp.toPx()
                for (i in 1..count) {
                    val y = step.toPx() * i - stroke
                    drawLine(rule, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
                }
            },
    )
}

/** The red header line under the title of a lined index card. */
fun Modifier.headerRule(color: Color): Modifier = drawBehind {
    val y = size.height + 3.dp.toPx()
    drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx())
}

/** "Due" field at the bottom of a task card: a label and a line, with the date written on it (or left blank). */
@Composable
fun DueField(note: Note, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val locale = appLocale()
    val today = rememberToday()
    val due = listOfNotNull(note.dueEpochDay?.let { dueText(context, it, today, locale) }, note.timeMinutes?.let { clockTime(it) })
        .joinToString(" · ")
    val label = stringResource(R.string.card_due_field)
    val line = scheme.outline
    Row(
        modifier
            .fillMaxWidth()
            .then(if (due.isEmpty()) Modifier.clearAndSetSemantics { } else Modifier.clearAndSetSemantics { contentDescription = "$label: $due" }),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(label.uppercase(locale), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            due,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .drawBehind {
                    val y = size.height
                    drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                },
        )
    }
}
