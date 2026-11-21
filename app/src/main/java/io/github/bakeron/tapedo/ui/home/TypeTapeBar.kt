package io.github.bakeron.tapedo.ui.home

import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.sp
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.ui.cards.TypeMark
import io.github.bakeron.tapedo.ui.settings.noteTypeName
import io.github.bakeron.tapedo.ui.theme.TapedoTheme

/**
 * The note-type picker above the record button: pieces of label-maker tape in a row you can swipe.
 * Loose pieces lie a little crooked on the desk; the chosen one is stuck on straight - dark,
 * with embossed letters and a lit LED. So the choice shows three ways, not by colour alone.
 *
 * `null` is "auto" - the rules / AI pick the type from the words. For screen readers it is a radio group.
 *
 * @param includeAuto false in the card editor - a saved card always has a concrete type.
 * @param bleed how far the row reaches out past its own bounds on both sides, up to the screen's edges - the
 *   tapes slide away there, not at the page margin (author 21.11.2026).
 * @param inset how far in from its own bounds the first and the last tape rest.
 */
@Composable
fun TypeTapeBar(
    selected: NoteType?,
    onSelect: (NoteType?) -> Unit,
    modifier: Modifier = Modifier,
    includeAuto: Boolean = true,
    bleed: Dp = 0.dp,
    inset: Dp = 16.dp,
) {
    val options: List<NoteType?> = (if (includeAuto) listOf(null) else emptyList()) + NoteType.entries
    val index = options.indexOf(selected).coerceAtLeast(0)
    val state = rememberLazyListState()
    val reduceMotion = TapedoTheme.reduceMotion
    // Keep the chosen tape in view - also when the saved choice arrives after the first frame.
    // One neighbour stays visible to its left, which shows there is more to swipe.
    LaunchedEffect(index) {
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        val fullyVisible = item != null && item.offset >= info.viewportStartOffset &&
            item.offset + item.size <= info.viewportEndOffset
        if (!fullyVisible) {
            val target = (index - 1).coerceAtLeast(0)
            if (reduceMotion) state.scrollToItem(target) else state.animateScrollToItem(target)
        }
    }

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val out = bleed.roundToPx()
                val wide = if (constraints.hasBoundedWidth) {
                    constraints.copy(minWidth = constraints.maxWidth + 2 * out, maxWidth = constraints.maxWidth + 2 * out)
                } else {
                    constraints
                }
                val placeable = measurable.measure(wide)
                layout(placeable.width - 2 * out, placeable.height) { placeable.place(-out, 0) }
            }
            .selectableGroup(),
        state = state,
        contentPadding = PaddingValues(horizontal = inset + bleed),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(options) { i, option ->
            TypeTape(
                type = option,
                text = noteTypeName(option),
                selected = option == selected,
                // Alternating small tilts - torn-off pieces, not a row of identical chips.
                tilt = if (i % 2 == 0) -1.6f else 1.2f,
                onClick = { onSelect(option) },
            )
        }
    }
}

@Composable
private fun TypeTape(type: NoteType?, text: String, selected: Boolean, tilt: Float, onClick: () -> Unit) {
    val colors = TapedoTheme.colors
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(1.dp)
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Picking a tape straightens it, inks it and lights its LED smoothly (author 21.11.2026).
        val chosen by animateFloatAsState(
            targetValue = if (selected) 1f else 0f,
            animationSpec = if (TapedoTheme.reduceMotion) snap() else tween(200),
            label = "tape chosen",
        )
        Row(
            Modifier
                .rotate(TapedoTheme.tilt(tilt) * (1f - chosen))
                .shadow(2.dp * chosen, shape)
                .background(lerp(colors.card, scheme.primary, chosen), shape)
                .border(1.dp, lerp(scheme.outline, scheme.primary, chosen), shape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            // LED in a bezel ring (paper-light on the dark tape, dark on amber) - lit on the chosen tape,
            // a dark socket on the others.
            Box(
                Modifier
                    .size(10.dp)
                    .border(1.5.dp, lerp(colors.controlEdge, scheme.onPrimary, chosen), CircleShape)
                    .padding(2.dp)
                    .background(lerp(colors.rule, colors.record, chosen), CircleShape),
            )
            val ink = lerp(scheme.onSurfaceVariant, scheme.onPrimary, chosen)
            // The same sign as on the strips in the card box ("auto" has none - it is not a kind).
            type?.let { TypeMark(it, ink, size = 15.dp) }
            // Embossed bold letters only on the stuck-on tape - laid over the plain ones and crossfaded.
            Box {
                Text(
                    text = text.uppercase(),
                    style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.6.sp),
                    fontWeight = FontWeight.Medium,
                    color = ink,
                    maxLines = 1,
                    modifier = Modifier.graphicsLayer { alpha = 1f - chosen },
                )
                Text(
                    text = text.uppercase(),
                    style = MaterialTheme.typography.labelLarge.copy(
                        letterSpacing = 1.6.sp,
                        shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 1.5f), 0f),
                    ),
                    fontWeight = FontWeight.Bold,
                    color = ink,
                    maxLines = 1,
                    modifier = Modifier.graphicsLayer { alpha = chosen }.clearAndSetSemantics { },
                )
            }
        }
    }
}
