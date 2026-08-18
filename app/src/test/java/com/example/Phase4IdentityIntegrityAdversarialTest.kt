package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.backup.BackupManager
import com.example.core.database.AppDatabase
import com.example.core.model.*
import com.example.core.sync.EventSyncResult
import com.example.core.sync.RemoteEvent
import com.example.core.sync.RemoteEventSource
import com.example.core.sync.RemoteSyncCoordinator
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
 * Phase 4 G5 Identity & Import Collision Safety Adversarial Test Suite (Tasks P4-05, P4-06, P4-07).
 *
 * Verifies that:
 * 1. Task P4-05: Identity is preserved across Restore Merge and Firebase:
 *    - Same transaction ID in live and backup resolves to 1 logical transaction.
 *    - Different transaction IDs are preserved.
 *    - Local ledger ID equals Firestore document key.
 *    - Cloud replay is idempotent.
 * 2. Task P4-06: Adversarial fixture for missing source-keys with identical business fields:
 *    - Row A and Row B in the same source get distinct stable provenance IDs (row A ID != row B ID).
 *    - Re-importing Row A and Row B yields the exact same IDs without duplication.
 * 3. Task P4-07: Identity equality + immutable financial content equality:
 *    - Identical payload is idempotent.
 *    - Divergent payload on same ID fails closed / quarantines conflict without corrupting history (INV-01 / INV-11).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase4IdentityIntegrityAdversarialTest {

    private lateinit var context: Context
    private lateinit var liveDb: AppDatabase
    private lateinit var backupDb: AppDatabase
    private lateinit var coordinator: RemoteSyncCoordinator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        liveDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        backupDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        coordinator = RemoteSyncCoordinator(
            appDatabase = liveDb,
            accountDao = liveDb.localAccountDao(),
            ledgerDao = liveDb.localLedgerEntryDao(),
            outboxDao = liveDb.syncOutboxDao(),
            batchDao = liveDb.importBatchDao(),
            metadataDao = liveDb.syncMetadataDao(),
            auditDao = liveDb.auditLogDao()
        )
    }

    @After
    fun tearDown() {
        liveDb.close()
        backupDb.close()
    }

    private fun createUtowerJsonFile(accounts: List<JSONObject>, transactions: List<JSONObject>): File {
        val file = File(context.cacheDir, "utower_adv_${UUID.randomUUID()}.json")
        val root = JSONObject().apply {
            val liveObj = JSONObject()
            for ((idx, sub) in accounts.withIndex()) {
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
     * P4-05: Restore Merge preserves same transaction ID as 1 logical transaction and different IDs as distinct rows.
     */
    @Test
    fun testRestoreMerge_sameIdResolvesToOne_differentIdsPreserved() = runBlocking {
        val acc = LocalAccount(id = "acc_merge_1", displayName = "Account Merge", openingDebtIqd = 0.0, debtIqd = 15000.0)
        liveDb.localAccountDao().insert(acc)
        backupDb.localAccountDao().insert(acc)

        // Shared transaction in both live and backup with identical payload
        val sharedTx = LocalLedgerEntry(id = "tx_shared", accountId = "acc_merge_1", amountIqd = 5000.0, debtAfterIqd = 5000.0, typeRaw = "took")
        liveDb.localLedgerEntryDao().insert(sharedTx)
        backupDb.localLedgerEntryDao().insert(sharedTx)

        // Live-only transaction
        val liveOnlyTx = LocalLedgerEntry(id = "tx_live_only", accountId = "acc_merge_1", amountIqd = 4000.0, debtAfterIqd = 9000.0, typeRaw = "took")
        liveDb.localLedgerEntryDao().insert(liveOnlyTx)

        // Backup-only transaction
        val backupOnlyTx = LocalLedgerEntry(id = "tx_backup_only", accountId = "acc_merge_1", amountIqd = 6000.0, debtAfterIqd = 15000.0, typeRaw = "took")
        backupDb.localLedgerEntryDao().insert(backupOnlyTx)

        val decision = RestoreMergeDecision(
            artifactIdentity = "hash_adv_123",
            selectedBaselineId = "acc_merge_1",
            conflictDecisions = emptyMap(),
            isApproved = true
        )

        val result = BackupManager.executeRestoreMergeInternal(liveDb, backupDb, decision)

        val mergedTxs = liveDb.localLedgerEntryDao().getByAccountIdOneShot("acc_merge_1")
        assertEquals("Merged dataset must contain exactly 3 transactions (1 shared + 1 live-only + 1 backup-only)", 3, mergedTxs.size)

        val txIds = mergedTxs.map { it.id }.toSet()
        assertTrue(txIds.contains("tx_shared"))
        assertTrue(txIds.contains("tx_live_only"))
        assertTrue(txIds.contains("tx_backup_only"))

        val accAfter = liveDb.localAccountDao().getByIdOneShot("acc_merge_1")
        assertEquals(15000.0, accAfter?.debtIqd ?: 0.0, 0.001)
    }

    /**
     * P4-05: Firestore replay of identical ledger event is idempotent and does not create duplicates.
     */
    @Test
    fun testFirestoreLedgerEvent_idempotentReplay() = runBlocking {
        val acc = LocalAccount(id = "acc_cloud_1", displayName = "Cloud Account", debtIqd = 0.0)
        liveDb.localAccountDao().insert(acc)

        val tx = LocalLedgerEntry(id = "tx_cloud_100", accountId = "acc_cloud_1", amountIqd = 8000.0, debtAfterIqd = 8000.0, typeRaw = "took")
        val event = RemoteEvent.LedgerUpsert(
            entityId = "tx_cloud_100",
            remoteVersion = 200L,
            source = RemoteEventSource.REALTIME,
            entry = tx
        )

        val res1 = coordinator.processEvent(event)
        assertEquals(EventSyncResult.APPLIED, res1)

        val res2 = coordinator.processEvent(event)
        assertEquals(EventSyncResult.SKIPPED_DUPLICATE, res2)

        val allTxs = liveDb.localLedgerEntryDao().getByAccountIdOneShot("acc_cloud_1")
        assertEquals(1, allTxs.size)
        assertEquals("tx_cloud_100", allTxs[0].id)
    }

    /**
     * P4-06: Adversarial counterexample: same account, date, amount, type, but distinct rows A != B in source.
     */
    @Test
    fun testAdversarialCounterexample_sameBusinessFields_distinctProvenanceIds_idempotentReimport() = runBlocking {
        val importer = UtowerImporter(context, liveDb)

        val accountJson = JSONObject().apply {
            put("id", "sub_adv_1")
            put("name", "Adversarial Subscriber")
            put("phone", "07700000099")
            put("debt", 0)
        }

        // Two distinct rows with exact same business fields (no sourceKey)
        val rowA = JSONObject().apply {
            put("toWho", "sub_adv_1")
            put("type", "took")
            put("amount", 10000)
            put("date", "2026-03-01 15:30:00")
            put("comment", "Subscription")
        }
        val rowB = JSONObject().apply {
            put("toWho", "sub_adv_1")
            put("type", "took")
            put("amount", 10000)
            put("date", "2026-03-01 15:30:00")
            put("comment", "Subscription")
        }

        val file = createUtowerJsonFile(listOf(accountJson), listOf(rowA, rowB))
        val result1 = importer.importFromFile(file, shouldReplace = false)
        assertTrue(result1.success)
        assertEquals(2, result1.transactionsImported)

        val importedAcc = liveDb.localAccountDao().getAllOneShot().first()
        val initialTxs = liveDb.localLedgerEntryDao().getByAccountIdOneShot(importedAcc.id)
        assertEquals(2, initialTxs.size)
        assertNotEquals("Row A and Row B must receive distinct stable IDs", initialTxs[0].id, initialTxs[1].id)

        val rowAId = initialTxs[0].id
        val rowBId = initialTxs[1].id

        // Re-import identical file
        val result2 = importer.importFromFile(file, shouldReplace = false)
        assertTrue(result2.success)

        val reimportedTxs = liveDb.localLedgerEntryDao().getByAccountIdOneShot(importedAcc.id)
        assertEquals("Re-importing must NOT produce extra rows", 2, reimportedTxs.size)

        val reimportedIds = reimportedTxs.map { it.id }.toSet()
        assertTrue("Re-import must produce identical ID for Row A", reimportedIds.contains(rowAId))
        assertTrue("Re-import must produce identical ID for Row B", reimportedIds.contains(rowBId))
    }

    /**
     * P4-07: Remote ledger event with same ID but divergent immutable payload is quarantined.
     */
    @Test
    fun testRemoteLedgerEvent_sameIdDivergentPayload_quarantinedConflict() = runBlocking {
        val acc = LocalAccount(id = "acc_div_1", displayName = "Div Account", debtIqd = 0.0)
        liveDb.localAccountDao().insert(acc)

        val localTx = LocalLedgerEntry(id = "tx_div_1", accountId = "acc_div_1", amountIqd = 5000.0, debtAfterIqd = 5000.0, typeRaw = "took")
        liveDb.localLedgerEntryDao().insert(localTx)

        // Incoming remote event with same ID "tx_div_1" but divergent amount 99999.0
        val incomingDivergentTx = LocalLedgerEntry(id = "tx_div_1", accountId = "acc_div_1", amountIqd = 99999.0, debtAfterIqd = 99999.0, typeRaw = "took")
        val event = RemoteEvent.LedgerUpsert(
            entityId = "tx_div_1",
            remoteVersion = 300L,
            source = RemoteEventSource.REALTIME,
            entry = incomingDivergentTx
        )

        val res = coordinator.processEvent(event)
        assertEquals(EventSyncResult.QUARANTINED_CONFLICT, res)

        // Local state must remain untouched
        val savedTx = liveDb.localLedgerEntryDao().getByIdOneShot("tx_div_1")
        assertNotNull(savedTx)
        assertEquals(5000.0, savedTx?.amountIqd ?: 0.0, 0.001)

        val auditLogs = liveDb.auditLogDao().getAllSync()
        assertTrue(auditLogs.any { it.action == "QUARANTINE_IDENTITY_CONFLICT" })
    }
}
