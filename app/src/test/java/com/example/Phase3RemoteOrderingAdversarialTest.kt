package com.example

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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 3 Task P3-07: Remote Ordering Coordinates & Adversarial Ordering Tests
 * (INV-01, INV-05, INV-06, INV-11).
 *
 * Verifies the 5 canonical ordering invariants:
 * 1. update -> delete (delete with newer version supersedes existing entity)
 * 2. delete -> stale upsert (stale upsert after tombstone is strictly rejected)
 * 3. duplicate delete (repeated delete is cleanly idempotent)
 * 4. newer update -> older update (older update cannot overwrite newer local state)
 * 5. newer update -> older delete (older delete cannot delete newer local state)
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase3RemoteOrderingAdversarialTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var coordinator: RemoteSyncCoordinator

    @Before
    fun setup() {
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

    /**
     * 1. update -> delete:
     * Entity exists at version 100L. Newer delete arrives at version 200L.
     * Result: Entity is deleted and tombstone is recorded at 200L.
     */
    @Test
    fun testUpdateThenDelete_deleteSupersedesEntity() = runBlocking {
        val account = LocalAccount(id = "acc_ord_1", displayName = "Account V100", debtIqd = 1000.0)
        val updateEvent = RemoteEvent.AccountUpsert(
            entityId = account.id,
            remoteVersion = 100L,
            source = RemoteEventSource.PULL,
            account = account
        )
        val res1 = coordinator.processEvent(updateEvent)
        assertEquals(EventSyncResult.APPLIED, res1)
        assertNotNull(db.localAccountDao().getByIdOneShot("acc_ord_1"))

        // Delete arrives with newer version 200L
        val deleteEvent = RemoteEvent.AccountDelete(
            entityId = "acc_ord_1",
            remoteVersion = 200L,
            source = RemoteEventSource.REALTIME
        )
        val res2 = coordinator.processEvent(deleteEvent)
        assertEquals(EventSyncResult.APPLIED, res2)

        // Entity is deleted
        assertNull("Entity must be deleted", db.localAccountDao().getByIdOneShot("acc_ord_1"))

        // Tombstone recorded at 200L
        val tombstone = db.syncMetadataDao().get("tombstone:account:acc_ord_1")
        assertEquals("200", tombstone)
    }

    /**
     * 2. delete -> stale upsert:
     * Entity deleted at version 200L with tombstone. Stale upsert arrives at version 150L.
     * Result: Stale upsert is rejected (SKIPPED_DUPLICATE); entity is NOT resurrected.
     */
    @Test
    fun testDeleteThenStaleUpsert_staleUpsertIsRejected() = runBlocking {
        // Record tombstone at 200L
        val deleteEvent = RemoteEvent.AccountDelete(
            entityId = "acc_ord_2",
            remoteVersion = 200L,
            source = RemoteEventSource.REALTIME
        )
        val res1 = coordinator.processEvent(deleteEvent)
        assertEquals(EventSyncResult.APPLIED, res1)

        // Stale upsert arrives with version 150L
        val staleAccount = LocalAccount(id = "acc_ord_2", displayName = "Ghost Resurrected", debtIqd = 5000.0)
        val staleUpsert = RemoteEvent.AccountUpsert(
            entityId = "acc_ord_2",
            remoteVersion = 150L,
            source = RemoteEventSource.PULL,
            account = staleAccount
        )
        val res2 = coordinator.processEvent(staleUpsert)
        assertEquals("Stale upsert must be skipped", EventSyncResult.SKIPPED_DUPLICATE, res2)

        // Entity remains deleted (no resurrection)
        assertNull("Entity must NOT be resurrected", db.localAccountDao().getByIdOneShot("acc_ord_2"))
    }

    /**
     * 3. duplicate delete:
     * Delete at version 200L arrives twice.
     * Result: First applies, second is cleanly idempotent (SKIPPED_DUPLICATE).
     */
    @Test
    fun testDuplicateDelete_isCleanlyIdempotent() = runBlocking {
        val deleteEvent1 = RemoteEvent.AccountDelete(
            entityId = "acc_ord_3",
            remoteVersion = 200L,
            source = RemoteEventSource.REALTIME
        )
        val res1 = coordinator.processEvent(deleteEvent1)
        assertEquals(EventSyncResult.APPLIED, res1)

        val deleteEvent2 = RemoteEvent.AccountDelete(
            entityId = "acc_ord_3",
            remoteVersion = 200L,
            source = RemoteEventSource.PULL
        )
        val res2 = coordinator.processEvent(deleteEvent2)
        assertEquals("Duplicate delete must be skipped as duplicate", EventSyncResult.SKIPPED_DUPLICATE, res2)
    }

    /**
     * 4. newer update -> older update:
     * Entity updated to version 300L with new name. Older update arrives with version 200L.
     * Result: Older update is rejected (SKIPPED_DUPLICATE); newer local data is preserved.
     */
    @Test
    fun testNewerUpdateThenOlderUpdate_olderUpdateRejected() = runBlocking {
        val newerAccount = LocalAccount(id = "acc_ord_4", displayName = "Newer Name V300", debtIqd = 3000.0)
        val newerEvent = RemoteEvent.AccountUpsert(
            entityId = "acc_ord_4",
            remoteVersion = 300L,
            source = RemoteEventSource.REALTIME,
            account = newerAccount
        )
        val res1 = coordinator.processEvent(newerEvent)
        assertEquals(EventSyncResult.APPLIED, res1)

        // Older event arrives with version 200L
        val olderAccount = LocalAccount(id = "acc_ord_4", displayName = "Older Name V200", debtIqd = 100.0)
        val olderEvent = RemoteEvent.AccountUpsert(
            entityId = "acc_ord_4",
            remoteVersion = 200L,
            source = RemoteEventSource.PULL,
            account = olderAccount
        )
        val res2 = coordinator.processEvent(olderEvent)
        assertEquals("Older update must be skipped", EventSyncResult.SKIPPED_DUPLICATE, res2)

        // Verify current state retains newer update
        val saved = db.localAccountDao().getByIdOneShot("acc_ord_4")
        assertNotNull(saved)
        assertEquals("Newer Name V300", saved?.displayName)
        assertEquals(3000.0, saved?.debtIqd ?: 0.0, 0.001)
    }

    /**
     * 5. newer update -> older delete:
     * Entity exists at version 300L. Older delete arrives with version 200L.
     * Result: Older delete is rejected (SKIPPED_DUPLICATE); entity remains active.
     */
    @Test
    fun testNewerUpdateThenOlderDelete_olderDeleteRejected() = runBlocking {
        val activeAccount = LocalAccount(id = "acc_ord_5", displayName = "Active V300", debtIqd = 7000.0)
        val newerEvent = RemoteEvent.AccountUpsert(
            entityId = "acc_ord_5",
            remoteVersion = 300L,
            source = RemoteEventSource.REALTIME,
            account = activeAccount
        )
        val res1 = coordinator.processEvent(newerEvent)
        assertEquals(EventSyncResult.APPLIED, res1)

        // Stale delete arrives with version 200L
        val staleDelete = RemoteEvent.AccountDelete(
            entityId = "acc_ord_5",
            remoteVersion = 200L,
            source = RemoteEventSource.PULL
        )
        val res2 = coordinator.processEvent(staleDelete)
        assertEquals("Older delete must be skipped", EventSyncResult.SKIPPED_DUPLICATE, res2)

        // Entity must NOT be deleted
        val saved = db.localAccountDao().getByIdOneShot("acc_ord_5")
        assertNotNull("Newer entity must remain active", saved)
        assertEquals("Active V300", saved?.displayName)
    }

    /**
     * 6. Ledger entry adversarial ordering:
     * Newer ledger entry (v500L) is not overwritten by older ledger entry (v400L).
     */
    @Test
    fun testLedgerNewerUpdateThenOlderUpdate_olderUpdateRejected() = runBlocking {
        val account = LocalAccount(id = "acc_ledger_ord", displayName = "Parent", debtIqd = 0.0)
        db.localAccountDao().insert(account)

        val txNewer = LocalLedgerEntry(id = "tx_ord_1", accountId = "acc_ledger_ord", amountIqd = 5000.0, debtAfterIqd = 5000.0, typeRaw = "took")
        val eventNewer = RemoteEvent.LedgerUpsert(
            entityId = "tx_ord_1",
            remoteVersion = 500L,
            source = RemoteEventSource.REALTIME,
            entry = txNewer
        )
        val res1 = coordinator.processEvent(eventNewer)
        assertEquals(EventSyncResult.APPLIED, res1)

        val txOlder = LocalLedgerEntry(id = "tx_ord_1", accountId = "acc_ledger_ord", amountIqd = 100.0, debtAfterIqd = 100.0, typeRaw = "took")
        val eventOlder = RemoteEvent.LedgerUpsert(
            entityId = "tx_ord_1",
            remoteVersion = 400L,
            source = RemoteEventSource.PULL,
            entry = txOlder
        )
        val res2 = coordinator.processEvent(eventOlder)
        assertTrue(
            "Older divergent/stale ledger event must be rejected/quarantined without mutating newer state",
            res2 == EventSyncResult.SKIPPED_DUPLICATE || res2 == EventSyncResult.QUARANTINED_CONFLICT
        )

        val savedTx = db.localLedgerEntryDao().getByIdOneShot("tx_ord_1")
        assertNotNull(savedTx)
        assertEquals(5000.0, savedTx?.amountIqd ?: 0.0, 0.001)
    }
}
