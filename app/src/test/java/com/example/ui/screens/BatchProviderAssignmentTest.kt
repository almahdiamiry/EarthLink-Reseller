package com.example.ui.screens

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.BatchProviderResult
import com.example.core.model.LocalAccount
import com.example.core.model.PendingExternalOperation
import com.example.core.model.SasProviders
import com.example.data.repository.AuditRepositoryImpl
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.AuditRepository
import com.example.domain.repository.SyncReason
import com.example.domain.repository.SyncRepository
import com.example.domain.repository.UtowerImportRepository
import com.example.ui.viewmodels.LocalAccountsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BatchProviderAssignmentTest
 *
 * Authoritative test suite for Task 11A of Alamiry/SAMM Integration:
 * Batch Provider Assignment with Atomic Room Transaction & TOCTOU Guard.
 *
 * Core Triad Specification:
 * 1. Claim:
 *    - INV-03 & INV-05: Multi-selection and filtered search allow batch provider updates without side effects.
 *    - Batch provider assignment updates eligible accounts atomically in Room.
 *    - Accounts already on target provider are detected and counted in [alreadyTarget] without redundant updates.
 *    - In-flight active operations (PENDING, DISPATCHING, RESOLVING) are re-checked inside the Room transaction
 *      (TOCTOU guard) and strictly skipped ([skippedDueToPending]).
 *    - PendingExternalOperation records keep their original provider and status completely untouched.
 *    - Subscriber financial balances and history remain completely unaffected.
 *    - Zero external network API calls are dispatched during batch assignment.
 *
 * 2. Seam / Environment:
 *    - ROBOLECTRIC tier ([RobolectricTestRunner]) for real SQLite Room database transactions and ViewModel state.
 *
 * 3. Independent Oracle:
 *    - Explicit literal expected counts (totalSelected, updated, alreadyTarget, skippedDueToPending).
 *    - Independent database assertions verifying account rows in Room.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BatchProviderAssignmentTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var accountRepo: LocalAccountRepositoryImpl
    private lateinit var ledgerRepo: LocalLedgerRepositoryImpl
    private lateinit var auditRepo: AuditRepository
    private lateinit var utowerRepo: UtowerImportRepository
    private lateinit var syncRepo: SyncRepository
    private lateinit var viewModel: LocalAccountsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        accountRepo = LocalAccountRepositoryImpl(
            database = db,
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao()
        )
        ledgerRepo = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = db.localLedgerEntryDao(),
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao(),
            pendingDao = db.pendingExternalOperationDao()
        )
        auditRepo = AuditRepositoryImpl(db, db.auditLogDao())
        utowerRepo = mock(UtowerImportRepository::class.java)
        syncRepo = mock(SyncRepository::class.java)

        viewModel = LocalAccountsViewModel(
            localRepo = accountRepo,
            ledgerRepo = ledgerRepo,
            utowerRepo = utowerRepo,
            audit = auditRepo,
            syncRepo = syncRepo
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    // =========================================================================
    // 1. SELECTION & FILTER TESTS
    // =========================================================================

    @Test
    fun multiSelection_toggle_selectAllVisible_and_clear() = runTest {
        // Claim: Multi-selection state accurately tracks toggles and visible additions.
        // Seam: LocalAccountsViewModel StateFlows.
        // Independent Oracle: Expected IDs in set.
        assertFalse(viewModel.isSelectionMode.value)
        assertEquals(emptySet<String>(), viewModel.selectedAccountIds.value)

        viewModel.toggleAccountSelection("acc_1")
        assertTrue(viewModel.isSelectionMode.value)
        assertEquals(setOf("acc_1"), viewModel.selectedAccountIds.value)

        viewModel.toggleAccountSelection("acc_2")
        assertEquals(setOf("acc_1", "acc_2"), viewModel.selectedAccountIds.value)

        // Toggle acc_1 off
        viewModel.toggleAccountSelection("acc_1")
        assertEquals(setOf("acc_2"), viewModel.selectedAccountIds.value)

        // Add visible
        viewModel.selectAllVisible(listOf("acc_3", "acc_4"))
        assertEquals(setOf("acc_2", "acc_3", "acc_4"), viewModel.selectedAccountIds.value)

        // Clear selection
        viewModel.clearSelection()
        assertFalse(viewModel.isSelectionMode.value)
        assertEquals(emptySet<String>(), viewModel.selectedAccountIds.value)
    }

    @Test
    fun selectAllResults_queriesFilteredAccountIds_withFilterPredicateIsolation() = runTest {
        // Claim: selectAllResults selects all accounts matching active search & filters, leaving excluded untouched.
        // Seam: LocalAccountsViewModel -> LocalAccountRepository.queryAccountIdsFiltered -> SQLite query.
        // Independent Oracle: Only matching account IDs are returned and selected.
        val acc1 = LocalAccount(id = "acc_el_debt", displayName = "Ahmed Ali", debtIqd = 25000.0, operationProvider = SasProviders.EARTHLINK)
        val acc2 = LocalAccount(id = "acc_el_nodebt", displayName = "Basim Kareem", debtIqd = 0.0, operationProvider = SasProviders.EARTHLINK)
        val acc3 = LocalAccount(id = "acc_samm_debt", displayName = "Chaloob Hassan", debtIqd = 50000.0, operationProvider = SasProviders.ALAMIRY)
        val acc4 = LocalAccount(id = "acc_history", displayName = "Dawood Salman", debtIqd = 10000.0, isHistoryOnlySubscriber = true)

        accountRepo.saveAccount(acc1)
        accountRepo.saveAccount(acc2)
        accountRepo.saveAccount(acc3)
        accountRepo.saveAccount(acc4)

        // Case A: Filter by debt only
        viewModel.toggleFilterDebt()
        viewModel.selectAllResults().join()

        // Should match acc1 and acc3 (acc4 is history-only, acc2 has debt = 0)
        assertEquals(setOf("acc_el_debt", "acc_samm_debt"), viewModel.selectedAccountIds.value)

        // Case B: Filter by debt AND provider = EARTHLINK
        viewModel.setFilterProvider(SasProviders.EARTHLINK)
        viewModel.selectAllResults().join()

        // Should match only acc1
        assertEquals(setOf("acc_el_debt"), viewModel.selectedAccountIds.value)
    }

    // =========================================================================
    // 2. BATCH PROVIDER ASSIGNMENT TESTS (EarthLink -> SAMM & SAMM -> EarthLink)
    // =========================================================================

    @Test
    fun batchSetProvider_earthlinkToSamm_updatesEligibleAndSkipsAlreadyTarget() = runTest {
        // Claim: EarthLink accounts migrate to SAMM; SAMM accounts are counted in alreadyTarget; financial balances untouched.
        // Seam: LocalAccountRepositoryImpl.batchSetProvider inside Room transaction.
        // Independent Oracle:
        // Expected totalSelected = 3, updated = 2, alreadyTarget = 1, skippedDueToPending = 0.
        val acc1 = LocalAccount(id = "acc_1", displayName = "User 1", debtIqd = 15000.0, operationProvider = SasProviders.EARTHLINK)
        val acc2 = LocalAccount(id = "acc_2", displayName = "User 2", debtIqd = 30000.0, operationProvider = SasProviders.EARTHLINK)
        val acc3 = LocalAccount(id = "acc_3", displayName = "User 3", debtIqd = 0.0, operationProvider = SasProviders.ALAMIRY)

        accountRepo.saveAccount(acc1)
        accountRepo.saveAccount(acc2)
        accountRepo.saveAccount(acc3)

        val result = accountRepo.batchSetProvider(listOf("acc_1", "acc_2", "acc_3"), SasProviders.ALAMIRY)

        assertEquals(BatchProviderResult(totalSelected = 3, updated = 2, alreadyTarget = 1, skippedDueToPending = 0), result)

        val p1 = accountRepo.getAccountByIdOneShot("acc_1")!!
        val p2 = accountRepo.getAccountByIdOneShot("acc_2")!!
        val p3 = accountRepo.getAccountByIdOneShot("acc_3")!!

        assertEquals(SasProviders.ALAMIRY, p1.operationProvider)
        assertEquals(SasProviders.ALAMIRY, p2.operationProvider)
        assertEquals(SasProviders.ALAMIRY, p3.operationProvider)

        // Financial balances must not be altered
        assertEquals(15000.0, p1.debtIqd, 0.001)
        assertEquals(30000.0, p2.debtIqd, 0.001)
        assertEquals(0.0, p3.debtIqd, 0.001)
    }

    @Test
    fun batchSetProvider_sammToEarthlink_updatesEligible() = runTest {
        // Claim: SAMM accounts migrate back to EarthLink correctly.
        // Seam: LocalAccountRepositoryImpl.batchSetProvider.
        // Independent Oracle:
        // Expected totalSelected = 2, updated = 2, alreadyTarget = 0, skippedDueToPending = 0.
        val acc1 = LocalAccount(id = "acc_s1", displayName = "SAMM User 1", operationProvider = SasProviders.ALAMIRY)
        val acc2 = LocalAccount(id = "acc_s2", displayName = "SAMM User 2", operationProvider = SasProviders.ALAMIRY)

        accountRepo.saveAccount(acc1)
        accountRepo.saveAccount(acc2)

        val result = accountRepo.batchSetProvider(listOf("acc_s1", "acc_s2"), SasProviders.EARTHLINK)

        assertEquals(BatchProviderResult(totalSelected = 2, updated = 2, alreadyTarget = 0, skippedDueToPending = 0), result)

        assertEquals(SasProviders.EARTHLINK, accountRepo.getAccountByIdOneShot("acc_s1")?.operationProvider)
        assertEquals(SasProviders.EARTHLINK, accountRepo.getAccountByIdOneShot("acc_s2")?.operationProvider)
    }

    // =========================================================================
    // 3. TOCTOU IN-FLIGHT OPERATION GUARD TESTS
    // =========================================================================

    @Test
    fun batchSetProvider_skipsAccountsWithActiveInFlightOperations_andPreservesPendingOp() = runTest {
        // Claim: Accounts with active in-flight ops (PENDING, DISPATCHING, RESOLVING) are skipped.
        // PendingExternalOperation.operationProvider remains completely untouched.
        // Seam: Room transaction with in-flight op check via LocalAccountDao.getAccountIdsWithActiveInFlightOperations.
        // Independent Oracle:
        // acc_pending: skippedDueToPending = 1, operationProvider remains EARTHLINK
        // acc_clean: updated = 1, operationProvider becomes ALAMIRY
        // pendingOp.operationProvider remains EARTHLINK
        val accPending = LocalAccount(id = "acc_pending", displayName = "Pending User", operationProvider = SasProviders.EARTHLINK)
        val accClean = LocalAccount(id = "acc_clean", displayName = "Clean User", operationProvider = SasProviders.EARTHLINK)

        accountRepo.saveAccount(accPending)
        accountRepo.saveAccount(accClean)

        // Insert active pending op for acc_pending
        val pendingOp = PendingExternalOperation(
            businessTransactionId = "tx_inflight_01",
            operationIntentId = "intent_inflight_01",
            accountId = "acc_pending",
            operationType = "RENEWAL",
            amountIqd = 35000L,
            status = "PENDING",
            dispatchClaimCount = 0,
            operationProvider = SasProviders.EARTHLINK
        )
        ledgerRepo.recordPendingOperation(pendingOp)

        val result = accountRepo.batchSetProvider(listOf("acc_pending", "acc_clean"), SasProviders.ALAMIRY)

        assertEquals(
            BatchProviderResult(totalSelected = 2, updated = 1, alreadyTarget = 0, skippedDueToPending = 1),
            result
        )

        // acc_pending skipped and remains on EARTHLINK
        val pPending = accountRepo.getAccountByIdOneShot("acc_pending")!!
        assertEquals(SasProviders.EARTHLINK, pPending.operationProvider)

        // acc_clean updated to ALAMIRY
        val pClean = accountRepo.getAccountByIdOneShot("acc_clean")!!
        assertEquals(SasProviders.ALAMIRY, pClean.operationProvider)

        // Pending operation record in Room must have original provider unchanged
        val storedOp = db.pendingExternalOperationDao().getByBusinessTransactionId("tx_inflight_01")
        assertNotNull(storedOp)
        assertEquals(SasProviders.EARTHLINK, storedOp?.operationProvider)
        assertEquals("PENDING", storedOp?.status)
    }

    @Test
    fun batchSetProvider_completedOrFailedOps_doNotBlockAssignment() = runTest {
        // Claim: Historical operations with status COMPLETED or FAILED do NOT count as active in-flight operations.
        // Seam: DAO query WHERE status NOT IN ('COMPLETED', 'FAILED').
        // Independent Oracle: Account is considered eligible and updated.
        val acc = LocalAccount(id = "acc_completed", displayName = "Completed Op User", operationProvider = SasProviders.EARTHLINK)
        accountRepo.saveAccount(acc)

        val completedOp = PendingExternalOperation(
            businessTransactionId = "tx_done_01",
            operationIntentId = "intent_done_01",
            accountId = "acc_completed",
            operationType = "ACTIVATION",
            amountIqd = 40000L,
            status = "COMPLETED",
            dispatchClaimCount = 1,
            operationProvider = SasProviders.EARTHLINK
        )
        ledgerRepo.recordPendingOperation(completedOp)

        val result = accountRepo.batchSetProvider(listOf("acc_completed"), SasProviders.ALAMIRY)

        assertEquals(BatchProviderResult(totalSelected = 1, updated = 1, alreadyTarget = 0, skippedDueToPending = 0), result)
        assertEquals(SasProviders.ALAMIRY, accountRepo.getAccountByIdOneShot("acc_completed")?.operationProvider)
    }

    // =========================================================================
    // 4. VIEWMODEL EXECUTION & AUDIT / SYNC TESTS
    // =========================================================================

    @Test
    fun viewModel_executeBatchProviderAssignment_clearsSelection_andRequestsSync() = runTest {
        // Claim: executeBatchProviderAssignment calls repository, updates batchResult, clears selection, logs audit, and requests sync.
        // Seam: LocalAccountsViewModel.executeBatchProviderAssignment.
        // Independent Oracle: batchResult matches, selection empty, sync requested.
        val acc = LocalAccount(id = "acc_vm_1", displayName = "VM User", operationProvider = SasProviders.EARTHLINK)
        accountRepo.saveAccount(acc)

        viewModel.toggleAccountSelection("acc_vm_1")
        assertTrue(viewModel.isSelectionMode.value)

        val result = viewModel.executeBatchProviderAssignment(SasProviders.ALAMIRY)

        assertEquals(BatchProviderResult(totalSelected = 1, updated = 1, alreadyTarget = 0, skippedDueToPending = 0), result)
        assertEquals(result, viewModel.batchResult.value)
        assertFalse(viewModel.isSelectionMode.value)
        assertEquals(emptySet<String>(), viewModel.selectedAccountIds.value)

        // Verify sync requested
        verify(syncRepo).requestSync(SyncReason.USER_ACTION)
    }

    @Test
    fun batchSetProvider_emptySelection_returnsZeroResultSafely() = runTest {
        // Claim: Passing empty list returns zeroed result without exception.
        // Seam: batchSetProvider boundary check.
        // Independent Oracle: All counts 0.
        val result = accountRepo.batchSetProvider(emptyList(), SasProviders.ALAMIRY)
        assertEquals(BatchProviderResult(0, 0, 0, 0), result)
    }
}
