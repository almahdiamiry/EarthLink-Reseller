package com.example.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.EarthlinkApp
import com.example.core.database.*
import com.example.core.model.*
import com.example.core.security.PreferenceManager
import com.example.domain.repository.SyncStatusState
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Yellow03ReadBackOptimizationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var syncRepository: SyncRepositoryImpl
    private val testUid = "test_reseller_user_123"
    private val mockDocRefs = mutableMapOf<String, DocumentReference>()

    @Before
    fun setup() {
        runBlocking {
            mockDocRefs.clear()
            context = ApplicationProvider.getApplicationContext()
            (context as? EarthlinkApp)?.isSafeDebugFallbackAllowedOverride = true
            context.getDatabasePath("earthlink_reseller_db").parentFile?.mkdirs()
            AppDatabase.closeDatabase()
            db = (context as? EarthlinkApp)?.database ?: AppDatabase.getDatabase(context, ByteArray(0), "earthlink_reseller_db")

            db.localLedgerEntryDao().deleteAll()
            db.localAccountDao().deleteAll()
            db.importBatchDao().deleteAll()
            db.syncOutboxDao().deleteAll()
            db.syncMetadataDao().deleteAll()
            db.auditLogDao().clearAll()

            // Initialize PreferenceManager for authentication
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

    private fun createMockFirebase(
        accountsDocs: List<DocumentSnapshot> = emptyList(),
        ledgersDocs: List<DocumentSnapshot> = emptyList(),
        batchesDocs: List<DocumentSnapshot> = emptyList(),
        singleDocGets: Map<String, DocumentSnapshot> = emptyMap(),
        batchShouldFail: Boolean = false,
        singleItemSetShouldFail: Boolean = false
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

        // Mock collection queries for pullRemoteChanges
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

        // Mock document reference behavior
        val answerDocRef = { invocation: org.mockito.invocation.InvocationOnMock ->
            val id = invocation.getArgument<String>(0)
            mockDocRefs.getOrPut(id) {
                val r = mock(DocumentReference::class.java)
                if (singleItemSetShouldFail) {
                    `when`(r.set(any(), any())).thenReturn(Tasks.forException(Exception("Single item set failure")))
                } else {
                    `when`(r.set(any(), any())).thenReturn(Tasks.forResult(null))
                }

                val docSnap = singleDocGets[id] ?: run {
                    val fallback = mock(DocumentSnapshot::class.java)
                    `when`(fallback.exists()).thenReturn(false)
                    fallback
                }
                `when`(r.get(any(Source::class.java))).thenReturn(Tasks.forResult(docSnap))
                `when`(r.get()).thenReturn(Tasks.forResult(docSnap))
                r
            }
        }

        `when`(mockAccountsColl.document(anyString())).thenAnswer(answerDocRef)
        `when`(mockLedgersColl.document(anyString())).thenAnswer(answerDocRef)
        `when`(mockBatchesColl.document(anyString())).thenAnswer(answerDocRef)

        val mockBatch = mock(WriteBatch::class.java)
        if (batchShouldFail) {
            `when`(mockBatch.commit()).thenReturn(Tasks.forException(Exception("Batch write error")))
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
        `when`(q1.orderBy(com.google.firebase.firestore.FieldPath.documentId())).thenReturn(q2)
        `when`(q2.limit(500)).thenReturn(q3)

        // For first page fetch
        `when`(q3.get(Source.SERVER)).thenReturn(Tasks.forResult(querySnapshot))

        // Chaining subsequent page fetches or start offsets
        `when`(q3.startAfter(any())).thenReturn(q4)
        `when`(q3.startAfter(any(java.util.Date::class.java), anyString())).thenReturn(q4)
        `when`(q3.startAt(any(java.util.Date::class.java))).thenReturn(q4)

        // Subsequent page fetches return empty snapshot to break the loop safely
        val emptySnapshot = mock(QuerySnapshot::class.java)
        `when`(emptySnapshot.documents).thenReturn(emptyList())
        `when`(emptySnapshot.isEmpty).thenReturn(true)
        `when`(q4.get(Source.SERVER)).thenReturn(Tasks.forResult(emptySnapshot))
    }

    // ============================================================
    // TEST A — NO BATCH READ-BACK
    // ============================================================
    @Test
    fun testABatchSucceeds_doesNotInvokeConfirmRemoteVersionReadBack() {
        runBlocking {
            // Seed two outbox items to force a multi-item batch
            OutboxManager.enqueue(db.syncOutboxDao(), "local_accounts", "acc_A", "delete", "{}")
            OutboxManager.enqueue(db.syncOutboxDao(), "local_accounts", "acc_B", "delete", "{}")

            val (mockAuth, mockFirestore, mockBatch) = createMockFirebase()
            syncRepository.setFirebaseInstancesForTest(mockAuth, mockFirestore)

            val success = syncRepository.triggerSyncOneShot()
            assertTrue(success)

            // Assert: Outbox items successfully acknowledged (deleted from table)
            val pending = db.syncOutboxDao().getAllOneShot()
            assertTrue("Outbox must be empty after successful sync", pending.isEmpty())

            // Preferred evidence: Verify no document 'get()' is called since batch confirmation is removed
            for (mockRef in mockDocRefs.values) {
                verify(mockRef, never()).get(any(Source::class.java))
                verify(mockRef, never()).get()
            }
        }
    }

    // ============================================================
    // TEST B — BATCH FAILURE PRESERVES OUTBOX
    // ============================================================
    @Test
    fun testBBatchFailure_preservesOutbox_doesNotWriteFakeRemoteVersion() {
        runBlocking {
            // Seed two outbox items
            val itemA = OutboxManager.enqueue(db.syncOutboxDao(), "local_accounts", "acc_A", "delete", "{\"syncMutationId\":\"mut_A\"}")
            val itemB = OutboxManager.enqueue(db.syncOutboxDao(), "local_accounts", "acc_B", "delete", "{\"syncMutationId\":\"mut_B\"}")

            // Set both batch commit and single-item sets to fail to ensure nothing goes through
            val (mockAuth, mockFirestore, mockBatch) = createMockFirebase(
                batchShouldFail = true,
                singleItemSetShouldFail = true
            )
            syncRepository.setFirebaseInstancesForTest(mockAuth, mockFirestore)

            val success = syncRepository.triggerSyncOneShot()

            // Outbox sync completed, but with item-level failures handled gracefully
            assertTrue(success)
            assertEquals(SyncStatusState.COMPLETE_WITH_ERRORS, syncRepository.syncState.value)

            // Assert: Items are NOT falsely acknowledged
            val remaining = db.syncOutboxDao().getAllOneShot()
            assertEquals(2, remaining.size)
            assertTrue(remaining.any { it.entityId == "acc_A" && it.status == "failed" })
            assertTrue(remaining.any { it.entityId == "acc_B" && it.status == "failed" })

            // Assert: No fake remote versions written to metadata
            assertNull(db.syncMetadataDao().get("remote_version:account:acc_A"))
            assertNull(db.syncMetadataDao().get("remote_version:account:acc_B"))
        }
    }

    // ============================================================
    // TEST C — POST-COMMIT PULL RECONCILIATION
    // ============================================================
    @Test
    fun testCPostCommitPullReconciliation_updatesRemoteVersionAndMetadata() {
        runBlocking {
            // Seed an account locally
            db.localAccountDao().insert(LocalAccount(id = "acc_A", displayName = "Sub A", phone1 = "07700000001", debtIqd = 10000.0))

            // Enqueue an outbox update for it
            OutboxManager.enqueue(db.syncOutboxDao(), "local_accounts", "acc_A", "upsert", "{\"displayName\":\"Sub A Updated\",\"syncMutationId\":\"mut_A\"}")

            // Setup a mock remote doc representing the server-confirmed state to be pulled in pullRemoteChanges
            val remoteDoc = createMockDoc(
                id = "acc_A",
                data = mapOf(
                    "displayName" to "Sub A Updated",
                    "syncMutationId" to "mut_A",
                    "updatedAt" to 1700000000123L,
                    "deletedAt" to null
                )
            )

            val (mockAuth, mockFirestore, _) = createMockFirebase(
                accountsDocs = listOf(remoteDoc)
            )
            syncRepository.setFirebaseInstancesForTest(mockAuth, mockFirestore)

            val success = syncRepository.triggerSyncOneShot()
            assertTrue(success)

            // Assert: Local state matches updated pulled state
            val localAcc = db.localAccountDao().getByIdOneShot("acc_A")
            assertNotNull(localAcc)
            assertEquals("Sub A Updated", localAcc?.displayName)

            // Assert: RemoteSyncCoordinator records authoritative remote_version
            val recordedVersion = db.syncMetadataDao().get("remote_version:account:acc_A")
            assertEquals("1700000000123", recordedVersion)
        }
    }

    // ============================================================
    // TEST D — INTERRUPTED POST-COMMIT RECOVERY
    // ============================================================
    @Test
    fun testDInterruptedPostCommitRecovery_rediscoversCommittedDocOnNextSync() {
        runBlocking {
            // Seed local account and outbox item
            db.localAccountDao().insert(LocalAccount(id = "acc_A", displayName = "Sub A", phone1 = "07700000001", debtIqd = 10000.0))
            OutboxManager.enqueue(db.syncOutboxDao(), "local_accounts", "acc_A", "upsert", "{\"displayName\":\"Sub A Updated\",\"syncMutationId\":\"mut_A\"}")

            // First pass: Batch succeeds but pull fails or is empty (representing interrupted downward sync)
            val (mockAuth1, mockFirestore1, _) = createMockFirebase(
                accountsDocs = emptyList() // No documents received from pull yet
            )
            syncRepository.setFirebaseInstancesForTest(mockAuth1, mockFirestore1)

            val pass1 = syncRepository.triggerSyncOneShot()
            assertTrue(pass1)

            // Outbox is cleared because upload succeeded, but cursor remains back and no remote_version is recorded yet
            val remainingOutbox = db.syncOutboxDao().getAllOneShot()
            assertTrue(remainingOutbox.isEmpty())
            assertNull(db.syncMetadataDao().get("remote_version:account:acc_A"))

            // Second pass (bootstrap/next sync): Pull returns the committed document
            val remoteDoc = createMockDoc(
                id = "acc_A",
                data = mapOf(
                    "displayName" to "Sub A Updated",
                    "syncMutationId" to "mut_A",
                    "updatedAt" to 1700000000123L,
                    "deletedAt" to null
                )
            )
            val (mockAuth2, mockFirestore2, _) = createMockFirebase(
                accountsDocs = listOf(remoteDoc)
            )
            syncRepository.setFirebaseInstancesForTest(mockAuth2, mockFirestore2)

            val pass2 = syncRepository.triggerSyncOneShot()
            assertTrue(pass2)

            // Committed document is rediscovered and remote_version eventually captured securely
            val recordedVersion = db.syncMetadataDao().get("remote_version:account:acc_A")
            assertEquals("1700000000123", recordedVersion)
        }
    }

    // ============================================================
    // TEST E — MULTI-DEVICE RACE
    // ============================================================
    @Test
    fun testEMultiDeviceRace_newerRemoteWins() {
        runBlocking {
            // Local has a mutation with version/timestamp T1 (1700000000000L)
            db.localAccountDao().insert(LocalAccount(id = "acc_A", displayName = "Sub A Local", phone1 = "07700000001", debtIqd = 10000.0))
            db.syncMetadataDao().put("remote_version:account:acc_A", "1700000000000")

            // Pull receives remote mutation T2 (1700000000999L) which is newer than local version
            val remoteDoc = createMockDoc(
                id = "acc_A",
                data = mapOf(
                    "displayName" to "Sub A Remote Winner",
                    "updatedAt" to 1700000000999L,
                    "deletedAt" to null
                )
            )

            val (mockAuth, mockFirestore, _) = createMockFirebase(
                accountsDocs = listOf(remoteDoc)
            )
            syncRepository.setFirebaseInstancesForTest(mockAuth, mockFirestore)

            val success = syncRepository.triggerSyncOneShot()
            assertTrue(success)

            // Assert: Remote wins because T2 > T1
            val localAcc = db.localAccountDao().getByIdOneShot("acc_A")
            assertEquals("Sub A Remote Winner", localAcc?.displayName)
            assertEquals("1700000000999", db.syncMetadataDao().get("remote_version:account:acc_A"))
        }
    }

    // ============================================================
    // TEST F — TOMBSTONE
    // ============================================================
    @Test
    fun testFTombstone_recordsTombstoneAndPreventsResurrect() {
        runBlocking {
            // Local has an account
            db.localAccountDao().insert(LocalAccount(id = "acc_A", displayName = "Sub A", phone1 = "07700000001", debtIqd = 10000.0))

            // Pull receives deletion tombstone
            val remoteDoc = createMockDoc(
                id = "acc_A",
                data = mapOf(
                    "displayName" to "Sub A",
                    "updatedAt" to 1700000001000L,
                    "deletedAt" to 1700000001000L
                )
            )

            val (mockAuth, mockFirestore, _) = createMockFirebase(
                accountsDocs = listOf(remoteDoc)
            )
            syncRepository.setFirebaseInstancesForTest(mockAuth, mockFirestore)

            val success = syncRepository.triggerSyncOneShot()
            assertTrue(success)

            // Assert: Subscriber is marked isHistoryOnlySubscriber (not completely deleted physically)
            val localAcc = db.localAccountDao().getByIdOneShot("acc_A")
            assertNotNull(localAcc)
            assertTrue(localAcc?.isHistoryOnlySubscriber == true)

            // Tombstone metadata is recorded
            assertEquals("1700000001000", db.syncMetadataDao().get("tombstone:account:acc_A"))
            assertEquals("1700000001000", db.syncMetadataDao().get("remote_version:account:acc_A"))
        }
    }
}
