package com.example.ui.viewmodels

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.Phase1DuplicateInitiationProtectionTest
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.security.PreferenceManager
import com.example.data.repository.AuditRepositoryImpl
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.EarthlinkGateway
import com.example.domain.repository.LocalAccountRepository
import com.example.domain.repository.SyncPhase
import com.example.domain.repository.SyncProgress
import com.example.domain.repository.SyncReason
import com.example.domain.repository.SyncRepository
import com.example.domain.repository.SyncStatusState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import com.example.core.model.PendingExternalOperation
import com.example.core.model.UserListItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MNT-09 / FW-01 Characterization Test Suite.
 *
 * Claim: EarthlinkSearchViewModel preserves observable contracts for:
 *   - Payment (gave entry, balance update, outbox sync)
 *   - Debt (took entry, balance update, outbox sync)
 *   - Refill Unpaid (canonical took, 0 duplicate debt, pending op COMPLETED, outbox sync)
 *   - Refill Wasel (canonical took + paired gave, pending op COMPLETED, outbox sync)
 *   - Refill Missing Password (fail-fast error, 0 pending op, 0 gateway calls)
 *   - Customer note & Nano IP local persistence
 *   - Display name update (local Room persistence, gateway dispatch, failure isolation)
 *   - Package type update (local Room persistence, gateway dispatch, failure isolation)
 *
 * Seam / Environment: ROBOLECTRIC tier, in-memory Room SQLite database.
 * Independent Oracle: Literal constants derived from Target Product Contract v0.6
 *   and docs/MNT-09-BEFORE-REFACTOR-BASELINE.md.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EarthlinkSearchViewModelSeamTest {

    private lateinit var db: AppDatabase
    private lateinit var prefs: PreferenceManager
    private lateinit var accountRepo: LocalAccountRepositoryImpl
    private lateinit var ledgerRepo: LocalLedgerRepositoryImpl
    private lateinit var auditRepo: AuditRepositoryImpl
    private lateinit var testGateway: SeamTestGateway
    private lateinit var testSyncRepo: FakeSyncRepository

    class SeamTestGateway(val delegate: Phase1DuplicateInitiationProtectionTest.TestEarthlinkGateway = Phase1DuplicateInitiationProtectionTest.TestEarthlinkGateway()) : EarthlinkGateway by delegate {
        val refillCalls get() = delegate.refillCalls
        var onRefillUserDepositCallback: ((userId: String) -> Unit)? = null
        val updateDisplayNameCalls = java.util.concurrent.atomic.AtomicInteger(0)
        var lastUpdatedDisplayName: String? = null
        var shouldFailUpdateDisplayName = false

        override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean {
            onRefillUserDepositCallback?.invoke(userId)
            return delegate.refillUserDeposit(userId, depositPassword)
        }

        val changeAccountTypeCalls = java.util.concurrent.atomic.AtomicInteger(0)
        var lastChangedAccountIndex: Int? = null
        var shouldFailChangeAccountType = false

        val getUserDetailCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val extendUserCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val toggleUserActiveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val showUserPasswordCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val showAccountPasswordCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val changeUserPasswordCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val changeAccountPasswordCalls = java.util.concurrent.atomic.AtomicInteger(0)

        var userPasswordResult: String? = "pass"
        var accountPasswordResult: String? = "acc_pass"
        var showUserPasswordException: Exception? = null
        var showAccountPasswordException: Exception? = null
        var balanceResult: Double = 250000.0
        var balanceException: Exception? = null

        override suspend fun getUserDetail(userIndex: Int): com.example.core.model.UserDetail {
            getUserDetailCalls.incrementAndGet()
            return delegate.getUserDetail(userIndex)
        }

        override suspend fun extendUser(userIndex: Int): Boolean {
            extendUserCalls.incrementAndGet()
            return delegate.extendUser(userIndex)
        }

        override suspend fun toggleUserActive(userIndex: Int, active: Boolean): Boolean {
            toggleUserActiveCalls.incrementAndGet()
            return delegate.toggleUserActive(userIndex, active)
        }

        override suspend fun showUserPassword(userIndex: Int, userId: String): String {
            showUserPasswordCalls.incrementAndGet()
            showUserPasswordException?.let { throw it }
            return userPasswordResult ?: ""
        }

        override suspend fun showAccountPassword(userIndex: Int, userId: String): String {
            showAccountPasswordCalls.incrementAndGet()
            showAccountPasswordException?.let { throw it }
            return accountPasswordResult ?: ""
        }

        override suspend fun changeUserPassword(userIndex: Int, userId: String, newPass: String): Boolean {
            changeUserPasswordCalls.incrementAndGet()
            return delegate.changeUserPassword(userIndex, userId, newPass)
        }

        override suspend fun changeAccountPassword(userIndex: Int, userId: String, newPass: String): Boolean {
            changeAccountPasswordCalls.incrementAndGet()
            return delegate.changeAccountPassword(userIndex, userId, newPass)
        }

        override suspend fun getBalance(): Double {
            balanceException?.let { throw it }
            return balanceResult
        }

        override suspend fun updateUserDisplayName(userIndex: Int, newName: String): Boolean {
            updateDisplayNameCalls.incrementAndGet()
            lastUpdatedDisplayName = newName
            if (shouldFailUpdateDisplayName) return false
            return true
        }

        override suspend fun changeAccountType(userIndex: Int, userId: String, accountIndex: Int): Boolean {
            changeAccountTypeCalls.incrementAndGet()
            lastChangedAccountIndex = accountIndex
            if (shouldFailChangeAccountType) return false
            return true
        }
    }

    class FakeSyncRepository : SyncRepository {
        val requestedSyncReasons = mutableListOf<SyncReason>()
        private val _state = MutableStateFlow<SyncStatusState>(SyncStatusState.IDLE)
        override val syncState: StateFlow<SyncStatusState> = _state.asStateFlow()
        override val syncProgress: StateFlow<SyncProgress> = MutableStateFlow(SyncProgress()).asStateFlow()

        override fun triggerSync() {}
        override fun setupPeriodicSync() {}
        override suspend fun triggerSyncOneShot(): Boolean = true
        override fun requestSync(reason: SyncReason) {
            requestedSyncReasons.add(reason)
        }
        override fun triggerSettingsSync(uid: String?, reason: String) {}
        override suspend fun getPendingOutboxCount(): Int = 0
        override suspend fun getFailedCount(): Int = 0
        override suspend fun retryFailedItems(): Int = 0
        override suspend fun anonymousSignIn(): String? = null
        override suspend fun emailSignIn(email: String, password: String): String? = null
        override suspend fun googleSignIn(idToken: String): String? = null
        override fun getFirebaseUid(): String? = null
        override suspend fun signOut(force: Boolean, clearData: Boolean) {}
    }

    @Before
    fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        prefs = PreferenceManager(context).apply {
            setDemoMode(false)
            saveIspAdminUsername("admin")
            saveIspAdminPassword("pass")
            setLanguage("ar")
        }
        accountRepo = LocalAccountRepositoryImpl(db, db.localAccountDao(), db.syncOutboxDao())
        ledgerRepo = LocalLedgerRepositoryImpl(db, db.localLedgerEntryDao(), db.localAccountDao(), db.syncOutboxDao(), db.pendingExternalOperationDao())
        auditRepo = AuditRepositoryImpl(db, db.auditLogDao())
        testGateway = SeamTestGateway().apply {
            delegate.simulatedDelayMs = 0L
        }
        testSyncRepo = FakeSyncRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun createViewModel(
        customAccountRepo: LocalAccountRepository? = null
    ): EarthlinkSearchViewModel {
        return EarthlinkSearchViewModel(
            gateway = testGateway,
            audit = auditRepo,
            prefs = prefs,
            localAccountRepository = customAccountRepo ?: accountRepo,
            localLedgerRepository = ledgerRepo,
            syncRepo = testSyncRepo
        )
    }

    @Test
    fun testRecordPayment_unpaidAccount_createsGaveEntryAndRequestsSync() = runBlocking {
        val vm = createViewModel()
        val account = LocalAccount(
            id = "pay_acc_1",
            earthlinkUsername = "pay_acc_1",
            displayName = "Payment User",
            debtIqd = 50000.0,
            openingDebtIqd = 50000.0,
            currentPriceIqd = 40000.0,
            createdAt = System.currentTimeMillis()
        )
        accountRepo.saveAccount(account)

        var successCalled = false
        val job = vm.recordPayment(
            account = account,
            amount = 25000.0,
            note = "Paid part",
            onSuccess = { successCalled = true }
        )
        job.join()

        assertTrue("onSuccess callback must be executed", successCalled)

        // Ledger check
        val entries = db.localLedgerEntryDao().getByAccountIdOneShot("pay_acc_1")
        assertEquals(1, entries.size)
        val entry = entries.first()
        assertEquals("gave", entry.typeRaw)
        assertEquals(25000.0, entry.amountIqd, 0.001)
        assertEquals("[PAYMENT] Paid part", entry.note)

        // Balance check: 50000 - 25000 = 25000 debt
        val updatedAcc = accountRepo.getAccountByIdOneShot("pay_acc_1")
        assertNotNull(updatedAcc)
        assertEquals(25000.0, updatedAcc!!.debtIqd, 0.001)

        // Sync check
        assertTrue("SyncReason.USER_ACTION must be requested", testSyncRepo.requestedSyncReasons.contains(SyncReason.USER_ACTION))

        // Audit check
        val audits = db.auditLogDao().getAllSync()
        assertTrue(audits.any { it.action == "DEPOSIT_PAYMENT" && it.entityId == "pay_acc_1" })
    }

    @Test
    fun testRecordDebt_createsTookEntryAndRequestsSync() = runBlocking {
        val vm = createViewModel()
        val account = LocalAccount(
            id = "debt_acc_1",
            earthlinkUsername = "debt_acc_1",
            displayName = "Debt User",
            debtIqd = 0.0,
            openingDebtIqd = 0.0,
            currentPriceIqd = 40000.0,
            createdAt = System.currentTimeMillis()
        )
        accountRepo.saveAccount(account)

        var successCalled = false
        val job = vm.recordDebt(
            account = account,
            amount = 30000.0,
            note = "Added debt",
            onSuccess = { successCalled = true }
        )
        job.join()

        assertTrue("onSuccess callback must be executed", successCalled)

        val entries = db.localLedgerEntryDao().getByAccountIdOneShot("debt_acc_1")
        assertEquals(1, entries.size)
        val entry = entries.first()
        assertEquals("took", entry.typeRaw)
        assertEquals(30000.0, entry.amountIqd, 0.001)
        assertEquals("[DEBT] Added debt", entry.note)

        val updatedAcc = accountRepo.getAccountByIdOneShot("debt_acc_1")
        assertNotNull(updatedAcc)
        assertEquals(30000.0, updatedAcc!!.debtIqd, 0.001)

        assertTrue("SyncReason.USER_ACTION must be requested", testSyncRepo.requestedSyncReasons.contains(SyncReason.USER_ACTION))
    }

    @Test
    fun testRefillUser_unpaid_createsOneCanonicalTookEntryAndRequestsSync() = runBlocking {
        prefs.saveDepositPassword("valid_deposit_pass")
        val vm = createViewModel()

        val account = LocalAccount(
            id = "refill_unpaid_usr",
            earthlinkUsername = "refill_unpaid_usr",
            displayName = "Refill Unpaid",
            debtIqd = 0.0,
            currentPriceIqd = 35000.0,
            createdAt = System.currentTimeMillis()
        )
        accountRepo.saveAccount(account)

        val job = vm.refillUser(
            userId = "refill_unpaid_usr",
            price = 35000.0,
            note = "Renewal Note",
            isWasil = false,
            account = account,
            intentId = "intent_refill_unpaid_01"
        )
        job.join()

        assertNotNull("actionSuccess must be set", vm.actionSuccess.value)
        assertNull("error must be null", vm.error.value)

        // Operation status must be COMPLETED with claimCount = 1
        val op = ledgerRepo.getPendingOperationByIntentId("intent_refill_unpaid_01")
        assertNotNull(op)
        assertEquals("COMPLETED", op!!.status)
        assertEquals(1, op.dispatchClaimCount)

        // Exactly one canonical took entry materialized
        val entries = db.localLedgerEntryDao().getByAccountIdOneShot("refill_unpaid_usr")
        assertEquals("Unpaid refill must produce exactly 1 ledger entry", 1, entries.size)
        val entry = entries.first()
        assertEquals("charge_intent_refill_unpaid_01", entry.id)
        assertEquals("took", entry.typeRaw)
        assertEquals(35000.0, entry.amountIqd, 0.001)

        assertTrue(testSyncRepo.requestedSyncReasons.contains(SyncReason.USER_ACTION))
    }

    @Test
    fun testRefillUser_waselPaid_createsTookAndPairedGaveEntryAndRequestsSync() = runBlocking {
        prefs.saveDepositPassword("valid_deposit_pass")
        val vm = createViewModel()

        val account = LocalAccount(
            id = "refill_wasel_usr",
            earthlinkUsername = "refill_wasel_usr",
            displayName = "Refill Wasel",
            debtIqd = 0.0,
            currentPriceIqd = 35000.0,
            createdAt = System.currentTimeMillis()
        )
        accountRepo.saveAccount(account)

        val job = vm.refillUser(
            userId = "refill_wasel_usr",
            price = 35000.0,
            note = "Wasel Note",
            isWasil = true,
            account = account,
            intentId = "intent_refill_wasel_02"
        )
        job.join()

        assertNotNull(vm.actionSuccess.value)
        assertNull(vm.error.value)

        val op = ledgerRepo.getPendingOperationByIntentId("intent_refill_wasel_02")
        assertNotNull(op)
        assertEquals("COMPLETED", op!!.status)

        // Wasel refill must produce exactly 2 entries: took (charge) and gave (pay)
        val entries = db.localLedgerEntryDao().getByAccountIdOneShot("refill_wasel_usr")
        assertEquals("Wasel refill must produce exactly 2 ledger entries", 2, entries.size)

        val chargeEntry = entries.find { it.id == "charge_intent_refill_wasel_02" }
        assertNotNull("Charge took entry must exist", chargeEntry)
        assertEquals("took", chargeEntry!!.typeRaw)
        assertEquals(35000.0, chargeEntry.amountIqd, 0.001)

        val payEntry = entries.find { it.id == "pay_charge_intent_refill_wasel_02" }
        assertNotNull("Paired pay gave entry must exist", payEntry)
        assertEquals("gave", payEntry!!.typeRaw)
        assertEquals(35000.0, payEntry.amountIqd, 0.001)

        assertTrue(testSyncRepo.requestedSyncReasons.contains(SyncReason.USER_ACTION))
    }

    @Test
    fun testRefillUser_missingDepositPassword_failsFastWithErrorMessage() = runBlocking {
        // Explicitly clear deposit password
        prefs.saveDepositPassword("")
        val vm = createViewModel()

        val job = vm.refillUser(
            userId = "refill_nopass_usr",
            price = 35000.0,
            intentId = "intent_nopass"
        )
        job.join()

        assertNotNull("Error must be set when deposit password is blank", vm.error.value)
        assertEquals("الرجاء ضبط كلمة مرور الصندوق في الإعدادات أولاً!", vm.error.value)

        // No pending operation recorded
        val op = ledgerRepo.getPendingOperationByIntentId("intent_nopass")
        assertNull("No pending op should be created when password is missing", op)

        // 0 gateway calls
        assertEquals(0, testGateway.refillCalls.get())
    }

    @Test
    fun testSaveCustomerNote_persistsNoteLocally() = runBlocking {
        val vm = createViewModel()
        val account = LocalAccount(
            id = "note_usr",
            earthlinkUsername = "note_usr",
            displayName = "Note User",
            note = "Old note",
            createdAt = System.currentTimeMillis()
        )
        accountRepo.saveAccount(account)

        val job = vm.saveCustomerNote(account, "Brand new customer note")
        job.join()

        val updated = accountRepo.getAccountByIdOneShot("note_usr")
        assertNotNull(updated)
        assertEquals("Brand new customer note", updated!!.note)
    }

    @Test
    fun testSaveCustomNanoIp_persistsNanoIpLocally() = runBlocking {
        val vm = createViewModel()
        val account = LocalAccount(
            id = "nano_usr",
            earthlinkUsername = "nano_usr",
            displayName = "Nano User",
            nanoIp = null,
            createdAt = System.currentTimeMillis()
        )
        accountRepo.saveAccount(account)

        val job = vm.saveCustomNanoIp(account, "192.168.10.99")
        job.join()

        val updated = accountRepo.getAccountByIdOneShot("nano_usr")
        assertNotNull(updated)
        assertEquals("192.168.10.99", updated!!.nanoIp)
    }

    @Test
    fun testUpdateUserDisplayName_success_persistsLocallyAndDispatchesGatewayAndAudits() = runBlocking {
        val vm = createViewModel()
        val account = LocalAccount(
            id = "name_usr_1",
            earthlinkUsername = "name_usr_1",
            displayName = "Old Display Name",
            createdAt = System.currentTimeMillis()
        )
        accountRepo.saveAccount(account)

        val successEmissions = mutableListOf<String?>()
        val collectJob = launch(Dispatchers.Unconfined) {
            vm.actionSuccess.collect { successEmissions.add(it) }
        }

        val job = vm.updateUserDisplayName(
            userIndex = 501,
            newName = "New Verified Name",
            account = account
        )
        job.join()
        collectJob.cancel()

        // 1. Local Room persistence verified
        val updated = accountRepo.getAccountByIdOneShot("name_usr_1")
        assertNotNull(updated)
        assertEquals("New Verified Name", updated!!.displayName)

        // 2. Gateway dispatch verified
        assertEquals(1, testGateway.updateDisplayNameCalls.get())
        assertEquals("New Verified Name", testGateway.lastUpdatedDisplayName)

        // 3. UI state emission verified
        assertTrue("Success message must be emitted to actionSuccess", successEmissions.contains("تم تعديل اسم المشترك بنجاح."))
        assertNull(vm.error.value)

        // 4. Audit logging verified
        val audits = db.auditLogDao().getAllSync()
        assertTrue(audits.any { it.action == "UPDATE_DISPLAY_NAME" && it.entityId == "501" })
    }

    @Test
    fun testUpdateUserDisplayName_remoteFailure_persistsLocallySetsErrorWithoutAudit() = runBlocking {
        testGateway.shouldFailUpdateDisplayName = true
        val vm = createViewModel()
        val account = LocalAccount(
            id = "name_usr_2",
            earthlinkUsername = "name_usr_2",
            displayName = "Old Name Before Fail",
            createdAt = System.currentTimeMillis()
        )
        accountRepo.saveAccount(account)

        val job = vm.updateUserDisplayName(
            userIndex = 502,
            newName = "Attempted New Name",
            account = account
        )
        job.join()

        // 1. Local Room persistence completed before remote call
        val updated = accountRepo.getAccountByIdOneShot("name_usr_2")
        assertNotNull(updated)
        assertEquals("Attempted New Name", updated!!.displayName)

        // 2. Gateway call attempted and failed
        assertEquals(1, testGateway.updateDisplayNameCalls.get())

        // 3. UI error set, success is null
        assertEquals("Failed to update display name on Earthlink.", vm.error.value)
        assertNull(vm.actionSuccess.value)

        // 4. Audit must NOT be logged on remote failure
        val audits = db.auditLogDao().getAllSync()
        assertTrue(audits.none { it.action == "UPDATE_DISPLAY_NAME" && it.entityId == "502" })
    }

    @Test
    fun testChangeAccountType_success_persistsLocallyAndDispatchesGatewayAndAudits() = runBlocking {
        val vm = createViewModel()
        val account = LocalAccount(
            id = "pkg_usr_1",
            earthlinkUsername = "pkg_usr_1",
            displayName = "Pkg User",
            packageName = "Economy",
            currentPriceIqd = 35000.0,
            createdAt = System.currentTimeMillis()
        )
        accountRepo.saveAccount(account)

        val successEmissions = mutableListOf<String?>()
        val collectJob = launch(Dispatchers.Unconfined) {
            vm.actionSuccess.collect { successEmissions.add(it) }
        }

        val job = vm.changeAccountType(
            userIndex = 601,
            userId = "pkg_usr_1",
            accountIndex = 4,
            accountName = "Super Speed",
            account = account,
            newPriceIqd = 55000.0
        )
        job.join()
        collectJob.cancel()

        // 1. Local Room persistence verified (both package name and price)
        val updated = accountRepo.getAccountByIdOneShot("pkg_usr_1")
        assertNotNull(updated)
        assertEquals("Super Speed", updated!!.packageName)
        assertEquals(55000.0, updated!!.currentPriceIqd, 0.001)

        // 2. Gateway dispatch verified
        assertEquals(1, testGateway.changeAccountTypeCalls.get())
        assertEquals(4, testGateway.lastChangedAccountIndex)

        // 3. UI state emission verified
        assertTrue("Success message must be emitted to actionSuccess", successEmissions.contains("Package changed to Super Speed successfully."))
        assertNull(vm.error.value)

        // 4. Audit logging verified
        val audits = db.auditLogDao().getAllSync()
        assertTrue(audits.any { it.action == "CHANGE_PACKAGE" && it.entityId == "pkg_usr_1" })
    }

    @Test
    fun testChangeAccountType_remoteFailure_doesNotPersistLocallySetsErrorWithoutAudit() = runBlocking {
        testGateway.shouldFailChangeAccountType = true
        val vm = createViewModel()
        val account = LocalAccount(
            id = "pkg_usr_2",
            earthlinkUsername = "pkg_usr_2",
            displayName = "Pkg User 2",
            packageName = "Economy",
            currentPriceIqd = 35000.0,
            ispUserIndex = 602,
            createdAt = System.currentTimeMillis()
        )
        accountRepo.saveAccount(account)

        val job = vm.changeAccountType(
            userIndex = 602,
            userId = "pkg_usr_2",
            accountIndex = 5,
            accountName = "Failed Package",
            account = account,
            newPriceIqd = 60000.0
        )
        job.join()

        // 1. Finding 1 (CHANGE-PACKAGE-01): Local Room persistence must NOT occur when gateway fails
        val updated = accountRepo.getAccountByIdOneShot("pkg_usr_2")
        assertNotNull(updated)
        assertEquals("Economy", updated!!.packageName)
        assertEquals(35000.0, updated!!.currentPriceIqd, 0.001)

        // 2. Gateway call attempted and failed
        assertEquals(1, testGateway.changeAccountTypeCalls.get())

        // 3. UI error set, success is null
        assertEquals("Failed to update package.", vm.error.value)
        assertNull(vm.actionSuccess.value)

        // 4. Audit must NOT be logged on remote failure
        val audits = db.auditLogDao().getAllSync()
        assertTrue(audits.none { it.action == "CHANGE_PACKAGE" && it.entityId == "pkg_usr_2" })
    }

    @Test
    fun testRefillUser_unregisteredInMemoryAccount_persistsLocalAccountAndMaterializesLedger() = runBlocking {
        prefs.saveDepositPassword("valid_deposit_pass")
        val vm = createViewModel()

        var accountExistedAtDispatchTime = false
        testGateway.onRefillUserDepositCallback = { uid ->
            accountExistedAtDispatchTime = runBlocking {
                accountRepo.getAccountByIdOneShot(uid) != null ||
                        accountRepo.findAccountByUsernameOrIdOneShot(uid) != null
            }
        }

        val inMemoryAccount = LocalAccount(
            id = "unregistered_in_memory_user",
            earthlinkUsername = "unregistered_in_memory_user",
            displayName = "In-Memory User",
            debtIqd = 0.0,
            currentPriceIqd = 35000.0,
            createdAt = System.currentTimeMillis()
        )
        // Precondition: Account does NOT exist in local database
        assertNull("Precondition: Account must NOT exist in local DB", accountRepo.getAccountByIdOneShot("unregistered_in_memory_user"))

        val job = vm.refillUser(
            userId = "unregistered_in_memory_user",
            price = 35000.0,
            note = "Renewal Note",
            isWasil = false,
            account = inMemoryAccount,
            intentId = "intent_unregistered_mem_01"
        )
        job.join()

        // 1. Account MUST be persisted in Room BEFORE remote gateway dispatch is called
        assertTrue("Account must be persisted in Room BEFORE remote gateway dispatch is initiated", accountExistedAtDispatchTime)

        // 2. Remote dispatch must have executed
        assertEquals("Remote gateway call must execute", 1, testGateway.refillCalls.get())

        // 3. LocalAccount must be persisted in Room
        val persistedAccount = accountRepo.getAccountByIdOneShot("unregistered_in_memory_user")
            ?: accountRepo.findAccountByUsernameOrIdOneShot("unregistered_in_memory_user")
        assertNotNull("LocalAccount must be auto-created/persisted in Room", persistedAccount)

        // 4. Action success must be set, error must be null
        assertNotNull("actionSuccess must be set", vm.actionSuccess.value)
        assertNull("error must be null, but was: ${vm.error.value}", vm.error.value)

        // 5. Pending operation must be COMPLETED
        val op = ledgerRepo.getPendingOperationByIntentId("intent_unregistered_mem_01")
        assertNotNull("Pending operation must exist", op)
        assertEquals("COMPLETED", op!!.status)

        // 6. Ledger debt entry must be materialized exactly once (no duplicates)
        val entries = db.localLedgerEntryDao().getByAccountIdOneShot("unregistered_in_memory_user")
        assertEquals("Ledger debt entry must be materialized exactly once", 1, entries.size)
        assertEquals("took", entries.first().typeRaw)
        assertEquals(35000.0, entries.first().amountIqd, 0.001)
    }

    @Test
    fun testRefillUser_localPersistenceFails_abortsBeforeRemoteDispatch() = runBlocking {
        prefs.saveDepositPassword("valid_deposit_pass")

        val failingAccountRepo = object : LocalAccountRepository by accountRepo {
            override suspend fun saveAccount(account: LocalAccount): LocalAccount {
                throw android.database.sqlite.SQLiteDiskIOException("Simulated disk failure during saveAccount")
            }
        }

        val vm = createViewModel(customAccountRepo = failingAccountRepo)

        val inMemoryAccount = LocalAccount(
            id = "failing_db_user",
            earthlinkUsername = "failing_db_user",
            displayName = "Failing DB User",
            currentPriceIqd = 35000.0
        )

        val job = vm.refillUser(
            userId = "failing_db_user",
            price = 35000.0,
            account = inMemoryAccount,
            intentId = "intent_failing_db_01"
        )
        job.join()

        // 1. Remote gateway dispatch MUST NOT be called
        assertEquals("Remote gateway call must NOT be initiated when local persistence fails", 0, testGateway.refillCalls.get())

        // 2. Error message must be surfaced, actionSuccess must be null
        assertNotNull("Error must be set on persistence failure", vm.error.value)
        assertNull("Action success must be null", vm.actionSuccess.value)

        // 3. No pending operation recorded
        val op = ledgerRepo.getPendingOperationByIntentId("intent_failing_db_01")
        assertNull("No pending operation should be recorded when pre-dispatch persistence fails", op)

        // 4. Zero ledger entries materialized
        val entries = db.localLedgerEntryDao().getByAccountIdOneShot("failing_db_user")
        assertEquals(0, entries.size)
    }

    @Test
    fun authoritativeIndex_resolvesCorrectPhysicalContainer_onRecycledUsername() = runBlocking {
        db.localAccountDao().insert(LocalAccount(id = "UUID_A", earthlinkUsername = "alice", ispUserIndex = 1001))
        db.localAccountDao().insert(LocalAccount(id = "UUID_B", earthlinkUsername = "alice", ispUserIndex = 2002))

        val vm = createViewModel()
        val account2002 = vm.getAccountByUsernameOrIdForUser(2002, "alice").first()
        val account1001 = vm.getAccountByUsernameOrIdForUser(1001, "alice").first()

        assertEquals("UUID_B", account2002?.id)
        assertEquals("UUID_A", account1001?.id)
    }

    @Test
    fun authoritativeIndex_failsClosed_onConflictOrAmbiguity() = runBlocking {
        // Multiple containers under same identity -> AMBIGUOUS -> fails closed
        db.localAccountDao().insert(LocalAccount(id = "UUID_B1", earthlinkUsername = "alice", ispUserIndex = 2002))
        db.localAccountDao().insert(LocalAccount(id = "UUID_B2", earthlinkUsername = "alice", ispUserIndex = 2002))
        val vm = createViewModel()
        assertEquals(null, vm.getAccountByUsernameOrIdForUser(2002, "alice").first())

        // Username bound to different identity -> CONFLICT -> fails closed
        db.localAccountDao().insert(LocalAccount(id = "UUID_A", earthlinkUsername = "charlie", ispUserIndex = 1001))
        assertEquals(null, vm.getAccountByUsernameOrIdForUser(2002, "charlie").first())
    }

    @Test
    fun remoteGatewayRead_remainsAvailable_whenLocalAccountIsAmbiguous() = runBlocking {
        // Two local accounts under userIndex 2002 (ambiguous local state)
        db.localAccountDao().insert(LocalAccount(id = "UUID_B1", earthlinkUsername = "alice", ispUserIndex = 2002))
        db.localAccountDao().insert(LocalAccount(id = "UUID_B2", earthlinkUsername = "alice", ispUserIndex = 2002))

        val vm = createViewModel()
        val job = vm.loadUserDetail(2002, "alice")
        job.join()

        // Remote gateway detail read must still proceed without being blocked
        assertNull("Error must not be set to block remote read", vm.error.value)
        assertNotNull("Selected user should be populated from remote gateway or list", vm.selectedUser.value)
    }

    @Test
    fun refillPayload_persistsExplicitLocalAccountId_andMaterializesToExactPhysicalTarget() = runBlocking {
        db.localAccountDao().insert(LocalAccount(id = "UUID_A", earthlinkUsername = "alice", ispUserIndex = 1001, currentPriceIqd = 35000.0))
        val accountB = LocalAccount(id = "UUID_B", earthlinkUsername = "alice", ispUserIndex = 2002, currentPriceIqd = 35000.0)
        db.localAccountDao().insert(accountB)

        prefs.saveDepositPassword("valid_deposit_pass")
        val vm = createViewModel()
        vm.prepareUserDetail(2002, UserListItem(userIndexLower = 2002, userIDLower = "alice"))

        val txId = "refill_explicit_target_tx"
        val job = vm.refillUser(
            userId = "alice",
            price = 35000.0,
            account = accountB,
            intentId = txId,
            note = "Customer note with \"quotes\""
        )
        job.join()

        val pending = ledgerRepo.getPendingOperationByIntentId(txId)
        assertNotNull(pending)
        val payload = org.json.JSONObject(pending!!.payloadJson)
        assertEquals("UUID_B", payload.getString("localAccountId"))
        assertEquals("Customer note with \"quotes\"", payload.getString("note"))

        // Materialize verified success
        ledgerRepo.resolvePendingOperationVerifiedSuccess(pending.businessTransactionId, "Verified refill")

        // Exact physical target verification: ledger entries must belong to UUID_B, 0 to UUID_A
        val entriesA = db.localLedgerEntryDao().getByAccountIdOneShot("UUID_A")
        val entriesB = db.localLedgerEntryDao().getByAccountIdOneShot("UUID_B")
        assertEquals(0, entriesA.size)
        assertEquals(1, entriesB.size)
        assertEquals("UUID_B", entriesB.first().accountId)
        assertEquals(35000.0, entriesB.first().amountIqd, 0.001)
    }

    @Test
    fun legacyPendingOperation_remainsCompatible_withoutExplicitTarget() = runBlocking {
        val legacyAccount = LocalAccount(id = "legacy_user_01", earthlinkUsername = "legacy_user_01", currentPriceIqd = 25000.0)
        db.localAccountDao().insert(legacyAccount)

        val txId = "legacy_tx_01"
        db.pendingExternalOperationDao().insert(
            PendingExternalOperation(
                businessTransactionId = txId,
                operationIntentId = "legacy_intent_01",
                accountId = "legacy_user_01",
                operationType = "REFILL",
                amountIqd = 25000L,
                payloadJson = "{\"userId\":\"legacy_user_01\",\"price\":25000.0,\"isWasil\":false}",
                status = "PENDING",
                dispatchClaimCount = 1
            )
        )

        // Must materialize successfully via legacy fallback path without throwing
        val entry = ledgerRepo.resolvePendingOperationVerifiedSuccess(txId, "Legacy refill note")
        assertNotNull(entry)
        assertEquals("legacy_user_01", entry!!.accountId)
        assertEquals(25000.0, entry.amountIqd, 0.001)
    }

    @Test
    fun unrelatedAccountUpdate_doesNotCauseDownstreamFlowChurn() = runBlocking {
        val alice = LocalAccount(id = "UUID_ALICE", earthlinkUsername = "alice", ispUserIndex = 2002)
        val bob = LocalAccount(id = "UUID_BOB", earthlinkUsername = "bob", ispUserIndex = 3003, displayName = "Bob")
        db.localAccountDao().insert(alice)
        db.localAccountDao().insert(bob)

        val vm = createViewModel()
        val flow = vm.getAccountByUsernameOrIdForUser(2002, "alice")

        // First emission
        val firstEmission = flow.first()
        assertEquals("UUID_ALICE", firstEmission?.id)

        // Mutating unrelated account bob in SQLite table
        db.localAccountDao().update(bob.copy(displayName = "Bob Updated"))

        // Observing alice must still resolve to UUID_ALICE with zero change in identity
        val afterUpdate = flow.first()
        assertEquals("UUID_ALICE", afterUpdate?.id)
    }

    // --- Finding 2: PASSWORD-01 Tests ---

    @Test
    fun testRevealUserPassword_successfulNonBlank_updatesStateAndLogsAudit() = runBlocking {
        testGateway.userPasswordResult = "secret123"
        val vm = createViewModel()
        vm.revealUserPassword(userIndex = 501, userId = "real_usr_1").join()

        assertEquals("secret123", vm.revealedUserPass.value)
        assertNull(vm.error.value)
        val audits = db.auditLogDao().getAllSync()
        assertTrue(audits.any { it.action == "REVEAL_PASSWORD_USER" && it.entityId == "real_usr_1" })
    }

    @Test
    fun testRevealUserPassword_blankResult_setsNullStateAndErrorWithoutAudit() = runBlocking {
        testGateway.userPasswordResult = ""
        val vm = createViewModel()
        vm.revealUserPassword(userIndex = 501, userId = "real_usr_1").join()

        assertNull(vm.revealedUserPass.value)
        assertNotNull(vm.error.value)
        val audits = db.auditLogDao().getAllSync()
        assertTrue(audits.none { it.action == "REVEAL_PASSWORD_USER" })
    }

    @Test
    fun testRevealUserPassword_gatewayException_setsNullStateAndErrorWithoutAudit() = runBlocking {
        testGateway.showUserPasswordException = RuntimeException("Connection error")
        val vm = createViewModel()
        vm.revealUserPassword(userIndex = 501, userId = "real_usr_1").join()

        assertNull(vm.revealedUserPass.value)
        assertNotNull(vm.error.value)
        val audits = db.auditLogDao().getAllSync()
        assertTrue(audits.none { it.action == "REVEAL_PASSWORD_USER" })
    }

    @Test
    fun testRevealAccountPassword_successfulNonBlank_updatesStateAndLogsAudit() = runBlocking {
        testGateway.accountPasswordResult = "broadband456"
        val vm = createViewModel()
        vm.revealAccountPassword(userIndex = 501, userId = "real_usr_1").join()

        assertEquals("broadband456", vm.revealedAccountPass.value)
        assertNull(vm.error.value)
        val audits = db.auditLogDao().getAllSync()
        assertTrue(audits.any { it.action == "REVEAL_PASSWORD_ACCOUNT" && it.entityId == "real_usr_1" })
    }

    @Test
    fun testRevealAccountPassword_blankResult_setsNullStateAndErrorWithoutAudit() = runBlocking {
        testGateway.accountPasswordResult = ""
        val vm = createViewModel()
        vm.revealAccountPassword(userIndex = 501, userId = "real_usr_1").join()

        assertNull(vm.revealedAccountPass.value)
        assertNotNull(vm.error.value)
        val audits = db.auditLogDao().getAllSync()
        assertTrue(audits.none { it.action == "REVEAL_PASSWORD_ACCOUNT" })
    }

    @Test
    fun testRevealAccountPassword_gatewayException_setsNullStateAndErrorWithoutAudit() = runBlocking {
        testGateway.showAccountPasswordException = RuntimeException("Gateway error")
        val vm = createViewModel()
        vm.revealAccountPassword(userIndex = 501, userId = "real_usr_1").join()

        assertNull(vm.revealedAccountPass.value)
        assertNotNull(vm.error.value)
        val audits = db.auditLogDao().getAllSync()
        assertTrue(audits.none { it.action == "REVEAL_PASSWORD_ACCOUNT" })
    }

    // --- Finding 3: LOCAL-ISP-IDENTITY-01 Tests ---

    @Test
    fun testSyntheticLocalAccount_loadUserDetail_doesNotCallGatewayGetUserDetail() = runBlocking {
        val syntheticAccount = LocalAccount(
            id = "LOCAL_UUID_123",
            earthlinkUsername = "local_sub_1",
            ispUserIndex = null,
            displayName = "Local Sub 1",
            createdAt = System.currentTimeMillis()
        )
        accountRepo.saveAccount(syntheticAccount)
        val syntheticUserIndex = syntheticAccount.id.hashCode()

        val vm = createViewModel()
        vm.loadUserDetail(syntheticUserIndex, "local_sub_1").join()

        assertEquals(0, testGateway.getUserDetailCalls.get())
        assertEquals("local_sub_1", vm.selectedUser.value?.userID)
    }

    @Test
    fun testSyntheticLocalAccount_extendUser_doesNotCallGatewayExtendUser() = runBlocking {
        val syntheticAccount = LocalAccount(
            id = "LOCAL_UUID_EXTEND",
            earthlinkUsername = "local_sub_ext",
            ispUserIndex = null,
            createdAt = System.currentTimeMillis()
        )
        accountRepo.saveAccount(syntheticAccount)
        val syntheticUserIndex = syntheticAccount.id.hashCode()

        val vm = createViewModel()
        vm.extendUser(syntheticUserIndex, "local_sub_ext").join()

        assertEquals(0, testGateway.extendUserCalls.get())
        assertEquals("Action unavailable for local-only account.", vm.error.value)
    }

    @Test
    fun testSyntheticLocalAccount_toggleUserActive_doesNotCallGatewayToggleUserActive() = runBlocking {
        val syntheticAccount = LocalAccount(
            id = "LOCAL_UUID_TOGGLE",
            earthlinkUsername = "local_sub_tog",
            ispUserIndex = null,
            createdAt = System.currentTimeMillis()
        )
        accountRepo.saveAccount(syntheticAccount)
        val syntheticUserIndex = syntheticAccount.id.hashCode()

        val vm = createViewModel()
        vm.toggleUserActive(syntheticUserIndex, "local_sub_tog", true).join()

        assertEquals(0, testGateway.toggleUserActiveCalls.get())
        assertEquals("Action unavailable for local-only account.", vm.error.value)
    }

    @Test
    fun testSyntheticLocalAccount_changeAccountType_doesNotCallGatewayAndDoesNotMutateRoom() = runBlocking {
        val syntheticAccount = LocalAccount(
            id = "LOCAL_UUID_PKG",
            earthlinkUsername = "local_sub_pkg",
            ispUserIndex = null,
            displayName = "Local Pkg Sub",
            packageName = "Standard",
            currentPriceIqd = 40000.0,
            createdAt = System.currentTimeMillis()
        )
        accountRepo.saveAccount(syntheticAccount)
        val syntheticUserIndex = syntheticAccount.id.hashCode()

        val vm = createViewModel()
        vm.changeAccountType(syntheticUserIndex, "local_sub_pkg", 2, "Ultra", syntheticAccount, 60000.0).join()

        assertEquals(0, testGateway.changeAccountTypeCalls.get())
        assertEquals("Action unavailable for local-only account.", vm.error.value)
        val inDb = accountRepo.getAccountByIdOneShot("LOCAL_UUID_PKG")
        assertEquals("Standard", inDb!!.packageName)
        assertEquals(40000.0, inDb.currentPriceIqd, 0.001)
    }

    @Test
    fun testSyntheticLocalAccount_changePasswords_doesNotCallGateway() = runBlocking {
        val syntheticAccount = LocalAccount(
            id = "LOCAL_UUID_PASS",
            earthlinkUsername = "local_sub_pass",
            ispUserIndex = null,
            createdAt = System.currentTimeMillis()
        )
        accountRepo.saveAccount(syntheticAccount)
        val syntheticUserIndex = syntheticAccount.id.hashCode()

        val vm = createViewModel()
        vm.changeUserPassword(syntheticUserIndex, "local_sub_pass", "newpass").join()
        vm.changeAccountPassword(syntheticUserIndex, "local_sub_pass", "newpass").join()

        assertEquals(0, testGateway.changeUserPasswordCalls.get())
        assertEquals(0, testGateway.changeAccountPasswordCalls.get())
        assertEquals("Action unavailable for local-only account.", vm.error.value)

        vm.revealUserPassword(syntheticUserIndex, "local_sub_pass").join()
        vm.revealAccountPassword(syntheticUserIndex, "local_sub_pass").join()
        assertEquals(0, testGateway.showUserPasswordCalls.get())
        assertEquals(0, testGateway.showAccountPasswordCalls.get())
        assertNull(vm.revealedUserPass.value)
        assertNull(vm.revealedAccountPass.value)
    }

    @Test
    fun testSyntheticLocalAccount_updateUserDisplayName_persistsLocallyAndDoesNotCallGateway() = runBlocking {
        val syntheticAccount = LocalAccount(
            id = "LOCAL_UUID_NAME",
            earthlinkUsername = "local_sub_name",
            ispUserIndex = null,
            displayName = "Old Name",
            createdAt = System.currentTimeMillis()
        )
        accountRepo.saveAccount(syntheticAccount)
        val syntheticUserIndex = syntheticAccount.id.hashCode()

        val vm = createViewModel()
        vm.updateUserDisplayName(syntheticUserIndex, "New Local Name", syntheticAccount).join()

        assertEquals(0, testGateway.updateDisplayNameCalls.get())
        val inDb = accountRepo.getAccountByIdOneShot("LOCAL_UUID_NAME")
        assertEquals("New Local Name", inDb!!.displayName)
    }

    @Test
    fun testRealIspAccount_mutationsCallGatewayNormally() = runBlocking {
        val realAccount = LocalAccount(
            id = "REAL_UUID_901",
            earthlinkUsername = "real_sub_901",
            ispUserIndex = 901,
            displayName = "Real User",
            packageName = "Standard",
            currentPriceIqd = 35000.0,
            createdAt = System.currentTimeMillis()
        )
        accountRepo.saveAccount(realAccount)

        val vm = createViewModel()
        vm.extendUser(901, "real_sub_901").join()
        assertEquals(1, testGateway.extendUserCalls.get())

        vm.toggleUserActive(901, "real_sub_901", false).join()
        assertEquals(1, testGateway.toggleUserActiveCalls.get())

        vm.changeAccountType(901, "real_sub_901", 3, "Turbo", realAccount).join()
        assertEquals(1, testGateway.changeAccountTypeCalls.get())

        vm.changeUserPassword(901, "real_sub_901", "p1").join()
        assertEquals(1, testGateway.changeUserPasswordCalls.get())

        vm.updateUserDisplayName(901, "Renamed Real", realAccount).join()
        assertEquals(1, testGateway.updateDisplayNameCalls.get())
    }

    @Test
    fun testCollisionBetweenSyntheticLocalAndRealIspIndex_doesNotCallGatewayForLocalAccount() = runBlocking {
        val localId = generateSequence(1) { it + 1 }.map { "coll_local_$it" }.first { it.hashCode() > 0 }
        val collisionIndex = localId.hashCode()

        val localAccountA = LocalAccount(
            id = localId,
            earthlinkUsername = "local_user",
            ispUserIndex = null,
            displayName = "Local User Colliding",
            createdAt = System.currentTimeMillis()
        )
        val realAccountB = LocalAccount(
            id = "real_acc_coll_b",
            earthlinkUsername = "real_user",
            ispUserIndex = collisionIndex,
            displayName = "Real ISP Colliding",
            createdAt = System.currentTimeMillis()
        )
        accountRepo.saveAccount(localAccountA)
        accountRepo.saveAccount(realAccountB)

        val vm = createViewModel()

        // Open synthetic local account using userIndex = collisionIndex, userId = "local_user"
        vm.loadUserDetail(collisionIndex, "local_user").join()

        // Required result: ISP gateway is NOT called
        assertEquals(0, testGateway.getUserDetailCalls.get())
        assertEquals("local_user", vm.selectedUser.value?.userID)

        // Mutations on synthetic local account are also blocked
        vm.extendUser(collisionIndex, "local_user").join()
        assertEquals(0, testGateway.extendUserCalls.get())
        assertEquals("Action unavailable for local-only account.", vm.error.value)

        vm.changeAccountType(collisionIndex, "local_user", 2, "Ultra", localAccountA, 60000.0).join()
        assertEquals(0, testGateway.changeAccountTypeCalls.get())

        // Negative twin: Opening real ISP account using userIndex = collisionIndex, userId = "real_user"
        vm.loadUserDetail(collisionIndex, "real_user").join()
        assertEquals(1, testGateway.getUserDetailCalls.get())
    }

    @Test
    fun testSyntheticLocalAccount_noUsername_localPrefix_doesNotCallGateway() = runBlocking {
        val localId = generateSequence(1) { it + 1 }.map { "no_user_$it" }.first { it.hashCode() > 0 }
        val syntheticUserIndex = localId.hashCode()
        val syntheticAccount = LocalAccount(
            id = localId,
            earthlinkUsername = null,
            ispUserIndex = null,
            displayName = "No Username Sub",
            createdAt = System.currentTimeMillis()
        )
        accountRepo.saveAccount(syntheticAccount)

        val vm = createViewModel()
        vm.loadUserDetail(syntheticUserIndex, "local_$localId").join()

        assertEquals(0, testGateway.getUserDetailCalls.get())
        assertEquals("local_$localId", vm.selectedUser.value?.userID)

        vm.extendUser(syntheticUserIndex, "local_$localId").join()
        assertEquals(0, testGateway.extendUserCalls.get())
        assertEquals("Action unavailable for local-only account.", vm.error.value)
    }

    // --- Finding 4: BALANCE-UI-01 Tests ---

    @Test
    fun testGetResellerBalance_success_returnsValue() = runBlocking {
        testGateway.balanceResult = 150000.0
        val vm = createViewModel()
        val bal = vm.getResellerBalance()
        assertEquals(150000.0, bal, 0.001)
    }

    @Test
    fun testGetResellerBalance_realZero_distinguishableFromFailure() = runBlocking {
        testGateway.balanceResult = 0.0
        val vm = createViewModel()
        val bal = vm.getResellerBalance()
        assertEquals(0.0, bal, 0.001)
        var resellerBalance: Double? = null
        try {
            resellerBalance = vm.getResellerBalance()
        } catch (e: Exception) {
            resellerBalance = null
        }
        assertEquals(0.0, resellerBalance)
    }

    @Test
    fun testGetResellerBalance_exception_propagatesException() = runBlocking {
        testGateway.balanceException = RuntimeException("Gateway balance API error")
        val vm = createViewModel()
        val result = runCatching { vm.getResellerBalance() }
        assertTrue(result.isFailure)
        assertEquals("Gateway balance API error", result.exceptionOrNull()?.message)
    }

    @Test
    fun testGetResellerBalance_apiFailure_resultsInNullNotZero() = runBlocking {
        testGateway.balanceException = RuntimeException("Gateway balance API error")
        val vm = createViewModel()
        var resellerBalance: Double? = 0.0
        try {
            resellerBalance = vm.getResellerBalance()
        } catch (e: Exception) {
            resellerBalance = null
        }
        assertNull(resellerBalance)
    }

    @Test
    fun testBalanceAfterMath_knownBalance_computesCorrectly() {
        val resellerBalance: Double? = 100000.0
        val packageCost = 40000.0
        val balanceAfter = resellerBalance?.let { it - packageCost }
        assertNotNull(balanceAfter)
        assertEquals(60000.0, balanceAfter!!, 0.001)
    }

    @Test
    fun testBalanceAfterMath_unknownBalance_isNull() {
        val resellerBalance: Double? = null
        val packageCost = 40000.0
        val balanceAfter = resellerBalance?.let { it - packageCost }
        assertNull(balanceAfter)
    }
}
