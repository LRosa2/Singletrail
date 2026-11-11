package io.github.lrosa2.singletrail.gpx

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * GPX timestamps without java.time in the common case. Parsing and formatting a time took
 * ~100 µs each on Android, which made a 50,000-point recording take many seconds to import.
 *
 * The fast path reads and writes exactly `yyyy-MM-ddTHH:mm:ss[.fffffffff](Z|±HH:mm|)` with
 * fields in range; anything else goes to java.time as before, so the results never differ.
 */
internal object IsoTime {
    /** Milliseconds since 1970 (a missing zone means UTC), or null if [s] isn't a valid ISO date-time. */
    fun parseEpochMilli(s: String): Long? = fast(s) ?: javaTime(s)

    private fun javaTime(s: String): Long? = try {
        OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        try {
            LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli() // no zone given: assume UTC
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** The common shape, or null to let java.time decide. */
    private fun fast(s: String): Long? {
        if (s.length < 19 || s[4] != '-' || s[7] != '-' || s[10] != 'T' || s[13] != ':' || s[16] != ':') return null
        val year = digits(s, 0, 4)
        val month = digits(s, 5, 2)
        val day = digits(s, 8, 2)
        val hour = digits(s, 11, 2)
        val minute = digits(s, 14, 2)
        val second = digits(s, 17, 2)
        if (year < 0 || month !in 1..12 || day < 1 || day > daysInMonth(year, month)) return null
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        var i = 19
        var nanos = 0
        if (i < s.length && s[i] == '.') {
            i++
            val start = i
            while (i < s.length && s[i] in '0'..'9') i++
            val count = i - start
            if (count !in 1..9) return null
            nanos = digits(s, start, count)
            repeat(9 - count) { nanos *= 10 }
        }
        val offsetSeconds = when {
            i == s.length -> 0
            s[i] == 'Z' && i + 1 == s.length -> 0
            (s[i] == '+' || s[i] == '-') && i + 6 == s.length && s[i + 3] == ':' -> {
                val oh = digits(s, i + 1, 2)
                val om = digits(s, i + 4, 2)
                if (oh !in 0..18 || om !in 0..59 || (oh == 18 && om > 0)) return null
                (oh * 3600 + om * 60) * if (s[i] == '-') -1 else 1
            }
            else -> return null
        }
        val seconds = daysFromCivil(year, month, day) * 86_400L + hour * 3_600L + minute * 60L + second - offsetSeconds
        return seconds * 1000 + nanos / 1_000_000
    }

    /** `Instant.ofEpochMilli(ms).toString()`, appended without java.time for years 0000 to 9999. */
    fun appendInstant(out: StringBuilder, ms: Long) {
        if (ms < MIN_FAST_MS || ms > MAX_FAST_MS) {
            out.append(Instant.ofEpochMilli(ms).toString())
            return
        }
        val seconds = Math.floorDiv(ms, 1000L)
        val millis = Math.floorMod(ms, 1000L).toInt()
        val days = Math.floorDiv(seconds, 86_400L)
        val secondOfDay = Math.floorMod(seconds, 86_400L).toInt()
        val z = days + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val dayOfEra = (z - era * 146_097).toInt()
        val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val mp = (5 * dayOfYear + 2) / 153
        val day = dayOfYear - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = (yearOfEra + era * 400).toInt() + if (month <= 2) 1 else 0
        pad(out, year, 4).append('-')
        pad(out, month, 2).append('-')
        pad(out, day, 2).append('T')
        pad(out, secondOfDay / 3600, 2).append(':')
        pad(out, secondOfDay / 60 % 60, 2).append(':')
        pad(out, secondOfDay % 60, 2)
        if (millis != 0) pad(out.append('.'), millis, 3)
        out.append('Z')
    }

    private const val MIN_FAST_MS = -62_167_219_200_000L // 0000-01-01T00:00:00Z
    private const val MAX_FAST_MS = 253_402_300_799_999L // 9999-12-31T23:59:59.999Z

    /** The number in [count] ASCII digits at [start], or -1 if any isn't a digit. */
    private fun digits(s: String, start: Int, count: Int): Int {
        var value = 0
        for (k in start until start + count) {
            val c = s[k]
            if (c !in '0'..'9') return -1
            value = value * 10 + (c - '0')
        }
        return value
    }

    private fun daysInMonth(year: Int, month: Int) = when (month) {
        2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Days since 1970-01-01 in the proleptic Gregorian calendar (H. Hinnant's algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = Math.floorDiv(y, 400L)
        val yearOfEra = y - era * 400
        val mp = (month + 9) % 12
        val dayOfYear = (153 * mp + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }

    private fun pad(out: StringBuilder, value: Int, width: Int): StringBuilder {
        var limit = 10
        repeat(width - 1) {
            if (value < limit) out.append('0')
            limit *= 10
        }
        return out.append(value)
    }
}
