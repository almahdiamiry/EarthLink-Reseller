package com.example.ui.viewmodels

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.SasProviders
import com.example.core.network.SasGateway
import com.example.core.network.SasGatewayRouter
import com.example.core.network.SasOperationResult
import com.example.core.network.SasSubscriberView
import com.example.core.network.SasVerificationOutcome
import com.example.core.security.PreferenceManager
import com.example.data.repository.AuditRepositoryImpl
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.ui.viewmodels.EarthlinkSearchViewModelProviderRoutingTest.TestEarthlinkGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ProviderSwitchBackReadTest
 *
 * 1. Claim:
 *    Switching a subscriber account's provider ALAMIRY -> EARTHLINK must re-read the subscriber
 *    from the EARTHLINK gateway. The rendered account type (UserDetail.packageName) and the
 *    persisted LocalAccount.packageName / ispUserIndex must reflect EARTHLINK only.
 *    When EARTHLINK holds the subscriber under a re-created identity, the stale EARTHLINK index
 *    must be re-bound from the username-resolved EARTHLINK record.
 *    When EARTHLINK holds no record at all, the UI must stop presenting the ALAMIRY plan as the
 *    current EARTHLINK account type (historical Room rows and ledger are untouched - INV-02).
 *
 * 2. Seam / Environment:
 *    ROBOLECTRIC tier, in-memory Room [AppDatabase] with the real [LocalAccountRepositoryImpl]
 *    and [LocalLedgerRepositoryImpl], and an id-addressed [SasGateway] double per provider.
 *
 * 3. Independent Oracle:
 *    Each gateway is seeded with a provider-exclusive literal plan name ("EARTHLINK-ONLY-PLAN" /
 *    "ALAMIRY-UNIQUE-TYPE") for the SAME username. Only the literal belonging to the currently
 *    selected provider can ever be correct, so no production expression is mirrored in the oracle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ProviderSwitchBackReadTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var prefs: PreferenceManager
    private lateinit var accountRepo: LocalAccountRepositoryImpl
    private lateinit var ledgerRepo: LocalLedgerRepositoryImpl
    private lateinit var auditRepo: AuditRepositoryImpl

    private lateinit var earthSas: IdAddressedGateway
    private lateinit var sammSas: IdAddressedGateway
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
            // PreferenceManager is backed by a file that outlives a single Robolectric method,
            // so every test must declare its own starting provider.
            setLastSelectedLoginProvider(SasProviders.EARTHLINK)
        }

        accountRepo = LocalAccountRepositoryImpl(db, db.localAccountDao(), db.syncOutboxDao())
        ledgerRepo = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = db.localLedgerEntryDao(),
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao(),
            pendingDao = db.pendingExternalOperationDao()
        )
        auditRepo = AuditRepositoryImpl(db, db.auditLogDao())

        earthSas = IdAddressedGateway(SasProviders.EARTHLINK)
        sammSas = IdAddressedGateway(SasProviders.ALAMIRY)

        viewModel = EarthlinkSearchViewModel(
            gateway = TestEarthlinkGateway(),
            sasGatewayRouter = SasGatewayRouter(earthSas, sammSas),
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

    @Test
    fun switchingBackToEarthlink_rendersEarthlinkPlan_evenWhenEarthlinkIdentityWasRecreated() = runTest {
        seedAlamiryOperatedAccount()

        // The subscriber returned to EarthLink under a NEW identity (42 was the pre-departure row).
        earthSas.byId["900"] = subscriber("900", "EARTHLINK-ONLY-PLAN", planId = "3")
        earthSas.searchCorpus = listOf(subscriber("900", "EARTHLINK-ONLY-PLAN", planId = "3"))

        viewModel.setSelectedProvider(SasProviders.ALAMIRY)
        viewModel.loadUserDetail(3001, "user1").join()
        awaitCondition { viewModel.selectedUser.value?.packageName == "ALAMIRY-UNIQUE-TYPE" }

        viewModel.updateAccountProvider(roomAccount(), SasProviders.EARTHLINK).join()

        awaitCondition {
            viewModel.selectedUser.value?.packageName == "EARTHLINK-ONLY-PLAN" &&
                accountRepo.getAccountByIdOneShot("acc_user1")?.packageName == "EARTHLINK-ONLY-PLAN"
        }

        val detail = viewModel.selectedUser.value
        assertEquals(
            "diagnostic: el=${earthSas.callLog} samm=${sammSas.callLog}",
            "EARTHLINK-ONLY-PLAN",
            detail?.packageName
        )
        assertEquals(SasProviders.EARTHLINK, detail?.originProvider)
        assertEquals("The resurrected EARTHLINK identity must win over the pre-departure index", 900, detail?.userIndex)

        val persisted = accountRepo.getAccountByIdOneShot("acc_user1")
        assertEquals("EARTHLINK-ONLY-PLAN", persisted?.packageName)
        assertEquals(SasProviders.EARTHLINK, persisted?.operationProvider)
        // INV-09: the local ISP identity guard is untouched by a plan refresh.
        assertEquals(42, persisted?.ispUserIndex)
    }

    @Test
    fun switchingBackToEarthlink_rendersEarthlinkPlan_whenOriginalIndexStillValid() = runTest {
        seedAlamiryOperatedAccount()

        earthSas.byId["42"] = subscriber("42", "EARTHLINK-ONLY-PLAN", planId = "3")

        viewModel.setSelectedProvider(SasProviders.ALAMIRY)
        viewModel.loadUserDetail(3001, "user1").join()
        awaitCondition { viewModel.selectedUser.value?.packageName == "ALAMIRY-UNIQUE-TYPE" }

        viewModel.updateAccountProvider(roomAccount(), SasProviders.EARTHLINK).join()

        awaitCondition {
            accountRepo.getAccountByIdOneShot("acc_user1")?.packageName == "EARTHLINK-ONLY-PLAN"
        }

        assertEquals("EARTHLINK-ONLY-PLAN", viewModel.selectedUser.value?.packageName)

        val persisted = accountRepo.getAccountByIdOneShot("acc_user1")
        assertEquals("EARTHLINK-ONLY-PLAN", persisted?.packageName)
        assertEquals(42, persisted?.ispUserIndex)
    }

    @Test
    fun switchingBackToEarthlink_dropsAlamiryPlanFromDisplay_whenEarthlinkHasNoRecord() = runTest {
        seedAlamiryOperatedAccount()
        // EarthLink gateway returns nothing for this username.
        earthSas.byId.clear()
        earthSas.searchCorpus = emptyList()

        viewModel.setSelectedProvider(SasProviders.ALAMIRY)
        viewModel.loadUserDetail(3001, "user1").join()
        awaitCondition { viewModel.selectedUser.value?.packageName == "ALAMIRY-UNIQUE-TYPE" }

        viewModel.updateAccountProvider(roomAccount(), SasProviders.EARTHLINK).join()

        // Wait for the fire-and-forget refresh to actually republish, not for a proxy that may
        // already be satisfied while the read is still in flight.
        val before = viewModel.selectedUser.value
        awaitDetailRepublished(before)

        val detail = viewModel.selectedUser.value
        assertNotNull("The subscriber container must survive an empty provider answer", detail)
        assertNull(
            "A plan that only exists on ALAMIRY must not be presented as the EARTHLINK account type. " +
                "diagnostic: el=${earthSas.callLog} samm=${sammSas.callLog} sel=${viewModel.selectedProvider.value}",
            detail?.packageName
        )
        assertEquals(SasProviders.EARTHLINK, detail?.originProvider)

        // INV-02: the local container and its history are preserved, only the provider tag moved.
        assertNotNull(
            "The local account must survive a provider switch",
            accountRepo.getAccountByIdOneShot("acc_user1")
        )
    }

    @Test
    fun reenteringUserPage_readsTheAccountsOwnProvider_notTheTappedSearchRow() = runTest {
        // The account belongs to EARTHLINK...
        db.localAccountDao().insert(
            roomAccount().copy(operationProvider = SasProviders.EARTHLINK)
        )
        earthSas.byId["42"] = subscriber("42", "EARTHLINK-ONLY-PLAN", planId = "3")
        sammSas.byId["3001"] = subscriber("3001", "ALAMIRY-UNIQUE-TYPE", planId = "9")

        // ...but the operator reaches it from a search/dashboard row that is SAMM-scoped, which is
        // what MainActivity hands to prepareUserDetail before navigating.
        val sammSearchRow = com.example.core.model.UserListItem(
            userIndexLower = 3001,
            userIDLower = "user1",
            customerNameLower = "User One",
            accountNameLower = "ALAMIRY-UNIQUE-TYPE",
            originProvider = SasProviders.ALAMIRY
        )

        viewModel.setSelectedProvider(SasProviders.ALAMIRY)
        viewModel.prepareUserDetail(3001, sammSearchRow)
        assertEquals("ALAMIRY-UNIQUE-TYPE", viewModel.selectedUser.value?.packageName)

        // Entering the page re-resolves the detail, exactly as UserDetailScreenV2 does on entry.
        val before = viewModel.selectedUser.value
        viewModel.loadUserDetail(3001, "user1").join()
        awaitDetailRepublished(before)

        val detail = viewModel.selectedUser.value
        assertEquals(
            "The detail must come from the account's own provider. diagnostic: el=${earthSas.callLog} samm=${sammSas.callLog}",
            "EARTHLINK-ONLY-PLAN",
            detail?.packageName
        )
        assertEquals(
            "The detail and the provider badge must be driven by the same authority",
            SasProviders.EARTHLINK,
            detail?.originProvider
        )
    }

    @Test
    fun selectedProvider_survivesViewModelRecreation() = runTest {
        viewModel.setSelectedProvider(SasProviders.ALAMIRY)

        val recreated = EarthlinkSearchViewModel(
            gateway = TestEarthlinkGateway(),
            sasGatewayRouter = SasGatewayRouter(earthSas, sammSas),
            audit = auditRepo,
            prefs = prefs,
            localAccountRepository = accountRepo,
            localLedgerRepository = ledgerRepo,
            syncRepo = null
        )

        assertEquals(
            "The operator's provider choice must survive leaving the page, and closing/reopening the app",
            SasProviders.ALAMIRY,
            recreated.selectedProvider.value
        )
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun subscriber(id: String, planName: String, planId: String) = SasSubscriberView(
        subscriberId = id,
        username = "user1",
        displayName = "User One",
        status = "Active",
        planId = planId,
        planName = planName,
        expiresAt = "2026-12-01",
        phone = "07700000001"
    )

    private fun roomAccount() = LocalAccount(
        id = "acc_user1",
        earthlinkUsername = "user1",
        displayName = "User One",
        ispUserIndex = 42,
        ispSubscriberId = "3001",
        packageName = "ALAMIRY-UNIQUE-TYPE",
        operationProvider = SasProviders.ALAMIRY
    )

    private suspend fun seedAlamiryOperatedAccount() {
        db.localAccountDao().insert(roomAccount())
        sammSas.byId["3001"] = subscriber("3001", "ALAMIRY-UNIQUE-TYPE", planId = "9")
    }

    private suspend fun awaitDetailRepublished(before: Any?) =
        awaitCondition { viewModel.selectedUser.value !== before }

    private suspend fun awaitCondition(timeoutMs: Long = 8_000, predicate: suspend () -> Boolean) {
        // Thread.sleep, not delay: runTest advances delay() on VIRTUAL time, so a delay-based poll
        // would spin through the whole timeout in microseconds of real time and assert while the
        // Dispatchers.IO read is still in flight. The work being awaited runs on real threads.
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(25)
        }
    }

    /**
     * Minimal id-addressed read double. Mutating operations are not exercised by these read-path
     * claims and fail loudly if they are ever reached by accident.
     */
    private class IdAddressedGateway(override val providerName: String) : SasGateway {
        val byId = mutableMapOf<String, SasSubscriberView>()
        var searchCorpus: List<SasSubscriberView> = emptyList()
        var getSubscriberCalls = 0
        val callLog = mutableListOf<String>()

        override suspend fun getSubscriber(subscriberId: String): SasSubscriberView? {
            getSubscriberCalls++
            val hit = byId[subscriberId]
            callLog += "getSubscriber($subscriberId)->${hit?.planName ?: "null"}"
            return hit
        }

        override suspend fun searchSubscribers(query: String, page: Int, pageSize: Int): List<SasSubscriberView> {
            val out = if (query.isBlank()) searchCorpus else searchCorpus.filter { it.username.equals(query.trim(), ignoreCase = true) }
            callLog += "searchSubscribers('$query')->${out.map { it.subscriberId }}"
            return out
        }

        override suspend fun createSubscriber(
            username: String, planId: String, password: String, firstName: String, lastName: String, phone: String?
        ): SasOperationResult = unsupported()

        override suspend fun suspendSubscriber(subscriberId: String): SasOperationResult = unsupported()
        override suspend fun activateSubscriber(subscriberId: String): SasOperationResult = unsupported()
        override suspend fun renewSubscriber(subscriberId: String): SasOperationResult = unsupported()
        override suspend fun changePlan(subscriberId: String, newPlanId: String): SasOperationResult = unsupported()
        override suspend fun changePassword(subscriberId: String, newPassword: String): SasOperationResult = unsupported()
        override suspend fun verifyOperationOutcome(
            operationType: String, targetIdentifier: String, baselineEvidence: String?
        ): SasVerificationOutcome = SasVerificationOutcome.VERIFIED_SUCCESS

        override suspend fun checkConnection(): Boolean = true

        private fun unsupported(): SasOperationResult =
            throw UnsupportedOperationException("mutations are out of scope for these read-path claims")
    }
}