package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.EarthlinkApp
import com.example.core.backup.BackupManager
import com.example.core.database.AppDatabase
import com.example.core.model.*
import com.example.core.sync.SyncRepositoryImpl
import com.example.core.sync.UtowerImporter
import com.example.domain.repository.UtowerImportPreview
import com.example.data.repository.rebuildAccountBalances
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Verification suite for History-Only Subscriber Infrastructure Integrity:
 *
 * Core Triad:
 * 1. Claim: INV-01 (Financial Correctness), INV-02 (History Preservation), INV-06 (Restore / Import Atomicity & Lineage),
 *    INV-08 (Durable Outbox Safety), INV-09 (Identity & Provenance Integrity).
 *    Infrastructure and account-integrity paths (Backup export/restore, restore merge, remote replace-all reconciliation,
 *    uTower import matching, full account-balance reconstruction) must see all persisted accounts including
 *    isHistoryOnlySubscriber accounts, while UI/search/expiry queries remain active-only.
 * 2. Seam / Environment: ROBOLECTRIC (SQLite Room databases via real production repository & manager components).
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
        (context as? EarthlinkApp)?.isSafeDebugFallbackAllowedOverride = true
        context.getDatabasePath("earthlink_reseller_db").parentFile?.mkdirs()
        AppDatabase.closeDatabase()
        database = (context as? EarthlinkApp)?.database ?: AppDatabase.getDatabase(context, ByteArray(0), "earthlink_reseller_db")
        runBlocking {
            database.localLedgerEntryDao().deleteAll()
            database.localAccountDao().deleteAll()
            database.importBatchDao().deleteAll()
            database.syncOutboxDao().deleteAll()
            database.syncMetadataDao().deleteAll()
            database.auditLogDao().clearAll()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            database.localLedgerEntryDao().deleteAll()
            database.localAccountDao().deleteAll()
            database.importBatchDao().deleteAll()
            database.syncOutboxDao().deleteAll()
            database.syncMetadataDao().deleteAll()
            database.auditLogDao().clearAll()
        }
        AppDatabase.closeDatabase()
    }

    private fun createMockDoc(
        id: String,
        data: Map<String, Any?>
    ): DocumentSnapshot {
        val mockDoc = mock(DocumentSnapshot::class.java)
        val mockRef = mock(DocumentReference::class.java)
        `when`(mockDoc.id).thenReturn(id)
        `when`(mockDoc.data).thenReturn(data)
        `when`(mockDoc.reference).thenReturn(mockRef)
        return mockDoc
    }

    private fun createMockFirebase(
        accountsDocs: List<DocumentSnapshot> = emptyList(),
        ledgersDocs: List<DocumentSnapshot> = emptyList(),
        batchesDocs: List<DocumentSnapshot> = emptyList()
    ): Triple<FirebaseAuth, FirebaseFirestore, WriteBatch> {
        val testUid = "test_reseller_user_123"
        val mockAuth = mock(FirebaseAuth::class.java)
        val mockUser = mock(FirebaseUser::class.java)
        `when`(mockAuth.currentUser).thenReturn(mockUser)
        `when`(mockUser.uid).thenReturn(testUid)

        val mockFirestore = mock(FirebaseFirestore::class.java)
        val mockUsersCollection = mock(CollectionReference::class.java)
        val mockUserDoc = mock(DocumentReference::class.java)

        `when`(mockFirestore.collection("users")).thenReturn(mockUsersCollection)
        `when`(mockUsersCollection.document(testUid)).thenReturn(mockUserDoc)

        val mockAccountsColl = mock(CollectionReference::class.java)
        val mockLedgersColl = mock(CollectionReference::class.java)
        val mockBatchesColl = mock(CollectionReference::class.java)

        `when`(mockUserDoc.collection("local_accounts")).thenReturn(mockAccountsColl)
        `when`(mockUserDoc.collection("local_ledger_entries")).thenReturn(mockLedgersColl)
        `when`(mockUserDoc.collection("import_batches")).thenReturn(mockBatchesColl)

        val accountsQuerySnapshot = mock(QuerySnapshot::class.java)
        `when`(accountsQuerySnapshot.documents).thenReturn(accountsDocs)
        `when`(mockAccountsColl.get(Source.SERVER)).thenReturn(Tasks.forResult(accountsQuerySnapshot))

        val ledgersQuerySnapshot = mock(QuerySnapshot::class.java)
        `when`(ledgersQuerySnapshot.documents).thenReturn(ledgersDocs)
        `when`(mockLedgersColl.get(Source.SERVER)).thenReturn(Tasks.forResult(ledgersQuerySnapshot))

        val batchesQuerySnapshot = mock(QuerySnapshot::class.java)
        `when`(batchesQuerySnapshot.documents).thenReturn(batchesDocs)
        `when`(mockBatchesColl.get(Source.SERVER)).thenReturn(Tasks.forResult(batchesQuerySnapshot))

        val mockBatch = mock(WriteBatch::class.java)
        `when`(mockBatch.commit()).thenReturn(Tasks.forResult(null))
        `when`(mockFirestore.batch()).thenReturn(mockBatch)

        return Triple(mockAuth, mockFirestore, mockBatch)
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

            // Exercise REAL BackupManager export path
            val backupFile = BackupManager.createLocalBackupZip(context, password = null)
            assertTrue("Backup file must be created by BackupManager", backupFile.exists())
            assertTrue("Backup file must not be empty", backupFile.length() > 0)

            // Clear target live database to simulate clean restore target
            database.localLedgerEntryDao().deleteAll()
            database.localAccountDao().deleteAll()
            assertEquals(0, database.localAccountDao().getTotalCount())

            // Exercise REAL BackupManager restore path (executes restoreBackupZip -> executeRestoreReplaceInternal)
            val restoreSuccess = BackupManager.restoreBackupZip(context, backupFile, force = true)
            assertTrue("Real BackupManager restore path must succeed", restoreSuccess)

            // Restored database must contain BOTH active and history-only accounts
            val restoredPersisted = database.localAccountDao().getAllPersistedOneShot()
            assertEquals(2, restoredPersisted.size)

            val restoredHist = database.localAccountDao().getByIdOneShot(historyAcc.id)
            assertNotNull(restoredHist)
            assertTrue("Restored history-only subscriber must retain isHistoryOnlySubscriber=true", restoredHist!!.isHistoryOnlySubscriber)
            assertEquals(70000.0, restoredHist.debtIqd, 0.001)

            // Active-only query on restored db still returns only 1 account
            assertEquals(1, database.localAccountDao().getAllOneShot().size)

            backupFile.delete()
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

            // Exercises REAL BackupManager executeRestoreMergeInternal path
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
            // Local Room database has 1 active account and 1 history-only account
            val activeAcc = LocalAccount(id = "acc_active_sync", displayName = "Active", isHistoryOnlySubscriber = false)
            val histAcc = LocalAccount(id = "acc_hist_sync", displayName = "History", isHistoryOnlySubscriber = true)

            database.localAccountDao().insert(activeAcc)
            database.localAccountDao().insert(histAcc)

            // Remote active docs in Firestore:
            // 1. "acc_active_sync" (matches active account in local Room)
            // 2. "acc_hist_sync" (matches history-only account in local Room)
            // 3. "acc_remote_orphan" (remote doc absent from local Room entirely)
            val docActive = createMockDoc("acc_active_sync", mapOf("displayName" to "Active", "deletedAt" to null))
            val docHist = createMockDoc("acc_hist_sync", mapOf("displayName" to "History", "deletedAt" to null))
            val docOrphan = createMockDoc("acc_remote_orphan", mapOf("displayName" to "Orphan", "deletedAt" to null))

            val (mockAuth, mockFirestore, mockBatch) = createMockFirebase(accountsDocs = listOf(docActive, docHist, docOrphan))

            val syncRepository = SyncRepositoryImpl(
                context = context,
                appDatabase = database,
                outboxDao = database.syncOutboxDao(),
                accountDao = database.localAccountDao(),
                ledgerDao = database.localLedgerEntryDao(),
                batchDao = database.importBatchDao(),
                metadataDao = database.syncMetadataDao(),
                auditDao = database.auditLogDao()
            )
            syncRepository.setFirebaseInstancesForTest(mockAuth, mockFirestore)

            // Exercise the REAL production reconciliation method
            val success = syncRepository.executeRemoteReplaceAllReconciliation()
            assertTrue("executeRemoteReplaceAllReconciliation must succeed", success)

            // Verify: ONLY the orphan document is tombstoned in Firestore.
            // Crucially, the history-only account is in canonicalAccountIds and must NOT be tombstoned!
            val refCaptor = ArgumentCaptor.forClass(DocumentReference::class.java)
            val optionsCaptor = ArgumentCaptor.forClass(SetOptions::class.java)
            verify(mockBatch, times(1)).set(refCaptor.capture(), anyMap<String, Any?>(), optionsCaptor.capture())

            assertEquals("Only the orphan document reference should be tombstoned", docOrphan.reference, refCaptor.value)
            verify(mockBatch, never()).set(eq(docHist.reference), anyMap<String, Any?>(), any<SetOptions>())
            verify(mockBatch, never()).set(eq(docActive.reference), anyMap<String, Any?>(), any<SetOptions>())
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
