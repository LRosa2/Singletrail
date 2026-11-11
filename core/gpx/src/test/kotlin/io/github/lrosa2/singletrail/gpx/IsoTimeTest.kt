package io.github.lrosa2.singletrail.gpx

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlin.random.Random

class IsoTimeTest {
    /** What the reader did before (slow on Android): IsoTime must agree exactly. */
    private fun reference(s: String): Long? = try {
        OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        try {
            LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun checkParse(s: String) = assertEquals(reference(s), IsoTime.parseEpochMilli(s), "parsing \"$s\"")

    private fun checkFormat(ms: Long) {
        val out = StringBuilder()
        IsoTime.appendInstant(out, ms)
        assertEquals(Instant.ofEpochMilli(ms).toString(), out.toString(), "formatting $ms")
    }

    @Test
    fun `common and odd timestamps parse like java time`() {
        listOf(
            "2026-06-14T08:00:00Z", "2026-06-14T08:00:00.250Z", "2026-06-14T08:00:00.1Z", "2026-06-14T08:00:00.123456789Z",
            "2026-06-14T08:00:00.1234567891Z", "2026-06-14T08:00:00+02:00", "2026-06-14T08:00:00.5-05:30", "2026-06-14T08:00:00",
            "2026-06-14T08:00:00.75", "2024-02-29T23:59:59Z", "2023-02-29T12:00:00Z", "2026-13-01T00:00:00Z", "2026-04-31T00:00:00Z",
            "2026-06-14T24:00:00Z", "2026-06-14T08:60:00Z", "2026-06-14T08:00:60Z", "2026-06-14t08:00:00z", "2026-06-14 08:00:00Z",
            "2026-06-14T08:00Z", "2026-06-14T08:00:00+0200", "2026-06-14T08:00:00+02", "2026-06-14T08:00:00+02:00:30",
            "2026-06-14T08:00:00+18:00", "2026-06-14T08:00:00+19:00", "+12026-06-14T08:00:00Z", "0000-01-01T00:00:00Z",
            "1969-12-31T23:59:59.999Z", "1970-01-01T00:00:00Z", "9999-12-31T23:59:59.999Z", "2026-06-14T08:00:00.Z",
            "2026-06-14T08:00:00ZZ", "", "garbage", "2026-6-14T08:00:00Z", "2026-06-14T8:00:00Z", "2026-06-14T08:00:00.250+00:00",
            "2026-06-14T08:00:00-00:00", "2026-06-14T08:00:00.000Z", "1900-03-01T00:00:00Z", "2000-02-29T00:00:00Z", "2100-02-29T00:00:00Z",
        ).forEach(::checkParse)
    }

    @Test
    fun `random timestamps parse like java time`() {
        val random = Random(11)
        repeat(100_000) {
            val ms = random.nextLong(-2_208_988_800_000L, 4_102_444_800_000L) // 1900 to 2100
            val base = Instant.ofEpochMilli(ms).toString() // ...Z, with or without .fff
            checkParse(base)
            val offset = listOf("+01:00", "-07:00", "+05:45", "+00:00", "+14:00", "-12:00").random(random)
            checkParse(base.removeSuffix("Z") + offset)
            checkParse(base.removeSuffix("Z"))
            val nanos = random.nextInt(0, 1_000_000_000).toString().padStart(9, '0').take(random.nextInt(1, 10))
            checkParse(base.substringBefore('.').removeSuffix("Z") + "." + nanos + "Z")
        }
    }

    @Test
    fun `instants format like java time`() {
        listOf(0L, 1L, 999L, 1000L, -1L, -1000L, -999L, 1_781_424_000_250L, 253_402_300_799_999L, 253_402_300_800_000L,
            -62_167_219_200_000L, -62_167_219_200_001L, Long.MAX_VALUE / 1000).forEach(::checkFormat)
        val random = Random(12)
        repeat(100_000) { checkFormat(random.nextLong(-62_167_219_200_000L, 253_402_300_799_999L)) }
    }
}
