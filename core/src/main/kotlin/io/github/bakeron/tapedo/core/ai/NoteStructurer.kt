package io.github.bakeron.tapedo.core.ai

import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.model.Priority
import io.github.bakeron.tapedo.core.rules.ListTitles
import io.github.bakeron.tapedo.core.rules.RuleBasedStructurer
import io.github.bakeron.tapedo.core.settings.AiProvider
import kotlinx.coroutines.CancellationException

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
    /** Set when AI was chosen but failed and the no-AI rules were used instead. */
    val aiError: AiError? = null,
)

/**
 * Picks AI or rules. AI is optional: with no provider, no key, no internet or an exhausted
 * free limit the app still works - the rules take over and the user is told why.
 */
class NoteStructurer(
    private val secrets: SecretStore,
    private val providerFor: (AiProvider, String) -> AiStructurer?,
) {

    suspend fun structure(
        transcript: String,
        provider: AiProvider,
        /** Type picked on the home screen, or null for "auto". */
        chosenType: NoteType?,
        defaultPriority: Priority,
        listTitles: ListTitles,
        todayEpochDay: Long,
        nowMillis: Long,
    ): StructureResult {
        val rules = RuleBasedStructurer(defaultPriority, listTitles)
        val fallback = { error: AiError? ->
            StructureResult(rules.structure(transcript, todayEpochDay, nowMillis, chosenType), error)
        }
        if (provider == AiProvider.NONE || transcript.isBlank()) return fallback(null)

        val key = try {
            secrets.apiKey(provider)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not "no key": the saved key is fine, so the user should simply try again.
            return fallback(AiError.KeyUnreadable)
        }
        if (key.isNullOrBlank()) return fallback(AiError.MissingKey)
        val ai = providerFor(provider, key) ?: return fallback(null)

        return try {
            StructureResult(
                ai.structure(StructureRequest(transcript, todayEpochDay, nowMillis, defaultPriority, chosenType)),
            )
        } catch (e: AiError) {
            fallback(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Anything unexpected from the AI path must not cost the note - the rules take over.
            fallback(AiError.BadResponse(e::class.simpleName ?: "unexpected error"))
        }
    }
}
