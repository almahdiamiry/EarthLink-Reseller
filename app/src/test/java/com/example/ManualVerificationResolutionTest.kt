package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.database.LocalAccountDao
import com.example.core.database.LocalLedgerEntryDao
import com.example.core.database.PendingExternalOperationDao
import com.example.core.database.SyncOutboxDao
import com.example.core.model.LocalAccount
import com.example.core.model.PendingExternalOperation
import com.example.core.model.UnknownOutcomeResolutionResult
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.LocalLedgerRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ManualVerificationResolutionTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var pendingDao: PendingExternalOperationDao
    private lateinit var accountDao: LocalAccountDao
    private lateinit var ledgerDao: LocalLedgerEntryDao
    private lateinit var outboxDao: SyncOutboxDao
    private lateinit var ledgerRepository: LocalLedgerRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        (context as? com.example.EarthlinkApp)?.isSafeDebugFallbackAllowedOverride = true
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        pendingDao = db.pendingExternalOperationDao()
        accountDao = db.localAccountDao()
        ledgerDao = db.localLedgerEntryDao()
        outboxDao = db.syncOutboxDao()
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

    @Test
    fun testValidEvidence_performsAtomicFinancialMaterialization() = runBlocking {
        val txId = "tx_" + UUID.randomUUID()
        val intentId = "intent_" + UUID.randomUUID()
        val accountId = "acc_01"
        val amount = 50000L

        // Set up the local account target
        val account = LocalAccount(
            id = accountId,
            displayName = "Manual Test Account",
            currentPriceIqd = 0.0,
            debtIqd = 0.0,
            advanceIqd = 0.0,
            loanIqd = 0.0
        )
        accountDao.insert(account)

        // Set up the pending operation
        val pending = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = intentId,
            accountId = accountId,
            operationType = "ACTIVATION",
            amountIqd = amount,
            status = "PENDING"
        )
        ledgerRepository.recordPendingOperation(pending)

        // Submit valid evidence
        val evidenceText = "Manual ISP reference code #102030"
        val resolution = ledgerRepository.submitManualVerificationEvidence(txId, evidenceText)

        // 1. Evidence persistence check
        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, resolution.result)
        assertEquals("COMPLETED", resolution.operation.status)
        assertEquals(evidenceText, resolution.operation.verificationEvidence)

        // 2. Canonical verified-success check & Atomic financial materialization check
        // Check local account position (the debt should have increased by amount)
        val updatedAccount = accountDao.getByIdOneShot(accountId)
        assertNotNull(updatedAccount)
        assertEquals(amount.toDouble(), updatedAccount?.debtIqd ?: 0.0, 0.001)

        // Check ledger entry
        val ledgerEntry = ledgerDao.getByIdOneShot(txId)
        assertNotNull(ledgerEntry)
        assertEquals(amount.toDouble(), ledgerEntry?.amountIqd ?: 0.0, 0.001)
        assertEquals("[MANUAL VERIFIED]", ledgerEntry?.note)

        // Check outbox entries
        val accountOutbox = outboxDao.getByEntity(accountId, "local_accounts")
        assertFalse("Account outbox entry should exist", accountOutbox.isEmpty())

        val ledgerOutbox = outboxDao.getByEntity(txId, "local_ledger_entries")
        assertFalse("Ledger outbox entry should exist", ledgerOutbox.isEmpty())
    }

    @Test
    fun testEmptyAndMalformedEvidence_throwsException() = runBlocking {
        val txId = "tx_" + UUID.randomUUID()
        val intentId = "intent_" + UUID.randomUUID()
        val accountId = "acc_02"

        val pending = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = intentId,
            accountId = accountId,
            operationType = "ACTIVATION",
            amountIqd = 40000L,
            status = "PENDING"
        )
        ledgerRepository.recordPendingOperation(pending)

        // 1. Empty evidence check
        try {
            ledgerRepository.submitManualVerificationEvidence(txId, "   ")
            fail("Should throw exception for blank evidence")
        } catch (e: IllegalArgumentException) {
            assertEquals("Insufficient external verification evidence provided.", e.message)
        }

        // 2. Too short (malformed) evidence check (length < 5)
        try {
            ledgerRepository.submitManualVerificationEvidence(txId, "123")
            fail("Should throw exception for too short evidence")
        } catch (e: IllegalArgumentException) {
            assertEquals("Insufficient external verification evidence provided.", e.message)
        }

        // Verify state is unmodified
        val original = pendingDao.getByBusinessTransactionId(txId)
        assertEquals("PENDING", original?.status)
        assertNull(original?.verificationEvidence)
    }

    @Test
    fun testDuplicateManualVerification_idempotenceCheck() = runBlocking {
        val txId = "tx_" + UUID.randomUUID()
        val intentId = "intent_" + UUID.randomUUID()
        val accountId = "acc_03"
        val amount = 30000L

        val account = LocalAccount(
            id = accountId,
            displayName = "Idempotence Account"
        )
        accountDao.insert(account)

        val pending = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = intentId,
            accountId = accountId,
            operationType = "RENEWAL",
            amountIqd = amount,
            status = "PENDING"
        )
        ledgerRepository.recordPendingOperation(pending)

        // First manual verification
        val evidence = "ISP transaction manual note 111"
        val firstResolution = ledgerRepository.submitManualVerificationEvidence(txId, evidence)
        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, firstResolution.result)

        val firstAccountState = accountDao.getByIdOneShot(accountId)
        assertEquals(amount.toDouble(), firstAccountState?.debtIqd ?: 0.0, 0.001)

        // Second manual verification (should return existing ledger, without duplicate debt)
        val secondResolution = ledgerRepository.submitManualVerificationEvidence(txId, evidence)
        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, secondResolution.result)
        assertEquals("Operation was already confirmed successful", secondResolution.diagnosticMessage)

        val secondAccountState = accountDao.getByIdOneShot(accountId)
        assertEquals(amount.toDouble(), secondAccountState?.debtIqd ?: 0.0, 0.001) // NO DUPLICATE CHARGE
    }

    @Test
    fun testMissingAccountTarget_preventsCompletion() = runBlocking {
        val txId = "tx_" + UUID.randomUUID()
        val intentId = "intent_" + UUID.randomUUID()
        val missingAccountId = "missing_acc_999"

        val pending = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = intentId,
            accountId = missingAccountId,
            operationType = "ACTIVATION",
            amountIqd = 40000L,
            status = "PENDING"
        )
        ledgerRepository.recordPendingOperation(pending)

        try {
            ledgerRepository.submitManualVerificationEvidence(txId, "Valid Evidence String")
            fail("Should throw IllegalStateException because account is missing")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("MISSING_LOCAL_FINANCIAL_TARGET"))
        }

        // Verify the status was NOT changed to COMPLETED
        val retrieved = pendingDao.getByBusinessTransactionId(txId)
        assertNotEquals("COMPLETED", retrieved?.status)
    }
}
