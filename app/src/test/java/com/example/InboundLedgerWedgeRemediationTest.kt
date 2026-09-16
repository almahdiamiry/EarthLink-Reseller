package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.sync.EventSyncResult
import com.example.core.sync.RemoteEvent
import com.example.core.sync.RemoteEventSource
import com.example.core.sync.RemoteSyncCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Core Triad Standard:
 * 1. Claim: An inbound account container referenced by a dependent ledger must not be discarded
 *    solely because its authentic sourceExternalId collides with another physical container.
 *    The incoming physical account container must be persisted in Room with its authentic sourceExternalId
 *    (relaxed index under Room v18), allowing dependent ledgers to resolve their parent accountId,
 *    return APPLIED, advance the cursor, and prevent the inbound ledger cursor wedge.
 * 2. Seam / Environment: ROBOLECTRIC (In-memory Room database on Room schema v18).
 * 3. Independent Oracle: Explicit multi-container inbound sync contract per Target Product Contract v0.6
 *    and Final Independent Adjudication Memo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class InboundLedgerWedgeRemediationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var coordinator: RemoteSyncCoordinator

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
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
    fun testInboundAccountCollision_persistsIncomingContainer_andAllowsDependentLedgerToResolve() = runBlocking {
        val sharedExternalId = "EXT_123"
        val existingAccountId = "UUID_A"
        val incomingAccountId = "UUID_B"
        val incomingLedgerId = "LEDGER_B_01"

        // 1. Establish pre-existing LocalAccount A with authentic sourceExternalId = EXT_123
        val accountA = LocalAccount(
            id = existingAccountId,
            sourceExternalId = sharedExternalId,
            displayName = "Local Account A",
            earthlinkUsername = "user_a",
            currentPriceIqd = 35000.0,
            updatedAt = 1000L
        )
        db.localAccountDao().upsert(accountA)
        db.syncMetadataDao().putMonotonicRemoteVersion("remote_version:account:$existingAccountId", 1000L)

        // Verify Account A exists
        assertNotNull(db.localAccountDao().getByIdOneShot(existingAccountId))
        assertEquals(sharedExternalId, db.localAccountDao().getByIdOneShot(existingAccountId)?.sourceExternalId)

        // 2. Inbound AccountUpsert arrives for UUID_B with the same authentic sourceExternalId = EXT_123
        val incomingAccountB = LocalAccount(
            id = incomingAccountId,
            sourceExternalId = sharedExternalId,
            displayName = "Incoming Remote Account B",
            earthlinkUsername = "user_b",
            currentPriceIqd = 45000.0,
            updatedAt = 2000L
        )
        val accountUpsertEvent = RemoteEvent.AccountUpsert(
            entityId = incomingAccountId,
            remoteVersion = 2000L,
            source = RemoteEventSource.PULL,
            account = incomingAccountB
        )

        // Process incoming account upsert
        val accountResult = coordinator.processEvent(accountUpsertEvent)

        // REQUIRED ASSERTION: Inbound account must NOT be quarantined/dropped as QUARANTINED_CONFLICT.
        // It must be APPLIED and persisted directly in Room.
        assertEquals(
            "AccountUpsert with authentic colliding sourceExternalId must be APPLIED, not quarantined",
            EventSyncResult.APPLIED,
            accountResult
        )
        assertTrue("Account event result must allow cursor advancement", accountResult.canAdvanceCursor())

        // Verify incoming Account B is physically persisted in Room
        val persistedAccountB = db.localAccountDao().getByIdOneShot(incomingAccountId)
        assertNotNull("Incoming physical container UUID_B must be persisted in Room", persistedAccountB)
        assertEquals(
            "Incoming account must retain its authentic sourceExternalId",
            sharedExternalId,
            persistedAccountB?.sourceExternalId
        )

        // Verify existing Account A is still preserved unchanged
        val persistedAccountA = db.localAccountDao().getByIdOneShot(existingAccountId)
        assertNotNull("Pre-existing container UUID_A must remain intact", persistedAccountA)
        assertEquals(
            "Pre-existing account must retain its authentic sourceExternalId",
            sharedExternalId,
            persistedAccountA?.sourceExternalId
        )

        // 3. Inbound dependent LedgerUpsert arrives referencing accountId = UUID_B
        val incomingLedger = LocalLedgerEntry(
            id = incomingLedgerId,
            accountId = incomingAccountId,
            typeRaw = "gave",
            amountIqd = 25000.0,
            debtAfterIqd = 0.0,
            occurredAt = 2005L
        )
        val ledgerUpsertEvent = RemoteEvent.LedgerUpsert(
            entityId = incomingLedgerId,
            remoteVersion = 2005L,
            source = RemoteEventSource.PULL,
            entry = incomingLedger
        )

        // Process incoming ledger upsert
        val ledgerResult = coordinator.processEvent(ledgerUpsertEvent)

        // REQUIRED ASSERTION: Inbound dependent ledger must resolve its parent UUID_B and apply.
        // Must NOT return FAILED_RETRYABLE (which would wedge the sync cursor).
        assertEquals(
            "Dependent LedgerUpsert must resolve parent account UUID_B and return APPLIED",
            EventSyncResult.APPLIED,
            ledgerResult
        )
        assertTrue("Ledger event result must allow cursor advancement (no cursor wedge)", ledgerResult.canAdvanceCursor())

        // Verify ledger is physically persisted in Room with ownership anchored to UUID_B
        val persistedLedger = db.localLedgerEntryDao().getByIdOneShot(incomingLedgerId)
        assertNotNull("Incoming ledger must be persisted in Room", persistedLedger)
        assertEquals("Ledger accountId must be exactly UUID_B", incomingAccountId, persistedLedger?.accountId)
        assertEquals(25000.0, persistedLedger?.amountIqd ?: 0.0, 0.001)

        // 4. Outbox safety verification: inbound persistence must NOT create outbound echo mutations
        val pendingOutbox = db.syncOutboxDao().getPending()
        assertEquals(
            "Inbound sync persistence must not produce any outbound outbox echo mutations",
            0,
            pendingOutbox.size
        )
    }
}
