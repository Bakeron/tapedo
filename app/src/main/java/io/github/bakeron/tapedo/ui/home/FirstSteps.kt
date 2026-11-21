package io.github.bakeron.tapedo.ui.home

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.bakeron.tapedo.R
import io.github.bakeron.tapedo.core.model.Priority
import io.github.bakeron.tapedo.ui.cards.PriorityTape
import io.github.bakeron.tapedo.ui.cards.RuledText
import io.github.bakeron.tapedo.ui.components.LabelTape
import io.github.bakeron.tapedo.ui.components.PairOrStack
import io.github.bakeron.tapedo.ui.components.PaperButton
import io.github.bakeron.tapedo.ui.components.PaperButtonStyle
import io.github.bakeron.tapedo.ui.theme.TapedoTheme
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The parts of the home screen the first-steps tips point at. */
enum class TourSpot { RECORD, TABS, BOX, SETTINGS }

/**
 * First steps (author 12.11.2026): a few typed slips that show what is where on the first start. Each one
 * circles its part of the screen with a yellow marker, like a note left in the box of a new recorder.
 * The priority slip has no spot - a new user has no card with a tape yet, so it shows the three tapes itself.
 */
enum class FirstStep(val spot: TourSpot?, @StringRes val title: Int, @StringRes val body: Int) {
    RECORD(TourSpot.RECORD, R.string.first_steps_record_title, R.string.first_steps_record_body),
    TABS(TourSpot.TABS, R.string.first_steps_tabs_title, R.string.first_steps_tabs_body),
    BOX(TourSpot.BOX, R.string.first_steps_box_title, R.string.first_steps_box_body),
    PRIORITY(null, R.string.first_steps_priority_title, R.string.first_steps_priority_body),
    SETTINGS(TourSpot.SETTINGS, R.string.first_steps_settings_title, R.string.first_steps_settings_body),
}

/** Where each [TourSpot] lies in the window - filled in by [tourSpot]. */
class TourSpots {
    val bounds = mutableStateMapOf<TourSpot, Rect>()
}

val LocalTourSpots = staticCompositionLocalOf<TourSpots?> { null }

/** Marks this element as [spot], so a first-steps slip can point at it. */
@Composable
fun Modifier.tourSpot(spot: TourSpot): Modifier {
    val spots = LocalTourSpots.current ?: return this
    return onGloballyPositioned {
        val r = it.boundsInWindow()
        if (spots.bounds[spot] != r) spots.bounds[spot] = r
    }
}

/**
 * The tips over the home screen: everything but the pointed part is dimmed, the slip sits beside it with a
 * pencil arrow. Touches do not reach the screen below. "Back" and the left arrow key go to the previous
 * slip (from the first one: close), the right arrow key to the next one, Escape skips.
 */
@Composable
fun FirstStepsOverlay(
    step: Int,
    spots: TourSpots,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    /** Recording ends only with the key (Settings) - the first slip must not promise "ends by itself". */
    endsOnStop: Boolean = false,
) {
    val colors = TapedoTheme.colors
    val reduceMotion = TapedoTheme.reduceMotion
    BackHandler(onBack = onBack)

    // The slip on screen lags [step] while the old one goes: it fades and its marker winds back, the window
    // in the shade closes, and only then the next slip comes (author 21.11.2026: no slip may just blink).
    var shown by remember { mutableIntStateOf(step) }
    var leaving by remember { mutableStateOf(false) }
    val current = FirstStep.entries[shown]
    val last = shown == FirstStep.entries.lastIndex

    var origin by remember { mutableStateOf(Offset.Zero) }
    val target = current.spot?.let { spots.bounds[it] }?.translate(-origin)
    var slip by remember { mutableStateOf(Rect.Zero) }

    // The marker draws its loop and the slip fades in - both at once with "reduce motion".
    val drawn = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(step, reduceMotion) {
        if (reduceMotion) {
            shown = step
            drawn.snapTo(1f)
        } else {
            if (shown != step) {
                leaving = true
                drawn.animateTo(0f, tween((UNDRAW_MS * drawn.value).toInt()))
                shown = step
            }
            // Also when a quick "Back" returned to the slip still on screen before it was gone.
            leaving = false
            drawn.animateTo(1f, tween(DRAW_MS))
        }
    }

    // The tips hold the keyboard focus (the screen below takes none), so the arrow keys work at once;
    // Tab still reaches the keys on the slip.
    val keys = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { keys.requestFocus() } }

    // How far the slip and the window in the shade are shown: going, they follow the marker evenly; coming,
    // they are fully there while the marker still draws.
    val fade = { if (leaving) drawn.value else (drawn.value * 2.5f).coerceAtMost(1f) }
    val title = stringResource(R.string.first_steps_pane)
    val marker = colors.tapeMedium
    val scrim = Color.Black.copy(alpha = SCRIM_ALPHA)
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() }
            .semantics { paneTitle = title }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .onPreviewKeyEvent { e ->
                val action = when (e.key) {
                    Key.DirectionRight -> onNext
                    Key.DirectionLeft -> onBack
                    Key.Escape -> onSkip
                    else -> return@onPreviewKeyEvent false
                }
                // Act on the press, swallow the release too - Escape must not also work as "Back".
                if (e.type == KeyEventType.KeyDown) action()
                true
            }
            .focusRequester(keys)
            .focusable()
            .drawBehind {
                val mark = target?.let { markFor(it, size) }
                val shade = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    // The clear window has the marker's shape: an oval inside the loop, or the frame's rectangle.
                    mark?.let { m -> if (m.small) addOval(m.oval(0.97f)) else addRoundRect(RoundRect(m.frame, CornerRadius(12.dp.toPx()))) }
                }
                drawPath(shade, scrim)
                if (mark != null) {
                    // The window opens with the marker and closes before the next slip - it never jumps.
                    val open = fade()
                    if (open < 1f) {
                        val hole = Path().apply {
                            if (mark.small) addOval(mark.oval(0.97f)) else addRoundRect(RoundRect(mark.frame, CornerRadius(12.dp.toPx())))
                        }
                        drawPath(hole, scrim.copy(alpha = SCRIM_ALPHA * (1f - open)))
                    }
                    drawMarker(markerLoop(mark), drawn.value, marker)
                    if (!slip.overlaps(mark.bounds)) {
                        drawArrow(slip, mark.bounds, ((drawn.value - 0.55f) / 0.45f).coerceIn(0f, 1f), marker)
                    }
                }
            },
    ) {
        val insets = WindowInsets.safeDrawing
        val direction = LocalLayoutDirection.current
        Layout(
            content = {
                TipSlip(
                    step = current,
                    index = shown,
                    last = last,
                    onNext = onNext,
                    onSkip = onSkip,
                    endsOnStop = endsOnStop,
                    modifier = Modifier.graphicsLayer { alpha = fade() },
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val margin = 16.dp.toPx()
            val area = Rect(
                insets.getLeft(this, direction) + margin,
                insets.getTop(this) + margin,
                constraints.maxWidth - insets.getRight(this, direction) - margin,
                constraints.maxHeight - insets.getBottom(this) - margin,
            )
            val width = min(area.width, SLIP_MAX_WIDTH.toPx()).toInt().coerceAtLeast(0)
            val placeable = measurables.first().measure(
                Constraints(minWidth = width, maxWidth = width, maxHeight = area.height.toInt().coerceAtLeast(0)),
            )
            val size = Size(placeable.width.toFloat(), placeable.height.toFloat())
            val at = placeSlip(target?.let { markFor(it, Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())).bounds }, size, area, ARROW_GAP.toPx())
            slip = Rect(at, size)
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place(at.x.toInt(), at.y.toInt())
            }
        }
    }
}

/** One slip: the step on a label tape, the title, the text typed on the card's lines, then the keys. */
@Composable
private fun TipSlip(
    step: FirstStep,
    index: Int,
    last: Boolean,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    endsOnStop: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = TapedoTheme.colors
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(3.dp)
    Column(
        modifier
            .rotate(TapedoTheme.tilt(-0.8f))
            .background(colors.card, shape)
            .border(1.dp, colors.controlEdge, shape)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LabelTape(stringResource(R.string.first_steps_count, index + 1, FirstStep.entries.size))
        // Title and text are read out together whenever the slip changes.
        Column(
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(step.title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            RuledText(
                stringResource(if (step == FirstStep.RECORD && endsOnStop) R.string.first_steps_record_body_stop else step.body),
                MaterialTheme.typography.bodyMedium,
                scheme.onSurface,
                colors.rule,
            )
        }
        if (step == FirstStep.PRIORITY) PriorityLegend()
        PairOrStack(Modifier.fillMaxWidth().padding(top = 4.dp), stackGap = 8.dp) {
            if (!last) PaperButton(stringResource(R.string.first_steps_skip), onClick = onSkip)
            PaperButton(
                stringResource(if (last) R.string.first_steps_done else R.string.first_steps_next),
                onClick = onNext,
                style = PaperButtonStyle.FILLED,
            )
        }
    }
}

/** The three priority tapes as they lie on a card's corner, each named with its pattern. */
@Composable
private fun PriorityLegend() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((priority, text) in listOf(
            Priority.HIGH to R.string.first_steps_priority_high,
            Priority.MEDIUM to R.string.first_steps_priority_medium,
            Priority.LOW to R.string.first_steps_priority_low,
        )) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PriorityTape(priority, Modifier.size(width = 44.dp, height = 16.dp).clearAndSetSemantics { }, angle = 0f)
                Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * Where the slip goes: below or above its spot (on the side with more room first), else beside it; a spot
 * as big as the screen (the card box) gets the slip over its lower part. Never out of [area].
 */
internal fun placeSlip(target: Rect?, slip: Size, area: Rect, gap: Float): Offset {
    fun clampX(x: Float) = x.coerceIn(area.left, max(area.left, area.right - slip.width))
    fun clampY(y: Float) = y.coerceIn(area.top, max(area.top, area.bottom - slip.height))
    if (target == null) return Offset(clampX(area.center.x - slip.width / 2), clampY(area.center.y - slip.height / 2))

    val x = clampX(target.center.x - slip.width / 2)
    val below = target.bottom + gap
    val above = target.top - gap - slip.height
    val fitsBelow = below + slip.height <= area.bottom
    val fitsAbove = above >= area.top
    val belowFirst = target.center.y < area.center.y
    when {
        belowFirst && fitsBelow -> return Offset(x, below)
        fitsAbove -> return Offset(x, above)
        fitsBelow -> return Offset(x, below)
    }
    val y = clampY(target.center.y - slip.height / 2)
    val left = target.left - gap - slip.width
    val right = target.right + gap
    return when {
        left >= area.left -> Offset(left, y)
        right + slip.width <= area.right -> Offset(right, y)
        else -> Offset(x, clampY(target.bottom - slip.height - gap / 2))
    }
}

/**
 * The marker around a spot: a hand-drawn oval around a small one (the key, the gear), a rounded frame around
 * a wide one (an oval would cut through the dividers' corners).
 */
private class Mark(val small: Boolean, val frame: Rect, val rx: Float, val ry: Float) {
    fun oval(scale: Float = 1f) = Rect(frame.center.x - rx * scale, frame.center.y - ry * scale, frame.center.x + rx * scale, frame.center.y + ry * scale)

    /** Everything the marker covers, its wobble included - the slip and the arrow keep out of it. */
    val bounds: Rect get() = if (small) oval(1.12f) else frame
}

/** The marker for [target]; a frame stays inside [screen], so all four sides are seen (the box is as wide as the screen). */
private fun Density.markFor(target: Rect, screen: Size): Mark {
    val hole = target.inflate(HOLE_PAD.toPx())
    if (max(hole.width, hole.height) > SMALL_SPOT.toPx()) {
        val edge = MARKER_WIDTH.toPx()
        return Mark(false, hole.inflate(3.dp.toPx()).intersect(Rect(edge, edge, screen.width - edge, screen.height - edge)), 0f, 0f)
    }
    val rx = hole.width / 2 + 2.dp.toPx()
    val ry = hole.height / 2 + 2.dp.toPx()
    // The gear sits in the corner: the oval moves in a little rather than run off the screen.
    val room = 1.12f
    val c = hole.center
    val x = c.x.coerceIn(rx * room, max(rx * room, screen.width - rx * room))
    val y = c.y.coerceIn(ry * room, max(ry * room, screen.height - ry * room))
    return Mark(true, hole.translate(x - c.x, y - c.y), rx, ry)
}

/** The marker's stroke: the frame, or an oval that overshoots its start, as drawn by hand. */
private fun Density.markerLoop(mark: Mark): Path {
    val path = Path()
    if (!mark.small) {
        path.addRoundRect(RoundRect(mark.frame, CornerRadius(12.dp.toPx())))
        return path
    }
    val rx = mark.rx
    val ry = mark.ry
    val c = mark.frame.center
    val start = -PI.toFloat() * 0.6f
    val turn = 2f * PI.toFloat() * 1.12f
    for (i in 0..LOOP_POINTS) {
        val t = i / LOOP_POINTS.toFloat()
        val a = start + turn * t
        // Wider at the end of the stroke, so the overshoot does not lie on the start.
        val wobble = 1f + 0.035f * sin(a * 3f) + 0.06f * t
        val p = Offset(c.x + cos(a) * rx * wobble, c.y + sin(a) * ry * wobble)
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    return path
}

private fun DrawScope.drawMarker(loop: Path, progress: Float, color: Color) {
    if (progress <= 0f) return
    val shown = if (progress >= 1f) {
        loop
    } else {
        val measure = PathMeasure().apply { setPath(loop, false) }
        Path().also { measure.getSegment(0f, measure.length * progress, it, true) }
    }
    drawPath(shown, color, style = Stroke(MARKER_WIDTH.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** A slightly bent pencil arrow from the slip's edge to the marked spot. */
private fun DrawScope.drawArrow(slip: Rect, spot: Rect, progress: Float, color: Color) {
    if (progress <= 0f || slip.isEmpty) return
    val aim = spot.center
    val from = Offset(aim.x.coerceIn(slip.left, slip.right), aim.y.coerceIn(slip.top, slip.bottom))
    var to = Offset(from.x.coerceIn(spot.left, spot.right), from.y.coerceIn(spot.top, spot.bottom))
    val length = hypot(to.x - from.x, to.y - from.y)
    if (length < 12.dp.toPx()) return
    // Stop a little short of the marker.
    val short = 6.dp.toPx() / length
    to = Offset(to.x - (to.x - from.x) * short, to.y - (to.y - from.y) * short)
    val bend = Offset(-(to.y - from.y) * 0.18f, (to.x - from.x) * 0.18f)
    val control = Offset((from.x + to.x) / 2 + bend.x, (from.y + to.y) / 2 + bend.y)
    val shaft = Path().apply {
        moveTo(from.x, from.y)
        quadraticTo(control.x, control.y, to.x, to.y)
    }
    val stroke = Stroke(ARROW_WIDTH.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val alpha = progress
    drawPath(shaft, color.copy(alpha = color.alpha * alpha), style = stroke)
    val angle = atan2(to.y - control.y, to.x - control.x)
    val head = 11.dp.toPx()
    for (side in listOf(-1f, 1f)) {
        val a = angle + PI.toFloat() + side * HEAD_ANGLE
        drawLine(
            color.copy(alpha = color.alpha * alpha),
            to,
            Offset(to.x + cos(a) * head, to.y + sin(a) * head),
            strokeWidth = ARROW_WIDTH.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

private const val DRAW_MS = 520
private const val UNDRAW_MS = 200
private const val SCRIM_ALPHA = 0.62f
private const val LOOP_POINTS = 72
private const val HEAD_ANGLE = 0.5f
private val HOLE_PAD = 6.dp
private val SMALL_SPOT = 140.dp
private val SLIP_MAX_WIDTH = 380.dp
private val ARROW_GAP = 56.dp
private val MARKER_WIDTH = 4.dp
private val ARROW_WIDTH = 2.5.dp
