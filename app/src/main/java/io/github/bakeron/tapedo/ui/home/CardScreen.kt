package io.github.bakeron.tapedo.ui.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.bakeron.tapedo.R
import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.report.Problem
import io.github.bakeron.tapedo.ui.cards.CardOptions
import io.github.bakeron.tapedo.ui.cards.EditableCard
import io.github.bakeron.tapedo.ui.components.Fold
import io.github.bakeron.tapedo.ui.components.FoldValue
import io.github.bakeron.tapedo.ui.components.PaperButton
import io.github.bakeron.tapedo.ui.components.PaperButtonStyle
import io.github.bakeron.tapedo.ui.theme.TapedoTheme

/**
 * "Karteczka": one card on the whole screen, with everything in one place (author 13.11.2026 - reading here
 * and writing in a separate editor was chaos). From the top: the card itself, written on directly; the wide
 * "Add by voice" key with the live line; type, priority and "Share"; "Delete card" at the bottom of the screen,
 * away from the ticks (undo is offered on the stack). Every change is kept by itself - back just closes.
 */
@Composable
fun CardScreen(
    note: Note,
    number: Int?,
    recording: RecordingState,
    appending: Boolean,
    actionFailed: Boolean,
    askOnline: Boolean,
    onChange: (Note) -> Unit,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onStartAppend: () -> Unit,
    onStopAppend: () -> Unit,
    onMicDenied: () -> Unit,
    onOnlineOnce: () -> Unit,
    onOnlineAlways: () -> Unit,
    onDismissError: () -> Unit,
    onDismissActionFailed: () -> Unit,
    onReport: ((Problem) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val reduceMotion = TapedoTheme.reduceMotion
    val active = recording as? RecordingState.Active
    // "Back" while adding by voice works like the key: cancels "3, 2, 1", afterwards stops and adds what was said.
    // Only then does it go back.
    val counting = active != null && active.countdown > 0
    BackHandler(onBack = if (active != null) onStopAppend else onBack)

    // The screen must not dim or lock mid-sentence.
    val view = LocalView.current
    DisposableEffect(active != null) {
        view.keepScreenOn = active != null
        onDispose { view.keepScreenOn = false }
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onStartAppend() else onMicDenied()
    }
    val onRecordKey = {
        when {
            active != null -> onStopAppend()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> onStartAppend()
            else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val scroll = rememberScrollState()
    // Where the voice block lies in the scrolled column - a long card scrolls down to it when recording starts.
    val voice = remember { floatArrayOf(0f, 0f) } // top, bottom (px)
    val margin = with(LocalDensity.current) { 16.dp.toPx() }
    LaunchedEffect(active != null) {
        if (active == null) return@LaunchedEffect
        val viewport = scroll.viewportSize.toFloat()
        val target = when {
            voice[1] > scroll.value + viewport -> (voice[1] - viewport + margin).toInt()
            voice[0] < scroll.value -> (voice[0] - margin).toInt()
            else -> return@LaunchedEffect
        }.coerceIn(0, scroll.maxValue)
        if (reduceMotion) scroll.scrollTo(target) else scroll.animateScrollTo(target)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(scheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back))
            }
            Text(
                stringResource(R.string.editor_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }

        Box(Modifier.weight(1f)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FoldValue(recording as? RecordingState.Failed) { failed ->
                    ErrorNote(
                        failed.error, askOnline, onOnlineOnce, onOnlineAlways, onDismissError,
                        code = failed.code, onReport = onReport,
                    )
                }
                Fold(actionFailed) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            stringResource(R.string.stack_action_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TapedoTheme.colors.record,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                        )
                        PaperButton(stringResource(R.string.home_dismiss), onClick = onDismissActionFailed)
                    }
                }

                EditableCard(note, number, onChange)

                // Add by voice, right under the card: the live line and words while recording, a short hint otherwise.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .onPlaced {
                            voice[0] = it.positionInParent().y
                            voice[1] = voice[0] + it.size.height
                        },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (active != null) {
                        SpeakNowCue(active)
                        Seismograph(active.levels, active.sampleCount, SEISMOGRAPH_SAMPLES, penUp = !active.ready)
                        // After the countdown and before the microphone listens: "starting", so no words are
                        // said into a closed mic.
                        val status = if (active.countdown == 0 && !active.listening) {
                            stringResource(if (active.micOpened) R.string.record_wait else R.string.record_starting)
                        } else {
                            null
                        }
                        if (status != null) {
                            Text(
                                status,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = TapedoTheme.colors.record,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        if (active.text.isNotEmpty() || active.ready) Text(
                            active.text.ifEmpty { stringResource(R.string.record_listening) },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (active.text.isEmpty()) scheme.onSurfaceVariant else scheme.onSurface,
                            maxLines = 3,
                            modifier = if (active.text.isEmpty()) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
                        )
                    } else {
                        Text(
                            stringResource(
                                when {
                                    appending -> R.string.preview_adding
                                    note.type.hasItems -> R.string.preview_add_hint_list
                                    else -> R.string.preview_add_hint_text
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    PaperButton(
                        stringResource(
                            when {
                                // During "3, 2, 1" nothing is recorded yet - the key says plainly that it cancels.
                                counting -> R.string.record_cancel
                                active != null -> R.string.preview_add_stop
                                else -> R.string.preview_add_voice
                            },
                        ),
                        onClick = onRecordKey,
                        style = PaperButtonStyle.FILLED,
                        // A card whose first save failed has nothing in the database to add to yet.
                        enabled = !appending && note.id != 0L,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    )
                }

                CardOptions(note, onChange, Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            }
            // "3, 2, 1" in the middle, over the card - the same dial as on the main screen.
            val lastDigit = remember { intArrayOf(3) }
            if (counting) lastDigit[0] = active!!.countdown
            // The plain (not column) version: the dial floats over the card.
            androidx.compose.animation.AnimatedVisibility(
                visible = counting,
                modifier = Modifier.align(Alignment.Center),
                enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(220)),
                exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(220)),
            ) {
                CountdownDial(lastDigit[0], onCancel = onStopAppend)
            }
        }

        // At the bottom of the screen, where the black key used to be - far from the ticks.
        PaperButton(
            stringResource(R.string.editor_delete),
            onClick = onDelete,
            style = PaperButtonStyle.DANGER,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).heightIn(min = 48.dp),
        )
    }
}
