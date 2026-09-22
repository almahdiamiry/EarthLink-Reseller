package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.database.LocalAccountDao
import com.example.core.database.LocalLedgerEntryDao
import com.example.core.database.PendingExternalOperationDao
import com.example.core.model.*
import com.example.domain.repository.EarthlinkGateway
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.ui.viewmodels.EarthlinkSearchViewModel
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

/**
 * Core Triad Standard:
 * 1. Claim: Defect RECYCLED-USERNAME-FINANCIAL-TARGET-01 remediation.
 *    For every new activation, the activation never selects an existing local account
 *    solely because earthlinkUsername matches. Stale prior local accounts never receive the new
 *    activation's financial ledger entry or debt. The new activation establishes and materializes
 *    an explicit deterministic local target.
 * 2. Seam / Environment: ROBOLECTRIC (In-memory Room AppDatabase + ViewModel + FakeGateway).
 * 3. Independent Oracle: Explicit arithmetic decomposition:
 *    - Stale account debt after = stale opening debt + 0.0. Stale account ledger count = 0.
 *    - New activation target debt = exact package cost. New activation ledger count = 1.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RecycledUsernameActivationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dbFile: File
    private lateinit var accountDao: LocalAccountDao
    private lateinit var ledgerDao: LocalLedgerEntryDao
    private lateinit var pendingDao: PendingExternalOperationDao
    private lateinit var accountRepo: LocalAccountRepositoryImpl
    private lateinit var ledgerRepo: LocalLedgerRepositoryImpl
    private lateinit var fakeGateway: TestFakeGateway
    private lateinit var viewModel: EarthlinkSearchViewModel

    private class TestFakeGateway : EarthlinkGateway {
        var checkUsernameAvailableResult: Boolean = true
        var accountCostResult: Double = 35000.0
        var createUserUsingDepositResult: String? = "generated_pass_123"

        override suspend fun login(username: String, password: String): LoginResponse = throw NotImplementedError()
        override suspend fun getBalance(): Double = 500000.0
        override suspend fun getTestUsersCount(affiliateIndex: Int?): Int = 0
        override suspend fun getActiveTestUsersCount(): Int = 0
        override suspend fun getPrepaidNeeded(): Double = 0.0
        override suspend fun getPackages(): List<AccountPackage> = emptyList()
        override suspend fun getAccountCost(accountIndex: Int): Double = accountCostResult
        override suspend fun searchUsers(query: String, startIndex: Int, rowCount: Int): UserListResponse = UserListResponse(itemsList = emptyList())
        override suspend fun getUserDetail(userIndex: Int): UserDetail = throw NotImplementedError()
        override suspend fun autocompleteUser(query: String): List<AutocompleteUser> = emptyList()
        override suspend fun checkUsernameAvailable(userId: String): Boolean = checkUsernameAvailableResult
        override suspend fun checkCustomerByPhone(phone: String): String? = null
        override suspend fun createCustomer(name: String, phone: String): Boolean = true
        override suspend fun createTestUser(username: String, phone: String, fullName: String, accountIndex: Int): String? = null
        override suspend fun createUserUsingDeposit(username: String, phone: String, fullName: String, accountIndex: Int, depositPassword: String): String? = createUserUsingDepositResult
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
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val app = context as com.example.EarthlinkApp
        app.isSafeDebugFallbackAllowedOverride = true

        dbFile = context.getDatabasePath("recycled_username_test.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()

        db = Room.databaseBuilder(context, AppDatabase::class.java, dbFile.name)
            .allowMainThreadQueries()
            .build()

        accountDao = db.localAccountDao()
        ledgerDao = db.localLedgerEntryDao()
        pendingDao = db.pendingExternalOperationDao()

        accountRepo = LocalAccountRepositoryImpl(
            database = db,
            accountDao = accountDao,
            outboxDao = db.syncOutboxDao()
        )
        ledgerRepo = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = ledgerDao,
            accountDao = accountDao,
            outboxDao = db.syncOutboxDao(),
            pendingDao = pendingDao
        )

        fakeGateway = TestFakeGateway()
        viewModel = EarthlinkSearchViewModel(
            gateway = fakeGateway,
            audit = app.auditRepository,
            prefs = app.preferenceManager,
            localAccountRepository = accountRepo,
            localLedgerRepository = ledgerRepo
        )
    }

    @After
    fun tearDown() {
        if (db.isOpen) {
            db.close()
        }
        dbFile.delete()
    }

    // -------------------------------------------------------------------------
    // Test A — Recycled username with one stale local account
    // -------------------------------------------------------------------------
    @Test
    fun testA_recycledUsernameWithOneStaleLocalAccount() = runTest {
        val oldAcc = LocalAccount(
            id = "old_account",
            earthlinkUsername = "recycled_user",
            displayName = "Old Alice",
            debtIqd = 10000.0,
            isHistoryOnlySubscriber = false
        )
        accountDao.insert(oldAcc)

        fakeGateway.checkUsernameAvailableResult = true
        fakeGateway.accountCostResult = 35000.0
        fakeGateway.createUserUsingDepositResult = "new_pass_123"

        val job = viewModel.createUserUsingDeposit(
            username = "recycled_user",
            phone = "07701111111",
            fullName = "New Bob",
            pkgIndex = 1,
            depositPass = "dep_pass",
            intentId = "intent_act_A"
        )
        job.join()

        // Expected 1: old_account receives NO new Activation ledger entry
        val oldLedgers = ledgerDao.getByAccountIdOneShot("old_account")
        assertTrue("old_account receives NO new Activation ledger entry", oldLedgers.isEmpty())

        // Expected 2: old_account debt remains unchanged by the new Activation
        val oldAccAfter = accountDao.getByIdOneShot("old_account")!!
        assertEquals("old_account debt remains unchanged by the new Activation", 10000.0, oldAccAfter.debtIqd, 0.001)

        // Expected 3: new Activation has a separate deterministic local target
        val op = ledgerRepo.getPendingOperationByIntentId("intent_act_A")!!
        assertEquals("COMPLETED", op.status)
        val payloadObj = org.json.JSONObject(op.payloadJson)
        val targetLocalId = payloadObj.getString("localAccountId")
        assertNotEquals("new Activation must not target old_account", "old_account", targetLocalId)

        val newAcc = accountDao.getByIdOneShot(targetLocalId)
        assertNotNull("new local target must exist", newAcc)
        assertEquals("recycled_user", newAcc!!.earthlinkUsername)
        assertEquals("New Bob", newAcc.displayName)
        assertEquals(35000.0, newAcc.debtIqd, 0.001)

        val newLedgers = ledgerDao.getByAccountIdOneShot(targetLocalId)
        assertEquals("new Activation ledger entry must be attached to new target", 1, newLedgers.size)
        assertEquals(35000.0, newLedgers[0].amountIqd, 0.001)
        assertEquals(op.businessTransactionId, newLedgers[0].id)
    }

    // -------------------------------------------------------------------------
    // Test B — Recycled username with multiple stale local accounts
    // -------------------------------------------------------------------------
    @Test
    fun testB_recycledUsernameWithMultipleStaleLocalAccounts() = runTest {
        val staleA = LocalAccount(
            id = "stale_account_1",
            earthlinkUsername = "recycled_user",
            displayName = "Stale One",
            debtIqd = 15000.0,
            isHistoryOnlySubscriber = false
        )
        val staleB = LocalAccount(
            id = "stale_account_2",
            earthlinkUsername = "recycled_user",
            displayName = "Stale Two",
            debtIqd = 20000.0,
            isHistoryOnlySubscriber = false
        )
        accountDao.insert(staleA)
        accountDao.insert(staleB)

        fakeGateway.checkUsernameAvailableResult = true
        fakeGateway.accountCostResult = 35000.0
        fakeGateway.createUserUsingDepositResult = "pass_multi"

        val job = viewModel.createUserUsingDeposit(
            username = "recycled_user",
            phone = "07702222222",
            fullName = "New Charlie",
            pkgIndex = 1,
            depositPass = "dep_pass",
            intentId = "intent_act_B"
        )
        job.join()

        // Expected: NO existing stale account receives the new Activation
        assertTrue("stale_account_1 receives NO ledger entry", ledgerDao.getByAccountIdOneShot("stale_account_1").isEmpty())
        assertTrue("stale_account_2 receives NO ledger entry", ledgerDao.getByAccountIdOneShot("stale_account_2").isEmpty())
        assertEquals("stale_account_1 debt unchanged", 15000.0, accountDao.getByIdOneShot("stale_account_1")!!.debtIqd, 0.001)
        assertEquals("stale_account_2 debt unchanged", 20000.0, accountDao.getByIdOneShot("stale_account_2")!!.debtIqd, 0.001)

        // Expected: NO arbitrary username-based winner is selected; new Activation resolves to its own deterministic target
        val op = ledgerRepo.getPendingOperationByIntentId("intent_act_B")!!
        assertEquals("COMPLETED", op.status)
        val payloadObj = org.json.JSONObject(op.payloadJson)
        val targetLocalId = payloadObj.getString("localAccountId")
        assertNotEquals("stale_account_1", targetLocalId)
        assertNotEquals("stale_account_2", targetLocalId)

        val newAcc = accountDao.getByIdOneShot(targetLocalId)
        assertNotNull("new activation target must exist", newAcc)
        assertEquals("New Charlie", newAcc!!.displayName)
        assertEquals(35000.0, newAcc.debtIqd, 0.001)

        val newLedgers = ledgerDao.getByAccountIdOneShot(targetLocalId)
        assertEquals(1, newLedgers.size)
        assertEquals(35000.0, newLedgers[0].amountIqd, 0.001)
    }

    // -------------------------------------------------------------------------
    // Test C — Failed Activation
    // -------------------------------------------------------------------------
    @Test
    fun testC_failedActivation() = runTest {
        val oldAcc = LocalAccount(
            id = "old_account_fail",
            earthlinkUsername = "fail_user",
            displayName = "Old User",
            debtIqd = 5000.0,
            isHistoryOnlySubscriber = false
        )
        accountDao.insert(oldAcc)

        fakeGateway.checkUsernameAvailableResult = true
        fakeGateway.accountCostResult = 35000.0
        fakeGateway.createUserUsingDepositResult = null // Gateway activation fails

        val job = viewModel.createUserUsingDeposit(
            username = "fail_user",
            phone = "07703333333",
            fullName = "Failed New User",
            pkgIndex = 1,
            depositPass = "dep_pass",
            intentId = "intent_act_C"
        )
        job.join()

        // Expected 1: NO Activation financial ledger entry
        val allLedgers = ledgerDao.getAllOneShot(100)
        assertTrue("NO Activation financial ledger entry", allLedgers.isEmpty())

        // Expected 2: NO debt added to stale accounts
        val oldAccAfter = accountDao.getByIdOneShot("old_account_fail")!!
        assertEquals("NO debt added to stale accounts", 5000.0, oldAccAfter.debtIqd, 0.001)

        val op = ledgerRepo.getPendingOperationByIntentId("intent_act_C")!!
        assertEquals("FAILED", op.status)
    }

    // -------------------------------------------------------------------------
    // Test D — Verified pending resolution
    // -------------------------------------------------------------------------
    @Test
    fun testD_verifiedPendingResolution() = runTest {
        // Setup stale account with matching username
        val stale = LocalAccount(
            id = "stale_account_D",
            earthlinkUsername = "target_user",
            displayName = "Stale Dave",
            debtIqd = 12000.0,
            isHistoryOnlySubscriber = false
        )
        accountDao.insert(stale)

        val explicitTargetId = "explicit_target_uuid_456"
        val txId = "tx_act_explicit_001"
        val payload = org.json.JSONObject().apply {
            put("username", "target_user")
            put("fullName", "Explicit User")
            put("phone", "07704444444")
            put("pkgIndex", 1)
            put("localAccountId", explicitTargetId)
        }.toString()

        pendingDao.insert(
            PendingExternalOperation(
                businessTransactionId = txId,
                operationIntentId = "intent_explicit_001",
                accountId = "target_user",
                operationType = "ACTIVATION",
                amountIqd = 45000L,
                payloadJson = payload,
                status = "PENDING",
                dispatchClaimCount = 1
            )
        )

        // Call resolvePendingOperationVerifiedSuccess
        val entry = ledgerRepo.resolvePendingOperationVerifiedSuccess(txId, "[VERIFIED ACTIVATION]")
        assertNotNull(entry)

        // Expected: financial materialization uses the explicit target, username fallback is not used
        assertEquals("Ledger entry must be attached to explicit target", explicitTargetId, entry!!.accountId)
        assertEquals(45000.0, entry.amountIqd, 0.001)

        // Stale account must be untouched
        val staleAfter = accountDao.getByIdOneShot("stale_account_D")!!
        assertEquals("Stale account debt must remain untouched", 12000.0, staleAfter.debtIqd, 0.001)
        val staleLedger = ledgerDao.getByAccountIdOneShot("stale_account_D")
        assertTrue("Stale account must receive no ledger entry", staleLedger.isEmpty())

        // Explicit target account exists with exact debt
        val targetAcc = accountDao.getByIdOneShot(explicitTargetId)
        assertNotNull("Explicit target account must be materialized", targetAcc)
        assertEquals(45000.0, targetAcc!!.debtIqd, 0.001)
        assertEquals("target_user", targetAcc.earthlinkUsername)
        assertEquals("Explicit User", targetAcc.displayName)
    }

    // -------------------------------------------------------------------------
    // Test E — Normal new Activation
    // -------------------------------------------------------------------------
    @Test
    fun testE_normalNewActivation() = runTest {
        fakeGateway.checkUsernameAvailableResult = true
        fakeGateway.accountCostResult = 40000.0
        fakeGateway.createUserUsingDepositResult = "brand_new_pass"

        val job = viewModel.createUserUsingDeposit(
            username = "brand_new_user",
            phone = "07705555555",
            fullName = "Brand New User",
            pkgIndex = 2,
            depositPass = "dep_pass",
            intentId = "intent_act_E"
        )
        job.join()

        val op = ledgerRepo.getPendingOperationByIntentId("intent_act_E")!!
        assertEquals("COMPLETED", op.status)
        val payloadObj = org.json.JSONObject(op.payloadJson)
        val targetLocalId = payloadObj.getString("localAccountId")

        // Expected: new local target created, exact Activation amount materialized once
        val newAcc = accountDao.getByIdOneShot(targetLocalId)
        assertNotNull("new local target created", newAcc)
        assertEquals("brand_new_user", newAcc!!.earthlinkUsername)
        assertEquals(40000.0, newAcc.debtIqd, 0.001)

        val ledger = ledgerDao.getByAccountIdOneShot(targetLocalId)
        assertEquals("exact Activation amount materialized once", 1, ledger.size)
        assertEquals(40000.0, ledger[0].amountIqd, 0.001)
        assertEquals(op.businessTransactionId, ledger[0].id)
    }

    // -------------------------------------------------------------------------
    // Test F — Explicit localAccountId points to history-only account (fails closed)
    // -------------------------------------------------------------------------
    @Test
    fun testF_explicitLocalAccountIdPointsToHistoryOnlyAccount_failsClosed() = runTest {
        val historyOnlyAcc = LocalAccount(
            id = "history_acc_F",
            earthlinkUsername = "recycled_user",
            displayName = "Old History Subscriber",
            debtIqd = 10000.0,
            isHistoryOnlySubscriber = true
        )
        accountDao.insert(historyOnlyAcc)

        val txId = "tx_act_history_only_target"
        val payload = """{"username":"recycled_user","localAccountId":"history_acc_F","fullName":"New Guy","phone":"07701112222"}"""

        pendingDao.insert(
            PendingExternalOperation(
                businessTransactionId = txId,
                operationIntentId = "intent_act_F",
                accountId = "recycled_user",
                operationType = "ACTIVATION",
                amountIqd = 35000L,
                payloadJson = payload,
                status = "PENDING",
                dispatchClaimCount = 1
            )
        )

        try {
            ledgerRepo.resolvePendingOperationVerifiedSuccess(txId, "[VERIFIED ACTIVATION]")
            fail("Expected IllegalStateException because explicit localAccountId points to history-only account")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Exception message must mention EXPLICIT_LOCAL_FINANCIAL_TARGET_INVALID",
                e.message?.contains("EXPLICIT_LOCAL_FINANCIAL_TARGET_INVALID") == true
            )
        }

        // Verify history-only account was NEVER mutated financially
        val historyAccAfter = accountDao.getByIdOneShot("history_acc_F")!!
        assertEquals("Debt must not change", 10000.0, historyAccAfter.debtIqd, 0.001)
        assertTrue("Must remain history-only", historyAccAfter.isHistoryOnlySubscriber)
        assertEquals("Display name must not be overwritten", "Old History Subscriber", historyAccAfter.displayName)

        // Verify zero ledger entries were attached
        val historyLedgers = ledgerDao.getByAccountIdOneShot("history_acc_F")
        assertTrue("History-only account must receive NO ledger entries", historyLedgers.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Test G — Legacy Activation recovery with history-only ID collision (fails closed)
    // -------------------------------------------------------------------------
    @Test
    fun testG_legacyActivationRecovery_historyOnlyIdCollision_failsClosed() = runTest {
        val historyAcc = LocalAccount(
            id = "recycled_user",
            earthlinkUsername = "recycled_user",
            displayName = "Old Historical User",
            debtIqd = 15000.0,
            isHistoryOnlySubscriber = true
        )
        accountDao.insert(historyAcc)

        val txId = "tx_act_legacy_collision"
        // Legacy payload has NO localAccountId
        val legacyPayload = """{"username":"recycled_user","fullName":"New Guy","phone":"07703334444"}"""

        pendingDao.insert(
            PendingExternalOperation(
                businessTransactionId = txId,
                operationIntentId = "intent_act_G",
                accountId = "recycled_user",
                operationType = "ACTIVATION",
                amountIqd = 35000L,
                payloadJson = legacyPayload,
                status = "PENDING",
                dispatchClaimCount = 1
            )
        )

        try {
            ledgerRepo.resolvePendingOperationVerifiedSuccess(txId, "[VERIFIED ACTIVATION]")
            fail("Expected IllegalStateException: legacy activation recovery must fail closed on history-only ID collision")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Exception message must mention MISSING_LOCAL_FINANCIAL_TARGET or history-only",
                e.message?.contains("MISSING_LOCAL_FINANCIAL_TARGET") == true
            )
        }

        // Verify history-only account was NEVER resurrected or mutated
        val historyAccAfter = accountDao.getByIdOneShot("recycled_user")!!
        assertEquals("Debt must remain untouched", 15000.0, historyAccAfter.debtIqd, 0.001)
        assertTrue("Account must remain history-only", historyAccAfter.isHistoryOnlySubscriber)
        assertEquals("Display name must not be overwritten", "Old Historical User", historyAccAfter.displayName)

        // Verify zero ledger entries were attached
        val ledgers = ledgerDao.getByAccountIdOneShot("recycled_user")
        assertTrue("History-only account must receive zero ledger entries", ledgers.isEmpty())
    }
}
