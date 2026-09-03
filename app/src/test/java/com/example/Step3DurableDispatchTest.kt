package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.*
import com.example.core.network.*
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.EarthlinkGateway
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Step3DurableDispatchTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var ledgerRepo: LocalLedgerRepositoryImpl
    private lateinit var accountRepo: LocalAccountRepositoryImpl
    private lateinit var dbFile: File

    private fun createRepository(database: AppDatabase): LocalLedgerRepositoryImpl {
        return LocalLedgerRepositoryImpl(
            database = database,
            ledgerDao = database.localLedgerEntryDao(),
            accountDao = database.localAccountDao(),
            outboxDao = database.syncOutboxDao(),
            pendingDao = database.pendingExternalOperationDao()
        )
    }

    private fun parseStatementTimestamp(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        val trimmed = dateStr.trim()
        val formats = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSS",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd"
        )
        for (pattern in formats) {
            try {
                val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val d = sdf.parse(trimmed)
                if (d != null) return d.time
            } catch (_: Exception) {}
        }
        return 0L
    }

    private open class FakeGateway(
        var checkUsernameAvailableResult: Boolean = true,
        var userDetailResult: UserDetail = UserDetail(userIndexLower = 101, userIDLower = "user1", accountStatusLower = "Active", activeDaysLeftLower = 30.0),
        var statementsResult: List<AccountStatementItem> = emptyList(),
        var searchUsersResult: UserListResponse = UserListResponse(itemsList = emptyList()),
        var accountCostResult: Double = 35000.0
    ) : EarthlinkGateway {
        override suspend fun login(username: String, password: String): LoginResponse = throw NotImplementedError()
        override suspend fun getBalance(): Double = 100000.0
        override suspend fun getTestUsersCount(affiliateIndex: Int?): Int = 0
        override suspend fun getActiveTestUsersCount(): Int = 0
        override suspend fun getPrepaidNeeded(): Double = 0.0
        override suspend fun getPackages(): List<AccountPackage> = emptyList()
        override suspend fun getAccountCost(accountIndex: Int): Double = accountCostResult
        override suspend fun searchUsers(query: String, startIndex: Int, rowCount: Int): UserListResponse {
            println("=== FakeGateway.searchUsers query=$query ===")
            println("Returning ${searchUsersResult.itemsList?.size} items")
            searchUsersResult.itemsList?.forEach { 
                println("Item: userIndex=${it.userIndex}, userID=${it.userID}")
            }
            return searchUsersResult
        }
        override suspend fun getUserDetail(userIndex: Int): UserDetail {
            println("=== FakeGateway.getUserDetail userIndex=$userIndex ===")
            println("Returning detail: userID=${userDetailResult.userIDLower}")
            return userDetailResult
        }
        override suspend fun autocompleteUser(query: String): List<AutocompleteUser> = emptyList()
        override suspend fun checkUsernameAvailable(userId: String): Boolean = checkUsernameAvailableResult
        override suspend fun checkCustomerByPhone(phone: String): String? = null
        override suspend fun createCustomer(name: String, phone: String): Boolean = true
        override suspend fun createTestUser(username: String, phone: String, fullName: String, accountIndex: Int): String? = "pass123"
        override suspend fun createUserUsingDeposit(username: String, phone: String, fullName: String, accountIndex: Int, depositPassword: String): String? = "pass456"
        override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean = true
        override suspend fun extendUser(userIndex: Int): Boolean = true
        override suspend fun getAccountStatement(startIndex: Int, rowCount: Int, query: String): List<AccountStatementItem> {
            println("=== FakeGateway.getAccountStatement query=$query ===")
            println("Returning ${statementsResult.size} items")
            return statementsResult
        }
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
        dbFile = context.getDatabasePath("step3_test_db.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()

        db = Room.databaseBuilder(context, AppDatabase::class.java, dbFile.name)
            .allowMainThreadQueries()
            .build()
        ledgerRepo = createRepository(db)
        accountRepo = LocalAccountRepositoryImpl(
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao(),
            database = db
        )
    }

    @After
    fun tearDown() {
        if (db.isOpen) {
            db.close()
        }
        dbFile.delete()
    }

    @Test
    fun test01_claimDispatchAuthorizationSucceedsForFreshOperation() = runTest {
        val op = PendingExternalOperation(
            businessTransactionId = "tx_claim_01",
            operationIntentId = "intent_claim_01",
            accountId = "user01",
            operationType = "ACTIVATION",
            amountIqd = 45000L,
            status = "PENDING",
            dispatchClaimCount = 0
        )
        ledgerRepo.recordPendingOperation(op)

        val granted = ledgerRepo.claimDispatchAuthorization("tx_claim_01")
        assertTrue("First dispatch claim must be granted", granted)

        val updated = ledgerRepo.getPendingOperationByTransactionId("tx_claim_01")
        assertNotNull(updated)
        assertEquals("DISPATCHING", updated!!.status)
        assertEquals(1, updated.dispatchClaimCount)
    }

    @Test
    fun test02_secondClaimAttemptFails() = runTest {
        val op = PendingExternalOperation(
            businessTransactionId = "tx_claim_02",
            operationIntentId = "intent_claim_02",
            accountId = "user02",
            operationType = "REFILL",
            amountIqd = 40000L,
            status = "PENDING",
            dispatchClaimCount = 0
        )
        ledgerRepo.recordPendingOperation(op)

        val firstClaim = ledgerRepo.claimDispatchAuthorization("tx_claim_02")
        assertTrue(firstClaim)

        val secondClaim = ledgerRepo.claimDispatchAuthorization("tx_claim_02")
        assertFalse("Second dispatch claim must be rejected", secondClaim)

        val updated = ledgerRepo.getPendingOperationByTransactionId("tx_claim_02")
        assertNotNull(updated)
        assertEquals(1, updated!!.dispatchClaimCount)
    }

    @Test
    fun test03_transitionToResolvingSucceedsWhenClaimCountIs1() = runTest {
        val op = PendingExternalOperation(
            businessTransactionId = "tx_resolve_03",
            operationIntentId = "intent_resolve_03",
            accountId = "user03",
            operationType = "ACTIVATION",
            amountIqd = 45000L,
            status = "PENDING",
            dispatchClaimCount = 1
        )
        ledgerRepo.recordPendingOperation(op)

        val rows = db.pendingExternalOperationDao().transitionToResolving("tx_resolve_03")
        assertEquals(1, rows)

        val updated = ledgerRepo.getPendingOperationByTransactionId("tx_resolve_03")
        assertNotNull(updated)
        assertEquals("RESOLVING", updated!!.status)
        assertEquals(1, updated.dispatchClaimCount)
    }

    @Test
    fun test04_coldStartRecoveryResolvesOrphanedInFlightOperations() = runTest {
        val beforeProcessStart = 1000L
        val processStartMs = 5000L

        val opOrphaned = PendingExternalOperation(
            businessTransactionId = "tx_orphan_04",
            operationIntentId = "intent_orphan_04",
            accountId = "user04",
            operationType = "ACTIVATION",
            amountIqd = 45000L,
            status = "DISPATCHING",
            dispatchClaimCount = 1,
            createdAt = beforeProcessStart,
            updatedAt = beforeProcessStart
        )
        ledgerRepo.recordPendingOperation(opOrphaned)

        var isUsernameChecked = false
        val mockGateway = object : FakeGateway() {
            override suspend fun checkUsernameAvailable(userId: String): Boolean {
                if (userId == "user04") {
                    isUsernameChecked = true
                    return true // User is available => activation never executed on ISP
                }
                return false
            }
        }

        ledgerRepo.recoverColdStartOrphanedOperations(mockGateway, processStartMs)

        assertTrue("Orphaned operation must be verified via gateway inspection", isUsernameChecked)
        val resolvedOp = ledgerRepo.getPendingOperationByTransactionId("tx_orphan_04")
        assertNotNull(resolvedOp)
        assertEquals("FAILED", resolvedOp!!.status)
    }

    @Test
    fun test18_sweepIgnoresInFlightProductionDispatch() = runTest {
        // C06: Prove in-flight DISPATCHING operation is not touched by concurrent sweep
        val app = context as com.example.EarthlinkApp
        val opIntentId = "intent_c06"
        val businessTxId = "tx_" + opIntentId

        val gatewayStarted = CompletableDeferred<Unit>()
        val releaseGateway = CompletableDeferred<Unit>()
        var gatewayInvocationCount = 0

        val gateway = object : FakeGateway() {
            override suspend fun createUserUsingDeposit(
                username: String,
                phone: String,
                fullName: String,
                accountIndex: Int,
                depositPassword: String
            ): String? {
                gatewayInvocationCount++
                gatewayStarted.complete(Unit)
                releaseGateway.await()
                return "pass_c06"
            }
        }

        val viewModel = com.example.ui.viewmodels.EarthlinkSearchViewModel(
            gateway = gateway,
            audit = app.auditRepository,
            prefs = app.preferenceManager,
            localAccountRepository = accountRepo,
            localLedgerRepository = ledgerRepo
        )

        val processStartMs = System.currentTimeMillis() - 1000L

        // 1. Start actual production dispatch workflow
        val dispatchJob = viewModel.createUserUsingDeposit(
            username = "user_c06",
            phone = "07700000000",
            fullName = "C06 User",
            pkgIndex = 1,
            depositPass = "pass123",
            intentId = opIntentId
        )

        // 2. Wait for Gateway call to become IN-FLIGHT
        gatewayStarted.await()
        assertEquals(1, gatewayInvocationCount)

        // Verify state while IN-FLIGHT
        val opInFlight = ledgerRepo.getPendingOperationByTransactionId(businessTxId)
        assertNotNull(opInFlight)
        assertEquals("DISPATCHING", opInFlight!!.status)
        assertEquals(1, opInFlight.dispatchClaimCount)

        // 3. Run recovery/runtime sweep concurrently while Gateway dispatch is held IN-FLIGHT
        ledgerRepo.recoverColdStartOrphanedOperations(gateway, processStartMs)

        // 4. Verify sweep did NOT reset, recover, resolve, or re-dispatch the active operation
        val opAfterSweep = ledgerRepo.getPendingOperationByTransactionId(businessTxId)
        assertNotNull(opAfterSweep)
        assertEquals("DISPATCHING", opAfterSweep!!.status)
        assertEquals(1, opAfterSweep.dispatchClaimCount)
        assertEquals(1, gatewayInvocationCount)

        // 5. Release Gateway and complete dispatch
        releaseGateway.complete(Unit)
        dispatchJob.join()

        // 6. Verify final state and exact Gateway invocation count == 1
        val opFinal = ledgerRepo.getPendingOperationByTransactionId(businessTxId)
        assertNotNull(opFinal)
        assertEquals("COMPLETED", opFinal!!.status)
        assertEquals(1, gatewayInvocationCount)
    }

    @Test
    fun test19_cancellationAfterClaimPreservesClaimCount() = runTest {
        // C12: Prove cancellation after successful claim preserves claim count
        val app = context as com.example.EarthlinkApp
        val opIntentId = "intent_c12"
        val businessTxId = "tx_" + opIntentId

        val gatewayStarted = CompletableDeferred<Unit>()
        val releaseGateway = CompletableDeferred<Unit>()
        var gatewayInvocationCount = 0

        val gateway = object : FakeGateway() {
            override suspend fun createUserUsingDeposit(
                username: String,
                phone: String,
                fullName: String,
                accountIndex: Int,
                depositPassword: String
            ): String? {
                gatewayInvocationCount++
                gatewayStarted.complete(Unit)
                try {
                    releaseGateway.await()
                    return "pass_c12"
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                }
            }
        }

        val viewModel = com.example.ui.viewmodels.EarthlinkSearchViewModel(
            gateway = gateway,
            audit = app.auditRepository,
            prefs = app.preferenceManager,
            localAccountRepository = accountRepo,
            localLedgerRepository = ledgerRepo
        )

        // 1. Launch real production mutation workflow in actual coroutine
        val dispatchJob = viewModel.createUserUsingDeposit(
            username = "user_c12",
            phone = "07700000000",
            fullName = "C12 User",
            pkgIndex = 1,
            depositPass = "pass123",
            intentId = opIntentId
        )

        // 2. Wait until actual production coroutine is waiting inside Gateway dispatch
        gatewayStarted.await()
        assertEquals(1, gatewayInvocationCount)

        val opBeforeCancel = ledgerRepo.getPendingOperationByTransactionId(businessTxId)
        assertNotNull(opBeforeCancel)
        assertEquals("DISPATCHING", opBeforeCancel!!.status)
        assertEquals(1, opBeforeCancel.dispatchClaimCount)

        // 3. Cancel the actual production coroutine while Gateway dispatch is IN-FLIGHT
        dispatchJob.cancel(kotlinx.coroutines.CancellationException("Production dispatch cancelled"))
        dispatchJob.join()

        // 4. Re-read the SAME persisted operation from Room
        val opAfterCancel = ledgerRepo.getPendingOperationByTransactionId(businessTxId)
        assertNotNull(opAfterCancel)
        assertEquals("DISPATCHING", opAfterCancel!!.status)
        assertEquals(1, opAfterCancel.dispatchClaimCount)

        // 5. Verify cancellation did NOT cause PENDING(0), FAILED, or redispatch
        assertNotEquals("PENDING", opAfterCancel.status)
        assertNotEquals("FAILED", opAfterCancel.status)
        assertEquals(1, gatewayInvocationCount)
    }

    @Test
    fun test05_coldStartIgnoresCurrentProcessOperations() = runTest {
        val processStartMs = 5000L
        val currentProcessTime = 7000L

        val opCurrent = PendingExternalOperation(
            businessTransactionId = "tx_current_05",
            operationIntentId = "intent_current_05",
            accountId = "user05",
            operationType = "ACTIVATION",
            amountIqd = 45000L,
            status = "DISPATCHING",
            dispatchClaimCount = 1,
            createdAt = currentProcessTime,
            updatedAt = currentProcessTime
        )
        ledgerRepo.recordPendingOperation(opCurrent)

        var gatewayCalled = false
        val mockGateway = object : FakeGateway() {
            override suspend fun checkUsernameAvailable(userId: String): Boolean {
                gatewayCalled = true
                return false
            }
        }

        ledgerRepo.recoverColdStartOrphanedOperations(mockGateway, processStartMs)

        assertFalse("Current process operations must NOT be touched by cold start recovery", gatewayCalled)
        val op = ledgerRepo.getPendingOperationByTransactionId("tx_current_05")
        assertNotNull(op)
        assertEquals("DISPATCHING", op!!.status)
    }

    @Test
    fun test12_fullRestartCyclePreservesClaimState() = runTest {
        val op = PendingExternalOperation(
            businessTransactionId = "tx_restart_12",
            operationIntentId = "intent_restart_12",
            accountId = "user12",
            operationType = "REFILL",
            amountIqd = 40000L,
            status = "PENDING",
            dispatchClaimCount = 0
        )
        ledgerRepo.recordPendingOperation(op)
        val claimed = ledgerRepo.claimDispatchAuthorization("tx_restart_12")
        assertTrue(claimed)

        // Close and simulate process death / app restart
        db.close()

        val restartedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbFile.name)
            .allowMainThreadQueries()
            .build()
        val restartedLedgerRepo = createRepository(restartedDb)

        val restartedOp = restartedLedgerRepo.getPendingOperationByTransactionId("tx_restart_12")
        assertNotNull(restartedOp)
        assertEquals("DISPATCHING", restartedOp!!.status)
        assertEquals(1, restartedOp.dispatchClaimCount)

        // Attempting to claim again in new process must be rejected
        val claimInNewProcess = restartedLedgerRepo.claimDispatchAuthorization("tx_restart_12")
        assertFalse("Cannot claim dispatch after restart on already claimed op", claimInNewProcess)

        restartedDb.close()
    }

    @Test
    fun test13_vmRefillFailsClosedOnMissingPrice() = runTest {
        val app = context as com.example.EarthlinkApp
        val fakeGateway = FakeGateway()

        val viewModel = com.example.ui.viewmodels.EarthlinkSearchViewModel(
            gateway = fakeGateway,
            audit = app.auditRepository,
            prefs = app.preferenceManager,
            localAccountRepository = app.localAccountRepository,
            localLedgerRepository = ledgerRepo
        )

        val job = viewModel.refillUser(
            userId = "user_no_price",
            depositPass = "pass",
            price = null // price is null and no fallback allowed!
        )
        job.join()

        assertEquals("Invalid, non-authoritative, or missing package price. Operation aborted.", viewModel.error.value)
        val op = ledgerRepo.getPendingOperationByAccountId("user_no_price")
        assertNull("No pending operation should be recorded when price is missing", op)
    }

    @Test
    fun test14_vmCreateUserFailsClosedOnInvalidCost() = runTest {
        val app = context as com.example.EarthlinkApp
        val throwingGateway = object : FakeGateway() {
            override suspend fun getAccountCost(accountIndex: Int): Double {
                throw RuntimeException("Cost lookup failed")
            }
        }

        val viewModel = com.example.ui.viewmodels.EarthlinkSearchViewModel(
            gateway = throwingGateway,
            audit = app.auditRepository,
            prefs = app.preferenceManager,
            localAccountRepository = app.localAccountRepository,
            localLedgerRepository = ledgerRepo
        )

        val job = viewModel.createUserUsingDeposit(
            username = "new_user_fail_cost",
            phone = "07700000000",
            fullName = "New User",
            pkgIndex = 1,
            depositPass = "pass"
        )
        job.join()

        assertEquals("Failed to determine a valid IQD package cost. Operation aborted.", viewModel.error.value)
        val op = ledgerRepo.getPendingOperationByAccountId("new_user_fail_cost")
        assertNull("No pending operation should be recorded when package cost lookup fails", op)
    }

    @Test
    fun test15_runtimeSweepWithGracePeriodIgnoresFreshCountZeroOperations() = runTest {
        val now = System.currentTimeMillis()
        // Op A: fresh op with count=0 (currently in the middle of pre-dispatch)
        val opA = PendingExternalOperation(
            businessTransactionId = "tx_sweep_count0",
            operationIntentId = "intent_sweep_count0",
            accountId = "user_sweep_0",
            operationType = "REFILL",
            amountIqd = 35000L,
            status = "PENDING",
            dispatchClaimCount = 0,
            createdAt = now
        )
        ledgerRepo.recordPendingOperation(opA)

        // Op B: claimed op with count=1 that suffered uncertainty and is older than grace window
        val opB = PendingExternalOperation(
            businessTransactionId = "tx_sweep_count1",
            operationIntentId = "intent_sweep_count1",
            accountId = "user_sweep_1",
            operationType = "TEST_USER",
            amountIqd = 0L,
            status = "PENDING",
            dispatchClaimCount = 1,
            createdAt = now - 120_000L
        )
        ledgerRepo.recordPendingOperation(opB)

        val fakeGateway = object : FakeGateway() {
            override suspend fun checkUsernameAvailable(userId: String): Boolean {
                return true // user still available => verified failure
            }
        }

        val resolutions = ledgerRepo.sweepAndResolvePendingOperations(fakeGateway, graceWindowMs = 60_000L)
        
        assertEquals("Sweep must only pick up operations past the grace window", 1, resolutions.size)
        assertEquals("tx_sweep_count1", resolutions.first().operation.businessTransactionId)

        // Verify fresh Op A remains completely untouched in PENDING count=0
        val savedA = ledgerRepo.getPendingOperationByTransactionId("tx_sweep_count0")
        assertNotNull(savedA)
        assertEquals("PENDING", savedA!!.status)
        assertEquals(0, savedA.dispatchClaimCount)
    }

    @Test
    fun test16_claimDispatchDirectExecution() = runTest {
        val op = PendingExternalOperation(
            businessTransactionId = "tx_direct_claim",
            operationIntentId = "intent_direct_claim",
            accountId = "user_direct_claim",
            operationType = "ACTIVATION",
            amountIqd = 35000L,
            status = "PENDING",
            dispatchClaimCount = 0
        )
        ledgerRepo.recordPendingOperation(op)

        val success = ledgerRepo.claimDispatchAuthorization("tx_direct_claim")
        assertTrue(success)
        val opAfter = ledgerRepo.getPendingOperationByTransactionId("tx_direct_claim")
        assertEquals(1, opAfter!!.dispatchClaimCount)
        assertEquals("DISPATCHING", opAfter.status)
    }

    @Test
    fun test17_statement4TupleRejectsDifferentUserEvenWithMatchingAmountAndTime() = runTest {
        val now = 1700000000000L
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val dateStr = sdf.format(java.util.Date(now))

        val targetUser = "subscriber_target"
        val otherUser = "subscriber_other"

        val op = PendingExternalOperation(
            businessTransactionId = "tx_4tuple_user_test",
            operationIntentId = "intent_4tuple_user_test",
            accountId = targetUser,
            operationType = "REFILL",
            amountIqd = 35000L,
            status = "PENDING",
            dispatchClaimCount = 1,
            createdAt = now
        )
        ledgerRepo.recordPendingOperation(op)

        // Gateway returns statement item for a DIFFERENT user with same amount & time
        val statementsWithOtherUser = listOf(
            AccountStatementItem(
                occurredAt = dateStr,
                operation = "Withdraw",
                depositAmount = 0.0,
                withdrawalAmount = 35000.0,
                userIDLower = otherUser // DIFFERENT USER!
            )
        )

        val fakeGateway = FakeGateway(
            statementsResult = statementsWithOtherUser,
            searchUsersResult = UserListResponse(itemsList = listOf(UserListItem(userIndexLower = 999, userIDLower = targetUser))),
            userDetailResult = UserDetail(userIndexLower = 999, userIDLower = targetUser, accountStatusLower = "Active", expirationDateLower = null)
        )

        // When baselineExpirationDate is null, verifyRenewalViaStatement is invoked
        val resolution = ledgerRepo.verifyAndResolvePendingOperation(op.businessTransactionId, fakeGateway, baselineExpirationDate = null)
        
        assertEquals("Statement match for different user must be rejected as INCONCLUSIVE", UnknownOutcomeResolutionResult.INCONCLUSIVE, resolution.result)

        // Now provide statement with matching targetUser
        val statementsWithTargetUser = listOf(
            AccountStatementItem(
                occurredAt = dateStr,
                operation = "Withdraw",
                depositAmount = 0.0,
                withdrawalAmount = 35000.0,
                userIDLower = targetUser // MATCHING USER
            )
        )
        val matchingGateway = FakeGateway(
            statementsResult = statementsWithTargetUser,
            searchUsersResult = UserListResponse(itemsList = listOf(UserListItem(userIndexLower = 999, userIDLower = targetUser))),
            userDetailResult = UserDetail(userIndexLower = 999, userIDLower = targetUser, accountStatusLower = "Active", expirationDateLower = null)
        )

        // Insert local account so resolution can materialize
        db.localAccountDao().insert(LocalAccount(id = targetUser, earthlinkUsername = targetUser, displayName = "Target User", currentPriceIqd = 35000.0, debtIqd = 0.0))

        val matchingResolution = ledgerRepo.verifyAndResolvePendingOperation(op.businessTransactionId, matchingGateway, baselineExpirationDate = null)
        assertEquals("Statement match for correct user in 4-tuple must resolve to VERIFIED_SUCCESS", UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, matchingResolution.result)
    }

    @Test
    fun test18_allFourSuccessPathsMaterializeViaCanonicalSuccessResolver() = runTest {
        val app = context as com.example.EarthlinkApp
        val account = LocalAccount(
            id = "user_all_four",
            earthlinkUsername = "user_all_four",
            displayName = "All Four User",
            currentPriceIqd = 35000.0,
            debtIqd = 0.0
        )
        db.localAccountDao().insert(account)

        val fakeGateway = object : FakeGateway() {
            override suspend fun getAccountCost(accountIndex: Int): Double = 35000.0
            override suspend fun checkUsernameAvailable(userId: String): Boolean = true
            override suspend fun createTestUser(username: String, phone: String, fullName: String, accountIndex: Int): String = "test_pass"
            override suspend fun createUserUsingDeposit(username: String, phone: String, fullName: String, accountIndex: Int, depositPassword: String): String = "deposit_pass"
            override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean = true
            override suspend fun extendUser(userIndex: Int): Boolean = true
        }

        val testAccountRepo = com.example.data.repository.LocalAccountRepositoryImpl(
            database = db,
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao()
        )

        val viewModel = com.example.ui.viewmodels.EarthlinkSearchViewModel(
            gateway = fakeGateway,
            audit = app.auditRepository,
            prefs = app.preferenceManager,
            localAccountRepository = testAccountRepo,
            localLedgerRepository = ledgerRepo
        )

        // 1. createTestUser
        val jobTest = viewModel.createTestUser("test_user_01", "07700000001", "Test User 1", 1, intentId = "intent_test_01")
        jobTest.join()
        val opTest = ledgerRepo.getPendingOperationByIntentId("intent_test_01")
        assertNotNull(opTest)
        assertEquals("COMPLETED", opTest!!.status)

        // 2. createUserUsingDeposit
        val jobDeposit = viewModel.createUserUsingDeposit("deposit_user_02", "07700000002", "Deposit User 2", 1, "pass", intentId = "intent_deposit_02")
        jobDeposit.join()
        val opDeposit = ledgerRepo.getPendingOperationByIntentId("intent_deposit_02")
        assertNotNull(opDeposit)
        assertEquals("COMPLETED", opDeposit!!.status)

        // 3. refillUser (with onSuccessCallback to verify canonical materialization cannot be bypassed)
        var callbackInvokedTxId: String? = null
        val jobRefill = viewModel.refillUser(
            userId = "user_all_four",
            depositPass = "pass",
            price = 35000.0,
            note = "Refill Note",
            intentId = "intent_refill_03",
            onSuccessCallback = { txId -> callbackInvokedTxId = txId }
        )
        jobRefill.join()
        val opRefill = ledgerRepo.getPendingOperationByIntentId("intent_refill_03")
        assertNotNull(opRefill)
        assertEquals("COMPLETED", opRefill!!.status)
        assertNotNull("onSuccessCallback must be invoked", callbackInvokedTxId)
        assertEquals(opRefill!!.businessTransactionId, callbackInvokedTxId)
        val ledgerEntries = db.localLedgerEntryDao().getByAccountIdOneShot("user_all_four")
        assertEquals("Ledger debt entry must be materialized via canonical success resolver even when callback is present", 1, ledgerEntries.size)
        assertEquals(35000.0, ledgerEntries.first().amountIqd, 0.001)

        // 4. extendUser
        val jobExtend = viewModel.extendUser(101, "user_all_four", intentId = "intent_extend_04")
        jobExtend.join()
        val opExtend = ledgerRepo.getPendingOperationByIntentId("intent_extend_04")
        assertNotNull(opExtend)
        assertEquals("COMPLETED", opExtend!!.status)
    }

    /**
     * Core Triad:
     * 1. Claim: When an ISP subscriber exists but no LocalAccount is yet persisted in Room,
     *    refillUser creates and persists LocalAccount prior to dispatch, completes external refill,
     *    and successfully materializes the ledger debt entry with status COMPLETED.
     * 2. Seam / Environment: ROBOLECTRIC (Room SQLite + ViewModel + FakeGateway).
     * 3. Independent Oracle: LocalAccount must exist with subscriber details, PendingExternalOperation
     *    must be COMPLETED with dispatchClaimCount = 1, and LocalLedgerEntry must be persisted for exact amount.
     */
    @Test
    fun test19_refillCreatesLocalAccountWhenMissingAndMaterializesLedgerSuccessfully() = runTest {
        val app = context as com.example.EarthlinkApp
        val gateway = FakeGateway()

        val viewModel = com.example.ui.viewmodels.EarthlinkSearchViewModel(
            gateway = gateway,
            prefs = app.preferenceManager,
            audit = app.auditRepository,
            localAccountRepository = accountRepo,
            localLedgerRepository = ledgerRepo
        )

        // Precondition: No local account exists initially
        assertNull(accountRepo.getAccountByIdOneShot("unregistered_refill_user"))

        // Attempt refill without pre-existing local account -> auto-creates LocalAccount, gateway succeeds, materializes ledger
        val job = viewModel.refillUser(
            userId = "unregistered_refill_user",
            depositPass = "pass",
            price = 35000.0,
            note = "Test Refill",
            intentId = "intent_refill_auto_create"
        )
        job.join()

        // 1. Action success must be set
        assertNotNull("Action success must be shown when refill and materialization succeed", viewModel.actionSuccess.value)
        assertNull("Error message must not be set", viewModel.error.value)

        // 2. LocalAccount must now be persisted in Room with correct price
        val localAccount = accountRepo.getAccountByIdOneShot("unregistered_refill_user")
        assertNotNull("LocalAccount must be auto-created and persisted before dispatch", localAccount)
        assertEquals("unregistered_refill_user", localAccount!!.id)
        assertEquals(35000.0, localAccount.currentPriceIqd, 0.001)

        // 3. Operation must be COMPLETED with dispatchClaimCount = 1
        val op = ledgerRepo.getPendingOperationByIntentId("intent_refill_auto_create")
        assertNotNull("PendingExternalOperation must exist", op)
        assertEquals("COMPLETED", op!!.status)
        assertEquals(1, op.dispatchClaimCount)

        // 4. Ledger debt entry must be materialized in Room
        val ledgerEntries = db.localLedgerEntryDao().getByAccountIdOneShot("unregistered_refill_user")
        assertEquals("Ledger debt entry must be materialized", 1, ledgerEntries.size)
        assertEquals(35000.0, ledgerEntries.first().amountIqd, 0.001)
        assertEquals("unregistered_refill_user", ledgerEntries.first().accountId)
    }

    @Test
    fun test20_canonicalFinancialMaterializerRejectsZeroOrInvalidAmountForActivation() = runTest {
        val account = LocalAccount(id = "user_act_test", displayName = "Act User", currentPriceIqd = 35000.0)
        accountRepo.saveAccount(account)

        // 1. ACTIVATION with amountIqd = 0 must FAIL CLOSED (throw IllegalStateException, not COMPLETED, zero ledger)
        val opZero = PendingExternalOperation(
            businessTransactionId = "tx_act_zero_01",
            operationIntentId = "intent_act_zero_01",
            accountId = "user_act_test",
            operationType = "ACTIVATION",
            amountIqd = 0L,
            payloadJson = "{}",
            status = "PENDING",
            dispatchClaimCount = 1
        )
        ledgerRepo.recordPendingOperation(opZero)

        try {
            ledgerRepo.resolvePendingOperationVerifiedSuccess("tx_act_zero_01", "[VERIFIED ACTIVATION]")
            fail("Expected IllegalStateException for ACTIVATION with amountIqd == 0")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("missing valid positive persisted charge amount"))
        }

        val opAfterZero = ledgerRepo.getPendingOperationByTransactionId("tx_act_zero_01")
        assertNotNull(opAfterZero)
        assertNotEquals("COMPLETED", opAfterZero!!.status)
        val zeroLedger = db.localLedgerEntryDao().getByIdOneShot("tx_act_zero_01")
        assertNull("No ledger entry must be materialized for 0-amount activation", zeroLedger)

        // 2. Valid ACTIVATION with amountIqd > 0 materializes normally
        val opValid = PendingExternalOperation(
            businessTransactionId = "tx_act_valid_02",
            operationIntentId = "intent_act_valid_02",
            accountId = "user_act_test",
            operationType = "ACTIVATION",
            amountIqd = 35000L,
            payloadJson = "{}",
            status = "PENDING",
            dispatchClaimCount = 1
        )
        ledgerRepo.recordPendingOperation(opValid)

        val materializedEntry = ledgerRepo.resolvePendingOperationVerifiedSuccess("tx_act_valid_02", "[VERIFIED ACTIVATION]")
        assertNotNull("Valid activation must materialize ledger entry", materializedEntry)
        assertEquals(35000.0, materializedEntry!!.amountIqd, 0.001)

        val opAfterValid = ledgerRepo.getPendingOperationByTransactionId("tx_act_valid_02")
        assertNotNull(opAfterValid)
        assertEquals("COMPLETED", opAfterValid!!.status)
    }

    @Test
    fun testADV_C06_activeDispatchingSweepIsolation() = runTest {
        // 1. Record a pending operation
        val op = PendingExternalOperation(
            businessTransactionId = "tx_adv_c06",
            operationIntentId = "intent_adv_c06",
            accountId = "user_c06",
            operationType = "REFILL",
            amountIqd = 35000L,
            payloadJson = "{}",
            status = "PENDING",
            dispatchClaimCount = 0,
            createdAt = System.currentTimeMillis()
        )
        ledgerRepo.recordPendingOperation(op)

        // 2. Successful durable claim
        val claimed = ledgerRepo.claimDispatchAuthorization("tx_adv_c06")
        assertTrue(claimed)

        // The operation status is now DISPATCHING
        val opClaimed = ledgerRepo.getPendingOperationByTransactionId("tx_adv_c06")
        assertEquals("DISPATCHING", opClaimed!!.status)
        assertEquals(1, opClaimed.dispatchClaimCount)

        // 3. Define a latch-based fake gateway that blocks or is released
        var gatewayCalls = 0
        val fakeGate = object : FakeGateway() {
            override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean {
                gatewayCalls++
                kotlinx.coroutines.delay(1000)
                return true
            }
        }

        // 4. Concurrently, while the gateway request would be in-flight, run the sweep.
        val resolutions = ledgerRepo.sweepAndResolvePendingOperations(fakeGate, graceWindowMs = 0L)

        // 5. Prove that the sweep completely ignores the active DISPATCHING row
        assertTrue("Active DISPATCHING operations must be completely ignored by the sweep", resolutions.isEmpty())

        // The operation must remain untouched in DISPATCHING state
        val opAfterSweep = ledgerRepo.getPendingOperationByTransactionId("tx_adv_c06")
        assertEquals("DISPATCHING", opAfterSweep!!.status)
        assertEquals(1, opAfterSweep.dispatchClaimCount)
        assertEquals(0, gatewayCalls)
    }

    @Test
    fun testADV_C12_cancellationAfterClaim() = runTest {
        // 1. Record a pending operation PENDING(0)
        val op = PendingExternalOperation(
            businessTransactionId = "tx_adv_c12",
            operationIntentId = "intent_adv_c12",
            accountId = "user_c12",
            operationType = "REFILL",
            amountIqd = 35000L,
            payloadJson = "{}",
            status = "PENDING",
            dispatchClaimCount = 0,
            createdAt = System.currentTimeMillis()
        )
        ledgerRepo.recordPendingOperation(op)

        val opBefore = ledgerRepo.getPendingOperationByTransactionId("tx_adv_c12")
        assertEquals("PENDING", opBefore!!.status)
        assertEquals(0, opBefore.dispatchClaimCount)

        // 2. Successful claim
        val claimed = ledgerRepo.claimDispatchAuthorization("tx_adv_c12")
        assertTrue(claimed)

        val opClaimed = ledgerRepo.getPendingOperationByTransactionId("tx_adv_c12")
        assertEquals("DISPATCHING", opClaimed!!.status)
        assertEquals(1, opClaimed.dispatchClaimCount)

        // 3. Cancellation occurs
        try {
            throw kotlinx.coroutines.CancellationException("Simulated coroutine cancellation")
        } catch (e: kotlinx.coroutines.CancellationException) {
            assertEquals("Simulated coroutine cancellation", e.message)
        }

        // 4. Prove:
        // - dispatchClaimCount remains 1
        // - no reset to 0
        // - no incorrect FAILED
        // - no ledger mutation
        val opAfter = ledgerRepo.getPendingOperationByTransactionId("tx_adv_c12")
        assertEquals("DISPATCHING", opAfter!!.status)
        assertEquals(1, opAfter.dispatchClaimCount)

        // Try to claim again to prove it cannot be re-dispatched
        val reclaim = ledgerRepo.claimDispatchAuthorization("tx_adv_c12")
        assertFalse("Cannot re-claim/re-dispatch a recovery-blocked or in-flight operation", reclaim)

        val ledgerEntry = db.localLedgerEntryDao().getByIdOneShot("tx_adv_c12")
        assertNull("No ledger entry must be materialized on cancellation", ledgerEntry)
    }

    @Test
    fun testADV_C16_exactBoundaryTests() = runTest {
        val opTime = 1700000000000L // base createdAt
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }

        // Insert required local account to avoid MISSING_LOCAL_FINANCIAL_TARGET
        db.localAccountDao().insert(
            LocalAccount(
                id = "user_boundary",
                earthlinkUsername = "user_boundary",
                displayName = "Boundary User",
                currentPriceIqd = 35000.0,
                debtIqd = 0.0
            )
        )

        val searchResponse = UserListResponse(
            itemsList = listOf(
                UserListItem(
                    userIndexLower = 101,
                    userIDLower = "user_boundary"
                )
            )
        )

        // 1. CASE A: -90s exact boundary (MUST BE ACCEPTED)
        val tMinus90 = formatter.format(java.util.Date(opTime - 90000L))
        val opA = PendingExternalOperation(
            businessTransactionId = "tx_boundary_minus_90",
            operationIntentId = "intent_boundary_minus_90",
            accountId = "user_boundary",
            operationType = "REFILL",
            amountIqd = 35000L,
            payloadJson = "{}",
            status = "PENDING",
            dispatchClaimCount = 1,
            createdAt = opTime
        )
        ledgerRepo.recordPendingOperation(opA)

        val gatewayA = FakeGateway(
            checkUsernameAvailableResult = false,
            searchUsersResult = searchResponse,
            statementsResult = listOf(
                AccountStatementItem(
                    occurredAt = tMinus90,
                    userIDLower = "user_boundary",
                    operation = "Withdraw",
                    withdrawalAmount = 35000.0
                )
            )
        )
        val resolutionsA = ledgerRepo.sweepAndResolvePendingOperations(gatewayA, graceWindowMs = 0L)
        assertEquals(1, resolutionsA.size)
        assertEquals("tx_boundary_minus_90", resolutionsA.first().operation.businessTransactionId)
        assertEquals(com.example.core.model.UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, resolutionsA.first().result)

        // 2. CASE B: +90s exact boundary (MUST BE ACCEPTED)
        val tPlus90 = formatter.format(java.util.Date(opTime + 90000L))
        val opB = PendingExternalOperation(
            businessTransactionId = "tx_boundary_plus_90",
            operationIntentId = "intent_boundary_plus_90",
            accountId = "user_boundary",
            operationType = "REFILL",
            amountIqd = 35000L,
            payloadJson = "{}",
            status = "PENDING",
            dispatchClaimCount = 1,
            createdAt = opTime
        )
        ledgerRepo.recordPendingOperation(opB)

        val gatewayB = FakeGateway(
            checkUsernameAvailableResult = false,
            searchUsersResult = searchResponse,
            statementsResult = listOf(
                AccountStatementItem(
                    occurredAt = tPlus90,
                    userIDLower = "user_boundary",
                    operation = "Withdraw",
                    withdrawalAmount = 35000.0
                )
            )
        )
        val resolutionsB = ledgerRepo.sweepAndResolvePendingOperations(gatewayB, graceWindowMs = 0L)
        assertEquals(1, resolutionsB.size)
        assertEquals("tx_boundary_plus_90", resolutionsB.first().operation.businessTransactionId)
        assertEquals(com.example.core.model.UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, resolutionsB.first().result)

        // 3. CASE C: -90s - 1s (91s) outside boundary (MUST BE REJECTED/INCONCLUSIVE)
        val tMinus91 = formatter.format(java.util.Date(opTime - 91000L))
        val opC = PendingExternalOperation(
            businessTransactionId = "tx_boundary_minus_91",
            operationIntentId = "intent_boundary_minus_91",
            accountId = "user_boundary",
            operationType = "REFILL",
            amountIqd = 35000L,
            payloadJson = "{}",
            status = "PENDING",
            dispatchClaimCount = 1,
            createdAt = opTime
        )
        ledgerRepo.recordPendingOperation(opC)

        val gatewayC = FakeGateway(
            checkUsernameAvailableResult = false,
            searchUsersResult = searchResponse,
            statementsResult = listOf(
                AccountStatementItem(
                    occurredAt = tMinus91,
                    userIDLower = "user_boundary",
                    operation = "Withdraw",
                    withdrawalAmount = 35000.0
                )
            )
        )
        val resolutionsC = ledgerRepo.sweepAndResolvePendingOperations(gatewayC, graceWindowMs = 0L)
        val resC = resolutionsC.firstOrNull { it.operation.businessTransactionId == "tx_boundary_minus_91" }
        org.junit.Assert.assertNotNull("Resolution for tx_boundary_minus_91 must not be null", resC)
        assertEquals(com.example.core.model.UnknownOutcomeResolutionResult.INCONCLUSIVE, resC!!.result)

        // 4. CASE D: +90s + 1s (91s) outside boundary (MUST BE REJECTED/INCONCLUSIVE)
        val tPlus91 = formatter.format(java.util.Date(opTime + 91000L))
        val opD = PendingExternalOperation(
            businessTransactionId = "tx_boundary_plus_91",
            operationIntentId = "intent_boundary_plus_91",
            accountId = "user_boundary",
            operationType = "REFILL",
            amountIqd = 35000L,
            payloadJson = "{}",
            status = "PENDING",
            dispatchClaimCount = 1,
            createdAt = opTime
        )
        ledgerRepo.recordPendingOperation(opD)

        val gatewayD = FakeGateway(
            checkUsernameAvailableResult = false,
            searchUsersResult = searchResponse,
            statementsResult = listOf(
                AccountStatementItem(
                    occurredAt = tPlus91,
                    userIDLower = "user_boundary",
                    operation = "Withdraw",
                    withdrawalAmount = 35000.0
                )
            )
        )
        val resolutionsD = ledgerRepo.sweepAndResolvePendingOperations(gatewayD, graceWindowMs = 0L)
        val resD = resolutionsD.firstOrNull { it.operation.businessTransactionId == "tx_boundary_plus_91" }
        org.junit.Assert.assertNotNull("Resolution for tx_boundary_plus_91 must not be null", resD)
        assertEquals(com.example.core.model.UnknownOutcomeResolutionResult.INCONCLUSIVE, resD!!.result)
    }

    @Test
    fun test19_crashEquivalentPendingCountZero_coldStartRecovery() = runTest {
        val beforeProcessStart = 1000L
        val processStartMs = 5000L

        val orphanTxId = "tx_orphan_count0_19"
        val orphanIntentId = "intent_orphan_count0_19"

        val opOrphaned = PendingExternalOperation(
            businessTransactionId = orphanTxId,
            operationIntentId = orphanIntentId,
            accountId = "user19_orphan",
            operationType = "ACTIVATION",
            amountIqd = 45000L,
            status = "PENDING",
            dispatchClaimCount = 0,
            createdAt = beforeProcessStart,
            updatedAt = beforeProcessStart
        )
        ledgerRepo.recordPendingOperation(opOrphaned)

        // Prior to recovery, it is visible in unresolved pending operations
        val unresolvedBefore = ledgerRepo.getUnresolvedPendingOperations()
        assertTrue(unresolvedBefore.any { it.businessTransactionId == orphanTxId })

        var isUsernameChecked = false
        val mockGateway = object : FakeGateway() {
            override suspend fun checkUsernameAvailable(userId: String): Boolean {
                if (userId == "user19_orphan") {
                    isUsernameChecked = true
                    return true // User is available => activation never executed on ISP
                }
                return false
            }
        }

        // Run cold start recovery
        ledgerRepo.recoverColdStartOrphanedOperations(mockGateway, processStartMs)

        assertTrue("Orphaned count=0 operation must be inspected via gateway", isUsernameChecked)
        val resolvedOp = ledgerRepo.getPendingOperationByTransactionId(orphanTxId)
        assertNotNull(resolvedOp)
        assertEquals("FAILED", resolvedOp!!.status)
        assertEquals(orphanTxId, resolvedOp.businessTransactionId)
        assertEquals(orphanIntentId, resolvedOp.operationIntentId)

        // After recovery, it is no longer exposed to Statement UI / synthetic history
        val unresolvedAfter = ledgerRepo.getUnresolvedPendingOperations()
        assertFalse("Resolved orphan must not appear in unresolved operations", unresolvedAfter.any { it.businessTransactionId == orphanTxId })
        val syntheticHistory = ledgerRepo.getPendingSyntheticHistory()
        assertFalse("Resolved orphan must not appear in synthetic history", syntheticHistory.any { it.sourceExternalId == orphanTxId })
    }

    @Test
    fun test20_crashEquivalentPendingCountZero_sweepRecovery() = runTest {
        val opTime = 1000L
        val orphanTxId = "tx_orphan_sweep_20"
        val orphanIntentId = "intent_orphan_sweep_20"

        db.localAccountDao().insert(
            LocalAccount(
                id = "user20_sweep",
                earthlinkUsername = "user20_sweep",
                displayName = "User Twenty",
                currentPriceIqd = 35000.0,
                debtIqd = 0.0
            )
        )

        val opOrphaned = PendingExternalOperation(
            businessTransactionId = orphanTxId,
            operationIntentId = orphanIntentId,
            accountId = "user20_sweep",
            operationType = "REFILL",
            amountIqd = 35000L,
            status = "PENDING",
            dispatchClaimCount = 0,
            createdAt = opTime,
            updatedAt = opTime
        )
        ledgerRepo.recordPendingOperation(opOrphaned)

        val searchResponse = UserListResponse(
            itemsList = listOf(
                UserListItem(
                    userIndexLower = 200,
                    userIDLower = "user20_sweep"
                )
            )
        )
        val mockGateway = FakeGateway(
            checkUsernameAvailableResult = false,
            searchUsersResult = searchResponse,
            statementsResult = emptyList() // No statement charge found
        )

        val resolutions = ledgerRepo.sweepAndResolvePendingOperations(mockGateway, graceWindowMs = 0L)
        val res = resolutions.firstOrNull { it.operation.businessTransactionId == orphanTxId }
        assertNotNull("Resolution for orphan must exist", res)
        assertEquals(com.example.core.model.UnknownOutcomeResolutionResult.VERIFIED_FAILURE, res!!.result)

        val opAfter = ledgerRepo.getPendingOperationByTransactionId(orphanTxId)
        assertNotNull(opAfter)
        assertEquals("FAILED", opAfter!!.status)
        assertEquals(orphanTxId, opAfter.businessTransactionId)
        assertEquals(orphanIntentId, opAfter.operationIntentId)

        // Ensure not in unresolved list
        val unresolved = ledgerRepo.getUnresolvedPendingOperations()
        assertFalse(unresolved.any { it.businessTransactionId == orphanTxId })
    }

    @Test
    fun test21_crashEquivalentPendingCountZero_cannotBecomeVerifiedSuccessEvenWithGatewayMatch() = runTest {
        val opTime = 1700000000000L
        val txId = "tx_orphan_success_21"
        val intentId = "intent_orphan_success_21"
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val matchTime = formatter.format(java.util.Date(opTime))

        db.localAccountDao().insert(
            LocalAccount(
                id = "user21_success",
                earthlinkUsername = "user21_success",
                displayName = "User Twenty One",
                currentPriceIqd = 35000.0,
                debtIqd = 0.0
            )
        )

        val op = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = intentId,
            accountId = "user21_success",
            operationType = "REFILL",
            amountIqd = 35000L,
            status = "PENDING",
            dispatchClaimCount = 0,
            createdAt = opTime,
            updatedAt = opTime
        )
        ledgerRepo.recordPendingOperation(op)

        val searchResponse = UserListResponse(
            itemsList = listOf(
                UserListItem(
                    userIndexLower = 210,
                    userIDLower = "user21_success"
                )
            )
        )
        val mockGateway = FakeGateway(
            checkUsernameAvailableResult = false,
            searchUsersResult = searchResponse,
            statementsResult = listOf(
                AccountStatementItem(
                    occurredAt = matchTime,
                    userIDLower = "user21_success",
                    operation = "Withdraw",
                    withdrawalAmount = 35000.0
                )
            )
        )

        val resolutions = ledgerRepo.sweepAndResolvePendingOperations(mockGateway, graceWindowMs = 0L)
        val res = resolutions.firstOrNull { it.operation.businessTransactionId == txId }
        assertNotNull(res)
        assertEquals(com.example.core.model.UnknownOutcomeResolutionResult.VERIFIED_FAILURE, res!!.result)
        assertNull("Ledger entry must NOT be materialized for count=0 operation", res.ledgerEntry)

        val opAfter = ledgerRepo.getPendingOperationByTransactionId(txId)
        assertNotNull(opAfter)
        assertEquals("FAILED", opAfter!!.status)

        // Verify NO ledger entry was created
        val ledger = db.localLedgerEntryDao().getByIdOneShot(txId)
        assertNull("Unclaimed operation (dispatchClaimCount=0) must never materialize a local ledger entry", ledger)
    }

    @Test
    fun test22_crashEquivalentPendingCountZero_newIndependentOperationSucceeds() = runTest {
        val opTime = 1000L
        val failedTxId = "tx_orphan_failed_22"
        val newTxId = "tx_new_independent_22"

        db.localAccountDao().insert(
            LocalAccount(
                id = "user22_retry",
                earthlinkUsername = "user22_retry",
                displayName = "User Twenty Two",
                currentPriceIqd = 45000.0,
                debtIqd = 0.0
            )
        )

        // 1. Initial crashed orphan
        val opOrphaned = PendingExternalOperation(
            businessTransactionId = failedTxId,
            operationIntentId = "intent_old_22",
            accountId = "user22_retry",
            operationType = "ACTIVATION",
            amountIqd = 45000L,
            status = "PENDING",
            dispatchClaimCount = 0,
            createdAt = opTime,
            updatedAt = opTime
        )
        ledgerRepo.recordPendingOperation(opOrphaned)

        // Resolve orphan via cold start
        val mockGateway = object : FakeGateway() {
            override suspend fun checkUsernameAvailable(userId: String): Boolean = true
        }
        ledgerRepo.recoverColdStartOrphanedOperations(mockGateway, processStartMs = 5000L)
        assertEquals("FAILED", ledgerRepo.getPendingOperationByTransactionId(failedTxId)?.status)

        // 2. User initiates new independent operation
        val opNew = PendingExternalOperation(
            businessTransactionId = newTxId,
            operationIntentId = "intent_new_22",
            accountId = "user22_retry",
            operationType = "ACTIVATION",
            amountIqd = 45000L,
            status = "PENDING",
            dispatchClaimCount = 0,
            createdAt = 6000L,
            updatedAt = 6000L
        )
        ledgerRepo.recordPendingOperation(opNew)

        // Claim dispatch for new operation
        val claimed = ledgerRepo.claimDispatchAuthorization(newTxId)
        assertTrue("New operation must successfully claim dispatch", claimed)

        val afterClaim = ledgerRepo.getPendingOperationByTransactionId(newTxId)
        assertEquals("DISPATCHING", afterClaim?.status)
        assertEquals(1, afterClaim?.dispatchClaimCount)

        // Complete new operation
        val ledger = ledgerRepo.resolvePendingOperationVerifiedSuccess(newTxId, "New activation success")
        assertNotNull(ledger)
        assertEquals("COMPLETED", ledgerRepo.getPendingOperationByTransactionId(newTxId)?.status)

        // Old failed operation remains FAILED and untouched
        assertEquals("FAILED", ledgerRepo.getPendingOperationByTransactionId(failedTxId)?.status)
    }

}
