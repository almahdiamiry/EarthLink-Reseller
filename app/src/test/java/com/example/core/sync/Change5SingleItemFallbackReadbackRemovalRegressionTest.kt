package com.example.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.EarthlinkApp
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.security.PreferenceManager
import com.example.domain.repository.SyncStatusState
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QueryDocumentSnapshot
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SnapshotMetadata
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.WriteBatch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * CHANGE 5 Regression Test Suite:
 * Permanent Removal of Single-Item Fallback Server Read-Back (confirmRemoteVersionReadBack).
 *
 * Verifies:
 * 1. Successful single-item fallback calls ZERO read-backs (get(Source.SERVER) count == 0 on docRef).
 * 2. Successful single-item fallback removes/acknowledges Outbox normally (markSucceeded).
 * 3. Read-back failure cannot create a false failure because no read-back exists.
 * 4. Pull recovers authoritative remote_version after successful fallback push.
 * 5. Realtime listener recovers authoritative remote_version after successful fallback push.
 * 6. Genuine write failure remains retryable (marked FAILED_RETRYABLE in Outbox).
 * 7. Uncertain write recovery remains safe via durable Outbox + idempotent set(merge).
 * 8. Duplicate logical mutation is not created across retries or reconciliation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Change5SingleItemFallbackReadbackRemovalRegressionTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var syncRepository: SyncRepositoryImpl
    private val testUid = "test_reseller_user_ch5"
    private val mockDocRefs = mutableMapOf<String, DocumentReference>()
    private val docGetServerCallCount = AtomicInteger(0)

    @Before
    fun setup() {
        runBlocking {
            mockDocRefs.clear()
            docGetServerCallCount.set(0)
            context = ApplicationProvider.getApplicationContext()
            (context as? EarthlinkApp)?.isSafeDebugFallbackAllowedOverride = true
            context.getDatabasePath("earthlink_reseller_ch5_db").parentFile?.mkdirs()
            AppDatabase.closeDatabase()
            db = (context as? EarthlinkApp)?.database ?: AppDatabase.getDatabase(context, ByteArray(0), "earthlink_reseller_ch5_db")

            db.localLedgerEntryDao().deleteAll()
            db.localAccountDao().deleteAll()
            db.importBatchDao().deleteAll()
            db.syncOutboxDao().deleteAll()
            db.syncMetadataDao().deleteAll()
            db.auditLogDao().clearAll()

            val prefManager = PreferenceManager(context)
            prefManager.saveAuthToken("mock_token")
            prefManager.saveIspAdminUsername("user")
            prefManager.saveIspAdminPassword("pass")
            prefManager.clearSettingsLocalMutation()

            syncRepository = SyncRepositoryImpl(
                context = context,
                appDatabase = db,
                outboxDao = db.syncOutboxDao(),
                accountDao = db.localAccountDao(),
                ledgerDao = db.localLedgerEntryDao(),
                batchDao = db.importBatchDao(),
                metadataDao = db.syncMetadataDao(),
                auditDao = db.auditLogDao()
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
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

    private fun createMockFirebase(
        accountsDocs: List<DocumentSnapshot> = emptyList(),
        ledgersDocs: List<DocumentSnapshot> = emptyList(),
        batchesDocs: List<DocumentSnapshot> = emptyList(),
        batchShouldFail: Boolean = false,
        singleItemSetFailDocIds: Set<String> = emptySet(),
        getShouldThrow: Boolean = false
    ): Triple<FirebaseAuth, FirebaseFirestore, WriteBatch> {
        val mockAuth = mock(FirebaseAuth::class.java)
        val mockUser = mock(FirebaseUser::class.java)
        `when`(mockAuth.currentUser).thenReturn(mockUser)
        `when`(mockUser.uid).thenReturn(testUid)

        val mockFirestore = mock(FirebaseFirestore::class.java)
        val mockUsersCollection = mock(CollectionReference::class.java)
        val mockUserDoc = mock(DocumentReference::class.java)

        `when`(mockFirestore.collection("users")).thenReturn(mockUsersCollection)
        `when`(mockUsersCollection.document(testUid)).thenReturn(mockUserDoc)

        val mockAccountsColl = mock(CollectionReference::class.java)
        val mockLedgersColl = mock(CollectionReference::class.java)
        val mockBatchesColl = mock(CollectionReference::class.java)
        val mockAuditsColl = mock(CollectionReference::class.java)

        `when`(mockUserDoc.collection("local_accounts")).thenReturn(mockAccountsColl)
        `when`(mockUserDoc.collection("local_ledger_entries")).thenReturn(mockLedgersColl)
        `when`(mockUserDoc.collection("import_batches")).thenReturn(mockBatchesColl)
        `when`(mockUserDoc.collection("audit_logs")).thenReturn(mockAuditsColl)

        val accountsQuerySnapshot = mock(QuerySnapshot::class.java)
        `when`(accountsQuerySnapshot.documents).thenReturn(accountsDocs)
        `when`(accountsQuerySnapshot.isEmpty).thenReturn(accountsDocs.isEmpty())

        val ledgersQuerySnapshot = mock(QuerySnapshot::class.java)
        `when`(ledgersQuerySnapshot.documents).thenReturn(ledgersDocs)
        `when`(ledgersQuerySnapshot.isEmpty).thenReturn(ledgersDocs.isEmpty())

        val batchesQuerySnapshot = mock(QuerySnapshot::class.java)
        `when`(batchesQuerySnapshot.documents).thenReturn(batchesDocs)
        `when`(batchesQuerySnapshot.isEmpty).thenReturn(batchesDocs.isEmpty())

        val auditsQuerySnapshot = mock(QuerySnapshot::class.java)
        `when`(auditsQuerySnapshot.documents).thenReturn(emptyList())
        `when`(auditsQuerySnapshot.isEmpty).thenReturn(true)

        setupCollectionQueryMock(mockAccountsColl, accountsQuerySnapshot)
        setupCollectionQueryMock(mockLedgersColl, ledgersQuerySnapshot)
        setupCollectionQueryMock(mockBatchesColl, batchesQuerySnapshot)
        setupCollectionQueryMock(mockAuditsColl, auditsQuerySnapshot)

        val answerDocRef = { invocation: org.mockito.invocation.InvocationOnMock ->
            val id = invocation.getArgument<String>(0)
            mockDocRefs.getOrPut(id) {
                val r = mock(DocumentReference::class.java)
                if (singleItemSetFailDocIds.contains(id)) {
                    `when`(r.set(any(), any())).thenReturn(Tasks.forException(Exception("Single item set failure for $id")))
                } else {
                    `when`(r.set(any(), any())).thenReturn(Tasks.forResult(null))
                }

                if (getShouldThrow) {
                    `when`(r.get(any(Source::class.java))).thenAnswer {
                        docGetServerCallCount.incrementAndGet()
                        Tasks.forException<DocumentSnapshot>(Exception("Simulated read-back network timeout / failure"))
                    }
                    `when`(r.get()).thenAnswer {
                        docGetServerCallCount.incrementAndGet()
                        Tasks.forException<DocumentSnapshot>(Exception("Simulated read-back network timeout / failure"))
                    }
                } else {
                    val fallback = mock(DocumentSnapshot::class.java)
                    `when`(fallback.exists()).thenReturn(false)
                    `when`(r.get(any(Source::class.java))).thenAnswer {
                        docGetServerCallCount.incrementAndGet()
                        Tasks.forResult(fallback)
                    }
                    `when`(r.get()).thenAnswer {
                        docGetServerCallCount.incrementAndGet()
                        Tasks.forResult(fallback)
                    }
                }
                r
            }
        }

        `when`(mockAccountsColl.document(anyString())).thenAnswer(answerDocRef)
        `when`(mockLedgersColl.document(anyString())).thenAnswer(answerDocRef)
        `when`(mockBatchesColl.document(anyString())).thenAnswer(answerDocRef)

        val mockBatch = mock(WriteBatch::class.java)
        if (batchShouldFail) {
            `when`(mockBatch.commit()).thenReturn(Tasks.forException(Exception("Simulated batch write error to trigger fallback")))
        } else {
            `when`(mockBatch.commit()).thenReturn(Tasks.forResult(null))
        }
        `when`(mockFirestore.batch()).thenReturn(mockBatch)

        return Triple(mockAuth, mockFirestore, mockBatch)
    }

    private fun setupCollectionQueryMock(coll: CollectionReference, querySnapshot: QuerySnapshot) {
        val q1 = mock(Query::class.java)
        val q2 = mock(Query::class.java)
        val q3 = mock(Query::class.java)
        val q4 = mock(Query::class.java)

        `when`(coll.orderBy("updatedAt")).thenReturn(q1)
        `when`(q1.orderBy(FieldPath.documentId())).thenReturn(q2)
        `when`(q2.limit(500)).thenReturn(q3)
        `when`(q3.get(Source.SERVER)).thenReturn(Tasks.forResult(querySnapshot))
        `when`(q3.startAfter(any())).thenReturn(q4)
        `when`(q3.startAfter(any(java.util.Date::class.java), anyString())).thenReturn(q4)
        `when`(q3.startAt(any(java.util.Date::class.java))).thenReturn(q4)

        val emptySnapshot = mock(QuerySnapshot::class.java)
        `when`(emptySnapshot.documents).thenReturn(emptyList())
        `when`(emptySnapshot.isEmpty).thenReturn(true)
        `when`(q4.get(Source.SERVER)).thenReturn(Tasks.forResult(emptySnapshot))
    }

    // =========================================================================
    // 1 & 2. ZERO READ-BACKS + NORMAL OUTBOX ACKNOWLEDGMENT ON FALLBACK SUCCESS
    // =========================================================================
    @Test
    fun test1And2_successfulSingleItemFallback_callsZeroReadBacksAndAcknowledgesOutbox() = runBlocking {
        // Seed 3 accounts locally
        db.localAccountDao().insert(LocalAccount(id = "acc_fb_1", displayName = "FB User 1", phone1 = "0770111111", debtIqd = 5000.0))
        db.localAccountDao().insert(LocalAccount(id = "acc_fb_2", displayName = "FB User 2", phone1 = "0770222222", debtIqd = 5000.0))
        db.localAccountDao().insert(LocalAccount(id = "acc_fb_3", displayName = "FB User 3", phone1 = "0770333333", debtIqd = 5000.0))

        // Enqueue 3 outbox updates
        OutboxManager.enqueue(db.syncOutboxDao(), "local_accounts", "acc_fb_1", "upsert", "{\"displayName\":\"FB User 1\"}")
        OutboxManager.enqueue(db.syncOutboxDao(), "local_accounts", "acc_fb_2", "upsert", "{\"displayName\":\"FB User 2\"}")
        OutboxManager.enqueue(db.syncOutboxDao(), "local_accounts", "acc_fb_3", "upsert", "{\"displayName\":\"FB User 3\"}")

        // Force batch failure to enter single-item fallback path
        val (mockAuth, mockFirestore, _) = createMockFirebase(
            batchShouldFail = true,
            singleItemSetFailDocIds = emptySet() // single item sets all succeed
        )
        syncRepository.setFirebaseInstancesForTest(mockAuth, mockFirestore)

        val success = syncRepository.triggerSyncOneShot()
        assertTrue("Sync should complete successfully after fallback", success)

        // 1. Explicit assertion: confirmRemoteVersionReadBack invocation count == 0 (no get(Source.SERVER) calls on individual docRefs)
        assertEquals("Fallback must perform ZERO server read-back calls", 0, docGetServerCallCount.get())
        for ((id, mockRef) in mockDocRefs) {
            verify(mockRef, never()).get(Source.SERVER)
            verify(mockRef, never()).get()
        }

        // 2. Outbox items successfully acknowledged and deleted
        val remaining = db.syncOutboxDao().getAllOneShot()
        assertTrue("Outbox must be completely empty after successful fallback", remaining.isEmpty())
    }

    // =========================================================================
    // 3. READ-BACK FAILURE CANNOT CREATE FALSE FAILURE
    // =========================================================================
    @Test
    fun test3_readbackFailureCannotCreateFalseFailureBecauseNoReadbackExists() = runBlocking {
        db.localAccountDao().insert(LocalAccount(id = "acc_safe_1", displayName = "Safe User", phone1 = "0770444444", debtIqd = 10000.0))
        OutboxManager.enqueue(db.syncOutboxDao(), "local_accounts", "acc_safe_1", "upsert", "{\"displayName\":\"Safe User\"}")

        // Configure mock such that get() would throw if called, and batch fails to trigger fallback
        val (mockAuth, mockFirestore, _) = createMockFirebase(
            batchShouldFail = true,
            singleItemSetFailDocIds = emptySet(),
            getShouldThrow = true // If read-back existed, this would fail the item push
        )
        syncRepository.setFirebaseInstancesForTest(mockAuth, mockFirestore)

        val success = syncRepository.triggerSyncOneShot()
        assertTrue("Sync should succeed because no read-back is invoked to trigger false failure", success)
        assertEquals(SyncStatusState.COMPLETE, syncRepository.syncState.value)

        // Verify outbox was successfully acknowledged
        val remaining = db.syncOutboxDao().getAllOneShot()
        assertTrue("Outbox item must be cleared", remaining.isEmpty())
    }

    // =========================================================================
    // 4. PULL RECOVERS REMOTE_VERSION AFTER FALLBACK
    // =========================================================================
    @Test
    fun test4_pullRecoversRemoteVersionAfterSuccessfulFallback() = runBlocking {
        db.localAccountDao().insert(LocalAccount(id = "acc_pull_rec", displayName = "Pull Rec User", phone1 = "0770555555", debtIqd = 15000.0))
        OutboxManager.enqueue(db.syncOutboxDao(), "local_accounts", "acc_pull_rec", "upsert", "{\"displayName\":\"Pull Rec User Updated\",\"syncMutationId\":\"mut_pull_1\"}")

        val serverTs = 1750000000123L
        val pulledDoc = createMockDoc(
            id = "acc_pull_rec",
            data = mapOf(
                "displayName" to "Pull Rec User Updated",
                "syncMutationId" to "mut_pull_1",
                "updatedAt" to serverTs,
                "deletedAt" to null
            )
        )

        val (mockAuth, mockFirestore, _) = createMockFirebase(
            batchShouldFail = true,
            accountsDocs = listOf(pulledDoc)
        )
        syncRepository.setFirebaseInstancesForTest(mockAuth, mockFirestore)

        val success = syncRepository.triggerSyncOneShot()
        assertTrue(success)

        // Verify Pull phase recorded the authoritative remote_version
        val recordedVersion = db.syncMetadataDao().get("remote_version:account:acc_pull_rec")
        assertEquals(serverTs.toString(), recordedVersion)
    }

    // =========================================================================
    // 5. REALTIME RECOVERS REMOTE_VERSION AFTER FALLBACK
    // =========================================================================
    @Test
    fun test5_realtimeRecoversRemoteVersionAfterSuccessfulFallback() = runBlocking {
        val accountId = "acc_rt_rec"
        db.localAccountDao().insert(LocalAccount(id = accountId, displayName = "RT Rec User", phone1 = "0770666666", debtIqd = 20000.0))
        OutboxManager.enqueue(db.syncOutboxDao(), "local_accounts", "acc_rt_rec", "upsert", "{\"displayName\":\"RT Rec User Updated\",\"syncMutationId\":\"mut_rt_1\"}")

        // 1. Execute fallback push
        val (mockAuth, mockFirestore, _) = createMockFirebase(
            batchShouldFail = true
        )
        syncRepository.setFirebaseInstancesForTest(mockAuth, mockFirestore)
        val pushSuccess = syncRepository.triggerSyncOneShot()
        assertTrue(pushSuccess)

        // At this instant, fallback push succeeded with 0 read-backs, so remote_version is not yet populated
        assertNull(db.syncMetadataDao().get("remote_version:account:$accountId"))

        // 2. Realtime listener arrives with server timestamp
        val serverTs = 1760000000456L
        val rtDoc = createMockQueryDoc(
            id = accountId,
            data = mapOf(
                "id" to accountId,
                "displayName" to "RT Rec User Updated",
                "phone1" to "0770666666",
                "debtIqd" to 20000.0,
                "openingDebtIqd" to 20000.0,
                "syncMutationId" to "mut_rt_1",
                "createdAt" to 1000L,
                "updatedAt" to serverTs
            )
        )

        val snapshot = createMockQuerySnapshot(listOf(rtDoc))
        val job = syncRepository.handleSnapshot(snapshot, null, "local_accounts", testUid)
        job?.join()

        // Verify Realtime update successfully populated authoritative remote_version
        val recordedVersion = db.syncMetadataDao().get("remote_version:account:$accountId")
        assertEquals(serverTs.toString(), recordedVersion)
    }

    // =========================================================================
    // 6. GENUINE WRITE FAILURE REMAINS RETRYABLE
    // =========================================================================
    @Test
    fun test6_genuineWriteFailureRemainsRetryable() = runBlocking {
        db.localAccountDao().insert(LocalAccount(id = "acc_fail_1", displayName = "Failing User", phone1 = "0770777777", debtIqd = 5000.0))
        OutboxManager.enqueue(db.syncOutboxDao(), "local_accounts", "acc_fail_1", "upsert", "{\"displayName\":\"Failing User\"}")

        // Batch fails AND single item set fails
        val (mockAuth, mockFirestore, _) = createMockFirebase(
            batchShouldFail = true,
            singleItemSetFailDocIds = setOf("acc_fail_1")
        )
        syncRepository.setFirebaseInstancesForTest(mockAuth, mockFirestore)

        val success = syncRepository.triggerSyncOneShot()
        assertTrue(success)
        assertEquals(SyncStatusState.COMPLETE_WITH_ERRORS, syncRepository.syncState.value)

        // Item remains in Outbox with status = failed (retryable)
        val remaining = db.syncOutboxDao().getAllOneShot()
        assertEquals(1, remaining.size)
        assertEquals("acc_fail_1", remaining[0].entityId)
        assertEquals("failed", remaining[0].status)
    }

    // =========================================================================
    // 7 & 8. UNCERTAIN WRITE RECOVERY AND IDEMPOTENCY
    // =========================================================================
    @Test
    fun test7And8_uncertainWriteRecoveryAndIdempotencySafe() = runBlocking {
        val accountId = "acc_uncertain_1"
        db.localAccountDao().insert(LocalAccount(id = accountId, displayName = "Uncertain User", phone1 = "0770888888", debtIqd = 30000.0))
        val outboxItem = OutboxManager.enqueue(db.syncOutboxDao(), "local_accounts", accountId, "upsert", "{\"displayName\":\"Uncertain User Final\",\"syncMutationId\":\"mut_unc_1\"}")

        // Simulate write succeeds on server, but process is simulated to restart or retry the outbox item
        val (mockAuth, mockFirestore, _) = createMockFirebase(
            batchShouldFail = true
        )
        syncRepository.setFirebaseInstancesForTest(mockAuth, mockFirestore)

        // First attempt (fallback push)
        val pass1 = syncRepository.triggerSyncOneShot()
        assertTrue(pass1)

        // Verify outbox was cleared
        assertTrue(db.syncOutboxDao().getAllOneShot().isEmpty())

        // Simulate identical outbox replay (idempotent retry scenario)
        OutboxManager.enqueue(db.syncOutboxDao(), "local_accounts", accountId, "upsert", "{\"displayName\":\"Uncertain User Final\",\"syncMutationId\":\"mut_unc_1\"}")
        val pass2 = syncRepository.triggerSyncOneShot()
        assertTrue(pass2)

        // Verify single account entry exists with consistent state, no duplicates
        val accounts = db.localAccountDao().getByIdOneShot(accountId)
        assertNotNull(accounts)
        assertEquals("Uncertain User", accounts?.displayName) // Local unchanged until pull/mutation
        assertEquals(30000.0, accounts?.debtIqd ?: 0.0, 0.001)
    }
}
