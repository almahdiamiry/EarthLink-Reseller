package com.example

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.core.backup.BackupManager
import com.example.core.database.AppDatabase
import com.example.core.model.AuditLog
import com.example.core.model.ImportBatch
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.model.SyncData
import com.example.core.model.SyncOutbox
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
 * Phase 1 Restore Transport Reconstruction Decision Table Test Suite (INV-13 / P1-G2-REQ-05).
 *
 * Verifies the Transport Reconstruction Decision Table invariants:
 * 1. Historical backup outbox / cursor metadata from backup archive is DISCARDED and never blindly replayed.
 * 2. Pre-restore unresolved cloud obligations with valid targets in restored state are RECONSTRUCTED with stable identity.
 * 3. Pre-restore unresolved cloud obligations whose targets are absent in restored state are CLASSIFIED AS ORPHANED and preserved durable (never silently deleted).
 * 4. Restored business snapshot baseline produces ZERO duplicate outbox storm.
 * 5. Operational sync metadata and remote cursors are reset to clean baseline.
 * 6. Repeated restore with identical inputs yields deterministic transport disposition.
 * 7. Direct decision table invocation validates all supported entity types (accounts, ledger entries, batches, audit logs, and unknown).
 * 8. In-flight syncing obligations are safely normalized to pending on restore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase1RestoreTransportReconstructionTest {

    private lateinit var context: Context
    private lateinit var liveDb: AppDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        (context as? EarthlinkApp)?.isSafeDebugFallbackAllowedOverride = true
        context.getDatabasePath("earthlink_reseller_db").parentFile?.mkdirs()
        AppDatabase.closeDatabase()
        liveDb = (context as? EarthlinkApp)?.database ?: AppDatabase.getDatabase(context, ByteArray(0), "earthlink_reseller_db")
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
        historicalSyncData: List<SyncData> = emptyList()
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
                put("dbPassphrase", "")
            }
            val metadataBytes = metadata.toString().toByteArray(Charsets.UTF_8)
            zos.putNextEntry(ZipEntry("backup_info.json"))
            zos.write(metadataBytes)
            zos.closeEntry()
        }

        context.deleteDatabase(srcDbName)
        return zipFile
    }

    // 1. Historical backup outbox and remote cursor metadata from backup archive -> Discarded / Cleared
    @Test
    fun case1_backupContainsStaleOutboxAndMetadata_restoreDiscardsItAndDoesNotReplay() = runBlocking {
        val restoredAcc = LocalAccount(id = "acc_historical_01", displayName = "Restored Account")
        val staleOutbox = listOf(
            SyncOutbox(
                id = 1,
                entityType = "local_accounts",
                entityId = "acc_stale_from_archive",
                operation = "upsert",
                payloadJson = """{"id":"acc_stale_from_archive"}""",
                status = "pending",
                attemptCount = 5
            )
        )
        val staleMetadata = listOf(
            SyncData(key = "firestore_cursor_accounts", value = "cursor_stale_12345")
        )

        val zipFile = createTestBackupZip(
            accounts = listOf(restoredAcc),
            historicalOutbox = staleOutbox,
            historicalSyncData = staleMetadata
        )

        val success = BackupManager.restoreBackupZip(context, zipFile, force = true)
        assertTrue("Restore must succeed", success)

        // Business account was restored
        assertEquals(1, liveDb.localAccountDao().getTotalCount())
        assertNotNull(liveDb.localAccountDao().getByIdOneShot("acc_historical_01"))

        // Stale outbox from archive was discarded and NOT replayed into live outbox
        val liveOutbox = liveDb.syncOutboxDao().getAllOneShot()
        assertTrue("Historical outbox from backup archive must not be replayed", liveOutbox.none { it.entityId == "acc_stale_from_archive" })

        // Stale sync metadata from archive was reset
        val liveMetadata = liveDb.syncMetadataDao().getAllOneShot()
        assertTrue("Sync metadata / cursors must be reset on restore", liveMetadata.none { it.key == "firestore_cursor_accounts" })
    }

    // 2. Pre-restore valid unresolved obligation whose target entity exists in restored snapshot -> Reconstructed with stable identity
    @Test
    fun case2_preRestoreValidUnresolvedObligation_withMatchingResultingEntity_isReconstructed() = runBlocking {
        val targetAcc = LocalAccount(id = "acc_valid_survivor", displayName = "Survivor Account")
        liveDb.localAccountDao().insert(targetAcc)
        val preRestoreObligation = SyncOutbox(
            entityType = "local_accounts",
            entityId = "acc_valid_survivor",
            operation = "upsert",
            payloadJson = """{"id":"acc_valid_survivor","name":"Survivor Account"}""",
            status = "pending",
            attemptCount = 2,
            lastError = "Temporary timeout"
        )
        liveDb.syncOutboxDao().insert(preRestoreObligation)

        val zipFile = createTestBackupZip(
            accounts = listOf(targetAcc)
        )

        val success = BackupManager.restoreBackupZip(context, zipFile, force = true)
        assertTrue("Restore must succeed", success)

        // Obligation survived with stable identity
        val outboxRows = liveDb.syncOutboxDao().getByEntity("acc_valid_survivor", "local_accounts")
        assertEquals(1, outboxRows.size)
        val reconstructed = outboxRows.first()
        assertEquals("acc_valid_survivor", reconstructed.entityId)
        assertEquals("local_accounts", reconstructed.entityType)
        assertEquals("upsert", reconstructed.operation)
        assertEquals("pending", reconstructed.status)
        assertEquals(2, reconstructed.attemptCount)
        assertEquals("Temporary timeout", reconstructed.lastError)
    }

    // 3. Pre-restore unresolved obligation whose target entity is absent in restored snapshot -> Classified as orphaned, not silently deleted
    @Test
    fun case3_preRestoreUnresolvedObligation_withAbsentResultingEntity_classifiedAsOrphaned() = runBlocking {
        val orphanAcc = LocalAccount(id = "acc_deleted_target", displayName = "Deleted Account")
        liveDb.localAccountDao().insert(orphanAcc)
        val preRestoreObligation = SyncOutbox(
            entityType = "local_accounts",
            entityId = "acc_deleted_target",
            operation = "upsert",
            payloadJson = """{"id":"acc_deleted_target"}""",
            status = "pending",
            attemptCount = 1
        )
        liveDb.syncOutboxDao().insert(preRestoreObligation)

        // Backup ZIP only contains a different account
        val otherAcc = LocalAccount(id = "acc_different_02", displayName = "Other Account")
        val zipFile = createTestBackupZip(
            accounts = listOf(otherAcc)
        )

        val success = BackupManager.restoreBackupZip(context, zipFile, force = true)
        assertTrue("Restore must succeed", success)

        // Target entity is absent in restored business state
        assertNull(liveDb.localAccountDao().getByIdOneShot("acc_deleted_target"))
        assertNotNull(liveDb.localAccountDao().getByIdOneShot("acc_different_02"))

        // Orphan obligation was NOT silently deleted, but surfaced as failed orphan
        val outboxRows = liveDb.syncOutboxDao().getByEntity("acc_deleted_target", "local_accounts")
        assertEquals(1, outboxRows.size)
        val orphanRow = outboxRows.first()
        assertEquals("failed", orphanRow.status)
        assertEquals(2, orphanRow.attemptCount)
        assertTrue("lastError must explain orphan condition", orphanRow.lastError?.startsWith("ORPHAN:") == true)
        assertTrue("lastError must reference absent entity", orphanRow.lastError?.contains("acc_deleted_target") == true)
    }

    // 4. Restore of completed business snapshot produces no duplicate outbox storm
    @Test
    fun case4_restoredBusinessSnapshotProducesNoDuplicateOutboxStorm() = runBlocking {
        val accounts = (1..30).map { i ->
            LocalAccount(id = "acc_snapshot_$i", displayName = "Snapshot Account $i")
        }
        val ledgers = (1..30).map { i ->
            LocalLedgerEntry(
                id = "tx_snapshot_$i",
                accountId = "acc_snapshot_$i",
                typeRaw = "debt",
                amountIqd = 10000.0,
                debtAfterIqd = 10000.0,
                occurredAt = 1000L + i,
                createdAt = 1000L + i,
                isSnapshotHistory = true
            )
        }

        val zipFile = createTestBackupZip(
            accounts = accounts,
            ledgerEntries = ledgers
        )

        // Live DB starts with zero outbox items
        assertEquals(0, liveDb.syncOutboxDao().getAllUnsyncedCount())

        val success = BackupManager.restoreBackupZip(context, zipFile, force = true)
        assertTrue("Restore must succeed", success)

        assertEquals(30, liveDb.localAccountDao().getTotalCount())
        assertEquals(30, liveDb.localLedgerEntryDao().getTotalCount())

        // Zero outbox storm
        assertEquals(0, liveDb.syncOutboxDao().getAllUnsyncedCount())
    }

    // 5. Operational sync metadata is reset and cleared according to contract
    @Test
    fun case5_operationalSyncMetadataResetAndCleared() = runBlocking {
        // Pre-existing live sync metadata
        liveDb.syncMetadataDao().put("live_cursor_tx", "live_cursor_val")
        liveDb.syncMetadataDao().put("sync_in_progress", "true")

        val zipFile = createTestBackupZip(
            accounts = listOf(LocalAccount(id = "acc_1", displayName = "Acc 1")),
            historicalSyncData = listOf(
                SyncData(key = "backup_cursor_acc", value = "backup_val")
            )
        )

        val success = BackupManager.restoreBackupZip(context, zipFile, force = true)
        assertTrue(success)

        val finalMetadata = liveDb.syncMetadataDao().getAllOneShot()
        assertTrue("Sync metadata table must be empty (reset) after restore", finalMetadata.isEmpty())
    }

    // 6. Repeated restore using the same identity inputs yields deterministic transport disposition
    @Test
    fun case6_repeatedRestoreYieldsDeterministicTransportDisposition() = runBlocking {
        val survivorAcc = LocalAccount(id = "acc_repeat_survivor", displayName = "Repeat Survivor")
        val orphanAcc = LocalAccount(id = "acc_repeat_orphan", displayName = "Repeat Orphan")

        val zipFile = createTestBackupZip(
            accounts = listOf(survivorAcc)
        )

        // Pass 1
        liveDb.localAccountDao().insert(survivorAcc)
        liveDb.localAccountDao().insert(orphanAcc)
        liveDb.syncOutboxDao().insert(
            SyncOutbox(entityType = "local_accounts", entityId = "acc_repeat_survivor", operation = "upsert", payloadJson = "{}", status = "pending", attemptCount = 1)
        )
        liveDb.syncOutboxDao().insert(
            SyncOutbox(entityType = "local_accounts", entityId = "acc_repeat_orphan", operation = "upsert", payloadJson = "{}", status = "pending", attemptCount = 1)
        )

        val success1 = BackupManager.restoreBackupZip(context, zipFile, force = true)
        assertTrue(success1)

        val pass1Outbox = liveDb.syncOutboxDao().getAllOneShot()
        val pass1Survivor = pass1Outbox.first { it.entityId == "acc_repeat_survivor" }
        val pass1Orphan = pass1Outbox.first { it.entityId == "acc_repeat_orphan" }
        assertEquals("pending", pass1Survivor.status)
        assertEquals("failed", pass1Orphan.status)

        // Re-inject identical pre-restore state and run Pass 2
        liveDb.syncOutboxDao().deleteAll()
        liveDb.syncOutboxDao().insert(
            SyncOutbox(entityType = "local_accounts", entityId = "acc_repeat_survivor", operation = "upsert", payloadJson = "{}", status = "pending", attemptCount = 1)
        )
        liveDb.syncOutboxDao().insert(
            SyncOutbox(entityType = "local_accounts", entityId = "acc_repeat_orphan", operation = "upsert", payloadJson = "{}", status = "pending", attemptCount = 1)
        )

        val success2 = BackupManager.restoreBackupZip(context, zipFile, force = true)
        assertTrue(success2)

        val pass2Outbox = liveDb.syncOutboxDao().getAllOneShot()
        val pass2Survivor = pass2Outbox.first { it.entityId == "acc_repeat_survivor" }
        val pass2Orphan = pass2Outbox.first { it.entityId == "acc_repeat_orphan" }
        assertEquals(pass1Survivor.status, pass2Survivor.status)
        assertEquals(pass1Orphan.status, pass2Orphan.status)
        assertEquals(pass1Orphan.lastError, pass2Orphan.lastError)
    }

    // 7. Direct decision table invocation across all entity types
    @Test
    fun case7_directDecisionTableInvocation_allEntityTypes() = runBlocking {
        // Setup existing target entities
        val acc = LocalAccount(id = "acc_target", displayName = "Account Target")
        liveDb.localAccountDao().insert(acc)

        val ledger = LocalLedgerEntry(
            id = "tx_target",
            accountId = "acc_target",
            typeRaw = "renewal",
            amountIqd = 15000.0,
            debtAfterIqd = 15000.0,
            occurredAt = 1000L,
            createdAt = 1000L
        )
        liveDb.localLedgerEntryDao().insert(ledger)

        val batch = ImportBatch(
            id = "batch_target",
            fileName = "batch.csv",
            fileHash = "hash123",
            accountsImported = 10,
            transactionsImported = 10,
            totalDebtIqd = 50000.0,
            status = "completed",
            createdAt = 1000L
        )
        liveDb.importBatchDao().insert(batch)

        val audit = AuditLog(
            id = "audit_target",
            action = "USER_ACTION",
            entityType = "USER",
            entityId = "user1",
            summary = "Test audit",
            createdAt = 1000L
        )
        liveDb.auditLogDao().insert(audit)

        val testObligations = listOf(
            // Existing entities
            SyncOutbox(entityType = "local_accounts", entityId = "acc_target", operation = "upsert", payloadJson = "{}", status = "pending"),
            SyncOutbox(entityType = "local_ledger_entries", entityId = "tx_target", operation = "upsert", payloadJson = "{}", status = "pending"),
            SyncOutbox(entityType = "import_batches", entityId = "batch_target", operation = "upsert", payloadJson = "{}", status = "pending"),
            SyncOutbox(entityType = "audit_logs", entityId = "audit_target", operation = "upsert", payloadJson = "{}", status = "pending"),

            // Absent entities
            SyncOutbox(entityType = "local_accounts", entityId = "acc_missing", operation = "upsert", payloadJson = "{}", status = "pending"),
            SyncOutbox(entityType = "local_ledger_entries", entityId = "tx_missing", operation = "upsert", payloadJson = "{}", status = "pending"),
            SyncOutbox(entityType = "import_batches", entityId = "batch_missing", operation = "upsert", payloadJson = "{}", status = "pending"),
            SyncOutbox(entityType = "audit_logs", entityId = "audit_missing", operation = "upsert", payloadJson = "{}", status = "pending"),
            SyncOutbox(entityType = "unknown_entity_type", entityId = "unknown_id", operation = "upsert", payloadJson = "{}", status = "pending")
        )

        liveDb.syncOutboxDao().deleteAll()
        BackupManager.reconstructTransportState(liveDb, testObligations)

        val resultOutbox = liveDb.syncOutboxDao().getAllOneShot()
        assertEquals(9, resultOutbox.size)

        // Check 4 valid targets are reconstructed with pending status
        val validTargets = listOf("acc_target", "tx_target", "batch_target", "audit_target")
        for (id in validTargets) {
            val item = resultOutbox.first { it.entityId == id }
            assertEquals("Valid target $id must be reconstructed as pending", "pending", item.status)
            assertNull("Valid target $id must not have lastError", item.lastError)
        }

        // Check 5 absent targets are classified as failed orphans
        val missingTargets = listOf("acc_missing", "tx_missing", "batch_missing", "audit_missing", "unknown_id")
        for (id in missingTargets) {
            val item = resultOutbox.first { it.entityId == id }
            assertEquals("Missing target $id must be classified as failed orphan", "failed", item.status)
            assertTrue("Missing target $id must have ORPHAN in lastError", item.lastError?.startsWith("ORPHAN:") == true)
        }
    }

    // 8. In-flight syncing obligations are safely normalized to pending on restore
    @Test
    fun case8_inFlightSyncingObligationsResetToPendingOnRestore() = runBlocking {
        val targetAcc = LocalAccount(id = "acc_inflight_01", displayName = "InFlight Account")
        liveDb.localAccountDao().insert(targetAcc)
        val inFlightObligation = SyncOutbox(
            entityType = "local_accounts",
            entityId = "acc_inflight_01",
            operation = "upsert",
            payloadJson = """{"id":"acc_inflight_01"}""",
            status = "syncing",
            attemptCount = 3
        )
        liveDb.syncOutboxDao().insert(inFlightObligation)

        val zipFile = createTestBackupZip(
            accounts = listOf(targetAcc)
        )

        val success = BackupManager.restoreBackupZip(context, zipFile, force = true)
        assertTrue(success)

        val outbox = liveDb.syncOutboxDao().getByEntity("acc_inflight_01", "local_accounts")
        assertEquals(1, outbox.size)
        val item = outbox.first()
        assertEquals("In-flight syncing status must normalize to pending upon restore", "pending", item.status)
        assertEquals(3, item.attemptCount)
    }
}
