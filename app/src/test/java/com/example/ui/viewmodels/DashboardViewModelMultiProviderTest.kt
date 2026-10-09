package com.example.ui.viewmodels

import com.example.core.model.ProviderAccessState
import com.example.core.model.SasProviders
import com.example.core.model.UserListItem
import com.example.core.model.UserListResponse
import com.example.core.network.*
import com.example.core.security.PreferenceManager
import com.example.domain.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Authoritative unit tests for Multi-Provider subscriber & metric loading in [DashboardViewModel].
 *
 * Core Triad Specification:
 * 1. Claim:
 *    - In SAMM_ONLY mode, [DashboardViewModel] must not abort due to missing EarthLink credentials
 *      or auth token. It must mark [DashboardViewModel.isCredentialsEmpty] as false, query subscribers
 *      from SAMM via [SasGatewayRouter], tag each subscriber with originProvider = ALAMIRY,
 *      reconcile SAMM disappearance with targetProvider = ALAMIRY, and skip EarthLink-only balance
 *      and prepaid network calls.
 *    - In EARTHLINK_ONLY mode, [DashboardViewModel] verifies EarthLink credentials, queries subscribers
 *      from EarthLink gateway, tags each subscriber with originProvider = EARTHLINK, reconciles EarthLink
 *      disappearance with targetProvider = EARTHLINK, and skips SAMM queries.
 *    - In BOTH mode, [DashboardViewModel] queries both EarthLink and SAMM concurrently, merges the
 *      resulting subscribers into [DashboardViewModel.subscribersList] with respective provider tags,
 *      and triggers provider-scoped reconciliation for each.
 *    - In NONE mode, [DashboardViewModel] aborts immediately, sets isCredentialsEmpty = true, and
 *      leaves subscribersList empty with zero network calls.
 *
 * 2. Seam / Environment:
 *    - ROBOLECTRIC tier ([RobolectricTestRunner]), [StandardTestDispatcher], ViewModel seam with
 *      mocked repositories, [PreferenceManager], and [SasGatewayRouter].
 *
 * 3. Independent Oracle:
 *    - Explicit literal assertions on [DashboardViewModel.isCredentialsEmpty], list contents,
 *      originProvider tags, and verified zero invocations of EarthLink balance/prepaid for SAMM_ONLY.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DashboardViewModelMultiProviderTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var earthlinkGateway: EarthlinkGateway
    private lateinit var earthlinkSasGateway: FakeSasGateway
    private lateinit var sammSasGateway: FakeSasGateway
    private lateinit var router: SasGatewayRouter
    private lateinit var audit: AuditRepository
    private lateinit var accountRepo: LocalAccountRepository
    private lateinit var ledgerRepo: LocalLedgerRepository
    private lateinit var syncRepo: SyncRepository
    private lateinit var prefs: PreferenceManager

    class FakeSasGateway(override val providerName: String) : SasGateway {
        var searchSubscribersResult: List<SasSubscriberView> = emptyList()
        var searchSubscribersCallCount: Int = 0

        override suspend fun searchSubscribers(query: String, page: Int, pageSize: Int): List<SasSubscriberView> {
            searchSubscribersCallCount++
            return searchSubscribersResult
        }

        override suspend fun getSubscriber(subscriberId: String): SasSubscriberView? = null
        override suspend fun createSubscriber(username: String, planId: String, password: String, firstName: String, lastName: String, phone: String?): SasOperationResult = SasOperationResult.Applied()
        override suspend fun suspendSubscriber(subscriberId: String): SasOperationResult = SasOperationResult.Applied()
        override suspend fun activateSubscriber(subscriberId: String): SasOperationResult = SasOperationResult.Applied()
        override suspend fun renewSubscriber(subscriberId: String): SasOperationResult = SasOperationResult.Applied()
        override suspend fun changePlan(subscriberId: String, newPlanId: String): SasOperationResult = SasOperationResult.Applied()
        override suspend fun changePassword(subscriberId: String, newPassword: String): SasOperationResult = SasOperationResult.Applied()
        override suspend fun verifyOperationOutcome(operationType: String, targetIdentifier: String, baselineEvidence: String?): SasVerificationOutcome = SasVerificationOutcome.VERIFIED_SUCCESS
        override suspend fun checkConnection(): Boolean = true
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        earthlinkGateway = mock(EarthlinkGateway::class.java)
        earthlinkSasGateway = FakeSasGateway(SasProviders.EARTHLINK)
        sammSasGateway = FakeSasGateway(SasProviders.ALAMIRY)
        router = SasGatewayRouter(earthlinkSasGateway, sammSasGateway)

        audit = mock(AuditRepository::class.java)
        accountRepo = mock(LocalAccountRepository::class.java)
        ledgerRepo = mock(LocalLedgerRepository::class.java)
        syncRepo = mock(SyncRepository::class.java)
        prefs = mock(PreferenceManager::class.java)

        `when`(accountRepo.getAllAccounts()).thenReturn(flowOf(emptyList()))
        `when`(syncRepo.syncState).thenReturn(MutableStateFlow(SyncStatusState.IDLE))
        `when`(syncRepo.syncProgress).thenReturn(MutableStateFlow(SyncProgress()))
        `when`(prefs.getDashboardSortOption()).thenReturn("name")
        `when`(prefs.getDemoMode()).thenReturn(false)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): DashboardViewModel {
        return DashboardViewModel(
            gateway = earthlinkGateway,
            sasGatewayRouter = router,
            audit = audit,
            localAccountRepository = accountRepo,
            localLedgerRepository = ledgerRepo,
            syncRepo = syncRepo,
            prefs = prefs,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun sammOnly_loadsSammSubscribers_andDoesNotAbort() = runTest(testDispatcher) {
        // GIVEN: SAMM_ONLY provider access state, no EarthLink credentials or auth token
        `when`(prefs.getProviderAccessState()).thenReturn(ProviderAccessState.SAMM_ONLY)
        `when`(prefs.getAuthToken()).thenReturn(null)
        `when`(prefs.getIspAdminUsername()).thenReturn(null)
        `when`(prefs.getIspAdminPassword()).thenReturn(null)

        val sammSubscribers = listOf(
            SasSubscriberView(
                subscriberId = "101",
                username = "samm_user_1",
                displayName = "SAMM Subscriber 1",
                status = "Active",
                planName = "Gold Plan",
                expiresAt = "2026-11-01",
                phone = "07700000001"
            ),
            SasSubscriberView(
                subscriberId = "102",
                username = "samm_user_2",
                displayName = "SAMM Subscriber 2",
                status = "Expired",
                planName = "Silver Plan",
                expiresAt = "2026-10-01",
                phone = "07700000002"
            )
        )
        sammSasGateway.searchSubscribersResult = sammSubscribers

        val viewModel = createViewModel()
        advanceUntilIdle()

        // THEN: Credentials are not marked empty, subscribers list loaded with originProvider = ALAMIRY
        assertFalse("isCredentialsEmpty must be false for SAMM_ONLY", viewModel.isCredentialsEmpty.value)
        val loadedList = viewModel.subscribersList.value
        assertEquals(2, loadedList.size)
        assertEquals("samm_user_1", loadedList[0].userID)
        assertEquals(SasProviders.ALAMIRY, loadedList[0].originProvider)
        assertEquals("samm_user_2", loadedList[1].userID)
        assertEquals(SasProviders.ALAMIRY, loadedList[1].originProvider)
        assertEquals(1, sammSasGateway.searchSubscribersCallCount)

        // AND: EarthLink balance and prepaid endpoints must NOT be called
        verify(earthlinkGateway, never()).getBalance()
        verify(earthlinkGateway, never()).getPrepaidNeeded(anyInt())
        verify(earthlinkGateway, never()).searchUsers(anyString(), anyInt(), anyInt())

        // AND: Provider-scoped disappearance reconciliation called for SAMM
        verify(accountRepo).reconcileIspDisappearance(
            authoritativeIspUserIds = setOf("samm_user_1", "samm_user_2"),
            isFetchComplete = true,
            targetProvider = SasProviders.ALAMIRY
        )
    }

    @Test
    fun earthlinkOnly_loadsEarthlinkSubscribers_tagsOriginProvider() = runTest(testDispatcher) {
        // GIVEN: EARTHLINK_ONLY provider access state with valid EarthLink credentials
        `when`(prefs.getProviderAccessState()).thenReturn(ProviderAccessState.EARTHLINK_ONLY)
        `when`(prefs.getAuthToken()).thenReturn("el_token_123")
        `when`(prefs.getIspAdminUsername()).thenReturn("admin_user")
        `when`(prefs.getIspAdminPassword()).thenReturn("admin_pass")

        val elResponse = UserListResponse(
            totalCount = 1,
            itemsList = listOf(
                UserListItem(
                    userIndexLower = 1,
                    userIDLower = "el_user_1",
                    customerNameLower = "EL User 1",
                    accountStatusLower = "Active"
                )
            )
        )
        `when`(earthlinkGateway.searchUsers(query = "", startIndex = 0, rowCount = 5000)).thenReturn(elResponse)
        `when`(earthlinkGateway.getBalance()).thenReturn(100000.0)
        `when`(earthlinkGateway.getPrepaidNeeded(7)).thenReturn(50000.0)
        `when`(earthlinkGateway.getTestUsersCount()).thenReturn(2)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // THEN: Subscribers list contains EarthLink user with originProvider = EARTHLINK
        assertFalse(viewModel.isCredentialsEmpty.value)
        val loadedList = viewModel.subscribersList.value
        assertEquals(1, loadedList.size)
        assertEquals("el_user_1", loadedList[0].userID)
        assertEquals(SasProviders.EARTHLINK, loadedList[0].originProvider)

        // AND: EarthLink balance/prepaid was called
        verify(earthlinkGateway).getBalance()
        verify(earthlinkGateway).getPrepaidNeeded(7)

        // AND: SAMM gateway must not be called
        assertEquals(0, sammSasGateway.searchSubscribersCallCount)

        // AND: Reconciliation called for EarthLink
        verify(accountRepo).reconcileIspDisappearance(
            authoritativeIspUserIds = setOf("el_user_1"),
            isFetchComplete = true,
            targetProvider = SasProviders.EARTHLINK
        )
    }

    @Test
    fun bothProviders_loadsAndCombinesSubscribersConcurrently() = runTest(testDispatcher) {
        // GIVEN: BOTH provider access state
        `when`(prefs.getProviderAccessState()).thenReturn(ProviderAccessState.BOTH)
        `when`(prefs.getAuthToken()).thenReturn("el_token_123")
        `when`(prefs.getIspAdminUsername()).thenReturn("admin_user")
        `when`(prefs.getIspAdminPassword()).thenReturn("admin_pass")

        val elResponse = UserListResponse(
            totalCount = 1,
            itemsList = listOf(
                UserListItem(
                    userIndexLower = 1,
                    userIDLower = "el_user_1",
                    customerNameLower = "EL User 1"
                )
            )
        )
        `when`(earthlinkGateway.searchUsers(query = "", startIndex = 0, rowCount = 5000)).thenReturn(elResponse)
        `when`(earthlinkGateway.getBalance()).thenReturn(100000.0)
        `when`(earthlinkGateway.getPrepaidNeeded(7)).thenReturn(50000.0)
        `when`(earthlinkGateway.getTestUsersCount()).thenReturn(0)

        val sammSubscribers = listOf(
            SasSubscriberView(
                subscriberId = "201",
                username = "samm_user_1",
                displayName = "SAMM User 1",
                status = "Active"
            )
        )
        sammSasGateway.searchSubscribersResult = sammSubscribers

        val viewModel = createViewModel()
        advanceUntilIdle()

        // THEN: Both lists are combined
        val loadedList = viewModel.subscribersList.value
        assertEquals(2, loadedList.size)

        val elItem = loadedList.first { it.userID == "el_user_1" }
        assertEquals(SasProviders.EARTHLINK, elItem.originProvider)

        val sammItem = loadedList.first { it.userID == "samm_user_1" }
        assertEquals(SasProviders.ALAMIRY, sammItem.originProvider)
        assertEquals(1, sammSasGateway.searchSubscribersCallCount)

        // AND: Reconciled per provider
        verify(accountRepo).reconcileIspDisappearance(
            authoritativeIspUserIds = setOf("el_user_1"),
            isFetchComplete = true,
            targetProvider = SasProviders.EARTHLINK
        )
        verify(accountRepo).reconcileIspDisappearance(
            authoritativeIspUserIds = setOf("samm_user_1"),
            isFetchComplete = true,
            targetProvider = SasProviders.ALAMIRY
        )
    }

    @Test
    fun noneProvider_setsCredentialsEmptyAndAborts() = runTest(testDispatcher) {
        // GIVEN: NONE provider access state
        `when`(prefs.getProviderAccessState()).thenReturn(ProviderAccessState.NONE)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // THEN: credentials marked empty, list is empty, zero gateway calls
        assertTrue(viewModel.isCredentialsEmpty.value)
        assertTrue(viewModel.subscribersList.value.isEmpty())
        verify(earthlinkGateway, never()).getBalance()
        verify(earthlinkGateway, never()).searchUsers(anyString(), anyInt(), anyInt())
        assertEquals(0, sammSasGateway.searchSubscribersCallCount)
    }
}
