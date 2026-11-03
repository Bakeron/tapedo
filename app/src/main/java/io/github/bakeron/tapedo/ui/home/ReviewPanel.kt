package io.github.bakeron.tapedo.ui.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import io.github.bakeron.tapedo.ui.cards.IndexCard
import io.github.bakeron.tapedo.ui.cards.marginLine
import io.github.bakeron.tapedo.ui.components.LabelTape
import io.github.bakeron.tapedo.ui.components.PaperButton
import io.github.bakeron.tapedo.ui.components.PaperButtonStyle
import io.github.bakeron.tapedo.ui.settings.aiErrorMessage
import io.github.bakeron.tapedo.ui.theme.TapedoTheme
import kotlinx.coroutines.delay

/**
 * The window after a recording: what was heard comes out of the typewriter letter by letter,
 * then it is cut into cards that drop in one by one. Save puts them on the stack; back saves too.
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

    // Back keeps the cards; while they are still being written it simply waits.
    BackHandler(onBack = { if (review != null) onSave() })

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
                .padding(start = 12.dp)
                .marginLine(colors.record.copy(alpha = 0.5f))
                .padding(end = 14.dp, top = 6.dp, bottom = 6.dp)
                .clearAndSetSemantics { contentDescription = transcript },
        )

        // Status line, read out as it changes: writing, how many cards, or why the AI did not help.
        val status = when {
            review == null -> stringResource(R.string.review_structuring)
            else -> pluralStringResource(R.plurals.review_count, review.cards.size, review.cards.size)
        }
        Text(
            status,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        review?.aiError?.let { error ->
            Text(
                stringResource(R.string.review_ai_failed, aiErrorMessage(error)),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            val cards = review?.cards.orEmpty()
            itemsIndexed(cards, key = { i, c -> "$i-${c.title}" }) { i, card ->
                AnimatedVisibility(
                    visible = i < revealed,
                    enter = if (reduceMotion) EnterTransition.None
                    else slideInVertically(tween(260)) { -it / 3 } + fadeIn(tween(200)),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val editLabel = stringResource(R.string.review_edit)
                        IndexCard(
                            card,
                            compact = false,
                            tilt = if (i % 2 == 0) -0.6f else 0.5f,
                            onClick = { onEdit(i) },
                            onClickLabel = editLabel,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PaperButton(editLabel, onClick = { onEdit(i) })
                            PaperButton(stringResource(R.string.review_remove_card), onClick = { onRemove(i) }, style = PaperButtonStyle.DANGER)
                        }
                    }
                }
            }
        }

        if (review?.saveFailed == true) {
            Text(
                stringResource(R.string.review_save_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.record,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        review?.moreFailed?.let { error ->
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
            // Throwing away a whole dictation takes two taps.
            var confirmDiscard by rememberSaveable(transcript) { mutableStateOf(false) }
            PaperButton(
                stringResource(if (confirmDiscard) R.string.review_discard_confirm else R.string.review_discard),
                onClick = { if (confirmDiscard) onDiscard() else confirmDiscard = true },
                style = PaperButtonStyle.DANGER,
                enabled = review != null,
            )
            PaperButton(
                if (review?.saveFailed == true) stringResource(R.string.review_save_retry)
                else pluralStringResource(R.plurals.review_save, cardCount.coerceAtLeast(1)),
                onClick = onSave,
                style = PaperButtonStyle.FILLED,
                enabled = review != null,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
