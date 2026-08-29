package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.sync.UtowerImporter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Audit and Forensic Verification Suite for utower_data_c.tgz.
 * This test executes the uTower legacy import on the real production-shaped dataset,
 * builds the Canonical Data Oracle metrics, and verifies Phase A (Import Integrity) constraints.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class UtowerDataCAuditTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var importer: UtowerImporter

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        (context as? EarthlinkApp)?.isSafeDebugFallbackAllowedOverride = true
        
        // Build in-memory database to avoid modifying disk-based production data
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        
        importer = UtowerImporter(context, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun findDatasetFile(): File {
        val searchNames = listOf("utower_data_c.tgz", "utower_data_c.tar.gz")
        var currentDir = File(".")
        
        // Search current folder and up to 5 levels of parent directories
        for (i in 0..5) {
            for (name in searchNames) {
                val f = File(currentDir, name)
                if (f.exists() && f.isFile) {
                    return f
                }
            }
            currentDir = currentDir.parentFile ?: break
        }
        
        // Check absolute path
        for (name in searchNames) {
            val absoluteFile = File("/$name")
            if (absoluteFile.exists() && absoluteFile.isFile) {
                return absoluteFile
            }
            val containerFile = File("/app/applet/$name")
            if (containerFile.exists() && containerFile.isFile) {
                return containerFile
            }
        }
        
        throw IllegalStateException("Unable to locate utower_data_c.tgz archive. Checked standard relative and absolute paths.")
    }

    @Test
    fun executeSystemWideImportAudit() = runBlocking {
        // 1. Locate and verify the archive
        val datasetFile = findDatasetFile()
        println("SUCCESS: Located canonical oracle dataset at: ${datasetFile.absolutePath} (Size: ${datasetFile.length()} bytes)")
        
        // Capture initial generation before import
        val initialGeneration = db.syncMetadataDao().getGeneration()
        
        // 2. Perform the import with shouldReplace = true (Phase A - Import Integrity)
        val importResult = importer.importFromFile(datasetFile, shouldReplace = true)
        
        assertTrue("Import from utower_data_c.tgz must report success", importResult.success)
        assertNotNull("Batch ID must be generated", importResult.batchId)
        
        // 3. Extract and verify General Dataset Metrics
        val accounts = db.localAccountDao().getAllOneShot(limit = Int.MAX_VALUE)
        val ledgers = db.localLedgerEntryDao().getAllOneShot(limit = Int.MAX_VALUE)
        val batches = db.importBatchDao().getAllOneShot()
        val outbox = db.syncOutboxDao().getAllOneShot()
        
        println("=== CANONICAL ORACLE GENERAL METRICS ===")
        println("Imported Account Count:          ${accounts.size}")
        println("Imported Ledger Entries Count:   ${ledgers.size}")
        println("Outbox Entries Generated:        ${outbox.size}")
        println("========================================")
        
        // Assertions matching the known scale from the Audit forensic report
        assertEquals("Canonical oracle must contain exactly 216 subscriber accounts", 216, accounts.size)
        assertEquals("Canonical oracle must contain exactly 2690 ledger entries", 2690, ledgers.size)
        
        // 4. Verify Zero-Balance Account metrics
        val zeroBalanceAccounts = accounts.filter { it.debtIqd == 0.0 }
        println("Zero-Balance Account Count:      ${zeroBalanceAccounts.size}")
        assertEquals("Canonical oracle must contain exactly 127 zero-balance accounts", 127, zeroBalanceAccounts.size)
        
        // 5. Verify the 5 specified subscriber accounts
        println("=== SPECIFIC SUBSCRIBER BALANCES ===")
        
        val almahdi = accounts.find { it.displayName.contains("Almahdi", ignoreCase = true) }
        assertNotNull("Almahdi Abdulkareem must exist", almahdi)
        println("Account: ${almahdi?.displayName} -> Debt: ${almahdi?.debtIqd} IQD")
        assertEquals(0.0, almahdi!!.debtIqd, 0.001)
        
        val saddam = accounts.find { it.displayName.contains("صدام") }
        assertNotNull("صدام must exist", saddam)
        println("Account: ${saddam?.displayName} -> Debt: ${saddam?.debtIqd} IQD")
        assertEquals(40000.0, saddam!!.debtIqd, 0.001)
        
        val mohammad = accounts.find { it.displayName.contains("محمد ناظم") }
        assertNotNull("محمد ناظم must exist", mohammad)
        println("Account: ${mohammad?.displayName} -> Debt: ${mohammad?.debtIqd} IQD")
        assertEquals(105000.0, mohammad!!.debtIqd, 0.001)
        
        val karrar = accounts.find { it.displayName.contains("كرار بيت ابو فراس") }
        assertNotNull("كرار بيت ابو فراس must exist", karrar)
        println("Account: ${karrar?.displayName} -> Debt: ${karrar?.debtIqd} IQD")
        assertEquals(40000.0, karrar!!.debtIqd, 0.001)
        
        val ibrahim = accounts.find { it.displayName.contains("ابراهيم ابو عباس") }
        assertNotNull("ابراهيم ابو عباس must exist", ibrahim)
        println("Account: ${ibrahim?.displayName} -> Debt: ${ibrahim?.debtIqd} IQD")
        assertEquals(0.0, ibrahim!!.debtIqd, 0.001)
        println("====================================")
        
        // 6. Verify Import Integrity (Phase A) Invariants
        
        // Invariant: Generation Incremented
        val finalGeneration = db.syncMetadataDao().getGeneration()
        println("Initial Generation: $initialGeneration, Final Generation: $finalGeneration")
        assertTrue("Generation must be incremented on shouldReplace=true", finalGeneration > initialGeneration)
        
        // Invariant: Cursor Reset (Clearing all synchronization cursors)
        val lastSyncTimestamp = db.syncMetadataDao().get("last_sync_timestamp")
        val lastSyncAccounts = db.syncMetadataDao().get("last_sync_local_accounts")
        val lastSyncLedgers = db.syncMetadataDao().get("last_sync_local_ledger_entries")
        val lastSyncBatches = db.syncMetadataDao().get("last_sync_import_batches")
        
        assertNull("last_sync_timestamp must be removed on replace", lastSyncTimestamp)
        assertNull("last_sync_local_accounts must be removed on replace", lastSyncAccounts)
        assertNull("last_sync_local_ledger_entries must be removed on replace", lastSyncLedgers)
        assertNull("last_sync_import_batches must be removed on replace", lastSyncBatches)
        
        val reconciliationFlag = db.syncMetadataDao().get("replace_all_pending_reconciliation")
        assertEquals("reconciliation pending flag must be set to true", "true", reconciliationFlag)
        
        // Invariant: Single-Batch Atomicity (Exactly 1 ImportBatch created)
        assertEquals("There must be exactly 1 ImportBatch created for the import", 1, batches.size)
        val batch = batches[0]
        assertEquals(importResult.batchId, batch.id)
        assertEquals("completed", batch.status)
        
        // Invariant: Outbox Generation (All accounts should generate outbox entries associated with this batch)
        val accountOutboxItems = outbox.filter { it.entityType == "local_accounts" }
        assertEquals("Every active account must generate an outbox entry", 216, accountOutboxItems.size)
        assertTrue("All generated outbox items must carry the import batch ID", outbox.all { it.importBatchId == batch.id })
        
        // Invariant: Historical Entries Attribute
        assertTrue("All imported legacy ledger entries must have isSnapshotHistory=true", ledgers.all { it.isSnapshotHistory })
        
        println("AUDIT VERIFICATION: All Phase A (Import Integrity) constraints and canonical metrics verified successfully.")
    }
}
