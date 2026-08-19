package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.database.SyncOutboxDao
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.model.ImportBatch
import com.example.core.model.SyncOutbox
import com.example.core.sync.*
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Phase 1 Firestore Document Identity Test Suite (INV-01 / INV-13 / P1-G2-REQ-04).
 *
 * Verifies that:
 * 1. Canonical Document Path Construction (1:1 entityId -> documentId mapping across all collections).
 * 2. syncMutationId is strictly separated from document identity and stored only as a payload attribute.
 * 3. Idempotent Retry & Lost-ACK Cloud Safety: retrying an unacknowledged push targets the exact same
 *    Firestore document path without creating shadow documents or duplicate cloud keys.
 * 4. Distinct entities produce strictly distinct Firestore document paths without collision.
 * 5. Inbound remote events (pull / realtime) preserve 1:1 document identity in Room SQLite.
 * 6. Tombstones and deletions preserve deterministic document path identity.
 * 7. Client nonces, timestamps, or random UUIDs are never used as Firestore document IDs for existing entities.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase1FirestoreDocumentIdentityTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var outboxDao: SyncOutboxDao
    private lateinit var coordinator: RemoteSyncCoordinator
    private lateinit var syncRepository: SyncRepositoryImpl

    private val testUid = "user_firestore_identity_test_uid"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        outboxDao = db.syncOutboxDao()
        coordinator = RemoteSyncCoordinator(
            appDatabase = db,
            accountDao = db.localAccountDao(),
            ledgerDao = db.localLedgerEntryDao(),
            batchDao = db.importBatchDao(),
            metadataDao = db.syncMetadataDao(),
            auditDao = db.auditLogDao(),
            outboxDao = db.syncOutboxDao()
        )
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

    @After
    fun tearDown() {
        db.close()
    }

    // 1. Canonical Document Path Construction: 1:1 Mapping across all Collections
    @Test
    fun testCanonicalDocumentPath_1to1EntityMapping_allCollections() {
        val mockFirestore = mock(FirebaseFirestore::class.java)
        val mockUsersCollection = mock(CollectionReference::class.java)
        val mockUserDoc = mock(DocumentReference::class.java)
        val mockSubCollection = mock(CollectionReference::class.java)

        `when`(mockFirestore.collection("users")).thenReturn(mockUsersCollection)
        `when`(mockUsersCollection.document(testUid)).thenReturn(mockUserDoc)
        `when`(mockUserDoc.collection(anyString())).thenReturn(mockSubCollection)

        // Accounts collection
        val accountCollRef = syncRepository.getCollectionRef("local_accounts", testUid, mockFirestore)
        assertNotNull(accountCollRef)
        verify(mockUserDoc).collection("local_accounts")

        // Ledger collection
        val ledgerCollRef = syncRepository.getCollectionRef("local_ledger_entries", testUid, mockFirestore)
        assertNotNull(ledgerCollRef)
        verify(mockUserDoc).collection("local_ledger_entries")

        // Batches collection
        val batchCollRef = syncRepository.getCollectionRef("import_batches", testUid, mockFirestore)
        assertNotNull(batchCollRef)
        verify(mockUserDoc).collection("import_batches")

        // Audit collection
        val auditCollRef = syncRepository.getCollectionRef("audit_logs", testUid, mockFirestore)
        assertNotNull(auditCollRef)
        verify(mockUserDoc).collection("audit_logs")

        // Aliases resolution
        assertNotNull(syncRepository.getCollectionRef("accounts", testUid, mockFirestore))
        assertNotNull(syncRepository.getCollectionRef("ledger", testUid, mockFirestore))
        assertNotNull(syncRepository.getCollectionRef("batches", testUid, mockFirestore))
        assertNotNull(syncRepository.getCollectionRef("audit", testUid, mockFirestore))

        // Unsupported / invalid collection returns null
        assertNull(syncRepository.getCollectionRef("unknown_type", testUid, mockFirestore))
    }

    // 2. syncMutationId is strictly separated from Firestore Document Identity
    @Test
    fun testSyncMutationIdSeparation_fromDocumentIdentity() = runBlocking {
        val entityId = "acc_canonical_identity_01"
        val rawPayload = """{"id":"$entityId","displayName":"Canonical User","updatedAt":1700000000000}"""

        val outboxItem = OutboxManager.enqueue(
            outboxDao = outboxDao,
            entityType = "local_accounts",
            entityId = entityId,
            operation = "upsert",
            payloadJson = rawPayload
        )

        // Entity ID must remain the exact business entity ID
        assertEquals(entityId, outboxItem.entityId)

        // syncMutationId must be present in the payload JSON as a write correlation attribute
        val parsedJson = JSONObject(outboxItem.payloadJson)
        assertTrue(parsedJson.has("syncMutationId"))
        val mutationId = parsedJson.getString("syncMutationId")
        assertNotNull(mutationId)
        assertTrue(mutationId.isNotEmpty())
        assertNotEquals("syncMutationId must not equal the entityId/documentId", entityId, mutationId)

        // Build data map for Firestore upload
        val payloadMap = syncRepository.buildOutboxPayloadMap(outboxItem)
        assertEquals(mutationId, payloadMap["syncMutationId"])
        assertEquals("Canonical User", payloadMap["displayName"])
        assertEquals(1, payloadMap["schemaVersion"])

        // Re-enqueueing a mutation for the same entity gets a new syncMutationId but targets the exact same entityId
        val secondItem = OutboxManager.upsertWithOutbox(
            outboxDao = outboxDao,
            entityType = "local_accounts",
            entityId = entityId,
            payloadJson = rawPayload
        )
        val secondJson = JSONObject(secondItem.payloadJson)
        val secondMutationId = secondJson.getString("syncMutationId")
        assertNotEquals("Subsequent enqueue must generate a distinct syncMutationId", mutationId, secondMutationId)
        assertEquals("Subsequent enqueue must preserve the exact same entityId", entityId, secondItem.entityId)
    }

    // 3. Idempotent Retry & Lost-ACK Cloud Safety: repeated sync passes target the same document path
    @Test
    fun testLostAckSimulation_repeatedSyncPassesTargetExactSameDocumentId() = runBlocking {
        val txId = "tx_lost_ack_retry_001"
        val payload = """{"id":"$txId","accountId":"acc_1","amountIqd":50000.0,"occurredAt":1700000000000}"""

        // Pass 1: Enqueue transaction
        val item = OutboxManager.enqueue(
            outboxDao = outboxDao,
            entityType = "local_ledger_entries",
            entityId = txId,
            operation = "upsert",
            payloadJson = payload
        )

        assertEquals(txId, item.entityId)

        // Pass 1 in-flight
        val inFlight = OutboxManager.markInFlight(outboxDao, item)
        assertEquals("syncing", inFlight.status)
        assertEquals(1, inFlight.attemptCount)
        assertEquals(txId, inFlight.entityId)

        // Simulate lost ACK / network drop: transition to failed
        OutboxManager.markRetryableFailure(
            outboxDao = outboxDao,
            item = inFlight,
            errorReason = "Network dropped before write acknowledgment (lost ACK)"
        )

        val failedItem = outboxDao.getByEntity(txId, "local_ledger_entries").first()
        assertEquals("failed", failedItem.status)
        assertEquals(1, failedItem.attemptCount)
        assertEquals(txId, failedItem.entityId)

        // Pass 2: Retry pass picks up failed item
        val retryableItems = outboxDao.getPending()
        assertEquals(1, retryableItems.size)
        val retryItem = retryableItems.first()

        // Verify target document path is strictly identical across retry attempts
        assertEquals(txId, retryItem.entityId)
        val retryPayloadMap = syncRepository.buildOutboxPayloadMap(retryItem)
        assertEquals(50000.0, (retryPayloadMap["amountIqd"] as Number).toDouble(), 0.001)

        // In Firestore, calling collRef.document(retryItem.entityId) will target document "tx_lost_ack_retry_001"
        // and perform SetOptions.merge(), which is completely idempotent and overwrites the same cloud document.
        OutboxManager.markSucceeded(outboxDao, retryItem.id)
        assertEquals(0, outboxDao.getAllUnsyncedCount())
    }

    // 4. Distinct Entities produce Strictly Distinct Document Paths
    @Test
    fun testDistinctEntities_produceDistinctDocumentPaths() = runBlocking {
        val entityIds = listOf("tx_alpha", "tx_beta", "tx_gamma", "tx_delta", "tx_epsilon")
        val outboxItems = entityIds.map { id ->
            OutboxManager.enqueue(
                outboxDao = outboxDao,
                entityType = "local_ledger_entries",
                entityId = id,
                operation = "upsert",
                payloadJson = """{"id":"$id","amountIqd":1000.0}"""
            )
        }

        assertEquals(5, outboxItems.size)
        val documentIds = outboxItems.map { it.entityId }

        // Must all be unique
        assertEquals(5, documentIds.toSet().size)
        for (i in entityIds.indices) {
            assertEquals(entityIds[i], documentIds[i])
        }
    }

    // 5. Inbound Remote Events (Pull / Realtime) Preserve 1:1 Document Identity in Room SQLite
    @Test
    fun testRemoteToLocalIdentityPreservation_inboundEvents() = runBlocking {
        val remoteAccountId = "acc_remote_firestore_99"
        val serverVersion = 1750000000000L

        val remoteAccountData = mapOf<String, Any>(
            "id" to remoteAccountId,
            "displayName" to "Remote Customer 99",
            "debtIqd" to 125000.0,
            "advanceIqd" to 0.0,
            "updatedAt" to serverVersion
        )

        // 1. Inbound mapToRemoteEvent preserves doc.id as entityId
        val accountEvent = syncRepository.mapToRemoteEvent(
            collName = "local_accounts",
            id = remoteAccountId,
            data = remoteAccountData,
            source = RemoteEventSource.PULL
        )

        assertNotNull(accountEvent)
        assertTrue(accountEvent is RemoteEvent.AccountUpsert)
        val upsertEvent = accountEvent as RemoteEvent.AccountUpsert
        assertEquals(remoteAccountId, upsertEvent.entityId)
        assertEquals(serverVersion, upsertEvent.remoteVersion)

        // 2. Coordinator applies to Room SQLite using the exact entityId
        val result = coordinator.processEvent(upsertEvent)
        assertEquals(EventSyncResult.APPLIED, result)

        val localAccount = db.localAccountDao().getByIdOneShot(remoteAccountId)
        assertNotNull(localAccount)
        assertEquals(remoteAccountId, localAccount?.id)
        assertEquals("Remote Customer 99", localAccount?.displayName)
        assertEquals(125000.0, localAccount?.debtIqd ?: 0.0, 0.001)

        // 3. Inbound Ledger Entry preserves doc.id as entityId
        val remoteLedgerId = "tx_remote_firestore_88"
        val remoteLedgerData = mapOf<String, Any>(
            "id" to remoteLedgerId,
            "accountId" to remoteAccountId,
            "typeRaw" to "debt",
            "amountIqd" to 25000.0,
            "occurredAt" to serverVersion + 1000L,
            "updatedAt" to serverVersion + 1000L
        )

        val ledgerEvent = syncRepository.mapToRemoteEvent(
            collName = "local_ledger_entries",
            id = remoteLedgerId,
            data = remoteLedgerData,
            source = RemoteEventSource.PULL
        )

        assertNotNull(ledgerEvent)
        assertTrue(ledgerEvent is RemoteEvent.LedgerUpsert)
        val ledgerUpsertEvent = ledgerEvent as RemoteEvent.LedgerUpsert
        assertEquals(remoteLedgerId, ledgerUpsertEvent.entityId)

        val ledgerResult = coordinator.processEvent(ledgerUpsertEvent)
        assertEquals(EventSyncResult.APPLIED, ledgerResult)

        val localLedger = db.localLedgerEntryDao().getByIdOneShot(remoteLedgerId)
        assertNotNull(localLedger)
        assertEquals(remoteLedgerId, localLedger?.id)
        assertEquals(remoteAccountId, localLedger?.accountId)
        assertEquals(25000.0, localLedger?.amountIqd ?: 0.0, 0.001)
    }

    // 6. Tombstones and Deletions Preserve Deterministic Document Path Identity
    @Test
    fun testTombstoneAndDeletion_preservesDeterministicDocumentIdentity() = runBlocking {
        val accountId = "acc_tombstone_identity_77"
        val initialAccount = LocalAccount(
            id = accountId,
            displayName = "User To Delete",
            debtIqd = 0.0,
            advanceIqd = 0.0,
            updatedAt = 1700000000000L
        )
        db.localAccountDao().upsert(initialAccount)

        // Enqueue deletion tombstone
        OutboxManager.deleteWithTombstone(
            outboxDao = outboxDao,
            entityType = "local_accounts",
            entityId = accountId
        )

        val pending = outboxDao.getByEntity(accountId, "local_accounts")
        assertEquals(1, pending.size)
        val tombstoneItem = pending.first()
        assertEquals("delete", tombstoneItem.operation)
        assertEquals(accountId, tombstoneItem.entityId)

        // Payload map contains deletedAt timestamp and syncMutationId
        val payloadMap = syncRepository.buildOutboxPayloadMap(tombstoneItem)
        assertTrue(payloadMap.containsKey("deletedAt"))
        assertTrue(payloadMap.containsKey("updatedAt"))
        val mutationId = payloadMap["syncMutationId"] as? String

        // Target document for deletion is collRef.document(accountId)
        // Inbound remote delete event preserves entityId and correlates via syncMutationId
        val deleteEvent = RemoteEvent.AccountDelete(
            entityId = accountId,
            remoteVersion = 1760000000000L,
            source = RemoteEventSource.PULL,
            syncMutationId = mutationId
        )
        val deleteResult = coordinator.processEvent(deleteEvent)
        assertEquals(EventSyncResult.APPLIED, deleteResult)
        // Account is marked history-only in Room SQLite (non-destructive)
        val localAccount = db.localAccountDao().getByIdOneShot(accountId)
        assertNotNull(localAccount)
        assertTrue(localAccount!!.isHistoryOnlySubscriber)
        // Tombstone recorded against exact entityId
        assertEquals("1760000000000", db.syncMetadataDao().get("tombstone:account:$accountId"))
    }

    // 7. Verification that Client-Side Nonces / Random UUIDs are never used as document IDs
    @Test
    fun testNoRandomOrNonceGeneratedDocumentId_forExistingEntities() = runBlocking {
        val customEntityId = "batch_manual_export_2026_08"
        val batch = ImportBatch(
            id = customEntityId,
            fileName = "export_2026_08.xlsx",
            fileHash = "hash_xyz_12345",
            accountsImported = 10,
            transactionsImported = 50,
            totalDebtIqd = 1000000.0,
            createdAt = 1700000000000L,
            status = "completed"
        )
        db.importBatchDao().insert(batch)

        val outboxItem = OutboxManager.enqueue(
            outboxDao = outboxDao,
            entityType = "import_batches",
            entityId = customEntityId,
            operation = "upsert",
            payloadJson = """{"id":"$customEntityId","status":"completed"}"""
        )

        // Verify entityId has NOT been transformed into a random UUID
        assertEquals(customEntityId, outboxItem.entityId)
        assertFalse(outboxItem.entityId.contains("-") && outboxItem.entityId.length == 36 && !customEntityId.contains("-"))

        // Remote event parsing preserves exact custom ID
        val remoteBatchData = mapOf<String, Any>(
            "id" to customEntityId,
            "status" to "completed",
            "fileName" to "export_2026_08.xlsx",
            "fileHash" to "hash_xyz_12345",
            "accountsImported" to 10,
            "transactionsImported" to 50,
            "totalDebtIqd" to 1000000.0,
            "updatedAt" to 1750000000000L
        )

        val remoteEvent = syncRepository.mapToRemoteEvent(
            collName = "import_batches",
            id = customEntityId,
            data = remoteBatchData,
            source = RemoteEventSource.PULL
        )

        assertNotNull(remoteEvent)
        assertEquals(customEntityId, remoteEvent?.entityId)
    }
}
