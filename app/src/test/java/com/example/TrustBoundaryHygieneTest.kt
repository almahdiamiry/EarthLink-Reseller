package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.sync.LocalVersionState
import com.example.core.sync.RemoteEntityValidationResult
import com.example.core.sync.RemoteEntityValidator
import com.example.core.sync.RemoteSyncCoordinator
import com.example.core.sync.SyncConflictResolver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 5 Workstream 5 (RC-09: Trust-Boundary Hygiene) Certification Test Suite.
 *
 * Verifies:
 * 1. RC-09a: Untracked local entities return Untracked(null) without cross-domain timestamp comparison.
 * 2. RC-09c: Missing remote fields fall back to existing local fields rather than resetting to 0.0 or regressing boolean state.
 * 3. RC-09c: isLegacy monotonicity (true cannot regress to false).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class TrustBoundaryHygieneTest {

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
            outboxDao = db.syncOutboxDao(),
            metadataDao = db.syncMetadataDao(),
            auditDao = db.auditLogDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testUntrackedLocalEntity_returnsUntrackedNull_withoutCrossDomainTimestampComparison() = runBlocking {
        // Insert a local account without any record in sync_metadata (untracked)
        val untrackedAccount = LocalAccount(
            id = "acc_untracked_001",
            earthlinkUsername = "untracked_user",
            displayName = "Untracked User",
            updatedAt = 500L // Sequence/local timestamp < 1e12
        )
        db.localAccountDao().insert(untrackedAccount)

        // Resolve local version state
        val localVersionState = coordinator.resolveLocalVersion("account", "acc_untracked_001")

        // Assert that state is Untracked and legacyFallback is null (no sub-1e12 fallback compared to server version)
        assertTrue(localVersionState is LocalVersionState.Untracked)
        val untrackedState = localVersionState as LocalVersionState.Untracked
        assertNull(untrackedState.legacyFallback)
    }

    @Test
    fun testMissingRemoteFields_fallbackToExistingLocalValues() {
        // 1. Existing local ledger entry with non-zero debtAfterIqd and specific note
        val existingLedger = LocalLedgerEntry(
            id = "ledger_missing_field_001",
            accountId = "acc_001",
            typeRaw = "PAYMENT",
            amountIqd = 25000.0,
            debtAfterIqd = 10000.0,
            note = "Original local note",
            occurredAt = 1600000000000L,
            createdAt = 1600000000000L
        )

        // Remote payload missing 'debtAfterIqd' and 'note'
        val remoteMap = mapOf<String, Any>(
            "accountId" to "acc_001",
            "typeRaw" to "PAYMENT",
            "amountIqd" to 25000.0,
            "occurredAt" to 1600000000000L
        )

        val result = RemoteEntityValidator.validateAndMapLedgerEntry(
            id = "ledger_missing_field_001",
            d = remoteMap,
            remoteUpdatedAt = 1700000000000L,
            existingLocalLedgerEntry = existingLedger
        )

        assertTrue(result is RemoteEntityValidationResult.Valid)
        val mapped = (result as RemoteEntityValidationResult.Valid).entity
        assertEquals(10000.0, mapped.debtAfterIqd, 0.01) // Preserves existing debtAfterIqd instead of 0.0
        assertEquals("Original local note", mapped.note) // Preserves existing note
    }

    @Test
    fun testIsLegacyMonotonicity_cannotRegressToFalse() {
        // Existing local account with isLegacy = true
        val existingAccount = LocalAccount(
            id = "acc_legacy_001",
            earthlinkUsername = "legacy_user",
            displayName = "Legacy User",
            isLegacy = true
        )

        // Remote payload explicitly setting isLegacy = false or omitting it
        val remoteMap = mapOf<String, Any>(
            "displayName" to "Legacy User Updated",
            "isLegacy" to false,
            "debtIqd" to 0.0
        )

        val result = RemoteEntityValidator.validateAndMapAccount(
            id = "acc_legacy_001",
            d = remoteMap,
            remoteUpdatedAt = 1700000000000L,
            existingLocalAccount = existingAccount
        )

        assertTrue(result is RemoteEntityValidationResult.Valid)
        val mapped = (result as RemoteEntityValidationResult.Valid).entity
        assertTrue("isLegacy must remain true and not regress to false", mapped.isLegacy)
    }
}
