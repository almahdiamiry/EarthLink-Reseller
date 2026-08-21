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
import com.example.core.network.*
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.EarthlinkGateway
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
class Step2OutcomeResolutionTest {

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

    // Helper mock gateway
    private open class FakeGateway(
        var checkUsernameAvailableResult: Boolean = true,
        var userDetailResult: UserDetail = UserDetail(userIndexLower = 101, userIDLower = "user1", accountStatusLower = "Active", activeDaysLeftLower = 30.0),
        var statementsResult: List<AccountStatementItem> = emptyList(),
        var searchUsersResult: UserListResponse = UserListResponse(itemsList = emptyList())
    ) : EarthlinkGateway {
        override suspend fun login(username: String, password: String): LoginResponse = throw NotImplementedError()
        override suspend fun getBalance(): Double = 100000.0
        override suspend fun getTestUsersCount(affiliateIndex: Int?): Int = 0
        override suspend fun getActiveTestUsersCount(): Int = 0
        override suspend fun getPrepaidNeeded(): Double = 0.0
        override suspend fun getPackages(): List<AccountPackage> = emptyList()
        override suspend fun getAccountCost(accountIndex: Int): Double = 35000.0
        override suspend fun searchUsers(query: String, startIndex: Int, rowCount: Int): UserListResponse = searchUsersResult
        override suspend fun getUserDetail(userIndex: Int): UserDetail = userDetailResult
        override suspend fun autocompleteUser(query: String): List<AutocompleteUser> = emptyList()
        override suspend fun checkUsernameAvailable(userId: String): Boolean = checkUsernameAvailableResult
        override suspend fun checkCustomerByPhone(phone: String): String? = null
        override suspend fun createCustomer(name: String, phone: String): Boolean = true
        override suspend fun createTestUser(username: String, phone: String, fullName: String, accountIndex: Int): String? = "pass123"
        override suspend fun createUserUsingDeposit(username: String, phone: String, fullName: String, accountIndex: Int, depositPassword: String): String? = "pass456"
        override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean = true
        override suspend fun extendUser(userIndex: Int): Boolean = true
        override suspend fun getAccountStatement(startIndex: Int, rowCount: Int, query: String): List<AccountStatementItem> = statementsResult
        override suspend fun showUserPassword(userIndex: Int, userId: String): String = "pass"
        override suspend fun showAccountPassword(userIndex: Int, userId: String): String = "pass"
        override suspend fun changeUserPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
        override suspend fun changeAccountPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
        override suspend fun toggleUserActive(userIndex: Int, active: Boolean): Boolean = true
        override suspend fun changeAccountType(userIndex: Int, userId: String, accountIndex: Int): Boolean = true
        override suspend fun updateUserDisplayName(userIndex: Int, newName: String): Boolean = true
    }

    /**
     * TEST-06: testDefinitiveSuccessBypassesResolving
     * Direct invocation of resolvePendingOperationVerifiedSuccess transitions directly to COMPLETED
     * without writing intermediate RESOLVING state.
     */
    @Test
    fun testDefinitiveSuccessBypassesResolving() = runBlocking {
        val txId = "tx_" + UUID.randomUUID()
        val accountId = "acc_test06"
        val account = LocalAccount(id = accountId, displayName = "Test 06 Account", debtIqd = 0.0)
        accountDao.insert(account)

        val pending = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = "intent_06",
            accountId = accountId,
            operationType = "REFILL",
            amountIqd = 35000L,
            status = "PENDING"
        )
        ledgerRepository.recordPendingOperation(pending)

        // Definitive success resolution
        val ledger = ledgerRepository.resolvePendingOperationVerifiedSuccess(txId, "[REFILL SUCCESS]")
        assertNotNull(ledger)
        assertEquals(35000.0, ledger?.amountIqd ?: 0.0, 0.001)

        val savedOp = pendingDao.getByBusinessTransactionId(txId)
        assertEquals("COMPLETED", savedOp?.status)
    }

    /**
     * TEST-07: testG1FFallbackDeleted
     * Null baseline expiration does NOT auto-complete; routes to 4-tuple statement check.
     * When statement list is empty, resolution is INCONCLUSIVE (remains PENDING, zero ledger).
     */
    @Test
    fun testG1FFallbackDeleted() = runBlocking {
        val txId = "tx_" + UUID.randomUUID()
        val accountId = "user_test07"
        val account = LocalAccount(id = accountId, earthlinkUsername = accountId, displayName = "Test 07 Account", debtIqd = 0.0)
        accountDao.insert(account)

        val now = System.currentTimeMillis()
        val pending = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = "intent_07",
            accountId = accountId,
            operationType = "REFILL",
            amountIqd = 35000L,
            status = "PENDING",
            createdAt = now
        )
        ledgerRepository.recordPendingOperation(pending)

        val fakeGateway = FakeGateway(
            searchUsersResult = UserListResponse(itemsList = listOf(UserListItem(userIndexLower = 107, userIDLower = accountId))),
            userDetailResult = UserDetail(userIndexLower = 107, userIDLower = accountId, accountStatusLower = "Active", expirationDateLower = "2026-10-01T00:00:00"),
            statementsResult = emptyList() // No statement matches
        )

        // baselineExpirationDate is NULL
        val resolution = ledgerRepository.verifyAndResolvePendingOperation(txId, fakeGateway, baselineExpirationDate = null)

        assertEquals(UnknownOutcomeResolutionResult.INCONCLUSIVE, resolution.result)
        assertNull(resolution.ledgerEntry)

        val savedOp = pendingDao.getByBusinessTransactionId(txId)
        assertEquals("PENDING", savedOp?.status)

        // Zero ledger charge
        val updatedAccount = accountDao.getByIdOneShot(accountId)
        assertEquals(0.0, updatedAccount?.debtIqd ?: 0.0, 0.001)
    }

    /**
     * TEST-08: testActivationSuspendedResolvesFailure
     * When activation recovery checks ISP and finds username still available (or SUSPENDED),
     * it resolves directly to FAILED with zero ledger materialization.
     */
    @Test
    fun testActivationSuspendedResolvesFailure() = runBlocking {
        val txId = "tx_" + UUID.randomUUID()
        val accountId = "user_test08"
        val account = LocalAccount(id = accountId, earthlinkUsername = accountId, displayName = "Test 08 Account", debtIqd = 0.0)
        accountDao.insert(account)

        val pending = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = "intent_08",
            accountId = accountId,
            operationType = "ACTIVATION",
            amountIqd = 40000L,
            status = "PENDING"
        )
        ledgerRepository.recordPendingOperation(pending)

        // Username is available on ISP -> proves non-execution
        val fakeGateway = FakeGateway(checkUsernameAvailableResult = true)

        val resolution = ledgerRepository.verifyAndResolvePendingOperation(txId, fakeGateway)

        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_FAILURE, resolution.result)
        assertNull(resolution.ledgerEntry)

        val savedOp = pendingDao.getByBusinessTransactionId(txId)
        assertEquals("FAILED", savedOp?.status)

        // Zero ledger charge
        val updatedAccount = accountDao.getByIdOneShot(accountId)
        assertEquals(0.0, updatedAccount?.debtIqd ?: 0.0, 0.001)
    }

    /**
     * TEST-09: testAccountStatementExactMatchSuccess
     * Matching 4-tuple within ±90s resolves VERIFIED_SUCCESS + ledger entry.
     */
    @Test
    fun testAccountStatementExactMatchSuccess() = runBlocking {
        val txId = "tx_" + UUID.randomUUID()
        val accountId = "user_test09"
        val account = LocalAccount(id = accountId, earthlinkUsername = accountId, displayName = "Test 09 Account", debtIqd = 0.0)
        accountDao.insert(account)

        val createdAt = 1700000000000L // arbitrary fixed time
        val pending = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = "intent_09",
            accountId = accountId,
            operationType = "REFILL",
            amountIqd = 35000L,
            status = "PENDING",
            createdAt = createdAt
        )
        ledgerRepository.recordPendingOperation(pending)

        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val statementDateStr = sdf.format(java.util.Date(createdAt + 15_000L)) // +15s inside window

        val matchingStatement = AccountStatementItem(
            occurredAt = statementDateStr,
            operation = "Withdraw",
            withdrawalAmount = 35000.0,
            userIDLower = accountId,
            note = "Refill for $accountId"
        )

        val fakeGateway = FakeGateway(
            searchUsersResult = UserListResponse(itemsList = listOf(UserListItem(userIndexLower = 109, userIDLower = accountId))),
            userDetailResult = UserDetail(userIndexLower = 109, userIDLower = accountId, accountStatusLower = "Active"),
            statementsResult = listOf(matchingStatement)
        )

        val resolution = ledgerRepository.verifyAndResolvePendingOperation(txId, fakeGateway, baselineExpirationDate = null)

        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, resolution.result)
        assertNotNull(resolution.ledgerEntry)
        assertEquals(35000.0, resolution.ledgerEntry?.amountIqd ?: 0.0, 0.001)

        val savedOp = pendingDao.getByBusinessTransactionId(txId)
        assertEquals("COMPLETED", savedOp?.status)

        val updatedAccount = accountDao.getByIdOneShot(accountId)
        assertEquals(35000.0, updatedAccount?.debtIqd ?: 0.0, 0.001)
    }

    /**
     * TEST-10: testAccountStatementAmbiguousRemainsInconclusive
     * 2 matching statement rows in the window resolve INCONCLUSIVE + zero ledger entry.
     */
    @Test
    fun testAccountStatementAmbiguousRemainsInconclusive() = runBlocking {
        val txId = "tx_" + UUID.randomUUID()
        val accountId = "user_test10"
        val account = LocalAccount(id = accountId, earthlinkUsername = accountId, displayName = "Test 10 Account", debtIqd = 0.0)
        accountDao.insert(account)

        val createdAt = 1700000000000L
        val pending = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = "intent_10",
            accountId = accountId,
            operationType = "REFILL",
            amountIqd = 35000L,
            status = "PENDING",
            createdAt = createdAt
        )
        ledgerRepository.recordPendingOperation(pending)

        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")

        val match1 = AccountStatementItem(
            occurredAt = sdf.format(java.util.Date(createdAt + 10_000L)),
            operation = "Withdraw",
            withdrawalAmount = 35000.0,
            userIDLower = accountId
        )
        val match2 = AccountStatementItem(
            occurredAt = sdf.format(java.util.Date(createdAt + 20_000L)),
            operation = "Withdraw",
            withdrawalAmount = 35000.0,
            userIDLower = accountId
        )

        val fakeGateway = FakeGateway(
            searchUsersResult = UserListResponse(itemsList = listOf(UserListItem(userIndexLower = 110, userIDLower = accountId))),
            userDetailResult = UserDetail(userIndexLower = 110, userIDLower = accountId, accountStatusLower = "Active"),
            statementsResult = listOf(match1, match2) // AMBIGUOUS MULTIPLE
        )

        val resolution = ledgerRepository.verifyAndResolvePendingOperation(txId, fakeGateway, baselineExpirationDate = null)

        assertEquals(UnknownOutcomeResolutionResult.INCONCLUSIVE, resolution.result)
        assertNull(resolution.ledgerEntry)

        val savedOp = pendingDao.getByBusinessTransactionId(txId)
        assertEquals("PENDING", savedOp?.status)

        val updatedAccount = accountDao.getByIdOneShot(accountId)
        assertEquals(0.0, updatedAccount?.debtIqd ?: 0.0, 0.001)
    }

    /**
     * TEST-11: testUnrelatedLaterOperationWindowRejection
     * Transaction at +600s is rejected by the ±90s window filter -> INCONCLUSIVE.
     */
    @Test
    fun testUnrelatedLaterOperationWindowRejection() = runBlocking {
        val txId = "tx_" + UUID.randomUUID()
        val accountId = "user_test11"
        val account = LocalAccount(id = accountId, earthlinkUsername = accountId, displayName = "Test 11 Account", debtIqd = 0.0)
        accountDao.insert(account)

        val createdAt = 1700000000000L
        val pending = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = "intent_11",
            accountId = accountId,
            operationType = "REFILL",
            amountIqd = 35000L,
            status = "PENDING",
            createdAt = createdAt
        )
        ledgerRepository.recordPendingOperation(pending)

        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")

        // Transaction occurred 10 minutes (600s) later
        val outsideWindowMatch = AccountStatementItem(
            occurredAt = sdf.format(java.util.Date(createdAt + 600_000L)),
            operation = "Withdraw",
            withdrawalAmount = 35000.0,
            userIDLower = accountId
        )

        val fakeGateway = FakeGateway(
            searchUsersResult = UserListResponse(itemsList = listOf(UserListItem(userIndexLower = 111, userIDLower = accountId))),
            userDetailResult = UserDetail(userIndexLower = 111, userIDLower = accountId, accountStatusLower = "Active"),
            statementsResult = listOf(outsideWindowMatch)
        )

        val resolution = ledgerRepository.verifyAndResolvePendingOperation(txId, fakeGateway, baselineExpirationDate = null)

        assertEquals(UnknownOutcomeResolutionResult.INCONCLUSIVE, resolution.result)
        assertNull(resolution.ledgerEntry)

        val savedOp = pendingDao.getByBusinessTransactionId(txId)
        assertEquals("PENDING", savedOp?.status)

        val updatedAccount = accountDao.getByIdOneShot(accountId)
        assertEquals(0.0, updatedAccount?.debtIqd ?: 0.0, 0.001)
    }

    /**
     * TEST-15 (VM Level): testViewModelTransportExceptionPreservesPendingStatus
     * When ViewModel calls refillUser and EarthlinkTransportException occurs,
     * the pending operation is resolved INCONCLUSIVE (remains PENDING), NOT FAILED.
     */
    @Test
    fun testViewModelTransportExceptionPreservesPendingStatus() = runBlocking {
        val app = context as EarthlinkApp
        val accountId = "user_vm_transport"
        val account = LocalAccount(id = accountId, earthlinkUsername = accountId, displayName = "VM Test", debtIqd = 0.0)
        accountDao.insert(account)

        val throwingGateway = object : FakeGateway() {
            override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean {
                throw EarthlinkTransportException("Socket timeout while contacting ISP gateway")
            }
        }

        val viewModel = com.example.ui.viewmodels.EarthlinkSearchViewModel(
            gateway = throwingGateway,
            audit = app.auditRepository,
            prefs = app.preferenceManager,
            localAccountRepository = app.localAccountRepository,
            localLedgerRepository = ledgerRepository
        )

        val intentId = "intent_vm_trans_" + UUID.randomUUID()
        val job = viewModel.refillUser(
            userId = accountId,
            depositPass = "dep_pass",
            price = 35000.0,
            intentId = intentId
        )
        job.join()

        val savedOp = pendingDao.getByOperationIntentId(intentId)
        assertNotNull(savedOp)
        // Must be PENDING (inconclusive), NEVER collapsed to FAILED
        assertEquals("PENDING", savedOp?.status)
        assertEquals(35000L, savedOp?.amountIqd)
    }

    /**
     * TEST (VM Level): testViewModelBusinessExceptionResolvesFailed
     * When ViewModel calls refillUser and EarthlinkBusinessException occurs (e.g. invalid password),
     * the pending operation is resolved to FAILED.
     */
    @Test
    fun testViewModelBusinessExceptionResolvesFailed() = runBlocking {
        val app = context as EarthlinkApp
        val accountId = "user_vm_biz"
        val account = LocalAccount(id = accountId, earthlinkUsername = accountId, displayName = "VM Biz Test", debtIqd = 0.0)
        accountDao.insert(account)

        val throwingGateway = object : FakeGateway() {
            override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean {
                throw EarthlinkBusinessException(400, "Wrong deposit password")
            }
        }

        val viewModel = com.example.ui.viewmodels.EarthlinkSearchViewModel(
            gateway = throwingGateway,
            audit = app.auditRepository,
            prefs = app.preferenceManager,
            localAccountRepository = app.localAccountRepository,
            localLedgerRepository = ledgerRepository
        )

        val intentId = "intent_vm_biz_" + UUID.randomUUID()
        val job = viewModel.refillUser(
            userId = accountId,
            depositPass = "wrong_pass",
            price = 35000.0,
            intentId = intentId
        )
        job.join()

        val savedOp = pendingDao.getByOperationIntentId(intentId)
        assertNotNull(savedOp)
        assertEquals("FAILED", savedOp?.status)
    }
}

