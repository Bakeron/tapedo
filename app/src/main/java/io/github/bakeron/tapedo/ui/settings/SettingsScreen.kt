package io.github.bakeron.tapedo.ui.settings

import android.os.Build
import android.speech.SpeechRecognizer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bakeron.tapedo.R
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.model.Priority
import io.github.bakeron.tapedo.core.report.Problem
import io.github.bakeron.tapedo.core.settings.AppSettings
import io.github.bakeron.tapedo.core.settings.OnlineSpeech
import io.github.bakeron.tapedo.core.settings.RecordingEnd
import io.github.bakeron.tapedo.core.settings.ThemeMode
import io.github.bakeron.tapedo.core.speech.ListeningMode
import io.github.bakeron.tapedo.locale.AppLanguage
import io.github.bakeron.tapedo.report.ReportMail
import io.github.bakeron.tapedo.ui.cards.RuledText
import io.github.bakeron.tapedo.ui.components.CounterSlider
import io.github.bakeron.tapedo.ui.components.Fold
import io.github.bakeron.tapedo.ui.components.LabelTape
import io.github.bakeron.tapedo.ui.components.PaperButton
import io.github.bakeron.tapedo.ui.components.SlideSelector
import io.github.bakeron.tapedo.ui.components.Tick
import io.github.bakeron.tapedo.ui.components.ToggleLever
import io.github.bakeron.tapedo.ui.components.holdInPlace
import io.github.bakeron.tapedo.ui.theme.TapedoTheme
import kotlin.math.roundToInt

@Composable
fun SettingsRoute(viewModel: SettingsViewModel, onBack: () -> Unit, onOpenLicenses: () -> Unit = {}) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ai by viewModel.ai.collectAsStateWithLifecycle()
    val saveFailed by viewModel.saveFailed.collectAsStateWithLifecycle()
    val listeningMode by viewModel.listeningMode.collectAsStateWithLifecycle()
    val actions = remember(viewModel) {
        AiActions(
            toggle = viewModel::toggle,
            editKey = viewModel::editKey,
            showKey = viewModel::showKey,
            saveKey = viewModel::saveKey,
            deleteKey = viewModel::deleteKey,
            keyNoteSeen = viewModel::keyNoteSeen,
            setModel = viewModel::setModel,
            check = viewModel::check,
            fetchModels = viewModel::fetchModels,
            setDailyLimit = { v -> viewModel.update { it.copy(aiDailyLimit = v) } },
            setTopUp = viewModel::setTopUp,
            setMode = viewModel::setMode,
            setDayLimit = viewModel::setDayLimit,
        )
    }
    val usdRate by viewModel.usdRate.collectAsStateWithLifecycle()
    settings?.let {
        SettingsScreen(
            settings = it,
            onChange = viewModel::update,
            onBack = onBack,
            onOpenLicenses = onOpenLicenses,
            saveFailed = saveFailed,
            listeningMode = listeningMode,
            aiSection = { AiSection(it, ai, actions, usdRate) },
        )
    }
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit = {},
    saveFailed: Boolean = false,
    listeningMode: ListeningMode = ListeningMode.UNKNOWN,
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
        Fold(saveFailed) {
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

        // Settings split into dividers like the card box on the home screen (author 10.11.2026, as in
        // Mikropłatności and Tablica ofert). The open divider is kept until the app is closed (author 11.11.2026).
        var tab by rememberSaveable { mutableStateOf(lastTab) }
        SettingsTabs(tab, onSelect = {
            tab = it
            lastTab = it
        })
        // One page at a time (author 11.11.2026: two pages fading through each other looked bad):
        // the old one fades out first, only then the new one fades in.
        val reduceMotion = TapedoTheme.reduceMotion
        AnimatedContent(
            targetState = tab,
            modifier = Modifier.weight(1f).fillMaxWidth().background(TapedoTheme.colors.cardEdge),
            transitionSpec = {
                if (reduceMotion) {
                    ContentTransform(EnterTransition.None, ExitTransition.None)
                } else {
                    fadeIn(tween(TAB_IN_MS, delayMillis = TAB_OUT_MS)) togetherWith fadeOut(tween(TAB_OUT_MS))
                }
            },
            label = "settingsTab",
        ) { shown ->
            // Each divider opens at its top.
            val scroll = rememberScrollState()
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp, bottom = 24.dp)
                    // Room for the keyboard too: typing a key, the fields below it could not be scrolled into
                    // view until the keyboard was closed (author 21.11.2026).
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                when (shown) {
                    SettingsTab.RECORDING -> {
                        Section(null) {
                            val onSilence = stringResource(R.string.settings_recording_end_silence)
                            val stopButton = stringResource(R.string.settings_recording_end_stop)
                            Setting(
                                title = stringResource(R.string.settings_recording_end),
                                richHint = boldWords(stringResource(R.string.settings_recording_end_hint, onSilence, stopButton), onSilence, stopButton),
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
                            // Which way this phone went in the last recording - continuous or restarted after pauses.
                            RuledText(
                                stringResource(
                                    when (listeningMode) {
                                        ListeningMode.CONTINUOUS -> R.string.settings_listening_continuous
                                        ListeningMode.RESUMING -> R.string.settings_listening_resuming
                                        ListeningMode.UNKNOWN -> R.string.settings_listening_unknown
                                    },
                                ),
                                MaterialTheme.typography.bodySmall,
                                MaterialTheme.colorScheme.onSurface,
                                TapedoTheme.colors.rule,
                                Modifier.padding(bottom = 10.dp),
                            )
                            Fold(settings.recordingEnd == RecordingEnd.SILENCE) {
                                Column {
                                    Rule()
                                    Setting(
                                        title = stringResource(R.string.settings_silence_length),
                                        hint = stringResource(R.string.settings_silence_length_hint),
                                    ) {
                                        CounterSlider(
                                            options = AppSettings.SILENCE_OPTIONS,
                                            selected = settings.silenceSeconds,
                                            label = { s -> stringResource(R.string.settings_seconds, s) },
                                            spokenLabel = { s -> pluralStringResource(R.plurals.duration_seconds, s, s) },
                                            tick = { s -> if (s % 10 == 0) Tick.MAJOR else if (s % 5 == 0) Tick.MINOR else Tick.NONE },
                                            onSelect = { v -> onChange { it.copy(silenceSeconds = v) } },
                                        )
                                    }
                                }
                            }
                            Rule()
                            Setting(
                                title = stringResource(R.string.settings_pause),
                                hint = stringResource(R.string.settings_pause_hint),
                            ) {
                                val never = stringResource(R.string.settings_pause_never)
                                CounterSlider(
                                    options = AppSettings.PAUSE_OPTIONS,
                                    selected = settings.pauseSeconds,
                                    label = { s -> if (s == 0) never else stringResource(R.string.settings_seconds, s) },
                                    spokenLabel = { s -> if (s == 0) never else pluralStringResource(R.plurals.duration_seconds, s, s) },
                                    tick = { s -> if (s % 5 == 0) Tick.MAJOR else Tick.MINOR },
                                    onSelect = { v -> onChange { it.copy(pauseSeconds = v) } },
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
                            if (!onDeviceRecognition()) {
                                RuledText(
                                    stringResource(R.string.settings_online_speech_no_ondevice),
                                    MaterialTheme.typography.bodySmall,
                                    TapedoTheme.colors.record,
                                    TapedoTheme.colors.rule,
                                    Modifier.padding(bottom = 10.dp),
                                )
                            }
                        }

                    }

                    SettingsTab.AI -> {
                        Section(null, content = aiSection)
                    }

                    SettingsTab.LOOK -> Section(null) {
                        // A new text size or language re-lays the whole page; these two switches stay under the finger.
                        Setting(
                            title = stringResource(R.string.settings_text_size),
                            hint = stringResource(R.string.settings_text_size_hint),
                            // Keyed on the scale the layout really uses: the setting reaches this screen a frame before the theme.
                            controlModifier = Modifier.holdInPlace(scroll, LocalDensity.current.fontScale),
                        ) {
                            SlideSelector(
                                options = AppSettings.TEXT_SCALES,
                                selected = settings.textScale,
                                label = { stringResource(R.string.settings_percent, (it * 100).roundToInt()) },
                                onSelect = { v -> onChange { it.copy(textScale = v) } },
                            )
                        }
                        Rule()
                        Setting(title = stringResource(R.string.settings_theme), hint = stringResource(R.string.settings_theme_hint)) {
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
                        val locale = LocalConfiguration.current.locales[0]
                        Setting(
                            title = stringResource(R.string.settings_app_language),
                            controlModifier = Modifier.holdInPlace(scroll, locale),
                        ) {
                            // The switch moves at once; the texts follow when the system hands over the new language.
                            var language by remember(locale) { mutableStateOf(AppLanguage.current()) }
                            SlideSelector(
                                options = AppLanguage.ALL,
                                selected = language,
                                label = { languageName(it) },
                                onSelect = {
                                    if (it != language) {
                                        language = it
                                        AppLanguage.set(it)
                                    }
                                },
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
                        Rule()
                        Setting(
                            title = stringResource(R.string.settings_tilted),
                            hint = stringResource(R.string.settings_tilted_hint),
                        ) {
                            ToggleLever(
                                checked = settings.tiltedPaper,
                                onChange = { v -> onChange { it.copy(tiltedPaper = v) } },
                                stateLabel = stringResource(if (settings.tiltedPaper) R.string.settings_on else R.string.settings_off),
                            )
                        }
                    }

                    // The same air above and below as a setting on the other dividers.
                    SettingsTab.ABOUT -> Section(null) {
                        Box(Modifier.padding(vertical = 10.dp)) {
                            Footer(
                                onOpenLicenses,
                                settings.speechLanguage,
                                // The tips are on the home screen - go back there to see them.
                                onShowFirstSteps = {
                                    onChange { it.copy(firstStepsSeen = false) }
                                    onBack()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class SettingsTab(val title: Int) {
    RECORDING(R.string.settings_tab_recording),
    AI(R.string.settings_tab_ai),
    LOOK(R.string.settings_tab_look),
    ABOUT(R.string.settings_tab_about),
}

/** The privacy policy page, PL and EN on one page (published with the browser demo). */
private const val PRIVACY_POLICY_URL = "https://tapedo.szymonbakonis.pl/privacy.html"

private const val TAB_OUT_MS = 100
private const val TAB_IN_MS = 140

/** The divider last open in Settings - in memory only, so it is kept until the app is closed. */
private var lastTab = SettingsTab.RECORDING

/**
 * Dividers of the settings, drawn like the card-box dividers on the home screen: the chosen one stands
 * taller and joins the paper below (same colour, no edge), the others sit lower behind it - the choice shows
 * by height, shape and bold text, not by colour alone. A row you can swipe when large text makes it too wide.
 */
@Composable
private fun SettingsTabs(selected: SettingsTab, onSelect: (SettingsTab) -> Unit) {
    val colors = TapedoTheme.colors
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
    // Every tab as tall as a chosen one: with bigger text a chosen tab is over 48 dp, and half-way through a
    // switch both were lower - the row shrank and the whole page bobbed up and back (author 21.11.2026).
    val tabHeight = maxOf(48.dp, with(LocalDensity.current) { MaterialTheme.typography.labelLarge.lineHeight.toDp() } + 28.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        SettingsTab.entries.forEach { tab ->
            val isSelected = tab == selected
            // The whole 48 dp is the touch target; the visible divider is lower when not chosen.
            Box(
                Modifier
                    .height(tabHeight)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) }),
                contentAlignment = Alignment.BottomCenter,
            ) {
                // Rising, colour, edge and bold all move together (author 21.11.2026: the jump looked broken).
                val still = TapedoTheme.reduceMotion
                val chosen by animateFloatAsState(if (isSelected) 1f else 0f, if (still) snap() else tween(TAB_LOOK_MS), label = "tab chosen")
                Box(
                    Modifier
                        .background(lerp(colors.card, colors.cardEdge, chosen), shape)
                        .border(1.dp, colors.controlEdge.copy(alpha = colors.controlEdge.alpha * (1f - chosen)), shape)
                        .padding(horizontal = 16.dp, vertical = 10.dp + 4.dp * chosen),
                    contentAlignment = Alignment.Center,
                ) {
                    // Bold and regular laid over each other and crossfaded - a weight cannot be animated.
                    val name = stringResource(tab.title)
                    val ink = lerp(scheme.onSurfaceVariant, scheme.onSurface, chosen)
                    Text(
                        name,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = ink,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.graphicsLayer { alpha = 1f - chosen },
                    )
                    Text(
                        name,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = ink,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.graphicsLayer { alpha = chosen }.clearAndSetSemantics { },
                    )
                }
            }
        }
    }
}

private const val TAB_LOOK_MS = 200

/**
 * A section is one index card with a label-maker heading stuck across its top edge - or without one,
 * when the divider above already names it.
 */
@Composable
private fun Section(title: String?, content: @Composable ColumnScope.() -> Unit) {
    val colors = TapedoTheme.colors
    val shape = RoundedCornerShape(4.dp)
    Column {
        if (title != null) LabelTape(title, Modifier.padding(start = 12.dp))
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
 * One setting: the title with a small "Explain" note at the right of the same line, then the control.
 * The explanation (hint and the longer [more]) unfolds under the title only when asked (author 11.11.2026:
 * too many descriptions on view) - written on the card's lines, like a pencilled footnote.
 */
@Composable
private fun Setting(
    title: String,
    hint: String? = null,
    more: String? = null,
    controlModifier: Modifier = Modifier,
    /** The hint with some words styled (e.g. option names in bold); used instead of [hint]. */
    richHint: AnnotatedString? = null,
    control: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val rule = TapedoTheme.colors.rule
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shownHint = richHint ?: hint?.let { AnnotatedString(it) }
    val explains = shownHint != null || more != null
    // The gaps live inside the parts, not in the column: a column gap around the folding part would jump
    // in at the start of the unfold and out at the end of the fold (author 21.11.2026: nothing all-or-nothing).
    Column(Modifier.padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (explains) ExplainNote(title, expanded, onClick = { expanded = !expanded })
        }
        if (explains) {
            Fold(expanded) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (shownHint != null) RuledText(shownHint, MaterialTheme.typography.bodySmall, scheme.onSurfaceVariant, rule)
                    if (more != null) RuledText(more, MaterialTheme.typography.bodySmall, scheme.onSurfaceVariant, rule)
                }
            }
        }
        Box(controlModifier.padding(top = 8.dp)) { control() }
    }
}

/**
 * "Explain" / "Hide": as wide as the wider of the two words in the current language, so switching them never
 * pushes the title beside it (author 11.11.2026).
 */
@Composable
private fun ExplainNote(title: String, expanded: Boolean, onClick: () -> Unit) {
    val explain = stringResource(R.string.settings_explain)
    val hide = stringResource(R.string.settings_explain_hide)
    val spoken = stringResource(R.string.settings_explain_desc, title)
    val state = stringResource(if (expanded) R.string.settings_explain_open else R.string.settings_explain_closed)
    val style = MaterialTheme.typography.labelLarge
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = spoken
                stateDescription = state
                role = Role.Button
            }
            .padding(start = 12.dp, end = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Both words are laid out; the hidden one only keeps the width. They cross-fade with the fold.
        val shownHide by animateFloatAsState(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = if (TapedoTheme.reduceMotion) snap() else tween(220),
            label = "explain word",
        )
        // One word out, then the other in - never both half-visible at once (author 21.11.2026).
        val out = (1f - 2f * shownHide).coerceAtLeast(0f)
        val inn = (2f * shownHide - 1f).coerceAtLeast(0f)
        for ((word, alpha) in listOf(explain to out, hide to inn)) {
            Text(
                word,
                style = style,
                color = MaterialTheme.colorScheme.onSurface,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.graphicsLayer { this.alpha = alpha },
            )
        }
    }
}

/** [text] with each of [words] in bold - option names quoted in a hint. */
private fun boldWords(text: String, vararg words: String): AnnotatedString = buildAnnotatedString {
    append(text)
    for (w in words) {
        var i = text.indexOf(w)
        while (i >= 0) {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), i, i + w.length)
            i = text.indexOf(w, i + w.length)
        }
    }
}

/** Whether this phone can recognise speech on the phone itself - otherwise the system may go online alone. */
@Composable
private fun onDeviceRecognition(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }
}

/** Ruled line between settings - the lines of the index card. */
@Composable
private fun Rule() = HorizontalDivider(thickness = 1.dp, color = TapedoTheme.colors.rule)

@Composable
private fun Footer(onOpenLicenses: () -> Unit, speechLanguage: String, onShowFirstSteps: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val version = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    // What Google Play asks of an app page (author 11.11.2026): what it does, where the data is, who to write to.
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text(stringResource(R.string.settings_about_what), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.settings_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        PaperButton(stringResource(R.string.settings_first_steps), onClick = onShowFirstSteps)
        if (ReportMail.available) {
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.settings_contact), style = MaterialTheme.typography.titleSmall)
            Text(
                ReportMail.address,
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { ReportMail.write(context) }
                    .wrapContentHeight(),
            )
            Spacer(Modifier.height(4.dp))
            PaperButton(stringResource(R.string.report_send), onClick = { ReportMail.send(context, Problem.UserReport, speechLanguage) })
        }
        Spacer(Modifier.height(12.dp))
        PaperButton(stringResource(R.string.settings_privacy_policy) + " ↗", onClick = { uri.openUri(PRIVACY_POLICY_URL) })
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.settings_version, version),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        PaperButton(stringResource(R.string.settings_licenses), onClick = onOpenLicenses)
        Spacer(Modifier.height(4.dp))
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
