package com.example.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.*
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.LocalAccountRepository
import com.example.domain.repository.LocalLedgerRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * Permanent regression suite for Defect R3 (Historical Account Matching and Username Recycling).
 *
 * Mandate (AGENTS.md / R3 Fix Specification):
 * 1. Historical account can NEVER be hijacked by a new subscriber after ISP username recycling.
 * 2. Historical financial state (debt, opening debt, ledger entries, provenance ID) is strictly immutable to incoming different entities.
 * 3. Exact provenance equality (same sourceExternalId) remains supported for legitimate replay without reactivating the account.
 * 4. Phone and Name fallback stages can NEVER cross the historical boundary.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class HistoricalSubscriberMatchingSafetyTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var accountDao: LocalAccountDao
    private lateinit var ledgerDao: LocalLedgerEntryDao
    private lateinit var outboxDao: SyncOutboxDao
    private lateinit var pendingDao: PendingExternalOperationDao
    private lateinit var accountRepo: LocalAccountRepository
    private lateinit var ledgerRepo: LocalLedgerRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        accountDao = db.localAccountDao()
        ledgerDao = db.localLedgerEntryDao()
        outboxDao = db.syncOutboxDao()
        pendingDao = db.pendingExternalOperationDao()

        accountRepo = LocalAccountRepositoryImpl(
            database = db,
            accountDao = accountDao,
            outboxDao = outboxDao
        )
        ledgerRepo = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = ledgerDao,
            accountDao = accountDao,
            outboxDao = outboxDao,
            pendingDao = pendingDao
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun createUtowerFile(
        key: String,
        username: String?,
        name: String,
        phone: String,
        debtUnits: Double,
        txId: String? = null,
        txAmountUnits: Double = 0.0
    ): File {
        val file = File(context.cacheDir, "utower_r3_${UUID.randomUUID()}.json")
        val root = JSONObject().apply {
            val liveObj = JSONObject().apply {
                val subObj = JSONObject().apply {
                    val liveData = JSONObject().apply {
                        if (username != null) put("userName", username)
                        put("name", name)
                        put("phone", phone)
                        put("currentPrice", 35)
                    }
                    val utowerData = JSONObject().apply {
                        put("debts", debtUnits)
                        put("phoneNumber", phone)
                    }
                    put("live", liveData)
                    put("utower", utowerData)
                }
                put(key, subObj)
            }
            put("live_users", liveObj)

            if (txId != null) {
                val txMap = JSONObject().apply {
                    val tx = JSONObject().apply {
                        put("toWho", key)
                        put("amount", txAmountUnits)
                        put("type", "took")
                        put("date", "2026-01-01 12:00:00")
                        put("totalDebitAfter", debtUnits)
                    }
                    put(txId, tx)
                }
                put("messagesofhistory", txMap)
            }
        }
        file.writeText(root.toString(), Charsets.UTF_8)
        return file
    }

    @Test
    fun testCoreR3_recycledUsername_doesNotMergeIntoHistoricalAccount() = runBlocking {
        val importer = UtowerImporter(context, db)

        // 1. Initial subscriber imported
        val file1 = createUtowerFile(
            key = "e_11111",
            username = "recycled_user@sacx",
            name = "Old Customer Ahmad",
            phone = "07700000001",
            debtUnits = 50.0,
            txId = "tx_101",
            txAmountUnits = 50.0
        )
        val res1 = importer.importFromFile(file1, shouldReplace = false)
        assertTrue(res1.success)

        val acc1 = accountDao.getAllPersistedOneShot().first()
        val txs1 = ledgerDao.getByAccountIdOneShot(acc1.id)
        assertEquals(1, txs1.size)
        assertEquals(50000.0, acc1.debtIqd, 0.0)

        // 2. Convert to historical through the real production delete path
        accountRepo.deleteAccount(acc1.id)
        val historicalAcc = accountDao.getByIdOneShot(acc1.id)!!
        assertTrue(historicalAcc.isHistoryOnlySubscriber)

        // 3. Incoming second subscriber with same username but DIFFERENT external ID e_22222
        val file2 = createUtowerFile(
            key = "e_22222",
            username = "recycled_user@sacx",
            name = "New Customer Ali",
            phone = "07700000002",
            debtUnits = 10.0,
            txId = "tx_202",
            txAmountUnits = 10.0
        )
        val res2 = importer.importFromFile(file2, shouldReplace = false)
        assertTrue(res2.success)

        // 4. Assert: MUST NOT MERGE. Exactly 2 accounts must exist
        val allAccounts = accountDao.getAllPersistedOneShot()
        assertEquals("Must create a new distinct account, total accounts must be 2", 2, allAccounts.size)

        val persistedHistorical = allAccounts.first { it.id == historicalAcc.id }
        assertEquals("Historical ID must remain unchanged", historicalAcc.id, persistedHistorical.id)
        assertEquals("Historical sourceExternalId must remain e_11111", "e_11111", persistedHistorical.sourceExternalId)
        assertEquals("Historical username must remain recycled_user@sacx", "recycled_user@sacx", persistedHistorical.earthlinkUsername)
        assertEquals("Historical displayName must remain Old Customer Ahmad", "Old Customer Ahmad", persistedHistorical.displayName)
        assertEquals("Historical debt must remain 50,000 IQD", 50000.0, persistedHistorical.debtIqd, 0.0)
        assertEquals("Historical opening debt must remain 50,000 IQD", 50000.0, persistedHistorical.openingDebtIqd, 0.0)
        assertTrue("Historical status must remain true", persistedHistorical.isHistoryOnlySubscriber)

        val historicalLedgers = ledgerDao.getByAccountIdOneShot(persistedHistorical.id)
        assertEquals("Historical ledger count must remain exactly 1", 1, historicalLedgers.size)
        assertEquals("tx_101", historicalLedgers.first().sourceExternalId)
        assertEquals(50000.0, historicalLedgers.first().amountIqd, 0.0)

        val newAcc = allAccounts.first { it.id != historicalAcc.id }
        assertEquals("New account must have sourceExternalId e_22222", "e_22222", newAcc.sourceExternalId)
        assertEquals("New account must have same username", "recycled_user@sacx", newAcc.earthlinkUsername)
        assertEquals("New account must have name New Customer Ali", "New Customer Ali", newAcc.displayName)
        assertEquals("New account debt must be 10,000 IQD", 10000.0, newAcc.debtIqd, 0.0)
        assertFalse("New account must be active", newAcc.isHistoryOnlySubscriber)

        val newLedgers = ledgerDao.getByAccountIdOneShot(newAcc.id)
        assertEquals("New transaction must be attached exclusively to new account", 1, newLedgers.size)
        assertEquals("tx_202", newLedgers.first().sourceExternalId)
        assertEquals(10000.0, newLedgers.first().amountIqd, 0.0)
    }

    @Test
    fun testReplay_exactProvenanceMatch_updatesExistingHistoricalAccountWithoutReactivation() = runBlocking {
        val importer = UtowerImporter(context, db)

        // Initial import and convert to historical
        val file1 = createUtowerFile(
            key = "e_11111",
            username = "replayed_user@sacx",
            name = "Replayed Ahmad",
            phone = "07700000001",
            debtUnits = 50.0,
            txId = "tx_101",
            txAmountUnits = 50.0
        )
        importer.importFromFile(file1, shouldReplace = false)
        val acc1 = accountDao.getAllPersistedOneShot().first()
        accountRepo.deleteAccount(acc1.id)

        // Re-import with SAME sourceExternalId e_11111 and updated debt
        val file2 = createUtowerFile(
            key = "e_11111",
            username = "replayed_user@sacx",
            name = "Replayed Ahmad",
            phone = "07700000001",
            debtUnits = 75.0,
            txId = "tx_303",
            txAmountUnits = 25.0
        )
        val res2 = importer.importFromFile(file2, shouldReplace = false)
        assertTrue(res2.success)

        val allAccounts = accountDao.getAllPersistedOneShot()
        assertEquals("Replay of same entity must not duplicate account", 1, allAccounts.size)

        val persisted = allAccounts.first()
        assertEquals(acc1.id, persisted.id)
        assertEquals("e_11111", persisted.sourceExternalId)
        assertTrue("Historical status MUST remain true on replay (no reactivation)", persisted.isHistoryOnlySubscriber)
    }

    @Test
    fun testPhoneMatching_cannotCrossHistoricalBoundary() {
        val historicalCandidate = LocalAccount(
            id = "acc_hist_1",
            sourceExternalId = "e_11111",
            displayName = "Historical Person",
            earthlinkUsername = "hist_user@sacx",
            phone1 = "07709999999",
            debtIqd = 30000.0,
            isHistoryOnlySubscriber = true
        )

        // Incoming subscriber has a different username, different extId, but same phone number
        val matched = SubscriberMatcher.matchSubscriber(
            candidates = listOf(historicalCandidate),
            extId = "e_22222",
            username = "other_user@sacx",
            phone = "07709999999"
        )
        assertNull("Historical account must NEVER be matched via phone fallback", matched)
    }

    @Test
    fun testNameMatching_cannotCrossHistoricalBoundary() {
        val historicalCandidate = LocalAccount(
            id = "acc_hist_2",
            sourceExternalId = "e_11111",
            displayName = "Same Common Name",
            earthlinkUsername = "hist_user@sacx",
            phone1 = "07701111111",
            debtIqd = 30000.0,
            isHistoryOnlySubscriber = true
        )

        // Incoming subscriber has different username, different extId, but same display name
        val matched = SubscriberMatcher.matchSubscriber(
            candidates = listOf(historicalCandidate),
            extId = "e_22222",
            username = "other_user@sacx",
            name = "Same Common Name"
        )
        assertNull("Historical account must NEVER be matched via name fallback", matched)
    }

    @Test
    fun testActiveAccountBehavior_remainsPreserved() {
        val activeCandidate = LocalAccount(
            id = "acc_act_1",
            sourceExternalId = "e_33333",
            displayName = "Active Subscriber",
            earthlinkUsername = "active_user@sacx",
            phone1 = "07703333333",
            debtIqd = 15000.0,
            isHistoryOnlySubscriber = false
        )

        // Same active identity matches normally
        val matched = SubscriberMatcher.matchSubscriber(
            candidates = listOf(activeCandidate),
            extId = "e_33333",
            username = "active_user@sacx"
        )
        assertNotNull(matched)
        assertEquals("acc_act_1", matched!!.id)
    }

    @Test
    fun testMultipleCandidatesWithSameUsername_selectsActiveCandidateOverHistorical() {
        val historicalCandidate = LocalAccount(
            id = "acc_hist_shared",
            sourceExternalId = "e_old",
            displayName = "Old User",
            earthlinkUsername = "shared_user@sacx",
            isHistoryOnlySubscriber = true
        )
        val activeCandidate = LocalAccount(
            id = "acc_act_shared",
            sourceExternalId = "e_new",
            displayName = "New User",
            earthlinkUsername = "shared_user@sacx",
            isHistoryOnlySubscriber = false
        )

        // Incoming subscriber is the active one (e_new)
        val matched = SubscriberMatcher.matchSubscriber(
            candidates = listOf(historicalCandidate, activeCandidate),
            extId = "e_new",
            username = "shared_user@sacx"
        )
        assertNotNull(matched)
        assertEquals("Must select the active candidate and ignore the historical candidate", "acc_act_shared", matched!!.id)
    }

    @Test
    fun testRecycledUsername_withNullExtId_cannotCrossHistoricalBoundary() {
        val historicalCandidate = LocalAccount(
            id = "acc_hist_null_ext",
            sourceExternalId = "e_old",
            displayName = "Old User",
            earthlinkUsername = "recycled_user@sacx",
            isHistoryOnlySubscriber = true
        )

        // Incoming has recycled username but null external ID
        val matched = SubscriberMatcher.matchSubscriber(
            candidates = listOf(historicalCandidate),
            extId = null,
            username = "recycled_user@sacx"
        )
        assertNull("Historical account must NOT be matched by username when incoming extId is null", matched)
    }
}
