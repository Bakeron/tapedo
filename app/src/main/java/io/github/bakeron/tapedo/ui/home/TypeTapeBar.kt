package io.github.bakeron.tapedo.ui.home

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
 *   tapes slide away there, not at the page margin (author 21.11.2026); they rest where they did before.
 */
@Composable
fun TypeTapeBar(
    selected: NoteType?,
    onSelect: (NoteType?) -> Unit,
    modifier: Modifier = Modifier,
    includeAuto: Boolean = true,
    bleed: Dp = 0.dp,
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
        contentPadding = PaddingValues(horizontal = 16.dp + bleed),
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
        Row(
            Modifier
                .rotate(if (selected) 0f else TapedoTheme.tilt(tilt))
                .then(if (selected) Modifier.shadow(2.dp, shape) else Modifier)
                .background(if (selected) scheme.primary else colors.card, shape)
                .border(1.dp, if (selected) scheme.primary else scheme.outline, shape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            // LED in a bezel ring (paper-light on the dark tape, dark on amber) - lit on the chosen tape,
            // a dark socket on the others.
            Box(
                Modifier
                    .size(10.dp)
                    .border(1.5.dp, if (selected) scheme.onPrimary else colors.controlEdge, CircleShape)
                    .padding(2.dp)
                    .background(if (selected) colors.record else colors.rule, CircleShape),
            )
            val ink = if (selected) scheme.onPrimary else scheme.onSurfaceVariant
            // The same sign as on the strips in the card box ("auto" has none - it is not a kind).
            type?.let { TypeMark(it, ink, size = 15.dp) }
            Text(
                text = text.uppercase(),
                style = MaterialTheme.typography.labelLarge.copy(
                    letterSpacing = 1.6.sp,
                    // Embossed letters only on the stuck-on tape.
                    shadow = if (selected) Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 1.5f), 0f) else null,
                ),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = ink,
                maxLines = 1,
            )
        }
    }
}
