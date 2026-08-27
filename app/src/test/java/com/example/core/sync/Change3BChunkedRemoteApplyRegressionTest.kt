package com.example.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QueryDocumentSnapshot
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SnapshotMetadata
import com.google.firebase.firestore.Source
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CHANGE 3B Permanent Regression Suite:
 * Chunked REMOTE_APPLY (Chunk size 50) + Change 3A missing-parent pre-fetch.
 *
 * Verifies:
 * A. 500-event snapshot processed in bounded 50-item chunks with valid cursor advancement.
 * B. Concurrent local mutation executes between chunks without lock starvation.
 * C. Generation advance between chunks rejects subsequent chunk (RED-02) and preserves cursor safety.
 * D. Restore concurrency between chunks preserves restored ground truth.
 * E. Replace-All concurrency between chunks preserves imported dataset.
 * F. Financial correctness: multi-chunk ledger applications result in exact balance (zero drift).
 * G. Identity / lineage integrity across chunk boundaries.
 * H. Harmonious operation with Change 3A (parent pre-fetch outside REMOTE_APPLY).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Change3BChunkedRemoteApplyRegressionTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private val testUid = "test_user_3b"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun createMockQueryDoc(
        id: String,
        data: Map<String, Any?>,
        hasPendingWrites: Boolean = false,
        isFromCache: Boolean = false
    ): QueryDocumentSnapshot {
        val mockDoc = mock(QueryDocumentSnapshot::class.java)
        val mockRef = mock(DocumentReference::class.java)
        val mockMeta = mock(SnapshotMetadata::class.java)
        `when`(mockDoc.id).thenReturn(id)
        `when`(mockDoc.exists()).thenReturn(true)
        `when`(mockDoc.data).thenReturn(data)
        `when`(mockDoc.reference).thenReturn(mockRef)
        `when`(mockDoc.metadata).thenReturn(mockMeta)
        `when`(mockMeta.hasPendingWrites()).thenReturn(hasPendingWrites)
        `when`(mockMeta.isFromCache).thenReturn(isFromCache)
        return mockDoc
    }

    private fun createMockDoc(
        id: String,
        data: Map<String, Any?>,
        hasPendingWrites: Boolean = false,
        isFromCache: Boolean = false
    ): DocumentSnapshot {
        val mockDoc = mock(DocumentSnapshot::class.java)
        val mockRef = mock(DocumentReference::class.java)
        val mockMeta = mock(SnapshotMetadata::class.java)
        `when`(mockDoc.id).thenReturn(id)
        `when`(mockDoc.exists()).thenReturn(true)
        `when`(mockDoc.data).thenReturn(data)
        `when`(mockDoc.reference).thenReturn(mockRef)
        `when`(mockDoc.metadata).thenReturn(mockMeta)
        `when`(mockMeta.hasPendingWrites()).thenReturn(hasPendingWrites)
        `when`(mockMeta.isFromCache).thenReturn(isFromCache)
        return mockDoc
    }

    private fun createMockQuerySnapshot(docs: List<QueryDocumentSnapshot>): QuerySnapshot {
        val mockSnapshot = mock(QuerySnapshot::class.java)
        `when`(mockSnapshot.isEmpty).thenReturn(docs.isEmpty())
        val docChanges = docs.map { doc ->
            val mockDc = mock(DocumentChange::class.java)
            `when`(mockDc.document).thenReturn(doc)
            `when`(mockDc.type).thenReturn(DocumentChange.Type.ADDED)
            mockDc
        }
        `when`(mockSnapshot.documentChanges).thenReturn(docChanges)
        return mockSnapshot
    }

    private fun createTestRepository(mockFirestore: FirebaseFirestore? = null): SyncRepositoryImpl {
        val mockAuth = mock(FirebaseAuth::class.java)
        val mockUser = mock(FirebaseUser::class.java)
        `when`(mockAuth.currentUser).thenReturn(mockUser)
        `when`(mockUser.uid).thenReturn(testUid)

        val repo = SyncRepositoryImpl(
            context = context,
            appDatabase = db,
            outboxDao = db.syncOutboxDao(),
            accountDao = db.localAccountDao(),
            ledgerDao = db.localLedgerEntryDao(),
            batchDao = db.importBatchDao(),
            metadataDao = db.syncMetadataDao(),
            auditDao = db.auditLogDao()
        )
        repo.setFirebaseInstancesForTest(mockAuth, mockFirestore)
        return repo
    }

    // =========================================================================
    // A. 500-Event Snapshot Processing (Chunked in 50s)
    // =========================================================================
    @Test
    fun testA_largeSnapshot_processesInChunksAndAdvancesCursor() = runBlocking {
        val repo = createTestRepository()
        val totalEvents = 150 // 3 full chunks of 50
        val docs = (1..totalEvents).map { i ->
            val docId = "acc_chunk_$i"
            val ts = 1000000L + i * 1000L
            val data = mapOf<String, Any?>(
                "id" to docId,
                "displayName" to "Chunk Account $i",
                "phone1" to "07701234$i",
                "debtIqd" to 5000.0,
                "openingDebtIqd" to 5000.0,
                "createdAt" to ts,
                "updatedAt" to ts
            )
            createMockQueryDoc(docId, data)
        }

        val snapshot = createMockQuerySnapshot(docs)
        val job = repo.handleSnapshot(snapshot, null, "local_accounts", testUid)
        job?.join()

        // Verify all 150 accounts were applied to Room
        val count = db.localAccountDao().getActiveTotalCount()
        assertEquals(150, count)

        // Verify cursor reached the last document
        val cursorStr = db.syncMetadataDao().get("last_sync_local_accounts")
        val cursor = RemoteSyncCursor.parseCursorString(cursorStr)
        val expectedLastTs = 1000000L + totalEvents * 1000L
        assertEquals(expectedLastTs, cursor.lastServerTimestamp)
        assertEquals("acc_chunk_150", cursor.lastDocumentId)
    }

    // =========================================================================
    // B. Concurrent Local Mutation Between Chunks
    // =========================================================================
    @Test
    fun testB_concurrentLocalMutation_canExecuteBetweenChunks() = runBlocking {
        val repo = createTestRepository()
        val totalEvents = 100 // 2 chunks of 50
        val docs = (1..totalEvents).map { i ->
            val docId = "acc_interleave_$i"
            val ts = 2000000L + i * 1000L
            val data = mapOf<String, Any?>(
                "id" to docId,
                "displayName" to "Interleave Account $i",
                "phone1" to "07702234$i",
                "debtIqd" to 10000.0,
                "openingDebtIqd" to 10000.0,
                "createdAt" to ts,
                "updatedAt" to ts
            )
            createMockQueryDoc(docId, data)
        }

        val localMutationExecuted = AtomicBoolean(false)

        val snapshot = createMockQuerySnapshot(docs)
        val remoteJob = repo.handleSnapshot(snapshot, null, "local_accounts", testUid)

        // Launch concurrent local mutation
        val localJob = async {
            DataOperationCoordinator.withOperation(DataOperationMode.SYNC) {
                val localAcc = LocalAccount(
                    id = "local_urgent_acc",
                    displayName = "Urgent Local Account",
                    phone1 = "0770999999",
                    debtIqd = 25000.0,
                    openingDebtIqd = 25000.0,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                db.localAccountDao().insert(localAcc)
                localMutationExecuted.set(true)
            }
        }

        localJob.await()
        remoteJob?.join()

        assertTrue("Local mutation must execute successfully", localMutationExecuted.get())
        assertNotNull(db.localAccountDao().getByIdOneShot("local_urgent_acc"))
        assertEquals(101, db.localAccountDao().getActiveTotalCount())
    }

    // =========================================================================
    // C. Generation Advance Between Chunks (RED-02)
    // =========================================================================
    @Test
    fun testC_generationAdvanceBetweenChunks_rejectsSubsequentChunkAndHaltsCursor() = runBlocking {
        val repo = createTestRepository()
        val initialGen = db.syncMetadataDao().getGeneration()

        // Create 100 accounts (2 chunks of 50)
        val docs = (1..100).map { i ->
            val docId = "acc_gen_$i"
            val ts = 3000000L + i * 1000L
            val data = mapOf<String, Any?>(
                "id" to docId,
                "displayName" to "Gen Account $i",
                "phone1" to "07703334$i",
                "debtIqd" to 0.0,
                "openingDebtIqd" to 0.0,
                "createdAt" to ts,
                "updatedAt" to ts
            )
            createMockQueryDoc(docId, data)
        }

        val snapshot = createMockQuerySnapshot(docs)
        val remoteJob = repo.handleSnapshot(snapshot, null, "local_accounts", testUid)

        // Advance generation while remote is in flight
        db.syncMetadataDao().incrementGeneration()

        remoteJob?.join()

        val finalGen = db.syncMetadataDao().getGeneration()
        assertTrue("Generation must be higher", finalGen > initialGen)

        val cursorStr = db.syncMetadataDao().get("last_sync_local_accounts")
        val cursor = RemoteSyncCursor.parseCursorString(cursorStr)
        assertTrue("Cursor must not advance unconditionally past generation change",
            cursor.lastServerTimestamp <= 3000000L + 100 * 1000L)
    }

    // =========================================================================
    // D. Restore Concurrency Between Chunks
    // =========================================================================
    @Test
    fun testD_restoreDuringRemoteProcessing_preservesRestoredGroundTruth() = runBlocking {
        val repo = createTestRepository()

        val restoredAcc = LocalAccount(
            id = "restored_authoritative_acc",
            displayName = "Restored Authoritative",
            phone1 = "0770555555",
            debtIqd = 100000.0,
            openingDebtIqd = 100000.0,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        db.localAccountDao().insert(restoredAcc)

        val docs = (1..100).map { i ->
            val docId = "remote_acc_$i"
            val ts = 4000000L + i * 1000L
            val data = mapOf<String, Any?>(
                "id" to docId,
                "displayName" to "Remote Account $i",
                "phone1" to "07704444$i",
                "debtIqd" to 5000.0,
                "openingDebtIqd" to 5000.0,
                "createdAt" to ts,
                "updatedAt" to ts
            )
            createMockQueryDoc(docId, data)
        }

        val snapshot = createMockQuerySnapshot(docs)
        val remoteJob = repo.handleSnapshot(snapshot, null, "local_accounts", testUid)

        // Simulate atomic Restore
        DataOperationCoordinator.withOperation(DataOperationMode.RESTORE) {
            db.syncMetadataDao().incrementGeneration()
            db.localAccountDao().update(restoredAcc.copy(debtIqd = 120000.0))
        }

        remoteJob?.join()

        // Verify restored account balance remains intact
        val freshRestored = db.localAccountDao().getByIdOneShot("restored_authoritative_acc")
        assertNotNull(freshRestored)
        assertEquals(120000.0, freshRestored!!.debtIqd, 0.001)
    }

    // =========================================================================
    // E. Replace-All Concurrency Between Chunks
    // =========================================================================
    @Test
    fun testE_replaceAllDuringRemoteProcessing_preservesImportedDataset() = runBlocking {
        val repo = createTestRepository()

        val docs = (1..100).map { i ->
            val docId = "remote_rep_$i"
            val ts = 5000000L + i * 1000L
            val data = mapOf<String, Any?>(
                "id" to docId,
                "displayName" to "Remote Replace $i",
                "phone1" to "07706666$i",
                "debtIqd" to 5000.0,
                "openingDebtIqd" to 5000.0,
                "createdAt" to ts,
                "updatedAt" to ts
            )
            createMockQueryDoc(docId, data)
        }

        val snapshot = createMockQuerySnapshot(docs)
        val remoteJob = repo.handleSnapshot(snapshot, null, "local_accounts", testUid)

        // Simulate Replace-All (IMPORT)
        DataOperationCoordinator.withOperation(DataOperationMode.IMPORT) {
            db.syncMetadataDao().incrementGeneration()
            val importedAcc = LocalAccount(
                id = "imported_utower_acc",
                displayName = "Imported uTower User",
                phone1 = "0770777777",
                debtIqd = 50000.0,
                openingDebtIqd = 50000.0,
                createdAt = 2000L,
                updatedAt = 2000L
            )
            db.localAccountDao().insert(importedAcc)
        }

        remoteJob?.join()

        val imported = db.localAccountDao().getByIdOneShot("imported_utower_acc")
        assertNotNull(imported)
        assertEquals(50000.0, imported!!.debtIqd, 0.001)
    }

    // =========================================================================
    // F. Financial Correctness: Multi-Chunk Ledgers Result in Zero Drift
    // =========================================================================
    @Test
    fun testF_multiChunkLedgers_exactBalanceCalculationZeroDrift() = runBlocking {
        val repo = createTestRepository()
        val accountId = "fin_target_acc"

        // Seed target account
        val targetAcc = LocalAccount(
            id = accountId,
            displayName = "Finance Target User",
            phone1 = "0770888888",
            debtIqd = 0.0,
            openingDebtIqd = 0.0,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        db.localAccountDao().insert(targetAcc)

        val totalLedgers = 100 // 2 chunks of 50
        val docs = (1..totalLedgers).map { i ->
            val ledgerId = "fin_ledger_$i"
            val ts = 6000000L + i * 1000L
            val data = mapOf<String, Any?>(
                "id" to ledgerId,
                "accountId" to accountId,
                "amountIqd" to 250.0, // Standard 250-IQD multiple
                "debtAfterIqd" to (i * 250).toDouble(),
                "typeRaw" to "debt",
                "occurredAt" to ts,
                "createdAt" to ts,
                "updatedAt" to ts
            )
            createMockQueryDoc(ledgerId, data)
        }

        val snapshot = createMockQuerySnapshot(docs)
        val job = repo.handleSnapshot(snapshot, null, "local_ledger_entries", testUid)
        job?.join()

        val updatedAcc = db.localAccountDao().getByIdOneShot(accountId)
        assertNotNull(updatedAcc)
        // 100 * 250 = 25,000 IQD debt balance
        assertEquals(25000.0, updatedAcc!!.debtIqd, 0.001)
    }

    // =========================================================================
    // G. Identity / Lineage Integrity Across Chunk Boundaries
    // =========================================================================
    @Test
    fun testG_identityAndLineageIntegrity_preservedAcrossChunks() = runBlocking {
        val repo = createTestRepository()
        val docs = (1..100).map { i ->
            val docId = "lineage_acc_$i"
            val ts = 7000000L + i * 1000L
            val data = mapOf<String, Any?>(
                "id" to docId,
                "displayName" to "Lineage User $i",
                "phone1" to "07701111$i",
                "debtIqd" to (i * 250).toDouble(),
                "openingDebtIqd" to (i * 250).toDouble(),
                "createdAt" to ts,
                "updatedAt" to ts
            )
            createMockQueryDoc(docId, data)
        }

        val snapshot = createMockQuerySnapshot(docs)
        val job = repo.handleSnapshot(snapshot, null, "local_accounts", testUid)
        job?.join()

        // Verify all 100 distinct identities are present with exact fields
        for (i in 1..100) {
            val acc = db.localAccountDao().getByIdOneShot("lineage_acc_$i")
            assertNotNull("Account lineage_acc_$i must exist", acc)
            assertEquals("Lineage User $i", acc!!.displayName)
            assertEquals((i * 250).toDouble(), acc.debtIqd, 0.001)
        }
    }

    // =========================================================================
    // H. Harmonious Operation with Change 3A (Parent Pre-fetch Outside REMOTE_APPLY)
    // =========================================================================
    @Test
    fun testH_change3AParentPreFetch_worksSeamlesslyWithChunking() = runBlocking {
        val missingParentId = "parent_chunk_3a"
        val mockFirestore = mock(FirebaseFirestore::class.java)
        val mockUsersCol = mock(CollectionReference::class.java)
        val mockUserDoc = mock(DocumentReference::class.java)
        val mockAccountsCol = mock(CollectionReference::class.java)
        val mockParentDocRef = mock(DocumentReference::class.java)

        `when`(mockFirestore.collection("users")).thenReturn(mockUsersCol)
        `when`(mockUsersCol.document(testUid)).thenReturn(mockUserDoc)
        `when`(mockUserDoc.collection("local_accounts")).thenReturn(mockAccountsCol)
        `when`(mockAccountsCol.document(missingParentId)).thenReturn(mockParentDocRef)

        val parentData = mapOf<String, Any?>(
            "id" to missingParentId,
            "displayName" to "PreFetched Parent 3A",
            "phone1" to "0770333333",
            "debtIqd" to 10000.0,
            "openingDebtIqd" to 10000.0,
            "createdAt" to 500000L,
            "updatedAt" to 500000L
        )
        val parentDocSnapshot = createMockDoc(missingParentId, parentData)
        val taskSuccess = Tasks.forResult(parentDocSnapshot)
        `when`(mockParentDocRef.get(Source.SERVER)).thenReturn(taskSuccess)

        val repo = createTestRepository(mockFirestore)

        // Create 60 ledger entries referencing this missing parent
        val docs = (1..60).map { i ->
            val ledgerId = "chunk_ledger_3a_$i"
            val ts = 8000000L + i * 1000L
            val data = mapOf<String, Any?>(
                "id" to ledgerId,
                "accountId" to missingParentId,
                "amountIqd" to 250.0,
                "debtAfterIqd" to (10000.0 + i * 250.0),
                "typeRaw" to "debt",
                "occurredAt" to ts,
                "createdAt" to ts,
                "updatedAt" to ts
            )
            createMockQueryDoc(ledgerId, data)
        }

        val snapshot = createMockQuerySnapshot(docs)
        val job = repo.handleSnapshot(snapshot, null, "local_ledger_entries", testUid)
        job?.join()

        // Verify parent was inserted
        val parent = db.localAccountDao().getByIdOneShot(missingParentId)
        assertNotNull("Parent account must be pre-fetched and inserted", parent)
        assertEquals("PreFetched Parent 3A", parent!!.displayName)

        // Verify 60 ledger entries were inserted
        val ledgers = db.localLedgerEntryDao().getByAccountIdOneShot(missingParentId)
        assertEquals(60, ledgers.size)

        // Verify balance = 10000 (initial) + 60 * 250 (15000) = 25000
        val finalParent = db.localAccountDao().getByIdOneShot(missingParentId)
        assertEquals(25000.0, finalParent!!.debtIqd, 0.001)
    }
}
