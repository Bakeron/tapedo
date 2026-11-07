package io.github.bakeron.tapedo.core.ai

import io.github.bakeron.tapedo.core.settings.AiProvider
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AiGatewayTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun problemOf(status: Int, body: String) = ProblemReader.error(status, body)

    @Test
    fun `error answers are named in plain words`() {
        // Real answers collected by the Oferty-pracy project on 7.11.2026.
        val geminiKey = """[{"error": {"code": 400, "message": "API key not valid. Please pass a valid API key.", "status": "INVALID_ARGUMENT", "details": [{"reason": "API_KEY_INVALID"}]}}]"""
        val e = problemOf(400, geminiKey)
        assertEquals(Problem.KEY, e.problem)
        assertEquals("API key not valid. Please pass a valid API key.", e.providerMessage)

        // The bug from 7.11.2026: this used to say "the provider rejected the key".
        val noFree = """{"error": {"code": 403, "message": "The Gemini API free tier is not available for this project. To use the Gemini API, please set up billing to upgrade to the paid tier at https://aistudio.google.com/billing.", "status": "PERMISSION_DENIED"}}"""
        assertEquals(Problem.NO_FREE_TIER, problemOf(403, noFree).problem)

        val verify = """{"error": {"code": 403, "message": "Your project has been denied access.", "status": "PERMISSION_DENIED", "details": [{"reason": "FREE_TIER_VERIFICATION_REQUIRED"}]}}"""
        assertEquals(Problem.VERIFY, problemOf(403, verify).problem)
        val denied = """{"error": {"code": 403, "message": "Your project has been denied access.", "status": "PERMISSION_DENIED"}}"""
        assertEquals(Problem.DENIED, problemOf(403, denied).problem)
        val off = """{"error": {"code": 403, "message": "Generative Language API has not been used in project 123 before or it is disabled.", "status": "PERMISSION_DENIED", "details": [{"reason": "SERVICE_DISABLED"}]}}"""
        assertEquals(Problem.API_OFF, problemOf(403, off).problem)

        // "limit: 0" = the model is not on this account's free plan - not a limit to wait for.
        val zero = """{"error": {"code": 429, "message": "You exceeded your current quota. \n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 0, model: gemini-2.5-flash", "status": "RESOURCE_EXHAUSTED"}}"""
        assertEquals(Problem.MODEL, problemOf(429, zero).problem)
        val quota = """{"error": {"code": 429, "message": "Quota exceeded for metric: x, limit: 20, model: gemini-3.5-flash-lite", "status": "RESOURCE_EXHAUSTED"}}"""
        assertEquals(Problem.LIMIT, problemOf(429, quota).problem)

        val gone = """{"error": {"code": 404, "message": "models/gemini-9 is not found for API version v1beta.", "status": "NOT_FOUND"}}"""
        assertEquals(Problem.MODEL, problemOf(404, gone).problem)
        val groq = """{"error":{"message":"The model `x` does not exist or you do not have access to it.","type":"invalid_request_error","code":"model_not_found"}}"""
        assertEquals(Problem.MODEL, problemOf(404, groq).problem)
        val anthropic = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
        assertEquals(Problem.KEY, problemOf(401, anthropic).problem)
        val credit = """{"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API."}}"""
        assertEquals(Problem.DENIED, problemOf(400, credit).problem)

        val html = problemOf(500, "<html><body><h1>Server error</h1></body></html>")
        assertEquals(Problem.OTHER, html.problem)
        assertEquals("Server error", html.providerMessage)
    }

    @Test
    fun `the key never reaches the message on screen`() {
        val key = "sk-abcdefghijklmnop"
        val e = ProblemReader.error(401, """{"error":{"message":"Incorrect API key provided: $key."}}""", key)
        assertFalse(key in e.providerMessage)
    }

    @Test
    fun `openai-compatible call sends the key only in the header and asks for json`() = runTest {
        var url = ""
        var auth: String? = null
        var body = ""
        val engine = MockEngine { req ->
            url = req.url.toString()
            auth = req.headers[HttpHeaders.Authorization]
            body = String(req.body.toByteArray())
            respond("""{"model":"openai/gpt-oss-120b","choices":[{"message":{"content":"{\"cards\":[]}"}}]}""", headers = jsonHeaders)
        }
        val answer = HttpAiGateway(aiHttpClient(engine)).chat(AiProvider.GROQ, "openai/gpt-oss-120b", "gsk_secret").ask("s", "u", json = true)
        assertEquals("https://api.groq.com/openai/v1/chat/completions", url)
        assertEquals("Bearer gsk_secret", auth)
        assertFalse("gsk_secret" in url)
        assertEquals("json_object", Json.parseToJsonElement(body).jsonObject["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("openai/gpt-oss-120b", answer.model)
    }

    @Test
    fun `gemini goes through its openai endpoint`() = runTest {
        var url = ""
        val engine = MockEngine { req ->
            url = req.url.toString()
            respond("""{"choices":[{"message":{"content":"ok"}}]}""", headers = jsonHeaders)
        }
        HttpAiGateway(aiHttpClient(engine)).chat(AiProvider.GEMINI, "gemini-3.5-flash-lite", "AIzaKey").ask("s", "u", json = true)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", url)
    }

    @Test
    fun `claude call uses the messages api and reads only text blocks`() = runTest {
        var headers = mapOf<String, String?>()
        var body = ""
        val engine = MockEngine { req ->
            headers = listOf("x-api-key", "anthropic-version", "anthropic-beta", HttpHeaders.Authorization).associateWith { req.headers[it] }
            body = String(req.body.toByteArray())
            respond(
                """{"model":"claude-opus-5-5","stop_reason":"end_turn","content":[{"type":"thinking","thinking":""},{"type":"text","text":"{\"cards\":"},{"type":"text","text":"[]}"}]}""",
                headers = jsonHeaders,
            )
        }
        val answer = HttpAiGateway(aiHttpClient(engine)).chat(AiProvider.ANTHROPIC, "claude-opus-5-5", "sk-ant-1").ask("s", "u", json = true)
        assertEquals("""{"cards":[]}""", answer.text)
        assertEquals("sk-ant-1", headers["x-api-key"])
        assertEquals("2026-10-21", headers["anthropic-version"])
        assertEquals("server-side-fallback-2026-07-01", headers["anthropic-beta"])
        assertEquals(null, headers[HttpHeaders.Authorization])
        val sent = Json.parseToJsonElement(body).jsonObject
        assertEquals("default", sent["fallbacks"]!!.jsonPrimitive.content)
        assertEquals("low", sent["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse("temperature" in sent)
    }

    @Test
    fun `a refusal or an empty answer is a broken answer, a lost connection is offline`() = runTest {
        suspend fun problem(status: HttpStatusCode, body: String, provider: AiProvider = AiProvider.ANTHROPIC): Problem {
            val engine = MockEngine { respond(body, status, jsonHeaders) }
            return try {
                HttpAiGateway(aiHttpClient(engine)).chat(provider, "m", "k").ask("s", "u", json = true)
                fail("expected an AiException"); throw IllegalStateException()
            } catch (e: AiException) {
                e.problem
            }
        }
        assertEquals(Problem.ANSWER, problem(HttpStatusCode.OK, """{"stop_reason":"refusal","content":[]}"""))
        assertEquals(Problem.ANSWER, problem(HttpStatusCode.OK, """{"choices":[]}""", AiProvider.OPENAI))
        assertEquals(Problem.ANSWER, problem(HttpStatusCode.OK, """{"choices":[{"finish_reason":"content_filter","message":{}}]}""", AiProvider.OPENAI))
        assertEquals(Problem.LIMIT, problem(HttpStatusCode.TooManyRequests, """{"error":{"message":"Rate limit reached"}}""", AiProvider.GROQ))

        val down = MockEngine { throw IOException("Unable to resolve host api.groq.com") }
        try {
            HttpAiGateway(aiHttpClient(down)).chat(AiProvider.GROQ, "m", "k").ask("s", "u", json = true)
            fail("expected offline")
        } catch (e: AiException) {
            assertEquals(Problem.OFFLINE, e.problem)
            assertFalse("the address is not shown", "groq.com" in e.providerMessage)
        }
    }

    @Test
    fun `only free chat models are offered, openrouter without a key`() = runTest {
        var openRouterAuth: String? = "unset"
        val engine = MockEngine { req ->
            if (req.url.host == "openrouter.ai") {
                openRouterAuth = req.headers[HttpHeaders.Authorization]
                respond("""{"data":[{"id":"google/gemma-4-31b-it:free"},{"id":"google/gemma-4-31b-it"},{"id":"openrouter/free"},{"id":"<b>x</b>:free"}]}""", headers = jsonHeaders)
            } else {
                respond("""{"data":[{"id":"llama-3.3-70b-versatile"},{"id":"whisper-large-v3"},{"id":"openai/gpt-oss-safeguard-20b"},{"id":"old","active":false}]}""", headers = jsonHeaders)
            }
        }
        val gateway = HttpAiGateway(aiHttpClient(engine))
        assertEquals(listOf("google/gemma-4-31b-it:free", "openrouter/free"), gateway.freeModels(AiProvider.OPENROUTER, null))
        assertEquals(null, openRouterAuth)
        assertEquals(listOf("llama-3.3-70b-versatile"), gateway.freeModels(AiProvider.GROQ, "gsk"))
        assertTrue(gateway.freeModels(AiProvider.ANTHROPIC, "k").isEmpty())
    }

    @Test
    fun `defaults of free providers are on their saved free lists`() {
        for (p in AiProvider.entries.filter { it.free }) assertTrue(p.name, p.defaultModel in p.savedFreeModels)
        assertEquals(listOf("groq", "openrouter", "anthropic", "openai", "gemini"), AiProvider.entries.map { it.id })
        assertFalse("Gemini is paid - new projects get no free plan", AiProvider.GEMINI.free)
    }
}
