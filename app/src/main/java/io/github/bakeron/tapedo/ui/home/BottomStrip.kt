package io.github.bakeron.tapedo.ui.home

import io.github.bakeron.tapedo.ui.components.Fold
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.bakeron.tapedo.R
import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.ui.components.PaperButton
import io.github.bakeron.tapedo.ui.components.PaperButtonStyle
import io.github.bakeron.tapedo.ui.theme.TapedoTheme
import kotlinx.coroutines.delay
import kotlin.math.ceil

private const val STRIP_IN_MS = 240
private const val STRIP_OUT_MS = 200

/** The rivet's touch area - also the room left around the strip, so the half that sticks out stays tappable. */
private val RIVET_TOUCH = 44.dp

/**
 * The strip of tape at the bottom of the stack: "Card deleted - Undo" or "The card is now under Done - Show".
 * It slides up out of the recorder and back down, and one strip turns into the other by fading - never
 * on / off. A fresh delete wins the strip: its undo matters more than where a ticked card went.
 */
@Composable
internal fun BottomStrip(
    deleted: Note?,
    moved: Moved?,
    tab: StackTab,
    durationMs: Long,
    onUndo: () -> Unit,
    onDismissDeleted: () -> Unit,
    onShowMoved: (StackTab) -> Unit,
    onDismissMoved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val still = TapedoTheme.reduceMotion
    val strip: Any? = deleted ?: moved
    AnimatedContent(
        targetState = strip,
        modifier = modifier,
        contentKey = {
            when (it) {
                is Note -> "deleted-${it.id}"
                is Moved -> "moved-${it.note.id}-${it.to}"
                else -> "none"
            }
        },
        transitionSpec = {
            if (still) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                // One strip after another: the old one leaves first, so the two never show at once.
                val wait = if (initialState != null && targetState != null) STRIP_OUT_MS else 0
                (slideInVertically(tween(STRIP_IN_MS, delayMillis = wait)) { it / 2 } + fadeIn(tween(STRIP_IN_MS, delayMillis = wait))) togetherWith
                    (slideOutVertically(tween(STRIP_OUT_MS)) { it / 2 } + fadeOut(tween(STRIP_OUT_MS)))
            }
        },
        label = "bottom strip",
    ) { shown ->
        when (shown) {
            is Note -> TapeStrip(
                key = "deleted-${shown.id}",
                durationMs = durationMs,
                text = stringResource(R.string.stack_deleted, shown.title),
                onClose = onDismissDeleted,
            ) {
                PaperButton(stringResource(R.string.stack_undo), onClick = onUndo, style = PaperButtonStyle.ON_INK)
            }
            is Moved -> TapeStrip(
                key = "moved-${shown.note.id}-${shown.to}",
                durationMs = durationMs,
                text = stringResource(R.string.stack_moved, stringResource(tabNameRes(shown.to))),
                onClose = onDismissMoved,
            ) {
                // Gone smoothly once its divider is open.
                Fold(shown.to != tab) {
                    PaperButton(
                        stringResource(R.string.stack_moved_show),
                        onClick = { onShowMoved(shown.to) },
                        style = PaperButtonStyle.ON_INK,
                    )
                }
            }
            // Nothing to say - an empty spot the next strip slides into.
            else -> Box(Modifier.fillMaxWidth())
        }
    }
}

/**
 * One strip: message and action on the ink tape, the time line at its foot running out from right to left,
 * and a round rivet with "x" pinned half over the top right corner to close it at once.
 */
@Composable
private fun TapeStrip(
    key: String,
    durationMs: Long,
    text: String,
    onClose: () -> Unit,
    action: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val half = RIVET_TOUCH / 2
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .padding(start = half, end = half, top = half, bottom = 12.dp)
                .fillMaxWidth()
                .background(scheme.primary, RoundedCornerShape(2.dp)),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 14.dp, top = 6.dp, bottom = 4.dp)
                    .heightIn(min = 48.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onPrimary,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                action()
            }
            TimeLine(key, durationMs)
        }
        Rivet(onClose, Modifier.align(Alignment.TopEnd))
    }
}

/**
 * How much time is left before the strip goes by itself: a thin line that shrinks from the right.
 * Counted from when the strip first showed (kept across a screen turn), so it ends together with the strip.
 * With "reduce motion" it does not glide - it steps once a second, so the time left is still there to see.
 */
@Composable
private fun TimeLine(key: String, durationMs: Long) {
    val start = rememberSaveable(key) { SystemClock.elapsedRealtime() }
    fun left(): Float = (1f - (SystemClock.elapsedRealtime() - start).toFloat() / durationMs).coerceIn(0f, 1f)
    val still = TapedoTheme.reduceMotion
    val fraction = remember(key) { Animatable(left()) }
    LaunchedEffect(key, still, durationMs) {
        if (still) {
            while (fraction.value > 0f) {
                val seconds = ceil(left() * durationMs / 1000f)
                fraction.snapTo((seconds * 1000f / durationMs).coerceIn(0f, 1f))
                delay(1000L - (SystemClock.elapsedRealtime() - start) % 1000L)
            }
        } else {
            fraction.snapTo(left())
            val rest = (fraction.value * durationMs).toInt()
            fraction.animateTo(0f, tween(rest, easing = LinearEasing))
        }
    }
    val color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
    Canvas(Modifier.fillMaxWidth().height(3.dp).clearAndSetSemantics { }) {
        drawRect(color, size = Size(size.width * fraction.value, size.height))
    }
}

/** The round "x" rivet: a small paper disc with a ring, its centre on the strip's corner. */
@Composable
private fun Rivet(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = TapedoTheme.colors
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(R.string.strip_close)
    Box(
        modifier
            .size(RIVET_TOUCH)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val r = size.minDimension / 2
            drawCircle(colors.card, r)
            drawCircle(scheme.primary, r - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            val arm = r * 0.42f
            val c = center
            val w = 2.dp.toPx()
            drawLine(scheme.onSurface, Offset(c.x - arm, c.y - arm), Offset(c.x + arm, c.y + arm), w, StrokeCap.Round)
            drawLine(scheme.onSurface, Offset(c.x - arm, c.y + arm), Offset(c.x + arm, c.y - arm), w, StrokeCap.Round)
        }
    }
}
