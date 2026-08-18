package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.database.AuditLogDao
import com.example.core.database.ImportBatchDao
import com.example.core.database.LocalAccountDao
import com.example.core.database.LocalLedgerEntryDao
import com.example.core.database.PendingExternalOperationDao
import com.example.core.database.SyncMetadataDao
import com.example.core.database.SyncOutboxDao
import com.example.core.model.AuditLog
import com.example.core.model.DivergentPayloadConflictException
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.model.PendingExternalOperation
import com.example.core.sync.EventSyncResult
import com.example.core.sync.RemoteEvent
import com.example.core.sync.RemoteEventSource
import com.example.core.sync.RemoteSyncCoordinator
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.LocalLedgerRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1 Same-ID Divergent-Payload Immutability Protection Test Suite (INV-01 / INV-11 / P1-11).
 *
 * Verifies that:
 * 1. Financial transaction IDs are permanently immutable once committed.
 * 2. Same ID + IDENTICAL payload -> Idempotent no-op (safe retry / convergence without duplicate rows or mutated balances).
 * 3. Same ID + DIVERGENT payload (different amount, different account ID, or different transaction type):
 *    - In LocalLedgerRepository write path: strictly fails closed with [DivergentPayloadConflictException], protecting the original committed record.
 *    - In RemoteSyncCoordinator inbound sync path: quarantined with [EventSyncResult.QUARANTINED_CONFLICT] and audit log, preserving local source of truth.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase1SameIdDivergentPayloadTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var accountDao: LocalAccountDao
    private lateinit var ledgerDao: LocalLedgerEntryDao
    private lateinit var outboxDao: SyncOutboxDao
    private lateinit var pendingDao: PendingExternalOperationDao
    private lateinit var batchDao: ImportBatchDao
    private lateinit var metadataDao: SyncMetadataDao
    private lateinit var auditDao: AuditLogDao
    private lateinit var ledgerRepository: LocalLedgerRepository
    private lateinit var remoteSyncCoordinator: RemoteSyncCoordinator

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
        batchDao = db.importBatchDao()
        metadataDao = db.syncMetadataDao()
        auditDao = db.auditLogDao()

        ledgerRepository = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = ledgerDao,
            accountDao = accountDao,
            outboxDao = outboxDao,
            pendingDao = pendingDao
        )

        remoteSyncCoordinator = RemoteSyncCoordinator(
            appDatabase = db,
            accountDao = accountDao,
            ledgerDao = ledgerDao,
            batchDao = batchDao,
            outboxDao = outboxDao,
            metadataDao = metadataDao,
            auditDao = auditDao
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
            displayName = displayName,
            debtIqd = debtIqd,
            advanceIqd = advanceIqd,
            currentPriceIqd = currentPriceIqd,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        accountDao.insert(account)
        return account
    }

    // ---------------------------------------------------------------------------------------------
    // Local Ledger Repository Write Path Tests
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testLocalLedger_sameId_identicalPayment_isIdempotentNoOp() = runBlocking {
        val account = createTestAccount("acc_p1", "Payment Account", debtIqd = 50000.0)
        val txId = "tx_pay_idempotent_1"

        val firstEntry = ledgerRepository.addPayment(account.id, 50000.0, "Cash Payment", txId)
        assertEquals(txId, firstEntry.id)
        assertEquals(50000.0, firstEntry.amountIqd, 0.001)
        assertEquals("gave", firstEntry.typeRaw)

        val updatedAcc = accountDao.getByIdOneShot(account.id)
        assertNotNull(updatedAcc)
        assertEquals(0.0, updatedAcc!!.debtIqd, 0.001)
        assertEquals(1, ledgerDao.getTotalCount())

        // Replay exact same payment with identical payload
        val replayedEntry = ledgerRepository.addPayment(account.id, 50000.0, "Cash Payment", txId)
        assertEquals(firstEntry.id, replayedEntry.id)
        assertEquals(firstEntry.amountIqd, replayedEntry.amountIqd, 0.001)

        // Balances and ledger count must remain completely unchanged
        val accAfterReplay = accountDao.getByIdOneShot(account.id)
        assertNotNull(accAfterReplay)
        assertEquals(0.0, accAfterReplay!!.debtIqd, 0.001)
        assertEquals(1, ledgerDao.getTotalCount())
    }

    @Test
    fun testLocalLedger_sameId_identicalDebt_isIdempotentNoOp() = runBlocking {
        val account = createTestAccount("acc_d1", "Debt Account", debtIqd = 0.0)
        val txId = "tx_debt_idempotent_1"

        val firstEntry = ledgerRepository.addDebt(account.id, 40000.0, "Monthly Charge", txId)
        assertEquals(txId, firstEntry.id)
        assertEquals(40000.0, firstEntry.amountIqd, 0.001)
        assertEquals("took", firstEntry.typeRaw)

        val updatedAcc = accountDao.getByIdOneShot(account.id)
        assertNotNull(updatedAcc)
        assertEquals(40000.0, updatedAcc!!.debtIqd, 0.001)
        assertEquals(1, ledgerDao.getTotalCount())

        // Replay exact same debt with identical payload
        val replayedEntry = ledgerRepository.addDebt(account.id, 40000.0, "Monthly Charge", txId)
        assertEquals(firstEntry.id, replayedEntry.id)

        // Balance must not double
        val accAfterReplay = accountDao.getByIdOneShot(account.id)
        assertNotNull(accAfterReplay)
        assertEquals(40000.0, accAfterReplay!!.debtIqd, 0.001)
        assertEquals(1, ledgerDao.getTotalCount())
    }

    @Test
    fun testLocalLedger_sameId_divergentAmountPayment_failsClosedWithException() = runBlocking {
        val account = createTestAccount("acc_p2", "Payment Divergent Amount", debtIqd = 100000.0)
        val txId = "tx_pay_divergent_amt"

        val firstEntry = ledgerRepository.addPayment(account.id, 50000.0, "First Payment", txId)
        assertEquals(50000.0, firstEntry.amountIqd, 0.001)

        val accAfterFirst = accountDao.getByIdOneShot(account.id)
        assertNotNull(accAfterFirst)
        assertEquals(50000.0, accAfterFirst!!.debtIqd, 0.001)

        // Attempt same ID with divergent amount (75,000 IQD)
        try {
            ledgerRepository.addPayment(account.id, 75000.0, "Tampered Payment", txId)
            fail("Expected DivergentPayloadConflictException for divergent payment amount")
        } catch (e: DivergentPayloadConflictException) {
            assertTrue(e.message!!.contains("Same-ID divergent payload conflict"))
            assertTrue(e.message!!.contains("50000.0"))
            assertTrue(e.message!!.contains("75000.0"))
        }

        // Verify local state was preserved without corruption
        val accAfterConflict = accountDao.getByIdOneShot(account.id)
        assertNotNull(accAfterConflict)
        assertEquals(50000.0, accAfterConflict!!.debtIqd, 0.001)
        val savedEntry = ledgerDao.getByIdOneShot(txId)
        assertNotNull(savedEntry)
        assertEquals(50000.0, savedEntry!!.amountIqd, 0.001)
        assertEquals(1, ledgerDao.getTotalCount())
    }

    @Test
    fun testLocalLedger_sameId_divergentAmountDebt_failsClosedWithException() = runBlocking {
        val account = createTestAccount("acc_d2", "Debt Divergent Amount", debtIqd = 0.0)
        val txId = "tx_debt_divergent_amt"

        val firstEntry = ledgerRepository.addDebt(account.id, 40000.0, "Standard Charge", txId)
        assertEquals(40000.0, firstEntry.amountIqd, 0.001)

        // Attempt same ID with divergent amount (60,000 IQD)
        try {
            ledgerRepository.addDebt(account.id, 60000.0, "Tampered Charge", txId)
            fail("Expected DivergentPayloadConflictException for divergent debt amount")
        } catch (e: DivergentPayloadConflictException) {
            assertTrue(e.message!!.contains("Same-ID divergent payload conflict"))
        }

        val accAfterConflict = accountDao.getByIdOneShot(account.id)
        assertNotNull(accAfterConflict)
        assertEquals(40000.0, accAfterConflict!!.debtIqd, 0.001)
        val savedEntry = ledgerDao.getByIdOneShot(txId)
        assertNotNull(savedEntry)
        assertEquals(40000.0, savedEntry!!.amountIqd, 0.001)
    }

    @Test
    fun testLocalLedger_sameId_divergentAccount_failsClosedWithException() = runBlocking {
        val acc1 = createTestAccount("acc_owner_1", "Account 1", debtIqd = 0.0)
        val acc2 = createTestAccount("acc_owner_2", "Account 2", debtIqd = 0.0)
        val txId = "tx_cross_account_collision"

        ledgerRepository.addDebt(acc1.id, 45000.0, "Charge for Acc1", txId)

        // Attempt to reuse txId for acc2
        try {
            ledgerRepository.addDebt(acc2.id, 45000.0, "Charge for Acc2", txId)
            fail("Expected DivergentPayloadConflictException for cross-account collision")
        } catch (e: DivergentPayloadConflictException) {
            assertTrue(e.message!!.contains("Same-ID divergent payload conflict"))
        }

        // Acc1 preserves original entry, Acc2 has 0 debt and 0 entries
        val acc1Db = accountDao.getByIdOneShot(acc1.id)
        val acc2Db = accountDao.getByIdOneShot(acc2.id)
        assertEquals(45000.0, acc1Db!!.debtIqd, 0.001)
        assertEquals(0.0, acc2Db!!.debtIqd, 0.001)
        assertEquals(1, ledgerDao.getTotalCount())
    }

    @Test
    fun testLocalLedger_sameId_divergentTransactionType_failsClosedWithException() = runBlocking {
        val account = createTestAccount("acc_type_1", "Type Collision Account", debtIqd = 100000.0)
        val txId = "tx_type_collision"

        // First recorded as debt ("took")
        ledgerRepository.addDebt(account.id, 30000.0, "Debt entry", txId)
        val accAfterDebt = accountDao.getByIdOneShot(account.id)
        assertEquals(130000.0, accAfterDebt!!.debtIqd, 0.001)

        // Attempt same ID as payment ("gave")
        try {
            ledgerRepository.addPayment(account.id, 30000.0, "Attempted payment collision", txId)
            fail("Expected DivergentPayloadConflictException for type collision (debt vs payment)")
        } catch (e: DivergentPayloadConflictException) {
            assertTrue(e.message!!.contains("Same-ID divergent payload conflict"))
        }

        // Preserved as debt
        val savedEntry = ledgerDao.getByIdOneShot(txId)
        assertNotNull(savedEntry)
        assertEquals("took", savedEntry!!.typeRaw)
        val finalAcc = accountDao.getByIdOneShot(account.id)
        assertEquals(130000.0, finalAcc!!.debtIqd, 0.001)
    }

    @Test
    fun testLocalLedger_recordAccountRenewal_sameId_identicalReplay_isIdempotent() = runBlocking {
        val account = createTestAccount("acc_renew_1", "Renewal Account", debtIqd = 0.0, currentPriceIqd = 45000.0)
        val renewKey = "renew_idempotent_key_1"

        val entry = ledgerRepository.recordAccountRenewal(
            account = account,
            newPriceIqd = 45000.0,
            chargeNote = "Renewal Charge",
            payNote = null,
            idempotencyKey = renewKey
        )
        assertNotNull(entry)
        assertEquals(45000.0, entry.amountIqd, 0.001)

        val accAfterFirst = accountDao.getByIdOneShot(account.id)
        assertEquals(45000.0, accAfterFirst!!.debtIqd, 0.001)
        assertEquals(1, ledgerDao.getTotalCount())

        // Replay same renewal
        val replayedEntry = ledgerRepository.recordAccountRenewal(
            account = account,
            newPriceIqd = 45000.0,
            chargeNote = "Renewal Charge",
            payNote = null,
            idempotencyKey = renewKey
        )
        assertEquals(entry.id, replayedEntry.id)

        // Balance not doubled
        val accAfterReplay = accountDao.getByIdOneShot(account.id)
        assertEquals(45000.0, accAfterReplay!!.debtIqd, 0.001)
        assertEquals(1, ledgerDao.getTotalCount())
    }

    @Test
    fun testLocalLedger_recordAccountRenewal_sameId_divergentAmount_failsClosed() = runBlocking {
        val account = createTestAccount("acc_renew_2", "Renewal Divergent Account", debtIqd = 0.0, currentPriceIqd = 40000.0)
        val renewKey = "renew_divergent_key_2"

        ledgerRepository.recordAccountRenewal(
            account = account,
            newPriceIqd = 40000.0,
            chargeNote = "Renewal 40k",
            payNote = null,
            idempotencyKey = renewKey
        )

        // Attempt same renewal key with 60k
        try {
            ledgerRepository.recordAccountRenewal(
                account = account,
                newPriceIqd = 60000.0,
                chargeNote = "Renewal 60k",
                payNote = null,
                idempotencyKey = renewKey
            )
            fail("Expected DivergentPayloadConflictException on divergent renewal price")
        } catch (e: DivergentPayloadConflictException) {
            assertTrue(e.message!!.contains("Same-ID divergent payload conflict"))
        }

        val accDb = accountDao.getByIdOneShot(account.id)
        assertEquals(40000.0, accDb!!.debtIqd, 0.001)
    }

    @Test
    fun testPendingOperation_sameId_divergentAmount_failsClosed() = runBlocking {
        val account = createTestAccount("acc_pend_1", "Pending Op Account", debtIqd = 0.0, currentPriceIqd = 40000.0)
        val txId = "tx_pending_verified_1"

        val op = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = "intent_1",
            accountId = account.id,
            operationType = "RENEWAL",
            amountIqd = 40000L,
            status = "PENDING"
        )
        pendingDao.insert(op)

        // Manually insert divergent local ledger entry with same ID but 55,000 IQD
        val divergentEntry = LocalLedgerEntry(
            id = txId,
            accountId = account.id,
            typeRaw = "took",
            amountIqd = 55000.0,
            debtAfterIqd = 55000.0,
            note = "Pre-existing Divergent Entry"
        )
        ledgerDao.insert(divergentEntry)

        try {
            ledgerRepository.resolvePendingOperationVerifiedSuccess(txId)
            fail("Expected DivergentPayloadConflictException for verified pending operation with divergent local ledger entry")
        } catch (e: DivergentPayloadConflictException) {
            assertTrue(e.message!!.contains("Same-ID divergent payload conflict"))
        }

        // Original 55k entry preserved
        val saved = ledgerDao.getByIdOneShot(txId)
        assertEquals(55000.0, saved!!.amountIqd, 0.001)
    }

    // ---------------------------------------------------------------------------------------------
    // Inbound Remote Sync Coordinator Tests
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testInboundSync_sameId_identicalLedger_appliesCleanly() = runBlocking {
        val account = createTestAccount("acc_sync_1", "Sync Account 1", debtIqd = 50000.0)
        val txId = "tx_remote_sync_1"

        // Existing local entry
        val localEntry = LocalLedgerEntry(
            id = txId,
            accountId = account.id,
            typeRaw = "took",
            amountIqd = 50000.0,
            debtAfterIqd = 50000.0,
            note = "Identical Remote Test"
        )
        ledgerDao.insert(localEntry)
        metadataDao.put("remote_version:ledger:$txId", "1000")

        // Incoming remote event with exact same business fields
        val remoteEntry = LocalLedgerEntry(
            id = txId,
            accountId = account.id,
            typeRaw = "took",
            amountIqd = 50000.0,
            debtAfterIqd = 50000.0,
            note = "Identical Remote Test"
        )
        val event = RemoteEvent.LedgerUpsert(
            entityId = txId,
            remoteVersion = 2000L,
            source = RemoteEventSource.PULL,
            entry = remoteEntry
        )

        val result = remoteSyncCoordinator.processEvent(event)
        assertEquals(EventSyncResult.APPLIED, result)

        val accDb = accountDao.getByIdOneShot(account.id)
        assertEquals(50000.0, accDb!!.debtIqd, 0.001)
        val ledgerDb = ledgerDao.getByIdOneShot(txId)
        assertEquals(50000.0, ledgerDb!!.amountIqd, 0.001)
        assertEquals("2000", metadataDao.get("remote_version:ledger:$txId"))
    }

    @Test
    fun testInboundSync_sameId_divergentAmount_quarantinesConflictAndPreservesLocalTruth() = runBlocking {
        val account = createTestAccount("acc_sync_2", "Sync Account 2", debtIqd = 50000.0)
        val txId = "tx_remote_sync_divergent_amt"

        // Existing committed local truth (50,000 IQD)
        val localEntry = LocalLedgerEntry(
            id = txId,
            accountId = account.id,
            typeRaw = "took",
            amountIqd = 50000.0,
            debtAfterIqd = 50000.0,
            note = "Committed Local Entry"
        )
        ledgerDao.insert(localEntry)
        metadataDao.put("remote_version:ledger:$txId", "1000")

        // Incoming remote payload with tampered / divergent amount (90,000 IQD)
        val remoteEntry = LocalLedgerEntry(
            id = txId,
            accountId = account.id,
            typeRaw = "took",
            amountIqd = 90000.0,
            debtAfterIqd = 90000.0,
            note = "Tampered Remote Payload"
        )
        val event = RemoteEvent.LedgerUpsert(
            entityId = txId,
            remoteVersion = 3000L,
            source = RemoteEventSource.PULL,
            entry = remoteEntry
        )

        val result = remoteSyncCoordinator.processEvent(event)
        assertEquals(EventSyncResult.QUARANTINED_CONFLICT, result)

        // Local truth must remain untouched: amount is still 50,000 IQD, debt balance is still 50,000 IQD
        val ledgerDb = ledgerDao.getByIdOneShot(txId)
        assertNotNull(ledgerDb)
        assertEquals(50000.0, ledgerDb!!.amountIqd, 0.001)
        assertEquals("Committed Local Entry", ledgerDb.note)

        val accDb = accountDao.getByIdOneShot(account.id)
        assertEquals(50000.0, accDb!!.debtIqd, 0.001)

        // Verify quarantine audit log
        val auditLogs = auditDao.getAllSync()
        assertTrue(auditLogs.any { it.action == "QUARANTINE_IDENTITY_CONFLICT" && it.entityId == txId })
    }

    @Test
    fun testInboundSync_sameId_divergentAccount_quarantinesConflictAndPreservesLocalTruth() = runBlocking {
        val acc1 = createTestAccount("acc_sync_acc1", "Account 1", debtIqd = 35000.0)
        val acc2 = createTestAccount("acc_sync_acc2", "Account 2", debtIqd = 0.0)
        val txId = "tx_remote_sync_divergent_acc"

        val localEntry = LocalLedgerEntry(
            id = txId,
            accountId = acc1.id,
            typeRaw = "gave",
            amountIqd = 35000.0,
            debtAfterIqd = 0.0,
            note = "Payment on Acc1"
        )
        ledgerDao.insert(localEntry)

        // Incoming remote event re-assigning txId to acc2
        val remoteEntry = LocalLedgerEntry(
            id = txId,
            accountId = acc2.id,
            typeRaw = "gave",
            amountIqd = 35000.0,
            debtAfterIqd = 0.0,
            note = "Payment re-assigned to Acc2"
        )
        val event = RemoteEvent.LedgerUpsert(
            entityId = txId,
            remoteVersion = 2000L,
            source = RemoteEventSource.REALTIME,
            entry = remoteEntry
        )

        val result = remoteSyncCoordinator.processEvent(event)
        assertEquals(EventSyncResult.QUARANTINED_CONFLICT, result)

        // Entry remains on Acc1
        val ledgerDb = ledgerDao.getByIdOneShot(txId)
        assertEquals(acc1.id, ledgerDb!!.accountId)
        val acc2Ledgers = ledgerDao.getByAccountIdOneShot(acc2.id)
        assertTrue(acc2Ledgers.isEmpty())
    }

    @Test
    fun testInboundSync_sameId_divergentType_quarantinesConflictAndPreservesLocalTruth() = runBlocking {
        val account = createTestAccount("acc_sync_type", "Type Test Account", debtIqd = 45000.0)
        val txId = "tx_remote_sync_divergent_type"

        val localEntry = LocalLedgerEntry(
            id = txId,
            accountId = account.id,
            typeRaw = "took",
            amountIqd = 45000.0,
            debtAfterIqd = 45000.0,
            note = "Debt entry"
        )
        ledgerDao.insert(localEntry)

        // Incoming remote payload claiming same ID is payment ("gave")
        val remoteEntry = LocalLedgerEntry(
            id = txId,
            accountId = account.id,
            typeRaw = "gave",
            amountIqd = 45000.0,
            debtAfterIqd = 0.0,
            note = "Payment entry"
        )
        val event = RemoteEvent.LedgerUpsert(
            entityId = txId,
            remoteVersion = 2000L,
            source = RemoteEventSource.PULL,
            entry = remoteEntry
        )

        val result = remoteSyncCoordinator.processEvent(event)
        assertEquals(EventSyncResult.QUARANTINED_CONFLICT, result)

        val ledgerDb = ledgerDao.getByIdOneShot(txId)
        assertEquals("took", ledgerDb!!.typeRaw)
        val accDb = accountDao.getByIdOneShot(account.id)
        assertEquals(45000.0, accDb!!.debtIqd, 0.001)
    }
}
