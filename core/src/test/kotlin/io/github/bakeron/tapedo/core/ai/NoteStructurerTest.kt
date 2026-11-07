package io.github.bakeron.tapedo.core.ai

import io.github.bakeron.tapedo.core.model.Priority
import io.github.bakeron.tapedo.core.rules.ListTitles
import io.github.bakeron.tapedo.core.settings.AiProvider
import io.github.bakeron.tapedo.core.settings.AiProvider.ANTHROPIC
import io.github.bakeron.tapedo.core.settings.AiProvider.GEMINI
import io.github.bakeron.tapedo.core.settings.AiProvider.GROQ
import io.github.bakeron.tapedo.core.settings.AiProvider.OPENROUTER
import io.github.bakeron.tapedo.core.settings.AppSettings
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NoteStructurerTest {

    private val today = 20_400L
    private val cards = """{"cards":[{"title":"Mleko","type":"shopping","items":[{"text":"Mleko","group":"dairy"}]}]}"""

    private class Secrets(val keys: Map<AiProvider, String>, val unreadable: Set<AiProvider> = emptySet()) : SecretStore {
        override suspend fun apiKey(provider: AiProvider): String? {
            if (provider in unreadable) throw IOException("keystore busy")
            return keys[provider]
        }
        override suspend fun setApiKey(provider: AiProvider, key: String?) = Unit
    }

    /** Each provider answers with its script: a text, or an exception to throw. */
    private class Gateway(val script: Map<AiProvider, () -> String>) : AiGateway {
        val asked = mutableListOf<AiProvider>()
        val models = mutableListOf<String>()
        override fun chat(provider: AiProvider, model: String, key: String) = AiChat { _, _, _ ->
            asked += provider
            models += model
            AiAnswer(script.getValue(provider)(), model)
        }
        override suspend fun freeModels(provider: AiProvider, key: String?) = emptyList<String>()
    }

    private fun fails(problem: Problem, message: String = ""): () -> String = { throw AiException(problem, message) }

    private val allKeys = AiProvider.entries.associateWith { "key-${it.id}" }

    private fun TestScope.structurer(gateway: Gateway, usage: AiUsage = MemoryAiUsage(), secrets: SecretStore = Secrets(allKeys)) =
        NoteStructurer(secrets, usage, gateway, testScheduler.timeSource)

    private suspend fun NoteStructurer.run(settings: AppSettings) =
        structure("kup mleko", settings, null, ListTitles(), today, 0L)

    private fun on(vararg p: AiProvider, limit: Int = 200) = AppSettings(aiProviders = p.toSet(), aiDailyLimit = limit)

    @Test
    fun `no provider turned on - the rules, without any message`() = runTest {
        val gateway = Gateway(emptyMap())
        val result = structurer(gateway).run(AppSettings())
        assertTrue(result.problems.isEmpty())
        assertFalse(result.notes.single().byAi)
        assertTrue(gateway.asked.isEmpty())
    }

    @Test
    fun `providers are asked in the fixed order, whatever order they were turned on`() = runTest {
        val gateway = Gateway(mapOf(GROQ to { cards }, GEMINI to { cards }))
        val result = structurer(gateway).run(AppSettings(aiProviders = linkedSetOf(GEMINI, GROQ)))
        assertEquals(listOf(GROQ), gateway.asked)
        assertTrue(result.notes.single().byAi)
    }

    @Test
    fun `a provider's limit sends the note to the next one and rests it until tomorrow`() = runTest {
        val usage = MemoryAiUsage()
        val gateway = Gateway(mapOf(GROQ to fails(Problem.LIMIT, "Rate limit reached"), OPENROUTER to { cards }))
        val s = structurer(gateway, usage)

        val first = s.run(on(GROQ, OPENROUTER))
        assertTrue(first.notes.single().byAi)
        assertEquals(listOf(AiProblem(GROQ, GROQ.defaultModel, Problem.LIMIT, "Rate limit reached")), first.problems)
        assertTrue(usage.limitHit(GROQ, today))

        // The next note does not ask Groq at all today.
        s.run(on(GROQ, OPENROUTER))
        assertEquals(listOf(GROQ, OPENROUTER, OPENROUTER), gateway.asked)
        // ...and tomorrow it does.
        assertFalse(usage.limitHit(GROQ, today + 1))
    }

    @Test
    fun `a wrong key or a missing free plan goes to the next one, asked again with the next note`() = runTest {
        val gateway = Gateway(mapOf(GROQ to fails(Problem.KEY), GEMINI to fails(Problem.NO_FREE_TIER), ANTHROPIC to { cards }))
        val s = structurer(gateway)
        val result = s.run(on(GROQ, ANTHROPIC, GEMINI))
        assertTrue(result.notes.single().byAi)
        assertEquals(listOf(Problem.KEY), result.problems.map { it.problem })
        s.run(on(GROQ, ANTHROPIC, GEMINI))
        assertEquals(listOf(GROQ, ANTHROPIC, GROQ, ANTHROPIC), gateway.asked)
    }

    @Test
    fun `everybody failing gives the rules and every reason`() = runTest {
        val gateway = Gateway(mapOf(GROQ to fails(Problem.MODEL), GEMINI to fails(Problem.NO_FREE_TIER)))
        val result = structurer(gateway).run(on(GROQ, GEMINI))
        assertFalse(result.notes.single().byAi)
        assertEquals(listOf("Mleko"), result.notes.single().items.map { it.text })
        assertEquals(listOf(GROQ to Problem.MODEL, GEMINI to Problem.NO_FREE_TIER), result.problems.map { it.provider to it.problem })
    }

    @Test
    fun `no connection - the rules at once, the next provider is not tried`() = runTest {
        val gateway = Gateway(mapOf(GROQ to fails(Problem.OFFLINE), OPENROUTER to { cards }))
        val usage = MemoryAiUsage()
        val result = structurer(gateway, usage).run(on(GROQ, OPENROUTER))
        assertFalse(result.notes.single().byAi)
        assertEquals(listOf(GROQ), gateway.asked)
        assertEquals("a call that never arrived does not count", 0, usage.callsToday(GROQ, today))
    }

    @Test
    fun `a broken answer - this note from the rules, counted once`() = runTest {
        val gateway = Gateway(mapOf(GROQ to { "Sorry, I can't do JSON today." }, OPENROUTER to { cards }))
        val usage = MemoryAiUsage()
        val result = structurer(gateway, usage).run(on(GROQ, OPENROUTER))
        assertFalse(result.notes.single().byAi)
        assertEquals(Problem.ANSWER, result.problems.single().problem)
        assertEquals(1, usage.callsToday(GROQ, today))
    }

    @Test
    fun `the daily limit from the settings stops a provider before it is asked`() = runTest {
        val usage = MemoryAiUsage()
        repeat(2) { usage.addCall(GROQ, today) }
        val gateway = Gateway(mapOf(GROQ to { cards }, OPENROUTER to { cards }))
        val result = structurer(gateway, usage).run(on(GROQ, OPENROUTER, limit = 2))
        assertEquals(listOf(OPENROUTER), gateway.asked)
        assertEquals(Problem.CAP, result.problems.single().problem)
    }

    @Test
    fun `no key or an unreadable key is reported and the next one asked`() = runTest {
        val gateway = Gateway(mapOf(OPENROUTER to { cards }))
        val secrets = Secrets(mapOf(OPENROUTER to "k", ANTHROPIC to "k"), unreadable = setOf(ANTHROPIC))
        val result = structurer(gateway, secrets = secrets).run(on(GROQ, ANTHROPIC))
        assertFalse(result.notes.single().byAi)
        assertEquals(listOf(Problem.NO_KEY, Problem.KEY_UNREADABLE), result.problems.map { it.problem })
        assertTrue(gateway.asked.isEmpty())
    }

    @Test
    fun `an empty model field means the default model, a typed one is used as written`() = runTest {
        val gateway = Gateway(mapOf(GROQ to { cards }))
        val s = structurer(gateway)
        s.run(on(GROQ).copy(aiModels = mapOf(GROQ to "  ")))
        s.run(on(GROQ).copy(aiModels = mapOf(GROQ to "llama-3.1-8b-instant")))
        assertEquals(listOf(GROQ.defaultModel, "llama-3.1-8b-instant"), gateway.models)
    }

    @Test
    fun `two notes in a row wait for the provider's pause`() = runTest {
        val gateway = Gateway(mapOf(OPENROUTER to { cards }))
        val s = structurer(gateway)
        s.run(on(OPENROUTER))
        val before = currentTime
        s.run(on(OPENROUTER))
        assertEquals(OPENROUTER.pauseMillis, currentTime - before)
    }

    @Test
    fun `check connection takes off today's limit mark and names the model that answered`() = runTest {
        val usage = MemoryAiUsage().apply { markLimit(GROQ, today) }
        val gateway = object : AiGateway {
            override fun chat(provider: AiProvider, model: String, key: String) = AiChat { _, _, _ -> AiAnswer("OK", "openai/gpt-oss-120b-2026") }
            override suspend fun freeModels(provider: AiProvider, key: String?) = emptyList<String>()
        }
        val check = AiConnection(Secrets(allKeys), usage, gateway).check(GROQ, AppSettings(), today)
        assertEquals(AiConnection.Check.Ok("openai/gpt-oss-120b-2026"), check)
        assertFalse(usage.limitHit(GROQ, today))
        assertEquals(1, usage.callsToday(GROQ, today))
    }

    @Test
    fun `free models fall back to the saved list with the reason`() = runTest {
        val gateway = object : AiGateway {
            override fun chat(provider: AiProvider, model: String, key: String) = AiChat { _, _, _ -> error("not used") }
            override suspend fun freeModels(provider: AiProvider, key: String?): List<String> = throw AiException(Problem.KEY)
        }
        val list = AiConnection(Secrets(allKeys), MemoryAiUsage(), gateway).freeModels(GROQ, AppSettings())
        assertFalse(list.fromProvider)
        assertEquals(GROQ.savedFreeModels, list.models)
        assertEquals(Problem.KEY, list.problem?.problem)
    }

    @Test
    fun `default priority from the settings reaches the rules`() = runTest {
        val result = structurer(Gateway(emptyMap())).structure(
            "zadzwonić do mamy", AppSettings(defaultPriority = Priority.LOW), null, ListTitles(), today, 0L,
        )
        assertEquals(Priority.LOW, result.notes.single().priority)
    }
}
