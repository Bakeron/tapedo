package io.github.bakeron.tapedo.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bakeron.tapedo.ui.theme.TapedoTheme

/**
 * A small rubber stamp in office-blue ink, pressed on slightly crooked and a bit faded.
 * Used for "free plan" on AI providers and later for "AI" on cards it structured - instead of sparkles.
 */
@Composable
fun RubberStamp(text: String, modifier: Modifier = Modifier, tilt: Float = -4f) {
    val ink = TapedoTheme.colors.stamp
    Text(
        text = text.uppercase(),
        color = ink,
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.6.sp),
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .rotate(TapedoTheme.tilt(tilt))
            .alpha(0.9f)
            .border(1.5.dp, ink, RoundedCornerShape(3.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}
