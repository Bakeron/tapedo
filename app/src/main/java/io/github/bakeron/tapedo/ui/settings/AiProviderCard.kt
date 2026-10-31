package io.github.bakeron.tapedo.ui.settings

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.bakeron.tapedo.R
import io.github.bakeron.tapedo.core.ai.AiError
import io.github.bakeron.tapedo.core.settings.AiProvider
import io.github.bakeron.tapedo.ui.components.PaperButton
import io.github.bakeron.tapedo.ui.components.PaperButtonStyle
import io.github.bakeron.tapedo.ui.components.RubberStamp
import io.github.bakeron.tapedo.ui.theme.TapedoTheme

private data class ProviderInfo(
    @StringRes val what: Int,
    @StringRes val privacy: Int,
    val steps: List<Int>,
    @StringRes val open: Int,
    val url: String,
)

private fun infoFor(provider: AiProvider): ProviderInfo? = when (provider) {
    AiProvider.GEMINI -> ProviderInfo(
        R.string.ai_gemini_what, R.string.ai_gemini_privacy,
        listOf(R.string.ai_gemini_step1, R.string.ai_gemini_step2, R.string.ai_gemini_step3),
        R.string.ai_gemini_open, "https://aistudio.google.com/apikey",
    )
    AiProvider.GROQ -> ProviderInfo(
        R.string.ai_groq_what, R.string.ai_groq_privacy,
        listOf(R.string.ai_groq_step1, R.string.ai_groq_step2, R.string.ai_groq_step3),
        R.string.ai_groq_open, "https://console.groq.com/keys",
    )
    AiProvider.NONE -> null
}

/**
 * What appears after picking a provider: what it is, where the text goes, how to get a key
 * step by step, and the key field. Shown only on demand, as zalozenia.md asks.
 */
@Composable
fun AiProviderCard(
    provider: AiProvider,
    keys: AiKeyState,
    onEditKey: (String) -> Unit,
    onSaveKey: (String) -> Unit,
    onDeleteKey: () -> Unit,
    onTest: () -> Unit,
) {
    val info = infoFor(provider) ?: return
    val uri = LocalUriHandler.current
    // The typed key lives in the ViewModel (memory only), not in rememberSaveable's saved state.
    val input = keys.drafts[provider].orEmpty()
    val suffix = keys.savedSuffix[provider]

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        RubberStamp(stringResource(R.string.ai_free_stamp), Modifier.padding(top = 4.dp))

        Block(stringResource(R.string.ai_what_title), stringResource(info.what))
        Block(stringResource(R.string.ai_privacy_title), stringResource(info.privacy))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ai_steps_title), style = MaterialTheme.typography.titleSmall)
            info.steps.forEachIndexed { i, step -> Step(i + 1, stringResource(step)) }
            PaperButton(stringResource(info.open), onClick = { uri.openUri(info.url) })
        }

        OutlinedTextField(
            value = input,
            onValueChange = { onEditKey(it.trim()) },
            label = { Text(stringResource(R.string.ai_key_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            // Password keyboard: no suggestions, no learning - the key never reaches the keyboard's dictionary.
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.ai_key_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (suffix != null) stringResource(R.string.ai_key_saved, suffix) else stringResource(R.string.ai_key_none),
            style = MaterialTheme.typography.bodyMedium,
        )

        // Wraps to a second line on narrow phones or with large text instead of squeezing "Delete key" out.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaperButton(stringResource(R.string.ai_key_save), onClick = { onSaveKey(input) }, enabled = input.length >= 8)
            PaperButton(
                stringResource(R.string.ai_key_test),
                onClick = onTest,
                enabled = suffix != null && keys.test != KeyTest.Running,
            )
            if (suffix != null) {
                PaperButton(stringResource(R.string.ai_key_delete), onClick = onDeleteKey, style = PaperButtonStyle.DANGER)
            }
        }
        // Says why a key is greyed out, instead of leaving a pale button to guess about.
        if (input.length < 8 || suffix == null) {
            Text(
                stringResource(R.string.ai_key_buttons_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = keys.test != KeyTest.Idle,
            enter = if (TapedoTheme.reduceMotion) EnterTransition.None else fadeIn() + expandVertically(),
            exit = if (TapedoTheme.reduceMotion) ExitTransition.None else fadeOut() + shrinkVertically(),
        ) {
            Text(
                testMessage(keys.test),
                style = MaterialTheme.typography.bodyMedium,
                color = if (keys.test is KeyTest.Failed) TapedoTheme.colors.record else MaterialTheme.colorScheme.onSurface,
                // Read out by TalkBack as soon as the result arrives.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun testMessage(test: KeyTest): String = when (test) {
    KeyTest.Idle -> ""
    KeyTest.Running -> stringResource(R.string.ai_test_running)
    is KeyTest.Ok -> stringResource(R.string.ai_test_ok, test.firstTitle)
    is KeyTest.Failed -> aiErrorMessage(test.error)
}

@Composable
fun aiErrorMessage(error: AiError): String = when (error) {
    AiError.MissingKey -> stringResource(R.string.ai_error_missing_key)
    AiError.InvalidKey -> stringResource(R.string.ai_error_invalid_key)
    AiError.RateLimited -> stringResource(R.string.ai_error_rate_limited)
    is AiError.Network -> stringResource(R.string.ai_error_network)
    is AiError.Server -> stringResource(R.string.ai_error_server, error.status)
    is AiError.BadResponse -> stringResource(R.string.ai_error_bad_response)
}

@Composable
private fun Block(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(text, style = MaterialTheme.typography.bodySmall)
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
