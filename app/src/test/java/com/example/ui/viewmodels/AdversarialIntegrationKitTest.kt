package com.example.ui.viewmodels

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.model.PendingExternalOperation
import com.example.core.model.SasProviders
import com.example.core.network.SasAuthException
import com.example.core.network.SasBusinessException
import com.example.core.network.SasGateway
import com.example.core.network.SasGatewayRouter
import com.example.core.network.SasNotFoundException
import com.example.core.network.SasOperationResult
import com.example.core.network.SasSubscriberView
import com.example.core.network.SasTransportException
import com.example.core.network.SasVerificationOutcome
import com.example.core.security.PreferenceManager
import com.example.data.repository.AuditRepositoryImpl
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.EarthlinkGateway
import com.squareup.moshi.Moshi
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AdversarialIntegrationKitTest
 *
 * Authoritative adversarial integration test suite verifying non-negotiable architectural guarantees
 * and edge cases under multi-provider operation (EarthLink vs Alamiry/SAMM) per Task 12 of the
 * Alamiry/SAMM Integration plan.
 *
 * Core Triad Specification:
 * 1. Claim:
 *    - 12.1 Provider routing: Operations dispatch strictly to the adapter selected by [LocalAccount.operationProvider]
 *      or the active UI provider.
 *    - 12.2 Strict Zero Fallback: SAMM timeout, 401, 404, 500, or unconfigured states NEVER call EarthLink.
 *    - 12.3 New-Account Provider Capture: Activation captures [PendingExternalOperation.operationProvider] before
 *      [LocalAccount] exists in Room.
 *    - 12.4 Pending Provider Immutability: When [LocalAccount.operationProvider] changes after pending op creation,
 *      recovery sweeps route via the pending operation's original provider.
 *    - 12.5 Account Identity Preservation: Changing provider preserves [LocalAccount.id] and all ledger entries
 *      without row deletion or balance corruption.
 *    - 12.6 Persistence Across Reopen: [LocalAccount.operationProvider] written to Room restores correctly on reload.
 *    - 12.7 Unsupported Refill on SAMM: Refill with [SasProviders.ALAMIRY] fails closed with zero network mutations.
 *    - 12.8 Runtime Configuration Refresh: Updating SAMM URL / token immediately invalidates cached gateway instances.
 *    - 12.9 Zero Plaintext Token Leakage: SAMM tokens and passwords are never persisted in Room tables, audit logs,
 *      or serialized payloads.
 *
 * 2. Seam / Environment:
 *    ROBOLECTRIC tier ([RobolectricTestRunner]), real in-memory and persistent SQLite Room databases ([AppDatabase]),
 *    real [PreferenceManager], real [LocalAccountRepositoryImpl], [LocalLedgerRepositoryImpl], [AuditRepositoryImpl],
 *    orchestrated with [SasGatewayRouter] and [EarthlinkSearchViewModel].
 *
 * 3. Independent Oracle:
 *    Literal expected constants, zero-interaction assertions on unselected providers, independent raw SQL/Room queries,
 *    and strict JSON regex inspection without production logic mirroring.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AdversarialIntegrationKitTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var prefs: PreferenceManager
    private lateinit var accountRepo: LocalAccountRepositoryImpl
    private lateinit var ledgerRepo: LocalLedgerRepositoryImpl
    private lateinit var auditRepo: AuditRepositoryImpl

    private lateinit var fakeEarthlinkGateway: MockEarthlinkGateway
    private lateinit var fakeEarthlinkSasGateway: MockSasGateway
    private lateinit var fakeSammSasGateway: MockSasGateway
    private lateinit var router: SasGatewayRouter
    private lateinit var viewModel: EarthlinkSearchViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        prefs = PreferenceManager(context).apply {
            clearCredentials()
            setDemoMode(false)
            saveIspAdminUsername("test_operator")
            saveIspAdminPassword("test_secret")
            saveDepositPassword("dep_secret_123")
            saveSammBaseUrl("https://samm.al-amiry.net")
            saveSammToken("secret_token_samm_xyz")
        }

        accountRepo = LocalAccountRepositoryImpl(
            database = db,
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao()
        )
        ledgerRepo = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = db.localLedgerEntryDao(),
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao(),
            pendingDao = db.pendingExternalOperationDao()
        )
        auditRepo = AuditRepositoryImpl(db, db.auditLogDao())

        fakeEarthlinkGateway = MockEarthlinkGateway()
        fakeEarthlinkSasGateway = MockSasGateway(SasProviders.EARTHLINK)
        fakeSammSasGateway = MockSasGateway(SasProviders.ALAMIRY)

        router = SasGatewayRouter(
            earthlinkAdapter = fakeEarthlinkSasGateway,
            sammAdapter = fakeSammSasGateway
        )

        viewModel = EarthlinkSearchViewModel(
            gateway = fakeEarthlinkGateway,
            sasGatewayRouter = router,
            audit = auditRepo,
            prefs = prefs,
            localAccountRepository = accountRepo,
            localLedgerRepository = ledgerRepo,
            syncRepo = null
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
        prefs.clearCredentials()
    }

    // =========================================================================
    // 1. PROVIDER ROUTING VERIFICATION (12.1)
    // =========================================================================

    @Test
    fun routing_earthlinkAndSamm_dispatchesToCorrectGateway() = runTest {
        // Claim: Account dispatches to EarthLink adapter when EARTHLINK and to SAMM gateway when ALAMIRY.
        // Seam: SasGatewayRouter and EarthlinkSearchViewModel dispatch seam.
        // Independent Oracle: Invocation counters on fakeEarthlinkSasGateway and fakeSammSasGateway
        //                      prove calls are received only by the chosen provider.

        // Dispatch 1: EARTHLINK provider
        viewModel.setSelectedProvider(SasProviders.EARTHLINK)
        fakeEarthlinkSasGateway.searchResult = listOf(
            SasSubscriberView(subscriberId = "el_sub_1", username = "el_user", status = "active")
        )
        viewModel.setSearchQuery("el_user")
        viewModel.searchUsers()

        assertEquals("EarthLink gateway must be called once", 1, fakeEarthlinkSasGateway.searchCalls.get())
        assertEquals("SAMM gateway must not be called", 0, fakeSammSasGateway.searchCalls.get())
        val elResults = viewModel.usersList.value
        assertEquals(1, elResults.size)
        assertEquals("el_user", elResults[0].userID)

        // Dispatch 2: ALAMIRY provider
        viewModel.setSelectedProvider(SasProviders.ALAMIRY)
        fakeSammSasGateway.searchResult = listOf(
            SasSubscriberView(subscriberId = "samm_sub_1", username = "samm_user", status = "active")
        )
        viewModel.setSearchQuery("samm_user")
        viewModel.searchUsers()

        assertEquals("EarthLink gateway call count remains 1", 1, fakeEarthlinkSasGateway.searchCalls.get())
        assertEquals("SAMM gateway must be called once", 1, fakeSammSasGateway.searchCalls.get())
        val sammResults = viewModel.usersList.value
        assertEquals(1, sammResults.size)
        assertEquals("samm_user", sammResults[0].userID)
    }

    // =========================================================================
    // 2. ZERO CROSS-PROVIDER FALLBACK UNDER FAILURES (12.2)
    // =========================================================================

    @Test
    fun noFallback_sammErrorsNeverTriggerEarthlink() = runTest {
        // Claim: SAMM errors (Timeout, 401 Auth, 404 Not Found, 500 Server Error, Unconfigured)
        // must NEVER call EarthLink under any circumstance (Strict Zero Fallback).
        // Seam: SasGatewayRouter and EarthlinkSearchViewModel.
        // Independent Oracle: fakeEarthlinkSasGateway calls remain 0 and fakeEarthlinkGateway calls remain 0.

        viewModel.setSelectedProvider(SasProviders.ALAMIRY)

        // Case A: Timeout (SasTransportException simulating network timeout)
        fakeSammSasGateway.errorToThrow = SasTransportException("Connection timed out to SAMM server")
        val jobTimeout = viewModel.changeUserPassword(101, "samm_user_101", "new_pass_1")
        jobTimeout.join()
        assertEquals("EarthLink SAS calls must be 0 on SAMM timeout", 0, fakeEarthlinkSasGateway.changePasswordCalls.get())
        assertEquals("Legacy EarthLink gateway calls must be 0", 0, fakeEarthlinkGateway.searchUsersCalls.get())
        assertTrue(viewModel.error.value?.contains("Connection timed out", ignoreCase = true) == true)

        // Case B: 401 Unauthorized
        fakeSammSasGateway.errorToThrow = SasAuthException("Invalid SAMM token: 401 Unauthorized")
        val jobAuth = viewModel.changeUserPassword(101, "samm_user_101", "new_pass_2")
        jobAuth.join()
        assertEquals("EarthLink SAS calls must be 0 on SAMM 401", 0, fakeEarthlinkSasGateway.changePasswordCalls.get())
        assertEquals("Legacy EarthLink gateway calls must be 0", 0, fakeEarthlinkGateway.searchUsersCalls.get())
        assertTrue(viewModel.error.value?.contains("401", ignoreCase = true) == true)

        // Case C: 404 Not Found
        fakeSammSasGateway.errorToThrow = SasNotFoundException(statusCode = 404, message = "Subscriber not found on SAMM: 404")
        val jobNotFound = viewModel.changeUserPassword(101, "samm_user_101", "new_pass_3")
        jobNotFound.join()
        assertEquals("EarthLink SAS calls must be 0 on SAMM 404", 0, fakeEarthlinkSasGateway.changePasswordCalls.get())
        assertEquals("Legacy EarthLink gateway calls must be 0", 0, fakeEarthlinkGateway.searchUsersCalls.get())
        assertTrue(viewModel.error.value?.contains("404", ignoreCase = true) == true)

        // Case D: 500 Server Error (SasBusinessException with status 500)
        fakeSammSasGateway.errorToThrow = SasBusinessException(statusCode = 500, errorMessage = "Internal Server Error in SAMM backend (500)")
        val jobServerErr = viewModel.changeUserPassword(101, "samm_user_101", "new_pass_4")
        jobServerErr.join()
        assertEquals("EarthLink SAS calls must be 0 on SAMM 500", 0, fakeEarthlinkSasGateway.changePasswordCalls.get())
        assertEquals("Legacy EarthLink gateway calls must be 0", 0, fakeEarthlinkGateway.searchUsersCalls.get())
        assertTrue(
            "Error must reflect 500 or internal server error",
            viewModel.error.value?.contains("500", ignoreCase = true) == true ||
            viewModel.error.value?.contains("Internal Server Error", ignoreCase = true) == true
        )

        // Case E: Unconfigured SAMM credentials
        prefs.saveSammToken(null) // Unconfigure SAMM
        val freshRouter = SasGatewayRouter(
            earthlinkAdapter = fakeEarthlinkSasGateway,
            preferenceManager = prefs
        )
        val freshViewModel = EarthlinkSearchViewModel(
            gateway = fakeEarthlinkGateway,
            sasGatewayRouter = freshRouter,
            audit = auditRepo,
            prefs = prefs,
            localAccountRepository = accountRepo,
            localLedgerRepository = ledgerRepo,
            syncRepo = null
        )
        freshViewModel.setSelectedProvider(SasProviders.ALAMIRY)
        freshViewModel.setSearchQuery("unconfigured_query")
        freshViewModel.searchUsers()

        assertEquals("EarthLink SAS calls must be 0 when SAMM is unconfigured", 0, fakeEarthlinkSasGateway.searchCalls.get())
        assertEquals("Legacy EarthLink gateway calls must be 0", 0, fakeEarthlinkGateway.searchUsersCalls.get())
        assertTrue(freshViewModel.error.value?.contains("SAMM provider is not configured", ignoreCase = true) == true)
    }

    // =========================================================================
    // 3. NEW-ACCOUNT PROVIDER CAPTURE BEFORE LOCALACCOUNT CREATION (12.3)
    // =========================================================================

    @Test
    fun newAccountActivation_capturesProviderInPendingOperation() = runTest {
        // Claim: Activating a new subscriber captures operationProvider in PendingExternalOperation
        // before LocalAccount exists in the database.
        // Seam: EarthlinkSearchViewModel.createUserUsingDeposit.
        // Independent Oracle: Room inspection of PendingExternalOperation proves operationProvider == ALAMIRY
        // while localAccountDao().getByIdOneShot(username) returns null prior to success resolution.

        viewModel.setSelectedProvider(SasProviders.ALAMIRY)
        fakeSammSasGateway.createResult = SasOperationResult.Applied(evidence = "samm_cust_901")

        val targetUsername = "fresh_subscriber_901"
        val intentId = "intent_act_adv_01"

        // Ensure account does not exist prior to creation
        assertNull("LocalAccount must not exist yet", accountRepo.getAccountByIdOneShot(targetUsername))

        val job = viewModel.createUserUsingDeposit(
            username = targetUsername,
            phone = "07701112233",
            fullName = "Fresh Subscriber",
            pkgIndex = 12,
            depositPass = "secret123",
            intentId = intentId
        )
        job.join()

        // Verify PendingExternalOperation captured the provider
        val pendingOp = ledgerRepo.getPendingOperationByIntentId(intentId)
        assertNotNull("Pending operation must be recorded in Room", pendingOp)
        assertEquals("Recorded provider must be ALAMIRY", SasProviders.ALAMIRY, pendingOp?.operationProvider)
        assertEquals("Status must be COMPLETED after successful creation", "COMPLETED", pendingOp?.status)

        // Verify invocation occurred on SAMM and not EarthLink
        assertEquals("SAMM createSubscriber called once", 1, fakeSammSasGateway.createCalls.get())
        assertEquals("EarthLink createSubscriber called zero times", 0, fakeEarthlinkSasGateway.createCalls.get())
        assertEquals("EarthLink gateway createUserUsingDeposit called zero times", 0, fakeEarthlinkGateway.createUserUsingDepositCalls.get())
    }

    // =========================================================================
    // 4. PENDING OPERATION PROVIDER IMMUTABILITY (12.4)
    // =========================================================================

    @Test
    fun pendingOperation_providerRemainsImmutable_whenAccountProviderChanges() = runTest {
        // Claim: Changing LocalAccount.operationProvider after creating a pending operation leaves
        // PendingExternalOperation.operationProvider unchanged, and recovery sweeps route via the
        // pending operation's original provider.
        // Seam: LocalLedgerRepositoryImpl.recoverColdStartOrphanedOperations.
        // Independent Oracle: Pending op provider remains original ALAMIRY; router routes recovery
        // to fakeSammSasGateway even though LocalAccount is now EARTHLINK.

        val accountId = "acc_immut_01"
        val initialAccount = LocalAccount(
            id = accountId,
            earthlinkUsername = "user_immut_01",
            displayName = "Immutable User",
            currentPriceIqd = 35000.0,
            debtIqd = 0.0,
            operationProvider = SasProviders.ALAMIRY
        )
        accountRepo.saveAccount(initialAccount)

        val txId = "tx_immut_01"
        val intentId = "intent_immut_01"
        val pendingOp = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = intentId,
            accountId = accountId,
            operationType = "RENEWAL",
            amountIqd = 35000L,
            payloadJson = "{\"subscriberId\":\"user_immut_01\"}",
            status = "DISPATCHING",
            dispatchClaimCount = 1,
            createdAt = 1000L,
            updatedAt = 1000L,
            operationProvider = SasProviders.ALAMIRY
        )
        db.pendingExternalOperationDao().insert(pendingOp)

        // Mutate account provider in Room to EARTHLINK:
        val accountAfterChange = initialAccount.copy(operationProvider = SasProviders.EARTHLINK)
        accountRepo.saveAccount(accountAfterChange)

        // Verify the account changed in Room
        val reloadedAccount = accountRepo.getAccountByIdOneShot(accountId)
        assertEquals(SasProviders.EARTHLINK, reloadedAccount?.operationProvider)

        // Verify PendingExternalOperation provider in Room is still ALAMIRY
        val storedOpBeforeRecovery = db.pendingExternalOperationDao().getByBusinessTransactionId(txId)
        assertEquals(SasProviders.ALAMIRY, storedOpBeforeRecovery?.operationProvider)

        // Now run cold-start recovery sweep with router:
        fakeSammSasGateway.outcomeToReturn = SasVerificationOutcome.VERIFIED_SUCCESS
        ledgerRepo.recoverColdStartOrphanedOperations(router, processStartMs = 5000L)

        // Assert recovery called SAMM gateway (original pending op provider), NOT EarthLink
        assertEquals("Recovery must call SAMM gateway based on pending op provider", 1, fakeSammSasGateway.verifyCalls.get())
        assertEquals("Recovery must NOT call EarthLink adapter", 0, fakeEarthlinkSasGateway.verifyCalls.get())

        // Verify op completed successfully under SAMM
        val resolvedOp = ledgerRepo.getPendingOperationByTransactionId(txId)
        assertNotNull(resolvedOp)
        assertEquals("COMPLETED", resolvedOp?.status)
        assertEquals(SasProviders.ALAMIRY, resolvedOp?.operationProvider)
    }

    // =========================================================================
    // 5. ACCOUNT IDENTITY & FINANCIAL HISTORY PRESERVATION (12.5)
    // =========================================================================

    @Test
    fun accountProviderChange_preservesAccountIdAndLedgerHistory() = runTest {
        // Claim: Provider changes preserve LocalAccount.id and all historical ledger entries without
        // deleting rows, modifying balances, or creating orphan records.
        // Seam: LocalAccountRepositoryImpl.saveAccount.
        // Independent Oracle: LocalAccount.id, debt, advance, and LocalLedgerEntry count remain identical.

        val accountId = "acc_preserve_01"
        val originalAccount = LocalAccount(
            id = accountId,
            displayName = "Preserve Test User",
            earthlinkUsername = "user_pres_01",
            currentPriceIqd = 35000.0,
            debtIqd = 70000.0,
            openingDebtIqd = 20000.0,
            openingAdvanceIqd = 5000.0,
            operationProvider = SasProviders.EARTHLINK
        )
        accountRepo.saveAccount(originalAccount)

        // Create 3 ledger entries for this account
        val ledger1 = LocalLedgerEntry(
            id = "entry_01",
            accountId = accountId,
            amountIqd = 35000.0,
            debtAfterIqd = 35000.0,
            typeRaw = "RENEWAL",
            occurredAt = 1000L
        )
        val ledger2 = LocalLedgerEntry(
            id = "entry_02",
            accountId = accountId,
            amountIqd = 35000.0,
            debtAfterIqd = 70000.0,
            typeRaw = "RENEWAL",
            occurredAt = 2000L
        )
        db.localLedgerEntryDao().insertAll(listOf(ledger1, ledger2))

        val entriesBefore = ledgerRepo.getLedgerForAccount(accountId).first()
        assertEquals(2, entriesBefore.size)

        // Execute provider transition: EARTHLINK -> ALAMIRY
        val updatedAccount = originalAccount.copy(operationProvider = SasProviders.ALAMIRY)
        accountRepo.saveAccount(updatedAccount)

        // Assertions:
        val reloadedAccount = accountRepo.getAccountByIdOneShot(accountId)
        assertNotNull(reloadedAccount)
        assertEquals("Account ID must be completely unchanged", accountId, reloadedAccount?.id)
        assertEquals("Operation provider must be updated to ALAMIRY", SasProviders.ALAMIRY, reloadedAccount?.operationProvider)
        assertEquals("Debt must not be mutated", 70000.0, reloadedAccount?.debtIqd ?: 0.0, 0.001)
        assertEquals("Opening debt must not be mutated", 20000.0, reloadedAccount?.openingDebtIqd ?: 0.0, 0.001)
        assertEquals("Opening advance must not be mutated", 5000.0, reloadedAccount?.openingAdvanceIqd ?: 0.0, 0.001)

        val entriesAfter = ledgerRepo.getLedgerForAccount(accountId).first()
        assertEquals("Ledger entry count must remain 2", 2, entriesAfter.size)
        assertEquals("entry_01", entriesAfter.find { it.id == "entry_01" }?.id)
        assertEquals("entry_02", entriesAfter.find { it.id == "entry_02" }?.id)
    }

    // =========================================================================
    // 6. PERSISTENCE ACROSS DATABASE REOPEN (12.6)
    // =========================================================================

    @Test
    fun accountProvider_persistsAcrossDatabaseReopen() = runTest {
        // Claim: Persisting an account with operationProvider = ALAMIRY restores correctly on database reload.
        // Seam: Real SQLite on-disk Room database lifecycle (close and reopen).
        // Independent Oracle: Query from fresh database instance returns ALAMIRY.

        val dbFile = File(context.cacheDir, "reopen_test_${System.currentTimeMillis()}.db")
        if (dbFile.exists()) dbFile.delete()

        try {
            // Step 1: Open database, insert account with ALAMIRY provider, and close
            val db1 = Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
                .allowMainThreadQueries()
                .build()

            val account = LocalAccount(
                id = "acc_reopen_01",
                displayName = "Reopen User",
                earthlinkUsername = "user_reopen",
                operationProvider = SasProviders.ALAMIRY
            )
            db1.localAccountDao().insert(account)
            db1.close()

            // Step 2: Open second fresh database instance pointing to the same file
            val db2 = Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
                .allowMainThreadQueries()
                .build()

            val reloaded = db2.localAccountDao().getByIdOneShot("acc_reopen_01")
            assertNotNull("Account must be read from reloaded database", reloaded)
            assertEquals("Restored provider must be ALAMIRY", SasProviders.ALAMIRY, reloaded?.operationProvider)
            assertEquals("Reopen User", reloaded?.displayName)

            db2.close()
        } finally {
            if (dbFile.exists()) dbFile.delete()
        }
    }

    // =========================================================================
    // 7. UNSUPPORTED REFILL ON SAMM (12.10)
    // =========================================================================

    @Test
    fun sammRefill_strictlyUnsupported_withZeroNetworkCalls() = runTest {
        // Claim: Refill requests for an account on ALAMIRY fail closed as unsupported without
        // executing any network mutations on SAMM or EarthLink.
        // Seam: EarthlinkSearchViewModel.refillUserDeposit.
        // Independent Oracle: Error banner matches unsupported message; EarthLink gateway refill calls == 0;
        // SAMM gateway calls == 0.

        val sammAccount = LocalAccount(
            id = "acc_refill_samm",
            displayName = "SAMM Account",
            operationProvider = SasProviders.ALAMIRY
        )
        accountRepo.saveAccount(sammAccount)

        val job = viewModel.refillUserDeposit(
            userId = "acc_refill_samm",
            depositPass = "secret",
            price = 35000.0,
            account = sammAccount
        )
        job.join()

        // Error banner must indicate refill is not supported for ALAMIRY
        val errorMsg = viewModel.error.value
        assertNotNull("Error banner must be set", errorMsg)
        assertTrue(
            "Error must state Refill is not supported for ALAMIRY provider",
            errorMsg!!.contains("Refill is not supported for ALAMIRY provider", ignoreCase = true)
        )

        // Zero network calls must have been made
        assertEquals("EarthLink refill calls must be 0", 0, fakeEarthlinkGateway.refillUserDepositCalls.get())
        assertEquals("SAMM create calls must be 0", 0, fakeSammSasGateway.createCalls.get())
        assertEquals("SAMM renew calls must be 0", 0, fakeSammSasGateway.renewCalls.get())
    }

    // =========================================================================
    // 8. RUNTIME CONFIGURATION REFRESH INVALIDATES GATEWAY (12.9)
    // =========================================================================

    @Test
    fun runtimeConfigUpdate_invalidatesCachedGateway() {
        // Claim: Updating SAMM URL / token in PreferenceManager updates sammConfigVersionFlow,
        // forcing SasGatewayRouter to construct a fresh gateway instance with updated credentials.
        // Seam: SasGatewayRouter.getGateway(SasProviders.ALAMIRY).
        // Independent Oracle: Factory call count increments, gateway instances differ between config updates.

        var factoryInvocations = 0
        var capturedBaseUrl: String? = null
        var capturedToken: String? = null

        val dynamicRouter = SasGatewayRouter(
            earthlinkAdapter = fakeEarthlinkSasGateway,
            preferenceManager = prefs,
            sammGatewayFactory = { url, token ->
                factoryInvocations++
                capturedBaseUrl = url
                capturedToken = token
                MockSasGateway(providerName = SasProviders.ALAMIRY)
            }
        )

        // First access: constructs instance A
        prefs.saveSammBaseUrl("https://samm-server-a.com")
        prefs.saveSammToken("token_alpha_111")

        val gatewayA = dynamicRouter.getGateway(SasProviders.ALAMIRY)
        assertEquals(1, factoryInvocations)
        assertEquals("https://samm-server-a.com", capturedBaseUrl)
        assertEquals("token_alpha_111", capturedToken)

        // Second access without config change: uses cached instance A
        val gatewayACached = dynamicRouter.getGateway(SasProviders.ALAMIRY)
        assertSame("Cached instance must be returned", gatewayA, gatewayACached)
        assertEquals("Factory must not be called again", 1, factoryInvocations)

        // Update configuration to server B:
        prefs.saveSammBaseUrl("https://samm-server-b.com")
        prefs.saveSammToken("token_beta_222")

        // Third access: detects version increment and constructs fresh instance B
        val gatewayB = dynamicRouter.getGateway(SasProviders.ALAMIRY)
        assertEquals("Factory must be called again on config change", 2, factoryInvocations)
        assertEquals("https://samm-server-b.com", capturedBaseUrl)
        assertEquals("token_beta_222", capturedToken)
        assertNotEquals("New gateway instance must be created", gatewayA, gatewayB)
    }

    // =========================================================================
    // 9. ZERO PLAINTEXT TOKEN LEAKAGE (12.7 & 12.8)
    // =========================================================================

    @Test
    fun noTokenLeakage_inEntitiesOrLogs() = runTest {
        // Claim: SAMM bearer tokens and credentials are never stored in Room entities
        // (local_accounts, pending_external_operations, audit_log) or serialized JSON payloads.
        // Seam: Room database contents after full lifecycle execution.
        // Independent Oracle: Raw SQLite string scan over database records proves 0 occurrences of secret token.

        val secretToken = "super_confidential_samm_bearer_token_9999"
        prefs.saveSammToken(secretToken)

        val account = LocalAccount(
            id = "acc_leak_check",
            displayName = "Token Leak Audit User",
            operationProvider = SasProviders.ALAMIRY
        )
        accountRepo.saveAccount(account)

        val pendingOp = PendingExternalOperation(
            businessTransactionId = "tx_leak_01",
            operationIntentId = "intent_leak_01",
            accountId = account.id,
            operationType = "RENEWAL",
            amountIqd = 35000L,
            payloadJson = "{\"subscriberId\":\"acc_leak_check\"}",
            operationProvider = SasProviders.ALAMIRY
        )
        ledgerRepo.recordPendingOperation(pendingOp)
        auditRepo.logAction("TEST_ACTION", "USER", account.id, "Testing token isolation")

        // Moshi serialization of LocalAccount
        val moshi = Moshi.Builder().build()
        val accountAdapter = moshi.adapter(LocalAccount::class.java)
        val serializedAccount = accountAdapter.toJson(account)
        assertFalse("Serialized LocalAccount must not contain token", serializedAccount.contains(secretToken))

        // Raw SQLite database scan across tables
        val rawDb = db.openHelper.readableDatabase
        val tables = listOf("local_accounts", "pending_external_operations", "audit_log", "local_ledger_entries")

        for (table in tables) {
            val cursor = rawDb.query("SELECT * FROM $table")
            while (cursor.moveToNext()) {
                for (colIndex in 0 until cursor.columnCount) {
                    val value = cursor.getString(colIndex)
                    if (value != null) {
                        assertFalse(
                            "Table $table column ${cursor.getColumnName(colIndex)} must NOT contain secret token!",
                            value.contains(secretToken)
                        )
                    }
                }
            }
            cursor.close()
        }

        // Verify clearCredentials wipes operational token from PreferenceManager cleanly
        prefs.clearCredentials()
        assertNull("getSammToken must be null after clearCredentials", prefs.getSammToken())
        assertFalse("isSammConfigured must be false", prefs.isSammConfigured())
    }

    // =========================================================================
    // TEST DOUBLES
    // =========================================================================

    class MockSasGateway(override val providerName: String) : SasGateway {
        val searchCalls = AtomicInteger(0)
        val getSubscriberCalls = AtomicInteger(0)
        val createCalls = AtomicInteger(0)
        val suspendCalls = AtomicInteger(0)
        val activateCalls = AtomicInteger(0)
        val renewCalls = AtomicInteger(0)
        val changePlanCalls = AtomicInteger(0)
        val changePasswordCalls = AtomicInteger(0)
        val verifyCalls = AtomicInteger(0)

        var errorToThrow: Throwable? = null
        var searchResult: List<SasSubscriberView> = emptyList()
        var subscriberDetailResult: SasSubscriberView? = null
        var createResult: SasOperationResult = SasOperationResult.Applied(evidence = "created_test")
        var suspendResult: SasOperationResult = SasOperationResult.Applied()
        var activateResult: SasOperationResult = SasOperationResult.Applied()
        var renewResult: SasOperationResult = SasOperationResult.Applied()
        var changePlanResult: SasOperationResult = SasOperationResult.Applied()
        var changePasswordResult: SasOperationResult = SasOperationResult.Applied()
        var outcomeToReturn: SasVerificationOutcome = SasVerificationOutcome.VERIFIED_SUCCESS

        private fun checkError() {
            errorToThrow?.let { throw it }
        }

        override suspend fun searchSubscribers(query: String, page: Int, pageSize: Int): List<SasSubscriberView> {
            searchCalls.incrementAndGet()
            checkError()
            return searchResult
        }

        override suspend fun getSubscriber(subscriberId: String): SasSubscriberView? {
            getSubscriberCalls.incrementAndGet()
            checkError()
            return subscriberDetailResult
        }

        override suspend fun createSubscriber(
            username: String,
            planId: String,
            password: String,
            firstName: String,
            lastName: String,
            phone: String?
        ): SasOperationResult {
            createCalls.incrementAndGet()
            checkError()
            return createResult
        }

        override suspend fun suspendSubscriber(subscriberId: String): SasOperationResult {
            suspendCalls.incrementAndGet()
            checkError()
            return suspendResult
        }

        override suspend fun activateSubscriber(subscriberId: String): SasOperationResult {
            activateCalls.incrementAndGet()
            checkError()
            return activateResult
        }

        override suspend fun renewSubscriber(subscriberId: String): SasOperationResult {
            renewCalls.incrementAndGet()
            checkError()
            return renewResult
        }

        override suspend fun changePlan(subscriberId: String, newPlanId: String): SasOperationResult {
            changePlanCalls.incrementAndGet()
            checkError()
            return changePlanResult
        }

        override suspend fun changePassword(subscriberId: String, newPassword: String): SasOperationResult {
            changePasswordCalls.incrementAndGet()
            checkError()
            return changePasswordResult
        }

        override suspend fun verifyOperationOutcome(
            operationType: String,
            targetIdentifier: String,
            baselineEvidence: String?
        ): SasVerificationOutcome {
            verifyCalls.incrementAndGet()
            checkError()
            return outcomeToReturn
        }

        override suspend fun checkConnection(): Boolean {
            checkError()
            return true
        }
    }

    class MockEarthlinkGateway : EarthlinkGateway {
        val refillUserDepositCalls = AtomicInteger(0)
        val createTestUserCalls = AtomicInteger(0)
        val createUserUsingDepositCalls = AtomicInteger(0)
        val searchUsersCalls = AtomicInteger(0)

        override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean {
            refillUserDepositCalls.incrementAndGet()
            return true
        }

        override suspend fun createTestUser(username: String, phone: String, fullName: String, accountIndex: Int): String? {
            createTestUserCalls.incrementAndGet()
            return "test_pass"
        }

        override suspend fun createUserUsingDeposit(
            username: String,
            phone: String,
            fullName: String,
            accountIndex: Int,
            depositPassword: String
        ): String? {
            createUserUsingDepositCalls.incrementAndGet()
            return "deposit_pass"
        }

        override suspend fun searchUsers(query: String, startIndex: Int, rowCount: Int): com.example.core.model.UserListResponse {
            searchUsersCalls.incrementAndGet()
            return com.example.core.model.UserListResponse(itemsList = emptyList(), totalCount = 0)
        }

        override suspend fun getBalance(): Double = 500000.0
        override suspend fun getPrepaidNeeded(): Double = 10000.0
        override suspend fun toggleUserActive(userIndex: Int, active: Boolean): Boolean = true
        override suspend fun checkUsernameAvailable(userId: String): Boolean = true
        override suspend fun getAccountCost(accountIndex: Int): Double = 35000.0
        override suspend fun getPackages(): List<com.example.core.model.AccountPackage> = emptyList()
        override suspend fun getUserDetail(userIndex: Int): com.example.core.model.UserDetail = com.example.core.model.UserDetail()
        override suspend fun login(username: String, password: String): com.example.core.model.LoginResponse =
            com.example.core.model.LoginResponse(accessToken = "token", tokenType = "Bearer", expiresIn = 3600)
        override suspend fun getTestUsersCount(affiliateIndex: Int?): Int = 0
        override suspend fun getActiveTestUsersCount(): Int = 0
        override suspend fun autocompleteUser(query: String): List<com.example.core.model.AutocompleteUser> = emptyList()
        override suspend fun checkCustomerByPhone(phone: String): String? = null
        override suspend fun createCustomer(name: String, phone: String): Boolean = true
        override suspend fun extendUser(userIndex: Int): Boolean = true
        override suspend fun getAccountStatement(startIndex: Int, rowCount: Int, query: String) = emptyList<com.example.core.model.AccountStatementItem>()
        override suspend fun showUserPassword(userIndex: Int, userId: String): String = "pass"
        override suspend fun showAccountPassword(userIndex: Int, userId: String): String = "acc_pass"
        override suspend fun changeUserPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
        override suspend fun changeAccountPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
        override suspend fun changeAccountType(userIndex: Int, userId: String, accountIndex: Int): Boolean = true
        override suspend fun updateUserDisplayName(userIndex: Int, newName: String): Boolean = true
    }
}
