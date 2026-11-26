package io.github.bakeron.tapedo.core.ai

import io.github.bakeron.tapedo.core.model.ChecklistItem
import io.github.bakeron.tapedo.core.model.Note
import io.github.bakeron.tapedo.core.model.NoteType
import io.github.bakeron.tapedo.core.model.Priority
import io.github.bakeron.tapedo.core.time.EpochDays
import io.github.bakeron.tapedo.core.rules.ListTitles
import io.github.bakeron.tapedo.core.settings.AiMode
import io.github.bakeron.tapedo.core.settings.AiProvider
import io.github.bakeron.tapedo.core.settings.AiProvider.ANTHROPIC
import io.github.bakeron.tapedo.core.settings.AiProvider.GEMINI
import io.github.bakeron.tapedo.core.settings.AiProvider.GROQ
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

    /** The cards of a recording - always the rules. */
    private fun NoteStructurer.run() = structure("kup mleko", null, ListTitles(), today, 0L)

    /** "More exact" - the only way any AI is asked (author 26.11.2026). */
    private suspend fun NoteStructurer.ask(settings: AppSettings) = refine("kup mleko", settings, null, today, 0L)

    private val Refined.reason get() = (this as Refined.Failed).problem

    private fun on(vararg p: AiProvider, limit: Int = 200) = AppSettings(aiProviders = p.toSet(), aiDailyLimit = limit)

    private val claude = AppSettings(aiMode = AiMode.CLAUDE)

    @Test
    fun `a recording is cut by the rules - no AI is asked on its own, free or paid`() = runTest {
        val gateway = Gateway(mapOf(GROQ to { cards }, ANTHROPIC to { cards }))
        val result = structurer(gateway).run()
        assertFalse(result.notes.single().byAi)
        assertEquals("rules wrote it", null, result.by)
        assertTrue(gateway.asked.isEmpty())
    }

    @Test
    fun `a provider's limit fails the tap and rests the provider until tomorrow`() = runTest {
        val usage = MemoryAiUsage()
        val gateway = Gateway(mapOf(GROQ to fails(Problem.LIMIT, "Rate limit reached")))
        val s = structurer(gateway, usage)

        assertEquals(AiProblem(GROQ, GROQ.defaultModel, Problem.LIMIT, "Rate limit reached"), s.ask(on(GROQ)).reason)
        assertTrue(usage.limitHit(GROQ, today))

        // The next tap does not ask Groq at all today.
        assertEquals(Problem.LIMIT, s.ask(on(GROQ)).reason.problem)
        assertEquals(listOf(GROQ), gateway.asked)
        // ...and tomorrow it does.
        assertFalse(usage.limitHit(GROQ, today + 1))
    }

    @Test
    fun `a busy model fails the tap without resting the provider until tomorrow`() = runTest {
        val usage = MemoryAiUsage()
        val gateway = Gateway(mapOf(GROQ to fails(Problem.BUSY, "rate-limited upstream")))
        val s = structurer(gateway, usage)

        assertEquals(Problem.BUSY, s.ask(on(GROQ)).reason.problem)
        assertFalse(usage.limitHit(GROQ, today))

        // The next tap asks Groq again.
        s.ask(on(GROQ))
        assertEquals(listOf(GROQ, GROQ), gateway.asked)
    }

    @Test
    fun `a limit per minute of a few seconds is waited out once, a longer one is not`() = runTest {
        var groqCalls = 0
        val short = Gateway(
            mapOf(GROQ to { if (groqCalls++ == 0) throw AiException(Problem.BUSY, "TPM. Please try again in 2.5s.") else cards }),
        )
        val start = testScheduler.currentTime
        assertTrue(structurer(short).ask(on(GROQ)) is Refined.Cards)
        assertEquals(listOf(GROQ, GROQ), short.asked)
        assertTrue(testScheduler.currentTime - start >= 2_500)

        val long = Gateway(mapOf(GROQ to fails(Problem.BUSY, "TPM. Please try again in 40s.")))
        val before = testScheduler.currentTime
        assertTrue(structurer(long).ask(on(GROQ)) is Refined.Failed)
        assertEquals(listOf(GROQ), long.asked)
        assertTrue(testScheduler.currentTime - before < 40_000)
    }

    @Test
    fun `a wrong key fails the tap, and the provider is asked again on the next one`() = runTest {
        val gateway = Gateway(mapOf(GROQ to fails(Problem.KEY)))
        val s = structurer(gateway)
        assertEquals(Problem.KEY, s.ask(on(GROQ)).reason.problem)
        s.ask(on(GROQ))
        assertEquals(listOf(GROQ, GROQ), gateway.asked)
    }

    @Test
    fun `a failing provider gives the reason`() = runTest {
        val gateway = Gateway(mapOf(GROQ to fails(Problem.MODEL)))
        val reason = structurer(gateway).ask(on(GROQ)).reason
        assertEquals(GROQ to Problem.MODEL, reason.provider to reason.problem)
    }

    @Test
    fun `no connection fails at once, and the call does not count`() = runTest {
        val gateway = Gateway(mapOf(GROQ to fails(Problem.OFFLINE)))
        val usage = MemoryAiUsage()
        assertEquals(Problem.OFFLINE, structurer(gateway, usage).ask(on(GROQ)).reason.problem)
        assertEquals(listOf(GROQ), gateway.asked)
        assertEquals("a call that never arrived does not count", 0, usage.callsToday(GROQ, today))
    }

    @Test
    fun `a broken answer fails the tap, counted once`() = runTest {
        val gateway = Gateway(mapOf(GROQ to { "Sorry, I can't do JSON today." }))
        val usage = MemoryAiUsage()
        assertEquals(Problem.ANSWER, structurer(gateway, usage).ask(on(GROQ)).reason.problem)
        assertEquals(1, usage.callsToday(GROQ, today))
    }

    @Test
    fun `the daily limit from the settings stops a provider before it is asked`() = runTest {
        val usage = MemoryAiUsage()
        repeat(2) { usage.addCall(GROQ, today) }
        val gateway = Gateway(mapOf(GROQ to { cards }))
        val refined = structurer(gateway, usage).ask(on(GROQ, limit = 2))
        assertTrue(gateway.asked.isEmpty())
        assertEquals(Problem.CAP, refined.reason.problem)
    }

    @Test
    fun `no key or an unreadable key is reported and nobody is asked`() = runTest {
        val gateway = Gateway(mapOf(GROQ to { cards }))
        val none = structurer(gateway, secrets = Secrets(emptyMap())).ask(on(GROQ))
        val unreadable = structurer(gateway, secrets = Secrets(mapOf(GROQ to "k"), unreadable = setOf(GROQ))).ask(on(GROQ))
        assertEquals(Problem.NO_KEY, none.reason.problem)
        assertEquals(Problem.KEY_UNREADABLE, unreadable.reason.problem)
        assertTrue(gateway.asked.isEmpty())
    }

    @Test
    fun `an empty model field means the default model, a typed one is used as written`() = runTest {
        val gateway = Gateway(mapOf(GROQ to { cards }))
        val s = structurer(gateway)
        s.ask(on(GROQ).copy(aiModels = mapOf(GROQ to "  ")))
        s.ask(on(GROQ).copy(aiModels = mapOf(GROQ to "llama-3.1-8b-instant")))
        assertEquals(listOf(GROQ.defaultModel, "llama-3.1-8b-instant"), gateway.models)
    }

    @Test
    fun `two taps in a row wait for the provider's pause`() = runTest {
        val gateway = Gateway(mapOf(GROQ to { cards }))
        val s = structurer(gateway)
        s.ask(on(GROQ))
        val before = currentTime
        s.ask(on(GROQ))
        assertEquals(GROQ.pauseMillis, currentTime - before)
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
    fun `a card with no priority said gets low - the green tape`() = runTest {
        val result = structurer(Gateway(emptyMap())).structure("zadzwonić do mamy", null, ListTitles(), today, 0L)
        assertEquals(Priority.LOW, result.notes.single().priority)
    }

    @Test
    fun `more exact asks Claude Sonnet even when another Claude model is set`() = runTest {
        val usage = MemoryAiUsage()
        val gateway = Gateway(mapOf(ANTHROPIC to { cards }))
        val settings = claude.copy(aiModels = mapOf(ANTHROPIC to "claude-haiku-5-5"))
        val refined = structurer(gateway, usage).refine("kup mleko", settings, null, today, 0L)
        assertEquals(listOf(ANTHROPIC), gateway.asked)
        assertEquals(listOf("claude-sonnet-5-5"), gateway.models)
        assertTrue((refined as Refined.Cards).notes.single().byAi)
        assertEquals(1, usage.callsToday(ANTHROPIC, today))
    }

    @Test
    fun `more exact without a Claude key asks nobody`() = runTest {
        val gateway = Gateway(emptyMap())
        val s = structurer(gateway, secrets = Secrets(mapOf(GROQ to "k")))
        assertFalse(s.hasKey(ANTHROPIC))
        assertTrue(s.hasKey(GROQ))
        val refined = s.refine("kup mleko", claude, null, today, 0L)
        assertEquals(Problem.NO_KEY, (refined as Refined.Failed).problem.problem)
        assertTrue(gateway.asked.isEmpty())
    }

    @Test
    fun `a failed more exact gives the reason, never cards from the rules`() = runTest {
        val usage = MemoryAiUsage()
        val gateway = Gateway(mapOf(ANTHROPIC to fails(Problem.LIMIT, "rate_limit_error")))
        val refined = structurer(gateway, usage).refine("kup mleko", claude, null, today, 0L)
        val failed = refined as Refined.Failed
        assertEquals(Problem.LIMIT, failed.problem.problem)
        assertEquals("claude-sonnet-5-5", failed.problem.model)
        assertTrue(usage.limitHit(ANTHROPIC, today))
    }

    @Test
    fun `in free mode more exact asks the free provider, and a failure gives the reason, never the rules`() = runTest {
        val gateway = Gateway(mapOf(GROQ to { cards }))
        val refined = structurer(gateway).refine("kup mleko", on(GROQ), null, today, 0L)
        assertEquals(GROQ, (refined as Refined.Cards).by)
        assertEquals(listOf(GROQ), gateway.asked)

        val offline = Gateway(mapOf(GROQ to fails(Problem.OFFLINE)))
        val failed = structurer(offline).refine("kup mleko", on(GROQ), null, today, 0L)
        assertEquals(Problem.OFFLINE, (failed as Refined.Failed).problem.problem)
        assertEquals(GROQ, failed.problem.provider)
    }

    @Test
    fun `more exact counts money, not calls - the daily spending limit stops it`() = runTest {
        val usage = MemoryAiUsage()
        repeat(10) { usage.addCall(ANTHROPIC, today) }
        val gateway = Gateway(mapOf(ANTHROPIC to { cards }))
        val s = structurer(gateway, usage)
        assertTrue(s.refine("kup mleko", claude.copy(aiDailyLimit = 10), null, today, 0L) is Refined.Cards)

        usage.addClaudeCost(today, 1_000_000) // $1 spent today
        val stopped = s.refine("kup mleko", claude.copy(claudeDayCents = 100), null, today, 0L)
        assertEquals(Problem.BUDGET, (stopped as Refined.Failed).problem.problem)
        assertEquals(1, gateway.asked.size)

        assertTrue("no limit", s.refine("kup mleko", claude.copy(claudeDayCents = null), null, today, 0L) is Refined.Cards)
    }

    @Test
    fun `free mode asks only the free providers - paid and hidden ones are skipped`() = runTest {
        val gateway = Gateway(mapOf(GROQ to fails(Problem.KEY), ANTHROPIC to { cards }, GEMINI to { cards }))
        val refined = structurer(gateway).ask(on(GROQ, ANTHROPIC, GEMINI))
        assertEquals(listOf(GROQ), gateway.asked)
        assertEquals(Problem.KEY, refined.reason.problem)
    }

    @Test
    fun `a saved card becomes plain lines for the AI, with the date and hour as ISO`() {
        val card = Note(
            title = "Dentysta ",
            body = "",
            type = NoteType.EVENT,
            items = listOf(ChecklistItem("zabrać kartę"), ChecklistItem("  ")),
            dueEpochDay = EpochDays.fromCivil(2026, 11, 25),
            timeMinutes = 14 * 60 + 5,
            place = " ul. Długa 3",
            createdAtMillis = 0,
        )
        assertEquals("Dentysta\n- zabrać kartę\n2026-11-25 14:05\nul. Długa 3", card.asDictation())
        assertEquals("Mleko\nkupić dwa", Note(title = "Mleko", body = "kupić dwa", createdAtMillis = 0).asDictation())
    }
}
