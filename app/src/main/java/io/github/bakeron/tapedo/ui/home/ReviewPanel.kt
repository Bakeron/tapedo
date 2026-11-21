package io.github.bakeron.tapedo.ui.home

import io.github.bakeron.tapedo.ui.components.fadeSwap
import io.github.bakeron.tapedo.ui.components.SwapText
import io.github.bakeron.tapedo.ui.components.FoldValue
import io.github.bakeron.tapedo.ui.components.Fold
import androidx.compose.animation.AnimatedContent
import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.bakeron.tapedo.R
import io.github.bakeron.tapedo.core.settings.AiMode
import io.github.bakeron.tapedo.ui.cards.IndexCard
import io.github.bakeron.tapedo.ui.components.LabelTape
import io.github.bakeron.tapedo.ui.components.PaperButton
import io.github.bakeron.tapedo.ui.components.PaperButtonStyle
import io.github.bakeron.tapedo.ui.settings.LowFundsWarning
import io.github.bakeron.tapedo.ui.settings.ProblemView
import io.github.bakeron.tapedo.ui.theme.TapedoTheme
import kotlinx.coroutines.delay

/**
 * The window after a recording: what was heard comes out of the typewriter letter by letter,
 * then it is cut into cards that drop in one by one. They are already saved when they appear;
 * "Done" or back closes the window, "Delete" takes the recording back out.
 * "Record more" keeps dictating into the same cards (e.g. the rest of a shopping list at the fridge).
 */
@Composable
fun ReviewPanel(
    state: RecordingState,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onEdit: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onRecordMore: () -> Unit = {},
    onRecordMoreDenied: () -> Unit = {},
    onRefine: () -> Unit = {},
    onAcceptRefined: () -> Unit = {},
    onKeepCards: () -> Unit = {},
) {
    val transcript = when (state) {
        is RecordingState.Structuring -> state.transcript
        is RecordingState.Review -> state.transcript
        else -> return
    }
    val review = state as? RecordingState.Review
    val scheme = MaterialTheme.colorScheme
    val colors = TapedoTheme.colors
    val reduceMotion = TapedoTheme.reduceMotion
    val context = LocalContext.current
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onRecordMore() else onRecordMoreDenied()
    }

    // Back closes the window (the cards are saved); while they are still being written it simply waits.
    // Claude's offer (or its question still running) goes first - the cards on screen stay.
    val refine = review?.refine
    BackHandler(onBack = {
        when {
            refine is RecordingState.Refine.Working || refine is RecordingState.Refine.Offer -> onKeepCards()
            review != null -> onSave()
        }
    })

    // Typewriter: letters appear over at most ~1.2 s; saved, so a rotation does not type again.
    var typed by rememberSaveable(transcript) { mutableIntStateOf(if (reduceMotion) transcript.length else 0) }
    LaunchedEffect(transcript) {
        val step = (1200L / transcript.length.coerceAtLeast(1)).coerceIn(4L, 28L)
        while (typed < transcript.length) {
            delay(step)
            typed = (typed + 1 + transcript.length / 200).coerceAtMost(transcript.length)
        }
    }
    val typingDone = typed >= transcript.length

    // Cards drop in one after another once the typing is done.
    var revealed by rememberSaveable(transcript) { mutableIntStateOf(if (reduceMotion) Int.MAX_VALUE else 0) }
    val cardCount = review?.cards?.size ?: 0
    LaunchedEffect(typingDone, cardCount) {
        if (!typingDone) return@LaunchedEffect
        while (revealed < cardCount) {
            delay(140)
            revealed++
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(scheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        LabelTape(stringResource(R.string.review_heard), Modifier.padding(start = 20.dp, top = 16.dp))

        // The typed sheet: a strip of paper with ruled lines. Screen readers get the full text at once.
        val sheet = RoundedCornerShape(2.dp)
        Text(
            transcript.take(typed),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .background(colors.card, sheet)
                .border(1.dp, colors.cardEdge, sheet)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .clearAndSetSemantics { contentDescription = transcript },
        )

        // Status line, read out as it changes: writing, how many cards, or why the AI did not help.
        val status = when {
            review == null -> stringResource(R.string.review_structuring)
            else -> pluralStringResource(R.plurals.review_count, review.cards.size, review.cards.size)
        }
        SwapText(
            status,
            style = MaterialTheme.typography.titleMedium,
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        Fold(review != null && !review.saveFailed) {
            Text(
                stringResource(R.string.review_saved),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        FoldValue(review?.lowFunds) { LowFundsWarning(it, Modifier.padding(horizontal = 20.dp)) }
        FoldValue(review?.aiProblems?.takeIf { it.isNotEmpty() }) { problems ->
            // Every provider that failed and what to do - the same words as in Settings.
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.review_ai_rules),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                problems.forEach { ProblemView(it) }
            }
        }

        val list = rememberLazyListState()
        // Claude's cards come in below the others - brought into view, without a ride when motion is reduced.
        LaunchedEffect(refine is RecordingState.Refine.Offer) {
            if (refine !is RecordingState.Refine.Offer) return@LaunchedEffect
            val at = review?.cards?.size ?: 0
            if (reduceMotion) list.scrollToItem(at) else list.animateScrollToItem(at)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = list,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            val cards = review?.cards.orEmpty()
            itemsIndexed(cards, key = { i, c -> "$i-${c.title}" }) { i, card ->
                // "Delete card" closes the gap smoothly instead of the cards below jumping up.
                AnimatedVisibility(
                    visible = i < revealed,
                    modifier = if (reduceMotion) Modifier else Modifier.animateItem(),
                    enter = if (reduceMotion) EnterTransition.None
                    else slideInVertically(tween(260)) { -it / 3 } + fadeIn(tween(200)),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // A tap opens the same card view as on the stack - no separate "Edit" (author 13.11.2026).
                        IndexCard(
                            card,
                            compact = false,
                            tilt = if (i % 2 == 0) -0.6f else 0.5f,
                            // The same whole card as in the card view - no title that only repeats the text.
                            preview = true,
                            onClick = { onEdit(i) },
                            onClickLabel = stringResource(R.string.stack_open_card),
                        )
                        PaperButton(stringResource(R.string.review_remove_card), onClick = { onRemove(i) }, style = PaperButtonStyle.DANGER)
                    }
                }
            }
            // "More exact" (Claude key) or "Try AI again" (free AI that failed): only when the rules wrote these
            // cards, once they are all in.
            val refineMode = review?.refineMode
            if (review != null && refineMode != null && review.by == null && revealed >= cards.size) {
                item(key = "refine") {
                    RefineBlock(
                        review.refine, RefineTexts.review(free = refineMode == AiMode.FREE), onRefine, onAcceptRefined, onKeepCards,
                        if (reduceMotion) Modifier else Modifier.animateItem(),
                    )
                }
            }
        }

        Fold(review?.saveFailed == true) {
            Text(
                stringResource(R.string.review_save_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.record,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        FoldValue(review?.moreFailed) { error ->
            Text(
                stringResource(R.string.review_more_failed, speechErrorText(error)),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.record,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        PaperButton(
            stringResource(R.string.review_record_more),
            onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    onRecordMore()
                } else {
                    askMic.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            enabled = review != null,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Throwing away a whole dictation takes two taps - two separate ones: a quick double tap
            // (a shaky finger) would otherwise arm and confirm at once, and there is no undo here.
            var confirmDiscard by rememberSaveable(transcript) { mutableStateOf(false) }
            var armedAt by remember { mutableLongStateOf(0L) }
            PaperButton(
                stringResource(if (confirmDiscard) R.string.review_discard_confirm else R.string.review_discard),
                onClick = {
                    val now = SystemClock.uptimeMillis()
                    if (!confirmDiscard) {
                        confirmDiscard = true
                        armedAt = now
                    } else if (now - armedAt >= DISCARD_CONFIRM_GAP_MS) {
                        onDiscard()
                    }
                },
                style = PaperButtonStyle.DANGER,
                enabled = review != null,
            )
            PaperButton(
                if (review?.saveFailed == true) stringResource(R.string.review_save_retry)
                else stringResource(R.string.editor_done),
                onClick = onSave,
                style = PaperButtonStyle.FILLED,
                enabled = review != null,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The words of [RefineBlock]: after a recording, or under one card in the card view. */
internal class RefineTexts(
    @StringRes val button: Int,
    @StringRes val working: Int,
    @StringRes val hint: Int,
    @StringRes val failed: Int,
    @StringRes val offer: Int,
    @StringRes val acceptOne: Int,
    @StringRes val accept: Int,
    @StringRes val keep: Int,
) {
    companion object {
        fun review(free: Boolean) = if (free) {
            RefineTexts(
                R.string.review_retry, R.string.review_retry_working, R.string.review_retry_hint, R.string.review_retry_failed,
                R.string.review_retry_offer, R.string.review_refine_accept, R.string.review_refine_accept, R.string.review_refine_keep,
            )
        } else {
            RefineTexts(
                R.string.review_refine, R.string.review_refine_working, R.string.review_refine_hint, R.string.review_refine_failed,
                R.string.review_refine_offer, R.string.review_refine_accept, R.string.review_refine_accept, R.string.review_refine_keep,
            )
        }

        fun card(free: Boolean) = if (free) {
            RefineTexts(
                R.string.card_refine_free, R.string.review_retry_working, R.string.card_refine_free_hint, R.string.card_refine_free_failed,
                R.string.review_retry_offer, R.string.card_refine_accept_one, R.string.review_refine_accept, R.string.card_refine_keep,
            )
        } else {
            RefineTexts(
                R.string.review_refine, R.string.review_refine_working, R.string.card_refine_hint, R.string.card_refine_failed,
                R.string.review_refine_offer, R.string.card_refine_accept_one, R.string.review_refine_accept, R.string.card_refine_keep,
            )
        }
    }
}

/**
 * "More exact" (or, with free AI, "Try AI again") under the cards: the button, then the AI's cards to take or
 * leave. The old cards stay saved above until "Use these" - the AI may be worse, so the user compares first.
 */
@Composable
internal fun RefineBlock(
    refine: RecordingState.Refine?,
    texts: RefineTexts,
    onRefine: () -> Unit,
    onAccept: () -> Unit,
    onKeep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val still = TapedoTheme.reduceMotion
    // The button and Claude's cards fade one into the other (old out, then new in).
    AnimatedContent(
        targetState = refine as? RecordingState.Refine.Offer,
        modifier = modifier,
        transitionSpec = { fadeSwap(still) },
        contentKey = { it != null },
        label = "refine",
    ) { offer ->
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (offer != null) {
            LabelTape(stringResource(texts.offer))
            offer.cards.forEachIndexed { i, card ->
                IndexCard(card, compact = false, tilt = if (i % 2 == 0) 0.5f else -0.6f, preview = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperButton(stringResource(texts.keep), onClick = onKeep)
                PaperButton(
                    stringResource(if (offer.cards.size == 1) texts.acceptOne else texts.accept),
                    onClick = onAccept,
                    style = PaperButtonStyle.FILLED,
                    modifier = Modifier.weight(1f),
                )
            }
            return@Column
        }
        val working = refine is RecordingState.Refine.Working
        PaperButton(
            stringResource(if (working) texts.working else texts.button),
            onClick = onRefine,
            enabled = !working,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            stringResource(texts.hint),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        FoldValue(refine as? RecordingState.Refine.Failed) { failed ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(texts.failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                ProblemView(failed.problem)
            }
        }
    }
    }
}

/** Shortest time between the two taps of "Delete" - less is one shaky double tap, not a decision. */
private const val DISCARD_CONFIRM_GAP_MS = 500L
