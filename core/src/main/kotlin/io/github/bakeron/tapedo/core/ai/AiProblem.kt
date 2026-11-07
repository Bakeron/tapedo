package io.github.bakeron.tapedo.core.ai

import io.github.bakeron.tapedo.core.settings.AiProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What went wrong with an AI provider - each kind has its own "what happened + what to do" text. */
enum class Problem {
    /** The provider refused the key. */
    KEY,

    /** The API is turned off in the provider's project (Google Cloud). */
    API_OFF,

    /** Google gives this project no free plan (a new key in it does not help). */
    NO_FREE_TIER,

    /** The project waits for a phone verification in Google AI Studio. */
    VERIFY,

    /** The provider refused this account or project for another reason (also: no credit). */
    DENIED,

    /** No such model, or not for this account or plan. */
    MODEL,

    /** The provider's own limit is used. */
    LIMIT,

    /** The daily limit from the settings is used. */
    CAP,

    /** No key in the settings. */
    NO_KEY,

    /** A key is saved, but the phone's key store could not read it for now - it is not lost. */
    KEY_UNREADABLE,

    /** No connection, or the provider does not answer. */
    OFFLINE,

    /** An answer came, but empty, refused or broken. */
    ANSWER,
    OTHER,
    ;

    /** A problem of this provider's key, project or model - the next provider is asked instead. */
    val tryNext: Boolean
        get() = this in setOf(KEY, API_OFF, NO_FREE_TIER, VERIFY, DENIED, MODEL, NO_KEY, KEY_UNREADABLE)
}

/**
 * An AI call failed. [providerMessage] is the provider's own words (cut to one line) - never the key,
 * the request address or the note text.
 */
class AiException(val problem: Problem, val providerMessage: String = "") : Exception(problem.name)

/** One provider that could not write the cards, and why - shown after a recording and in Settings. */
data class AiProblem(
    val provider: AiProvider,
    val model: String,
    val problem: Problem,
    val providerMessage: String = "",
)

/** Reads a provider's error answer: the kind of problem from the whole answer, and its message for display. */
object ProblemReader {

    private const val MAX_MESSAGE = 500

    fun error(status: Int, body: String, key: String? = null): AiException =
        AiException(problemIn(status, body), mask(providerMessage(body) ?: snippet(body), key))

    /** What an error answer says - read from the whole answer, before it is cut for display. */
    fun problemIn(status: Int, body: String): Problem {
        val t = body.lowercase()
        fun has(vararg words: String) = words.any { it in t }
        if (has("free_tier_verification_required", "verification needed", "verification required")) return Problem.VERIFY
        if (has("free tier is not available", "free tier not available", "not eligible for the free tier")) return Problem.NO_FREE_TIER
        if (has("service_disabled", "has not been used in project", "it is disabled", "api is not enabled", "api has not been enabled")) {
            return Problem.API_OFF
        }
        if (has(
                "api_key_invalid", "api key not valid", "api key expired", "invalid api key", "incorrect api key", "invalid_api_key",
                "invalid x-api-key", "authentication_error", "reported as leaked", "api key was blocked",
            )
        ) {
            return Problem.KEY
        }
        // Google answers "limit: 0" when the free plan of this account does not include the model.
        if (Regex("limit:\\s*0(?!\\d)").containsMatchIn(t)) return Problem.MODEL
        val modelGone = has(
            "not found", "does not exist", "not supported", "no longer available", "not available", "decommissioned",
            "no endpoints found", "invalid model", "unknown model", "is not a valid model", "model_not_found",
        )
        if (status == 404 || ("model" in t && modelGone)) return Problem.MODEL
        // No money on a paid account (Claude: 402 billing_error / "credit balance is too low").
        if (status == 402 || has("credit balance", "insufficient_quota", "billing_error")) return Problem.DENIED
        return when {
            status == 401 -> Problem.KEY
            status == 429 -> Problem.LIMIT
            has("resource_exhausted", "quota", "rate limit") -> Problem.LIMIT
            status == 403 -> Problem.DENIED
            else -> Problem.OTHER
        }
    }

    private val json = Json { isLenient = true }

    /**
     * The message inside an error answer: OpenAI-style `{"error": {"message"}}`, Google's list of such
     * objects, Anthropic's `{"type":"error","error":{"message"}}`, or `{"error": "..."}`.
     */
    fun providerMessage(body: String): String? {
        val root = try {
            json.parseToJsonElement(body.trim())
        } catch (e: Exception) {
            return null
        }
        val v = (if (root is JsonArray) root.firstOrNull() else root) as? JsonObject ?: return null
        val error = v["error"]
        val text = ((error as? JsonObject)?.get("message")).text()
            ?: error.text()
            ?: v["message"].text()
            ?: v["detail"].text()
            ?: return null
        return oneLine(text, MAX_MESSAGE).ifEmpty { null }
    }

    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** A non-JSON error page: its text without tags, short. */
    private fun snippet(body: String): String = oneLine(body.replace(Regex("<[^>]*>"), " "), 200)

    fun oneLine(text: String, max: Int): String = text.replace(Regex("\\s+"), " ").trim().take(max)

    /** Some providers repeat part of the key in their message - it never reaches the screen. */
    private fun mask(message: String, key: String?): String =
        if (key.isNullOrBlank() || key.length < 8) message else message.replace(key, "••••")
}
