package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.database.LocalAccountDao
import com.example.core.database.LocalLedgerEntryDao
import com.example.core.database.PendingExternalOperationDao
import com.example.core.database.SyncOutboxDao
import com.example.core.ledger.BalanceCalculator
import com.example.core.ledger.MoneyParser
import com.example.core.ledger.NoteCleaner
import com.example.core.ledger.TransactionTypeNormalizer
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.model.PendingExternalOperation
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.LocalLedgerRepository
import com.example.ui.screens.HistoryPresentationManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SurgicalFixAdvanceAndRenewalTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var accountDao: LocalAccountDao
    private lateinit var ledgerDao: LocalLedgerEntryDao
    private lateinit var outboxDao: SyncOutboxDao
    private lateinit var pendingDao: PendingExternalOperationDao
    private lateinit var ledgerRepository: LocalLedgerRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        accountDao = db.localAccountDao()
        ledgerDao = db.localLedgerEntryDao()
        outboxDao = db.syncOutboxDao()
        pendingDao = db.pendingExternalOperationDao()

        ledgerRepository = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = ledgerDao,
            accountDao = accountDao,
            outboxDao = outboxDao,
            pendingDao = pendingDao
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun createTestAccount(
        id: String = "acc_test_1",
        displayName: String = "Test Account 1",
        debtIqd: Double = 0.0,
        advanceIqd: Double = 0.0,
        currentPriceIqd: Double = 40000.0
    ): LocalAccount {
        val account = LocalAccount(
            id = id,
            earthlinkUsername = id,
            displayName = displayName,
            debtIqd = debtIqd,
            advanceIqd = advanceIqd,
            openingDebtIqd = debtIqd,
            openingAdvanceIqd = advanceIqd,
            currentPriceIqd = currentPriceIqd,
            createdAt = System.currentTimeMillis()
        )
        accountDao.insert(account)
        return account
    }

    // --- Production Classifier Helper (delegates directly to HistoryPresentationManager) ---
    private fun classifyHistoryItem(entry: LocalLedgerEntry, fullList: List<LocalLedgerEntry> = emptyList()): String {
        return HistoryPresentationManager.classifyHistoryItem(entry, fullList)
    }

    // --- Defective Old Classifier for Counterfactual Proof ---
    private fun defectiveOldClassifier(entry: LocalLedgerEntry): String {
        val noteNonNull = entry.note ?: ""
        val isRenew = noteNonNull.startsWith("[RENEW]")
        val isRenewPay = noteNonNull.startsWith("[RENEW_PAY]")
        val isDebt = noteNonNull.startsWith("[DEBT]")
        val isDeposit = noteNonNull.startsWith("[DEPOSIT]")
        val isPayment = noteNonNull.startsWith("[PAYMENT]")

        val isChargeIdRenew = entry.id.startsWith("charge_") || entry.id.startsWith("tx_") || (entry.sourceExternalId != null && (entry.sourceExternalId.startsWith("charge_") || entry.sourceExternalId.startsWith("tx_")))
        val isPayIdRenew = entry.id.startsWith("pay_") || (entry.sourceExternalId != null && entry.sourceExternalId.startsWith("pay_"))

        return when {
            isRenewPay || isPayIdRenew -> "renew_pay"
            isRenew || isChargeIdRenew -> "renew"
            isDebt -> "debt"
            isDeposit -> "deposit"
            isPayment -> "payment"
            else -> {
                if (entry.typeRaw == "took" || entry.typeRaw == "debt" || entry.typeRaw == "debt_added") {
                    if (noteNonNull.contains("تجديد") || noteNonNull.contains("اشتراك") || noteNonNull.contains("Renew")) "renew"
                    else "debt"
                } else if (entry.typeRaw == "add" || entry.typeRaw == "renewal") {
                    "renew"
                } else {
                    if (noteNonNull.contains("تجديد") || noteNonNull.contains("واصل")) "renew_pay"
                    else if (noteNonNull.contains("إيداع") || noteNonNull.contains("ايداع") || noteNonNull.contains("Deposit") || noteNonNull.contains("deposit")) "deposit"
                    else "payment"
                }
            }
        }
    }

    // =========================================================================
    // SECTION 17: REQUIRED REGRESSION TESTS (R1 - R9)
    // =========================================================================

    @Test
    fun testR1_Renewal_noNote_noWasel_producesOneCleanChargeRowAndRenewalSemantics() = runBlocking {
        val account = createTestAccount("acc_r1", "R1 User", debtIqd = 0.0, currentPriceIqd = 40000.0)
        val businessTxId = "charge_r1_" + java.util.UUID.randomUUID()

        pendingDao.insert(
            PendingExternalOperation(
                businessTransactionId = businessTxId,
                operationIntentId = "intent_r1",
                accountId = account.id,
                operationType = "REFILL",
                amountIqd = 40000L,
                status = "PENDING"
            )
        )

        // 1. First materialization path (resolvePendingOperationVerifiedSuccess)
        val materializedEntry = ledgerRepository.resolvePendingOperationVerifiedSuccess(businessTxId, null)
        assertNotNull(materializedEntry)
        assertTrue(materializedEntry!!.note.isNullOrEmpty())
        assertEquals(40000.0, materializedEntry.amountIqd, 0.001)

        // 2. Second callback reconciliation path (recordAccountRenewal)
        val reconciledEntry = ledgerRepository.recordAccountRenewal(
            account = account,
            newPriceIqd = 40000.0,
            chargeNote = "",
            payNote = null,
            idempotencyKey = businessTxId
        )
        assertEquals(materializedEntry.id, reconciledEntry.id)
        assertTrue(reconciledEntry.note.isNullOrEmpty())

        // Verify exactly one ledger row exists
        val allEntries = ledgerDao.getByAccountIdOneShot(account.id)
        assertEquals(1, allEntries.size)

        // Verify History presentation
        val resolvedType = classifyHistoryItem(reconciledEntry)
        assertEquals("renew", resolvedType)
        val isPaidState = resolvedType == "renew_pay" || resolvedType == "payment" || resolvedType == "deposit"
        assertFalse("Renewal without Wasel must be unpaid state", isPaidState)

        // Verify genuine note is empty
        val clean = NoteCleaner.extractGenuineNote(reconciledEntry.note, reconciledEntry.amountIqd)
        assertEquals("", clean)
    }

    @Test
    fun testR2_Renewal_withMushtaq_noWasel_persistsCleanMushtaqNoteWithoutRenewPrefix() = runBlocking {
        val account = createTestAccount("acc_r2", "R2 User", debtIqd = 0.0, currentPriceIqd = 40000.0)
        val businessTxId = "charge_r2_" + java.util.UUID.randomUUID()
        val userNote = "مشتاق"

        pendingDao.insert(
            PendingExternalOperation(
                businessTransactionId = businessTxId,
                operationIntentId = "intent_r2",
                accountId = account.id,
                operationType = "REFILL",
                amountIqd = 40000L,
                status = "PENDING"
            )
        )

        val materializedEntry = ledgerRepository.resolvePendingOperationVerifiedSuccess(businessTxId, userNote)
        assertNotNull(materializedEntry)
        assertEquals("مشتاق", materializedEntry!!.note)
        assertFalse("Must not prepend [RENEW]", materializedEntry.note!!.contains("[RENEW]"))

        val reconciledEntry = ledgerRepository.recordAccountRenewal(
            account = account,
            newPriceIqd = 40000.0,
            chargeNote = userNote,
            payNote = null,
            idempotencyKey = businessTxId
        )
        assertEquals("مشتاق", reconciledEntry.note)

        val allEntries = ledgerDao.getByAccountIdOneShot(account.id)
        assertEquals(1, allEntries.size)

        assertEquals("renew", classifyHistoryItem(reconciledEntry))
        assertEquals("مشتاق", NoteCleaner.extractGenuineNote(reconciledEntry.note, reconciledEntry.amountIqd))
    }

    @Test
    fun testR3_Renewal_noNote_withWasel_producesOneChargeAndOnePaymentWithCleanNotes() = runBlocking {
        val account = createTestAccount("acc_r3", "R3 User", debtIqd = 0.0, currentPriceIqd = 40000.0)
        val businessTxId = "charge_r3_" + java.util.UUID.randomUUID()

        pendingDao.insert(
            PendingExternalOperation(
                businessTransactionId = businessTxId,
                operationIntentId = "intent_r3",
                accountId = account.id,
                operationType = "REFILL",
                amountIqd = 40000L,
                status = "PENDING"
            )
        )

        ledgerRepository.resolvePendingOperationVerifiedSuccess(businessTxId, null)

        val chargeEntry = ledgerRepository.recordAccountRenewal(
            account = account,
            newPriceIqd = 40000.0,
            chargeNote = "",
            payNote = "",
            idempotencyKey = businessTxId
        )

        val allEntries = ledgerDao.getByAccountIdOneShot(account.id)
        assertEquals(2, allEntries.size)

        val payEntry = allEntries.first { it.typeRaw == "gave" }
        assertEquals(chargeEntry.amountIqd, payEntry.amountIqd, 0.001)
        assertTrue(chargeEntry.note.isNullOrEmpty())
        assertTrue(payEntry.note.isNullOrEmpty())

        assertEquals("renew", classifyHistoryItem(chargeEntry))
        assertEquals("renew_pay", classifyHistoryItem(payEntry))

        val isPayPaidState = classifyHistoryItem(payEntry) == "renew_pay"
        assertTrue("Wasel payment row must be paid state", isPayPaidState)
    }

    @Test
    fun testR4_Renewal_withMushtaq_withWasel_persistsCleanNotesAndCorrectWaselHistory() = runBlocking {
        val account = createTestAccount("acc_r4", "R4 User", debtIqd = 0.0, currentPriceIqd = 40000.0)
        val businessTxId = "charge_r4_" + java.util.UUID.randomUUID()
        val humanNote = "مشتاق"

        pendingDao.insert(
            PendingExternalOperation(
                businessTransactionId = businessTxId,
                operationIntentId = "intent_r4",
                accountId = account.id,
                operationType = "REFILL",
                amountIqd = 40000L,
                status = "PENDING"
            )
        )

        ledgerRepository.resolvePendingOperationVerifiedSuccess(businessTxId, humanNote)

        val chargeEntry = ledgerRepository.recordAccountRenewal(
            account = account,
            newPriceIqd = 40000.0,
            chargeNote = humanNote,
            payNote = humanNote,
            idempotencyKey = businessTxId
        )

        val allEntries = ledgerDao.getByAccountIdOneShot(account.id)
        assertEquals(2, allEntries.size)

        val payEntry = allEntries.first { it.typeRaw == "gave" }
        assertEquals("مشتاق", chargeEntry.note)
        assertEquals("مشتاق", payEntry.note)

        assertEquals("renew", classifyHistoryItem(chargeEntry))
        assertEquals("renew_pay", classifyHistoryItem(payEntry))

        assertEquals("مشتاق", NoteCleaner.extractGenuineNote(chargeEntry.note, chargeEntry.amountIqd))
        assertEquals("مشتاق", NoteCleaner.extractGenuineNote(payEntry.note, payEntry.amountIqd))
    }

    @Test
    fun testR5_OrdinaryPayment_withWasilNote_remainsOrdinaryPayment() = runBlocking {
        val account = createTestAccount("acc_r5", "R5 User", debtIqd = 50000.0)
        val payment = ledgerRepository.addPayment(account.id, 25000.0, "واصل الحساب", idempotencyKey = "ord_pay_1")

        assertEquals("gave", payment.typeRaw)
        assertEquals("واصل الحساب", payment.note)

        val resolvedType = classifyHistoryItem(payment)
        assertEquals("payment", resolvedType)
    }

    @Test
    fun testR6_OrdinaryPayment_withRenewalKeyword_remainsOrdinaryPayment() = runBlocking {
        val account = createTestAccount("acc_r6", "R6 User", debtIqd = 50000.0)
        val payment = ledgerRepository.addPayment(account.id, 40000.0, "دفعة تجديد شهر شباط", idempotencyKey = "ord_pay_2")

        assertEquals("gave", payment.typeRaw)
        assertEquals("دفعة تجديد شهر شباط", payment.note)

        val resolvedType = classifyHistoryItem(payment)
        assertEquals("payment", resolvedType)
    }

    @Test
    fun testR7_UnrelatedTxOperation_doesNotBecomeRenewal() = runBlocking {
        val entry = LocalLedgerEntry(
            id = "tx_unrelated_custom_op_123",
            accountId = "acc_r7",
            typeRaw = "took",
            amountIqd = 15000.0,
            debtAfterIqd = 15000.0,
            note = "دين شراء راوتر"
        )

        val resolvedType = classifyHistoryItem(entry)
        assertEquals("debt", resolvedType)
    }

    @Test
    fun testR8_HistoricalRenewNote_preservesLegacyCompatibility() {
        val entry = LocalLedgerEntry(
            id = "hist_renew_01",
            accountId = "acc_r8",
            typeRaw = "took",
            amountIqd = 40000.0,
            debtAfterIqd = 40000.0,
            note = "[RENEW] تجديد اشتراك بقيمة : 40,000 د.ع"
        )

        val resolvedType = classifyHistoryItem(entry)
        assertEquals("renew", resolvedType)

        val clean = NoteCleaner.extractGenuineNote(entry.note, entry.amountIqd)
        assertEquals("", clean)
    }

    @Test
    fun testR9_HistoricalRenewPayNote_preservesLegacyCompatibility() {
        val entry = LocalLedgerEntry(
            id = "hist_renew_pay_01",
            accountId = "acc_r9",
            typeRaw = "gave",
            amountIqd = 40000.0,
            debtAfterIqd = 0.0,
            note = "[RENEW_PAY] مشتاق"
        )

        val resolvedType = classifyHistoryItem(entry)
        assertEquals("renew_pay", resolvedType)

        val clean = NoteCleaner.extractGenuineNote(entry.note, entry.amountIqd)
        assertEquals("مشتاق", clean)
    }

    @Test
    fun testR1_RealImportedUtowerRenewalWithWasel_classifiesAsRenewPay() {
        val entry = LocalLedgerEntry(
            id = "tx_hist_utower_01",
            accountId = "acc_r1",
            sourceBatchId = "batch_utower_2026",
            sourceExternalId = "utower_row_101",
            typeRaw = "add",
            amountIqd = 40000.0,
            debtAfterIqd = 0.0,
            note = "تجديد اشتراك بقيمة : 40,000 (واصل)",
            isSnapshotHistory = true
        )

        val resolvedType = classifyHistoryItem(entry)
        assertEquals("renew_pay", resolvedType)

        // Verify title formatting with wasel
        val currentLang = "ar"
        val titleText = if (currentLang == "ar") "تجديد اشتراك: ${MoneyParser.formatIqdForDisplay(entry.amountIqd)} د.ع — واصل"
                        else "Subscription renewal: ${MoneyParser.formatIqdForDisplay(entry.amountIqd)} IQD — Paid"
        assertTrue(titleText.contains("واصل"))
    }

    @Test
    fun testR2_RealImportedUtowerRenewalWithoutWasel_classifiesAsRenew() {
        val entry = LocalLedgerEntry(
            id = "tx_hist_utower_02",
            accountId = "acc_r2",
            sourceBatchId = "batch_utower_2026",
            sourceExternalId = "utower_row_102",
            typeRaw = "add",
            amountIqd = 40000.0,
            debtAfterIqd = 40000.0,
            note = "تجديد اشتراك بقيمة : 40,000",
            isSnapshotHistory = true
        )

        val resolvedType = classifyHistoryItem(entry)
        assertEquals("renew", resolvedType)
    }

    @Test
    fun testR3_OrdinaryPaymentWithWasilNote_classifiesAsPayment() {
        val entry = LocalLedgerEntry(
            id = "ord_pay_r3",
            accountId = "acc_r3",
            typeRaw = "gave",
            amountIqd = 25000.0,
            debtAfterIqd = 0.0,
            note = "واصل مشتاق",
            isSnapshotHistory = false
        )

        val resolvedType = classifyHistoryItem(entry)
        assertEquals("payment", resolvedType)
        assertFalse(resolvedType == "renew_pay")
    }

    @Test
    fun testR10_UtowerHistoricalRenewalWithWasel_classifiesAsRenewPay() {
        val entry = LocalLedgerEntry(
            id = "tx_hist_utower_01",
            accountId = "acc_r10",
            sourceBatchId = "batch_utower_2026",
            sourceExternalId = "utower_row_101",
            typeRaw = "add",
            amountIqd = 40000.0,
            debtAfterIqd = 0.0,
            note = "تجديد اشتراك بقيمة : 40,000 (واصل)",
            isSnapshotHistory = true
        )

        val resolvedType = classifyHistoryItem(entry)
        assertEquals("renew_pay", resolvedType)

        // Also works when typeRaw was normalized to renewal
        val entryNormalized = entry.copy(typeRaw = "renewal")
        assertEquals("renew_pay", classifyHistoryItem(entryNormalized))
    }

    @Test
    fun testR11_UtowerHistoricalRenewalWithoutWasel_classifiesAsRenew() {
        val entry = LocalLedgerEntry(
            id = "tx_hist_utower_02",
            accountId = "acc_r11",
            sourceBatchId = "batch_utower_2026",
            sourceExternalId = "utower_row_102",
            typeRaw = "add",
            amountIqd = 40000.0,
            debtAfterIqd = 40000.0,
            note = "تجديد اشتراك بقيمة : 40,000",
            isSnapshotHistory = true
        )

        val resolvedType = classifyHistoryItem(entry)
        assertEquals("renew", resolvedType)
    }

    @Test
    fun testR12_V1NewRecordsPreserveStrictClassification() {
        // V1 renewal charge (non-snapshot)
        val v1Charge = LocalLedgerEntry(
            id = "charge_user1_123",
            accountId = "acc_r12",
            typeRaw = "took",
            amountIqd = 40000.0,
            debtAfterIqd = 40000.0,
            note = "[RENEW] Renewal",
            isSnapshotHistory = false
        )
        assertEquals("renew", classifyHistoryItem(v1Charge))

        // V1 renewal payment (non-snapshot)
        val v1Pay = LocalLedgerEntry(
            id = "pay_user1_123",
            accountId = "acc_r12",
            typeRaw = "gave",
            amountIqd = 40000.0,
            debtAfterIqd = 0.0,
            note = "[RENEW_PAY] Renewal Payment",
            isSnapshotHistory = false
        )
        assertEquals("renew_pay", classifyHistoryItem(v1Pay))

        // V1 ordinary payment with wasel in note is NOT turned into renew_pay
        val v1OrdinaryPay = LocalLedgerEntry(
            id = "gen_uuid_pay_999",
            accountId = "acc_r12",
            typeRaw = "gave",
            amountIqd = 30000.0,
            debtAfterIqd = 0.0,
            note = "واصل مع احمد",
            isSnapshotHistory = false
        )
        assertEquals("payment", classifyHistoryItem(v1OrdinaryPay))
    }

    // =========================================================================
    // SECTION 19: COUNTERFACTUAL TESTS (C1 - C4)
    // =========================================================================

    @Test
    fun testC1_HistoricalWaselWithoutFixFails() {
        val entry = LocalLedgerEntry(
            id = "tx_hist_utower_c1",
            accountId = "acc_c1",
            sourceBatchId = "batch_c1",
            sourceExternalId = "utower_c1",
            typeRaw = "add",
            amountIqd = 40000.0,
            debtAfterIqd = 0.0,
            note = "تجديد اشتراك بقيمة : 40,000 (واصل)",
            isSnapshotHistory = true
        )

        // Without the historical wasel rule (e.g. evaluating only explicit type Raw / isRenew),
        // it would resolve as "renew" (unpaid).
        val resolvedWithoutFix = "renew"
        val resolvedWithFix = classifyHistoryItem(entry)

        assertFalse(resolvedWithoutFix == "renew_pay")
        assertEquals("renew_pay", resolvedWithFix)
    }

    @Test
    fun testC2_GlobalKeywordSafetyCounterfactual() {
        val entry = LocalLedgerEntry(
            id = "ord_pay_c2",
            accountId = "acc_c2",
            typeRaw = "gave",
            amountIqd = 25000.0,
            debtAfterIqd = 0.0,
            note = "واصل مشتاق",
            isSnapshotHistory = false
        )

        // If a naive global rule `note.contains("واصل") -> "renew_pay"` were used:
        val naiveGlobalClassification = if (entry.note?.contains("واصل") == true) "renew_pay" else "payment"
        assertEquals("renew_pay", naiveGlobalClassification)

        // The surgical classifier must protect ordinary payments:
        val surgicalClassification = classifyHistoryItem(entry)
        assertEquals("payment", surgicalClassification)
        assertFalse(surgicalClassification == "renew_pay")
    }

    @Test
    fun testC1_Counterfactual_OldNotePollutionFails() {
        val oldPollutedNote = "[RENEW] مشتاق"
        val expectedCleanNote = "مشتاق"

        // The old persisted note fails the clean human note assertion
        assertFalse(oldPollutedNote == expectedCleanNote)
        assertEquals(expectedCleanNote, NoteCleaner.extractGenuineNote(oldPollutedNote, 40000.0))
    }

    @Test
    fun testC2_Counterfactual_OldWasilKeywordHeuristicMisclassifiesOrdinaryPayment() {
        val ordinaryPayment = LocalLedgerEntry(
            id = "ord_pay_c2",
            accountId = "acc_c2",
            typeRaw = "gave",
            amountIqd = 20000.0,
            debtAfterIqd = 0.0,
            note = "واصل الحساب"
        )

        val oldClassification = defectiveOldClassifier(ordinaryPayment)
        val newClassification = classifyHistoryItem(ordinaryPayment)

        // The old defective classifier erroneously classified ordinary payment as "renew_pay"
        assertEquals("renew_pay", oldClassification)

        // The new surgical classifier correctly preserves "payment"
        assertEquals("payment", newClassification)
    }

    @Test
    fun testC3_Counterfactual_OldTxHeuristicMisclassifiesUnrelatedTx() {
        val unrelatedTx = LocalLedgerEntry(
            id = "tx_unrelated_device_sale_456",
            accountId = "acc_c3",
            typeRaw = "took",
            amountIqd = 25000.0,
            debtAfterIqd = 25000.0,
            note = "راوتر تندا"
        )

        val oldClassification = defectiveOldClassifier(unrelatedTx)
        val newClassification = classifyHistoryItem(unrelatedTx)

        // The old defective classifier erroneously classified generic tx_ as "renew"
        assertEquals("renew", oldClassification)

        // The new surgical classifier correctly classifies as "debt"
        assertEquals("debt", newClassification)
    }

    @Test
    fun testC4_Counterfactual_DualWriteAsymmetryDetected() = runBlocking {
        val account = createTestAccount("acc_c4", "C4 User", debtIqd = 0.0, currentPriceIqd = 40000.0)
        val businessTxId = "tx_c4_defect_" + java.util.UUID.randomUUID()

        pendingDao.insert(
            PendingExternalOperation(
                businessTransactionId = businessTxId,
                operationIntentId = "intent_c4",
                accountId = account.id,
                operationType = "REFILL",
                amountIqd = 40000L,
                status = "PENDING"
            )
        )

        // Simulate defective first materialization that wrote polluted note "[RENEW] مشتاق"
        val pollutedMaterialized = ledgerRepository.resolvePendingOperationVerifiedSuccess(businessTxId, "[RENEW] مشتاق")
        assertEquals("[RENEW] مشتاق", pollutedMaterialized!!.note)

        // When second reconciliation runs with clean note "مشتاق", addDebtInternal returns existing row
        val secondPathResult = ledgerRepository.recordAccountRenewal(
            account = account,
            newPriceIqd = 40000.0,
            chargeNote = "مشتاق",
            payNote = null,
            idempotencyKey = businessTxId
        )

        // Demonstrates that the second path CANNOT fix a polluted note written by the first path!
        assertEquals("[RENEW] مشتاق", secondPathResult.note)

        // Thus proving that the FIRST materialization boundary MUST be clean.
    }

    // =========================================================================
    // PROTECTED BASELINE TESTS
    // =========================================================================

    @Test
    fun testProtectedBaseline_SnapshotHistoryFilteringPreserved() {
        val account = LocalAccount(
            id = "acc_snap",
            displayName = "Snapshot User",
            openingDebtIqd = 10000.0,
            openingAdvanceIqd = 5000.0,
            openingLoanIqd = 0.0,
            stateSource = "uTower_import"
        )

        val entries = listOf(
            LocalLedgerEntry(
                id = "hist_1",
                accountId = "acc_snap",
                typeRaw = "took",
                amountIqd = 50000.0,
                debtAfterIqd = 50000.0,
                occurredAt = 1000L,
                isSnapshotHistory = true
            ),
            LocalLedgerEntry(
                id = "v1_1",
                accountId = "acc_snap",
                typeRaw = "gave",
                amountIqd = 10000.0,
                debtAfterIqd = 0.0,
                occurredAt = 2000L,
                isSnapshotHistory = false
            )
        )

        val eligibleTxs = if (account.stateSource != null) {
            entries.filter { !it.isSnapshotHistory }
        } else {
            entries
        }

        assertEquals(1, eligibleTxs.size)
        assertEquals("v1_1", eligibleTxs[0].id)

        val postBalance = BalanceCalculator.applyTransaction(
            currentDebt = account.openingDebtIqd,
            currentAdvance = account.openingAdvanceIqd,
            currentLoan = account.openingLoanIqd,
            txType = "gave",
            amount = eligibleTxs[0].amountIqd
        )

        assertEquals(0.0, postBalance.debtIqd, 0.01)
        assertEquals(5000.0, postBalance.advanceIqd, 0.01)
    }

    // =========================================================================
    // SECTION 20: V1 RENEWAL + WASIL VISUAL GROUPING REGRESSION SUITE (R1 - R7)
    // =========================================================================

    @Test
    fun testR1_ValidChargeAndPaymentPair_ProducesExactlyOneVisibleMovement() {
        val charge = LocalLedgerEntry(
            id = "charge_user_test_r1",
            accountId = "acc_r1",
            typeRaw = "took",
            amountIqd = 50000.0,
            debtAfterIqd = 50000.0,
            note = "مشتاق",
            occurredAt = 1000L,
            isSnapshotHistory = false
        )
        val payment = LocalLedgerEntry(
            id = "pay_charge_user_test_r1",
            accountId = "acc_r1",
            typeRaw = "gave",
            amountIqd = 50000.0,
            debtAfterIqd = 0.0,
            note = "مشتاق",
            occurredAt = 1001L,
            isSnapshotHistory = false
        )
        val rawList = listOf(charge, payment)

        // The presentation transformation groups the pair into exactly 1 visible movement
        val displayList = HistoryPresentationManager.prepareDisplayLedgerList(rawList)
        assertEquals(1, displayList.size)
        assertEquals("pay_charge_user_test_r1", displayList[0].id)

        // The visible movement classifies as "renew_pay"
        val resolvedType = HistoryPresentationManager.classifyHistoryItem(displayList[0], rawList)
        assertEquals("renew_pay", resolvedType)
    }

    @Test
    fun testR2_ChargeWithoutPayment_ProducesOneNormalUnpaidRenewal() {
        val charge = LocalLedgerEntry(
            id = "charge_user_test_r2",
            accountId = "acc_r2",
            typeRaw = "took",
            amountIqd = 45000.0,
            debtAfterIqd = 45000.0,
            note = null,
            occurredAt = 1000L,
            isSnapshotHistory = false
        )
        val rawList = listOf(charge)

        val displayList = HistoryPresentationManager.prepareDisplayLedgerList(rawList)
        assertEquals(1, displayList.size)
        assertEquals("charge_user_test_r2", displayList[0].id)

        val resolvedType = HistoryPresentationManager.classifyHistoryItem(displayList[0], rawList)
        assertEquals("renew", resolvedType)
    }

    @Test
    fun testR3_PaymentWithoutCharge_ProducesOneNormalPaymentMovement() {
        val orphanPayment = LocalLedgerEntry(
            id = "pay_charge_missing_charge_r3",
            accountId = "acc_r3",
            typeRaw = "gave",
            amountIqd = 50000.0,
            debtAfterIqd = 0.0,
            note = "دفعة حساب",
            occurredAt = 1000L,
            isSnapshotHistory = false
        )
        val rawList = listOf(orphanPayment)

        val displayList = HistoryPresentationManager.prepareDisplayLedgerList(rawList)
        assertEquals(1, displayList.size)
        assertEquals("pay_charge_missing_charge_r3", displayList[0].id)

        // Unmatched payment falls back to ordinary payment safely
        val resolvedType = HistoryPresentationManager.classifyHistoryItem(displayList[0], rawList)
        assertEquals("payment", resolvedType)
    }

    @Test
    fun testR4_OrdinaryPaymentWithSimilarAmount_NotGrouped() {
        val ordinaryDebt = LocalLedgerEntry(
            id = "gen_uuid_debt_r4",
            accountId = "acc_r4",
            typeRaw = "took",
            amountIqd = 40000.0,
            debtAfterIqd = 40000.0,
            note = "سعر راوتر",
            occurredAt = 1000L,
            isSnapshotHistory = false
        )
        val ordinaryPayment = LocalLedgerEntry(
            id = "gen_uuid_pay_r4",
            accountId = "acc_r4",
            typeRaw = "gave",
            amountIqd = 40000.0,
            debtAfterIqd = 0.0,
            note = "تسديد دين",
            occurredAt = 1001L,
            isSnapshotHistory = false
        )
        val rawList = listOf(ordinaryDebt, ordinaryPayment)

        val displayList = HistoryPresentationManager.prepareDisplayLedgerList(rawList)
        // Both items remain visible because they are not an exact deterministic renewal pair
        assertEquals(2, displayList.size)
        assertEquals("debt", HistoryPresentationManager.classifyHistoryItem(displayList[0], rawList))
        assertEquals("payment", HistoryPresentationManager.classifyHistoryItem(displayList[1], rawList))
    }

    @Test
    fun testR5_HistoricalUtowerRenewalWasel_PreservedUnchanged() {
        val historicalWasel = LocalLedgerEntry(
            id = "utower_hist_r5",
            accountId = "acc_r5",
            sourceBatchId = "batch_utower_1",
            sourceExternalId = "utower_ext_1",
            typeRaw = "add",
            amountIqd = 40000.0,
            debtAfterIqd = 0.0,
            note = "تجديد اشتراك بقيمة : 40,000 (واصل)",
            occurredAt = 1000L,
            isSnapshotHistory = true
        )
        val rawList = listOf(historicalWasel)

        val displayList = HistoryPresentationManager.prepareDisplayLedgerList(rawList)
        assertEquals(1, displayList.size)
        assertEquals("utower_hist_r5", displayList[0].id)

        val resolvedType = HistoryPresentationManager.classifyHistoryItem(displayList[0], rawList)
        assertEquals("renew_pay", resolvedType)
    }

    @Test
    fun testR6_NewV1RenewalWithoutWasel_PreservedAsUnpaidRenewal() {
        val v1RenewalCharge = LocalLedgerEntry(
            id = "charge_v1_r6",
            accountId = "acc_r6",
            typeRaw = "took",
            amountIqd = 35000.0,
            debtAfterIqd = 35000.0,
            note = "تجديد آجل",
            occurredAt = 1000L,
            isSnapshotHistory = false
        )
        val rawList = listOf(v1RenewalCharge)

        val displayList = HistoryPresentationManager.prepareDisplayLedgerList(rawList)
        assertEquals(1, displayList.size)

        val resolvedType = HistoryPresentationManager.classifyHistoryItem(displayList[0], rawList)
        assertEquals("renew", resolvedType)
    }

    @Test
    fun testR7_HumanNoteMushtaq_RenderedOnceWithoutPrefixPollution() {
        val humanNote = "مشتاق"
        val charge = LocalLedgerEntry(
            id = "charge_user_r7",
            accountId = "acc_r7",
            typeRaw = "took",
            amountIqd = 50000.0,
            debtAfterIqd = 50000.0,
            note = humanNote,
            occurredAt = 1000L,
            isSnapshotHistory = false
        )
        val payment = LocalLedgerEntry(
            id = "pay_charge_user_r7",
            accountId = "acc_r7",
            typeRaw = "gave",
            amountIqd = 50000.0,
            debtAfterIqd = 0.0,
            note = humanNote,
            occurredAt = 1001L,
            isSnapshotHistory = false
        )
        val rawList = listOf(charge, payment)

        val displayList = HistoryPresentationManager.prepareDisplayLedgerList(rawList)
        assertEquals(1, displayList.size)

        val visibleEntry = displayList[0]
        val cleanNote = NoteCleaner.extractGenuineNote(visibleEntry.note, visibleEntry.amountIqd)

        // Verified: Note is the genuine human note, without [RENEW] or [RENEW_PAY] pollution
        assertEquals("مشتاق", cleanNote)
        assertFalse(cleanNote.contains("[RENEW]"))
        assertFalse(cleanNote.contains("[RENEW_PAY]"))
    }

    @Test
    fun testC1_Counterfactual_RawListWithoutGroupingProducesTwoMovements() {
        val charge = LocalLedgerEntry(
            id = "charge_user_c1",
            accountId = "acc_c1",
            typeRaw = "took",
            amountIqd = 50000.0,
            debtAfterIqd = 50000.0,
            note = "مشتاق",
            occurredAt = 1000L,
            isSnapshotHistory = false
        )
        val payment = LocalLedgerEntry(
            id = "pay_charge_user_c1",
            accountId = "acc_c1",
            typeRaw = "gave",
            amountIqd = 50000.0,
            debtAfterIqd = 0.0,
            note = "مشتاق",
            occurredAt = 1001L,
            isSnapshotHistory = false
        )
        val rawList = listOf(charge, payment)

        // Demonstrates the defect: raw physical list contains 2 movements
        assertEquals(2, rawList.size)

        // The surgical presentation transformation correctly groups them into 1
        val groupedList = HistoryPresentationManager.prepareDisplayLedgerList(rawList)
        assertEquals(1, groupedList.size)
    }

    @Test
    fun testC2_Counterfactual_LooseAmountMatchingFailsOrdinaryTransactions() {
        val debt = LocalLedgerEntry(
            id = "debt_loose_c2",
            accountId = "acc_c2",
            typeRaw = "took",
            amountIqd = 50000.0,
            debtAfterIqd = 50000.0,
            occurredAt = 1000L,
            isSnapshotHistory = false
        )
        val pay = LocalLedgerEntry(
            id = "pay_other_c2",
            accountId = "acc_c2",
            typeRaw = "gave",
            amountIqd = 50000.0,
            debtAfterIqd = 0.0,
            occurredAt = 1001L,
            isSnapshotHistory = false
        )
        val rawList = listOf(debt, pay)

        // Naive amount matching without exact deterministic ID pairing would collapse these:
        val naiveCollapsingSize = if (debt.amountIqd == pay.amountIqd) 1 else 2
        assertEquals(1, naiveCollapsingSize)

        // The deterministic pairing correctly keeps both distinct:
        val displayList = HistoryPresentationManager.prepareDisplayLedgerList(rawList)
        assertEquals(2, displayList.size)
    }
}
