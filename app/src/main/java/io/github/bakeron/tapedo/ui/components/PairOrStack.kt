package io.github.bakeron.tapedo.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Keys side by side, equally wide and equally tall; when a larger font makes any label too wide for its share,
 * all of them go one under the other at full width (author 14.11.2026).
 */
@Composable
fun PairOrStack(
    modifier: Modifier = Modifier,
    rowGap: Dp = 12.dp,
    stackGap: Dp = 18.dp,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        if (measurables.isEmpty()) return@Layout layout(constraints.minWidth, constraints.minHeight) {}
        val width = constraints.maxWidth
        val rowPx = rowGap.roundToPx()
        val share = (width - rowPx * (measurables.size - 1)) / measurables.size
        val sideBySide = constraints.hasBoundedWidth &&
            measurables.all { it.maxIntrinsicWidth(Constraints.Infinity) <= share }
        if (sideBySide) {
            val height = measurables.maxOf { it.maxIntrinsicHeight(share) }
            val placeables = measurables.map { it.measure(Constraints.fixed(share, height)) }
            layout(width, height) {
                placeables.forEachIndexed { i, p -> p.place(i * (share + rowPx), 0) }
            }
        } else {
            val full = if (constraints.hasBoundedWidth) width else measurables.maxOf { it.maxIntrinsicWidth(Constraints.Infinity) }
            val stackPx = stackGap.roundToPx()
            val placeables = measurables.map { it.measure(Constraints(minWidth = full, maxWidth = full)) }
            val height = placeables.sumOf { it.height } + stackPx * (placeables.size - 1)
            layout(full, height) {
                var y = 0
                placeables.forEach { p ->
                    p.place(0, y)
                    y += p.height + stackPx
                }
            }
        }
    }
}
