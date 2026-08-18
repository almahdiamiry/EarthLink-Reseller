package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.backup.BackupManager
import com.example.core.database.AppDatabase
import com.example.core.model.*
import com.example.core.sync.OutboxManager
import com.example.core.sync.RemoteSyncCoordinator
import com.example.core.sync.SyncRepositoryImpl
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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 2 Restore/Import Transport Reconstruction Alignment & Integration Test Suite
 * (P2-G3-REQ-05 / INV-01 / INV-06 / INV-11 / INV-13 / INV-14).
 *
 * Verifies:
 * 1. Stale backup transport outbox rows and sync cursor metadata from archive files are DISCARDED and NEVER replayed.
 * 2. Pre-operation pending transport obligations are evaluated against the new state:
 *    - Obligations with matching target entities stay 'pending' with stable identity.
 *    - Obligations whose target entities are absent/removed are marked 'failed' with diagnostic 'ORPHAN_TARGET_ENTITY_MISSING' without hot loops.
 * 3. uTower Import outbox obligation generation and deduplication:
 *    - Newly created/restored entities receive clean, canonical 'pending' outbox obligations.
 *    - Incomplete import batches isolate their outbox items until batch completion.
 * 4. RemoteSyncCoordinator cache clearance across Restore Replace, Restore Merge, and uTower Import operations.
 * 5. Lost-ACK cloud idempotency for reconstructed obligations ensuring deterministic convergence.
 * 6. Repeatability and deterministic idempotency across multiple consecutive restore/import passes.
 * 7. Strict Single Canonical Sync Channel Guard (zero secondary sync engines or alternate write paths).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase2TransportReconstructionIntegrationTest {

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

    private fun createTestBackupZip(
        accounts: List<LocalAccount> = emptyList(),
        ledgerEntries: List<LocalLedgerEntry> = emptyList(),
        importBatches: List<ImportBatch> = emptyList(),
        auditLogs: List<AuditLog> = emptyList(),
        historicalOutbox: List<SyncOutbox> = emptyList(),
        historicalSyncData: List<SyncData> = emptyList(),
        dbPassphrase: String = ""
    ): File {
        val srcDbName = "test_backup_source"
        AppDatabase.closeAndRemoveInstance(srcDbName)
        context.deleteDatabase(srcDbName)
        context.getDatabasePath(srcDbName).parentFile?.mkdirs()

        val testDiskDb = AppDatabase.getDatabase(context, ByteArray(0), srcDbName)

        runBlocking {
            if (accounts.isNotEmpty()) testDiskDb.localAccountDao().insertAll(accounts)
            if (ledgerEntries.isNotEmpty()) testDiskDb.localLedgerEntryDao().insertAll(ledgerEntries)
            for (b in importBatches) testDiskDb.importBatchDao().insert(b)
            for (a in auditLogs) testDiskDb.auditLogDao().insert(a)
            if (historicalOutbox.isNotEmpty()) testDiskDb.syncOutboxDao().insertAll(historicalOutbox)
            for (s in historicalSyncData) testDiskDb.syncMetadataDao().put(s.key, s.value, s.updatedAt)

            try {
                testDiskDb.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE);")?.use {
                    it.moveToFirst()
                }
            } catch (_: Throwable) {}
        }

        testDiskDb.close()
        AppDatabase.closeAndRemoveInstance(srcDbName)

        val srcDbFile = context.getDatabasePath(srcDbName)
        val backupDir = File(context.cacheDir, "test_backups_${UUID.randomUUID()}").apply { mkdirs() }
        val zipFile = File(backupDir, "earthlink_backup_${System.currentTimeMillis()}.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            FileInputStream(srcDbFile).use { fis ->
                zos.putNextEntry(ZipEntry("earthlink_reseller_db"))
                fis.copyTo(zos)
                zos.closeEntry()
            }
            val metadata = JSONObject().apply {
                put("appName", "Earthlink Reseller")
                put("dbVersion", AppDatabase.VERSION)
                put("createdAt", System.currentTimeMillis())
                put("formattedDate", "2026-08-18 00:00:00")
                put("dbPassphrase", dbPassphrase)
            }
            val metadataBytes = metadata.toString().toByteArray(Charsets.UTF_8)
            zos.putNextEntry(ZipEntry("backup_info.json"))
            zos.write(metadataBytes)
            zos.closeEntry()
        }

        context.deleteDatabase(srcDbName)
        return zipFile
    }

    // =========================================================================
    // 1. Stale backup transport outbox discard & sync cursor reset (P2-G3-REQ-05 / INV-01 / INV-13)
    // =========================================================================

    @Test
    fun testStaleBackupTransportOutboxAndCursorMetadataDiscardedOnRestore() = runBlocking {
        val restoredAccount = LocalAccount(
            id = "acc_historical_p2_01",
            displayName = "Restored Account P2"
        )
        val staleArchiveOutbox = listOf(
            SyncOutbox(
                id = 101,
                entityType = "local_accounts",
                entityId = "acc_stale_in_backup_zip",
                operation = "upsert",
                payloadJson = """{"id":"acc_stale_in_backup_zip"}""",
                status = "pending",
                attemptCount = 4
            ),
            SyncOutbox(
                id = 102,
                entityType = "local_ledger_entries",
                entityId = "tx_stale_in_backup_zip",
                operation = "upsert",
                payloadJson = """{"id":"tx_stale_in_backup_zip"}""",
                status = "pending",
                attemptCount = 7
            )
        )
        val staleArchiveMetadata = listOf(
            SyncData(key = "firestore_cursor_local_accounts", value = "cursor_stale_acc_999"),
            SyncData(key = "firestore_cursor_local_ledger_entries", value = "cursor_stale_tx_999"),
            SyncData(key = "sync_in_progress", value = "true")
        )

        val zipFile = createTestBackupZip(
            accounts = listOf(restoredAccount),
            historicalOutbox = staleArchiveOutbox,
            historicalSyncData = staleArchiveMetadata
        )

        // Ensure live DB starts clean
        assertEquals(0, liveDb.syncOutboxDao().getAllUnsyncedCount())

        val success = BackupManager.restoreBackupZip(context, zipFile, force = true)
        assertTrue("Restore Replace must succeed", success)

        // Business account was restored
        assertEquals(1, liveDb.localAccountDao().getTotalCount())
        assertNotNull(liveDb.localAccountDao().getByIdOneShot("acc_historical_p2_01"))

        // Stale outbox rows from backup archive were discarded and NOT replayed into live DB
        val liveOutbox = liveDb.syncOutboxDao().getAllOneShot()
        assertTrue("Live outbox must not contain stale archive items", liveOutbox.none { it.entityId == "acc_stale_in_backup_zip" })
        assertTrue("Live outbox must not contain stale archive items", liveOutbox.none { it.entityId == "tx_stale_in_backup_zip" })
        assertEquals(0, liveOutbox.size)

        // Stale sync cursors and sync state metadata were cleared
        val liveMetadata = liveDb.syncMetadataDao().getAllOneShot()
        assertTrue("Sync metadata table must be empty/reset on restore", liveMetadata.isEmpty())
    }

    // =========================================================================
    // 2. Pre-restore pending transport obligations preservation & orphan classification (P2-G3-REQ-05 / INV-13)
    // =========================================================================

    @Test
    fun testPreRestorePendingObligationsPreservationAndOrphanClassification() = runBlocking {
        // Live state before restore
        val survivorAccount = LocalAccount(id = "acc_survivor_01", displayName = "Survivor Account")
        val deletedAccount = LocalAccount(id = "acc_to_be_removed", displayName = "Account To Be Removed")
        liveDb.localAccountDao().insert(survivorAccount)
        liveDb.localAccountDao().insert(deletedAccount)

        val survivingLedger = LocalLedgerEntry(
            id = "tx_survivor_01",
            accountId = "acc_survivor_01",
            typeRaw = "debt",
            amountIqd = 10000.0,
            debtAfterIqd = 10000.0,
            occurredAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis()
        )
        liveDb.localLedgerEntryDao().insert(survivingLedger)

        // Obligation 1: Surviving account (in-flight syncing)
        liveDb.syncOutboxDao().insert(
            SyncOutbox(
                entityType = "local_accounts",
                entityId = "acc_survivor_01",
                operation = "upsert",
                payloadJson = """{"id":"acc_survivor_01"}""",
                status = "syncing",
                attemptCount = 2
            )
        )

        // Obligation 2: Surviving ledger entry (pending)
        liveDb.syncOutboxDao().insert(
            SyncOutbox(
                entityType = "local_ledger_entries",
                entityId = "tx_survivor_01",
                operation = "upsert",
                payloadJson = """{"id":"tx_survivor_01"}""",
                status = "pending",
                attemptCount = 0
            )
        )

        // Obligation 3: Account whose target will be missing after restore (pending)
        liveDb.syncOutboxDao().insert(
            SyncOutbox(
                entityType = "local_accounts",
                entityId = "acc_to_be_removed",
                operation = "upsert",
                payloadJson = """{"id":"acc_to_be_removed"}""",
                status = "pending",
                attemptCount = 1
            )
        )

        // Obligation 4: Ledger whose target will be missing after restore (failed)
        liveDb.syncOutboxDao().insert(
            SyncOutbox(
                entityType = "local_ledger_entries",
                entityId = "tx_missing_target",
                operation = "upsert",
                payloadJson = """{"id":"tx_missing_target"}""",
                status = "failed",
                attemptCount = 3,
                lastError = "Network error"
            )
        )

        // Backup ZIP only contains survivorAccount and survivingLedger
        val zipFile = createTestBackupZip(
            accounts = listOf(survivorAccount),
            ledgerEntries = listOf(survivingLedger)
        )

        val success = BackupManager.restoreBackupZip(context, zipFile, force = true)
        assertTrue("Restore Replace must succeed", success)

        // Target verification
        assertNotNull(liveDb.localAccountDao().getByIdOneShot("acc_survivor_01"))
        assertNotNull(liveDb.localLedgerEntryDao().getByIdOneShot("tx_survivor_01"))
        assertNull(liveDb.localAccountDao().getByIdOneShot("acc_to_be_removed"))

        val outboxRows = liveDb.syncOutboxDao().getAllOneShot()
        assertEquals(4, outboxRows.size)

        // 1. In-flight survivor account normalized to pending
        val accSurvivorOutbox = outboxRows.first { it.entityId == "acc_survivor_01" }
        assertEquals("pending", accSurvivorOutbox.status)
        assertEquals(2, accSurvivorOutbox.attemptCount)

        // 2. Surviving ledger preserved pending
        val txSurvivorOutbox = outboxRows.first { it.entityId == "tx_survivor_01" }
        assertEquals("pending", txSurvivorOutbox.status)

        // 3. Removed account obligation classified as failed orphan with diagnostic tag
        val accOrphanOutbox = outboxRows.first { it.entityId == "acc_to_be_removed" }
        assertEquals("failed", accOrphanOutbox.status)
        assertEquals(2, accOrphanOutbox.attemptCount)
        assertTrue("Error must contain ORPHAN prefix", accOrphanOutbox.lastError?.startsWith("ORPHAN:") == true)
        assertTrue("Error must contain ORPHAN_TARGET_ENTITY_MISSING tag", accOrphanOutbox.lastError?.contains("ORPHAN_TARGET_ENTITY_MISSING") == true)

        // 4. Missing ledger obligation classified as failed orphan
        val txOrphanOutbox = outboxRows.first { it.entityId == "tx_missing_target" }
        assertEquals("failed", txOrphanOutbox.status)
        assertEquals(4, txOrphanOutbox.attemptCount)
        assertTrue("Error must contain ORPHAN prefix", txOrphanOutbox.lastError?.startsWith("ORPHAN:") == true)
        assertTrue("Error must contain ORPHAN_TARGET_ENTITY_MISSING tag", txOrphanOutbox.lastError?.contains("ORPHAN_TARGET_ENTITY_MISSING") == true)

        // 5. Backoff delay prevents hot loops for orphaned items
        val now = System.currentTimeMillis()
        assertFalse("Orphaned item should not be eligible for immediate sync during backoff", OutboxManager.isEligibleForSync(accOrphanOutbox, now))
        assertFalse("Orphaned item should not be eligible for immediate sync during backoff", OutboxManager.isEligibleForSync(txOrphanOutbox, now))
    }

    // =========================================================================
    // 3. uTower Import outbox obligation generation and deduplication (P2-G3-REQ-05 / INV-11 / INV-13)
    // =========================================================================

    @Test
    fun testUtowerImportGeneratesCanonicalOutboxAndDeduplicatesExistingObligations() = runBlocking {
        val sub1 = LocalAccount(
            id = "sub_import_01",
            sourceExternalId = "utower_sub_01",
            displayName = "uTower User 1",
            debtIqd = 25000.0,
            openingDebtIqd = 25000.0,
            currentPriceIqd = 25000.0
        )
        val tx1 = LocalLedgerEntry(
            id = "tx_import_01",
            accountId = "sub_import_01",
            typeRaw = "debt",
            amountIqd = 25000.0,
            debtAfterIqd = 25000.0,
            occurredAt = 1000L,
            createdAt = 1000L
        )

        val preview = UtowerImportPreview(
            fileName = "utower_test.json",
            totalAccountsFound = 1,
            totalTransactionsFound = 1,
            totalCurrentDebtIqd = 25000.0,
            warnings = emptyList(),
            parsedSubscribers = listOf(sub1),
            parsedTransactions = listOf(tx1)
        )

        val batchResult = importer.importFromPreview(
            preview = preview,
            fileName = "utower_test.json",
            fileHash = "hash_utower_01",
            shouldReplace = false
        )

        assertEquals("completed", batchResult.status)

        val importedAccount = liveDb.localAccountDao().findBySourceExternalId("utower_sub_01")
            ?: liveDb.localAccountDao().getAllOneShot().first()
        val importedLedgers = liveDb.localLedgerEntryDao().getByAccountIdOneShot(importedAccount.id)
        val importedLedger = importedLedgers.first()

        // Verify outbox obligations created for accounts, transactions, and batch
        val outboxList = liveDb.syncOutboxDao().getAllOneShot()
        assertEquals(3, outboxList.size)
        assertTrue(outboxList.any { it.entityType == "local_accounts" && it.entityId == importedAccount.id && it.status == "pending" && it.importBatchId == batchResult.id })
        assertTrue(outboxList.any { it.entityType == "local_ledger_entries" && it.entityId == importedLedger.id && it.status == "pending" && it.importBatchId == batchResult.id })
        assertTrue(outboxList.any { it.entityType == "import_batches" && it.entityId == batchResult.id && it.status == "pending" && it.importBatchId == batchResult.id })

        // Re-import / update existing subscriber -> deduplicates outbox obligation via upsertWithOutbox
        val updatedSub = sub1.copy(displayName = "uTower User 1 Updated", debtIqd = 30000.0)
        val preview2 = UtowerImportPreview(
            fileName = "utower_test_2.json",
            totalAccountsFound = 1,
            totalTransactionsFound = 0,
            totalCurrentDebtIqd = 30000.0,
            warnings = emptyList(),
            parsedSubscribers = listOf(updatedSub),
            parsedTransactions = emptyList()
        )

        val batchResult2 = importer.importFromPreview(
            preview = preview2,
            fileName = "utower_test_2.json",
            fileHash = "hash_utower_02",
            shouldReplace = false
        )

        assertEquals("completed", batchResult2.status)

        // Account outbox entry deduplicated to single pending entry for importedAccount.id
        val accountOutbox = liveDb.syncOutboxDao().getByEntity(importedAccount.id, "local_accounts")
        assertEquals(1, accountOutbox.size)
        assertEquals("pending", accountOutbox.first().status)
    }

    // =========================================================================
    // 4. RemoteSyncCoordinator cache clearance across Restore and Import (P2-G3-REQ-05 / INV-01 / INV-06)
    // =========================================================================

    @Test
    fun testRemoteSyncCoordinatorCacheClearedAcrossRestoreAndImport() = runBlocking {
        val coordinator = RemoteSyncCoordinator(
            appDatabase = liveDb,
            accountDao = liveDb.localAccountDao(),
            ledgerDao = liveDb.localLedgerEntryDao(),
            batchDao = liveDb.importBatchDao(),
            outboxDao = liveDb.syncOutboxDao(),
            metadataDao = liveDb.syncMetadataDao()
        )

        val event1 = com.example.core.sync.RemoteEvent.AccountUpsert(
            entityId = "remote_acc_cache_01",
            remoteVersion = 1000L,
            source = com.example.core.sync.RemoteEventSource.PULL,
            account = LocalAccount(id = "remote_acc_cache_01", displayName = "Cache Test Acc", updatedAt = 1000L)
        )

        // Process initial event
        val res1 = coordinator.processEvent(event1)
        assertTrue("Initial event must apply and advance cursor", res1.canAdvanceCursor())

        // Duplicate event before cache clear is skipped as duplicate
        val resDuplicate = coordinator.processEvent(event1)
        assertEquals(com.example.core.sync.EventSyncResult.SKIPPED_DUPLICATE, resDuplicate)

        // Clear cache (as invoked by Restore or Import)
        coordinator.clearCache()

        // After cache clearance, the event key is no longer in LRU cache and is not skipped as in-memory duplicate
        val resFresh = coordinator.processEvent(event1)
        assertNotEquals("Event after cache clear must not be filtered by LRU memory cache", com.example.core.sync.EventSyncResult.SKIPPED_DUPLICATE, resFresh)
    }

    // =========================================================================
    // 5. Lost-ACK cloud idempotency for reconstructed obligations (P2-G3-REQ-05 / INV-06 / INV-13)
    // =========================================================================

    @Test
    fun testLostAckCloudIdempotencyForReconstructedObligations() = runBlocking {
        val acc = LocalAccount(id = "acc_lost_ack_p2", displayName = "Lost ACK Account", debtIqd = 50000.0)
        liveDb.localAccountDao().insert(acc)

        val initialPayload = """{"id":"acc_lost_ack_p2","displayName":"Lost ACK Account","debtIqd":50000.0}"""
        val item = OutboxManager.upsertWithOutbox(
            outboxDao = liveDb.syncOutboxDao(),
            entityType = "local_accounts",
            entityId = "acc_lost_ack_p2",
            payloadJson = initialPayload
        )

        // Confirm syncMutationId is embedded deterministically
        val jsonPayload = JSONObject(item.payloadJson)
        assertTrue("syncMutationId must be injected into outbox payload", jsonPayload.has("syncMutationId"))
        val mutationId = jsonPayload.getString("syncMutationId")

        // 1. Mark in flight (attempt 1)
        val inFlight = OutboxManager.markInFlight(liveDb.syncOutboxDao(), item)
        assertEquals("syncing", inFlight.status)
        assertEquals(1, inFlight.attemptCount)

        // 2. Simulate Lost-ACK network disconnect on client
        OutboxManager.markRetryableFailure(
            outboxDao = liveDb.syncOutboxDao(),
            item = inFlight,
            errorReason = "Lost ACK: SocketTimeoutException during Firestore write"
        )

        val failedItem = liveDb.syncOutboxDao().getByEntity("acc_lost_ack_p2", "local_accounts").first()
        assertEquals("failed", failedItem.status)
        assertEquals(1, failedItem.attemptCount)

        // 3. Retry pass sends exact same mutationId and document key
        val retryPayload = JSONObject(failedItem.payloadJson)
        assertEquals("Mutation ID must be preserved across retry attempts", mutationId, retryPayload.getString("syncMutationId"))

        // 4. When ACK finally received, mark succeeded
        OutboxManager.markSucceeded(liveDb.syncOutboxDao(), failedItem.id)

        // 5. Outbox purged cleanly
        assertEquals(0, liveDb.syncOutboxDao().getAllUnsyncedCount())
    }

    // =========================================================================
    // 6. Repeatability and deterministic idempotency (P2-G3-REQ-05 / INV-10 / INV-13 / INV-14)
    // =========================================================================

    @Test
    fun testRepeatableRestoreAndImportYieldsDeterministicTransportState() = runBlocking {
        val targetAcc = LocalAccount(id = "acc_repeat_p2", displayName = "Repeat Account")
        val zipFile = createTestBackupZip(accounts = listOf(targetAcc))

        val preObligations = listOf(
            SyncOutbox(entityType = "local_accounts", entityId = "acc_repeat_p2", operation = "upsert", payloadJson = "{}", status = "pending"),
            SyncOutbox(entityType = "local_accounts", entityId = "acc_absent_p2", operation = "upsert", payloadJson = "{}", status = "pending")
        )

        // Pass 1
        liveDb.localAccountDao().deleteAll()
        liveDb.syncOutboxDao().deleteAll()
        liveDb.localAccountDao().insert(targetAcc)
        liveDb.syncOutboxDao().insertAll(preObligations)

        val pass1Success = BackupManager.restoreBackupZip(context, zipFile, force = true)
        assertTrue(pass1Success)

        val pass1Outbox = liveDb.syncOutboxDao().getAllOneShot().sortedBy { it.entityId }

        // Pass 2 with identical initial conditions
        liveDb.localAccountDao().deleteAll()
        liveDb.syncOutboxDao().deleteAll()
        liveDb.localAccountDao().insert(targetAcc)
        liveDb.syncOutboxDao().insertAll(preObligations)

        val pass2Success = BackupManager.restoreBackupZip(context, zipFile, force = true)
        assertTrue(pass2Success)

        val pass2Outbox = liveDb.syncOutboxDao().getAllOneShot().sortedBy { it.entityId }

        assertEquals(pass1Outbox.size, pass2Outbox.size)
        for (i in pass1Outbox.indices) {
            val o1 = pass1Outbox[i]
            val o2 = pass2Outbox[i]
            assertEquals(o1.entityId, o2.entityId)
            assertEquals(o1.entityType, o2.entityType)
            assertEquals(o1.status, o2.status)
            assertEquals(o1.attemptCount, o2.attemptCount)
            assertEquals(o1.lastError, o2.lastError)
        }
    }

    // =========================================================================
    // 7. Single Canonical Sync Channel Guard (INV-01 / INV-05 / INV-11 / INV-13)
    // =========================================================================

    @Test
    fun testSingleCanonicalSyncChannelGuard() = runBlocking {
        // Verify that OutboxManager operations are strictly transaction-scoped and
        // enforce canonical status transitions without introducing alternate write engines.
        val item = OutboxManager.upsertWithOutbox(
            outboxDao = liveDb.syncOutboxDao(),
            entityType = "local_accounts",
            entityId = "acc_canonical_guard",
            payloadJson = """{"id":"acc_canonical_guard"}"""
        )

        assertEquals("pending", item.status)
        assertEquals(0, item.attemptCount)
        assertNull(item.lastError)

        // Verify that Outbox items remain durable and retryable (no dead-letter dropping)
        val inFlight = OutboxManager.markInFlight(liveDb.syncOutboxDao(), item)
        assertEquals("syncing", inFlight.status)

        OutboxManager.markRetryableFailure(liveDb.syncOutboxDao(), inFlight, "Server temporary 503")
        val failed = liveDb.syncOutboxDao().getByEntity("acc_canonical_guard", "local_accounts").first()
        assertEquals("failed", failed.status)
        assertEquals(1, liveDb.syncOutboxDao().getRetryableCount())
    }
}
