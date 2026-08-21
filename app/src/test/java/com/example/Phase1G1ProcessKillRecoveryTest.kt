package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.*
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

/**
 * Phase 1 G1 Process Kill Recovery Certification Test (Workstream 4 / RC-08 / INV-11).
 *
 * Verifies that:
 * 1. ISP operations that recorded a PENDING record survive process restarts.
 * 2. The startup / SyncWorker sweep (sweepAndResolvePendingOperations) recovers unresolved operations.
 * 3. Confirmed external operations materialize local ledger entries idempotently with exact businessTransactionId.
 * 4. Duplicate sweep executions are idempotent and do not duplicate ledger entries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase1G1ProcessKillRecoveryTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var ledgerRepository: LocalLedgerRepository
    private lateinit var fakeGateway: EarthlinkGateway

    class TestGateway : EarthlinkGateway {
        override suspend fun login(username: String, password: String): LoginResponse =
            LoginResponse(accessToken = "test_token", tokenType = "Bearer", expiresIn = 3600)
        override suspend fun getBalance(): Double = 500000.0
        override suspend fun getTestUsersCount(affiliateIndex: Int?): Int = 0
        override suspend fun getActiveTestUsersCount(): Int = 0
        override suspend fun getPrepaidNeeded(): Double = 0.0
        override suspend fun getPackages(): List<AccountPackage> = emptyList()
        override suspend fun getAccountCost(accountIndex: Int): Double = 35000.0
        override suspend fun searchUsers(query: String, startIndex: Int, rowCount: Int): UserListResponse =
            UserListResponse(itemsList = listOf(UserListItem(userIndexLower = 1, userIDLower = "test_user_kill_001")), totalCount = 1)
        override suspend fun getUserDetail(userIndex: Int): UserDetail =
            UserDetail(userIndexLower = 1, userIDLower = "test_user_kill_001", userActiveLower = true, activeDaysLeftLower = 30)
        override suspend fun autocompleteUser(query: String): List<AutocompleteUser> = emptyList()
        override suspend fun checkUsernameAvailable(userId: String): Boolean = false
        override suspend fun checkCustomerByPhone(phone: String): String? = null
        override suspend fun createCustomer(name: String, phone: String): Boolean = true
        override suspend fun createTestUser(username: String, phone: String, fullName: String, accountIndex: Int): String? = "pass"
        override suspend fun createUserUsingDeposit(username: String, phone: String, fullName: String, accountIndex: Int, depositPassword: String): String? = "pass"
        override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean = true
        override suspend fun extendUser(userIndex: Int): Boolean = true
        override suspend fun getAccountStatement(startIndex: Int, rowCount: Int, query: String): List<AccountStatementItem> {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val dateStr = sdf.format(java.util.Date(System.currentTimeMillis() - 10000L))
            return listOf(
                AccountStatementItem(
                    occurredAt = dateStr,
                    operation = "Withdraw",
                    depositAmount = 0.0,
                    withdrawalAmount = 35000.0,
                    userIDLower = query,
                    note = "Refill for $query"
                )
            )
        }
        override suspend fun showUserPassword(userIndex: Int, userId: String): String = "pass"
        override suspend fun showAccountPassword(userIndex: Int, userId: String): String = "pass"
        override suspend fun changeUserPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
        override suspend fun changeAccountPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
        override suspend fun changeAccountType(userIndex: Int, userId: String, accountIndex: Int): Boolean = true
        override suspend fun toggleUserActive(userIndex: Int, active: Boolean): Boolean = true
        override suspend fun updateUserDisplayName(userIndex: Int, newName: String): Boolean = true
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        ledgerRepository = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = db.localLedgerEntryDao(),
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao()
        )

        val existingAccount = LocalAccount(
            id = "test_user_kill_001",
            earthlinkUsername = "test_user_kill_001",
            displayName = "Process Kill Test User",
            currentPriceIqd = 35000.0,
            debtIqd = 0.0,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        runBlocking {
            db.localAccountDao().insert(existingAccount)
        }

        fakeGateway = TestGateway()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testProcessKillRecovery_recoversPendingOperation_andMaterializesLedgerEntry() = runBlocking {
        val intentId = "intent_kill_001"
        val businessTxId = "tx_intent_kill_001"
        val accountId = "test_user_kill_001"

        // 1. Record pending operation prior to network call (simulating process start)
        val op = PendingExternalOperation(
            operationIntentId = intentId,
            businessTransactionId = businessTxId,
            accountId = accountId,
            operationType = "RENEWAL",
            amountIqd = 35000L,
            status = "PENDING",
            dispatchClaimCount = 1,
            createdAt = System.currentTimeMillis() - 10000L
        )
        ledgerRepository.recordPendingOperation(op)

        // Verify operation is pending before process kill / recovery
        val unresolvedBefore = ledgerRepository.getUnresolvedPendingOperations()
        assertEquals(1, unresolvedBefore.size)
        assertEquals(businessTxId, unresolvedBefore.first().businessTransactionId)

        // 2. Simulate process recovery sweep (graceWindowMs = 0 to process immediately)
        val resolutions = ledgerRepository.sweepAndResolvePendingOperations(fakeGateway, graceWindowMs = 0L)

        // 3. Verify sweep resolved the operation to VERIFIED_SUCCESS
        assertEquals(1, resolutions.size)
        val resolution = resolutions.first()
        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, resolution.result)
        assertNotNull(resolution.ledgerEntry)
        assertEquals(businessTxId, resolution.ledgerEntry?.id)

        // 4. Verify pending operation status in DB is now COMPLETED
        val pendingAfter = db.pendingExternalOperationDao().getByBusinessTransactionId(businessTxId)
        assertNotNull(pendingAfter)
        assertEquals("COMPLETED", pendingAfter?.status)

        // 5. Verify ledger entry exists in DB
        val ledgerInDb = db.localLedgerEntryDao().getByIdOneShot(businessTxId)
        assertNotNull(ledgerInDb)
        assertEquals(35000.0, ledgerInDb?.amountIqd)

        // 6. Verify subsequent sweep runs are idempotent and return empty or already-completed resolutions
        val secondSweep = ledgerRepository.sweepAndResolvePendingOperations(fakeGateway, graceWindowMs = 0L)
        assertTrue(secondSweep.isEmpty())

        // Verify ledger entry count is still 1 (no duplication)
        val allLedgers = db.localLedgerEntryDao().getByAccountIdOneShot(accountId)
        assertEquals(1, allLedgers.size)
    }
}
