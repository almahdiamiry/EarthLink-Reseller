package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.PendingExternalOperation
import com.example.core.model.SasProviders
import com.example.core.model.UnknownOutcomeResolutionResult
import com.example.core.network.SasBusinessException
import com.example.core.network.SasGateway
import com.example.core.network.SasGatewayRouter
import com.example.core.network.SasOperationResult
import com.example.core.network.SasSubscriberView
import com.example.core.network.SasTransportException
import com.example.core.network.SasVerificationOutcome
import com.example.core.model.AccountStatementItem
import com.example.core.model.UserDetail
import com.example.core.model.UserListItem
import com.example.core.model.UserListResponse
import com.example.domain.repository.EarthlinkGateway
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
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
 * DurableOperationsProviderTest
 *
 * Authoritative unit test suite verifying provider-aware durable pending operations,
 * immutability guarantees, and router-driven cold-start recovery sweeps (Task 9).
 *
 * Core Triad Specification:
 * 1. Claim:
 *    - Step 9.1 & 9.2: [LocalLedgerRepository.recordPendingOperation] strictly captures and persists
 *      the explicit [PendingExternalOperation.operationProvider] ("ALAMIRY" and "EARTHLINK").
 *    - Step 9.10: Provider Immutability: Mutating an account's provider ([LocalAccount.operationProvider])
 *      never cascades or alters the stored [PendingExternalOperation.operationProvider].
 *    - Step 9.7 & 9.8: Recovery Routing:
 *      - An ALAMIRY pending operation routes verification exclusively to the SAMM gateway via [SasGatewayRouter].
 *      - An EARTHLINK pending operation routes verification exclusively to the EarthLink gateway via [SasGatewayRouter].
 *      - Zero Cross-Provider Fallback: Errors, timeouts, or inconclusive results from SAMM never query EarthLink.
 *    - Step 9.7: Cold-Start Sweep: [LocalLedgerRepository.recoverColdStartOrphanedOperations] uses [SasGatewayRouter]
 *      to resolve orphaned in-flight operations to their correct terminal state according to each operation's stored provider.
 *    - Fail-Closed Dispatch Guard: Operations with `dispatchClaimCount == 0` strictly fail closed without materializing debt.
 *
 * 2. Seam / Environment:
 *    ROBOLECTRIC tier ([RobolectricTestRunner], Android SDK test sandbox), in-memory SQLite Room [AppDatabase],
 *    real [LocalLedgerRepositoryImpl], coupled with mock [SasGateway] seams and [SasGatewayRouter].
 *
 * 3. Independent Oracle:
 *    Expected provider strings ("ALAMIRY", "EARTHLINK"), gateway invocation counters (exact 1 or 0),
 *    terminal resolution states ([UnknownOutcomeResolutionResult.VERIFIED_SUCCESS], [UnknownOutcomeResolutionResult.VERIFIED_FAILURE],
 *    [UnknownOutcomeResolutionResult.INCONCLUSIVE]), database status strings ("COMPLETED", "FAILED", "PENDING"),
 *    and exact ledger debt amounts derived independently of production logic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DurableOperationsProviderTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var ledgerRepo: LocalLedgerRepositoryImpl

    private lateinit var fakeEarthlinkGateway: TestSasGateway
    private lateinit var fakeSammGateway: TestSasGateway
    private lateinit var router: SasGatewayRouter

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
            outboxDao = db.syncOutboxDao(),
            pendingDao = db.pendingExternalOperationDao()
        )

        fakeEarthlinkGateway = TestSasGateway(SasProviders.EARTHLINK)
        fakeSammGateway = TestSasGateway(SasProviders.ALAMIRY)

        router = SasGatewayRouter(
            earthlinkAdapter = fakeEarthlinkGateway,
            sammAdapter = fakeSammGateway
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ========================================================================
    // Test Double for SasGateway
    // ========================================================================

    class TestSasGateway(override val providerName: String) : SasGateway {
        val verifyCalls = mutableListOf<VerifyCall>()
        var outcomeToReturn: SasVerificationOutcome = SasVerificationOutcome.VERIFIED_SUCCESS
        var exceptionToThrow: Throwable? = null

        data class VerifyCall(
            val operationType: String,
            val targetIdentifier: String,
            val baselineEvidence: String?
        )

        override suspend fun verifyOperationOutcome(
            operationType: String,
            targetIdentifier: String,
            baselineEvidence: String?
        ): SasVerificationOutcome {
            verifyCalls.add(VerifyCall(operationType, targetIdentifier, baselineEvidence))
            exceptionToThrow?.let { throw it }
            return outcomeToReturn
        }

        override suspend fun checkConnection(): Boolean = true
        override suspend fun searchSubscribers(query: String, page: Int, pageSize: Int): List<SasSubscriberView> = emptyList()
        override suspend fun getSubscriber(subscriberId: String): SasSubscriberView? = null
        override suspend fun createSubscriber(username: String, planId: String, password: String, firstName: String, lastName: String, phone: String?): SasOperationResult = SasOperationResult.Applied()
        override suspend fun suspendSubscriber(username: String): SasOperationResult = SasOperationResult.Applied()
        override suspend fun activateSubscriber(username: String): SasOperationResult = SasOperationResult.Applied()
        override suspend fun renewSubscriber(username: String): SasOperationResult = SasOperationResult.Applied()
        override suspend fun changePlan(username: String, newPlanId: String): SasOperationResult = SasOperationResult.Applied()
        override suspend fun changePassword(username: String, newPassword: String): SasOperationResult = SasOperationResult.Applied()
    }

    // ========================================================================
    // Step 9.1 & 9.2: Provider Capture on Recording
    // ========================================================================

    /**
     * Claim: recordPendingOperation persists the operationProvider field for both ALAMIRY and EARTHLINK providers.
     * Seam: ROBOLECTRIC tier (Room SQLite insert & query).
     * Independent Oracle: Retrieved operationProvider equals expected constant ("ALAMIRY" / "EARTHLINK").
     */
    @Test
    fun test01_recordPendingOperation_preservesOperationProvider_alamiryAndEarthlink() = runTest {
        // 1. Record ALAMIRY operation
        val opAlamiry = PendingExternalOperation(
            businessTransactionId = "tx_alamiry_01",
            operationIntentId = "intent_alamiry_01",
            accountId = "sub_alamiry_01",
            operationType = "RENEWAL",
            amountIqd = 35_000L,
            payloadJson = "{\"subscriberId\":\"sub_alamiry_01\"}",
            status = "PENDING",
            dispatchClaimCount = 0,
            operationProvider = SasProviders.ALAMIRY
        )
        val recordedAlamiry = ledgerRepo.recordPendingOperation(opAlamiry)
        assertEquals("ALAMIRY", recordedAlamiry.operationProvider)

        val retrievedAlamiry = ledgerRepo.getPendingOperationByTransactionId("tx_alamiry_01")
        assertNotNull("Retrieved ALAMIRY operation must exist in database", retrievedAlamiry)
        assertEquals("ALAMIRY", retrievedAlamiry!!.operationProvider)

        // 2. Record EARTHLINK operation
        val opEarthlink = PendingExternalOperation(
            businessTransactionId = "tx_earthlink_01",
            operationIntentId = "intent_earthlink_01",
            accountId = "sub_earthlink_01",
            operationType = "ACTIVATION",
            amountIqd = 45_000L,
            payloadJson = "{\"username\":\"sub_earthlink_01\"}",
            status = "PENDING",
            dispatchClaimCount = 0,
            operationProvider = SasProviders.EARTHLINK
        )
        val recordedEarthlink = ledgerRepo.recordPendingOperation(opEarthlink)
        assertEquals("EARTHLINK", recordedEarthlink.operationProvider)

        val retrievedEarthlink = ledgerRepo.getPendingOperationByTransactionId("tx_earthlink_01")
        assertNotNull("Retrieved EARTHLINK operation must exist in database", retrievedEarthlink)
        assertEquals("EARTHLINK", retrievedEarthlink!!.operationProvider)
    }

    // ========================================================================
    // Step 9.10: Provider Immutability
    // ========================================================================

    /**
     * Claim: Mutating an account's provider does not alter existing PendingExternalOperation records.
     * Seam: ROBOLECTRIC tier (Room LocalAccount update vs PendingExternalOperation isolation).
     * Independent Oracle: op.operationProvider remains "ALAMIRY" after account.operationProvider changes to "EARTHLINK".
     */
    @Test
    fun test02_providerImmutability_accountProviderMutationDoesNotAlterPendingProvider() = runTest {
        val account = LocalAccount(
            id = "acc_immutability_01",
            earthlinkUsername = "sub_immutability",
            displayName = "Immutability Test User",
            currentPriceIqd = 35_000.0,
            debtIqd = 0.0,
            operationProvider = SasProviders.ALAMIRY
        )
        db.localAccountDao().insert(account)

        val op = PendingExternalOperation(
            businessTransactionId = "tx_immutability_01",
            operationIntentId = "intent_immutability_01",
            accountId = account.id,
            operationType = "RENEWAL",
            amountIqd = 35_000L,
            status = "PENDING",
            dispatchClaimCount = 1,
            operationProvider = SasProviders.ALAMIRY
        )
        ledgerRepo.recordPendingOperation(op)

        // Mutate account's provider to EARTHLINK
        val updatedAccount = account.copy(operationProvider = SasProviders.EARTHLINK)
        db.localAccountDao().update(updatedAccount)

        val accountFromDb = db.localAccountDao().getByIdOneShot(account.id)
        assertNotNull(accountFromDb)
        assertEquals("Account provider must have updated to EARTHLINK", SasProviders.EARTHLINK, accountFromDb!!.operationProvider)

        // Verify pending operation's provider is untouched
        val opFromDb = ledgerRepo.getPendingOperationByTransactionId("tx_immutability_01")
        assertNotNull(opFromDb)
        assertEquals("Pending operation provider must remain immutable ALAMIRY", SasProviders.ALAMIRY, opFromDb!!.operationProvider)
    }

    // ========================================================================
    // Step 9.7 & 9.8: Recovery Routing
    // ========================================================================

    /**
     * Claim: An ALAMIRY pending operation routes verification exclusively to the SAMM gateway via the router.
     * Seam: ROBOLECTRIC tier (verifyAndResolvePendingOperation with SasGatewayRouter).
     * Independent Oracle: SAMM gateway verifyCalls = 1, EarthLink verifyCalls = 0, outcome = VERIFIED_SUCCESS.
     */
    @Test
    fun test03_recoveryRouting_alamiryRoutesExclusivelyToSammGateway() = runTest {
        val account = LocalAccount(
            id = "acc_samm_route",
            earthlinkUsername = "user_samm",
            displayName = "SAMM Route User",
            currentPriceIqd = 35_000.0,
            debtIqd = 0.0,
            operationProvider = SasProviders.ALAMIRY
        )
        db.localAccountDao().insert(account)

        val op = PendingExternalOperation(
            businessTransactionId = "tx_samm_route_01",
            operationIntentId = "intent_samm_route_01",
            accountId = account.id,
            operationType = "RENEWAL",
            amountIqd = 35_000L,
            payloadJson = "{\"subscriberId\":\"999\",\"baselineExpirationDate\":\"2026-10-01\"}",
            status = "PENDING",
            dispatchClaimCount = 1,
            operationProvider = SasProviders.ALAMIRY
        )
        ledgerRepo.recordPendingOperation(op)

        fakeSammGateway.outcomeToReturn = SasVerificationOutcome.VERIFIED_SUCCESS

        val resolution = ledgerRepo.verifyAndResolvePendingOperation(
            businessTransactionId = op.businessTransactionId,
            router = router,
            baselineExpirationDate = "2026-10-01"
        )

        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, resolution.result)
        assertEquals("SAMM gateway must be queried exactly once", 1, fakeSammGateway.verifyCalls.size)
        assertEquals("EarthLink gateway must NOT be queried for ALAMIRY operation", 0, fakeEarthlinkGateway.verifyCalls.size)

        val call = fakeSammGateway.verifyCalls.first()
        assertEquals("RENEWAL", call.operationType)
        assertEquals("999", call.targetIdentifier)
        assertEquals("2026-10-01", call.baselineEvidence)

        val resolvedOp = ledgerRepo.getPendingOperationByTransactionId(op.businessTransactionId)
        assertNotNull(resolvedOp)
        assertEquals("COMPLETED", resolvedOp!!.status)
        assertNotNull("Ledger entry must be materialized for verified renewal", resolution.ledgerEntry)
        assertEquals(35_000.0, resolution.ledgerEntry!!.amountIqd, 0.001)
    }

    /**
     * Claim: An EARTHLINK pending operation routes verification exclusively to the EarthLink gateway via the router.
     * Seam: ROBOLECTRIC tier (verifyAndResolvePendingOperation with SasGatewayRouter).
     * Independent Oracle: EarthLink gateway verifyCalls = 1, SAMM verifyCalls = 0, outcome = VERIFIED_SUCCESS.
     */
    @Test
    fun test04_recoveryRouting_earthlinkRoutesExclusivelyToEarthlinkGateway() = runTest {
        val account = LocalAccount(
            id = "acc_el_route",
            earthlinkUsername = "user_el",
            displayName = "EarthLink Route User",
            currentPriceIqd = 45_000.0,
            debtIqd = 0.0,
            operationProvider = SasProviders.EARTHLINK
        )
        db.localAccountDao().insert(account)

        val op = PendingExternalOperation(
            businessTransactionId = "tx_el_route_01",
            operationIntentId = "intent_el_route_01",
            accountId = account.id,
            operationType = "ACTIVATION",
            amountIqd = 45_000L,
            payloadJson = "{\"username\":\"user_el\"}",
            status = "PENDING",
            dispatchClaimCount = 1,
            operationProvider = SasProviders.EARTHLINK
        )
        ledgerRepo.recordPendingOperation(op)

        fakeEarthlinkGateway.outcomeToReturn = SasVerificationOutcome.VERIFIED_SUCCESS

        val resolution = ledgerRepo.verifyAndResolvePendingOperation(
            businessTransactionId = op.businessTransactionId,
            router = router,
            baselineExpirationDate = null
        )

        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, resolution.result)
        assertEquals("EarthLink gateway must be queried exactly once", 1, fakeEarthlinkGateway.verifyCalls.size)
        assertEquals("SAMM gateway must NOT be queried for EARTHLINK operation", 0, fakeSammGateway.verifyCalls.size)

        val call = fakeEarthlinkGateway.verifyCalls.first()
        assertEquals("ACTIVATION", call.operationType)
        assertEquals("user_el", call.targetIdentifier)

        val resolvedOp = ledgerRepo.getPendingOperationByTransactionId(op.businessTransactionId)
        assertNotNull(resolvedOp)
        assertEquals("COMPLETED", resolvedOp!!.status)
    }

    /**
     * Claim: Zero Cross-Provider Fallback: Errors on SAMM never trigger fallback to EarthLink.
     * Seam: ROBOLECTRIC tier.
     * Independent Oracle: SAMM verifyCalls = 1, EarthLink verifyCalls = 0, outcome = INCONCLUSIVE.
     */
    @Test
    fun test05_recoveryRouting_zeroCrossProviderFallbackOnError() = runTest {
        val account = LocalAccount(
            id = "acc_err_route",
            earthlinkUsername = "user_err",
            displayName = "Error Fallback Test User",
            currentPriceIqd = 35_000.0,
            debtIqd = 0.0,
            operationProvider = SasProviders.ALAMIRY
        )
        db.localAccountDao().insert(account)

        val op = PendingExternalOperation(
            businessTransactionId = "tx_err_route_01",
            operationIntentId = "intent_err_route_01",
            accountId = account.id,
            operationType = "RENEWAL",
            amountIqd = 35_000L,
            payloadJson = "{\"subscriberId\":\"user_err\"}",
            status = "PENDING",
            dispatchClaimCount = 1,
            operationProvider = SasProviders.ALAMIRY
        )
        ledgerRepo.recordPendingOperation(op)

        fakeSammGateway.exceptionToThrow = SasTransportException("Connection timeout to SAMM API")

        val resolution = ledgerRepo.verifyAndResolvePendingOperation(
            businessTransactionId = op.businessTransactionId,
            router = router,
            baselineExpirationDate = null
        )

        assertEquals(UnknownOutcomeResolutionResult.INCONCLUSIVE, resolution.result)
        assertEquals("SAMM gateway was queried", 1, fakeSammGateway.verifyCalls.size)
        assertEquals("EarthLink gateway must NEVER be queried on SAMM error (zero fallback)", 0, fakeEarthlinkGateway.verifyCalls.size)
        assertTrue("Diagnostic message must indicate inconclusive on ALAMIRY", resolution.diagnosticMessage.contains("ALAMIRY"))

        val opAfter = ledgerRepo.getPendingOperationByTransactionId(op.businessTransactionId)
        assertNotNull(opAfter)
        assertEquals("PENDING", opAfter!!.status)
    }

    // ========================================================================
    // Step 9.7: Cold-Start Recovery Sweep with Router
    // ========================================================================

    /**
     * Claim: recoverColdStartOrphanedOperations uses the router to route each orphaned operation
     * strictly to its own provider gateway, resolving orphaned in-flight operations.
     * Seam: ROBOLECTRIC tier (recoverColdStartOrphanedOperations with SasGatewayRouter).
     * Independent Oracle: ALAMIRY orphan resolved to COMPLETED (SAMM calls = 1),
     * EARTHLINK orphan resolved to FAILED (EarthLink calls = 1), zero cross-calling.
     */
    @Test
    fun test06_coldStartRecoverySweep_usesRouterToResolveOrphanedOperations() = runTest {
        val processStartMs = 100_000L

        // Accounts
        val accSamm = LocalAccount(
            id = "acc_cold_samm",
            earthlinkUsername = "user_cold_samm",
            displayName = "Cold SAMM User",
            currentPriceIqd = 35_000.0,
            debtIqd = 0.0,
            operationProvider = SasProviders.ALAMIRY
        )
        val accEl = LocalAccount(
            id = "acc_cold_el",
            earthlinkUsername = "user_cold_el",
            displayName = "Cold EarthLink User",
            currentPriceIqd = 45_000.0,
            debtIqd = 0.0,
            operationProvider = SasProviders.EARTHLINK
        )
        db.localAccountDao().insert(accSamm)
        db.localAccountDao().insert(accEl)

        // Orphan 1: ALAMIRY in DISPATCHING status
        val orphanSamm = PendingExternalOperation(
            businessTransactionId = "tx_orphan_samm",
            operationIntentId = "intent_orphan_samm",
            accountId = accSamm.id,
            operationType = "RENEWAL",
            amountIqd = 35_000L,
            payloadJson = "{\"subscriberId\":\"user_cold_samm\"}",
            status = "DISPATCHING",
            dispatchClaimCount = 1,
            createdAt = 50_000L,
            updatedAt = 50_000L,
            operationProvider = SasProviders.ALAMIRY
        )

        // Orphan 2: EARTHLINK in RESOLVING status
        val orphanEl = PendingExternalOperation(
            businessTransactionId = "tx_orphan_el",
            operationIntentId = "intent_orphan_el",
            accountId = accEl.id,
            operationType = "ACTIVATION",
            amountIqd = 45_000L,
            payloadJson = "{\"username\":\"user_cold_el\"}",
            status = "RESOLVING",
            dispatchClaimCount = 1,
            createdAt = 60_000L,
            updatedAt = 60_000L,
            operationProvider = SasProviders.EARTHLINK
        )

        db.pendingExternalOperationDao().insert(orphanSamm)
        db.pendingExternalOperationDao().insert(orphanEl)

        // Configure oracles: SAMM verifies success, EarthLink verifies failure
        fakeSammGateway.outcomeToReturn = SasVerificationOutcome.VERIFIED_SUCCESS
        fakeEarthlinkGateway.outcomeToReturn = SasVerificationOutcome.VERIFIED_FAILURE

        ledgerRepo.recoverColdStartOrphanedOperations(router, processStartMs)

        // Verify SAMM orphan
        val resolvedSamm = ledgerRepo.getPendingOperationByTransactionId("tx_orphan_samm")
        assertNotNull(resolvedSamm)
        assertEquals("ALAMIRY orphan must transition to COMPLETED", "COMPLETED", resolvedSamm!!.status)
        assertEquals("SAMM gateway must have been called for SAMM orphan", 1, fakeSammGateway.verifyCalls.size)

        // Verify EarthLink orphan
        val resolvedEl = ledgerRepo.getPendingOperationByTransactionId("tx_orphan_el")
        assertNotNull(resolvedEl)
        assertEquals("EARTHLINK orphan must transition to FAILED", "FAILED", resolvedEl!!.status)
        assertEquals("EarthLink gateway must have been called for EarthLink orphan", 1, fakeEarthlinkGateway.verifyCalls.size)
    }

    // ========================================================================
    // Fail-Closed Dispatch Guard
    // ========================================================================

    /**
     * Claim: An operation with dispatchClaimCount == 0 fails closed to FAILED upon recovery,
     * even if gateway reports VERIFIED_SUCCESS, preventing un-dispatched operations from creating debt.
     * Seam: ROBOLECTRIC tier.
     * Independent Oracle: resolution = VERIFIED_FAILURE, op.status = FAILED, ledgerEntry = null.
     */
    @Test
    fun test07_coldStartRecovery_zeroClaimCountFailsClosedWithoutMaterializingDebt() = runTest {
        val account = LocalAccount(
            id = "acc_zero_claim",
            earthlinkUsername = "user_zero",
            displayName = "Zero Claim User",
            currentPriceIqd = 35_000.0,
            debtIqd = 0.0,
            operationProvider = SasProviders.ALAMIRY
        )
        db.localAccountDao().insert(account)

        val op = PendingExternalOperation(
            businessTransactionId = "tx_zero_claim_01",
            operationIntentId = "intent_zero_claim_01",
            accountId = account.id,
            operationType = "RENEWAL",
            amountIqd = 35_000L,
            payloadJson = "{\"subscriberId\":\"user_zero\"}",
            status = "PENDING",
            dispatchClaimCount = 0,
            operationProvider = SasProviders.ALAMIRY
        )
        ledgerRepo.recordPendingOperation(op)

        fakeSammGateway.outcomeToReturn = SasVerificationOutcome.VERIFIED_SUCCESS

        val resolution = ledgerRepo.verifyAndResolvePendingOperation(
            businessTransactionId = op.businessTransactionId,
            router = router,
            baselineExpirationDate = null
        )

        assertEquals("Must fail closed because dispatchClaimCount was 0", UnknownOutcomeResolutionResult.VERIFIED_FAILURE, resolution.result)
        assertNull("Zero financial debt must be materialized", resolution.ledgerEntry)

        val resolvedOp = ledgerRepo.getPendingOperationByTransactionId(op.businessTransactionId)
        assertNotNull(resolvedOp)
        assertEquals("FAILED", resolvedOp!!.status)
        assertTrue(resolvedOp.lastError?.contains("dispatchClaimCount=0") == true)
    }

    // ========================================================================
    // Step 9.10: Provider Immutability during Recovery
    // ========================================================================

    /**
     * Claim: When an account's provider is changed after dispatch, recovery sweep still resolves
     * strictly against the provider stored on the pending operation (op.operationProvider).
     * Seam: ROBOLECTRIC tier.
     * Independent Oracle: SAMM gateway is queried (calls = 1), EarthLink gateway is NOT queried (calls = 0).
     */
    @Test
    fun test08_recoveryRouting_preservesPendingProviderWhenAccountSwitched() = runTest {
        val account = LocalAccount(
            id = "acc_switched_provider",
            earthlinkUsername = "user_switched",
            displayName = "Switched Provider User",
            currentPriceIqd = 35_000.0,
            debtIqd = 0.0,
            operationProvider = SasProviders.ALAMIRY
        )
        db.localAccountDao().insert(account)

        val op = PendingExternalOperation(
            businessTransactionId = "tx_switched_01",
            operationIntentId = "intent_switched_01",
            accountId = account.id,
            operationType = "RENEWAL",
            amountIqd = 35_000L,
            payloadJson = "{\"subscriberId\":\"user_switched\"}",
            status = "PENDING",
            dispatchClaimCount = 1,
            operationProvider = SasProviders.ALAMIRY
        )
        ledgerRepo.recordPendingOperation(op)

        // Switch the account's provider in the database to EARTHLINK
        db.localAccountDao().update(account.copy(operationProvider = SasProviders.EARTHLINK))

        fakeSammGateway.outcomeToReturn = SasVerificationOutcome.VERIFIED_SUCCESS

        // Resolve using serialized recovery
        val resolution = ledgerRepo.resolvePendingOperationSerialized(
            businessTransactionId = op.businessTransactionId,
            router = router,
            baselineExpirationDate = null
        )

        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, resolution.result)
        assertEquals("Must query SAMM gateway because op.operationProvider was ALAMIRY", 1, fakeSammGateway.verifyCalls.size)
        assertEquals("Must NOT query EarthLink gateway even though account is now EARTHLINK", 0, fakeEarthlinkGateway.verifyCalls.size)
    }

    // ========================================================================
    // T6: Sweep Order & Provider Isolation (EL Sweep ignores SAMM)
    // ========================================================================

    /**
     * Claim: INV-03 & INV-05. sweepAndResolvePendingOperations(EarthlinkGateway) strictly ignores
     * non-EarthLink operations (e.g. ALAMIRY), resolving EarthLink operations via statement
     * without touching or double-booking ALAMIRY operations.
     * Seam: ROBOLECTRIC tier.
     * Independent Oracle:
     * - EARTHLINK op resolved to COMPLETED with 1 ledger debt entry.
     * - ALAMIRY op remains untouched in PENDING status.
     * - mockEarthlinkGateway called only for EARTHLINK op query.
     */
    @Test
    fun test09_sweepAndResolvePendingOperations_ignoresNonEarthlinkOperations() = runTest {
        val opTime = 1_768_476_600_000L

        val accEl = LocalAccount(
            id = "user_el_sweep",
            earthlinkUsername = "user_el_sweep",
            displayName = "EL Sweep User",
            currentPriceIqd = 35_000.0,
            debtIqd = 0.0,
            operationProvider = SasProviders.EARTHLINK
        )
        val accSamm = LocalAccount(
            id = "user_samm_sweep",
            earthlinkUsername = "user_samm_sweep",
            displayName = "SAMM Sweep User",
            currentPriceIqd = 45_000.0,
            debtIqd = 0.0,
            operationProvider = SasProviders.ALAMIRY
        )
        db.localAccountDao().insert(accEl)
        db.localAccountDao().insert(accSamm)

        // EL pending op with claim=1
        val opEl = PendingExternalOperation(
            businessTransactionId = "tx_el_sweep",
            operationIntentId = "intent_el_sweep",
            accountId = accEl.id,
            operationType = "RENEWAL",
            amountIqd = 35_000L,
            payloadJson = "{}",
            status = "PENDING",
            dispatchClaimCount = 1,
            createdAt = opTime,
            operationProvider = SasProviders.EARTHLINK
        )
        // ALAMIRY pending op with claim=1
        val opSamm = PendingExternalOperation(
            businessTransactionId = "tx_samm_sweep",
            operationIntentId = "intent_samm_sweep",
            accountId = accSamm.id,
            operationType = "RENEWAL",
            amountIqd = 45_000L,
            payloadJson = "{}",
            status = "PENDING",
            dispatchClaimCount = 1,
            createdAt = opTime,
            operationProvider = SasProviders.ALAMIRY
        )
        ledgerRepo.recordPendingOperation(opEl)
        ledgerRepo.recordPendingOperation(opSamm)

        // Mock EarthlinkGateway that returns matching statement for EL user
        val mockElGateway = object : EarthlinkGateway by com.example.Phase1DuplicateInitiationProtectionTest.TestEarthlinkGateway() {
            override suspend fun searchUsers(
                query: String,
                startIndex: Int,
                rowCount: Int
            ): UserListResponse = UserListResponse(
                itemsList = listOf(UserListItem(userIndexLower = 999, userIDLower = accEl.id))
            )

            override suspend fun getUserDetail(userIndex: Int): UserDetail = UserDetail(
                userIndexLower = 999,
                userIDLower = accEl.id,
                accountStatusLower = "Active",
                expirationDateLower = null
            )

            override suspend fun getAccountStatement(
                startIndex: Int,
                rowCount: Int,
                query: String
            ): List<AccountStatementItem> {
                return if (query == accEl.id) {
                    listOf(
                        AccountStatementItem(
                            occurredAt = "2026-01-15 14:30:00",
                            operation = "Withdraw",
                            depositAmount = 0.0,
                            withdrawalAmount = 35_000.0,
                            userIDLower = accEl.id
                        )
                    )
                } else {
                    emptyList()
                }
            }
        }

        // Run sweep with 0 grace window
        val resolutions = ledgerRepo.sweepAndResolvePendingOperations(mockElGateway, graceWindowMs = 0L)

        // Verify: EL op resolved to VERIFIED_SUCCESS
        val resolvedEl = ledgerRepo.getPendingOperationByTransactionId(opEl.businessTransactionId)
        assertNotNull(resolvedEl)
        assertEquals("COMPLETED", resolvedEl!!.status)

        // Verify: ALAMIRY op was NOT resolved by EL sweep and remains PENDING
        val untouchedSamm = ledgerRepo.getPendingOperationByTransactionId(opSamm.businessTransactionId)
        assertNotNull(untouchedSamm)
        assertEquals("PENDING", untouchedSamm!!.status)

        // Verify: Exactly 1 debt entry was created (for EL account)
        val entries = db.localLedgerEntryDao().getByAccountIdOneShot(accEl.id)
        assertEquals(1, entries.size)
        assertEquals(35_000.0, entries[0].amountIqd, 0.0)

        val sammEntries = db.localLedgerEntryDao().getByAccountIdOneShot(accSamm.id)
        assertEquals(0, sammEntries.size)
    }

    // ========================================================================
    // T8: Dual-Account Provider Preference Query & Materializer Resolution
    // ========================================================================

    /**
     * Claim: When two active accounts share the exact same username across different providers,
     * findActiveAccountByUsernameOrIdPreferringProviderOneShot and resolvePendingOperationVerifiedSuccess
     * correctly pick and attach debt to the account corresponding to the operation's operationProvider.
     * Seam: ROBOLECTRIC tier.
     * Independent Oracle:
     * - Query with provider "ALAMIRY" returns the SAMM account row (id "acc_dual_samm").
     * - Query with provider "EARTHLINK" returns the EL account row (id "acc_dual_el").
     * - resolvePendingOperationVerifiedSuccess for ALAMIRY operation creates ledger entry under "acc_dual_samm".
     */
    @Test
    fun test10_dualAccount_preferringProvider_resolvesAndAttachesDebtToCorrectAccount() = runTest {
        val sharedUsername = "dual_reseller_user"

        val accEl = LocalAccount(
            id = "acc_dual_el",
            earthlinkUsername = sharedUsername,
            displayName = "Dual EL User",
            currentPriceIqd = 35_000.0,
            debtIqd = 0.0,
            isHistoryOnlySubscriber = false,
            operationProvider = SasProviders.EARTHLINK
        )
        val accSamm = LocalAccount(
            id = "acc_dual_samm",
            earthlinkUsername = sharedUsername,
            displayName = "Dual SAMM User",
            currentPriceIqd = 35_000.0,
            debtIqd = 0.0,
            isHistoryOnlySubscriber = false,
            operationProvider = SasProviders.ALAMIRY
        )
        db.localAccountDao().insert(accEl)
        db.localAccountDao().insert(accSamm)

        // 1. Verify DAO query directly
        val foundEl = db.localAccountDao().findActiveAccountByUsernameOrIdPreferringProviderOneShot(
            username = sharedUsername,
            provider = SasProviders.EARTHLINK
        )
        assertNotNull(foundEl)
        assertEquals("acc_dual_el", foundEl!!.id)
        assertEquals(SasProviders.EARTHLINK, foundEl.operationProvider)

        val foundSamm = db.localAccountDao().findActiveAccountByUsernameOrIdPreferringProviderOneShot(
            username = sharedUsername,
            provider = SasProviders.ALAMIRY
        )
        assertNotNull(foundSamm)
        assertEquals("acc_dual_samm", foundSamm!!.id)
        assertEquals(SasProviders.ALAMIRY, foundSamm.operationProvider)

        // 2. Verify materializer fallback: op.accountId = sharedUsername (username fallback branch)
        val opSamm = PendingExternalOperation(
            businessTransactionId = "tx_dual_samm",
            operationIntentId = "intent_dual_samm",
            accountId = sharedUsername,
            operationType = "RENEWAL",
            amountIqd = 35_000L,
            payloadJson = "{}",
            status = "PENDING",
            dispatchClaimCount = 1,
            operationProvider = SasProviders.ALAMIRY
        )
        ledgerRepo.recordPendingOperation(opSamm)

        // Materialize verified success
        val resolution = ledgerRepo.resolvePendingOperationVerifiedSuccess(
            businessTransactionId = opSamm.businessTransactionId,
            chargeNote = "Verified via SAMM gateway"
        )
        assertNotNull(resolution)
        assertEquals("acc_dual_samm", resolution!!.accountId)
        assertEquals(35_000.0, resolution.amountIqd, 0.0)

        // Assert ledger entry was saved to acc_dual_samm, NOT acc_dual_el
        val sammEntries = db.localLedgerEntryDao().getByAccountIdOneShot("acc_dual_samm")
        assertEquals(1, sammEntries.size)
        assertEquals(35_000.0, sammEntries[0].amountIqd, 0.0)

        val elEntries = db.localLedgerEntryDao().getByAccountIdOneShot("acc_dual_el")
        assertEquals(0, elEntries.size)
    }
}
