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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Permanent Regression Test for BUG-11:
 * Remote Account Version Poisoning by Ledger Event Version.
 *
 * Invariant / Contract Protection:
 * - Claim: When a LedgerUpsert with a pre-fetched parent account is applied,
 *   the account remote version metadata ("remote_version:account:<id>") MUST be
 *   recorded using the parent account's authoritative server updatedAt, NOT the
 *   ledger event's remoteVersion.
 * - Test A: Poisoning is eliminated. Account (1000) and Ledger (5000) results in
 *   account metadata == 1000 (NOT 5000). Later AccountUpsert (1500) applies cleanly.
 * - Test B: Negative/stale control. Stale AccountUpsert (900 < 1000) is rejected as
 *   SKIPPED_DUPLICATE and local account remains unchanged.
 * - Test C: Existing parent control. When parent account already exists locally,
 *   LedgerUpsert skips prefetch block and does not overwrite existing account version.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Bug11ForensicReproductionTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var coordinator: RemoteSyncCoordinator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        coordinator = RemoteSyncCoordinator(
            accountDao = db.localAccountDao(),
            ledgerDao = db.localLedgerEntryDao(),
            batchDao = db.importBatchDao(),
            outboxDao = db.syncOutboxDao(),
            metadataDao = db.syncMetadataDao(),
            auditDao = db.auditLogDao(),
            appDatabase = db
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // =====================================================================
    // TEST A — POISONING IS ELIMINATED (Primary Regression)
    // =====================================================================
    @Test
    fun testA_ledgerParentPrefetch_preservesAccountRemoteVersion_andAllowsSubsequentAccountUpsert() = runBlocking {
        val accountId = "acc_bug11_test_a"
        val ledgerId = "led_bug11_test_a"

        // 1. Account document in Firestore has authoritative updatedAt = 1000L
        val preFetchedAccount = LocalAccount(
            id = accountId,
            displayName = "Original Account Name",
            earthlinkUsername = "user_test_a",
            debtIqd = 0.0,
            updatedAt = 1000L
        )

        // 2. Ledger document in Firestore belongs to this account with updatedAt = 5000L
        val ledgerEntry = LocalLedgerEntry(
            id = ledgerId,
            accountId = accountId,
            typeRaw = "took",
            amountIqd = 25000.0,
            debtAfterIqd = 25000.0,
            occurredAt = 5000L
        )

        // Precondition: Parent account is missing locally in Room
        assertNull(
            "Parent account must not exist in local Room prior to ledger event",
            db.localAccountDao().getByIdOneShot(accountId)
        )

        // 3. Process LedgerUpsert with attached preFetchedParentAccount
        val ledgerEvent = RemoteEvent.LedgerUpsert(
            entityId = ledgerId,
            remoteVersion = 5000L, // Belongs to the LEDGER
            source = RemoteEventSource.REALTIME,
            entry = ledgerEntry,
            preFetchedParentAccount = preFetchedAccount
        )

        val ledgerSyncResult = coordinator.processEvent(ledgerEvent)
        assertEquals("Ledger upsert must apply successfully", EventSyncResult.APPLIED, ledgerSyncResult)

        // 4. Assert Entity Separation:
        // Stored account version MUST be 1000 (Account updatedAt), NEVER 5000 (Ledger remoteVersion)
        val storedAccountVersion = db.syncMetadataDao().get("remote_version:account:$accountId")
        assertEquals(
            "Account metadata must contain authoritative account version (1000)",
            "1000",
            storedAccountVersion
        )
        assertNotEquals(
            "Account metadata must NEVER be poisoned with ledger version (5000)",
            "5000",
            storedAccountVersion
        )

        val storedLedgerVersion = db.syncMetadataDao().get("remote_version:ledger:$ledgerId")
        assertEquals("Ledger metadata must contain ledger version (5000)", "5000", storedLedgerVersion)

        // 5. Subsequent legitimate AccountUpsert arrives with version 1500L
        val updatedAccount = preFetchedAccount.copy(
            displayName = "Legitimate Updated Name",
            updatedAt = 1500L
        )
        val accountEvent = RemoteEvent.AccountUpsert(
            entityId = accountId,
            remoteVersion = 1500L,
            source = RemoteEventSource.REALTIME,
            account = updatedAccount
        )

        val accountSyncResult = coordinator.processEvent(accountEvent)

        // Fixed behavior: Must be APPLIED, NOT rejected as stale
        assertEquals(
            "Subsequent legitimate AccountUpsert must apply successfully",
            EventSyncResult.APPLIED,
            accountSyncResult
        )

        val persistedAccount = db.localAccountDao().getByIdOneShot(accountId)!!
        assertEquals(
            "Local account in Room must reflect the updated display name",
            "Legitimate Updated Name",
            persistedAccount.displayName
        )
        assertEquals(
            "Account metadata must advance to 1500",
            "1500",
            db.syncMetadataDao().get("remote_version:account:$accountId")
        )
    }

    // =====================================================================
    // TEST B — NEGATIVE / STALE CONTROL
    // =====================================================================
    @Test
    fun testB_negativeStaleControl_staleAccountUpsertRejectedAfterAuthoritativeVersionEstablished() = runBlocking {
        val accountId = "acc_bug11_test_b"
        val ledgerId = "led_bug11_test_b"

        val preFetchedAccount = LocalAccount(
            id = accountId,
            displayName = "Initial Authoritative Name",
            earthlinkUsername = "user_test_b",
            debtIqd = 0.0,
            updatedAt = 1000L
        )
        val ledgerEntry = LocalLedgerEntry(
            id = ledgerId,
            accountId = accountId,
            typeRaw = "took",
            amountIqd = 10000.0,
            debtAfterIqd = 10000.0,
            occurredAt = 5000L
        )

        val ledgerEvent = RemoteEvent.LedgerUpsert(
            entityId = ledgerId,
            remoteVersion = 5000L,
            source = RemoteEventSource.REALTIME,
            entry = ledgerEntry,
            preFetchedParentAccount = preFetchedAccount
        )
        coordinator.processEvent(ledgerEvent)

        // Account version is established at 1000L
        assertEquals("1000", db.syncMetadataDao().get("remote_version:account:$accountId"))

        // Stale AccountUpsert arrives with version 900L (< 1000L)
        val staleAccount = preFetchedAccount.copy(
            displayName = "Stale Name From The Past",
            updatedAt = 900L
        )
        val staleEvent = RemoteEvent.AccountUpsert(
            entityId = accountId,
            remoteVersion = 900L,
            source = RemoteEventSource.REALTIME,
            account = staleAccount
        )

        val staleResult = coordinator.processEvent(staleEvent)
        assertEquals(
            "Stale account event must be rejected as SKIPPED_DUPLICATE",
            EventSyncResult.SKIPPED_DUPLICATE,
            staleResult
        )

        // Local account must remain unchanged
        val currentAccount = db.localAccountDao().getByIdOneShot(accountId)!!
        assertEquals("Initial Authoritative Name", currentAccount.displayName)
        assertEquals("1000", db.syncMetadataDao().get("remote_version:account:$accountId"))
    }

    // =====================================================================
    // TEST C — EXISTING PARENT CONTROL PATH
    // =====================================================================
    @Test
    fun testC_existingParentControlPath_ledgerUpsertDoesNotOverwriteExistingAccountVersion() = runBlocking {
        val accountId = "acc_bug11_test_c"
        val ledgerId = "led_bug11_test_c"

        // Seed existing account directly into Room
        val existingAccount = LocalAccount(
            id = accountId,
            displayName = "Existing Account Pre-Seeded",
            earthlinkUsername = "user_test_c",
            debtIqd = 0.0,
            updatedAt = 2000L
        )
        db.localAccountDao().insert(existingAccount)
        db.syncMetadataDao().putMonotonicRemoteVersion("remote_version:account:$accountId", 2000L)

        // Ledger event arrives with preFetchedParentAccount having updatedAt = 1000L (or 5000L)
        val ledgerEntry = LocalLedgerEntry(
            id = ledgerId,
            accountId = accountId,
            typeRaw = "took",
            amountIqd = 15000.0,
            debtAfterIqd = 15000.0,
            occurredAt = 5000L
        )
        val candidatePrefetch = existingAccount.copy(displayName = "Should Not Overwrite", updatedAt = 1000L)

        val ledgerEvent = RemoteEvent.LedgerUpsert(
            entityId = ledgerId,
            remoteVersion = 5000L,
            source = RemoteEventSource.REALTIME,
            entry = ledgerEntry,
            preFetchedParentAccount = candidatePrefetch
        )

        val result = coordinator.processEvent(ledgerEvent)
        assertEquals(EventSyncResult.APPLIED, result)

        // Account was not overwritten by prefetch block
        val accountInDb = db.localAccountDao().getByIdOneShot(accountId)!!
        assertEquals("Existing Account Pre-Seeded", accountInDb.displayName)
        assertEquals("2000", db.syncMetadataDao().get("remote_version:account:$accountId"))

        // Ledger was applied normally
        assertEquals("5000", db.syncMetadataDao().get("remote_version:ledger:$ledgerId"))
    }
}
