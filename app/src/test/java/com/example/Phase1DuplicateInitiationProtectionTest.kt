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
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 1 Duplicate-Initiation Protection & Intent Deduplication Test Suite (INV-11 / G1).
 *
 * Verifies that:
 * 1. Rapid UI taps or concurrent coroutine launches for the same logical operation
 *    (Activation, Renewal, Refill) produce exactly 1 external network call, 1 PendingExternalOperation,
 *    and 1 local ledger entry per user intent (INV-11 / G1).
 * 2. Inflight mutex locking collapses concurrent duplicate invocations for the same account.
 * 3. Sequential duplicate taps with the same operationIntentId reuse existing completed pending results
 *    without re-executing external network calls or creating duplicate ledger entries.
 * 4. Subsequent distinct legitimate operations with new intent IDs execute independently.
 * 5. Inflight failures safely unlock the account for subsequent legitimate attempts.
 * 6. Multi-threaded Room transaction atomicity ensures idempotent pending operation recording
 *    at the repository layer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase1DuplicateInitiationProtectionTest {

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
        val createTestUserCalls = AtomicInteger(0)
        val createUserUsingDepositCalls = AtomicInteger(0)
        val extendUserCalls = AtomicInteger(0)
        val customStatements = mutableListOf<AccountStatementItem>()

        var shouldFailRefill = false
        var refillException: Exception? = null
        var simulatedDelayMs = 5L

        override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean {
            refillCalls.incrementAndGet()
            if (simulatedDelayMs > 0) delay(simulatedDelayMs)
            refillException?.let { throw it }
            if (shouldFailRefill) return false
            return true
        }

        override suspend fun createTestUser(username: String, phone: String, fullName: String, accountIndex: Int): String? {
            createTestUserCalls.incrementAndGet()
            if (simulatedDelayMs > 0) delay(simulatedDelayMs)
            return "generated_test_pass_123"
        }

        override suspend fun createUserUsingDeposit(username: String, phone: String, fullName: String, accountIndex: Int, depositPassword: String): String? {
            createUserUsingDepositCalls.incrementAndGet()
            if (simulatedDelayMs > 0) delay(simulatedDelayMs)
            return "generated_deposit_pass_456"
        }

        override suspend fun extendUser(userIndex: Int): Boolean {
            extendUserCalls.incrementAndGet()
            if (simulatedDelayMs > 0) delay(simulatedDelayMs)
            return true
        }

        override suspend fun checkUsernameAvailable(userId: String): Boolean = true
        override suspend fun getBalance(): Double = 250000.0
        override suspend fun getPackages(): List<AccountPackage> = emptyList()
        override suspend fun getAccountCost(accountIndex: Int): Double = 35000.0
        override suspend fun searchUsers(query: String, startIndex: Int, rowCount: Int): UserListResponse = UserListResponse(itemsList = emptyList(), totalCount = 0)
        override suspend fun getUserDetail(userIndex: Int): UserDetail = UserDetail()
        override suspend fun autocompleteUser(query: String): List<AutocompleteUser> = emptyList()
        override suspend fun checkCustomerByPhone(phone: String): String? = null
        override suspend fun createCustomer(name: String, phone: String): Boolean = true
        override suspend fun getAccountStatement(startIndex: Int, rowCount: Int, query: String): List<AccountStatementItem> = emptyList()
        override suspend fun showUserPassword(userIndex: Int, userId: String): String = "pass"
        override suspend fun showAccountPassword(userIndex: Int, userId: String): String = "acc_pass"
        override suspend fun changeUserPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
        override suspend fun changeAccountPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
        override suspend fun toggleUserActive(userIndex: Int, active: Boolean): Boolean = true
        override suspend fun changeAccountType(userIndex: Int, userId: String, accountIndex: Int): Boolean = true
        override suspend fun updateUserDisplayName(userIndex: Int, newName: String): Boolean = true
        override suspend fun login(username: String, password: String): LoginResponse = LoginResponse(accessToken = "test_token", tokenType = "Bearer", expiresIn = 3600)
        override suspend fun getTestUsersCount(affiliateIndex: Int?): Int = 0
        override suspend fun getActiveTestUsersCount(): Int = 0
        override suspend fun getPrepaidNeeded(): Double = 0.0
        override fun addCustomStatement(statement: AccountStatementItem) {
            synchronized(customStatements) {
                customStatements.add(statement)
            }
        }
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

    // 1. 10 concurrent coroutines attempting to trigger renewal for account X produce exactly 1 external network call, 1 PendingExternalOperation, and 1 local ledger entry
    @Test
    fun test1_concurrentDuplicateRefill_collapsesToSingleNetworkCallAndLedgerEntry(): Unit = runBlocking(Dispatchers.Default) {
        val viewModel = createViewModel()
        val accountId = "user_dup_refill_1"
        val initialAccount = LocalAccount(
            id = "acc_dup_1",
            earthlinkUsername = accountId,
            displayName = "Concurrent Refill User 1",
            currentPriceIqd = 40000.0,
            debtIqd = 0.0
        )
        accountDao.insert(initialAccount)

        val sharedIntentId = "intent_refill_shared_001"
        val concurrentCount = 10

        // Launch 10 concurrent coroutines with the same intent ID
        val jobs = (1..concurrentCount).map {
            viewModel.refillUser(
                userId = accountId,
                depositPass = "dep_pass_123",
                price = 40000.0,
                note = "Concurrent duplicate tap",
                intentId = sharedIntentId
            )
        }

        // Await all coroutines
        jobs.joinAll()

        // 1. Exactly 1 external gateway call
        assertEquals("Expected exactly 1 external network call for 10 concurrent invocations", 1, testGateway.refillCalls.get())

        // 2. Exactly 1 PendingExternalOperation persisted
        val pendingOps = pendingDao.getAllOneShot().filter { it.accountId == accountId }
        assertEquals("Expected exactly 1 pending operation record", 1, pendingOps.size)
        assertEquals("COMPLETED", pendingOps.first().status)
        assertEquals(sharedIntentId, pendingOps.first().operationIntentId)

        // 3. Exactly 1 local ledger entry materialized
        val ledgerEntries = ledgerDao.getByAccountIdOneShot(initialAccount.id)
        assertEquals("Expected exactly 1 ledger entry materialized", 1, ledgerEntries.size)
        assertEquals(40000.0, ledgerEntries.first().amountIqd, 0.001)

        // 4. Exactly 1 ledger outbox entry created
        val outboxEntries = outboxDao.getPending().filter { it.entityType == "local_ledger_entries" }
        assertEquals("Expected exactly 1 outbox entry for ledger", 1, outboxEntries.size)
    }

    // 2. 10 concurrent coroutines without explicit intent ID collapse to 1 external call via inflight account mutex lock
    @Test
    fun test2_concurrentDuplicateRefill_withoutExplicitIntent_collapsesDueToInflightAccountLock(): Unit = runBlocking(Dispatchers.Default) {
        val viewModel = createViewModel()
        val accountId = "user_dup_refill_2"
        val initialAccount = LocalAccount(
            id = "acc_dup_2",
            earthlinkUsername = accountId,
            displayName = "Concurrent Refill User 2",
            currentPriceIqd = 45000.0,
            debtIqd = 0.0
        )
        accountDao.insert(initialAccount)

        val concurrentCount = 10

        // Launch 10 concurrent coroutines without explicit intent ID (intentId = null)
        val jobs = (1..concurrentCount).map {
            viewModel.refillUser(
                userId = accountId,
                depositPass = "dep_pass_456",
                price = 45000.0,
                note = "Rapid UI taps without intent ID",
                intentId = null
            )
        }

        jobs.joinAll()

        // Exactly 1 external gateway call
        assertEquals("Expected exactly 1 external network call due to inflight lock", 1, testGateway.refillCalls.get())

        // Exactly 1 PendingExternalOperation persisted
        val pendingOps = pendingDao.getAllOneShot().filter { it.accountId == accountId }
        assertEquals("Expected exactly 1 pending operation record", 1, pendingOps.size)
        assertEquals("COMPLETED", pendingOps.first().status)

        // Exactly 1 local ledger entry materialized
        val ledgerEntries = ledgerDao.getByAccountIdOneShot(initialAccount.id)
        assertEquals("Expected exactly 1 ledger entry materialized", 1, ledgerEntries.size)
        assertEquals(45000.0, ledgerEntries.first().amountIqd, 0.001)
    }

    // 3. Sequential duplicate tap with same operationIntentId reuses existing pending result without re-executing external network call
    @Test
    fun test3_sequentialDuplicateTap_sameIntentId_reusesExistingPendingResultWithoutSecondNetworkCall(): Unit = runBlocking(Dispatchers.Default) {
        val viewModel = createViewModel()
        val accountId = "user_seq_tap_3"
        val initialAccount = LocalAccount(
            id = "acc_seq_3",
            earthlinkUsername = accountId,
            displayName = "Sequential Tap User",
            currentPriceIqd = 40000.0,
            debtIqd = 0.0
        )
        accountDao.insert(initialAccount)

        val sharedIntentId = "intent_seq_tap_003"

        // First tap: executes external network call and materializes ledger
        val job1 = viewModel.refillUser(
            userId = accountId,
            depositPass = "dep_pass_seq",
            price = 40000.0,
            note = "First legitimate tap",
            intentId = sharedIntentId
        )
        job1.join()

        assertEquals("Expected 1 external call after first tap", 1, testGateway.refillCalls.get())
        assertEquals(1, ledgerDao.getByAccountIdOneShot(initialAccount.id).size)

        // Second tap with IDENTICAL operationIntentId
        val job2 = viewModel.refillUser(
            userId = accountId,
            depositPass = "dep_pass_seq",
            price = 40000.0,
            note = "Second sequential tap with same intent",
            intentId = sharedIntentId
        )
        job2.join()

        // Assert no second network call and no second ledger entry
        assertEquals("Expected still exactly 1 external network call (no replay)", 1, testGateway.refillCalls.get())
        assertEquals("Expected still exactly 1 ledger entry", 1, ledgerDao.getByAccountIdOneShot(initialAccount.id).size)
        assertEquals("Expected still exactly 1 pending operation record", 1, pendingDao.getAllOneShot().filter { it.accountId == accountId }.size)
    }

    // 4. Subsequent distinct legitimate renewal with new intent produces a new operation
    @Test
    fun test4_subsequentDistinctLegitimateRenewal_withNewIntent_producesNewOperation(): Unit = runBlocking(Dispatchers.Default) {
        val viewModel = createViewModel()
        val accountId = "user_distinct_renewal_4"
        val initialAccount = LocalAccount(
            id = "acc_distinct_4",
            earthlinkUsername = accountId,
            displayName = "Distinct Renewal User",
            currentPriceIqd = 40000.0,
            debtIqd = 0.0
        )
        accountDao.insert(initialAccount)

        val intent1 = "intent_distinct_month_1"
        val intent2 = "intent_distinct_month_2"

        // First monthly renewal
        val job1 = viewModel.refillUser(
            userId = accountId,
            depositPass = "dep_pass_4",
            price = 40000.0,
            note = "Month 1 Renewal",
            intentId = intent1
        )
        job1.join()

        assertEquals(1, testGateway.refillCalls.get())
        assertEquals(1, ledgerDao.getByAccountIdOneShot(initialAccount.id).size)

        // Second distinct legitimate renewal (e.g. next month)
        val job2 = viewModel.refillUser(
            userId = accountId,
            depositPass = "dep_pass_4",
            price = 40000.0,
            note = "Month 2 Renewal",
            intentId = intent2
        )
        job2.join()

        assertEquals("Expected 2 external network calls for 2 distinct legitimate operations", 2, testGateway.refillCalls.get())
        assertEquals("Expected 2 distinct pending operations", 2, pendingDao.getAllOneShot().filter { it.accountId == accountId }.size)
        assertEquals("Expected 2 distinct ledger entries", 2, ledgerDao.getByAccountIdOneShot(initialAccount.id).size)
    }

    // 5. Inflight failure unlocks the account for subsequent attempts
    @Test
    fun test5_inflightFailure_unlocksAccountForSubsequentAttempts(): Unit = runBlocking(Dispatchers.Default) {
        val viewModel = createViewModel()
        val accountId = "user_fail_retry_5"
        val initialAccount = LocalAccount(
            id = "acc_fail_retry_5",
            earthlinkUsername = accountId,
            displayName = "Failure Retry User",
            currentPriceIqd = 40000.0,
            debtIqd = 0.0
        )
        accountDao.insert(initialAccount)

        val failIntent = "intent_fail_attempt_1"
        val retryIntent = "intent_retry_attempt_2"

        // Configure gateway to fail on first attempt
        testGateway.refillException = IOException("Network connection timed out on Earthlink server")

        val job1 = viewModel.refillUser(
            userId = accountId,
            depositPass = "dep_pass_5",
            price = 40000.0,
            note = "Failed attempt",
            intentId = failIntent
        )
        job1.join()

        // Verify failure recorded
        assertEquals(1, testGateway.refillCalls.get())
        val failedOp = pendingDao.getByOperationIntentId(failIntent)
        assertNotNull("Failed pending operation must exist", failedOp)
        assertEquals("FAILED", failedOp?.status)
        assertEquals(0, ledgerDao.getByAccountIdOneShot(initialAccount.id).size)

        // Clear gateway failure condition
        testGateway.refillException = null

        // Second attempt: must NOT be blocked by lock or prior failure
        val job2 = viewModel.refillUser(
            userId = accountId,
            depositPass = "dep_pass_5",
            price = 40000.0,
            note = "Retry attempt after network recovery",
            intentId = retryIntent
        )
        job2.join()

        assertEquals("Expected second external call to execute on retry", 2, testGateway.refillCalls.get())
        val retryOp = pendingDao.getByOperationIntentId(retryIntent)
        assertNotNull("Retry pending operation must exist", retryOp)
        assertEquals("COMPLETED", retryOp?.status)
        assertEquals("Expected 1 ledger entry materialized from successful retry", 1, ledgerDao.getByAccountIdOneShot(initialAccount.id).size)
    }

    // 6. 10 concurrent coroutines attempting to create test user collapse to exactly 1 external call and 1 pending record
    @Test
    fun test6_concurrentDuplicateActivation_testUser_collapsesToSingleNetworkCall(): Unit = runBlocking(Dispatchers.Default) {
        val viewModel = createViewModel()
        val username = "test_user_dup_6"
        val sharedIntentId = "intent_act_test_006"
        val concurrentCount = 10

        val jobs = (1..concurrentCount).map {
            viewModel.createTestUser(
                username = username,
                phone = "07701234567",
                fullName = "Concurrent Test User",
                pkgIndex = 1,
                intentId = sharedIntentId
            )
        }
        jobs.joinAll()

        assertEquals("Expected exactly 1 external createTestUser network call", 1, testGateway.createTestUserCalls.get())
        val pendingOps = pendingDao.getAllOneShot().filter { it.accountId == username }
        assertEquals("Expected exactly 1 pending operation record", 1, pendingOps.size)
        assertEquals("COMPLETED", pendingOps.first().status)
        assertEquals("ACTIVATION", pendingOps.first().operationType)
    }

    // 7. 10 concurrent coroutines attempting to create paid user collapse to exactly 1 external call and 1 pending record
    @Test
    fun test7_concurrentDuplicateActivation_paidUser_collapsesToSingleNetworkCall(): Unit = runBlocking(Dispatchers.Default) {
        val viewModel = createViewModel()
        val username = "paid_user_dup_7"
        val sharedIntentId = "intent_act_paid_007"
        val concurrentCount = 10

        val jobs = (1..concurrentCount).map {
            viewModel.createUserUsingDeposit(
                username = username,
                phone = "07709876543",
                fullName = "Concurrent Paid User",
                pkgIndex = 2,
                depositPass = "dep_pass_paid",
                intentId = sharedIntentId
            )
        }
        jobs.joinAll()

        assertEquals("Expected exactly 1 external createUserUsingDeposit network call", 1, testGateway.createUserUsingDepositCalls.get())
        val pendingOps = pendingDao.getAllOneShot().filter { it.accountId == username }
        assertEquals("Expected exactly 1 pending operation record", 1, pendingOps.size)
        assertEquals("COMPLETED", pendingOps.first().status)
        assertEquals("ACTIVATION", pendingOps.first().operationType)
    }

    // 8. 10 concurrent coroutines attempting to extend user collapse to exactly 1 external call and 1 pending record
    @Test
    fun test8_concurrentDuplicateExtension_collapsesToSingleNetworkCall(): Unit = runBlocking(Dispatchers.Default) {
        val viewModel = createViewModel()
        val userId = "user_ext_dup_8"
        val sharedIntentId = "intent_ext_008"
        val concurrentCount = 10

        val jobs = (1..concurrentCount).map {
            viewModel.extendUser(
                userIndex = 505,
                userId = userId,
                intentId = sharedIntentId
            )
        }
        jobs.joinAll()

        assertEquals("Expected exactly 1 external extendUser network call", 1, testGateway.extendUserCalls.get())
        val pendingOps = pendingDao.getAllOneShot().filter { it.accountId == userId }
        assertEquals("Expected exactly 1 pending operation record", 1, pendingOps.size)
        assertEquals("COMPLETED", pendingOps.first().status)
        assertEquals("RENEWAL", pendingOps.first().operationType)
    }

    // 9. Repository-level concurrent recordPendingOperation is idempotent under Room transaction boundary
    @Test
    fun test9_repositoryLevel_concurrentRecordPendingOperation_idempotent(): Unit = runBlocking(Dispatchers.Default) {
        val sharedIntentId = "intent_repo_dup_9"
        val txId = "tx_repo_dup_9"
        val accountId = "acc_repo_dup_9"
        val concurrentCount = 10

        val deferredResults = (1..concurrentCount).map { idx ->
            async {
                val op = PendingExternalOperation(
                    businessTransactionId = txId,
                    operationIntentId = sharedIntentId,
                    accountId = accountId,
                    operationType = "REFILL",
                    amountIqd = 40000L,
                    payloadJson = """{"userId":"$accountId","thread":$idx}""",
                    status = "PENDING"
                )
                ledgerRepository.recordPendingOperation(op)
            }
        }

        val results = deferredResults.awaitAll()

        // All returned operations must reference the exact same businessTransactionId
        results.forEach { res ->
            assertEquals(txId, res.businessTransactionId)
            assertEquals(sharedIntentId, res.operationIntentId)
        }

        // Exactly 1 record in SQLite
        val allInDb = pendingDao.getAllOneShot().filter { it.operationIntentId == sharedIntentId }
        assertEquals("Expected exactly 1 row in SQLite", 1, allInDb.size)
    }

    // 10. Process interruption / restart reuses pending operation record without manufacturing duplicates
    @Test
    fun test10_processInterruption_pendingRecordReusedOnRestart(): Unit = runBlocking(Dispatchers.Default) {
        val sharedIntentId = "intent_restart_010"
        val txId = "tx_restart_010"
        val accountId = "user_restart_10"

        // Simulate pre-call crash: operation recorded as PENDING
        val initialOp = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = sharedIntentId,
            accountId = accountId,
            operationType = "REFILL",
            amountIqd = 35000L,
            payloadJson = """{"userId":"$accountId","price":35000}""",
            status = "PENDING"
        )
        ledgerRepository.recordPendingOperation(initialOp)

        // New session / restarted repository
        val retrieved = ledgerRepository.getPendingOperationByIntentId(sharedIntentId)
        assertNotNull("Pending operation must be preserved across sessions", retrieved)
        assertEquals("PENDING", retrieved?.status)
        assertEquals(txId, retrieved?.businessTransactionId)

        // Resubmission with same intent returns the existing pending record
        val resubmitted = ledgerRepository.recordPendingOperation(initialOp)
        assertEquals(txId, resubmitted.businessTransactionId)
        assertEquals(1, pendingDao.getAllOneShot().filter { it.operationIntentId == sharedIntentId }.size)
    }
}
