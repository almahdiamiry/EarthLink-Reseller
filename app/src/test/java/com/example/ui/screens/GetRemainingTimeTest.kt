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

    @Test
    fun testGetRemainingTimeNullAndEmptyInputs() {
        val resultNullEn = getRemainingTime(
            expirationDateStr = null,
            activeDaysLeftStr = null,
            lang = "en",
            accountStatus = null
        )
        assertEquals("Expired", resultNullEn)

        val resultNullAr = getRemainingTime(
            expirationDateStr = "",
            activeDaysLeftStr = "",
            lang = "ar",
            accountStatus = ""
        )
        assertEquals("منتهي", resultNullAr)
    }

    @Test
    fun testGetRemainingTimeZeroDaysLeftActiveStatus() {
        val result = getRemainingTime(
            expirationDateStr = null,
            activeDaysLeftStr = "0 days",
            lang = "en",
            accountStatus = "Active"
        )
        assertEquals("Remaining: 30 minutes", result)
    }

    @Test
    fun testOfflineAccountStatusParsingIsoAndBgh() {
        val isoExpiredStr = "2020-01-01T00:00:00Z"
        val expireDateIso = if (isoExpiredStr.endsWith("Z") && isoExpiredStr.contains("T")) {
            com.example.ui.viewmodels.EarthlinkSearchViewModel.parseIsoDate(isoExpiredStr)
        } else {
            com.example.ui.viewmodels.EarthlinkSearchViewModel.parseBghDate(isoExpiredStr)
        }
        assertTrue("ISO expired date should be parsed as before now", expireDateIso != null && expireDateIso.before(java.util.Date()))

        val bghExpiredStr = "2020-01-01"
        val expireDateBgh = if (bghExpiredStr.endsWith("Z") && bghExpiredStr.contains("T")) {
            com.example.ui.viewmodels.EarthlinkSearchViewModel.parseIsoDate(bghExpiredStr)
        } else {
            com.example.ui.viewmodels.EarthlinkSearchViewModel.parseBghDate(bghExpiredStr)
        }
        assertTrue("BGH expired date should be parsed as before now", expireDateBgh != null && expireDateBgh.before(java.util.Date()))

        val futureStr = "2099-01-01T00:00:00Z"
        val expireDateFuture = if (futureStr.endsWith("Z") && futureStr.contains("T")) {
            com.example.ui.viewmodels.EarthlinkSearchViewModel.parseIsoDate(futureStr)
        } else {
            com.example.ui.viewmodels.EarthlinkSearchViewModel.parseBghDate(futureStr)
        }
        assertTrue("Future date should not be before now", expireDateFuture != null && !expireDateFuture.before(java.util.Date()))
    }
}
