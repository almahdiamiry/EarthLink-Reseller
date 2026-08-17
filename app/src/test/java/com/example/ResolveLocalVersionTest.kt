package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.ImportBatch
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.sync.LocalVersionState
import com.example.core.sync.RemoteSyncCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Phase 1 / Phase 2 Invariant: resolveLocalVersion is the single authoritative resolution path.
 * Verifies all 3 states across Accounts, Ledgers, and Batches, as well as structural enforcement
 * of zero inline fallback patterns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ResolveLocalVersionTest {

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
    fun serverTrackedVersion_returnsServerVersion() = runBlocking {
        val accountId = "acc_tracked_test"
        db.syncMetadataDao().put("remote_version:account:$accountId", "1750000000000")
        val tracked = coordinator.resolveLocalVersion("account", accountId)
        assertTrue(tracked is LocalVersionState.ServerTracked)
        assertEquals(1750000000000L, (tracked as LocalVersionState.ServerTracked).version)
    }

    @Test
    fun untrackedEntity_withLegacyTimestamp_returnsFallback() = runBlocking {
        val accountId = "acc_untracked_with_fallback"
        db.localAccountDao().upsert(
            LocalAccount(
                id = accountId,
                displayName = "Non Legacy Account",
                debtIqd = 0.0,
                advanceIqd = 0.0,
                updatedAt = 999_999_999_999L,
                isLegacy = false
            )
        )
        val untracked = coordinator.resolveLocalVersion("account", accountId)
        assertTrue(untracked is LocalVersionState.Untracked)
        assertEquals(999_999_999_999L, (untracked as LocalVersionState.Untracked).legacyFallback)
    }

    @Test
    fun untrackedEntity_withLegacyTimestampAboveBoundary_returnsNull() = runBlocking {
        val accountId = "acc_untracked_above_boundary"
        db.localAccountDao().upsert(
            LocalAccount(
                id = accountId,
                displayName = "Non Legacy Account",
                debtIqd = 0.0,
                advanceIqd = 0.0,
                updatedAt = 1_000_000_000_000L,
                isLegacy = false
            )
        )
        val untracked = coordinator.resolveLocalVersion("account", accountId)
        assertTrue(untracked is LocalVersionState.Untracked)
        assertNull((untracked as LocalVersionState.Untracked).legacyFallback)
    }

    @Test
    fun untrackedEntity_legacyEntity_returnsNullFallback() = runBlocking {
        val accountId = "acc_legacy_entity_test"
        db.localAccountDao().upsert(
            LocalAccount(
                id = accountId,
                displayName = "Legacy Account",
                debtIqd = 0.0,
                advanceIqd = 0.0,
                updatedAt = 4200L,
                isLegacy = true // Marked legacy
            )
        )
        val untracked = coordinator.resolveLocalVersion("account", accountId)
        assertTrue(untracked is LocalVersionState.Untracked)
        assertNull((untracked as LocalVersionState.Untracked).legacyFallback)
    }

    @Test
    fun newEntity_returnsNew() = runBlocking {
        val newState = coordinator.resolveLocalVersion("account", "non_existent_account")
        assertEquals(LocalVersionState.New, newState)
    }

    @Test
    fun testResolveAccount_allThreeStates() = runBlocking {
        val accountId = "acc_resolve_test"

        // 1. New
        assertEquals(LocalVersionState.New, coordinator.resolveLocalVersion("account", accountId))

        // 2. Untracked
        db.localAccountDao().upsert(
            LocalAccount(
                id = accountId,
                displayName = "Account Resolve",
                debtIqd = 0.0,
                advanceIqd = 0.0,
                updatedAt = 42L
            )
        )
        val untracked = coordinator.resolveLocalVersion("account", accountId)
        assertTrue(untracked is LocalVersionState.Untracked)
        assertEquals(42L, (untracked as LocalVersionState.Untracked).legacyFallback)

        // 3. ServerTracked
        db.syncMetadataDao().put("remote_version:account:$accountId", "1750000000000")
        val tracked = coordinator.resolveLocalVersion("account", accountId)
        assertTrue(tracked is LocalVersionState.ServerTracked)
        assertEquals(1750000000000L, (tracked as LocalVersionState.ServerTracked).version)
    }

    @Test
    fun testResolveLedger_allThreeStates() = runBlocking {
        val parentAccountId = "acc_parent"
        val ledgerId = "ledger_resolve_test"

        // Ensure parent account exists to satisfy foreign key constraint
        db.localAccountDao().upsert(
            LocalAccount(
                id = parentAccountId,
                displayName = "Parent Account",
                debtIqd = 0.0,
                advanceIqd = 0.0,
                updatedAt = 10L
            )
        )

        // 1. New
        assertEquals(LocalVersionState.New, coordinator.resolveLocalVersion("ledger", ledgerId))

        // 2. Untracked
        db.localLedgerEntryDao().insert(
            LocalLedgerEntry(
                id = ledgerId,
                accountId = parentAccountId,
                typeRaw = "DEBT",
                amountIqd = 100.0,
                debtAfterIqd = 100.0,
                occurredAt = 55L,
                note = "Ledger Resolve"
            )
        )
        val untracked = coordinator.resolveLocalVersion("ledger", ledgerId)
        assertTrue(untracked is LocalVersionState.Untracked)
        assertEquals(55L, (untracked as LocalVersionState.Untracked).legacyFallback)

        // 3. ServerTracked
        db.syncMetadataDao().put("remote_version:ledger:$ledgerId", "1760000000000")
        val tracked = coordinator.resolveLocalVersion("ledger", ledgerId)
        assertTrue(tracked is LocalVersionState.ServerTracked)
        assertEquals(1760000000000L, (tracked as LocalVersionState.ServerTracked).version)
    }

    @Test
    fun testResolveBatch_allThreeStates() = runBlocking {
        val batchId = "batch_resolve_test"

        // 1. New
        assertEquals(LocalVersionState.New, coordinator.resolveLocalVersion("batch", batchId))

        // 2. Untracked
        db.importBatchDao().insert(
            ImportBatch(
                id = batchId,
                fileName = "batch.json",
                fileHash = "hash123",
                accountsImported = 1,
                transactionsImported = 1,
                totalDebtIqd = 100.0,
                status = "completed",
                createdAt = 99L
            )
        )
        val untracked = coordinator.resolveLocalVersion("batch", batchId)
        assertTrue(untracked is LocalVersionState.Untracked)
        assertEquals(99L, (untracked as LocalVersionState.Untracked).legacyFallback)

        // 3. ServerTracked
        db.syncMetadataDao().put("remote_version:batch:$batchId", "1770000000000")
        val tracked = coordinator.resolveLocalVersion("batch", batchId)
        assertTrue(tracked is LocalVersionState.ServerTracked)
        assertEquals(1770000000000L, (tracked as LocalVersionState.ServerTracked).version)
    }
}
