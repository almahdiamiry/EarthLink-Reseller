package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.*
import com.example.core.security.PreferenceManager
import com.example.core.sync.IspDisappearanceReconciler
import com.example.core.sync.RemoteEntityValidationResult
import com.example.core.sync.RemoteEntityValidator
import com.example.core.sync.SubscriberMatcher
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.*
import com.example.ui.viewmodels.DashboardViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verification suite for RC-03 and RC-04:
 * - Separate lifecycle state for isHistoryOnlySubscriber (decoupled from isLegacy).
 * - Monotonic transition for isHistoryOnlySubscriber.
 * - ISP disappearance algorithm using earthlinkUsername <-> userID mapping.
 * - Exclusion of null/blank earthlinkUsername accounts.
 * - Incomplete ISP fetch safety (abort on partial/failed data).
 * - Production wiring via LocalAccountRepository and DashboardViewModel (R6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase5IspLifecycleAndHistoryOnlyTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var accountRepo: LocalAccountRepositoryImpl
    private lateinit var ledgerRepo: LocalLedgerRepositoryImpl
    private lateinit var fakeGateway: FakeGateway
    private lateinit var audit: AuditRepository
    private lateinit var syncRepo: SyncRepository
    private lateinit var prefs: PreferenceManager
    private lateinit var viewModel: DashboardViewModel

    private class FakeGateway(
        var searchUsersResult: UserListResponse = UserListResponse(itemsList = emptyList(), totalCount = 0)
    ) : EarthlinkGateway {
        override suspend fun login(username: String, password: String): LoginResponse = throw NotImplementedError()
        override suspend fun getBalance(): Double = 100000.0
        override suspend fun getTestUsersCount(affiliateIndex: Int?): Int = 0
        override suspend fun getActiveTestUsersCount(): Int = 0
        override suspend fun getPrepaidNeeded(): Double = 0.0
        override suspend fun getPrepaidNeeded(days: Int): Double = 0.0
        override suspend fun getPackages(): List<AccountPackage> = emptyList()
        override suspend fun getAccountCost(accountIndex: Int): Double = 0.0
        override suspend fun searchUsers(query: String, startIndex: Int, rowCount: Int): UserListResponse = searchUsersResult
        override suspend fun getUserDetail(userIndex: Int): UserDetail = throw NotImplementedError()
        override suspend fun autocompleteUser(query: String): List<AutocompleteUser> = emptyList()
        override suspend fun checkUsernameAvailable(userId: String): Boolean = true
        override suspend fun checkCustomerByPhone(phone: String): String? = null
        override suspend fun createCustomer(name: String, phone: String): Boolean = true
        override suspend fun createTestUser(username: String, phone: String, fullName: String, accountIndex: Int): String? = null
        override suspend fun createUserUsingDeposit(username: String, phone: String, fullName: String, accountIndex: Int, depositPassword: String): String? = null
        override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean = true
        override suspend fun extendUser(userIndex: Int): Boolean = true
        override suspend fun getAccountStatement(startIndex: Int, rowCount: Int, query: String): List<AccountStatementItem> = emptyList()
        override suspend fun showUserPassword(userIndex: Int, userId: String): String = ""
        override suspend fun showAccountPassword(userIndex: Int, userId: String): String = ""
        override suspend fun changeUserPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
        override suspend fun changeAccountPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
        override suspend fun toggleUserActive(userIndex: Int, active: Boolean): Boolean = true
        override suspend fun changeAccountType(userIndex: Int, userId: String, accountIndex: Int): Boolean = true
        override suspend fun updateUserDisplayName(userIndex: Int, newName: String): Boolean = true
    }

    @Before
    fun setUp() {
        org.robolectric.shadows.ShadowLog.stream = System.out
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        accountRepo = LocalAccountRepositoryImpl(
            database = database,
            accountDao = database.localAccountDao(),
            outboxDao = database.syncOutboxDao()
        )
        ledgerRepo = LocalLedgerRepositoryImpl(
            database = database,
            ledgerDao = database.localLedgerEntryDao(),
            accountDao = database.localAccountDao(),
            outboxDao = database.syncOutboxDao(),
            pendingDao = database.pendingExternalOperationDao()
        )
        fakeGateway = FakeGateway()
        audit = mock(AuditRepository::class.java)
        syncRepo = mock(SyncRepository::class.java)
        prefs = mock(PreferenceManager::class.java)

        `when`(syncRepo.syncState).thenReturn(MutableStateFlow(SyncStatusState.IDLE))
        `when`(syncRepo.syncProgress).thenReturn(MutableStateFlow(SyncProgress()))
        `when`(prefs.getDemoMode()).thenReturn(false)
        `when`(prefs.getAuthToken()).thenReturn("mock_token")
        `when`(prefs.getIspAdminUsername()).thenReturn("admin_user")
        `when`(prefs.getIspAdminPassword()).thenReturn("admin_pass")
        `when`(prefs.getDashboardSortOption()).thenReturn("name")

        viewModel = DashboardViewModel(
            gateway = fakeGateway,
            audit = audit,
            localAccountRepository = accountRepo,
            localLedgerRepository = ledgerRepo,
            syncRepo = syncRepo,
            prefs = prefs,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun testIspDisappearance_marksActiveSubscriberAsHistoryOnly() = runBlocking {
        val acc1 = LocalAccount(
            id = "acc_active_1",
            displayName = "Active User 1",
            earthlinkUsername = "user_active_1",
            isHistoryOnlySubscriber = false
        )
        val acc2 = LocalAccount(
            id = "acc_active_2",
            displayName = "Active User 2",
            earthlinkUsername = "user_active_2",
            isHistoryOnlySubscriber = false
        )
        database.localAccountDao().insert(acc1)
        database.localAccountDao().insert(acc2)

        // ISP fetch contains only user_active_1 (user_active_2 disappeared)
        val authoritativeIsp = setOf("user_active_1")
        val transitioned = IspDisappearanceReconciler.reconcile(
            database = database,
            accountDao = database.localAccountDao(),
            auditDao = database.auditLogDao(),
            authoritativeIspUserIds = authoritativeIsp,
            isFetchComplete = true
        )

        assertEquals(1, transitioned.size)
        assertEquals("acc_active_2", transitioned[0])

        val updatedAcc1 = database.localAccountDao().getByIdOneShot("acc_active_1")
        val updatedAcc2 = database.localAccountDao().getByIdOneShot("acc_active_2")

        assertNotNull(updatedAcc1)
        assertNotNull(updatedAcc2)
        assertFalse(updatedAcc1!!.isHistoryOnlySubscriber)
        assertTrue(updatedAcc2!!.isHistoryOnlySubscriber)

        // Check audit log
        val auditLogs = database.auditLogDao().getRecent(10)
        assertTrue(auditLogs.any { it.action == "ISP_SUBSCRIBER_DISAPPEARED" && it.entityId == "acc_active_2" })
    }

    @Test
    fun testIspDisappearance_excludesAccountsWithNullOrBlankUsername() = runBlocking {
        val accNoUser = LocalAccount(
            id = "acc_no_user",
            displayName = "Local Only Customer",
            earthlinkUsername = null,
            isHistoryOnlySubscriber = false
        )
        val accBlankUser = LocalAccount(
            id = "acc_blank_user",
            displayName = "Blank Username Customer",
            earthlinkUsername = "   ",
            isHistoryOnlySubscriber = false
        )
        database.localAccountDao().insert(accNoUser)
        database.localAccountDao().insert(accBlankUser)

        val authoritativeIsp = setOf("some_other_user")
        val transitioned = IspDisappearanceReconciler.reconcile(
            database = database,
            accountDao = database.localAccountDao(),
            auditDao = database.auditLogDao(),
            authoritativeIspUserIds = authoritativeIsp,
            isFetchComplete = true
        )

        assertTrue(transitioned.isEmpty())

        val acc1 = database.localAccountDao().getByIdOneShot("acc_no_user")
        val acc2 = database.localAccountDao().getByIdOneShot("acc_blank_user")
        assertFalse(acc1!!.isHistoryOnlySubscriber)
        assertFalse(acc2!!.isHistoryOnlySubscriber)
    }

    @Test
    fun testIspDisappearance_abortsWhenFetchIsIncomplete() = runBlocking {
        val acc = LocalAccount(
            id = "acc_test",
            displayName = "Test User",
            earthlinkUsername = "user_test",
            isHistoryOnlySubscriber = false
        )
        database.localAccountDao().insert(acc)

        // Incomplete fetch (e.g. network failure or partial timeout)
        val transitioned = IspDisappearanceReconciler.reconcile(
            database = database,
            accountDao = database.localAccountDao(),
            auditDao = database.auditLogDao(),
            authoritativeIspUserIds = emptySet(),
            isFetchComplete = false
        )

        assertTrue(transitioned.isEmpty())
        val accAfter = database.localAccountDao().getByIdOneShot("acc_test")
        assertFalse(accAfter!!.isHistoryOnlySubscriber)
    }

    @Test
    fun testIspDisappearance_monotonicity_neverResetsTrueToFalse() = runBlocking {
        val acc = LocalAccount(
            id = "acc_already_history",
            displayName = "History User",
            earthlinkUsername = "user_history",
            isHistoryOnlySubscriber = true
        )
        database.localAccountDao().insert(acc)

        // ISP fetch returns empty - already true, should not re-transition or duplicate audit
        val transitioned = IspDisappearanceReconciler.reconcile(
            database = database,
            accountDao = database.localAccountDao(),
            auditDao = database.auditLogDao(),
            authoritativeIspUserIds = emptySet(),
            isFetchComplete = true
        )

        assertTrue(transitioned.isEmpty())
        val accAfter = database.localAccountDao().getByIdOneShot("acc_already_history")
        assertTrue(accAfter!!.isHistoryOnlySubscriber)
    }

    @Test
    fun testRemoteEntityValidator_monotonicHistoryOnlySubscriberMerge() {
        val existingLocal = LocalAccount(
            id = "acc_val_1",
            displayName = "Existing Local",
            isHistoryOnlySubscriber = true
        )

        // Remote payload has isHistoryOnlySubscriber = false (or absent)
        val remotePayload = mapOf<String, Any>(
            "displayName" to "Existing Local",
            "debtIqd" to 1000.0,
            "isHistoryOnlySubscriber" to false
        )

        val result = RemoteEntityValidator.validateAndMapAccount(
            id = "acc_val_1",
            d = remotePayload,
            remoteUpdatedAt = System.currentTimeMillis(),
            existingLocalAccount = existingLocal
        )

        assertTrue(result is RemoteEntityValidationResult.Valid)
        val mappedAccount = (result as RemoteEntityValidationResult.Valid).entity
        // Monotonic: true must not revert to false
        assertTrue(mappedAccount.isHistoryOnlySubscriber)
    }

    /**
     * R6 Scenario 1: Complete fetch transitions absent active subscriber to history-only,
     * while present active subscriber remains active.
     */
    @Test
    fun testProductionWiring_completeFetchTransitionsAbsentActiveSubscriber() = runBlocking {
        val accA = LocalAccount(
            id = "acc_prod_a",
            displayName = "User A (Disappearing)",
            earthlinkUsername = "user_a",
            isHistoryOnlySubscriber = false
        )
        val accB = LocalAccount(
            id = "acc_prod_b",
            displayName = "User B (Active)",
            earthlinkUsername = "user_b",
            isHistoryOnlySubscriber = false
        )
        database.localAccountDao().insert(accA)
        database.localAccountDao().insert(accB)

        // Gateway returns complete snapshot containing ONLY user_b
        fakeGateway.searchUsersResult = UserListResponse(
            itemsList = listOf(UserListItem(userIDLower = "user_b")),
            totalCount = 1
        )

        viewModel.loadDashboardData().join()
        assertEquals(1, viewModel.subscribersList.value.size)

        val updatedA = database.localAccountDao().getByIdOneShot("acc_prod_a")
        val updatedB = database.localAccountDao().getByIdOneShot("acc_prod_b")

        assertNotNull(updatedA)
        assertNotNull(updatedB)
        assertTrue("Absent active subscriber must transition to history-only", updatedA!!.isHistoryOnlySubscriber)
        assertFalse("Present active subscriber must remain active", updatedB!!.isHistoryOnlySubscriber)

        // Verify outbox queued for updatedA
        val outboxItems = database.syncOutboxDao().getPending()
        assertTrue("Outbox must contain mutation for transitioned account", outboxItems.any { it.entityId == "acc_prod_a" })

        // Verify audit log recorded
        val auditLogs = database.auditLogDao().getRecent(10)
        assertTrue("Audit log must record ISP_SUBSCRIBER_DISAPPEARED", auditLogs.any { it.action == "ISP_SUBSCRIBER_DISAPPEARED" && it.entityId == "acc_prod_a" })
    }

    /**
     * R6 Scenario 2: Incomplete fetch (itemsList.size < totalCount) aborts without transitioning any accounts.
     */
    @Test
    fun testProductionWiring_incompleteFetchDoesNotTransition() = runBlocking {
        val accA = LocalAccount(
            id = "acc_inc_a",
            displayName = "User A",
            earthlinkUsername = "user_a",
            isHistoryOnlySubscriber = false
        )
        database.localAccountDao().insert(accA)

        // Gateway returns partial fetch: totalCount = 10, but only 1 item returned
        fakeGateway.searchUsersResult = UserListResponse(
            itemsList = listOf(UserListItem(userIDLower = "other_user")),
            totalCount = 10
        )

        viewModel.loadDashboardData().join()

        val updatedA = database.localAccountDao().getByIdOneShot("acc_inc_a")
        assertNotNull(updatedA)
        assertFalse("Incomplete fetch must NOT transition active account to history-only", updatedA!!.isHistoryOnlySubscriber)

        val auditLogs = database.auditLogDao().getRecent(10)
        assertFalse("No disappearance audit logs must be recorded on incomplete fetch", auditLogs.any { it.action == "ISP_SUBSCRIBER_DISAPPEARED" })
    }

    /**
     * R6 Scenario 3: Zero-result anomaly (empty items or totalCount = 0) does NOT mass-historize active accounts.
     */
    @Test
    fun testProductionWiring_zeroResultAnomalyDoesNotMassHistorize() = runBlocking {
        val acc1 = LocalAccount(id = "acc_zr_1", displayName = "User 1", earthlinkUsername = "user_1", isHistoryOnlySubscriber = false)
        val acc2 = LocalAccount(id = "acc_zr_2", displayName = "User 2", earthlinkUsername = "user_2", isHistoryOnlySubscriber = false)
        database.localAccountDao().insert(acc1)
        database.localAccountDao().insert(acc2)

        // Gateway returns 0 items (e.g. reseller account suspended or empty response anomaly)
        fakeGateway.searchUsersResult = UserListResponse(
            itemsList = emptyList(),
            totalCount = 0
        )

        viewModel.loadDashboardData().join()

        val after1 = database.localAccountDao().getByIdOneShot("acc_zr_1")
        val after2 = database.localAccountDao().getByIdOneShot("acc_zr_2")

        assertNotNull(after1)
        assertNotNull(after2)
        assertFalse("Zero-result anomaly must not historize acc_zr_1", after1!!.isHistoryOnlySubscriber)
        assertFalse("Zero-result anomaly must not historize acc_zr_2", after2!!.isHistoryOnlySubscriber)
    }

    /**
     * R6 Scenario 4: Recycled username creates a separate active account while historical account
     * and debt are preserved intact.
     */
    @Test
    fun testProductionWiring_recycledUsername_createsSeparateAccountAndPreservesHistoricalLedger() = runBlocking {
        val recycledUsername = "pppoe_recycled_user"

        // Step 1: Account 1 was active, had debt of 25,000 IQD
        val acc1 = LocalAccount(
            id = "acc_gen1",
            displayName = "Original Subscriber",
            earthlinkUsername = recycledUsername,
            debtIqd = 25000.0,
            isHistoryOnlySubscriber = false
        )
        database.localAccountDao().insert(acc1)

        val entry = LocalLedgerEntry(
            id = "entry_1",
            accountId = "acc_gen1",
            typeRaw = "took",
            amountIqd = 25000.0,
            debtAfterIqd = 25000.0,
            note = "Historical invoice",
            occurredAt = 1000L
        )
        database.localLedgerEntryDao().insert(entry)

        // Step 2: Authoritative ISP fetch runs where recycledUsername disappeared
        val transitioned = accountRepo.reconcileIspDisappearance(
            authoritativeIspUserIds = setOf("completely_different_user"),
            isFetchComplete = true
        )
        assertEquals(listOf("acc_gen1"), transitioned)

        val acc1AfterDisp = database.localAccountDao().getByIdOneShot("acc_gen1")
        assertNotNull(acc1AfterDisp)
        assertTrue("Account 1 must now be history-only", acc1AfterDisp!!.isHistoryOnlySubscriber)
        assertEquals("Account 1 debt must remain intact", 25000.0, acc1AfterDisp.debtIqd, 0.001)

        // Step 3: Later, a new subscriber with the same username arrives via ISP import / creation
        // Matcher checks local accounts; must EXCLUDE historical accounts from active match
        val existingAccounts = database.localAccountDao().getAllPersistedOneShot()
        val matched = SubscriberMatcher.matchSubscriber(
            candidates = existingAccounts,
            username = recycledUsername,
            extId = "new_external_id_999",
            phone = "07700000000"
        )
        // SubscriberMatcher must NOT match historical acc1
        assertNull("SubscriberMatcher must not match historical account for active incoming subscriber", matched)

        // Step 4: Create new active Account 2 for the recycled username
        val acc2 = LocalAccount(
            id = "acc_gen2",
            displayName = "New Recycled Subscriber",
            earthlinkUsername = recycledUsername,
            sourceExternalId = "new_external_id_999",
            debtIqd = 0.0,
            isHistoryOnlySubscriber = false
        )
        database.localAccountDao().insert(acc2)

        // Verify two separate records exist in database
        val allPersisted = database.localAccountDao().getAllPersistedOneShot()
        assertEquals("Both historical and active accounts must exist", 2, allPersisted.size)

        val activeMatch = database.localAccountDao().findActiveAccountByUsernameOrIdOneShot(recycledUsername)
        assertNotNull(activeMatch)
        assertEquals("Active lookup must return only the new active account", "acc_gen2", activeMatch!!.id)
        assertFalse(activeMatch.isHistoryOnlySubscriber)

        // Verify historical account and its ledger are completely uncorrupted
        val preservedAcc1 = database.localAccountDao().getByIdOneShot("acc_gen1")
        assertNotNull(preservedAcc1)
        assertTrue(preservedAcc1!!.isHistoryOnlySubscriber)
        assertEquals(25000.0, preservedAcc1.debtIqd, 0.001)

        val ledgerAcc1 = database.localLedgerEntryDao().getByAccountIdOneShot("acc_gen1")
        assertEquals(1, ledgerAcc1.size)
        assertEquals("entry_1", ledgerAcc1[0].id)
        assertEquals(25000.0, ledgerAcc1[0].amountIqd, 0.001)
    }

    /**
     * R6 Identity-Integrity Gate:
     * When items.size == totalCount, but at least one returned item has a blank/null userID,
     * the snapshot is NOT reconciliation-safe. No reconciliation occurs and active accounts remain active.
     */
    @Test
    fun testProductionWiring_blankUserIdInCompleteSnapshot_doesNotTriggerReconciliation() = runBlocking {
        val accA = LocalAccount(
            id = "acc_valid_user",
            displayName = "Valid User",
            earthlinkUsername = "user_valid",
            isHistoryOnlySubscriber = false
        )
        database.localAccountDao().insert(accA)

        // Gateway returns 2 items matching totalCount = 2, but one item has blank userID
        fakeGateway.searchUsersResult = UserListResponse(
            itemsList = listOf(
                UserListItem(userIDLower = "other_user"),
                UserListItem(userIDLower = "   ") // Blank userID
            ),
            totalCount = 2
        )

        viewModel.loadDashboardData().join()

        // Verify account was NOT transitioned to history-only
        val afterAcc = database.localAccountDao().getByIdOneShot("acc_valid_user")
        assertNotNull(afterAcc)
        assertFalse("Account must remain active when snapshot contains blank userID", afterAcc!!.isHistoryOnlySubscriber)

        // Verify no audit log recorded
        val auditLogs = database.auditLogDao().getRecent(10)
        assertFalse("No disappearance audit logs should be recorded", auditLogs.any { it.action == "ISP_SUBSCRIBER_DISAPPEARED" })
    }
}

