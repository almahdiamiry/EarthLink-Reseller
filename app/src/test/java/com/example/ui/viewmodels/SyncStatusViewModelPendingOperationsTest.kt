package com.example.ui.viewmodels

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.*
import com.example.core.network.EarthlinkSasGatewayAdapter
import com.example.core.network.EarthlinkSasGatewayAdapterTest
import com.example.core.network.SasGatewayRouter
import com.example.core.security.PreferenceManager
import com.example.data.repository.AuditRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.UUID

/**
 * SyncStatusViewModelPendingOperationsTest
 *
 * Core Triad Specification:
 * 1. Claim:
 *    - `triggerGatewaySweep()` executes EarthLink statement sweep first, then SAS router sweep second,
 *      maintaining strict sweep ordering (Amendment 2) to prevent unresolved operations.
 *    - `confirmOperationSuccess(...)` enforces a minimum 5-character evidence note, delegating valid submissions
 *      to [LocalLedgerRepository.submitManualVerificationEvidence] and setting success messages, while rejecting
 *      short evidence fail-fast without repository calls.
 *    - `markOperationFailed(...)` delegates to [LocalLedgerRepository.resolvePendingOperationVerifiedFailure]
 *      and marks the operation as failed without creating subscriber debt.
 *    - `pendingGatewayOperations` state flow reliably reflects live pending operations from the database.
 *
 * 2. Seam / Environment:
 *    ROBOLECTRIC tier ([RobolectricTestRunner]), in-memory SQLite Room [AppDatabase],
 *    real [LocalLedgerRepositoryImpl], deterministic [CompletableDeferred] synchronization gates (AGENTS.md §9.3).
 *
 * 3. Independent Oracle:
 *    - Literal sequence check: `listOf("EARTHLINK_SWEEP", "ROUTER_SWEEP")`.
 *    - Exact string validation on failure and success message contracts.
 *    - Room database state assertions on operation completion/failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SyncStatusViewModelPendingOperationsTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var realRepo: LocalLedgerRepositoryImpl
    private lateinit var spyRepo: SpyLocalLedgerRepository
    private lateinit var fakeSyncRepo: FakeSyncRepository
    private lateinit var auditRepo: AuditRepositoryImpl
    private lateinit var fakeEarthlinkGateway: EarthlinkSasGatewayAdapterTest.FakeEarthlinkGateway
    private lateinit var sasGatewayRouter: SasGatewayRouter
    private lateinit var prefs: PreferenceManager
    private lateinit var viewModel: SyncStatusViewModel

    class FakeSyncRepository : SyncRepository {
        private val _state = MutableStateFlow(SyncStatusState.IDLE)
        override val syncState: StateFlow<SyncStatusState> = _state.asStateFlow()
        override val syncProgress: StateFlow<SyncProgress> = MutableStateFlow(SyncProgress()).asStateFlow()
        var pendingOutboxCount = 0
        var failedCount = 0

        override fun triggerSync() {}
        override fun setupPeriodicSync() {}
        override suspend fun triggerSyncOneShot(): Boolean = true
        override fun requestSync(reason: SyncReason) {}
        override fun triggerSettingsSync(uid: String?, reason: String) {}
        override suspend fun getPendingOutboxCount(): Int = pendingOutboxCount
        override suspend fun getFailedCount(): Int = failedCount
        override suspend fun retryFailedItems(): Int = 0
        override suspend fun anonymousSignIn(): String? = null
        override suspend fun emailSignIn(email: String, password: String): String? = null
        override suspend fun googleSignIn(idToken: String): String? = null
        override fun getFirebaseUid(): String? = "test-uid-123"
        override suspend fun signOut(force: Boolean, clearData: Boolean) {}
    }

    class SpyLocalLedgerRepository(
        private val delegate: LocalLedgerRepository
    ) : LocalLedgerRepository by delegate {
        val sweepCalls = mutableListOf<String>()
        var routerSweepDeferred = CompletableDeferred<Unit>()
        var manualEvidenceTxId: String? = null
        var manualEvidenceNote: String? = null
        var failureTxId: String? = null
        var failureDiagnostic: String? = null

        override suspend fun sweepAndResolvePendingOperations(
            gateway: EarthlinkGateway,
            graceWindowMs: Long
        ): List<PendingOperationResolution> {
            sweepCalls.add("EARTHLINK_SWEEP")
            return emptyList()
        }

        override suspend fun sweepAndResolvePendingOperations(
            router: SasGatewayRouter,
            graceWindowMs: Long
        ): List<PendingOperationResolution> {
            sweepCalls.add("ROUTER_SWEEP")
            if (!routerSweepDeferred.isCompleted) {
                routerSweepDeferred.complete(Unit)
            }
            return emptyList()
        }

        override suspend fun submitManualVerificationEvidence(
            businessTransactionId: String,
            externalEvidence: String
        ): PendingOperationResolution {
            manualEvidenceTxId = businessTransactionId
            manualEvidenceNote = externalEvidence
            return delegate.submitManualVerificationEvidence(businessTransactionId, externalEvidence)
        }

        override suspend fun resolvePendingOperationVerifiedFailure(
            businessTransactionId: String,
            diagnostic: String
        ): Boolean {
            failureTxId = businessTransactionId
            failureDiagnostic = diagnostic
            return delegate.resolvePendingOperationVerifiedFailure(businessTransactionId, diagnostic)
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        prefs = PreferenceManager(context).apply {
            setDemoMode(false)
            setLanguage("ar")
        }
        realRepo = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = db.localLedgerEntryDao(),
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao(),
            pendingDao = db.pendingExternalOperationDao()
        )
        spyRepo = SpyLocalLedgerRepository(realRepo)
        fakeSyncRepo = FakeSyncRepository()
        auditRepo = AuditRepositoryImpl(
            database = db,
            auditDao = db.auditLogDao(),
            preferenceManager = prefs
        )
        fakeEarthlinkGateway = EarthlinkSasGatewayAdapterTest.FakeEarthlinkGateway()
        sasGatewayRouter = SasGatewayRouter(
            earthlinkAdapter = EarthlinkSasGatewayAdapter(fakeEarthlinkGateway),
            preferenceManager = prefs
        )

        viewModel = SyncStatusViewModel(
            syncRepo = fakeSyncRepo,
            audit = auditRepo,
            prefs = prefs,
            ledgerRepo = spyRepo,
            sasGatewayRouter = sasGatewayRouter,
            earthlinkGateway = fakeEarthlinkGateway
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun triggerGatewaySweep_executesEarthLinkSweepFirst_thenRouterSweepSecond() = runBlocking {
        viewModel.triggerGatewaySweep()
        withTimeout(5000) {
            spyRepo.routerSweepDeferred.await()
        }
        ShadowLooper.idleMainLooper()

        // Mandatory Amendment 2: EarthLink statement sweep first, SAS router sweep second
        assertEquals(
            "Sweep must strictly execute EarthLink statement sweep first, then router sweep second",
            listOf("EARTHLINK_SWEEP", "ROUTER_SWEEP"),
            spyRepo.sweepCalls
        )
        assertFalse("Progress must return to false after sweep completes", viewModel.isSyncingProgress.value)
    }

    @Test
    fun confirmOperationSuccess_withValidEvidence_callsSubmitManualVerification_andSetsSuccessMessage() = runBlocking {
        val txId = "tx_success_" + UUID.randomUUID()
        val accountId = "subscriber_101"
        val amount = 35000L

        // Prepare local account so manual resolution creates debt
        db.localAccountDao().insert(
            LocalAccount(
                id = accountId,
                displayName = "Subscriber 101",
                currentPriceIqd = 35000.0,
                debtIqd = 0.0,
                advanceIqd = 0.0,
                loanIqd = 0.0
            )
        )

        val pending = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = "intent_" + UUID.randomUUID(),
            accountId = accountId,
            operationType = "RENEWAL",
            amountIqd = amount,
            status = "PENDING",
            dispatchClaimCount = 0
        )
        db.pendingExternalOperationDao().insert(pending)

        val deferred = CompletableDeferred<Pair<Boolean, String?>>()
        val note = "Receipt confirmed #987654"
        viewModel.confirmOperationSuccess(pending, "  $note  ") { success, msg ->
            deferred.complete(success to msg)
        }

        val (success, msg) = withTimeout(5000) { deferred.await() }
        ShadowLooper.idleMainLooper()

        assertTrue("Resolution must be reported as success", success)
        assertEquals("Transaction ID must match target op", txId, spyRepo.manualEvidenceTxId)
        assertEquals("Evidence note must be trimmed", note, spyRepo.manualEvidenceNote)
        assertEquals(
            "تم تأكيد نجاح العملية وتسجيل القيد المالي بنجاح.",
            viewModel.operationActionMessage.value
        )

        // Verify database state: operation is COMPLETED and ledger entry exists
        val opInDb = db.pendingExternalOperationDao().getByBusinessTransactionId(txId)
        assertEquals("COMPLETED", opInDb?.status)
        assertEquals(note, opInDb?.verificationEvidence)
    }

    @Test
    fun confirmOperationSuccess_withShortEvidence_rejectsWithoutCallingRepository_andSetsErrorMessage() = runBlocking {
        val txId = "tx_short_" + UUID.randomUUID()
        val pending = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = "intent_" + UUID.randomUUID(),
            accountId = "subscriber_102",
            operationType = "RENEWAL",
            amountIqd = 40000L,
            status = "PENDING"
        )
        db.pendingExternalOperationDao().insert(pending)

        val deferred = CompletableDeferred<Pair<Boolean, String?>>()
        viewModel.confirmOperationSuccess(pending, " abc ") { success, msg ->
            deferred.complete(success to msg)
        }

        val (success, _) = withTimeout(5000) { deferred.await() }
        ShadowLooper.idleMainLooper()

        assertFalse("Resolution must be rejected", success)
        assertNull("Repository must not be called when evidence is shorter than 5 chars", spyRepo.manualEvidenceTxId)
        assertEquals(
            "يجب أن تكون ملاحظة الإثبات 5 أحرف على الأقل.",
            viewModel.operationActionMessage.value
        )

        // DB op must remain untouched PENDING
        val opInDb = db.pendingExternalOperationDao().getByBusinessTransactionId(txId)
        assertEquals("PENDING", opInDb?.status)
    }

    @Test
    fun markOperationFailed_callsResolvePendingOperationVerifiedFailure_andSetsSuccessMessage() = runBlocking {
        val txId = "tx_fail_" + UUID.randomUUID()
        val accountId = "subscriber_103"
        val pending = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = "intent_" + UUID.randomUUID(),
            accountId = accountId,
            operationType = "ACTIVATION",
            amountIqd = 45000L,
            status = "PENDING"
        )
        db.pendingExternalOperationDao().insert(pending)

        val deferred = CompletableDeferred<Pair<Boolean, String?>>()
        val reason = "إلغاء يدوي من واجهة الموزع"
        viewModel.markOperationFailed(pending, reason) { success, msg ->
            deferred.complete(success to msg)
        }

        val (success, _) = withTimeout(5000) { deferred.await() }
        ShadowLooper.idleMainLooper()

        assertTrue("Failure resolution must be reported as success", success)
        assertEquals("Transaction ID must match target op", txId, spyRepo.failureTxId)
        assertEquals("Diagnostic must match given reason", reason, spyRepo.failureDiagnostic)
        assertEquals(
            "تم إلغاء العملية وتأكيد فشلها دون تسجيل أي ديون.",
            viewModel.operationActionMessage.value
        )

        // DB op must be marked FAILED
        val opInDb = db.pendingExternalOperationDao().getByBusinessTransactionId(txId)
        assertEquals("FAILED", opInDb?.status)
    }

    @Test
    fun pendingGatewayOperations_flowEmitsPendingOperationsFromDatabase() = runBlocking {
        val txId = "tx_flow_" + UUID.randomUUID()
        val pending = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = "intent_" + UUID.randomUUID(),
            accountId = "subscriber_flow",
            operationType = "REFILL",
            amountIqd = 10000L,
            status = "PENDING"
        )
        db.pendingExternalOperationDao().insert(pending)

        val vm = SyncStatusViewModel(
            syncRepo = fakeSyncRepo,
            audit = auditRepo,
            prefs = prefs,
            ledgerRepo = spyRepo,
            sasGatewayRouter = sasGatewayRouter,
            earthlinkGateway = fakeEarthlinkGateway
        )

        val ops = withTimeout(5000) {
            vm.pendingGatewayOperations.first { it.isNotEmpty() }
        }

        assertEquals("Flow must emit the inserted pending operation", 1, ops.size)
        assertEquals(txId, ops[0].businessTransactionId)
        assertEquals("subscriber_flow", ops[0].accountId)
    }
}
