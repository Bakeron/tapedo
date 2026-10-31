package io.github.bakeron.tapedo.core.time

/**
 * Calendar date <-> epoch day (days since 1970-01-01) without a date library,
 * so the core module stays platform-free. Algorithm: Howard Hinnant, "chrono-compatible
 * low-level date algorithms" (proleptic Gregorian calendar).
 */
object EpochDays {

    fun fromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + if (month > 2) -3 else 9).toLong()
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /** Returns (year, month 1-12, day 1-31). */
    fun toCivil(epochDay: Long): Triple<Int, Int, Int> {
        val z = epochDay + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
        return Triple(year, month, day)
    }

    /** 0 = Monday ... 6 = Sunday. 1970-01-01 was a Thursday. */
    fun weekday(epochDay: Long): Int = (epochDay + 3).mod(7L).toInt()

    fun toIso(epochDay: Long): String {
        val (y, m, d) = toCivil(epochDay)
        return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }

    /** Parses "YYYY-MM-DD"; null for anything else, including impossible dates like 2026-02-30. */
    fun parseIso(text: String?): Long? {
        val match = ISO.matchEntire(text?.trim() ?: return null) ?: return null
        val (y, m, d) = match.destructured.toList().map { it.toInt() }
        if (m !in 1..12 || d !in 1..31) return null
        val day = fromCivil(y, m, d)
        return day.takeIf { toCivil(it) == Triple(y, m, d) }
    }

    private val ISO = Regex("(\\d{4})-(\\d{2})-(\\d{2})")
}
