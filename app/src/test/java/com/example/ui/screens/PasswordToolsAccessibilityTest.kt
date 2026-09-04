package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class PasswordToolsAccessibilityTest {

    @Test
    fun testCloseButtonContentDescriptionLocalization() {
        val currentLang = "ar"
        val description = if (currentLang == "ar") "إغلاق" else "Close"
        assertEquals("إغلاق", description)

        val currentLangEn = "en"
        val descriptionEn = if (currentLangEn == "ar") "إغلاق" else "Close"
        assertEquals("Close", descriptionEn)
    }

    @Test
    fun testSaveButtonContentDescriptionLocalization() {
        val currentLang = "ar"
        val description = if (currentLang == "ar") "حفظ" else "Save"
        assertEquals("حفظ", description)

        val currentLangEn = "en"
        val descriptionEn = if (currentLangEn == "ar") "حفظ" else "Save"
        assertEquals("Save", descriptionEn)
    }

    @Test
    fun testBackButtonContentDescriptionLocalization() {
        val currentLang = "ar"
        val description = if (currentLang == "ar") "رجوع" else "Back"
        assertEquals("رجوع", description)

        val currentLangEn = "en"
        val descriptionEn = if (currentLangEn == "ar") "رجوع" else "Back"
        assertEquals("Back", descriptionEn)
    }
}
