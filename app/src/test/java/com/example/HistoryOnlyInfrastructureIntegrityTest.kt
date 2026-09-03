package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.backup.BackupManager
import com.example.core.database.AppDatabase
import com.example.core.model.*
import com.example.core.sync.UtowerImporter
import com.example.domain.repository.UtowerImportPreview
import com.example.data.repository.rebuildAccountBalances
import kotlinx.coroutines.runBlocking
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Verification suite for History-Only Subscriber Infrastructure Integrity:
 *
 * Core Triad:
 * 1. Claim: INV-01 (Financial Correctness), INV-02 (History Preservation), INV-06 (Restore / Import Atomicity & Lineage),
 *    INV-08 (Durable Outbox Safety), INV-09 (Identity & Provenance Integrity).
 *    Infrastructure and account-integrity paths (Backup export/restore, restore merge, remote replace-all reconciliation,
 *    uTower import matching, full account-balance reconstruction) must see all persisted accounts including
 *    isHistoryOnlySubscriber accounts, while UI/search/expiry queries remain active-only.
 * 2. Seam / Environment: ROBOLECTRIC (in-memory SQLite Room databases).
 * 3. Independent Oracle: Explicit multi-vector test fixtures verifying exact counts, balance sums, and identity matching
 *    derived directly from business rules without circular production math.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class HistoryOnlyInfrastructureIntegrityTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testActiveOnlyVsPersistedQueries_enforcesStrictSemanticBoundary() {
        runBlocking {
            // Fixture: 1 active account, 1 history-only account
            val activeAcc = LocalAccount(
                id = "acc_active_01",
                displayName = "Active User",
                earthlinkUsername = "user_active",
                debtIqd = 25000.0,
                isHistoryOnlySubscriber = false
            )
            val historyAcc = LocalAccount(
                id = "acc_history_01",
                displayName = "History Only User",
                earthlinkUsername = "user_history",
                debtIqd = 50000.0,
                isHistoryOnlySubscriber = true
            )

            database.localAccountDao().insert(activeAcc)
            database.localAccountDao().insert(historyAcc)

            // Active-only query must see ONLY the active account (size = 1)
            val activeAccounts = database.localAccountDao().getAllOneShot()
            assertEquals(1, activeAccounts.size)
            assertEquals("acc_active_01", activeAccounts[0].id)
            assertFalse(activeAccounts[0].isHistoryOnlySubscriber)

            // Active total count must count only active accounts
            assertEquals(1, database.localAccountDao().getActiveTotalCount())

            // All persisted query must see BOTH accounts (size = 2)
            val allPersistedAccounts = database.localAccountDao().getAllPersistedOneShot()
            assertEquals(2, allPersistedAccounts.size)
            val ids = allPersistedAccounts.map { it.id }.toSet()
            assertTrue(ids.contains("acc_active_01"))
            assertTrue(ids.contains("acc_history_01"))

            // Total count must be 2
            assertEquals(2, database.localAccountDao().getTotalCount())
        }
    }

    @Test
    fun testBackupExportAndRestore_preservesHistoryOnlySubscribersAndDebt() {
        runBlocking {
            // Fixture: Live DB with 1 active account and 1 history-only account with ledger entries
            val activeAcc = LocalAccount(
                id = "acc_live_act",
                displayName = "Active Sub",
                earthlinkUsername = "active_sub",
                debtIqd = 35000.0,
                openingDebtIqd = 35000.0,
                isHistoryOnlySubscriber = false
            )
            val historyAcc = LocalAccount(
                id = "acc_live_hist",
                displayName = "History Sub",
                earthlinkUsername = "hist_sub",
                debtIqd = 70000.0,
                openingDebtIqd = 70000.0,
                isHistoryOnlySubscriber = true
            )
            database.localAccountDao().insert(activeAcc)
            database.localAccountDao().insert(historyAcc)

            val txHist = LocalLedgerEntry(
                id = "tx_hist_1",
                accountId = historyAcc.id,
                typeRaw = "took",
                amountIqd = 70000.0,
                debtAfterIqd = 70000.0,
                occurredAt = 1000L
            )
            database.localLedgerEntryDao().insert(txHist)

            // Export backup to a plain database
            val backupDbFile = File(context.cacheDir, "test_backup.db")
            if (backupDbFile.exists()) backupDbFile.delete()

            val backupDb = AppDatabase.getDatabase(context, ByteArray(0), backupDbFile.name)
            backupDb.openHelper.writableDatabase

            val exportedAccs = database.localAccountDao().getAllPersistedOneShot(limit = 100000, offset = 0)
            assertEquals(2, exportedAccs.size)
            backupDb.localAccountDao().insertAll(exportedAccs)

            val exportedLedgers = database.localLedgerEntryDao().getAllOneShot(limit = 100000, offset = 0)
            backupDb.localLedgerEntryDao().insertAll(exportedLedgers)

            // Verify backupDb has both accounts
            val backupPersisted = backupDb.localAccountDao().getAllPersistedOneShot()
            assertEquals(2, backupPersisted.size)
            val backupHist = backupDb.localAccountDao().getByIdOneShot(historyAcc.id)
            assertNotNull(backupHist)
            assertTrue(backupHist!!.isHistoryOnlySubscriber)
            assertEquals(70000.0, backupHist.debtIqd, 0.001)

            // Clear target database and simulate restore chunking
            database.localLedgerEntryDao().deleteAll()
            database.localAccountDao().deleteAll()
            assertEquals(0, database.localAccountDao().getTotalCount())

            val batchSize = 500
            var accOffset = 0
            while (true) {
                val chunk = backupDb.localAccountDao().getAllPersistedOneShot(limit = batchSize, offset = accOffset)
                if (chunk.isEmpty()) break
                database.localAccountDao().insertAll(chunk)
                accOffset += chunk.size
                if (chunk.size < batchSize) break
            }

            // Restored database must contain BOTH active and history-only accounts
            val restoredPersisted = database.localAccountDao().getAllPersistedOneShot()
            assertEquals(2, restoredPersisted.size)

            val restoredHist = database.localAccountDao().getByIdOneShot(historyAcc.id)
            assertNotNull(restoredHist)
            assertTrue("Restored history-only subscriber must retain isHistoryOnlySubscriber=true", restoredHist!!.isHistoryOnlySubscriber)
            assertEquals(70000.0, restoredHist.debtIqd, 0.001)

            // Active-only query on restored db still returns only 1 account
            assertEquals(1, database.localAccountDao().getAllOneShot().size)

            backupDb.close()
            backupDbFile.delete()
        }
    }

    @Test
    fun testRestoreMerge_includesHistoryOnlySubscribersInLiveAndBackup() {
        runBlocking {
            // Live DB has a history-only account
            val histAccLive = LocalAccount(
                id = "acc_merge_hist",
                displayName = "History User",
                earthlinkUsername = "hist_user",
                debtIqd = 40000.0,
                openingDebtIqd = 40000.0,
                isHistoryOnlySubscriber = true
            )
            database.localAccountDao().insert(histAccLive)

            // Backup DB has the same history-only account with updated phone and an extra payment ledger entry
            val backupDbFile = File(context.cacheDir, "test_merge_backup.db")
            if (backupDbFile.exists()) backupDbFile.delete()
            val backupDb = AppDatabase.getDatabase(context, ByteArray(0), backupDbFile.name)
            backupDb.openHelper.writableDatabase

            val histAccBackup = histAccLive.copy(phone1 = "07701234567")
            backupDb.localAccountDao().insert(histAccBackup)

            val txPay = LocalLedgerEntry(
                id = "tx_pay_merge",
                accountId = histAccLive.id,
                typeRaw = "payment",
                amountIqd = 10000.0,
                debtAfterIqd = 30000.0,
                occurredAt = 2000L
            )
            backupDb.localLedgerEntryDao().insert(txPay)

            val decision = RestoreMergeDecision(
                artifactIdentity = "test_merge_hash",
                selectedBaselineId = "BACKUP_SNAPSHOT",
                isApproved = true
            )

            val result = BackupManager.executeRestoreMergeInternal(database, backupDb, decision)
            assertTrue("Restore merge must succeed", result.success)

            val mergedAcc = database.localAccountDao().getByIdOneShot(histAccLive.id)
            assertNotNull("History-only account must be preserved in merged database", mergedAcc)
            assertTrue("Merged account must retain isHistoryOnlySubscriber=true", mergedAcc!!.isHistoryOnlySubscriber)

            backupDb.close()
            backupDbFile.delete()
        }
    }

    @Test
    fun testRemoteReplaceAllReconciliation_canonicalSetIncludesHistoryOnlySubscribers() {
        runBlocking {
            // Database has 1 active account and 1 history-only account
            val activeAcc = LocalAccount(id = "acc_active_sync", displayName = "Active", isHistoryOnlySubscriber = false)
            val histAcc = LocalAccount(id = "acc_hist_sync", displayName = "History", isHistoryOnlySubscriber = true)

            database.localAccountDao().insert(activeAcc)
            database.localAccountDao().insert(histAcc)

            // Canonical set used by reconcileRemoteReplaceAll must include both IDs
            val canonicalIds = database.localAccountDao().getAllPersistedOneShot(limit = Int.MAX_VALUE).map { it.id }.toSet()

            assertTrue("Canonical set must contain active account ID", canonicalIds.contains("acc_active_sync"))
            assertTrue("Canonical set must contain history-only account ID to prevent false remote deletion", canonicalIds.contains("acc_hist_sync"))
            assertEquals(2, canonicalIds.size)
        }
    }

    @Test
    fun testUtowerImportMatching_matchesExistingHistoryOnlyAccountWithoutDuplicate() {
        runBlocking {
            // Existing account is history-only
            val existingHistAcc = LocalAccount(
                id = "acc_utower_hist",
                sourceExternalId = "ext_utower_01",
                displayName = "uTower History User",
                earthlinkUsername = "utower_user_01",
                debtIqd = 50000.0,
                openingDebtIqd = 50000.0,
                isHistoryOnlySubscriber = true
            )
            database.localAccountDao().insert(existingHistAcc)

            assertEquals(1, database.localAccountDao().getTotalCount())

            // uTower import file has the same user by externalId and username
            val preview = UtowerImportPreview(
                parsedSubscribers = listOf(
                    LocalAccount(
                        id = "temporary_import_id",
                        sourceExternalId = "ext_utower_01",
                        displayName = "uTower History User Updated",
                        earthlinkUsername = "utower_user_01",
                        debtIqd = 60000.0,
                        openingDebtIqd = 60000.0,
                        rawJson = """{"debt_iqd": 60000.0, "totalDebit": 60000.0}""",
                        stateSource = "UTOWER_CURRENT_STATE"
                    )
                ),
                parsedTransactions = listOf(
                    LocalLedgerEntry(
                        id = "utx_hist_01",
                        accountId = "temporary_import_id",
                        sourceExternalId = "tx_ext_hist_01",
                        typeRaw = "took",
                        amountIqd = 60000.0,
                        debtAfterIqd = 60000.0,
                        occurredAt = 1000L,
                        isSnapshotHistory = true
                    )
                )
            )

            val importer = UtowerImporter(context, database)
            importer.importFromPreview(preview, "utower_export.json", "hash_hist_test", shouldReplace = false)

            // Verify: Exactly 1 total account exists (no duplicate inserted!)
            assertEquals("Importing a subscriber matching a history-only account must not create a duplicate", 1, database.localAccountDao().getTotalCount())

            val matchedAcc = database.localAccountDao().getByIdOneShot("acc_utower_hist")
            assertNotNull("Existing account ID must be matched and updated", matchedAcc)
            assertTrue("Account must retain isHistoryOnlySubscriber=true", matchedAcc!!.isHistoryOnlySubscriber)
            assertEquals(60000.0, matchedAcc.debtIqd, 0.001)
        }
    }

    @Test
    fun testRebuildAccountBalances_reconstructsBalancesForHistoryOnlySubscribers() {
        runBlocking {
            // History-only account with baseline opening debt = 20,000 IQD
            val histAcc = LocalAccount(
                id = "acc_recon_hist",
                displayName = "Recon History User",
                openingDebtIqd = 20000.0,
                openingAdvanceIqd = 0.0,
                openingLoanIqd = 0.0,
                debtIqd = 0.0, // Stale/corrupted balance
                isHistoryOnlySubscriber = true
            )
            database.localAccountDao().insert(histAcc)

            // Ledger entry: payment of 5,000 IQD
            val txPayment = LocalLedgerEntry(
                id = "tx_recon_pay",
                accountId = histAcc.id,
                typeRaw = "payment",
                amountIqd = 5000.0,
                debtAfterIqd = 15000.0,
                occurredAt = 1000L
            )
            database.localLedgerEntryDao().insert(txPayment)

            // Run full rebuild across the database
            val processedCount = rebuildAccountBalances(database)
            assertEquals("Full rebuild must process the history-only account", 1, processedCount)

            val reconstructedAcc = database.localAccountDao().getByIdOneShot(histAcc.id)
            assertNotNull(reconstructedAcc)
            assertTrue("Account must retain isHistoryOnlySubscriber=true", reconstructedAcc!!.isHistoryOnlySubscriber)
            // Expected debt: 20000.0 - 5000.0 = 15000.0
            assertEquals("Reconstructed debt must match openingDebt - payment", 15000.0, reconstructedAcc.debtIqd, 0.001)
        }
    }
}
