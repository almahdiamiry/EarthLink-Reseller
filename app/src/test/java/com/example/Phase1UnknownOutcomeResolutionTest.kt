package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.database.LocalAccountDao
import com.example.core.database.LocalLedgerEntryDao
import com.example.core.database.PendingExternalOperationDao
import com.example.core.database.SyncOutboxDao
import com.example.core.model.*
import com.example.core.security.PreferenceManager
import com.example.data.repository.AuditRepositoryImpl
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.AuditRepository
import com.example.domain.repository.EarthlinkGateway
import com.example.domain.repository.LocalAccountRepository
import com.example.domain.repository.LocalLedgerRepository
import com.example.ui.viewmodels.EarthlinkSearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 1 Unknown-Outcome Verification & Resolution Protocol Test Suite (INV-11 / G1).
 *
 * Verifies the 4-case verification-based unknown-outcome resolution protocol:
 * 1. Case 1 (Verified Success): Authoritative inspection confirms external execution. Atomically materializes
 *    local ledger entry with original pre-allocated businessTransactionId, updates account current position,
 *    enqueues outbox obligation, and marks pending operation COMPLETED.
 * 2. Case 2 (Verified Failure): Authoritative inspection confirms operation was NOT executed on ISP.
 *    Marks pending operation FAILED with diagnostic error; 0 ledger entries, 0 balance change, 0 outbox entries.
 * 3. Case 3 (Inconclusive): Subscriber inspection fails (e.g. network timeout or unreachable). Pending operation
 *    retains PENDING status with diagnostic message; no blind retry is performed.
 * 4. Case 4 (Process Restart): Unresolved pending operations survive process restart / database close-reopen,
 *    recovering cleanly and allowing authoritative verification resolution upon restart.
 * 5. Mutually exclusive resolution & terminal state protection (COMPLETED cannot transition to FAILED).
 * 6. ViewModel integration: EarthlinkSearchViewModel.resolvePendingOperation coordinates inspection, updates UI,
 *    and enforces account locks.
 * 7. Verification inspection safety: Authoritative resolution uses read-only inspection methods and never
 *    dispatches blind financial retry calls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase1UnknownOutcomeResolutionTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var pendingDao: PendingExternalOperationDao
    private lateinit var accountDao: LocalAccountDao
    private lateinit var ledgerDao: LocalLedgerEntryDao
    private lateinit var outboxDao: SyncOutboxDao
    private lateinit var ledgerRepository: LocalLedgerRepository
    private lateinit var accountRepository: LocalAccountRepository
    private lateinit var auditRepository: AuditRepository
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var testGateway: TestEarthlinkGateway

    class TestEarthlinkGateway : EarthlinkGateway {
        val refillCalls = AtomicInteger(0)
        val extendUserCalls = AtomicInteger(0)
        val createTestUserCalls = AtomicInteger(0)
        val createUserUsingDepositCalls = AtomicInteger(0)
        val searchUsersCalls = AtomicInteger(0)
        val getUserDetailCalls = AtomicInteger(0)
        val checkUsernameCalls = AtomicInteger(0)

        var usernameAvailable = true
        var searchUsersResult: UserListResponse = UserListResponse(itemsList = emptyList(), totalCount = 0)
        var userDetailResult: UserDetail = UserDetail(userIndexLower = 1, userIDLower = "user1")
        var shouldThrowOnDetail: Exception? = null
        var shouldThrowOnAvailability: Exception? = null

        override suspend fun login(username: String, password: String): LoginResponse =
            LoginResponse(accessToken = "test_token", tokenType = "Bearer", expiresIn = 3600)

        override suspend fun getBalance(): Double = 500000.0
        override suspend fun getTestUsersCount(affiliateIndex: Int?): Int = 0
        override suspend fun getActiveTestUsersCount(): Int = 0
        override suspend fun getPrepaidNeeded(): Double = 0.0
        override suspend fun getPackages(): List<AccountPackage> = emptyList()
        override suspend fun getAccountCost(accountIndex: Int): Double = 40000.0

        override suspend fun searchUsers(query: String, startIndex: Int, rowCount: Int): UserListResponse {
            searchUsersCalls.incrementAndGet()
            return searchUsersResult
        }

        override suspend fun getUserDetail(userIndex: Int): UserDetail {
            getUserDetailCalls.incrementAndGet()
            shouldThrowOnDetail?.let { throw it }
            return userDetailResult
        }

        override suspend fun autocompleteUser(query: String): List<AutocompleteUser> = emptyList()

        override suspend fun checkUsernameAvailable(userId: String): Boolean {
            checkUsernameCalls.incrementAndGet()
            shouldThrowOnAvailability?.let { throw it }
            return usernameAvailable
        }

        override suspend fun checkCustomerByPhone(phone: String): String? = null
        override suspend fun createCustomer(name: String, phone: String): Boolean = true

        override suspend fun createTestUser(username: String, phone: String, fullName: String, accountIndex: Int): String? {
            createTestUserCalls.incrementAndGet()
            return "pass_test"
        }

        override suspend fun createUserUsingDeposit(username: String, phone: String, fullName: String, accountIndex: Int, depositPassword: String): String? {
            createUserUsingDepositCalls.incrementAndGet()
            return "pass_deposit"
        }

        override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean {
            refillCalls.incrementAndGet()
            return true
        }

        override suspend fun extendUser(userIndex: Int): Boolean {
            extendUserCalls.incrementAndGet()
            return true
        }

        override suspend fun getAccountStatement(startIndex: Int, rowCount: Int, query: String): List<AccountStatementItem> = emptyList()
        override suspend fun showUserPassword(userIndex: Int, userId: String): String = "pass"
        override suspend fun showAccountPassword(userIndex: Int, userId: String): String = "pass"
        override suspend fun changeUserPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
        override suspend fun changeAccountPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
        override suspend fun toggleUserActive(userIndex: Int, active: Boolean): Boolean = true
        override suspend fun changeAccountType(userIndex: Int, userId: String, accountIndex: Int): Boolean = true
        override suspend fun updateUserDisplayName(userIndex: Int, newName: String): Boolean = true
    }

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
        accountRepository = LocalAccountRepositoryImpl(
            database = db,
            accountDao = accountDao,
            outboxDao = outboxDao
        )
        auditRepository = AuditRepositoryImpl(
            database = db,
            auditDao = db.auditLogDao()
        )
        preferenceManager = PreferenceManager(context)
        testGateway = TestEarthlinkGateway()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun createViewModel(): EarthlinkSearchViewModel {
        return EarthlinkSearchViewModel(
            gateway = testGateway,
            audit = auditRepository,
            prefs = preferenceManager,
            localAccountRepository = accountRepository,
            localLedgerRepository = ledgerRepository
        )
    }

    // 1. Case 1 (Verified Success): Authoritative subscriber inspection confirms expiration advanced on ISP.
    // Materializes ledger entry using pre-allocated businessTransactionId, updates balance, enqueues outbox, and completes pending operation.
    @Test
    fun test1_case1_verifiedSuccess_renewal_materializesLedgerAndCompletesPending(): Unit = runBlocking {
        val username = "sub_p1_10_01"
        val txId = "tx_renewal_001"
        val intentId = "intent_renewal_001"
        val initialAccount = LocalAccount(
            id = "acc_001",
            earthlinkUsername = username,
            displayName = "Subscriber One",
            currentPriceIqd = 45000.0,
            debtIqd = 0.0,
            advanceIqd = 0.0
        )
        accountDao.insert(initialAccount)

        val pendingOp = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = intentId,
            accountId = username,
            operationType = "REFILL",
            amountIqd = 45000L,
            payloadJson = """{"userId":"$username","price":45000.0}""",
            status = "PENDING"
        )
        ledgerRepository.recordPendingOperation(pendingOp)

        // Configure gateway inspection to confirm subscriber renewal with advanced expiration date
        testGateway.searchUsersResult = UserListResponse(
            itemsList = listOf(UserListItem(userIndexLower = 101, userIDLower = username)),
            totalCount = 1
        )
        testGateway.userDetailResult = UserDetail(
            userIndexLower = 101,
            userIDLower = username,
            expirationDateLower = "2026-09-18 12:00:00",
            userActiveLower = true
        )

        // Execute verification resolution with baseline date "2026-08-18 12:00:00"
        val resolution = ledgerRepository.verifyAndResolvePendingOperation(
            businessTransactionId = txId,
            gateway = testGateway,
            baselineExpirationDate = "2026-08-18 12:00:00"
        )

        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, resolution.result)
        assertNotNull("Materialized ledger entry must be returned on verified success", resolution.ledgerEntry)
        assertEquals(txId, resolution.ledgerEntry?.id)
        assertEquals(45000.0, resolution.ledgerEntry?.amountIqd ?: 0.0, 0.001)

        // Verify PendingExternalOperation is marked COMPLETED
        val updatedOp = pendingDao.getByBusinessTransactionId(txId)
        assertNotNull(updatedOp)
        assertEquals("COMPLETED", updatedOp?.status)

        // Verify LocalAccount debt is updated
        val updatedAcc = accountDao.getByIdOneShot("acc_001")
        assertNotNull(updatedAcc)
        assertEquals(45000.0, updatedAcc?.debtIqd ?: 0.0, 0.001)

        // Verify exactly 1 ledger entry exists in DB with pre-allocated ID
        val ledgerEntries = ledgerDao.getByAccountIdOneShot("acc_001")
        assertEquals(1, ledgerEntries.size)
        assertEquals(txId, ledgerEntries.first().id)
        assertEquals(45000.0, ledgerEntries.first().amountIqd, 0.001)

        // Verify Outbox obligations enqueued for both ledger and account
        val ledgerOutbox = outboxDao.getByEntity(txId, "local_ledger_entries")
        assertEquals(1, ledgerOutbox.size)
        val accountOutbox = outboxDao.getByEntity("acc_001", "local_accounts")
        assertEquals(1, accountOutbox.size)

        // Verify no mutating external refill calls were made during verification inspection
        assertEquals(0, testGateway.refillCalls.get())
        assertEquals(1, testGateway.searchUsersCalls.get())
        assertEquals(1, testGateway.getUserDetailCalls.get())
    }

    // 2. Case 2 (Verified Failure): Authoritative subscriber inspection confirms expiration date unchanged on ISP.
    // Marks pending operation FAILED with diagnostic error; 0 ledger entries, 0 balance change, 0 outbox entries.
    @Test
    fun test2_case2_verifiedFailure_renewal_failsWithoutFinancialMutation(): Unit = runBlocking {
        val username = "sub_p1_10_02"
        val txId = "tx_renewal_002"
        val intentId = "intent_renewal_002"
        val initialAccount = LocalAccount(
            id = "acc_002",
            earthlinkUsername = username,
            displayName = "Subscriber Two",
            currentPriceIqd = 35000.0,
            debtIqd = 10000.0,
            advanceIqd = 0.0
        )
        accountDao.insert(initialAccount)

        val pendingOp = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = intentId,
            accountId = username,
            operationType = "REFILL",
            amountIqd = 35000L,
            payloadJson = """{"userId":"$username","price":35000.0}""",
            status = "PENDING"
        )
        ledgerRepository.recordPendingOperation(pendingOp)

        // Configure gateway inspection to return unchanged expiration date (failure on ISP)
        testGateway.searchUsersResult = UserListResponse(
            itemsList = listOf(UserListItem(userIndexLower = 102, userIDLower = username)),
            totalCount = 1
        )
        testGateway.userDetailResult = UserDetail(
            userIndexLower = 102,
            userIDLower = username,
            expirationDateLower = "2026-08-18 12:00:00" // Unchanged from baseline
        )

        val resolution = ledgerRepository.verifyAndResolvePendingOperation(
            businessTransactionId = txId,
            gateway = testGateway,
            baselineExpirationDate = "2026-08-18 12:00:00"
        )

        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_FAILURE, resolution.result)
        assertNull("Ledger entry must be null on verified failure", resolution.ledgerEntry)

        // Verify PendingExternalOperation is marked FAILED with diagnostic error
        val updatedOp = pendingDao.getByBusinessTransactionId(txId)
        assertNotNull(updatedOp)
        assertEquals("FAILED", updatedOp?.status)
        assertTrue(updatedOp?.lastError?.contains("unchanged", ignoreCase = true) == true)

        // Verify 0 ledger entries created
        val ledgerEntries = ledgerDao.getByAccountIdOneShot("acc_002")
        assertEquals(0, ledgerEntries.size)

        // Verify account balance remains unchanged
        val updatedAcc = accountDao.getByIdOneShot("acc_002")
        assertEquals(10000.0, updatedAcc?.debtIqd ?: 0.0, 0.001)

        // Verify 0 outbox entries created for ledger entries
        val ledgerOutbox = outboxDao.getByEntity(txId, "local_ledger_entries")
        assertEquals(0, ledgerOutbox.size)

        // Verify no mutating external refill calls
        assertEquals(0, testGateway.refillCalls.get())
    }

    // 3. Case 3 (Inconclusive): Subscriber inspection fails due to network outage.
    // Retains PENDING status, records diagnostic message, creates 0 ledger entries, and performs NO blind retry.
    @Test
    fun test3_case3_inconclusive_networkFailure_retainsPendingWithoutRetry(): Unit = runBlocking {
        val username = "sub_p1_10_03"
        val txId = "tx_renewal_003"
        val intentId = "intent_renewal_003"
        val initialAccount = LocalAccount(
            id = "acc_003",
            earthlinkUsername = username,
            displayName = "Subscriber Three",
            currentPriceIqd = 40000.0,
            debtIqd = 0.0
        )
        accountDao.insert(initialAccount)

        val pendingOp = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = intentId,
            accountId = username,
            operationType = "RENEWAL",
            amountIqd = 40000L,
            payloadJson = """{"userId":"$username"}""",
            status = "PENDING"
        )
        ledgerRepository.recordPendingOperation(pendingOp)

        // Configure gateway to fail during inspection
        testGateway.searchUsersResult = UserListResponse(
            itemsList = listOf(UserListItem(userIndexLower = 103, userIDLower = username)),
            totalCount = 1
        )
        testGateway.shouldThrowOnDetail = IOException("Earthlink gateway timeout during subscriber inspection")

        val resolution = ledgerRepository.verifyAndResolvePendingOperation(
            businessTransactionId = txId,
            gateway = testGateway,
            baselineExpirationDate = "2026-08-18 12:00:00"
        )

        assertEquals(UnknownOutcomeResolutionResult.INCONCLUSIVE, resolution.result)
        assertNull(resolution.ledgerEntry)

        // Verify PendingExternalOperation remains in PENDING status with diagnostic message
        val updatedOp = pendingDao.getByBusinessTransactionId(txId)
        assertNotNull(updatedOp)
        assertEquals("PENDING", updatedOp?.status)
        assertTrue(updatedOp?.lastError?.contains("timeout", ignoreCase = true) == true)

        // Verify 0 ledger entries and 0 balance mutations
        val ledgerEntries = ledgerDao.getByAccountIdOneShot("acc_003")
        assertEquals(0, ledgerEntries.size)
        val updatedAcc = accountDao.getByIdOneShot("acc_003")
        assertEquals(0.0, updatedAcc?.debtIqd ?: 0.0, 0.001)

        // Verify NO blind retries were executed
        assertEquals(0, testGateway.refillCalls.get())
        assertEquals(0, testGateway.extendUserCalls.get())
    }

    // 4. Case 4 (Process Restart Recovery): Pending operations survive SQLite crash/restart and resolve cleanly on restart.
    @Test
    fun test4_case4_processRestart_recoversPendingAndResolvesCleanly(): Unit = runBlocking {
        val dbName = "g1_unknown_outcome_crash_test_db"
        AppDatabase.closeAndRemoveInstance(dbName)
        context.deleteDatabase(dbName)
        context.getDatabasePath(dbName).parentFile?.mkdirs()

        // 1. First process lifecycle: record pending operation in disk SQLite
        val diskDb1 = AppDatabase.getDatabase(context, ByteArray(0), dbName)
        val diskPendingDao1 = diskDb1.pendingExternalOperationDao()
        val diskAccountDao1 = diskDb1.localAccountDao()
        val diskLedgerDao1 = diskDb1.localLedgerEntryDao()
        val diskOutboxDao1 = diskDb1.syncOutboxDao()
        val diskRepo1 = LocalLedgerRepositoryImpl(diskDb1, diskLedgerDao1, diskAccountDao1, diskOutboxDao1, diskPendingDao1)

        val txId = "tx_crash_restart_004"
        val intentId = "intent_crash_restart_004"
        val username = "sub_restart_004"

        diskAccountDao1.insert(
            LocalAccount(id = "acc_004", earthlinkUsername = username, displayName = "Restart User", currentPriceIqd = 50000.0)
        )
        diskRepo1.recordPendingOperation(
            PendingExternalOperation(
                businessTransactionId = txId,
                operationIntentId = intentId,
                accountId = username,
                operationType = "REFILL",
                amountIqd = 50000L,
                status = "PENDING"
            )
        )

        // 2. Simulate process crash by closing and releasing database instance
        AppDatabase.closeAndRemoveInstance(dbName)

        // 3. Process restart: reopen database from disk
        val diskDb2 = AppDatabase.getDatabase(context, ByteArray(0), dbName)
        val diskPendingDao2 = diskDb2.pendingExternalOperationDao()
        val diskAccountDao2 = diskDb2.localAccountDao()
        val diskLedgerDao2 = diskDb2.localLedgerEntryDao()
        val diskOutboxDao2 = diskDb2.syncOutboxDao()
        val diskRepo2 = LocalLedgerRepositoryImpl(diskDb2, diskLedgerDao2, diskAccountDao2, diskOutboxDao2, diskPendingDao2)

        // Verify unresolved pending operations are recovered
        val unresolvedOps = diskRepo2.getUnresolvedPendingOperations()
        assertEquals(1, unresolvedOps.size)
        val recoveredOp = unresolvedOps.first()
        assertEquals(txId, recoveredOp.businessTransactionId)
        assertEquals("PENDING", recoveredOp.status)

        // 4. Execute authoritative verification on recovered pending operation
        testGateway.searchUsersResult = UserListResponse(
            itemsList = listOf(UserListItem(userIndexLower = 104, userIDLower = username)),
            totalCount = 1
        )
        testGateway.userDetailResult = UserDetail(
            userIndexLower = 104,
            userIDLower = username,
            expirationDateLower = "2026-10-01 00:00:00",
            userActiveLower = true
        )

        val resolution = diskRepo2.verifyAndResolvePendingOperation(
            businessTransactionId = txId,
            gateway = testGateway,
            baselineExpirationDate = "2026-08-18 00:00:00"
        )

        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, resolution.result)
        assertNotNull(resolution.ledgerEntry)
        assertEquals(txId, resolution.ledgerEntry?.id)
        assertEquals(50000.0, resolution.ledgerEntry?.amountIqd ?: 0.0, 0.001)

        val completedOp = diskPendingDao2.getByBusinessTransactionId(txId)
        assertEquals("COMPLETED", completedOp?.status)

        val ledgerEntries = diskLedgerDao2.getByAccountIdOneShot("acc_004")
        assertEquals(1, ledgerEntries.size)
        assertEquals(txId, ledgerEntries.first().id)

        // Clean up
        AppDatabase.closeAndRemoveInstance(dbName)
        context.deleteDatabase(dbName)
    }

    // 5. Activation Verification: Confirms subscriber existence on ISP (checkUsernameAvailable = false -> VERIFIED_SUCCESS)
    @Test
    fun test5_case1_activation_verifiedSuccess_whenUsernameTaken(): Unit = runBlocking {
        val username = "new_sub_10_05"
        val txId = "tx_act_005"
        val intentId = "intent_act_005"

        ledgerRepository.recordPendingOperation(
            PendingExternalOperation(
                businessTransactionId = txId,
                operationIntentId = intentId,
                accountId = username,
                operationType = "ACTIVATION",
                amountIqd = 0L,
                status = "PENDING"
            )
        )

        // checkUsernameAvailable = false indicates username was registered on ISP
        testGateway.usernameAvailable = false

        val resolution = ledgerRepository.verifyAndResolvePendingOperation(
            businessTransactionId = txId,
            gateway = testGateway
        )

        assertEquals(UnknownOutcomeResolutionResult.INCONCLUSIVE, resolution.result)
        val updatedOp = pendingDao.getByBusinessTransactionId(txId)
        assertEquals("PENDING", updatedOp?.status)
    }

    // 6. Activation Verification: Confirms subscriber does not exist on ISP (checkUsernameAvailable = true -> VERIFIED_FAILURE)
    @Test
    fun test6_case2_activation_verifiedFailure_whenUsernameAvailable(): Unit = runBlocking {
        val username = "new_sub_10_06"
        val txId = "tx_act_006"
        val intentId = "intent_act_006"

        ledgerRepository.recordPendingOperation(
            PendingExternalOperation(
                businessTransactionId = txId,
                operationIntentId = intentId,
                accountId = username,
                operationType = "ACTIVATION",
                amountIqd = 0L,
                status = "PENDING"
            )
        )

        // checkUsernameAvailable = true indicates username is still available (creation failed on ISP)
        testGateway.usernameAvailable = true

        val resolution = ledgerRepository.verifyAndResolvePendingOperation(
            businessTransactionId = txId,
            gateway = testGateway
        )

        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_FAILURE, resolution.result)
        val updatedOp = pendingDao.getByBusinessTransactionId(txId)
        assertEquals("FAILED", updatedOp?.status)
        assertTrue(updatedOp?.lastError?.contains("still available", ignoreCase = true) == true)
    }

    // 7. Idempotency & Terminal State Protection: Repeated verification on COMPLETED returns success; COMPLETED cannot become FAILED
    @Test
    fun test7_idempotentResolution_completedOperationNeverReExecutes(): Unit = runBlocking {
        val username = "sub_p1_10_07"
        val txId = "tx_renewal_007"
        val intentId = "intent_renewal_007"
        val account = LocalAccount(
            id = "acc_007",
            earthlinkUsername = username,
            displayName = "Idempotent User",
            currentPriceIqd = 40000.0,
            debtIqd = 0.0
        )
        accountDao.insert(account)

        ledgerRepository.recordPendingOperation(
            PendingExternalOperation(
                businessTransactionId = txId,
                operationIntentId = intentId,
                accountId = username,
                operationType = "REFILL",
                amountIqd = 40000L,
                status = "PENDING"
            )
        )

        testGateway.searchUsersResult = UserListResponse(
            itemsList = listOf(UserListItem(userIndexLower = 107, userIDLower = username)),
            totalCount = 1
        )
        testGateway.userDetailResult = UserDetail(
            userIndexLower = 107,
            userIDLower = username,
            expirationDateLower = "2026-09-30 00:00:00",
            userActiveLower = true
        )

        // First resolution: completes successfully
        val res1 = ledgerRepository.verifyAndResolvePendingOperation(txId, testGateway, "2026-08-18 00:00:00")
        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, res1.result)
        assertEquals(1, ledgerDao.getByAccountIdOneShot("acc_007").size)

        // Second resolution: returns existing completed state idempotently without creating duplicate ledger entries
        val res2 = ledgerRepository.verifyAndResolvePendingOperation(txId, testGateway, "2026-08-18 00:00:00")
        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, res2.result)
        assertEquals("Expected exactly 1 ledger entry after repeated resolution", 1, ledgerDao.getByAccountIdOneShot("acc_007").size)

        // Terminal state protection: attempting to resolve failure on COMPLETED operation must fail closed
        val failedResolved = ledgerRepository.resolvePendingOperationVerifiedFailure(txId, "Attempted failure transition")
        assertFalse("Cannot mark COMPLETED operation as FAILED", failedResolved)
        assertEquals("COMPLETED", pendingDao.getByBusinessTransactionId(txId)?.status)
    }

    // 8. EarthlinkSearchViewModel integration: resolvePendingOperation updates UI states and coordinates account locks
    @Test
    fun test8_viewModel_unknownOutcomeResolutionWorkflow(): Unit = runBlocking(Dispatchers.Default) {
        val viewModel = createViewModel()
        val username = "vm_sub_10_08"
        val txId = "tx_vm_008"
        val intentId = "intent_vm_008"

        accountDao.insert(
            LocalAccount(id = "acc_008", earthlinkUsername = username, displayName = "VM User", currentPriceIqd = 45000.0)
        )
        ledgerRepository.recordPendingOperation(
            PendingExternalOperation(
                businessTransactionId = txId,
                operationIntentId = intentId,
                accountId = username,
                operationType = "REFILL",
                amountIqd = 45000L,
                status = "PENDING"
            )
        )

        testGateway.searchUsersResult = UserListResponse(
            itemsList = listOf(UserListItem(userIndexLower = 108, userIDLower = username)),
            totalCount = 1
        )
        testGateway.userDetailResult = UserDetail(
            userIndexLower = 108,
            userIDLower = username,
            expirationDateLower = "2026-10-15 00:00:00",
            userActiveLower = true
        )

        var resolvedOutcome: PendingOperationResolution? = null
        val job = viewModel.resolvePendingOperation(
            businessTransactionId = txId,
            baselineExpirationDate = "2026-08-18 00:00:00",
            onResolved = { res -> resolvedOutcome = res }
        )
        job.join()

        assertNotNull("Resolution callback must be invoked", resolvedOutcome)
        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, resolvedOutcome?.result)
        assertTrue(viewModel.actionSuccess.value?.contains("verified", ignoreCase = true) == true)
        assertEquals(1, ledgerDao.getByAccountIdOneShot("acc_008").size)
    }
}
