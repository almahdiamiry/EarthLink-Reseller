package com.example.core.ledger

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MoneyParserTest {

    @Test
    fun parseAmount_numericValues() {
        val json = JSONObject()
        json.put("price", 35000)
        json.put("strPrice", "35000")

        val resultNum = MoneyParser.parseAmount(json, keys = listOf("price"))
        assertNotNull(resultNum)
        assertEquals(35000.0, resultNum!!, 0.001)

        val resultStr = MoneyParser.parseAmount(json, keys = listOf("strPrice"))
        assertNotNull(resultStr)
        assertEquals(35000.0, resultStr!!, 0.001)
    }

    @Test
    fun parseAmount_nullAndMissingKeys() {
        val json = JSONObject()
        json.put("price", JSONObject.NULL)
        val result = MoneyParser.parseAmount(json, keys = listOf("price", "nonExistent"))
        assertNull(result)
    }

    @Test
    fun parseUtowerAmount_scalesByThousand() {
        assertEquals(15000.0, MoneyParser.parseUtowerAmount(15.0), 0.001)
        assertEquals(0.0, MoneyParser.parseUtowerAmount(null), 0.001)
    }

    @Test
    fun parseRawIqd_returnsExactValue() {
        assertEquals(25000.0, MoneyParser.parseRawIqd(25000.0), 0.001)
        assertEquals(0.0, MoneyParser.parseRawIqd(null), 0.001)
    }

    @Test
    fun parseUiThousandsAmount_parsing() {
        assertEquals(50000L, MoneyParser.parseUiThousandsAmount("50"))
        assertEquals(50000L, MoneyParser.parseUiThousandsAmount("50k"))
        assertEquals(50000L, MoneyParser.parseUiThousandsAmount("50,000"))
        assertNull(MoneyParser.parseUiThousandsAmount(""))
    }
}
