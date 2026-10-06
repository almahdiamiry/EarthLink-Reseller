package com.example.ui.viewmodels

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.AccountPackage
import com.example.core.model.AutocompleteUser
import com.example.core.model.LocalAccount
import com.example.core.model.LoginResponse
import com.example.core.model.SasProviders
import com.example.core.model.UserDetail
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * EarthlinkSearchViewModelProviderRoutingTest
 *
 * Authoritative unit test suite verifying provider-aware routing in [EarthlinkSearchViewModel]
 * according to Task 10 of the Alamiry/SAMM Integration plan.
 *
 * Core Triad Specification:
 * 1. Claim:
 *    - Common subscriber operations (searchUsers, getUserDetail, activation, toggleUserActive,
 *      changePlan, changePassword) route dynamically via [SasGatewayRouter] to SAMM when
 *      [SasProviders.ALAMIRY] is selected, and to EarthLink when [SasProviders.EARTHLINK] is selected.
 *    - Provider intent is immutably captured before asynchronous dispatch, preventing race conditions.
 *    - Activation persists [PendingExternalOperation.operationProvider] strictly matching the selected provider.
 *    - Refill is strictly EarthLink-only: targeting ALAMIRY fails closed with an unsupported error
 *      and executes exactly 0 network calls against any gateway.
 *    - EarthLink-only portal operations (getBalance, getPrepaidNeeded, createTestUser) remain direct
 *      on [EarthlinkGateway] and never route to SAMM.
 *
 * 2. Seam / Environment:
 *    ROBOLECTRIC tier ([RobolectricTestRunner]), in-memory SQLite Room [AppDatabase],
 *    real [LocalAccountRepositoryImpl], [LocalLedgerRepositoryImpl], [AuditRepositoryImpl],
 *    coupled with test-double [SasGateway] instances orchestrated by [SasGatewayRouter].
 *
 * 3. Independent Oracle:
 *    Independent invocation counters (exact 1 or 0) asserting mutual exclusivity across providers,
 *    literal expected error messages, database-persisted operation provider tags, and domain views
 *    verified independently of production ViewModel wiring.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EarthlinkSearchViewModelProviderRoutingTest {

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
    private lateinit var viewModel: EarthlinkSearchViewModel

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
    }

    // ========================================================================
    // 1. Search Routing
    // ========================================================================

    /**
     * Claim: When ALAMIRY provider is selected, searchUsers routes exclusively to SAMM gateway.
     * Seam: ROBOLECTRIC tier, EarthlinkSearchViewModel.searchUsers.
     * Oracle: SAMM searchSubscribers called exactly once, EarthLink called zero times.
     */
    @Test
    fun test01_searchUsers_routesToSamm_whenAlamirySelected() = runTest {
        fakeSammSasGateway.searchResult = listOf(
            SasSubscriberView(
                subscriberId = "101",
                username = "samm_sub_1",
                displayName = "SAMM Subscriber 1",
                status = "Active",
                planName = "SAMM 10M"
            )
        )

        viewModel.setSelectedProvider(SasProviders.ALAMIRY)
        assertEquals(SasProviders.ALAMIRY, viewModel.selectedProvider.value)

        viewModel.setSearchQuery("samm_sub")
        viewModel.searchUsers()

        assertEquals("SAMM gateway must receive exactly 1 search call", 1, fakeSammSasGateway.searchSubscribersCalls.get())
        assertEquals("EarthLink SAS gateway must receive 0 search calls", 0, fakeEarthlinkSasGateway.searchSubscribersCalls.get())
        assertEquals("Legacy EarthlinkGateway must receive 0 search calls", 0, fakeEarthlinkGateway.searchUsersCalls.get())

        val results = viewModel.usersList.value
        assertEquals(1, results.size)
        assertEquals("samm_sub_1", results[0].userID)
        assertEquals("SAMM Subscriber 1", results[0].customerName)
    }

    /**
     * Claim: When EARTHLINK provider is selected, searchUsers routes exclusively to EarthLink gateway.
     * Seam: ROBOLECTRIC tier, EarthlinkSearchViewModel.searchUsers.
     * Oracle: EarthLink searchSubscribers called exactly once, SAMM called zero times.
     */
    @Test
    fun test02_searchUsers_routesToEarthlink_whenEarthlinkSelected() = runTest {
        fakeEarthlinkSasGateway.searchResult = listOf(
            SasSubscriberView(
                subscriberId = "202",
                username = "earth_sub_2",
                displayName = "EarthLink Subscriber 2",
                status = "Active",
                planName = "Standard Plan"
            )
        )

        viewModel.setSelectedProvider(SasProviders.EARTHLINK)
        assertEquals(SasProviders.EARTHLINK, viewModel.selectedProvider.value)

        viewModel.setSearchQuery("earth_sub")
        viewModel.searchUsers()

        assertEquals("EarthLink SAS gateway must receive exactly 1 search call", 1, fakeEarthlinkSasGateway.searchSubscribersCalls.get())
        assertEquals("SAMM gateway must receive 0 search calls", 0, fakeSammSasGateway.searchSubscribersCalls.get())

        val results = viewModel.usersList.value
        assertEquals(1, results.size)
        assertEquals("earth_sub_2", results[0].userID)
        assertEquals("EarthLink Subscriber 2", results[0].customerName)
    }

    // ========================================================================
    // 2. User Detail Routing
    // ========================================================================

    /**
     * Claim: When ALAMIRY provider is selected, getUserDetail routes exclusively to SAMM gateway.
     * Seam: ROBOLECTRIC tier, EarthlinkSearchViewModel.getUserDetail and loadUserDetail.
     * Oracle: SAMM getSubscriber called exactly once, EarthLink called zero times.
     */
    @Test
    fun test03_getUserDetail_routesToSamm_whenAlamirySelected() = runTest {
        fakeSammSasGateway.subscriberDetailResult = SasSubscriberView(
            subscriberId = "101",
            username = "samm_detail_user",
            displayName = "SAMM Detail User",
            status = "Active",
            planId = "5",
            planName = "SAMM VIP",
            phone = "07701111111"
        )

        viewModel.setSelectedProvider(SasProviders.ALAMIRY)
        val detail = viewModel.getUserDetail(101)

        assertEquals("SAMM gateway must receive exactly 1 getSubscriber call", 1, fakeSammSasGateway.getSubscriberCalls.get())
        assertEquals("EarthLink SAS gateway must receive 0 getSubscriber calls", 0, fakeEarthlinkSasGateway.getSubscriberCalls.get())
        assertEquals("Legacy EarthlinkGateway must receive 0 getUserDetail calls", 0, fakeEarthlinkGateway.getUserDetailCalls.get())

        assertNotNull(detail)
        assertEquals("samm_detail_user", detail?.userID)
        assertEquals("SAMM Detail User", detail?.customerFullName)
        assertEquals("SAMM VIP", detail?.packageName)
    }

    /**
     * Claim: When EARTHLINK provider is selected, getUserDetail routes exclusively to EarthLink gateway.
     * Seam: ROBOLECTRIC tier, EarthlinkSearchViewModel.getUserDetail.
     * Oracle: EarthLink getSubscriber called exactly once, SAMM called zero times.
     */
    @Test
    fun test04_getUserDetail_routesToEarthlink_whenEarthlinkSelected() = runTest {
        fakeEarthlinkSasGateway.subscriberDetailResult = SasSubscriberView(
            subscriberId = "202",
            username = "earth_detail_user",
            displayName = "EarthLink Detail User",
            status = "Active",
            planId = "2",
            planName = "EarthLink Premium",
            phone = "07702222222"
        )

        viewModel.setSelectedProvider(SasProviders.EARTHLINK)
        val detail = viewModel.getUserDetail(202)

        assertEquals("EarthLink SAS gateway must receive exactly 1 getSubscriber call", 1, fakeEarthlinkSasGateway.getSubscriberCalls.get())
        assertEquals("SAMM gateway must receive 0 getSubscriber calls", 0, fakeSammSasGateway.getSubscriberCalls.get())

        assertNotNull(detail)
        assertEquals("earth_detail_user", detail?.userID)
        assertEquals("EarthLink Detail User", detail?.customerFullName)
    }

    // ========================================================================
    // 3. Activation Routing & Provider Capture
    // ========================================================================

    /**
     * Claim: When ALAMIRY provider is selected, activation routes to SAMM createSubscriber
     * and persists operationProvider = "ALAMIRY" in PendingExternalOperation.
     * Seam: ROBOLECTRIC tier, EarthlinkSearchViewModel.createUserUsingDeposit.
     * Oracle: SAMM createSubscriber called once, EarthLink called zero times, pending op records ALAMIRY.
     */
    @Test
    fun test05_activation_routesToSamm_whenAlamirySelected() = runTest {
        fakeSammSasGateway.createResult = SasOperationResult.Applied(evidence = "samm_cust_999")

        viewModel.setSelectedProvider(SasProviders.ALAMIRY)
        val job = viewModel.createUserUsingDeposit(
            username = "new_samm_user",
            phone = "07701234567",
            fullName = "New Samm",
            pkgIndex = 5,
            depositPass = "secret123",
            intentId = "intent_samm_01"
        )
        job.join()

        assertEquals("SAMM gateway must receive exactly 1 createSubscriber call", 1, fakeSammSasGateway.createSubscriberCalls.get())
        assertEquals("EarthLink SAS gateway must receive 0 createSubscriber calls", 0, fakeEarthlinkSasGateway.createSubscriberCalls.get())
        assertEquals("Legacy EarthlinkGateway must receive 0 createUserUsingDeposit calls", 0, fakeEarthlinkGateway.createUserUsingDepositCalls.get())

        val pendingOp = ledgerRepo.getPendingOperationByIntentId("intent_samm_01")
        assertNotNull("Pending operation must be recorded", pendingOp)
        assertEquals("operationProvider must be ALAMIRY", SasProviders.ALAMIRY, pendingOp?.operationProvider)
        assertEquals("Operation status must be COMPLETED", "COMPLETED", pendingOp?.status)
    }

    /**
     * Claim: When EARTHLINK provider is selected, activation routes to EarthLink createSubscriber
     * and persists operationProvider = "EARTHLINK" in PendingExternalOperation.
     * Seam: ROBOLECTRIC tier, EarthlinkSearchViewModel.createUserUsingDeposit.
     * Oracle: EarthLink createSubscriber called once, SAMM called zero times, pending op records EARTHLINK.
     */
    @Test
    fun test06_activation_routesToEarthlink_whenEarthlinkSelected() = runTest {
        fakeEarthlinkSasGateway.createResult = SasOperationResult.Applied(evidence = "gen_pass_777")

        viewModel.setSelectedProvider(SasProviders.EARTHLINK)
        val job = viewModel.createUserUsingDeposit(
            username = "new_earth_user",
            phone = "07707654321",
            fullName = "New Earth",
            pkgIndex = 1,
            depositPass = "dep_pass_456",
            intentId = "intent_earth_01"
        )
        job.join()

        assertEquals("EarthLink SAS gateway must receive exactly 1 createSubscriber call", 1, fakeEarthlinkSasGateway.createSubscriberCalls.get())
        assertEquals("SAMM gateway must receive 0 createSubscriber calls", 0, fakeSammSasGateway.createSubscriberCalls.get())

        val pendingOp = ledgerRepo.getPendingOperationByIntentId("intent_earth_01")
        assertNotNull("Pending operation must be recorded", pendingOp)
        assertEquals("operationProvider must be EARTHLINK", SasProviders.EARTHLINK, pendingOp?.operationProvider)
        assertEquals("Operation status must be COMPLETED", "COMPLETED", pendingOp?.status)
    }

    // ========================================================================
    // 4. Toggle Active Routing
    // ========================================================================

    /**
     * Claim: When ALAMIRY provider is selected (or account is ALAMIRY), toggleUserActive routes to SAMM.
     * Seam: ROBOLECTRIC tier, EarthlinkSearchViewModel.toggleUserActive.
     * Oracle: SAMM suspendSubscriber called once, EarthLink called zero times.
     */
    @Test
    fun test07_toggleUserActive_routesToSamm_whenAlamirySelected() = runTest {
        viewModel.setSelectedProvider(SasProviders.ALAMIRY)
        val job = viewModel.toggleUserActive(101, "samm_sub", active = false)
        job.join()

        assertEquals("SAMM gateway must receive exactly 1 suspendSubscriber call", 1, fakeSammSasGateway.suspendSubscriberCalls.get())
        assertEquals("EarthLink SAS gateway must receive 0 suspendSubscriber calls", 0, fakeEarthlinkSasGateway.suspendSubscriberCalls.get())
        assertEquals("Legacy EarthlinkGateway must receive 0 toggleUserActive calls", 0, fakeEarthlinkGateway.toggleUserActiveCalls.get())
    }

    /**
     * Claim: When EARTHLINK provider is selected, toggleUserActive routes to EarthLink.
     * Seam: ROBOLECTRIC tier, EarthlinkSearchViewModel.toggleUserActive.
     * Oracle: EarthLink activateSubscriber called once, SAMM called zero times.
     */
    @Test
    fun test08_toggleUserActive_routesToEarthlink_whenEarthlinkSelected() = runTest {
        viewModel.setSelectedProvider(SasProviders.EARTHLINK)
        val job = viewModel.toggleUserActive(202, "earth_sub", active = true)
        job.join()

        assertEquals("EarthLink SAS gateway must receive exactly 1 activateSubscriber call", 1, fakeEarthlinkSasGateway.activateSubscriberCalls.get())
        assertEquals("SAMM gateway must receive 0 activateSubscriber calls", 0, fakeSammSasGateway.activateSubscriberCalls.get())
    }

    // ========================================================================
    // 5. Refill Fail-Closed Boundary (ALAMIRY strictly unsupported)
    // ========================================================================

    /**
     * Claim: Refill with ALAMIRY fails closed with an unsupported error message and 0 network calls.
     * Seam: ROBOLECTRIC tier, EarthlinkSearchViewModel.refillUserDeposit.
     * Oracle: Error emitted, 0 refill calls to EarthLink, 0 calls to SAMM gateway.
     */
    @Test
    fun test09_refill_alamiryFailsClosed_zeroNetworkCalls() = runTest {
        viewModel.setSelectedProvider(SasProviders.ALAMIRY)

        val job = viewModel.refillUserDeposit(
            userId = "samm_user_101",
            depositPass = "pass123",
            price = 35000.0
        )
        job.join()

        val err = viewModel.error.value
        assertNotNull("Error must be emitted for ALAMIRY refill", err)
        assertTrue("Error must state unsupported for ALAMIRY", err!!.contains("Refill is not supported for ALAMIRY provider", ignoreCase = true))

        assertEquals("EarthlinkGateway refillUserDeposit must receive 0 calls", 0, fakeEarthlinkGateway.refillUserDepositCalls.get())
        assertEquals("SAMM renewSubscriber must receive 0 calls", 0, fakeSammSasGateway.renewSubscriberCalls.get())
        assertEquals("EarthLink renewSubscriber must receive 0 calls", 0, fakeEarthlinkSasGateway.renewSubscriberCalls.get())
    }

    // ========================================================================
    // 6. EarthLink-Only Operations Preserved Direct
    // ========================================================================

    /**
     * Claim: Portal operations (getBalance, createTestUser) remain direct on EarthlinkGateway
     * regardless of selectedProvider state, and never query SAMM.
     * Seam: ROBOLECTRIC tier, EarthlinkSearchViewModel portal methods.
     * Oracle: EarthlinkGateway methods called, SAMM receives 0 calls.
     */
    @Test
    fun test10_earthlinkOnlyMethods_remainDirectOnEarthlinkGateway() = runTest {
        viewModel.setSelectedProvider(SasProviders.ALAMIRY)

        val balance = viewModel.getResellerBalance()
        assertEquals(500000.0, balance, 0.001)
        assertEquals("EarthlinkGateway getBalance must receive 1 call", 1, fakeEarthlinkGateway.getBalanceCalls.get())

        val testUserJob = viewModel.createTestUser(
            username = "test_user_01",
            phone = "07703333333",
            fullName = "Test User",
            pkgIndex = 1
        )
        testUserJob.join()
        assertEquals("EarthlinkGateway createTestUser must receive 1 call", 1, fakeEarthlinkGateway.createTestUserCalls.get())

        // Verify SAMM received zero calls across all operations
        assertEquals(0, fakeSammSasGateway.createSubscriberCalls.get())
        assertEquals(0, fakeSammSasGateway.searchSubscribersCalls.get())
        assertEquals(0, fakeSammSasGateway.getSubscriberCalls.get())
    }

    // ========================================================================
    // 7. Plan Change & Password Change Routing
    // ========================================================================

    /**
     * Claim: changeAccountType and changeUserPassword route to SAMM when ALAMIRY is selected.
     * Seam: ROBOLECTRIC tier, EarthlinkSearchViewModel plan and password management.
     * Oracle: SAMM changePlan and changePassword called once, EarthLink called zero times.
     */
    @Test
    fun test11_planAndPassChange_routeToSamm_whenAlamirySelected() = runTest {
        viewModel.setSelectedProvider(SasProviders.ALAMIRY)

        val planJob = viewModel.changeAccountType(
            userIndex = 101,
            userId = "samm_user_101",
            accountIndex = 3,
            accountName = "Gold Plan"
        )
        planJob.join()
        assertEquals("SAMM gateway must receive 1 changePlan call", 1, fakeSammSasGateway.changePlanCalls.get())
        assertEquals("EarthLink SAS gateway must receive 0 changePlan calls", 0, fakeEarthlinkSasGateway.changePlanCalls.get())

        val passJob = viewModel.changeUserPassword(
            userIndex = 101,
            userId = "samm_user_101",
            newPass = "newSecret99"
        )
        passJob.join()
        assertEquals("SAMM gateway must receive 1 changePassword call", 1, fakeSammSasGateway.changePasswordCalls.get())
        assertEquals("EarthLink SAS gateway must receive 0 changePassword calls", 0, fakeEarthlinkSasGateway.changePasswordCalls.get())
    }

    // ========================================================================
    // 8. Race-Condition Protection: Provider Intent Capture
    // ========================================================================

    /**
     * Claim: Capturing provider intent before launch prevents mid-flight provider switching from redirecting an operation.
     * Seam: ROBOLECTRIC tier, EarthlinkSearchViewModel.createUserUsingDeposit.
     * Oracle: Initial selectedProvider (ALAMIRY) governs dispatch and recorded operationProvider even if
     * setSelectedProvider is altered immediately after dispatch initiation.
     */
    @Test
    fun test12_providerIntentCapturedBeforeAsyncLaunch_preventsRaceCondition() = runTest {
        fakeSammSasGateway.createResult = SasOperationResult.Applied(evidence = "samm_cust_race")

        viewModel.setSelectedProvider(SasProviders.ALAMIRY)

        val job = viewModel.createUserUsingDeposit(
            username = "race_user",
            phone = "07709999999",
            fullName = "Race Condition User",
            pkgIndex = 2,
            depositPass = "secretRace",
            intentId = "intent_race_01"
        )

        // Operator immediately switches provider in UI before coroutine completes:
        viewModel.setSelectedProvider(SasProviders.EARTHLINK)

        job.join()

        assertEquals("Must still dispatch to SAMM", 1, fakeSammSasGateway.createSubscriberCalls.get())
        assertEquals("Must NOT dispatch to EarthLink", 0, fakeEarthlinkSasGateway.createSubscriberCalls.get())

        val pendingOp = ledgerRepo.getPendingOperationByIntentId("intent_race_01")
        assertEquals("Recorded provider must be ALAMIRY", SasProviders.ALAMIRY, pendingOp?.operationProvider)
    }

    // ========================================================================
    // Test Doubles
    // ========================================================================

    class TestSasGateway(override val providerName: String) : SasGateway {
        val searchSubscribersCalls = AtomicInteger(0)
        val getSubscriberCalls = AtomicInteger(0)
        val createSubscriberCalls = AtomicInteger(0)
        val suspendSubscriberCalls = AtomicInteger(0)
        val activateSubscriberCalls = AtomicInteger(0)
        val renewSubscriberCalls = AtomicInteger(0)
        val changePlanCalls = AtomicInteger(0)
        val changePasswordCalls = AtomicInteger(0)
        val verifyCalls = AtomicInteger(0)

        var searchResult: List<SasSubscriberView> = emptyList()
        var subscriberDetailResult: SasSubscriberView? = null
        var createResult: SasOperationResult = SasOperationResult.Applied(evidence = "test_evidence")
        var suspendResult: SasOperationResult = SasOperationResult.Applied()
        var activateResult: SasOperationResult = SasOperationResult.Applied()
        var renewResult: SasOperationResult = SasOperationResult.Applied()
        var changePlanResult: SasOperationResult = SasOperationResult.Applied()
        var changePasswordResult: SasOperationResult = SasOperationResult.Applied()

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
        ): SasOperationResult {
            createSubscriberCalls.incrementAndGet()
            return createResult
        }

        override suspend fun suspendSubscriber(subscriberId: String): SasOperationResult {
            suspendSubscriberCalls.incrementAndGet()
            return suspendResult
        }

        override suspend fun activateSubscriber(subscriberId: String): SasOperationResult {
            activateSubscriberCalls.incrementAndGet()
            return activateResult
        }

        override suspend fun renewSubscriber(subscriberId: String): SasOperationResult {
            renewSubscriberCalls.incrementAndGet()
            return renewResult
        }

        override suspend fun changePlan(subscriberId: String, newPlanId: String): SasOperationResult {
            changePlanCalls.incrementAndGet()
            return changePlanResult
        }

        override suspend fun changePassword(subscriberId: String, newPassword: String): SasOperationResult {
            changePasswordCalls.incrementAndGet()
            return changePasswordResult
        }

        override suspend fun verifyOperationOutcome(
            operationType: String,
            targetIdentifier: String,
            baselineEvidence: String?
        ): SasVerificationOutcome {
            verifyCalls.incrementAndGet()
            return SasVerificationOutcome.VERIFIED_SUCCESS
        }

        override suspend fun checkConnection(): Boolean = true
    }

    class TestEarthlinkGateway : EarthlinkGateway {
        val refillUserDepositCalls = AtomicInteger(0)
        val createTestUserCalls = AtomicInteger(0)
        val getBalanceCalls = AtomicInteger(0)
        val getPrepaidNeededCalls = AtomicInteger(0)
        val createUserUsingDepositCalls = AtomicInteger(0)
        val toggleUserActiveCalls = AtomicInteger(0)
        val searchUsersCalls = AtomicInteger(0)
        val getUserDetailCalls = AtomicInteger(0)

        override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean {
            refillUserDepositCalls.incrementAndGet()
            return true
        }

        override suspend fun createTestUser(username: String, phone: String, fullName: String, accountIndex: Int): String? {
            createTestUserCalls.incrementAndGet()
            return "test_pass_123"
        }

        override suspend fun getBalance(): Double {
            getBalanceCalls.incrementAndGet()
            return 500000.0
        }

        override suspend fun getPrepaidNeeded(): Double {
            getPrepaidNeededCalls.incrementAndGet()
            return 10000.0
        }

        override suspend fun createUserUsingDeposit(username: String, phone: String, fullName: String, accountIndex: Int, depositPassword: String): String? {
            createUserUsingDepositCalls.incrementAndGet()
            return "deposit_pass_456"
        }

        override suspend fun toggleUserActive(userIndex: Int, active: Boolean): Boolean {
            toggleUserActiveCalls.incrementAndGet()
            return true
        }

        override suspend fun checkUsernameAvailable(userId: String): Boolean = true
        override suspend fun getAccountCost(accountIndex: Int): Double = 35000.0
        override suspend fun getPackages(): List<AccountPackage> = emptyList()
        override suspend fun searchUsers(query: String, startIndex: Int, rowCount: Int): UserListResponse {
            searchUsersCalls.incrementAndGet()
            return UserListResponse(itemsList = emptyList(), totalCount = 0)
        }
        override suspend fun getUserDetail(userIndex: Int): UserDetail {
            getUserDetailCalls.incrementAndGet()
            return UserDetail()
        }
        override suspend fun login(username: String, password: String): LoginResponse =
            LoginResponse(accessToken = "token", tokenType = "Bearer", expiresIn = 3600)
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
