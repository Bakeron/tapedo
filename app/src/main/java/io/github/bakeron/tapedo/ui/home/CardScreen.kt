package io.github.bakeron.tapedo.ui.home

import io.github.bakeron.tapedo.ui.components.fadeSwap
import io.github.bakeron.tapedo.ui.components.SwapText
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.snap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import android.Manifest
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.github.bakeron.tapedo.core.report.Problem
import io.github.bakeron.tapedo.core.settings.AiMode
import io.github.bakeron.tapedo.ui.settings.LowFundsWarning
import io.github.bakeron.tapedo.ui.cards.CardOptions
import io.github.bakeron.tapedo.ui.cards.EditableCard
import io.github.bakeron.tapedo.ui.cards.PencilBox
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import io.github.bakeron.tapedo.ui.components.Fold
import io.github.bakeron.tapedo.ui.components.FoldValue
import io.github.bakeron.tapedo.ui.components.PaperButton
import io.github.bakeron.tapedo.ui.components.PaperButtonStyle
import io.github.bakeron.tapedo.ui.theme.TapedoTheme

/**
 * "Karteczka": one card on the whole screen, with everything in one place (author 13.11.2026 - reading here
 * and writing in a separate editor was chaos). From the top: the card itself, written on directly (a list has
 * "Add by voice" next to "Add item"); the live line, and on a text card the wide "Add by voice" key; type,
 * priority, then "Share" and "Delete card" at the end, away from the ticks (undo is offered on the stack).
 * Every change is kept by itself - back just closes.
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
    refine: CardRefine? = null,
    onRefine: () -> Unit = {},
    onAcceptRefine: () -> Unit = {},
    onKeepRefine: () -> Unit = {},
    onRefineOtherKinds: (Boolean) -> Unit = {},
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

    val listCard = note.type.hasItems
    val scroll = rememberScrollState()
    // "Add by voice" (author 26.11.2026): what is under the card folds away and the view slides down to the card's
    // end, where the words will go; once recording, the panel at the bottom grows and the view keeps to the end.
    val live = active != null && !counting
    LaunchedEffect(active != null) {
        if (active == null) return@LaunchedEffect
        if (!reduceMotion) delay(FOLD_MS)
        if (reduceMotion) scroll.scrollTo(scroll.maxValue) else scroll.animateScrollTo(scroll.maxValue)
    }
    LaunchedEffect(live) {
        if (!live) return@LaunchedEffect
        val start = withFrameNanos { it }
        do {
            val now = withFrameNanos { it }
            if (scroll.value < scroll.maxValue) scroll.scrollTo(scroll.maxValue)
        } while (!reduceMotion && now - start < PANEL_FOLLOW_NS)
    }
    // "Show settings" unfolded below the screen's edge and seemed to do nothing (author 21.11.2026): the view
    // follows the unfolding down, frame by frame, also while the keyboard slides away.
    var optionsOpened by remember { mutableIntStateOf(0) }
    LaunchedEffect(optionsOpened) {
        if (optionsOpened == 0) return@LaunchedEffect
        val start = withFrameNanos { it }
        do {
            val now = withFrameNanos { it }
            if (scroll.value < scroll.maxValue) scroll.scrollTo(scroll.maxValue)
        } while (!reduceMotion && now - start < OPTIONS_FOLLOW_NS)
    }

    // The read-only preview over the card view (author 21.11.2026); the keyboard goes first, or it covers it.
    var previewing by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Box(Modifier.fillMaxSize()) {
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
                IconButton(onClick = {
                    keyboard?.hide()
                    focus.clearFocus()
                    previewing = true
                }) {
                    Icon(painterResource(R.drawable.ic_preview), contentDescription = stringResource(R.string.card_preview_open))
                }
            }

            Box(Modifier.weight(1f)) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    // Each part carries its own gap, so a message that folds away takes its gap with it - a shared
                    // spacedBy gap jumped in and out around the fold (author 21.11.2026).
                    verticalArrangement = Arrangement.Top,
                ) {
                    FoldValue(recording as? RecordingState.Failed, Modifier.padding(bottom = GAP)) { failed ->
                        ErrorNote(
                            failed.error, askOnline, onOnlineOnce, onOnlineAlways, onDismissError,
                            code = failed.code, onReport = onReport,
                        )
                    }
                    Fold(actionFailed, Modifier.padding(bottom = GAP)) {
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

                    // The black "Add by voice" key: on a list next to "Add item" inside the card (author 14.11.2026),
                    // on a text card under it, with the hint. While recording the red key at the bottom stops.
                    val voiceKey: @Composable () -> Unit = {
                        PaperButton(
                            stringResource(R.string.preview_add_voice),
                            onClick = onRecordKey,
                            style = PaperButtonStyle.FILLED,
                            // A card whose first save failed has nothing in the database to add to yet.
                            enabled = active == null && !appending && note.id != 0L,
                            modifier = if (listCard) Modifier else Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        )
                    }
                    // Blurred under "3, 2, 1" - the countdown is what to look at; sharp again for the words.
                    val haze by animateFloatAsState(
                        targetValue = if (counting) 1f else 0f,
                        animationSpec = if (reduceMotion) snap() else tween(FOLD_MS.toInt()),
                        label = "card haze",
                    )
                    EditableCard(
                        note, number, onChange,
                        modifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Modifier.blur(8.dp * haze)
                        } else {
                            Modifier.graphicsLayer { alpha = 1f - 0.6f * haze }
                        },
                        voiceKey = if (listCard) voiceKey else null,
                    )

                    // Under a text card the hint and the key; "adding" after a recording. All of it, "More exact" and
                    // the settings fold away while recording (author 26.11.2026) and come back after.
                    Fold(active == null && (appending || !listCard)) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = GAP)
                                .padding(horizontal = 4.dp),
                        ) {
                            SwapText(
                                stringResource(if (appending) R.string.preview_adding else R.string.preview_add_hint_text),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                contentAlignment = Alignment.CenterStart,
                                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            if (!listCard) Box(Modifier.padding(top = 8.dp)) { voiceKey() }
                        }
                    }
                    Fold(active == null) {
                        Column {
                            // "More exact" for every card (author 26.11.2026): the AI's version waits for a yes.
                            FoldValue(refine, Modifier.padding(top = GAP).padding(horizontal = 4.dp)) { r ->
                                Column {
                                    FoldValue(r.lowFunds) { LowFundsWarning(it, Modifier.padding(bottom = 8.dp)) }
                                    RefineBlock(
                                        r.state, RefineTexts.card(free = r.mode == AiMode.FREE), onRefine, onAcceptRefine, onKeepRefine,
                                        extra = { OtherKindsBox(r.otherKinds, enabled = r.state !is RecordingState.Refine.Working, onRefineOtherKinds) },
                                    )
                                }
                            }

                            // "Delete card" scrolls with the rest, next to "Share" at the very end - far from the ticks.
                            CardOptions(
                                note, onChange, onDelete, Modifier.padding(top = GAP).padding(horizontal = 4.dp, vertical = 8.dp),
                                onOpened = { optionsOpened++ },
                            )
                        }
                    }
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

            // At the bottom while recording: the seismograph, "Listening…" or the words, and the red stop key. The
            // card above it still scrolls by finger.
            active?.let { SpeakNowCue(it) }
            val lastActive = remember { arrayOfNulls<RecordingState.Active>(1) }
            if (active != null) lastActive[0] = active
            androidx.compose.animation.AnimatedVisibility(
                visible = live,
                enter = if (reduceMotion) EnterTransition.None else expandVertically(tween(FOLD_MS.toInt())) + fadeIn(tween(FOLD_MS.toInt())),
                exit = if (reduceMotion) ExitTransition.None else shrinkVertically(tween(FOLD_MS.toInt())) + fadeOut(tween(FOLD_MS.toInt())),
            ) {
                lastActive[0]?.let { LivePanel(it, onStopAppend) }
            }
        }
        CardPreview(previewing, note, number, onClose = { previewing = false }, onChange = onChange)
    }
}

/** The recording under the card view: what the pen draws, what is heard, and the key that ends it. */
@Composable
private fun LivePanel(shown: RecordingState.Active, onStop: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val reduceMotion = TapedoTheme.reduceMotion
    val edge = TapedoTheme.colors.controlEdge
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawLine(edge, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx()) }
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 8.dp),
    ) {
        Seismograph(shown.levels, shown.sampleCount, SEISMOGRAPH_SAMPLES, penUp = !shown.ready)
        // After the countdown and before the microphone listens: "starting", so no words are said into a closed mic.
        val status = if (!shown.listening) {
            stringResource(if (shown.micOpened) R.string.record_wait else R.string.record_starting)
        } else {
            null
        }
        FoldValue(status) { text ->
            SwapText(
                text,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = TapedoTheme.colors.record,
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Fold(shown.text.isNotEmpty() || shown.ready) {
            val ink by animateColorAsState(
                if (shown.text.isEmpty()) scheme.onSurfaceVariant else scheme.onSurface,
                if (reduceMotion) snap() else tween(200),
                label = "words ink",
            )
            Text(
                shown.text.ifEmpty { stringResource(R.string.record_listening) },
                style = MaterialTheme.typography.bodyMedium,
                color = ink,
                maxLines = 3,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .then(if (shown.text.isEmpty()) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
            )
        }
        RecordButton(
            recording = true,
            label = stringResource(R.string.preview_add_stop),
            onClick = onStop,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
        )
    }
}

/**
 * "May change the type and split" next to "More exact" (author 26.11.2026) - unticked, the AI gives back one card
 * of the same type.
 */
@Composable
private fun OtherKindsBox(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PencilBox(checked, ink)
        Text(stringResource(R.string.card_refine_other_kinds), style = MaterialTheme.typography.bodyMedium, color = ink)
    }
}

/** Room between the parts of the card screen. */
private val GAP = 16.dp

/** How long the view follows the unfolding settings: the fold (0.22 s) and the keyboard sliding away. */
private const val OPTIONS_FOLLOW_NS = 450_000_000L

/** What folds away when recording starts, and the bottom panel growing in. */
private const val FOLD_MS = 220L

/** How long the view keeps to the card's end while the recording panel grows in. */
private const val PANEL_FOLLOW_NS = 300_000_000L
