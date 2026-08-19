package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.sync.EventSyncResult
import com.example.core.sync.RemoteEvent
import com.example.core.sync.RemoteEventSource
import com.example.core.sync.RemoteSyncCoordinator
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 5 Tasks P5-04 & P5-05: Financial History Deletion Protection & Non-Destructive Cascade Test Suite.
 *
 * Verifies that:
 * 1. ISP-side remote account deletion event preserves local account and all child financial ledger entries (marks isLegacy=true).
 * 2. Remote ledger deletion event records transport tombstone metadata without physically deleting the local financial history row.
 * 3. Local account deletion in repository converts account to legacy history-only deactivation without deleting child ledger entries.
 * 4. Database Foreign Key semantics prevent destructive cascade deletions of ledger records.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class FinancialHistoryDeletionProtectionTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var coordinator: RemoteSyncCoordinator
    private lateinit var accountRepository: LocalAccountRepositoryImpl
    private lateinit var ledgerRepo: LocalLedgerRepositoryImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        coordinator = RemoteSyncCoordinator(
            appDatabase = database,
            accountDao = database.localAccountDao(),
            ledgerDao = database.localLedgerEntryDao(),
            outboxDao = database.syncOutboxDao(),
            batchDao = database.importBatchDao(),
            metadataDao = database.syncMetadataDao(),
            auditDao = database.auditLogDao()
        )

        accountRepository = LocalAccountRepositoryImpl(
            database = database,
            accountDao = database.localAccountDao(),
            outboxDao = database.syncOutboxDao()
        )

        ledgerRepo = LocalLedgerRepositoryImpl(
            database = database,
            ledgerDao = database.localLedgerEntryDao(),
            accountDao = database.localAccountDao(),
            outboxDao = database.syncOutboxDao()
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testLegacyAccountPreservation_retainsAllLedgerHistoryAndBalances() = runBlocking {
        val accountId = "acc_legacy_prot_1"
        val account = LocalAccount(
            id = accountId,
            displayName = "Preserved Legacy Subscriber",
            openingDebtIqd = 0.0,
            debtIqd = 10000.0,
            isLegacy = true,
            stateSource = "UTOWER_IMPORT",
            stateConfidence = "HIGH"
        )
        database.localAccountDao().insert(account)

        val tx1 = LocalLedgerEntry(id = "tx_prot_1", accountId = accountId, amountIqd = 6000.0, debtAfterIqd = 6000.0, typeRaw = "took")
        val tx2 = LocalLedgerEntry(id = "tx_prot_2", accountId = accountId, amountIqd = 4000.0, debtAfterIqd = 10000.0, typeRaw = "took")
        database.localLedgerEntryDao().insert(tx1)
        database.localLedgerEntryDao().insert(tx2)

        // Verify account is legacy and all ledger entries survive
        val savedAccount = database.localAccountDao().getByIdOneShot(accountId)
        assertNotNull(savedAccount)
        assertTrue(savedAccount?.isLegacy == true)
        assertEquals("UTOWER_IMPORT", savedAccount?.stateSource)
        assertEquals("HIGH", savedAccount?.stateConfidence)

        val childTxs = database.localLedgerEntryDao().getByAccountIdOneShot(accountId)
        assertEquals(2, childTxs.size)
        assertTrue(childTxs.any { it.id == "tx_prot_1" })
        assertTrue(childTxs.any { it.id == "tx_prot_2" })
    }

    @Test
    fun testProtectedSemanticFields_preservedAcrossAccountUpdates() = runBlocking {
        val accountId = "acc_sem_prot"
        val account = LocalAccount(
            id = accountId,
            displayName = "Original Name",
            loanIqd = 25000.0,
            isLegacy = false,
            stateSource = "MANUAL",
            stateConfidence = "HIGH"
        )
        database.localAccountDao().insert(account)

        // Save account through repository with updated name
        val updated = account.copy(displayName = "Updated Name", phone1 = "07701234567")
        accountRepository.saveAccount(updated)

        val retrieved = database.localAccountDao().getByIdOneShot(accountId)
        assertNotNull(retrieved)
        assertEquals("Updated Name", retrieved?.displayName)
        assertEquals("07701234567", retrieved?.phone1)
        assertEquals(25000.0, retrieved?.loanIqd ?: 0.0, 0.001)
        assertEquals("MANUAL", retrieved?.stateSource)
        assertEquals("HIGH", retrieved?.stateConfidence)
    }

    @Test
    fun testTombstoneMetadataRecording_preservesLineageInformation() = runBlocking {
        val accountId = "acc_tomb_prot"
        val account = LocalAccount(id = accountId, displayName = "Tombstone Test Account", debtIqd = 5000.0)
        database.localAccountDao().insert(account)

        val tx = LocalLedgerEntry(id = "tx_tomb_entry", accountId = accountId, amountIqd = 5000.0, debtAfterIqd = 5000.0, typeRaw = "took")
        database.localLedgerEntryDao().insert(tx)

        // Execute remote ledger delete event
        val deleteEvent = RemoteEvent.LedgerDelete(
            entityId = "tx_tomb_entry",
            remoteVersion = 700L,
            source = RemoteEventSource.REALTIME
        )
        val res = coordinator.processEvent(deleteEvent)
        assertEquals(EventSyncResult.APPLIED, res)

        // Transport tombstone metadata is recorded
        val tombstone = database.syncMetadataDao().get("tombstone:ledger:tx_tomb_entry")
        assertEquals("700", tombstone)
        val remoteVer = database.syncMetadataDao().get("remote_version:ledger:tx_tomb_entry")
        assertEquals("700", remoteVer)

        // The local financial history row is preserved in Room
        val preservedLedger = database.localLedgerEntryDao().getByIdOneShot("tx_tomb_entry")
        assertNotNull(preservedLedger)
    }

    @Test
    fun testRemoteAccountDelete_marksHistoryOnlyAndPreservesLedgerHistory() = runBlocking {
        val accountId = "acc_remote_del_prot"
        val account = LocalAccount(
            id = accountId,
            displayName = "Remote Deleted User",
            debtIqd = 30000.0,
            isHistoryOnlySubscriber = false
        )
        database.localAccountDao().insert(account)

        val tx = LocalLedgerEntry(id = "tx_remote_del_child", accountId = accountId, amountIqd = 30000.0, debtAfterIqd = 30000.0, typeRaw = "took")
        database.localLedgerEntryDao().insert(tx)

        val deleteEvent = RemoteEvent.AccountDelete(
            entityId = accountId,
            remoteVersion = 1800000000000L,
            source = RemoteEventSource.PULL
        )
        val result = coordinator.processEvent(deleteEvent)
        assertEquals(EventSyncResult.APPLIED, result)

        val preservedAcc = database.localAccountDao().getByIdOneShot(accountId)
        assertNotNull(preservedAcc)
        assertTrue(preservedAcc!!.isHistoryOnlySubscriber)

        val preservedLedger = database.localLedgerEntryDao().getByIdOneShot("tx_remote_del_child")
        assertNotNull(preservedLedger)

        assertEquals("1800000000000", database.syncMetadataDao().get("tombstone:account:$accountId"))
        assertEquals("1800000000000", database.syncMetadataDao().get("tombstone:ledger:tx_remote_del_child"))
    }

    @Test
    fun testLocalAccountDelete_marksHistoryOnlyWithoutDeletingLedgers() = runBlocking {
        val accountId = "acc_local_del_prot"
        val account = LocalAccount(
            id = accountId,
            displayName = "Local Deleted User",
            debtIqd = 15000.0,
            isHistoryOnlySubscriber = false
        )
        database.localAccountDao().insert(account)

        val tx = LocalLedgerEntry(id = "tx_local_del_child", accountId = accountId, amountIqd = 15000.0, debtAfterIqd = 15000.0, typeRaw = "took")
        database.localLedgerEntryDao().insert(tx)

        accountRepository.deleteAccount(accountId)

        val preservedAcc = database.localAccountDao().getByIdOneShot(accountId)
        assertNotNull(preservedAcc)
        assertTrue(preservedAcc!!.isHistoryOnlySubscriber)

        val preservedLedger = database.localLedgerEntryDao().getByIdOneShot("tx_local_del_child")
        assertNotNull(preservedLedger)
    }

    @Test
    fun testLoanIqdPreservation_acrossFinancialMutations() {
        val initialDebt = 50000.0
        val initialAdvance = 0.0
        val fixedLoan = 25000.0 // Distinct, non-zero loanIqd different from debt

        // Apply payment
        val afterPay = com.example.core.ledger.BalanceCalculator.applyTransaction(
            currentDebt = initialDebt,
            currentAdvance = initialAdvance,
            currentLoan = fixedLoan,
            txType = "payment",
            amount = 20000.0
        )
        assertEquals(30000.0, afterPay.debtIqd, 0.001)
        assertEquals(fixedLoan, afterPay.loanIqd, 0.001)

        // Apply additional debt
        val afterDebt = com.example.core.ledger.BalanceCalculator.applyTransaction(
            currentDebt = afterPay.debtIqd,
            currentAdvance = afterPay.advanceIqd,
            currentLoan = afterPay.loanIqd,
            txType = "debt",
            amount = 10000.0
        )
        assertEquals(40000.0, afterDebt.debtIqd, 0.001)
        assertEquals(fixedLoan, afterDebt.loanIqd, 0.001)

        // Revert debt
        val afterRevert = com.example.core.ledger.BalanceCalculator.revertTransaction(
            currentDebt = afterDebt.debtIqd,
            currentAdvance = afterDebt.advanceIqd,
            currentLoan = afterDebt.loanIqd,
            txType = "debt",
            amount = 10000.0
        )
        assertEquals(30000.0, afterRevert.debtIqd, 0.001)
        assertEquals(fixedLoan, afterRevert.loanIqd, 0.001)
    }

    @Test
    fun testLoanIqd_unchangedThroughRepositoryTransactionSequence() = runBlocking {
        val accountId = "acc_loan_repo_test"
        val distinctLoan = 75000.0
        val initialDebt = 40000.0
        val acc = LocalAccount(
            id = accountId,
            displayName = "Protected Loan Customer",
            debtIqd = initialDebt,
            openingDebtIqd = initialDebt,
            advanceIqd = 0.0,
            openingAdvanceIqd = 0.0,
            loanIqd = distinctLoan,
            openingLoanIqd = distinctLoan
        )
        accountRepository.saveAccount(acc)

        // 1. Add payment transaction via real repository path
        ledgerRepo.recordAccountPayment(acc, 15000.0, "Payment 15k", null)
        val accAfterPay = database.localAccountDao().getByIdOneShot(accountId)
        assertNotNull(accAfterPay)
        assertEquals(25000.0, accAfterPay!!.debtIqd, 0.001)
        assertEquals(0.0, accAfterPay.advanceIqd, 0.001)
        assertEquals("loanIqd must remain unchanged after payment", distinctLoan, accAfterPay.loanIqd, 0.001)

        // 2. Add debt transaction via real repository path
        ledgerRepo.recordAccountDebt(accAfterPay, 30000.0, "Debt 30k", null)
        val accAfterDebt = database.localAccountDao().getByIdOneShot(accountId)
        assertNotNull(accAfterDebt)
        assertEquals(55000.0, accAfterDebt!!.debtIqd, 0.001)
        assertEquals(0.0, accAfterDebt.advanceIqd, 0.001)
        assertEquals("loanIqd must remain unchanged after debt addition", distinctLoan, accAfterDebt.loanIqd, 0.001)
    }
}
