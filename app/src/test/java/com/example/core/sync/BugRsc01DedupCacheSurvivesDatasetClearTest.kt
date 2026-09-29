package com.example.core.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED regression test for BUG-RSC-1.
 *
 * Claim: the in-memory dedup LRU in RemoteSyncCoordinator must not survive a full local
 * dataset clear. After a logout that wipes the device, the same remote event must still be
 * applicable; otherwise it is silently suppressed AND the caller advances the sync cursor
 * past it, so the state never arrives.
 *
 * The cache key carries no lineage-generation component, and clearCache() is called only
 * by BackupManager and UtowerImporter - never by the signOut(clearData = true) path.
 *
 * Seam / Environment: ROBOLECTRIC tier, in-memory Room SQLite, real RemoteSyncCoordinator.
 *
 * Independent Oracle: the local database was destroyed, so the coordinator has no record of
 * ever having applied the event. A correct implementation therefore re-applies it. Only the
 * LRU, which is not part of the database, can make it skip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BugRsc01DedupCacheSurvivesDatasetClearTest {

    private lateinit var db: AppDatabase
    private lateinit var coordinator: RemoteSyncCoordinator

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
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
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun sameRemoteEvent_reappliedAfterFullLocalDataClear() = kotlinx.coroutines.runBlocking {
        val event = RemoteEvent.AccountUpsert(
            entityId = "acc_shared",
            remoteVersion = 1000L,
            source = RemoteEventSource.PULL,
            account = LocalAccount(
                id = "acc_shared",
                sourceExternalId = "ext_shared",
                earthlinkUsername = "shared_user",
                displayName = "Shared",
                ispUserIndex = 555,
                createdAt = 1_700_000_000_000L
            ),
            syncMutationId = "mut_1"
        )

        // First delivery: applied.
        val first = coordinator.processEvent(event)
        assertEquals("Precondition: first delivery must apply", EventSyncResult.APPLIED, first)

        // The operator signs out and the device data is wiped, exactly as
        // SyncRepositoryImpl.signOut(clearData = true) does. clearAllData() wipes the
        // database; nothing here tells the coordinator its memory is stale.
        db.clearAllData()
        val accountsAfterWipe = db.localAccountDao().getAllPersistedOneShot(limit = Int.MAX_VALUE)
        assertEquals("Precondition: the local dataset must be empty after the wipe", 0, accountsAfterWipe.size)

        // The same event is re-delivered by a later pull (server updatedAt unchanged).
        val second = coordinator.processEvent(event)

        val applied = db.localAccountDao().getAllPersistedOneShot(limit = Int.MAX_VALUE).size
        val diag = "first=$first second=$second accountsAfterSecondDelivery=$applied"

        assertEquals(
            "The local dataset was wiped, so the coordinator has no record of applying this " +
                "event and must re-apply it. Skipping it means EventSyncResult.SKIPPED_DUPLICATE " +
                "returns canAdvanceCursor() == true, so SyncRepositoryImpl advances last_sync past " +
                "the event and the account never reaches the device again.\n$diag",
            EventSyncResult.APPLIED, second
        )
        assertEquals(
            "The account must exist after the re-delivery.\n$diag",
            1, applied
        )
    }
}
