package io.github.bakeron.tapedo.ui.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
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
import io.github.bakeron.tapedo.ui.cards.IndexCard
import io.github.bakeron.tapedo.ui.components.PaperButton
import io.github.bakeron.tapedo.ui.components.PaperButtonStyle
import io.github.bakeron.tapedo.ui.theme.TapedoTheme

/**
 * One saved card on the whole screen - e.g. the shopping list in the shop, without the rest of the stack.
 * Items tick off here; "Add by voice" records more and adds it to this card; "Edit" opens the editor;
 * "Delete" (under the card, away from the ticks) goes back to the stack, where undo is offered.
 */
@Composable
fun PreviewScreen(
    note: Note,
    recording: RecordingState,
    appending: Boolean,
    actionFailed: Boolean,
    askOnline: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onToggleItem: (Int) -> Unit,
    onToggleDone: () -> Unit,
    onDelete: () -> Unit,
    onStartAppend: () -> Unit,
    onStopAppend: () -> Unit,
    onMicDenied: () -> Unit,
    onOnlineOnce: () -> Unit,
    onOnlineAlways: () -> Unit,
    onDismissError: () -> Unit,
    onDismissActionFailed: () -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val active = recording as? RecordingState.Active
    BackHandler(onBack = onBack)

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

    Column(
        Modifier
            .fillMaxSize()
            .background(scheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back))
            }
            Text(
                stringResource(R.string.preview_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            PaperButton(stringResource(R.string.review_edit), onClick = onEdit, modifier = Modifier.padding(end = 12.dp))
        }

        Box(Modifier.weight(1f)) {
            // Only this card, all of it - no other cards around it.
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (recording is RecordingState.Failed) {
                    ErrorNote(recording.error, askOnline, onOnlineOnce, onOnlineAlways, onDismissError)
                }
                if (actionFailed) {
                    Text(
                        stringResource(R.string.stack_action_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TapedoTheme.colors.record,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                    PaperButton(stringResource(R.string.home_dismiss), onClick = onDismissActionFailed)
                }
                IndexCard(
                    note,
                    compact = false,
                    onToggleItem = onToggleItem,
                    onToggleDone = onToggleDone,
                )
                PaperButton(stringResource(R.string.editor_delete), onClick = onDelete, style = PaperButtonStyle.DANGER)
            }
            // "3, 2, 1" in the middle, over the card - the same dial as on the main screen.
            if (active != null && active.countdown > 0) CountdownDial(active.countdown, Modifier.align(Alignment.Center))
        }

        // Add by voice: the live line and words while recording, a short hint otherwise.
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (active != null) {
                SpeakNowCue(active)
                Seismograph(active.levels, active.sampleCount, SEISMOGRAPH_SAMPLES, penUp = !active.ready)
                // After the countdown (the dial above) and before the microphone listens: "starting", so no
                // words are said into a closed mic.
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
                stringResource(if (active != null) R.string.preview_add_stop else R.string.preview_add_voice),
                onClick = onRecordKey,
                style = PaperButtonStyle.FILLED,
                enabled = !appending,
                // Dimmed during "3, 2, 1" like the record key - nothing is recorded yet; it still cancels.
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .alpha(if (active != null && active.countdown > 0) DIMMED_ALPHA else 1f),
            )
        }
    }
}
