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
        override suspend fun suspendSubscriber(subscriberId: String): SasOperationResult = SasOperationResult.Applied()
        override suspend fun activateSubscriber(subscriberId: String): SasOperationResult = SasOperationResult.Applied()
        override suspend fun renewSubscriber(subscriberId: String): SasOperationResult = SasOperationResult.Applied()
        override suspend fun changePlan(subscriberId: String, newPlanId: String): SasOperationResult = SasOperationResult.Applied()
        override suspend fun changePassword(subscriberId: String, newPassword: String): SasOperationResult = SasOperationResult.Applied()
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
}
