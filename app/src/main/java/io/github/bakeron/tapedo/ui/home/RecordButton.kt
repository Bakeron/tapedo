package io.github.bakeron.tapedo.ui.home

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.bakeron.tapedo.ui.theme.TapedoTheme

/**
 * The big record key: a red dome in a recessed bezel, like on a pocket recorder.
 * It sinks when pressed and stays down (latched) while recording, showing a square "stop" mark -
 * the state shows by position and shape, not by colour alone. The phone gives a short buzz on each press.
 */
@Composable
fun RecordButton(
    recording: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Spoken after the label, e.g. "recording, 12 s of 1 min". */
    stateText: String? = null,
) {
    val colors = TapedoTheme.colors
    val scheme = MaterialTheme.colorScheme
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Keyboard / switch access: a thick ring around the bezel shows where the focus is.
    val focused by interaction.collectIsFocusedAsState()
    val down = pressed || recording
    val reduceMotion = TapedoTheme.reduceMotion

    val elevation by animateDpAsState(
        targetValue = if (down) 1.dp else 7.dp,
        animationSpec = if (reduceMotion) snap() else spring(),
        label = "keyElevation",
    )
    val sink by animateFloatAsState(
        targetValue = if (pressed) 0.92f else if (recording) 0.95f else 1f,
        animationSpec = if (reduceMotion) snap() else spring(),
        label = "keySink",
    )

    // The square "stop" mark grows in on the latched key and shrinks away when it pops up.
    val stopMark by animateFloatAsState(
        targetValue = if (recording) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(180),
        label = "stopMark",
    )

    // Bezel: a recessed ring the key sits in.
    Box(
        modifier
            .size(112.dp)
            .background(scheme.surfaceContainerHighest, CircleShape)
            .border(if (focused) 3.dp else 1.dp, if (focused) scheme.primary else colors.controlEdge, CircleShape)
            .semantics {
                contentDescription = label
                if (stateText != null) stateDescription = stateText
            }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                view.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                    else HapticFeedbackConstants.VIRTUAL_KEY,
                )
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(84.dp)
                .scale(sink)
                .shadow(elevation, CircleShape)
                .background(colors.record, CircleShape)
                // A glint in the upper part of the dome - on the dark panel the shadow is invisible,
                // so the light shows it is a raised key. Dimmer while the key is held down.
                .drawBehind {
                    val glint = Color.White.copy(alpha = if (down) 0.1f else 0.22f)
                    drawArc(
                        color = glint,
                        startAngle = 215f,
                        sweepAngle = 80f,
                        useCenter = false,
                        topLeft = Offset(size.width * 0.18f, size.height * 0.14f),
                        size = Size(size.width * 0.64f, size.height * 0.56f),
                        style = Stroke(width = size.width * 0.06f, cap = StrokeCap.Round),
                    )
                }
                .border(2.dp, colors.card.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(26.dp)
                    .graphicsLayer {
                        alpha = stopMark
                        scaleX = 0.6f + 0.4f * stopMark
                        scaleY = 0.6f + 0.4f * stopMark
                    }
                    .background(colors.card, RoundedCornerShape(4.dp)),
            )
        }
    }
}
