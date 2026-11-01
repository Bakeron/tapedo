package io.github.bakeron.tapedo.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bakeron.tapedo.R
import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.rules.ListTitles
import io.github.bakeron.tapedo.core.settings.OnlineSpeech
import io.github.bakeron.tapedo.core.speech.SpeechError
import io.github.bakeron.tapedo.ui.cards.NoteEditor
import io.github.bakeron.tapedo.ui.components.PaperButton
import io.github.bakeron.tapedo.ui.components.PaperButtonStyle
import io.github.bakeron.tapedo.ui.theme.TapedoTheme

/**
 * @param debugTranscript debug builds only: text passed with `adb shell am start ... --es debug_transcript "..."`
 * goes straight to the review window - the emulator cannot recognise speech.
 */
@Composable
fun HomeRoute(viewModel: HomeViewModel, onOpenSettings: () -> Unit, debugTranscript: String? = null) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val stack by viewModel.stack.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    val actionFailed by viewModel.actionFailed.collectAsStateWithLifecycle()
    val lockedNotes by viewModel.lockedNotes.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val appending by viewModel.appending.collectAsStateWithLifecycle()

    val listTitles = ListTitles(
        shopping = stringResource(R.string.default_shopping_title),
        checklist = stringResource(R.string.default_checklist_title),
    )
    SideEffect { viewModel.listTitles = listTitles }

    var debugUsed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(debugTranscript, settings != null) {
        // Wait for settings, so the chosen type and AI provider apply; use the text once.
        if (debugTranscript != null && settings != null && !debugUsed) {
            debugUsed = true
            viewModel.onTranscript(debugTranscript)
        }
    }

    val edit = editing
    val shown = preview
    when {
        edit != null -> NoteEditor(
            note = edit.note,
            saveFailed = actionFailed,
            onChange = viewModel::changeEditing,
            onDone = viewModel::finishEditing,
            onDelete = viewModel::deleteEditing,
        )

        recording is RecordingState.Structuring || recording is RecordingState.Review -> ReviewPanel(
            state = recording,
            onSave = viewModel::saveReview,
            onDiscard = viewModel::discardReview,
            onEdit = viewModel::editDraft,
            onRemove = viewModel::removeCard,
        )

        shown != null -> PreviewScreen(
            note = shown,
            recording = recording,
            appending = appending,
            actionFailed = actionFailed,
            askOnline = settings?.onlineSpeech == OnlineSpeech.ASK,
            onBack = viewModel::closePreview,
            onEdit = { viewModel.editSaved(shown) },
            onToggleItem = { i -> viewModel.toggleItem(shown, i) },
            onToggleDone = { viewModel.toggleDone(shown) },
            onStartAppend = { viewModel.startRecording(appendTo = shown.id) },
            onStopAppend = viewModel::stopRecording,
            onMicDenied = viewModel::micDenied,
            onOnlineOnce = { viewModel.startRecording(allowOnlineOnce = true, appendTo = shown.id) },
            onOnlineAlways = { viewModel.allowOnlineAlways(appendTo = shown.id) },
            onDismissError = viewModel::dismissError,
            onDismissActionFailed = viewModel::dismissActionFailed,
        )

        else -> HomeScreen(
            typeChoice = settings?.noteTypeChoice,
            recording = recording,
            stack = stack,
            onTypeChoice = viewModel::chooseType,
            onStartRecording = viewModel::startRecording,
            onStopRecording = viewModel::stopRecording,
            onMicDenied = viewModel::micDenied,
            onOpenSettings = onOpenSettings,
            askOnline = settings?.onlineSpeech == OnlineSpeech.ASK,
            onOnlineOnce = { viewModel.startRecording(allowOnlineOnce = true) },
            onOnlineAlways = viewModel::allowOnlineAlways,
            onDismissError = viewModel::dismissError,
            onOpenCard = viewModel::openPreview,
            onToggleItem = viewModel::toggleItem,
            onToggleDone = viewModel::toggleDone,
            onRetryStack = viewModel::retryStack,
            deleted = deleted,
            onUndoDelete = viewModel::undoDelete,
            actionFailed = actionFailed,
            onDismissActionFailed = viewModel::dismissActionFailed,
            lockedNotes = lockedNotes,
            onDismissLockedNotes = viewModel::dismissLockedNotes,
        )
    }
}

/**
 * Home: the card stack (or the live recording) in the middle; type bar and record key where the thumb is -
 * at the bottom in portrait, in a column on the right in landscape, so the middle never shrinks to nothing.
 */
@Composable
fun HomeScreen(
    typeChoice: NoteType?,
    recording: RecordingState,
    stack: StackState,
    onTypeChoice: (NoteType?) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onMicDenied: () -> Unit,
    onOpenSettings: () -> Unit,
    /** No offline language pack and the setting is "ask" - offer online recognition. */
    askOnline: Boolean = false,
    onOnlineOnce: () -> Unit = {},
    onOnlineAlways: () -> Unit = {},
    onDismissError: () -> Unit = {},
    onOpenCard: (Note) -> Unit = {},
    onToggleItem: (Note, Int) -> Unit = { _, _ -> },
    onToggleDone: (Note) -> Unit = {},
    onRetryStack: () -> Unit = {},
    deleted: Note? = null,
    onUndoDelete: () -> Unit = {},
    actionFailed: Boolean = false,
    onDismissActionFailed: () -> Unit = {},
    lockedNotes: Boolean = false,
    onDismissLockedNotes: () -> Unit = {},
) {
    val context = LocalContext.current
    val isRecording = recording is RecordingState.Active

    // The screen must not dim or lock mid-sentence.
    val view = LocalView.current
    DisposableEffect(isRecording) {
        view.keepScreenOn = isRecording
        onDispose { view.keepScreenOn = false }
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onStartRecording() else onMicDenied()
    }
    val onRecordKey = {
        when {
            isRecording -> onStopRecording()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> onStartRecording()
            else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val middle = @Composable { modifier: Modifier ->
        Box(modifier) {
            if (recording is RecordingState.Active) {
                ListeningPanel(recording, Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp))
            } else {
                CardStack(
                    stack = stack,
                    onOpen = onOpenCard,
                    onToggleItem = onToggleItem,
                    onToggleDone = onToggleDone,
                    onRetry = onRetryStack,
                    modifier = Modifier.fillMaxSize(),
                    top = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (recording is RecordingState.Failed) {
                                ErrorNote(recording.error, askOnline, onOnlineOnce, onOnlineAlways, onDismissError)
                            }
                            if (lockedNotes) LockedNotesNote(onDismissLockedNotes)
                            if (actionFailed) ActionFailedNote(onDismissActionFailed)
                        }
                    },
                )
                deleted?.let { UndoBar(it, onUndoDelete, Modifier.align(Alignment.BottomCenter)) }
            }
        }
    }
    val controls = @Composable { scope: ColumnScope ->
        with(scope) { RecordControls(typeChoice, recording, onTypeChoice, onRecordKey) }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val landscape = maxWidth > maxHeight
        val controlsWidth = maxWidth * 0.42f
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.home_open_settings))
                }
            }
            if (landscape) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    middle(Modifier.weight(1f).fillMaxHeight())
                    Column(
                        Modifier.width(controlsWidth).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Center,
                    ) { controls(this) }
                }
            } else {
                middle(Modifier.weight(1f).fillMaxWidth())
                controls(this)
            }
        }
    }
}

/** Type question, type tapes, the hint and the record key with its tape counter. */
@Composable
private fun ColumnScope.RecordControls(
    typeChoice: NoteType?,
    recording: RecordingState,
    onTypeChoice: (NoteType?) -> Unit,
    onRecordKey: () -> Unit,
) {
    val isRecording = recording is RecordingState.Active
    Text(
        stringResource(R.string.home_type_question),
        style = MaterialTheme.typography.titleMedium,
        // Air above: the question must not stick to the last card of the stack.
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp).semantics { heading() },
    )
    TypeTapeBar(selected = typeChoice, onSelect = onTypeChoice, modifier = Modifier.padding(vertical = 2.dp))
    Text(
        typeHint(typeChoice),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        modifier = Modifier.padding(horizontal = 20.dp).heightIn(min = 36.dp),
    )

    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val counter = (recording as? RecordingState.Active)?.let {
            stringResource(R.string.record_counter, clock(it.elapsedSeconds), clock(it.limitSeconds))
        }
        RecordButton(
            recording = isRecording,
            label = stringResource(if (isRecording) R.string.record_stop else R.string.record_start),
            stateText = (recording as? RecordingState.Active)?.let {
                stringResource(R.string.record_state, spokenDuration(it.elapsedSeconds), spokenDuration(it.limitSeconds))
            },
            onClick = onRecordKey,
        )
        Spacer(Modifier.height(8.dp))
        // Tape counter while recording, the key's name otherwise. The key already says both to screen readers.
        Text(
            text = counter ?: stringResource(R.string.record_start),
            style = if (isRecording) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.labelLarge,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/** While recording: the seismograph and the words as they are recognised. */
@Composable
private fun ListeningPanel(state: RecordingState.Active, modifier: Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Seismograph(state.levels, state.sampleCount, SEISMOGRAPH_SAMPLES)
        Spacer(Modifier.height(16.dp))
        Text(
            state.text.ifEmpty { stringResource(R.string.record_listening) },
            style = MaterialTheme.typography.bodyLarge,
            color = if (state.text.isEmpty()) muted else MaterialTheme.colorScheme.onSurface,
            // "Listening…" is announced; the growing text is not re-read on every word.
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .then(if (state.text.isEmpty()) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
        )
    }
}

/** A note pinned above the stack: red edge, message, actions. */
@Composable
private fun PinnedNote(content: @Composable ColumnScope.() -> Unit) {
    val colors = TapedoTheme.colors
    val shape = RoundedCornerShape(3.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.card, shape)
            .border(1.5.dp, colors.record, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
internal fun ErrorNote(
    error: SpeechError,
    askOnline: Boolean,
    onOnlineOnce: () -> Unit,
    onOnlineAlways: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val offlineAsk = error == SpeechError.OFFLINE_UNAVAILABLE && askOnline
    PinnedNote {
        Text(
            if (offlineAsk) stringResource(R.string.speech_error_offline_ask) else speechErrorText(error),
            style = MaterialTheme.typography.bodyMedium,
            // Read out at once - the focus is still on the record key.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
        // Online recognition only after a clear yes - for this recording or from now on.
        if (offlineAsk) {
            PaperButton(stringResource(R.string.speech_online_once), onClick = onOnlineOnce, modifier = Modifier.fillMaxWidth())
            PaperButton(stringResource(R.string.speech_online_always), onClick = onOnlineAlways, modifier = Modifier.fillMaxWidth())
        }
        if (error == SpeechError.NO_PERMISSION) {
            PaperButton(
                stringResource(R.string.mic_open_settings),
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        PaperButton(stringResource(R.string.home_dismiss), onClick = onDismiss)
    }
}

@Composable
private fun LockedNotesNote(onDismiss: () -> Unit) {
    PinnedNote {
        Text(stringResource(R.string.locked_notes_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.locked_notes_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        PaperButton(stringResource(R.string.locked_notes_ok), onClick = onDismiss)
    }
}

@Composable
private fun ActionFailedNote(onDismiss: () -> Unit) {
    PinnedNote {
        Text(
            stringResource(R.string.stack_action_failed),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
        PaperButton(stringResource(R.string.home_dismiss), onClick = onDismiss)
    }
}

/** "Card deleted - Undo", on a strip of tape at the bottom of the stack. */
@Composable
private fun UndoBar(note: Note, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .padding(12.dp)
            .fillMaxWidth()
            .background(scheme.primary, RoundedCornerShape(2.dp))
            .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.stack_deleted, note.title),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onPrimary,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        PaperButton(
            stringResource(R.string.stack_undo),
            onClick = onUndo,
            style = PaperButtonStyle.ON_INK,
        )
    }
}

@Composable
private fun speechErrorText(error: SpeechError): String = stringResource(
    when (error) {
        SpeechError.NO_PERMISSION -> R.string.speech_error_permission
        SpeechError.NOT_AVAILABLE -> R.string.speech_error_not_available
        SpeechError.NO_SPEECH -> R.string.speech_error_no_speech
        SpeechError.NETWORK -> R.string.speech_error_network
        SpeechError.BUSY -> R.string.speech_error_busy
        SpeechError.OFFLINE_UNAVAILABLE -> R.string.speech_error_offline_never
        SpeechError.OTHER -> R.string.speech_error_other
    },
)

/** 75 -> "1:15" - like a tape counter. */
private fun clock(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

/** 75 -> "1 min 15 s" - how a screen reader should say the counter. */
@Composable
private fun spokenDuration(seconds: Int): String {
    val min = seconds / 60
    val sec = seconds % 60
    return when {
        min == 0 -> stringResource(R.string.settings_seconds, sec)
        sec == 0 -> stringResource(R.string.settings_minutes, min)
        else -> stringResource(R.string.settings_minutes, min) + " " + stringResource(R.string.settings_seconds, sec)
    }
}

/** One line under the type bar saying what the chosen type does. */
@Composable
private fun typeHint(type: NoteType?): String = stringResource(
    when (type) {
        null -> R.string.type_hint_auto
        NoteType.NOTE -> R.string.type_hint_note
        NoteType.CHECKLIST -> R.string.type_hint_checklist
        NoteType.TASK -> R.string.type_hint_task
        NoteType.IDEA -> R.string.type_hint_idea
        NoteType.EVENT -> R.string.type_hint_event
        NoteType.SHOPPING -> R.string.type_hint_shopping
    },
)
