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
    fun testNormalizeArabicPersianDigits() {
        assertEquals("0123456789", normalizeArabicPersianDigits("٠١٢٣٤٥٦٧٨٩"))
        assertEquals("0123456789", normalizeArabicPersianDigits("۰۱۲۳۴۵۶۷٨٩"))
        assertEquals("Active 123 days", normalizeArabicPersianDigits("Active 123 days"))
        assertEquals("الاشتراك 5 أيام و 0 ساعة", normalizeArabicPersianDigits("الاشتراك ٥ أيام و ۰ ساعة"))
    }

    @Test
    fun testSanitizePresentationDateStringNullAndSentinels() {
        org.junit.Assert.assertNull(sanitizePresentationDateString(null))
        org.junit.Assert.assertNull(sanitizePresentationDateString(""))
        org.junit.Assert.assertNull(sanitizePresentationDateString("   "))
        org.junit.Assert.assertNull(sanitizePresentationDateString("none"))
        org.junit.Assert.assertNull(sanitizePresentationDateString("NONE"))
        org.junit.Assert.assertNull(sanitizePresentationDateString("n/a"))
        org.junit.Assert.assertNull(sanitizePresentationDateString("N/A"))
    }

    @Test
    fun testSanitizePresentationDateStringFormattingAndNumerals() {
        val sanitizedDirectional = sanitizePresentationDateString("\u200E 2026-07-01\u200F  \u00A0 14:30:00 ")
        assertEquals("2026-07-01 14:30:00", sanitizedDirectional)

        val sanitizedArabic = sanitizePresentationDateString("٢٠٢٦/٠٧/٠١   ٠٢:٠٦   PM")
        assertEquals("2026/07/01 02:06 PM", sanitizedArabic)

        val sanitizedIsoFractional = sanitizePresentationDateString("2026-07-01t14:30:00.1234567z")
        assertEquals("2026-07-01T14:30:00.123Z", sanitizedIsoFractional)
    }

    @Test
    fun testGetRemainingTimeWithArabicNumerals() {
        val resultActiveDays = getRemainingTime(
            expirationDateStr = null,
            activeDaysLeftStr = "٥ days",
            lang = "en",
            accountStatus = "Active"
        )
        assertEquals("Remaining: 5 days", resultActiveDays)

        val resultExpiredArabicDigits = getRemainingTime(
            expirationDateStr = "٢٠٢٠-٠١-٠١T٠٠:٠٠:٠٠.٠٠٠Z",
            activeDaysLeftStr = null,
            lang = "en",
            accountStatus = "Expired"
        )
        assertEquals("Expired", resultExpiredArabicDigits)
    }

    @Test
    fun testParseExpirationTimestamp() {
        org.junit.Assert.assertNull(parseExpirationTimestamp(null))
        org.junit.Assert.assertNull(parseExpirationTimestamp("none"))
        org.junit.Assert.assertNull(parseExpirationTimestamp("n/a"))
        org.junit.Assert.assertNull(parseExpirationTimestamp("invalid date"))

        val timestampStandard = parseExpirationTimestamp("2026-07-01 14:30:00")
        org.junit.Assert.assertNotNull(timestampStandard)

        val timestampArabic = parseExpirationTimestamp("٢٠٢٦-٠٧-٠١ ١٤:٣٠:٠٠")
        org.junit.Assert.assertNotNull(timestampArabic)
        assertEquals(timestampStandard, timestampArabic)
    }
}
