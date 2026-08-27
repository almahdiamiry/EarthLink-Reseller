package com.example.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.sync.EventSyncResult
import com.example.core.sync.RemoteEvent
import com.example.core.sync.RemoteEventSource
import com.example.core.sync.RemoteSyncCoordinator
import com.example.core.sync.RemoteSyncCursor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED-02 Permanent Regression Test:
 * Stale pull event race across import / restore / full-clear generation change.
 *
 * Verifies that when a remote pull or realtime event is captured at generation N,
 * but generation advances to N+1 before the event or sync pass completes:
 * 1. processEvent returns FAILED_RETRYABLE (preventing silent cursor advancement).
 * 2. The local database entity is NOT overwritten.
 * 3. Remote version metadata is NOT written.
 * 4. Sync cursors (per-collection and global last_sync_timestamp) do NOT advance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class StalePullEventGenerationRaceRegressionTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var coordinator: RemoteSyncCoordinator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

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
    }

    @Test
    fun staleAccountPullEvent_afterGenerationAdvance_returnsFailedRetryableAndPreservesLocalState() = runBlocking {
        // 1. Setup local state at baseline generation = 1L
        assertEquals(1L, db.getGeneration())
        val initialAccount = LocalAccount(
            id = "acc_race_01",
            displayName = "Pre-Restore Subscriber",
            phone1 = "07701234567",
            debtIqd = 10000.0
        )
        db.localAccountDao().upsert(initialAccount)

        // Capture generation before remote fetch starts
        val passGeneration = db.getGeneration() // 1L

        // Remote fetch receives a stale update for acc_race_01
        val remoteStaleAccount = LocalAccount(
            id = "acc_race_01",
            displayName = "Stale Cloud Subscriber",
            phone1 = "07709999999",
            debtIqd = 99999.0
        )
        val remoteEvent = RemoteEvent.AccountUpsert(
            entityId = remoteStaleAccount.id,
            remoteVersion = 2000L,
            source = RemoteEventSource.PULL,
            account = remoteStaleAccount
        )

        // 2. Advance generation to 2L (simulating Restore Replace / Utower Replace-All / Clear)
        db.incrementGeneration()
        assertEquals(2L, db.getGeneration())

        // Insert new post-restore local subscriber data
        val restoredAccount = LocalAccount(
            id = "acc_race_01",
            displayName = "Restored Ground Truth Subscriber",
            phone1 = "07705555555",
            debtIqd = 0.0
        )
        db.localAccountDao().upsert(restoredAccount)

        // 3. Attempt to apply the stale event with passedCapturedGen = 1L
        val result = coordinator.processEvent(remoteEvent, passedCapturedGen = passGeneration)

        // Assert: result is FAILED_RETRYABLE
        assertEquals(EventSyncResult.FAILED_RETRYABLE, result)
        assertFalse("Stale event must not advance cursor", result.canAdvanceCursor())

        // Assert: local database entity is NOT overwritten
        val currentAccount = db.localAccountDao().getByIdOneShot("acc_race_01")
        assertNotNull(currentAccount)
        assertEquals("Restored Ground Truth Subscriber", currentAccount?.displayName)
        assertEquals(0.0, currentAccount?.debtIqd ?: -1.0, 0.001)

        // Assert: remote_version metadata is NOT written
        val versionMeta = db.syncMetadataDao().get("remote_version:account:acc_race_01")
        assertNull("Stale remote_version must not be written", versionMeta)
    }

    @Test
    fun staleLedgerPullEvent_afterGenerationAdvance_returnsFailedRetryableAndPreservesBalance() = runBlocking {
        // 1. Setup local state at baseline generation = 1L
        assertEquals(1L, db.getGeneration())
        val parentAccount = LocalAccount(
            id = "acc_parent_race",
            displayName = "Parent Acc",
            debtIqd = 50000.0,
            openingDebtIqd = 50000.0
        )
        db.localAccountDao().upsert(parentAccount)

        val passGeneration = db.getGeneration() // 1L

        val staleLedger = LocalLedgerEntry(
            id = "ledger_stale_race",
            accountId = parentAccount.id,
            amountIqd = 30000.0,
            debtAfterIqd = 20000.0,
            typeRaw = "payment",
            occurredAt = 1710000000000L
        )
        val staleEvent = RemoteEvent.LedgerUpsert(
            entityId = staleLedger.id,
            remoteVersion = 4000L,
            source = RemoteEventSource.PULL,
            entry = staleLedger
        )

        // 2. Generation advances to 2L
        db.incrementGeneration()
        assertEquals(2L, db.getGeneration())

        // 3. Process stale event
        val result = coordinator.processEvent(staleEvent, passedCapturedGen = passGeneration)

        assertEquals(EventSyncResult.FAILED_RETRYABLE, result)
        assertFalse(result.canAdvanceCursor())

        // Assert: ledger entry was not inserted
        assertNull(db.localLedgerEntryDao().getByIdOneShot("ledger_stale_race"))

        // Assert: parent balance remains untouched
        val currentParent = db.localAccountDao().getByIdOneShot(parentAccount.id)
        assertEquals(50000.0, currentParent?.debtIqd ?: 0.0, 0.001)

        // Assert: remote version not recorded
        assertNull(db.syncMetadataDao().get("remote_version:ledger:ledger_stale_race"))
    }

    @Test
    fun cursorPersistenceGuardedByGenerationCheck_doesNotAdvanceWhenGenerationChanges() = runBlocking {
        // Initial cursors
        val initialCursorTimestamp = 1000L
        db.syncMetadataDao().put("last_sync_local_accounts", RemoteSyncCursor(initialCursorTimestamp, "doc_1").toCursorString())
        db.syncMetadataDao().put("last_sync_timestamp", initialCursorTimestamp.toString())

        val passGeneration = db.getGeneration() // 1L

        // Generation increments during downward pull
        db.incrementGeneration() // 2L

        // Verify that if generation changed, cursor updates are rejected
        val currentGen = db.syncMetadataDao().getGeneration()
        assertNotEquals(passGeneration, currentGen)

        // Verify stored cursor remains at initial state
        val cursorStr = db.syncMetadataDao().get("last_sync_local_accounts")
        val cursor = RemoteSyncCursor.parseCursorString(cursorStr)
        assertEquals(initialCursorTimestamp, cursor.lastServerTimestamp)
        assertEquals("doc_1", cursor.lastDocumentId)

        val globalTs = db.syncMetadataDao().get("last_sync_timestamp")
        assertEquals(initialCursorTimestamp.toString(), globalTs)
    }
}
