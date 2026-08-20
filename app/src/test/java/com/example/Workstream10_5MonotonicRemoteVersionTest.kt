package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.*
import com.example.core.sync.EventSyncResult
import com.example.core.sync.RemoteEvent
import com.example.core.sync.RemoteEventSource
import com.example.core.sync.RemoteSyncCoordinator
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Workstream 10.5 Certification Test: Global remote_version:* Monotonicity.
 *
 * Verifies:
 * 1. Atomic DAO boundary: putMonotonicRemoteVersion stores max(current, incoming)
 * 2. Downgrade prevention: 200 accepted, then 150 arrives -> stored version remains 200
 * 3. Application boundary enforcement: 160 arrives on RemoteSyncCoordinator -> rejected as stale
 * 4. Idempotent same-version: incomingVersion == storedVersion -> stored value remains unchanged
 * 5. Concurrent DAO writers: concurrent writes with [200, 160, 180, 150] converges to 200
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Workstream10_5MonotonicRemoteVersionTest {

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
            accountDao = db.localAccountDao(),
            ledgerDao = db.localLedgerEntryDao(),
            batchDao = db.importBatchDao(),
            outboxDao = db.syncOutboxDao(),
            metadataDao = db.syncMetadataDao(),
            auditDao = db.auditLogDao(),
            appDatabase = db
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testDaoBoundary_Monotonicity_DowngradeIgnored_HigherAccepted() = runBlocking {
        val metaDao = db.syncMetadataDao()
        val key = "remote_version:account:acc_mono_001"

        // 1. Initial write of version 200
        metaDao.putMonotonicRemoteVersion(key, 200L)
        assertEquals("200", metaDao.get(key))

        // 2. Stale write of version 150 arrives -> must NOT overwrite 200
        metaDao.putMonotonicRemoteVersion(key, 150L)
        assertEquals("200", metaDao.get(key))

        // 3. Higher version 250 arrives -> updates to 250
        metaDao.putMonotonicRemoteVersion(key, 250L)
        assertEquals("250", metaDao.get(key))

        // 4. Equal version 250 arrives -> remains 250
        metaDao.putMonotonicRemoteVersion(key, 250L)
        assertEquals("250", metaDao.get(key))
    }

    @Test
    fun testDaoBoundary_ConcurrentWriters_AlwaysConvergesToMax() = runBlocking {
        val metaDao = db.syncMetadataDao()
        val key = "remote_version:ledger:led_concurrent_001"

        // Concurrently dispatch multiple versions in arbitrary order
        val versions = listOf(50L, 200L, 160L, 80L, 190L, 120L, 175L)
        val jobs = versions.map { ver ->
            async(Dispatchers.IO) {
                metaDao.putMonotonicRemoteVersion(key, ver)
            }
        }
        jobs.awaitAll()

        assertEquals("Maximum version (200) must be the final stored value", "200", metaDao.get(key))
    }

    @Test
    fun testRemoteSyncCoordinator_StaleRemoteEventRejected_AfterHigherVersionStored() = runBlocking {
        val accountId = "acc_remote_mono_001"
        val account = LocalAccount(
            id = accountId,
            displayName = "Monotonic Account",
            earthlinkUsername = "mono_user",
            debtIqd = 0.0,
            updatedAt = 200L
        )

        // Remote event with version 200 applies
        val event200 = RemoteEvent.AccountUpsert(
            entityId = accountId,
            remoteVersion = 200L,
            source = RemoteEventSource.REALTIME,
            account = account
        )
        val res200 = coordinator.processEvent(event200)
        assertEquals(EventSyncResult.APPLIED, res200)
        assertEquals("200", db.syncMetadataDao().get("remote_version:account:$accountId"))

        // Remote event with version 160 arrives -> must be rejected as stale duplicate
        val staleAccount = account.copy(displayName = "Stale Modified Name", updatedAt = 160L)
        val event160 = RemoteEvent.AccountUpsert(
            entityId = accountId,
            remoteVersion = 160L,
            source = RemoteEventSource.REALTIME,
            account = staleAccount
        )
        val res160 = coordinator.processEvent(event160)
        assertEquals(EventSyncResult.SKIPPED_DUPLICATE, res160)

        // Stored version remains 200, and local account was NOT modified by stale event
        assertEquals("200", db.syncMetadataDao().get("remote_version:account:$accountId"))
        val storedAcc = db.localAccountDao().getByIdOneShot(accountId)
        assertEquals("Monotonic Account", storedAcc?.displayName)
    }

    @Test
    fun testRemoteSyncCoordinator_EqualVersion_IdempotentNoOp() = runBlocking {
        val accountId = "acc_remote_mono_002"
        val account = LocalAccount(
            id = accountId,
            displayName = "Idempotent Account",
            earthlinkUsername = "idemp_user",
            debtIqd = 0.0,
            updatedAt = 200L
        )

        // Apply version 200
        val event200 = RemoteEvent.AccountUpsert(
            entityId = accountId,
            remoteVersion = 200L,
            source = RemoteEventSource.REALTIME,
            account = account
        )
        val res1 = coordinator.processEvent(event200)
        assertEquals(EventSyncResult.APPLIED, res1)
        assertEquals("200", db.syncMetadataDao().get("remote_version:account:$accountId"))

        // Apply same version 200 again -> SKIPPED_DUPLICATE, not treated as error
        val res200Repeat = coordinator.processEvent(event200)
        assertEquals(EventSyncResult.SKIPPED_DUPLICATE, res200Repeat)
        assertEquals("200", db.syncMetadataDao().get("remote_version:account:$accountId"))
    }
}
