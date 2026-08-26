package com.example

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.core.backup.BackupManager
import com.example.core.database.AppDatabase
import com.example.core.model.*
import com.example.core.sync.*
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.LocalAccountRepository
import com.example.domain.repository.LocalLedgerRepository
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
import java.util.UUID

/**
 * Phase 3 Behavioral Test Suite: Generation Advancement on Full Replacement/Clear Only
 * (P3-G4-REQ-03, P3-G4-REQ-04, INV-05, INV-11).
 *
 * Verifies that:
 * 1. Restore Replace increments local lineage generation transactionally (+1).
 * 2. Utower import with shouldReplace=true increments generation transactionally (+1) in both preview and file flows.
 * 3. Full dataset clear via AppDatabase, LocalAccountRepository, or SyncRepository.signOut(clearData=true) increments generation (+1).
 * 4. Sign-out without data clear (clearData=false) does NOT increment generation.
 * 5. Normal financial mutations (account save/delete, ledger add payment/debt/renewal, transaction delete) do NOT increment generation.
 * 6. Restore Merge and import with shouldReplace=false do NOT increment generation.
 * 7. In-flight remote operations captured before a replacement/clear are strictly rejected due to generation mismatch.
 * 8. Fresh remote operations captured under the new generation apply successfully.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase3GenerationAdvanceBoundaryTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var accountRepo: LocalAccountRepository
    private lateinit var ledgerRepo: LocalLedgerRepository
    private lateinit var importer: UtowerImporter
    private lateinit var syncRepo: SyncRepositoryImpl
    private lateinit var coordinator: RemoteSyncCoordinator

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        (context as? EarthlinkApp)?.isSafeDebugFallbackAllowedOverride = true
        context.getDatabasePath("earthlink_reseller_db").parentFile?.mkdirs()
        AppDatabase.closeDatabase()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        accountRepo = LocalAccountRepositoryImpl(db, db.localAccountDao(), db.syncOutboxDao())
        ledgerRepo = LocalLedgerRepositoryImpl(db, db.localLedgerEntryDao(), db.localAccountDao(), db.syncOutboxDao())
        importer = UtowerImporter(context, db)
        syncRepo = SyncRepositoryImpl(
            context = context,
            appDatabase = db,
            outboxDao = db.syncOutboxDao(),
            accountDao = db.localAccountDao(),
            ledgerDao = db.localLedgerEntryDao(),
            batchDao = db.importBatchDao(),
            metadataDao = db.syncMetadataDao(),
            auditDao = db.auditLogDao()
        )
        coordinator = RemoteSyncCoordinator(
            appDatabase = db,
            accountDao = db.localAccountDao(),
            ledgerDao = db.localLedgerEntryDao(),
            batchDao = db.importBatchDao(),
            metadataDao = db.syncMetadataDao(),
            auditDao = db.auditLogDao(),
            outboxDao = db.syncOutboxDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
        AppDatabase.closeDatabase()
    }

    // =========================================================================
    // 1. Restore Replace Generation Advancement Tests
    // =========================================================================

    @Test
    fun restoreReplace_incrementsGenerationByExactlyOneTransactionally() = runBlocking {
        // Initial generation is 1L
        assertEquals(1L, db.getGeneration())

        val backupDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            backupDb.localAccountDao().insert(LocalAccount(id = "acc_backup_1", displayName = "Backup Acc 1"))
            backupDb.localLedgerEntryDao().insert(
                LocalLedgerEntry(
                    id = "tx_backup_1",
                    accountId = "acc_backup_1",
                    amountIqd = 50000.0,
                    debtAfterIqd = 50000.0,
                    typeRaw = "took"
                )
            )

            // Seed live database
            db.localAccountDao().insert(LocalAccount(id = "acc_live_old", displayName = "Old Live Acc"))

            db.withTransaction {
                BackupManager.executeRestoreReplaceInternal(
                    liveDb = db,
                    backupDb = backupDb,
                    passphrase = "test_passphrase"
                )
            }

            // Generation must advance from 1L to 2L
            val nextGen = db.getGeneration()
            assertEquals(2L, nextGen)

            // Replaced content verified
            val liveAccounts = db.localAccountDao().getAllOneShot(limit = 100)
            assertEquals(1, liveAccounts.size)
            assertEquals("acc_backup_1", liveAccounts[0].id)
        } finally {
            backupDb.close()
        }
    }

    @Test
    fun restoreReplace_inFailingTransaction_rollsBackGenerationIncrement() = runBlocking {
        assertEquals(1L, db.getGeneration())

        val backupDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            backupDb.localAccountDao().insert(LocalAccount(id = "acc_backup_err", displayName = "Backup Err"))

            try {
                db.withTransaction {
                    BackupManager.executeRestoreReplaceInternal(
                        liveDb = db,
                        backupDb = backupDb,
                        passphrase = "test_passphrase"
                    )
                    throw RuntimeException("Simulated mid-transaction failure")
                }
                fail("Transaction should have failed")
            } catch (e: RuntimeException) {
                assertEquals("Simulated mid-transaction failure", e.message)
            }

            // Generation must roll back to 1L
            assertEquals(1L, db.getGeneration())
        } finally {
            backupDb.close()
        }
    }

    @Test
    fun restoreMerge_doesNotIncrementGeneration() = runBlocking {
        assertEquals(1L, db.getGeneration())

        val backupDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            backupDb.localAccountDao().insert(LocalAccount(id = "acc_merge_1", displayName = "Merge Acc 1"))

            val decision = RestoreMergeDecision(
                artifactIdentity = "hash123",
                selectedBaselineId = "LIVE_SNAPSHOT",
                selectedLineageScope = "COMPLETE_LINEAGE",
                isApproved = true
            )

            val result = db.withTransaction {
                BackupManager.executeRestoreMergeInternal(
                    liveDb = db,
                    backupDb = backupDb,
                    decision = decision
                )
            }

            assertTrue(result.success)
            // Restore Merge is same-lineage: generation MUST remain 1L
            assertEquals(1L, db.getGeneration())
        } finally {
            backupDb.close()
        }
    }

    // =========================================================================
    // 2. Utower Import Generation Advancement Tests
    // =========================================================================

    @Test
    fun importFromPreview_shouldReplaceTrue_incrementsGeneration() = runBlocking {
        assertEquals(1L, db.getGeneration())

        val preview = UtowerImportPreview(
            parsedSubscribers = listOf(
                LocalAccount(id = "sub_replace_1", displayName = "Replace Sub 1", debtIqd = 10000.0)
            ),
            parsedTransactions = listOf(
                LocalLedgerEntry(id = "tx_replace_1", accountId = "sub_replace_1", amountIqd = 10000.0, debtAfterIqd = 10000.0, typeRaw = "took")
            ),
            totalCurrentDebtIqd = 10000.0
        )

        importer.importFromPreview(
            preview = preview,
            fileName = "backup.json",
            fileHash = "hash_preview_replace",
            shouldReplace = true
        )

        assertEquals(2L, db.getGeneration())
    }

    @Test
    fun importFromPreview_shouldReplaceFalse_doesNotIncrementGeneration() = runBlocking {
        assertEquals(1L, db.getGeneration())

        val preview = UtowerImportPreview(
            parsedSubscribers = listOf(
                LocalAccount(id = "sub_merge_1", displayName = "Merge Sub 1", debtIqd = 5000.0)
            ),
            parsedTransactions = emptyList(),
            totalCurrentDebtIqd = 5000.0
        )

        importer.importFromPreview(
            preview = preview,
            fileName = "backup.json",
            fileHash = "hash_preview_merge",
            shouldReplace = false
        )

        // Normal diff-merge import MUST NOT increment generation
        assertEquals(1L, db.getGeneration())
    }

    @Test
    fun importFromFile_shouldReplaceTrue_incrementsGeneration() = runBlocking {
        assertEquals(1L, db.getGeneration())

        val jsonContent = JSONObject().apply {
            val liveObj = JSONObject().apply {
                put("file_user_1", JSONObject().apply {
                    put("id", "1")
                    put("name", "File Replace User")
                    put("username", "file_user_1")
                    put("phone", "07700000001")
                    put("debt_iqd", 25000.0)
                    put("price_iqd", 35000.0)
                })
            }
            put("live_users", liveObj)

            val txObj = JSONObject().apply {
                put("tx_1", JSONObject().apply {
                    put("id", "1")
                    put("toWho", "file_user_1")
                    put("amount_iqd", 25000.0)
                    put("debt_after_iqd", 25000.0)
                    put("type", "debt")
                    put("date", "2026-08-18 10:00:00")
                })
            }
            put("messagesofhistory", txObj)
        }

        val tempFile = File(context.cacheDir, "test_import_replace_${UUID.randomUUID()}.json")
        tempFile.writeText(jsonContent.toString(), Charsets.UTF_8)
        try {
            val result = importer.importFromFile(tempFile, shouldReplace = true)
            assertTrue(result.success)
            assertEquals(2L, db.getGeneration())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun importFromFile_shouldReplaceFalse_doesNotIncrementGeneration() = runBlocking {
        assertEquals(1L, db.getGeneration())

        val jsonContent = JSONObject().apply {
            val liveObj = JSONObject().apply {
                put("file_user_2", JSONObject().apply {
                    put("id", "2")
                    put("name", "File Merge User")
                    put("username", "file_user_2")
                    put("phone", "07700000002")
                    put("debt_iqd", 15000.0)
                })
            }
            put("live_users", liveObj)
            put("messagesofhistory", JSONObject())
        }

        val tempFile = File(context.cacheDir, "test_import_merge_${UUID.randomUUID()}.json")
        tempFile.writeText(jsonContent.toString(), Charsets.UTF_8)
        try {
            val result = importer.importFromFile(tempFile, shouldReplace = false)
            assertTrue(result.success)
            // Normal import without replace stays in same lineage
            assertEquals(1L, db.getGeneration())
        } finally {
            tempFile.delete()
        }
    }

    // =========================================================================
    // 3. Full Dataset Clear & Sign-Out Generation Advancement Tests
    // =========================================================================

    @Test
    fun fullDatasetClear_viaAppDatabaseClearAllData_incrementsGeneration() = runBlocking {
        assertEquals(1L, db.getGeneration())

        db.localAccountDao().insert(LocalAccount(id = "acc_clear_1", displayName = "Clear Acc 1"))
        db.localLedgerEntryDao().insert(
            LocalLedgerEntry(
                id = "tx_clear_1",
                accountId = "acc_clear_1",
                amountIqd = 1000.0,
                debtAfterIqd = 1000.0,
                typeRaw = "took"
            )
        )
        db.syncOutboxDao().insert(
            SyncOutbox(
                entityType = "local_accounts",
                entityId = "acc_clear_1",
                payloadJson = "{}",
                operation = "UPSERT",
                status = "pending"
            )
        )

        val newGen = db.clearAllData()
        assertEquals(2L, newGen)
        assertEquals(2L, db.getGeneration())

        assertTrue(db.localAccountDao().getAllOneShot(limit = 10).isEmpty())
        assertTrue(db.localLedgerEntryDao().getAllOneShot(limit = 10).isEmpty())
        assertTrue(db.syncOutboxDao().getAllOneShot().isEmpty())
    }

    @Test
    fun fullDatasetClear_viaLocalAccountRepositoryClearAllData_incrementsGeneration() = runBlocking {
        assertEquals(1L, db.getGeneration())

        accountRepo.saveAccount(LocalAccount(id = "acc_repo_clear", displayName = "Repo Clear Acc"))
        val newGen = accountRepo.clearAllData()

        assertEquals(2L, newGen)
        assertEquals(2L, db.getGeneration())
        assertTrue(accountRepo.getAllAccountsOneShot().isEmpty())
    }

    @Test
    fun deleteAllAccounts_viaLocalAccountRepository_incrementsGeneration() = runBlocking {
        assertEquals(1L, db.getGeneration())

        accountRepo.saveAccount(LocalAccount(id = "acc_del_all_1", displayName = "Del All 1"))
        accountRepo.saveAccount(LocalAccount(id = "acc_del_all_2", displayName = "Del All 2"))

        accountRepo.deleteAllAccounts()

        assertEquals(2L, db.getGeneration())
        assertTrue(accountRepo.getAllAccountsOneShot().isEmpty())
    }

    @Test
    fun signOut_withClearDataTrue_incrementsGenerationAndClearsTables() = runBlocking {
        assertEquals(1L, db.getGeneration())

        db.localAccountDao().insert(LocalAccount(id = "acc_signout_1", displayName = "SignOut Acc"))
        syncRepo.signOut(force = true, clearData = true)

        assertEquals(2L, db.getGeneration())
        assertTrue(db.localAccountDao().getAllOneShot(limit = 10).isEmpty())
    }

    @Test
    fun signOut_withClearDataFalse_preservesGenerationAndData() = runBlocking {
        assertEquals(1L, db.getGeneration())

        db.localAccountDao().insert(LocalAccount(id = "acc_signout_keep", displayName = "SignOut Keep Acc"))
        syncRepo.signOut(force = true, clearData = false)

        // Sign-out without data clear MUST NOT increment generation
        assertEquals(1L, db.getGeneration())
        val retainedAccounts = db.localAccountDao().getAllOneShot(limit = 10)
        assertEquals(1, retainedAccounts.size)
        assertEquals("acc_signout_keep", retainedAccounts[0].id)
    }

    // =========================================================================
    // 4. Same-Lineage Normal Mutations Invariant (INV-05 / P3-G4-REQ-04)
    // =========================================================================

    @Test
    fun normalFinancialMutations_doNotIncrementGeneration() = runBlocking {
        assertEquals(1L, db.getGeneration())

        // 1. Save account
        val account = accountRepo.saveAccount(LocalAccount(id = "acc_mut_1", displayName = "Mutation Account", debtIqd = 0.0))
        assertEquals(1L, db.getGeneration())

        // 2. Add payment
        ledgerRepo.addPayment(account.id, 25000.0, "Monthly Payment")
        assertEquals(1L, db.getGeneration())

        // 3. Add debt
        ledgerRepo.addDebt(account.id, 10000.0, "Extra Debt")
        assertEquals(1L, db.getGeneration())

        // 4. Record renewal
        ledgerRepo.recordAccountRenewal(account, 35000.0, "Renewal", "Renewal Payment")
        assertEquals(1L, db.getGeneration())

        // 5. Delete single ledger transaction
        val allTx = db.localLedgerEntryDao().getAllOneShot(limit = 10)
        if (allTx.isNotEmpty()) {
            ledgerRepo.deleteTransaction(allTx[0].id)
            assertEquals(1L, db.getGeneration())
        }

        // 6. Delete single account
        accountRepo.deleteAccount(account.id)
        assertEquals(1L, db.getGeneration())
    }

    // =========================================================================
    // 5. Stale Remote Result Invalidation on Replacement/Clear Boundary
    // =========================================================================

    @Test
    fun staleInFlightRemoteOperation_rejectedAfterRestoreReplace() = runBlocking {
        val account = LocalAccount(id = "acc_stale_restore", displayName = "Stale Restore Remote")

        // Capture generation before replacement (Gen = 1L)
        val capturedGen = db.getGeneration()
        assertEquals(1L, capturedGen)

        val event = RemoteEvent.AccountUpsert(
            entityId = account.id,
            remoteVersion = 1000L,
            source = RemoteEventSource.REALTIME,
            account = account
        )

        // Execute Restore Replace (Gen advances to 2L)
        val backupDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.withTransaction {
                BackupManager.executeRestoreReplaceInternal(db, backupDb)
            }
        } finally {
            backupDb.close()
        }
        assertEquals(2L, db.getGeneration())

        // Coordinator processEvent will check capturedGen vs currentGen and reject stale result
        // Simulating the in-flight remote event captured at Gen 1L arriving at current Gen 2L
        val result = coordinator.processEvent(event, passedCapturedGen = capturedGen)
        assertEquals(EventSyncResult.FAILED_RETRYABLE, result)

        // Entity must NOT be in database
        assertNull(db.localAccountDao().getByIdOneShot(account.id))
    }

    @Test
    fun staleInFlightRemoteOperation_rejectedAfterFullDatasetClear() = runBlocking {
        val account = LocalAccount(id = "acc_stale_clear", displayName = "Stale Clear Remote")

        // Capture generation before clear (Gen = 1L)
        val capturedGen = db.getGeneration()
        assertEquals(1L, capturedGen)

        val event = RemoteEvent.AccountUpsert(
            entityId = account.id,
            remoteVersion = 1000L,
            source = RemoteEventSource.PULL,
            account = account
        )

        // Clear dataset (Gen advances to 2L)
        db.clearAllData()
        assertEquals(2L, db.getGeneration())

        // In-flight event captured before clear (capturedGen=1L) evaluated against currentGen=2L
        val result = coordinator.processEvent(event, passedCapturedGen = capturedGen)
        assertEquals(EventSyncResult.FAILED_RETRYABLE, result)

        assertNull(db.localAccountDao().getByIdOneShot(account.id))
    }

    @Test
    fun staleInFlightRemoteOperation_rejectedAfterImportWithReplace() = runBlocking {
        val account = LocalAccount(id = "acc_stale_import", displayName = "Stale Import Remote")

        // Capture generation before import replace (Gen = 1L)
        val capturedGen = db.getGeneration()
        assertEquals(1L, capturedGen)

        val event = RemoteEvent.AccountUpsert(
            entityId = account.id,
            remoteVersion = 1000L,
            source = RemoteEventSource.PULL,
            account = account
        )

        // Import with shouldReplace=true (Gen advances to 2L)
        val preview = UtowerImportPreview(
            parsedSubscribers = listOf(LocalAccount(id = "sub_imp", displayName = "Imported Sub")),
            parsedTransactions = emptyList(),
            totalCurrentDebtIqd = 0.0
        )
        importer.importFromPreview(preview, "import.json", "hash_imp_replace", shouldReplace = true)
        assertEquals(2L, db.getGeneration())

        // In-flight event captured before import replace evaluated against currentGen=2L
        val result = coordinator.processEvent(event, passedCapturedGen = capturedGen)
        assertEquals(EventSyncResult.FAILED_RETRYABLE, result)

        assertNull(db.localAccountDao().getByIdOneShot(account.id))
    }

    @Test
    fun freshRemoteOperation_acceptedAfterLineageAdvance() = runBlocking {
        // Advance generation to 2L via clearAllData
        db.clearAllData()
        assertEquals(2L, db.getGeneration())

        val account = LocalAccount(id = "acc_fresh_2", displayName = "Fresh Gen 2 Account")
        val event = RemoteEvent.AccountUpsert(
            entityId = account.id,
            remoteVersion = 2000L,
            source = RemoteEventSource.REALTIME,
            account = account
        )

        val result = coordinator.processEvent(event)
        assertEquals(EventSyncResult.APPLIED, result)

        val savedAccount = db.localAccountDao().getByIdOneShot(account.id)
        assertNotNull(savedAccount)
        assertEquals("acc_fresh_2", savedAccount?.id)
        assertEquals("2000", db.syncMetadataDao().get("remote_version:account:${account.id}"))
    }
}
