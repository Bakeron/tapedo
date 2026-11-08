package io.github.bakeron.tapedo.ui.cards

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bakeron.tapedo.R
import io.github.bakeron.tapedo.core.model.ChecklistItem
import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.ui.components.RubberStamp
import io.github.bakeron.tapedo.ui.settings.priorityName
import io.github.bakeron.tapedo.ui.theme.TapedoTheme
import java.time.LocalDate

/**
 * One index card: type and date in small capitals, the title, typed text or ticks on the paper of its kind
 * (see [CardPaper]), a priority tape on the corner and a blue "AI" stamp when the AI wrote it.
 *
 * @param compact the stack shows a few lines; the review window shows everything.
 * @param onToggleItem / [onToggleDone] null = read-only (e.g. a card not saved yet).
 */
@Composable
fun IndexCard(
    note: Note,
    modifier: Modifier = Modifier,
    compact: Boolean = true,
    tilt: Float = 0f,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onToggleItem: ((Int) -> Unit)? = null,
    onToggleDone: (() -> Unit)? = null,
) {
    val colors = TapedoTheme.colors
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val locale = appLocale()
    val today = remember { LocalDate.now().toEpochDay() }
    val type = note.type

    // Centred: the receipt and the idea slip are narrower than the row they lie in.
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        // An idea slip hangs on its pin a little more crooked than the other cards.
        Box(Modifier.fillMaxWidth(paperWidth(type)).rotate(tilt + if (type == NoteType.IDEA) -1.8f else 0f)) {
            CardPaper(
                note,
                Modifier.padding(top = 6.dp, end = 6.dp),
                clickable = if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier,
            ) {
                // Header: type (and date) on the left, the AI stamp on the right. The calendar page and the
                // task's due field carry the date themselves, so it is not repeated here.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val meta = when (type) {
                        NoteType.EVENT -> note.place.orEmpty()
                        NoteType.TASK -> ""
                        else -> metaLine(context, note, today, locale)
                    }
                    Text(
                        (stringResource(typeNameRes(type)) + if (meta.isNotEmpty()) "  ·  $meta" else "").uppercase(locale),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(end = 52.dp),
                        maxLines = 2,
                    )
                }
                Spacer(Modifier.padding(top = 6.dp))

                // A lined note: the title sits on the red header line, like on an index card.
                TitleLine(note, onToggleDone, if (type == NoteType.NOTE) Modifier.headerRule(colors.record.copy(alpha = 0.6f)) else Modifier)

                val body = note.body.isNotBlank() && !bodyRepeatsTitle(note)
                when {
                    type == NoteType.NOTE -> {
                        Spacer(Modifier.padding(top = 6.dp))
                        if (body) {
                            RuledText(
                                note.body,
                                MaterialTheme.typography.bodyMedium,
                                scheme.onSurface,
                                colors.rule,
                                maxLines = if (compact) 4 else Int.MAX_VALUE,
                            )
                        }
                        // The paper goes on after the text - also when the note is only its title.
                        BlankLines(if (body) 1 else 2, MaterialTheme.typography.bodyMedium, colors.rule)
                    }
                    body -> Text(
                        note.body,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (compact) 4 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            // Lists and receipts have their own ruling; the idea slip is plain.
                            .then(
                                if (type.hasItems || type == NoteType.IDEA) Modifier else Modifier.marginLine(colors.record.copy(alpha = 0.5f)),
                            ),
                    )
                }

                if (note.items.isNotEmpty()) {
                    Spacer(Modifier.padding(top = 4.dp))
                    val limit = if (compact) 6 else Int.MAX_VALUE
                    var shown = 0
                    for ((group, items) in groupedItems(note)) {
                        if (shown >= limit) break
                        if (group != null) {
                            // Shop sections printed like a till receipt: a dashed cut and the section in caps.
                            Spacer(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).receiptRule(scheme.outline))
                            Text(
                                groupName(group).uppercase(locale),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        for ((index, item) in items) {
                            if (shown >= limit) break
                            ItemRow(item, onToggle = onToggleItem?.let { { it(index) } })
                            shown++
                        }
                    }
                    if (note.items.size > shown) {
                        Text(
                            pluralStringResource(R.plurals.card_more_items, note.items.size - shown, note.items.size - shown),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                if (type == NoteType.TASK) DueField(note, Modifier.padding(top = 12.dp))

                // Pressed on the bottom corner, a little crooked - like an office stamp.
                if (note.byAi) {
                    RubberStamp(
                        stringResource(R.string.card_ai_stamp),
                        Modifier
                            .align(Alignment.End)
                            .padding(top = 8.dp)
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

@Composable
private fun TitleLine(note: Note, onToggleDone: (() -> Unit)?, rule: Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val struck = note.type == NoteType.TASK && note.done
    val title = @Composable { mod: Modifier ->
        Text(
            note.title,
            style = MaterialTheme.typography.titleMedium,
            onTextLayout = { layout = it },
            modifier = mod.handStrike(struck, { layout }, ink),
        )
    }
    if (note.type == NoteType.TASK && onToggleDone != null) {
        val state = stringResource(if (note.done) R.string.card_done else R.string.card_not_done)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = note.done, role = Role.Checkbox, onValueChange = { onToggleDone() })
                .semantics { stateDescription = state },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PencilBox(note.done, ink)
            Spacer(Modifier.width(10.dp))
            title(Modifier.weight(1f))
        }
    } else {
        Row(rule.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (note.type == NoteType.TASK) {
                PencilBox(note.done, ink)
                Spacer(Modifier.width(10.dp))
            }
            title(Modifier.padding(end = 40.dp))
        }
    }
}

@Composable
private fun ItemRow(item: ChecklistItem, onToggle: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    val ink = if (item.checked) scheme.onSurfaceVariant else scheme.onSurface
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val state = stringResource(if (item.checked) R.string.card_item_checked else R.string.card_item_unchecked)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (onToggle != null) 48.dp else 32.dp)
            .then(
                if (onToggle != null) {
                    Modifier.toggleable(value = item.checked, role = Role.Checkbox, onValueChange = { onToggle() })
                } else {
                    Modifier
                },
            )
            .semantics { stateDescription = state },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PencilBox(item.checked, ink)
        Text(
            item.text,
            style = MaterialTheme.typography.bodyMedium,
            color = ink,
            onTextLayout = { layout = it },
            modifier = Modifier.handStrike(item.checked, { layout }, scheme.onSurface),
        )
    }
}
