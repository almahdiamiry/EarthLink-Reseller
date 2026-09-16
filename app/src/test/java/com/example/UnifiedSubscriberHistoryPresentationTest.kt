package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.security.PreferenceManager
import com.example.core.sync.EventSyncResult
import com.example.core.sync.RemoteEvent
import com.example.core.sync.RemoteEventSource
import com.example.core.sync.RemoteSyncCoordinator
import com.example.data.repository.AuditRepositoryImpl
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.EarthlinkGateway
import com.example.domain.repository.SyncProgress
import com.example.domain.repository.SyncReason
import com.example.domain.repository.SyncRepository
import com.example.domain.repository.SyncStatusState
import com.example.ui.screens.HistoryPresentationManager
import com.example.ui.viewmodels.EarthlinkSearchViewModel
import com.example.ui.viewmodels.LocalAccountsViewModel
import com.example.data.repository.UtowerImportRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CORE TRIAD SPECIFICATION:
 * 1. Claim: Unified Subscriber History Display across multi-device physical containers.
 *    When a logical subscriber has multiple active LocalAccount physical containers sharing
 *    the authoritative subscriber identity (ispUserIndex or username fallback), the user detail
 *    screen read path presents the union of their ledger history as one coherent subscriber history
 *    without reparenting, merging, or modifying physical containers or stored ledger rows.
 *    (INV-01 Financial Correctness, INV-02 History Preservation, INV-09 Identity & Provenance Integrity)
 * 2. Seam / Environment: ROBOLECTRIC (Multi-database SQLite simulation with real DAOs, Repositories,
 *    RemoteSyncCoordinator, EarthlinkSearchViewModel, and HistoryPresentationManager).
 * 3. Independent Oracle: Explicit set comparison against mathematically derived literal constants
 *    and predefined transaction identifiers; verification of 0 loss, 0 duplicates, 0 leakage,
 *    and 0 container/ledger ID reparenting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class UnifiedSubscriberHistoryPresentationTest {

    private lateinit var context: Context
    private lateinit var dbDevice1: AppDatabase
    private lateinit var dbDevice2: AppDatabase

    private lateinit var coordinator1: RemoteSyncCoordinator
    private lateinit var coordinator2: RemoteSyncCoordinator

    private lateinit var ledgerRepo1: LocalLedgerRepositoryImpl
    private lateinit var ledgerRepo2: LocalLedgerRepositoryImpl

    private lateinit var accountRepo1: LocalAccountRepositoryImpl
    private lateinit var accountRepo2: LocalAccountRepositoryImpl

    private lateinit var prefs1: PreferenceManager
    private lateinit var prefs2: PreferenceManager

    private lateinit var viewModel1: EarthlinkSearchViewModel
    private lateinit var viewModel2: EarthlinkSearchViewModel

    private class DummySyncRepo : SyncRepository {
        private val _state = MutableStateFlow<SyncStatusState>(SyncStatusState.IDLE)
        override val syncState: StateFlow<SyncStatusState> = _state.asStateFlow()
        override val syncProgress: StateFlow<SyncProgress> = MutableStateFlow(SyncProgress()).asStateFlow()
        override fun triggerSync() {}
        override fun setupPeriodicSync() {}
        override suspend fun triggerSyncOneShot(): Boolean = true
        override fun requestSync(reason: SyncReason) {}
        override fun triggerSettingsSync(uid: String?, reason: String) {}
        override suspend fun getPendingOutboxCount(): Int = 0
        override suspend fun getFailedCount(): Int = 0
        override suspend fun retryFailedItems(): Int = 0
        override suspend fun anonymousSignIn(): String? = null
        override suspend fun emailSignIn(email: String, password: String): String? = null
        override suspend fun googleSignIn(idToken: String): String? = null
        override fun getFirebaseUid(): String? = null
        override suspend fun signOut(force: Boolean, clearData: Boolean) {}
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        dbDevice1 = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dbDevice2 = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        coordinator1 = RemoteSyncCoordinator(
            appDatabase = dbDevice1,
            accountDao = dbDevice1.localAccountDao(),
            ledgerDao = dbDevice1.localLedgerEntryDao(),
            outboxDao = dbDevice1.syncOutboxDao(),
            batchDao = dbDevice1.importBatchDao(),
            metadataDao = dbDevice1.syncMetadataDao(),
            auditDao = dbDevice1.auditLogDao()
        )

        coordinator2 = RemoteSyncCoordinator(
            appDatabase = dbDevice2,
            accountDao = dbDevice2.localAccountDao(),
            ledgerDao = dbDevice2.localLedgerEntryDao(),
            outboxDao = dbDevice2.syncOutboxDao(),
            batchDao = dbDevice2.importBatchDao(),
            metadataDao = dbDevice2.syncMetadataDao(),
            auditDao = dbDevice2.auditLogDao()
        )

        ledgerRepo1 = LocalLedgerRepositoryImpl(
            database = dbDevice1,
            ledgerDao = dbDevice1.localLedgerEntryDao(),
            accountDao = dbDevice1.localAccountDao(),
            outboxDao = dbDevice1.syncOutboxDao(),
            pendingDao = dbDevice1.pendingExternalOperationDao()
        )

        ledgerRepo2 = LocalLedgerRepositoryImpl(
            database = dbDevice2,
            ledgerDao = dbDevice2.localLedgerEntryDao(),
            accountDao = dbDevice2.localAccountDao(),
            outboxDao = dbDevice2.syncOutboxDao(),
            pendingDao = dbDevice2.pendingExternalOperationDao()
        )

        accountRepo1 = LocalAccountRepositoryImpl(
            database = dbDevice1,
            accountDao = dbDevice1.localAccountDao(),
            outboxDao = dbDevice1.syncOutboxDao()
        )

        accountRepo2 = LocalAccountRepositoryImpl(
            database = dbDevice2,
            accountDao = dbDevice2.localAccountDao(),
            outboxDao = dbDevice2.syncOutboxDao()
        )

        prefs1 = PreferenceManager(context).apply {
            setDemoMode(false)
            setLanguage("ar")
        }
        prefs2 = PreferenceManager(context).apply {
            setDemoMode(false)
            setLanguage("ar")
        }

        val audit1 = AuditRepositoryImpl(dbDevice1, dbDevice1.auditLogDao())
        val audit2 = AuditRepositoryImpl(dbDevice2, dbDevice2.auditLogDao())

        val gateway1 = Phase1DuplicateInitiationProtectionTest.TestEarthlinkGateway()
        val gateway2 = Phase1DuplicateInitiationProtectionTest.TestEarthlinkGateway()

        viewModel1 = EarthlinkSearchViewModel(
            gateway = gateway1,
            audit = audit1,
            prefs = prefs1,
            localAccountRepository = accountRepo1,
            localLedgerRepository = ledgerRepo1,
            syncRepo = DummySyncRepo()
        )

        viewModel2 = EarthlinkSearchViewModel(
            gateway = gateway2,
            audit = audit2,
            prefs = prefs2,
            localAccountRepository = accountRepo2,
            localLedgerRepository = ledgerRepo2,
            syncRepo = DummySyncRepo()
        )

        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        dbDevice1.close()
        dbDevice2.close()
    }

    // =========================================================================
    // STEP 6: FOCUSED REGRESSION TEST FOR PRESENTATION AGGREGATION BEHAVIOR
    // =========================================================================
    @Test
    fun testStep6_unifiedSubscriberHistory_aggregatesAllContainersWithoutReparentingOrLeakage() = runBlocking {
        // SUBSCRIBER AHMED (authoritative identity: ispUserIndex = 1001, username = "ahmed")
        // Device A has physical container "acc_ahmed_A" with ledgers L1, L2, L3
        val accA = LocalAccount(
            id = "acc_ahmed_A",
            earthlinkUsername = "ahmed",
            ispUserIndex = 1001,
            displayName = "Ahmed Device A",
            debtIqd = 11000.0
        )
        dbDevice1.localAccountDao().insert(accA)

        val l1 = ledgerRepo1.addDebt("acc_ahmed_A", 10000.0, "[DEBT] Initial debt", "L1")
        val l2 = ledgerRepo1.addPayment("acc_ahmed_A", 4000.0, "[PAYMENT] Cash", "L2")
        val l3 = ledgerRepo1.addDebt("acc_ahmed_A", 5000.0, "[DEBT] Extra pack", "L3")

        // Device B has physical container "acc_ahmed_B" with ledgers L4, L5
        val accB = LocalAccount(
            id = "acc_ahmed_B",
            earthlinkUsername = "ahmed",
            ispUserIndex = 1001,
            displayName = "Ahmed Device B",
            debtIqd = 3000.0
        )
        dbDevice1.localAccountDao().insert(accB)

        val l4 = ledgerRepo1.addPayment("acc_ahmed_B", 2000.0, "[PAYMENT] Transfer", "L4")
        val l5 = ledgerRepo1.addDebt("acc_ahmed_B", 5000.0, "[DEBT] Refill", "L5")

        // ANOTHER SUBSCRIBER MOHAMED (authoritative identity: ispUserIndex = 2002, username = "mohamed")
        val accMohamed = LocalAccount(
            id = "acc_mohamed",
            earthlinkUsername = "mohamed",
            ispUserIndex = 2002,
            displayName = "Mohamed",
            debtIqd = 7000.0
        )
        dbDevice1.localAccountDao().insert(accMohamed)
        val m1 = ledgerRepo1.addDebt("acc_mohamed", 7000.0, "[DEBT] Mohamed debt", "M1")

        // 1. Verify PRE-FIX behavior was limited to single container:
        val legacyAHistory = viewModel1.getLedgerForAccount("acc_ahmed_A").first()
        assertEquals("Pre-fix single container query only returned container A's 3 items", 3, legacyAHistory.size)
        assertEquals(setOf("L1", "L2", "L3"), legacyAHistory.map { it.id }.toSet())

        // 2. Verify POST-FIX Unified Subscriber History:
        val unifiedHistory = viewModel1.getUnifiedLedgerForSubscriber(
            userIndex = 1001,
            username = "ahmed",
            fallbackAccountId = "acc_ahmed_A"
        ).first()

        // Assertions:
        // - All expected ledger IDs present: L1, L2, L3, L4, L5
        val retrievedIds = unifiedHistory.map { it.id }
        assertEquals("Unified history must contain exactly 5 transactions", 5, retrievedIds.size)
        assertEquals("No duplicate ledger IDs allowed", retrievedIds.toSet().size, retrievedIds.size)
        assertEquals("Must match union of L1, L2, L3, L4, L5", setOf("L1", "L2", "L3", "L4", "L5"), retrievedIds.toSet())

        // - No unexpected ledger IDs present (M1 from Mohamed MUST NOT leak):
        assertFalse("Cross-subscriber leakage forbidden: Mohamed M1 must not appear in Ahmed history", retrievedIds.contains("M1"))

        // - Original LocalAccount IDs unchanged in database:
        val dbAccA = dbDevice1.localAccountDao().getByIdOneShot("acc_ahmed_A")
        val dbAccB = dbDevice1.localAccountDao().getByIdOneShot("acc_ahmed_B")
        assertNotNull(dbAccA)
        assertNotNull(dbAccB)
        assertEquals("acc_ahmed_A", dbAccA?.id)
        assertEquals("acc_ahmed_B", dbAccB?.id)

        // - Original ledger.accountId unchanged (NO REPARENTING):
        val dbL1 = dbDevice1.localLedgerEntryDao().getByIdOneShot("L1")
        val dbL4 = dbDevice1.localLedgerEntryDao().getByIdOneShot("L4")
        assertEquals("acc_ahmed_A", dbL1?.accountId)
        assertEquals("acc_ahmed_B", dbL4?.accountId)

        // In the retrieved unified list, each entry retains its original accountId:
        assertEquals("acc_ahmed_A", unifiedHistory.first { it.id == "L1" }.accountId)
        assertEquals("acc_ahmed_B", unifiedHistory.first { it.id == "L4" }.accountId)

        // - Physical containers remain separate:
        val activeAhmedContainers = dbDevice1.localAccountDao().findActiveAccountsByIspUserIndex(1001)
        assertEquals("Both containers remain separate physical active accounts", 2, activeAhmedContainers.size)
        assertEquals(setOf("acc_ahmed_A", "acc_ahmed_B"), activeAhmedContainers.map { it.id }.toSet())
    }

    // =========================================================================
    // STEP 7: CROSS-DEVICE END-TO-END SIMULATION WITH SYNC & REPLAY
    // =========================================================================
    @Test
    fun testStep7_crossDeviceEndToEndSimulation_bidirectionalSyncAndReplay() = runBlocking {
        // DEVICE A:
        // Ahmed container A: L1, L2, L3
        val accDevA = LocalAccount(
            id = "acc_ahmed_devA",
            earthlinkUsername = "ahmed",
            ispUserIndex = 1001,
            displayName = "Ahmed DevA",
            debtIqd = 11000.0
        )
        dbDevice1.localAccountDao().insert(accDevA)
        val l1 = ledgerRepo1.addDebt("acc_ahmed_devA", 10000.0, "[DEBT] Initial debt", "L1")
        val l2 = ledgerRepo1.addPayment("acc_ahmed_devA", 4000.0, "[PAYMENT] Cash", "L2")
        val l3 = ledgerRepo1.addDebt("acc_ahmed_devA", 5000.0, "[DEBT] Extra pack", "L3")

        // Other subscriber Mohamed on Device A: M1
        val accMohamedDevA = LocalAccount(
            id = "acc_mohamed_devA",
            earthlinkUsername = "mohamed",
            ispUserIndex = 2002,
            displayName = "Mohamed",
            debtIqd = 8000.0
        )
        dbDevice1.localAccountDao().insert(accMohamedDevA)
        val m1 = ledgerRepo1.addDebt("acc_mohamed_devA", 8000.0, "[DEBT] Mohamed debt", "M1")

        // DEVICE B:
        // Ahmed container B: L4, L5
        val accDevB = LocalAccount(
            id = "acc_ahmed_devB",
            earthlinkUsername = "ahmed",
            ispUserIndex = 1001,
            displayName = "Ahmed DevB",
            debtIqd = 3000.0
        )
        dbDevice2.localAccountDao().insert(accDevB)
        val l4 = ledgerRepo2.addPayment("acc_ahmed_devB", 2000.0, "[PAYMENT] Transfer", "L4")
        val l5 = ledgerRepo2.addDebt("acc_ahmed_devB", 5000.0, "[DEBT] Refill", "L5")

        // Legitimate third container Ahmed C: L6
        val accDevC = LocalAccount(
            id = "acc_ahmed_devC",
            earthlinkUsername = "ahmed",
            ispUserIndex = 1001,
            displayName = "Ahmed DevC",
            debtIqd = 1000.0
        )
        dbDevice2.localAccountDao().insert(accDevC)
        val l6 = ledgerRepo2.addDebt("acc_ahmed_devC", 1000.0, "[DEBT] Minor charge", "L6")

        // -------------------------------------------------------------
        // BIDIRECTIONAL SYNCHRONIZATION
        // Sync Device 1 -> Device 2:
        val dev1Accounts = listOf(accDevA, accMohamedDevA)
        val dev1Txs = listOf(l1, l2, l3, m1)
        for (acc in dev1Accounts) {
            coordinator2.processEvent(RemoteEvent.AccountUpsert(acc.id, 100L, RemoteEventSource.REALTIME, acc))
        }
        for (tx in dev1Txs) {
            coordinator2.processEvent(RemoteEvent.LedgerUpsert(tx.id, 101L, RemoteEventSource.REALTIME, tx))
        }

        // Sync Device 2 -> Device 1:
        val dev2Accounts = listOf(accDevB, accDevC)
        val dev2Txs = listOf(l4, l5, l6)
        for (acc in dev2Accounts) {
            coordinator1.processEvent(RemoteEvent.AccountUpsert(acc.id, 102L, RemoteEventSource.REALTIME, acc))
        }
        for (tx in dev2Txs) {
            coordinator1.processEvent(RemoteEvent.LedgerUpsert(tx.id, 103L, RemoteEventSource.REALTIME, tx))
        }

        // REPLAY / DUPLICATE DELIVERY:
        // Process exact same ledger events again on both devices
        for (tx in dev1Txs) {
            val res = coordinator2.processEvent(RemoteEvent.LedgerUpsert(tx.id, 101L, RemoteEventSource.REALTIME, tx))
            assertEquals("Duplicate delivery must be SKIPPED_DUPLICATE", EventSyncResult.SKIPPED_DUPLICATE, res)
        }
        for (tx in dev2Txs) {
            val res = coordinator1.processEvent(RemoteEvent.LedgerUpsert(tx.id, 103L, RemoteEventSource.REALTIME, tx))
            assertEquals("Duplicate delivery must be SKIPPED_DUPLICATE", EventSyncResult.SKIPPED_DUPLICATE, res)
        }

        // -------------------------------------------------------------
        // VERIFY FINAL STATE ON DEVICE A:
        val devAAhmedHistory = viewModel1.getUnifiedLedgerForSubscriber(1001, "ahmed", "acc_ahmed_devA").first()
        val devAMohamedHistory = viewModel1.getUnifiedLedgerForSubscriber(2002, "mohamed", "acc_mohamed_devA").first()

        val expectedAhmedIds = setOf("L1", "L2", "L3", "L4", "L5", "L6")
        assertEquals("Device A must contain all 6 Ahmed transactions", 6, devAAhmedHistory.size)
        assertEquals("LOSS = 0 on Device A", expectedAhmedIds, devAAhmedHistory.map { it.id }.toSet())
        assertEquals("DUPLICATES = 0 on Device A", 6, devAAhmedHistory.map { it.id }.toSet().size)
        assertFalse("CROSS-SUBSCRIBER LEAKAGE = 0: Mohamed M1 not in Ahmed history", devAAhmedHistory.any { it.id == "M1" })

        assertEquals("Device A Mohamed history has only M1", setOf("M1"), devAMohamedHistory.map { it.id }.toSet())

        // -------------------------------------------------------------
        // VERIFY FINAL STATE ON DEVICE B:
        val devBAhmedHistory = viewModel2.getUnifiedLedgerForSubscriber(1001, "ahmed", "acc_ahmed_devB").first()
        val devBMohamedHistory = viewModel2.getUnifiedLedgerForSubscriber(2002, "mohamed").first()

        assertEquals("Device B must contain all 6 Ahmed transactions", 6, devBAhmedHistory.size)
        assertEquals("LOSS = 0 on Device B", expectedAhmedIds, devBAhmedHistory.map { it.id }.toSet())
        assertEquals("DUPLICATES = 0 on Device B", 6, devBAhmedHistory.map { it.id }.toSet().size)
        assertFalse("CROSS-SUBSCRIBER LEAKAGE = 0: Mohamed M1 not in Ahmed history", devBAhmedHistory.any { it.id == "M1" })

        assertEquals("Device B Mohamed history has only M1", setOf("M1"), devBMohamedHistory.map { it.id }.toSet())

        // VERIFY PHYSICAL INVARIANTS:
        // Containers remain distinct and have original IDs:
        assertEquals("acc_ahmed_devA", devAAhmedHistory.first { it.id == "L1" }.accountId)
        assertEquals("acc_ahmed_devB", devAAhmedHistory.first { it.id == "L4" }.accountId)
        assertEquals("acc_ahmed_devC", devAAhmedHistory.first { it.id == "L6" }.accountId)
    }

    // =========================================================================
    // STEP 8: NEGATIVE / CONTROL CASES
    // =========================================================================
    @Test
    fun testStep8A_controlSingleContainer_existingHistoryBehaviorUnchanged() = runBlocking {
        val singleAcc = LocalAccount(
            id = "acc_single",
            earthlinkUsername = "single_user",
            ispUserIndex = 3001,
            displayName = "Single User"
        )
        dbDevice1.localAccountDao().insert(singleAcc)
        ledgerRepo1.addDebt("acc_single", 15000.0, "[DEBT] Plan", "S1")
        ledgerRepo1.addPayment("acc_single", 15000.0, "[PAYMENT] Plan payment", "S2")

        val unified = viewModel1.getUnifiedLedgerForSubscriber(3001, "single_user", "acc_single").first()
        val legacy = viewModel1.getLedgerForAccount("acc_single").first()

        assertEquals("Unified history exactly matches legacy query for single container", legacy.map { it.id }, unified.map { it.id })
        assertEquals(listOf("S2", "S1"), unified.map { it.id })
    }

    @Test
    fun testStep8C_controlSameDisplayName_differentAuthoritativeIdentity_noCrossLeakage() = runBlocking {
        // Two different subscribers happen to share the exact same display name "Ali Hassan"
        // Subscriber 1: ispUserIndex = 4001, username = "ali1"
        val sub1 = LocalAccount(
            id = "acc_ali_1",
            earthlinkUsername = "ali1",
            ispUserIndex = 4001,
            displayName = "Ali Hassan"
        )
        // Subscriber 2: ispUserIndex = 4002, username = "ali2"
        val sub2 = LocalAccount(
            id = "acc_ali_2",
            earthlinkUsername = "ali2",
            ispUserIndex = 4002,
            displayName = "Ali Hassan"
        )
        dbDevice1.localAccountDao().insert(sub1)
        dbDevice1.localAccountDao().insert(sub2)

        ledgerRepo1.addDebt("acc_ali_1", 20000.0, "Ali 1 Debt", "ALI1_TX")
        ledgerRepo1.addDebt("acc_ali_2", 30000.0, "Ali 2 Debt", "ALI2_TX")

        val historySub1 = viewModel1.getUnifiedLedgerForSubscriber(4001, "ali1", "acc_ali_1").first()
        val historySub2 = viewModel1.getUnifiedLedgerForSubscriber(4002, "ali2", "acc_ali_2").first()

        assertEquals(setOf("ALI1_TX"), historySub1.map { it.id }.toSet())
        assertEquals(setOf("ALI2_TX"), historySub2.map { it.id }.toSet())
        assertFalse("Must never leak across different authoritative identities despite same display name", historySub1.any { it.id == "ALI2_TX" })
        assertFalse("Must never leak across different authoritative identities despite same display name", historySub2.any { it.id == "ALI1_TX" })
    }

    @Test
    fun testStep8E_siblingContainers_accountIdDifferent_remainPhysicallyUntouched() = runBlocking {
        // Two physical containers for same subscriber
        val c1 = LocalAccount(id = "c1", earthlinkUsername = "hasan", ispUserIndex = 5001)
        val c2 = LocalAccount(id = "c2", earthlinkUsername = "hasan", ispUserIndex = 5001)
        dbDevice1.localAccountDao().insert(c1)
        dbDevice1.localAccountDao().insert(c2)

        val tx1 = ledgerRepo1.addPayment("c1", 1000.0, "Pay 1", "T_C1")
        val tx2 = ledgerRepo1.addPayment("c2", 2000.0, "Pay 2", "T_C2")

        val unified = viewModel1.getUnifiedLedgerForSubscriber(5001, "hasan", "c1").first()
        assertEquals(2, unified.size)

        // Sibling physical records in SQLite MUST have original accountId:
        val physicalTx1 = dbDevice1.localLedgerEntryDao().getByIdOneShot("T_C1")
        val physicalTx2 = dbDevice1.localLedgerEntryDao().getByIdOneShot("T_C2")
        assertEquals("c1", physicalTx1?.accountId)
        assertEquals("c2", physicalTx2?.accountId)
    }

    // =========================================================================
    // STEP 9: FINANCIAL SEMANTICS VERIFICATION
    // =========================================================================
    @Test
    fun testStep9_financialSemanticsPreserved_noAlteredValuesOrDeletedRows() = runBlocking {
        val acc1 = LocalAccount(id = "fin_1", earthlinkUsername = "zayd", ispUserIndex = 6001, openingDebtIqd = 10000.0)
        val acc2 = LocalAccount(id = "fin_2", earthlinkUsername = "zayd", ispUserIndex = 6001, openingDebtIqd = 0.0)
        dbDevice1.localAccountDao().insert(acc1)
        dbDevice1.localAccountDao().insert(acc2)

        val txA = ledgerRepo1.addDebt("fin_1", 5000.0, "[DEBT] Add debt", "TX_A")
        val txB = ledgerRepo1.addPayment("fin_2", 2000.0, "[PAYMENT] Cash", "TX_B")

        val unified = viewModel1.getUnifiedLedgerForSubscriber(6001, "zayd", "fin_1").first()
        val entryA = unified.first { it.id == "TX_A" }
        val entryB = unified.first { it.id == "TX_B" }

        // Assert exact financial attributes:
        assertEquals(5000.0, entryA.amountIqd, 0.0001)
        assertEquals(2000.0, entryB.amountIqd, 0.0001)
        assertEquals(txA.debtAfterIqd, entryA.debtAfterIqd, 0.0001)
        assertEquals(txB.debtAfterIqd, entryB.debtAfterIqd, 0.0001)

        // Assert ledger count:
        assertEquals(2, unified.size)
        // No physical deletion occurred:
        assertEquals(2, dbDevice1.localLedgerEntryDao().getByAccountIdsOneShot(listOf("fin_1", "fin_2")).size)
    }

    // =========================================================================
    // STEP 10: VERIFY MUTATION TARGETING REMAINS SEPARATE
    // =========================================================================
    @Test
    fun testStep10_mutationTargetingRemainsStrictlyBoundToSelectedContainer() = runBlocking {
        val acc1 = LocalAccount(id = "target_1", earthlinkUsername = "omar", ispUserIndex = 7001, debtIqd = 10000.0)
        val acc2 = LocalAccount(id = "target_2", earthlinkUsername = "omar", ispUserIndex = 7001, debtIqd = 5000.0)
        dbDevice1.localAccountDao().insert(acc1)
        dbDevice1.localAccountDao().insert(acc2)

        // Perform write operation targeting specific physical account target_1:
        val mutationTx = ledgerRepo1.addPayment("target_1", 3000.0, "Payment on container 1", "MUT_1")
        assertNotNull(mutationTx)
        assertEquals("target_1", mutationTx.accountId)

        // Verify target_2 was NOT modified or rewritten:
        val dbAcc2 = dbDevice1.localAccountDao().getByIdOneShot("target_2")
        assertEquals(5000.0, dbAcc2?.debtIqd ?: 0.0, 0.0001)

        // Verify target_1 was updated:
        val dbAcc1 = dbDevice1.localAccountDao().getByIdOneShot("target_1")
        assertEquals(7000.0, dbAcc1?.debtIqd ?: 0.0, 0.0001)

        // Both appear in unified history:
        val history = viewModel1.getUnifiedLedgerForSubscriber(7001, "omar", "target_1").first()
        assertTrue(history.any { it.id == "MUT_1" })
    }

    // =========================================================================
    // STEP 11: ACTUAL UI-PATH SIMULATION
    // =========================================================================
    @Test
    fun testStep11_actualUiPathSimulation_presentationManagerAndDisplayItems() = runBlocking {
        // Two containers for Ahmed
        val accDevA = LocalAccount(id = "ui_acc_A", earthlinkUsername = "ahmed", ispUserIndex = 8001)
        val accDevB = LocalAccount(id = "ui_acc_B", earthlinkUsername = "ahmed", ispUserIndex = 8001)
        dbDevice1.localAccountDao().insert(accDevA)
        dbDevice1.localAccountDao().insert(accDevB)

        // Acc A has renewal charge + paired renewal payment
        val chargeA = ledgerRepo1.addDebt("ui_acc_A", 35000.0, "[RENEW] Monthly subscription", "charge_ui_1")
        val payA = ledgerRepo1.addPayment("ui_acc_A", 35000.0, "[RENEW_PAY] Monthly renewal", "pay_charge_ui_1")

        // Acc B has an independent debt and payment
        val debtB = ledgerRepo1.addDebt("ui_acc_B", 10000.0, "[DEBT] Router purchase", "tx_router_b")
        val payB = ledgerRepo1.addPayment("ui_acc_B", 5000.0, "[PAYMENT] Partial payment", "tx_pay_b")

        // UI Read Path:
        // 1. screen-level account/subscriber resolution:
        val rawUnifiedHistory = viewModel1.getUnifiedLedgerForSubscriber(
            userIndex = 8001,
            username = "ahmed",
            fallbackAccountId = "ui_acc_A"
        ).first()

        assertEquals("Raw unified history has all 4 entries", 4, rawUnifiedHistory.size)

        // 2. HistoryPresentationManager.prepareDisplayLedgerList (charge pairing):
        val displayList = HistoryPresentationManager.prepareDisplayLedgerList(rawUnifiedHistory)

        // charge_ui_1 is paired with pay_charge_ui_1, so charge_ui_1 is folded:
        // pay_charge_ui_1, tx_router_b, tx_pay_b remain
        assertEquals("Paired charge folded correctly; 3 display items remain", 3, displayList.size)
        assertFalse("Folded charge should not be in displayList", displayList.any { it.id == "charge_ui_1" })
        assertTrue("Renewal payment remains in displayList", displayList.any { it.id == "pay_charge_ui_1" })
        assertTrue("Container B debt remains in displayList", displayList.any { it.id == "tx_router_b" })
        assertTrue("Container B payment remains in displayList", displayList.any { it.id == "tx_pay_b" })

        // 3. HistoryPresentationManager.classifyHistoryItem:
        val classifiedItems = displayList.associate { it.id to HistoryPresentationManager.classifyHistoryItem(it, displayList) }
        assertEquals("renew_pay", classifiedItems["pay_charge_ui_1"])
        assertEquals("debt", classifiedItems["tx_router_b"])
        assertEquals("payment", classifiedItems["tx_pay_b"])
    }

    // =========================================================================
    // STEP 12: NEGATIVE REGRESSION — DIFFERENT ISP USER INDEX, SAME USERNAME
    // =========================================================================
    @Test
    fun testStep12_differentIspUserIndex_sameUsername_mustNeverAggregateTogether() = runBlocking {
        // Account A: ispUserIndex = 1001, username = "alice", id = "acc_alice_1001"
        val accA = LocalAccount(
            id = "acc_alice_1001",
            earthlinkUsername = "alice",
            ispUserIndex = 1001,
            displayName = "Alice One"
        )
        // Account B: ispUserIndex = 2002, username = "alice", id = "acc_alice_2002"
        val accB = LocalAccount(
            id = "acc_alice_2002",
            earthlinkUsername = "alice",
            ispUserIndex = 2002,
            displayName = "Alice Two"
        )
        dbDevice1.localAccountDao().insert(accA)
        dbDevice1.localAccountDao().insert(accB)

        ledgerRepo1.addDebt("acc_alice_1001", 10000.0, "Debt for 1001", "TX_1001")
        ledgerRepo1.addPayment("acc_alice_2002", 5000.0, "Payment for 2002", "TX_2002")

        // 1. Query for 1001 even with fallbackAccountId = "acc_alice_2002":
        // Must NOT aggregate acc_alice_2002 because its ispUserIndex (2002) differs from 1001!
        val history1001WithFallback = viewModel1.getUnifiedLedgerForSubscriber(
            userIndex = 1001,
            username = "alice",
            fallbackAccountId = "acc_alice_2002"
        ).first()

        assertEquals("History for 1001 must only contain TX_1001", 1, history1001WithFallback.size)
        assertEquals("TX_1001", history1001WithFallback[0].id)
        assertFalse("TX_2002 must not leak into 1001 history", history1001WithFallback.any { it.id == "TX_2002" })

        // 2. Query for 2002 even with fallbackAccountId = "acc_alice_1001":
        // Must NOT aggregate acc_alice_1001 because its ispUserIndex (1001) differs from 2002!
        val history2002WithFallback = viewModel1.getUnifiedLedgerForSubscriber(
            userIndex = 2002,
            username = "alice",
            fallbackAccountId = "acc_alice_1001"
        ).first()

        assertEquals("History for 2002 must only contain TX_2002", 1, history2002WithFallback.size)
        assertEquals("TX_2002", history2002WithFallback[0].id)
        assertFalse("TX_1001 must not leak into 2002 history", history2002WithFallback.any { it.id == "TX_1001" })

        // 3. Query without fallback:
        val history1001 = viewModel1.getUnifiedLedgerForSubscriber(userIndex = 1001, username = "alice").first()
        val history2002 = viewModel1.getUnifiedLedgerForSubscriber(userIndex = 2002, username = "alice").first()
        assertEquals(listOf("TX_1001"), history1001.map { it.id })
        assertEquals(listOf("TX_2002"), history2002.map { it.id })
    }

    // =========================================================================
    // STEP 13: FINANCIAL PRESENTATION SEMANTICS — NO CROSS-CONTAINER CORRUPTION
    // =========================================================================
    @Test
    fun testStep13_financialPresentation_multiContainerDoesNotCorruptRunningBalances() = runBlocking {
        // Scenario from Step 3:
        // Logical subscriber: AHMED, ispUserIndex = 1001
        // Physical account A: openingDebt = 10,000, debt = 15,000, L1 amount = +5,000 (debtAfterIqd = 15,000)
        // Physical account B: openingDebt = 50,000, debt = 30,000, L2 amount = -20,000 (debtAfterIqd = 30,000)
        val accA = LocalAccount(
            id = "A",
            earthlinkUsername = "ahmed",
            ispUserIndex = 1001,
            openingDebtIqd = 10000.0,
            debtIqd = 15000.0
        )
        val accB = LocalAccount(
            id = "B",
            earthlinkUsername = "ahmed",
            ispUserIndex = 1001,
            openingDebtIqd = 50000.0,
            debtIqd = 30000.0
        )
        dbDevice1.localAccountDao().insert(accA)
        dbDevice1.localAccountDao().insert(accB)

        val txL1 = LocalLedgerEntry(
            id = "L1",
            accountId = "A",
            typeRaw = "took",
            amountIqd = 5000.0,
            debtAfterIqd = 10000.0 + 5000.0, // 15000.0
            occurredAt = 1000L
        )
        val txL2 = LocalLedgerEntry(
            id = "L2",
            accountId = "B",
            typeRaw = "gave",
            amountIqd = 20000.0,
            debtAfterIqd = 50000.0 - 20000.0, // 30000.0
            occurredAt = 2000L
        )
        dbDevice1.localLedgerEntryDao().insert(txL1)
        dbDevice1.localLedgerEntryDao().insert(txL2)

        // 1. Both rows visible in unified history:
        val unified = viewModel1.getUnifiedLedgerForSubscriber(1001, "ahmed", "A").first()
        assertEquals("Both rows must be visible in unified history", 2, unified.size)
        assertEquals(setOf("L1", "L2"), unified.map { it.id }.toSet())

        // 2. Presentation Balances when viewing via matchingAccount = A:
        val balancesMapA = HistoryPresentationManager.computeEntryBalancesMap(unified, accA)
        val (postDebtL1FromA, postAdvL1FromA) = HistoryPresentationManager.resolveRowPostBalances(txL1, accA, balancesMapA)
        val (postDebtL2FromA, postAdvL2FromA) = HistoryPresentationManager.resolveRowPostBalances(txL2, accA, balancesMapA)

        // L1 is from container A -> running debt is openingDebt(10,000) + 5,000 = 15,000:
        assertEquals("L1 debt must be 15,000", 15000.0, postDebtL1FromA, 0.0001)
        assertEquals("L1 advance must be 0", 0.0, postAdvL1FromA, 0.0001)

        // L2 is from container B -> must preserve B's actual stored debtAfterIqd (30,000):
        // It MUST NOT treat B's transaction as if it belonged to A's lineage (which would yield 15,000 - 20,000 = -5,000 advance!):
        assertEquals("L2 debt must be 30,000 from B's stored debt", 30000.0, postDebtL2FromA, 0.0001)
        assertEquals("L2 advance must be 0, NOT corrupted to 5,000", 0.0, postAdvL2FromA, 0.0001)

        // 3. Presentation Balances when viewing via matchingAccount = B:
        val balancesMapB = HistoryPresentationManager.computeEntryBalancesMap(unified, accB)
        val (postDebtL1FromB, postAdvL1FromB) = HistoryPresentationManager.resolveRowPostBalances(txL1, accB, balancesMapB)
        val (postDebtL2FromB, postAdvL2FromB) = HistoryPresentationManager.resolveRowPostBalances(txL2, accB, balancesMapB)

        // L1 is from container A -> must preserve A's actual stored debtAfterIqd (15,000):
        // It MUST NOT treat A's transaction as if it belonged to B's lineage (which would yield 50,000 + 5,000 = 55,000)!
        assertEquals("L1 debt must be 15,000 from A's stored debt", 15000.0, postDebtL1FromB, 0.0001)
        assertEquals("L1 advance must be 0", 0.0, postAdvL1FromB, 0.0001)

        // L2 is from container B -> running debt is openingDebt(50,000) - 20,000 = 30,000:
        assertEquals("L2 debt must be 30,000", 30000.0, postDebtL2FromB, 0.0001)
        assertEquals("L2 advance must be 0", 0.0, postAdvL2FromB, 0.0001)

        // 4. Physical rows remain untouched:
        val storedL1 = dbDevice1.localLedgerEntryDao().getByIdOneShot("L1")
        val storedL2 = dbDevice1.localLedgerEntryDao().getByIdOneShot("L2")
        assertEquals("A", storedL1?.accountId)
        assertEquals("B", storedL2?.accountId)
        assertEquals(15000.0, storedL1?.debtAfterIqd ?: 0.0, 0.0001)
        assertEquals(30000.0, storedL2?.debtAfterIqd ?: 0.0, 0.0001)
    }

    // =========================================================================
    // STEP 14: LOCAL ACCOUNTS VIEW MODEL — UNIFIED READ WITH ISOLATED MUTATIONS
    // =========================================================================
    @Test
    fun testStep14_localAccountsViewModel_selectAccount_loadsUnifiedSubscriberHistory() = runBlocking {
        // Two containers for subscriber Ahmed (ispUserIndex = 1001)
        val accA = LocalAccount(
            id = "acc_ahmed_vm_A",
            earthlinkUsername = "ahmed_vm",
            ispUserIndex = 1001,
            displayName = "Ahmed Container A",
            debtIqd = 15000.0
        )
        val accB = LocalAccount(
            id = "acc_ahmed_vm_B",
            earthlinkUsername = "ahmed_vm",
            ispUserIndex = 1001,
            displayName = "Ahmed Container B",
            debtIqd = 25000.0
        )
        // Separate subscriber Mohamed (ispUserIndex = 2002)
        val accC = LocalAccount(
            id = "acc_mohamed_vm_C",
            earthlinkUsername = "mohamed_vm",
            ispUserIndex = 2002,
            displayName = "Mohamed Container C",
            debtIqd = 5000.0
        )
        dbDevice1.localAccountDao().insert(accA)
        dbDevice1.localAccountDao().insert(accB)
        dbDevice1.localAccountDao().insert(accC)

        ledgerRepo1.addDebt("acc_ahmed_vm_A", 10000.0, "Debt A1", "TX_A1")
        ledgerRepo1.addDebt("acc_ahmed_vm_A", 5000.0, "Debt A2", "TX_A2")
        ledgerRepo1.addDebt("acc_ahmed_vm_B", 20000.0, "Debt B1", "TX_B1")
        ledgerRepo1.addPayment("acc_ahmed_vm_B", 5000.0, "Payment B2", "TX_B2")
        ledgerRepo1.addDebt("acc_mohamed_vm_C", 5000.0, "Debt C1", "TX_C1")

        val audit1 = AuditRepositoryImpl(dbDevice1, dbDevice1.auditLogDao())
        val utower1 = UtowerImportRepositoryImpl(
            context = context,
            database = dbDevice1,
            batchDao = dbDevice1.importBatchDao(),
            accountDao = dbDevice1.localAccountDao(),
            ledgerDao = dbDevice1.localLedgerEntryDao(),
            outboxDao = dbDevice1.syncOutboxDao(),
            auditRepo = audit1
        )

        val localVm = LocalAccountsViewModel(
            localRepo = accountRepo1,
            ledgerRepo = ledgerRepo1,
            utowerRepo = utower1,
            audit = audit1,
            syncRepo = DummySyncRepo(),
            appDatabase = dbDevice1,
            prefs = prefs1
        )

        // 1. Select Container A:
        localVm.selectAccount(accA)

        // Allow coroutine flow collection:
        val readFeed = withTimeout(5000) {
            localVm.ledgerEntries.first { it.size == 4 }
        }
        assertEquals("Unified feed must contain all 4 transactions across containers A and B", 4, readFeed.size)
        val feedIds = readFeed.map { it.id }.toSet()
        assertEquals(setOf("TX_A1", "TX_A2", "TX_B1", "TX_B2"), feedIds)
        assertFalse("Must never leak Mohamed's transaction TX_C1 into Ahmed's feed", feedIds.contains("TX_C1"))

        // 2. Perform local mutation targeting Container A:
        localVm.addPaymentLocal("acc_ahmed_vm_A", 4000.0, "Cash on A")

        // 3. Verify Container A was updated:
        // Initial 15,000 + 10,000 (TX_A1) + 5,000 (TX_A2) - 4,000 (Payment) = 26,000.0
        val freshA = dbDevice1.localAccountDao().getByIdOneShot("acc_ahmed_vm_A")
        assertEquals(26000.0, freshA?.debtIqd ?: 0.0, 0.0001)

        // 4. Verify Container B remains STRICTLY UNTOUCHED:
        // Initial 25,000 + 20,000 (TX_B1) - 5,000 (TX_B2) = 40,000.0
        val freshB = dbDevice1.localAccountDao().getByIdOneShot("acc_ahmed_vm_B")
        assertEquals(40000.0, freshB?.debtIqd ?: 0.0, 0.0001)

        // 5. Verify physical storage separation (no container merging or row reparenting):
        val allAhmedAccounts = dbDevice1.localAccountDao().findActiveAccountsByIspUserIndex(1001)
        assertEquals("Two distinct physical accounts remain in SQLite", 2, allAhmedAccounts.size)
        val storedTxsA = dbDevice1.localLedgerEntryDao().getByAccountIdOneShot("acc_ahmed_vm_A")
        val storedTxsB = dbDevice1.localLedgerEntryDao().getByAccountIdOneShot("acc_ahmed_vm_B")
        assertEquals("Container A has 3 transactions (A1, A2, + payment)", 3, storedTxsA.size)
        assertEquals("Container B has 2 transactions (B1, B2)", 2, storedTxsB.size)
        assertTrue(storedTxsA.all { it.accountId == "acc_ahmed_vm_A" })
        assertTrue(storedTxsB.all { it.accountId == "acc_ahmed_vm_B" })
    }

    // =========================================================================
    // STEP 15: FINANCIAL PRESENTATION — ADVANCE CREDIT & SETTLED BALANCES
    // =========================================================================
    @Test
    fun testStep15_financialPresentation_advanceCreditAndSettledBalancesAcrossContainers() = runBlocking {
        // Logical subscriber: SAMI, ispUserIndex = 3003
        // Container 1: Opening Advance = 20,000, debt = 0. Transaction: 15,000 renewal charge -> Remaining Advance = 5,000
        val acc1 = LocalAccount(
            id = "acc_sami_1",
            earthlinkUsername = "sami",
            ispUserIndex = 3003,
            openingDebtIqd = 0.0,
            openingAdvanceIqd = 20000.0,
            debtIqd = 0.0,
            advanceIqd = 5000.0
        )
        // Container 2: Opening Debt = 10,000. Transaction: 10,000 payment -> Remaining Debt = 0, Advance = 0 (settled)
        val acc2 = LocalAccount(
            id = "acc_sami_2",
            earthlinkUsername = "sami",
            ispUserIndex = 3003,
            openingDebtIqd = 10000.0,
            openingAdvanceIqd = 0.0,
            debtIqd = 0.0,
            advanceIqd = 0.0
        )
        dbDevice1.localAccountDao().insert(acc1)
        dbDevice1.localAccountDao().insert(acc2)

        val txS1 = LocalLedgerEntry(
            id = "S1_CHARGE",
            accountId = "acc_sami_1",
            typeRaw = "took",
            amountIqd = 15000.0,
            debtAfterIqd = -5000.0, // Stored legacy convention for 5,000 advance
            occurredAt = 1000L
        )
        val txS2 = LocalLedgerEntry(
            id = "S2_PAYMENT",
            accountId = "acc_sami_2",
            typeRaw = "gave",
            amountIqd = 10000.0,
            debtAfterIqd = 0.0, // Stored debt = 0 (settled)
            occurredAt = 2000L
        )
        dbDevice1.localLedgerEntryDao().insert(txS1)
        dbDevice1.localLedgerEntryDao().insert(txS2)

        val unified = viewModel1.getUnifiedLedgerForSubscriber(3003, "sami", "acc_sami_1").first()
        assertEquals(2, unified.size)

        // Case A: Presentation viewing from Container 1 (acc1):
        val balancesMapA = HistoryPresentationManager.computeEntryBalancesMap(unified, acc1)
        val (debtS1FromA, advS1FromA) = HistoryPresentationManager.resolveRowPostBalances(txS1, acc1, balancesMapA)
        val (debtS2FromA, advS2FromA) = HistoryPresentationManager.resolveRowPostBalances(txS2, acc1, balancesMapA)

        // S1 is acc1's own entry: opening advance 20,000 - 15,000 = 5,000 advance credit:
        assertEquals("S1 remaining debt must be 0", 0.0, debtS1FromA, 0.0001)
        assertEquals("S1 remaining advance must be 5,000", 5000.0, advS1FromA, 0.0001)

        // S2 is acc2's foreign entry: must preserve acc2's stored 0 debt without corrupting acc1's advance:
        assertEquals("S2 debt from foreign container must be 0", 0.0, debtS2FromA, 0.0001)
        assertEquals("S2 advance from foreign container must be 0", 0.0, advS2FromA, 0.0001)

        // Case B: Presentation viewing from Container 2 (acc2):
        val balancesMapB = HistoryPresentationManager.computeEntryBalancesMap(unified, acc2)
        val (debtS1FromB, advS1FromB) = HistoryPresentationManager.resolveRowPostBalances(txS1, acc2, balancesMapB)
        val (debtS2FromB, advS2FromB) = HistoryPresentationManager.resolveRowPostBalances(txS2, acc2, balancesMapB)

        // S2 is acc2's own entry: opening debt 10,000 - 10,000 payment = 0 debt, 0 advance (settled):
        assertEquals("S2 remaining debt must be 0 (settled)", 0.0, debtS2FromB, 0.0001)
        assertEquals("S2 advance must be 0", 0.0, advS2FromB, 0.0001)

        // S1 is acc1's foreign entry: must preserve acc1's stored advance (-5000 -> 5000 advance credit):
        assertEquals("S1 debt from foreign container must be 0", 0.0, debtS1FromB, 0.0001)
        assertEquals("S1 advance credit from foreign container must be 5,000", 5000.0, advS1FromB, 0.0001)
    }

    // =========================================================================
    // STEP 16: END-TO-END MULTI-CONTAINER SIMULATION (MUTATION & CROSS-DEVICE SYNC)
    // =========================================================================
    @Test
    fun testStep16_endToEndMultiContainerSimulation_bidirectionalSyncAndPresentation() = runBlocking {
        // DEVICE 1: Subscriber KHALID (ispUserIndex = 9001) has two physical containers:
        // Container 1: acc_khalid_dev1_A (Contract 2025)
        // Container 2: acc_khalid_dev1_B (Contract 2026)
        val accDev1A = LocalAccount(
            id = "acc_khalid_dev1_A",
            earthlinkUsername = "khalid",
            ispUserIndex = 9001,
            displayName = "Khalid 2025",
            debtIqd = 10000.0
        )
        val accDev1B = LocalAccount(
            id = "acc_khalid_dev1_B",
            earthlinkUsername = "khalid",
            ispUserIndex = 9001,
            displayName = "Khalid 2026",
            debtIqd = 20000.0
        )
        dbDevice1.localAccountDao().insert(accDev1A)
        dbDevice1.localAccountDao().insert(accDev1B)

        val txK1 = ledgerRepo1.addDebt("acc_khalid_dev1_A", 10000.0, "2025 Pack", "TX_K1")
        val txK2 = ledgerRepo1.addDebt("acc_khalid_dev1_B", 15000.0, "2026 Pack", "TX_K2")
        val txK3 = ledgerRepo1.addDebt("acc_khalid_dev1_B", 5000.0, "2026 Addon", "TX_K3")

        // 1. Verify Device 1 unified read view before sync:
        val dev1Unified = viewModel1.getUnifiedLedgerForSubscriber(9001, "khalid", "acc_khalid_dev1_A").first()
        assertEquals(3, dev1Unified.size)
        assertEquals(setOf("TX_K1", "TX_K2", "TX_K3"), dev1Unified.map { it.id }.toSet())

        // 2. Sync physical account containers from Device 1 to Device 2:
        coordinator2.processEvent(RemoteEvent.AccountUpsert(accDev1A.id, 100L, RemoteEventSource.REALTIME, accDev1A))
        coordinator2.processEvent(RemoteEvent.AccountUpsert(accDev1B.id, 100L, RemoteEventSource.REALTIME, accDev1B))

        // Replay all outbox events from Device 1 to Device 2:
        val outboxEventsDev1 = dbDevice1.syncOutboxDao().getPending()
        for (event in outboxEventsDev1) {
            val remoteEv = when (event.entityType) {
                "local_accounts" -> {
                    val acc = dbDevice1.localAccountDao().getByIdOneShot(event.entityId)!!
                    RemoteEvent.AccountUpsert(
                        acc.id,
                        1L,
                        RemoteEventSource.PULL,
                        acc
                    )
                }
                "local_ledger_entries" -> {
                    val tx = dbDevice1.localLedgerEntryDao().getByIdOneShot(event.entityId)!!
                    RemoteEvent.LedgerUpsert(
                        tx.id,
                        1L,
                        RemoteEventSource.PULL,
                        tx
                    )
                }
                else -> null
            }
            if (remoteEv != null) {
                coordinator2.processEvent(remoteEv)
            }
        }

        // 3. Verify Device 2 now has separate physical containers and full unified history:
        val dev2Containers = dbDevice2.localAccountDao().findActiveAccountsByIspUserIndex(9001)
        assertEquals("Device 2 must have 2 distinct physical containers", 2, dev2Containers.size)
        assertEquals(setOf("acc_khalid_dev1_A", "acc_khalid_dev1_B"), dev2Containers.map { it.id }.toSet())

        val dev2Unified = viewModel2.getUnifiedLedgerForSubscriber(9001, "khalid", "acc_khalid_dev1_A").first()
        assertEquals("Device 2 unified history must contain all 3 transactions", 3, dev2Unified.size)
        assertEquals(setOf("TX_K1", "TX_K2", "TX_K3"), dev2Unified.map { it.id }.toSet())

        // 4. Device 2 performs a mutation strictly targeting Container B:
        val txK4 = ledgerRepo2.addPayment("acc_khalid_dev1_B", 7000.0, "Payment on Dev 2", "TX_K4")
        assertNotNull(txK4)
        assertEquals("acc_khalid_dev1_B", txK4.accountId)

        // Device 2's container A is NOT modified by Container B mutation:
        val dev2AccA = dbDevice2.localAccountDao().getByIdOneShot("acc_khalid_dev1_A")
        assertEquals(10000.0, dev2AccA?.debtIqd ?: 0.0, 0.0001)

        // Device 2's unified history immediately reflects 4 entries:
        val dev2UnifiedUpdated = viewModel2.getUnifiedLedgerForSubscriber(9001, "khalid", "acc_khalid_dev1_A").first()
        assertEquals(4, dev2UnifiedUpdated.size)
        assertTrue(dev2UnifiedUpdated.any { it.id == "TX_K4" })

        // 5. Sync mutation K4 from Device 2 to Device 1:
        coordinator1.processEvent(
            RemoteEvent.LedgerUpsert(
                txK4.id,
                2L,
                RemoteEventSource.PULL,
                txK4
            )
        )

        // 6. Device 1 now also presents 4 entries in unified history:
        val dev1UnifiedUpdated = viewModel1.getUnifiedLedgerForSubscriber(9001, "khalid", "acc_khalid_dev1_A").first()
        assertEquals("Device 1 unified history now reflects all 4 entries", 4, dev1UnifiedUpdated.size)
        assertTrue(dev1UnifiedUpdated.any { it.id == "TX_K4" })

        // 7. Verify physical invariants on both devices:
        // Container A: On Device 1, 10,000 + 10,000 (TX_K1) = 20,000. On Device 2, synced baseline is 10,000. Both untouched by Container B mutation.
        val finalDev1A = dbDevice1.localAccountDao().getByIdOneShot("acc_khalid_dev1_A")
        val finalDev2A = dbDevice2.localAccountDao().getByIdOneShot("acc_khalid_dev1_A")
        assertEquals("Device 1 Container A debt untouched", 20000.0, finalDev1A?.debtIqd ?: 0.0, 0.0001)
        assertEquals("Device 2 Container A debt untouched", 10000.0, finalDev2A?.debtIqd ?: 0.0, 0.0001)

        val txK4Dev1 = dbDevice1.localLedgerEntryDao().getByIdOneShot("TX_K4")
        val txK4Dev2 = dbDevice2.localLedgerEntryDao().getByIdOneShot("TX_K4")
        assertEquals("acc_khalid_dev1_B", txK4Dev1?.accountId)
        assertEquals("acc_khalid_dev1_B", txK4Dev2?.accountId)
    }
}
