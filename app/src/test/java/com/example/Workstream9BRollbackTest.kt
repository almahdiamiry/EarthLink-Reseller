package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.*
import com.example.data.repository.AuditRepositoryImpl
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.data.repository.UtowerImportRepositoryImpl
import com.example.domain.repository.AuditRepository
import com.example.domain.repository.SyncReason
import com.example.domain.repository.SyncRepository
import com.example.domain.repository.SyncStatusState
import com.example.domain.repository.UtowerImportRepository
import com.example.ui.viewmodels.LocalAccountsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Workstream9BRollbackTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var auditRepo: AuditRepository
    private lateinit var utowerRepo: UtowerImportRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        auditRepo = AuditRepositoryImpl(db, db.auditLogDao())
        utowerRepo = UtowerImportRepositoryImpl(
            context = context,
            database = db,
            batchDao = db.importBatchDao(),
            accountDao = db.localAccountDao(),
            ledgerDao = db.localLedgerEntryDao(),
            outboxDao = db.syncOutboxDao(),
            auditRepo = auditRepo
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun testRollbackUnacceptedBatchRemovesTemporaryLocalRecordsAndEmitsNoTombstones() = runBlocking {
        val batchId = UUID.randomUUID().toString()
        val unacceptedBatch = ImportBatch(
            id = batchId,
            fileName = "unaccepted_import.tgz",
            fileHash = "hash123",
            accountsImported = 1,
            transactionsImported = 1,
            totalDebtIqd = 50000.0,
            status = "in_progress", // Unaccepted / in progress
            createdAt = System.currentTimeMillis()
        )
        db.importBatchDao().insert(unacceptedBatch)

        val accountId = UUID.randomUUID().toString()
        val tempAccount = LocalAccount(
            id = accountId,
            earthlinkUsername = "temp_user",
            phone1 = "07700000000",
            debtIqd = 50000.0,
            sourceExternalId = "sub_temp_1",
            sourceBatchId = batchId,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        db.localAccountDao().insert(tempAccount)

        val txId = UUID.randomUUID().toString()
        val tempTx = LocalLedgerEntry(
            id = txId,
            accountId = accountId,
            sourceExternalId = "tx_temp_1",
            sourceBatchId = batchId,
            typeRaw = "took",
            amountIqd = 50000.0,
            debtAfterIqd = 50000.0,
            occurredAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            isSnapshotHistory = false
        )
        db.localLedgerEntryDao().insert(tempTx)

        // Add temporary outbox entries
        db.syncOutboxDao().insert(SyncOutbox(
            entityType = "local_accounts",
            entityId = accountId,
            operation = "upsert",
            payloadJson = "{}",
            importBatchId = batchId
        ))
        db.syncOutboxDao().insert(SyncOutbox(
            entityType = "local_ledger_entries",
            entityId = txId,
            operation = "upsert",
            payloadJson = "{}",
            importBatchId = batchId
        ))
        db.syncOutboxDao().insert(SyncOutbox(
            entityType = "import_batches",
            entityId = batchId,
            operation = "upsert",
            payloadJson = "{}",
            importBatchId = batchId
        ))

        // Execute rollback on unaccepted batch
        val success = utowerRepo.rollbackImportBatch(batchId)
        assertTrue("Rollback on unaccepted batch must succeed", success)

        // Verify local temporary records removed
        assertNull("Temporary batch must be removed locally", db.importBatchDao().getById(batchId))
        assertEquals("Temporary tx must be removed locally", 0, db.localLedgerEntryDao().getByBatchId(batchId).size)
        assertNull("Temporary tx must not exist in DB", db.localLedgerEntryDao().getByIdOneShot(txId))
        assertNull("Temporary account with no other transactions must be removed locally", db.localAccountDao().getByIdOneShot(accountId))

        // Verify WS9B Rule: No delete tombstones emitted in outbox for temporary unaccepted records
        val pendingOutbox = db.syncOutboxDao().getPending()
        val deleteTombstones = pendingOutbox.filter { it.operation == "delete" }
        assertEquals("Must NOT emit delete tombstones for rolled back unaccepted batch", 0, deleteTombstones.size)
        assertEquals("Outbox must be empty after unaccepted batch rollback", 0, pendingOutbox.size)
    }

    @Test
    fun testRollbackAcceptedBatchIsBlockedAndPreservesHistory() = runBlocking {
        val batchId = UUID.randomUUID().toString()
        val acceptedBatch = ImportBatch(
            id = batchId,
            fileName = "accepted_import.tgz",
            fileHash = "hash456",
            accountsImported = 1,
            transactionsImported = 1,
            totalDebtIqd = 25000.0,
            status = "completed", // Accepted into canonical history
            createdAt = System.currentTimeMillis()
        )
        db.importBatchDao().insert(acceptedBatch)

        val accountId = UUID.randomUUID().toString()
        val account = LocalAccount(
            id = accountId,
            earthlinkUsername = "perm_user",
            phone1 = "07711111111",
            debtIqd = 25000.0,
            sourceExternalId = "sub_perm_1",
            sourceBatchId = batchId,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        db.localAccountDao().insert(account)

        val txId = UUID.randomUUID().toString()
        val tx = LocalLedgerEntry(
            id = txId,
            accountId = accountId,
            sourceExternalId = "tx_perm_1",
            sourceBatchId = batchId,
            typeRaw = "took",
            amountIqd = 25000.0,
            debtAfterIqd = 25000.0,
            occurredAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            isSnapshotHistory = true
        )
        db.localLedgerEntryDao().insert(tx)

        // Attempt rollback on accepted batch
        val success = utowerRepo.rollbackImportBatch(batchId)
        assertFalse("Rollback on accepted batch MUST be rejected", success)

        // Verify accepted business records and financial history are strictly preserved
        assertNotNull("Accepted batch record must remain intact", db.importBatchDao().getById(batchId))
        assertNotNull("Accepted account record must remain intact", db.localAccountDao().getByIdOneShot(accountId))
        assertNotNull("Accepted financial ledger history must remain intact", db.localLedgerEntryDao().getByIdOneShot(txId))

        // Verify no delete tombstones in outbox
        val pendingOutbox = db.syncOutboxDao().getPending()
        val deleteTombstones = pendingOutbox.filter { it.operation == "delete" }
        assertEquals("Must NOT emit delete tombstones", 0, deleteTombstones.size)
    }

    @Test
    fun testViewModelRollbackSemanticsAndErrorFeedback() = runBlocking {
        val mockSyncRepo = object : SyncRepository {
            private val _state = MutableStateFlow(SyncStatusState.IDLE)
            override val syncState: StateFlow<SyncStatusState> = _state.asStateFlow()
            override fun triggerSync() {}
            override fun setupPeriodicSync() {}
            override suspend fun triggerSyncOneShot(): Boolean = true
            override fun requestSync(reason: SyncReason) {}
            override fun triggerSettingsSync(uid: String?, reason: String) {}
            override suspend fun getPendingOutboxCount(): Int = 0
            override suspend fun getFailedCount(): Int = 0
            override suspend fun retryFailedItems(): Int = 0
            override suspend fun anonymousSignIn(): String? = null
            override suspend fun emailSignIn(email: String, password: String): String? = null
            override suspend fun googleSignIn(idToken: String): String? = null
            override fun getFirebaseUid(): String? = null
            override suspend fun signOut(force: Boolean, clearData: Boolean) {}
        }

        val accountRepo = LocalAccountRepositoryImpl(db, db.localAccountDao(), db.syncOutboxDao())
        val ledgerRepo = LocalLedgerRepositoryImpl(db, db.localLedgerEntryDao(), db.localAccountDao(), db.syncOutboxDao())
        val viewModel = LocalAccountsViewModel(
            localRepo = accountRepo,
            ledgerRepo = ledgerRepo,
            utowerRepo = utowerRepo,
            audit = auditRepo,
            syncRepo = mockSyncRepo,
            appDatabase = db
        )

        // 1. Rollback non-existent batch
        viewModel.rollbackBatch("missing-id").join()
        assertNotNull(viewModel.error.value)
        assertTrue(viewModel.error.value!!.contains("Cannot rollback import batch"))

        // 2. Rollback accepted batch
        val acceptedBatchId = UUID.randomUUID().toString()
        db.importBatchDao().insert(ImportBatch(
            id = acceptedBatchId,
            fileName = "perm.tgz",
            fileHash = "h1",
            accountsImported = 1,
            transactionsImported = 1,
            totalDebtIqd = 100.0,
            status = "completed"
        ))
        viewModel.rollbackBatch(acceptedBatchId).join()
        assertNotNull(viewModel.error.value)
        assertTrue(viewModel.error.value!!.contains("Cannot rollback import batch"))

        // 3. Rollback unaccepted batch
        val unacceptedBatchId = UUID.randomUUID().toString()
        db.importBatchDao().insert(ImportBatch(
            id = unacceptedBatchId,
            fileName = "unacc.tgz",
            fileHash = "h2",
            accountsImported = 1,
            transactionsImported = 1,
            totalDebtIqd = 100.0,
            status = "in_progress"
        ))
        viewModel.rollbackBatch(unacceptedBatchId).join()
        assertNull(db.importBatchDao().getById(unacceptedBatchId))
    }
}
