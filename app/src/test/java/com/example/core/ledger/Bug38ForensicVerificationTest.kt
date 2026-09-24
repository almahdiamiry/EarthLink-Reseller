package com.example.core.ledger

import androidx.test.core.app.ApplicationProvider
import com.example.EarthlinkApp
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Permanent Regression Test for BUG-38:
 * NoteCleaner preserves legitimate time-containing notes while maintaining structural colon cleaning.
 *
 * Core Triad:
 * - Claim: Invariant INV-02 & INV-06: Storage integrity and presentation fidelity. User-entered notes
 *   containing time tokens (HH:mm, HH:mm:ss, in ASCII or Arabic-Indic digits) must be preserved in presentation,
 *   while the underlying Room database row remains completely immutable.
 * - Seam / Environment: ROBOLECTRIC in-memory SQLite database executing Room ledger storage and NoteCleaner.
 * - Independent Oracle: Direct string equality assertions comparing input note expressions with cleaned presentation outputs
 *   and verifying database row field identity before and after presentation rendering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Bug38ForensicVerificationTest {

    private lateinit var app: EarthlinkApp
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.isSafeDebugFallbackAllowedOverride = true
        AppDatabase.closeDatabase()
        db = app.database
        runBlocking {
            db.localLedgerEntryDao().deleteAll()
            db.localAccountDao().deleteAll()
        }
    }

    @After
    fun tearDown() {
        AppDatabase.closeDatabase()
    }

    @Test
    fun testTimePreservation_asciiDigits() {
        assertEquals("تجديد 10:30", NoteCleaner.extractGenuineNote("تجديد 10:30"))
        assertEquals("الزبون طلب التجديد الساعة 10:30", NoteCleaner.extractGenuineNote("الزبون طلب التجديد الساعة 10:30"))
        assertEquals("تجديد 10:30 بمبلغ 40,000", NoteCleaner.extractGenuineNote("تجديد 10:30 بمبلغ 40,000"))
        assertEquals("تجديد 10:30 بمبلغ 40,000", NoteCleaner.extractGenuineNote("تجديد 10:30 بمبلغ 40,000", 40000.0))
        assertEquals("تجديد 10:30:45", NoteCleaner.extractGenuineNote("تجديد 10:30:45"))
    }

    @Test
    fun testTimePreservation_arabicIndicDigits() {
        assertEquals("تجديد ١٠:٣٠", NoteCleaner.extractGenuineNote("تجديد ١٠:٣٠"))
        assertEquals("الزبون طلب التجديد الساعة ١٠:٣٠", NoteCleaner.extractGenuineNote("الزبون طلب التجديد الساعة ١٠:٣٠"))
    }

    @Test
    fun testNegativeTwin_structuralColonsPreserved() {
        assertEquals("", NoteCleaner.extractGenuineNote("تجديد اشتراك بقيمة : 40,000", 40000.0))
        assertEquals("", NoteCleaner.extractGenuineNote("تجديد اشتراك بقيمة : 40,000 د.ع", 40000.0))
        assertEquals("", NoteCleaner.extractGenuineNote("تسديد مبلغ : 35,000 د.ع", 35000.0))
        assertEquals("", NoteCleaner.extractGenuineNote("إضافة دين بقيمة : 15,000 د.ع", 15000.0))
    }

    @Test
    fun testStorageIntegrity_roomNoteRemainsUntouched(): Unit = runBlocking {
        val originalRawNote = "تجديد 10:30"
        val entry = LocalLedgerEntry(
            id = "tx_storage_check",
            accountId = "acc_storage_check",
            typeRaw = "took",
            amountIqd = 40000.0,
            debtAfterIqd = 40000.0,
            occurredAt = 1000L,
            createdAt = 1000L,
            note = originalRawNote
        )
        db.localAccountDao().insert(
            LocalAccount(
                id = "acc_storage_check",
                displayName = "Storage Check User",
                debtIqd = 40000.0,
                updatedAt = 1000L
            )
        )
        db.localLedgerEntryDao().insert(entry)

        // Read stored entity
        val storedBefore = db.localLedgerEntryDao().getByIdOneShot("tx_storage_check")
        assertNotNull(storedBefore)
        assertEquals(originalRawNote, storedBefore?.note)

        // Production presentation call (LocalAccountDetailScreen, UserDetailScreenV2, PdfStatementGenerator seam)
        val presentedNote = NoteCleaner.extractGenuineNote(storedBefore?.note, storedBefore?.amountIqd)
        assertEquals("تجديد 10:30", presentedNote)

        // Verify database row remains unchanged
        val storedAfter = db.localLedgerEntryDao().getByIdOneShot("tx_storage_check")
        assertNotNull(storedAfter)
        assertEquals(
            "Original note stored in SQLite Room database must remain completely unaltered",
            originalRawNote,
            storedAfter?.note
        )
    }

    @Test
    fun testProductionCallers_presentationBehavior() {
        val timeNote = "تجديد 10:30"
        val cleaned = NoteCleaner.extractGenuineNote(timeNote, 40000.0)

        // Caller 1: LocalAccountDetailScreen seam (cleanNote.isNotBlank())
        assertTrue("LocalAccountDetailScreen must render note", cleaned.isNotBlank())
        assertEquals("تجديد 10:30", cleaned)

        // Caller 2: UserDetailScreenV2 seam (cleanNote.isNotBlank())
        val userDetailText = if (cleaned.isNotBlank()) "ملاحظة: $cleaned" else ""
        assertEquals("ملاحظة: تجديد 10:30", userDetailText)

        // Caller 3: PdfStatementGenerator seam (noteToDraw = if (genuineNote.isNotBlank()) ... else "-")
        val pdfNoteToDraw = if (cleaned.isNotBlank()) {
            if (cleaned.length > 25) cleaned.substring(0, 22) + "..." else cleaned
        } else {
            "-"
        }
        assertEquals("تجديد 10:30", pdfNoteToDraw)
    }
}
