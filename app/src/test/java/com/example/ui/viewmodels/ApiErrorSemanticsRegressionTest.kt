package com.example.ui.viewmodels

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.*
import com.example.core.network.*
import com.example.core.security.PreferenceManager
import com.example.data.repository.*
import com.example.domain.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Regression Test Suite for API-02, API-03, and API-04 Error Semantics.
 *
 * Claims:
 * 1. API-02: getAccountCost() failure propagates typed EarthlinkGatewayException; does not swallow to 0.0.
 * 2. API-02 Preview: previewPackageCost() clears preview to null on error; never presents false 0.0 IQD.
 * 3. API-02 Activation: createUserUsingDeposit() fails closed on cost failure; 0 pending ops, 0 dispatch, 0 ledger rows.
 * 4. API-03 Repository: getTestUsersCount() propagates transport failure, rejects missing count JSON payload with EarthlinkBusinessException, preserves legitimate 0.
 * 5. API-03 ViewModel: DashboardViewModel preserves server-reported 0 as Int 0, and network failure as null (unavailable).
 *
 * Tier: ROBOLECTRIC tier.
 * Independent Oracle: Explicit contracts derived from Target Product Contract v0.6 & Invariant Matrix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ApiErrorSemanticsRegressionTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var accountRepo: LocalAccountRepositoryImpl
    private lateinit var ledgerRepo: LocalLedgerRepositoryImpl
    private lateinit var auditRepo: AuditRepositoryImpl
    private lateinit var mockPrefs: PreferenceManager
    private lateinit var mockApiService: EarthlinkApiService
    private lateinit var gatewayImpl: EarthlinkGatewayImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        accountRepo = LocalAccountRepositoryImpl(db, db.localAccountDao(), db.syncOutboxDao())
        ledgerRepo = LocalLedgerRepositoryImpl(
            db,
            db.localLedgerEntryDao(),
            db.localAccountDao(),
            db.syncOutboxDao(),
            db.pendingExternalOperationDao()
        )
        auditRepo = AuditRepositoryImpl(db, db.auditLogDao())
        mockPrefs = mock(PreferenceManager::class.java)
        mockApiService = mock(EarthlinkApiService::class.java)
        gatewayImpl = EarthlinkGatewayImpl(mockApiService, mockPrefs)

        `when`(mockPrefs.getDemoMode()).thenReturn(false)
        `when`(mockPrefs.getAuthToken()).thenReturn("mock_token")
        `when`(mockPrefs.getIspAdminUsername()).thenReturn("admin")
        `when`(mockPrefs.getIspAdminPassword()).thenReturn("pass")
        `when`(mockPrefs.getDashboardSortOption()).thenReturn("name")
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    // ==========================================
    // API-02: getAccountCost Repository Tests
    // ==========================================

    @Test
    fun testApi02_repository_networkFailureThrowsTransportException() = runTest(testDispatcher) {
        val testAccountIdx = 90001
        `when`(mockApiService.getAccountCost(testAccountIdx)).thenAnswer {
            throw SocketTimeoutException("Connection timed out to rapi.earthlink.iq")
        }

        try {
            gatewayImpl.getAccountCost(testAccountIdx)
            fail("Expected EarthlinkTransportException when network fails on getAccountCost")
        } catch (e: Exception) {
            assertTrue("Expected EarthlinkTransportException, got ${e::class.java.name}", e is EarthlinkTransportException)
            assertTrue(e.message?.contains("Network unavailable") == true)
        }
    }

    @Test
    fun testApi02_repository_malformedOrZeroCostThrowsBusinessException() = runTest(testDispatcher) {
        val testAccountIdx = 90002
        val errorJson = "{\"isSuccessful\":false,\"responseMessage\":\"Invalid account index\"}"
        `when`(mockApiService.getAccountCost(testAccountIdx)).thenReturn(
            errorJson.toResponseBody("application/json".toMediaTypeOrNull())
        )

        try {
            gatewayImpl.getAccountCost(testAccountIdx)
            fail("Expected EarthlinkBusinessException when cost is 0.0 / absent")
        } catch (e: Exception) {
            assertTrue("Expected EarthlinkBusinessException, got ${e::class.java.name}", e is EarthlinkBusinessException)
            assertTrue(e.message?.contains("Failed to determine valid package cost") == true)
        }
    }

    @Test
    fun testApi02_repository_validPositiveCostSucceedsAndIsReReadEachTime() = runTest(testDispatcher) {
        val testAccountIdx = 90003
        // thenAnswer (not thenReturn): each call must receive a FRESH ResponseBody, because the
        // production path consumes the body via `use { it.string() }`.
        `when`(mockApiService.getAccountCost(testAccountIdx)).thenAnswer {
            "{\"value\":35000.0}".toResponseBody("application/json".toMediaTypeOrNull())
        }

        val cost = gatewayImpl.getAccountCost(testAccountIdx)
        assertEquals(35000.0, cost, 0.001)

        // Every call re-reads the authoritative ISP cost; nothing is memoized.
        val secondCost = gatewayImpl.getAccountCost(testAccountIdx)
        assertEquals(35000.0, secondCost, 0.001)
        verify(mockApiService, times(2)).getAccountCost(testAccountIdx)
    }

    /**
     * Core Triad Standard:
     * 1. Claim: A NEW Activation must be able to obtain the CURRENT authoritative ISP package
     *    cost within the same process, without an application restart. A price the ISP changes
     *    after a first read must never be served again from process-lifetime state, because the
     *    Activation path freezes this value into PendingExternalOperation.amountIqd and thence
     *    into the local ledger and currentPriceIqd.
     * 2. Seam / Environment: ROBOLECTRIC tier (real EarthlinkGatewayImpl + mocked
     *    EarthlinkApiService, same seam the Activation path uses).
     * 3. Independent Oracle: T1 and T3 are two separate authoritative values supplied by the
     *    mocked server (40000, then 45000). The expectation "T3 == 45000" is derived from the
     *    server, not from production code. Proof that T3 cannot come from stale process state:
     *    the server is asked a SECOND time (verify times(2)), so on any implementation that
     *    memoizes, T3 would return the T1 value with only one network read and this test fails
     *    on both the value assertion and the interaction count. A never-cached sibling index
     *    controls for "the gateway stopped calling the API at all".
     */
    @Test
    fun testApi02_costAuthoritativePriceChangeIsObservedWithoutProcessRestart() = runTest(testDispatcher) {
        val idx = 90004
        val neverCachedControlIdx = 90005
        var authoritative = 40000.0

        `when`(mockApiService.getAccountCost(idx)).thenAnswer {
            "{\"value\":$authoritative}".toResponseBody("application/json".toMediaTypeOrNull())
        }
        `when`(mockApiService.getAccountCost(neverCachedControlIdx)).thenAnswer {
            "{\"value\":60000.0}".toResponseBody("application/json".toMediaTypeOrNull())
        }

        // T1: authoritative cost is 40000.
        assertEquals(40000.0, gatewayImpl.getAccountCost(idx), 0.001)
        verify(mockApiService, times(1)).getAccountCost(idx)

        // T2: the ISP changes the authoritative price for the SAME package index.
        authoritative = 45000.0

        // T3: a NEW Activation's cost read must observe the new authoritative price.
        assertEquals(
            "T3 must return the NEW authoritative price, not the T1 value",
            45000.0, gatewayImpl.getAccountCost(idx), 0.001
        )

        // The second value provably came from a second network read, not from a memo.
        verify(mockApiService, times(2)).getAccountCost(idx)

        // CONTROL: the gateway still performs network reads for other indices.
        assertEquals(60000.0, gatewayImpl.getAccountCost(neverCachedControlIdx), 0.001)
    }

    // ==========================================
    // API-02: ViewModel Presentation & Fail-Closed Tests
    // ==========================================

    @Test
    fun testApi02_previewPackageCost_networkFailureLeavesPreviewNullAndSetsError() = runTest(testDispatcher) {
        val failingGateway = mock(EarthlinkGateway::class.java)
        `when`(failingGateway.getAccountCost(101)).thenAnswer {
            throw EarthlinkTransportException("Network unavailable. Check connection and retry.")
        }

        val viewModel = EarthlinkSearchViewModel(
            gateway = failingGateway,
            audit = auditRepo,
            prefs = mockPrefs,
            localAccountRepository = accountRepo,
            localLedgerRepository = ledgerRepo
        )

        viewModel.previewPackageCost(101)
        advanceUntilIdle()

        assertNull("costPreview MUST be null on error; must NOT be 0.0", viewModel.costPreview.value)
        assertNotNull("error message must be populated", viewModel.error.value)
        assertTrue(viewModel.error.value!!.contains("Failed package cost preview"))
    }

    @Test
    fun testApi02_createUserUsingDeposit_networkFailureFailsClosedWithoutPendingOpOrLedger() = runTest(testDispatcher) {
        val failingGateway = mock(EarthlinkGateway::class.java)
        `when`(failingGateway.checkUsernameAvailable("new_subscriber")).thenReturn(true)
        `when`(failingGateway.getAccountCost(102)).thenAnswer {
            throw EarthlinkTransportException("Network unavailable on cost check")
        }

        val viewModel = EarthlinkSearchViewModel(
            gateway = failingGateway,
            audit = auditRepo,
            prefs = mockPrefs,
            localAccountRepository = accountRepo,
            localLedgerRepository = ledgerRepo
        )

        val job = viewModel.createUserUsingDeposit(
            username = "new_subscriber",
            phone = "07700000000",
            fullName = "Test Subscriber",
            pkgIndex = 102,
            depositPass = "secret123"
        )
        job.join()

        // 1. Error message indicates cost failure directly, NOT collapsed to zero validation
        assertNotNull(viewModel.error.value)
        assertTrue(
            "Error should indicate package cost lookup failure, not 0.0 validation: ${viewModel.error.value}",
            viewModel.error.value!!.startsWith("Failed to determine package cost:")
        )
        assertTrue(
            "Error message should preserve underlying exception message: ${viewModel.error.value}",
            viewModel.error.value!!.contains("Network unavailable on cost check")
        )
        assertFalse(
            "Error must not indicate valid IQD validation failure: ${viewModel.error.value}",
            viewModel.error.value!!.contains("valid IQD package cost")
        )

        // 2. Fail-closed: ZERO pending operations recorded
        val pendingOps = ledgerRepo.getAllPendingOperations()
        assertTrue("ZERO pending operations must exist on cost failure", pendingOps.isEmpty())

        // 3. Fail-closed: ZERO dispatch claims recorded
        val claims = pendingOps.filter { it.dispatchClaimCount > 0 }
        assertTrue("ZERO dispatch claims must exist on cost failure", claims.isEmpty())

        // 4. ZERO ledger entries recorded
        val ledgerEntries = db.localLedgerEntryDao().getByAccountIdOneShot("new_subscriber")
        assertTrue("ZERO ledger entries must exist on cost failure", ledgerEntries.isEmpty())
    }

    @Test
    fun testApi02_createUserUsingDeposit_invalidCostFailsValidation() = runTest(testDispatcher) {
        val gatewayWithInvalidCost = mock(EarthlinkGateway::class.java)
        `when`(gatewayWithInvalidCost.checkUsernameAvailable("invalid_cost_user")).thenReturn(true)
        // 35001.0 is positive and finite, but not a 250 IQD multiple
        `when`(gatewayWithInvalidCost.getAccountCost(103)).thenReturn(35001.0)

        val viewModel = EarthlinkSearchViewModel(
            gateway = gatewayWithInvalidCost,
            audit = auditRepo,
            prefs = mockPrefs,
            localAccountRepository = accountRepo,
            localLedgerRepository = ledgerRepo
        )

        val job = viewModel.createUserUsingDeposit(
            username = "invalid_cost_user",
            phone = "07700000000",
            fullName = "Invalid Cost User",
            pkgIndex = 103,
            depositPass = "secret123"
        )
        job.join()

        // 1. Error message indicates validation failure (cost was retrieved, but invalid)
        assertNotNull(viewModel.error.value)
        assertEquals("Failed to determine a valid IQD package cost. Operation aborted.", viewModel.error.value)

        // 2. Fail-closed: ZERO pending operations recorded
        val pendingOps = ledgerRepo.getAllPendingOperations()
        assertTrue("ZERO pending operations must exist on validation failure", pendingOps.isEmpty())

        // 3. ZERO ledger entries recorded
        val ledgerEntries = db.localLedgerEntryDao().getByAccountIdOneShot("invalid_cost_user")
        assertTrue("ZERO ledger entries must exist on validation failure", ledgerEntries.isEmpty())
    }

    // ==========================================
    // API-03: getTestUsersCount Repository & ViewModel Tests
    // ==========================================

    @Test
    fun testApi03_repository_networkFailureThrowsTransportException() = runTest(testDispatcher) {
        `when`(mockApiService.getTestUsersCount(null)).thenAnswer {
            throw IOException("Network reset by peer")
        }

        try {
            gatewayImpl.getTestUsersCount()
            fail("Expected EarthlinkTransportException on network failure")
        } catch (e: Exception) {
            assertTrue("Expected EarthlinkTransportException, got ${e::class.java.name}", e is EarthlinkTransportException)
        }
    }

    @Test
    fun testApi03_repository_missingCountFieldThrowsBusinessException() = runTest(testDispatcher) {
        val emptyJson = "{\"status\":\"ok\"}"
        `when`(mockApiService.getTestUsersCount(null)).thenReturn(
            emptyJson.toResponseBody("application/json".toMediaTypeOrNull())
        )

        try {
            gatewayImpl.getTestUsersCount()
            fail("Expected EarthlinkBusinessException when count fields are missing from JSON")
        } catch (e: Exception) {
            assertTrue("Expected EarthlinkBusinessException, got ${e::class.java.name}", e is EarthlinkBusinessException)
            assertTrue(e.message?.contains("Missing test users count field") == true)
        }
    }

    @Test
    fun testApi03_repository_legitimateZeroReturned() = runTest(testDispatcher) {
        val zeroJson = "{\"value\":0}"
        `when`(mockApiService.getTestUsersCount(null)).thenReturn(
            zeroJson.toResponseBody("application/json".toMediaTypeOrNull())
        )

        val count = gatewayImpl.getTestUsersCount()
        assertEquals(0, count)
    }

    @Test
    fun testApi03_dashboard_legitimateZeroPreserved() = runTest(testDispatcher) {
        val mockGateway = mock(EarthlinkGateway::class.java)
        val mockSyncRepo = mock(SyncRepository::class.java)
        `when`(mockSyncRepo.syncState).thenReturn(MutableStateFlow(SyncStatusState.IDLE))
        `when`(mockSyncRepo.syncProgress).thenReturn(MutableStateFlow(SyncProgress()))
        `when`(mockGateway.getBalance()).thenReturn(100000.0)
        `when`(mockGateway.searchUsers(anyString(), anyInt(), anyInt())).thenReturn(UserListResponse(itemsList = emptyList(), totalCount = 0))
        `when`(mockGateway.getPrepaidNeeded(anyInt())).thenReturn(0.0)
        `when`(mockGateway.getTestUsersCount()).thenReturn(0)

        val vm = DashboardViewModel(
            gateway = mockGateway,
            audit = auditRepo,
            localAccountRepository = accountRepo,
            localLedgerRepository = ledgerRepo,
            syncRepo = mockSyncRepo,
            prefs = mockPrefs,
            ioDispatcher = testDispatcher
        )

        vm.loadDashboardData()
        advanceUntilIdle()

        // Server reported 0 -> state MUST be 0
        assertEquals(Integer.valueOf(0), vm.testCount.value)
    }

    @Test
    fun testApi03_dashboard_networkFailureYieldsNullUnavailable() = runTest(testDispatcher) {
        val mockGateway = mock(EarthlinkGateway::class.java)
        val mockSyncRepo = mock(SyncRepository::class.java)
        `when`(mockSyncRepo.syncState).thenReturn(MutableStateFlow(SyncStatusState.IDLE))
        `when`(mockSyncRepo.syncProgress).thenReturn(MutableStateFlow(SyncProgress()))
        `when`(mockGateway.getBalance()).thenReturn(100000.0)
        `when`(mockGateway.searchUsers(anyString(), anyInt(), anyInt())).thenReturn(UserListResponse(itemsList = emptyList(), totalCount = 0))
        `when`(mockGateway.getPrepaidNeeded(anyInt())).thenReturn(0.0)
        `when`(mockGateway.getTestUsersCount()).thenAnswer {
            throw EarthlinkTransportException("Connection failure")
        }

        val vm = DashboardViewModel(
            gateway = mockGateway,
            audit = auditRepo,
            localAccountRepository = accountRepo,
            localLedgerRepository = ledgerRepo,
            syncRepo = mockSyncRepo,
            prefs = mockPrefs,
            ioDispatcher = testDispatcher
        )

        vm.loadDashboardData()
        advanceUntilIdle()

        // PRECONDITION: the awaited state is that the testCount fetch was actually ATTEMPTED.
        // testCount starts at null (DashboardViewModel.kt:40), so without this the assertNull below
        // is satisfied by the untouched initial value and no network failure is ever observed.
        // atLeastOnce() rather than times(1): DashboardViewModel.kt:63-65 calls loadDashboardData()
        // from init, so construction plus the explicit call above legitimately fetches more than once.
        // Evidence: DashboardViewModel.kt:184 (job launch) / :186 (gateway fetch).
        verify(mockGateway, atLeastOnce()).getTestUsersCount()

        // Failure -> state MUST be null (unavailable), distinguishable from 0
        assertNull("Network failure must set testCount to null (unavailable)", vm.testCount.value)
    }
}
