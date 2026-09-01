package com.example.ui.viewmodels

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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DashboardViewModelForecastTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var gateway: EarthlinkGateway
    private lateinit var audit: AuditRepository
    private lateinit var accountRepo: LocalAccountRepository
    private lateinit var ledgerRepo: LocalLedgerRepository
    private lateinit var syncRepo: SyncRepository
    private lateinit var prefs: PreferenceManager
    private lateinit var viewModel: DashboardViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        gateway = mock(EarthlinkGateway::class.java)
        audit = mock(AuditRepository::class.java)
        accountRepo = mock(LocalAccountRepository::class.java)
        ledgerRepo = mock(LocalLedgerRepository::class.java)
        syncRepo = mock(SyncRepository::class.java)
        prefs = mock(PreferenceManager::class.java)

        `when`(accountRepo.getAllAccounts()).thenReturn(flowOf(emptyList()))
        `when`(syncRepo.syncState).thenReturn(MutableStateFlow(SyncStatusState.IDLE))
        `when`(syncRepo.syncProgress).thenReturn(MutableStateFlow(SyncProgress()))
        `when`(prefs.getDemoMode()).thenReturn(false)
        `when`(prefs.getAuthToken()).thenReturn("mock_auth_token")
        `when`(prefs.getIspAdminUsername()).thenReturn("admin_user")
        `when`(prefs.getIspAdminPassword()).thenReturn("admin_pass")
        `when`(prefs.getDashboardSortOption()).thenReturn("name")

        viewModel = DashboardViewModel(
            gateway = gateway,
            audit = audit,
            localAccountRepository = accountRepo,
            localLedgerRepository = ledgerRepo,
            syncRepo = syncRepo,
            prefs = prefs,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testDefaultDaysIsSeven() = runTest(testDispatcher) {
        assertEquals(7, viewModel.prepaidNeededDays.value)
    }

    @Test
    fun testLoadDashboardData_usesConfiguredDays() = runTest(testDispatcher) {
        `when`(gateway.getBalance()).thenReturn(500000.0)
        `when`(gateway.getPrepaidNeeded(7)).thenReturn(200000.0)
        `when`(gateway.getActiveTestUsersCount()).thenReturn(3)

        viewModel.loadDashboardData()
        advanceUntilIdle()

        verify(gateway, atLeastOnce()).getPrepaidNeeded(7)
        assertEquals(500000.0, viewModel.balance.value, 0.001)
        assertEquals(200000.0, viewModel.prepaidNeeded.value, 0.001)
        val expectedForecastAfter = viewModel.balance.value - viewModel.prepaidNeeded.value
        assertEquals(300000.0, expectedForecastAfter, 0.001)
    }

    @Test
    fun testSetPrepaidNeededDays_updatesDaysAndFetchesForecast() = runTest(testDispatcher) {
        `when`(gateway.getBalance()).thenReturn(500000.0)
        `when`(gateway.getPrepaidNeeded(7)).thenReturn(200000.0)
        `when`(gateway.getPrepaidNeeded(14)).thenReturn(400000.0)
        `when`(gateway.getActiveTestUsersCount()).thenReturn(3)

        viewModel.loadDashboardData()
        advanceUntilIdle()
        assertEquals(200000.0, viewModel.prepaidNeeded.value, 0.001)

        // User changes days to 14
        viewModel.setPrepaidNeededDays(14)
        advanceUntilIdle()

        assertEquals(14, viewModel.prepaidNeededDays.value)
        verify(gateway).getPrepaidNeeded(14)
        assertEquals(400000.0, viewModel.prepaidNeeded.value, 0.001)
        val expectedForecastAfter = viewModel.balance.value - viewModel.prepaidNeeded.value
        // Expected balance after this = 500,000 - 400,000 = 100,000
        assertEquals(100000.0, expectedForecastAfter, 0.001)
    }
}
