package io.github.bakeron.tapedo.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.togetherWith
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.bakeron.tapedo.ui.theme.TapedoTheme

private const val FOLD_MS = 220

// Old out, then new in - 0.2 s together, never both on screen at once (author 21.11.2026).
private const val SWAP_OUT_MS = 100
private const val SWAP_IN_MS = 100

/** One thing fades out, then the next fades in on the same spot - at once with "reduce motion". */
fun fadeSwap(still: Boolean): ContentTransform =
    if (still) EnterTransition.None togetherWith ExitTransition.None
    else fadeIn(tween(SWAP_IN_MS, delayMillis = SWAP_OUT_MS)) togetherWith fadeOut(tween(SWAP_OUT_MS))

/** A text that changes (a key's name, a hint): the old words fade out, then the new ones fade in. */
@Composable
fun SwapText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    contentAlignment: Alignment = Alignment.Center,
) {
    val still = TapedoTheme.reduceMotion
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        transitionSpec = { fadeSwap(still).let { if (still) it using null else it } },
        contentAlignment = contentAlignment,
        label = "text swap",
    ) { shown -> Text(shown, style = style, color = color, textAlign = textAlign) }
}

/** Content that is one of a few looks ([target]): the old look fades out, then the new one fades in. */
@Composable
fun <T> Swap(
    target: T,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable (T) -> Unit,
) {
    val still = TapedoTheme.reduceMotion
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = { fadeSwap(still).let { if (still) it using null else it } },
        contentAlignment = contentAlignment,
        label = "swap",
    ) { content(it) }
}

/**
 * Content that unfolds downward and fades in, like a card pulled up out of the box - and folds back the
 * same way. With "reduce motion" it appears and disappears at once.
 */
@Composable
fun Fold(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val still = TapedoTheme.reduceMotion
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (still) EnterTransition.None
        else expandVertically(tween(FOLD_MS), expandFrom = Alignment.Top) + fadeIn(tween(FOLD_MS)),
        exit = if (still) ExitTransition.None
        else shrinkVertically(tween(FOLD_MS), shrinkTowards = Alignment.Top) + fadeOut(tween(FOLD_MS / 2)),
    ) { content() }
}

/**
 * [Fold] for a value that comes and goes (a warning, a "Saved" note): while it folds away the last value
 * stays on screen, so the text does not vanish before the motion ends.
 */
@Composable
fun <T : Any> FoldValue(value: T?, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val last = remember { arrayOfNulls<Any>(1) }
    if (value != null) last[0] = value
    @Suppress("UNCHECKED_CAST")
    val shown = (value ?: last[0]) as T?
    Fold(visible = value != null, modifier = modifier) { if (shown != null) content(shown) }
}

/** The ▸ that turns down to point at what is unfolded - turned, not swapped for another sign. */
@Composable
fun FoldArrow(open: Boolean, style: TextStyle, modifier: Modifier = Modifier) {
    val turn by animateFloatAsState(
        targetValue = if (open) 90f else 0f,
        animationSpec = if (TapedoTheme.reduceMotion) snap() else tween(FOLD_MS),
        label = "fold arrow",
    )
    Text("▸", style = style, modifier = modifier.graphicsLayer { rotationZ = turn }.clearAndSetSemantics { })
}

/** An underlined label with the turning arrow in front - a fold / unfold line. */
@Composable
fun FoldLabel(label: String, open: Boolean, style: TextStyle, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FoldArrow(open, style)
        // "Unfold" / "Fold": the old word fades out, then the new one in.
        SwapText(label, style = style.copy(textDecoration = TextDecoration.Underline), contentAlignment = Alignment.CenterStart)
    }
}

/**
 * Keeps a control where it is on screen while the layout around it changes (bigger text, another language):
 * the page scrolls by as much as the control moved, so it stays under the finger. Moves caused by the user's
 * own scrolling are left alone - only a change of [key] is evened out. No animation: it has to stand still.
 */
@Composable
fun Modifier.holdInPlace(scroll: ScrollState, key: Any): Modifier {
    // Plain holders, not state: they are read and written only in the positioning callback.
    val last = remember { arrayOfNulls<Any>(2) } // [0] = y in window, [1] = key at that y
    return onGloballyPositioned { coords ->
        val y = coords.positionInWindow().y
        val lastY = last[0] as Float?
        if (lastY != null && last[1] != key) {
            val moved = y - lastY
            if (moved != 0f) {
                scroll.dispatchRawDelta(moved)
                // The next positioning reports where the scroll put it; until then assume it stood still.
                last[0] = lastY
                last[1] = key
                return@onGloballyPositioned
            }
        }
        last[0] = y
        last[1] = key
    }
}
