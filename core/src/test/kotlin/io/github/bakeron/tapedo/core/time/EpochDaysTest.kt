package io.github.bakeron.tapedo.core.time

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpochDaysTest {

    @Test
    fun `matches java time across a wide range, leap years included`() {
        // java.time is only the reference here; production code does not use it.
        var date = LocalDate.of(1899, 12, 25)
        val end = LocalDate.of(2101, 3, 5)
        while (date <= end) {
            val day = date.toEpochDay()
            assertEquals(day, EpochDays.fromCivil(date.year, date.monthValue, date.dayOfMonth))
            assertEquals(Triple(date.year, date.monthValue, date.dayOfMonth), EpochDays.toCivil(day))
            assertEquals(date.dayOfWeek.value - 1, EpochDays.weekday(day))
            date = date.plusDays(13)
        }
    }

    @Test
    fun `iso round trip and validation`() {
        val day = EpochDays.fromCivil(2028, 2, 29)
        assertEquals("2028-02-29", EpochDays.toIso(day))
        assertEquals(day, EpochDays.parseIso("2028-02-29"))
        assertNull(EpochDays.parseIso("2027-02-29"))
        assertNull(EpochDays.parseIso("2026-13-01"))
        assertNull(EpochDays.parseIso("jutro"))
        assertNull(EpochDays.parseIso(null))
    }
}
