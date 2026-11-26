package io.github.bakeron.tapedo.ui.cards

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.layout.layout
import io.github.bakeron.tapedo.R
import io.github.bakeron.tapedo.core.model.ChecklistItem
import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.model.Priority
import io.github.bakeron.tapedo.core.model.ShoppingGroup
import io.github.bakeron.tapedo.ui.components.Fold
import io.github.bakeron.tapedo.ui.components.FoldValue
import io.github.bakeron.tapedo.ui.components.LabelTape
import io.github.bakeron.tapedo.ui.components.fadeSwap
import io.github.bakeron.tapedo.ui.components.PairOrStack
import io.github.bakeron.tapedo.ui.components.PaperButton
import io.github.bakeron.tapedo.ui.components.PaperButtonStyle
import io.github.bakeron.tapedo.ui.components.SlideSelector
import io.github.bakeron.tapedo.ui.home.TypeTapeBar
import io.github.bakeron.tapedo.ui.home.tidy
import io.github.bakeron.tapedo.ui.settings.priorityName
import io.github.bakeron.tapedo.ui.theme.TapedoTheme
import kotlinx.coroutines.delay

/**
 * The card in the card view, written on directly (author 13.11.2026: reading in one place and writing in
 * another was chaos): its kind on top ("Zadanie 3"), the title, text or items to tick, add and remove,
 * place, date, time and "done", on the paper of its kind with the priority tape (no "AI" mark - author 26.11.2026).
 * Every change goes to [onChange] at once. A list shows [voiceKey] ("Add by voice") next to "Add item".
 */
@Composable
fun EditableCard(
    note: Note,
    number: Int?,
    onChange: (Note) -> Unit,
    modifier: Modifier = Modifier,
    voiceKey: (@Composable () -> Unit)? = null,
) {
    // Another type is another paper: the old sheet fades out, then the new one fades in (author 21.11.2026).
    val still = TapedoTheme.reduceMotion
    AnimatedContent(
        targetState = note.type,
        modifier = modifier,
        transitionSpec = { fadeSwap(still).let { if (still) it using null else it } },
        contentAlignment = Alignment.TopCenter,
        label = "card paper",
    ) { shownType ->
        CardSheet(if (shownType == note.type) note else note.copy(type = shownType), number, onChange, voiceKey)
    }
}

@Composable
private fun CardSheet(
    note: Note,
    number: Int?,
    onChange: (Note) -> Unit,
    voiceKey: (@Composable () -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val locale = appLocale()
    val today = rememberToday()
    val type = note.type
    // A crooked priority tape sticks out about 14 dp above the card, and the scrolling screen cut its top off
    // (author 14.11.2026) - the card keeps that room inside its own bounds. Straight tapes fit already.
    val tapeRoom = if (type != NoteType.IDEA && TapedoTheme.tilt(1f) != 0f) 14.dp else 0.dp
    // Centred: the receipt and the idea slip are narrower than the screen.
    Box(Modifier.fillMaxWidth().padding(top = tapeRoom), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxWidth(paperWidth(type))) {
            // The gap above each part sits inside it, so a part that folds away takes its gap along -
            // a shared spacedBy gap would jump in or out at the end of the fold.
            CardPaper(
                note,
                Modifier.padding(top = 6.dp, end = 6.dp),
                verticalArrangement = Arrangement.Top,
            ) {
                // The kind in small capitals, numbered like on the stack; date and time have their own lines below.
                // A list has no such header: its number is the title field's hint instead (author 26.11.2026).
                val kind = if (number != null && type != NoteType.EVENT) numberedName(type, number) else stringResource(typeNameRes(type))
                val listTitle = type == NoteType.CHECKLIST
                if (!listTitle) {
                    Text(
                        kind.uppercase(locale),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 52.dp, bottom = GAP).semantics { heading() },
                    )
                }
                // Title and place wrap onto more lines instead of scrolling sideways (author 21.11.2026);
                // still one line of meaning, so Enter makes a space.
                if (listTitle) {
                    ListTitleField(note, if (number != null) kind else stringResource(R.string.default_checklist_title), onChange)
                } else {
                    PaperField(
                        label = stringResource(R.string.editor_field_title),
                        value = note.title,
                        onValueChange = { onChange(note.copy(title = it.replace('\n', ' '))) },
                        textStyle = MaterialTheme.typography.titleLarge,
                    )
                }
                // A list is its items - an empty "text" field above them only takes room.
                // Text a list already has (e.g. from the AI) stays visible and editable.
                Fold(!note.type.hasItems || note.body.isNotBlank()) {
                    PaperField(
                        label = stringResource(R.string.editor_field_body),
                        value = note.body,
                        onValueChange = { onChange(note.copy(body = it)) },
                        textStyle = MaterialTheme.typography.bodyLarge,
                        minLines = if (note.type.hasItems) 1 else 3,
                        // A note is written on lined paper.
                        ruled = note.type == NoteType.NOTE,
                        modifier = Modifier.padding(top = GAP),
                    )
                }
                Fold(note.type.hasItems) {
                    Box(Modifier.padding(top = GAP)) { ItemsEditor(note, voiceKey) { onChange(note.copy(items = it)) } }
                }
                Fold(note.type == NoteType.EVENT) {
                    PaperField(
                        label = stringResource(R.string.editor_field_place),
                        value = note.place.orEmpty(),
                        onValueChange = { onChange(note.copy(place = it.replace('\n', ' '))) },
                        textStyle = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = GAP),
                    )
                }
                FoldValue(note.dueEpochDay, Modifier.padding(top = GAP)) { day ->
                    Removable(
                        label = stringResource(R.string.editor_due, dueText(context, day, today, locale)),
                        removeLabel = stringResource(R.string.editor_remove_due),
                        onRemove = { onChange(note.copy(dueEpochDay = null)) },
                    )
                }
                FoldValue(note.timeMinutes, Modifier.padding(top = GAP)) { minutes ->
                    Removable(
                        label = stringResource(R.string.editor_time, clockTime(minutes)),
                        removeLabel = stringResource(R.string.editor_remove_time),
                        onRemove = { onChange(note.copy(timeMinutes = null)) },
                    )
                }
                Fold(note.type == NoteType.TASK, Modifier.padding(top = GAP)) {
                    DoneToggle(note.done) { onChange(note.copy(done = it)) }
                }
            }
            // Ideas have no priority, so no tape.
            if (type != NoteType.IDEA) {
                val priorityDesc = stringResource(R.string.card_priority_desc, priorityName(note.priority))
                PriorityTape(
                    note.priority,
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = 4.dp)
                        .semantics { contentDescription = priorityDesc },
                )
            }
        }
    }
}

/**
 * Under the card: a wide "Show settings" key (author 15.11.2026: the card itself comes first), and behind it
 * its type, its priority (not for an idea), then "Share" and "Delete card" as the last keys.
 */
@Composable
fun CardOptions(
    note: Note,
    onChange: (Note) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onOpened: () -> Unit = {},
) {
    val context = LocalContext.current
    val locale = appLocale()
    val today = rememberToday()
    var open by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Column(modifier) {
        PaperButton(
            stringResource(if (open) R.string.editor_options_hide else R.string.editor_options_show),
            onClick = {
                // With the keyboard up the unfolding happened behind it and the key seemed to do nothing
                // (author 21.11.2026) - the keyboard slides away first.
                keyboard?.hide()
                focus.clearFocus()
                open = !open
                if (open) onOpened()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        // The type row reaches out to the screen's edges, but unfolding clips to the fold's own bounds: the fold
        // reaches out as far, or the row's ends popped in only after the motion (author 21.11.2026).
        Fold(open, Modifier.reachOut(TAPE_BLEED)) {
        Column(Modifier.padding(horizontal = TAPE_BLEED).padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LabelTape(stringResource(R.string.editor_field_type))
            TypeTapeBar(
                selected = note.type,
                onSelect = { type -> if (type != null) onChange(note.changeType(type)) },
                includeAuto = false,
                bleed = TAPE_BLEED,
                // The first tape starts under the "Type" label, not further in (author 21.11.2026).
                inset = 0.dp,
            )
        }
        // Ideas have no priority: the selector folds away when the type turns into one.
        Fold(note.type != NoteType.IDEA) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LabelTape(stringResource(R.string.editor_field_priority))
                SlideSelector(
                    options = Priority.entries,
                    selected = note.priority,
                    label = { priorityName(it) },
                    onSelect = { onChange(note.copy(priority = it)) },
                )
            }
        }
        PairOrStack(Modifier.fillMaxWidth()) {
            PaperButton(
                stringResource(R.string.card_share),
                onClick = { shareNote(context, note.tidy(), today, locale) },
            )
            PaperButton(
                stringResource(R.string.editor_delete),
                onClick = onDelete,
                style = PaperButtonStyle.DANGER,
            )
        }
        }
        }
    }
}

/**
 * Switching the type keeps what was said. To a list: the text becomes list items, one per line or comma.
 * From a list to a kind without items: every item becomes a line of the text (a ticked one starts with "✓"),
 * so nothing hides from view while search and "Share" still find it (author 15.11.2026).
 */
internal fun Note.changeType(type: NoteType): Note {
    if (type == this.type) return this
    if (!type.hasItems && items.isNotEmpty()) {
        val lines = items.map { if (it.checked) "$TICKED${it.text}" else it.text }
        return copy(type = type, body = (listOf(body.trimEnd()).filter { it.isNotEmpty() } + lines).joinToString("\n"), items = emptyList())
    }
    if (type.hasItems && items.isEmpty() && body.isNotBlank()) {
        val parts = body.split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size > 1) {
            val group = if (type == NoteType.SHOPPING) ShoppingGroup.OTHER else null
            return copy(
                type = type,
                items = parts.map { ChecklistItem(it.removePrefix(TICKED).trim(), checked = it.startsWith(TICKED), group = group) },
                body = "",
            )
        }
    }
    return copy(type = type)
}

/** The card screen's side margins (12 dp) and the options' own (4 dp) - how far the type row reaches out. */
private val TAPE_BLEED = 16.dp

/** Lays the content out [by] wider on both sides than the room it is given, overhanging it evenly. */
private fun Modifier.reachOut(by: Dp): Modifier = layout { measurable, constraints ->
    val out = by.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = 2 * out))
    layout(placeable.width - 2 * out, placeable.height) { placeable.place(-out, 0) }
}

/** How a ticked list item is written in the text of a card that has no items. */
private const val TICKED = "✓ "

/**
 * A list's own name, with its number as the hint ("Lista 2") while it has none (author 26.11.2026). The card
 * keeps the plain kind name then, so the stack numbers it; a typed name shows everywhere instead.
 */
@Composable
private fun ListTitleField(note: Note, numbered: String, onChange: (Note) -> Unit) {
    val names = kindNames(note.type)
    val plain = stringResource(R.string.default_checklist_title)
    fun stored(text: String) = text.replace('\n', ' ').ifBlank { plain }
    fun shown() = if (hasOwnListName(note, names)) note.title else ""
    var text by remember(note.id) { mutableStateOf(shown()) }
    // Titles this field wrote itself. The card comes back a frame late while typing, so an older one of these is
    // not news - taking it as a title from outside ate the last letters of fast typing.
    val mine = remember(note.id) { mutableSetOf(note.title) }
    // A title set from outside (the AI's version taken) comes into the field.
    LaunchedEffect(note.title) {
        if (note.title !in mine) {
            text = shown()
            mine.clear()
            mine += note.title
        }
    }
    PaperField(
        label = stringResource(R.string.editor_field_title),
        value = text,
        onValueChange = {
            text = it.replace('\n', ' ')
            val title = stored(it)
            mine += title
            onChange(note.copy(title = title))
        },
        textStyle = MaterialTheme.typography.titleLarge,
        placeholder = numbered,
    )
}

/** A label and a line to write on - no boxed Material field. The line thickens while typing. */
@Composable
private fun PaperField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    textStyle: TextStyle,
    singleLine: Boolean = false,
    minLines: Int = 1,
    ruled: Boolean = false,
    modifier: Modifier = Modifier,
    /** Shown faded while the field is empty; it fades out with the first letter. */
    placeholder: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val rule = TapedoTheme.colors.rule
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = textStyle.copy(color = scheme.onSurface),
            singleLine = singleLine,
            minLines = minLines,
            cursorBrush = SolidColor(scheme.primary),
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .semantics { contentDescription = label },
            decorationBox = { inner ->
                Column {
                    // Ruled: a faint rule between the lines, spaced by the text's own lines (any text size);
                    // the line below the field is the last rule.
                    Box(
                        if (ruled) {
                            Modifier.drawBehind {
                                val text = layout ?: return@drawBehind
                                // Each rule under its own line: the first line is taller than the next ones,
                                // so one step from it drifted into the words from the third line on.
                                val n = text.lineCount
                                val step = when {
                                    n >= 2 -> text.getLineBottom(1) - text.getLineTop(1)
                                    textStyle.lineHeight.isSp -> textStyle.lineHeight.toPx()
                                    else -> text.getLineBottom(0) - text.getLineTop(0)
                                }
                                val lines = maxOf(n, minLines)
                                for (i in 1 until lines) {
                                    val y = if (i < n) text.getLineBottom(i - 1) else text.getLineBottom(n - 1) + step * (i - n)
                                    drawLine(rule, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                                }
                            }
                        } else {
                            Modifier
                        },
                    ) {
                        if (placeholder != null) {
                            val hint by animateFloatAsState(
                                targetValue = if (value.isEmpty()) 1f else 0f,
                                animationSpec = if (TapedoTheme.reduceMotion) snap() else tween(200),
                                label = "field hint",
                            )
                            if (hint > 0f) {
                                Text(
                                    placeholder,
                                    style = textStyle,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.graphicsLayer { alpha = hint }.clearAndSetSemantics { },
                                )
                            }
                        }
                        inner()
                    }
                    Spacer(Modifier.height(6.dp))
                    val still = TapedoTheme.reduceMotion
                    val lineHeight by animateDpAsState(if (focused) 2.dp else 1.dp, if (still) snap() else tween(200), label = "field line")
                    val lineColor by animateColorAsState(if (focused) scheme.primary else scheme.outline, if (still) snap() else tween(200), label = "field ink")
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(lineHeight)
                            .background(lineColor),
                    )
                }
            },
        )
    }
}

/**
 * The list items as ruled lines: tick box, text and x sit on one line, with the rule under the whole row -
 * not under the text field alone, which centred the rule on the tick box and left the text floating above it.
 * A shopping list keeps its shop sections ("NABIAŁ"), printed like on a till receipt.
 */
@Composable
private fun ItemsEditor(note: Note, voiceKey: (@Composable () -> Unit)?, onChange: (List<ChecklistItem>) -> Unit) {
    val items = note.items
    val shopping = note.type == NoteType.SHOPPING
    val scheme = MaterialTheme.colorScheme
    val locale = appLocale()
    val still = TapedoTheme.reduceMotion
    val latestItems by rememberUpdatedState(items)
    val latestOnChange by rememberUpdatedState(onChange)
    // "Add item" puts the cursor on the new line, so typing can start at once.
    var focusIndex by remember { mutableStateOf<Int?>(null) }
    val newItem = remember { FocusRequester() }
    LaunchedEffect(focusIndex, items.size) {
        if (focusIndex != null && focusIndex in items.indices) {
            newItem.requestFocus()
            focusIndex = null
        }
    }
    // Items have no ids, so the editor keeps its own key per line: a line that comes or goes folds in or out
    // instead of the lines below it jumping (author 21.11.2026). The change itself reaches the card a frame
    // later, through the view model - the keys follow it then.
    val rows = remember { ItemRows() }
    rows.follow(items.size)
    var leaving by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(leaving) {
        val key = leaving ?: return@LaunchedEffect
        if (!still) delay(ITEM_FOLD_MS.toLong())
        val at = rows.indexOf(key)
        if (at >= 0 && at in latestItems.indices) {
            rows.drop(key)
            latestOnChange(latestItems.filterIndexed { j, _ -> j != at })
        }
        leaving = null
    }
    Column {
        for ((group, inGroup) in groupedItems(note)) {
            if (group != null) {
                // A shop section folds away with its last item and folds in with its first.
                key("group", group) {
                    ItemFold(
                        visible = inGroup.any { !rows.gone(rows.keyAt(it.index), leaving) },
                        isNew = rows.isNewGroup(),
                    ) {
                        Column {
                            Spacer(Modifier.height(14.dp))
                            Text(
                                groupName(group).uppercase(locale),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp).semantics { heading() },
                            )
                        }
                    }
                }
            }
            for ((i, item) in inGroup) key(rows.keyAt(i)) {
                val rowKey = rows.keyAt(i)
                val itemLabel = stringResource(R.string.editor_item_label, i + 1)
                val rule = scheme.outlineVariant
                ItemFold(visible = !rows.gone(rowKey, leaving), isNew = rows.isNew(rowKey)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .drawBehind {
                            val y = size.height - 0.5.dp.toPx()
                            drawLine(rule, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val state = stringResource(if (item.checked) R.string.card_item_checked else R.string.card_item_unchecked)
                    Box(
                        Modifier
                            .size(44.dp)
                            .toggleable(
                                value = item.checked,
                                role = Role.Checkbox,
                                onValueChange = { checked -> onChange(items.mapIndexed { j, it -> if (j == i) it.copy(checked = checked) else it }) },
                            )
                            .semantics {
                                contentDescription = itemLabel
                                stateDescription = state
                            },
                        contentAlignment = Alignment.Center,
                    ) { PencilBox(item.checked, scheme.onSurface) }
                    // A ticked item is struck through with the pencil line, drawn on and wiped off (author 21.11.2026).
                    var itemLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
                    // A long item wraps onto more lines instead of scrolling sideways out of view (author
                    // 21.11.2026); a line break would split one item into a strange two-line one, so it is a space.
                    BasicTextField(
                        value = item.text,
                        onTextLayout = { itemLayout = it },
                        onValueChange = { text ->
                            val oneItem = text.replace('\n', ' ')
                            onChange(items.mapIndexed { j, it -> if (j == i) it.copy(text = oneItem) else it })
                        },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                        cursorBrush = SolidColor(scheme.primary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 10.dp)
                            .then(if (i == items.lastIndex) Modifier.focusRequester(newItem) else Modifier)
                            .handStrike(item.checked, { itemLayout }, scheme.onSurface)
                            .semantics { contentDescription = itemLabel },
                    )
                    val removeLabel = stringResource(R.string.editor_remove_item, i + 1)
                    IconButton(
                        onClick = { if (leaving == null) leaving = rowKey },
                        modifier = Modifier.size(44.dp).semantics { contentDescription = removeLabel },
                    ) {
                        Text(
                            "×",
                            style = MaterialTheme.typography.titleLarge,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                    }
                }
                }
            }
        }
        // Room above the keys, so they do not sit on the last item's ruled line (author 21.11.2026).
        PairOrStack(Modifier.fillMaxWidth().padding(top = 20.dp), stackGap = 8.dp) {
            PaperButton(
                stringResource(R.string.editor_add_item),
                onClick = {
                    onChange(items + ChecklistItem("", group = if (shopping) ShoppingGroup.OTHER else null))
                    focusIndex = items.size
                },
            )
            voiceKey?.invoke()
        }
    }
}

private const val ITEM_FOLD_MS = 200

/** Room between the parts of the card. */
private val GAP = 16.dp

/**
 * The editor's own key for each list line, kept in step with the card's items. A line added after the editor
 * opened is "new" (it folds in); a removed line keeps its key until the shorter list arrives, so the line
 * below does not take its place for a frame. Plain fields: read and changed only during composition.
 */
private class ItemRows {
    private val keys = mutableListOf<Long>()
    private val fresh = mutableSetOf<Long>()
    private var next = 0L
    private var frames = 0
    private var dropping: Long? = null

    fun follow(size: Int) {
        frames++
        dropping?.let { key ->
            if (size == keys.size - 1) {
                keys.remove(key)
                dropping = null
            }
        }
        while (keys.size < size) {
            val key = next++
            keys += key
            if (frames > 1) fresh += key
        }
        while (keys.size > size) keys.removeAt(keys.lastIndex)
    }

    fun keyAt(index: Int): Long = keys[index]
    fun indexOf(key: Long): Int = keys.indexOf(key)
    fun isNew(key: Long): Boolean = key in fresh
    fun isNewGroup(): Boolean = frames > 1

    /** The line is gone from the card; its key goes when the card's shorter list comes back. */
    fun drop(key: Long) {
        dropping = key
    }

    fun gone(key: Long, leaving: Long?): Boolean = key == leaving || key == dropping
}

/** A list line or shop section that unfolds when it comes and folds away when it goes. */
@Composable
private fun ItemFold(visible: Boolean, isNew: Boolean, content: @Composable () -> Unit) {
    val still = TapedoTheme.reduceMotion
    // Target set at once, so a new line is laid out in its first frame (the cursor can go to it right away).
    val state = remember { MutableTransitionState(!isNew || still).apply { targetState = visible } }
    SideEffect { state.targetState = visible }
    AnimatedVisibility(
        visibleState = state,
        enter = if (still) EnterTransition.None
        else expandVertically(tween(ITEM_FOLD_MS), expandFrom = Alignment.Top) + fadeIn(tween(ITEM_FOLD_MS)),
        exit = if (still) ExitTransition.None
        else shrinkVertically(tween(ITEM_FOLD_MS), shrinkTowards = Alignment.Top) + fadeOut(tween(ITEM_FOLD_MS)),
    ) { content() }
}

@Composable
private fun Removable(label: String, removeLabel: String, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        PaperButton(removeLabel, onClick = onRemove)
    }
}

@Composable
private fun DoneToggle(done: Boolean, onChange: (Boolean) -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = done, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PencilBox(done, ink)
        Text(stringResource(R.string.editor_task_done), style = MaterialTheme.typography.bodyLarge)
    }
}
