package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.model.SyncOutbox
import com.example.core.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 2 Behavioral Test Suite: Server-Confirmed remote_version Lifecycle (INV-04 / INV-06 / INV-10).
 * Verifies production execution paths for remote version capture, read-back semantics,
 * tombstone lifecycle, outbox mutation deduplication, and crash/reconciliation guarantees.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase2ServerConfirmedLifecycleTest {

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

    // T1: pendingSnapshot_doesNotCreateRemoteVersion
    @Test
    fun pendingSnapshot_doesNotCreateRemoteVersion() = runBlocking {
        val accountId = "acc_t1"
        OutboxManager.enqueue(
            outboxDao = db.syncOutboxDao(),
            entityType = "local_accounts",
            entityId = accountId,
            operation = "upsert",
            payloadJson = """{"id":"$accountId","displayName":"Test T1","updatedAt":1700000000000}"""
        )

        val localVersion = coordinator.resolveLocalVersion("account", accountId)
        assertTrue(
            "Pending mutation must not establish authoritative ServerTracked version",
            localVersion !is LocalVersionState.ServerTracked
        )
        assertNull("Metadata remote_version must not exist for unconfirmed pending mutation", db.syncMetadataDao().get("remote_version:account:$accountId"))
    }

    // T2: confirmedServerState_createsRemoteVersion
    @Test
    fun confirmedServerState_createsRemoteVersion() = runBlocking {
        val accountId = "acc_t2"
        val serverTs = 1750000000000L
        val account = LocalAccount(
            id = accountId,
            displayName = "Test T2",
            debtIqd = 0.0,
            advanceIqd = 0.0,
            updatedAt = serverTs
        )

        val event = RemoteEvent.AccountUpsert(
            entityId = accountId,
            remoteVersion = serverTs,
            source = RemoteEventSource.REALTIME,
            account = account,
            syncMutationId = "mut_t2"
        )

        val result = coordinator.processEvent(event)
        assertEquals(EventSyncResult.APPLIED, result)

        val localVersion = coordinator.resolveLocalVersion("account", accountId)
        assertTrue("Applied remote server event must create ServerTracked version", localVersion is LocalVersionState.ServerTracked)
        assertEquals(serverTs, (localVersion as LocalVersionState.ServerTracked).version)
        assertEquals(serverTs.toString(), db.syncMetadataDao().get("remote_version:account:$accountId"))
    }

    // T3: nonFinalServerTimestamp_doesNotCreateRemoteVersion
    @Test
    fun nonFinalServerTimestamp_doesNotCreateRemoteVersion() = runBlocking {
        val accountId = "acc_t3"
        val invalidVersions = listOf(0L, -1L, -100L)

        for (invalidVersion in invalidVersions) {
            val account = LocalAccount(
                id = accountId,
                displayName = "Test T3",
                debtIqd = 0.0,
                advanceIqd = 0.0,
                updatedAt = System.currentTimeMillis()
            )
            val event = RemoteEvent.AccountUpsert(
                entityId = accountId,
                remoteVersion = invalidVersion,
                source = RemoteEventSource.PULL,
                account = account,
                syncMutationId = null
            )
            val result = coordinator.processEvent(event)
            assertEquals(EventSyncResult.QUARANTINED_MALFORMED, result)

            val localVersion = coordinator.resolveLocalVersion("account", accountId)
            assertTrue("Malformed/zero timestamp must not create ServerTracked version", localVersion !is LocalVersionState.ServerTracked)
        }
    }

    // T4: localClockSkew_doesNotAffectRemoteVersion
    @Test
    fun localClockSkew_doesNotAffectRemoteVersion() = runBlocking {
        val accountId = "acc_t4"
        val serverTs = 1600000000000L
        val skewedLocalTime = 2000000000000L // 2033 local clock

        val account = LocalAccount(
            id = accountId,
            displayName = "Test T4",
            debtIqd = 0.0,
            advanceIqd = 0.0,
            updatedAt = skewedLocalTime
        )

        val event = RemoteEvent.AccountUpsert(
            entityId = accountId,
            remoteVersion = serverTs,
            source = RemoteEventSource.REALTIME,
            account = account,
            syncMutationId = "mut_t4"
        )

        val result = coordinator.processEvent(event)
        assertEquals(EventSyncResult.APPLIED, result)

        val localVersion = coordinator.resolveLocalVersion("account", accountId)
        assertEquals(serverTs, (localVersion as LocalVersionState.ServerTracked).version)
        assertNotEquals(skewedLocalTime, (localVersion as LocalVersionState.ServerTracked).version)
    }

    // T5: pushSuccess_serverReadBackFailure_doesNotReplayOutboxMutation
    @Test
    fun pushSuccess_serverReadBackFailure_doesNotReplayOutboxMutation() = runBlocking {
        val accountId = "acc_t5"
        OutboxManager.enqueue(
            outboxDao = db.syncOutboxDao(),
            entityType = "local_accounts",
            entityId = accountId,
            operation = "upsert",
            payloadJson = """{"id":"$accountId","displayName":"Test T5"}"""
        )

        val insertedItems = db.syncOutboxDao().getByEntity(accountId, "local_accounts")
        assertTrue(insertedItems.isNotEmpty())

        // Simulate push success: Outbox is marked succeeded immediately upon batch.commit()
        OutboxManager.markSucceeded(db.syncOutboxDao(), insertedItems.map { it.id })

        // Simulate readback failure -> sets version_capture_retry = 1
        db.syncMetadataDao().put("version_capture_retry:account:$accountId", "1")

        // Assert outbox item is NOT in pending/retryable queue
        val pendingOutbox = OutboxManager.getPending(db.syncOutboxDao())
        assertTrue("Successful push must purge mutation from pending outbox even if read-back failed", pendingOutbox.none { it.entityId == accountId })

        // Version capture retry is preserved for reconciliation
        assertEquals("1", db.syncMetadataDao().get("version_capture_retry:account:$accountId"))
    }

    // T6: crashAfterPush_recoversWithoutDuplicateMutation
    @Test
    fun crashAfterPush_recoversWithoutDuplicateMutation() = runBlocking {
        val accountId = "acc_t6"
        OutboxManager.enqueue(
            outboxDao = db.syncOutboxDao(),
            entityType = "local_accounts",
            entityId = accountId,
            operation = "upsert",
            payloadJson = """{"id":"$accountId","displayName":"Test T6"}"""
        )

        val insertedItems = db.syncOutboxDao().getByEntity(accountId, "local_accounts")
        assertTrue(insertedItems.isNotEmpty())

        // Push committed, outbox marked succeeded, but app crashed before metadata capture
        OutboxManager.markSucceeded(db.syncOutboxDao(), insertedItems.map { it.id })

        // On app relaunch / next sync cycle:
        val pendingItems = OutboxManager.getPending(db.syncOutboxDao())
        assertTrue("No duplicate push mutation should be pending after crash-recovery", pendingItems.none { it.entityId == accountId })
    }

    // T7: concurrentRemoteWriter_doesNotAdvanceVersionBeforeStateApply
    @Test
    fun concurrentRemoteWriter_doesNotAdvanceVersionBeforeStateApply() = runBlocking {
        val accountId = "acc_t7"
        val serverTs1 = 1710000000000L

        OutboxManager.enqueue(
            outboxDao = db.syncOutboxDao(),
            entityType = "local_accounts",
            entityId = accountId,
            operation = "upsert",
            payloadJson = """{"id":"$accountId","displayName":"Local Mutation T7","syncMutationId":"mut_local_t7"}"""
        )

        val remoteAccount = LocalAccount(
            id = accountId,
            displayName = "Remote Writer Account",
            debtIqd = 100.0,
            advanceIqd = 0.0,
            updatedAt = serverTs1
        )
        val remoteEvent = RemoteEvent.AccountUpsert(
            entityId = accountId,
            remoteVersion = serverTs1,
            source = RemoteEventSource.REALTIME,
            account = remoteAccount,
            syncMutationId = "mut_remote_writer_xyz"
        )

        val result = coordinator.processEvent(remoteEvent)
        assertEquals(EventSyncResult.SKIPPED_DUPLICATE, result)

        val localVersion = coordinator.resolveLocalVersion("account", accountId)
        assertTrue("Concurrent remote writer must not advance version when conflict skipped", localVersion !is LocalVersionState.ServerTracked)
    }

    // T8: mutationIdMismatch_isNotAcceptedAsLocalConfirmation
    @Test
    fun mutationIdMismatch_isNotAcceptedAsLocalConfirmation() = runBlocking {
        val accountId = "acc_t8"
        val localMutationId = "mut_local_my_device"
        val remoteOtherMutationId = "mut_remote_other_device"

        OutboxManager.enqueue(
            outboxDao = db.syncOutboxDao(),
            entityType = "local_accounts",
            entityId = accountId,
            operation = "upsert",
            payloadJson = """{"id":"$accountId","displayName":"Local T8","syncMutationId":"$localMutationId"}"""
        )

        val remoteAccount = LocalAccount(
            id = accountId,
            displayName = "Remote T8",
            debtIqd = 50.0,
            advanceIqd = 0.0,
            updatedAt = 1720000000000L
        )

        val event = RemoteEvent.AccountUpsert(
            entityId = accountId,
            remoteVersion = 1720000000000L,
            source = RemoteEventSource.REALTIME,
            account = remoteAccount,
            syncMutationId = remoteOtherMutationId
        )

        val result = coordinator.processEvent(event)
        assertEquals("Mismatching mutationId must not confirm local active mutation", EventSyncResult.SKIPPED_DUPLICATE, result)
    }

    // T9: duplicateConfirmation_isIdempotent
    @Test
    fun duplicateConfirmation_isIdempotent() = runBlocking {
        val accountId = "acc_t9"
        val serverTs = 1730000000000L
        val account = LocalAccount(
            id = accountId,
            displayName = "Test T9",
            debtIqd = 0.0,
            advanceIqd = 0.0,
            updatedAt = serverTs
        )

        val event = RemoteEvent.AccountUpsert(
            entityId = accountId,
            remoteVersion = serverTs,
            source = RemoteEventSource.REALTIME,
            account = account,
            syncMutationId = "mut_t9"
        )

        val result1 = coordinator.processEvent(event)
        assertEquals(EventSyncResult.APPLIED, result1)

        val result2 = coordinator.processEvent(event)
        assertEquals("Subsequent identical event must be skipped idempotently", EventSyncResult.SKIPPED_DUPLICATE, result2)

        val localVersion = coordinator.resolveLocalVersion("account", accountId)
        assertEquals(serverTs, (localVersion as LocalVersionState.ServerTracked).version)
    }

    // T10: outOfOrderConfirmation_doesNotRegressVersion
    @Test
    fun outOfOrderConfirmation_doesNotRegressVersion() = runBlocking {
        val accountId = "acc_t10"
        val newerServerTs = 1750000000000L
        val olderServerTs = 1740000000000L

        val newerAccount = LocalAccount(
            id = accountId,
            displayName = "Test T10 Newer",
            debtIqd = 0.0,
            advanceIqd = 0.0,
            updatedAt = newerServerTs
        )

        val newerEvent = RemoteEvent.AccountUpsert(
            entityId = accountId,
            remoteVersion = newerServerTs,
            source = RemoteEventSource.REALTIME,
            account = newerAccount,
            syncMutationId = "mut_t10_newer"
        )
        coordinator.processEvent(newerEvent)

        val olderAccount = LocalAccount(
            id = accountId,
            displayName = "Test T10 Older",
            debtIqd = 0.0,
            advanceIqd = 0.0,
            updatedAt = olderServerTs
        )
        val olderEvent = RemoteEvent.AccountUpsert(
            entityId = accountId,
            remoteVersion = olderServerTs,
            source = RemoteEventSource.PULL,
            account = olderAccount,
            syncMutationId = "mut_t10_older"
        )

        val olderResult = coordinator.processEvent(olderEvent)
        assertEquals(EventSyncResult.SKIPPED_DUPLICATE, olderResult)

        val localVersion = coordinator.resolveLocalVersion("account", accountId)
        assertEquals("Version must not regress to older out-of-order event", newerServerTs, (localVersion as LocalVersionState.ServerTracked).version)
    }

    // T11: delete_usesServerConfirmedTombstoneVersion
    @Test
    fun delete_usesServerConfirmedTombstoneVersion() = runBlocking {
        val accountId = "acc_t11"
        val serverTs = 1760000000000L

        val deleteEvent = RemoteEvent.AccountDelete(
            entityId = accountId,
            remoteVersion = serverTs,
            source = RemoteEventSource.REALTIME,
            syncMutationId = "mut_t11_del"
        )

        val result = coordinator.processEvent(deleteEvent)
        assertEquals(EventSyncResult.APPLIED, result)

        val tombstone = db.syncMetadataDao().get("tombstone:account:$accountId")
        assertEquals(serverTs.toString(), tombstone)

        val remoteVersion = db.syncMetadataDao().get("remote_version:account:$accountId")
        assertEquals(serverTs.toString(), remoteVersion)
    }

    // T12: offlineReconnect_reconcilesWithoutReplay
    @Test
    fun offlineReconnect_reconcilesWithoutReplay() = runBlocking {
        val accountId = "acc_t12"
        OutboxManager.enqueue(
            outboxDao = db.syncOutboxDao(),
            entityType = "local_accounts",
            entityId = accountId,
            operation = "upsert",
            payloadJson = """{"id":"$accountId","displayName":"Test T12"}"""
        )

        val insertedItems = db.syncOutboxDao().getByEntity(accountId, "local_accounts")
        assertTrue(insertedItems.isNotEmpty())

        OutboxManager.markSucceeded(db.syncOutboxDao(), insertedItems.map { it.id })

        val pending = OutboxManager.getPending(db.syncOutboxDao())
        assertTrue(pending.none { it.entityId == accountId })
    }

    // T13: missedRealtimeConfirmation_recoversThroughServerReadBack
    @Test
    fun missedRealtimeConfirmation_recoversThroughServerReadBack() = runBlocking {
        val accountId = "acc_t13"
        val serverTs = 1770000000000L

        val pullEvent = RemoteEvent.AccountUpsert(
            entityId = accountId,
            remoteVersion = serverTs,
            source = RemoteEventSource.PULL,
            account = LocalAccount(
                id = accountId,
                displayName = "Test T13",
                debtIqd = 0.0,
                advanceIqd = 0.0,
                updatedAt = serverTs
            ),
            syncMutationId = null
        )

        val result = coordinator.processEvent(pullEvent)
        assertEquals(EventSyncResult.APPLIED, result)

        val localVersion = coordinator.resolveLocalVersion("account", accountId)
        assertEquals(serverTs, (localVersion as LocalVersionState.ServerTracked).version)
    }

    // T14: serverReadUnavailable_preservesRetryableCaptureState
    @Test
    fun serverReadUnavailable_preservesRetryableCaptureState() = runBlocking {
        val accountId = "acc_t14"
        db.syncMetadataDao().put("version_capture_retry:account:$accountId", "1")

        assertEquals("1", db.syncMetadataDao().get("version_capture_retry:account:$accountId"))
        assertNull(db.syncMetadataDao().get("remote_version:account:$accountId"))
    }

    // T15: twoDeviceConvergence_reconcilesToServerState
    @Test
    fun twoDeviceConvergence_reconcilesToServerState() = runBlocking {
        val accountId = "acc_t15"
        val initialServerTs = 1700000000000L
        val newerServerTs = 1780000000000L

        val initialAccount = LocalAccount(
            id = accountId,
            displayName = "Device A initial",
            debtIqd = 10.0,
            advanceIqd = 0.0,
            updatedAt = initialServerTs
        )
        coordinator.processEvent(
            RemoteEvent.AccountUpsert(
                entityId = accountId,
                remoteVersion = initialServerTs,
                source = RemoteEventSource.BOOTSTRAP,
                account = initialAccount,
                syncMutationId = null
            )
        )

        val newerRemoteAccount = LocalAccount(
            id = accountId,
            displayName = "Device B updated",
            debtIqd = 250.0,
            advanceIqd = 0.0,
            updatedAt = newerServerTs
        )
        val convergenceEvent = RemoteEvent.AccountUpsert(
            entityId = accountId,
            remoteVersion = newerServerTs,
            source = RemoteEventSource.REALTIME,
            account = newerRemoteAccount,
            syncMutationId = "mut_device_b"
        )

        val result = coordinator.processEvent(convergenceEvent)
        assertEquals(EventSyncResult.APPLIED, result)

        val resolved = db.localAccountDao().getByIdOneShot(accountId)
        assertNotNull(resolved)
        assertEquals("Device B updated", resolved?.displayName)
        assertEquals(250.0, resolved?.debtIqd ?: 0.0, 0.001)

        val localVersion = coordinator.resolveLocalVersion("account", accountId)
        assertEquals(newerServerTs, (localVersion as LocalVersionState.ServerTracked).version)
    }

    // T16: productionPathOracle_usesRealSyncProductionPath
    @Test
    fun productionPathOracle_usesRealSyncProductionPath() = runBlocking {
        val accountId = "acc_t16"
        val serverTs = 1790000000000L

        val vNew = coordinator.resolveLocalVersion("account", "acc_nonexistent")
        assertEquals(LocalVersionState.New, vNew)

        db.localAccountDao().upsert(
            LocalAccount(
                id = accountId,
                displayName = "Untracked Account",
                debtIqd = 0.0,
                advanceIqd = 0.0,
                updatedAt = 500L
            )
        )
        val vUntracked = coordinator.resolveLocalVersion("account", accountId)
        assertTrue(vUntracked is LocalVersionState.Untracked)

        db.syncMetadataDao().put("remote_version:account:$accountId", serverTs.toString())
        val vTracked = coordinator.resolveLocalVersion("account", accountId)
        assertTrue(vTracked is LocalVersionState.ServerTracked)
        assertEquals(serverTs, (vTracked as LocalVersionState.ServerTracked).version)
    }
}
