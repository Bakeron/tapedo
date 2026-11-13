package io.github.bakeron.tapedo.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.bakeron.tapedo.core.ai.AiUsage
import io.github.bakeron.tapedo.core.settings.AiProvider
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

private val Context.aiUsageDataStore by preferencesDataStore(
    name = "ai_usage",
    // Lost counts only mean a fresh day - never a reason to fail.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * AI calls per provider and day in a small DataStore file (no database migration needed): keys
 * `calls:<provider>:<epoch day>` for the last 30 days, `limit:<provider>` = the day the provider said "limit",
 * and `claude:<epoch day>` = Claude's cost that day in millionths of a dollar, kept for about a year
 * (the spending counter adds up the days since the last top-up).
 */
class DataStoreAiUsage(private val store: DataStore<Preferences>) : AiUsage {

    constructor(context: Context) : this(context.applicationContext.aiUsageDataStore)

    private suspend fun prefs(): Preferences =
        store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.first()

    private fun callsKey(provider: AiProvider, day: Long) = intPreferencesKey("calls:${provider.id}:$day")
    private fun limitKey(provider: AiProvider) = longPreferencesKey("limit:${provider.id}")

    override suspend fun callsToday(provider: AiProvider, today: Long) = prefs()[callsKey(provider, today)] ?: 0

    override suspend fun callsLast30Days(provider: AiProvider, today: Long): Int {
        val p = prefs()
        return (today - 29..today).sumOf { p[callsKey(provider, it)] ?: 0 }
    }

    override suspend fun addCall(provider: AiProvider, today: Long) {
        store.edit { p ->
            val key = callsKey(provider, today)
            p[key] = (p[key] ?: 0) + 1
            dropOld(p, today)
        }
    }

    override suspend fun limitHit(provider: AiProvider, today: Long) = prefs()[limitKey(provider)] == today

    override suspend fun markLimit(provider: AiProvider, today: Long) {
        store.edit { it[limitKey(provider)] = today }
    }

    override suspend fun clearLimit(provider: AiProvider, today: Long) {
        store.edit { it.remove(limitKey(provider)) }
    }

    private fun claudeKey(day: Long) = longPreferencesKey("claude:$day")

    override suspend fun addClaudeCost(day: Long, microUsd: Long) {
        store.edit { p ->
            val key = claudeKey(day)
            p[key] = (p[key] ?: 0L) + microUsd
            dropOld(p, day)
        }
    }

    override suspend fun claudeCostMicros(fromDay: Long, toDay: Long): Long =
        prefs().asMap().entries.sumOf { (key, value) ->
            val day = key.name.takeIf { it.startsWith("claude:") }?.substringAfter(':')?.toLongOrNull()
            if (day != null && day in fromDay..toDay) (value as? Long ?: 0L) else 0L
        }

    /** Calls are shown for 30 days, Claude's cost for about a year - older days go, so the file stays small. */
    private fun dropOld(p: MutablePreferences, today: Long) {
        val old = p.asMap().keys.filter { key ->
            val name = key.name
            val day = name.substringAfterLast(':').toLongOrNull()
            when {
                day == null -> false
                name.startsWith("calls:") -> day < today - 29
                name.startsWith("claude:") -> day < today - CLAUDE_DAYS_KEPT
                else -> false
            }
        }
        old.forEach { p.remove(it) }
    }

    private companion object {
        const val CLAUDE_DAYS_KEPT = 400
    }
}
