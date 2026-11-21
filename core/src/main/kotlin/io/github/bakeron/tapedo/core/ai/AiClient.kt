package io.github.bakeron.tapedo.core.ai

import io.github.bakeron.tapedo.core.settings.AiProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * One HTTP client for all providers: connection 15 s, the whole answer 90 s, no retries in a loop.
 * No logging plugin on purpose: note text and keys never reach logs.
 */
fun aiHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 90_000
    }
}

/** The message of an answer with no text - "Check connection" still counts it as working. */
const val EMPTY_ANSWER = "empty answer"

/**
 * An answer and the model that wrote it (as the provider names it; null when it does not say).
 * [tokens] only from Claude - its cost goes to the spending counter.
 */
data class AiAnswer(val text: String, val model: String?, val tokens: Tokens? = null)

/** One question, one answer - what the note structurer needs from a provider. Swappable in tests. */
fun interface AiChat {
    /** @param json the answer must be a JSON object. Throws [AiException]. */
    suspend fun ask(system: String, user: String, json: Boolean): AiAnswer
}

/** Builds clients and reads model lists; the app gives the real HTTP one, tests a fake. */
interface AiGateway {
    fun chat(provider: AiProvider, model: String, key: String): AiChat

    /** Free chat models of a free provider as it lists them today. */
    suspend fun freeModels(provider: AiProvider, key: String?): List<String>
}

/**
 * Two kinds of API cover every provider: OpenAI-compatible chat (Groq, OpenAI, Gemini through
 * its OpenAI endpoint) and the Anthropic Messages API (Claude). The key goes only into a request header.
 */
class HttpAiGateway(private val http: HttpClient) : AiGateway {

    override fun chat(provider: AiProvider, model: String, key: String): AiChat = AiChat { system, user, json ->
        if (provider == AiProvider.ANTHROPIC) askAnthropic(model, key, system, user) else askOpenAi(provider, model, key, system, user, json)
    }

    private suspend fun askOpenAi(provider: AiProvider, model: String, key: String, system: String, user: String, json: Boolean): AiAnswer {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", system) }
                addJsonObject { put("role", "user"); put("content", user) }
            }
            if (json) putJsonObject("response_format") { put("type", "json_object") }
        }
        val root = call(key) {
            http.post(chatUrl(provider)) {
                header(HttpHeaders.Authorization, "Bearer $key")
                setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
        }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: throw empty()
        if (choice.str("finish_reason") == "content_filter") throw AiException(Problem.ANSWER, "the provider refused to answer")
        val text = (choice["message"] as? JsonObject)?.str("content")?.takeIf { it.isNotBlank() } ?: throw empty()
        return AiAnswer(text, root.str("model"))
    }

    /** Claude: JSON is asked for in the prompt (the answer is checked field by field anyway). */
    private suspend fun askAnthropic(model: String, key: String, system: String, user: String): AiAnswer {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 16_000)
            put("system", system)
            putJsonArray("messages") { addJsonObject { put("role", "user"); put("content", user) } }
            // Cutting one memo into cards is a simple task - low effort keeps it fast and cheap.
            putJsonObject("output_config") { put("effort", "low") }
            // A safety decline is retried on a fallback model chosen by the API.
            put("fallbacks", "default")
        }
        val root = call(key) {
            http.post("https://api.anthropic.com/v1/messages") {
                header("x-api-key", key)
                header("anthropic-version", "2026-10-21")
                header("anthropic-beta", "server-side-fallback-2026-07-01")
                setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
        }
        if (root.str("stop_reason") == "refusal") throw AiException(Problem.ANSWER, "the model refused to answer")
        val text = (root["content"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.str("type") == "text" }
            .joinToString("") { it.str("text").orEmpty() }
        if (text.isBlank()) throw empty()
        return AiAnswer(text, root.str("model"), Tokens.fromUsage(root["usage"] as? JsonObject))
    }

    override suspend fun freeModels(provider: AiProvider, key: String?): List<String> = when (provider) {
        AiProvider.GROQ -> {
            val root = call(key) {
                http.get("https://api.groq.com/openai/v1/models") { header(HttpHeaders.Authorization, "Bearer ${key.orEmpty()}") }
            }
            tidy(models(root).filter { it.active && chatGroq(it.id) }.map { it.id })
        }
        AiProvider.ANTHROPIC, AiProvider.OPENAI, AiProvider.GEMINI -> emptyList()
    }

    private fun chatUrl(provider: AiProvider) = when (provider) {
        AiProvider.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
        AiProvider.GROQ -> "https://api.groq.com/openai/v1/chat/completions"
        AiProvider.OPENAI -> "https://api.openai.com/v1/chat/completions"
        AiProvider.ANTHROPIC -> "https://api.anthropic.com/v1/messages"
    }

    private class Model(val id: String, val active: Boolean)

    private fun models(root: JsonObject): List<Model> = (root["data"] as? JsonArray).orEmpty().mapNotNull { m ->
        val o = m as? JsonObject ?: return@mapNotNull null
        Model(o.str("id") ?: return@mapNotNull null, (o["active"] as? JsonPrimitive)?.booleanOrNull != false)
    }

    private suspend fun call(key: String?, block: suspend () -> HttpResponse): JsonObject = providerJson(key, block)

    private fun empty() = AiException(Problem.ANSWER, EMPTY_ANSWER)
}

/** A provider's answer as JSON; an error answer becomes [AiException] with its kind. */
internal suspend fun providerJson(key: String?, block: suspend () -> HttpResponse): JsonObject {
    val response = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Timeouts, no network, DNS - the exception text may hold the address, so only the kind is kept.
        throw AiException(Problem.OFFLINE)
    }
    val text = try {
        response.bodyAsText()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw AiException(Problem.OFFLINE)
    }
    val status = response.status.value
    if (status !in 200..299) throw ProblemReader.error(status, text, key)
    return try {
        lenient.parseToJsonElement(text) as? JsonObject
    } catch (e: Exception) {
        null
    } ?: throw AiException(Problem.ANSWER, "not JSON")
}

private val lenient = Json { isLenient = true }

internal fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

/** Outside data: only plain model names, a short sorted list. */
internal fun tidy(names: List<String>): List<String> = names.filter(::isModelName).distinct().sorted().take(40)

/** Model names as providers write them: letters, digits and `-_.:/@`. */
internal fun isModelName(v: String): Boolean =
    v.isNotEmpty() && v.length <= 120 && v.all { it.isLetterOrDigit() && it.code < 128 || it in "-_.:/@" }

/** Groq lists speech and safety models too - only chat models are offered. */
internal fun chatGroq(name: String): Boolean = listOf("whisper", "orpheus", "guard", "tts", "playai", "distil").none { it in name }
