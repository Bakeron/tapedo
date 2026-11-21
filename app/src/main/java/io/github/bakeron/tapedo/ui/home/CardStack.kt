package io.github.bakeron.tapedo.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bakeron.tapedo.R
import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.ui.cards.DoneMark
import io.github.bakeron.tapedo.ui.cards.PencilBox
import io.github.bakeron.tapedo.ui.cards.PriorityTape
import io.github.bakeron.tapedo.ui.cards.RuledText
import io.github.bakeron.tapedo.ui.cards.TypeMark
import io.github.bakeron.tapedo.ui.cards.TypeTapeMark
import io.github.bakeron.tapedo.ui.cards.appLocale
import io.github.bakeron.tapedo.ui.cards.handStrike
import io.github.bakeron.tapedo.ui.cards.isDone
import io.github.bakeron.tapedo.ui.cards.kindNumbers
import io.github.bakeron.tapedo.ui.cards.matches
import io.github.bakeron.tapedo.ui.cards.metaLine
import io.github.bakeron.tapedo.ui.cards.numberedName
import io.github.bakeron.tapedo.ui.cards.numberedNameRes
import io.github.bakeron.tapedo.ui.cards.rememberToday
import io.github.bakeron.tapedo.ui.cards.typeNameRes
import io.github.bakeron.tapedo.ui.components.PaperButton
import io.github.bakeron.tapedo.ui.settings.priorityName
import io.github.bakeron.tapedo.ui.theme.TapedoTheme

/**
 * The dividers of the card box: one per kind of card, and the done pile. The open divider is also where
 * the next recording goes - it becomes a card of that kind (author 10.11.2026: no "All", no type bar).
 */
enum class StackTab(val type: NoteType?) {
    SHOPPING(NoteType.SHOPPING),
    CHECKLIST(NoteType.CHECKLIST),
    TASK(NoteType.TASK),
    EVENT(NoteType.EVENT),
    NOTE(NoteType.NOTE),
    IDEA(NoteType.IDEA),
    DONE(null),
    ;

    fun holds(note: Note): Boolean = when (this) {
        DONE -> note.isDone()
        else -> !note.isDone() && note.type == type
    }

    companion object {
        fun of(type: NoteType): StackTab = entries.first { it.type == type }
    }
}

/**
 * The saved cards as a card box: dividers on top (the kind's sign and how many cards it holds), a search line,
 * and the cards as narrow strips - their top edges, as seen in the box - so ten fit on a screen.
 * Tap opens the card; tasks tick off right on the strip; the × throws a card out (undo on the bar below).
 */
@Composable
fun CardStack(
    stack: StackState,
    tab: StackTab,
    onSelectTab: (StackTab) -> Unit,
    onOpen: (Note) -> Unit,
    onToggleDone: (Note) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (Note) -> Unit = {},
    top: @Composable () -> Unit = {},
    /** Kept by the caller: opening a card or recording swaps the box out, and both must survive that. */
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    listStates: Map<StackTab, LazyListState> = remember { stackListStates() },
    /** The search line is out (the magnifier on top was pressed). */
    searchOpen: Boolean = false,
    onCloseSearch: () -> Unit = {},
) {
    val colors = TapedoTheme.colors
    val reduceMotion = TapedoTheme.reduceMotion
    // "Back" first closes the search - it undoes the last move instead of closing the app.
    BackHandler(enabled = searchOpen || query.isNotEmpty()) { if (searchOpen) onCloseSearch() else onQueryChange("") }

    val notes = (stack as? StackState.Loaded)?.notes.orEmpty()
    val counts = remember(notes) { StackTab.entries.associateWith { t -> notes.count(t::holds) } }
    val numbers = remember(notes) { kindNumbers(notes) }
    // The strip shows "Zadanie 2", not the title - so that name finds the card too.
    val context = LocalContext.current
    val kindNames = NoteType.entries.associateWith { stringResource(numberedNameRes(it)) }
    fun shownOn(tab: StackTab): List<Note> =
        notes.filter { note ->
            tab.holds(note) && (
                note.matches(query) ||
                    (note.type != NoteType.EVENT && numbers[note.id]?.let { n ->
                        context.getString(R.string.card_numbered, kindNames.getValue(note.type), n).contains(query.trim(), ignoreCase = true)
                    } == true)
                )
        }

    Column(modifier) {
        // Every divider is always there, also empty: it is where the next recording goes.
        DividerTabs(tab, counts, onSelect = onSelectTab)
        // The inside of the box: same colour as the chosen divider, so the two read as one piece.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .tourSpot(TourSpot.BOX)
                .background(colors.cardEdge),
        ) {
            // Slides out of the box's top edge under the magnifier and back in - at once with "reduce motion".
            AnimatedVisibility(
                visible = searchOpen,
                enter = if (reduceMotion) EnterTransition.None else expandVertically(tween(SEARCH_MS)) + fadeIn(tween(SEARCH_MS)),
                exit = if (reduceMotion) ExitTransition.None else shrinkVertically(tween(SEARCH_MS)) + fadeOut(tween(SEARCH_MS)),
            ) {
                SearchLine(query, onChange = onQueryChange, Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp))
            }
            // Another divider is another pile: the old one fades out as a whole, then the new one fades in.
            // Animated one strip at a time, the old strips flew out over the record key (author 21.11.2026).
            AnimatedContent(
                targetState = tab,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    if (reduceMotion) EnterTransition.None togetherWith ExitTransition.None
                    else fadeIn(tween(PILE_IN_MS, delayMillis = PILE_OUT_MS)) togetherWith fadeOut(tween(PILE_OUT_MS))
                },
                label = "divider pile",
            ) { pileTab ->
            val shown = remember(notes, pileTab, query, kindNames) { shownOn(pileTab) }
            // Clipped: a strip fading out after "Delete" must not be drawn outside the box.
            LazyColumn(
                Modifier.fillMaxSize().clipToBounds(),
                state = listStates.getValue(pileTab),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "top") { top() }
                when (stack) {
                    StackState.Loading -> Unit
                    StackState.Failed -> item(key = "failed") { StackFailed(onRetry) }
                    is StackState.Loaded -> when {
                        // An empty kind's divider: the dashed card says the red button fills it.
                        shown.isEmpty() && query.isBlank() && pileTab != StackTab.DONE -> item(key = "empty") { EmptyCard() }
                        shown.isEmpty() -> item(key = "none") { NothingFound(query) }
                        else -> items(shown, key = { it.id }) { note ->
                            NoteStrip(
                                note,
                                number = numbers[note.id],
                                onOpen = { onOpen(note) },
                                onToggleDone = { onToggleDone(note) },
                                onDelete = { onDelete(note) },
                                modifier = if (reduceMotion) Modifier else Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

/** One remembered place in the box for every divider. */
internal fun stackListStates(): Map<StackTab, LazyListState> = StackTab.entries.associateWith { LazyListState() }

private const val PILE_OUT_MS = 110
private const val PILE_IN_MS = 170
private const val SEARCH_MS = 180

/**
 * Card-box dividers: only the kind's drawn sign and the count, so all seven fit side by side. The chosen one
 * stands taller and joins the box below (same paper, no edge between), the others sit lower behind it - the
 * choice shows by height and shape, not by colour alone. Screen readers hear the name and the count.
 */
@Composable
private fun DividerTabs(selected: StackTab, counts: Map<StackTab, Int>, onSelect: (StackTab) -> Unit) {
    val colors = TapedoTheme.colors
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .tourSpot(TourSpot.TABS)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        StackTab.entries.forEach { tab ->
            val isSelected = tab == selected
            val count = counts.getValue(tab)
            val desc = pluralStringResource(R.plurals.tab_desc, count, stringResource(tabNameRes(tab)), count)
            // The whole 48 dp is the touch target; the visible divider is lower when not chosen.
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) })
                    .semantics { contentDescription = desc },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(if (isSelected) 46.dp else 38.dp)
                        .background(if (isSelected) colors.cardEdge else colors.card, shape)
                        .then(if (isSelected) Modifier else Modifier.border(1.dp, colors.controlEdge, shape))
                        .padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
                ) {
                    val ink = if (isSelected) scheme.onSurface else scheme.onSurfaceVariant
                    val size = if (isSelected) 20.dp else 17.dp
                    val type = tab.type
                    if (type != null) TypeMark(type, ink, size = size) else DoneMark(ink, size = size)
                    Text(
                        count.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = ink,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
        }
    }
}

/** A line to type in, with a clear button - search on the chosen divider. */
@Composable
private fun SearchLine(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = TapedoTheme.colors
    val scheme = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val label = stringResource(R.string.search_hint)
    val shape = RoundedCornerShape(3.dp)
    // Opened by the magnifier: the cursor is in the line and the keyboard comes up, ready to type.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // The typewriter font sits low in its line - centred line height keeps the hint and the words mid-line.
    val style = MaterialTheme.typography.bodyMedium.copy(
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(colors.card, shape)
            .border(1.dp, scheme.outline, shape)
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = style.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = label },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(label, style = style, color = scheme.onSurfaceVariant)
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            val clear = stringResource(R.string.search_clear)
            IconButton(onClick = { onChange("") }, modifier = Modifier.semantics { contentDescription = clear }) {
                Text("×", style = MaterialTheme.typography.titleLarge, color = scheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics { })
            }
        } else {
            Spacer(Modifier.width(14.dp))
        }
    }
}

/**
 * One card as a narrow strip: priority tape on the corner, title, type and date, list progress.
 * Done cards (on the "Done" divider) have their title struck through.
 */
@Composable
private fun NoteStrip(
    note: Note,
    number: Int?,
    onOpen: () -> Unit,
    onToggleDone: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TapedoTheme.colors
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val locale = appLocale()
    val today = rememberToday()
    val shape = RoundedCornerShape(3.dp)
    val done = note.isDone()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // "Zakupy 1", "Zadanie 2" in the order recorded (author 10.11.2026); an event keeps its own title.
    val numbered = number != null && note.type != NoteType.EVENT
    val label = number?.takeIf { numbered }?.let { numberedName(note.type, it) } ?: note.title

    // A crooked priority tape sticks out about 9 dp above the strip. The strip's fade in and out (after "Undo",
    // a new card) draws it in a layer cut to its own bounds, so the tape lost its top (author 11.11.2026) -
    // the strip keeps that room inside its bounds. Straight tapes fit already.
    val tapeRoom = if (note.type != NoteType.IDEA && TapedoTheme.tilt(1f) != 0f) 10.dp else 0.dp
    Box(modifier.fillMaxWidth().padding(top = tapeRoom)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, start = 4.dp, end = 4.dp)
                .background(colors.card, shape)
                .border(1.dp, colors.controlEdge, shape)
                .clickable(onClickLabel = stringResource(R.string.stack_open_card), onClick = onOpen)
                .heightIn(min = 60.dp)
                // Room on the left for the type tape stuck over the edge.
                .padding(start = 36.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (note.type == NoteType.TASK) {
                val state = stringResource(if (note.done) R.string.card_done else R.string.card_not_done)
                Box(
                    Modifier
                        .size(48.dp)
                        .toggleable(value = note.done, role = Role.Checkbox, onValueChange = { onToggleDone() })
                        .semantics { stateDescription = state },
                    contentAlignment = Alignment.Center,
                ) { PencilBox(note.done, scheme.onSurface) }
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (done) scheme.onSurfaceVariant else scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { layout = it },
                    modifier = Modifier.handStrike(done, { layout }, scheme.onSurface),
                )
                // The numbered label already names the kind - only the date, time and place under it.
                val meta = metaLine(context, note, today, locale)
                val second = if (numbered) meta else stringResource(typeNameRes(note.type)) + if (meta.isNotEmpty()) "  ·  $meta" else ""
                if (second.isNotEmpty()) {
                    Text(
                        second.uppercase(locale),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (note.type.hasItems && note.items.isNotEmpty()) {
                val ticked = note.items.count { it.checked }
                val progress = stringResource(R.string.strip_progress_desc, ticked, note.items.size)
                Text(
                    "$ticked/${note.items.size}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clearAndSetSemantics { contentDescription = progress },
                )
            }
            Text(
                "›",
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp).clearAndSetSemantics { },
            )
            val deleteDesc = stringResource(R.string.strip_delete_desc, label)
            IconButton(onClick = onDelete, modifier = Modifier.semantics { contentDescription = deleteDesc }) {
                Text("×", style = MaterialTheme.typography.titleLarge, color = scheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics { })
            }
        }

        // The kind at a glance (author 08.11.2026: shopping and lists looked the same). Over the left edge,
        // so the priority tape moved to the top-right corner - the same corner as on the open card.
        TypeTapeMark(note.type, Modifier.align(Alignment.CenterStart).padding(top = 4.dp))

        // Ideas have no priority, so no tape.
        if (note.type != NoteType.IDEA) {
            val priorityDesc = stringResource(R.string.card_priority_desc, priorityName(note.priority))
            PriorityTape(
                note.priority,
                Modifier
                    .align(Alignment.TopEnd)
                    .size(width = 34.dp, height = 12.dp)
                    .semantics { contentDescription = priorityDesc },
            )
        }
    }
}

internal fun tabNameRes(tab: StackTab): Int = when (tab) {
    StackTab.SHOPPING -> R.string.tab_shopping
    StackTab.CHECKLIST -> R.string.tab_checklist
    StackTab.TASK -> R.string.tab_task
    StackTab.EVENT -> R.string.tab_event
    StackTab.NOTE -> R.string.tab_note
    StackTab.IDEA -> R.string.tab_idea
    StackTab.DONE -> R.string.tab_done
}

@Composable
private fun NothingFound(query: String) {
    Text(
        if (query.isBlank()) stringResource(R.string.tab_empty) else stringResource(R.string.search_none, query.trim()),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** An empty card with a dashed edge and the first hint written by its margin - not a grey paragraph. */
@Composable
private fun EmptyCard() {
    val colors = TapedoTheme.colors
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
            .rotate(TapedoTheme.tilt(-1.2f))
            .background(colors.card.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
            .drawBehind {
                drawRoundRect(
                    color = scheme.outline,
                    cornerRadius = CornerRadius(3.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                )
            }
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text(stringResource(R.string.stack_empty_title), style = MaterialTheme.typography.titleMedium)
        // Typed on the card's lines.
        RuledText(
            stringResource(R.string.home_empty),
            MaterialTheme.typography.bodyMedium,
            scheme.onSurfaceVariant,
            colors.rule,
            Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun StackFailed(onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.stack_failed),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        PaperButton(stringResource(R.string.stack_retry), onClick = onRetry)
    }
}
