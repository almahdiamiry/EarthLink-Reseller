package com.example.ui.viewmodels

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.SasProviders
import com.example.core.network.EarthlinkBusinessException
import com.example.core.network.SasAuthException
import com.example.core.network.SasGateway
import com.example.core.network.SasGatewayException
import com.example.core.network.SasGatewayRouter
import com.example.core.network.SasInconclusiveException
import com.example.core.network.SasOperationResult
import com.example.core.network.SasSubscriberPresence
import com.example.core.network.SasSubscriberView
import com.example.core.network.SasTransportException
import com.example.core.network.SasVerificationOutcome
import com.example.core.security.PreferenceManager
import com.example.data.repository.AuditRepositoryImpl
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.EarthlinkGateway
import com.example.domain.repository.SyncProgress
import com.example.domain.repository.SyncReason
import com.example.domain.repository.SyncRepository
import com.example.domain.repository.SyncStatusState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ProviderPresenceCheckTest
 *
 * `checkProviderPresence` gates a provider-neutral action in the "Change provider" dialog. It shipped
 * in this branch as the ONLY user-visible behaviour restriction the work added, with zero tests.
 *
 * Its failure mode is a lie, not a crash: collapsing a failed lookup into a confirmed absence makes
 * the dialog assert that a subscriber is on neither ISP while the device is simply offline.
 *
 * Core Triad:
 * 1. Claim - presence is three-valued. A provider that could not be asked reports UNKNOWN and never
 *    ABSENT; only a provider that was successfully asked and had no such row reports ABSENT.
 * 2. Seam - ROBOLECTRIC tier, real in-memory Room repositories plus scripted [SasGateway] doubles.
 *    The doubles never consult Room, so any result is attributable to the presence logic itself.
 * 3. Oracle - the three enum constants are the specification, asserted one at a time against three
 *    mutually exclusive fixtures (one row / zero rows / a thrown transport failure). No expected
 *    value is derived from the production expression.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ProviderPresenceCheckTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var prefs: PreferenceManager
    private lateinit var viewModel: EarthlinkSearchViewModel

    private lateinit var earthlinkSas: ScriptedGateway
    private lateinit var sammSas: ScriptedGateway

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        prefs = PreferenceManager(context).apply {
            setDemoMode(false)
            saveIspAdminUsername("presence_admin")
            saveIspAdminPassword("presence_pass")
            setLastSelectedLoginProvider(SasProviders.EARTHLINK)
        }

        val accountRepo = LocalAccountRepositoryImpl(db, db.localAccountDao(), db.syncOutboxDao())
        val ledgerRepo = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = db.localLedgerEntryDao(),
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao(),
            pendingDao = db.pendingExternalOperationDao()
        )

        earthlinkSas = ScriptedGateway(SasProviders.EARTHLINK)
        sammSas = ScriptedGateway(SasProviders.ALAMIRY)

        viewModel = EarthlinkSearchViewModel(
            gateway = NoOpEarthlinkGateway(),
            sasGatewayRouter = SasGatewayRouter(earthlinkSas, sammSas),
            audit = AuditRepositoryImpl(db, db.auditLogDao()),
            prefs = prefs,
            localAccountRepository = accountRepo,
            localLedgerRepository = ledgerRepo,
            syncRepo = NoOpSyncRepository()
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    /**
     * Claim: a subscriber carried by BOTH providers reports PRESENT for both.
     * Seam: ROBOLECTRIC, scripted gateway doubles.
     * Independent Oracle: Both fixtures hold the exact literal row, so PRESENT / PRESENT is the only
     *        outcome consistent with two directories that both returned it.
     */
    @Test
    fun subscriberOnBothProviders_reportsPresentForBoth() = runTest {
        earthlinkSas.rows = listOf(subscriberRow("20562240", "moved_user@sacx"))
        sammSas.rows = listOf(subscriberRow("31415", "moved_user@sacx"))

        val presence = viewModel.checkProviderPresence("moved_user@sacx")

        assertEquals(SasSubscriberPresence.PRESENT, presence[SasProviders.EARTHLINK])
        assertEquals(SasSubscriberPresence.PRESENT, presence[SasProviders.ALAMIRY])
    }

    /**
     * Claim: a provider that was successfully asked and had no such row reports ABSENT, and only
     *        that provider does.
     * Seam: ROBOLECTRIC, scripted gateway doubles.
     * Independent Oracle: SAMM's fixture is an empty directory; EarthLink's holds the row. The only
     *        correct pair is PRESENT / ABSENT.
     */
    @Test
    fun subscriberOnOneProviderOnly_reportsAbsentOnlyForTheOther() = runTest {
        earthlinkSas.rows = listOf(subscriberRow("20562240", "moved_user@sacx"))
        sammSas.rows = emptyList()

        val presence = viewModel.checkProviderPresence("moved_user@sacx")

        assertEquals(SasSubscriberPresence.PRESENT, presence[SasProviders.EARTHLINK])
        assertEquals(SasSubscriberPresence.ABSENT, presence[SasProviders.ALAMIRY])
    }

    /**
     * Claim: a provider that ERRORS reports UNKNOWN, never ABSENT. This is the defect under test: the
     *        old implementation caught every exception and returned false, so an offline device or an
     *        expired token greyed out both rows and the dialog stated the subscriber is on neither ISP.
     * Seam: ROBOLECTRIC, scripted gateway doubles that throw on lookup.
     * Independent Oracle: Both gateways are scripted to fail. The only correct answer for either is
     *        UNKNOWN; an implementation mapping the catch branch to ABSENT fails both assertions.
     */
    @Test
    fun lookupFailure_reportsUnknownAndNeverAbsent() = runTest {
        earthlinkSas.lookupFailure = SasTransportException("ISP unreachable")
        sammSas.lookupFailure = SasTransportException("ISP unreachable")

        val presence = viewModel.checkProviderPresence("moved_user@sacx")

        assertEquals(SasSubscriberPresence.UNKNOWN, presence[SasProviders.EARTHLINK])
        assertEquals(SasSubscriberPresence.UNKNOWN, presence[SasProviders.ALAMIRY])
        assertNotEquals(
            "A failed lookup must never be rendered as a confirmed absence",
            SasSubscriberPresence.ABSENT,
            presence[SasProviders.EARTHLINK]
        )
        assertNotEquals(
            "A failed lookup must never be rendered as a confirmed absence",
            SasSubscriberPresence.ABSENT,
            presence[SasProviders.ALAMIRY]
        )
    }

    /**
     * Claim: the three states stay distinguishable when only ONE provider fails. A mixed answer is
     *        the realistic offline case and is the one that produced "on neither provider".
     * Seam: ROBOLECTRIC, scripted gateway doubles.
     * Independent Oracle: EarthLink's fixture has the row; SAMM throws. Only (PRESENT, UNKNOWN)
     *        describes that world. Notably UNKNOWN, not ABSENT: SAMM was never successfully asked.
     */
    @Test
    fun oneProviderFailsWhileTheOtherAnswers_keepsTheFailureDistinctFromAbsence() = runTest {
        earthlinkSas.rows = listOf(subscriberRow("20562240", "moved_user@sacx"))
        sammSas.lookupFailure = SasTransportException("SAMM unreachable")

        val presence = viewModel.checkProviderPresence("moved_user@sacx")

        assertEquals(SasSubscriberPresence.PRESENT, presence[SasProviders.EARTHLINK])
        assertEquals(SasSubscriberPresence.UNKNOWN, presence[SasProviders.ALAMIRY])
    }

    /**
     * Claim: an HTTP 5xx and an expired token are both UNKNOWN, not ABSENT. safeApiCall throws on any
     *        non-2xx, so the old catch-all returned false for 401 and 500 alike - the two cases an
     *        offline or logged-out device actually produces.
     * Seam: ROBOLECTRIC, scripted gateway doubles.
     * Independent Oracle: EarthLink is scripted with the literal HTTP 500 reason "directory exploded"
     *        and SAMM with the literal 401 reason "token expired". Only UNKNOWN describes either, and
     *        SAMM still answers PRESENT for its own row, so the two providers are judged separately.
     */
    @Test
    fun serverErrorOnOneProviderAndSuccessOnTheOther_reportsUnknownForTheFailureOnly() = runTest {
        earthlinkSas.rows = listOf(subscriberRow("20562240", "moved_user@sacx"))
        earthlinkSas.lookupFailure = SasInconclusiveException(statusCode = 500, message = "directory exploded")
        sammSas.rows = listOf(subscriberRow("31415", "moved_user@sacx"))
        sammSas.lookupFailure = SasAuthException("token expired")

        val presence = viewModel.checkProviderPresence("moved_user@sacx")

        assertEquals(
            "A 500 establishes nothing about the subscriber",
            SasSubscriberPresence.UNKNOWN,
            presence[SasProviders.EARTHLINK]
        )
        assertEquals(
            "An expired token establishes nothing about the subscriber",
            SasSubscriberPresence.UNKNOWN,
            presence[SasProviders.ALAMIRY]
        )
    }

    /**
     * Claim: presence is read through the seam's own directory lookup, never through a short paged
     *        search. The previous implementation called
     *        searchSubscribers(query, page = 1, pageSize = 2); EarthLink forwards pageSize to the API's
     *        rowCount and returns newest-first, so an existing subscriber older than two newer
     *        substring matches fell out of the response and was reported absent.
     * Seam: ROBOLECTRIC, scripted gateway doubles that record every call they receive.
     * Independent Oracle: Zero searchSubscribers calls is the only outcome consistent with the seam
     *        lookup being used. Reintroducing the paged search makes the counters non-zero and fails
     *        here, which is what a window-2 search cannot be caught by any other assertion here.
     */
    @Test
    fun presence_isReadThroughTheSeamLookup_andNotThroughAShortPagedSearch() = runTest {
        earthlinkSas.rows = listOf(subscriberRow("20562240", "moved_user@sacx"))
        sammSas.rows = listOf(subscriberRow("31415", "moved_user@sacx"))

        viewModel.checkProviderPresence("moved_user@sacx")

        assertEquals(
            "The presence check must go through resolveSubscriberPresence, not a paged search",
            0,
            earthlinkSas.searchSubscribersCalls
        )
        assertEquals(0, sammSas.searchSubscribersCalls)
        assertEquals(1, earthlinkSas.presenceCalls)
        assertEquals(1, sammSas.presenceCalls)
    }

    /**
     * Claim: the username that reached the seam is the one that was asked for, on both providers.
     * Seam: ROBOLECTRIC, scripted gateway doubles.
     * Independent Oracle: The literal trimmed username is compared against what each gateway received.
     */
    @Test
    fun presence_asksEachProviderForTheExactUsername() = runTest {
        earthlinkSas.rows = listOf(subscriberRow("20562240", "moved_user@sacx"))
        sammSas.rows = listOf(subscriberRow("31415", "moved_user@sacx"))

        viewModel.checkProviderPresence("  moved_user@sacx  ")

        assertEquals("moved_user@sacx", earthlinkSas.lastQueriedUsername)
        assertEquals("moved_user@sacx", sammSas.lastQueriedUsername)
    }

    /**
     * Claim: a blank username asks nothing and reports nothing.
     * Seam: ROBOLECTRIC, scripted gateway doubles.
     * Independent Oracle: No gateway may be consulted at all; zero lookup calls is the only outcome
     *        consistent with "there was nothing to look up".
     */
    @Test
    fun blankUsername_asksNoProvider() = runTest {
        val presence = viewModel.checkProviderPresence("   ")

        assertEquals(emptyMap<String, SasSubscriberPresence>(), presence)
        assertEquals(0, earthlinkSas.presenceCalls)
        assertEquals(0, sammSas.presenceCalls)
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun subscriberRow(id: String, username: String) = SasSubscriberView(
        subscriberId = id,
        username = username,
        displayName = "Moved Person",
        status = "Active",
        planId = "3",
        planName = "Economy",
        expiresAt = "2026-12-01",
        phone = "07700000001"
    )

    /**
     * A gateway double whose only moving part is the directory read. Presence is derived from [rows]
     * when no [lookupFailure] is scripted, so each test's fixture states the world it is describing,
     * and every call is counted so a test can prove which seam member the ViewModel actually used.
     */
    private class ScriptedGateway(override val providerName: String) : SasGateway {
        var rows: List<SasSubscriberView> = emptyList()
        var lookupFailure: SasGatewayException? = null
        var presenceCalls = 0
        var searchSubscribersCalls = 0
        var lastQueriedUsername: String? = null

        override suspend fun resolveSubscriberPresence(username: String): SasSubscriberPresence {
            presenceCalls++
            lastQueriedUsername = username
            lookupFailure?.let { throw it }
            val key = username.trim()
            return if (rows.any { it.username.equals(key, ignoreCase = true) }) {
                SasSubscriberPresence.PRESENT
            } else {
                SasSubscriberPresence.ABSENT
            }
        }

        override suspend fun getSubscriber(username: String): SasSubscriberView? =
            rows.firstOrNull { it.username.equals(username.trim(), ignoreCase = true) }

        override suspend fun searchSubscribers(query: String, page: Int, pageSize: Int): List<SasSubscriberView> {
            searchSubscribersCalls++
            return rows.filter { it.username.contains(query.trim(), ignoreCase = true) }
        }

        override suspend fun createSubscriber(
            username: String, planId: String, password: String, firstName: String, lastName: String, phone: String?
        ): SasOperationResult = unsupported()

        override suspend fun suspendSubscriber(username: String): SasOperationResult = unsupported()
        override suspend fun activateSubscriber(username: String): SasOperationResult = unsupported()
        override suspend fun renewSubscriber(username: String): SasOperationResult = unsupported()
        override suspend fun changePlan(username: String, newPlanId: String): SasOperationResult = unsupported()
        override suspend fun changePassword(username: String, newPassword: String): SasOperationResult = unsupported()
        override suspend fun verifyOperationOutcome(
            operationType: String, targetIdentifier: String, baselineEvidence: String?
        ): SasVerificationOutcome = SasVerificationOutcome.INCONCLUSIVE

        override suspend fun checkConnection(): Boolean = true

        private fun unsupported(): SasOperationResult = throw IllegalStateException("out of scope for presence claims")
    }

    /**
     * The legacy EarthlinkGateway is not on the presence path; it exists only because the ViewModel
     * requires one, and it fails loudly if anything ever reaches it.
     */
    private class NoOpEarthlinkGateway : EarthlinkGateway {
        private fun unreachable(): Nothing =
            throw IllegalStateException("The legacy EarthlinkGateway must not be reached by presence checks")

        override suspend fun login(username: String, password: String): com.example.core.model.LoginResponse = unreachable()
        override suspend fun getBalance(): Double = unreachable()
        override suspend fun getTestUsersCount(affiliateIndex: Int?): Int = unreachable()
        override suspend fun getActiveTestUsersCount(): Int = unreachable()
        override suspend fun getPrepaidNeeded(): Double = unreachable()
        override suspend fun getPackages(): List<com.example.core.model.AccountPackage> = unreachable()
        override suspend fun getAccountCost(accountIndex: Int): Double = unreachable()
        override suspend fun getUserDetail(userIndex: Int): com.example.core.model.UserDetail = unreachable()
        override suspend fun searchUsers(query: String, startIndex: Int, rowCount: Int): com.example.core.model.UserListResponse = unreachable()
        override suspend fun checkUsernameAvailable(userId: String): Boolean = unreachable()
        override suspend fun autocompleteUser(query: String): List<com.example.core.model.AutocompleteUser> = unreachable()
        override suspend fun checkCustomerByPhone(phone: String): String? = unreachable()
        override suspend fun createCustomer(name: String, phone: String): Boolean = unreachable()
        override suspend fun createTestUser(username: String, phone: String, fullName: String, accountIndex: Int): String? = unreachable()
        override suspend fun createUserUsingDeposit(username: String, phone: String, fullName: String, accountIndex: Int, depositPassword: String): String? = unreachable()
        override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean = unreachable()
        override suspend fun toggleUserActive(userIndex: Int, active: Boolean): Boolean = unreachable()
        override suspend fun changeAccountType(userIndex: Int, userId: String, accountIndex: Int): Boolean = unreachable()
        override suspend fun extendUser(userIndex: Int): Boolean = unreachable()
        override suspend fun getAccountStatement(startIndex: Int, rowCount: Int, query: String): List<com.example.core.model.AccountStatementItem> = unreachable()
        override suspend fun showUserPassword(userIndex: Int, userId: String): String = unreachable()
        override suspend fun showAccountPassword(userIndex: Int, userId: String): String = unreachable()
        override suspend fun changeAccountPassword(userIndex: Int, userId: String, newPass: String): Boolean = unreachable()
        override suspend fun changeUserPassword(userIndex: Int, userId: String, newPass: String): Boolean = unreachable()
        override suspend fun updateUserDisplayName(userIndex: Int, newName: String): Boolean = unreachable()
    }

    private class NoOpSyncRepository : SyncRepository {
        private val state = MutableStateFlow<SyncStatusState>(SyncStatusState.IDLE)
        override val syncState: StateFlow<SyncStatusState> = state.asStateFlow()
        override val syncProgress: StateFlow<SyncProgress> = MutableStateFlow(SyncProgress()).asStateFlow()
        override fun triggerSync() = Unit
        override fun setupPeriodicSync() = Unit
        override suspend fun triggerSyncOneShot(): Boolean = true
        override fun requestSync(reason: SyncReason) = Unit
        override fun triggerSettingsSync(uid: String?, reason: String) = Unit
        override suspend fun getPendingOutboxCount(): Int = 0
        override suspend fun getFailedCount(): Int = 0
        override suspend fun retryFailedItems(): Int = 0
        override suspend fun anonymousSignIn(): String? = null
        override suspend fun emailSignIn(email: String, password: String): String? = null
        override suspend fun googleSignIn(idToken: String): String? = null
        override fun getFirebaseUid(): String? = null
        override suspend fun signOut(force: Boolean, clearData: Boolean) = Unit
    }
}