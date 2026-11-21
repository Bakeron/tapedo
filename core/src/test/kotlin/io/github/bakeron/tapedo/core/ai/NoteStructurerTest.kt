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

    private suspend fun NoteStructurer.run(settings: AppSettings) =
        structure("kup mleko", settings, null, ListTitles(), today, 0L)

    private fun on(vararg p: AiProvider, limit: Int = 200) = AppSettings(aiProviders = p.toSet(), aiDailyLimit = limit)

    private val claude = AppSettings(aiMode = AiMode.CLAUDE)

    @Test
    fun `no provider turned on - the rules, without any message`() = runTest {
        val gateway = Gateway(emptyMap())
        val result = structurer(gateway).run(AppSettings())
        assertTrue(result.problems.isEmpty())
        assertFalse(result.notes.single().byAi)
        assertTrue(gateway.asked.isEmpty())
    }

    @Test
    fun `a provider's limit gives the note to the rules and rests the provider until tomorrow`() = runTest {
        val usage = MemoryAiUsage()
        val gateway = Gateway(mapOf(GROQ to fails(Problem.LIMIT, "Rate limit reached")))
        val s = structurer(gateway, usage)

        val first = s.run(on(GROQ))
        assertFalse(first.notes.single().byAi)
        assertEquals(listOf(AiProblem(GROQ, GROQ.defaultModel, Problem.LIMIT, "Rate limit reached")), first.problems)
        assertTrue(usage.limitHit(GROQ, today))

        // The next note does not ask Groq at all today.
        assertEquals(Problem.LIMIT, s.run(on(GROQ)).problems.single().problem)
        assertEquals(listOf(GROQ), gateway.asked)
        // ...and tomorrow it does.
        assertFalse(usage.limitHit(GROQ, today + 1))
    }

    @Test
    fun `a busy model gives the note to the rules without resting the provider until tomorrow`() = runTest {
        val usage = MemoryAiUsage()
        val gateway = Gateway(mapOf(GROQ to fails(Problem.BUSY, "rate-limited upstream")))
        val s = structurer(gateway, usage)

        val first = s.run(on(GROQ))
        assertFalse(first.notes.single().byAi)
        assertEquals(Problem.BUSY, first.problems.single().problem)
        assertFalse(usage.limitHit(GROQ, today))

        // The next note asks Groq again.
        s.run(on(GROQ))
        assertEquals(listOf(GROQ, GROQ), gateway.asked)
    }

    @Test
    fun `a limit per minute of a few seconds is waited out once, a longer one is not`() = runTest {
        var groqCalls = 0
        val short = Gateway(
            mapOf(GROQ to { if (groqCalls++ == 0) throw AiException(Problem.BUSY, "TPM. Please try again in 2.5s.") else cards }),
        )
        val start = testScheduler.currentTime
        val shortResult = structurer(short).run(on(GROQ))
        assertTrue(shortResult.notes.single().byAi)
        assertEquals(listOf(GROQ, GROQ), short.asked)
        assertTrue(testScheduler.currentTime - start >= 2_500)

        val long = Gateway(mapOf(GROQ to fails(Problem.BUSY, "TPM. Please try again in 40s.")))
        val before = testScheduler.currentTime
        assertFalse(structurer(long).run(on(GROQ)).notes.single().byAi)
        assertEquals(listOf(GROQ), long.asked)
        assertTrue(testScheduler.currentTime - before < 40_000)
    }

    @Test
    fun `a wrong key gives the note to the rules, and the provider is asked again with the next note`() = runTest {
        val gateway = Gateway(mapOf(GROQ to fails(Problem.KEY)))
        val s = structurer(gateway)
        val result = s.run(on(GROQ))
        assertFalse(result.notes.single().byAi)
        assertEquals(listOf(Problem.KEY), result.problems.map { it.problem })
        s.run(on(GROQ))
        assertEquals(listOf(GROQ, GROQ), gateway.asked)
    }

    @Test
    fun `a failing provider gives the rules and the reason`() = runTest {
        val gateway = Gateway(mapOf(GROQ to fails(Problem.MODEL)))
        val result = structurer(gateway).run(on(GROQ))
        assertFalse(result.notes.single().byAi)
        assertEquals(listOf("Mleko"), result.notes.single().items.map { it.text })
        assertEquals(listOf(GROQ to Problem.MODEL), result.problems.map { it.provider to it.problem })
    }

    @Test
    fun `no connection - the rules at once, and the call does not count`() = runTest {
        val gateway = Gateway(mapOf(GROQ to fails(Problem.OFFLINE)))
        val usage = MemoryAiUsage()
        val result = structurer(gateway, usage).run(on(GROQ))
        assertFalse(result.notes.single().byAi)
        assertEquals(listOf(GROQ), gateway.asked)
        assertEquals("a call that never arrived does not count", 0, usage.callsToday(GROQ, today))
    }

    @Test
    fun `a broken answer - this note from the rules, counted once`() = runTest {
        val gateway = Gateway(mapOf(GROQ to { "Sorry, I can't do JSON today." }))
        val usage = MemoryAiUsage()
        val result = structurer(gateway, usage).run(on(GROQ))
        assertFalse(result.notes.single().byAi)
        assertEquals(Problem.ANSWER, result.problems.single().problem)
        assertEquals(1, usage.callsToday(GROQ, today))
    }

    @Test
    fun `the daily limit from the settings stops a provider before it is asked`() = runTest {
        val usage = MemoryAiUsage()
        repeat(2) { usage.addCall(GROQ, today) }
        val gateway = Gateway(mapOf(GROQ to { cards }))
        val result = structurer(gateway, usage).run(on(GROQ, limit = 2))
        assertTrue(gateway.asked.isEmpty())
        assertEquals(Problem.CAP, result.problems.single().problem)
    }

    @Test
    fun `no key or an unreadable key is reported and nobody is asked`() = runTest {
        val gateway = Gateway(mapOf(GROQ to { cards }))
        val none = structurer(gateway, secrets = Secrets(emptyMap())).run(on(GROQ))
        val unreadable = structurer(gateway, secrets = Secrets(mapOf(GROQ to "k"), unreadable = setOf(GROQ))).run(on(GROQ))
        assertFalse(none.notes.single().byAi)
        assertEquals(listOf(Problem.NO_KEY), none.problems.map { it.problem })
        assertEquals(listOf(Problem.KEY_UNREADABLE), unreadable.problems.map { it.problem })
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
        val gateway = Gateway(mapOf(GROQ to { cards }))
        val s = structurer(gateway)
        s.run(on(GROQ))
        val before = currentTime
        s.run(on(GROQ))
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
        val result = structurer(Gateway(emptyMap())).structure(
            "zadzwonić do mamy", AppSettings(), null, ListTitles(), today, 0L,
        )
        assertEquals(Priority.LOW, result.notes.single().priority)
    }

    @Test
    fun `the result says which provider wrote the cards - none for the rules`() = runTest {
        val gateway = Gateway(mapOf(GROQ to { cards }))
        assertEquals(GROQ, structurer(gateway).run(on(GROQ)).by)
        assertEquals(null, structurer(Gateway(emptyMap())).run(AppSettings()).by)
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
    fun `in free mode trying again asks the free provider, and a failure gives the reason, never the rules`() = runTest {
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
    fun `paid Claude never writes the cards on its own - the rules do, nobody is asked`() = runTest {
        val gateway = Gateway(mapOf(GROQ to { cards }, ANTHROPIC to { cards }))
        val result = structurer(gateway).run(claude.copy(aiProviders = setOf(GROQ, ANTHROPIC)))
        assertFalse(result.notes.single().byAi)
        assertTrue(result.problems.isEmpty())
        assertTrue(gateway.asked.isEmpty())
    }

    @Test
    fun `free mode asks only the free providers - paid and hidden ones are skipped`() = runTest {
        val gateway = Gateway(mapOf(GROQ to fails(Problem.KEY), ANTHROPIC to { cards }, GEMINI to { cards }))
        val result = structurer(gateway).run(on(GROQ, ANTHROPIC, GEMINI))
        assertEquals(listOf(GROQ), gateway.asked)
        assertFalse(result.notes.single().byAi)
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
