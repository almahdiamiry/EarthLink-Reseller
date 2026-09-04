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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
    fun testChangeAccountType_remoteFailure_persistsLocallySetsErrorWithoutAudit() = runBlocking {
        testGateway.shouldFailChangeAccountType = true
        val vm = createViewModel()
        val account = LocalAccount(
            id = "pkg_usr_2",
            earthlinkUsername = "pkg_usr_2",
            displayName = "Pkg User 2",
            packageName = "Economy",
            currentPriceIqd = 35000.0,
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

        // 1. Local Room persistence completed before remote call
        val updated = accountRepo.getAccountByIdOneShot("pkg_usr_2")
        assertNotNull(updated)
        assertEquals("Failed Package", updated!!.packageName)
        assertEquals(60000.0, updated!!.currentPriceIqd, 0.001)

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
}
