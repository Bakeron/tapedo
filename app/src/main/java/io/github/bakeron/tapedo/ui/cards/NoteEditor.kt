package io.github.bakeron.tapedo.ui.cards

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.bakeron.tapedo.R
import io.github.bakeron.tapedo.core.model.ChecklistItem
import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.model.Priority
import io.github.bakeron.tapedo.core.model.ShoppingGroup
import io.github.bakeron.tapedo.ui.components.LabelTape
import io.github.bakeron.tapedo.ui.components.PaperButton
import io.github.bakeron.tapedo.ui.components.PaperButtonStyle
import io.github.bakeron.tapedo.ui.components.SlideSelector
import io.github.bakeron.tapedo.ui.home.TypeTapeBar
import io.github.bakeron.tapedo.ui.settings.priorityName
import io.github.bakeron.tapedo.ui.theme.TapedoTheme
import java.time.LocalDate

/**
 * Full-screen editor for one card - the card itself, enlarged, with its lines to write on.
 * Back and "Done" both keep the changes (notes are kept by default); "Delete" offers undo on the stack.
 */
@Composable
fun NoteEditor(
    note: Note,
    saveFailed: Boolean,
    onChange: (Note) -> Unit,
    onDone: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = TapedoTheme.colors
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val locale = appLocale()
    val today = remember { LocalDate.now().toEpochDay() }
    BackHandler(onBack = onDone)

    Column(
        Modifier
            .fillMaxSize()
            .background(scheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back))
            }
            Text(
                stringResource(R.string.editor_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            PaperButton(
                stringResource(R.string.editor_done),
                onClick = onDone,
                style = PaperButtonStyle.FILLED,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        if (saveFailed) {
            Text(
                stringResource(R.string.stack_action_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.record,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // The card being edited.
            val shape = RoundedCornerShape(3.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.card, shape)
                    .border(1.dp, colors.cardEdge, shape)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PaperField(
                    label = stringResource(R.string.editor_field_title),
                    value = note.title,
                    onValueChange = { onChange(note.copy(title = it)) },
                    textStyle = MaterialTheme.typography.titleLarge,
                    singleLine = true,
                )
                PaperField(
                    label = stringResource(R.string.editor_field_body),
                    value = note.body,
                    onValueChange = { onChange(note.copy(body = it)) },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    minLines = 3,
                )
                if (note.type.hasItems) {
                    ItemsEditor(note.items, note.type == NoteType.SHOPPING) { onChange(note.copy(items = it)) }
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
            }

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

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PaperButton(
                    stringResource(R.string.card_share),
                    onClick = { shareNote(context, note, today, locale) },
                    modifier = Modifier.weight(1f),
                )
                PaperButton(
                    stringResource(R.string.editor_delete),
                    onClick = onDelete,
                    style = PaperButtonStyle.DANGER,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Switching to a list keeps what was said: the text becomes list items, one per line or comma. */
private fun Note.changeType(type: NoteType): Note {
    if (type == this.type) return this
    if (type.hasItems && items.isEmpty() && body.isNotBlank()) {
        val parts = body.split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size > 1) {
            return copy(type = type, items = parts.map { ChecklistItem(it, group = if (type == NoteType.SHOPPING) ShoppingGroup.OTHER else null) }, body = "")
        }
    }
    return copy(type = type)
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
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .semantics { contentDescription = label },
            decorationBox = { inner ->
                Column {
                    inner()
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

@Composable
private fun ItemsEditor(items: List<ChecklistItem>, shopping: Boolean, onChange: (List<ChecklistItem>) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.editor_field_items), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        items.forEachIndexed { i, item ->
            val itemLabel = stringResource(R.string.editor_item_label, i + 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val state = stringResource(if (item.checked) R.string.card_item_checked else R.string.card_item_unchecked)
                IconButton(
                    onClick = { onChange(items.mapIndexed { j, it -> if (j == i) it.copy(checked = !it.checked) else it }) },
                    modifier = Modifier.semantics {
                        contentDescription = itemLabel
                        stateDescription = state
                    },
                ) { PencilBox(item.checked, scheme.onSurface) }
                BasicTextField(
                    value = item.text,
                    onValueChange = { text -> onChange(items.mapIndexed { j, it -> if (j == i) it.copy(text = text) else it }) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                    singleLine = true,
                    cursorBrush = SolidColor(scheme.primary),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .semantics { contentDescription = itemLabel },
                    decorationBox = { inner ->
                        Column {
                            inner()
                            Spacer(Modifier.height(4.dp))
                            Spacer(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
                        }
                    },
                )
                val removeLabel = stringResource(R.string.editor_remove_item, i + 1)
                IconButton(
                    onClick = { onChange(items.filterIndexed { j, _ -> j != i }) },
                    modifier = Modifier.semantics { contentDescription = removeLabel },
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
        PaperButton(
            stringResource(R.string.editor_add_item),
            onClick = { onChange(items + ChecklistItem("", group = if (shopping) ShoppingGroup.OTHER else null)) },
        )
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
