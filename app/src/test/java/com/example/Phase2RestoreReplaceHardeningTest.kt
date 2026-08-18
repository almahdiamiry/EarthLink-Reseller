package com.example

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.core.backup.BackupManager
import com.example.core.database.AppDatabase
import com.example.core.model.*
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
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Phase 2 Restore Replace Hardening Test Suite (P2-G3-REQ-01 / P2-G3-REQ-05 / P2-G3-REQ-06 / INV-11 / INV-13 / INV-14).
 *
 * Verifies:
 * 1. Process interruption / failure before final Room transaction leaves active data 100% untouched.
 * 2. Exception / failure inside final Room transaction triggers 100% ACID rollback (all-or-nothing atomicity).
 * 3. Successful Direct Atomic Room Restore Replace is 100% complete with correct accounts, ledger entries, and audit trail.
 * 4. Stale historical outbox items in the backup archive are discarded and NEVER blindly replayed.
 * 5. Pre-restore unresolved transport obligations are reconstructed or classified as orphaned per Decision Table.
 * 6. Incomplete/uncommitted import batches from backup are quarantined (status='failed') per backup_state_classification.yaml.
 * 7. Persistent pre-restore safety backup is created in EarthlinkBackups directory before destructive replacement.
 * 8. Capacity envelope measurement validates 5,000+ records processed cleanly within transaction limits with zero memory exhaustion or timeout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase2RestoreReplaceHardeningTest {

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
        historicalSyncData: List<SyncData> = emptyList(),
        dbPassphrase: String = ""
    ): File {
        val srcDbName = "test_backup_source"
        AppDatabase.closeAndRemoveInstance(srcDbName)
        context.deleteDatabase(srcDbName)

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

    /**
     * Requirement 1: Interruption or failure before final Room transaction leaves active data 100% untouched.
     */
    @Test
    fun testInterruptionOrFailureBeforeRoomTransactionLeavesActiveDataUntouched() = runBlocking {
        // Populate initial live data
        liveDb.localAccountDao().insert(LocalAccount(id = "live_acc_orig", displayName = "Original Account", debtIqd = 12500.0))
        liveDb.localLedgerEntryDao().insert(LocalLedgerEntry(id = "live_tx_orig", accountId = "live_acc_orig", amountIqd = 12500.0, debtAfterIqd = 12500.0, typeRaw = "took"))
        liveDb.syncOutboxDao().insert(SyncOutbox(entityType = "accounts", entityId = "live_acc_orig", operation = "upsert", payloadJson = "{}", status = "pending"))

        // Case A: Corrupted / Non-SQLite backup file
        val corruptZip = File(context.cacheDir, "corrupt_${UUID.randomUUID()}.zip")
        ZipOutputStream(FileOutputStream(corruptZip)).use { zos ->
            zos.putNextEntry(ZipEntry("earthlink_reseller_db"))
            zos.write("GARBAGE_DATA_NOT_A_VALID_DATABASE".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        val resultA = BackupManager.restoreBackupZip(context, corruptZip, force = true)
        assertFalse("Restore must return false for corrupted backup", resultA)

        // Verify active state remains 100% untouched
        assertEquals(1, liveDb.localAccountDao().getAllOneShot().size)
        assertEquals("live_acc_orig", liveDb.localAccountDao().getAllOneShot()[0].id)
        assertEquals(12500.0, liveDb.localAccountDao().getAllOneShot()[0].debtIqd, 0.001)
        assertEquals(1, liveDb.localLedgerEntryDao().getAllOneShot().size)
        assertEquals(1, liveDb.syncOutboxDao().getAllOneShot().size)

        // Case B: Unapproved RestoreMergeDecision
        val validBackupZip = createTestBackupZip(
            accounts = listOf(LocalAccount(id = "backup_acc_1", displayName = "Backup Acc", debtIqd = 50000.0))
        )
        val unapprovedDecision = BackupManager.prepareRestoreMergeDecision(
            context = context,
            backupFile = validBackupZip,
            isApproved = false
        )
        val resultB = BackupManager.restoreWithDecision(context, validBackupZip, unapprovedDecision, force = true)
        assertFalse("Restore must abort for unapproved decision", resultB)

        // Verify active state remains 100% untouched
        assertEquals(1, liveDb.localAccountDao().getAllOneShot().size)
        assertEquals("live_acc_orig", liveDb.localAccountDao().getAllOneShot()[0].id)
        assertEquals(1, liveDb.localLedgerEntryDao().getAllOneShot().size)
    }

    /**
     * Requirement 2: Exception inside final Room transaction triggers 100% ACID rollback (all-or-nothing atomicity).
     */
    @Test
    fun testExceptionInsideFinalRoomTransactionTriggers100PercentRollback() = runBlocking {
        // Establish baseline live state
        liveDb.localAccountDao().insert(LocalAccount(id = "acc_stay_1", displayName = "Stay 1", debtIqd = 1000.0))
        liveDb.localAccountDao().insert(LocalAccount(id = "acc_stay_2", displayName = "Stay 2", debtIqd = 2000.0))
        liveDb.localLedgerEntryDao().insert(LocalLedgerEntry(id = "tx_stay_1", accountId = "acc_stay_1", amountIqd = 1000.0, debtAfterIqd = 1000.0, typeRaw = "took"))
        liveDb.syncOutboxDao().insert(SyncOutbox(entityType = "accounts", entityId = "acc_stay_1", operation = "upsert", payloadJson = "{}", status = "pending"))

        // Create backup database
        val backupDbName = "rollback_test_backup_src"
        context.deleteDatabase(backupDbName)
        val backupDiskDb = androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, backupDbName)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        backupDiskDb.localAccountDao().insert(LocalAccount(id = "restored_acc_new", displayName = "New Restore", debtIqd = 99999.0))
        backupDiskDb.close()

        val reopenedBackupDb = androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, backupDbName)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()

        // Simulate transactional failure inside withTransaction
        var caughtException = false
        try {
            liveDb.withTransaction {
                // Execute standard replace operations
                BackupManager.executeRestoreReplaceInternal(
                    liveDb = liveDb,
                    backupDb = reopenedBackupDb,
                    unresolvedObligations = emptyList()
                )

                // Inject transactional failure before transaction completes
                throw IllegalStateException("INJECTED_TRANSACTION_FAILURE_DURING_RESTORE")
            }
        } catch (e: IllegalStateException) {
            caughtException = true
            assertEquals("INJECTED_TRANSACTION_FAILURE_DURING_RESTORE", e.message)
        } finally {
            reopenedBackupDb.close()
            context.deleteDatabase(backupDbName)
        }

        assertTrue("Expected injected exception to be caught", caughtException)

        // Verify 100% rollback: pre-restore active dataset is completely intact with zero partial visibility
        val liveAccounts = liveDb.localAccountDao().getAllOneShot()
        val liveLedgers = liveDb.localLedgerEntryDao().getAllOneShot()
        val liveOutbox = liveDb.syncOutboxDao().getAllOneShot()

        assertEquals("Live accounts must be completely restored to pre-transaction state (count=2)", 2, liveAccounts.size)
        assertTrue(liveAccounts.any { it.id == "acc_stay_1" })
        assertTrue(liveAccounts.any { it.id == "acc_stay_2" })
        assertFalse(liveAccounts.any { it.id == "restored_acc_new" })

        assertEquals("Live ledgers must be completely restored to pre-transaction state (count=1)", 1, liveLedgers.size)
        assertEquals("tx_stay_1", liveLedgers[0].id)

        assertEquals("Live outbox must be completely restored to pre-transaction state (count=1)", 1, liveOutbox.size)
        assertEquals("acc_stay_1", liveOutbox[0].entityId)
    }

    /**
     * Requirement 3: Successful Direct Atomic Room Restore Replace is 100% complete and atomically replaced.
     */
    @Test
    fun testSuccessfulRestoreReplaceIsAtomicAndComplete() = runBlocking {
        // Initial live state (to be replaced)
        liveDb.localAccountDao().insert(LocalAccount(id = "old_live_1", displayName = "Old Acc", debtIqd = 500.0))
        liveDb.localLedgerEntryDao().insert(LocalLedgerEntry(id = "old_tx_1", accountId = "old_live_1", amountIqd = 500.0, debtAfterIqd = 500.0, typeRaw = "took"))

        val backupAcc1 = LocalAccount(id = "restored_acc_1", displayName = "Restored One", debtIqd = 75000.0)
        val backupAcc2 = LocalAccount(id = "restored_acc_2", displayName = "Restored Two", debtIqd = 25000.0)
        val backupTx1 = LocalLedgerEntry(id = "restored_tx_1", accountId = "restored_acc_1", amountIqd = 75000.0, debtAfterIqd = 75000.0, typeRaw = "took")
        val backupTx2 = LocalLedgerEntry(id = "restored_tx_2", accountId = "restored_acc_2", amountIqd = 25000.0, debtAfterIqd = 25000.0, typeRaw = "took")

        val backupZip = createTestBackupZip(
            accounts = listOf(backupAcc1, backupAcc2),
            ledgerEntries = listOf(backupTx1, backupTx2)
        )

        val approvedDecision = BackupManager.prepareRestoreMergeDecision(
            context = context,
            backupFile = backupZip,
            selectedBaselineId = "BACKUP_SNAPSHOT",
            isApproved = true
        )

        val result = BackupManager.restoreWithDecision(context, backupZip, approvedDecision, force = true)
        assertTrue("Restore with approved decision must succeed", result)

        // Verify old records are completely replaced with new records
        val liveAccounts = liveDb.localAccountDao().getAllOneShot()
        val liveLedgers = liveDb.localLedgerEntryDao().getAllOneShot()

        assertEquals("Live accounts must contain exactly 2 restored accounts", 2, liveAccounts.size)
        assertNull("Old account must be wiped", liveAccounts.find { it.id == "old_live_1" })
        assertNotNull("Restored account 1 must exist", liveAccounts.find { it.id == "restored_acc_1" })
        assertNotNull("Restored account 2 must exist", liveAccounts.find { it.id == "restored_acc_2" })

        assertEquals("Live ledgers must contain exactly 2 restored transactions", 2, liveLedgers.size)
        assertNull("Old transaction must be wiped", liveLedgers.find { it.id == "old_tx_1" })
        assertNotNull("Restored tx 1 must exist", liveLedgers.find { it.id == "restored_tx_1" })
        assertNotNull("Restored tx 2 must exist", liveLedgers.find { it.id == "restored_tx_2" })

        // Verify DATABASE_RESTORE audit log is recorded with RESTORE_EVENT origin
        val auditLogs = liveDb.auditLogDao().getAllSync()
        val restoreAudit = auditLogs.find { it.action == "DATABASE_RESTORE" }
        assertNotNull("DATABASE_RESTORE audit log must be recorded", restoreAudit)
        assertEquals("RESTORE_EVENT", restoreAudit?.origin)
        assertNotNull("Audit signature must be present", restoreAudit?.signature)
    }

    /**
     * Requirement 4: Stale historical outbox items in the backup archive are discarded and NEVER blindly replayed.
     */
    @Test
    fun testHistoricalOutboxFromBackupIsNotReplayed() = runBlocking {
        val backupAcc = LocalAccount(id = "acc_with_historical_outbox", displayName = "User", debtIqd = 10000.0)
        val staleOutbox = listOf(
            SyncOutbox(id = 100, entityType = "accounts", entityId = "acc_with_historical_outbox", operation = "upsert", payloadJson = "{}", status = "pending"),
            SyncOutbox(id = 101, entityType = "ledger_entries", entityId = "tx_historical_999", operation = "upsert", payloadJson = "{}", status = "pending"),
            SyncOutbox(id = 102, entityType = "accounts", entityId = "acc_deleted_in_past", operation = "delete", payloadJson = "{}", status = "pending")
        )

        val backupZip = createTestBackupZip(
            accounts = listOf(backupAcc),
            historicalOutbox = staleOutbox
        )

        val success = BackupManager.restoreBackupZip(context, backupZip, force = true)
        assertTrue(success)

        // Verify live outbox is NOT polluted with historical backup outbox entries (Zero Outbox Storm)
        val liveOutbox = liveDb.syncOutboxDao().getAllOneShot()
        assertEquals("Historical backup outbox entries must be discarded (outbox size must be 0)", 0, liveOutbox.size)
    }

    /**
     * Requirement 5: Pre-restore unresolved transport obligations are reconstructed or classified as orphaned.
     */
    @Test
    fun testPreRestoreUnresolvedObligationsReconstructionAndOrphanClassification() = runBlocking {
        // Pre-restore unresolved outbox items
        val obligationValidTarget = SyncOutbox(
            id = 1,
            entityType = "accounts",
            entityId = "restored_target_acc",
            operation = "upsert",
            payloadJson = """{"debtIqd": 40000.0}""",
            status = "pending",
            attemptCount = 0
        )
        val obligationAbsentTarget = SyncOutbox(
            id = 2,
            entityType = "accounts",
            entityId = "absent_target_acc_not_in_backup",
            operation = "upsert",
            payloadJson = """{"debtIqd": 90000.0}""",
            status = "pending",
            attemptCount = 1
        )

        liveDb.syncOutboxDao().insert(obligationValidTarget)
        liveDb.syncOutboxDao().insert(obligationAbsentTarget)

        // Backup dataset containing ONLY restored_target_acc
        val backupZip = createTestBackupZip(
            accounts = listOf(LocalAccount(id = "restored_target_acc", displayName = "Target Exists", debtIqd = 40000.0))
        )

        val success = BackupManager.restoreBackupZip(context, backupZip, force = true)
        assertTrue(success)

        val liveOutbox = liveDb.syncOutboxDao().getAllOneShot()
        assertEquals("Live outbox must contain 2 reconstructed obligations", 2, liveOutbox.size)

        val validReconstructed = liveOutbox.find { it.entityId == "restored_target_acc" }
        assertNotNull("Valid target obligation must be retained", validReconstructed)
        assertEquals("pending", validReconstructed?.status)

        val absentOrphaned = liveOutbox.find { it.entityId == "absent_target_acc_not_in_backup" }
        assertNotNull("Absent target obligation must be classified as orphaned", absentOrphaned)
        assertEquals("failed", absentOrphaned?.status)
        assertTrue(
            "Orphaned record lastError must describe missing target",
            absentOrphaned?.lastError?.contains("ORPHAN") == true
        )
    }

    /**
     * Requirement 6: Incomplete/uncommitted import batches from backup are quarantined per backup_state_classification.yaml.
     */
    @Test
    fun testIncompleteImportBatchesQuarantined() = runBlocking {
        val completedBatch = ImportBatch(
            id = "batch_completed_01",
            fileName = "utower_c.json",
            fileHash = "hash_c",
            accountsImported = 10,
            transactionsImported = 25,
            totalDebtIqd = 100000.0,
            status = "completed",
            createdAt = System.currentTimeMillis()
        )
        val inProgressBatch = ImportBatch(
            id = "batch_incomplete_02",
            fileName = "utower_partial.json",
            fileHash = "hash_partial",
            accountsImported = 3,
            transactionsImported = 5,
            totalDebtIqd = 15000.0,
            status = "in_progress",
            createdAt = System.currentTimeMillis()
        )

        val backupZip = createTestBackupZip(
            importBatches = listOf(completedBatch, inProgressBatch)
        )

        val success = BackupManager.restoreBackupZip(context, backupZip, force = true)
        assertTrue(success)

        val restoredBatches = liveDb.importBatchDao().getAllOneShot()
        assertEquals(2, restoredBatches.size)

        val bCompleted = restoredBatches.find { it.id == "batch_completed_01" }
        assertNotNull(bCompleted)
        assertEquals("completed", bCompleted?.status)

        val bIncomplete = restoredBatches.find { it.id == "batch_incomplete_02" }
        assertNotNull(bIncomplete)
        assertEquals("failed", bIncomplete?.status)
        assertTrue(
            "Incomplete batch must contain quarantine warning in warningsJson",
            bIncomplete?.warningsJson?.contains("Quarantined on restore") == true
        )
    }

    /**
     * Requirement 7: Persistent pre-restore safety backup is created in EarthlinkBackups directory.
     */
    @Test
    fun testPreRestoreSafetyBackupCreatedAndValid() = runBlocking {
        liveDb.localAccountDao().insert(LocalAccount(id = "pre_safety_acc", displayName = "Safety User", debtIqd = 33000.0))
        liveDb.localLedgerEntryDao().insert(LocalLedgerEntry(id = "pre_safety_tx", accountId = "pre_safety_acc", amountIqd = 33000.0, debtAfterIqd = 33000.0, typeRaw = "took"))

        val backupZip = createTestBackupZip(
            accounts = listOf(LocalAccount(id = "new_restored_acc", displayName = "New Restored", debtIqd = 88000.0))
        )

        val backupsDir = BackupManager.getBackupsDirectory(context)
        val countBefore = backupsDir.listFiles()?.filter { it.name.startsWith("pre_restore_backup_") }?.size ?: 0

        val success = BackupManager.restoreBackupZip(context, backupZip, force = true)
        assertTrue(success)

        val preBackupsAfter: List<File> = backupsDir.listFiles()?.filter { it.name.startsWith("pre_restore_backup_") } ?: emptyList()
        assertTrue("Pre-restore safety backup must be created", preBackupsAfter.size > countBefore)

        val latestPreBackup = preBackupsAfter.maxByOrNull { it.lastModified() }
        assertNotNull(latestPreBackup)
        assertTrue("Pre-restore backup file must exist and be non-empty", latestPreBackup!!.length() > 0)

        // Verify the pre-restore backup zip contains valid zip entries
        var hasDbEntry = false
        var hasInfoEntry = false
        ZipFile(latestPreBackup!!).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name == "earthlink_reseller_db" || entry.name.endsWith(".db")) hasDbEntry = true
                if (entry.name == "backup_info.json") hasInfoEntry = true
            }
        }
        assertTrue("Pre-restore backup must contain database entry", hasDbEntry)
        assertTrue("Pre-restore backup must contain backup_info.json", hasInfoEntry)
    }

    /**
     * Requirement 8: Capacity envelope measurement validates 5,000+ records processed cleanly within transaction limits.
     */
    @Test
    fun testCapacityEnvelopeMeasurementLargeDataset() = runBlocking {
        val totalAccounts = 2500
        val totalLedgers = 2500
        val totalRecords = totalAccounts + totalLedgers

        val accountsList = mutableListOf<LocalAccount>()
        val ledgersList = mutableListOf<LocalLedgerEntry>()

        for (i in 1..totalAccounts) {
            val accId = "bulk_acc_$i"
            accountsList.add(
                LocalAccount(
                    id = accId,
                    earthlinkUsername = "user_$i",
                    displayName = "Bulk Subscriber $i",
                    debtIqd = (i * 1000).toDouble(),
                    updatedAt = 1700000000000L + i
                )
            )
            ledgersList.add(
                LocalLedgerEntry(
                    id = "bulk_tx_$i",
                    accountId = accId,
                    amountIqd = (i * 1000).toDouble(),
                    debtAfterIqd = (i * 1000).toDouble(),
                    typeRaw = "took",
                    occurredAt = 1700000000000L + i
                )
            )
        }

        val largeBackupZip = createTestBackupZip(
            accounts = accountsList,
            ledgerEntries = ledgersList
        )

        assertTrue("Large backup zip must exist", largeBackupZip.exists())
        val zipSizeBytes = largeBackupZip.length()

        val runtime = Runtime.getRuntime()
        runtime.gc()
        val memBeforeBytes = runtime.totalMemory() - runtime.freeMemory()

        val startTimeMs = System.currentTimeMillis()
        val success = BackupManager.restoreBackupZip(context, largeBackupZip, force = true)
        val durationMs = System.currentTimeMillis() - startTimeMs

        runtime.gc()
        val memAfterBytes = runtime.totalMemory() - runtime.freeMemory()

        assertTrue("Large capacity restore must succeed", success)
        assertTrue("Transaction duration must complete within 30 seconds (actual: ${durationMs}ms)", durationMs < 30000)

        // Verify all 5,000 records are accurately materialized
        val liveAccountsCount = liveDb.localAccountDao().getTotalCount()
        val liveLedgersCount = liveDb.localLedgerEntryDao().getTotalCount()

        assertEquals(totalAccounts, liveAccountsCount)
        assertEquals(totalLedgers, liveLedgersCount)

        // Log capacity envelope metrics
        println("=== CAPACITY ENVELOPE METRICS ===")
        println("Total Records Restored: $totalRecords ($totalAccounts accounts, $totalLedgers ledgers)")
        println("Backup Zip Size: $zipSizeBytes bytes")
        println("Total Duration: ${durationMs} ms")
        println("Approx Peak Memory Delta: ${(memAfterBytes - memBeforeBytes) / (1024 * 1024)} MB")
        println("=================================")
    }
}
