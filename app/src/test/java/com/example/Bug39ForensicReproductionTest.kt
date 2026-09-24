package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.backup.BackupManager
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Permanent Regression Test for BUG-39:
 * Backup database cloning pagination beyond 100,000 rows.
 *
 * Core Triad:
 * - Claim: Invariant INV-02 & INV-06: Complete backup cloning and history preservation. Datasets
 *   exceeding 100,000 rows must be fully paginated into the backup database without silent truncation.
 * - Seam / Environment: ROBOLECTRIC in-memory SQLite database executing the real production backup creation
 *   and restore pipeline (BackupManager.createLocalBackupZip and BackupManager.restoreBackupZip).
 * - Independent Oracle: Strict count equality (100,000 -> 100,000 and 100,001 -> 100,001) and explicit
 *   sentinel row presence at boundary tails, independent of internal pagination batch sizing.
 *
 * Scenarios:
 * Case A (Boundary): Exactly 100,000 ledger rows -> Backup preserves all 100,000 rows.
 * Case B (One Over Boundary): Exactly 100,001 ledger rows with oldest-tail sentinel row.
 *    -> Backup fully paginates all 100,001 rows.
 *    -> Restored database contains exactly 100,001 rows including the sentinel row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Bug39ForensicReproductionTest {

    private lateinit var app: EarthlinkApp
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.isSafeDebugFallbackAllowedOverride = true
        AppDatabase.closeDatabase()
        app.getDatabasePath("earthlink_reseller_db").parentFile?.mkdirs()
        db = app.database
        runBlocking {
            db.localLedgerEntryDao().deleteAll()
            db.localAccountDao().deleteAll()
            db.importBatchDao().deleteAll()
            db.syncOutboxDao().deleteAll()
            db.syncMetadataDao().deleteAll()
        }
    }

    @After
    fun tearDown() {
        AppDatabase.closeDatabase()
    }

    private fun seedAccount(accountId: String = "acc_main") = runBlocking {
        val account = LocalAccount(
            id = accountId,
            displayName = "Main Test Subscriber",
            earthlinkUsername = "test_user",
            debtIqd = 0.0,
            updatedAt = 1000L
        )
        db.localAccountDao().insert(account)
    }

    private fun insertLedgerRows(count: Int, sentinelId: String? = null) {
        val sqliteDb = db.openHelper.writableDatabase
        sqliteDb.beginTransaction()
        try {
            val stmt = sqliteDb.compileStatement(
                "INSERT INTO local_ledger_entries (id, accountId, typeRaw, amountIqd, debtAfterIqd, occurredAt, createdAt, isSnapshotHistory) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            )
            for (i in 1..count) {
                val rowId = if (i == count && sentinelId != null) sentinelId else "tx_${String.format("%07d", i)}"
                // For determinism: occurredAt is decreasing so earliest inserted rows have highest occurredAt
                // or increasing: let's use timestamp proportional to i
                val ts = 1000000L + i
                stmt.bindString(1, rowId)
                stmt.bindString(2, "acc_main")
                stmt.bindString(3, "took")
                stmt.bindDouble(4, 1000.0)
                stmt.bindDouble(5, 1000.0)
                stmt.bindLong(6, ts)
                stmt.bindLong(7, ts)
                stmt.bindLong(8, 0)
                stmt.executeInsert()
            }
            sqliteDb.setTransactionSuccessful()
        } finally {
            sqliteDb.endTransaction()
        }
    }

    // =====================================================================
    // CASE A — BOUNDARY (100,000 rows)
    // =====================================================================
    @Test
    fun testCaseA_boundary_100000Rows_fullyPreservedInBackup(): Unit = runBlocking {
        seedAccount()
        val rowCount = 100000
        val sentinelId = "tx_sentinel_100k"
        insertLedgerRows(rowCount, sentinelId = sentinelId)

        assertEquals(100000, db.localLedgerEntryDao().getTotalCount())
        assertNotNull("Sentinel row must exist before backup", db.localLedgerEntryDao().getByIdOneShot(sentinelId))

        // Create backup using the production path
        val backupZip = BackupManager.createLocalBackupZip(app)
        assertNotNull(backupZip)

        // Clear local database to simulate restore on a clean device
        db.localLedgerEntryDao().deleteAll()
        assertEquals(0, db.localLedgerEntryDao().getTotalCount())

        // Restore backup
        val restoreSuccess = BackupManager.restoreBackupZip(app, backupZip, force = true)
        assertEquals("Restore must succeed", true, restoreSuccess)

        // Verify all 100,000 rows restored
        val restoredCount = db.localLedgerEntryDao().getTotalCount()
        assertEquals("Negative Twin: All 100,000 rows must be restored", 100000, restoredCount)
        assertNotNull("Sentinel row must be present after restore", db.localLedgerEntryDao().getByIdOneShot(sentinelId))

        backupZip.delete()
        Unit
    }

    // =====================================================================
    // CASE B — ONE OVER BOUNDARY (100,001 rows) -> VERIFIES BUG-39 REMEDIATION
    // =====================================================================
    @Test
    fun testCaseB_oneOverBoundary_100001Rows_fullyPreservedInBackup(): Unit = runBlocking {
        seedAccount()
        val rowCount = 100001
        // The DAO query orders by `occurredAt DESC, createdAt DESC, id DESC`.
        // The row with smallest occurredAt (i=1) will be at index 100,001 (beyond former LIMIT 100000 OFFSET 0).
        // We set sentinel with occurredAt = 1 (oldest), so it is ranked #100,001 in the DESC ordering.
        val sqliteDb = db.openHelper.writableDatabase
        sqliteDb.beginTransaction()
        try {
            val stmt = sqliteDb.compileStatement(
                "INSERT INTO local_ledger_entries (id, accountId, typeRaw, amountIqd, debtAfterIqd, occurredAt, createdAt, isSnapshotHistory) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            )
            // Insert sentinel as the oldest row (was omitted by former unpaginated LIMIT 100000)
            stmt.bindString(1, "tx_sentinel_oldest_tail")
            stmt.bindString(2, "acc_main")
            stmt.bindString(3, "took")
            stmt.bindDouble(4, 1000.0)
            stmt.bindDouble(5, 1000.0)
            stmt.bindLong(6, 1L) // Oldest timestamp
            stmt.bindLong(7, 1L)
            stmt.bindLong(8, 0)
            stmt.executeInsert()

            // Insert remaining 100,000 rows with higher timestamps
            for (i in 2..100001) {
                val rowId = "tx_${String.format("%07d", i)}"
                val ts = 1000000L + i
                stmt.bindString(1, rowId)
                stmt.bindString(2, "acc_main")
                stmt.bindString(3, "took")
                stmt.bindDouble(4, 1000.0)
                stmt.bindDouble(5, 1000.0)
                stmt.bindLong(6, ts)
                stmt.bindLong(7, ts)
                stmt.bindLong(8, 0)
                stmt.executeInsert()
            }
            sqliteDb.setTransactionSuccessful()
        } finally {
            sqliteDb.endTransaction()
        }

        assertEquals("Precondition: exactly 100,001 rows exist in database", 100001, db.localLedgerEntryDao().getTotalCount())
        assertNotNull("Sentinel row exists prior to backup", db.localLedgerEntryDao().getByIdOneShot("tx_sentinel_oldest_tail"))

        // Create backup via production path
        val backupZip = BackupManager.createLocalBackupZip(app)
        assertNotNull("Backup zip was created", backupZip)

        // Clear local database to simulate recovery
        db.localLedgerEntryDao().deleteAll()
        assertEquals(0, db.localLedgerEntryDao().getTotalCount())

        // Restore backup
        val restoreSuccess = BackupManager.restoreBackupZip(app, backupZip, force = true)
        assertEquals("Restore succeeded without warning of incomplete backup", true, restoreSuccess)

        // Remediation Verified: Restored count matches exactly 100,001 rows!
        val restoredCount = db.localLedgerEntryDao().getTotalCount()
        assertEquals(
            "Remediation Verified: All 100,001 rows must be preserved in backup and restored without truncation",
            100001,
            restoredCount
        )

        // The sentinel row is preserved in the restored database
        assertNotNull(
            "Remediation Verified: Sentinel row beyond former 100,000 limit was safely paginated and restored",
            db.localLedgerEntryDao().getByIdOneShot("tx_sentinel_oldest_tail")
        )

        backupZip.delete()
        Unit
    }
}
