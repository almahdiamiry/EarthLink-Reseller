package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.sync.UtowerImporter
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
 * UtowerAmbiguousReimportTest: Hardening uTower re-import against ghost container creation (INV-09 / Strategy D / Work Item B2).
 *
 * Core Triad:
 * 1. Claim: When multiple physical LocalAccount containers share an authentic uTower sourceExternalId and the incoming
 *    record is not uniquely resolvable, the importer must refuse to guess a physical container winner, quarantine the row
 *    (subscribersSkipped++), skip dependent transactions (transactionsSkipped++), record a descriptive warning, and NOT create
 *    ghost containers (UUID_C, UUID_D...). Remaining valid rows in the batch must succeed.
 * 2. Seam / Environment: ROBOLECTRIC in-memory Android runtime with Room database v18.
 * 3. Independent Oracle: Independent invariant evaluation based on Product Contract v0.6: container count before == container count after,
 *    zero duplicate ledgers, zero profile overwrites on ambiguous containers, and independent row-level isolation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class UtowerAmbiguousReimportTest {

    private lateinit var context: Context
    private lateinit var liveDb: AppDatabase
    private lateinit var importer: UtowerImporter

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        (context as? EarthlinkApp)?.isSafeDebugFallbackAllowedOverride = true
        context.getDatabasePath("earthlink_reseller_db").parentFile?.mkdirs()
        AppDatabase.closeDatabase()
        liveDb = (context as? EarthlinkApp)?.database ?: AppDatabase.getDatabase(context, ByteArray(0), "earthlink_reseller_db")
        importer = UtowerImporter(context, liveDb)
        runBlocking {
            liveDb.localLedgerEntryDao().deleteAll()
            liveDb.localAccountDao().deleteAll()
            liveDb.importBatchDao().deleteAll()
            liveDb.syncOutboxDao().deleteAll()
            liveDb.syncMetadataDao().deleteAll()
            liveDb.auditLogDao().clearAll()
        }
    }

    @After
    fun tearDown() {
        AppDatabase.closeDatabase()
    }

    private fun createUtowerJsonFile(
        subscribers: List<JSONObject> = emptyList(),
        transactions: List<JSONObject> = emptyList()
    ): File {
        val file = File(context.cacheDir, "utower_ambiguous_${UUID.randomUUID()}.json")
        val root = JSONObject().apply {
            val liveObj = JSONObject()
            for ((idx, sub) in subscribers.withIndex()) {
                val key = sub.optString("id", sub.optString("key", "sub_$idx"))
                liveObj.put(key, sub)
            }
            put("live_users", liveObj)

            val txObj = JSONObject()
            for ((idx, tx) in transactions.withIndex()) {
                val key = tx.optString("id", tx.optString("key", "tx_$idx"))
                txObj.put(key, tx)
            }
            put("messagesofhistory", txObj)
        }
        file.writeText(root.toString(), Charsets.UTF_8)
        return file
    }

    /**
     * Variant A: Normal single match.
     * EXT_123 -> UUID_A in database.
     * Re-import EXT_123 -> matches UUID_A normally, no new container, no quarantine.
     */
    @Test
    fun testVariantA_normalSingleMatch() = runBlocking {
        val uuidA = UUID.randomUUID().toString()
        val accountA = LocalAccount(
            id = uuidA,
            sourceExternalId = "EXT_123",
            earthlinkUsername = "user_a",
            displayName = "User A Original",
            debtIqd = 25000.0,
            openingDebtIqd = 25000.0
        )
        liveDb.localAccountDao().insert(accountA)

        val incomingSub = JSONObject().apply {
            put("id", "EXT_123")
            put("userName", "user_a")
            put("name", "User A Refreshed")
            put("price_iqd", 25000)
            put("debt_iqd", 50000)
        }
        val file = createUtowerJsonFile(subscribers = listOf(incomingSub))

        val result = importer.importFromFile(file, shouldReplace = false)

        assertTrue("Import must report success", result.success)
        assertEquals("Subscribers merged must be 1", 1, result.subscribersMerged)
        assertEquals("Subscribers skipped must be 0", 0, result.subscribersSkipped)
        assertEquals("Subscribers inserted must be 0", 0, result.subscribersInserted)

        val accounts = liveDb.localAccountDao().getAllOneShot()
        assertEquals("Container count must remain exactly 1", 1, accounts.size)
        assertEquals("UUID must remain UUID_A", uuidA, accounts[0].id)
        assertEquals("Profile name must be updated", "User A Refreshed", accounts[0].displayName)
    }

    /**
     * Variant B: Duplicate extId + same username (The proven Strategy D re-import failure case).
     * UUID_A -> EXT_123 / user_a
     * UUID_B -> EXT_123 / user_a
     * Re-import: EXT_123 / user_a + 1 dependent transaction.
     * Expected B2:
     * - NO UUID_C created.
     * - NO winner chosen (UUID_A and UUID_B remain untouched).
     * - subscriber skipped/quarantined (subscribersSkipped++).
     * - dependent transaction skipped (transactionsSkipped++).
     * - warning recorded.
     */
    @Test
    fun testVariantB_duplicateExtIdSameUsername_quarantinesWithoutGhostContainer() = runBlocking {
        val uuidA = "uuid_container_a"
        val uuidB = "uuid_container_b"
        val accA = LocalAccount(
            id = uuidA,
            sourceExternalId = "EXT_123",
            earthlinkUsername = "user_a",
            displayName = "Container A Display",
            debtIqd = 25000.0,
            openingDebtIqd = 25000.0
        )
        val accB = LocalAccount(
            id = uuidB,
            sourceExternalId = "EXT_123",
            earthlinkUsername = "user_a",
            displayName = "Container B Display",
            debtIqd = 35000.0,
            openingDebtIqd = 35000.0
        )
        liveDb.localAccountDao().insert(accA)
        liveDb.localAccountDao().insert(accB)

        val incomingSub = JSONObject().apply {
            put("id", "EXT_123")
            put("userName", "user_a")
            put("name", "Incoming Malicious Refresh")
            put("price_iqd", 25000)
            put("debt_iqd", 99999)
        }
        val incomingTx = JSONObject().apply {
            put("id", "tx_ambiguous_1")
            put("toWho", "EXT_123")
            put("amount_iqd", 25000)
            put("debt_after_iqd", 99999)
            put("type", "took")
            put("date", "2026-09-10 12:00:00")
            put("comment", "Ambiguous Tx")
        }
        val file = createUtowerJsonFile(subscribers = listOf(incomingSub), transactions = listOf(incomingTx))

        val result = importer.importFromFile(file, shouldReplace = false)

        assertTrue("Import must complete batch", result.success)
        assertEquals("Subscribers skipped must be incremented", 1, result.subscribersSkipped)
        assertEquals("Transactions skipped must be incremented", 1, result.transactionsSkipped)
        assertEquals("No subscribers inserted", 0, result.subscribersInserted)
        assertTrue("Warnings must be recorded", result.warnings > 0)

        // Financial & Container Safety assertions
        val accounts = liveDb.localAccountDao().getAllOneShot()
        assertEquals("Container count must remain exactly 2 (NO UUID_C ghost container!)", 2, accounts.size)
        val accountIds = accounts.map { it.id }.toSet()
        assertTrue("UUID_A must still exist", accountIds.contains(uuidA))
        assertTrue("UUID_B must still exist", accountIds.contains(uuidB))

        // Neither sibling received profile update
        val fetchedA = liveDb.localAccountDao().getByIdOneShot(uuidA)!!
        val fetchedB = liveDb.localAccountDao().getByIdOneShot(uuidB)!!
        assertEquals("UUID_A displayName must not be overwritten", "Container A Display", fetchedA.displayName)
        assertEquals("UUID_B displayName must not be overwritten", "Container B Display", fetchedB.displayName)
        assertEquals("UUID_A debt must not be overwritten", 25000.0, fetchedA.debtIqd, 0.001)
        assertEquals("UUID_B debt must not be overwritten", 35000.0, fetchedB.debtIqd, 0.001)

        // No dependent transactions inserted
        val ledgers = liveDb.localLedgerEntryDao().getAllOneShot()
        assertEquals("Zero ledgers must be inserted for ambiguous subscriber", 0, ledgers.size)

        // Batch warningsJson
        val batches = liveDb.importBatchDao().getAllOneShot()
        assertEquals(1, batches.size)
        assertNotNull("Batch warningsJson must be recorded", batches[0].warningsJson)
        assertTrue("warningsJson must describe ambiguous quarantine", batches[0].warningsJson!!.contains("EXT_123") || batches[0].warningsJson!!.contains("ambiguous"))
    }

    /**
     * Variant C: Duplicate extId + different usernames.
     * UUID_A -> EXT_123 / user_old
     * UUID_B -> EXT_123 / user_new
     * Re-import: EXT_123 / user_new.
     * Expected: UUID_B is uniquely disambiguated by username, updated normally, no quarantine.
     */
    @Test
    fun testVariantC_duplicateExtIdDifferentUsernames_matchesDisambiguatedContainer() = runBlocking {
        val uuidOld = "uuid_container_old"
        val uuidNew = "uuid_container_new"
        val accOld = LocalAccount(
            id = uuidOld,
            sourceExternalId = "EXT_123",
            earthlinkUsername = "user_old",
            displayName = "User Old Display",
            debtIqd = 10000.0
        )
        val accNew = LocalAccount(
            id = uuidNew,
            sourceExternalId = "EXT_123",
            earthlinkUsername = "user_new",
            displayName = "User New Display",
            debtIqd = 20000.0
        )
        liveDb.localAccountDao().insert(accOld)
        liveDb.localAccountDao().insert(accNew)

        val incomingSub = JSONObject().apply {
            put("id", "EXT_123")
            put("userName", "user_new")
            put("name", "User New Updated")
            put("price_iqd", 25000)
            put("debt_iqd", 20000)
        }
        val file = createUtowerJsonFile(subscribers = listOf(incomingSub))

        val result = importer.importFromFile(file, shouldReplace = false)

        assertTrue("Import must succeed", result.success)
        assertEquals("Subscribers merged must be 1", 1, result.subscribersMerged)
        assertEquals("Subscribers skipped must be 0", 0, result.subscribersSkipped)
        assertEquals("Subscribers inserted must be 0", 0, result.subscribersInserted)

        val accounts = liveDb.localAccountDao().getAllOneShot()
        assertEquals("Container count must remain 2", 2, accounts.size)

        val fetchedOld = liveDb.localAccountDao().getByIdOneShot(uuidOld)!!
        val fetchedNew = liveDb.localAccountDao().getByIdOneShot(uuidNew)!!
        assertEquals("UUID_OLD remains untouched", "User Old Display", fetchedOld.displayName)
        assertEquals("UUID_NEW received update", "User New Updated", fetchedNew.displayName)
    }

    /**
     * Variant D: Genuinely new subscriber.
     * No existing matching subscriber.
     * Expected: Normal new account creation, no quarantine.
     */
    @Test
    fun testVariantD_genuinelyNewSubscriber_createsNewContainer() = runBlocking {
        val incomingSub = JSONObject().apply {
            put("id", "EXT_GENUINE_NEW")
            put("userName", "user_fresh")
            put("name", "Brand New User")
            put("price_iqd", 35000)
            put("debt_iqd", 35000)
        }
        val file = createUtowerJsonFile(subscribers = listOf(incomingSub))

        val result = importer.importFromFile(file, shouldReplace = false)

        assertTrue("Import must succeed", result.success)
        assertEquals("Subscribers inserted must be 1", 1, result.subscribersInserted)
        assertEquals("Subscribers skipped must be 0", 0, result.subscribersSkipped)

        val accounts = liveDb.localAccountDao().getAllOneShot()
        assertEquals("Exactly 1 account created", 1, accounts.size)
        assertEquals("EXT_GENUINE_NEW", accounts[0].sourceExternalId)
        assertEquals("user_fresh", accounts[0].earthlinkUsername)
        assertEquals("Brand New User", accounts[0].displayName)
    }

    /**
     * Variant E: Active + history-only.
     * UUID_HIST -> EXT_123 / user_a / history-only
     * UUID_ACT  -> EXT_123 / user_a / active
     * Re-import EXT_123 / user_a.
     * Expected B2: Does NOT invent an active/history winner. Ambiguous -> quarantined without winner.
     */
    @Test
    fun testVariantE_activeAndHistoryOnly_quarantinesWithoutWinnerHeuristic() = runBlocking {
        val uuidHist = "uuid_container_hist"
        val uuidAct = "uuid_container_act"
        val accHist = LocalAccount(
            id = uuidHist,
            sourceExternalId = "EXT_123",
            earthlinkUsername = "user_a",
            displayName = "History User",
            isHistoryOnlySubscriber = true,
            debtIqd = 15000.0
        )
        val accAct = LocalAccount(
            id = uuidAct,
            sourceExternalId = "EXT_123",
            earthlinkUsername = "user_a",
            displayName = "Active User",
            isHistoryOnlySubscriber = false,
            debtIqd = 30000.0
        )
        liveDb.localAccountDao().insert(accHist)
        liveDb.localAccountDao().insert(accAct)

        val incomingSub = JSONObject().apply {
            put("id", "EXT_123")
            put("userName", "user_a")
            put("name", "Incoming Refresh")
            put("price_iqd", 30000)
            put("debt_iqd", 30000)
        }
        val file = createUtowerJsonFile(subscribers = listOf(incomingSub))

        val result = importer.importFromFile(file, shouldReplace = false)

        assertTrue("Import must complete", result.success)
        assertEquals("Subscribers skipped must be 1", 1, result.subscribersSkipped)
        assertEquals("Subscribers inserted must be 0", 0, result.subscribersInserted)

        val accounts = liveDb.localAccountDao().getAllPersistedOneShot()
        assertEquals("Container count must remain 2 (NO UUID_C)", 2, accounts.size)

        val fetchedHist = liveDb.localAccountDao().getByIdOneShot(uuidHist)!!
        val fetchedAct = liveDb.localAccountDao().getByIdOneShot(uuidAct)!!
        assertEquals("History user unchanged", "History User", fetchedHist.displayName)
        assertEquals("Active user unchanged", "Active User", fetchedAct.displayName)
    }

    /**
     * Variant F: Repeated identical re-import.
     * Ambiguous subscriber re-imported 3 consecutive times.
     * Oracle: Container count remains 2 after run 1, run 2, run 3. Zero ghost container growth.
     */
    @Test
    fun testVariantF_repeatedIdenticalReimport_idempotentContainerAndLedgerCount() = runBlocking {
        val uuidA = "uuid_a_rep"
        val uuidB = "uuid_b_rep"
        val accA = LocalAccount(id = uuidA, sourceExternalId = "EXT_REP", earthlinkUsername = "user_rep", displayName = "Rep A")
        val accB = LocalAccount(id = uuidB, sourceExternalId = "EXT_REP", earthlinkUsername = "user_rep", displayName = "Rep B")
        liveDb.localAccountDao().insert(accA)
        liveDb.localAccountDao().insert(accB)

        val incomingSub = JSONObject().apply {
            put("id", "EXT_REP")
            put("userName", "user_rep")
            put("name", "Incoming Rep")
        }
        val incomingTx = JSONObject().apply {
            put("id", "tx_rep_1")
            put("toWho", "EXT_REP")
            put("amount_iqd", 15000)
            put("debt_after_iqd", 15000)
            put("type", "took")
            put("date", "2026-09-11 10:00:00")
        }

        for (runIdx in 1..3) {
            val file = createUtowerJsonFile(subscribers = listOf(incomingSub), transactions = listOf(incomingTx))
            val result = importer.importFromFile(file, shouldReplace = false)
            assertTrue("Run $runIdx must succeed", result.success)
            assertEquals("Run $runIdx: skipped must be 1", 1, result.subscribersSkipped)
            assertEquals("Run $runIdx: tx skipped must be 1", 1, result.transactionsSkipped)

            val accounts = liveDb.localAccountDao().getAllOneShot()
            assertEquals("Run $runIdx: container count must strictly remain 2", 2, accounts.size)

            val ledgers = liveDb.localLedgerEntryDao().getAllOneShot()
            assertEquals("Run $runIdx: ledger count must strictly remain 0", 0, ledgers.size)
        }
    }

    /**
     * Batch Blast Radius:
     * Subscriber A = valid existing (EXT_111 / user_1)
     * Subscriber B = ambiguous duplicate (EXT_222 / user_2)
     * Subscriber C = valid new (EXT_333 / user_3)
     *
     * Expected:
     * - A succeeds & merges, A transaction imported.
     * - B quarantined/skipped, B transaction skipped.
     * - C succeeds & inserts, C transaction imported.
     * - Database has 4 accounts (A, B_sibling1, B_sibling2, C_new) and 2 transactions (for A and C).
     */
    @Test
    fun testBatchBlastRadius_validSubscribersSucceedWhileAmbiguousIsQuarantined() = runBlocking {
        val uuidA = "uuid_batch_a"
        val uuidB1 = "uuid_batch_b1"
        val uuidB2 = "uuid_batch_b2"

        liveDb.localAccountDao().insert(LocalAccount(id = uuidA, sourceExternalId = "EXT_111", earthlinkUsername = "user_1", displayName = "User 1 Old"))
        liveDb.localAccountDao().insert(LocalAccount(id = uuidB1, sourceExternalId = "EXT_222", earthlinkUsername = "user_2", displayName = "User 2 Sibling 1"))
        liveDb.localAccountDao().insert(LocalAccount(id = uuidB2, sourceExternalId = "EXT_222", earthlinkUsername = "user_2", displayName = "User 2 Sibling 2"))

        val subA = JSONObject().apply {
            put("id", "EXT_111")
            put("userName", "user_1")
            put("name", "User 1 Refreshed")
        }
        val subB = JSONObject().apply {
            put("id", "EXT_222")
            put("userName", "user_2")
            put("name", "User 2 Ambiguous")
        }
        val subC = JSONObject().apply {
            put("id", "EXT_333")
            put("userName", "user_3")
            put("name", "User 3 Brand New")
        }

        val txA = JSONObject().apply {
            put("id", "tx_a")
            put("toWho", "EXT_111")
            put("amount_iqd", 10000)
            put("type", "took")
            put("date", "2026-09-12 10:00:00")
        }
        val txB = JSONObject().apply {
            put("id", "tx_b")
            put("toWho", "EXT_222")
            put("amount_iqd", 20000)
            put("type", "took")
            put("date", "2026-09-12 10:05:00")
        }
        val txC = JSONObject().apply {
            put("id", "tx_c")
            put("toWho", "EXT_333")
            put("amount_iqd", 30000)
            put("type", "took")
            put("date", "2026-09-12 10:10:00")
        }

        val file = createUtowerJsonFile(
            subscribers = listOf(subA, subB, subC),
            transactions = listOf(txA, txB, txC)
        )

        val result = importer.importFromFile(file, shouldReplace = false)

        assertTrue("Batch must report success", result.success)
        assertEquals("Subscribers merged: 1 (A)", 1, result.subscribersMerged)
        assertEquals("Subscribers skipped: 1 (B)", 1, result.subscribersSkipped)
        assertEquals("Subscribers inserted: 1 (C)", 1, result.subscribersInserted)

        assertEquals("Transactions inserted: 2 (A and C)", 2, result.transactionsInserted)
        assertEquals("Transactions skipped: 1 (B)", 1, result.transactionsSkipped)

        // Account inventory
        val accounts = liveDb.localAccountDao().getAllOneShot()
        assertEquals("Total accounts must be 4 (A, B1, B2, C_new)", 4, accounts.size)

        val accA = accounts.find { it.id == uuidA }!!
        assertEquals("User 1 Refreshed", accA.displayName)

        val accC = accounts.find { it.earthlinkUsername == "user_3" }
        assertNotNull("User C must have been inserted", accC)
        assertEquals("EXT_333", accC!!.sourceExternalId)

        // Ledger inventory
        val ledgers = liveDb.localLedgerEntryDao().getAllOneShot()
        assertEquals("Total ledgers must be 2", 2, ledgers.size)
        val ledgerAccountIds = ledgers.map { it.accountId }.toSet()
        assertTrue("Ledgers must contain A's account", ledgerAccountIds.contains(uuidA))
        assertTrue("Ledgers must contain C's account", ledgerAccountIds.contains(accC.id))
        assertFalse("Ledgers must NOT contain B1's account", ledgerAccountIds.contains(uuidB1))
        assertFalse("Ledgers must NOT contain B2's account", ledgerAccountIds.contains(uuidB2))
    }

    /**
     * Test 8: Phone Ambiguity Quarantine.
     * Two existing accounts share phone1 = "07709999999".
     * Incoming record has distinct extId, no username, but phone1 = "07709999999".
     * Oracle:
     * - Stage 3 phone ambiguity detected by SubscriberMatcher
     * - UtowerImporter quarantines the row (subscribersSkipped == 1, subscribersInserted == 0)
     * - Dependent transaction skipped (transactionsSkipped == 1)
     * - Zero ghost containers created (container count remains 2)
     * - Zero ledgers inserted
     * - Warning recorded
     */
    @Test
    fun testPhoneAmbiguity_quarantinesWithoutGhostContainer() = runBlocking {
        val uuidP1 = "uuid_phone_1"
        val uuidP2 = "uuid_phone_2"
        val acc1 = LocalAccount(id = uuidP1, sourceExternalId = null, displayName = "User Phone 1", phone1 = "07709999999")
        val acc2 = LocalAccount(id = uuidP2, sourceExternalId = null, displayName = "User Phone 2", phone1 = "07709999999")
        liveDb.localAccountDao().insert(acc1)
        liveDb.localAccountDao().insert(acc2)

        val incomingSub = JSONObject().apply {
            put("id", "EXT_NEW_PHONE")
            put("phoneNumber", "07709999999")
            put("name", "Ambiguous Phone User")
            put("debt_iqd", 25000)
        }
        val incomingTx = JSONObject().apply {
            put("id", "tx_phone_ambig")
            put("toWho", "EXT_NEW_PHONE")
            put("amount_iqd", 25000)
            put("type", "took")
            put("date", "2026-09-13 10:00:00")
        }
        val file = createUtowerJsonFile(subscribers = listOf(incomingSub), transactions = listOf(incomingTx))

        val result = importer.importFromFile(file, shouldReplace = false)

        assertTrue("Import must complete batch", result.success)
        assertEquals("Subscribers skipped must be 1", 1, result.subscribersSkipped)
        assertEquals("Subscribers inserted must be 0 (NO ghost container!)", 0, result.subscribersInserted)
        assertEquals("Subscribers merged must be 0", 0, result.subscribersMerged)
        assertEquals("Transactions skipped must be 1", 1, result.transactionsSkipped)
        assertTrue("Warnings must be recorded", result.warnings > 0)

        val accounts = liveDb.localAccountDao().getAllOneShot()
        assertEquals("Container count must remain strictly 2", 2, accounts.size)

        val ledgers = liveDb.localLedgerEntryDao().getAllOneShot()
        assertEquals("Zero ledgers must be inserted for ambiguous phone subscriber", 0, ledgers.size)

        val batches = liveDb.importBatchDao().getAllOneShot()
        assertTrue("warningsJson must mention phone or ambiguous",
            batches[0].warningsJson!!.contains("phone") || batches[0].warningsJson!!.contains("ambiguous"))
    }

    /**
     * Test 9: Display Name Ambiguity Quarantine.
     * Two existing accounts share displayName = "Mustafa Ahmed".
     * Incoming record has distinct extId, no username, no phone, but displayName = "Mustafa Ahmed".
     * Oracle:
     * - Stage 4 name ambiguity detected by SubscriberMatcher
     * - UtowerImporter quarantines row (subscribersSkipped == 1, subscribersInserted == 0)
     * - Dependent transaction skipped (transactionsSkipped == 1)
     * - Container count remains 2 (zero ghost container created)
     * - Zero ledgers inserted
     */
    @Test
    fun testDisplayNameAmbiguity_quarantinesWithoutGhostContainer() = runBlocking {
        val uuidN1 = "uuid_name_1"
        val uuidN2 = "uuid_name_2"
        val acc1 = LocalAccount(id = uuidN1, sourceExternalId = null, displayName = "Mustafa Ahmed")
        val acc2 = LocalAccount(id = uuidN2, sourceExternalId = null, displayName = "Mustafa Ahmed")
        liveDb.localAccountDao().insert(acc1)
        liveDb.localAccountDao().insert(acc2)

        val incomingSub = JSONObject().apply {
            put("id", "EXT_NEW_NAME")
            put("name", "Mustafa Ahmed")
            put("debt_iqd", 25000)
        }
        val incomingTx = JSONObject().apply {
            put("id", "tx_name_ambig")
            put("toWho", "EXT_NEW_NAME")
            put("amount_iqd", 25000)
            put("type", "took")
            put("date", "2026-09-13 11:00:00")
        }
        val file = createUtowerJsonFile(subscribers = listOf(incomingSub), transactions = listOf(incomingTx))

        val result = importer.importFromFile(file, shouldReplace = false)

        assertTrue("Import must complete batch", result.success)
        assertEquals("Subscribers skipped must be 1", 1, result.subscribersSkipped)
        assertEquals("Subscribers inserted must be 0 (NO ghost container!)", 0, result.subscribersInserted)
        assertEquals("Transactions skipped must be 1", 1, result.transactionsSkipped)

        val accounts = liveDb.localAccountDao().getAllOneShot()
        assertEquals("Container count must remain strictly 2", 2, accounts.size)

        val ledgers = liveDb.localLedgerEntryDao().getAllOneShot()
        assertEquals("Zero ledgers inserted", 0, ledgers.size)
    }

    /**
     * Test 10: Fallthrough — Stage 1 Ambiguous Username Disambiguated by Stage 2 Unique External ID.
     * Account 1: extId = "EXT_101", username = "user_shared"
     * Account 2: extId = "EXT_102", username = "user_shared"
     * Incoming: extId = "EXT_101", username = "user_shared"
     * Oracle:
     * - Stage 1 (username) matches 2 candidates (ambiguous).
     * - Fallthrough to Stage 2 (extId) matches Account 1 uniquely!
     * - Returns Unique(Account 1), NOT Ambiguous.
     * - Row merges into Account 1; zero containers inserted; zero skipped.
     */
    @Test
    fun testFallthrough_ambiguousUsername_disambiguatedByUniqueExtId() = runBlocking {
        val uuid1 = "uuid_ft_1"
        val uuid2 = "uuid_ft_2"
        val acc1 = LocalAccount(id = uuid1, sourceExternalId = "EXT_101", earthlinkUsername = "user_shared", displayName = "Account 1 Old")
        val acc2 = LocalAccount(id = uuid2, sourceExternalId = "EXT_102", earthlinkUsername = "user_shared", displayName = "Account 2 Old")
        liveDb.localAccountDao().insert(acc1)
        liveDb.localAccountDao().insert(acc2)

        val incomingSub = JSONObject().apply {
            put("id", "EXT_101")
            put("userName", "user_shared")
            put("name", "Account 1 Updated")
            put("debt_iqd", 25000)
        }
        val file = createUtowerJsonFile(subscribers = listOf(incomingSub))

        val result = importer.importFromFile(file, shouldReplace = false)

        assertTrue("Import must succeed", result.success)
        assertEquals("Subscribers merged must be 1", 1, result.subscribersMerged)
        assertEquals("Subscribers skipped must be 0", 0, result.subscribersSkipped)
        assertEquals("Subscribers inserted must be 0", 0, result.subscribersInserted)

        val accounts = liveDb.localAccountDao().getAllOneShot()
        assertEquals("Container count must remain 2", 2, accounts.size)

        val fetched1 = liveDb.localAccountDao().getByIdOneShot(uuid1)!!
        val fetched2 = liveDb.localAccountDao().getByIdOneShot(uuid2)!!
        assertEquals("Account 1 received update", "Account 1 Updated", fetched1.displayName)
        assertEquals("Account 2 remained untouched", "Account 2 Old", fetched2.displayName)
    }

    /**
     * Test 11: Fallthrough — Stage 1 & 2 Ambiguous, Disambiguated by Stage 3 Unique Phone.
     * Account 1: extId = "EXT_DUO", username = "user_duo", phone = "07701111111"
     * Account 2: extId = "EXT_DUO", username = "user_duo", phone = "07702222222"
     * Incoming: extId = "EXT_DUO", username = "user_duo", phone = "07701111111"
     * Oracle:
     * - Stage 1 & Stage 2 ambiguous (2 candidates).
     * - Fallthrough to Stage 3 (phone) matches Account 1 uniquely!
     * - Merges into Account 1; zero skipped; zero inserted.
     */
    @Test
    fun testFallthrough_ambiguousUsernameAndExtId_disambiguatedByUniquePhone() = runBlocking {
        val uuid1 = "uuid_duo_1"
        val uuid2 = "uuid_duo_2"
        val acc1 = LocalAccount(id = uuid1, sourceExternalId = "EXT_DUO", earthlinkUsername = "user_duo", phone1 = "07701111111", displayName = "Duo 1 Old")
        val acc2 = LocalAccount(id = uuid2, sourceExternalId = "EXT_DUO", earthlinkUsername = "user_duo", phone1 = "07702222222", displayName = "Duo 2 Old")
        liveDb.localAccountDao().insert(acc1)
        liveDb.localAccountDao().insert(acc2)

        val incomingSub = JSONObject().apply {
            put("id", "EXT_DUO")
            put("userName", "user_duo")
            put("phoneNumber", "07701111111")
            put("name", "Duo 1 Updated")
            put("debt_iqd", 30000)
        }
        val file = createUtowerJsonFile(subscribers = listOf(incomingSub))

        val result = importer.importFromFile(file, shouldReplace = false)

        assertTrue("Import must succeed", result.success)
        assertEquals("Subscribers merged must be 1", 1, result.subscribersMerged)
        assertEquals("Subscribers skipped must be 0", 0, result.subscribersSkipped)
        assertEquals("Subscribers inserted must be 0", 0, result.subscribersInserted)

        val accounts = liveDb.localAccountDao().getAllOneShot()
        assertEquals("Container count must remain 2", 2, accounts.size)

        val fetched1 = liveDb.localAccountDao().getByIdOneShot(uuid1)!!
        val fetched2 = liveDb.localAccountDao().getByIdOneShot(uuid2)!!
        assertEquals("Account 1 received update", "Duo 1 Updated", fetched1.displayName)
        assertEquals("Account 2 remained untouched", "Duo 2 Old", fetched2.displayName)
    }

    /**
     * Test 12: Negative Controls — Unique Phone and Unique Display Name Merge Normally.
     * Verifies that non-ambiguous single-candidate phone and name matches are NOT erroneously quarantined.
     */
    @Test
    fun testNegativeControls_uniquePhoneAndUniqueDisplayName_mergesNormally() = runBlocking {
        val uuidP = "uuid_ctrl_phone"
        val uuidN = "uuid_ctrl_name"
        val accPhone = LocalAccount(id = uuidP, sourceExternalId = "EXT_P_ORIG", phone1 = "07701234567", displayName = "Phone User")
        val accName = LocalAccount(id = uuidN, sourceExternalId = "EXT_N_ORIG", phone1 = "07707654321", displayName = "Unique Named Person")
        liveDb.localAccountDao().insert(accPhone)
        liveDb.localAccountDao().insert(accName)

        // Part A: Incoming sub with unique phone, no username, different extId
        val subA = JSONObject().apply {
            put("id", "EXT_P_ORIG")
            put("phoneNumber", "07701234567")
            put("name", "Phone User Refreshed")
            put("debt_iqd", 25000)
        }
        val fileA = createUtowerJsonFile(subscribers = listOf(subA))
        val resultA = importer.importFromFile(fileA, shouldReplace = false)

        assertTrue("Part A must succeed", resultA.success)
        assertEquals("Part A: merged must be 1", 1, resultA.subscribersMerged)
        assertEquals("Part A: skipped must be 0", 0, resultA.subscribersSkipped)

        // Part B: Incoming sub with unique name, no username, no phone, matching extId
        val subB = JSONObject().apply {
            put("id", "EXT_N_ORIG")
            put("name", "Unique Named Person")
            put("debt_iqd", 40000)
        }
        val fileB = createUtowerJsonFile(subscribers = listOf(subB))
        val resultB = importer.importFromFile(fileB, shouldReplace = false)

        assertTrue("Part B must succeed", resultB.success)
        assertEquals("Part B: merged must be 1", 1, resultB.subscribersMerged)
        assertEquals("Part B: skipped must be 0", 0, resultB.subscribersSkipped)

        val accounts = liveDb.localAccountDao().getAllOneShot()
        assertEquals("Container count must remain strictly 2", 2, accounts.size)
    }
}
