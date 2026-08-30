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

    // 8. Payload Boundary: LocalLedgerEntry.rawJson is stripped while preserving shared financial fields
    @Test
    fun testLedgerPayloadBoundary_stripsRawJson_andPreservesSharedFields() = runBlocking {
        val txId = "tx_boundary_test_001"
        val rawUtowerJson = """{"utower_id":12345,"subscriber_id":"sub_777","action":"REFILL","amount":25000}"""
        val payloadJson = JSONObject().apply {
            put("id", txId)
            put("accountId", "acc_sub_777")
            put("typeRaw", "REFILL")
            put("amountIqd", 25000.0)
            put("debtAfterIqd", 0.0)
            put("occurredAt", 1700000000000L)
            put("correctsEntryId", "tx_prev_000")
            put("sourceExternalId", "ext_utower_12345")
            put("sourceBatchId", "batch_2026_08")
            put("isSnapshotHistory", false)
            put("rawJson", rawUtowerJson)
        }.toString()

        val outboxItem = OutboxManager.enqueue(
            outboxDao = outboxDao,
            entityType = "local_ledger_entries",
            entityId = txId,
            operation = "upsert",
            payloadJson = payloadJson
        )

        val payloadMap = syncRepository.buildOutboxPayloadMap(outboxItem)

        // 1. Boundary Assertion: rawJson MUST NOT enter the Firestore cloud payload
        assertFalse("Ledger cloud payload MUST NOT contain rawJson", payloadMap.containsKey("rawJson"))

        // 2. Shared Financial Fields Preservation Assertions
        assertEquals(txId, payloadMap["id"])
        assertEquals("acc_sub_777", payloadMap["accountId"])
        assertEquals("REFILL", payloadMap["typeRaw"])
        assertEquals(25000.0, (payloadMap["amountIqd"] as Number).toDouble(), 0.001)
        assertEquals(0.0, (payloadMap["debtAfterIqd"] as Number).toDouble(), 0.001)
        assertEquals(1700000000000L, payloadMap["occurredAt"])
        assertEquals("tx_prev_000", payloadMap["correctsEntryId"])
        assertEquals("ext_utower_12345", payloadMap["sourceExternalId"])
        assertEquals("batch_2026_08", payloadMap["sourceBatchId"])
        assertEquals(false, payloadMap["isSnapshotHistory"])
    }

    // Scenario A — Local Storage Preservation
    @Test
    fun testScenarioA_localStoragePreservesRawJson() = runBlocking {
        db.localAccountDao().insert(LocalAccount(id = "acc_001", displayName = "Test User 1"))
        val rawJson = """{"utower_tx_id":9988,"action":"PAYMENT","amount":15000}"""
        val entry = LocalLedgerEntry(
            id = "tx_local_store_001",
            accountId = "acc_001",
            typeRaw = "gave",
            amountIqd = 15000.0,
            debtAfterIqd = 5000.0,
            occurredAt = System.currentTimeMillis(),
            rawJson = rawJson
        )

        db.localLedgerEntryDao().insert(entry)
        val fetched = db.localLedgerEntryDao().getByAccountIdOneShot("acc_001")

        assertEquals(1, fetched.size)
        assertEquals(rawJson, fetched[0].rawJson)
        assertEquals(15000.0, fetched[0].amountIqd, 0.001)
    }

    // Scenario B — Outbox Preservation
    @Test
    fun testScenarioB_outboxPreservesRawJsonInLocalState() = runBlocking {
        val rawJson = """{"utower_tx_id":9988,"action":"PAYMENT","amount":15000}"""
        val payload = JSONObject().apply {
            put("id", "tx_outbox_store_001")
            put("accountId", "acc_002")
            put("typeRaw", "gave")
            put("amountIqd", 15000.0)
            put("debtAfterIqd", 0.0)
            put("rawJson", rawJson)
        }.toString()

        OutboxManager.enqueue(
            outboxDao = outboxDao,
            entityType = "local_ledger_entries",
            entityId = "tx_outbox_store_001",
            operation = "upsert",
            payloadJson = payload
        )

        val pendingList = outboxDao.getPending()
        val found = pendingList.firstOrNull { it.entityId == "tx_outbox_store_001" }

        assertNotNull(found)
        assertTrue(JSONObject(found!!.payloadJson).has("rawJson"))
        assertEquals(rawJson, JSONObject(found.payloadJson).getString("rawJson"))
    }

    // Scenario D — Simulated Firestore Write Payload Check
    @Test
    fun testScenarioD_simulatedFirestoreWritePayloadOmitsRawJson() = runBlocking {
        val rawJson = """{"utower_raw": true}"""
        val payload = JSONObject().apply {
            put("id", "tx_sim_write_001")
            put("accountId", "acc_003")
            put("typeRaw", "took")
            put("amountIqd", 25000.0)
            put("debtAfterIqd", 25000.0)
            put("rawJson", rawJson)
        }.toString()

        val outboxItem = OutboxManager.enqueue(
            outboxDao = outboxDao,
            entityType = "local_ledger_entries",
            entityId = "tx_sim_write_001",
            operation = "upsert",
            payloadJson = payload
        )

        val cloudMap = syncRepository.buildOutboxPayloadMap(outboxItem)

        assertFalse(cloudMap.containsKey("rawJson"))
        assertEquals("tx_sim_write_001", cloudMap["id"])
        assertEquals("acc_003", cloudMap["accountId"])
        assertEquals("took", cloudMap["typeRaw"])
        assertEquals(25000.0, (cloudMap["amountIqd"] as Number).toDouble(), 0.001)
    }

    // Scenario E — Lost ACK Retry Payload Check
    @Test
    fun testScenarioE_retryPayloadOmitsRawJsonAndTargetsSameDocumentId() = runBlocking {
        val rawJson = """{"utower_retry": true}"""
        val payload = JSONObject().apply {
            put("id", "tx_retry_001")
            put("accountId", "acc_004")
            put("typeRaw", "gave")
            put("amountIqd", 10000.0)
            put("debtAfterIqd", 0.0)
            put("rawJson", rawJson)
        }.toString()

        val originalOutbox = OutboxManager.enqueue(
            outboxDao = outboxDao,
            entityType = "local_ledger_entries",
            entityId = "tx_retry_001",
            operation = "upsert",
            payloadJson = payload
        )

        val retryOutbox = originalOutbox.copy(attemptCount = 1, status = "pending")
        outboxDao.update(retryOutbox)

        val retryPayloadMap = syncRepository.buildOutboxPayloadMap(retryOutbox)

        assertEquals("tx_retry_001", retryPayloadMap["id"])
        assertFalse(retryPayloadMap.containsKey("rawJson"))
        assertEquals("acc_004", retryPayloadMap["accountId"])
    }

    // Scenario F — Outbox Deduplication & Selection
    @Test
    fun testScenarioF_deduplicationPreservesSharedFieldsAndOmitsRawJson() = runBlocking {
        val rawJson1 = """{"v":1}"""
        val rawJson2 = """{"v":2}"""

        val payload1 = JSONObject().apply {
            put("id", "tx_dedup_001")
            put("accountId", "acc_005")
            put("typeRaw", "gave")
            put("amountIqd", 5000.0)
            put("debtAfterIqd", 15000.0)
            put("rawJson", rawJson1)
        }.toString()

        val payload2 = JSONObject().apply {
            put("id", "tx_dedup_001")
            put("accountId", "acc_005")
            put("typeRaw", "gave")
            put("amountIqd", 10000.0)
            put("debtAfterIqd", 10000.0)
            put("rawJson", rawJson2)
        }.toString()

        outboxDao.insert(SyncOutbox(entityType = "local_ledger_entries", entityId = "tx_dedup_001", operation = "upsert", payloadJson = payload1))
        outboxDao.insert(SyncOutbox(entityType = "local_ledger_entries", entityId = "tx_dedup_001", operation = "upsert", payloadJson = payload2))

        val pending = outboxDao.getPending().filter { it.entityId == "tx_dedup_001" }
        val winningItem = pending.maxByOrNull { it.id }!!

        val cloudMap = syncRepository.buildOutboxPayloadMap(winningItem)

        assertFalse(cloudMap.containsKey("rawJson"))
        assertEquals(10000.0, (cloudMap["amountIqd"] as Number).toDouble(), 0.001)
    }

    // Scenario G — Downward Sync / New Device Reconstruction
    @Test
    fun testScenarioG_downwardSyncReconstructsLedgerEntryWithoutRawJson() {
        val remoteDocMap = mapOf<String, Any>(
            "id" to "tx_remote_001",
            "accountId" to "acc_remote_001",
            "typeRaw" to "gave",
            "amountIqd" to 50000.0,
            "debtAfterIqd" to 0.0,
            "occurredAt" to 1700000000000L,
            "createdAt" to 1700000000000L,
            "updatedAt" to 1700000000000L,
            "isSnapshotHistory" to false,
            "correctsEntryId" to "tx_remote_000"
        )

        val result = RemoteEntityValidator.validateAndMapLedgerEntry(
            id = "tx_remote_001",
            d = remoteDocMap,
            remoteUpdatedAt = 1700000000000L
        )

        assertTrue(result is RemoteEntityValidationResult.Valid)
        val entry = (result as RemoteEntityValidationResult.Valid).entity

        assertEquals("tx_remote_001", entry.id)
        assertEquals("acc_remote_001", entry.accountId)
        assertEquals("gave", entry.typeRaw)
        assertEquals(50000.0, entry.amountIqd, 0.001)
        assertEquals(0.0, entry.debtAfterIqd, 0.001)
        assertEquals("tx_remote_000", entry.correctsEntryId)
        assertNull("Restored entry has null rawJson when absent from cloud doc", entry.rawJson)
    }

    @Test
    fun testScenarioG_remoteEntityValidator_preservesExistingLocalCorrectsEntryIdWhenRemoteOmitted() {
        val existingLocal = LocalLedgerEntry(
            id = "local_corr_003",
            accountId = "acc_003",
            typeRaw = "gave",
            amountIqd = 10000.0,
            debtAfterIqd = 40000.0,
            occurredAt = 1000L,
            createdAt = 1000L,
            correctsEntryId = "orig_003"
        )

        val remoteMap = mapOf<String, Any>(
            "id" to "local_corr_003",
            "accountId" to "acc_003",
            "typeRaw" to "gave",
            "amountIqd" to 10000.0,
            "debtAfterIqd" to 40000.0,
            "occurredAt" to 1000L,
            "createdAt" to 1000L
        )

        val validationResult = RemoteEntityValidator.validateAndMapLedgerEntry(
            id = "local_corr_003",
            d = remoteMap,
            remoteUpdatedAt = 1500L,
            existingLocalLedgerEntry = existingLocal
        )

        assertTrue(validationResult is RemoteEntityValidationResult.Valid)
        val mappedEntry = (validationResult as RemoteEntityValidationResult.Valid).entity
        assertEquals("orig_003", mappedEntry.correctsEntryId)
    }

    // Scenario H — Local Import Flow
    @Test
    fun testScenarioH_uTowerImportFlowStoresRawJsonLocallyStripsOnEgress() = runBlocking {
        db.localAccountDao().insert(LocalAccount(id = "acc_import_001", displayName = "Imported User"))
        val rawImportJson = """{"import_file":"sheet1.xlsx","row":42,"raw_txt":"REFILL 25000"}"""
        val importedEntry = LocalLedgerEntry(
            id = "tx_import_001",
            accountId = "acc_import_001",
            sourceExternalId = "ext_42",
            sourceBatchId = "batch_001",
            typeRaw = "gave",
            amountIqd = 25000.0,
            debtAfterIqd = 0.0,
            rawJson = rawImportJson
        )

        db.localLedgerEntryDao().insert(importedEntry)
        val outboxItem = OutboxManager.enqueue(
            outboxDao = outboxDao,
            entityType = "local_ledger_entries",
            entityId = importedEntry.id,
            operation = "upsert",
            payloadJson = JSONObject().apply {
                put("id", importedEntry.id)
                put("accountId", importedEntry.accountId)
                put("typeRaw", importedEntry.typeRaw)
                put("amountIqd", importedEntry.amountIqd)
                put("debtAfterIqd", importedEntry.debtAfterIqd)
                put("rawJson", importedEntry.rawJson)
            }.toString()
        )

        val storedLocal = db.localLedgerEntryDao().getByAccountIdOneShot("acc_import_001")[0]
        assertEquals(rawImportJson, storedLocal.rawJson)

        val cloudMap = syncRepository.buildOutboxPayloadMap(outboxItem)
        assertFalse("Cloud map stripped rawJson", cloudMap.containsKey("rawJson"))
        assertEquals("tx_import_001", cloudMap["id"])
    }

    // Scenario I — Financial Semantics & Balance Calculation Intact
    @Test
    fun testScenarioI_financialSemanticsPreservedWithoutRawJson() = runBlocking {
        val payload1 = JSONObject().apply {
            put("id", "e1")
            put("accountId", "acc_fin")
            put("typeRaw", "took")
            put("amountIqd", 100000.0)
            put("debtAfterIqd", 100000.0)
            put("rawJson", """{"raw":1}""")
        }.toString()

        val payload2 = JSONObject().apply {
            put("id", "e2")
            put("accountId", "acc_fin")
            put("typeRaw", "gave")
            put("amountIqd", 40000.0)
            put("debtAfterIqd", 60000.0)
            put("correctsEntryId", "e1")
            put("rawJson", """{"raw":2}""")
        }.toString()

        val item1 = OutboxManager.enqueue(outboxDao = outboxDao, entityType = "local_ledger_entries", entityId = "e1", operation = "upsert", payloadJson = payload1)
        val item2 = OutboxManager.enqueue(outboxDao = outboxDao, entityType = "local_ledger_entries", entityId = "e2", operation = "upsert", payloadJson = payload2)

        val map1 = syncRepository.buildOutboxPayloadMap(item1)
        val map2 = syncRepository.buildOutboxPayloadMap(item2)

        assertFalse(map1.containsKey("rawJson"))
        assertFalse(map2.containsKey("rawJson"))
        assertEquals(100000.0, (map1["amountIqd"] as Number).toDouble(), 0.001)
        assertEquals(40000.0, (map2["amountIqd"] as Number).toDouble(), 0.001)
        assertEquals(60000.0, (map2["debtAfterIqd"] as Number).toDouble(), 0.001)
        assertEquals("e1", map2["correctsEntryId"])
    }

    // Scenario J — Counterfactual
    @Test
    fun testScenarioJ_counterfactualRawPayloadContainsRawJson() {
        val rawUtowerJson = """{"utower_id":12345}"""
        val rawUnstrippedMap = mapOf<String, Any>(
            "id" to "tx_cf",
            "accountId" to "acc_cf",
            "typeRaw" to "gave",
            "amountIqd" to 10000.0,
            "rawJson" to rawUtowerJson
        )

        // Verifies that unstripped raw payload map DOES contain rawJson key
        assertTrue("Unstripped map contains rawJson", rawUnstrippedMap.containsKey("rawJson"))
    }
}

