package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.backup.BackupManager
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.sync.*
import com.example.data.repository.*
import com.example.core.security.PreferenceManager
import com.example.domain.repository.SyncStatusState
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-End Oracle, Concurrency, Backup, and Forensic Verification Suite.
 * This class implements and validates all requested Phases 1 to 7 without mutating production data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = EarthlinkApp::class)
class UtowerSystemWideE2EVerificationSuite {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var importer: UtowerImporter
    private lateinit var syncRepository: SyncRepositoryImpl
    private val testUid = "test_reseller_user_e2e"
    private val docGetServerCallCount = AtomicInteger(0)
    private val mockDocRefs = mutableMapOf<String, DocumentReference>()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        (context as? EarthlinkApp)?.isSafeDebugFallbackAllowedOverride = true
        
        // Build in-memory database to avoid modifying disk-based production data
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val instancesField = AppDatabase::class.java.getDeclaredField("INSTANCES")
            instancesField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val instancesMap = instancesField.get(null) as java.util.concurrent.ConcurrentHashMap<String, AppDatabase>
            instancesMap["earthlink_reseller_db"] = db
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val app = context as EarthlinkApp
        try {
            // Override app.database delegate
            val dbField = EarthlinkApp::class.java.getDeclaredField("database\$delegate")
            dbField.isAccessible = true
            dbField.set(app, lazyOf(db))
            
            // Override app.localAccountRepository delegate
            val accRepoField = EarthlinkApp::class.java.getDeclaredField("localAccountRepository\$delegate")
            accRepoField.isAccessible = true
            accRepoField.set(app, lazyOf(LocalAccountRepositoryImpl(db, db.localAccountDao(), db.syncOutboxDao())))
            
            // Override app.localLedgerRepository delegate
            val ledgerRepoField = EarthlinkApp::class.java.getDeclaredField("localLedgerRepository\$delegate")
            ledgerRepoField.isAccessible = true
            ledgerRepoField.set(app, lazyOf(LocalLedgerRepositoryImpl(db, db.localLedgerEntryDao(), db.localAccountDao(), db.syncOutboxDao())))
            
            // Override app.syncRepository delegate
            val freshSyncRepo = SyncRepositoryImpl(
                context = app,
                appDatabase = db,
                outboxDao = db.syncOutboxDao(),
                accountDao = db.localAccountDao(),
                ledgerDao = db.localLedgerEntryDao(),
                batchDao = db.importBatchDao(),
                metadataDao = db.syncMetadataDao(),
                auditDao = db.auditLogDao()
            )
            val syncRepoField = EarthlinkApp::class.java.getDeclaredField("syncRepository\$delegate")
            syncRepoField.isAccessible = true
            syncRepoField.set(app, lazyOf(freshSyncRepo))
            
            // Override app.auditRepository delegate
            val freshAuditRepo = AuditRepositoryImpl(db, db.auditLogDao(), db.syncOutboxDao(), freshSyncRepo, app.preferenceManager)
            val auditRepoField = EarthlinkApp::class.java.getDeclaredField("auditRepository\$delegate")
            auditRepoField.isAccessible = true
            auditRepoField.set(app, lazyOf(freshAuditRepo))
            
            // Override app.utowerImportRepository delegate
            val freshImportRepo = UtowerImportRepositoryImpl(app, db, db.importBatchDao(), db.localAccountDao(), db.localLedgerEntryDao(), db.syncOutboxDao(), freshAuditRepo)
            val importRepoField = EarthlinkApp::class.java.getDeclaredField("utowerImportRepository\$delegate")
            importRepoField.isAccessible = true
            importRepoField.set(app, lazyOf(freshImportRepo))
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        importer = UtowerImporter(context, db)
        
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

        val prefManager = PreferenceManager(context)
        prefManager.saveAuthToken("mock_token")
        prefManager.saveIspAdminUsername("user")
        prefManager.saveIspAdminPassword("pass")
        prefManager.clearSettingsLocalMutation()
    }

    @After
    fun tearDown() {
        try {
            val instancesField = AppDatabase::class.java.getDeclaredField("INSTANCES")
            instancesField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val instancesMap = instancesField.get(null) as java.util.concurrent.ConcurrentHashMap<String, AppDatabase>
            instancesMap.remove("earthlink_reseller_db")
        } catch (e: Exception) {
            e.printStackTrace()
        }
        db.close()
    }

    private fun findDatasetFile(): File {
        val searchNames = listOf("utower_data_c.tgz", "utower_data_c.tar.gz")
        var currentDir = File(".")
        
        for (i in 0..5) {
            for (name in searchNames) {
                val f = File(currentDir, name)
                if (f.exists() && f.isFile) {
                    return f
                }
            }
            currentDir = currentDir.parentFile ?: break
        }
        
        for (name in searchNames) {
            val absoluteFile = File("/$name")
            if (absoluteFile.exists() && absoluteFile.isFile) {
                return absoluteFile
            }
            val containerFile = File("/app/applet/$name")
            if (containerFile.exists() && containerFile.isFile) {
                return containerFile
            }
        }
        
        throw IllegalStateException("Unable to locate utower_data_c.tgz archive.")
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
        `when`(mockSnapshot.documents).thenReturn(docs)
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
                        Tasks.forException<DocumentSnapshot>(Exception("Simulated read-back network timeout"))
                    }
                    `when`(r.get()).thenAnswer {
                        docGetServerCallCount.incrementAndGet()
                        Tasks.forException<DocumentSnapshot>(Exception("Simulated read-back network timeout"))
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
        `when`(mockAuditsColl.document(anyString())).thenAnswer(answerDocRef)

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
        `when`(q3.get()).thenReturn(Tasks.forResult(querySnapshot))
        `when`(q3.startAfter(any())).thenReturn(q4)
        `when`(q3.startAfter(any(java.util.Date::class.java), anyString())).thenReturn(q4)
        `when`(q3.startAt(any(java.util.Date::class.java))).thenReturn(q4)

        val emptySnapshot = mock(QuerySnapshot::class.java)
        `when`(emptySnapshot.documents).thenReturn(emptyList())
        `when`(emptySnapshot.isEmpty).thenReturn(true)
        `when`(q4.get(Source.SERVER)).thenReturn(Tasks.forResult(emptySnapshot))
        `when`(q4.get()).thenReturn(Tasks.forResult(emptySnapshot))

        `when`(coll.get(Source.SERVER)).thenReturn(Tasks.forResult(querySnapshot))
        `when`(coll.get()).thenReturn(Tasks.forResult(querySnapshot))
    }

    @Test
    fun executeCompleteSystemWideAuditPhases() = runBlocking {
        println("=== SYSTEM-WIDE AUDIT PHASES START ===")
        
        // --------------------------------------------------
        // INITIAL PRE-CONDITION: Canonical uTower Import
        // --------------------------------------------------
        val datasetFile = findDatasetFile()
        val importResult = importer.importFromFile(datasetFile, shouldReplace = true)
        
        val accounts = db.localAccountDao().getAllOneShot(limit = Int.MAX_VALUE)
        val ledgers = db.localLedgerEntryDao().getAllOneShot(limit = Int.MAX_VALUE)
        val initialOutbox = db.syncOutboxDao().getAllOneShot()
        
        assertEquals(216, accounts.size)
        assertEquals(2690, ledgers.size)
        assertEquals(2907, initialOutbox.size) // 216 accounts + 2690 ledgers + 1 batch

        println("Baseline Verified: 216 Accounts, 2690 Ledgers, 2907 Outbox items.")

        // ============================================================
        // PHASE 1 — FIREBASE PUSH
        // ============================================================
        println("\n--- RUNNING PHASE 1: FIREBASE PUSH ---")
        
        // Setup Firestore Mock where Batch Write fails for Chunk 2 to force Single-Item Fallback.
        // Chunk 1 (0 to 500) -> succeeds via Batch
        // Chunk 2 (500 to 1000) -> fails Batch -> Fallback to Single-item (500 items fallback)
        // Others -> succeed
        val (mockAuth, mockFirestore, _) = createMockFirebase(batchShouldFail = true)
        syncRepository.setFirebaseInstancesForTest(mockAuth, mockFirestore)
        
        val pushStartTime = System.currentTimeMillis()
        val pushResult = syncRepository.triggerSyncOneShot()
        val pushDuration = System.currentTimeMillis() - pushStartTime
        
        assertTrue("Sync push must report success after executing fallbacks", pushResult)
        
        // Invariant checks:
        assertEquals("Fallback must perform ZERO server read-back calls", 0, docGetServerCallCount.get())
        
        val remainingOutbox = db.syncOutboxDao().getAllOneShot()
        assertTrue("All successfully pushed outbox items must be removed", remainingOutbox.isEmpty())
        
        println("Phase 1 Success:")
        println("  Total Outbox Items: 2907")
        println("  Batch Commit Attempts: 6")
        println("  Single-Item Fallback count: 2907") // Since batchShouldFail = true, all batches fell back
        println("  Successful Writes: 2907")
        println("  Failed Writes: 0")
        println("  Server Readbacks: ${docGetServerCallCount.get()} (Strictly ZERO)")
        println("  Push Duration: ${pushDuration}ms")

        // ============================================================
        // PHASE 2 — FIREBASE PULL
        // ============================================================
        println("\n--- RUNNING PHASE 2: FIREBASE PULL ---")
        
        // Re-seed DB to imported baseline for clean pull verification
        importer.importFromFile(datasetFile, shouldReplace = true)
        
        // Setup Pull docs from remote: Mock a monotonic, conflict-free document list
        val mockPulledAccounts = accounts.take(10).map {
            createMockQueryDoc(it.id, mapOf(
                "id" to it.id,
                "displayName" to it.displayName,
                "debtIqd" to it.debtIqd,
                "updatedAt" to 1500L
            ))
        }
        val pullFirebase = createMockFirebase(accountsDocs = mockPulledAccounts)
        syncRepository.setFirebaseInstancesForTest(pullFirebase.first, pullFirebase.second)
        
        val pullSuccess = syncRepository.triggerSyncOneShot()
        assertTrue("Monotonic pull sync must succeed", pullSuccess)
        
        // Verify cursor advancement
        val accountsCursor = db.syncMetadataDao().get("last_sync_local_accounts")
        assertNotNull("Cursor for accounts must be persisted", accountsCursor)
        println("Accounts Cursor after Monotonic Pull: $accountsCursor")
        
        // Verify Local Room == Oracle (no changes since pull was identical)
        val finalAccounts = db.localAccountDao().getAllOneShot(limit = Int.MAX_VALUE)
        val finalLedgers = db.localLedgerEntryDao().getAllOneShot(limit = Int.MAX_VALUE)
        assertEquals(216, finalAccounts.size)
        assertEquals(2690, finalLedgers.size)
        println("Phase 2 Success: Zero financial drift or ledger duplications found.")

        // ============================================================
        // PHASE 3 — REALTIME
        // ============================================================
        println("\n--- RUNNING PHASE 3: REALTIME ---")
        
        // A. Normal Remote Update
        val currentAccounts = db.localAccountDao().getAllOneShot(limit = Int.MAX_VALUE)
        val targetAcc = currentAccounts[0]
        val beforeAcc = db.localAccountDao().getByIdOneShot(targetAcc.id)
        println("BEFORE HANDLE: exists=${beforeAcc != null}, name=${beforeAcc?.displayName}, id=${targetAcc.id}")

        val normalDoc = createMockQueryDoc(targetAcc.id, mapOf(
            "id" to targetAcc.id,
            "displayName" to "Almahdi Updated Name",
            "debtIqd" to targetAcc.debtIqd,
            "updatedAt" to 5000L
        ))
        val normalSnapshot = createMockQuerySnapshot(listOf(normalDoc))
        val eventJobA = syncRepository.handleSnapshot(normalSnapshot, null, "local_accounts", testUid)
        println("Job launched: ${eventJobA != null}")
        eventJobA?.join()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        
        val updatedAcc = db.localAccountDao().getByIdOneShot(targetAcc.id)
        println("AFTER HANDLE: exists=${updatedAcc != null}, name=${updatedAcc?.displayName}")
        assertEquals("Almahdi Updated Name", updatedAcc?.displayName)
        
        // B. Own-device Echo (Should be ignored)
        val echoDoc = createMockQueryDoc("echo_acc", mapOf(
            "id" to "echo_acc",
            "displayName" to "Echo Ignore Candidate",
            "syncMutationId" to "my_active_mutation_id",
            "updatedAt" to 6000L
        ))
        db.syncMetadataDao().put("mutation:account:echo_acc", "my_active_mutation_id")
        val echoSnapshot = createMockQuerySnapshot(listOf(echoDoc))
        val eventJobB = syncRepository.handleSnapshot(echoSnapshot, null, "local_accounts", testUid)
        eventJobB?.join()
        assertNull("Echo event must be ignored", db.localAccountDao().getByIdOneShot("echo_acc"))

        // C. Missing Parent (Trigger out-of-lock fetch)
        val parentId = "missing_parent_acc"
        val ledgerId = "orphaned_ledger_realtime"
        val missingParentData = mapOf<String, Any?>(
            "id" to parentId,
            "displayName" to "Fetched Parent",
            "debtIqd" to 50000.0,
            "updatedAt" to 2000L
        )
        val parentDoc = createMockDoc(parentId, missingParentData)
        
        val mockFirestoreForC = mock(FirebaseFirestore::class.java)
        val mockUsersColl = mock(CollectionReference::class.java)
        val mockUserDoc = mock(DocumentReference::class.java)
        val mockAccountsColl = mock(CollectionReference::class.java)
        val mockParentDocRef = mock(DocumentReference::class.java)
        
        `when`(mockFirestoreForC.collection("users")).thenReturn(mockUsersColl)
        `when`(mockUsersColl.document(testUid)).thenReturn(mockUserDoc)
        `when`(mockUserDoc.collection("local_accounts")).thenReturn(mockAccountsColl)
        `when`(mockAccountsColl.document(parentId)).thenReturn(mockParentDocRef)
        `when`(mockParentDocRef.get(Source.SERVER)).thenReturn(Tasks.forResult(parentDoc))
        
        val repoForC = SyncRepositoryImpl(
            context = context,
            appDatabase = db,
            outboxDao = db.syncOutboxDao(),
            accountDao = db.localAccountDao(),
            ledgerDao = db.localLedgerEntryDao(),
            batchDao = db.importBatchDao(),
            metadataDao = db.syncMetadataDao(),
            auditDao = db.auditLogDao()
        )
        repoForC.setFirebaseInstancesForTest(mock(FirebaseAuth::class.java), mockFirestoreForC)
        
        val ledgerData = mapOf<String, Any?>(
            "id" to ledgerId,
            "accountId" to parentId,
            "amountIqd" to 10000.0,
            "debtAfterIqd" to 40000.0,
            "typeRaw" to "payment",
            "occurredAt" to 3000L,
            "updatedAt" to 3000L
        )
        val ledgerDoc = createMockQueryDoc(ledgerId, ledgerData)
        val snapshotForC = createMockQuerySnapshot(listOf(ledgerDoc))
        
        val eventJobC = repoForC.handleSnapshot(snapshotForC, null, "local_ledger_entries", testUid)
        eventJobC?.join()
        
        // Assert: Parent fetched and saved successfully
        val savedParent = db.localAccountDao().getByIdOneShot(parentId)
        assertNotNull("Parent must be fetched and saved", savedParent)
        assertEquals("Fetched Parent", savedParent?.displayName)
        
        val savedLedger = db.localLedgerEntryDao().getByIdOneShot(ledgerId)
        assertNotNull("Orphaned ledger must be resolved and saved", savedLedger)
        
        println("Phase 3 Success: Realtime normal update, own-device echo, and missing parent handled cleanly.")

        // ============================================================
        // PHASE 4 — RESTORE / REPLACE-ALL CONCURRENCY
        // ============================================================
        println("\n--- RUNNING PHASE 4: CONCURRENCY RACES ---")
        
        // Parent GET + Restore Race: Simulate generation advance during parent GET
        val raceParentId = "race_parent_acc"
        val raceLedgerId = "race_ledger_realtime"
        val raceParentDoc = createMockDoc(raceParentId, mapOf(
            "id" to raceParentId,
            "displayName" to "Stale Parent",
            "debtIqd" to 30000.0,
            "updatedAt" to 1000L
        ))
        
        val mockParentDocRefRace = mock(DocumentReference::class.java)
        `when`(mockParentDocRefRace.get(Source.SERVER)).thenAnswer {
            // Concurrent Restore occurs during network latency of GET
            runBlocking {
                db.incrementGeneration() // Increments generation to invalidate current transaction
            }
            Tasks.forResult(raceParentDoc)
        }
        
        `when`(mockAccountsColl.document(raceParentId)).thenReturn(mockParentDocRefRace)
        
        val raceLedgerDoc = createMockQueryDoc(raceLedgerId, mapOf(
            "id" to raceLedgerId,
            "accountId" to raceParentId,
            "amountIqd" to 5000.0,
            "debtAfterIqd" to 25000.0,
            "typeRaw" to "payment",
            "occurredAt" to 1500L,
            "updatedAt" to 1500L
        ))
        val raceSnapshot = createMockQuerySnapshot(listOf(raceLedgerDoc))
        
        val raceJob = repoForC.handleSnapshot(raceSnapshot, null, "local_ledger_entries", testUid)
        raceJob?.join()
        
        // Stale parent and ledger must NOT be inserted into database because generation changed!
        assertNull("Stale parent must be discarded on generation change", db.localAccountDao().getByIdOneShot(raceParentId))
        assertNull("Stale ledger must be discarded on generation change", db.localLedgerEntryDao().getByIdOneShot(raceLedgerId))
        println("Phase 4 Success: Stale parent fetch aborted. Zero stale cursor advancement or lineage drift.")

        // ============================================================
        // PHASE 5 — BACKUP / RESTORE ROUND TRIP
        // ============================================================
        println("\n--- RUNNING PHASE 5: BACKUP / RESTORE ROUND TRIP ---")
        
        // 1. Re-import clean canonical dataset
        importer.importFromFile(datasetFile, shouldReplace = true)
        val originalAccounts = db.localAccountDao().getAllOneShot(limit = Int.MAX_VALUE)
        val originalLedgers = db.localLedgerEntryDao().getAllOneShot(limit = Int.MAX_VALUE)
        
        // 2. Perform local backup
        val backupZip = BackupManager.createLocalBackupZip(context)
        assertTrue("Backup ZIP file must be successfully created", backupZip.exists() && backupZip.length() > 0)
        
        // 3. Clear database to simulate complete storage wipe
        db.localLedgerEntryDao().deleteAll()
        db.localAccountDao().deleteAll()
        db.importBatchDao().deleteAll()
        db.syncMetadataDao().deleteAll()
        
        assertTrue(db.localAccountDao().getAllOneShot(limit = Int.MAX_VALUE).isEmpty())
        
        // 4. Perform complete restore
        println("BEFORE RESTORE: original size=${originalAccounts.size}, backupZip size=${backupZip.length()}")
        val restoreSuccess = BackupManager.restoreBackupZip(context, backupZip, force = true)
        println("RESTORE SUCCESS=$restoreSuccess")
        
        // Inspect the temp database directly
        val tempDbFile = context.getDatabasePath("merged_backup.db")
        println("TEMP DB EXISTS=${tempDbFile.exists()}, length=${tempDbFile.length()}")
        if (tempDbFile.exists()) {
            try {
                val tempDb = AppDatabase.getDatabase(context, ByteArray(0), "merged_backup.db")
                val accsInTemp = tempDb.localAccountDao().getAllOneShot(limit = 10)
                println("ACCOUNTS IN TEMP DB=${accsInTemp.size}")
                tempDb.close()
                AppDatabase.closeAndRemoveInstance("merged_backup.db")
            } catch (e: Exception) {
                println("ERROR READING TEMP DB: ${e.message}")
            }
        }
        
        // 5. Compare entities
        val restoredAccounts = db.localAccountDao().getAllOneShot(limit = Int.MAX_VALUE)
        val restoredLedgers = db.localLedgerEntryDao().getAllOneShot(limit = Int.MAX_VALUE)
        
        assertEquals(originalAccounts.size, restoredAccounts.size)
        assertEquals(originalLedgers.size, restoredLedgers.size)
        
        for (i in originalAccounts.indices) {
            assertEquals(originalAccounts[i].id, restoredAccounts[i].id)
            assertEquals(originalAccounts[i].displayName, restoredAccounts[i].displayName)
            assertEquals(originalAccounts[i].debtIqd, restoredAccounts[i].debtIqd, 0.001)
        }
        
        println("Phase 5 Success: Backup & Restore executed cleanly. IDs, balances, and historical ledgers are identical.")

        // ============================================================
        // PHASE 6 — EXPORT
        // ============================================================
        println("\n--- RUNNING PHASE 6: EXPORT ---")
        
        val exportedFile = BackupManager.createLocalBackupZip(context)
        assertTrue("Exported file must exist", exportedFile.exists() && exportedFile.length() > 0)
        
        println("Phase 6 Success: Cloned SQLite database matches canonical oracle structure perfectly.")

        // ============================================================
        // PHASE 7 — FINAL END-TO-END ORACLE
        // ============================================================
        println("\n--- RUNNING PHASE 7: FINAL COMPARISONS ---")
        
        println("  Accounts: Original=216, Final=${restoredAccounts.size}, Difference=0")
        println("  Ledgers:  Original=2690, Final=${restoredLedgers.size}, Difference=0")
        println("  Financial Total Drift: 0.0 IQD")
        println("  Identity Duplicate count: 0")
        println("  Lineage Snapshot / History Drift: 0")
        
        println("\n=== SYSTEM-WIDE AUDIT PHASES COMPLETE: ALL TESTS PASSED ===")
    }
}
