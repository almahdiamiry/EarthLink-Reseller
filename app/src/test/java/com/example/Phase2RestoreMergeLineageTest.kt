package com.example

import android.content.Context
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
import java.util.zip.ZipOutputStream

/**
 * Phase 2 Restore Merge Lineage Test Suite (P2-G3-REQ-01 / P2-G3-REQ-02 / INV-01 / INV-06 / INV-11 / INV-14).
 *
 * Verifies:
 * 1. Same transaction in both snapshots -> exactly 1 ledger record (idempotent deduplication).
 * 2. Independent transactions in both snapshots -> both retained.
 * 3. Same-ID divergent payload -> strict conflict detected and handled (no silent overwrite, no duplicate IDs).
 * 4. Incompatible baselines cannot be silently mixed across lineages (fail-closed rejection).
 * 5. Selected lineage carries its complete eligible history (no cross-contamination).
 * 6. Repeated merge is idempotent (zero duplicate ledger rows, identical balance state).
 * 7. Derived balances match exact financial sums (zero double-counting).
 * 8. Pre-commit decision preparation evaluates candidates outside Room write transaction.
 * 9. Lineage purity validation rejects mixed baseline and ledger associations.
 * 10. Full end-to-end backup ZIP restore merge integration with audit trail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase2RestoreMergeLineageTest {

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

    private suspend fun <T> withTemporaryDatabase(
        accounts: List<LocalAccount> = emptyList(),
        ledgerEntries: List<LocalLedgerEntry> = emptyList(),
        importBatches: List<ImportBatch> = emptyList(),
        block: suspend (AppDatabase) -> T
    ): T {
        val tempDb = androidx.room.Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        if (accounts.isNotEmpty()) tempDb.localAccountDao().insertAll(accounts)
        if (ledgerEntries.isNotEmpty()) tempDb.localLedgerEntryDao().insertAll(ledgerEntries)
        for (b in importBatches) tempDb.importBatchDao().insert(b)
        return try {
            block(tempDb)
        } finally {
            tempDb.close()
        }
    }

    private fun createTestBackupZip(
        accounts: List<LocalAccount> = emptyList(),
        ledgerEntries: List<LocalLedgerEntry> = emptyList(),
        importBatches: List<ImportBatch> = emptyList(),
        auditLogs: List<AuditLog> = emptyList()
    ): File {
        val srcDbName = "test_src_${UUID.randomUUID().toString().replace("-", "")}"
        val srcDbFile = context.getDatabasePath(srcDbName)
        srcDbFile.parentFile?.mkdirs()

        val testDiskDb = AppDatabase.getDatabase(context, ByteArray(0), srcDbName)

        runBlocking {
            if (accounts.isNotEmpty()) testDiskDb.localAccountDao().insertAll(accounts)
            if (ledgerEntries.isNotEmpty()) testDiskDb.localLedgerEntryDao().insertAll(ledgerEntries)
            for (b in importBatches) testDiskDb.importBatchDao().insert(b)
            for (a in auditLogs) testDiskDb.auditLogDao().insert(a)

            try {
                testDiskDb.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE);")?.use {
                    it.moveToFirst()
                }
            } catch (_: Throwable) {}
        }

        AppDatabase.closeAndRemoveInstance(srcDbName)

        val backupDir = File(context.cacheDir, "test_backups_${UUID.randomUUID()}").apply { mkdirs() }
        val zipFile = File(backupDir, "earthlink_merge_backup_${System.currentTimeMillis()}.zip")

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
     * Test 1: Same transaction present in both snapshots -> exactly 1 ledger record (Idempotent deduplication).
     */
    @Test
    fun testSameTransactionInBothSnapshotsDeduplicatesToSingleRecord() {
        runBlocking {
            val account = LocalAccount(id = "acc_001", displayName = "Subscriber 001", debtIqd = 50000.0)
            val sharedTx = LocalLedgerEntry(
                id = "tx_shared_100",
                accountId = "acc_001",
                amountIqd = 50000.0,
                debtAfterIqd = 50000.0,
                typeRaw = "took",
                occurredAt = 1000L
            )

            liveDb.localAccountDao().insert(account)
            liveDb.localLedgerEntryDao().insert(sharedTx)

            withTemporaryDatabase(
                accounts = listOf(account),
                ledgerEntries = listOf(sharedTx)
            ) { backupDb ->
                val decision = RestoreMergeDecision(
                    artifactIdentity = "test_hash_t1",
                    selectedBaselineId = "BACKUP_SNAPSHOT",
                    isApproved = true
                )

                val result = BackupManager.executeRestoreMergeInternal(liveDb, backupDb, decision)
                assertTrue(result.success)
                assertEquals("Ledger entries must report 1 deduplicated", 1, result.ledgersDeduplicated)

                val liveLedgers = liveDb.localLedgerEntryDao().getAllOneShot()
                assertEquals("Exactly 1 ledger record must exist", 1, liveLedgers.size)
                assertEquals("tx_shared_100", liveLedgers[0].id)

                val liveAcc = liveDb.localAccountDao().getByIdOneShot("acc_001")
                assertNotNull(liveAcc)
                assertEquals(50000.0, liveAcc!!.debtIqd, 0.001)
            }
        }
    }

    /**
     * Test 2: Independent transactions in both snapshots -> both retained.
     */
    @Test
    fun testIndependentTransactionsInBothSnapshotsBothRetained() {
        runBlocking {
            val account = LocalAccount(id = "acc_002", displayName = "Subscriber 002", debtIqd = 80000.0)
            val liveTx = LocalLedgerEntry(
                id = "tx_live_only",
                accountId = "acc_002",
                amountIqd = 50000.0,
                debtAfterIqd = 50000.0,
                typeRaw = "took",
                occurredAt = 1000L
            )
            val backupTx = LocalLedgerEntry(
                id = "tx_backup_only",
                accountId = "acc_002",
                amountIqd = 30000.0,
                debtAfterIqd = 80000.0,
                typeRaw = "took",
                occurredAt = 2000L
            )

            liveDb.localAccountDao().insert(account.copy(debtIqd = 50000.0))
            liveDb.localLedgerEntryDao().insert(liveTx)

            withTemporaryDatabase(
                accounts = listOf(account.copy(debtIqd = 30000.0)),
                ledgerEntries = listOf(backupTx)
            ) { backupDb ->
                val decision = RestoreMergeDecision(
                    artifactIdentity = "test_hash_t2",
                    selectedBaselineId = "BACKUP_SNAPSHOT",
                    isApproved = true
                )

                val result = BackupManager.executeRestoreMergeInternal(liveDb, backupDb, decision)
                assertTrue(result.success)

                val liveLedgers = liveDb.localLedgerEntryDao().getAllOneShot()
                assertEquals("Both independent transactions must be retained", 2, liveLedgers.size)
                val ids = liveLedgers.map { it.id }.toSet()
                assertTrue(ids.contains("tx_live_only"))
                assertTrue(ids.contains("tx_backup_only"))

                val liveAcc = liveDb.localAccountDao().getByIdOneShot("acc_002")
                assertNotNull(liveAcc)
                assertEquals(80000.0, liveAcc!!.debtIqd, 0.001)
            }
        }
    }

    /**
     * Test 3: Same-ID divergent payload -> strict conflict detected and handled (P2-G3-REQ-01 / Section 5.3).
     * Required adversarial fixture:
     * snapshot A: T100 = amount 50,000 / type took
     * snapshot B: T100 = amount 90,000 / type took
     */
    @Test
    fun testSameIdDivergentPayloadWithoutResolutionFailsClosed() {
        runBlocking {
            val account = LocalAccount(id = "acc_003", displayName = "Subscriber 003")
            val liveTx = LocalLedgerEntry(
                id = "T100",
                accountId = "acc_003",
                amountIqd = 50000.0,
                debtAfterIqd = 50000.0,
                typeRaw = "took",
                occurredAt = 1000L
            )
            val backupTx = LocalLedgerEntry(
                id = "T100",
                accountId = "acc_003",
                amountIqd = 90000.0,
                debtAfterIqd = 90000.0,
                typeRaw = "took",
                occurredAt = 1000L
            )

            liveDb.localAccountDao().insert(account)
            liveDb.localLedgerEntryDao().insert(liveTx)

            withTemporaryDatabase(
                accounts = listOf(account),
                ledgerEntries = listOf(backupTx)
            ) { backupDb ->
                val unconfiguredDecision = RestoreMergeDecision(
                    artifactIdentity = "test_hash_t3",
                    selectedBaselineId = "BACKUP_SNAPSHOT",
                    conflictDecisions = emptyMap(),
                    isApproved = true
                )

                try {
                    BackupManager.executeRestoreMergeInternal(liveDb, backupDb, unconfiguredDecision)
                    fail("Expected DivergentPayloadConflictException for same-ID divergent payload")
                } catch (e: DivergentPayloadConflictException) {
                    assertTrue(e.message?.contains("Same-ID divergent payload conflict") == true)
                    assertTrue(e.message?.contains("T100") == true)
                }
            }
        }
    }

    /**
     * Test 3b: Same-ID divergent payload with explicit decision resolves deterministically.
     */
    @Test
    fun testSameIdDivergentPayloadWithExplicitDecisionResolvesDeterministically() {
        runBlocking {
            val account = LocalAccount(id = "acc_003b", displayName = "Subscriber 003b")
            val liveTx = LocalLedgerEntry(
                id = "T100",
                accountId = "acc_003b",
                amountIqd = 50000.0,
                debtAfterIqd = 50000.0,
                typeRaw = "took",
                occurredAt = 1000L
            )
            val backupTx = LocalLedgerEntry(
                id = "T100",
                accountId = "acc_003b",
                amountIqd = 90000.0,
                debtAfterIqd = 90000.0,
                typeRaw = "took",
                occurredAt = 1000L
            )

            liveDb.localAccountDao().insert(account)
            liveDb.localLedgerEntryDao().insert(liveTx)

            withTemporaryDatabase(
                accounts = listOf(account),
                ledgerEntries = listOf(backupTx)
            ) { backupDb ->
                val backupChoiceDecision = RestoreMergeDecision(
                    artifactIdentity = "test_hash_t3b",
                    selectedBaselineId = "BACKUP_SNAPSHOT",
                    conflictDecisions = mapOf("T100" to ConflictResolutionChoice.USE_BACKUP),
                    isApproved = true
                )

                val result = BackupManager.executeRestoreMergeInternal(liveDb, backupDb, backupChoiceDecision)
                assertTrue(result.success)

                val ledgers = liveDb.localLedgerEntryDao().getAllOneShot()
                assertEquals("Exactly 1 ledger record must exist", 1, ledgers.size)
                assertEquals("T100", ledgers[0].id)
                assertEquals(90000.0, ledgers[0].amountIqd, 0.001)

                val acc = liveDb.localAccountDao().getByIdOneShot("acc_003b")
                assertEquals(90000.0, acc!!.debtIqd, 0.001)
            }
        }
    }

    /**
     * Test 4: Incompatible baselines cannot be silently mixed across lineages (P2-G3-REQ-02 / TQ-25).
     */
    @Test
    fun testIncompatibleBaselinesCannotBeSilentlyMixedAcrossLineages() {
        runBlocking {
            val liveAccount = LocalAccount(
                id = "acc_conflict_base",
                displayName = "Baseline User",
                openingDebtIqd = 50000.0,
                debtIqd = 50000.0,
                sourceExternalId = "ext_lineage_a"
            )
            val backupAccount = LocalAccount(
                id = "acc_conflict_base",
                displayName = "Baseline User",
                openingDebtIqd = 100000.0,
                debtIqd = 100000.0,
                sourceExternalId = "ext_lineage_b"
            )

            liveDb.localAccountDao().insert(liveAccount)

            withTemporaryDatabase(
                accounts = listOf(backupAccount)
            ) { backupDb ->
                val ambiguousDecision = RestoreMergeDecision(
                    artifactIdentity = "test_hash_t4",
                    selectedBaselineId = "AMBIGUOUS_BASELINE",
                    conflictDecisions = emptyMap(),
                    isApproved = true
                )

                try {
                    BackupManager.executeRestoreMergeInternal(liveDb, backupDb, ambiguousDecision)
                    fail("Expected IncompatibleBaselineConflictException for conflicting baseline opening positions")
                } catch (e: IncompatibleBaselineConflictException) {
                    assertTrue(e.message?.contains("Incompatible opening/current baseline") == true)
                }
            }
        }
    }

    /**
     * Test 5a: Selected Live lineage carries complete eligible history without cross-contamination.
     */
    @Test
    fun testSelectedLiveLineageCarriesCompleteHistoryWithoutCrossContamination() {
        runBlocking {
            val liveAccount = LocalAccount(
                id = "acc_lineage_005a",
                displayName = "Lineage User A",
                openingDebtIqd = 50000.0,
                debtIqd = 40000.0,
                sourceExternalId = "lineage_a"
            )
            val liveTx1 = LocalLedgerEntry(id = "tx_a1", accountId = "acc_lineage_005a", amountIqd = 10000.0, debtAfterIqd = 60000.0, typeRaw = "took", occurredAt = 1000L)
            val liveTx2 = LocalLedgerEntry(id = "tx_a2", accountId = "acc_lineage_005a", amountIqd = 20000.0, debtAfterIqd = 40000.0, typeRaw = "gave", occurredAt = 2000L)

            val backupAccount = LocalAccount(
                id = "acc_lineage_005a",
                displayName = "Lineage User A",
                openingDebtIqd = 100000.0,
                debtIqd = 140000.0,
                sourceExternalId = "lineage_b"
            )
            val backupTx1 = LocalLedgerEntry(id = "tx_b1", accountId = "acc_lineage_005a", amountIqd = 40000.0, debtAfterIqd = 140000.0, typeRaw = "took", occurredAt = 3000L)

            liveDb.localAccountDao().insert(liveAccount)
            liveDb.localLedgerEntryDao().insert(liveTx1)
            liveDb.localLedgerEntryDao().insert(liveTx2)

            withTemporaryDatabase(
                accounts = listOf(backupAccount),
                ledgerEntries = listOf(backupTx1)
            ) { backupDb ->
                val decisionLive = RestoreMergeDecision(
                    artifactIdentity = "hash_t5_a",
                    selectedBaselineId = "LIVE_SNAPSHOT",
                    isApproved = true
                )
                val resultA = BackupManager.executeRestoreMergeInternal(liveDb, backupDb, decisionLive)
                assertTrue(resultA.success)

                val ledgersA = liveDb.localLedgerEntryDao().getAllOneShot()
                assertEquals(2, ledgersA.size)
                val idsA = ledgersA.map { it.id }.toSet()
                assertTrue(idsA.contains("tx_a1"))
                assertTrue(idsA.contains("tx_a2"))
                assertFalse("Tx B1 from Lineage B must NOT be mixed into Lineage A", idsA.contains("tx_b1"))

                val accA = liveDb.localAccountDao().getByIdOneShot("acc_lineage_005a")
                assertEquals(50000.0, accA!!.openingDebtIqd, 0.001)
                assertEquals(40000.0, accA.debtIqd, 0.001)
            }
        }
    }

    /**
     * Test 5b: Selected Backup lineage carries complete eligible history without cross-contamination.
     */
    @Test
    fun testSelectedBackupLineageCarriesCompleteHistoryWithoutCrossContamination() {
        runBlocking {
            val liveAccount = LocalAccount(
                id = "acc_lineage_005b",
                displayName = "Lineage User B",
                openingDebtIqd = 50000.0,
                debtIqd = 40000.0,
                sourceExternalId = "lineage_a"
            )
            val liveTx1 = LocalLedgerEntry(id = "tx_a1", accountId = "acc_lineage_005b", amountIqd = 10000.0, debtAfterIqd = 60000.0, typeRaw = "took", occurredAt = 1000L)
            val liveTx2 = LocalLedgerEntry(id = "tx_a2", accountId = "acc_lineage_005b", amountIqd = 20000.0, debtAfterIqd = 40000.0, typeRaw = "gave", occurredAt = 2000L)

            val backupAccount = LocalAccount(
                id = "acc_lineage_005b",
                displayName = "Lineage User B",
                openingDebtIqd = 100000.0,
                debtIqd = 140000.0,
                sourceExternalId = "lineage_b"
            )
            val backupTx1 = LocalLedgerEntry(id = "tx_b1", accountId = "acc_lineage_005b", amountIqd = 40000.0, debtAfterIqd = 140000.0, typeRaw = "took", occurredAt = 3000L)

            liveDb.localAccountDao().insert(liveAccount)
            liveDb.localLedgerEntryDao().insert(liveTx1)
            liveDb.localLedgerEntryDao().insert(liveTx2)

            withTemporaryDatabase(
                accounts = listOf(backupAccount),
                ledgerEntries = listOf(backupTx1)
            ) { backupDb ->
                val decisionBackup = RestoreMergeDecision(
                    artifactIdentity = "hash_t5_b",
                    selectedBaselineId = "BACKUP_SNAPSHOT",
                    isApproved = true
                )
                val resultB = BackupManager.executeRestoreMergeInternal(liveDb, backupDb, decisionBackup)
                assertTrue(resultB.success)

                val ledgersB = liveDb.localLedgerEntryDao().getAllOneShot()
                assertEquals(1, ledgersB.size)
                assertEquals("tx_b1", ledgersB[0].id)

                val accB = liveDb.localAccountDao().getByIdOneShot("acc_lineage_005b")
                assertEquals(100000.0, accB!!.openingDebtIqd, 0.001)
                assertEquals(140000.0, accB.debtIqd, 0.001)
            }
        }
    }

    /**
     * Test 6: Repeated merge is idempotent (zero duplicate ledger rows, identical balance state).
     */
    @Test
    fun testRepeatedMergeIsIdempotent() {
        runBlocking {
            val account = LocalAccount(id = "acc_idempotent", displayName = "Idempotent User", debtIqd = 60000.0)
            val tx1 = LocalLedgerEntry(id = "tx_idem_1", accountId = "acc_idempotent", amountIqd = 40000.0, debtAfterIqd = 40000.0, typeRaw = "took", occurredAt = 1000L)
            val tx2 = LocalLedgerEntry(id = "tx_idem_2", accountId = "acc_idempotent", amountIqd = 20000.0, debtAfterIqd = 60000.0, typeRaw = "took", occurredAt = 2000L)

            liveDb.localAccountDao().insert(account.copy(debtIqd = 40000.0))
            liveDb.localLedgerEntryDao().insert(tx1)

            withTemporaryDatabase(
                accounts = listOf(account),
                ledgerEntries = listOf(tx1, tx2)
            ) { backupDb ->
                val decision = RestoreMergeDecision(
                    artifactIdentity = "test_hash_t6",
                    selectedBaselineId = "BACKUP_SNAPSHOT",
                    isApproved = true
                )

                // Pass 1
                val result1 = BackupManager.executeRestoreMergeInternal(liveDb, backupDb, decision)
                assertTrue(result1.success)

                val ledgersPass1 = liveDb.localLedgerEntryDao().getAllOneShot()
                val accPass1 = liveDb.localAccountDao().getByIdOneShot("acc_idempotent")
                assertEquals(2, ledgersPass1.size)
                assertEquals(60000.0, accPass1!!.debtIqd, 0.001)

                // Pass 2 (Repeated merge)
                val result2 = BackupManager.executeRestoreMergeInternal(liveDb, backupDb, decision)
                assertTrue(result2.success)

                val ledgersPass2 = liveDb.localLedgerEntryDao().getAllOneShot()
                val accPass2 = liveDb.localAccountDao().getByIdOneShot("acc_idempotent")
                assertEquals("Repeated merge must NOT create duplicate ledger records", 2, ledgersPass2.size)
                assertEquals("Repeated merge must preserve identical balance state", 60000.0, accPass2!!.debtIqd, 0.001)
            }
        }
    }

    /**
     * Test 7: Derived balances match exact financial sums (zero double-counting).
     */
    @Test
    fun testDerivedBalancesMatchExactFinancialSums() {
        runBlocking {
            val account = LocalAccount(
                id = "acc_math_exact",
                displayName = "Math Exact User",
                openingDebtIqd = 20000.0,
                debtIqd = 20000.0
            )

            val liveTxs = listOf(
                LocalLedgerEntry(id = "tx_m1", accountId = "acc_math_exact", amountIqd = 10000.0, debtAfterIqd = 30000.0, typeRaw = "took", occurredAt = 1000L),
                LocalLedgerEntry(id = "tx_m2", accountId = "acc_math_exact", amountIqd = 5000.0, debtAfterIqd = 25000.0, typeRaw = "gave", occurredAt = 2000L)
            )
            val backupTxs = listOf(
                LocalLedgerEntry(id = "tx_m1", accountId = "acc_math_exact", amountIqd = 10000.0, debtAfterIqd = 30000.0, typeRaw = "took", occurredAt = 1000L), // Shared/Deduplicated
                LocalLedgerEntry(id = "tx_m3", accountId = "acc_math_exact", amountIqd = 15000.0, debtAfterIqd = 40000.0, typeRaw = "took", occurredAt = 3000L),
                LocalLedgerEntry(id = "tx_m4", accountId = "acc_math_exact", amountIqd = 20000.0, debtAfterIqd = 20000.0, typeRaw = "gave", occurredAt = 4000L)
            )

            liveDb.localAccountDao().insert(account)
            liveDb.localLedgerEntryDao().insertAll(liveTxs)

            withTemporaryDatabase(
                accounts = listOf(account),
                ledgerEntries = backupTxs
            ) { backupDb ->
                val decision = RestoreMergeDecision(
                    artifactIdentity = "test_hash_t7",
                    selectedBaselineId = "BACKUP_SNAPSHOT",
                    isApproved = true
                )

                val result = BackupManager.executeRestoreMergeInternal(liveDb, backupDb, decision)
                assertTrue(result.success)

                val allLedgers = liveDb.localLedgerEntryDao().getAllOneShot().sortedBy { it.occurredAt }
                assertEquals(4, allLedgers.size)

                // Mathematical verification:
                // Baseline: +20,000
                // tx_m1 (took): +10,000 -> 30,000
                // tx_m2 (gave): -5,000  -> 25,000
                // tx_m3 (took): +15,000 -> 40,000
                // tx_m4 (gave): -20,000 -> 20,000
                val acc = liveDb.localAccountDao().getByIdOneShot("acc_math_exact")
                assertNotNull(acc)
                assertEquals(20000.0, acc!!.debtIqd, 0.001)

                assertEquals(30000.0, allLedgers[0].debtAfterIqd, 0.001)
                assertEquals(25000.0, allLedgers[1].debtAfterIqd, 0.001)
                assertEquals(40000.0, allLedgers[2].debtAfterIqd, 0.001)
                assertEquals(20000.0, allLedgers[3].debtAfterIqd, 0.001)
            }
        }
    }

    /**
     * Test 8: Pre-commit decision preparation evaluates candidates outside Room write transaction.
     */
    @Test
    fun testPreCommitDecisionPreparationLeavesLiveDbUntouched() {
        runBlocking {
            liveDb.localAccountDao().insert(LocalAccount(id = "preserve_acc", displayName = "Preserved", debtIqd = 10000.0))
            liveDb.localLedgerEntryDao().insert(LocalLedgerEntry(id = "preserve_tx", accountId = "preserve_acc", amountIqd = 10000.0, debtAfterIqd = 10000.0, typeRaw = "took"))

            val backupAcc = LocalAccount(id = "candidate_acc", displayName = "Candidate", debtIqd = 90000.0)
            val backupZip = createTestBackupZip(accounts = listOf(backupAcc))

            val decision = BackupManager.prepareRestoreMergeDecision(
                context = context,
                backupFile = backupZip,
                selectedBaselineId = "BACKUP_SNAPSHOT",
                isApproved = false
            )

            assertFalse(decision.isApproved)
            assertEquals(BackupManager.calculateFileHash(backupZip), decision.artifactIdentity)

            // Live database is verified completely untouched
            val accounts = liveDb.localAccountDao().getAllOneShot()
            val ledgers = liveDb.localLedgerEntryDao().getAllOneShot()
            assertEquals(1, accounts.size)
            assertEquals("preserve_acc", accounts[0].id)
            assertEquals(1, ledgers.size)
            assertEquals("preserve_tx", ledgers[0].id)
        }
    }

    /**
     * Test 9: Lineage purity validation rejects mixed baseline and ledger associations.
     */
    @Test
    fun testLineagePurityValidationRejectsMixedLineages() {
        val baseline = LocalAccount(id = "acc_pure_1", sourceBatchId = "batch_alpha")
        val foreignTx = LocalLedgerEntry(id = "tx_foreign", accountId = "acc_foreign", amountIqd = 5000.0, debtAfterIqd = 5000.0, typeRaw = "took")

        try {
            BackupManager.validateLineagePairing(baseline, listOf(foreignTx), expectedLineageId = "batch_alpha")
            fail("Expected MixedLineageConflictException for mismatched account in lineage")
        } catch (e: MixedLineageConflictException) {
            assertTrue(e.message?.contains("Lineage purity violation") == true)
        }

        val mismatchedBatchTx = LocalLedgerEntry(id = "tx_beta", accountId = "acc_pure_1", sourceBatchId = "batch_beta", amountIqd = 5000.0, debtAfterIqd = 5000.0, typeRaw = "took")
        try {
            BackupManager.validateLineagePairing(baseline, listOf(mismatchedBatchTx), expectedLineageId = "batch_alpha")
            fail("Expected MixedLineageConflictException for mismatched batch lineage")
        } catch (e: MixedLineageConflictException) {
            assertTrue(e.message?.contains("Lineage purity violation") == true)
        }
    }

    /**
     * Test 10: Full end-to-end backup ZIP restore merge integration with audit trail.
     */
    @Test
    fun testFullBackupZipRestoreMergeIntegration() {
        runBlocking {
            // Initial live state
            liveDb.localAccountDao().insert(LocalAccount(id = "acc_live_e2e", displayName = "Live Subscriber", debtIqd = 25000.0))
            liveDb.localLedgerEntryDao().insert(LocalLedgerEntry(id = "tx_live_e2e", accountId = "acc_live_e2e", amountIqd = 25000.0, debtAfterIqd = 25000.0, typeRaw = "took", occurredAt = 1000L))

            // Backup state with an independent subscriber and an incremental transaction
            val backupAcc1 = LocalAccount(id = "acc_live_e2e", displayName = "Live Subscriber", debtIqd = 25000.0)
            val backupTx1 = LocalLedgerEntry(id = "tx_live_e2e", accountId = "acc_live_e2e", amountIqd = 25000.0, debtAfterIqd = 25000.0, typeRaw = "took", occurredAt = 1000L) // shared
            val backupTx2 = LocalLedgerEntry(id = "tx_new_e2e", accountId = "acc_live_e2e", amountIqd = 15000.0, debtAfterIqd = 40000.0, typeRaw = "took", occurredAt = 2000L) // new

            val backupAcc2 = LocalAccount(id = "acc_new_e2e", displayName = "New Subscriber", debtIqd = 50000.0)
            val backupTx3 = LocalLedgerEntry(id = "tx_sub2_e2e", accountId = "acc_new_e2e", amountIqd = 50000.0, debtAfterIqd = 50000.0, typeRaw = "took", occurredAt = 3000L)

            val backupZip = createTestBackupZip(
                accounts = listOf(backupAcc1, backupAcc2),
                ledgerEntries = listOf(backupTx1, backupTx2, backupTx3)
            )

            val decision = BackupManager.prepareRestoreMergeDecision(
                context = context,
                backupFile = backupZip,
                selectedBaselineId = "BACKUP_SNAPSHOT",
                isApproved = true
            )

            val mergeSuccess = BackupManager.restoreMergeWithDecision(context, backupZip, decision, force = true)
            assertTrue("Restore merge execution must succeed", mergeSuccess)

            val accounts = liveDb.localAccountDao().getAllOneShot().sortedBy { it.id }
            assertEquals(2, accounts.size)
            assertEquals("acc_live_e2e", accounts[0].id)
            assertEquals(40000.0, accounts[0].debtIqd, 0.001)
            assertEquals("acc_new_e2e", accounts[1].id)
            assertEquals(50000.0, accounts[1].debtIqd, 0.001)

            val ledgers = liveDb.localLedgerEntryDao().getAllOneShot()
            assertEquals("Exactly 3 unique ledger entries must exist", 3, ledgers.size)

            // Audit log created with signed DATABASE_RESTORE_MERGE action
            val auditLogs = liveDb.auditLogDao().getAllSync()
            val restoreMergeLog = auditLogs.find { it.action == "DATABASE_RESTORE_MERGE" }
            assertNotNull("DATABASE_RESTORE_MERGE audit log must be recorded", restoreMergeLog)
            assertNotNull("Audit log signature must be present", restoreMergeLog?.signature)
        }
    }
}
