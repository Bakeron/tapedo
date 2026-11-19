package io.github.bakeron.tapedo.ui.cards

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
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
import io.github.bakeron.tapedo.R
import io.github.bakeron.tapedo.core.model.ChecklistItem
import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.model.Priority
import io.github.bakeron.tapedo.core.model.ShoppingGroup
import io.github.bakeron.tapedo.ui.components.LabelTape
import io.github.bakeron.tapedo.ui.components.PairOrStack
import io.github.bakeron.tapedo.ui.components.PaperButton
import io.github.bakeron.tapedo.ui.components.PaperButtonStyle
import io.github.bakeron.tapedo.ui.components.RubberStamp
import io.github.bakeron.tapedo.ui.components.SlideSelector
import io.github.bakeron.tapedo.ui.home.TypeTapeBar
import io.github.bakeron.tapedo.ui.home.tidy
import io.github.bakeron.tapedo.ui.settings.priorityName
import io.github.bakeron.tapedo.ui.theme.TapedoTheme

/**
 * The card in the card view, written on directly (author 13.11.2026: reading in one place and writing in
 * another was chaos): its kind on top ("Zadanie 3"), the title, text or items to tick, add and remove,
 * place, date, time and "done", on the paper of its kind with the priority tape and the AI stamp.
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
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val locale = appLocale()
    val today = rememberToday()
    val type = note.type
    // A crooked priority tape sticks out about 14 dp above the card, and the scrolling screen cut its top off
    // (author 14.11.2026) - the card keeps that room inside its own bounds. Straight tapes fit already.
    val tapeRoom = if (type != NoteType.IDEA && TapedoTheme.tilt(1f) != 0f) 14.dp else 0.dp
    // Centred: the receipt and the idea slip are narrower than the screen.
    Box(modifier.fillMaxWidth().padding(top = tapeRoom), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxWidth(paperWidth(type))) {
            // Changing the type below changes the paper at once.
            CardPaper(
                note,
                Modifier.padding(top = 6.dp, end = 6.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // The kind in small capitals, numbered like on the stack; date and time have their own lines below.
                val kind = if (number != null && type != NoteType.EVENT) numberedName(type, number) else stringResource(typeNameRes(type))
                Text(
                    kind.uppercase(locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 52.dp).semantics { heading() },
                )
                PaperField(
                    label = stringResource(R.string.editor_field_title),
                    value = note.title,
                    onValueChange = { onChange(note.copy(title = it)) },
                    textStyle = MaterialTheme.typography.titleLarge,
                    singleLine = true,
                )
                // A list is its items - an empty "text" field above them only takes room.
                // Text a list already has (e.g. from the AI) stays visible and editable.
                if (!note.type.hasItems || note.body.isNotBlank()) {
                    PaperField(
                        label = stringResource(R.string.editor_field_body),
                        value = note.body,
                        onValueChange = { onChange(note.copy(body = it)) },
                        textStyle = MaterialTheme.typography.bodyLarge,
                        minLines = if (note.type.hasItems) 1 else 3,
                        // A note is written on lined paper.
                        ruled = note.type == NoteType.NOTE,
                    )
                }
                if (note.type.hasItems) {
                    ItemsEditor(note, voiceKey) { onChange(note.copy(items = it)) }
                }
                if (note.type == NoteType.EVENT) {
                    PaperField(
                        label = stringResource(R.string.editor_field_place),
                        value = note.place.orEmpty(),
                        onValueChange = { onChange(note.copy(place = it)) },
                        textStyle = MaterialTheme.typography.bodyLarge,
                        singleLine = true,
                    )
                }
                note.dueEpochDay?.let { day ->
                    Removable(
                        label = stringResource(R.string.editor_due, dueText(context, day, today, locale)),
                        removeLabel = stringResource(R.string.editor_remove_due),
                        onRemove = { onChange(note.copy(dueEpochDay = null)) },
                    )
                }
                note.timeMinutes?.let { minutes ->
                    Removable(
                        label = stringResource(R.string.editor_time, clockTime(minutes)),
                        removeLabel = stringResource(R.string.editor_remove_time),
                        onRemove = { onChange(note.copy(timeMinutes = null)) },
                    )
                }
                if (note.type == NoteType.TASK) {
                    DoneToggle(note.done) { onChange(note.copy(done = it)) }
                }
                // Pressed on the bottom corner, like on the card in the box.
                if (note.byAi) {
                    RubberStamp(
                        stringResource(R.string.card_ai_stamp),
                        Modifier
                            .align(Alignment.End)
                            .semantics { contentDescription = context.getString(R.string.card_ai_desc) },
                    )
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
fun CardOptions(note: Note, onChange: (Note) -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val locale = appLocale()
    val today = rememberToday()
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        PaperButton(
            stringResource(if (open) R.string.editor_options_hide else R.string.editor_options_show),
            onClick = { open = !open },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!open) return@Column
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LabelTape(stringResource(R.string.editor_field_type))
            TypeTapeBar(
                selected = note.type,
                onSelect = { type -> if (type != null) onChange(note.changeType(type)) },
                includeAuto = false,
            )
        }
        if (note.type != NoteType.IDEA) {
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

/** How a ticked list item is written in the text of a card that has no items. */
private const val TICKED = "✓ "

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
                    ) { inner() }
                    Spacer(Modifier.height(6.dp))
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(if (focused) 2.dp else 1.dp)
                            .background(if (focused) scheme.primary else scheme.outline),
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
    // "Add item" puts the cursor on the new line, so typing can start at once.
    var focusIndex by remember { mutableStateOf<Int?>(null) }
    val newItem = remember { FocusRequester() }
    LaunchedEffect(focusIndex, items.size) {
        if (focusIndex != null && focusIndex in items.indices) {
            newItem.requestFocus()
            focusIndex = null
        }
    }
    Column {
        for ((group, inGroup) in groupedItems(note)) {
            if (group != null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    groupName(group).uppercase(locale),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp).semantics { heading() },
                )
            }
            for ((i, item) in inGroup) {
                val itemLabel = stringResource(R.string.editor_item_label, i + 1)
                val rule = scheme.outlineVariant
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
                    BasicTextField(
                        value = item.text,
                        onValueChange = { text -> onChange(items.mapIndexed { j, it -> if (j == i) it.copy(text = text) else it }) },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                        singleLine = true,
                        cursorBrush = SolidColor(scheme.primary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier
                            .weight(1f)
                            .then(if (i == items.lastIndex) Modifier.focusRequester(newItem) else Modifier)
                            .semantics { contentDescription = itemLabel },
                    )
                    val removeLabel = stringResource(R.string.editor_remove_item, i + 1)
                    IconButton(
                        onClick = { onChange(items.filterIndexed { j, _ -> j != i }) },
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
        PairOrStack(Modifier.fillMaxWidth().padding(top = 8.dp), stackGap = 8.dp) {
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
