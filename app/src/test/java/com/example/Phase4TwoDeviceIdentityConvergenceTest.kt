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
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Phase 4 Task P4-08: Two-Device Identity & Convergence Proof.
 *
 * Verifies that:
 * 1. Both devices start from shared initial baseline state.
 * 2. Device 1 creates independent local transaction T2 while offline with stable runtime idempotency identity.
 * 3. Device 2 creates independent local transaction T3 while offline with stable runtime idempotency identity.
 * 4. When syncing and exchanging cloud events (regardless of arrival order), both transactions T2 and T3
 *    are preserved with distinct stable IDs without collapse, collision, or duplicate generation (INV-01 / INV-05 / INV-11).
 * 5. Derived financial balance on both devices converges to the exact identical value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase4TwoDeviceIdentityConvergenceTest {

    private lateinit var context: Context
    private lateinit var dbDevice1: AppDatabase
    private lateinit var dbDevice2: AppDatabase
    private lateinit var coordinator1: RemoteSyncCoordinator
    private lateinit var coordinator2: RemoteSyncCoordinator
    private lateinit var ledgerRepo1: LocalLedgerRepositoryImpl
    private lateinit var ledgerRepo2: LocalLedgerRepositoryImpl
    private lateinit var accountRepo1: LocalAccountRepositoryImpl
    private lateinit var accountRepo2: LocalAccountRepositoryImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbDevice1 = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dbDevice2 = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        coordinator1 = RemoteSyncCoordinator(
            appDatabase = dbDevice1,
            accountDao = dbDevice1.localAccountDao(),
            ledgerDao = dbDevice1.localLedgerEntryDao(),
            outboxDao = dbDevice1.syncOutboxDao(),
            batchDao = dbDevice1.importBatchDao(),
            metadataDao = dbDevice1.syncMetadataDao(),
            auditDao = dbDevice1.auditLogDao()
        )

        coordinator2 = RemoteSyncCoordinator(
            appDatabase = dbDevice2,
            accountDao = dbDevice2.localAccountDao(),
            ledgerDao = dbDevice2.localLedgerEntryDao(),
            outboxDao = dbDevice2.syncOutboxDao(),
            batchDao = dbDevice2.importBatchDao(),
            metadataDao = dbDevice2.syncMetadataDao(),
            auditDao = dbDevice2.auditLogDao()
        )

        ledgerRepo1 = LocalLedgerRepositoryImpl(
            database = dbDevice1,
            ledgerDao = dbDevice1.localLedgerEntryDao(),
            accountDao = dbDevice1.localAccountDao(),
            outboxDao = dbDevice1.syncOutboxDao(),
            pendingDao = dbDevice1.pendingExternalOperationDao()
        )

        ledgerRepo2 = LocalLedgerRepositoryImpl(
            database = dbDevice2,
            ledgerDao = dbDevice2.localLedgerEntryDao(),
            accountDao = dbDevice2.localAccountDao(),
            outboxDao = dbDevice2.syncOutboxDao(),
            pendingDao = dbDevice2.pendingExternalOperationDao()
        )

        accountRepo1 = LocalAccountRepositoryImpl(
            database = dbDevice1,
            accountDao = dbDevice1.localAccountDao(),
            outboxDao = dbDevice1.syncOutboxDao()
        )

        accountRepo2 = LocalAccountRepositoryImpl(
            database = dbDevice2,
            accountDao = dbDevice2.localAccountDao(),
            outboxDao = dbDevice2.syncOutboxDao()
        )
    }

    @After
    fun tearDown() {
        dbDevice1.close()
        dbDevice2.close()
    }

    @Test
    fun testTwoDevice_independentOfflineTransactions_convergeWithPreservedIdentities() = runBlocking {
        val accountId = "acc_conv_408"
        val sharedAccount = LocalAccount(
            id = accountId,
            displayName = "Subscriber Multi-Device",
            openingDebtIqd = 10000.0,
            debtIqd = 10000.0
        )

        // Initial sync: both devices receive baseline account
        dbDevice1.localAccountDao().insert(sharedAccount)
        dbDevice2.localAccountDao().insert(sharedAccount)

        // Device 1 executes offline payment T2 (-4000 IQD)
        val t2 = ledgerRepo1.addPayment(
            accountId = accountId,
            amount = 4000.0,
            note = "Payment from Dev1",
            idempotencyKey = "idemp_dev1_t2"
        )
        assertNotNull(t2)
        assertEquals("idemp_dev1_t2", t2.id)

        // Device 2 executes offline payment T3 (-3000 IQD)
        val t3 = ledgerRepo2.addPayment(
            accountId = accountId,
            amount = 3000.0,
            note = "Payment from Dev2",
            idempotencyKey = "idemp_dev2_t3"
        )
        assertNotNull(t3)
        assertEquals("idemp_dev2_t3", t3.id)

        // Both devices have distinct transaction IDs
        assertNotEquals(t2.id, t3.id)

        // Cloud Relay / Sync Simulation:
        // Device 1 receives T3 via remote sync event
        val eventT3ForDev1 = RemoteEvent.LedgerUpsert(
            entityId = t3.id,
            remoteVersion = 500L,
            source = RemoteEventSource.REALTIME,
            entry = t3
        )
        val res1 = coordinator1.processEvent(eventT3ForDev1)
        assertEquals(EventSyncResult.APPLIED, res1)

        // Device 2 receives T2 via remote sync event
        val eventT2ForDev2 = RemoteEvent.LedgerUpsert(
            entityId = t2.id,
            remoteVersion = 501L,
            source = RemoteEventSource.REALTIME,
            entry = t2
        )
        val res2 = coordinator2.processEvent(eventT2ForDev2)
        assertEquals(EventSyncResult.APPLIED, res2)

        // Verify Device 1 converged state
        val dev1Txs = dbDevice1.localLedgerEntryDao().getByAccountIdOneShot(accountId)
        assertEquals("Device 1 must contain exactly 2 transactions (T2 and T3)", 2, dev1Txs.size)
        val dev1TxIds = dev1Txs.map { it.id }.toSet()
        assertTrue(dev1TxIds.contains("idemp_dev1_t2"))
        assertTrue(dev1TxIds.contains("idemp_dev2_t3"))

        // Verify Device 2 converged state
        val dev2Txs = dbDevice2.localLedgerEntryDao().getByAccountIdOneShot(accountId)
        assertEquals("Device 2 must contain exactly 2 transactions (T2 and T3)", 2, dev2Txs.size)
        val dev2TxIds = dev2Txs.map { it.id }.toSet()
        assertTrue(dev2TxIds.contains("idemp_dev1_t2"))
        assertTrue(dev2TxIds.contains("idemp_dev2_t3"))

        // Verify Financial Convergence: 10000 opening - 4000 (T2) - 3000 (T3) = 3000 remaining debt
        val accDev1 = dbDevice1.localAccountDao().getByIdOneShot(accountId)
        val accDev2 = dbDevice2.localAccountDao().getByIdOneShot(accountId)

        assertEquals(3000.0, accDev1?.debtIqd ?: 0.0, 0.001)
        assertEquals(3000.0, accDev2?.debtIqd ?: 0.0, 0.001)
    }

    @Test
    fun testTwoDevice_reconnectOrderInvariance() = runBlocking {
        val accountId = "acc_order_inv"
        val sharedAccount = LocalAccount(
            id = accountId,
            displayName = "Order Invariant Account",
            openingDebtIqd = 20000.0,
            debtIqd = 20000.0
        )

        dbDevice1.localAccountDao().insert(sharedAccount)

        val txA = LocalLedgerEntry(id = "tx_ord_a", accountId = accountId, amountIqd = 5000.0, debtAfterIqd = 15000.0, typeRaw = "gave", occurredAt = 1000L)
        val txB = LocalLedgerEntry(id = "tx_ord_b", accountId = accountId, amountIqd = 3000.0, debtAfterIqd = 12000.0, typeRaw = "gave", occurredAt = 2000L)

        // Apply in reverse order (txB first, then txA)
        coordinator1.processEvent(RemoteEvent.LedgerUpsert(entityId = "tx_ord_b", remoteVersion = 101L, source = RemoteEventSource.PULL, entry = txB))
        coordinator1.processEvent(RemoteEvent.LedgerUpsert(entityId = "tx_ord_a", remoteVersion = 102L, source = RemoteEventSource.PULL, entry = txA))

        val accAfter = dbDevice1.localAccountDao().getByIdOneShot(accountId)
        // 20000 opening - 5000 (gave) - 3000 (gave) = 12000 debt
        assertEquals(12000.0, accAfter?.debtIqd ?: 0.0, 0.001)

        val allTxs = dbDevice1.localLedgerEntryDao().getByAccountIdOneShot(accountId)
        assertEquals(2, allTxs.size)
    }
}
