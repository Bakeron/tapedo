package io.github.bakeron.tapedo.core.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Złoty for one dollar and the day of the NBP table it comes from (YYYY-MM-DD). */
data class UsdRate(val pln: Double, val day: String)

/** The dollar's rate for showing Claude's spending in złoty too. Null without a connection - the rough rate is shown then. */
fun interface UsdRates {
    suspend fun usdRate(today: Long): UsdRate?
}

/**
 * The dollar's mid rate from the National Bank of Poland (api.nbp.pl - public, free, no key), as in the
 * job-offers board. Nothing about the person is sent. Asked once a day at most, and only while Claude is chosen.
 */
class NbpRates(private val http: HttpClient) : UsdRates {
    private val lock = Mutex()
    private var cached: Pair<Long, UsdRate>? = null

    override suspend fun usdRate(today: Long): UsdRate? = lock.withLock {
        cached?.takeIf { it.first == today }?.let { return it.second }
        val rate = try {
            val response = http.get(URL)
            if (!response.status.isSuccess()) return null
            readNbp(response.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        cached = today to rate
        rate
    }

    private companion object {
        const val URL = "https://api.nbp.pl/api/exchangerates/rates/a/usd/?format=json"
    }
}

/** NBP's answer, or null when it is not a sane rate (the złoty has been between 1 and 10 for a dollar for decades). */
internal fun readNbp(body: String): UsdRate? {
    val root = try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (e: Exception) {
        null
    } ?: return null
    val row = (root["rates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
    val day = row.str("effectiveDate") ?: return null
    val mid = (row["mid"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
    val dayOk = day.length == 10 && day.all { it.isDigit() || it == '-' }
    return if (dayOk && mid.isFinite() && mid in 1.0..10.0) UsdRate(mid, day) else null
}
