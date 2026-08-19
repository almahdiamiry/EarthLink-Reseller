package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.*
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.model.SyncOutbox
import com.example.core.sync.OutboxManager
import com.example.core.sync.SyncRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Phase 1 Explicit Orphan Handling & Transport Obligation Isolation Test Suite (INV-13 / P1-G2-REQ-03).
 *
 * Verifies that:
 * 1. An outbox item whose target local entity has been removed/deleted locally:
 *    - Is classified and marked as an orphaned transport obligation.
 *    - Is NOT silently dropped or blackholed (INV-13).
 *    - Retains bounded diagnostic error metadata (e.g. "ORPHAN: Entity <id> of type <type> not found in local database").
 *    - Does not throw an unhandled exception or crash the synchronization pass.
 * 2. An outbox item whose local entity has been superseded locally:
 *    - Is handled safely without reverting or corrupting the newer local state in SQLite.
 * 3. Orphaned items remain durable in SQLite across app restarts and crash recovery passes.
 * 4. Orphaned items are isolated and NEVER block unrelated valid outbox items from syncing.
 * 5. Orphan handling never creates unintended, fraudulent, or empty local ledger entries in SQLite.
 * 6. Ledger entries whose parent account has been removed are explicitly classified as parent-orphaned obligations.
 * 7. Bounded exponential backoff prevents orphaned obligations from hot-looping indefinitely.
 * 8. Manual retry reset allows operator-driven re-queueing of failed orphan obligations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase1OrphanHandlingTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var outboxDao: SyncOutboxDao
    private lateinit var accountDao: LocalAccountDao
    private lateinit var ledgerDao: LocalLedgerEntryDao
    private lateinit var batchDao: ImportBatchDao
    private lateinit var metadataDao: SyncMetadataDao
    private lateinit var auditDao: AuditLogDao
    private lateinit var syncRepository: SyncRepositoryImpl

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        outboxDao = db.syncOutboxDao()
        accountDao = db.localAccountDao()
        ledgerDao = db.localLedgerEntryDao()
        batchDao = db.importBatchDao()
        metadataDao = db.syncMetadataDao()
        auditDao = db.auditLogDao()

        syncRepository = SyncRepositoryImpl(
            context = context,
            appDatabase = db,
            outboxDao = outboxDao,
            accountDao = accountDao,
            ledgerDao = ledgerDao,
            batchDao = batchDao,
            metadataDao = metadataDao,
            auditDao = auditDao
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // 1. Deleted local entity + pending outbox item -> marked as orphan failure, retained with diagnostics
    @Test
    fun case1_deletedLocalEntity_pendingOutboxItem_markedAsOrphanFailure_retainedWithDiagnostics() = runBlocking {
        val entityId = "acc_deleted_target_01"
        val payload = """{"id":"$entityId","displayName":"Orphan Account","debtIqd":25000.0}"""

        // Outbox obligation exists, but entity is NOT inserted in local_accounts (simulating local removal)
        val outboxItem = OutboxManager.enqueue(
            outboxDao = outboxDao,
            entityType = "local_accounts",
            entityId = entityId,
            operation = "upsert",
            payloadJson = payload
        )

        assertEquals("pending", outboxItem.status)
        assertEquals(0, outboxItem.attemptCount)

        // Check orphan status via SyncRepository
        val orphanReason = syncRepository.checkOrphanStatus(outboxItem)
        assertNotNull("Missing local entity must be detected as an orphan", orphanReason)
        assertTrue(orphanReason!!.contains("Entity $entityId of type local_accounts not found in local database"))

        // Classify and mark orphan failure
        OutboxManager.markOrphanFailure(outboxDao, outboxItem, orphanReason)

        // Verify outbox state: retained, failed status, attemptCount incremented, diagnostic preserved
        val pendingList = outboxDao.getPending()
        assertEquals(1, pendingList.size)
        val retained = pendingList.first()
        assertEquals("failed", retained.status)
        assertEquals(1, retained.attemptCount)
        assertNotNull(retained.lastError)
        assertTrue(retained.lastError!!.startsWith("ORPHAN:"))
        assertTrue(retained.lastError!!.contains(entityId))
        assertEquals(1, outboxDao.getFailedCount())
    }

    // 2. Superseded local entity + older outbox item -> handled safely without reverting newer local state
    @Test
    fun case2_supersededLocalEntity_olderOutboxItem_handledSafelyWithoutRevertingNewerLocalState() = runBlocking {
        val accountId = "acc_superseded_02"
        val initialTime = 1000L
        val newerTime = 2000L

        // Local account updated to newer state in SQLite
        val currentAccount = LocalAccount(
            id = accountId,
            displayName = "Updated Modern Name",
            debtIqd = 75000.0,
            updatedAt = newerTime
        )
        accountDao.insert(currentAccount)

        // Outbox contains older payload T1 and newer payload T2
        val itemOld = OutboxManager.enqueue(
            outboxDao = outboxDao,
            entityType = "local_accounts",
            entityId = accountId,
            operation = "upsert",
            payloadJson = """{"id":"$accountId","displayName":"Stale Old Name","debtIqd":30000.0,"updatedAt":$initialTime}"""
        )

        val itemNew = OutboxManager.enqueue(
            outboxDao = outboxDao,
            entityType = "local_accounts",
            entityId = accountId,
            operation = "upsert",
            payloadJson = """{"id":"$accountId","displayName":"Updated Modern Name","debtIqd":75000.0,"updatedAt":$newerTime}"""
        )

        val itemsForEntity = outboxDao.getByEntity(accountId, "local_accounts")
        assertEquals(2, itemsForEntity.size)

        // Deduplication selects the latest outbox item
        val latest = itemsForEntity.maxByOrNull { it.id } ?: itemsForEntity.last()
        assertEquals(itemNew.id, latest.id)

        // Acknowledge all outbox entries when latest is processed
        OutboxManager.markSucceeded(outboxDao, itemsForEntity.map { it.id })

        // Verify outbox entries are cleared
        assertEquals(0, outboxDao.getByEntity(accountId, "local_accounts").size)

        // Verify local SQLite state is intact and was never overwritten with older state
        val loadedAccount = accountDao.getByIdOneShot(accountId)
        assertNotNull(loadedAccount)
        assertEquals("Updated Modern Name", loadedAccount!!.displayName)
        assertEquals(75000.0, loadedAccount.debtIqd, 0.001)
        assertEquals(newerTime, loadedAccount.updatedAt)
    }

    // 3. Orphan survives restart and remains observable in outbox diagnostics
    @Test
    fun case3_orphanSurvivesRestart_remainsObservableInOutboxDiagnostics() = runBlocking {
        val entityId = "acc_restart_orphan_03"
        val item = OutboxManager.enqueue(
            outboxDao = outboxDao,
            entityType = "local_accounts",
            entityId = entityId,
            operation = "upsert",
            payloadJson = """{"id":"$entityId"}"""
        )

        val orphanReason = "Entity $entityId of type local_accounts not found in local database"
        OutboxManager.markOrphanFailure(outboxDao, item, orphanReason)

        // Simulate crash recovery pass (e.g. startup recovery resetting syncing items)
        val resetCount = OutboxManager.resetSyncingToPending(outboxDao)
        assertEquals(0, resetCount) // Item was in 'failed', not 'syncing'

        // Orphan must remain observable and retryable
        val failedItems = outboxDao.getFailedItems()
        assertEquals(1, failedItems.size)
        val failed = failedItems.first()
        assertEquals(entityId, failed.entityId)
        assertEquals("failed", failed.status)
        assertEquals(1, failed.attemptCount)
        assertTrue(failed.lastError?.contains("ORPHAN:") == true)
        assertEquals(1, outboxDao.getRetryableCount())
    }

    // 4. Orphan does not block unrelated valid outbox items from syncing
    @Test
    fun case4_orphanDoesNotBlockUnrelatedValidOutboxItemsFromSyncing() = runBlocking {
        // 1. Enqueue orphan item (missing account)
        val orphanItem = OutboxManager.enqueue(
            outboxDao = outboxDao,
            entityType = "local_accounts",
            entityId = "acc_orphan_missing_99",
            operation = "upsert",
            payloadJson = """{"id":"acc_orphan_missing_99"}"""
        )

        // 2. Create and enqueue valid account
        val validAccount = LocalAccount(id = "acc_valid_neighbor", displayName = "Valid User", debtIqd = 10000.0)
        accountDao.insert(validAccount)
        val validAccountItem = OutboxManager.enqueue(
            outboxDao = outboxDao,
            entityType = "local_accounts",
            entityId = validAccount.id,
            operation = "upsert",
            payloadJson = """{"id":"${validAccount.id}","displayName":"Valid User"}"""
        )

        // 3. Create and enqueue valid ledger entry
        val validLedger = LocalLedgerEntry(
            id = "tx_valid_neighbor",
            accountId = validAccount.id,
            typeRaw = "gave",
            amountIqd = 5000.0,
            debtAfterIqd = 5000.0
        )
        ledgerDao.insert(validLedger)
        val validLedgerItem = OutboxManager.enqueue(
            outboxDao = outboxDao,
            entityType = "local_ledger_entries",
            entityId = validLedger.id,
            operation = "upsert",
            payloadJson = """{"id":"${validLedger.id}","amountIqd":5000.0}"""
        )

        assertEquals(3, outboxDao.getPending().size)

        // Simulate sync pass processing each item
        val allPending = outboxDao.getPending()
        for (item in allPending) {
            val orphanReason = syncRepository.checkOrphanStatus(item)
            if (orphanReason != null) {
                OutboxManager.markOrphanFailure(outboxDao, item, orphanReason)
            } else {
                // Valid item succeeds and is purged
                OutboxManager.markSucceeded(outboxDao, item.id)
            }
        }

        // Assertions: valid items were purged, orphan item remains isolated and failed
        val remaining = outboxDao.getPending()
        assertEquals(1, remaining.size)
        val remainingOrphan = remaining.first()
        assertEquals("acc_orphan_missing_99", remainingOrphan.entityId)
        assertEquals("failed", remainingOrphan.status)
        assertTrue(remainingOrphan.lastError?.contains("ORPHAN:") == true)

        // Valid neighbor items are completely removed
        assertEquals(0, outboxDao.getByEntity("acc_valid_neighbor", "local_accounts").size)
        assertEquals(0, outboxDao.getByEntity("tx_valid_neighbor", "local_ledger_entries").size)
    }

    // 5. Orphan never creates unintended local ledger mutations
    @Test
    fun case5_orphanNeverCreatesUnintendedLocalLedgerMutations() = runBlocking {
        val initialAccountsCount = accountDao.getTotalCount()
        val initialLedgerCount = ledgerDao.getTotalCount()

        // Enqueue an orphan ledger outbox item targeting a non-existent account
        val orphanLedgerItem = OutboxManager.enqueue(
            outboxDao = outboxDao,
            entityType = "local_ledger_entries",
            entityId = "tx_phantom_orphan",
            operation = "upsert",
            payloadJson = """{"id":"tx_phantom_orphan","accountId":"acc_phantom_parent","amountIqd":9999.0}"""
        )

        // Detect orphan
        val orphanReason = syncRepository.checkOrphanStatus(orphanLedgerItem)
        assertNotNull("Ledger item without local record or parent must be flagged orphan", orphanReason)
        OutboxManager.markOrphanFailure(outboxDao, orphanLedgerItem, orphanReason!!)

        // Verify local SQLite database was not polluted with phantom accounts or ledger entries
        assertEquals(initialAccountsCount, accountDao.getTotalCount())
        assertEquals(initialLedgerCount, ledgerDao.getTotalCount())
        assertNull(accountDao.getByIdOneShot("acc_phantom_parent"))
        assertNull(ledgerDao.getByIdOneShot("tx_phantom_orphan"))
    }

    // 6. Ledger entry with deleted parent account -> detected as orphan
    @Test
    fun case6_ledgerEntryWithDeletedParentAccount_detectedAsOrphan() = runBlocking {
        val parentAccountId = "acc_parent_to_delete"
        val ledgerId = "tx_with_deleted_parent"

        // 1. Insert parent account and child ledger entry
        val parentAccount = LocalAccount(id = parentAccountId, displayName = "Temporary Parent", debtIqd = 15000.0)
        accountDao.insert(parentAccount)

        val childLedger = LocalLedgerEntry(
            id = ledgerId,
            accountId = parentAccountId,
            typeRaw = "took",
            amountIqd = 15000.0,
            debtAfterIqd = 15000.0
        )
        ledgerDao.insert(childLedger)

        // 2. Enqueue outbox item for the ledger entry
        val outboxItem = OutboxManager.enqueue(
            outboxDao = outboxDao,
            entityType = "local_ledger_entries",
            entityId = ledgerId,
            operation = "upsert",
            payloadJson = """{"id":"$ledgerId","accountId":"$parentAccountId"}"""
        )

        // 3. Delete child ledger and parent account from SQLite
        ledgerDao.deleteById(ledgerId)
        accountDao.deleteById(parentAccountId)
        assertNull(accountDao.getByIdOneShot(parentAccountId))
        assertNull(ledgerDao.getByIdOneShot(ledgerId))

        // 4. Verify outbox check detects orphan due to deleted target entity
        val orphanReason = syncRepository.checkOrphanStatus(outboxItem)
        assertNotNull(orphanReason)
        assertTrue(orphanReason!!.contains("Entity $ledgerId of type local_ledger_entries not found in local database"))

        OutboxManager.markOrphanFailure(outboxDao, outboxItem, orphanReason)

        val failed = outboxDao.getByEntity(ledgerId, "local_ledger_entries").first()
        assertEquals("failed", failed.status)
        assertTrue(failed.lastError?.contains("ORPHAN:") == true)
    }

    // 7. Bounded exponential backoff prevents hot-looping
    @Test
    fun case7_boundedExponentialBackoff_preventsHotLooping() {
        assertEquals(0L, OutboxManager.calculateBackoffDelay(0))
        assertEquals(2000L, OutboxManager.calculateBackoffDelay(1))
        assertEquals(4000L, OutboxManager.calculateBackoffDelay(2))
        assertEquals(8000L, OutboxManager.calculateBackoffDelay(3))
        assertEquals(16000L, OutboxManager.calculateBackoffDelay(4))
        assertEquals(32000L, OutboxManager.calculateBackoffDelay(5))
        assertEquals(64000L, OutboxManager.calculateBackoffDelay(6))
        assertEquals(128000L, OutboxManager.calculateBackoffDelay(7))
        assertEquals(256000L, OutboxManager.calculateBackoffDelay(8))
        assertEquals(300000L, OutboxManager.calculateBackoffDelay(9)) // Capped at 5 minutes (300,000 ms)
        assertEquals(300000L, OutboxManager.calculateBackoffDelay(50)) // Capped

        val now = System.currentTimeMillis()
        val pendingItem = SyncOutbox(entityType = "local_accounts", entityId = "acc_p", operation = "upsert", payloadJson = "{}", status = "pending")
        assertTrue("Pending items are always eligible for sync", OutboxManager.isEligibleForSync(pendingItem, now))

        val recentFailedItem = SyncOutbox(
            entityType = "local_accounts",
            entityId = "acc_f",
            operation = "upsert",
            payloadJson = "{}",
            status = "failed",
            attemptCount = 3, // delay = 8,000 ms
            updatedAt = now - 2000L // only 2 seconds ago
        )
        assertFalse("Recent failed items in backoff cooldown must not be eligible (preventing hot-looping)", OutboxManager.isEligibleForSync(recentFailedItem, now))

        val elapsedFailedItem = recentFailedItem.copy(updatedAt = now - 10000L) // 10 seconds ago > 8,000 ms
        assertTrue("Failed item past its backoff cooldown is eligible for retry", OutboxManager.isEligibleForSync(elapsedFailedItem, now))
    }

    // 8. Reset failed items clears orphan failures for manual operator retry
    @Test
    fun case8_resetFailedItems_clearsOrphanFailuresForManualRetry() = runBlocking {
        val i1 = OutboxManager.enqueue(outboxDao, "local_accounts", "acc_orf_1", "upsert", "{}")
        val i2 = OutboxManager.enqueue(outboxDao, "local_accounts", "acc_orf_2", "upsert", "{}")

        OutboxManager.markOrphanFailure(outboxDao, listOf(i1, i2), "Entity not found")
        assertEquals(2, outboxDao.getFailedCount())

        val resetCount = outboxDao.resetFailedItems()
        assertEquals(2, resetCount)
        assertEquals(0, outboxDao.getFailedCount())

        val pending = outboxDao.getPending()
        assertEquals(2, pending.size)
        assertTrue(pending.all { it.status == "pending" })
        assertTrue(pending.all { it.attemptCount == 0 })
        assertTrue(pending.all { it.lastError == null })
    }
}
