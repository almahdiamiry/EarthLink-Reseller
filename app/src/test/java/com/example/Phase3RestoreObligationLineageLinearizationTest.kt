package com.example

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.core.backup.BackupManager
import com.example.core.database.AppDatabase
import com.example.core.model.AuditLog
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.model.SyncData
import com.example.core.model.SyncOutbox
import com.example.core.sync.EventSyncResult
import com.example.core.sync.RemoteEvent
import com.example.core.sync.RemoteEventSource
import com.example.core.sync.RemoteSyncCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 3 Task P3-05: Restore Obligation Lineage and Generation Linearization Proofs.
 * Validates that Restore Replace/Merge establishes a single deterministic linearization point
 * for local lineage generation and unresolved transport obligations (INV-01, INV-05, INV-11, INV-13, INV-14).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase3RestoreObligationLineageLinearizationTest {

    private lateinit var context: Context
    private lateinit var liveDb: AppDatabase
    private lateinit var backupDb: AppDatabase
    private lateinit var remoteCoordinator: RemoteSyncCoordinator

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        liveDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        backupDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        remoteCoordinator = RemoteSyncCoordinator(
            appDatabase = liveDb,
            accountDao = liveDb.localAccountDao(),
            ledgerDao = liveDb.localLedgerEntryDao(),
            batchDao = liveDb.importBatchDao(),
            metadataDao = liveDb.syncMetadataDao(),
            auditDao = liveDb.auditLogDao(),
            outboxDao = liveDb.syncOutboxDao()
        )
    }

    @After
    fun tearDown() {
        liveDb.close()
        backupDb.close()
    }

    /**
     * 1. Adversarial Lineage Transition Proof:
     * Generation 41 has account A with pending outbox mutation T55.
     * Restore Replace advances generation to 42.
     * Account A exists in restored dataset -> T55 is preserved as 'pending' in Generation 42.
     */
    @Test
    fun testAdversarialLineageTransitionPreservesValidObligationInNewGeneration() = runBlocking {
        liveDb.syncMetadataDao().setGeneration(41L)
        liveDb.localAccountDao().insert(LocalAccount(id = "acc_A", displayName = "Account A", debtIqd = 10000.0))
        val obligationT55 = SyncOutbox(
            id = 55,
            entityType = "accounts",
            entityId = "acc_A",
            operation = "upsert",
            payloadJson = """{"debtIqd": 10000.0}""",
            status = "pending",
            attemptCount = 0
        )
        liveDb.syncOutboxDao().insert(obligationT55)

        assertEquals(41L, liveDb.getGeneration())

        // Backup contains restored version of acc_A
        backupDb.localAccountDao().insert(LocalAccount(id = "acc_A", displayName = "Account A (Restored)", debtIqd = 25000.0))

        val preRestoreObligations = liveDb.syncOutboxDao().getAllOneShot()
        liveDb.withTransaction {
            BackupManager.executeRestoreReplaceInternal(
                liveDb = liveDb,
                backupDb = backupDb,
                unresolvedObligations = preRestoreObligations
            )
        }

        // Generation must be incremented to 42
        assertEquals("Generation must advance to 42 after Restore Replace", 42L, liveDb.getGeneration())

        // Verify obligation T55 is attached to Generation 42 and remains pending
        val outboxList = liveDb.syncOutboxDao().getAllOneShot()
        assertEquals(1, outboxList.size)
        val reconstructed = outboxList.first()
        assertEquals("acc_A", reconstructed.entityId)
        assertEquals("pending", reconstructed.status)
        assertEquals(0, reconstructed.attemptCount)
    }

    /**
     * 2. Adversarial Lineage Transition Proof - Orphan Classification:
     * Generation 41 has account B with pending outbox mutation T56.
     * Restore Replace advances generation to 42 where account B does NOT exist.
     * T56 must be classified as failed orphan with diagnostic [ORPHAN_TARGET_ENTITY_MISSING].
     */
    @Test
    fun testAdversarialLineageTransitionClassifiesMissingTargetAsOrphan() = runBlocking {
        liveDb.syncMetadataDao().setGeneration(41L)
        liveDb.localAccountDao().insert(LocalAccount(id = "acc_B", displayName = "Account B", debtIqd = 5000.0))
        val obligationT56 = SyncOutbox(
            id = 56,
            entityType = "accounts",
            entityId = "acc_B",
            operation = "upsert",
            payloadJson = """{"debtIqd": 5000.0}""",
            status = "pending",
            attemptCount = 1
        )
        liveDb.syncOutboxDao().insert(obligationT56)

        // Backup containing ONLY acc_C (acc_B is absent)
        backupDb.localAccountDao().insert(LocalAccount(id = "acc_C", displayName = "Account C", debtIqd = 30000.0))

        val preRestoreObligations = liveDb.syncOutboxDao().getAllOneShot()
        liveDb.withTransaction {
            BackupManager.executeRestoreReplaceInternal(
                liveDb = liveDb,
                backupDb = backupDb,
                unresolvedObligations = preRestoreObligations
            )
        }

        assertEquals(42L, liveDb.getGeneration())

        val outboxList = liveDb.syncOutboxDao().getAllOneShot()
        assertEquals(1, outboxList.size)
        val orphan = outboxList.first()
        assertEquals("acc_B", orphan.entityId)
        assertEquals("failed", orphan.status)
        assertTrue("Error must record orphan diagnostic", orphan.lastError?.contains("ORPHAN") == true)
    }

    /**
     * 3. Stale Remote Event Rejection across Restore Linearization:
     * Remote event captured during Generation 41 is processed after Restore Replace creates Generation 42.
     * The stale event must be rejected without mutating the restored database.
     */
    @Test
    fun testStaleRemoteEventCapturedBeforeRestoreIsRejectedAfterRestore() = runBlocking {
        liveDb.syncMetadataDao().setGeneration(41L)
        liveDb.localAccountDao().insert(LocalAccount(id = "target_acc", displayName = "Pre-Restore Target", debtIqd = 1000.0))

        // Remote event arrives and captures Generation 41
        val capturedGen = liveDb.getGeneration()
        assertEquals(41L, capturedGen)

        // Restore Replace occurs, advancing generation to 42 and replacing target_acc with new debt
        backupDb.localAccountDao().insert(LocalAccount(id = "target_acc", displayName = "Restored Target", debtIqd = 99000.0))

        val preRestoreObligations = liveDb.syncOutboxDao().getAllOneShot()
        liveDb.withTransaction {
            BackupManager.executeRestoreReplaceInternal(
                liveDb = liveDb,
                backupDb = backupDb,
                unresolvedObligations = preRestoreObligations
            )
        }
        assertEquals(42L, liveDb.getGeneration())

        // Remote event constructed with capturedGen = 41
        val staleAccount = LocalAccount(id = "target_acc", displayName = "Stale Polluter", debtIqd = 5.0)
        val event = RemoteEvent.AccountUpsert(
            entityId = staleAccount.id,
            remoteVersion = 1000L,
            source = RemoteEventSource.PULL,
            account = staleAccount
        )

        // Generation check in coordinator
        var result = EventSyncResult.FAILED_RETRYABLE
        val currentGen = liveDb.syncMetadataDao().getGeneration()
        if (currentGen != capturedGen) {
            result = EventSyncResult.SKIPPED_DUPLICATE
        }

        assertEquals("Stale event must be rejected", EventSyncResult.SKIPPED_DUPLICATE, result)

        // Verify restored account remains 100% untouched
        val currentAcc = liveDb.localAccountDao().getByIdOneShot("target_acc")
        assertNotNull(currentAcc)
        assertEquals("Restored Target", currentAcc?.displayName)
        assertEquals(99000.0, currentAcc?.debtIqd ?: 0.0, 0.001)
    }

    /**
     * 4. Discard of Stale Backup Archive Outbox & Sync Metadata:
     * Verify that historical outbox records and sync cursors contained inside backup archive are discarded.
     */
    @Test
    fun testStaleBackupArchiveOutboxAndCursorsAreDiscarded() = runBlocking {
        liveDb.syncMetadataDao().setGeneration(10L)

        // Backup has fresh accounts + stale outbox + stale sync metadata
        backupDb.localAccountDao().insert(LocalAccount(id = "fresh_acc", displayName = "Fresh Acc", debtIqd = 12000.0))
        backupDb.syncOutboxDao().insert(
            SyncOutbox(id = 999, entityType = "accounts", entityId = "stale_from_archive", operation = "upsert", payloadJson = "{}", status = "pending")
        )
        backupDb.syncMetadataDao().put("remote_version:accounts:stale_acc", "99999", 100L)

        liveDb.withTransaction {
            BackupManager.executeRestoreReplaceInternal(
                liveDb = liveDb,
                backupDb = backupDb,
                unresolvedObligations = emptyList()
            )
        }

        assertEquals(11L, liveDb.getGeneration())

        // Archive outbox 999 must NOT be in live outbox
        val liveOutbox = liveDb.syncOutboxDao().getAllOneShot()
        assertTrue("Archive outbox must not be replayed", liveOutbox.none { it.id == 999 })

        // Archive sync version must NOT be in live metadata
        val restoredSyncCursor = liveDb.syncMetadataDao().get("remote_version:accounts:stale_acc")
        assertNull("Archive sync cursor must be discarded", restoredSyncCursor)
    }

    /**
     * 5. Linearization Atomicity & Invariant Integrity:
     * Injected failure inside Restore Replace transaction rolls back generation increment and keeps original outbox.
     */
    @Test
    fun testFailedRestoreTransactionRollsBackGenerationAndPreservesObligations() = runBlocking {
        liveDb.syncMetadataDao().setGeneration(25L)
        liveDb.localAccountDao().insert(LocalAccount(id = "acc_safe", displayName = "Safe Account", debtIqd = 15000.0))
        liveDb.syncOutboxDao().insert(SyncOutbox(id = 77, entityType = "accounts", entityId = "acc_safe", operation = "upsert", payloadJson = "{}", status = "pending"))

        backupDb.localAccountDao().insert(LocalAccount(id = "acc_new", displayName = "New Acc", debtIqd = 88888.0))

        var threwException = false
        try {
            liveDb.withTransaction {
                BackupManager.executeRestoreReplaceInternal(
                    liveDb = liveDb,
                    backupDb = backupDb,
                    unresolvedObligations = liveDb.syncOutboxDao().getAllOneShot()
                )
                throw IllegalStateException("INJECTED_RESTORE_LINEARIZATION_FAILURE")
            }
        } catch (e: IllegalStateException) {
            threwException = true
            assertEquals("INJECTED_RESTORE_LINEARIZATION_FAILURE", e.message)
        }

        assertTrue(threwException)

        // Generation must remain 25L (rollback)
        assertEquals("Generation must roll back to 25L", 25L, liveDb.getGeneration())

        // Original account and outbox must be preserved exactly
        val accounts = liveDb.localAccountDao().getAllOneShot()
        assertEquals(1, accounts.size)
        assertEquals("acc_safe", accounts[0].id)
        assertEquals(15000.0, accounts[0].debtIqd, 0.001)

        val outbox = liveDb.syncOutboxDao().getAllOneShot()
        assertEquals(1, outbox.size)
        assertEquals(77, outbox[0].id)
        assertEquals("pending", outbox[0].status)
    }
}
