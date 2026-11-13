package io.github.bakeron.tapedo.ui.settings

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.bakeron.tapedo.R
import io.github.bakeron.tapedo.core.ai.AiProblem
import io.github.bakeron.tapedo.core.ai.LowFunds
import io.github.bakeron.tapedo.core.ai.Spent
import io.github.bakeron.tapedo.core.ai.TopUpInput
import io.github.bakeron.tapedo.core.ai.USD_PLN
import io.github.bakeron.tapedo.core.ai.lowFunds
import io.github.bakeron.tapedo.core.ai.Problem
import io.github.bakeron.tapedo.core.ai.UsedUp
import io.github.bakeron.tapedo.core.settings.AiProvider
import io.github.bakeron.tapedo.core.settings.AppSettings
import io.github.bakeron.tapedo.ui.cards.RuledText
import io.github.bakeron.tapedo.ui.components.CounterSlider
import io.github.bakeron.tapedo.ui.components.Fold
import io.github.bakeron.tapedo.ui.components.FoldLabel
import io.github.bakeron.tapedo.ui.components.FoldValue
import io.github.bakeron.tapedo.ui.components.PaperButton
import io.github.bakeron.tapedo.ui.components.PaperButtonStyle
import io.github.bakeron.tapedo.ui.components.ToggleLever
import io.github.bakeron.tapedo.ui.theme.TapedoTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.delay

/** What the AI part of Settings can do - wired to [SettingsViewModel] by the route. */
class AiActions(
    val toggle: (AiProvider, Boolean) -> Unit = { _, _ -> },
    val editKey: (AiProvider, String) -> Unit = { _, _ -> },
    val showKey: (AiProvider, Boolean) -> Unit = { _, _ -> },
    val saveKey: (AiProvider) -> Unit = {},
    val deleteKey: (AiProvider) -> Unit = {},
    val keyNoteSeen: (AiProvider) -> Unit = {},
    val setModel: (AiProvider, String) -> Unit = { _, _ -> },
    val check: (AiProvider) -> Unit = {},
    val fetchModels: (AiProvider) -> Unit = {},
    val setDailyLimit: (Int) -> Unit = {},
    /** Claude's top-up in US cents; null clears it. */
    val setTopUp: (Int?) -> Unit = {},
)

private class ProviderTexts(
    @StringRes val what: Int,
    @StringRes val privacy: Int,
    @StringRes val hint: Int,
    val steps: List<Int>,
)

private fun textsOf(p: AiProvider) = when (p) {
    AiProvider.GROQ -> ProviderTexts(
        R.string.ai_groq_what, R.string.ai_groq_privacy, R.string.ai_p_groq_hint,
        listOf(R.string.ai_how_groq_1, R.string.ai_how_groq_2, R.string.ai_how_groq_3),
    )
    AiProvider.OPENROUTER -> ProviderTexts(
        R.string.ai_openrouter_what, R.string.ai_openrouter_privacy, R.string.ai_p_openrouter_hint,
        listOf(R.string.ai_how_openrouter_1, R.string.ai_how_openrouter_2, R.string.ai_how_openrouter_3),
    )
    AiProvider.ANTHROPIC -> ProviderTexts(
        R.string.ai_anthropic_what, R.string.ai_anthropic_privacy, R.string.ai_p_anthropic_hint,
        listOf(R.string.ai_how_anthropic_1, R.string.ai_how_anthropic_2, R.string.ai_how_anthropic_3),
    )
    AiProvider.OPENAI -> ProviderTexts(
        R.string.ai_openai_what, R.string.ai_openai_privacy, R.string.ai_p_openai_hint,
        listOf(R.string.ai_how_openai_1, R.string.ai_how_openai_2, R.string.ai_how_openai_3),
    )
    AiProvider.GEMINI -> ProviderTexts(
        R.string.ai_gemini_what, R.string.ai_gemini_privacy, R.string.ai_p_gemini_hint,
        listOf(R.string.ai_how_gemini_1, R.string.ai_how_gemini_2, R.string.ai_how_gemini_3),
    )
}

@Composable
fun providerName(p: AiProvider): String = stringResource(
    when (p) {
        AiProvider.GROQ -> R.string.ai_p_groq
        AiProvider.OPENROUTER -> R.string.ai_p_openrouter
        AiProvider.ANTHROPIC -> R.string.ai_p_anthropic
        AiProvider.OPENAI -> R.string.ai_p_openai
        AiProvider.GEMINI -> R.string.ai_p_gemini
    },
)

/** What happened and what to do - in words, never the technical text. */
@Composable
fun problemText(p: Problem): String = stringResource(
    when (p) {
        Problem.KEY -> R.string.ai_problem_key
        Problem.API_OFF -> R.string.ai_problem_api_off
        Problem.NO_FREE_TIER -> R.string.ai_problem_no_free_tier
        Problem.VERIFY -> R.string.ai_problem_verify
        Problem.DENIED -> R.string.ai_problem_denied
        Problem.MODEL -> R.string.ai_problem_model
        Problem.LIMIT -> R.string.ai_problem_limit
        Problem.BUSY -> R.string.ai_problem_busy
        Problem.CAP -> R.string.ai_problem_cap
        Problem.NO_KEY -> R.string.ai_problem_no_key
        Problem.KEY_UNREADABLE -> R.string.ai_problem_key_unreadable
        Problem.OFFLINE -> R.string.ai_problem_offline
        Problem.ANSWER -> R.string.ai_problem_answer
        Problem.NO_AUDIO -> R.string.ai_problem_no_audio
        Problem.TOO_LONG -> R.string.ai_problem_too_long
        Problem.OTHER -> R.string.ai_problem_other
    },
)

/**
 * "Google Gemini (gemini-3.5-flash-lite): this Google project has no free plan…", and the provider's own
 * words folded under "Provider's answer" (for when the plain words are not enough).
 */
@Composable
fun ProblemView(problem: AiProblem, modifier: Modifier = Modifier) {
    var open by rememberSaveable(problem) { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Warning(stringResource(R.string.ai_problem_line, providerName(problem.provider), problem.model, problemText(problem.problem)))
        if (problem.providerMessage.isNotBlank()) {
            Unfold(stringResource(R.string.ai_provider_answer), open, Modifier.padding(start = 26.dp)) { open = !open }
            Fold(open) {
                Text(
                    problem.providerMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 26.dp),
                )
            }
        }
    }
}

/** A warning: a red "!" stamp plus the words, so it is never told by colour alone. */
@Composable
private fun Warning(text: String, modifier: Modifier = Modifier) {
    val record = TapedoTheme.colors.record
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .padding(top = 1.dp)
                .sizeIn(minWidth = 18.dp, minHeight = 18.dp)
                .border(1.5.dp, record, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("!", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = record)
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

/** An underlined fold / unfold line with a triangle - like a tab on a card divider. */
@Composable
private fun Unfold(label: String, open: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FoldLabel(
        label,
        open,
        MaterialTheme.typography.labelLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button) { onClick() }
            .padding(vertical = 12.dp),
    )
}

/** Settings - AI: every provider on its own index card (on / off, key, model, check), asked in turn. */
@Composable
fun ColumnScope.AiSection(settings: AppSettings, ai: Map<AiProvider, ProviderState>, actions: AiActions) {
    val scheme = MaterialTheme.colorScheme
    val rule = TapedoTheme.colors.rule
    Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RuledText(stringResource(R.string.ai_intro), MaterialTheme.typography.bodySmall, scheme.onSurface, rule)
        RuledText(stringResource(R.string.ai_queue), MaterialTheme.typography.bodySmall, scheme.onSurfaceVariant, rule)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (p in AiProvider.entries) {
            ProviderCard(p, p in settings.aiProviders, settings, ai.getValue(p), actions)
        }
    }

    Column(Modifier.padding(top = 16.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ai_limit), style = MaterialTheme.typography.titleMedium)
        CounterSlider(
            options = AppSettings.AI_DAILY_LIMITS,
            selected = settings.aiDailyLimit,
            label = { "$it" },
            onSelect = actions.setDailyLimit,
        )
        RuledText(stringResource(R.string.ai_limit_hint), MaterialTheme.typography.bodySmall, scheme.onSurfaceVariant, rule)

        val enabled = AiProvider.entries.filter { it in settings.aiProviders }
        val usedUp = enabled.filter { ai.getValue(it).usedUp != null }
        if (usedUp.isNotEmpty()) {
            val list = usedUp.map { p ->
                providerName(p) + " (" + stringResource(
                    if (ai.getValue(p).usedUp == UsedUp.APP) R.string.ai_limit_app else R.string.ai_limit_provider,
                ) + ")"
            }.joinToString(", ")
            Warning(
                stringResource(R.string.ai_limit_now, list) + " " +
                    stringResource(if (usedUp.size == enabled.size) R.string.ai_limit_all else R.string.ai_limit_next),
            )
        }
        RuledText(stringResource(R.string.ai_privacy), MaterialTheme.typography.bodySmall, scheme.onSurfaceVariant, rule)
    }
}

@Composable
private fun ProviderCard(p: AiProvider, on: Boolean, settings: AppSettings, st: ProviderState, actions: AiActions) {
    val colors = TapedoTheme.colors
    val scheme = MaterialTheme.colorScheme
    val texts = textsOf(p)
    val name = providerName(p)
    var open by rememberSaveable(p) { mutableStateOf(false) }
    val shape = RoundedCornerShape(3.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(scheme.background, shape)
            // A card that is on gets an inked edge - the lever and its words say it too.
            .border(if (on) 1.5.dp else 1.dp, if (on) colors.controlEdge else colors.cardEdge, shape)
            .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { open = !open }
                    .semantics { stateDescription = "" },
                verticalArrangement = Arrangement.Center,
            ) {
                Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                FoldLabel(stringResource(if (open) R.string.ai_fold else R.string.ai_unfold), open, MaterialTheme.typography.labelLarge)
            }
            ToggleLever(
                checked = on,
                onChange = { actions.toggle(p, it) },
                stateLabel = stringResource(if (on) R.string.ai_on else R.string.ai_off),
                modifier = Modifier.semantics { contentDescription = name },
                allLabels = listOf(stringResource(R.string.ai_on), stringResource(R.string.ai_off)),
            )
        }
        Text(
            buildAnnotatedString {
                if (p.free) {
                    append(stringResource(R.string.ai_free))
                } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stringResource(R.string.ai_paid)) }
                }
                append(" - ")
                append(stringResource(texts.hint))
            },
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        val low = if (p == AiProvider.ANTHROPIC) lowFunds(settings.claudeTopUpUsd, st.spent?.sinceTopUp) else null
        val warning = if (!on) null else when {
            st.keySuffix == null -> stringResource(R.string.ai_no_key)
            st.usedUp == UsedUp.APP -> stringResource(R.string.ai_used_up_app, settings.aiDailyLimit)
            st.usedUp == UsedUp.PROVIDER -> stringResource(R.string.ai_used_up_provider, name)
            low != null -> lowFundsText(low)
            else -> null
        }
        FoldValue(warning) { Warning(it) }
        Fold(open) {
            ProviderBody(p, texts, settings, st, actions)
        }
    }
}

@Composable
private fun ProviderBody(p: AiProvider, texts: ProviderTexts, settings: AppSettings, st: ProviderState, actions: AiActions) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Block(stringResource(R.string.ai_what_title), stringResource(texts.what))
        Block(stringResource(R.string.ai_privacy_title), stringResource(texts.privacy))
        KeySteps(p, texts.steps, noKey = st.keySuffix == null)
        KeyField(p, st, actions)
        ModelField(p, settings, actions)
        if (p.free) ModelPicker(p, settings.modelFor(p), st, actions)
        ConnectionCheck(p, st, actions)
        Text(
            stringResource(R.string.ai_usage, st.callsToday, settings.aiDailyLimit, st.callsMonth),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        if (p == AiProvider.ANTHROPIC) TopUp(settings, st.spent, actions)
    }
}

@Composable
private fun appLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

/** "1,23 $" / "$1.23"; a few calls under a cent are not shown as zero. */
private fun usdText(usd: Double, locale: Locale): String {
    val small = usd > 0 && usd < 0.005
    val n = String.format(locale, "%.2f", if (small) 0.01 else usd.coerceAtLeast(0.0))
    // No-break space: "1,23" and "$" must not land on two lines.
    val text = if (locale.language == "pl") "$n $" else "$$n"
    return if (small) "< $text" else text
}

/** Dollars in złoty at the rough rate - always shown as "about". */
private fun plnText(usd: Double, locale: Locale) = String.format(locale, "%.2f", usd.coerceAtLeast(0.0) * USD_PLN)

private fun dayText(epochDay: Long, locale: Locale): String =
    LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

@Composable
private fun lowFundsText(low: LowFunds): String {
    val locale = appLocale()
    return stringResource(R.string.ai_low_funds, usdText(low.left, locale), usdText(low.topUp, locale))
}

/** After a recording: Claude answered, but less than 10% of the top-up is left. */
@Composable
fun LowFundsWarning(low: LowFunds, modifier: Modifier = Modifier) {
    Warning(lowFundsText(low) + " " + stringResource(R.string.ai_low_funds_where), modifier)
}

/**
 * The last top-up of the Claude account (in $, as Anthropic counts; about how much in złoty). Spending is
 * counted from the day it is typed in. Below: spent of it and what is left, today and this month.
 */
@Composable
private fun TopUp(settings: AppSettings, spent: Spent?, actions: AiActions) {
    val scheme = MaterialTheme.colorScheme
    val locale = appLocale()
    val cents = settings.claudeTopUpCents
    val saved = cents?.let { String.format(locale, "%.2f", it / 100.0) }.orEmpty()
    var draft by rememberSaveable(saved) { mutableStateOf(saved) }
    var wrong by rememberSaveable { mutableStateOf(false) }
    var justSaved by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val label = stringResource(R.string.ai_topup)
    fun save() {
        when (val v = TopUpInput.parse(draft)) {
            TopUpInput.Wrong -> wrong = true
            TopUpInput.Cleared -> {
                wrong = false
                if (cents != null) {
                    actions.setTopUp(null)
                    justSaved = true
                }
            }
            is TopUpInput.Cents -> {
                wrong = false
                if (v.cents != cents) {
                    actions.setTopUp(v.cents)
                    justSaved = true
                }
            }
        }
    }
    LaunchedEffect(justSaved) {
        if (justSaved) {
            delay(3_000)
            justSaved = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
        LineField(
            value = draft,
            onValueChange = {
                draft = it.take(16)
                wrong = false
            },
            label = label,
            placeholder = String.format(locale, "%.2f", 5.0),
            secret = false,
            onDone = { focus.clearFocus() },
            onLeave = ::save,
            number = true,
        )
        FoldValue(if (wrong) stringResource(R.string.ai_topup_wrong) else null) { Warning(it) }
        Fold(justSaved) {
            Text(
                stringResource(R.string.ai_model_saved),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        val usd = settings.claudeTopUpUsd
        val day = settings.claudeTopUpDay
        Text(
            if (usd != null && day != null) {
                stringResource(R.string.ai_topup_pln, plnText(usd, locale), dayText(day, locale))
            } else {
                stringResource(R.string.ai_topup_hint)
            },
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        if (spent != null) {
            Column(
                Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val since = spent.sinceTopUp
                if (usd != null && day != null && since != null) {
                    val left = (usd - since).coerceAtLeast(0.0)
                    Text(
                        stringResource(
                            R.string.ai_spent,
                            dayText(day, locale), usdText(since, locale), usdText(usd, locale), usdText(left, locale), plnText(left, locale),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    lowFunds(usd, since)?.let { Warning(lowFundsText(it) + " " + stringResource(R.string.ai_low_funds_here)) }
                }
                Text(
                    stringResource(R.string.ai_spent_periods, usdText(spent.today, locale), usdText(spent.month, locale)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(stringResource(R.string.ai_spent_hint), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Block(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

/** "How to get a key" - unfolded while there is no key (the next thing to do), with "Create a key". */
@Composable
private fun KeySteps(p: AiProvider, steps: List<Int>, noKey: Boolean) {
    val uri = LocalUriHandler.current
    var open by rememberSaveable(p, noKey) { mutableStateOf(noKey) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Unfold(stringResource(R.string.ai_how_key), open) { open = !open }
        Fold(open) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperButton(stringResource(R.string.ai_get_key) + " ↗", onClick = { uri.openUri(p.keyPage) })
                steps.forEachIndexed { i, step -> Step(i + 1, stringResource(step)) }
            }
        }
    }
}

/** Numbered step with the number in a pencil-drawn circle. */
@Composable
private fun Step(number: Int, text: String) {
    val colors = TapedoTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .sizeIn(minWidth = 24.dp, minHeight = 24.dp)
                .border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                .background(colors.card, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 4.dp))
        }
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(top = 3.dp))
    }
}

/** A key: typed once, saved straight to the phone's vault, shown only as "••••1234" unless "Show key". */
@Composable
private fun KeyField(p: AiProvider, st: ProviderState, actions: AiActions) {
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(R.string.ai_key)
    // While the key is readable on screen, no screenshot or screen recording may catch it.
    val window = LocalActivity.current?.window
    DisposableEffect(st.shown, window) {
        if (st.shown) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (st.shown) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(st.keyNote) {
        if (st.keyNote != null) {
            delay(3_000)
            actions.keyNoteSeen(p)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).wrapContentHeight(),
            )
            // Only a saved key can be shown (author's decision 7.11.2026); the row keeps its height either way.
            AnimatedVisibility(
                visible = st.keySuffix != null,
                enter = if (TapedoTheme.reduceMotion) EnterTransition.None else fadeIn(tween(220)),
                exit = if (TapedoTheme.reduceMotion) ExitTransition.None else fadeOut(tween(160)),
            ) {
                Text(
                    stringResource(if (st.shown) R.string.ai_hide_key else R.string.ai_show_key),
                    style = MaterialTheme.typography.labelLarge,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Switch) { actions.showKey(p, !st.shown) }
                        .semantics { selected = st.shown }
                        .padding(start = 12.dp, top = 14.dp, bottom = 14.dp),
                )
            }
        }
        LineField(
            value = st.draft,
            onValueChange = { actions.editKey(p, it) },
            label = label,
            placeholder = st.keySuffix?.let { "••••$it" },
            secret = !st.shown,
        )
        Text(
            if (st.keySuffix != null) stringResource(R.string.ai_key_set, st.keySuffix) else stringResource(R.string.ai_key_not_set),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperButton(
                stringResource(R.string.ai_key_save),
                onClick = { actions.saveKey(p) },
                style = PaperButtonStyle.FILLED,
                enabled = st.draft.length >= 8 && st.draft != st.revealed,
            )
            if (st.keySuffix != null) {
                // Deleting a key takes two taps.
                var confirm by rememberSaveable(p, st.keySuffix) { mutableStateOf(false) }
                PaperButton(
                    stringResource(if (confirm) R.string.ai_key_delete_confirm else R.string.ai_key_delete),
                    onClick = { if (confirm) actions.deleteKey(p) else confirm = true },
                    style = PaperButtonStyle.DANGER,
                )
            }
        }
        FoldValue(st.keyNote) { note ->
            Text(
                stringResource(if (note == KeyNote.SAVED) R.string.ai_key_saved_note else R.string.ai_key_deleted_note),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Text(stringResource(R.string.ai_key_hint), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
}

/** "Model": saved when the field is left (not on every letter); empty = the provider's default model. */
@Composable
private fun ModelField(p: AiProvider, settings: AppSettings, actions: AiActions) {
    val saved = settings.aiModels[p].orEmpty()
    var draft by rememberSaveable(p, saved) { mutableStateOf(saved) }
    var justSaved by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val label = stringResource(R.string.ai_model)
    fun save() {
        if (draft.trim() != saved) {
            actions.setModel(p, draft)
            justSaved = true
        }
    }
    LaunchedEffect(justSaved) {
        if (justSaved) {
            delay(3_000)
            justSaved = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LineField(
            value = draft,
            onValueChange = { draft = it.take(AppSettings.MAX_MODEL_NAME) },
            label = label,
            placeholder = p.defaultModel,
            secret = false,
            onDone = { focus.clearFocus() },
            onLeave = ::save,
        )
        Fold(justSaved) {
            Text(
                stringResource(R.string.ai_model_saved),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Text(
            stringResource(R.string.ai_model_hint, p.defaultModel),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Show free models": the provider's list (or the one saved in the app); a tape is a pick. */
@Composable
private fun ModelPicker(p: AiProvider, current: String, st: ProviderState, actions: AiActions) {
    val loading = st.models == ModelsState.Loading
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        PaperButton(
            stringResource(if (loading) R.string.ai_free_models_fetching else R.string.ai_free_models_fetch),
            onClick = { actions.fetchModels(p) },
            enabled = !loading,
        )
        FoldValue((st.models as? ModelsState.Loaded)?.list) { list ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val problem = list.problem
                if (problem != null) {
                    Warning(stringResource(R.string.ai_free_models_failed, problemText(problem.problem)))
                } else {
                    Text(
                        stringResource(if (list.fromProvider) R.string.ai_free_models_live else R.string.ai_free_models_saved),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (list.models.isNotEmpty()) {
                    Text(stringResource(R.string.ai_free_models), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (m in list.models) ModelTape(m, m == current) { actions.setModel(p, m) }
                    }
                }
            }
        }
    }
}

/** A model name punched on label-maker tape; the picked one is inked and ticked. */
@Composable
private fun ModelTape(name: String, picked: Boolean, onPick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val colors = TapedoTheme.colors
    val shape = RoundedCornerShape(2.dp)
    val chosen = stringResource(R.string.ai_model_chosen)
    Text(
        (if (picked) "✓ " else "") + name,
        style = MaterialTheme.typography.labelLarge,
        color = if (picked) scheme.onPrimary else scheme.onSurface,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.RadioButton, onClick = onPick)
            .semantics {
                selected = picked
                if (picked) stateDescription = chosen
            }
            .padding(vertical = 6.dp)
            .background(if (picked) scheme.primary else colors.card, shape)
            .border(1.dp, if (picked) scheme.primary else colors.controlEdge, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/** "Check connection": one tiny question with the model from the settings. */
@Composable
private fun ConnectionCheck(p: AiProvider, st: ProviderState, actions: AiActions) {
    val running = st.check == CheckState.Running
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PaperButton(
            stringResource(if (running) R.string.ai_testing else R.string.ai_test),
            onClick = { actions.check(p) },
            enabled = !running && st.keySuffix != null,
        )
        Text(
            stringResource(R.string.ai_test_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            when (val c = st.check) {
                is CheckState.Ok -> Text(stringResource(R.string.ai_test_ok, c.model), style = MaterialTheme.typography.bodyMedium)
                is CheckState.Failed -> ProblemView(c.problem)
                CheckState.Idle, CheckState.Running -> Unit
            }
        }
    }
}

/**
 * A field as a line on a paper form: a pencil line to write on - not Material's floating-label box.
 * The line gets thicker and inked while the field has focus.
 * @param secret password keyboard and dots: no suggestions, no learning - a key never reaches the keyboard's dictionary.
 */
@Composable
private fun LineField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String?,
    secret: Boolean,
    onDone: () -> Unit = {},
    onLeave: () -> Unit = {},
    /** A sum of money: the number keyboard with a decimal point. */
    number: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var hadFocus by remember { mutableStateOf(false) }
    val style = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                secret -> KeyboardType.Password
                number -> KeyboardType.Decimal
                else -> KeyboardType.Uri
            },
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        textStyle = style,
        cursorBrush = SolidColor(scheme.primary),
        interactionSource = interaction,
        decorationBox = { inner ->
            Box {
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, style = style.copy(color = scheme.onSurfaceVariant))
                }
                inner()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .onFocusChanged {
                if (hadFocus && !it.isFocused) onLeave()
                hadFocus = it.isFocused
            }
            .semantics { contentDescription = label }
            .drawBehind {
                val stroke = (if (focused) 2.5.dp else 1.5.dp).toPx()
                val y = size.height - stroke / 2
                drawLine(if (focused) scheme.primary else scheme.outline, Offset(0f, y), Offset(size.width, y), stroke)
            }
            .padding(top = 12.dp, bottom = 8.dp),
    )
}
