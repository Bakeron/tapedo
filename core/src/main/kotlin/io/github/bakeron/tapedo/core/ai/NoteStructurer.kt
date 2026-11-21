package io.github.bakeron.tapedo.core.ai

import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.rules.ListTitles
import io.github.bakeron.tapedo.core.rules.RuleBasedStructurer
import io.github.bakeron.tapedo.core.settings.AiMode
import io.github.bakeron.tapedo.core.settings.AiProvider
import io.github.bakeron.tapedo.core.settings.AppSettings
import io.github.bakeron.tapedo.core.time.EpochDays
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
    /** Claude answered and less than 10% of the top-up is left - said after the recording. */
    val lowFunds: LowFunds? = null,
    /** The provider that wrote the cards; null when the rules did. */
    val by: AiProvider? = null,
)

/** The answer to "More exact" / "Try AI again": the AI's cards to offer, or why there are none (the old cards stay). */
sealed interface Refined {
    data class Cards(val notes: List<Note>, val lowFunds: LowFunds?, val by: AiProvider = AiProvider.ANTHROPIC) : Refined
    data class Failed(val problem: AiProblem, val lowFunds: LowFunds? = null) : Refined
}

/**
 * A saved card as words for "More exact" in the card view (author 21.11.2026): title, text, items, the date
 * and hour as ISO and the place, one per line - the AI reads it like a dictation and may cut it differently.
 */
fun Note.asDictation(): String = buildList {
    add(title.trim())
    if (body.isNotBlank()) add(body.trim())
    items.map { it.text.trim() }.filter { it.isNotEmpty() }.forEach { add("- $it") }
    val day = dueEpochDay?.let { EpochDays.toIso(it) }
    val hour = timeMinutes?.let { "${(it / 60).toString().padStart(2, '0')}:${(it % 60).toString().padStart(2, '0')}" }
    listOfNotNull(day, hour).joinToString(" ").ifEmpty { null }?.let(::add)
    place?.trim()?.ifEmpty { null }?.let(::add)
}.filter { it.isNotEmpty() }.joinToString("\n")

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
        val rules = { StructureResult(RuleBasedStructurer(AppSettings.NEW_CARD_PRIORITY, listTitles).structure(transcript, todayEpochDay, nowMillis, chosenType), problems.toList()) }
        // Paid Claude never runs on its own: in Claude mode the rules write the cards, "More exact" asks Claude.
        val providers = settings.autoProviders
        if (providers.isEmpty() || transcript.isBlank()) return rules()

        val request = StructureRequest(transcript, todayEpochDay, nowMillis, AppSettings.NEW_CARD_PRIORITY, chosenType)
        val (notes, by) = freeCards(request, settings, todayEpochDay, problems) ?: return rules()
        return StructureResult(notes, problems.toList(), by = by)
    }

    /**
     * The free providers in turn, as described on the class; the cards and who wrote them, or null when none
     * did - every reason is then in [problems].
     */
    private suspend fun freeCards(
        request: StructureRequest,
        settings: AppSettings,
        todayEpochDay: Long,
        problems: MutableList<AiProblem>,
    ): Pair<List<Note>, AiProvider>? {
        for (provider in settings.autoProviders) {
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
                ask(provider, model, key, request, todayEpochDay)
            } catch (e: AiException) {
                // A call that never reached the provider does not count.
                if (e.problem != Problem.OFFLINE) usage.addCall(provider, todayEpochDay)
                failed(e.problem, e.providerMessage)
                when {
                    e.problem == Problem.LIMIT -> { usage.markLimit(provider, todayEpochDay); continue }
                    e.problem.tryNext -> continue
                    else -> return null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything unexpected from the AI path must not cost the note.
                failed(Problem.OTHER)
                return null
            } finally {
                lastCall[provider] = timeSource.markNow()
            }
            usage.addCall(provider, todayEpochDay)
            return try {
                AiContract.parse(answer.text, request) to provider
            } catch (e: AiException) {
                // A broken answer: asking again would cost another call.
                failed(e.problem, e.providerMessage)
                null
            }
        }
        return null
    }

    /** A usable key is saved for [provider] - "More exact" is offered only with a Claude key. */
    suspend fun hasKey(provider: AiProvider): Boolean = (readKey(provider) as? Key.Found)?.value != null

    /**
     * In Claude mode "More exact" after a recording that the rules wrote: the same words once more to
     * Claude Sonnet (paid, about 2-3 grosze), whatever Claude model the settings name - a cheaper model
     * would not be more exact. This tap is the only way Claude is asked; the daily spending limit is checked
     * first. In free mode "Try AI again" after the free AI failed: the free providers in turn, as after a
     * recording (author 21.11.2026). No rules here - on any failure the old cards simply stay.
     */
    suspend fun refine(
        transcript: String,
        settings: AppSettings,
        chosenType: NoteType?,
        todayEpochDay: Long,
        nowMillis: Long,
    ): Refined {
        if (settings.aiMode == AiMode.FREE) {
            val problems = mutableListOf<AiProblem>()
            val request = StructureRequest(transcript, todayEpochDay, nowMillis, AppSettings.NEW_CARD_PRIORITY, chosenType)
            val (notes, by) = freeCards(request, settings, todayEpochDay, problems)
                ?: return Refined.Failed(problems.lastOrNull() ?: AiProblem(AiProvider.FREE.first(), "", Problem.NO_KEY))
            return Refined.Cards(notes, lowFunds = null, by = by)
        }
        val provider = AiProvider.ANTHROPIC
        val model = REFINE_MODEL
        fun failed(problem: Problem, message: String = "", low: LowFunds? = null) =
            Refined.Failed(AiProblem(provider, model, problem, message), low)

        // Claude counts money, not calls: the daily limit of calls is for the free providers only.
        if (usage.limitHit(provider, todayEpochDay)) return failed(Problem.LIMIT)
        if (overBudget(settings, todayEpochDay)) return failed(Problem.BUDGET)
        val key = when (val k = readKey(provider)) {
            Key.Unreadable -> return failed(Problem.KEY_UNREADABLE)
            is Key.Found -> k.value ?: return failed(Problem.NO_KEY)
        }
        val request = StructureRequest(transcript, todayEpochDay, nowMillis, AppSettings.NEW_CARD_PRIORITY, chosenType)
        val answer = try {
            ask(provider, model, key, request, todayEpochDay)
        } catch (e: AiException) {
            if (e.problem != Problem.OFFLINE) usage.addCall(provider, todayEpochDay)
            if (e.problem == Problem.LIMIT) usage.markLimit(provider, todayEpochDay)
            return failed(e.problem, e.providerMessage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failed(Problem.OTHER)
        } finally {
            lastCall[provider] = timeSource.markNow()
        }
        usage.addCall(provider, todayEpochDay)
        val low = claudeSpending(answer, model, settings, todayEpochDay)
        return try {
            Refined.Cards(AiContract.parse(answer.text, request), low)
        } catch (e: AiException) {
            failed(e.problem, e.providerMessage, low)
        }
    }

    /**
     * Claude's spending today reached the daily limit in $ from the settings. Checked before each call, so the
     * last one may go a little over it. A counter that cannot be read does not block Claude.
     */
    private suspend fun overBudget(settings: AppSettings, today: Long): Boolean {
        val limit = settings.claudeDayCents ?: return false
        return try {
            usage.claudeCostMicros(today, today) >= limit * 10_000L
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** Claude's answer is paid: its cost is counted, and the top-up checked. A counter failure never costs the note. */
    private suspend fun claudeSpending(answer: AiAnswer, model: String, settings: AppSettings, today: Long): LowFunds? = try {
        answer.tokens?.let { usage.recordClaude(today, answer.model ?: model, it) }
        lowFunds(settings.claudeTopUpUsd, usage.claudeSpent(today, settings.claudeTopUpDay).sinceTopUp)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * One question to the provider. A limit per minute that the provider says ends in a few seconds
     * ("try again in 2.5s") is waited out once - the person is looking at the screen, so a longer wait
     * goes to the next provider instead.
     */
    private suspend fun ask(provider: AiProvider, model: String, key: String, request: StructureRequest, today: Long): AiAnswer {
        val chat = gateway.chat(provider, model, key)
        val system = AiContract.systemPrompt(request)
        val user = AiContract.userMessage(request)
        pause(provider)
        try {
            return chat.ask(system, user, json = true)
        } catch (e: AiException) {
            val wait = ProblemReader.retryAfterMillis(e.providerMessage)
            if (e.problem != Problem.BUSY || wait == null || wait > SHORT_WAIT_MILLIS) throw e
            usage.addCall(provider, today)
            delay(wait)
        }
        return chat.ask(system, user, json = true)
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

    companion object {
        /** Longest wait for a limit per minute before the next provider is asked instead. */
        private const val SHORT_WAIT_MILLIS = 5_000L

        /** "More exact" always asks Sonnet 5.5 - the cost shown on the button is worked out for it. */
        val REFINE_MODEL = AiProvider.ANTHROPIC.defaultModel
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
            if (provider == AiProvider.ANTHROPIC) answer.tokens?.let { usage.recordClaude(today, answer.model ?: model, it) }
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
            val key = secrets.apiKey(provider)?.takeIf { it.isNotBlank() }
            if (key == null) {
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
