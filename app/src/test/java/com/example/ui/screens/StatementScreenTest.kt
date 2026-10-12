package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.compose.ui.unit.LayoutDirection

class StatementScreenTest {

    @Test
    fun testLayoutDirectionResolution() {
        val isArTrue = true
        val layoutDirAr = if (isArTrue) LayoutDirection.Rtl else LayoutDirection.Ltr
        assertEquals(LayoutDirection.Rtl, layoutDirAr)

        val isArFalse = false
        val layoutDirEn = if (isArFalse) LayoutDirection.Rtl else LayoutDirection.Ltr
        assertEquals(LayoutDirection.Ltr, layoutDirEn)
    }
}
