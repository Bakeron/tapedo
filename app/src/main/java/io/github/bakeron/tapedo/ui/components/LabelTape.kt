package io.github.bakeron.tapedo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bakeron.tapedo.ui.theme.TapedoTheme

/**
 * Section heading styled like embossed label-maker tape stuck on the recorder:
 * a dark strip with raised light capitals, stuck on slightly crooked.
 */
@Composable
fun LabelTape(text: String, modifier: Modifier = Modifier, tilt: Float = -1.2f) {
    val strip = MaterialTheme.colorScheme.primary
    val letters = MaterialTheme.colorScheme.onPrimary
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelLarge.copy(
            letterSpacing = 2.4.sp,
            // A faint highlight under each letter reads as embossing.
            shadow = Shadow(color = Color.Black.copy(alpha = 0.35f), offset = Offset(0f, 1.5f), blurRadius = 0f),
        ),
        color = letters,
        modifier = modifier
            .semantics { heading() }
            .rotate(TapedoTheme.tilt(tilt))
            .background(strip, RoundedCornerShape(1.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}
