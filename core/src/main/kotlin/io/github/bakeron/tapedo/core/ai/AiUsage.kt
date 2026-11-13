package io.github.bakeron.tapedo.core.ai

import io.github.bakeron.tapedo.core.settings.AiProvider

/**
 * Calls per provider and day, plus today's mark "the provider itself said: limit". Days are epoch days
 * (local calendar), so "today" turns over at the user's midnight. Only counts - no note text.
 */
interface AiUsage {
    suspend fun callsToday(provider: AiProvider, today: Long): Int

    /** Today and the 29 days before it. */
    suspend fun callsLast30Days(provider: AiProvider, today: Long): Int

    suspend fun addCall(provider: AiProvider, today: Long)

    suspend fun limitHit(provider: AiProvider, today: Long): Boolean

    suspend fun markLimit(provider: AiProvider, today: Long)

    /** A new model or a working "Check connection": the provider is asked again today. */
    suspend fun clearLimit(provider: AiProvider, today: Long)

    /** Adds the cost of one answer from Claude to [day], in millionths of a dollar ([recordClaude]). */
    suspend fun addClaudeCost(day: Long, microUsd: Long)

    /** Claude's cost from [fromDay] to [toDay], both included, in millionths of a dollar. */
    suspend fun claudeCostMicros(fromDay: Long, toDay: Long): Long
}

/** Why a provider is not asked today. */
enum class UsedUp {
    /** The daily limit from the settings is used. */
    APP,

    /** The provider answered that its own limit is used. */
    PROVIDER,
}

suspend fun AiUsage.usedUp(provider: AiProvider, today: Long, dailyLimit: Int): UsedUp? = when {
    callsToday(provider, today) >= dailyLimit -> UsedUp.APP
    limitHit(provider, today) -> UsedUp.PROVIDER
    else -> null
}

/** Counts in memory - for tests, and the shape the stored version follows. */
class MemoryAiUsage : AiUsage {
    private val calls = mutableMapOf<Pair<AiProvider, Long>, Int>()
    private val limits = mutableMapOf<AiProvider, Long>()
    private val claude = mutableMapOf<Long, Long>()

    override suspend fun callsToday(provider: AiProvider, today: Long) = calls[provider to today] ?: 0

    override suspend fun callsLast30Days(provider: AiProvider, today: Long) =
        (today - 29..today).sumOf { calls[provider to it] ?: 0 }

    override suspend fun addCall(provider: AiProvider, today: Long) {
        calls[provider to today] = callsToday(provider, today) + 1
    }

    override suspend fun limitHit(provider: AiProvider, today: Long) = limits[provider] == today

    override suspend fun markLimit(provider: AiProvider, today: Long) {
        limits[provider] = today
    }

    override suspend fun clearLimit(provider: AiProvider, today: Long) {
        limits.remove(provider)
    }

    override suspend fun addClaudeCost(day: Long, microUsd: Long) {
        claude[day] = (claude[day] ?: 0) + microUsd
    }

    override suspend fun claudeCostMicros(fromDay: Long, toDay: Long) =
        claude.filterKeys { it in fromDay..toDay }.values.sum()
}
