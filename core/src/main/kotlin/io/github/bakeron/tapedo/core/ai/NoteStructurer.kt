package io.github.bakeron.tapedo.core.ai

import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.rules.ListTitles
import io.github.bakeron.tapedo.core.rules.RuleBasedStructurer
import io.github.bakeron.tapedo.core.settings.AiProvider
import io.github.bakeron.tapedo.core.settings.AppSettings
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** API keys, encrypted by the platform (Android Keystore). Keys never go to logs or to Git. */
interface SecretStore {
    /**
     * Null when no usable key exists (none saved, or lost for good). Throws when reading failed only
     * for now - the key is still there and the next try may work.
     */
    suspend fun apiKey(provider: AiProvider): String?

    suspend fun setApiKey(provider: AiProvider, key: String?)
}

data class StructureResult(
    val notes: List<Note>,
    /** Every provider that could not write the cards, and why - also when the next one took over. */
    val problems: List<AiProblem> = emptyList(),
)

/**
 * AI or rules. AI is optional: with no provider on, no key, no internet or every limit used the app still
 * works - the rules take over and the user is told why. Providers are asked one after another, in the order
 * of [AiProvider.entries]; each note is one call:
 * - the provider's own limit: it rests until tomorrow, the note goes to the next one;
 * - wrong key, API off, no free plan, no such model: the note goes to the next one, this one is asked
 *   again with the next note;
 * - no connection, a broken or refused answer, another error: the rules write this note at once.
 */
class NoteStructurer(
    private val secrets: SecretStore,
    private val usage: AiUsage,
    private val gateway: AiGateway,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val lastCall = mutableMapOf<AiProvider, TimeMark>()

    suspend fun structure(
        transcript: String,
        settings: AppSettings,
        /** Type picked on the home screen (or the list being added to), or null for "auto". */
        chosenType: NoteType?,
        listTitles: ListTitles,
        todayEpochDay: Long,
        nowMillis: Long,
    ): StructureResult {
        val problems = mutableListOf<AiProblem>()
        val rules = { StructureResult(RuleBasedStructurer(settings.defaultPriority, listTitles).structure(transcript, todayEpochDay, nowMillis, chosenType), problems.toList()) }
        val providers = AiProvider.entries.filter { it in settings.aiProviders }
        if (providers.isEmpty() || transcript.isBlank()) return rules()

        val request = StructureRequest(transcript, todayEpochDay, nowMillis, settings.defaultPriority, chosenType)
        for (provider in providers) {
            val model = settings.modelFor(provider)
            fun failed(problem: Problem, message: String = "") = problems.add(AiProblem(provider, model, problem, message))

            when (usage.usedUp(provider, todayEpochDay, settings.aiDailyLimit)) {
                UsedUp.APP -> { failed(Problem.CAP); continue }
                UsedUp.PROVIDER -> { failed(Problem.LIMIT); continue }
                null -> Unit
            }
            val key = when (val k = readKey(provider)) {
                Key.Unreadable -> { failed(Problem.KEY_UNREADABLE); continue }
                is Key.Found -> k.value
            }
            if (key == null) { failed(Problem.NO_KEY); continue }

            val answer = try {
                pause(provider)
                gateway.chat(provider, model, key).ask(AiContract.systemPrompt(request), AiContract.userMessage(request), json = true)
            } catch (e: AiException) {
                // A call that never reached the provider does not count.
                if (e.problem != Problem.OFFLINE) usage.addCall(provider, todayEpochDay)
                failed(e.problem, e.providerMessage)
                when {
                    e.problem == Problem.LIMIT -> { usage.markLimit(provider, todayEpochDay); continue }
                    e.problem.tryNext -> continue
                    else -> return rules()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything unexpected from the AI path must not cost the note - the rules take over.
                failed(Problem.OTHER)
                return rules()
            } finally {
                lastCall[provider] = timeSource.markNow()
            }
            usage.addCall(provider, todayEpochDay)
            return try {
                StructureResult(AiContract.parse(answer.text, request), problems.toList())
            } catch (e: AiException) {
                // A broken answer: this note is written by the rules (asking again would cost another call).
                failed(e.problem, e.providerMessage)
                rules()
            }
        }
        return rules()
    }

    /** The provider's pause between two calls, so free per-minute limits are not hit. */
    private suspend fun pause(provider: AiProvider) {
        val since = lastCall[provider]?.elapsedNow()?.inWholeMilliseconds ?: return
        if (since < provider.pauseMillis) delay(provider.pauseMillis - since)
    }

    private sealed interface Key {
        class Found(val value: String?) : Key
        data object Unreadable : Key
    }

    private suspend fun readKey(provider: AiProvider): Key = try {
        Key.Found(secrets.apiKey(provider)?.takeIf { it.isNotBlank() })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Not "no key": the saved key is fine, so the user should simply try again.
        Key.Unreadable
    }
}

/** "Check connection" and the free model lists in Settings. */
class AiConnection(
    private val secrets: SecretStore,
    private val usage: AiUsage,
    private val gateway: AiGateway,
) {
    sealed interface Check {
        /** It works - [model] is the one that answered. */
        data class Ok(val model: String) : Check
        data class Failed(val problem: AiProblem) : Check
    }

    /**
     * One tiny question with the model from the settings (counts as one call of the day). When it works,
     * today's "limit" mark of the provider is taken off - it can be asked again.
     */
    suspend fun check(provider: AiProvider, settings: AppSettings, today: Long): Check {
        val model = settings.modelFor(provider)
        fun failed(problem: Problem, message: String = "") = Check.Failed(AiProblem(provider, model, problem, message))
        val key = try {
            secrets.apiKey(provider)?.takeIf { it.isNotBlank() } ?: return failed(Problem.NO_KEY)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failed(Problem.KEY_UNREADABLE)
        }
        return try {
            val answer = gateway.chat(provider, model, key).ask("Answer with the single word OK.", "Are you there?", json = false)
            usage.addCall(provider, today)
            usage.clearLimit(provider, today)
            Check.Ok(answer.model?.let { ProblemReader.oneLine(it, 120) }?.ifEmpty { null } ?: model)
        } catch (e: AiException) {
            if (e.problem != Problem.OFFLINE) usage.addCall(provider, today)
            // An empty answer still means the key, the API and the model work.
            if (e.problem == Problem.ANSWER && e.providerMessage == EMPTY_ANSWER) {
                usage.clearLimit(provider, today)
                Check.Ok(model)
            } else {
                failed(e.problem, e.providerMessage)
            }
        }
    }

    /** Free models of a provider: its own list, or the short list saved in the app (with the reason). */
    data class FreeModels(val models: List<String>, val fromProvider: Boolean, val problem: AiProblem? = null)

    suspend fun freeModels(provider: AiProvider, settings: AppSettings): FreeModels {
        val saved = provider.savedFreeModels
        val model = settings.modelFor(provider)
        return try {
            // OpenRouter's list is public - it is shown before a key is saved.
            val key = if (provider == AiProvider.OPENROUTER) null else secrets.apiKey(provider)?.takeIf { it.isNotBlank() }
            if (key == null && provider != AiProvider.OPENROUTER) {
                return FreeModels(saved, false, AiProblem(provider, model, Problem.NO_KEY))
            }
            val models = gateway.freeModels(provider, key)
            if (models.isEmpty()) FreeModels(saved, false) else FreeModels(models, true)
        } catch (e: AiException) {
            FreeModels(saved, false, AiProblem(provider, model, e.problem, e.providerMessage))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FreeModels(saved, false, AiProblem(provider, model, Problem.KEY_UNREADABLE))
        }
    }
}
