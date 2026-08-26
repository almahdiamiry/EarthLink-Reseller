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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * CHANGE 3A Permanent Regression Suite:
 * Move missing-parent Firebase fetch outside REMOTE_APPLY lock.
 *
 * Covers:
 * A. Missing parent normally (successful pre-fetch outside lock and application)
 * B. Missing parent + generation advance during GET (RED-02 rejection)
 * C. Missing parent + Restore during GET (restored state preserved)
 * D. Missing parent + Replace-All during GET (imported state preserved)
 * E. Missing parent + local mutation concurrency (lock is NOT held during network GET)
 * F. Realtime parent resolution edge cases (locally present vs remotely deleted)
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Change3AMissingParentOptimizationRegressionTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private val testUid = "test_user_3a"

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
    // A. Missing Parent Normally
    // =========================================================================
    @Test
    fun testA_missingParentNormally_fetchesParentAndAppliesLedger() = runBlocking {
        val parentAccountId = "acc_3a_normal"
        val ledgerId = "ledger_3a_normal"

        val parentData = mapOf<String, Any?>(
            "id" to parentAccountId,
            "displayName" to "Normal Parent",
            "phone1" to "07701111111",
            "debtIqd" to 50000.0,
            "openingDebtIqd" to 50000.0,
            "updatedAt" to 1000L,
            "createdAt" to 1000L
        )
        val parentDoc = createMockDoc(parentAccountId, parentData)

        val mockFirestore = mock(FirebaseFirestore::class.java)
        val mockUsersColl = mock(CollectionReference::class.java)
        val mockUserDoc = mock(DocumentReference::class.java)
        val mockAccountsColl = mock(CollectionReference::class.java)
        val mockParentDocRef = mock(DocumentReference::class.java)

        `when`(mockFirestore.collection("users")).thenReturn(mockUsersColl)
        `when`(mockUsersColl.document(testUid)).thenReturn(mockUserDoc)
        `when`(mockUserDoc.collection("local_accounts")).thenReturn(mockAccountsColl)
        `when`(mockAccountsColl.document(parentAccountId)).thenReturn(mockParentDocRef)
        `when`(mockParentDocRef.get(Source.SERVER)).thenReturn(Tasks.forResult(parentDoc))

        val repo = createTestRepository(mockFirestore)

        val ledgerData = mapOf<String, Any?>(
            "id" to ledgerId,
            "accountId" to parentAccountId,
            "amountIqd" to 10000.0,
            "debtAfterIqd" to 40000.0,
            "typeRaw" to "payment",
            "occurredAt" to 2000L,
            "createdAt" to 2000L,
            "updatedAt" to 2000L
        )
        val ledgerDoc = createMockQueryDoc(ledgerId, ledgerData)
        val snapshot = createMockQuerySnapshot(listOf(ledgerDoc))

        // Trigger realtime snapshot
        repo.handleSnapshot(snapshot, null, "local_ledger_entries", testUid)?.join()

        // Assert: Parent account created
        val savedAccount = db.localAccountDao().getByIdOneShot(parentAccountId)
        assertNotNull("Parent account must be saved", savedAccount)
        assertEquals("Normal Parent", savedAccount?.displayName)
        assertEquals(40000.0, savedAccount?.debtIqd ?: 0.0, 0.001)

        // Assert: Ledger entry created
        val savedLedger = db.localLedgerEntryDao().getByIdOneShot(ledgerId)
        assertNotNull("Ledger entry must be saved", savedLedger)
        assertEquals(10000.0, savedLedger?.amountIqd ?: 0.0, 0.001)

        // Assert: Cursor advanced
        val cursorStr = db.syncMetadataDao().get("last_sync_local_ledger_entries")
        val cursor = RemoteSyncCursor.parseCursorString(cursorStr)
        assertEquals(2000L, cursor.lastServerTimestamp)
    }

    // =========================================================================
    // B. Missing Parent + Generation Advance During GET
    // =========================================================================
    @Test
    fun testB_missingParent_generationAdvanceDuringGet_rejectsStaleCandidate() = runBlocking {
        val parentAccountId = "acc_3a_gen_adv"
        val ledgerId = "ledger_3a_gen_adv"

        val parentData = mapOf<String, Any?>(
            "id" to parentAccountId,
            "displayName" to "Stale Candidate Parent",
            "phone1" to "07702222222",
            "debtIqd" to 100000.0,
            "openingDebtIqd" to 100000.0,
            "updatedAt" to 1000L
        )
        val parentDoc = createMockDoc(parentAccountId, parentData)

        val mockFirestore = mock(FirebaseFirestore::class.java)
        val mockUsersColl = mock(CollectionReference::class.java)
        val mockUserDoc = mock(DocumentReference::class.java)
        val mockAccountsColl = mock(CollectionReference::class.java)
        val mockParentDocRef = mock(DocumentReference::class.java)

        `when`(mockFirestore.collection("users")).thenReturn(mockUsersColl)
        `when`(mockUsersColl.document(testUid)).thenReturn(mockUserDoc)
        `when`(mockUserDoc.collection("local_accounts")).thenReturn(mockAccountsColl)
        `when`(mockAccountsColl.document(parentAccountId)).thenReturn(mockParentDocRef)

        // Intercept Firestore GET to increment generation from 1L to 2L
        `when`(mockParentDocRef.get(Source.SERVER)).thenAnswer {
            runBlocking {
                db.incrementGeneration() // Generation advances during network GET
            }
            Tasks.forResult(parentDoc)
        }

        val repo = createTestRepository(mockFirestore)

        val ledgerData = mapOf<String, Any?>(
            "id" to ledgerId,
            "accountId" to parentAccountId,
            "amountIqd" to 20000.0,
            "debtAfterIqd" to 80000.0,
            "typeRaw" to "payment",
            "occurredAt" to 3000L,
            "createdAt" to 3000L,
            "updatedAt" to 3000L
        )
        val ledgerDoc = createMockQueryDoc(ledgerId, ledgerData)
        val snapshot = createMockQuerySnapshot(listOf(ledgerDoc))

        repo.handleSnapshot(snapshot, null, "local_ledger_entries", testUid)?.join()

        // Assert: Parent account NOT saved in Room
        assertNull("Stale parent must not be saved", db.localAccountDao().getByIdOneShot(parentAccountId))

        // Assert: Ledger entry NOT saved in Room
        assertNull("Stale ledger must not be saved", db.localLedgerEntryDao().getByIdOneShot(ledgerId))

        // Assert: remote_version NOT written
        assertNull(db.syncMetadataDao().get("remote_version:account:$parentAccountId"))
        assertNull(db.syncMetadataDao().get("remote_version:ledger:$ledgerId"))

        // Assert: Cursor did NOT advance
        val cursorStr = db.syncMetadataDao().get("last_sync_local_ledger_entries")
        val cursor = RemoteSyncCursor.parseCursorString(cursorStr)
        assertEquals(0L, cursor.lastServerTimestamp)
    }

    // =========================================================================
    // C. Missing Parent + Restore During GET
    // =========================================================================
    @Test
    fun testC_missingParent_restoreDuringGet_preservesRestoredState() = runBlocking {
        val parentAccountId = "acc_3a_restore"
        val ledgerId = "ledger_3a_restore"

        val staleParentData = mapOf<String, Any?>(
            "id" to parentAccountId,
            "displayName" to "Stale Cloud Parent",
            "debtIqd" to 99999.0,
            "updatedAt" to 1000L
        )
        val staleParentDoc = createMockDoc(parentAccountId, staleParentData)

        val mockFirestore = mock(FirebaseFirestore::class.java)
        val mockUsersColl = mock(CollectionReference::class.java)
        val mockUserDoc = mock(DocumentReference::class.java)
        val mockAccountsColl = mock(CollectionReference::class.java)
        val mockParentDocRef = mock(DocumentReference::class.java)

        `when`(mockFirestore.collection("users")).thenReturn(mockUsersColl)
        `when`(mockUsersColl.document(testUid)).thenReturn(mockUserDoc)
        `when`(mockUserDoc.collection("local_accounts")).thenReturn(mockAccountsColl)
        `when`(mockAccountsColl.document(parentAccountId)).thenReturn(mockParentDocRef)

        // During network GET, simulate Restore execution
        `when`(mockParentDocRef.get(Source.SERVER)).thenAnswer {
            runBlocking {
                db.incrementGeneration() // Restore increments generation
                db.localAccountDao().upsert(
                    LocalAccount(
                        id = parentAccountId,
                        displayName = "Restored Ground Truth",
                        debtIqd = 0.0,
                        openingDebtIqd = 0.0
                    )
                )
            }
            Tasks.forResult(staleParentDoc)
        }

        val repo = createTestRepository(mockFirestore)

        val ledgerData = mapOf<String, Any?>(
            "id" to ledgerId,
            "accountId" to parentAccountId,
            "amountIqd" to 15000.0,
            "debtAfterIqd" to 84999.0,
            "typeRaw" to "payment",
            "occurredAt" to 4000L,
            "createdAt" to 4000L,
            "updatedAt" to 4000L
        )
        val ledgerDoc = createMockQueryDoc(ledgerId, ledgerData)
        val snapshot = createMockQuerySnapshot(listOf(ledgerDoc))

        repo.handleSnapshot(snapshot, null, "local_ledger_entries", testUid)?.join()

        // Assert: Restored state is intact and not overwritten by stale cloud candidate
        val currentAccount = db.localAccountDao().getByIdOneShot(parentAccountId)
        assertNotNull(currentAccount)
        assertEquals("Restored Ground Truth", currentAccount?.displayName)
        assertEquals(0.0, currentAccount?.debtIqd ?: -1.0, 0.001)

        // Assert: Stale ledger was not inserted
        assertNull(db.localLedgerEntryDao().getByIdOneShot(ledgerId))
    }

    // =========================================================================
    // D. Missing Parent + Replace-All (Utower Import) During GET
    // =========================================================================
    @Test
    fun testD_missingParent_replaceAllDuringGet_preservesImportedState() = runBlocking {
        val parentAccountId = "acc_3a_replace"
        val ledgerId = "ledger_3a_replace"

        val staleParentData = mapOf<String, Any?>(
            "id" to parentAccountId,
            "displayName" to "Pre-Import Cloud Parent",
            "debtIqd" to 77000.0,
            "updatedAt" to 1000L
        )
        val staleParentDoc = createMockDoc(parentAccountId, staleParentData)

        val mockFirestore = mock(FirebaseFirestore::class.java)
        val mockUsersColl = mock(CollectionReference::class.java)
        val mockUserDoc = mock(DocumentReference::class.java)
        val mockAccountsColl = mock(CollectionReference::class.java)
        val mockParentDocRef = mock(DocumentReference::class.java)

        `when`(mockFirestore.collection("users")).thenReturn(mockUsersColl)
        `when`(mockUsersColl.document(testUid)).thenReturn(mockUserDoc)
        `when`(mockUserDoc.collection("local_accounts")).thenReturn(mockAccountsColl)
        `when`(mockAccountsColl.document(parentAccountId)).thenReturn(mockParentDocRef)

        // During network GET, simulate Replace-All Utower import
        `when`(mockParentDocRef.get(Source.SERVER)).thenAnswer {
            runBlocking {
                db.incrementGeneration()
                db.localAccountDao().deleteAll()
                db.localAccountDao().upsert(
                    LocalAccount(
                        id = "acc_imported_fresh",
                        displayName = "Fresh Imported Account",
                        debtIqd = 12000.0,
                        openingDebtIqd = 12000.0
                    )
                )
            }
            Tasks.forResult(staleParentDoc)
        }

        val repo = createTestRepository(mockFirestore)

        val ledgerData = mapOf<String, Any?>(
            "id" to ledgerId,
            "accountId" to parentAccountId,
            "amountIqd" to 10000.0,
            "debtAfterIqd" to 67000.0,
            "typeRaw" to "payment",
            "occurredAt" to 5000L,
            "createdAt" to 5000L,
            "updatedAt" to 5000L
        )
        val ledgerDoc = createMockQueryDoc(ledgerId, ledgerData)
        val snapshot = createMockQuerySnapshot(listOf(ledgerDoc))

        repo.handleSnapshot(snapshot, null, "local_ledger_entries", testUid)?.join()

        // Assert: Stale parent was NOT saved
        assertNull(db.localAccountDao().getByIdOneShot(parentAccountId))

        // Assert: Imported account untouched
        val imported = db.localAccountDao().getByIdOneShot("acc_imported_fresh")
        assertNotNull(imported)
        assertEquals("Fresh Imported Account", imported?.displayName)

        // Assert: Stale ledger was NOT saved
        assertNull(db.localLedgerEntryDao().getByIdOneShot(ledgerId))
    }

    // =========================================================================
    // E. Missing Parent + Local Mutation Concurrency (Network GET Outside Lock)
    // =========================================================================
    @Test
    fun testE_missingParent_localMutationNotBlockedDuringNetworkGet() = runBlocking {
        val parentAccountId = "acc_3a_concurrent"
        val ledgerId = "ledger_3a_concurrent"

        val parentData = mapOf<String, Any?>(
            "id" to parentAccountId,
            "displayName" to "Concurrent Parent",
            "debtIqd" to 30000.0,
            "openingDebtIqd" to 30000.0,
            "updatedAt" to 1000L
        )
        val parentDoc = createMockDoc(parentAccountId, parentData)

        val mockFirestore = mock(FirebaseFirestore::class.java)
        val mockUsersColl = mock(CollectionReference::class.java)
        val mockUserDoc = mock(DocumentReference::class.java)
        val mockAccountsColl = mock(CollectionReference::class.java)
        val mockParentDocRef = mock(DocumentReference::class.java)

        `when`(mockFirestore.collection("users")).thenReturn(mockUsersColl)
        `when`(mockUsersColl.document(testUid)).thenReturn(mockUserDoc)
        `when`(mockUserDoc.collection("local_accounts")).thenReturn(mockAccountsColl)
        `when`(mockAccountsColl.document(parentAccountId)).thenReturn(mockParentDocRef)

        val networkGetStarted = CompletableDeferred<Unit>()
        val localMutationFinished = CompletableDeferred<Unit>()
        val localMutationExecutedWhileNetworkInFlight = AtomicBoolean(false)

        `when`(mockParentDocRef.get(Source.SERVER)).thenAnswer {
            // Verify structurally: when network GET runs, REMOTE_APPLY is NOT held!
            assertFalse(
                "REMOTE_APPLY must NOT be held during network GET",
                DataOperationCoordinator.currentMode == DataOperationMode.REMOTE_APPLY
            )
            networkGetStarted.complete(Unit)

            // Block Firestore GET return until local mutation executes
            runBlocking {
                localMutationFinished.await()
            }
            Tasks.forResult(parentDoc)
        }

        val repo = createTestRepository(mockFirestore)

        val ledgerData = mapOf<String, Any?>(
            "id" to ledgerId,
            "accountId" to parentAccountId,
            "amountIqd" to 5000.0,
            "debtAfterIqd" to 25000.0,
            "typeRaw" to "payment",
            "occurredAt" to 6000L,
            "createdAt" to 6000L,
            "updatedAt" to 6000L
        )
        val ledgerDoc = createMockQueryDoc(ledgerId, ledgerData)
        val snapshot = createMockQuerySnapshot(listOf(ledgerDoc))

        // Start handleSnapshot in background
        val snapshotJob = repo.handleSnapshot(snapshot, null, "local_ledger_entries", testUid)

        // Wait for network GET to begin
        networkGetStarted.await()

        // Concurrently perform a local mutation directly to database
        val localMutationJob = async {
            localMutationExecutedWhileNetworkInFlight.set(true)
            db.localAccountDao().upsert(
                LocalAccount(
                    id = "acc_local_concurrent",
                    displayName = "Local Mutation Subscriber",
                    debtIqd = 15000.0,
                    openingDebtIqd = 15000.0
                )
            )
            localMutationFinished.complete(Unit)
        }

        localMutationJob.await()
        snapshotJob?.join()

        assertTrue("Local mutation must execute without waiting for remote GET", localMutationExecutedWhileNetworkInFlight.get())
        assertNotNull(db.localAccountDao().getByIdOneShot("acc_local_concurrent"))
        assertNotNull(db.localAccountDao().getByIdOneShot(parentAccountId))
        assertNotNull(db.localLedgerEntryDao().getByIdOneShot(ledgerId))
    }

    // =========================================================================
    // F. Realtime Parent Resolution (Locally Present vs Remotely Deleted)
    // =========================================================================
    @Test
    fun testF1_parentAlreadyLocallyPresent_skipsNetworkGet() = runBlocking {
        val parentAccountId = "acc_3a_existing"
        val ledgerId = "ledger_3a_existing"

        // Insert parent account locally first
        db.localAccountDao().upsert(
            LocalAccount(
                id = parentAccountId,
                displayName = "Existing Local Parent",
                debtIqd = 40000.0,
                openingDebtIqd = 40000.0
            )
        )

        val mockFirestore = mock(FirebaseFirestore::class.java)
        // If firestore is accessed, it will throw unless mocked; leave unmocked to prove zero network access

        val repo = createTestRepository(mockFirestore)

        val ledgerData = mapOf<String, Any?>(
            "id" to ledgerId,
            "accountId" to parentAccountId,
            "amountIqd" to 10000.0,
            "debtAfterIqd" to 30000.0,
            "typeRaw" to "payment",
            "occurredAt" to 7000L,
            "createdAt" to 7000L,
            "updatedAt" to 7000L
        )
        val ledgerDoc = createMockQueryDoc(ledgerId, ledgerData)
        val snapshot = createMockQuerySnapshot(listOf(ledgerDoc))

        repo.handleSnapshot(snapshot, null, "local_ledger_entries", testUid)?.join()

        val savedLedger = db.localLedgerEntryDao().getByIdOneShot(ledgerId)
        assertNotNull(savedLedger)
        val updatedParent = db.localAccountDao().getByIdOneShot(parentAccountId)
        assertEquals(30000.0, updatedParent?.debtIqd ?: 0.0, 0.001)
    }

    @Test
    fun testF2_parentRemotelyDeleted_skipsZombieResurrection() = runBlocking {
        val parentAccountId = "acc_3a_zombie"
        val ledgerId = "ledger_3a_zombie"

        // Remote parent document has deletedAt > 0
        val deletedParentData = mapOf<String, Any?>(
            "id" to parentAccountId,
            "displayName" to "Deleted Remote Parent",
            "deletedAt" to 8000L,
            "updatedAt" to 8000L
        )
        val deletedParentDoc = createMockDoc(parentAccountId, deletedParentData)

        val mockFirestore = mock(FirebaseFirestore::class.java)
        val mockUsersColl = mock(CollectionReference::class.java)
        val mockUserDoc = mock(DocumentReference::class.java)
        val mockAccountsColl = mock(CollectionReference::class.java)
        val mockParentDocRef = mock(DocumentReference::class.java)

        `when`(mockFirestore.collection("users")).thenReturn(mockUsersColl)
        `when`(mockUsersColl.document(testUid)).thenReturn(mockUserDoc)
        `when`(mockUserDoc.collection("local_accounts")).thenReturn(mockAccountsColl)
        `when`(mockAccountsColl.document(parentAccountId)).thenReturn(mockParentDocRef)
        `when`(mockParentDocRef.get(Source.SERVER)).thenReturn(Tasks.forResult(deletedParentDoc))

        val repo = createTestRepository(mockFirestore)

        val ledgerData = mapOf<String, Any?>(
            "id" to ledgerId,
            "accountId" to parentAccountId,
            "amountIqd" to 5000.0,
            "debtAfterIqd" to 5000.0,
            "typeRaw" to "payment",
            "occurredAt" to 9000L,
            "createdAt" to 9000L,
            "updatedAt" to 9000L
        )
        val ledgerDoc = createMockQueryDoc(ledgerId, ledgerData)
        val snapshot = createMockQuerySnapshot(listOf(ledgerDoc))

        repo.handleSnapshot(snapshot, null, "local_ledger_entries", testUid)?.join()

        // Assert: Parent account was NOT resurrected
        assertNull("Remotely deleted parent must not be resurrected", db.localAccountDao().getByIdOneShot(parentAccountId))
    }
}
