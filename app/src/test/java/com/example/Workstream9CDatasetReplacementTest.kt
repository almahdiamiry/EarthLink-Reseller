package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.*
import com.example.core.sync.RemoteEvent
import com.example.core.sync.RemoteEventSource
import com.example.core.sync.RemoteSyncCoordinator
import com.example.core.sync.UtowerImporter
import com.example.data.repository.AuditRepositoryImpl
import com.example.data.repository.UtowerImportRepositoryImpl
import com.example.domain.repository.AuditRepository
import com.example.domain.repository.UtowerImportPreview
import com.example.domain.repository.UtowerImportRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Workstream9CDatasetReplacementTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var auditRepo: AuditRepository
    private lateinit var utowerRepo: UtowerImportRepository
    private lateinit var importer: UtowerImporter
    private lateinit var coordinator: RemoteSyncCoordinator

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        auditRepo = AuditRepositoryImpl(db, db.auditLogDao())
        utowerRepo = UtowerImportRepositoryImpl(
            context = context,
            database = db,
            batchDao = db.importBatchDao(),
            accountDao = db.localAccountDao(),
            ledgerDao = db.localLedgerEntryDao(),
            outboxDao = db.syncOutboxDao(),
            auditRepo = auditRepo
        )
        importer = UtowerImporter(context, db)
        coordinator = RemoteSyncCoordinator(
            appDatabase = db,
            accountDao = db.localAccountDao(),
            ledgerDao = db.localLedgerEntryDao(),
            batchDao = db.importBatchDao(),
            metadataDao = db.syncMetadataDao(),
            auditDao = db.auditLogDao(),
            outboxDao = db.syncOutboxDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun testReproductionAndResolution_ReplacementWipesOldDataAndTombstonesItToPreventResurrection() = runBlocking {
        val now = System.currentTimeMillis()
        val oldAcc1 = LocalAccount(id = "old_acc_1", displayName = "Old Account 1", earthlinkUsername = "old_user_1", debtIqd = 15000.0, updatedAt = now)
        val oldAcc2 = LocalAccount(id = "old_acc_2", displayName = "Old Account 2", earthlinkUsername = "old_user_2", debtIqd = 25000.0, updatedAt = now)
        val oldTx1 = LocalLedgerEntry(id = "old_tx_1", accountId = "old_acc_1", amountIqd = 15000.0, debtAfterIqd = 15000.0, typeRaw = "took", createdAt = now)
        val oldTx2 = LocalLedgerEntry(id = "old_tx_2", accountId = "old_acc_2", amountIqd = 25000.0, debtAfterIqd = 25000.0, typeRaw = "took", createdAt = now)

        db.localAccountDao().insert(oldAcc1)
        db.localAccountDao().insert(oldAcc2)
        db.localLedgerEntryDao().insert(oldTx1)
        db.localLedgerEntryDao().insert(oldTx2)

        // Verify pre-existing data exists
        assertEquals(2, db.localAccountDao().getAllOneShot().size)
        assertEquals(2, db.localLedgerEntryDao().getAllOneShot().size)

        val newAccJson = JSONObject().apply {
            put("id", "new_canon_ext_1")
            put("name", "New Canonical Acc")
            put("userName", "canon_user")
            put("debt_iqd", 30000.0)
        }.toString()

        val newTxJson = JSONObject().apply {
            put("id", "new_canon_tx_ext_1")
            put("user_id", "new_canon_ext_1")
            put("amount_iqd", 30000.0)
            put("debt_after_iqd", 30000.0)
            put("type", "took")
        }.toString()

        // Perform Replace Import with a brand new dataset
        val preview = UtowerImportPreview(
            parsedSubscribers = listOf(
                LocalAccount(id = "new_canon_acc", displayName = "New Canonical Acc", earthlinkUsername = "canon_user", debtIqd = 30000.0, rawJson = newAccJson)
            ),
            parsedTransactions = listOf(
                LocalLedgerEntry(id = "new_canon_tx", accountId = "new_canon_acc", amountIqd = 30000.0, debtAfterIqd = 30000.0, typeRaw = "took", rawJson = newTxJson)
            ),
            totalCurrentDebtIqd = 30000.0
        )

        val batch = importer.importFromPreview(
            preview = preview,
            fileName = "canon_utower.json",
            fileHash = "hash_canon_replace_001",
            shouldReplace = true
        )

        assertNotNull(batch)

        // 1. Verify local state has ONLY the new canonical data
        val activeAccounts = db.localAccountDao().getAllOneShot()
        val activeLedgers = db.localLedgerEntryDao().getAllOneShot()

        assertEquals(1, activeAccounts.size)
        assertEquals("New Canonical Acc", activeAccounts[0].displayName)
        assertEquals(1, activeLedgers.size)

        // 2. Verify outbox contains delete tombstones for replaced records
        val outbox = db.syncOutboxDao().getAllOneShot()
        val accountDeletes = outbox.filter { it.entityType == "local_accounts" && it.operation == "delete" }
        val ledgerDeletes = outbox.filter { it.entityType == "local_ledger_entries" && it.operation == "delete" }

        assertTrue(accountDeletes.any { it.entityId == "old_acc_1" })
        assertTrue(accountDeletes.any { it.entityId == "old_acc_2" })
        assertTrue(ledgerDeletes.any { it.entityId == "old_tx_1" })
        assertTrue(ledgerDeletes.any { it.entityId == "old_tx_2" })

        // 3. Verify syncMetadataDao recorded tombstones locally
        assertNotNull(db.syncMetadataDao().get("tombstone:account:old_acc_1"))
        assertNotNull(db.syncMetadataDao().get("tombstone:account:old_acc_2"))
        assertNotNull(db.syncMetadataDao().get("tombstone:ledger:old_tx_1"))
        assertNotNull(db.syncMetadataDao().get("tombstone:ledger:old_tx_2"))

        // 4. Simulate a subsequent remote pull of stale cloud versions of the wiped accounts
        val staleRemoteEvent = RemoteEvent.AccountUpsert(
            entityId = oldAcc1.id,
            account = oldAcc1,
            remoteVersion = now - 1000L,
            source = RemoteEventSource.PULL,
            syncMutationId = null
        )
        val result = coordinator.processEvent(staleRemoteEvent)

        // Stale remote upsert should be skipped as duplicate / stale because of tombstone
        assertEquals(com.example.core.sync.EventSyncResult.SKIPPED_DUPLICATE, result)

        // Pre-existing wiped account must NOT have resurrected
        assertNull(db.localAccountDao().getByIdOneShot("old_acc_1"))
        assertEquals(1, db.localAccountDao().getAllOneShot().size)
    }

    @Test
    fun testReplacementResetsSyncCursors() = runBlocking {
        db.syncMetadataDao().put("last_sync_timestamp", "1700000000000")
        db.syncMetadataDao().put("coll_cursor:local_accounts", "cursor_acc_123")
        db.syncMetadataDao().put("coll_cursor:local_ledger_entries", "cursor_ledger_123")
        db.syncMetadataDao().put("coll_cursor:import_batches", "cursor_batch_123")

        val subJson = JSONObject().apply {
            put("id", "ext_cursor_1")
            put("name", "Cursor Sub")
            put("debt_iqd", 5000.0)
        }.toString()

        val preview = UtowerImportPreview(
            parsedSubscribers = listOf(
                LocalAccount(id = "reset_cursor_sub", displayName = "Cursor Sub", debtIqd = 5000.0, rawJson = subJson)
            ),
            parsedTransactions = emptyList(),
            totalCurrentDebtIqd = 5000.0
        )

        importer.importFromPreview(
            preview = preview,
            fileName = "reset_cursor.json",
            fileHash = "hash_reset_cursor_001",
            shouldReplace = true
        )

        // Sync cursors should be removed so subsequent sync starts fresh
        assertNull(db.syncMetadataDao().get("last_sync_timestamp"))
        assertNull(db.syncMetadataDao().get("coll_cursor:local_accounts"))
        assertNull(db.syncMetadataDao().get("coll_cursor:local_ledger_entries"))
        assertNull(db.syncMetadataDao().get("coll_cursor:import_batches"))
    }

    @Test
    fun testMergeImport_RetainsExistingDataWithoutTombstones() = runBlocking {
        val existingAcc = LocalAccount(id = "keep_acc_1", displayName = "Keep Account", debtIqd = 10000.0)
        db.localAccountDao().insert(existingAcc)

        val mergeSubJson = JSONObject().apply {
            put("id", "ext_merge_2")
            put("name", "Merged Account")
            put("debt_iqd", 20000.0)
        }.toString()

        val preview = UtowerImportPreview(
            parsedSubscribers = listOf(
                LocalAccount(id = "merged_acc_2", displayName = "Merged Account", debtIqd = 20000.0, rawJson = mergeSubJson)
            ),
            parsedTransactions = emptyList(),
            totalCurrentDebtIqd = 20000.0
        )

        importer.importFromPreview(
            preview = preview,
            fileName = "merge_test.json",
            fileHash = "hash_merge_test_001",
            shouldReplace = false
        )

        val allAccounts = db.localAccountDao().getAllOneShot()
        assertEquals(2, allAccounts.size)
        assertTrue(allAccounts.any { it.id == "keep_acc_1" })
        assertTrue(allAccounts.any { it.displayName == "Merged Account" })

        val outbox = db.syncOutboxDao().getAllOneShot()
        val deleteTombstones = outbox.filter { it.operation == "delete" }
        assertTrue("Merge must not generate delete tombstones for existing records", deleteTombstones.isEmpty())
    }
}
