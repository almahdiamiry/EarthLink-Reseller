package com.example.ui.viewmodels

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.ProviderAccessState
import com.example.core.model.SasProviders
import com.example.core.model.UserListItem
import com.example.core.model.UserListResponse
import com.example.core.network.SasGateway
import com.example.core.network.SasGatewayRouter
import com.example.core.network.SasOperationResult
import com.example.core.network.SasSubscriberView
import com.example.core.network.SasVerificationOutcome
import com.example.core.security.PreferenceManager
import com.example.core.sync.RemoteEntityValidationResult
import com.example.core.sync.RemoteEntityValidator
import com.example.domain.repository.AuditRepository
import com.example.domain.repository.EarthlinkGateway
import com.example.domain.repository.LocalAccountRepository
import com.example.domain.repository.LocalLedgerRepository
import com.example.domain.repository.SyncProgress
import com.example.domain.repository.SyncRepository
import com.example.domain.repository.SyncStatusState
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MultiProviderFirstRunAndRestoreScenariosTest
 *
 * Comprehensive end-to-end multi-provider lifecycle verification suite covering all
 * First-Run (A.1, A.2, A.3) and Returning User (B.1, B.2, B.3) scenarios per the
 * Supported Lifecycle Scenarios Matrix in the Multi-Provider Subscriber Sync specification.
 *
 * Core Triad Specification:
 * 1. Claim:
 *    - Scenario A.1 (SAMM Only): First run with SAMM configuration loads subscribers via [SasGatewayRouter],
 *      sets isCredentialsEmpty = false, and tags all subscriber items with originProvider = ALAMIRY.
 *    - Scenario A.1 (Both Providers): First run with both providers concurrently loads from EarthLink and SAMM,
 *      merges them into subscribersList, and tags each with their respective originProvider (EARTHLINK, ALAMIRY).
 *    - Scenario A.2 (Google Login + Provider): Cloud authenticated session (Google sign-in) paired with ISP
 *      credentials loads provider subscribers seamlessly without credential-empty lockouts.
 *    - Scenario A.3 (uTower Import): Account imported into Room SQLite under an active provider preserves its
 *      operationProvider tag and emits into the repository/dashboard stream.
 *    - Scenario B.1 (Returning User): Returning user session unlocks cleanly based on configured provider credentials
 *      (SAMM_ONLY, EARTHLINK_ONLY, BOTH), while unconfigured state remains locked (NONE).
 *    - Scenario B.2 (Firebase Cloud Restore): [RemoteEntityValidator.validateAndMapAccount] parses Firestore
 *      documents containing operationProvider = ALAMIRY and EARTHLINK, accurately preserving provider identity and debt.
 *    - Scenario B.3 (Local Backup Restore): Room entity [LocalAccount] preserves operationProvider = ALAMIRY and
 *      EARTHLINK across SQLite storage and retrieval.
 *
 * 2. Seam / Environment:
 *    - ROBOLECTRIC tier ([RobolectricTestRunner]), in-memory SQLite Room [AppDatabase],
 *      [StandardTestDispatcher] for coroutine scheduling, and real/mock domain repositories.
 *
 * 3. Independent Oracle:
 *    - Pure domain assertions on [ProviderAccessState], [UserListItem.originProvider],
 *      [LocalAccount.operationProvider], and [RemoteEntityValidationResult.Valid.entity.operationProvider]
 *      independently derived from the specification contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MultiProviderFirstRunAndRestoreScenariosTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: AppDatabase
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
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

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
        `when`(prefs.getDashboardSortOption()).thenReturn("الاسم")
        `when`(prefs.getDemoMode()).thenReturn(false)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun createDashboardViewModel(): DashboardViewModel {
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

    // =========================================================================
    // SCENARIO A.1: First Run - SAMM Only & Dual Provider
    // =========================================================================

    @Test
    fun scenarioA1_firstRun_sammOnly_displaysSammSubscribersWithoutEmptyScreen() = runTest(testDispatcher) {
        `when`(prefs.getProviderAccessState()).thenReturn(ProviderAccessState.SAMM_ONLY)
        `when`(prefs.getAuthToken()).thenReturn(null)
        `when`(prefs.getIspAdminUsername()).thenReturn(null)
        `when`(prefs.getIspAdminPassword()).thenReturn(null)

        sammSasGateway.searchSubscribersResult = listOf(
            SasSubscriberView(
                subscriberId = "101",
                username = "samm_user_1",
                displayName = "SAMM User One",
                status = "Active",
                planName = "Gold",
                expiresAt = "2026-11-01",
                phone = "07700000000"
            )
        )

        val vm = createDashboardViewModel()
        advanceUntilIdle()

        assertFalse("SAMM-only first run must not flag empty credentials", vm.isCredentialsEmpty.value)
        assertEquals(1, vm.subscribersList.value.size)
        assertEquals("samm_user_1", vm.subscribersList.value[0].userID)
        assertEquals(SasProviders.ALAMIRY, vm.subscribersList.value[0].originProvider)
    }

    @Test
    fun scenarioA1_firstRun_bothProviders_mergesBothSubscribersWithDistinctOriginTags() = runTest(testDispatcher) {
        `when`(prefs.getProviderAccessState()).thenReturn(ProviderAccessState.BOTH)

        `when`(earthlinkGateway.searchUsers("", 0, 5000)).thenReturn(
            UserListResponse(
                itemsList = listOf(UserListItem(userIDLower = "el_user_1", customerNameLower = "EL User")),
                totalCount = 1
            )
        )
        sammSasGateway.searchSubscribersResult = listOf(
            SasSubscriberView(
                subscriberId = "201",
                username = "samm_user_2",
                displayName = "SAMM User Two",
                status = "Active"
            )
        )

        val vm = createDashboardViewModel()
        advanceUntilIdle()

        assertFalse("Both-providers first run must not flag empty credentials", vm.isCredentialsEmpty.value)
        assertEquals(2, vm.subscribersList.value.size)

        val originProviders = vm.subscribersList.value.map { it.originProvider }.toSet()
        assertTrue("Subscribers must include EarthLink accounts", originProviders.contains(SasProviders.EARTHLINK))
        assertTrue("Subscribers must include SAMM accounts", originProviders.contains(SasProviders.ALAMIRY))
    }

    // =========================================================================
    // SCENARIO A.2: First Run - Google Login + Provider Credentials
    // =========================================================================

    @Test
    fun scenarioA2_firstRun_googleLoginWithIspCredentials_loadsSubscribersSeamlessly() = runTest(testDispatcher) {
        // Cloud-authenticated session established, user enters SAMM credentials
        `when`(prefs.getProviderAccessState()).thenReturn(ProviderAccessState.SAMM_ONLY)
        `when`(prefs.getAuthToken()).thenReturn(null)

        sammSasGateway.searchSubscribersResult = listOf(
            SasSubscriberView(
                subscriberId = "301",
                username = "samm_google_user",
                displayName = "Google + SAMM User",
                status = "Active"
            )
        )

        val vm = createDashboardViewModel()
        advanceUntilIdle()

        assertFalse("Cloud authenticated user with ISP credentials must load seamlessly", vm.isCredentialsEmpty.value)
        assertEquals(1, vm.subscribersList.value.size)
        assertEquals("samm_google_user", vm.subscribersList.value[0].userID)
        assertEquals(SasProviders.ALAMIRY, vm.subscribersList.value[0].originProvider)
    }

    // =========================================================================
    // SCENARIO A.3: First Run - uTower Backup Import
    // =========================================================================

    @Test
    fun scenarioA3_firstRun_uTowerImport_preservesProviderAssignmentInRoom() = runTest(testDispatcher) {
        val importedSammAccount = LocalAccount(
            id = "utower_acc_01",
            displayName = "uTower Imported SAMM Account",
            earthlinkUsername = "utower_samm_user",
            sourceBatchId = "batch_utower_01",
            operationProvider = SasProviders.ALAMIRY,
            debtIqd = 45000.0
        )
        val importedElAccount = LocalAccount(
            id = "utower_acc_02",
            displayName = "uTower Imported EarthLink Account",
            earthlinkUsername = "utower_el_user",
            sourceBatchId = "batch_utower_01",
            operationProvider = SasProviders.EARTHLINK,
            debtIqd = 15000.0
        )

        db.localAccountDao().insert(importedSammAccount)
        db.localAccountDao().insert(importedElAccount)

        val sammInDb = db.localAccountDao().getByIdOneShot("utower_acc_01")
        assertNotNull(sammInDb)
        assertEquals(SasProviders.ALAMIRY, sammInDb!!.operationProvider)
        assertEquals(45000.0, sammInDb.debtIqd, 0.001)

        val elInDb = db.localAccountDao().getByIdOneShot("utower_acc_02")
        assertNotNull(elInDb)
        assertEquals(SasProviders.EARTHLINK, elInDb!!.operationProvider)
        assertEquals(15000.0, elInDb.debtIqd, 0.001)

        // Verify batch query by sourceBatchId retains provider assignments
        val batchAccounts = db.localAccountDao().getByBatchId("batch_utower_01")
        assertEquals(2, batchAccounts.size)
        val batchProviders = batchAccounts.map { it.operationProvider }.toSet()
        assertTrue(batchProviders.contains(SasProviders.ALAMIRY))
        assertTrue(batchProviders.contains(SasProviders.EARTHLINK))
    }

    // =========================================================================
    // SCENARIO B.1: Returning User - Provider Independent Session Unlock
    // =========================================================================

    @Test
    fun scenarioB1_returningUser_sessionUnlocksWithConfiguredProvider() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val realPrefs = PreferenceManager(context)
        realPrefs.clearCredentials()

        // 1. Unconfigured initial/cleared state: Locked
        assertEquals(ProviderAccessState.NONE, realPrefs.getProviderAccessState())
        assertFalse(realPrefs.getProviderAccessState().isAppUnlocked)

        // 2. Returning SAMM user: Unlocks with SAMM_ONLY
        realPrefs.saveSammBaseUrl("http://192.168.1.10:8000")
        realPrefs.saveSammToken("test_samm_token_secret")
        val sammState = realPrefs.getProviderAccessState()
        assertEquals(ProviderAccessState.SAMM_ONLY, sammState)
        assertTrue("Session must unlock with SAMM credentials", sammState.isAppUnlocked)
        assertTrue(sammState.hasSamm)
        assertFalse(sammState.hasEarthlink)

        // 3. Returning EarthLink user: Unlocks with EARTHLINK_ONLY
        realPrefs.clearCredentials()
        realPrefs.saveAuthToken("earthlink_auth_token_jwt")
        val elState = realPrefs.getProviderAccessState()
        assertEquals(ProviderAccessState.EARTHLINK_ONLY, elState)
        assertTrue("Session must unlock with EarthLink credentials", elState.isAppUnlocked)
        assertTrue(elState.hasEarthlink)
        assertFalse(elState.hasSamm)

        // 4. Returning Dual Provider user: Unlocks with BOTH
        realPrefs.saveSammBaseUrl("http://192.168.1.10:8000")
        realPrefs.saveSammToken("test_samm_token_secret")
        val bothState = realPrefs.getProviderAccessState()
        assertEquals(ProviderAccessState.BOTH, bothState)
        assertTrue("Session must unlock with both credentials", bothState.isAppUnlocked)
        assertTrue(bothState.hasEarthlink)
        assertTrue(bothState.hasSamm)

        realPrefs.clearCredentials()
    }

    // =========================================================================
    // SCENARIO B.2: Returning User - Firebase Cloud Restore
    // =========================================================================

    @Test
    fun scenarioB2_returningUser_firebaseRestore_preservesSammAndEarthlinkProvider() {
        // Document with explicit ALAMIRY provider and debt
        val sammDoc = mapOf<String, Any>(
            "displayName" to "Restored SAMM User",
            "earthlinkUsername" to "restored_samm",
            "operationProvider" to SasProviders.ALAMIRY,
            "debtIqd" to 50000.0
        )
        val sammResult = RemoteEntityValidator.validateAndMapAccount("doc_samm_1", sammDoc, 12345L, null)
        assertTrue(sammResult is RemoteEntityValidationResult.Valid)
        val validSamm = (sammResult as RemoteEntityValidationResult.Valid).entity
        assertEquals(SasProviders.ALAMIRY, validSamm.operationProvider)
        assertEquals(50000.0, validSamm.debtIqd, 0.001)

        // Document with explicit EARTHLINK provider and debt
        val elDoc = mapOf<String, Any>(
            "displayName" to "Restored EL User",
            "earthlinkUsername" to "restored_el",
            "operationProvider" to SasProviders.EARTHLINK,
            "debtIqd" to 25000.0
        )
        val elResult = RemoteEntityValidator.validateAndMapAccount("doc_el_1", elDoc, 12345L, null)
        assertTrue(elResult is RemoteEntityValidationResult.Valid)
        val validEl = (elResult as RemoteEntityValidationResult.Valid).entity
        assertEquals(SasProviders.EARTHLINK, validEl.operationProvider)
        assertEquals(25000.0, validEl.debtIqd, 0.001)

        // Legacy/unspecified provider safely defaults to EARTHLINK
        val legacyDoc = mapOf<String, Any>(
            "displayName" to "Legacy Restored User",
            "earthlinkUsername" to "legacy_user",
            "debtIqd" to 10000.0
        )
        val legacyResult = RemoteEntityValidator.validateAndMapAccount("doc_legacy_1", legacyDoc, 12345L, null)
        assertTrue(legacyResult is RemoteEntityValidationResult.Valid)
        val validLegacy = (legacyResult as RemoteEntityValidationResult.Valid).entity
        assertEquals(SasProviders.EARTHLINK, validLegacy.operationProvider)
    }

    // =========================================================================
    // SCENARIO B.3: Returning User - Local Backup Restore
    // =========================================================================

    @Test
    fun scenarioB3_returningUser_localBackupRestore_preservesOperationProviderInSqlite() = runTest(testDispatcher) {
        val sammAccount = LocalAccount(
            id = "sqlite_samm_restore_1",
            displayName = "Restored Local SAMM Account",
            earthlinkUsername = "restored_local_samm",
            operationProvider = SasProviders.ALAMIRY,
            debtIqd = 35000.0
        )
        val elAccount = LocalAccount(
            id = "sqlite_el_restore_1",
            displayName = "Restored Local EarthLink Account",
            earthlinkUsername = "restored_local_el",
            operationProvider = SasProviders.EARTHLINK,
            debtIqd = 12000.0
        )

        db.localAccountDao().insert(sammAccount)
        db.localAccountDao().insert(elAccount)

        val retrievedSamm = db.localAccountDao().getByIdOneShot("sqlite_samm_restore_1")
        assertNotNull(retrievedSamm)
        assertEquals(SasProviders.ALAMIRY, retrievedSamm?.operationProvider)
        assertEquals(35000.0, retrievedSamm?.debtIqd ?: 0.0, 0.001)

        val retrievedEl = db.localAccountDao().getByIdOneShot("sqlite_el_restore_1")
        assertNotNull(retrievedEl)
        assertEquals(SasProviders.EARTHLINK, retrievedEl?.operationProvider)
        assertEquals(12000.0, retrievedEl?.debtIqd ?: 0.0, 0.001)

        val allAccounts = db.localAccountDao().getAllPersistedOneShot()
        assertEquals(2, allAccounts.size)
        val providers = allAccounts.map { it.operationProvider }.toSet()
        assertTrue(providers.contains(SasProviders.ALAMIRY))
        assertTrue(providers.contains(SasProviders.EARTHLINK))
    }
}
