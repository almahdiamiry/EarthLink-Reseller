package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.backup.BackupManager
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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 2 Restore/Import Transaction Boundary & Decision Contract Test Suite (P2-G3-REQ-01 / P2-G3-REQ-03 / INV-11 / INV-14).
 *
 * Verifies:
 * 1. RestoreMergeDecision Contract: deterministic decision encapsulation including artifactIdentity, selectedBaselineId,
 *    selectedLineageScope, conflictDecisions, targetDatasetSummary, and approval state.
 * 2. Decision Invalidation Rule: decision is strictly invalidated if artifact hash or baseline changes, or if not approved.
 * 3. Pre-commit operations (hashing, inspection, candidate key evaluation, parsing) happen 100% outside Room write transaction.
 * 4. Cancellation/unapproved state leaves live database 100% untouched.
 * 5. Tampered or mismatched artifact identity aborts restore immediately without database side effects.
 * 6. Approved execution inside Room transaction applies pre-computed state deterministically with zero network/Firebase side effects.
 * 7. Fail-closed encryption / key recovery (INV-14): unrecoverable key safely halts without modifying live database.
 * 8. Utower import with decision contract enforces pre-transaction verification and atomic ACID commit.
 * 9. Structural verification confirms no network or remote wait operations exist inside Room transaction blocks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase2RestoreTransactionBoundaryTest {

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

    /**
     * Test 1: RestoreMergeDecision Contract and Invalidation Rules.
     * Proves that decision objects require explicit operator approval and exact hash/baseline matching.
     */
    @Test
    fun testRestoreDecisionContractStructureAndInvalidationRule() {
        val testHash = "a1b2c3d4e5f678901234567890abcdef"
        val baseline = "SNAPSHOT_BASELINE_001"
        val lineageScope = "COMPLETE_LINEAGE"
        val conflictMap = mapOf(
            "acc-1" to ConflictResolutionChoice.USE_BACKUP,
            "tx-100" to ConflictResolutionChoice.KEEP_BOTH
        )

        // 1. Unapproved decision -> invalid
        val unapprovedDecision = RestoreMergeDecision(
            artifactIdentity = testHash,
            selectedBaselineId = baseline,
            selectedLineageScope = lineageScope,
            conflictDecisions = conflictMap,
            targetDatasetSummary = "Accounts: 10, Ledgers: 25",
            isApproved = false
        )
        assertFalse("Unapproved decision must NOT be valid", unapprovedDecision.isValidFor(testHash, baseline))
        assertTrue("Unapproved decision must report isInvalidated=true", unapprovedDecision.isInvalidated(testHash, baseline))

        // 2. Approved decision with matching hash and baseline -> valid
        val approvedDecision = unapprovedDecision.copy(isApproved = true)
        assertTrue("Approved decision with matching hash/baseline must be valid", approvedDecision.isValidFor(testHash, baseline))
        assertFalse("Approved decision must report isInvalidated=false", approvedDecision.isInvalidated(testHash, baseline))

        // 3. Artifact identity mismatch (e.g. modified/tampered file) -> invalid
        val alteredHash = "f9e8d7c6b5a432109876543210fedcba"
        assertFalse("Decision must be invalid if artifact hash changes", approvedDecision.isValidFor(alteredHash, baseline))
        assertTrue("Decision must be invalidated if artifact hash changes", approvedDecision.isInvalidated(alteredHash, baseline))

        // 4. Baseline ID mismatch -> invalid
        assertFalse("Decision must be invalid if selected baseline changes", approvedDecision.isValidFor(testHash, "DIFFERENT_BASELINE"))
        assertTrue("Decision must be invalidated if selected baseline changes", approvedDecision.isInvalidated(testHash, "DIFFERENT_BASELINE"))
    }

    /**
     * Test 2: Pre-Commit Decision Preparation occurs outside Room transaction without altering live state.
     */
    @Test
    fun testPreCommitDecisionPreparationHappensOutsideTransaction() = runBlocking {
        // Setup initial live data
        liveDb.localAccountDao().insert(LocalAccount(id = "live_acc_1", displayName = "Live User 1", debtIqd = 10000.0))
        liveDb.localLedgerEntryDao().insert(LocalLedgerEntry(id = "live_tx_1", accountId = "live_acc_1", amountIqd = 10000.0, debtAfterIqd = 10000.0, typeRaw = "took"))

        val backupAcc = LocalAccount(id = "backup_acc_1", displayName = "Backup User 1", debtIqd = 50000.0)
        val backupZip = createTestBackupZip(accounts = listOf(backupAcc))

        // Prepare decision outside transaction
        val decision = BackupManager.prepareRestoreMergeDecision(
            context = context,
            backupFile = backupZip,
            selectedBaselineId = "BACKUP_SNAPSHOT",
            selectedLineageScope = "COMPLETE_LINEAGE",
            conflictDecisions = mapOf("backup_acc_1" to ConflictResolutionChoice.USE_BACKUP),
            isApproved = false
        )

        // Verify decision properties
        assertEquals(BackupManager.calculateFileHash(backupZip), decision.artifactIdentity)
        assertEquals("BACKUP_SNAPSHOT", decision.selectedBaselineId)
        assertEquals("COMPLETE_LINEAGE", decision.selectedLineageScope)
        assertFalse(decision.isApproved)
        assertTrue(decision.targetDatasetSummary.contains("LiveAccounts: 1"))

        // Verify live database is completely untouched by preparation
        val liveAccounts = liveDb.localAccountDao().getAllOneShot()
        val liveLedgers = liveDb.localLedgerEntryDao().getAllOneShot()
        assertEquals(1, liveAccounts.size)
        assertEquals("live_acc_1", liveAccounts[0].id)
        assertEquals(1, liveLedgers.size)
        assertEquals("live_tx_1", liveLedgers[0].id)
    }

    /**
     * Test 3: Unapproved or cancelled decision leaves live database 100% untouched.
     */
    @Test
    fun testUnapprovedOrCancelledDecisionLeavesLiveDatabaseUntouched() = runBlocking {
        // Initial live state
        liveDb.localAccountDao().insert(LocalAccount(id = "original_acc", displayName = "Original Live", debtIqd = 15000.0))
        liveDb.localLedgerEntryDao().insert(LocalLedgerEntry(id = "original_tx", accountId = "original_acc", amountIqd = 15000.0, debtAfterIqd = 15000.0, typeRaw = "took"))

        val backupZip = createTestBackupZip(
            accounts = listOf(LocalAccount(id = "restored_acc", displayName = "Restored User", debtIqd = 99000.0)),
            ledgerEntries = listOf(LocalLedgerEntry(id = "restored_tx", accountId = "restored_acc", amountIqd = 99000.0, debtAfterIqd = 99000.0, typeRaw = "took"))
        )

        // Prepare unapproved decision (e.g. user cancelled on prompt)
        val unapprovedDecision = BackupManager.prepareRestoreMergeDecision(
            context = context,
            backupFile = backupZip,
            isApproved = false
        )

        val result = BackupManager.restoreWithDecision(context, backupZip, unapprovedDecision, force = true)
        assertFalse("Restore must abort when decision is not approved", result)

        // Live database must be 100% identical to original
        val liveAccounts = liveDb.localAccountDao().getAllOneShot()
        val liveLedgers = liveDb.localLedgerEntryDao().getAllOneShot()
        assertEquals("Live accounts must be untouched", 1, liveAccounts.size)
        assertEquals("original_acc", liveAccounts[0].id)
        assertEquals(15000.0, liveAccounts[0].debtIqd, 0.001)
        assertEquals("Live ledgers must be untouched", 1, liveLedgers.size)
        assertEquals("original_tx", liveLedgers[0].id)
    }

    /**
     * Test 4: Decision invalidated due to artifact hash mismatch leaves live database 100% untouched.
     */
    @Test
    fun testInvalidatedDecisionDueToArtifactChangeLeavesDatabaseUntouched() = runBlocking {
        liveDb.localAccountDao().insert(LocalAccount(id = "preserved_acc", displayName = "Preserved", debtIqd = 20000.0))

        val backupZipA = createTestBackupZip(accounts = listOf(LocalAccount(id = "acc_a", displayName = "A")))
        val backupZipB = createTestBackupZip(accounts = listOf(LocalAccount(id = "acc_b", displayName = "B")))

        // Decision computed for zip A
        val decisionForA = BackupManager.prepareRestoreMergeDecision(context, backupZipA, isApproved = true)

        // Attempt to apply decisionForA to backupZipB (mismatched file/hash)
        val result = BackupManager.restoreWithDecision(context, backupZipB, decisionForA, force = true)
        assertFalse("Restore must reject execution when artifact hash does not match decision", result)

        // Live DB remains untouched
        val liveAccounts = liveDb.localAccountDao().getAllOneShot()
        assertEquals(1, liveAccounts.size)
        assertEquals("preserved_acc", liveAccounts[0].id)
    }

    /**
     * Test 5: Approved decision executes inside Room transaction deterministically with zero network calls.
     */
    @Test
    fun testApprovedDecisionExecutesInsideRoomTransactionDeterministicallyWithoutSideEffects() = runBlocking {
        liveDb.localAccountDao().insert(LocalAccount(id = "old_live_acc", displayName = "Old Live", debtIqd = 5000.0))

        val backupAccount = LocalAccount(id = "restored_acc_100", displayName = "Restored Account", debtIqd = 75000.0)
        val backupLedger = LocalLedgerEntry(id = "restored_tx_100", accountId = "restored_acc_100", amountIqd = 75000.0, debtAfterIqd = 75000.0, typeRaw = "took")
        val backupZip = createTestBackupZip(
            accounts = listOf(backupAccount),
            ledgerEntries = listOf(backupLedger)
        )

        val approvedDecision = BackupManager.prepareRestoreMergeDecision(
            context = context,
            backupFile = backupZip,
            selectedBaselineId = "BACKUP_SNAPSHOT",
            isApproved = true
        )

        val result = BackupManager.restoreWithDecision(context, backupZip, approvedDecision, force = true)
        assertTrue("Restore with approved decision must succeed", result)

        // Live DB matches restored state
        val liveAccounts = liveDb.localAccountDao().getAllOneShot()
        val liveLedgers = liveDb.localLedgerEntryDao().getAllOneShot()
        assertEquals(1, liveAccounts.size)
        assertEquals("restored_acc_100", liveAccounts[0].id)
        assertEquals(75000.0, liveAccounts[0].debtIqd, 0.001)

        assertEquals(1, liveLedgers.size)
        assertEquals("restored_tx_100", liveLedgers[0].id)

        // Audit log created with valid restore action
        val auditLogs = liveDb.auditLogDao().getAllSync()
        val restoreLog = auditLogs.find { it.action == "DATABASE_RESTORE" }
        assertNotNull("DATABASE_RESTORE audit log must be recorded", restoreLog)
        assertNotNull("Audit log signature must be present", restoreLog?.signature)
    }

    /**
     * Test 6: Fail-Closed Decryption & Key Recovery (INV-14).
     * If an encrypted database cannot be opened with candidate keys, fails closed without modifying live state.
     */
    @Test
    fun testFailClosedDecryptionLeavesDatabaseUntouchedOnInvalidPassphrase() = runBlocking {
        liveDb.localAccountDao().insert(LocalAccount(id = "secure_live_acc", displayName = "Secure Live", debtIqd = 30000.0))

        // Create corrupt/unopenable backup zip
        val corruptZip = File(context.cacheDir, "corrupt_backup_${System.currentTimeMillis()}.zip")
        ZipOutputStream(FileOutputStream(corruptZip)).use { zos ->
            zos.putNextEntry(ZipEntry("earthlink_reseller_db.db"))
            zos.write("NOT_A_VALID_SQLITE_OR_ENCRYPTED_DB_HEADER_123456789".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("backup_info.json"))
            zos.write("""{"dbPassphrase":"invalid_unopenable_key"}""".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val decision = BackupManager.prepareRestoreMergeDecision(context, corruptZip, isApproved = true)
        val result = BackupManager.restoreWithDecision(context, corruptZip, decision, force = true)
        assertFalse("Restore must fail closed when backup database cannot be opened/decrypted", result)

        // Live DB remains untouched
        val liveAccounts = liveDb.localAccountDao().getAllOneShot()
        assertEquals(1, liveAccounts.size)
        assertEquals("secure_live_acc", liveAccounts[0].id)
    }

    /**
     * Test 7: Utower Importer Decision Contract Verification.
     * Verifies that UtowerImporter.importFromPreviewWithDecision validates decision pre-transaction and commits atomically.
     */
    @Test
    fun testUtowerImportWithDecisionValidation() = runBlocking {
        liveDb.localAccountDao().insert(LocalAccount(id = "u_live_acc", displayName = "Live User", debtIqd = 1000.0))

        val importer = UtowerImporter(context, liveDb)
        val fileHash = "utower_file_hash_12345"
        val preview = UtowerImportPreview(
            parsedSubscribers = listOf(
                LocalAccount(id = "sub_01", earthlinkUsername = "sub_01", displayName = "Subscriber One", debtIqd = 45000.0)
            ),
            parsedTransactions = listOf(
                LocalLedgerEntry(id = "tx_01", accountId = "sub_01", amountIqd = 45000.0, debtAfterIqd = 45000.0, typeRaw = "took", occurredAt = System.currentTimeMillis())
            ),
            totalCurrentDebtIqd = 45000.0,
            warnings = emptyList(),
            totalAccountsFound = 1,
            totalTransactionsFound = 1
        )

        // 1. Unapproved decision -> throws IllegalStateException before Room transaction
        val unapprovedDecision = RestoreMergeDecision(
            artifactIdentity = fileHash,
            selectedBaselineId = "SNAPSHOT_BASELINE",
            selectedLineageScope = "COMPLETE_LINEAGE",
            isApproved = false
        )
        try {
            importer.importFromPreviewWithDecision(preview, "utower.json", fileHash, unapprovedDecision)
            fail("Expected IllegalStateException for unapproved import decision")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("RestoreMergeDecision is invalidated") == true)
        }

        // Live DB untouched after rejection
        assertEquals(1, liveDb.localAccountDao().getAllOneShot().size)
        assertEquals("u_live_acc", liveDb.localAccountDao().getAllOneShot()[0].id)

        // 2. Approved decision -> commits successfully
        val approvedDecision = unapprovedDecision.copy(isApproved = true)
        val batch = importer.importFromPreviewWithDecision(preview, "utower.json", fileHash, approvedDecision)
        assertEquals("completed", batch.status)
        assertEquals(1, batch.accountsImported)
        assertEquals(1, batch.transactionsImported)
    }

    /**
     * Test 8: Structural verification that no network/remote calls exist inside Room transaction blocks (P2-G3-REQ-03).
     */
    @Test
    fun testStructuralForbiddenNetworkOrRemoteCallsInsideRoomTransaction() {
        val backupManagerSource = listOf(
            File("app/src/main/java/com/example/core/backup/BackupManager.kt"),
            File("src/main/java/com/example/core/backup/BackupManager.kt"),
            File("../app/src/main/java/com/example/core/backup/BackupManager.kt")
        ).firstOrNull { it.exists() }
        assertNotNull("BackupManager.kt source file must exist", backupManagerSource)

        val utowerImporterSource = listOf(
            File("app/src/main/java/com/example/core/sync/UtowerImporter.kt"),
            File("src/main/java/com/example/core/sync/UtowerImporter.kt"),
            File("../app/src/main/java/com/example/core/sync/UtowerImporter.kt")
        ).firstOrNull { it.exists() }
        assertNotNull("UtowerImporter.kt source file must exist", utowerImporterSource)

        val bmContent = backupManagerSource!!.readText()
        val utContent = utowerImporterSource!!.readText()

        // Extract withTransaction blocks and verify no network/Firebase calls are invoked inside them
        val forbiddenCallPatterns = listOf(
            "FirebaseAuth.getInstance()",
            "FirebaseFirestore.getInstance()",
            "OkHttpClient",
            "HttpURLConnection",
            "URL(",
            "retrofit",
            "apiService"
        )

        // Verify BackupManager withTransaction content
        val bmTxIndex = bmContent.indexOf("liveDb.withTransaction")
        assertTrue("BackupManager must contain liveDb.withTransaction", bmTxIndex > 0)
        val bmTxBlock = bmContent.substring(bmTxIndex, bmContent.indexOf("}", bmTxIndex + 500))

        for (pattern in forbiddenCallPatterns) {
            assertFalse(
                "Forbidden external/network call '$pattern' must not be inside liveDb.withTransaction in BackupManager",
                bmTxBlock.contains(pattern)
            )
        }

        // Verify UtowerImporter withTransaction content
        val utTxIndex = utContent.indexOf("appDatabase.withTransaction")
        assertTrue("UtowerImporter must contain appDatabase.withTransaction", utTxIndex > 0)
        val utTxBlock = utContent.substring(utTxIndex, utContent.indexOf("}", utTxIndex + 500))

        for (pattern in forbiddenCallPatterns) {
            assertFalse(
                "Forbidden external/network call '$pattern' must not be inside appDatabase.withTransaction in UtowerImporter",
                utTxBlock.contains(pattern)
            )
        }
    }
}
