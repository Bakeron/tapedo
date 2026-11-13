package io.github.bakeron.tapedo.core.ai

import java.time.LocalDate
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Claude's spending counter. A normal API key cannot read the account's balance, so Tapedo counts what it
 * spent itself: the tokens of every answer from Claude, priced from the list below, added up per day.
 * Free providers are not counted. Same rules as in the author's job-offers board (11.11.2026).
 */

/** Token counts from one answer (`usage` of the Anthropic Messages API). */
data class Tokens(
    /** Full-price input (the part of the prompt that was not in the cache). */
    val input: Long = 0,
    val output: Long = 0,
    /** Read back from the prompt cache. */
    val cacheRead: Long = 0,
    /** Written to the prompt cache (5 minutes). */
    val cacheWrite: Long = 0,
) {
    companion object {
        /** Null when the answer has no token counts at all. Negative or broken numbers count as 0. */
        fun fromUsage(usage: JsonObject?): Tokens? {
            if (usage == null) return null
            fun n(field: String) = (usage[field] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.coerceAtLeast(0)
            val fields = listOf("input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens").map(::n)
            if (fields.all { it == null }) return null
            val (input, output, cacheRead, cacheWrite) = fields.map { it ?: 0 }
            return Tokens(input, output, cacheRead, cacheWrite)
        }
    }
}

/** Dollars per million tokens. A 5-minute cache write costs 1.25 x input. */
data class Price(val input: Double, val output: Double, val cacheRead: Double) {
    val cacheWrite: Double get() = input * 1.25
}

object ClaudePrices {
    /**
     * Anthropic's pricing page, as of 11.2026. An answer may name a model with a date at the end,
     * so a model matches by the beginning of its name.
     */
    private val PRICES = listOf(
        "claude-sonnet-5-5" to Price(2.0, 10.0, 0.10),
        "claude-opus-5-5" to Price(4.0, 20.0, 0.20),
        "claude-haiku-5-5" to Price(0.10, 0.50, 0.01),
        "claude-fable-5-2" to Price(8.0, 40.0, 0.20),
        // Older models the safety fallback ("fallbacks": "default") may answer with.
        "claude-sonnet-5" to Price(2.0, 10.0, 0.20),
        "claude-opus-5" to Price(5.0, 25.0, 0.50),
        "claude-opus-4-8" to Price(5.0, 25.0, 0.50),
    )

    /** A model missing from the list is counted at Opus 5.5's price: rather too much than too little. */
    val UNKNOWN = Price(4.0, 20.0, 0.20)

    /** The longest matching name wins (`claude-sonnet-5-5` before `claude-sonnet-5`). */
    fun of(model: String): Price {
        val m = model.trim().lowercase()
        return PRICES
            .filter { (name, _) -> m == name || (m.startsWith(name) && m.getOrNull(name.length) in setOf('-', '@')) }
            .maxByOrNull { (name, _) -> name.length }
            ?.second ?: UNKNOWN
    }

    fun costUsd(model: String, t: Tokens): Double {
        val p = of(model)
        return (t.input * p.input + t.output * p.output + t.cacheRead * p.cacheRead + t.cacheWrite * p.cacheWrite) / 1_000_000.0
    }
}

/** Claude's spending in dollars: today, this calendar month and since the day of the last top-up (null = none given). */
data class Spent(val today: Double, val month: Double, val sinceTopUp: Double?)

/** What is left of the last top-up, in $ - only when it is getting low. */
data class LowFunds(val left: Double, val topUp: Double)

/** Below this share of the top-up left Tapedo warns (in Settings and after a recording). */
private const val LOW_SHARE = 0.10

/** Rough rate for showing dollars in złoty (PLN per $1, as of 11.2026) - always shown as "about". */
const val USD_PLN = 4.0

/** Less than 10% of the top-up left (spent more than it = 0 left, never below); null without a top-up or its day. */
fun lowFunds(topUpUsd: Double?, sinceTopUp: Double?): LowFunds? {
    val topUp = topUpUsd?.takeIf { it.isFinite() && it > 0 } ?: return null
    val left = (topUp - (sinceTopUp ?: return null)).coerceAtLeast(0.0)
    return if (left < topUp * LOW_SHARE) LowFunds(left, topUp) else null
}

/** One answer from Claude: its cost goes to today's sum. Stored in millionths of a dollar - whole numbers add up exactly. */
suspend fun AiUsage.recordClaude(today: Long, model: String, tokens: Tokens) {
    val micros = (ClaudePrices.costUsd(model, tokens) * 1_000_000).roundToLong()
    if (micros > 0) addClaudeCost(today, micros)
}

suspend fun AiUsage.claudeSpent(today: Long, topUpDay: Long?): Spent {
    val monthStart = LocalDate.ofEpochDay(today).withDayOfMonth(1).toEpochDay()
    fun usd(micros: Long) = micros / 1_000_000.0
    return Spent(
        today = usd(claudeCostMicros(today, today)),
        month = usd(claudeCostMicros(monthStart, today)),
        sinceTopUp = topUpDay?.let { usd(claudeCostMicros(it, today)) },
    )
}

/** A typed top-up: "5", "20,50", "$5", "5 USD". */
sealed interface TopUpInput {
    data object Cleared : TopUpInput

    /** Not a sum Tapedo accepts (0.01 to 10 000, at most two digits after the point). */
    data object Wrong : TopUpInput

    data class Cents(val cents: Int) : TopUpInput

    companion object {
        const val MAX_CENTS = 1_000_000

        fun parse(text: String): TopUpInput {
            val v = text.replace(Regex("(?i)\\$|usd"), "").replace(Regex("\\s"), "").replace(',', '.')
            if (v.isEmpty()) return Cleared
            if (!Regex("\\d{1,6}(\\.\\d{1,2})?").matches(v)) return Wrong
            val whole = v.substringBefore('.').toInt()
            val part = v.substringAfter('.', "").padEnd(2, '0').toInt()
            val cents = whole * 100 + part
            return if (cents in 1..MAX_CENTS) Cents(cents) else Wrong
        }
    }
}
