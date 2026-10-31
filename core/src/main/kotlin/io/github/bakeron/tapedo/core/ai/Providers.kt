package io.github.bakeron.tapedo.core.ai

import io.github.bakeron.tapedo.core.model.Note
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One HTTP client for all providers. No logging plugin on purpose: note text and keys never reach logs. */
fun aiHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 30_000
    }
}

/** Google Gemini, free tier available. https://ai.google.dev/gemini-api/docs */
class GeminiStructurer(
    private val http: HttpClient,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
) : AiStructurer {

    override suspend fun structure(request: StructureRequest): List<Note> {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", AiContract.systemPrompt(request)) } }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", AiContract.userMessage(request)) } }
                }
            }
            putJsonObject("generationConfig") { put("responseMimeType", "application/json") }
        }
        val response = call {
            http.post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent") {
                // Key in a header, not in the URL - URLs end up in proxies and crash reports.
                header("x-goog-api-key", apiKey)
                setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
        }
        val text = readJson(response) { root ->
            root["candidates"]!!.jsonArray[0].jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray
                .map { it.jsonObject }
                .filterNot { it["thought"]?.jsonPrimitive?.boolean == true }
                .joinToString("") { it["text"]?.jsonPrimitive?.content.orEmpty() }
        }
        return AiContract.parse(text, request)
    }

    companion object {
        /** Newest stable Flash on the free tier (checked 31.10.2026). */
        const val DEFAULT_MODEL = "gemini-3.8-flash"
    }
}

/** Groq (OpenAI-compatible API), free tier available. https://console.groq.com/docs */
class GroqStructurer(
    private val http: HttpClient,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
) : AiStructurer {

    override suspend fun structure(request: StructureRequest): List<Note> {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", AiContract.systemPrompt(request)) }
                addJsonObject { put("role", "user"); put("content", AiContract.userMessage(request)) }
            }
            putJsonObject("response_format") { put("type", "json_object") }
            put("reasoning_effort", "low")
        }
        val response = call {
            http.post("https://api.groq.com/openai/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
        }
        val text = readJson(response) { root ->
            root["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
        }
        return AiContract.parse(text, request)
    }

    companion object {
        /** Groq's flagship production model (checked 31.10.2026). */
        const val DEFAULT_MODEL = "openai/gpt-oss-120b"
    }
}

private suspend fun call(block: suspend () -> HttpResponse): HttpResponse =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw AiError.Network(e)
    }

private suspend fun readJson(response: HttpResponse, extract: (JsonObject) -> String): String {
    val text = response.bodyAsText()
    val status = response.status.value
    when {
        status == 429 -> throw AiError.RateLimited
        status == 401 || status == 403 -> throw AiError.InvalidKey
        // Gemini answers a wrong key with 400 + API_KEY_INVALID.
        status == 400 && "API_KEY_INVALID" in text -> throw AiError.InvalidKey
        status >= 400 -> throw AiError.Server(status)
    }
    return try {
        extract(Json.parseToJsonElement(text).jsonObject)
    } catch (e: Exception) {
        throw AiError.BadResponse("missing text in provider reply")
    }
}
