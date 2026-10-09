package com.example.ui.viewmodels

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.AccountPackage
import com.example.core.model.AutocompleteUser
import com.example.core.model.LoginResponse
import com.example.core.model.SasProviders
import com.example.core.model.UserDetail
import com.example.core.model.UserListItem
import com.example.core.model.UserListResponse
import com.example.core.network.SasGateway
import com.example.core.network.SasGatewayRouter
import com.example.core.network.SasOperationResult
import com.example.core.network.SasSubscriberView
import com.example.core.network.SasVerificationOutcome
import com.example.core.security.PreferenceManager
import com.example.data.repository.AuditRepositoryImpl
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.EarthlinkGateway
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * EarthlinkSearchViewModelDefaultProviderTest
 *
 * Core Triad Specification:
 * 1. Claim:
 *    - `EarthlinkSearchViewModel._selectedProvider` initializes to the provider returned by
 *      `PreferenceManager.getLastSelectedLoginProvider()` rather than hardcoding `EARTHLINK`.
 *    - `prepareUserDetail` preserves `originProvider` on the resulting `_selectedUser`.
 *    - When viewing/refreshing a subscriber without an existing `LocalAccount` in Room,
 *      `loadUserDetail` and `getUserDetail` resolve `targetProvider` by checking `_selectedUser.originProvider`
 *      before falling back to `currentProvider`, routing external gateway calls to the subscriber's origin provider.
 * 2. Seam / Environment:
 *    ROBOLECTRIC tier ([RobolectricTestRunner]), in-memory SQLite Room [AppDatabase],
 *    real [LocalAccountRepositoryImpl], [LocalLedgerRepositoryImpl], [AuditRepositoryImpl],
 *    [PreferenceManager] backed by application context, and test doubles for [SasGateway]
 *    orchestrated by [SasGatewayRouter].
 * 3. Independent Oracle:
 *    Literal expected provider strings ([SasProviders.ALAMIRY], [SasProviders.EARTHLINK])
 *    and exact gateway invocation counters verifying that detail queries route to the subscriber's
 *    origin provider rather than the UI-selected default provider.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EarthlinkSearchViewModelDefaultProviderTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var prefs: PreferenceManager
    private lateinit var accountRepo: LocalAccountRepositoryImpl
    private lateinit var ledgerRepo: LocalLedgerRepositoryImpl
    private lateinit var auditRepo: AuditRepositoryImpl

    private lateinit var fakeEarthlinkGateway: TestEarthlinkGateway
    private lateinit var fakeEarthlinkSasGateway: TestSasGateway
    private lateinit var fakeSammSasGateway: TestSasGateway
    private lateinit var router: SasGatewayRouter

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        prefs = PreferenceManager(context).apply {
            setDemoMode(false)
            saveIspAdminUsername("test_admin")
            saveIspAdminPassword("test_pass")
            saveDepositPassword("dep_secret")
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

        fakeEarthlinkGateway = TestEarthlinkGateway()
        fakeEarthlinkSasGateway = TestSasGateway(SasProviders.EARTHLINK)
        fakeSammSasGateway = TestSasGateway(SasProviders.ALAMIRY)

        router = SasGatewayRouter(
            earthlinkAdapter = fakeEarthlinkSasGateway,
            sammAdapter = fakeSammSasGateway
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun createViewModel(): EarthlinkSearchViewModel {
        return EarthlinkSearchViewModel(
            gateway = fakeEarthlinkGateway,
            sasGatewayRouter = router,
            audit = auditRepo,
            prefs = prefs,
            localAccountRepository = accountRepo,
            localLedgerRepository = ledgerRepo,
            syncRepo = null
        )
    }

    @Test
    fun selectedProvider_initializesToLastSelectedLoginProvider() {
        prefs.setLastSelectedLoginProvider(SasProviders.ALAMIRY)

        val vm = createViewModel()

        assertEquals(
            "Selected provider must initialize to the last selected login provider (ALAMIRY)",
            SasProviders.ALAMIRY,
            vm.selectedProvider.value
        )
    }

    @Test
    fun selectedProvider_initializesToEarthlinkWhenDefault() {
        prefs.setLastSelectedLoginProvider(SasProviders.EARTHLINK)

        val vm = createViewModel()

        assertEquals(
            "Selected provider must initialize to EARTHLINK when configured",
            SasProviders.EARTHLINK,
            vm.selectedProvider.value
        )
    }

    @Test
    fun prepareUserDetail_preservesOriginProvider() {
        val vm = createViewModel()
        val partialItem = UserListItem(
            userIndexLower = 42,
            userIDLower = "alamiry_user_42",
            customerNameLower = "Alamiry Subscriber",
            originProvider = SasProviders.ALAMIRY
        )

        vm.prepareUserDetail(42, partialItem)

        val selected = vm.selectedUser.value
        assertNotNull("Selected user must be populated", selected)
        assertEquals(
            "Origin provider must be preserved from partial item",
            SasProviders.ALAMIRY,
            selected?.originProvider
        )
    }

    @Test
    fun loadUserDetail_routesByOriginProviderWhenLocalAccountMissing() = runTest {
        val vm = createViewModel()
        vm.setSelectedProvider(SasProviders.EARTHLINK)

        fakeSammSasGateway.subscriberDetailResult = SasSubscriberView(
            subscriberId = "42",
            username = "alamiry_user_42",
            displayName = "Alamiry Subscriber",
            status = "Active",
            planName = "Standard"
        )

        val partialItem = UserListItem(
            userIndexLower = 42,
            userIDLower = "alamiry_user_42",
            customerNameLower = "Alamiry Subscriber",
            originProvider = SasProviders.ALAMIRY
        )
        vm.prepareUserDetail(42, partialItem)

        val job = vm.loadUserDetail(42, "alamiry_user_42")
        job.join()

        assertEquals("SAMM gateway must receive getSubscriber call", 1, fakeSammSasGateway.getSubscriberCalls.get())
        assertEquals("EarthLink gateway must not receive getSubscriber call", 0, fakeEarthlinkSasGateway.getSubscriberCalls.get())
        assertEquals("Selected user must preserve origin provider", SasProviders.ALAMIRY, vm.selectedUser.value?.originProvider)
    }

    @Test
    fun getUserDetail_routesByOriginProviderWhenLocalAccountMissing() = runTest {
        val vm = createViewModel()
        vm.setSelectedProvider(SasProviders.EARTHLINK)

        fakeSammSasGateway.subscriberDetailResult = SasSubscriberView(
            subscriberId = "42",
            username = "alamiry_user_42",
            displayName = "Alamiry Subscriber",
            status = "Active",
            planName = "Standard"
        )

        val partialItem = UserListItem(
            userIndexLower = 42,
            userIDLower = "alamiry_user_42",
            customerNameLower = "Alamiry Subscriber",
            originProvider = SasProviders.ALAMIRY
        )
        vm.prepareUserDetail(42, partialItem)

        val detail = vm.getUserDetail(42)

        assertEquals("SAMM gateway must receive getSubscriber call", 1, fakeSammSasGateway.getSubscriberCalls.get())
        assertEquals("EarthLink gateway must not receive getSubscriber call", 0, fakeEarthlinkSasGateway.getSubscriberCalls.get())
        assertNotNull("UserDetail must not be null", detail)
        assertEquals("UserDetail must preserve origin provider", SasProviders.ALAMIRY, detail?.originProvider)
    }

    // ========================================================================
    // Test Doubles
    // ========================================================================

    class TestSasGateway(override val providerName: String) : SasGateway {
        val searchSubscribersCalls = AtomicInteger(0)
        val getSubscriberCalls = AtomicInteger(0)

        var searchResult: List<SasSubscriberView> = emptyList()
        var subscriberDetailResult: SasSubscriberView? = null

        override suspend fun searchSubscribers(query: String, page: Int, pageSize: Int): List<SasSubscriberView> {
            searchSubscribersCalls.incrementAndGet()
            return searchResult
        }

        override suspend fun getSubscriber(subscriberId: String): SasSubscriberView? {
            getSubscriberCalls.incrementAndGet()
            return subscriberDetailResult
        }

        override suspend fun createSubscriber(
            username: String,
            planId: String,
            password: String,
            firstName: String,
            lastName: String,
            phone: String?
        ): SasOperationResult = SasOperationResult.Applied()

        override suspend fun suspendSubscriber(subscriberId: String): SasOperationResult = SasOperationResult.Applied()
        override suspend fun activateSubscriber(subscriberId: String): SasOperationResult = SasOperationResult.Applied()
        override suspend fun renewSubscriber(subscriberId: String): SasOperationResult = SasOperationResult.Applied()
        override suspend fun changePlan(subscriberId: String, newPlanId: String): SasOperationResult = SasOperationResult.Applied()
        override suspend fun changePassword(subscriberId: String, newPassword: String): SasOperationResult = SasOperationResult.Applied()
        override suspend fun verifyOperationOutcome(
            operationType: String,
            targetIdentifier: String,
            baselineEvidence: String?
        ): SasVerificationOutcome = SasVerificationOutcome.VERIFIED_SUCCESS
        override suspend fun checkConnection(): Boolean = true
    }

    class TestEarthlinkGateway : EarthlinkGateway {
        val getBalanceCalls = AtomicInteger(0)
        val getUserDetailCalls = AtomicInteger(0)

        override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean = true
        override suspend fun createTestUser(username: String, phone: String, fullName: String, accountIndex: Int): String? = "pass"
        override suspend fun getBalance(): Double {
            getBalanceCalls.incrementAndGet()
            return 500000.0
        }
        override suspend fun getPrepaidNeeded(): Double = 10000.0
        override suspend fun createUserUsingDeposit(username: String, phone: String, fullName: String, accountIndex: Int, depositPassword: String): String? = "pass"
        override suspend fun toggleUserActive(userIndex: Int, active: Boolean): Boolean = true
        override suspend fun checkUsernameAvailable(userId: String): Boolean = true
        override suspend fun getAccountCost(accountIndex: Int): Double = 35000.0
        override suspend fun getPackages(): List<AccountPackage> = emptyList()
        override suspend fun searchUsers(query: String, startIndex: Int, rowCount: Int): UserListResponse = UserListResponse(emptyList(), 0)
        override suspend fun getUserDetail(userIndex: Int): UserDetail {
            getUserDetailCalls.incrementAndGet()
            return UserDetail()
        }
        override suspend fun login(username: String, password: String): LoginResponse = LoginResponse("token", "Bearer", 3600)
        override suspend fun getTestUsersCount(affiliateIndex: Int?): Int = 0
        override suspend fun getActiveTestUsersCount(): Int = 0
        override suspend fun autocompleteUser(query: String): List<AutocompleteUser> = emptyList()
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
