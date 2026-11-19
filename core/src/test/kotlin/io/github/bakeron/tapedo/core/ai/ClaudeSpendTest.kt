package io.github.bakeron.tapedo.core.ai

import io.github.bakeron.tapedo.core.settings.AiMode
import io.github.bakeron.tapedo.core.settings.AiProvider
import io.github.bakeron.tapedo.core.settings.AppSettings
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeSpendTest {

    private fun near(a: Double, b: Double) = assertEquals(b, a, 1e-9)

    private fun usage(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `reads the token counts of an answer`() {
        val u = usage("""{"input_tokens":512,"output_tokens":830,"cache_read_input_tokens":1700,"cache_creation_input_tokens":0}""")
        assertEquals(Tokens(512, 830, 1700, 0), Tokens.fromUsage(u))
        // Missing fields (an older answer) or broken ones: 0, never a negative cost.
        assertEquals(Tokens(input = 10), Tokens.fromUsage(usage("""{"input_tokens":10,"output_tokens":-5}""")))
        assertNull(Tokens.fromUsage(usage("{}")))
        assertNull(Tokens.fromUsage(usage("""{"input_tokens":"a lot"}""")))
        assertNull(Tokens.fromUsage(null))
    }

    @Test
    fun `a model gets its own price`() {
        assertEquals(Price(2.0, 10.0, 0.10), ClaudePrices.of("claude-sonnet-5-5"))
        // With a date at the end, other letter case.
        assertEquals(Price(2.0, 10.0, 0.10), ClaudePrices.of("Claude-Sonnet-5-5-20261001"))
        // The older Sonnet 5 is not mistaken for Sonnet 5.5 and the other way round.
        assertEquals(Price(2.0, 10.0, 0.20), ClaudePrices.of("claude-sonnet-5"))
        assertEquals(Price(4.0, 20.0, 0.20), ClaudePrices.of("claude-opus-5-5"))
        // Unknown: Opus 5.5's price; a name that only starts the same is not a match.
        assertEquals(ClaudePrices.UNKNOWN, ClaudePrices.of("claude-sonnet-55"))
        assertEquals(ClaudePrices.UNKNOWN, ClaudePrices.of(""))
        near(ClaudePrices.of("claude-sonnet-5-5").cacheWrite, 2.5)
    }

    @Test
    fun `the cost of a call comes from its tokens`() {
        // About one minute of speech: 1000 tokens of prompt and transcript, 400 of cards.
        val note = Tokens(input = 1000, output = 400)
        near(ClaudePrices.costUsd("claude-sonnet-5-5", note), 0.006)
        near(ClaudePrices.costUsd("claude-opus-5-5", note), 0.012)
        near(ClaudePrices.costUsd("claude-sonnet-5-5", Tokens(cacheRead = 1_000_000, cacheWrite = 1_000_000)), 0.10 + 2.5)
        near(ClaudePrices.costUsd("claude-sonnet-5-5", Tokens()), 0.0)
    }

    @Test
    fun `adds up today, this month and since the top-up`() = runTest {
        val u = MemoryAiUsage()
        val nov11 = LocalDate.of(2026, 11, 11).toEpochDay()
        // 100 000 output tokens: $1 on Sonnet, $2 on Opus.
        u.recordClaude(LocalDate.of(2026, 10, 30).toEpochDay(), "claude-sonnet-5-5", Tokens(output = 100_000))
        u.recordClaude(LocalDate.of(2026, 11, 2).toEpochDay(), "claude-sonnet-5-5", Tokens(output = 100_000))
        u.recordClaude(nov11, "claude-sonnet-5-5", Tokens(output = 50_000))
        u.recordClaude(nov11, "claude-opus-5-5", Tokens(output = 100_000))
        val s = u.claudeSpent(nov11, LocalDate.of(2026, 10, 30).toEpochDay())
        near(s.today, 2.5)
        near(s.month, 3.5)
        near(s.sinceTopUp!!, 4.5)
        near(u.claudeSpent(nov11, LocalDate.of(2026, 11, 3).toEpochDay()).sinceTopUp!!, 2.5)
        assertNull(u.claudeSpent(nov11, null).sinceTopUp)
        // A new month starts from zero.
        val dec1 = LocalDate.of(2026, 12, 1).toEpochDay()
        assertEquals(Spent(0.0, 0.0, 0.0), u.claudeSpent(dec1, dec1))
    }

    @Test
    fun `warns when less than a tenth of the top-up is left`() {
        assertNull(lowFunds(5.0, 4.0))
        assertNull(lowFunds(5.0, 4.5))
        val l = lowFunds(5.0, 4.6)!!
        near(l.left, 0.4)
        // Spent more than the top-up (the counter is an estimate): 0 left, never below.
        assertEquals(LowFunds(0.0, 5.0), lowFunds(5.0, 7.0))
        assertNull(lowFunds(null, 100.0))
        assertNull(lowFunds(5.0, null))
        assertNull(lowFunds(0.0, 1.0))
    }

    @Test
    fun `a typed top-up`() {
        assertEquals(TopUpInput.Cents(500), TopUpInput.parse("5"))
        assertEquals(TopUpInput.Cents(2050), TopUpInput.parse(" 20,50 "))
        assertEquals(TopUpInput.Cents(2050), TopUpInput.parse("$20.5"))
        assertEquals(TopUpInput.Cents(1000), TopUpInput.parse("10 USD"))
        assertEquals(TopUpInput.Cents(1_000_000), TopUpInput.parse("10000"))
        assertEquals(TopUpInput.Cleared, TopUpInput.parse("  "))
        for (wrong in listOf("0", "0,00", "10000,01", "5,555", "-5", "abc", "1e3", "5.")) {
            assertEquals(wrong, TopUpInput.Wrong, TopUpInput.parse(wrong))
        }
    }

    @Test
    fun `a sum without its day is not compared`() {
        assertNull(AppSettings(claudeTopUpCents = 500).claudeTopUpUsd)
        assertEquals(5.0, AppSettings(claudeTopUpCents = 500, claudeTopUpDay = 1).claudeTopUpUsd!!, 0.0)
    }

    private class Keys : SecretStore {
        override suspend fun apiKey(provider: AiProvider) = "key-${provider.id}"
        override suspend fun setApiKey(provider: AiProvider, key: String?) = Unit
    }

    private class ClaudeAnswers(val tokens: Tokens?) : AiGateway {
        override fun chat(provider: AiProvider, model: String, key: String) = AiChat { _, _, _ ->
            AiAnswer("""{"cards":[{"title":"Mleko","type":"shopping","items":[{"text":"Mleko"}]}]}""", "claude-sonnet-5-5-20261001", tokens)
        }
        override suspend fun freeModels(provider: AiProvider, key: String?) = emptyList<String>()
    }

    @Test
    fun `an answer from Claude is counted and a low top-up is said after the recording`() = runTest {
        val today = 20_400L
        val u = MemoryAiUsage()
        val settings = AppSettings(aiMode = AiMode.CLAUDE, claudeTopUpCents = 1, claudeTopUpDay = today)
        val s = NoteStructurer(Keys(), u, ClaudeAnswers(Tokens(input = 1000, output = 400)), testScheduler.timeSource)
        val result = s.refine("kup mleko", settings, null, today, 0L) as Refined.Cards
        assertTrue(result.notes.single().byAi)
        assertEquals(6_000L, u.claudeCostMicros(today, today))
        // $0.006 of a $0.01 top-up: 40% left - no warning yet.
        assertNull(result.lowFunds)
        val second = s.refine("kup mleko", settings, null, today, 0L) as Refined.Cards
        assertEquals(LowFunds(0.0, 0.01), second.lowFunds)
    }

    @Test
    fun `an answer without token counts costs nothing and warns of nothing`() = runTest {
        val u = MemoryAiUsage()
        val s = NoteStructurer(Keys(), u, ClaudeAnswers(null), testScheduler.timeSource)
        val result = s.refine("kup mleko", AppSettings(aiMode = AiMode.CLAUDE), null, 20_400L, 0L) as Refined.Cards
        assertTrue(result.notes.single().byAi)
        assertEquals(0L, u.claudeCostMicros(0, 30_000))
        assertNull(result.lowFunds)
    }
}
