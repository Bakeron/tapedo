package io.github.bakeron.tapedo.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bakeron.tapedo.R
import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.report.Problem
import io.github.bakeron.tapedo.core.rules.ListTitles
import io.github.bakeron.tapedo.core.settings.AppSettings
import io.github.bakeron.tapedo.core.settings.OnlineSpeech
import io.github.bakeron.tapedo.core.settings.RecordingEnd
import io.github.bakeron.tapedo.core.speech.SpeechError
import io.github.bakeron.tapedo.report.ReportMail
import io.github.bakeron.tapedo.ui.cards.kindNumbers
import io.github.bakeron.tapedo.ui.cards.TypeMark
import io.github.bakeron.tapedo.ui.components.Fold
import io.github.bakeron.tapedo.ui.components.FoldValue
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
    val moved by viewModel.moved.collectAsStateWithLifecycle()
    val actionFailed by viewModel.actionFailed.collectAsStateWithLifecycle()
    val lockedNotes by viewModel.lockedNotes.collectAsStateWithLifecycle()
    val appending by viewModel.appending.collectAsStateWithLifecycle()
    val ownAudioRefused by viewModel.ownAudioRefused.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // No address in this build (e.g. built from GitHub without local.properties) - no report button.
    val report: ((Problem) -> Unit)? =
        if (ReportMail.available) { problem -> ReportMail.send(context, problem, settings?.speechLanguage.orEmpty()) } else null

    val listTitles = ListTitles(
        shopping = stringResource(R.string.default_shopping_title),
        checklist = stringResource(R.string.default_checklist_title),
    )
    SideEffect { viewModel.listTitles = listTitles }
    // A screen reader user may have asked Android for more time to act on messages (Accessibility settings).
    val stripMs = remember(context) {
        val a11y = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && a11y != null) {
            a11y.getRecommendedTimeoutMillis(
                UNDO_MS.toInt(),
                android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT or
                    android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS,
            ).toLong()
        } else {
            UNDO_MS
        }
    }
    SideEffect { viewModel.stripMs = stripMs }
    // Screen off or another app: write what is open now - the phone may close Tapedo in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackground() }

    // The open divider is the kind of the next recording (kept in the settings); "Done" only shows the done
    // cards - a recording started there goes to the last kind, which opens again.
    var onDone by rememberSaveable { mutableStateOf(false) }
    val kind = settings?.noteTypeChoice ?: NoteType.NOTE
    val tab = if (onDone) StackTab.DONE else StackTab.of(kind)

    var debugUsed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(debugTranscript, settings != null) {
        // Wait for settings, so the chosen type and AI provider apply; use the text once.
        if (debugTranscript != null && settings != null && !debugUsed) {
            debugUsed = true
            viewModel.onTranscript(debugTranscript)
        }
    }

    // The card view, the window after recording and the box fade into one another (author 11.11.2026:
    // nothing in the app may just blink). The screen fading out keeps what it showed until it is gone.
    val screen = when {
        editing != null -> HomeShown.CARD
        recording is RecordingState.Structuring || recording is RecordingState.Review -> HomeShown.REVIEW
        else -> HomeShown.BOX
    }
    val last = remember { arrayOfNulls<Any>(2) } // [0] the open card, [1] review state
    editing?.let { last[0] = it }
    if (screen == HomeShown.REVIEW) last[1] = recording
    val stillNow = TapedoTheme.reduceMotion

    // First steps: on the first start (and when asked for again in Settings), over the card box only.
    // Closed at once on screen; the setting is written behind - its change also clears "closed" for next time.
    val tourSpots = remember { TourSpots() }
    var tourStep by rememberSaveable { mutableIntStateOf(0) }
    // The search and the place in the box outlive opening a card and recording - both swap the box out.
    var stackQuery by rememberSaveable { mutableStateOf("") }
    val stackScroll = rememberLazyListState()
    var tourClosed by rememberSaveable(settings?.firstStepsSeen) { mutableStateOf(false) }
    val touring = settings?.firstStepsSeen == false && !tourClosed &&
        screen == HomeShown.BOX && recording !is RecordingState.Active
    val finishTour = {
        tourClosed = true
        tourStep = 0
        viewModel.finishFirstSteps()
    }

    Box(Modifier.fillMaxSize()) {
    CompositionLocalProvider(LocalTourSpots provides tourSpots) {
    AnimatedContent(
        targetState = screen,
        // Under the tips the screen is out of a screen reader's reach - only the slip is read.
        // Nor can the keyboard focus wander there.
        modifier = if (touring) {
            Modifier
                .clearAndSetSemantics { }
                .focusProperties { onEnter = { cancelFocusChange() } }
                .focusGroup()
        } else {
            Modifier
        },
        transitionSpec = { fadeSwap(stillNow) },
        label = "home screens",
    ) { target ->
    val edit = editing ?: last[0] as Editing?
    val reviewState = if (screen == HomeShown.REVIEW) recording else last[1] as RecordingState? ?: recording
    when {
        target == HomeShown.CARD && edit != null -> CardScreen(
            note = edit.note,
            number = (stack as? StackState.Loaded)?.notes?.let { kindNumbers(it)[edit.note.id] },
            // Words being added belong to this card; the review window put aside meanwhile is not shown here.
            recording = if (recording is RecordingState.Review) RecordingState.Idle else recording,
            appending = appending,
            actionFailed = actionFailed,
            askOnline = settings?.onlineSpeech == OnlineSpeech.ASK,
            onChange = viewModel::changeEditing,
            onBack = viewModel::finishEditing,
            onDelete = viewModel::deleteEditing,
            onStartAppend = { viewModel.startRecording(appendTo = edit.note.id) },
            onStopAppend = viewModel::stopRecording,
            onMicDenied = viewModel::micDenied,
            onOnlineOnce = { viewModel.startRecording(allowOnlineOnce = true, appendTo = edit.note.id) },
            onOnlineAlways = { viewModel.allowOnlineAlways(appendTo = edit.note.id) },
            onDismissError = viewModel::dismissError,
            onDismissActionFailed = viewModel::dismissActionFailed,
            onReport = report,
        )

        target == HomeShown.REVIEW -> ReviewPanel(
            state = reviewState,
            onSave = viewModel::saveReview,
            onDiscard = viewModel::discardReview,
            onEdit = viewModel::editDraft,
            onRemove = viewModel::removeCard,
            onRecordMore = { viewModel.recordMore() },
            onRecordMoreDenied = viewModel::recordMoreDenied,
            onRefine = viewModel::refine,
            onAcceptRefined = viewModel::acceptRefined,
            onKeepCards = viewModel::keepCards,
        )

        else -> HomeScreen(
            tab = tab,
            recording = recording,
            stack = stack,
            onSelectTab = { t ->
                onDone = t == StackTab.DONE
                t.type?.let(viewModel::chooseType)
            },
            kind = kind,
            onStartRecording = {
                onDone = false
                viewModel.startRecording()
            },
            onStopRecording = viewModel::stopRecording,
            onMicDenied = viewModel::micDenied,
            onOpenSettings = onOpenSettings,
            askOnline = settings?.onlineSpeech == OnlineSpeech.ASK,
            onOnlineOnce = { viewModel.startRecording(allowOnlineOnce = true) },
            onOnlineAlways = viewModel::allowOnlineAlways,
            onDismissError = viewModel::dismissError,
            onOpenCard = viewModel::openCard,
            onToggleDone = viewModel::toggleDone,
            onDeleteCard = viewModel::delete,
            onRetryStack = viewModel::retryStack,
            deleted = deleted,
            onUndoDelete = viewModel::undoDelete,
            onDismissDeleted = viewModel::dismissDeleted,
            moved = moved,
            onShowMoved = { t ->
                onDone = t == StackTab.DONE
                t.type?.let(viewModel::chooseType)
                viewModel.dismissMoved()
            },
            onDismissMoved = viewModel::dismissMoved,
            stripMs = stripMs,
            actionFailed = actionFailed,
            onDismissActionFailed = viewModel::dismissActionFailed,
            lockedNotes = lockedNotes,
            onDismissLockedNotes = viewModel::dismissLockedNotes,
            ownAudioRefused = ownAudioRefused,
            onDismissOwnAudioRefused = viewModel::dismissOwnAudioRefused,
            onReport = report,
            pauseSeconds = settings?.pauseSeconds ?: AppSettings().pauseSeconds,
            query = stackQuery,
            onQueryChange = { stackQuery = it },
            listState = stackScroll,
        )
    }
    }
    }
    if (touring) {
        FirstStepsOverlay(
            step = tourStep,
            spots = tourSpots,
            onNext = { if (tourStep == FirstStep.entries.lastIndex) finishTour() else tourStep++ },
            onBack = { if (tourStep == 0) finishTour() else tourStep-- },
            onSkip = finishTour,
            endsOnStop = settings?.recordingEnd == RecordingEnd.STOP_BUTTON,
        )
    }
    }
}

private enum class HomeShown { BOX, REVIEW, CARD }

/**
 * Home: the card box (or the live recording) in the middle - its open divider is where the next recording
 * goes; the record key where the thumb is - at the bottom in portrait, in a column on the right in landscape,
 * so the middle never shrinks to nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    tab: StackTab,
    recording: RecordingState,
    stack: StackState,
    onSelectTab: (StackTab) -> Unit,
    /** The kind the next recording becomes - also while "Done" is open. */
    kind: NoteType,
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
    onToggleDone: (Note) -> Unit = {},
    onDeleteCard: (Note) -> Unit = {},
    onRetryStack: () -> Unit = {},
    deleted: Note? = null,
    onUndoDelete: () -> Unit = {},
    onDismissDeleted: () -> Unit = {},
    moved: Moved? = null,
    onShowMoved: (StackTab) -> Unit = {},
    onDismissMoved: () -> Unit = {},
    /** How long the bottom strip stays, for its time line. */
    stripMs: Long = UNDO_MS,
    actionFailed: Boolean = false,
    onDismissActionFailed: () -> Unit = {},
    lockedNotes: Boolean = false,
    onDismissLockedNotes: () -> Unit = {},
    /** The recognizer's error number when it refused Tapedo's own recording - null: nothing to tell. */
    ownAudioRefused: Int? = null,
    onDismissOwnAudioRefused: () -> Unit = {},
    /** Opens the user's mail app with an error report for the app's author; null = no report button. */
    onReport: ((Problem) -> Unit)? = null,
    /** Seconds of silence that start a new list item (Settings); 0 = never. */
    pauseSeconds: Int = 2,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
) {
    val context = LocalContext.current
    val isRecording = recording is RecordingState.Active

    // The screen must not dim or lock mid-sentence.
    val view = LocalView.current
    DisposableEffect(isRecording) {
        view.keepScreenOn = isRecording
        onDispose { view.keepScreenOn = false }
    }

    // "Back" never closes the app mid-recording (author 11.11.2026): during "3, 2, 1" it cancels (nothing is
    // recorded yet), afterwards it works like "Stop" - the card is made from what was said.
    BackHandler(enabled = isRecording, onBack = onStopRecording)
    // An error note on the box is the last thing that happened - "Back" closes it before leaving the app.
    BackHandler(enabled = recording is RecordingState.Failed, onBack = onDismissError)

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
        // The box, the countdown and the live recording fade into one another - nothing just blinks in.
        val mode = when {
            recording is RecordingState.Active && recording.countdown > 0 -> Middle.COUNTDOWN
            recording is RecordingState.Active -> Middle.LISTENING
            else -> Middle.STACK
        }
        // The last digit stays on the dial while it fades out.
        val lastDigit = remember { intArrayOf(3) }
        (recording as? RecordingState.Active)?.countdown?.takeIf { it > 0 }?.let { lastDigit[0] = it }
        val stillNow = TapedoTheme.reduceMotion
        AnimatedContent(
            targetState = mode,
            modifier = modifier,
            transitionSpec = { fadeSwap(stillNow) },
            label = "home middle",
        ) { shownMode ->
          Box(Modifier.fillMaxSize()) {
            if (shownMode == Middle.COUNTDOWN) {
                // Nothing else in the middle - the digit stays in one place, the eye has nothing else to read.
                CountdownDial(lastDigit[0], onCancel = onStopRecording, modifier = Modifier.align(Alignment.Center))
            } else if (shownMode == Middle.LISTENING) {
                (recording as? RecordingState.Active)?.let {
                    ListeningPanel(it, Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp))
                }
            } else {
                CardStack(
                    stack = stack,
                    tab = tab,
                    onSelectTab = onSelectTab,
                    onOpen = onOpenCard,
                    onToggleDone = onToggleDone,
                    onRetry = onRetryStack,
                    modifier = Modifier.fillMaxSize(),
                    onDelete = onDeleteCard,
                    query = query,
                    onQueryChange = onQueryChange,
                    listState = listState,
                    top = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            FoldValue(recording as? RecordingState.Failed) { failed ->
                                ErrorNote(
                                    failed.error, askOnline, onOnlineOnce, onOnlineAlways, onDismissError,
                                    code = failed.code, onReport = onReport,
                                )
                            }
                            FoldValue(ownAudioRefused) { OwnAudioRefusedNote(it, onReport, onDismissOwnAudioRefused) }
                            Fold(lockedNotes) { LockedNotesNote(onDismissLockedNotes) }
                            Fold(actionFailed) { ActionFailedNote(onDismissActionFailed) }
                        }
                    },
                )
                BottomStrip(
                    deleted = deleted,
                    moved = moved,
                    tab = tab,
                    durationMs = stripMs,
                    onUndo = onUndoDelete,
                    onDismissDeleted = onDismissDeleted,
                    onShowMoved = onShowMoved,
                    onDismissMoved = onDismissMoved,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
          }
        }
    }
    val controls = @Composable { scope: ColumnScope ->
        with(scope) { RecordControls(recording, kind, pauseSeconds, onRecordKey) }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val landscape = maxWidth > maxHeight
        val controlsWidth = maxWidth * 0.42f
        // While typing in the search line the record controls step aside, so the found cards have room.
        val typing = WindowInsets.isImeVisible
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Nameplate(stringResource(R.string.app_name))
                IconButton(onClick = onOpenSettings, modifier = Modifier.tourSpot(TourSpot.SETTINGS)) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.home_open_settings))
                }
            }
            if (landscape) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    middle(Modifier.weight(1f).fillMaxHeight())
                    if (!typing) {
                        Column(
                            Modifier.width(controlsWidth).fillMaxHeight().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center,
                        ) { controls(this) }
                    }
                }
            } else {
                middle(Modifier.weight(1f).fillMaxWidth())
                if (!typing) controls(this)
            }
        }
    }
}

/** The pause rule and the record key with its tape counter. */
@Composable
private fun ColumnScope.RecordControls(
    recording: RecordingState,
    kind: NoteType,
    pauseSeconds: Int,
    onRecordKey: () -> Unit,
) {
    val isRecording = recording is RecordingState.Active
    // Where the recording goes, said before it starts (author 10.11.2026: a recording made on "Done" landed
    // on another divider without a word) - with the divider's own sign.
    Fold(!isRecording, Modifier.align(Alignment.CenterHorizontally)) {
        val into = stringResource(R.string.home_record_into, stringResource(tabNameRes(StackTab.of(kind))))
        Row(
            Modifier
                .padding(start = 20.dp, end = 20.dp, top = 14.dp)
                .semantics(mergeDescendants = true) { contentDescription = into },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TypeMark(kind, MaterialTheme.colorScheme.onSurface, size = 16.dp)
            Text(
                into,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    // The pause rule (author 08.11.2026): said up front, so a list in one item is no surprise.
    // Air above: it must not stick to the last card of the box.
    Text(
        if (pauseSeconds > 0) stringResource(R.string.home_pause_rule, pauseSeconds) else stringResource(R.string.home_pause_off),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.align(Alignment.CenterHorizontally).padding(start = 20.dp, end = 20.dp, top = if (isRecording) 14.dp else 4.dp),
    )

    // During "3, 2, 1" nothing is recorded yet: the key says plainly that it cancels (author 11.11.2026 - a dimmed
    // key looked switched off, and "Record" pressed by mistake had no visible way out).
    val waiting = (recording as? RecordingState.Active)?.countdown?.let { it > 0 } == true
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val counter = (recording as? RecordingState.Active)?.let {
            stringResource(R.string.record_counter, clock(it.elapsedSeconds), clock(it.limitSeconds))
        }
        RecordButton(
            recording = isRecording,
            label = stringResource(
                when {
                    waiting -> R.string.record_cancel
                    isRecording -> R.string.record_stop
                    else -> R.string.record_start
                },
            ),
            stateText = if (waiting) null else (recording as? RecordingState.Active)?.let {
                stringResource(R.string.record_state, spokenDuration(it.elapsedSeconds), spokenDuration(it.limitSeconds))
            },
            onClick = onRecordKey,
            modifier = Modifier.tourSpot(TourSpot.RECORD),
        )
        Spacer(Modifier.height(8.dp))
        // Under the key: its name, "Cancel" during the countdown, the tape counter while recording - each fades
        // into the next. The key already says all of it to screen readers.
        val under = when {
            waiting -> UnderKey.CANCEL
            counter != null -> UnderKey.COUNTER
            else -> UnderKey.NAME
        }
        val lastCounter = remember { arrayOf("") }
        if (counter != null) lastCounter[0] = counter
        val stillNow = TapedoTheme.reduceMotion
        AnimatedContent(
            targetState = under,
            transitionSpec = { fadeSwap(stillNow) },
            contentAlignment = Alignment.Center,
            label = "under the key",
        ) { shown ->
            when (shown) {
                UnderKey.COUNTER -> CounterDisplay(counter ?: lastCounter[0], Modifier.clearAndSetSemantics { })
                UnderKey.CANCEL -> Text(
                    text = stringResource(R.string.record_cancel),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = TapedoTheme.colors.record,
                    modifier = Modifier.clearAndSetSemantics { },
                )
                UnderKey.NAME -> Text(
                    text = stringResource(R.string.record_start),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        }
    }
}

private enum class Middle { STACK, COUNTDOWN, LISTENING }

private enum class UnderKey { NAME, CANCEL, COUNTER }

private const val SWAP_MS = 220

/** One thing fades into the next in place - at once with "reduce motion". */
internal fun fadeSwap(still: Boolean): ContentTransform =
    if (still) EnterTransition.None togetherWith ExitTransition.None
    else fadeIn(tween(SWAP_MS)) togetherWith fadeOut(tween(SWAP_MS))

/** While recording: the seismograph and the words as they are recognised. */
@Composable
private fun ListeningPanel(state: RecordingState.Active, modifier: Modifier) {
    SpeakNowCue(state)
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Seismograph(state.levels, state.sampleCount, SEISMOGRAPH_SAMPLES, penUp = !state.ready)
        Spacer(Modifier.height(16.dp))
        // The microphone is not open yet, or the phone restarts its recognizer - words said now would be lost.
        Fold(!state.listening) {
            Text(
                stringResource(if (state.micOpened) R.string.record_wait else R.string.record_starting),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = TapedoTheme.colors.record,
                modifier = Modifier.padding(bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (state.text.isEmpty()) {
            // Only when really listening: a typed "Listening…" (announced once); the REC lamp blinks by the counter.
            Fold(state.ready) { Text(
                stringResource(R.string.record_listening),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) }
        } else {
            // The growing text is not re-read on every word.
            Text(
                state.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            )
        }
    }
}

/**
 * "3, 2, 1": one big typewriter digit on a round paper dial, so the user knows exactly when to start.
 * The digit is already huge - the system font size is ignored so it never runs off the dial.
 * A tap on the dial cancels - the hint under it says so, for a "Record" pressed by mistake.
 */
@Composable
internal fun CountdownDial(countdown: Int, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val colors = TapedoTheme.colors
    val spoken = stringResource(R.string.record_countdown, countdown)
    val cancel = stringResource(R.string.record_cancel)
    val digit = with(LocalDensity.current) { 120.dp.toSp() }
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = cancel, role = Role.Button, onClick = onCancel)
            .padding(12.dp)
            .clearAndSetSemantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
                onClick(label = cancel) { onCancel(); true }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(200.dp)
                .background(colors.card, CircleShape)
                .border(3.dp, colors.record, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                countdown.toString(),
                style = MaterialTheme.typography.displayLarge.copy(fontSize = digit, lineHeight = digit),
                fontWeight = FontWeight.Bold,
                color = colors.record,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.record_countdown_cancel_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** After the countdown, with the microphone open: speak now. */
internal val RecordingState.Active.ready: Boolean get() = countdown == 0 && listening

/**
 * One short buzz each time the microphone really listens: after the countdown, and again when it comes
 * back after the phone restarted its recognizer (words said in that gap are lost) - "speak now",
 * without looking at the screen.
 */
@Composable
internal fun SpeakNowCue(state: RecordingState.Active) {
    val view = LocalView.current
    val go = state.ready
    LaunchedEffect(go) {
        if (go) {
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
                else HapticFeedbackConstants.VIRTUAL_KEY,
            )
        }
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
    code: Int? = null,
    onReport: ((Problem) -> Unit)? = null,
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
        // The offline way first: the language pack keeps the voice on the phone (author 11.11.2026).
        if (error == SpeechError.OFFLINE_UNAVAILABLE) {
            PaperButton(
                stringResource(R.string.speech_get_offline_pack),
                onClick = {
                    val voice = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val general = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(voice)
                    } catch (_: android.content.ActivityNotFoundException) {
                        context.startActivity(general)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
        // Errors the user cannot fix: the author should hear about them at once.
        if (onReport != null && error in REPORTABLE_ERRORS) {
            ReportButton { onReport(Problem.RecordingFailed(error.name, code)) }
        }
        PaperButton(stringResource(R.string.home_dismiss), onClick = onDismiss)
    }
}

private val REPORTABLE_ERRORS = setOf(SpeechError.OTHER, SpeechError.NOT_AVAILABLE, SpeechError.BUSY)

@Composable
private fun ReportButton(onClick: () -> Unit) {
    PaperButton(stringResource(R.string.report_send), onClick = onClick, modifier = Modifier.fillMaxWidth())
    Text(stringResource(R.string.report_privacy), style = MaterialTheme.typography.bodySmall)
}

/** The recording still works (the old way) - told calmly, once, with the report button. */
@Composable
private fun OwnAudioRefusedNote(code: Int, onReport: ((Problem) -> Unit)?, onDismiss: () -> Unit) {
    PinnedNote {
        Text(
            stringResource(R.string.own_audio_refused) +
                if (onReport != null) " " + stringResource(R.string.own_audio_refused_report) else "",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (onReport != null) ReportButton { onReport(Problem.OwnAudioRefused(code)) }
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

@Composable
internal fun speechErrorText(error: SpeechError): String = stringResource(
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

/** 75 -> "1 minuta 15 sekund" - how a screen reader should say the counter. */
@Composable
private fun spokenDuration(seconds: Int): String {
    val min = seconds / 60
    val sec = seconds % 60
    return when {
        min == 0 -> pluralStringResource(R.plurals.duration_seconds, sec, sec)
        sec == 0 -> pluralStringResource(R.plurals.duration_minutes, min, min)
        else -> pluralStringResource(R.plurals.duration_minutes, min, min) + " " +
            pluralStringResource(R.plurals.duration_seconds, sec, sec)
    }
}

/**
 * The recorder's nameplate: the name engraved on a narrow metal strip held by two screws -
 * part of the device, not an app bar title.
 */
@Composable
private fun Nameplate(name: String) {
    val colors = TapedoTheme.colors
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(2.dp)
    Row(
        Modifier
            .background(colors.track, shape)
            .border(1.dp, colors.controlEdge, shape)
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .clearAndSetSemantics {
                heading()
                contentDescription = name
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Screw()
        Text(
            name.uppercase(),
            style = MaterialTheme.typography.titleLarge.copy(
                letterSpacing = 4.sp,
                // Engraved: a light edge under each letter.
                shadow = Shadow(colors.knobLight.copy(alpha = 0.8f), Offset(0f, 1.5f), 0f),
            ),
            fontWeight = FontWeight.Bold,
            color = scheme.onSurface,
        )
        Screw()
    }
}

@Composable
private fun Screw() {
    val colors = TapedoTheme.colors
    Canvas(Modifier.size(9.dp)) {
        drawCircle(colors.knob)
        drawCircle(colors.controlEdge, style = Stroke(1.dp.toPx()))
        // The slot, a little turned.
        val r = size.minDimension / 2 * 0.7f
        drawLine(
            colors.controlEdge,
            Offset(center.x - r * 0.8f, center.y - r * 0.6f),
            Offset(center.x + r * 0.8f, center.y + r * 0.6f),
            strokeWidth = 1.2.dp.toPx(),
        )
    }
}

/** The tape counter in a small recessed display window (amber digits in Panel, graphite in Paper), with the REC lamp. */
@Composable
private fun CounterDisplay(text: String, modifier: Modifier = Modifier) {
    val colors = TapedoTheme.colors
    val shape = RoundedCornerShape(3.dp)
    Row(
        modifier
            .background(colors.display, shape)
            .border(1.dp, colors.controlEdge, shape)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RecLamp()
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = colors.displayInk,
        )
    }
}

/** Red REC lamp: blinks once a second while recording; steady with "reduce motion". */
@Composable
private fun RecLamp() {
    val colors = TapedoTheme.colors
    val alpha = if (TapedoTheme.reduceMotion) {
        1f
    } else {
        val blink = rememberInfiniteTransition(label = "rec")
        val a by blink.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 500), RepeatMode.Reverse),
            label = "recAlpha",
        )
        a
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(9.dp)) { drawCircle(colors.record.copy(alpha = alpha)) }
        Text(
            "REC",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = colors.record,
        )
    }
}
