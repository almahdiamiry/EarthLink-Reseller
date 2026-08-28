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
}
