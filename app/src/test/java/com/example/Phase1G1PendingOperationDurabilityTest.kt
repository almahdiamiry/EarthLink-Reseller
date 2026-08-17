package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.database.PendingExternalOperationDao
import com.example.core.database.LocalAccountDao
import com.example.core.database.LocalLedgerEntryDao
import com.example.core.database.SyncOutboxDao
import com.example.core.model.LocalAccount
import com.example.core.model.PendingExternalOperation
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
import java.io.File
import java.util.UUID

/**
 * Phase 1 G1 Durable Pending Operation & Call-Path Durability Test Suite (INV-11 / G1).
 *
 * Verifies that:
 * 1. Financial ISP operations (Activation, Renewal, Refill) write a durable PendingExternalOperation
 *    prior to external network dispatch.
 * 2. Process crashes or interruptions leave the pending record intact in SQLite for recovery.
 * 3. Confirmed external success atomically commits ledger entries, account balance updates, outbox
 *    obligations, and pending status resolution in Room.
 * 4. Duplicate submissions with identical Operation Intent IDs are suppressed idempotently.
 * 5. Pending operations survive full database close/reopen cycles across all canonical operation types.
 * 6. External failure marks the pending operation FAILED with diagnostic error without ledger mutations.
 * 7. Renewal/Extension operations are normalized to canonical RENEWAL category under G1 boundaries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase1G1PendingOperationDurabilityTest {

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

    // 1. Pending operation is written before external API call
    @Test
    fun test1_pendingOperationWrittenBeforeExternalCall(): Unit = runBlocking {
        val intentId = UUID.randomUUID().toString()
        val txId = "tx_" + UUID.randomUUID().toString()
        val accountId = "acc_g1_01"

        val op = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = intentId,
            accountId = accountId,
            operationType = "REFILL",
            amountIqd = 45000L,
            payloadJson = """{"userId":"$accountId","price":45000}""",
            status = "PENDING"
        )

        val savedOp = ledgerRepository.recordPendingOperation(op)
        assertEquals(txId, savedOp.businessTransactionId)
        assertEquals("PENDING", savedOp.status)

        // Verify stored in DB
        val retrieved = pendingDao.getByBusinessTransactionId(txId)
        assertNotNull("Pending operation must be durably stored", retrieved)
        assertEquals("PENDING", retrieved?.status)
        assertEquals(intentId, retrieved?.operationIntentId)
        assertEquals(45000L, retrieved?.amountIqd)

        // Verify no ledger entries or outbox entries exist before external success
        val ledgers = ledgerDao.getByAccountIdOneShot(accountId)
        assertTrue("No ledger entries should exist prior to external confirmation", ledgers.isEmpty())
        val outbox = outboxDao.getByEntity(txId, "local_ledger_entries")
        assertTrue("No outbox records should exist prior to external confirmation", outbox.isEmpty())
    }

    // 2. Interruption between external call and local record leaves pending record intact
    @Test
    fun test2_interruptionAfterExternalCallLeavesPendingRecordIntact(): Unit = runBlocking {
        val dbName = "g1_crash_test_db"
        AppDatabase.closeAndRemoveInstance(dbName)
        context.deleteDatabase(dbName)
        context.getDatabasePath(dbName).parentFile?.mkdirs()
        val diskDb = AppDatabase.getDatabase(context, ByteArray(0), dbName)

        val intentId = UUID.randomUUID().toString()
        val txId = "tx_crash_01"
        val pendingOp = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = intentId,
            accountId = "acc_crash_user",
            operationType = "RENEWAL",
            amountIqd = 35000L,
            payloadJson = """{"userId":"acc_crash_user"}""",
            status = "PENDING"
        )

        diskDb.pendingExternalOperationDao().insert(pendingOp)
        val initialCheck = diskDb.pendingExternalOperationDao().getByBusinessTransactionId(txId)
        assertNotNull(initialCheck)

        // Simulate crash by closing and removing instance
        AppDatabase.closeAndRemoveInstance(dbName)

        // Reopen database to simulate app recovery
        val recoveredDb = AppDatabase.getDatabase(context, ByteArray(0), dbName)

        val pendingList = recoveredDb.pendingExternalOperationDao().getPendingOperations()
        assertEquals(1, pendingList.size)
        assertEquals(txId, pendingList[0].businessTransactionId)
        assertEquals("PENDING", pendingList[0].status)
        assertEquals("acc_crash_user", pendingList[0].accountId)

        AppDatabase.closeAndRemoveInstance(dbName)
        context.deleteDatabase(dbName)
        Unit
    }

    // 3. Successful completion commits ledger, account, outbox, and pending status atomically
    @Test
    fun test3_successfulCompletionCommitsLedgerAccountOutboxPendingAtomically(): Unit = runBlocking {
        val account = LocalAccount(
            id = "acc_atomic_01",
            displayName = "Atomic Test User",
            earthlinkUsername = "atomic_user",
            currentPriceIqd = 40000.0,
            debtIqd = 0.0
        )
        accountDao.insert(account)

        val intentId = UUID.randomUUID().toString()
        val txId = "tx_atomic_01"
        val pendingOp = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = intentId,
            accountId = account.id,
            operationType = "RENEWAL",
            amountIqd = 40000L,
            payloadJson = """{"userId":"${account.id}","price":40000.0}""",
            status = "PENDING"
        )
        ledgerRepository.recordPendingOperation(pendingOp)

        // Execute atomic materialization
        val ledgerEntry = ledgerRepository.recordAccountRenewal(
            account = account,
            newPriceIqd = 40000.0,
            chargeNote = "[RENEW] Monthly package renewal",
            payNote = null,
            idempotencyKey = txId
        )

        // 1. Ledger entry committed with stable transaction ID
        assertNotNull(ledgerEntry)
        assertEquals(txId, ledgerEntry.id)
        assertEquals(40000.0, ledgerEntry.amountIqd, 0.001)

        // 2. LocalAccount debt balance updated
        val updatedAcc = accountDao.getByIdOneShot(account.id)
        assertNotNull(updatedAcc)
        assertEquals(40000.0, updatedAcc?.debtIqd ?: 0.0, 0.001)

        // 3. Outbox entries created for ledger and account
        val ledgerOutbox = outboxDao.getByEntity(txId, "local_ledger_entries")
        assertEquals(1, ledgerOutbox.size)
        assertEquals("pending", ledgerOutbox[0].status)

        val accountOutbox = outboxDao.getByEntity(account.id, "local_accounts")
        assertTrue("Account outbox record must exist", accountOutbox.isNotEmpty())

        // 4. Pending operation resolved to COMPLETED
        val resolvedPending = pendingDao.getByBusinessTransactionId(txId)
        assertEquals("COMPLETED", resolvedPending?.status)
    }

    // 4. Duplicate submission with same intent ID is suppressed idempotently
    @Test
    fun test4_duplicateSubmissionWithSameIntentIdSuppressedIdempotently(): Unit = runBlocking {
        val intentId = "intent_fixed_123"
        val txId = "tx_fixed_123"
        val account = LocalAccount(
            id = "acc_idempotent_01",
            displayName = "Idempotent User",
            currentPriceIqd = 50000.0
        )
        accountDao.insert(account)

        val op1 = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = intentId,
            accountId = account.id,
            operationType = "REFILL",
            amountIqd = 50000L,
            status = "PENDING"
        )
        val recorded1 = ledgerRepository.recordPendingOperation(op1)

        // Second duplicate submission attempt with same intent ID
        val op2 = PendingExternalOperation(
            businessTransactionId = "tx_different_attempt",
            operationIntentId = intentId,
            accountId = account.id,
            operationType = "REFILL",
            amountIqd = 50000L,
            status = "PENDING"
        )
        val recorded2 = ledgerRepository.recordPendingOperation(op2)

        // Must return original pending operation without inserting a duplicate
        assertEquals(txId, recorded2.businessTransactionId)
        val allPending = pendingDao.getAllOneShot()
        assertEquals(1, allPending.size)

        // Now complete the operation
        ledgerRepository.recordAccountRenewal(
            account = account,
            newPriceIqd = 50000.0,
            chargeNote = "[RENEW] Idempotent renewal",
            payNote = null,
            idempotencyKey = txId
        )

        // Second completion call with same transaction ID
        ledgerRepository.recordAccountRenewal(
            account = account,
            newPriceIqd = 50000.0,
            chargeNote = "[RENEW] Idempotent renewal replay",
            payNote = null,
            idempotencyKey = txId
        )

        // Verify ledger entries not duplicated and balance charged only once
        val ledgers = ledgerDao.getByAccountIdOneShot(account.id)
        assertEquals(1, ledgers.size)
        val finalAcc = accountDao.getByIdOneShot(account.id)
        assertEquals(50000.0, finalAcc?.debtIqd ?: 0.0, 0.001)
    }

    // 5. Operations survive full restart and disk persistence across ACTIVATION, RENEWAL, REFILL
    @Test
    fun test5_operationsSurviveRestartAndDatabaseReopen(): Unit = runBlocking {
        val dbName = "g1_multi_op_test_db"
        AppDatabase.closeAndRemoveInstance(dbName)
        context.deleteDatabase(dbName)
        context.getDatabasePath(dbName).parentFile?.mkdirs()
        val diskDb = AppDatabase.getDatabase(context, ByteArray(0), dbName)
        val dao = diskDb.pendingExternalOperationDao()

        val ops = listOf(
            PendingExternalOperation(
                businessTransactionId = "tx_act_01",
                operationIntentId = "intent_act_01",
                accountId = "user_act",
                operationType = "ACTIVATION",
                amountIqd = 0L,
                payloadJson = """{"username":"user_act","pkg":1}"""
            ),
            PendingExternalOperation(
                businessTransactionId = "tx_ren_01",
                operationIntentId = "intent_ren_01",
                accountId = "user_ren",
                operationType = "RENEWAL",
                amountIqd = 30000L,
                payloadJson = """{"userId":"user_ren","price":30000}"""
            ),
            PendingExternalOperation(
                businessTransactionId = "tx_ref_01",
                operationIntentId = "intent_ref_01",
                accountId = "user_ref",
                operationType = "REFILL",
                amountIqd = 45000L,
                payloadJson = """{"userId":"user_ref","price":45000}"""
            )
        )

        ops.forEach { dao.insert(it) }
        AppDatabase.closeAndRemoveInstance(dbName)

        // Reopen database
        val reopenedDb = AppDatabase.getDatabase(context, ByteArray(0), dbName)
        val reopenedDao = reopenedDb.pendingExternalOperationDao()

        val allRetrieved = reopenedDao.getAllOneShot()
        assertEquals(3, allRetrieved.size)

        val actOp = reopenedDao.getByBusinessTransactionId("tx_act_01")
        assertNotNull(actOp)
        assertEquals("ACTIVATION", actOp?.operationType)
        assertEquals("intent_act_01", actOp?.operationIntentId)

        val renOp = reopenedDao.getByBusinessTransactionId("tx_ren_01")
        assertNotNull(renOp)
        assertEquals("RENEWAL", renOp?.operationType)
        assertEquals(30000L, renOp?.amountIqd)

        val refOp = reopenedDao.getByBusinessTransactionId("tx_ref_01")
        assertNotNull(refOp)
        assertEquals("REFILL", refOp?.operationType)
        assertEquals(45000L, refOp?.amountIqd)

        AppDatabase.closeAndRemoveInstance(dbName)
        context.deleteDatabase(dbName)
        Unit
    }

    // 6. Failed external call marks pending operation failed without ledger mutation
    @Test
    fun test6_failedExternalCallMarksPendingOperationFailed(): Unit = runBlocking {
        val txId = "tx_failed_01"
        val intentId = "intent_failed_01"
        val accountId = "acc_fail_user"

        val op = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = intentId,
            accountId = accountId,
            operationType = "REFILL",
            amountIqd = 35000L,
            status = "PENDING"
        )
        ledgerRepository.recordPendingOperation(op)

        // Mark failed after ISP error
        val errorMsg = "HTTP 400: Insufficient reseller deposit balance"
        ledgerRepository.markPendingOperationFailed(txId, errorMsg)

        val failedOp = pendingDao.getByBusinessTransactionId(txId)
        assertNotNull(failedOp)
        assertEquals("FAILED", failedOp?.status)
        assertEquals(errorMsg, failedOp?.lastError)

        // Ensure zero ledger or outbox entries created
        val ledgers = ledgerDao.getByAccountIdOneShot(accountId)
        assertTrue("No ledger entries on failure", ledgers.isEmpty())
        val outbox = outboxDao.getByEntity(txId, "local_ledger_entries")
        assertTrue("No outbox records on failure", outbox.isEmpty())
    }

    // 7. Renewal / Extension normalization maintains single canonical authority
    @Test
    fun test7_renewalExtensionOperationNormalization(): Unit = runBlocking {
        val account = LocalAccount(
            id = "acc_ext_01",
            displayName = "Extension User",
            currentPriceIqd = 35000.0,
            debtIqd = 0.0
        )
        accountDao.insert(account)

        val txId = "tx_ext_01"
        val op = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = "intent_ext_01",
            accountId = account.id,
            operationType = "RENEWAL", // Canonical operation category for Renewal/Extension
            amountIqd = 35000L,
            payloadJson = """{"userId":"${account.id}","userIndex":101}""",
            status = "PENDING"
        )
        ledgerRepository.recordPendingOperation(op)

        // Complete extension under RENEWAL canonical authority
        val entry = ledgerRepository.recordAccountRenewal(
            account = account,
            newPriceIqd = 35000.0,
            chargeNote = "[EXTENSION] Subscriber duration extended",
            payNote = "[EXTENSION_PAID] Immediate payment",
            idempotencyKey = txId
        )

        assertNotNull(entry)
        val ledgers = ledgerDao.getByAccountIdOneShot(account.id)
        assertEquals(2, ledgers.size) // 1 charge + 1 immediate payment

        val pending = pendingDao.getByBusinessTransactionId(txId)
        assertEquals("COMPLETED", pending?.status)
    }
}
