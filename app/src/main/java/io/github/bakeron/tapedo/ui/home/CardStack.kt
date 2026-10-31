package io.github.bakeron.tapedo.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.bakeron.tapedo.R
import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.ui.cards.IndexCard
import io.github.bakeron.tapedo.ui.cards.ruledLines
import io.github.bakeron.tapedo.ui.components.PaperButton
import io.github.bakeron.tapedo.ui.theme.TapedoTheme

/**
 * The stack of saved cards, newest on top. Each lies a touch crooked (fixed per card, so it does not
 * wobble on scroll). Tap opens the card; list items and tasks tick off right here.
 */
@Composable
fun CardStack(
    stack: StackState,
    onOpen: (Note) -> Unit,
    onToggleItem: (Note, Int) -> Unit,
    onToggleDone: (Note) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    top: @Composable () -> Unit = {},
) {
    val reduceMotion = TapedoTheme.reduceMotion
    val openLabel = stringResource(R.string.stack_open_card)
    LazyColumn(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "top") { top() }
        when (stack) {
            StackState.Loading -> Unit
            StackState.Failed -> item(key = "failed") { StackFailed(onRetry) }
            is StackState.Loaded -> {
                if (stack.notes.isEmpty()) {
                    item(key = "empty") { EmptyCard() }
                } else {
                    items(stack.notes, key = { it.id }) { note ->
                        IndexCard(
                            note,
                            modifier = if (reduceMotion) Modifier else Modifier.animateItem(),
                            tilt = tiltFor(note.id),
                            onClick = { onOpen(note) },
                            onClickLabel = openLabel,
                            onToggleItem = { i -> onToggleItem(note, i) },
                            onToggleDone = { onToggleDone(note) },
                        )
                    }
                }
            }
        }
    }
}

/** -0.8° .. +0.8°, always the same for the same card. */
private fun tiltFor(id: Long): Float = ((id * 37 % 17) - 8) / 10f

/** An empty card with a dashed edge and the first hint written on its lines - not a grey paragraph. */
@Composable
private fun EmptyCard() {
    val colors = TapedoTheme.colors
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
            .rotate(-1.2f)
            .background(colors.card.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
            .drawBehind {
                drawRoundRect(
                    color = scheme.outline,
                    cornerRadius = CornerRadius(3.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                )
            }
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text(stringResource(R.string.stack_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.home_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp).ruledLines(colors.rule, 22.dp),
        )
    }
}

@Composable
private fun StackFailed(onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.stack_failed),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        PaperButton(stringResource(R.string.stack_retry), onClick = onRetry)
    }
}
