package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.PendingExternalOperation
import com.example.data.repository.LocalAccountRepositoryImpl
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
import java.io.File
import java.util.UUID

/**
 * Claim: WS4 / OUT-01, OUT-05, FIN-03 —
 * 1. Concurrent recordPendingOperation calls for the same subscriber with different intent IDs
 *    must collapse so only one active pending operation exists and claimDispatchAuthorization
 *    succeeds for at most one caller (INV-03 / RED-3).
 * 2. Fractional or non-250 IQD amounts are rejected at repository boundaries (INV-01 / RED-1).
 * Seam: Robolectric in-memory SQLite repository execution.
 * Independent Oracle: Invariants RED-1 (250-IQD multiple) and RED-3 (Atomic dispatch single-writer claim).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class Workstream4AtomicDispatchGuardTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var ledgerRepo: LocalLedgerRepositoryImpl
    private lateinit var accountRepo: LocalAccountRepositoryImpl
    private lateinit var dbFile: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.filesDir, "test_ws4_${UUID.randomUUID()}.db")
        db = Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()

        ledgerRepo = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = db.localLedgerEntryDao(),
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao(),
            pendingDao = db.pendingExternalOperationDao()
        )
        accountRepo = LocalAccountRepositoryImpl(
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao(),
            database = db
        )
    }

    @After
    fun tearDown() {
        db.close()
        if (dbFile.exists()) dbFile.delete()
    }

    @Test
    fun testNon250MultipleAmountsAreRejected() = runBlocking {
        val accountId = "acc_iqd_val"
        val account = LocalAccount(id = accountId, displayName = "IQD Validation Account")
        accountRepo.saveAccount(account)

        // 1. recordPendingOperation rejects non-250 multiple
        try {
            ledgerRepo.recordPendingOperation(
                PendingExternalOperation(
                    accountId = accountId,
                    operationType = "REFILL",
                    amountIqd = 35100L // Not a multiple of 250
                )
            )
            fail("Expected IllegalArgumentException for non-250 IQD pending operation")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("250") == true)
        }

        // 2. addDebt rejects non-250 multiple
        try {
            ledgerRepo.addDebt(accountId, 10123.0, note = "Invalid debt")
            fail("Expected IllegalArgumentException for non-250 IQD debt")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("250") == true)
        }

        // 3. addPayment rejects non-250 multiple
        try {
            ledgerRepo.addPayment(accountId, 5555.0, note = "Invalid payment")
            fail("Expected IllegalArgumentException for non-250 IQD payment")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("250") == true)
        }
    }

    @Test
    fun testConcurrentDispatchAttemptsForSameAccountCollapseToOne() = runBlocking {
        val accountId = "acc_concurrent_dispatch"
        val account = LocalAccount(id = accountId, displayName = "Concurrent Dispatch Account")
        accountRepo.saveAccount(account)

        val txId1 = "tx_1_" + UUID.randomUUID()
        val txId2 = "tx_2_" + UUID.randomUUID()

        val op1 = PendingExternalOperation(
            businessTransactionId = txId1,
            operationIntentId = "intent_concurrent_1",
            accountId = accountId,
            operationType = "REFILL",
            amountIqd = 35000L,
            status = "PENDING"
        )
        val op2 = PendingExternalOperation(
            businessTransactionId = txId2,
            operationIntentId = "intent_concurrent_2",
            accountId = accountId,
            operationType = "REFILL",
            amountIqd = 35000L,
            status = "PENDING"
        )

        // Launch both concurrently
        val results = listOf(
            async(Dispatchers.IO) {
                val recorded = ledgerRepo.recordPendingOperation(op1)
                val claimed = ledgerRepo.claimDispatchAuthorization(txId1)
                recorded to claimed
            },
            async(Dispatchers.IO) {
                val recorded = ledgerRepo.recordPendingOperation(op2)
                val claimed = ledgerRepo.claimDispatchAuthorization(txId2)
                recorded to claimed
            }
        ).awaitAll()

        val claimedCount = results.count { it.second }
        assertEquals("Exactly one caller must acquire dispatch claim authorization", 1, claimedCount)

        val pendingInDb = db.pendingExternalOperationDao().getByAccountId(accountId)
        val activeOps = pendingInDb.filter { it.status in listOf("PENDING", "DISPATCHING", "RESOLVING") }
        assertEquals("Exactly one active pending operation row must exist for this account", 1, activeOps.size)
    }
}
