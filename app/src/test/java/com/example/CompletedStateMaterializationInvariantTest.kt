package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.core.database.AppDatabase
import com.example.core.database.LocalAccountDao
import com.example.core.database.LocalLedgerEntryDao
import com.example.core.database.PendingExternalOperationDao
import com.example.core.database.SyncOutboxDao
import com.example.core.model.PendingExternalOperation
import com.example.data.repository.LocalLedgerRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CompletedStateMaterializationInvariantTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var pendingDao: PendingExternalOperationDao
    private lateinit var accountDao: LocalAccountDao
    private lateinit var ledgerDao: LocalLedgerEntryDao
    private lateinit var outboxDao: SyncOutboxDao
    private lateinit var ledgerRepository: LocalLedgerRepositoryImpl

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        pendingDao = db.pendingExternalOperationDao()
        accountDao = db.localAccountDao()
        ledgerDao = db.localLedgerEntryDao()
        outboxDao = db.syncOutboxDao()
        ledgerRepository = LocalLedgerRepositoryImpl(
            database = db,
            accountDao = accountDao,
            ledgerDao = ledgerDao,
            pendingDao = pendingDao,
            outboxDao = outboxDao
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun missingFinancialTarget_preventsCompletedState() = runBlocking(Dispatchers.Default) {
        val txId = UUID.randomUUID().toString()
        val op = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = "intent1",
            accountId = "missing_user", // This account doesn't exist in local DB
            operationType = "REFILL",
            amountIqd = 45000L,
            payloadJson = "{}",
            status = "IN_PROGRESS"
        )
        pendingDao.insert(op)

        try {
            ledgerRepository.resolvePendingOperationVerifiedSuccess(txId, null)
            fail("Should have thrown IllegalStateException for missing local account")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("MISSING_LOCAL_FINANCIAL_TARGET"))
        }

        // Verify the status remains IN_PROGRESS and NOT COMPLETED
        val verifyOp = pendingDao.getByBusinessTransactionId(txId)
        assertEquals("IN_PROGRESS", verifyOp?.status)
    }
}
