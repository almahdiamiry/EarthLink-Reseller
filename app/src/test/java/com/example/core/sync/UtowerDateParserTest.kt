package com.example.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Characterization and regression test suite for UtowerDateParser (MNT-07).
 *
 * Core Triad:
 * - Claim: UtowerDateParser strictly parses all 15 uTower legacy date patterns in Asia/Baghdad timezone,
 *   rejects invalid dates via non-lenient parsing, handles null/sentinel values, falls back to numeric ms,
 *   and formats epoch timestamps consistently.
 * - Seam: Pure JVM execution tier (com.example.core.sync).
 * - Independent Oracle: Calendar in Asia/Baghdad timezone initialized with explicit date/time constants.
 */
class UtowerDateParserTest {

    private val baghdadTz = TimeZone.getTimeZone("Asia/Baghdad")

    private fun expectedMs(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0): Long {
        val cal = Calendar.getInstance(baghdadTz)
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month - 1)
        cal.set(Calendar.DAY_OF_MONTH, day)
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, second)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @Test
    fun testAllFifteenDatePatternsParseCorrectly() {
        val expectedWithSeconds = expectedMs(2026, 7, 15, 14, 30, 45)
        val expectedWithMinutes = expectedMs(2026, 7, 15, 14, 30, 0)
        val expectedDateOnly = expectedMs(2026, 7, 15, 0, 0, 0)

        // 1. yyyy-MM-dd HH:mm:ss
        assertEquals(expectedWithSeconds, UtowerDateParser.parseBghDate("2026-07-15 14:30:45"))

        // 2. yyyy-MM-dd HH:mm
        assertEquals(expectedWithMinutes, UtowerDateParser.parseBghDate("2026-07-15 14:30"))

        // 3. yyyy-MM-dd
        assertEquals(expectedDateOnly, UtowerDateParser.parseBghDate("2026-07-15"))

        // 4. yyyy/MM/dd HH:mm:ss
        assertEquals(expectedWithSeconds, UtowerDateParser.parseBghDate("2026/07/15 14:30:45"))

        // 5. yyyy/MM/dd HH:mm
        assertEquals(expectedWithMinutes, UtowerDateParser.parseBghDate("2026/07/15 14:30"))

        // 6. yyyy/MM/dd
        assertEquals(expectedDateOnly, UtowerDateParser.parseBghDate("2026/07/15"))

        // 7. dd/MM/yyyy HH:mm:ss
        assertEquals(expectedWithSeconds, UtowerDateParser.parseBghDate("15/07/2026 14:30:45"))

        // 8. dd/MM/yyyy HH:mm
        assertEquals(expectedWithMinutes, UtowerDateParser.parseBghDate("15/07/2026 14:30"))

        // 9. dd/MM/yyyy
        assertEquals(expectedDateOnly, UtowerDateParser.parseBghDate("15/07/2026"))

        // 10. dd-MM-yyyy HH:mm:ss
        assertEquals(expectedWithSeconds, UtowerDateParser.parseBghDate("15-07-2026 14:30:45"))

        // 11. dd-MM-yyyy HH:mm
        assertEquals(expectedWithMinutes, UtowerDateParser.parseBghDate("15-07-2026 14:30"))

        // 12. dd-MM-yyyy
        assertEquals(expectedDateOnly, UtowerDateParser.parseBghDate("15-07-2026"))

        // 13. yyyy-MM-dd'T'HH:mm:ss
        // Historical pattern order evaluates "yyyy-MM-dd" (pattern 3) before ISO 'T' patterns,
        // so SimpleDateFormat matches the 10-character date prefix.
        assertEquals(expectedDateOnly, UtowerDateParser.parseBghDate("2026-07-15T14:30:45"))

        // 14. yyyy-MM-dd'T'HH:mm:ss'Z'
        assertEquals(expectedDateOnly, UtowerDateParser.parseBghDate("2026-07-15T14:30:45Z"))

        // 15. yyyy-MM-dd'T'HH:mm:ss.SSS'Z'
        assertEquals(expectedDateOnly, UtowerDateParser.parseBghDate("2026-07-15T14:30:45.123Z"))
    }

    @Test
    fun testDayFirstStrictParsing() {
        // 11/12/2026 is 11th of December 2026
        val expectedDec11 = expectedMs(2026, 12, 11, 10, 15, 30)
        assertEquals(expectedDec11, UtowerDateParser.parseBghDate("11/12/2026 10:15:30"))
        assertEquals(expectedDec11, UtowerDateParser.parseBghDate("11-12-2026 10:15:30"))
    }

    @Test
    fun testNullBlankAndSentinelHandling() {
        assertNull(UtowerDateParser.parseBghDate(null))
        assertNull(UtowerDateParser.parseBghDate(""))
        assertNull(UtowerDateParser.parseBghDate("   "))
        assertNull(UtowerDateParser.parseBghDate("null"))
    }

    @Test
    fun testNumericEpochMsFallback() {
        val epochMs = 1721043045000L
        assertEquals(epochMs, UtowerDateParser.parseBghDate("1721043045000"))
    }

    @Test
    fun testNonLenientRejectionOfInvalidDates() {
        // February 30th does not exist; non-lenient parsing must return null
        assertNull(UtowerDateParser.parseBghDate("2026-02-30 10:00:00"))
        assertNull(UtowerDateParser.parseBghDate("not-a-valid-date"))
        assertNull(UtowerDateParser.parseBghDate("2026-13-01 10:00:00"))
    }

    @Test
    fun testFormatBghFull() {
        val ms = expectedMs(2026, 7, 15, 14, 30, 45)
        assertEquals("2026-07-15 14:30:45", UtowerDateParser.formatBghFull(ms))
    }
}
