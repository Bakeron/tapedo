package io.github.bakeron.tapedo.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bakeron.tapedo.R
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.model.Priority
import io.github.bakeron.tapedo.core.rules.ListTitles
import io.github.bakeron.tapedo.core.settings.AiProvider
import io.github.bakeron.tapedo.core.settings.AppSettings
import io.github.bakeron.tapedo.core.settings.OnlineSpeech
import io.github.bakeron.tapedo.core.settings.RecordingEnd
import io.github.bakeron.tapedo.core.settings.ThemeMode
import io.github.bakeron.tapedo.locale.AppLanguage
import io.github.bakeron.tapedo.ui.cards.RuledText
import io.github.bakeron.tapedo.ui.components.CounterSlider
import io.github.bakeron.tapedo.ui.components.ToggleLever
import io.github.bakeron.tapedo.ui.components.Tick
import io.github.bakeron.tapedo.ui.components.LabelTape
import io.github.bakeron.tapedo.ui.components.PaperButton
import io.github.bakeron.tapedo.ui.components.SlideSelector
import io.github.bakeron.tapedo.ui.theme.TapedoTheme
import kotlin.math.roundToInt

@Composable
fun SettingsRoute(viewModel: SettingsViewModel, onBack: () -> Unit, onOpenLicenses: () -> Unit = {}) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val keys by viewModel.keys.collectAsStateWithLifecycle()
    val saveFailed by viewModel.saveFailed.collectAsStateWithLifecycle()
    val sample = stringResource(R.string.ai_test_sample)
    val listTitles = ListTitles(
        shopping = stringResource(R.string.default_shopping_title),
        checklist = stringResource(R.string.default_checklist_title),
    )
    settings?.let {
        SettingsScreen(
            settings = it,
            onChange = viewModel::update,
            onBack = onBack,
            onOpenLicenses = onOpenLicenses,
            saveFailed = saveFailed,
            aiSection = {
                AiSection(
                    provider = it.aiProvider,
                    keys = keys,
                    onProvider = { p -> viewModel.update { s -> s.copy(aiProvider = p) } },
                    onEditKey = { text -> viewModel.editKey(it.aiProvider, text) },
                    onSaveKey = { key -> viewModel.saveKey(it.aiProvider, key) },
                    onDeleteKey = { viewModel.deleteKey(it.aiProvider) },
                    onTest = { viewModel.testKey(it.aiProvider, sample, listTitles) },
                )
            },
        )
    }
}

@Composable
private fun ColumnScope.AiSection(
    provider: AiProvider,
    keys: AiKeyState,
    onProvider: (AiProvider) -> Unit,
    onEditKey: (String) -> Unit,
    onSaveKey: (String) -> Unit,
    onDeleteKey: () -> Unit,
    onTest: () -> Unit,
) {
    Setting(title = stringResource(R.string.ai_provider)) {
        SlideSelector(
            options = AiProvider.entries,
            selected = provider,
            label = {
                when (it) {
                    AiProvider.NONE -> stringResource(R.string.ai_none)
                    AiProvider.GEMINI -> stringResource(R.string.ai_gemini)
                    AiProvider.GROQ -> stringResource(R.string.ai_groq)
                }
            },
            onSelect = onProvider,
        )
    }
    Rule()
    Column(Modifier.padding(vertical = 10.dp)) {
        if (provider == AiProvider.NONE) {
            RuledText(
                stringResource(R.string.ai_none_hint),
                MaterialTheme.typography.bodySmall,
                MaterialTheme.colorScheme.onSurfaceVariant,
                TapedoTheme.colors.rule,
            )
        } else {
            AiProviderCard(provider, keys, onEditKey, onSaveKey, onDeleteKey, onTest)
        }
    }
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit = {},
    saveFailed: Boolean = false,
    aiSection: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back))
            }
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        }
        if (saveFailed) {
            Text(
                stringResource(R.string.settings_save_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = TapedoTheme.colors.record,
                // Read out by TalkBack as soon as it appears.
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Section(stringResource(R.string.settings_section_recording)) {
                Setting(
                    title = stringResource(R.string.settings_recording_end),
                    hint = stringResource(R.string.settings_recording_end_hint),
                ) {
                    SlideSelector(
                        options = RecordingEnd.entries,
                        selected = settings.recordingEnd,
                        label = {
                            when (it) {
                                RecordingEnd.SILENCE -> stringResource(R.string.settings_recording_end_silence)
                                RecordingEnd.STOP_BUTTON -> stringResource(R.string.settings_recording_end_stop)
                            }
                        },
                        onSelect = { v -> onChange { it.copy(recordingEnd = v) } },
                    )
                }
                Rule()
                Setting(
                    title = stringResource(R.string.settings_max_length),
                    hint = stringResource(R.string.settings_max_length_hint),
                ) {
                    CounterSlider(
                        options = AppSettings.MAX_RECORDING_OPTIONS,
                        selected = settings.maxRecordingSeconds,
                        label = { s ->
                            if (s < 60) stringResource(R.string.settings_seconds, s)
                            else stringResource(R.string.settings_minutes, s / 60)
                        },
                        // Screen readers get "12 minut", not "12 min".
                        spokenLabel = { s ->
                            if (s < 60) pluralStringResource(R.plurals.duration_seconds, s, s)
                            else pluralStringResource(R.plurals.duration_minutes, s / 60, s / 60)
                        },
                        // Scale under the track: short tick every 5 min, long every 15 min.
                        tick = { s -> if (s % 900 == 0) Tick.MAJOR else if (s % 300 == 0) Tick.MINOR else Tick.NONE },
                        onSelect = { v -> onChange { it.copy(maxRecordingSeconds = v) } },
                    )
                }
                Rule()
                Setting(
                    title = stringResource(R.string.settings_speech_language),
                    hint = stringResource(R.string.settings_speech_language_hint),
                    more = stringResource(R.string.settings_speech_language_more),
                ) {
                    SlideSelector(
                        options = AppSettings.SPEECH_LANGUAGES,
                        selected = settings.speechLanguage,
                        label = { speechLanguageName(it) },
                        onSelect = { v -> onChange { it.copy(speechLanguage = v) } },
                    )
                }
                Rule()
                Setting(
                    title = stringResource(R.string.settings_online_speech),
                    hint = stringResource(R.string.settings_online_speech_hint),
                    more = stringResource(R.string.settings_online_speech_more),
                ) {
                    SlideSelector(
                        options = OnlineSpeech.entries,
                        selected = settings.onlineSpeech,
                        label = {
                            when (it) {
                                OnlineSpeech.ASK -> stringResource(R.string.settings_online_speech_ask)
                                OnlineSpeech.ALWAYS -> stringResource(R.string.settings_online_speech_always)
                                OnlineSpeech.NEVER -> stringResource(R.string.settings_online_speech_never)
                            }
                        },
                        onSelect = { v -> onChange { it.copy(onlineSpeech = v) } },
                    )
                }
            }

            Section(stringResource(R.string.settings_section_ai), content = aiSection)

            // The note type is picked on the home screen, right before recording.
            Section(stringResource(R.string.settings_section_notes)) {
                Setting(
                    title = stringResource(R.string.settings_default_priority),
                    hint = stringResource(R.string.settings_default_priority_hint),
                    more = stringResource(R.string.settings_default_priority_more),
                ) {
                    SlideSelector(
                        options = Priority.entries,
                        selected = settings.defaultPriority,
                        label = { priorityName(it) },
                        onSelect = { v -> onChange { it.copy(defaultPriority = v) } },
                    )
                }
            }

            Section(stringResource(R.string.settings_section_look)) {
                Setting(
                    title = stringResource(R.string.settings_text_size),
                    hint = stringResource(R.string.settings_text_size_hint),
                ) {
                    SlideSelector(
                        options = AppSettings.TEXT_SCALES,
                        selected = settings.textScale,
                        label = { stringResource(R.string.settings_percent, (it * 100).roundToInt()) },
                        onSelect = { v -> onChange { it.copy(textScale = v) } },
                    )
                }
                Rule()
                Setting(title = stringResource(R.string.settings_theme)) {
                    SlideSelector(
                        options = ThemeMode.entries,
                        selected = settings.theme,
                        label = {
                            when (it) {
                                ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                                ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                                ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                            }
                        },
                        onSelect = { v -> onChange { it.copy(theme = v) } },
                    )
                }
                Rule()
                Setting(title = stringResource(R.string.settings_app_language)) {
                    // Read on every composition: switching recreates the activity anyway.
                    SlideSelector(
                        options = AppLanguage.ALL,
                        selected = AppLanguage.current(),
                        label = { languageName(it) },
                        onSelect = { if (it != AppLanguage.current()) AppLanguage.set(it) },
                    )
                }
                Rule()
                Setting(
                    title = stringResource(R.string.settings_reduce_motion),
                    hint = stringResource(R.string.settings_reduce_motion_hint),
                ) {
                    ToggleLever(
                        checked = settings.reduceMotion,
                        onChange = { v -> onChange { it.copy(reduceMotion = v) } },
                        stateLabel = stringResource(if (settings.reduceMotion) R.string.settings_on else R.string.settings_off),
                    )
                }
            }

            Footer(onOpenLicenses)
        }
    }
}

/** A section is one index card with a label-maker heading stuck across its top edge. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = TapedoTheme.colors
    val shape = RoundedCornerShape(4.dp)
    Column {
        LabelTape(title, Modifier.padding(start = 12.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .background(colors.card, shape)
                .border(1.dp, colors.cardEdge, shape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            content = content,
        )
    }
}

/**
 * One setting: title, control and a one-sentence hint written on the card's lines.
 * The longer explanation ([more]) waits behind a small "more" note in the margin, like a pencilled footnote.
 */
@Composable
private fun Setting(title: String, hint: String? = null, more: String? = null, control: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val rule = TapedoTheme.colors.rule
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        control()
        if (hint != null) {
            // The "more" note sits in the right margin, at the hint's last line.
            Row(verticalAlignment = Alignment.Bottom) {
                RuledText(hint, MaterialTheme.typography.bodySmall, scheme.onSurfaceVariant, rule, Modifier.weight(1f))
                if (more != null) {
                    Text(
                        stringResource(if (expanded) R.string.settings_less else R.string.settings_more),
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurface,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button) { expanded = !expanded }
                            .wrapContentHeight(Alignment.Bottom)
                            .padding(start = 12.dp, end = 4.dp, bottom = 2.dp),
                    )
                }
            }
        }
        if (more != null && expanded) {
            RuledText(more, MaterialTheme.typography.bodySmall, scheme.onSurfaceVariant, rule)
        }
    }
}

/** Ruled line between settings - the lines of the index card. */
@Composable
private fun Rule() = HorizontalDivider(thickness = 1.dp, color = TapedoTheme.colors.rule)

@Composable
private fun Footer(onOpenLicenses: () -> Unit) {
    val context = LocalContext.current
    val version = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text(
            stringResource(R.string.settings_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.settings_version, version),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        PaperButton(stringResource(R.string.settings_licenses), onClick = onOpenLicenses)
        Spacer(Modifier.height(24.dp))
    }
}

/** Speech language named in the app's language ("Angielski" in Polish), unlike the app language list. */
@Composable
fun speechLanguageName(tag: String): String = when {
    tag.startsWith("en") -> stringResource(R.string.speech_language_english)
    else -> stringResource(R.string.speech_language_polish)
}

@Composable
fun languageName(tag: String): String = when {
    tag.startsWith("en") -> stringResource(R.string.language_english)
    else -> stringResource(R.string.language_polish)
}

/** Name of a note type; null is "auto" (the rules / AI pick the type). */
@Composable
fun noteTypeName(type: NoteType?): String = stringResource(
    when (type) {
        null -> R.string.note_type_auto
        NoteType.NOTE -> R.string.note_type_note
        NoteType.CHECKLIST -> R.string.note_type_checklist
        NoteType.TASK -> R.string.note_type_task
        NoteType.IDEA -> R.string.note_type_idea
        NoteType.EVENT -> R.string.note_type_event
        NoteType.SHOPPING -> R.string.note_type_shopping
    },
)

@Composable
fun priorityName(priority: Priority): String = when (priority) {
    Priority.HIGH -> stringResource(R.string.priority_high)
    Priority.MEDIUM -> stringResource(R.string.priority_medium)
    Priority.LOW -> stringResource(R.string.priority_low)
}
