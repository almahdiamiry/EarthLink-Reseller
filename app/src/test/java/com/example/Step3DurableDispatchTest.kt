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
import kotlinx.coroutines.test.runTest
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
    fun test15_runtimeSweepIgnoresUnclaimedCountZeroOperations() = runTest {
        // Op A: fresh op with count=0 (in the middle of pre-dispatch)
        val opA = PendingExternalOperation(
            businessTransactionId = "tx_sweep_count0",
            operationIntentId = "intent_sweep_count0",
            accountId = "user_sweep_0",
            operationType = "REFILL",
            amountIqd = 35000L,
            status = "PENDING",
            dispatchClaimCount = 0,
            createdAt = 1000L
        )
        ledgerRepo.recordPendingOperation(opA)

        // Op B: claimed op with count=1 that suffered uncertainty
        val opB = PendingExternalOperation(
            businessTransactionId = "tx_sweep_count1",
            operationIntentId = "intent_sweep_count1",
            accountId = "user_sweep_1",
            operationType = "TEST_USER",
            amountIqd = 0L,
            status = "PENDING",
            dispatchClaimCount = 1,
            createdAt = 1000L
        )
        ledgerRepo.recordPendingOperation(opB)

        val fakeGateway = object : FakeGateway() {
            override suspend fun checkUsernameAvailable(userId: String): Boolean {
                return true // user still available => verified failure
            }
        }

        val resolutions = ledgerRepo.sweepAndResolvePendingOperations(fakeGateway, graceWindowMs = 0L)
        
        assertEquals("Sweep must only pick up count=1 operations", 1, resolutions.size)
        assertEquals("tx_sweep_count1", resolutions.first().operation.businessTransactionId)

        // Verify Op A remains completely untouched in PENDING count=0
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

    @Test
    fun test19_refillSuccessNotReportedWhenLocalMaterializationFails() = runTest {
        val app = context as com.example.EarthlinkApp
        val gateway = FakeGateway()

        // Create a repository instance that fails in resolvePendingOperationVerifiedSuccess by omitting local account
        // (Missing local account for positive IQD throws MISSING_LOCAL_FINANCIAL_TARGET)
        val viewModel = com.example.ui.viewmodels.EarthlinkSearchViewModel(
            gateway = gateway,
            prefs = app.preferenceManager,
            audit = app.auditRepository,
            localAccountRepository = accountRepo,
            localLedgerRepository = ledgerRepo
        )

        // Attempt refill without pre-existing local account -> gateway succeeds, but materialization throws MISSING_LOCAL_FINANCIAL_TARGET
        val job = viewModel.refillUser(
            userId = "unregistered_refill_user",
            depositPass = "pass",
            price = 35000.0,
            note = "Test Refill",
            intentId = "intent_refill_fail_mat"
        )
        job.join()

        // 1. Action success must NOT be set
        assertNull("Action success must not be shown when local materialization fails", viewModel.actionSuccess.value)
        // 2. Error message must reflect pending confirmation
        assertNotNull("Error message must be set", viewModel.error.value)
        assertTrue(
            "Error must indicate local record confirmation is pending",
            viewModel.error.value!!.contains("local record confirmation is pending") || viewModel.error.value!!.contains("فشل تسجيل القيد المحلي")
        )
        // 3. Operation must remain in DISPATCHING with claim count = 1 (recoverable, blocked from redispatch)
        val op = ledgerRepo.getPendingOperationByIntentId("intent_refill_fail_mat")
        assertNotNull(op)
        assertEquals("DISPATCHING", op!!.status)
        assertEquals(1, op.dispatchClaimCount)
        assertNull(op.lastError)

        // 4. Perform real cold-start recovery with fresh repository / process restart
        // Save the missing local account so materialization can succeed on recovery verification
        val account = LocalAccount(id = "unregistered_refill_user", displayName = "Refill User", currentPriceIqd = 35000.0)
        accountRepo.saveAccount(account)

        // Add matching subscriber user search result and statement to FakeGateway to prove statement-based resolution
        gateway.searchUsersResult = UserListResponse(itemsList = listOf(UserListItem(userIndexLower = 101, userIDLower = "unregistered_refill_user")))
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val dateStr = sdf.format(java.util.Date())
        gateway.statementsResult = listOf(
            com.example.core.model.AccountStatementItem(
                occurredAt = dateStr,
                operation = "Withdraw",
                depositAmount = 0.0,
                withdrawalAmount = 35000.0,
                balanceAfter = 50000.0,
                note = "RENEW",
                userIDLower = "unregistered_refill_user"
            )
        )

        // Simulate process restart with processStartMs after the operation timestamp
        val futureProcessStartMs = System.currentTimeMillis() + 1000L
        ledgerRepo.recoverColdStartOrphanedOperations(gateway, futureProcessStartMs)

        // 5. Prove transition: DISPATCHING -> PENDING(count=1) -> COMPLETED with materialized ledger entry
        val opAfterRecovery = ledgerRepo.getPendingOperationByIntentId("intent_refill_fail_mat")
        assertNotNull(opAfterRecovery)
        assertEquals("COMPLETED", opAfterRecovery!!.status)

        val ledgerEntry = db.localLedgerEntryDao().getByIdOneShot(op.businessTransactionId)
        assertNotNull("Ledger debt entry must be materialized after cold-start recovery verification", ledgerEntry)
        assertEquals(35000.0, ledgerEntry!!.amountIqd, 0.001)
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
}
