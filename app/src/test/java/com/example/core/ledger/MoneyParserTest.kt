package com.example.core.ledger

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MoneyParserTest {

    @Test
    fun testParseAmount_numericInput() {
        val json = JSONObject().apply {
            put("price", 12500)
            put("strPrice", "15,000")
        }
        val numVal = MoneyParser.parseAmount(json, keys = listOf("price"))
        assertEquals(12500.0, numVal!!, 0.001)

        val strVal = MoneyParser.parseAmount(json, keys = listOf("strPrice"))
        assertEquals(15000.0, strVal!!, 0.001)
    }

    @Test
    fun testParseAmount_nullAndMissingKeys() {
        val json = JSONObject().apply {
            put("price", JSONObject.NULL)
            put("other", "abc")
        }
        val nullResult = MoneyParser.parseAmount(json, keys = listOf("price", "missing"))
        assertNull(nullResult)

        val nullJsonResult = MoneyParser.parseAmount(null, keys = listOf("price"))
        assertNull(nullJsonResult)
    }

    @Test
    fun testParseUtowerAmount() {
        assertEquals(0.0, MoneyParser.parseUtowerAmount(null), 0.001)
        assertEquals(0.0, MoneyParser.parseUtowerAmount(Double.NaN), 0.001)
        assertEquals(15000.0, MoneyParser.parseUtowerAmount(15.0), 0.001)
        assertEquals(40000.0, MoneyParser.parseUtowerAmount(40.0), 0.001)
    }

    @Test
    fun testParseRawIqd() {
        assertEquals(0.0, MoneyParser.parseRawIqd(null), 0.001)
        assertEquals(0.0, MoneyParser.parseRawIqd(Double.NaN), 0.001)
        assertEquals(15000.0, MoneyParser.parseRawIqd(15000.0), 0.001)
    }

    @Test
    fun testParseUiThousandsAmount() {
        assertNull(MoneyParser.parseUiThousandsAmount(null))
        assertNull(MoneyParser.parseUiThousandsAmount(""))
        assertNull(MoneyParser.parseUiThousandsAmount("   "))

        assertEquals(50000L, MoneyParser.parseUiThousandsAmount("50"))
        assertEquals(50000L, MoneyParser.parseUiThousandsAmount("50k"))
        assertEquals(50000L, MoneyParser.parseUiThousandsAmount("50,000"))
        assertEquals(-50000L, MoneyParser.parseUiThousandsAmount("-50k"))
    }

    @Test
    fun testParseSubscriptionPriceIqd() {
        assertNull(MoneyParser.parseSubscriptionPriceIqd(null))
        assertNull(MoneyParser.parseSubscriptionPriceIqd(""))
        assertNull(MoneyParser.parseSubscriptionPriceIqd("   "))
        assertNull(MoneyParser.parseSubscriptionPriceIqd("-35k"))

        // Decimals in thousands scale by 1000
        assertEquals(22500L, MoneyParser.parseSubscriptionPriceIqd("22.5"))
        assertEquals(35000L, MoneyParser.parseSubscriptionPriceIqd("35"))
        assertEquals(35000L, MoneyParser.parseSubscriptionPriceIqd("35k"))
        assertEquals(35000L, MoneyParser.parseSubscriptionPriceIqd("35K IQD"))
        assertEquals(40000L, MoneyParser.parseSubscriptionPriceIqd("40 د.ع"))

        // Full IQD values (>= 1000) remain exact
        assertEquals(22500L, MoneyParser.parseSubscriptionPriceIqd("22500"))
        assertEquals(35000L, MoneyParser.parseSubscriptionPriceIqd("35,000"))
    }

    @Test
    fun testParseRawIqdString() {
        assertNull(MoneyParser.parseRawIqdString(null))
        assertNull(MoneyParser.parseRawIqdString(""))
        assertNull(MoneyParser.parseRawIqdString("   "))

        assertEquals(50000.0, MoneyParser.parseRawIqdString("50000")!!, 0.001)
        assertEquals(50000.0, MoneyParser.parseRawIqdString("50,000 IQD")!!, 0.001)
        assertEquals(50000.0, MoneyParser.parseRawIqdString("50000 د.ع")!!, 0.001)
    }

    @Test
    fun testFormatIqdToUiString() {
        assertEquals("", MoneyParser.formatIqdToUiString(0.0))
        assertEquals("", MoneyParser.formatIqdToUiString(Double.NaN))
        assertEquals("15000", MoneyParser.formatIqdToUiString(15000.0))
        assertEquals("500000", MoneyParser.formatIqdToUiString(500000.0))
        assertEquals("123.45", MoneyParser.formatIqdToUiString(123.45))
    }

    @Test
    fun testFormatIqdForDisplay() {
        assertEquals("0", MoneyParser.formatIqdForDisplay(Double.NaN))
        assertEquals("15,000", MoneyParser.formatIqdForDisplay(15000.0))
        assertEquals("1,000,000", MoneyParser.formatIqdForDisplay(1000000.0))
        assertEquals("1,234.50", MoneyParser.formatIqdForDisplay(1234.50))
    }
}
