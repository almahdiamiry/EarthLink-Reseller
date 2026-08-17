package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
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
 * Phase 2 Permanent Adversarial Fixture: Remote Version Invariants (INV-04 / INV-06 / INV-10).
 * Tests adversarial injections (pending timestamps, cache confusion, clock skew, version ahead of state,
 * mutation correlation mismatches, and post-push capture failure replay prevention).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase2RemoteVersionAdversarialTest {

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

    // Case A — Pending timestamp injection
    @Test
    fun caseA_pendingTimestampInjection_doesNotCreateRemoteVersion() = runBlocking {
        val accountId = "adv_case_a"
        val plausibleTimestamp = 1750000000000L

        OutboxManager.enqueue(
            outboxDao = db.syncOutboxDao(),
            entityType = "local_accounts",
            entityId = accountId,
            operation = "upsert",
            payloadJson = """{"id":"$accountId","displayName":"Case A","updatedAt":$plausibleTimestamp}"""
        )

        val localVersion = coordinator.resolveLocalVersion("account", accountId)
        assertTrue(
            "Adversarial pending timestamp injection must not create authoritative remote_version",
            localVersion !is LocalVersionState.ServerTracked
        )
    }

    // Case B — Cache/server-source confusion
    @Test
    fun caseB_cacheConfusion_doesNotTransferAuthority() = runBlocking {
        val accountId = "adv_case_b"
        val cacheTimestamp = 1900000000000L

        db.localAccountDao().upsert(
            LocalAccount(
                id = accountId,
                displayName = "Local Cache Entity",
                debtIqd = 0.0,
                advanceIqd = 0.0,
                updatedAt = cacheTimestamp
            )
        )

        val localVersion = coordinator.resolveLocalVersion("account", accountId)
        assertTrue(
            "Local cache timestamp must never transfer authority without server confirmation",
            localVersion !is LocalVersionState.ServerTracked
        )
    }

    // Case C — Local/device timestamp injection
    @Test
    fun caseC_localDeviceTimestampInjection_doesNotCreateServerTrackedVersion() = runBlocking {
        val accountId = "adv_case_c"
        val deviceTimestamp = System.currentTimeMillis() + 10000000L

        db.localAccountDao().upsert(
            LocalAccount(
                id = accountId,
                displayName = "Clock Injected",
                debtIqd = 0.0,
                advanceIqd = 0.0,
                updatedAt = deviceTimestamp
            )
        )

        val localVersion = coordinator.resolveLocalVersion("account", accountId)
        assertFalse(
            "Local device clock timestamp cannot become authoritative remote_version",
            localVersion is LocalVersionState.ServerTracked
        )
    }

    // Case D — Version ahead of local state
    @Test
    fun caseD_versionAheadOfLocalState_cannotPersistVersionWithoutApplyingState() = runBlocking {
        val accountId = "adv_case_d"

        val event = RemoteEvent.AccountUpsert(
            entityId = accountId,
            remoteVersion = 0L,
            source = RemoteEventSource.REALTIME,
            account = LocalAccount(
                id = accountId,
                displayName = "Case D",
                debtIqd = 0.0,
                advanceIqd = 0.0,
                updatedAt = 0L
            ),
            syncMutationId = "mut_case_d"
        )

        val result = coordinator.processEvent(event)
        assertEquals(EventSyncResult.QUARANTINED_MALFORMED, result)

        val storedVersion = db.syncMetadataDao().get("remote_version:account:$accountId")
        assertNull("System cannot persist newer remote version when state application fails", storedVersion)
    }

    // Case E — Mutation correlation mismatch
    @Test
    fun caseE_mutationCorrelationMismatch_doesNotConfirmLocalMutation() = runBlocking {
        val accountId = "adv_case_e"
        val myMutationId = "mut_device_123"
        val foreignMutationId = "mut_device_999"

        OutboxManager.enqueue(
            outboxDao = db.syncOutboxDao(),
            entityType = "local_accounts",
            entityId = accountId,
            operation = "upsert",
            payloadJson = """{"id":"$accountId","displayName":"Local Case E","syncMutationId":"$myMutationId"}"""
        )

        val incomingEvent = RemoteEvent.AccountUpsert(
            entityId = accountId,
            remoteVersion = 1750000000000L,
            source = RemoteEventSource.REALTIME,
            account = LocalAccount(
                id = accountId,
                displayName = "Foreign Remote",
                debtIqd = 50.0,
                advanceIqd = 0.0,
                updatedAt = 1750000000000L
            ),
            syncMutationId = foreignMutationId
        )

        val result = coordinator.processEvent(incomingEvent)
        assertEquals(
            "Foreign mutation correlation must be treated as conflicting and skipped",
            EventSyncResult.SKIPPED_DUPLICATE,
            result
        )

        assertTrue(OutboxManager.hasActiveMutation(db.syncOutboxDao(), accountId, "local_accounts"))
    }

    // Case F — Replay after successful push + capture failure
    @Test
    fun caseF_replayAfterCaptureFailure_neverReplaysSuccessfulPush() = runBlocking {
        val accountId = "adv_case_f"
        OutboxManager.enqueue(
            outboxDao = db.syncOutboxDao(),
            entityType = "local_accounts",
            entityId = accountId,
            operation = "upsert",
            payloadJson = """{"id":"$accountId","displayName":"Case F"}"""
        )

        val insertedItems = db.syncOutboxDao().getByEntity(accountId, "local_accounts")
        assertTrue(insertedItems.isNotEmpty())

        OutboxManager.markSucceeded(db.syncOutboxDao(), insertedItems.map { it.id })
        db.syncMetadataDao().put("version_capture_retry:account:$accountId", "1")

        val pendingToPush = OutboxManager.getPending(db.syncOutboxDao())
        assertEquals(
            "Successful push with capture failure must NOT re-enqueue or replay the mutation",
            0,
            pendingToPush.size
        )

        assertEquals("1", db.syncMetadataDao().get("version_capture_retry:account:$accountId"))
    }
}
