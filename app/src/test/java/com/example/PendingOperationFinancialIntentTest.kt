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
class PendingOperationFinancialIntentTest {

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
    fun testActivation_exactSubscriberChargePersistedBeforeDispatch() = runBlocking {
        val txId = "tx_act_" + UUID.randomUUID()
        val intentId = "intent_act_" + UUID.randomUUID()
        val accountId = "acc_act_01"
        val exactAmount = 45000L

        val pending = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = intentId,
            accountId = accountId,
            operationType = "ACTIVATION",
            amountIqd = exactAmount,
            payloadJson = """{"username":"$accountId","price":$exactAmount}""",
            status = "PENDING"
        )

        val saved = ledgerRepository.recordPendingOperation(pending)
        assertEquals(exactAmount, saved.amountIqd)
        assertEquals("ACTIVATION", saved.operationType)

        val retrieved = pendingDao.getByBusinessTransactionId(txId)
        assertNotNull(retrieved)
        assertEquals(exactAmount, retrieved?.amountIqd)
        assertEquals(accountId, retrieved?.accountId)
    }

    @Test
    fun testRenewalAndRefill_exactChargePersistedBeforeDispatch() = runBlocking {
        val exactRenewalAmount = 35000L
        val exactRefillAmount = 50000L

        val opRenewal = PendingExternalOperation(
            businessTransactionId = "tx_ren_" + UUID.randomUUID(),
            operationIntentId = "intent_ren_" + UUID.randomUUID(),
            accountId = "acc_ren_01",
            operationType = "RENEWAL",
            amountIqd = exactRenewalAmount,
            status = "PENDING"
        )
        val savedRenewal = ledgerRepository.recordPendingOperation(opRenewal)
        assertEquals(exactRenewalAmount, savedRenewal.amountIqd)

        val opRefill = PendingExternalOperation(
            businessTransactionId = "tx_ref_" + UUID.randomUUID(),
            operationIntentId = "intent_ref_" + UUID.randomUUID(),
            accountId = "acc_ref_01",
            operationType = "REFILL",
            amountIqd = exactRefillAmount,
            status = "PENDING"
        )
        val savedRefill = ledgerRepository.recordPendingOperation(opRefill)
        assertEquals(exactRefillAmount, savedRefill.amountIqd)
    }

    @Test
    fun testRecovery_usesPersistedAmountNotPackagePricing() = runBlocking {
        val txId = "tx_rec_" + UUID.randomUUID()
        val accountId = "acc_rec_01"
        val persistedAmount = 55000L
        val differentPackagePrice = 30000.0

        // Create account with a different current price
        val account = LocalAccount(
            id = accountId,
            displayName = "Test Recovery",
            currentPriceIqd = differentPackagePrice,
            debtIqd = 0.0
        )
        accountDao.insert(account)

        val pending = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = "intent_rec_" + UUID.randomUUID(),
            accountId = accountId,
            operationType = "RENEWAL",
            amountIqd = persistedAmount,
            status = "PENDING"
        )
        ledgerRepository.recordPendingOperation(pending)

        // Resolve verified success
        val ledger = ledgerRepository.resolvePendingOperationVerifiedSuccess(txId, "[VERIFIED RENEW]")
        assertNotNull(ledger)
        assertEquals("Ledger entry MUST use persisted amount, not package price", persistedAmount.toDouble(), ledger?.amountIqd ?: 0.0, 0.001)
    }

    @Test
    fun testResolvePendingOperation_rejectsMissingPersistedAmount() = runBlocking {
        val txId = "tx_no_amt_" + UUID.randomUUID()
        val accountId = "acc_no_amt_01"

        val account = LocalAccount(
            id = accountId,
            displayName = "No Amount Test",
            currentPriceIqd = 40000.0
        )
        accountDao.insert(account)

        val pendingWithZeroAmount = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = "intent_no_amt_" + UUID.randomUUID(),
            accountId = accountId,
            operationType = "REFILL",
            amountIqd = 0L, // Invalid/absent exact amount
            status = "PENDING"
        )
        ledgerRepository.recordPendingOperation(pendingWithZeroAmount)

        try {
            ledgerRepository.resolvePendingOperationVerifiedSuccess(txId)
            fail("Should throw IllegalStateException when persisted amount is absent/zero")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("MISSING_PERSISTED_FINANCIAL_AMOUNT") == true)
        }
    }

    @Test
    fun testStableIdentityReusedThroughRecoveryAndMaterialization() = runBlocking {
        val txId = "tx_stable_" + UUID.randomUUID()
        val intentId = "intent_stable_" + UUID.randomUUID()
        val accountId = "acc_stable_01"
        val amount = 40000L

        val account = LocalAccount(
            id = accountId,
            displayName = "Stable Identity Test",
            currentPriceIqd = amount.toDouble()
        )
        accountDao.insert(account)

        val pending = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = intentId,
            accountId = accountId,
            operationType = "ACTIVATION",
            amountIqd = amount,
            status = "PENDING"
        )
        ledgerRepository.recordPendingOperation(pending)

        // Materialize
        val ledger = ledgerRepository.resolvePendingOperationVerifiedSuccess(txId)
        assertNotNull(ledger)
        assertEquals("Ledger entry ID must be the stable businessTransactionId", txId, ledger?.id)

        // Outbox entry should also reuse txId
        val outbox = outboxDao.getByEntity(txId, "local_ledger_entries")
        assertFalse("Outbox entry should exist with stable txId", outbox.isEmpty())
    }

    @Test
    fun testSubmitManualVerificationEvidence_persistsEvidenceAndResolvesSuccess() = runBlocking {
        val txId = "tx_manual_ev_" + UUID.randomUUID()
        val intentId = "intent_manual_ev_" + UUID.randomUUID()
        val accountId = "acc_manual_ev_01"
        val amount = 40000L

        val account = LocalAccount(
            id = accountId,
            displayName = "Manual Evidence Test",
            currentPriceIqd = amount.toDouble()
        )
        accountDao.insert(account)

        val pending = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = intentId,
            accountId = accountId,
            operationType = "ACTIVATION",
            amountIqd = amount,
            status = "PENDING"
        )
        ledgerRepository.recordPendingOperation(pending)

        val evidence = "Confirmed by ISP ref #987654"
        val resolution = ledgerRepository.submitManualVerificationEvidence(txId, evidence)

        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, resolution.result)
        assertEquals("COMPLETED", resolution.operation.status)
        assertEquals(evidence, resolution.operation.verificationEvidence)

        val retrieved = pendingDao.getByBusinessTransactionId(txId)
        assertNotNull(retrieved)
        assertEquals("COMPLETED", retrieved?.status)
        assertEquals(evidence, retrieved?.verificationEvidence)
    }
}
