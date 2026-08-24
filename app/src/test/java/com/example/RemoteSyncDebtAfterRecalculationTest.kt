package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.sync.RemoteEvent
import com.example.core.sync.RemoteEventSource
import com.example.core.sync.RemoteSyncCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for debtAfterIqd recalculation and persistence during RemoteSync reconciliation.
 *
 * Verifies:
 * 1. An earlier remote transaction (T1 at T=1000) inserted via RemoteSync properly updates the running
 *    balance (debtAfterIqd) of existing later transactions (T2 at T=2000).
 * 2. Room local_ledger_entries table contains the updated debtAfterIqd for all transactions.
 * 3. The account debtIqd reflects the exact total (55,000 IQD).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RemoteSyncDebtAfterRecalculationTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var coordinator: RemoteSyncCoordinator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        (context as? EarthlinkApp)?.isSafeDebugFallbackAllowedOverride = true
        context.getDatabasePath("earthlink_reseller_db").parentFile?.mkdirs()
        AppDatabase.closeDatabase()
        database = (context as? EarthlinkApp)?.database ?: AppDatabase.getDatabase(context, ByteArray(0), "earthlink_reseller_db")
        
        coordinator = RemoteSyncCoordinator(
            appDatabase = database,
            accountDao = database.localAccountDao(),
            ledgerDao = database.localLedgerEntryDao(),
            batchDao = database.importBatchDao(),
            outboxDao = database.syncOutboxDao(),
            metadataDao = database.syncMetadataDao(),
            auditDao = database.auditLogDao()
        )

        runBlocking {
            database.localLedgerEntryDao().deleteAll()
            database.localAccountDao().deleteAll()
            database.importBatchDao().deleteAll()
            database.syncOutboxDao().deleteAll()
            database.syncMetadataDao().deleteAll()
            database.auditLogDao().clearAll()
        }
    }

    @After
    fun tearDown() {
        AppDatabase.closeDatabase()
    }

    @Test
    fun testRemoteSyncReconciliation_persistsChronologicalDebtAfterIqd() = runBlocking {
        val accountId = "acc_test_1"
        val initialAccount = LocalAccount(
            id = accountId,
            displayName = "Subscriber Test",
            debtIqd = 25000.0,
            advanceIqd = 0.0,
            openingDebtIqd = 0.0,
            openingAdvanceIqd = 0.0,
            currentPriceIqd = 35000.0,
            updatedAt = 2000L
        )
        database.localAccountDao().insert(initialAccount)

        // Existing local transaction T2 at occurredAt = 2000L with debtAfterIqd = 25000.0
        val localT2 = LocalLedgerEntry(
            id = "tx_local_2",
            accountId = accountId,
            amountIqd = 25000.0,
            typeRaw = "DEBT",
            debtAfterIqd = 25000.0,
            occurredAt = 2000L,
            createdAt = 2000L
        )
        database.localLedgerEntryDao().insert(localT2)

        // Inbound remote transaction T1 at occurredAt = 1000L (earlier) with amount = 30,000.0
        val remoteT1 = LocalLedgerEntry(
            id = "tx_remote_1",
            accountId = accountId,
            amountIqd = 30000.0,
            typeRaw = "DEBT",
            debtAfterIqd = 30000.0,
            occurredAt = 1000L,
            createdAt = 1000L
        )

        val event = RemoteEvent.LedgerUpsert(
            entityId = remoteT1.id,
            remoteVersion = 3000L,
            source = RemoteEventSource.PULL,
            entry = remoteT1
        )

        val result = coordinator.processEvent(event)
        assertEquals(com.example.core.sync.EventSyncResult.APPLIED, result)

        // Verify account balance
        val updatedAccount = database.localAccountDao().getByIdOneShot(accountId)!!
        assertEquals(55000.0, updatedAccount.debtIqd, 0.001)

        // Verify persisted ledger entries in Room
        val persistedT1 = database.localLedgerEntryDao().getByIdOneShot("tx_remote_1")!!
        val persistedT2 = database.localLedgerEntryDao().getByIdOneShot("tx_local_2")!!

        assertEquals(30000.0, persistedT1.debtAfterIqd, 0.001)
        // Before fix: persistedT2.debtAfterIqd was 25000.0 (stale)
        // After fix: persistedT2.debtAfterIqd must be 55000.0 (recalculated and persisted)
        assertEquals("T2 debtAfterIqd must be updated after earlier transaction is applied", 55000.0, persistedT2.debtAfterIqd, 0.001)
    }
}
