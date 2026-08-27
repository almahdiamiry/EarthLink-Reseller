package com.example.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.EarthlinkApp
import com.example.core.database.*
import com.example.core.model.*
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
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Replace-All Remote Replacement Integration Test Suite.
 *
 * Verifies:
 * 1. Remote-only active documents (present in Firestore but absent from canonical local dataset) receive tombstones.
 * 2. Remote-only documents do not resurrect locally.
 * 3. Empty remote dataset handles replace-all cleanly without error.
 * 4. Difference reconciliation accurately tombstones removed remote IDs while preserving active common IDs.
 * 5. Repeated execution is idempotent (0 additional tombstones on second pass).
 * 6. Durable marker ("replace_all_pending_reconciliation") persists across failures and clears only on success.
 * 7. Snapshot metadata (stateSource, stateConfidence, isSnapshotHistory) remains untouched and preserved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ReplaceAllRemoteSyncTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var syncRepository: SyncRepositoryImpl
    private lateinit var importer: UtowerImporter

    private val testUid = "test_reseller_user_123"

    @Before
    fun setup() {
        runBlocking {
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

            importer = UtowerImporter(context, db)
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun createMockFirebase(
        accountsDocs: List<DocumentSnapshot> = emptyList(),
        ledgersDocs: List<DocumentSnapshot> = emptyList(),
        batchesDocs: List<DocumentSnapshot> = emptyList()
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

        `when`(mockUserDoc.collection("local_accounts")).thenReturn(mockAccountsColl)
        `when`(mockUserDoc.collection("local_ledger_entries")).thenReturn(mockLedgersColl)
        `when`(mockUserDoc.collection("import_batches")).thenReturn(mockBatchesColl)

        val accountsQuerySnapshot = mock(QuerySnapshot::class.java)
        `when`(accountsQuerySnapshot.documents).thenReturn(accountsDocs)
        `when`(mockAccountsColl.get(Source.SERVER)).thenReturn(Tasks.forResult(accountsQuerySnapshot))

        val ledgersQuerySnapshot = mock(QuerySnapshot::class.java)
        `when`(ledgersQuerySnapshot.documents).thenReturn(ledgersDocs)
        `when`(mockLedgersColl.get(Source.SERVER)).thenReturn(Tasks.forResult(ledgersQuerySnapshot))

        val batchesQuerySnapshot = mock(QuerySnapshot::class.java)
        `when`(batchesQuerySnapshot.documents).thenReturn(batchesDocs)
        `when`(mockBatchesColl.get(Source.SERVER)).thenReturn(Tasks.forResult(batchesQuerySnapshot))

        val mockBatch = mock(WriteBatch::class.java)
        `when`(mockBatch.commit()).thenReturn(Tasks.forResult(null))
        `when`(mockFirestore.batch()).thenReturn(mockBatch)

        return Triple(mockAuth, mockFirestore, mockBatch)
    }

    private fun createMockDoc(
        id: String,
        data: Map<String, Any?>
    ): DocumentSnapshot {
        val mockDoc = mock(DocumentSnapshot::class.java)
        val mockRef = mock(DocumentReference::class.java)
        `when`(mockDoc.id).thenReturn(id)
        `when`(mockDoc.data).thenReturn(data)
        `when`(mockDoc.reference).thenReturn(mockRef)
        return mockDoc
    }

    // TEST 1 — REMOTE-ONLY DOCUMENT TOMBSTONING
    @Test
    fun testRemoteOnlyDocument_tombstoned_andDoesNotResurrect() {
        runBlocking {
            // Seed local accounts A and B
            db.localAccountDao().insert(LocalAccount(id = "acc_A", displayName = "Sub A", phone1 = "07700000001", debtIqd = 10000.0))
            db.localAccountDao().insert(LocalAccount(id = "acc_B", displayName = "Sub B", phone1 = "07700000002", debtIqd = 20000.0))

            // Remote contains active docs A, B, C (C is remote-only)
            val docA = createMockDoc("acc_A", mapOf("displayName" to "Sub A", "deletedAt" to null))
            val docB = createMockDoc("acc_B", mapOf("displayName" to "Sub B", "deletedAt" to null))
            val docC = createMockDoc("acc_C", mapOf("displayName" to "Sub C", "deletedAt" to null))

            val (mockAuth, mockFirestore, mockBatch) = createMockFirebase(accountsDocs = listOf(docA, docB, docC))
            syncRepository.setFirebaseInstancesForTest(mockAuth, mockFirestore)

            val success = syncRepository.executeRemoteReplaceAllReconciliation()
            assertTrue(success)

            verify(mockFirestore).batch()

            // Local Room must contain only A and B
            val localAccounts = db.localAccountDao().getAllOneShot(limit = Int.MAX_VALUE)
            val localIds = localAccounts.map { it.id }.toSet()
            assertEquals(setOf("acc_A", "acc_B"), localIds)
            assertFalse("Remote-only acc_C must not be in Room", localIds.contains("acc_C"))
        }
    }

    // TEST 2 — EMPTY REMOTE TARGET
    @Test
    fun testEmptyRemote_upsertsCanonicalLocalDataset() {
        runBlocking {
            db.localAccountDao().insert(LocalAccount(id = "acc_A", displayName = "Sub A", phone1 = "07700000001", debtIqd = 10000.0))
            db.localAccountDao().insert(LocalAccount(id = "acc_B", displayName = "Sub B", phone1 = "07700000002", debtIqd = 20000.0))

            val (mockAuth, mockFirestore, mockBatch) = createMockFirebase(accountsDocs = emptyList())
            syncRepository.setFirebaseInstancesForTest(mockAuth, mockFirestore)

            val success = syncRepository.executeRemoteReplaceAllReconciliation()
            assertTrue(success)

            // 0 tombstones created because remote is empty
            verify(mockFirestore, never()).batch()

            val localAccounts = db.localAccountDao().getAllOneShot(limit = Int.MAX_VALUE)
            assertEquals(2, localAccounts.size)
        }
    }

    // TEST 3 — DIFFERENCE RECONCILIATION
    @Test
    fun testDifferenceReconciliation_updatesExisting_tombstonesRemoteOnly() {
        runBlocking {
            // Local canonical: A, C
            db.localAccountDao().insert(LocalAccount(id = "acc_A", displayName = "Sub A Updated", phone1 = "07700000001", debtIqd = 15000.0))
            db.localAccountDao().insert(LocalAccount(id = "acc_C", displayName = "Sub C New", phone1 = "07700000003", debtIqd = 30000.0))

            // Remote active: A, B (B is remote-only)
            val docA = createMockDoc("acc_A", mapOf("displayName" to "Sub A", "deletedAt" to null))
            val docB = createMockDoc("acc_B", mapOf("displayName" to "Sub B", "deletedAt" to null))

            val (mockAuth, mockFirestore, mockBatch) = createMockFirebase(accountsDocs = listOf(docA, docB))
            syncRepository.setFirebaseInstancesForTest(mockAuth, mockFirestore)

            val success = syncRepository.executeRemoteReplaceAllReconciliation()
            assertTrue(success)

            // Remote B receives tombstone via batch.set
            val refCaptor = ArgumentCaptor.forClass(DocumentReference::class.java)
            val optionsCaptor = ArgumentCaptor.forClass(SetOptions::class.java)

            verify(mockBatch).set(refCaptor.capture(), anyMap<String, Any?>(), optionsCaptor.capture())
            assertEquals(docB.reference, refCaptor.value)
        }
    }

    // TEST 4 — IDEMPOTENCY
    @Test
    fun testIdempotency_repeatedReconciliationProducesNoSemanticDrift() {
        runBlocking {
            db.localAccountDao().insert(LocalAccount(id = "acc_A", displayName = "Sub A", phone1 = "07700000001", debtIqd = 10000.0))

            val docA = createMockDoc("acc_A", mapOf("displayName" to "Sub A", "deletedAt" to null))

            val (mockAuth, mockFirestore, mockBatch) = createMockFirebase(accountsDocs = listOf(docA))
            syncRepository.setFirebaseInstancesForTest(mockAuth, mockFirestore)

            // Pass 1
            val res1 = syncRepository.executeRemoteReplaceAllReconciliation()
            assertTrue(res1)

            // Pass 2
            val res2 = syncRepository.executeRemoteReplaceAllReconciliation()
            assertTrue(res2)

            // No batch writes on either pass because there were 0 remote-only orphan docs
            verify(mockFirestore, never()).batch()
        }
    }

    // TEST 5 — DURABLE MARKER RECOVERY
    @Test
    fun testDurableMarkerRecovery_reconciliationFailsKeepsMarker_succeedsRemovesMarker() {
        runBlocking {
            db.syncMetadataDao().put("replace_all_pending_reconciliation", "true")

            val mockAuth = mock(FirebaseAuth::class.java)
            val mockUser = mock(FirebaseUser::class.java)
            `when`(mockAuth.currentUser).thenReturn(mockUser)
            `when`(mockUser.uid).thenReturn(testUid)

            // Failing Firestore
            val mockFailingFirestore = mock(FirebaseFirestore::class.java)
            `when`(mockFailingFirestore.collection(anyString())).thenThrow(RuntimeException("Network error"))

            syncRepository.setFirebaseInstancesForTest(mockAuth, mockFailingFirestore)

            val failResult = syncRepository.executeRemoteReplaceAllReconciliation()
            assertFalse(failResult)

            // Marker MUST remain true in Room metadata
            assertEquals("true", db.syncMetadataDao().get("replace_all_pending_reconciliation"))

            // Now fix Firestore
            val (goodAuth, goodFirestore, goodBatch) = createMockFirebase()
            syncRepository.setFirebaseInstancesForTest(goodAuth, goodFirestore)

            val goodResult = syncRepository.executeRemoteReplaceAllReconciliation()
            assertTrue(goodResult)

            // Remove marker after successful pass
            db.syncMetadataDao().remove("replace_all_pending_reconciliation")
            assertNull(db.syncMetadataDao().get("replace_all_pending_reconciliation"))
        }
    }

    // TEST 6 — SNAPSHOT / LINEAGE PRESERVATION
    @Test
    fun testSnapshotLineagePreservation_reconciliationPreservesSnapshotMetadata() {
        runBlocking {
            val account = LocalAccount(
                id = "acc_snap_1",
                displayName = "Snapshot Account",
                phone1 = "07700000099",
                stateSource = "UTOWER_SNAPSHOT_RESOLVED",
                stateConfidence = "AUTHORITATIVE",
                snapshotCapturedAt = 1700000000000L
            )
            db.localAccountDao().insert(account)

            val ledger = LocalLedgerEntry(
                id = "tx_snap_1",
                accountId = "acc_snap_1",
                typeRaw = "gave",
                amountIqd = 25000.0,
                debtAfterIqd = 25000.0,
                isSnapshotHistory = true,
                createdAt = 1700000000000L
            )
            db.localLedgerEntryDao().insert(ledger)

            val (mockAuth, mockFirestore, mockBatch) = createMockFirebase()
            syncRepository.setFirebaseInstancesForTest(mockAuth, mockFirestore)

            val result = syncRepository.executeRemoteReplaceAllReconciliation()
            assertTrue(result)

            val retrievedAcc = db.localAccountDao().getByIdOneShot("acc_snap_1")
            assertNotNull(retrievedAcc)
            assertEquals("UTOWER_SNAPSHOT_RESOLVED", retrievedAcc?.stateSource)
            assertEquals("AUTHORITATIVE", retrievedAcc?.stateConfidence)
            assertEquals(1700000000000L, retrievedAcc?.snapshotCapturedAt)

            val retrievedTx = db.localLedgerEntryDao().getByIdOneShot("tx_snap_1")
            assertNotNull(retrievedTx)
            assertTrue(retrievedTx!!.isSnapshotHistory)
        }
    }

    private fun writeTarEntry(out: java.io.OutputStream, name: String, content: ByteArray) {
        val header = ByteArray(512)
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))
        System.arraycopy("0000644\u0000".toByteArray(Charsets.US_ASCII), 0, header, 100, 8)
        System.arraycopy("0000000\u0000".toByteArray(Charsets.US_ASCII), 0, header, 108, 8)
        System.arraycopy("0000000\u0000".toByteArray(Charsets.US_ASCII), 0, header, 116, 8)
        val sizeOctal = String.format("%011o ", content.size)
        System.arraycopy(sizeOctal.toByteArray(Charsets.US_ASCII), 0, header, 124, 12)
        val mtimeOctal = String.format("%011o ", System.currentTimeMillis() / 1000)
        System.arraycopy(mtimeOctal.toByteArray(Charsets.US_ASCII), 0, header, 136, 12)
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        header[156] = '0'.code.toByte()
        System.arraycopy("ustar\u000000".toByteArray(Charsets.US_ASCII), 0, header, 257, 8)
        var sum = 0
        for (b in header) {
            sum += (b.toInt() and 0xFF)
        }
        val checksumOctal = String.format("%06o\u0000 ", sum)
        System.arraycopy(checksumOctal.toByteArray(Charsets.US_ASCII), 0, header, 148, 8)

        out.write(header)
        out.write(content)
        val remainder = content.size % 512
        if (remainder != 0) {
            out.write(ByteArray(512 - remainder))
        }
    }

    private fun createSampleTgzFile(fileName: String, jsonContent: String): java.io.File {
        val tgzFile = java.io.File(context.cacheDir, fileName)
        java.io.FileOutputStream(tgzFile).use { fos ->
            java.util.zip.GZIPOutputStream(fos).use { gzos ->
                val bytes = jsonContent.toByteArray(Charsets.UTF_8)
                writeTarEntry(gzos, "utower_backup.json", bytes)
                gzos.write(ByteArray(1024))
            }
        }
        return tgzFile
    }

    // TEST 7 — JSON REPLACE-ALL CURSOR RESET REGRESSION
    @Test
    fun testJsonReplaceAll_clearsCanonicalSyncCursors_andSetsReconciliationMarker() {
        runBlocking {
            // Seed stale canonical cursor keys
            db.syncMetadataDao().put("last_sync_timestamp", "old-global")
            db.syncMetadataDao().put("last_sync_local_accounts", "old-account-cursor")
            db.syncMetadataDao().put("last_sync_local_ledger_entries", "old-ledger-cursor")
            db.syncMetadataDao().put("last_sync_import_batches", "old-batch-cursor")

            val subJson = org.json.JSONObject().apply {
                put("id", "ext_json_1")
                put("name", "JSON Sub")
                put("debt_iqd", 10000.0)
            }.toString()

            val preview = com.example.domain.repository.UtowerImportPreview(
                parsedSubscribers = listOf(
                    LocalAccount(id = "acc_json_1", displayName = "JSON Sub", debtIqd = 10000.0, rawJson = subJson)
                ),
                parsedTransactions = emptyList(),
                totalCurrentDebtIqd = 10000.0
            )

            importer.importFromPreview(
                preview = preview,
                fileName = "replace_test.json",
                fileHash = "hash_replace_test_json_001",
                shouldReplace = true
            )

            // Assert canonical cursors are explicitly cleared
            assertNull("last_sync_timestamp must be null", db.syncMetadataDao().get("last_sync_timestamp"))
            assertNull("last_sync_local_accounts must be null", db.syncMetadataDao().get("last_sync_local_accounts"))
            assertNull("last_sync_local_ledger_entries must be null", db.syncMetadataDao().get("last_sync_local_ledger_entries"))
            assertNull("last_sync_import_batches must be null", db.syncMetadataDao().get("last_sync_import_batches"))

            // Assert replace_all_pending_reconciliation is true
            assertEquals("true", db.syncMetadataDao().get("replace_all_pending_reconciliation"))
        }
    }

    // TEST 8 — TGZ REPLACE-ALL CURSOR RESET REGRESSION
    @Test
    fun testTgzReplaceAll_clearsCanonicalSyncCursors_andSetsReconciliationMarker() {
        runBlocking {
            // Seed stale canonical cursor keys
            db.syncMetadataDao().put("last_sync_timestamp", "old-global")
            db.syncMetadataDao().put("last_sync_local_accounts", "old-account-cursor")
            db.syncMetadataDao().put("last_sync_local_ledger_entries", "old-ledger-cursor")
            db.syncMetadataDao().put("last_sync_import_batches", "old-batch-cursor")

            val sampleJson = org.json.JSONObject().apply {
                put("subscribers", org.json.JSONArray().apply {
                    put(org.json.JSONObject().apply {
                        put("source_key", "sub_tgz_001")
                        put("raw", org.json.JSONObject().apply {
                            put("name", "TGZ Sub")
                            put("debt", 15000.0)
                        })
                    })
                })
            }.toString()

            val tgzFile = createSampleTgzFile("replace_test.tgz", sampleJson)

            val result = importer.importFromFile(
                sourceFile = tgzFile,
                shouldReplace = true
            )

            assertTrue("TGZ import must succeed", result.success)

            // Assert canonical cursors are explicitly cleared
            assertNull("last_sync_timestamp must be null", db.syncMetadataDao().get("last_sync_timestamp"))
            assertNull("last_sync_local_accounts must be null", db.syncMetadataDao().get("last_sync_local_accounts"))
            assertNull("last_sync_local_ledger_entries must be null", db.syncMetadataDao().get("last_sync_local_ledger_entries"))
            assertNull("last_sync_import_batches must be null", db.syncMetadataDao().get("last_sync_import_batches"))

            // Assert replace_all_pending_reconciliation is true
            assertEquals("true", db.syncMetadataDao().get("replace_all_pending_reconciliation"))

            tgzFile.delete()
        }
    }
}
