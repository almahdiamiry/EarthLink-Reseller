package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.*
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.EarthlinkGateway
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Workstream 10 Certification Test: G1 Lock Unification and Resolution Concurrency.
 *
 * Verifies:
 * 1. Single repository entry point: resolvePendingOperationSerialized
 * 2. Re-read under lock: already resolved operations return idempotent outcome without second ISP verification
 * 3. Same account + same operation concurrent calls: only 1 verification occurs, both callers get valid result
 * 4. Same account + different operations concurrent calls: serialized per-account execution
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Workstream10LockUnificationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var ledgerRepo: LocalLedgerRepositoryImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        ledgerRepo = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = db.localLedgerEntryDao(),
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private class CountingFakeGateway(
        private val usernameAvailableVal: Boolean = false,
        private val delayMs: Long = 50L
    ) : EarthlinkGateway {
        val checkAvailabilityCount = AtomicInteger(0)
        val searchUsersCount = AtomicInteger(0)
        val getUserDetailCount = AtomicInteger(0)

        override suspend fun login(username: String, password: String): LoginResponse =
            LoginResponse(accessToken = "token", tokenType = "Bearer", expiresIn = 3600)

        override suspend fun getBalance(): Double = 1000000.0
        override suspend fun getTestUsersCount(affiliateIndex: Int?): Int = 0
        override suspend fun getActiveTestUsersCount(): Int = 0
        override suspend fun getPrepaidNeeded(): Double = 0.0
        override suspend fun getPackages(): List<AccountPackage> = emptyList()
        override suspend fun getAccountCost(accountIndex: Int): Double = 40000.0

        override suspend fun searchUsers(query: String, startIndex: Int, rowCount: Int): UserListResponse {
            searchUsersCount.incrementAndGet()
            if (delayMs > 0) delay(delayMs)
            return UserListResponse(
                totalCount = 1,
                itemsList = listOf(
                    UserListItem(
                        userIndexLower = 101,
                        userIDLower = query
                    )
                )
            )
        }

        override suspend fun getUserDetail(userIndex: Int): UserDetail {
            getUserDetailCount.incrementAndGet()
            if (delayMs > 0) delay(delayMs)
            return UserDetail(
                userIndexLower = userIndex,
                userIDLower = "test_user",
                userActiveLower = true,
                expirationDateLower = "2026-09-01 00:00:00"
            )
        }

        override suspend fun autocompleteUser(query: String): List<AutocompleteUser> = emptyList()

        override suspend fun checkUsernameAvailable(userId: String): Boolean {
            checkAvailabilityCount.incrementAndGet()
            if (delayMs > 0) delay(delayMs)
            return usernameAvailableVal
        }

        override suspend fun checkCustomerByPhone(phone: String): String? = null
        override suspend fun createCustomer(name: String, phone: String): Boolean = true
        override suspend fun createTestUser(username: String, phone: String, fullName: String, accountIndex: Int): String? = "pass"
        override suspend fun createUserUsingDeposit(username: String, phone: String, fullName: String, accountIndex: Int, depositPassword: String): String? = "pass"
        override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean = true
        override suspend fun extendUser(userIndex: Int): Boolean = true
        override suspend fun getAccountStatement(startIndex: Int, rowCount: Int, query: String): List<AccountStatementItem> = emptyList()
        override suspend fun showUserPassword(userIndex: Int, userId: String): String = "pass"
        override suspend fun showAccountPassword(userIndex: Int, userId: String): String = "pass"
        override suspend fun changeUserPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
        override suspend fun changeAccountPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
        override suspend fun toggleUserActive(userIndex: Int, active: Boolean): Boolean = true
        override suspend fun changeAccountType(userIndex: Int, userId: String, accountIndex: Int): Boolean = true
        override suspend fun updateUserDisplayName(userIndex: Int, newName: String): Boolean = true
        override fun addCustomStatement(statement: AccountStatementItem) {}
    }

    @Test
    fun testSameAccount_SameOperation_ConcurrentResolution_OnlyOneIspCall() = runBlocking {
        val account = LocalAccount(
            id = "acc_lock_001",
            displayName = "Lock Test User 1",
            earthlinkUsername = "lockuser1",
            debtIqd = 0.0
        )
        db.localAccountDao().insert(account)

        val txId = "tx_lock_concurrent_001"
        val pendingOp = PendingExternalOperation(
            operationIntentId = "intent_001",
            businessTransactionId = txId,
            accountId = "acc_lock_001",
            operationType = "ACTIVATION",
            amountIqd = 45000L,
            status = "PENDING_UNKNOWN",
            payloadJson = "{\"username\":\"lockuser1\"}",
            createdAt = System.currentTimeMillis()
        )
        ledgerRepo.recordPendingOperation(pendingOp)

        val fakeGateway = CountingFakeGateway(usernameAvailableVal = false, delayMs = 100L)

        // Launch concurrent UI resolution and Background Sweep resolution simultaneously
        val deferred1 = async(Dispatchers.IO) {
            ledgerRepo.resolvePendingOperationSerialized(txId, fakeGateway)
        }
        val deferred2 = async(Dispatchers.IO) {
            delay(10) // slight offset while first is inflight under lock
            ledgerRepo.resolvePendingOperationSerialized(txId, fakeGateway)
        }

        val res1 = deferred1.await()
        val res2 = deferred2.await()

        // Both callers must receive VERIFIED_SUCCESS
        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, res1.result)
        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, res2.result)

        // Exactly ONE network verification must have occurred at the gateway!
        assertEquals("ISP checkUsernameAvailable must be called exactly once despite concurrent resolutions", 1, fakeGateway.checkAvailabilityCount.get())

        // The second caller observed the completed state under the lock
        assertEquals("Operation was already confirmed successful", res2.diagnosticMessage)

        // For ACTIVATION, status becomes COMPLETED
        val updatedOp = ledgerRepo.getPendingOperationByTransactionId(txId)
        assertEquals("COMPLETED", updatedOp?.status)
    }

    @Test
    fun testSameAccount_DifferentOperations_ConcurrentResolution_SerializedPerAccount() = runBlocking {
        val account = LocalAccount(
            id = "acc_lock_002",
            displayName = "Lock Test User 2",
            earthlinkUsername = "lockuser2",
            debtIqd = 0.0
        )
        db.localAccountDao().insert(account)

        val txId1 = "tx_lock_act_002"
        val pendingOp1 = PendingExternalOperation(
            operationIntentId = "intent_002_1",
            businessTransactionId = txId1,
            accountId = "acc_lock_002",
            operationType = "ACTIVATION",
            amountIqd = 40000L,
            status = "PENDING_UNKNOWN",
            payloadJson = "{\"username\":\"lockuser2\"}",
            createdAt = System.currentTimeMillis() - 2000L
        )
        val txId2 = "tx_lock_ren_002"
        val pendingOp2 = PendingExternalOperation(
            operationIntentId = "intent_002_2",
            businessTransactionId = txId2,
            accountId = "acc_lock_002",
            operationType = "RENEWAL",
            amountIqd = 35000L,
            status = "PENDING_UNKNOWN",
            payloadJson = "{\"username\":\"lockuser2\"}",
            createdAt = System.currentTimeMillis() - 1000L
        )

        ledgerRepo.recordPendingOperation(pendingOp1)
        ledgerRepo.recordPendingOperation(pendingOp2)

        val fakeGateway = CountingFakeGateway(usernameAvailableVal = false, delayMs = 50L)

        // Launch concurrent resolutions for two distinct operations on the SAME account
        val deferred1 = async(Dispatchers.IO) {
            ledgerRepo.resolvePendingOperationSerialized(txId1, fakeGateway)
        }
        val deferred2 = async(Dispatchers.IO) {
            ledgerRepo.resolvePendingOperationSerialized(txId2, fakeGateway)
        }

        val res1 = deferred1.await()
        val res2 = deferred2.await()

        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, res1.result)
        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, res2.result)

        // Both operations must have completed safely under per-account serialization
        val updatedOp1 = ledgerRepo.getPendingOperationByTransactionId(txId1)
        val updatedOp2 = ledgerRepo.getPendingOperationByTransactionId(txId2)
        assertEquals("COMPLETED", updatedOp1?.status)
        assertEquals("COMPLETED", updatedOp2?.status)

        val ledgerEntries = db.localLedgerEntryDao().getByAccountIdOneShot("acc_lock_002")
        assertEquals(2, ledgerEntries.size) // Both ACTIVATION and RENEWAL created verified ledger entries
        assertTrue(ledgerEntries.any { it.id == txId1 })
        assertTrue(ledgerEntries.any { it.id == txId2 })
    }

    @Test
    fun testRereadUnderLock_AlreadyCompleted_ReturnsDirectlyWithoutGatewayCalls() = runBlocking {
        val account = LocalAccount(
            id = "acc_lock_003",
            displayName = "Lock Test User 3",
            earthlinkUsername = "lockuser3",
            debtIqd = 50000.0
        )
        db.localAccountDao().insert(account)

        val txId = "tx_lock_already_done"
        val pendingOp = PendingExternalOperation(
            operationIntentId = "intent_003",
            businessTransactionId = txId,
            accountId = "acc_lock_003",
            operationType = "ACTIVATION",
            amountIqd = 50000L,
            status = "COMPLETED",
            payloadJson = "{\"username\":\"lockuser3\"}",
            createdAt = System.currentTimeMillis()
        )
        ledgerRepo.recordPendingOperation(pendingOp)

        val fakeGateway = CountingFakeGateway(usernameAvailableVal = false, delayMs = 0L)

        val resolution = ledgerRepo.resolvePendingOperationSerialized(txId, fakeGateway)

        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, resolution.result)
        assertEquals("Operation was already confirmed successful", resolution.diagnosticMessage)
        assertEquals(0, fakeGateway.checkAvailabilityCount.get())
        assertEquals(0, fakeGateway.searchUsersCount.get())
    }

    @Test
    fun testRereadUnderLock_AlreadyFailed_ReturnsDirectlyWithoutGatewayCalls() = runBlocking {
        val account = LocalAccount(
            id = "acc_lock_004",
            displayName = "Lock Test User 4",
            earthlinkUsername = "lockuser4",
            debtIqd = 0.0
        )
        db.localAccountDao().insert(account)

        val txId = "tx_lock_already_failed"
        val pendingOp = PendingExternalOperation(
            operationIntentId = "intent_004",
            businessTransactionId = txId,
            accountId = "acc_lock_004",
            operationType = "ACTIVATION",
            amountIqd = 50000L,
            status = "FAILED",
            lastError = "Subscriber does not exist on ISP",
            payloadJson = "{\"username\":\"lockuser4\"}",
            createdAt = System.currentTimeMillis()
        )
        ledgerRepo.recordPendingOperation(pendingOp)

        val fakeGateway = CountingFakeGateway(usernameAvailableVal = false, delayMs = 0L)

        val resolution = ledgerRepo.resolvePendingOperationSerialized(txId, fakeGateway)

        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_FAILURE, resolution.result)
        assertEquals("Subscriber does not exist on ISP", resolution.diagnosticMessage)
        assertEquals(0, fakeGateway.checkAvailabilityCount.get())
    }
}
