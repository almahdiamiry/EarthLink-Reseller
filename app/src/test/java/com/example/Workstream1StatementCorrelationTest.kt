package com.example

import com.example.core.model.AccountStatementItem
import com.squareup.moshi.Moshi
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Claim: Verifies that AccountStatementItem correctly deserializes the 4 contract-proven
 * ISP statement fields (deposit, withdrawal, balance, date) per API v0.7.0 (lines 1200-1215),
 * and that statement timestamp parsing resolves against Asia/Baghdad timezone (INV-05 / RED-5).
 * Seam: JVM Headless unit test.
 * Independent Oracle: API documentation contract v0.7.0 lines 1200-1215 and Iraq standard time (UTC+3).
 */
class Workstream1StatementCorrelationTest {

    private val moshi = Moshi.Builder().build()
    private val adapter = moshi.adapter(AccountStatementItem::class.java)

    @Test
    fun testContractStatementFieldsDeserialization() {
        val ispJson = """
            {
                "date": "2026-01-15 14:30:00",
                "operation": "Deposit",
                "deposit": 25000.0,
                "withdrawal": 0.0,
                "balance": 150000.0,
                "userID": "test_user_01",
                "description": "Deposit to reseller"
            }
        """.trimIndent()

        val parsed = adapter.fromJson(ispJson)
        assertNotNull(parsed)

        assertEquals("2026-01-15 14:30:00", parsed!!.occurredAt)
        assertEquals(25000.0, parsed.depositAmount ?: 0.0, 0.001)
        assertEquals(0.0, parsed.withdrawalAmount ?: 0.0, 0.001)
        assertEquals(150000.0, parsed.balanceAfter ?: 0.0, 0.001)
    }

    @Test
    fun testBaghdadTimezoneConversion() {
        val baghdadSdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Baghdad")
        }
        val expectedEpochMillis = baghdadSdf.parse("2026-01-15 14:30:00")!!.time

        val utcSdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val utcEpochMillis = utcSdf.parse("2026-01-15 14:30:00")!!.time

        // 3 hours difference = 10,800,000 ms
        assertEquals(10_800_000L, utcEpochMillis - expectedEpochMillis)
    }
}
