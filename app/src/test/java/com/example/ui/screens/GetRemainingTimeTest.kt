package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GetRemainingTimeTest {

    @Test
    fun testGetRemainingTimeWithActiveDaysLeftEnglish() {
        val result = getRemainingTime(
            expirationDateStr = null,
            activeDaysLeftStr = "5 days",
            lang = "en",
            accountStatus = "Active"
        )
        assertEquals("Remaining: 5 days", result)
    }

    @Test
    fun testGetRemainingTimeWithActiveDaysLeftArabic() {
        val result = getRemainingTime(
            expirationDateStr = null,
            activeDaysLeftStr = "5 days",
            lang = "ar",
            accountStatus = "Active"
        )
        assertEquals("متبقي 5 أيام", result)
    }

    @Test
    fun testGetRemainingTimeExpired() {
        val result = getRemainingTime(
            expirationDateStr = "2020-01-01T00:00:00.000Z",
            activeDaysLeftStr = null,
            lang = "en",
            accountStatus = "Expired"
        )
        assertEquals("Expired", result)
    }

    @Test
    fun testGetRemainingTimeArabicExpired() {
        val result = getRemainingTime(
            expirationDateStr = "2020-01-01T00:00:00.000Z",
            activeDaysLeftStr = null,
            lang = "ar",
            accountStatus = "Expired"
        )
        assertEquals("منتهي", result)
    }

    @Test
    fun testGetRemainingTimeFractionalSecondsSanitization() {
        val result = getRemainingTime(
            expirationDateStr = "2020-01-01T00:00:00.1234567Z",
            activeDaysLeftStr = "0 days",
            lang = "en",
            accountStatus = "Expired"
        )
        assertEquals("Expired", result)
    }
}
