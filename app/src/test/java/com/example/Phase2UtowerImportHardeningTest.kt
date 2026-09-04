package com.example

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.*
import com.example.core.sync.UtowerImporter
import com.example.domain.repository.UtowerImportPreview
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
 * Phase 2 uTower Import Hardening Test Suite (P2-G3-REQ-01 / P2-G3-REQ-05 / INV-11 / INV-14).
 *
 * Verifies:
 * 1. Process interruption / parsing failure before Room transaction leaves active database 100% untouched.
 * 2. Exception inside final Room transaction triggers 100% ACID rollback (zero partial business visibility).
 * 3. Successful Direct Atomic Room Import from File (.json) is 100% complete with correct accounts, ledger, and batch metadata.
 * 4. Successful Direct Atomic Room Import from Preview with clean atomic wipe & insert when shouldReplace=true.
 * 5. Re-importing identical uTower dataset is idempotent (zero duplicate accounts, zero duplicate transactions, deterministic balances).
 * 6. Smart merge vs replace distinction (merging properties on existing accounts vs clean wipe-and-replace).
 * 7. Operational guard invariant: ImportBatch records metadata without becoming a second business authority.
 * 8. Capacity envelope measurement validates 5,000+ records processed cleanly within transaction limits with zero memory exhaustion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase2UtowerImportHardeningTest {

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
        transactions: List<JSONObject> = emptyList(),
        rawContent: String? = null
    ): File {
        val file = File(context.cacheDir, "utower_${UUID.randomUUID()}.json")
        if (rawContent != null) {
            file.writeText(rawContent, Charsets.UTF_8)
            return file
        }
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
     * Requirement 1: Interruption or parsing failure before Room transaction leaves active database 100% untouched.
     */
    @Test
    fun testInterruptionOrParsingFailureBeforeRoomTransactionLeavesActiveDataUntouched() = runBlocking {
        // Populate initial live baseline
        liveDb.localAccountDao().insert(LocalAccount(id = "live_acc_baseline", displayName = "Baseline Account", debtIqd = 25000.0))
        liveDb.localLedgerEntryDao().insert(LocalLedgerEntry(id = "live_tx_baseline", accountId = "live_acc_baseline", amountIqd = 25000.0, debtAfterIqd = 25000.0, typeRaw = "took"))
        liveDb.syncOutboxDao().insert(SyncOutbox(entityType = "accounts", entityId = "live_acc_baseline", operation = "upsert", payloadJson = "{}", status = "pending"))

        // Case A: Corrupted TGZ file (invalid gzip stream)
        val corruptTgz = File(context.cacheDir, "corrupt_${UUID.randomUUID()}.tgz").apply {
            writeBytes("GARBAGE_NON_GZIP_CONTENT".toByteArray(Charsets.UTF_8))
        }
        val resultA = importer.importFromFile(corruptTgz, shouldReplace = false)
        assertFalse("Import must report failure for corrupted TGZ", resultA.success)

        // Verify active database is 100% untouched
        assertEquals(1, liveDb.localAccountDao().getAllOneShot().size)
        assertEquals("live_acc_baseline", liveDb.localAccountDao().getAllOneShot()[0].id)
        assertEquals(25000.0, liveDb.localAccountDao().getAllOneShot()[0].debtIqd, 0.001)
        assertEquals(1, liveDb.localLedgerEntryDao().getAllOneShot().size)
        assertEquals(1, liveDb.syncOutboxDao().getAllOneShot().size)
        assertEquals(0, liveDb.importBatchDao().getAllOneShot().size)

        // Case B: Corrupted JSON file (malformed JSON syntax)
        val corruptJson = createUtowerJsonFile(rawContent = "{ \"live_users\": { \"sub_1\": { \"name\": \"Broken JSON")
        val resultB = importer.importFromFile(corruptJson, shouldReplace = false)
        assertFalse("Import must report failure for malformed JSON", resultB.success)

        // Verify active database is 100% untouched
        assertEquals(1, liveDb.localAccountDao().getAllOneShot().size)
        assertEquals(1, liveDb.localLedgerEntryDao().getAllOneShot().size)
        assertEquals(1, liveDb.syncOutboxDao().getAllOneShot().size)
        assertEquals(0, liveDb.importBatchDao().getAllOneShot().size)

        // Case C: Unapproved RestoreMergeDecision with preview import
        val preview = UtowerImportPreview(
            parsedSubscribers = listOf(LocalAccount(id = "sub_unapproved", displayName = "Unapproved Sub", debtIqd = 10000.0)),
            parsedTransactions = emptyList(),
            totalCurrentDebtIqd = 10000.0
        )
        val unapprovedDecision = RestoreMergeDecision(
            artifactIdentity = "hash_unapproved",
            selectedBaselineId = "PREVIEW_SNAPSHOT",
            selectedLineageScope = "COMPLETE_LINEAGE",
            isApproved = false
        )
        try {
            importer.importFromPreviewWithDecision(preview, "preview.json", "hash_unapproved", unapprovedDecision)
            fail("Expected IllegalStateException for unapproved import decision")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("RestoreMergeDecision is invalidated") == true)
        }

        // Verify active database is 100% untouched
        assertEquals(1, liveDb.localAccountDao().getAllOneShot().size)
        assertEquals(1, liveDb.localLedgerEntryDao().getAllOneShot().size)
        assertEquals(1, liveDb.syncOutboxDao().getAllOneShot().size)
        assertEquals(0, liveDb.importBatchDao().getAllOneShot().size)
    }

    /**
     * Requirement 2: Exception inside final Room transaction triggers 100% ACID rollback.
     */
    @Test
    fun testExceptionInsideFinalRoomTransactionTriggers100PercentRollback() = runBlocking {
        // Establish baseline live state
        liveDb.localAccountDao().insert(LocalAccount(id = "stay_acc_1", displayName = "Account Stay", debtIqd = 15000.0))
        liveDb.localLedgerEntryDao().insert(LocalLedgerEntry(id = "stay_tx_1", accountId = "stay_acc_1", amountIqd = 15000.0, debtAfterIqd = 15000.0, typeRaw = "took"))

        val preview = UtowerImportPreview(
            parsedSubscribers = listOf(
                LocalAccount(id = "rollback_sub_1", sourceExternalId = "rb_ext_1", displayName = "Rollback User", debtIqd = 50000.0)
            ),
            parsedTransactions = listOf(
                LocalLedgerEntry(id = "rollback_tx_1", accountId = "rollback_sub_1", sourceExternalId = "rb_tx_ext_1", amountIqd = 50000.0, debtAfterIqd = 50000.0, typeRaw = "took", occurredAt = System.currentTimeMillis())
            ),
            totalCurrentDebtIqd = 50000.0
        )

        var caughtException = false
        try {
            liveDb.withTransaction {
                // Perform preview import inside a parent transaction that then throws
                importer.importFromPreview(preview, "rollback_test.json", "hash_rollback", shouldReplace = true)
                throw IllegalStateException("INJECTED_FAILURE_INSIDE_TRANSACTION")
            }
        } catch (e: IllegalStateException) {
            caughtException = true
            assertEquals("INJECTED_FAILURE_INSIDE_TRANSACTION", e.message)
        }

        assertTrue("Injected exception must be caught", caughtException)

        // Verify 100% rollback: original live dataset is intact, zero imported records exist
        val accounts = liveDb.localAccountDao().getAllOneShot()
        val ledgers = liveDb.localLedgerEntryDao().getAllOneShot()
        val batches = liveDb.importBatchDao().getAllOneShot()

        assertEquals(1, accounts.size)
        assertEquals("stay_acc_1", accounts[0].id)
        assertEquals(15000.0, accounts[0].debtIqd, 0.001)

        assertEquals(1, ledgers.size)
        assertEquals("stay_tx_1", ledgers[0].id)

        assertEquals(0, batches.size)
    }

    /**
     * Requirement 3: Successful Direct Atomic Room Import from File (.json).
     */
    @Test
    fun testSuccessfulImportFromFileAtomicAndComplete() = runBlocking {
        val sub1Json = JSONObject().apply {
            put("id", "sub_u1")
            put("name", "Mustafa Ali")
            put("userName", "mustafa_ali")
            put("phoneNumber", "07709998877")
            put("price_iqd", 35000)
            put("debt_iqd", 70000)
            put("nanoIp", "192.168.1.100")
            put("note", "Tower A VIP")
        }
        val sub2Json = JSONObject().apply {
            put("id", "sub_u2")
            put("name", "Zaid Hassan")
            put("userName", "zaid_hassan")
            put("phoneNumber", "07801112233")
            put("price_iqd", 25000)
            put("debt_iqd", 25000)
        }
        val tx1Json = JSONObject().apply {
            put("id", "tx_u1")
            put("toWho", "sub_u1")
            put("amount_iqd", 70000)
            put("debt_after_iqd", 70000)
            put("type", "took")
            put("date", "2026-08-10 14:30:00")
            put("comment", "Subscription payment")
        }

        val jsonFile = createUtowerJsonFile(
            subscribers = listOf(sub1Json, sub2Json),
            transactions = listOf(tx1Json)
        )

        val result = importer.importFromFile(jsonFile, shouldReplace = false)
        assertTrue("Import from JSON file must succeed", result.success)
        assertEquals(2, result.subscribersFound)
        assertEquals(2, result.subscribersImported)
        assertEquals(1, result.transactionsImported)

        // Verify accounts in DB
        val accounts = liveDb.localAccountDao().getAllOneShot()
        assertEquals(2, accounts.size)

        val accMustafa = accounts.find { it.earthlinkUsername == "mustafa_ali" }
        assertNotNull("Mustafa must exist", accMustafa)
        assertEquals("Mustafa Ali", accMustafa?.displayName)
        assertEquals(70000.0, accMustafa!!.debtIqd, 0.001)
        assertEquals(70000.0, accMustafa.openingDebtIqd, 0.001)
        assertEquals("192.168.1.100", accMustafa.nanoIp)
        assertEquals("Tower A VIP", accMustafa.note)

        val accZaid = accounts.find { it.earthlinkUsername == "zaid_hassan" }
        assertNotNull("Zaid must exist", accZaid)
        assertEquals(25000.0, accZaid!!.debtIqd, 0.001)

        // Verify transactions in DB
        val ledgers = liveDb.localLedgerEntryDao().getAllOneShot()
        assertEquals(1, ledgers.size)
        assertEquals(accMustafa.id, ledgers[0].accountId)
        assertEquals(70000.0, ledgers[0].amountIqd, 0.001)
        assertEquals("took", ledgers[0].typeRaw)
        assertTrue("Imported transaction must have isSnapshotHistory=true", ledgers[0].isSnapshotHistory)

        // Verify ImportBatch metadata
        val batches = liveDb.importBatchDao().getAllOneShot()
        assertEquals(1, batches.size)
        assertEquals("completed", batches[0].status)
        assertEquals(2, batches[0].accountsImported)
        assertEquals(1, batches[0].transactionsImported)
        assertEquals(95000.0, batches[0].totalDebtIqd, 0.001)

        // Verify Outbox records generated with importBatchId
        val outbox = liveDb.syncOutboxDao().getAllOneShot()
        assertTrue("Outbox must contain import entries", outbox.isNotEmpty())
        for (item in outbox) {
            assertEquals("Outbox item must carry batch ID", batches[0].id, item.importBatchId)
        }
    }

    /**
     * Requirement 4: Successful Direct Atomic Room Import from Preview with clean atomic wipe & insert when shouldReplace=true.
     */
    @Test
    fun testSuccessfulImportFromPreviewWithReplace() = runBlocking {
        // Pre-populate old live state
        liveDb.localAccountDao().insert(LocalAccount(id = "old_acc_to_wipe", displayName = "Wipe Account", debtIqd = 9999.0))
        liveDb.localLedgerEntryDao().insert(LocalLedgerEntry(id = "old_tx_to_wipe", accountId = "old_acc_to_wipe", amountIqd = 9999.0, debtAfterIqd = 9999.0, typeRaw = "took"))

        val preview = UtowerImportPreview(
            parsedSubscribers = listOf(
                LocalAccount(id = "prev_sub_1", sourceExternalId = "prev_ext_1", earthlinkUsername = "prev_user_1", displayName = "Preview User 1", debtIqd = 40000.0, currentPriceIqd = 40000.0),
                LocalAccount(id = "prev_sub_2", sourceExternalId = "prev_ext_2", earthlinkUsername = "prev_user_2", displayName = "Preview User 2", debtIqd = 60000.0, currentPriceIqd = 60000.0)
            ),
            parsedTransactions = listOf(
                LocalLedgerEntry(id = "prev_tx_1", accountId = "prev_sub_1", sourceExternalId = "tx_ext_p1", amountIqd = 40000.0, debtAfterIqd = 40000.0, typeRaw = "took", occurredAt = 1000L, isSnapshotHistory = true),
                LocalLedgerEntry(id = "prev_tx_2", accountId = "prev_sub_2", sourceExternalId = "tx_ext_p2", amountIqd = 60000.0, debtAfterIqd = 60000.0, typeRaw = "took", occurredAt = 2000L, isSnapshotHistory = true)
            ),
            totalCurrentDebtIqd = 100000.0
        )

        val batch = importer.importFromPreview(preview, "preview_test.json", "preview_hash_abc", shouldReplace = true)
        assertEquals("completed", batch.status)
        assertEquals(2, batch.accountsImported)
        assertEquals(2, batch.transactionsImported)
        assertEquals(100000.0, batch.totalDebtIqd, 0.001)

        // Verify old records are wiped completely
        val accounts = liveDb.localAccountDao().getAllOneShot()
        val ledgers = liveDb.localLedgerEntryDao().getAllOneShot()

        assertEquals(2, accounts.size)
        assertNull("Old account must be wiped", accounts.find { it.id == "old_acc_to_wipe" })
        assertNotNull("Preview user 1 must exist", accounts.find { it.earthlinkUsername == "prev_user_1" })
        assertNotNull("Preview user 2 must exist", accounts.find { it.earthlinkUsername == "prev_user_2" })

        assertEquals(2, ledgers.size)
        assertNull("Old tx must be wiped", ledgers.find { it.id == "old_tx_to_wipe" })
        for (tx in ledgers) {
            assertTrue("Imported tx must have isSnapshotHistory=true", tx.isSnapshotHistory)
        }
    }

    /**
     * Requirement 5: Re-importing identical uTower dataset is idempotent (zero duplicate accounts, zero duplicate transactions, deterministic balances).
     */
    @Test
    fun testReimportingIdenticalDatasetIsIdempotent() = runBlocking {
        val sub1Json = JSONObject().apply {
            put("id", "idem_sub_1")
            put("name", "Idempotent User")
            put("userName", "idem_user")
            put("phoneNumber", "07705554433")
            put("price_iqd", 35000)
            put("debt_iqd", 35000)
        }
        val tx1Json = JSONObject().apply {
            put("id", "idem_tx_1")
            put("toWho", "idem_sub_1")
            put("amount_iqd", 35000)
            put("debt_after_iqd", 35000)
            put("type", "took")
            put("date", "2026-08-15 12:00:00")
        }

        val jsonFile = createUtowerJsonFile(
            subscribers = listOf(sub1Json),
            transactions = listOf(tx1Json)
        )

        // Pass 1: Initial import
        val result1 = importer.importFromFile(jsonFile, shouldReplace = false)
        assertTrue(result1.success)
        assertEquals(1, result1.subscribersImported)
        assertEquals(1, result1.transactionsImported)

        val accountsPass1 = liveDb.localAccountDao().getAllOneShot()
        val ledgersPass1 = liveDb.localLedgerEntryDao().getAllOneShot()
        assertEquals(1, accountsPass1.size)
        assertEquals(1, ledgersPass1.size)
        val initialDebt = accountsPass1[0].debtIqd
        val initialAccId = accountsPass1[0].id
        val initialTxId = ledgersPass1[0].id

        // Pass 2: Re-import identical dataset
        val result2 = importer.importFromFile(jsonFile, shouldReplace = false)
        assertTrue(result2.success)
        assertEquals(1, result2.subscribersMerged)
        assertEquals(1, result2.transactionsSkipped)

        val accountsPass2 = liveDb.localAccountDao().getAllOneShot()
        val ledgersPass2 = liveDb.localLedgerEntryDao().getAllOneShot()

        // Assert zero duplication
        assertEquals("Account count must remain exactly 1 (zero duplicate accounts)", 1, accountsPass2.size)
        assertEquals("Ledger count must remain exactly 1 (zero duplicate transactions)", 1, ledgersPass2.size)
        assertEquals("Account ID must be preserved", initialAccId, accountsPass2[0].id)
        assertEquals("Ledger ID must be preserved", initialTxId, ledgersPass2[0].id)
        assertEquals("Debt balance must remain exactly 35,000 IQD without inflating", initialDebt, accountsPass2[0].debtIqd, 0.001)
    }

    /**
     * Requirement 6: Smart merge vs replace distinction.
     */
    @Test
    fun testSmartMergeVsReplaceDistinction() = runBlocking {
        // Pre-existing account in live database
        val existingAcc = LocalAccount(
            id = "merge_acc_live",
            sourceExternalId = "ext_existing_01",
            earthlinkUsername = "user_existing",
            displayName = "Existing Account",
            phone1 = "07700000001",
            debtIqd = 20000.0,
            openingDebtIqd = 20000.0
        )
        liveDb.localAccountDao().insert(existingAcc)

        // Dataset containing update for existing account + 1 new account
        val updateSubJson = JSONObject().apply {
            put("id", "ext_existing_01")
            put("name", "Existing Account Updated")
            put("userName", "user_existing")
            put("phoneNumber", "07700000001")
            put("price_iqd", 45000)
            put("debt_iqd", 20000)
            put("nanoIp", "10.10.10.5")
            put("note", "Updated via merge")
        }
        val newSubJson = JSONObject().apply {
            put("id", "ext_new_02")
            put("name", "New Account")
            put("userName", "user_new_02")
            put("phoneNumber", "07700000002")
            put("price_iqd", 35000)
            put("debt_iqd", 35000)
        }

        val jsonFile = createUtowerJsonFile(subscribers = listOf(updateSubJson, newSubJson))

        // Scenario A: shouldReplace = false -> Smart Merge
        val mergeResult = importer.importFromFile(jsonFile, shouldReplace = false)
        assertTrue(mergeResult.success)

        val mergedAccounts = liveDb.localAccountDao().getAllOneShot()
        assertEquals("Database must contain 2 accounts after smart merge", 2, mergedAccounts.size)

        val mergedExisting = mergedAccounts.find { it.id == "merge_acc_live" }
        assertNotNull("Existing account ID must be preserved", mergedExisting)
        assertEquals("Existing Account Updated", mergedExisting?.displayName)
        assertEquals("10.10.10.5", mergedExisting?.nanoIp)
        assertEquals("Updated via merge", mergedExisting?.note)

        val newAcc = mergedAccounts.find { it.earthlinkUsername == "user_new_02" }
        assertNotNull("New account must be inserted", newAcc)

        // Scenario B: shouldReplace = true -> Clean Atomic Replace
        val singleSubJson = JSONObject().apply {
            put("id", "ext_sole_03")
            put("name", "Sole Replacement Account")
            put("userName", "user_sole_03")
            put("price_iqd", 50000)
            put("debt_iqd", 50000)
        }
        val replaceJson = createUtowerJsonFile(subscribers = listOf(singleSubJson))

        val replaceResult = importer.importFromFile(replaceJson, shouldReplace = true)
        assertTrue(replaceResult.success)

        val replacedAccounts = liveDb.localAccountDao().getAllOneShot()
        assertEquals("Database must contain exactly 1 account after replace", 1, replacedAccounts.size)
        assertEquals("user_sole_03", replacedAccounts[0].earthlinkUsername)
        assertNull("Previous accounts must be completely wiped", replacedAccounts.find { it.id == "merge_acc_live" })
    }

    /**
     * Requirement 7: Operational guard: ImportBatch records metadata without becoming a second business authority.
     */
    @Test
    fun testImportBatchOperationalGuardDoesNotBecomeBusinessAuthority() = runBlocking {
        val subJson = JSONObject().apply {
            put("id", "sub_authority_test")
            put("name", "Authority Test User")
            put("userName", "auth_user")
            put("debt_iqd", 60000)
        }
        val jsonFile = createUtowerJsonFile(subscribers = listOf(subJson))

        val result = importer.importFromFile(jsonFile, shouldReplace = false)
        assertTrue(result.success)

        val batchesBefore = liveDb.importBatchDao().getAllOneShot()
        assertEquals(1, batchesBefore.size)
        assertEquals(60000.0, batchesBefore[0].totalDebtIqd, 0.001)

        // Deliberately corrupt or delete the ImportBatch operational record
        liveDb.importBatchDao().insert(batchesBefore[0].copy(totalDebtIqd = 999999.0, accountsImported = 999))

        // Query financial business state
        val account = liveDb.localAccountDao().findAccountByUsernameOrIdOneShot("auth_user")
        assertNotNull(account)
        assertEquals("Financial debt must remain 60,000 IQD independent of mutated batch metadata", 60000.0, account!!.debtIqd, 0.001)
        assertEquals("Opening debt must remain 60,000 IQD independent of mutated batch metadata", 60000.0, account.openingDebtIqd, 0.001)

        // Delete import batch record entirely
        liveDb.importBatchDao().deleteAll()
        val accountAfterBatchDeletion = liveDb.localAccountDao().findAccountByUsernameOrIdOneShot("auth_user")
        assertNotNull(accountAfterBatchDeletion)
        assertEquals(60000.0, accountAfterBatchDeletion!!.debtIqd, 0.001)
    }

    /**
     * Requirement 8: Capacity envelope measurement validates 5,000+ records processed cleanly within transaction limits.
     */
    @Test
    fun testCapacityEnvelopeMeasurementRealisticDataset() = runBlocking {
        val totalAccounts = 2500
        val totalTransactions = 2500
        val totalRecords = totalAccounts + totalTransactions

        val subList = mutableListOf<JSONObject>()
        val txList = mutableListOf<JSONObject>()

        for (i in 1..totalAccounts) {
            val subId = "bulk_sub_$i"
            val sub = JSONObject().apply {
                put("id", subId)
                put("name", "Subscriber $i")
                put("userName", "user_$i")
                put("phoneNumber", "0770${1000000 + i}")
                put("price_iqd", 35000)
                put("debt_iqd", (i * 1000).toDouble())
            }
            subList.add(sub)

            val tx = JSONObject().apply {
                put("id", "bulk_tx_$i")
                put("toWho", subId)
                put("amount_iqd", (i * 1000).toDouble())
                put("debt_after_iqd", (i * 1000).toDouble())
                put("type", "took")
                put("date", "2026-08-18 10:00:00")
                put("comment", "Bulk Tx $i")
            }
            txList.add(tx)
        }

        val jsonFile = createUtowerJsonFile(subscribers = subList, transactions = txList)
        assertTrue(jsonFile.exists())
        val jsonSizeBytes = jsonFile.length()

        val runtime = Runtime.getRuntime()
        runtime.gc()
        val memBeforeBytes = runtime.totalMemory() - runtime.freeMemory()

        val startTimeMs = System.currentTimeMillis()
        val result = importer.importFromFile(jsonFile, shouldReplace = false)
        val durationMs = System.currentTimeMillis() - startTimeMs

        runtime.gc()
        val memAfterBytes = runtime.totalMemory() - runtime.freeMemory()

        assertTrue("Large bulk import must succeed", result.success)
        assertEquals(totalAccounts, result.subscribersImported)
        assertEquals(totalTransactions, result.transactionsImported)

        assertTrue("Import duration must complete within 30 seconds (actual: ${durationMs}ms)", durationMs < 30000)

        // Verify database counts
        val liveAccountsCount = liveDb.localAccountDao().getTotalCount()
        val liveLedgersCount = liveDb.localLedgerEntryDao().getTotalCount()
        assertEquals(totalAccounts, liveAccountsCount)
        assertEquals(totalTransactions, liveLedgersCount)

        // Log capacity envelope metrics
        println("=== CAPACITY ENVELOPE METRICS ===")
        println("Total Records Imported: $totalRecords ($totalAccounts accounts, $totalTransactions ledgers)")
        println("JSON File Size: $jsonSizeBytes bytes")
        println("Total Duration: ${durationMs} ms")
        println("Approx Peak Memory Delta: ${(memAfterBytes - memBeforeBytes) / (1024 * 1024)} MB")
        println("=================================")
    }

    /**
     * Requirement: Day-first date formats (DD/MM/YYYY and DD-MM-YYYY) must be parsed strictly
     * and must not roll over to year 17 AD or get quarantined/dropped during preview or commit.
     */
    @Test
    fun testDayFirstDateParsingInUtowerPreviewAndImport() = runBlocking {
        val sub = JSONObject().apply {
            put("id", "sub_date_test")
            put("name", "Date Test User")
            put("userName", "date_user")
            put("debt_iqd", 50000)
        }
        val txSlash = JSONObject().apply {
            put("id", "tx_slash")
            put("toWho", "sub_date_test")
            put("amount_iqd", 25000.0)
            put("debt_after_iqd", 25000.0)
            put("type", "took")
            put("date", "11/12/2026 10:15:30")
        }
        val txDash = JSONObject().apply {
            put("id", "tx_dash")
            put("toWho", "sub_date_test")
            put("amount_iqd", 25000.0)
            put("debt_after_iqd", 50000.0)
            put("type", "took")
            put("date", "11-12-2026 10:15:30")
        }

        val previewJson = JSONObject().apply {
            put("subscribers", org.json.JSONArray().apply { put(sub) })
            put("transactions", org.json.JSONArray().apply { put(txSlash); put(txDash) })
        }.toString()

        val utowerRepo = com.example.data.repository.UtowerImportRepositoryImpl(
            context,
            liveDb,
            liveDb.importBatchDao(),
            liveDb.localAccountDao(),
            liveDb.localLedgerEntryDao(),
            liveDb.syncOutboxDao()
        )
        val preview = utowerRepo.processImportPreview(previewJson)

        assertEquals("Preview must parse both transactions without quarantine", 2, preview.totalTransactionsFound)
        assertEquals("Preview must have zero warnings", 0, preview.warnings.size)
        assertTrue("Transaction occurredAt must be in year 2026 (positive ms)", preview.parsedTransactions.all { it.occurredAt > 1700000000000L })

        val jsonFile = createUtowerJsonFile(
            subscribers = listOf(sub),
            transactions = listOf(txSlash, txDash)
        )
        val importResult = importer.importFromFile(jsonFile, shouldReplace = true)
        assertTrue("Direct file import must succeed", importResult.success)
        assertEquals("Direct file import must import 2 transactions", 2, importResult.transactionsImported)

        val importedTxs = liveDb.localLedgerEntryDao().getAllOneShot()
        assertEquals("Direct file import must write 2 transactions to Room", 2, importedTxs.size)
        val previewTxMap = preview.parsedTransactions.associateBy { it.sourceExternalId }
        for (importedTx in importedTxs) {
            val previewTx = previewTxMap[importedTx.sourceExternalId]
            assertNotNull("Imported transaction must match preview transaction", previewTx)
            assertEquals("Preview and imported transaction occurredAt must be exactly identical", previewTx!!.occurredAt, importedTx.occurredAt)
        }
    }
}

