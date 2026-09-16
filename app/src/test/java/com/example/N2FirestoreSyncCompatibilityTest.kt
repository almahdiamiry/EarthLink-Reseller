package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.database.SyncOutboxDao
import com.example.core.model.LocalAccount
import com.example.core.model.SyncOutbox
import com.example.core.sync.RemoteEntityValidationResult
import com.example.core.sync.RemoteEntityValidator
import com.example.core.sync.SyncRepositoryImpl
import com.squareup.moshi.Moshi
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Core Triad:
 * Claim: INV-01 / INV-13 / Task 4: Firestore account synchronization transport & validation
 *        safely carries optional v18 ISP identity metadata (ispSubscriberId, ispUserIndex)
 *        across egress, ingress, and partial merge cycles without loss, corruption, or destruction
 *        of physical container identity or sourceExternalId provenance.
 * Seam / Environment: ROBOLECTRIC (in-memory Room DB + Moshi + SyncRepositoryImpl + RemoteEntityValidator).
 * Independent Oracle: Literal expected metadata values, explicit field-by-field assertions,
 *                      and independent validation contract oracles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class N2FirestoreSyncCompatibilityTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var outboxDao: SyncOutboxDao
    private lateinit var syncRepository: SyncRepositoryImpl
    private val moshi = Moshi.Builder().build()
    private val accountAdapter = moshi.adapter(LocalAccount::class.java)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        outboxDao = db.syncOutboxDao()
        syncRepository = SyncRepositoryImpl(
            context = context,
            appDatabase = db,
            outboxDao = outboxDao,
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

    private fun accountToOutboxItem(account: LocalAccount, outboxId: Int = 1): SyncOutbox {
        val json = accountAdapter.toJson(account)
        return SyncOutbox(
            id = outboxId,
            entityType = "local_accounts",
            entityId = account.id,
            operation = "upsert",
            payloadJson = json,
            status = "pending",
            attemptCount = 0,
            lastError = null,
            createdAt = System.currentTimeMillis()
        )
    }

    // -------------------------------------------------------------------------
    // TEST A — V18 -> V18 NEW-FIELD ROUND TRIP
    // -------------------------------------------------------------------------
    @Test
    @Suppress("UNCHECKED_CAST")
    fun testA_v18ToV18NewFieldRoundTrip() {
        val originalAccount = LocalAccount(
            id = UUID.randomUUID().toString(),
            sourceExternalId = "EXT_A_001",
            sourceBatchId = "BATCH_001",
            displayName = "Ahmed Al-Saadi",
            earthlinkUsername = "ahmed_saadi",
            phone1 = "07701234567",
            currentPriceIqd = 35000.0,
            debtIqd = 15000.0,
            ispSubscriberId = "earthlink:9912",
            ispUserIndex = 9912,
            createdAt = 1700000000000L,
            updatedAt = 1700000000000L
        )

        // Egress
        val outbox = accountToOutboxItem(originalAccount)
        val egressPayload = syncRepository.buildOutboxPayloadMap(outbox)

        assertEquals("Egress must carry ispSubscriberId", "earthlink:9912", egressPayload["ispSubscriberId"])
        assertEquals("Egress must carry ispUserIndex", 9912, egressPayload["ispUserIndex"])

        // Ingress
        val ingressResult = RemoteEntityValidator.validateAndMapAccount(
            id = originalAccount.id,
            d = egressPayload as Map<String, Any>,
            remoteUpdatedAt = 1700000500000L,
            existingLocalAccount = null
        )

        assertTrue(
            "Validation must succeed for valid round-trip payload: $ingressResult",
            ingressResult is RemoteEntityValidationResult.Valid
        )

        val reconstructed = (ingressResult as RemoteEntityValidationResult.Valid).entity
        assertEquals("Reconstructed id must match exactly", originalAccount.id, reconstructed.id)
        assertEquals("Reconstructed sourceExternalId must match exactly", originalAccount.sourceExternalId, reconstructed.sourceExternalId)
        assertEquals("Reconstructed displayName must match exactly", originalAccount.displayName, reconstructed.displayName)
        assertEquals("Reconstructed debtIqd must match exactly", 15000.0, reconstructed.debtIqd, 0.001)
        assertEquals("Reconstructed ispSubscriberId must match exactly", "earthlink:9912", reconstructed.ispSubscriberId)
        assertEquals("Reconstructed ispUserIndex must match exactly", 9912, reconstructed.ispUserIndex)
    }

    // -------------------------------------------------------------------------
    // TEST B — LEGACY PAYLOAD WITHOUT NEW FIELDS
    // -------------------------------------------------------------------------
    @Test
    fun testB_legacyPayloadWithoutNewFields() {
        val legacyPayload = mapOf<String, Any>(
            "displayName" to "Legacy Reseller User",
            "earthlinkUsername" to "legacy_user",
            "debtIqd" to 25000.0,
            "currentPriceIqd" to 40000.0,
            "stateSource" to "MANUAL",
            "isFullSnapshot" to true
        )

        val result = RemoteEntityValidator.validateAndMapAccount(
            id = "acc_legacy_001",
            d = legacyPayload,
            remoteUpdatedAt = 1700000000000L,
            existingLocalAccount = null
        )

        assertTrue("Legacy payload must be accepted: $result", result is RemoteEntityValidationResult.Valid)
        val account = (result as RemoteEntityValidationResult.Valid).entity
        assertEquals("DisplayName must match", "Legacy Reseller User", account.displayName)
        assertEquals("Debt must match", 25000.0, account.debtIqd, 0.001)
        assertNull("ispSubscriberId must remain null when absent in legacy payload", account.ispSubscriberId)
        assertNull("ispUserIndex must remain null when absent in legacy payload", account.ispUserIndex)
    }

    // -------------------------------------------------------------------------
    // TEST C — EXPLICIT NULL
    // -------------------------------------------------------------------------
    @Test
    fun testC_explicitNullPayload() {
        val explicitNullPayload = mutableMapOf<String, Any?>()
        explicitNullPayload["displayName"] = "Null Field User"
        explicitNullPayload["debtIqd"] = 0.0
        explicitNullPayload["isFullSnapshot"] = true
        explicitNullPayload["ispSubscriberId"] = null
        explicitNullPayload["ispUserIndex"] = null

        @Suppress("UNCHECKED_CAST")
        val result = RemoteEntityValidator.validateAndMapAccount(
            id = "acc_null_001",
            d = explicitNullPayload as Map<String, Any>,
            remoteUpdatedAt = 1700000000000L,
            existingLocalAccount = null
        )

        assertTrue("Explicit null payload must be accepted: $result", result is RemoteEntityValidationResult.Valid)
        val account = (result as RemoteEntityValidationResult.Valid).entity
        assertNull("ispSubscriberId must be null", account.ispSubscriberId)
        assertNull("ispUserIndex must be null", account.ispUserIndex)
    }

    // -------------------------------------------------------------------------
    // TEST D — ABSENT VS NULL
    // -------------------------------------------------------------------------
    @Test
    fun testD_absentVsExplicitNullComparison() {
        // 1. Without existing local account: both produce null
        val absentPayload = mapOf<String, Any>(
            "displayName" to "Absent User",
            "debtIqd" to 0.0,
            "isFullSnapshot" to true
        )
        val nullMap = mutableMapOf<String, Any?>()
        nullMap["displayName"] = "Null User"
        nullMap["debtIqd"] = 0.0
        nullMap["isFullSnapshot"] = true
        nullMap["ispSubscriberId"] = null
        nullMap["ispUserIndex"] = null

        val resAbsent = RemoteEntityValidator.validateAndMapAccount("acc_absent", absentPayload, 1000L, null)
        @Suppress("UNCHECKED_CAST")
        val resNull = RemoteEntityValidator.validateAndMapAccount("acc_null", nullMap as Map<String, Any>, 1000L, null)

        assertTrue(resAbsent is RemoteEntityValidationResult.Valid)
        assertTrue(resNull is RemoteEntityValidationResult.Valid)

        val accAbsent = (resAbsent as RemoteEntityValidationResult.Valid).entity
        val accNull = (resNull as RemoteEntityValidationResult.Valid).entity

        assertEquals(accAbsent.ispSubscriberId, accNull.ispSubscriberId)
        assertEquals(accAbsent.ispUserIndex, accNull.ispUserIndex)
        assertNull(accAbsent.ispSubscriberId)
        assertNull(accAbsent.ispUserIndex)

        // 2. With existing local account: under existing validator semantics, both fall back to existing
        val existing = LocalAccount(
            id = "acc_existing_base",
            displayName = "Base User",
            ispSubscriberId = "earthlink:5000",
            ispUserIndex = 5000,
            debtIqd = 0.0
        )

        val patchAbsent = mapOf<String, Any>(
            "payloadKind" to "PATCH",
            "note" to "Note update"
        )
        val patchNull = mutableMapOf<String, Any?>()
        patchNull["payloadKind"] = "PATCH"
        patchNull["note"] = "Note update"
        patchNull["ispSubscriberId"] = null
        patchNull["ispUserIndex"] = null

        val resPatchAbsent = RemoteEntityValidator.validateAndMapAccount("acc_existing_base", patchAbsent, 2000L, existing)
        @Suppress("UNCHECKED_CAST")
        val resPatchNull = RemoteEntityValidator.validateAndMapAccount("acc_existing_base", patchNull as Map<String, Any>, 2000L, existing)

        assertTrue(resPatchAbsent is RemoteEntityValidationResult.Valid)
        assertTrue(resPatchNull is RemoteEntityValidationResult.Valid)

        val accPatchAbsent = (resPatchAbsent as RemoteEntityValidationResult.Valid).entity
        val accPatchNull = (resPatchNull as RemoteEntityValidationResult.Valid).entity

        // Both fall back to existing values under proven repository convention
        assertEquals("earthlink:5000", accPatchAbsent.ispSubscriberId)
        assertEquals(5000, accPatchAbsent.ispUserIndex)
        assertEquals("earthlink:5000", accPatchNull.ispSubscriberId)
        assertEquals(5000, accPatchNull.ispUserIndex)
    }

    // -------------------------------------------------------------------------
    // TEST E — PARTIAL MERGE PRESERVATION
    // -------------------------------------------------------------------------
    @Test
    fun testE_partialMergePreservation() {
        val existing = LocalAccount(
            id = "acc_partial_001",
            sourceExternalId = "EXT_PARTIAL",
            displayName = "Partial User",
            phone1 = "07701111111",
            currentPriceIqd = 30000.0,
            debtIqd = 10000.0,
            ispSubscriberId = "earthlink:9912",
            ispUserIndex = 9912,
            note = "Original note"
        )

        // A partial update arriving from remote with only phone1 modified
        val partialRemoteMap = mapOf<String, Any>(
            "payloadKind" to "PATCH",
            "phone1" to "07702222222"
        )

        val result = RemoteEntityValidator.validateAndMapAccount(
            id = existing.id,
            d = partialRemoteMap,
            remoteUpdatedAt = 1700001000000L,
            existingLocalAccount = existing
        )

        assertTrue("Partial update must succeed: $result", result is RemoteEntityValidationResult.Valid)
        val updated = (result as RemoteEntityValidationResult.Valid).entity

        assertEquals("Phone1 must be updated", "07702222222", updated.phone1)
        assertEquals("DisplayName must be preserved from existing", "Partial User", updated.displayName)
        assertEquals("Debt must be preserved from existing", 10000.0, updated.debtIqd, 0.001)
        assertEquals("ispSubscriberId must NOT be cleared by partial update", "earthlink:9912", updated.ispSubscriberId)
        assertEquals("ispUserIndex must NOT be cleared by partial update", 9912, updated.ispUserIndex)
        assertEquals("Note must be preserved", "Original note", updated.note)
    }

    // -------------------------------------------------------------------------
    // TEST F — OLD-CLIENT ROUND-TRIP (SIMULATED MERGE PRESERVATION)
    // -------------------------------------------------------------------------
    @Test
    fun testF_oldClientRoundTripMergePreservation() {
        // Step 1: V18 client writes document to Firestore containing v18 fields
        val v18DocInCloud = mutableMapOf<String, Any?>(
            "displayName" to "Ahmed V18",
            "debtIqd" to 15000.0,
            "currentPriceIqd" to 35000.0,
            "stateSource" to "MANUAL",
            "isFullSnapshot" to true,
            "ispSubscriberId" to "earthlink:9912",
            "ispUserIndex" to 9912,
            "note" to "V18 note"
        )

        // Step 2: Older client (which knows nothing about ispSubscriberId / ispUserIndex)
        // updates only its known fields (e.g. note = "Old client updated note")
        // using Firestore SetOptions.merge().
        val oldClientUpdate = mapOf<String, Any?>(
            "note" to "Old client updated note"
        )

        // In Firestore, docRef.set(oldClientUpdate, SetOptions.merge()) merges the map:
        val mergedCloudDoc = HashMap(v18DocInCloud)
        for ((k, v) in oldClientUpdate) {
            mergedCloudDoc[k] = v
        }

        // Verify that in the merged document, N2 fields survived intact
        assertEquals("earthlink:9912", mergedCloudDoc["ispSubscriberId"])
        assertEquals(9912, mergedCloudDoc["ispUserIndex"])
        assertEquals("Old client updated note", mergedCloudDoc["note"])

        // Step 3: Later read by v18 client via RemoteEntityValidator
        @Suppress("UNCHECKED_CAST")
        val result = RemoteEntityValidator.validateAndMapAccount(
            id = "acc_old_client_merge",
            d = mergedCloudDoc as Map<String, Any>,
            remoteUpdatedAt = 1700002000000L,
            existingLocalAccount = null
        )

        assertTrue(result is RemoteEntityValidationResult.Valid)
        val account = (result as RemoteEntityValidationResult.Valid).entity
        assertEquals("Note must reflect old client's update", "Old client updated note", account.note)
        assertEquals("ispSubscriberId must survive old client merge write", "earthlink:9912", account.ispSubscriberId)
        assertEquals("ispUserIndex must survive old client merge write", 9912, account.ispUserIndex)
    }

    // -------------------------------------------------------------------------
    // TEST G — UNKNOWN TOP-LEVEL FIELD
    // -------------------------------------------------------------------------
    @Test
    fun testG_unknownTopLevelFieldTolerated() {
        val payloadWithFutureFields = mapOf<String, Any>(
            "displayName" to "Future Schema User",
            "debtIqd" to 10000.0,
            "currentPriceIqd" to 30000.0,
            "stateSource" to "MANUAL",
            "isFullSnapshot" to true,
            "futureProtocolVersion" to 42,
            "futureNestedConfig" to mapOf("experimentalFlag" to true),
            "ispSubscriberId" to "earthlink:1234",
            "ispUserIndex" to 1234
        )

        val result = RemoteEntityValidator.validateAndMapAccount(
            id = "acc_future_fields",
            d = payloadWithFutureFields,
            remoteUpdatedAt = 1700003000000L,
            existingLocalAccount = null
        )

        assertTrue("Unknown top-level fields must be safely tolerated: $result", result is RemoteEntityValidationResult.Valid)
        val account = (result as RemoteEntityValidationResult.Valid).entity
        assertEquals("DisplayName must be parsed", "Future Schema User", account.displayName)
        assertEquals("ispSubscriberId must be parsed", "earthlink:1234", account.ispSubscriberId)
        assertEquals("ispUserIndex must be parsed", 1234, account.ispUserIndex)
    }

    // -------------------------------------------------------------------------
    // TEST H — WRONG TYPE FOR ispUserIndex
    // -------------------------------------------------------------------------
    @Test
    fun testH_wrongTypeForIspUserIndex() {
        fun validateWithIndex(value: Any?): LocalAccount {
            val d = mutableMapOf<String, Any?>()
            d["displayName"] = "Numeric Test User"
            d["debtIqd"] = 0.0
            d["isFullSnapshot"] = true
            if (value != null) {
                d["ispUserIndex"] = value
            }
            @Suppress("UNCHECKED_CAST")
            val res = RemoteEntityValidator.validateAndMapAccount("acc_num", d as Map<String, Any>, 1000L, null)
            assertTrue("Validation must succeed: $res", res is RemoteEntityValidationResult.Valid)
            return (res as RemoteEntityValidationResult.Valid).entity
        }

        // Valid representations
        assertEquals("Int value preserved", 9912, validateWithIndex(9912).ispUserIndex)
        assertEquals("Long value converted to Int", 9912, validateWithIndex(9912L).ispUserIndex)
        assertEquals("Double value converted to Int", 9912, validateWithIndex(9912.0).ispUserIndex)

        // Absent / null
        assertNull("Null value results in null", validateWithIndex(null).ispUserIndex)

        // Invalid types -> safely evaluate to null (not crashing or corrupting)
        assertNull("String representation not parsed as number", validateWithIndex("9912").ispUserIndex)
        assertNull("Boolean representation not parsed as number", validateWithIndex(true).ispUserIndex)
        assertNull("Map representation not parsed as number", validateWithIndex(mapOf("num" to 9912)).ispUserIndex)
    }

    // -------------------------------------------------------------------------
    // TEST I — NULL / ABSENT / WRONG-TYPE EXISTING ACCOUNT FALLBACK
    // -------------------------------------------------------------------------
    @Test
    fun testI_existingAccountFallbackBehavior() {
        val existing = LocalAccount(
            id = "acc_fallback_test",
            displayName = "Original Display",
            debtIqd = 5000.0,
            ispSubscriberId = "earthlink:7777",
            ispUserIndex = 7777
        )

        // 1. Remote field absent -> falls back to existing
        val patchAbsent = mapOf<String, Any>(
            "payloadKind" to "PATCH",
            "displayName" to "New Display"
        )
        val resAbsent = RemoteEntityValidator.validateAndMapAccount(existing.id, patchAbsent, 2000L, existing)
        val accAbsent = (resAbsent as RemoteEntityValidationResult.Valid).entity
        assertEquals("New Display", accAbsent.displayName)
        assertEquals("earthlink:7777", accAbsent.ispSubscriberId)
        assertEquals(7777, accAbsent.ispUserIndex)

        // 2. Remote field explicitly null -> falls back to existing under current contract
        val patchNull = mutableMapOf<String, Any?>()
        patchNull["payloadKind"] = "PATCH"
        patchNull["ispSubscriberId"] = null
        patchNull["ispUserIndex"] = null
        @Suppress("UNCHECKED_CAST")
        val resNull = RemoteEntityValidator.validateAndMapAccount(existing.id, patchNull as Map<String, Any>, 2000L, existing)
        val accNull = (resNull as RemoteEntityValidationResult.Valid).entity
        assertEquals("earthlink:7777", accNull.ispSubscriberId)
        assertEquals(7777, accNull.ispUserIndex)

        // 3. Remote field wrong type -> falls back to existing
        val patchWrongType = mapOf<String, Any>(
            "payloadKind" to "PATCH",
            "ispUserIndex" to "invalid_number_string"
        )
        val resWrongType = RemoteEntityValidator.validateAndMapAccount(existing.id, patchWrongType, 2000L, existing)
        val accWrongType = (resWrongType as RemoteEntityValidationResult.Valid).entity
        assertEquals(7777, accWrongType.ispUserIndex)

        // 4. Remote field valid new value -> overwrites existing
        val patchNewValues = mapOf<String, Any>(
            "payloadKind" to "PATCH",
            "ispSubscriberId" to "earthlink:8888",
            "ispUserIndex" to 8888
        )
        val resNewValues = RemoteEntityValidator.validateAndMapAccount(existing.id, patchNewValues, 2000L, existing)
        val accNewValues = (resNewValues as RemoteEntityValidationResult.Valid).entity
        assertEquals("earthlink:8888", accNewValues.ispSubscriberId)
        assertEquals(8888, accNewValues.ispUserIndex)
    }
}
