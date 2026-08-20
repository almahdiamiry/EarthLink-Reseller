package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.sync.*
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Workstream 9D Certification Test: Full-Pipeline Lineage Verification for correctsEntryId.
 *
 * Verifies that correctsEntryId is strictly preserved across:
 * 1. Room -> Outbox payload (Moshi JSON serialization/deserialization).
 * 2. RemoteEntityValidator validation & mapping from simulated Firestore payload.
 * 3. RemoteSyncCoordinator standard upsert path into Room.
 * 4. Fallback preservation of existing local correctsEntryId when remote payload omits the field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Workstream9DLineagePipelineTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var accountRepo: LocalAccountRepositoryImpl
    private lateinit var ledgerRepo: LocalLedgerRepositoryImpl
    private lateinit var coordinator: RemoteSyncCoordinator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        accountRepo = LocalAccountRepositoryImpl(
            database = db,
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao()
        )

        ledgerRepo = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = db.localLedgerEntryDao(),
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao()
        )

        coordinator = RemoteSyncCoordinator(
            appDatabase = db,
            accountDao = db.localAccountDao(),
            ledgerDao = db.localLedgerEntryDao(),
            batchDao = db.importBatchDao(),
            metadataDao = db.syncMetadataDao(),
            auditDao = db.auditLogDao(),
            outboxDao = db.syncOutboxDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testRoom_To_Outbox_To_Moshi_Serialization_PreservesCorrectsEntryId() = runBlocking {
        val account = LocalAccount(
            id = "acc_pipe_001",
            displayName = "Pipeline Account",
            debtIqd = 100000.0
        )
        accountRepo.saveAccount(account)

        val originalEntry = ledgerRepo.recordAccountDebt(
            account = account,
            amount = 100000.0,
            note = "Original took 100k",
            idempotencyKey = "biz_orig_001"
        )
        assertEquals("biz_orig_001", originalEntry.id)
        assertNull(originalEntry.correctsEntryId)

        // Perform correction-by-difference: intended 70k -> correction gave 30k
        val correctionEntry = ledgerRepo.correctTransaction(
            originalEntryId = "biz_orig_001",
            intendedAmount = 70000.0,
            note = "Correction to 70k",
            idempotencyKey = "biz_corr_001"
        )
        assertEquals("biz_orig_001", correctionEntry.correctsEntryId)

        // Verify outbox entry was generated with valid payloadJson containing correctsEntryId
        val outbox = db.syncOutboxDao().getAllOneShot()
        val correctionOutbox = outbox.firstOrNull { it.entityId == correctionEntry.id }
        assertNotNull("Outbox must contain obligation for correction entry", correctionOutbox)
        assertTrue("Outbox payload must contain correctsEntryId field", correctionOutbox!!.payloadJson.contains("\"correctsEntryId\":\"biz_orig_001\""))

        // Verify Moshi adapter deserialization
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(LocalLedgerEntry::class.java)
        val deserialized = adapter.fromJson(correctionOutbox.payloadJson)
        assertNotNull(deserialized)
        assertEquals("biz_orig_001", deserialized!!.correctsEntryId)
        assertEquals(30000.0, deserialized.amountIqd, 0.001)
        assertEquals("gave", deserialized.typeRaw)
    }

    @Test
    fun testSimulatedFirestore_To_RemoteEntityValidator_To_Coordinator_PreservesLineage() = runBlocking {
        val account = LocalAccount(
            id = "acc_pipe_002",
            displayName = "Remote Lineage Account",
            debtIqd = 50000.0
        )
        db.localAccountDao().insert(account)

        val original = LocalLedgerEntry(
            id = "remote_orig_002",
            accountId = "acc_pipe_002",
            typeRaw = "took",
            amountIqd = 50000.0,
            debtAfterIqd = 50000.0,
            occurredAt = 1000L,
            createdAt = 1000L
        )
        db.localLedgerEntryDao().insert(original)

        // 1. Simulate Firestore payload from another device containing correctsEntryId
        val firestoreMap = mapOf<String, Any>(
            "id" to "remote_corr_002",
            "accountId" to "acc_pipe_002",
            "typeRaw" to "gave",
            "amountIqd" to 20000.0,
            "debtAfterIqd" to 30000.0,
            "occurredAt" to 2000L,
            "createdAt" to 2000L,
            "correctsEntryId" to "remote_orig_002"
        )

        val validationResult = RemoteEntityValidator.validateAndMapLedgerEntry(
            id = "remote_corr_002",
            d = firestoreMap,
            remoteUpdatedAt = 2000L,
            existingLocalLedgerEntry = null
        )

        assertTrue(validationResult is RemoteEntityValidationResult.Valid)
        val mappedEntry = (validationResult as RemoteEntityValidationResult.Valid).entity
        assertEquals("remote_orig_002", mappedEntry.correctsEntryId)

        // 2. Process event via RemoteSyncCoordinator
        val event = RemoteEvent.LedgerUpsert(
            entityId = mappedEntry.id,
            entry = mappedEntry,
            remoteVersion = 2000L,
            source = RemoteEventSource.REALTIME,
            syncMutationId = "mut_remote_corr_002",
            preFetchedParentAccount = null
        )
        val syncResult = coordinator.processEvent(event)
        assertEquals(EventSyncResult.APPLIED, syncResult)

        // 3. Verify in Room database
        val saved = db.localLedgerEntryDao().getByIdOneShot("remote_corr_002")
        assertNotNull(saved)
        assertEquals("remote_orig_002", saved!!.correctsEntryId)
    }

    @Test
    fun testRemoteEntityValidator_preservesExistingLocalCorrectsEntryIdWhenRemoteOmitted() {
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

        // Remote payload omits correctsEntryId (e.g. legacy sync payload)
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
}
