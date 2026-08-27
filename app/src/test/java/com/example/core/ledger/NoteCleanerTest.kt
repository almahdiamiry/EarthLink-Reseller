package com.example.core.ledger

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteCleanerTest {

    @Test
    fun `null or blank note returns empty string`() {
        assertEquals("", NoteCleaner.extractGenuineNote(null))
        assertEquals("", NoteCleaner.extractGenuineNote(""))
        assertEquals("", NoteCleaner.extractGenuineNote("   "))
        assertEquals("", NoteCleaner.extractGenuineNote("null"))
        assertEquals("", NoteCleaner.extractGenuineNote("NULL"))
    }

    @Test
    fun `strips prefixes and cleans raw amount noise`() {
        assertEquals("", NoteCleaner.extractGenuineNote("[RENEW] 40,000", 40000.0))
        assertEquals("", NoteCleaner.extractGenuineNote("[RENEW] 40000", 40000.0))
        assertEquals("", NoteCleaner.extractGenuineNote("[DEBT] 25,000 د.ع", 25000.0))
        assertEquals("", NoteCleaner.extractGenuineNote("[DEPOSIT] 50000", 50000.0))
        assertEquals("", NoteCleaner.extractGenuineNote("[PAYMENT] 000"))
        assertEquals("", NoteCleaner.extractGenuineNote("40,000"))
        assertEquals("", NoteCleaner.extractGenuineNote("40000 د.ع"))
        assertEquals("", NoteCleaner.extractGenuineNote("40000 IQD"))
    }

    @Test
    fun `filters out system boilerplate and generated payment notes`() {
        assertEquals("", NoteCleaner.extractGenuineNote("تجديد اشتراك بقيمة : 40,000 د.ع", 40000.0))
        assertEquals("", NoteCleaner.extractGenuineNote("تسديد مبلغ : 35,000 د.ع", 35000.0))
        assertEquals("", NoteCleaner.extractGenuineNote("إضافة دين بقيمة : 15,000 د.ع", 15000.0))
        assertEquals("", NoteCleaner.extractGenuineNote("تسديد نقدي 40,000", 40000.0))
        assertEquals("", NoteCleaner.extractGenuineNote("تسديد 80,000", 80000.0))
        assertEquals("", NoteCleaner.extractGenuineNote("40,000 واصل", 40000.0))
        assertEquals("", NoteCleaner.extractGenuineNote("واصل 40,000", 40000.0))
        assertEquals("", NoteCleaner.extractGenuineNote("واصل كاش 40,000", 40000.0))
        assertEquals("", NoteCleaner.extractGenuineNote("تسديد كاش 80,000", 80000.0))
        assertEquals("", NoteCleaner.extractGenuineNote("تسديد", 80000.0))
        assertEquals("", NoteCleaner.extractGenuineNote("واصل", 40000.0))
    }

    @Test
    fun `preserves genuine user entered notes`() {
        assertEquals("واصل مع احمد", NoteCleaner.extractGenuineNote("واصل مع احمد"))
        assertEquals("دفعة اولى والباقي الاسبوع القادم", NoteCleaner.extractGenuineNote("[PAYMENT] دفعة اولى والباقي الاسبوع القادم"))
        assertEquals("وصل رقم 1234", NoteCleaner.extractGenuineNote("وصل رقم 1234"))
        assertEquals("تحويل زين كاش", NoteCleaner.extractGenuineNote("[DEPOSIT] تحويل زين كاش"))
        assertEquals("واصل مشتاق", NoteCleaner.extractGenuineNote("واصل مشتاق", 40000.0))
        assertEquals("نجم", NoteCleaner.extractGenuineNote("نجم", 40000.0))
        assertEquals("على مشتاق", NoteCleaner.extractGenuineNote("على مشتاق", 40000.0))
        assertEquals("على نجم والي قبله مشتاق", NoteCleaner.extractGenuineNote("على نجم والي قبله مشتاق", 40000.0))
        assertEquals("مشتاق", NoteCleaner.extractGenuineNote("[RENEW_PAY] مشتاق", 40000.0))
        assertEquals("مشتاق", NoteCleaner.extractGenuineNote("[RENEW] مشتاق", 40000.0))
    }

    @Test
    fun `extracts genuine segment from composite note with separator`() {
        assertEquals("تجديد شهر 8", NoteCleaner.extractGenuineNote("40,000 | تجديد شهر 8", 40000.0))
        assertEquals("مع ابو علي", NoteCleaner.extractGenuineNote("تجديد اشتراك - مع ابو علي", 40000.0))
        assertEquals("", NoteCleaner.extractGenuineNote("40,000 | 40,000", 40000.0))
        assertEquals("نجم", NoteCleaner.extractGenuineNote("40,000 واصل | نجم", 40000.0))
    }
}
