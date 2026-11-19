package io.github.bakeron.tapedo.core.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsdRateTest {

    private val answer = """{"table":"A","currency":"dolar amerykański","code":"USD","rates":[{"no":"221/A/NBP/2026","effectiveDate":"2026-11-13","mid":3.6512}]}"""

    @Test
    fun `a sane rate is read and a strange one is not`() {
        assertEquals(UsdRate(3.6512, "2026-11-13"), readNbp(answer))
        assertNull(readNbp("""{"rates":[]}"""))
        assertNull(readNbp("""{"rates":[{"effectiveDate":"2026-11-13","mid":365.12}]}"""))
        assertNull(readNbp("""{"rates":[{"effectiveDate":"<b>x</b>","mid":3.6}]}"""))
        assertNull(readNbp("""{"rates":[{"effectiveDate":"2026-11-13","mid":"3.6"}]}"""))
        assertNull(readNbp("not json"))
    }

    @Test
    fun `NBP is asked once a day, and nothing without a connection`() = runTest {
        var calls = 0
        val ok = NbpRates(HttpClient(MockEngine { calls++; respond(answer) }))
        assertEquals(3.6512, ok.usdRate(100)!!.pln, 0.0)
        ok.usdRate(100)
        assertEquals(1, calls)
        ok.usdRate(101)
        assertEquals(2, calls)

        val down = NbpRates(HttpClient(MockEngine { respond("", HttpStatusCode.ServiceUnavailable) }))
        assertNull(down.usdRate(100))
    }
}
