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
import com.example.data.repository.LocalLedgerRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Workstream 15 Certification Test: Coordinator / Transport Concurrency & Eventual Financial Coherence.
 *
 * Verifies:
 * 1. Ordering A: Local mutation entered first, followed by Remote event application via RemoteSyncCoordinator.
 * 2. Ordering B: Remote event applied first via RemoteSyncCoordinator, followed by Local repository mutation.
 * 3. Genuinely concurrent interleaved dispatch across thread boundaries.
 * 4. Eventual financial invariant: Local ledger calculation and remote balance adjustments settle to
 *    exact deterministic mathematical equilibrium without loss of outbox obligations or duplicate mutations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Workstream15CoordinatorTransportConcurrencyTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var coordinator: RemoteSyncCoordinator
    private lateinit var ledgerRepository: LocalLedgerRepositoryImpl

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

        ledgerRepository = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = db.localLedgerEntryDao(),
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testOrderingA_LocalMutationFirst_ThenRemoteEventApplied() = runBlocking {
        val accountId = "acc_order_a"
        val account = LocalAccount(
            id = accountId,
            displayName = "Ordering A User",
            earthlinkUsername = accountId,
            openingDebtIqd = 10000.0,
            debtIqd = 10000.0,
            updatedAt = 100L
        )
        db.localAccountDao().insert(account)

        // 1. Local mutation: add debt of 25,000 IQD
        val localEntry = ledgerRepository.addDebt(
            accountId = accountId,
            amount = 25000.0,
            note = "Local Charge"
        )
        assertNotNull(localEntry)

        // Mid-state: debt is 35,000 IQD
        val midAcc = db.localAccountDao().getByIdOneShot(accountId)
        assertEquals(35000.0, midAcc?.debtIqd ?: 0.0, 0.001)

        // 2. Remote event: remote payment of 15,000 IQD arrives
        val remoteEntry = LocalLedgerEntry(
            id = "rem_tx_001",
            accountId = accountId,
            typeRaw = "gave",
            amountIqd = 15000.0,
            debtAfterIqd = 0.0,
            occurredAt = 200L
        )
        val event = RemoteEvent.LedgerUpsert(
            entityId = remoteEntry.id,
            remoteVersion = 200L,
            source = RemoteEventSource.REALTIME,
            entry = remoteEntry
        )

        val result = coordinator.processEvent(event)
        assertEquals(EventSyncResult.APPLIED, result)

        // Settled state:
        // Opening = 10,000 + Local Took 25,000 - Remote Gave 15,000 = 20,000 IQD
        val settledAcc = db.localAccountDao().getByIdOneShot(accountId)
        assertEquals("Settled balance must equal 20,000 IQD", 20000.0, settledAcc?.debtIqd ?: 0.0, 0.001)

        val ledgers = db.localLedgerEntryDao().getByAccountIdOneShot(accountId)
        assertEquals("Both local and remote ledgers must be present", 2, ledgers.size)
        assertTrue(ledgers.any { it.id == localEntry.id })
        assertTrue(ledgers.any { it.id == remoteEntry.id })

        // Outbox must contain local entry obligation
        val outbox = db.syncOutboxDao().getPending()
        assertTrue("Outbox must hold local mutation obligation", outbox.any { it.entityId == localEntry.id })
    }

    @Test
    fun testOrderingB_RemoteEventFirst_ThenLocalMutationApplied() = runBlocking {
        val accountId = "acc_order_b"
        val account = LocalAccount(
            id = accountId,
            displayName = "Ordering B User",
            earthlinkUsername = accountId,
            openingDebtIqd = 10000.0,
            debtIqd = 10000.0,
            updatedAt = 100L
        )
        db.localAccountDao().insert(account)

        // 1. Remote event: remote debt addition of 30,000 IQD arrives
        val remoteEntry = LocalLedgerEntry(
            id = "rem_tx_002",
            accountId = accountId,
            typeRaw = "took",
            amountIqd = 30000.0,
            debtAfterIqd = 0.0,
            occurredAt = 150L
        )
        val event = RemoteEvent.LedgerUpsert(
            entityId = remoteEntry.id,
            remoteVersion = 150L,
            source = RemoteEventSource.REALTIME,
            entry = remoteEntry
        )

        val result = coordinator.processEvent(event)
        assertEquals(EventSyncResult.APPLIED, result)

        // Mid-state: 10,000 + 30,000 = 40,000 IQD
        val midAcc = db.localAccountDao().getByIdOneShot(accountId)
        assertEquals(40000.0, midAcc?.debtIqd ?: 0.0, 0.001)

        // 2. Local mutation: local payment of 12,000 IQD
        val localEntry = ledgerRepository.addPayment(
            accountId = accountId,
            amount = 12000.0,
            note = "Local Payment"
        )
        assertNotNull(localEntry)

        // Settled state:
        // 10,000 + Remote Took 30,000 - Local Gave 12,000 = 28,000 IQD
        val settledAcc = db.localAccountDao().getByIdOneShot(accountId)
        assertEquals("Settled balance must equal 28,000 IQD", 28000.0, settledAcc?.debtIqd ?: 0.0, 0.001)

        val ledgers = db.localLedgerEntryDao().getByAccountIdOneShot(accountId)
        assertEquals(2, ledgers.size)
        assertTrue(ledgers.any { it.id == localEntry.id })
        assertTrue(ledgers.any { it.id == remoteEntry.id })

        val outbox = db.syncOutboxDao().getPending()
        assertTrue("Outbox must hold local payment obligation", outbox.any { it.entityId == localEntry.id })
    }

    @Test
    fun testConcurrentInterleaved_LocalAndRemoteExecution() = runBlocking {
        val accountId = "acc_concurrent_01"
        val account = LocalAccount(
            id = accountId,
            displayName = "Concurrent User",
            earthlinkUsername = accountId,
            openingDebtIqd = 50000.0,
            debtIqd = 50000.0,
            updatedAt = 100L
        )
        db.localAccountDao().insert(account)

        val remoteEntry = LocalLedgerEntry(
            id = "rem_concurrent_tx",
            accountId = accountId,
            typeRaw = "gave",
            amountIqd = 20000.0,
            debtAfterIqd = 0.0,
            occurredAt = 200L
        )
        val event = RemoteEvent.LedgerUpsert(
            entityId = remoteEntry.id,
            remoteVersion = 200L,
            source = RemoteEventSource.REALTIME,
            entry = remoteEntry
        )

        // Launch concurrent tasks on Dispatchers.Default
        val jobLocal = async(Dispatchers.Default) {
            ledgerRepository.addDebt(
                accountId = accountId,
                amount = 15000.0,
                note = "Concurrent Local Debt"
            )
        }

        val jobRemote = async(Dispatchers.Default) {
            coordinator.processEvent(event)
        }

        val results = awaitAll(jobLocal, jobRemote)
        assertNotNull(results[0])
        assertEquals(EventSyncResult.APPLIED, results[1])

        // Settled state invariant:
        // Opening (50,000) + Local Took (15,000) - Remote Gave (20,000) = 45,000 IQD
        val finalAcc = db.localAccountDao().getByIdOneShot(accountId)
        assertEquals("Concurrent operations must settle to deterministic balance of 45,000 IQD", 45000.0, finalAcc?.debtIqd ?: 0.0, 0.001)

        val finalLedgers = db.localLedgerEntryDao().getByAccountIdOneShot(accountId)
        assertEquals(2, finalLedgers.size)
    }
}
