package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.EarthlinkApp
import com.example.core.backup.BackupManager
import com.example.core.database.AppDatabase
import com.example.core.ledger.BalanceCalculator
import com.example.core.model.*
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
 * PERMANENT FOCUSED REGRESSION TEST — BUG-02 Restore Merge Snapshot/History Row Preservation.
 *
 * Core Triad:
 * 1. Claim: INV-02 (History Preservation — no physical row deletion on local_ledger_entries) and
 *    INV-01 (Financial Correctness) during Restore Merge (BackupManager.executeRestoreMergeInternal).
 *    Pre-existing isSnapshotHistory=true ledger rows MUST survive Restore Merge when the account has
 *    stateSource != null. Balance-eligible list must never be used as the complete persistence set,
 *    and deleteByAccountId() must never be executed on compatible-baseline merge.
 * 2. Seam / Environment: ROBOLECTRIC — real production Restore Merge path (executeRestoreMergeInternal
 *    -> BalanceCalculator.reconstructCurrentPosition -> non-destructive persistence: retain/NO-OP,
 *    insert new, in-place update divergent) against self-contained deterministic fixture accounts and ledgers.
 * 3. Oracle Strategy:
 *    - history preservation oracle = independent BEFORE/AFTER Room row fingerprint covering:
 *      (id, accountId, typeRaw, amountIqd, debtAfterIqd, occurredAt, sourceExternalId, isSnapshotHistory).
 *    - financial stability oracle = stored 3-vector comparison (debtIqd, advanceIqd, loanIqd) plus existing
 *      BalanceCalculator where explicitly used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Bug02RestoreMergeHistoryLossTest {

    private lateinit var context: Context

    private val liveDbName = "b02_live_${UUID.randomUUID().toString().take(8)}"
    private val backupDbName = "b02_bak_${UUID.randomUUID().toString().take(8)}"

    data class AccountBalanceVector(
        val debtIqd: Double,
        val advanceIqd: Double,
        val loanIqd: Double
    )

    data class SnapshotLedgerFingerprint(
        val id: String,
        val accountId: String,
        val typeRaw: String,
        val amountIqd: Double,
        val debtAfterIqd: Double,
        val occurredAt: Long,
        val sourceExternalId: String?,
        val isSnapshotHistory: Boolean
    )

    private fun LocalLedgerEntry.toSnapshotFingerprint() = SnapshotLedgerFingerprint(
        id = id,
        accountId = accountId,
        typeRaw = typeRaw,
        amountIqd = amountIqd,
        debtAfterIqd = debtAfterIqd,
        occurredAt = occurredAt,
        sourceExternalId = sourceExternalId,
        isSnapshotHistory = isSnapshotHistory
    )

    private suspend fun snapshotLedgerFingerprints(db: AppDatabase): Map<String, SnapshotLedgerFingerprint> {
        return db.localLedgerEntryDao().getAllOneShot()
            .filter { it.isSnapshotHistory }
            .associate { it.id to it.toSnapshotFingerprint() }
    }

    private fun mergeDecision(conflictDecisions: Map<String, ConflictResolutionChoice> = emptyMap()) = RestoreMergeDecision(
        artifactIdentity = "bug02-deterministic-test",
        selectedBaselineId = "BACKUP_SNAPSHOT",
        selectedLineageScope = "COMPLETE_LINEAGE",
        conflictDecisions = conflictDecisions,
        isApproved = true
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getDatabasePath(liveDbName).parentFile?.mkdirs()
        (context as? EarthlinkApp)?.isSafeDebugFallbackAllowedOverride = true
        AppDatabase.closeDatabase()
    }

    @After
    fun tearDown() {
        AppDatabase.closeDatabase()
        AppDatabase.closeAndRemoveInstance(liveDbName)
        AppDatabase.closeAndRemoveInstance(backupDbName)
    }

    private suspend fun accountBalanceMap(db: AppDatabase): Map<String, AccountBalanceVector> {
        return db.localAccountDao().getAllPersistedOneShot().associate {
            it.id to AccountBalanceVector(it.debtIqd, it.advanceIqd, it.loanIqd)
        }
    }

    private fun assertBalanceVectorsEqual(
        message: String,
        expected: Map<String, AccountBalanceVector>,
        actual: Map<String, AccountBalanceVector>
    ) {
        assertEquals("$message: account count mismatch", expected.size, actual.size)
        for ((accId, expVec) in expected) {
            val actVec = actual[accId]
            assertNotNull("$message: account $accId must exist in actual", actVec)
            assertEquals("$message [$accId] debtIqd drift", expVec.debtIqd, actVec!!.debtIqd, 0.001)
            assertEquals("$message [$accId] advanceIqd drift", expVec.advanceIqd, actVec.advanceIqd, 0.001)
            assertEquals("$message [$accId] loanIqd drift", expVec.loanIqd, actVec.loanIqd, 0.001)
        }
    }

    /**
     * Deterministic fixture derived from the real production uTower snapshot reproduction.
     * Contains:
     * - Account 1: uTower snapshot account (stateSource != null) with 3 snapshot-history rows + 2 live rows.
     * - Account 2: uTower snapshot account with advance and loan vectors + 1 snapshot row.
     * - Account 3: Control regular account (stateSource == null) to verify negative twin.
     */
    private data class TestFixture(
        val accounts: List<LocalAccount>,
        val ledgers: List<LocalLedgerEntry>,
        val snapshotLedgerIds: Set<String>,
        val liveLedgerIds: Set<String>
    )

    private fun createStandardFixture(): TestFixture {
        val acc1Id = "acc_snap_alpha"
        val acc2Id = "acc_snap_beta"
        val acc3Id = "acc_regular_gamma"

        // Account 1: openingDebt = 50000, post-snapshot: + took 40000 => 90000, - gave 15000 => 75000
        val acc1 = LocalAccount(
            id = acc1Id,
            displayName = "Alpha uTower Snapshot Subscriber",
            earthlinkUsername = "alpha_user",
            openingDebtIqd = 50000.0,
            openingAdvanceIqd = 0.0,
            openingLoanIqd = 0.0,
            debtIqd = 75000.0,
            advanceIqd = 0.0,
            loanIqd = 0.0,
            stateSource = "UTOWER_SNAPSHOT_RESOLVED",
            createdAt = 1700000000000L,
            updatedAt = 1700005000000L
        )

        // Snapshot history rows for Account 1: MUST NOT be counted in rolling reconstruction from openingDebt,
        // but MUST NEVER BE PHYSICALLY DELETED from database during merge.
        val snap1 = LocalLedgerEntry(
            id = "tx_snap_alpha_1", accountId = acc1Id, sourceExternalId = "ut_ext_1",
            typeRaw = "took", amountIqd = 25000.0, debtAfterIqd = 25000.0,
            occurredAt = 1699900000000L, isSnapshotHistory = true
        )
        val snap2 = LocalLedgerEntry(
            id = "tx_snap_alpha_2", accountId = acc1Id, sourceExternalId = "ut_ext_2",
            typeRaw = "gave", amountIqd = 10000.0, debtAfterIqd = 15000.0,
            occurredAt = 1699910000000L, isSnapshotHistory = true
        )
        val snap3 = LocalLedgerEntry(
            id = "tx_snap_alpha_3", accountId = acc1Id, sourceExternalId = "ut_ext_3",
            typeRaw = "took", amountIqd = 35000.0, debtAfterIqd = 50000.0,
            occurredAt = 1699920000000L, isSnapshotHistory = true
        )

        // Live rows for Account 1: participate in rolling debt reconstruction
        val live1 = LocalLedgerEntry(
            id = "tx_live_alpha_1", accountId = acc1Id, sourceExternalId = null,
            typeRaw = "took", amountIqd = 40000.0, debtAfterIqd = 90000.0,
            occurredAt = 1700001000000L, isSnapshotHistory = false
        )
        val live2 = LocalLedgerEntry(
            id = "tx_live_alpha_2", accountId = acc1Id, sourceExternalId = null,
            typeRaw = "gave", amountIqd = 15000.0, debtAfterIqd = 75000.0,
            occurredAt = 1700002000000L, isSnapshotHistory = false
        )

        // Account 2: has advance and loan baseline
        val acc2 = LocalAccount(
            id = acc2Id,
            displayName = "Beta uTower Snapshot Subscriber (Advance & Loan)",
            earthlinkUsername = "beta_user",
            openingDebtIqd = 20000.0,
            openingAdvanceIqd = 5000.0,
            openingLoanIqd = 10000.0,
            debtIqd = 20000.0,
            advanceIqd = 5000.0,
            loanIqd = 10000.0,
            stateSource = "UTOWER_CURRENT_STATE",
            createdAt = 1700000000000L,
            updatedAt = 1700005000000L
        )
        val snapBeta1 = LocalLedgerEntry(
            id = "tx_snap_beta_1", accountId = acc2Id, sourceExternalId = "ut_ext_b1",
            typeRaw = "took", amountIqd = 20000.0, debtAfterIqd = 20000.0,
            occurredAt = 1699900000000L, isSnapshotHistory = true
        )

        // Account 3: Control non-snapshot account
        val acc3 = LocalAccount(
            id = acc3Id,
            displayName = "Gamma Regular Subscriber (Control)",
            earthlinkUsername = "gamma_user",
            openingDebtIqd = 0.0,
            openingAdvanceIqd = 0.0,
            openingLoanIqd = 0.0,
            debtIqd = 30000.0,
            advanceIqd = 0.0,
            loanIqd = 0.0,
            stateSource = null,
            createdAt = 1700000000000L,
            updatedAt = 1700005000000L
        )
        val reg1 = LocalLedgerEntry(
            id = "tx_reg_gamma_1", accountId = acc3Id, sourceExternalId = null,
            typeRaw = "took", amountIqd = 30000.0, debtAfterIqd = 30000.0,
            occurredAt = 1700001000000L, isSnapshotHistory = false
        )

        val accounts = listOf(acc1, acc2, acc3)
        val ledgers = listOf(snap1, snap2, snap3, live1, live2, snapBeta1, reg1)
        val snapshotIds = setOf(snap1.id, snap2.id, snap3.id, snapBeta1.id)
        val liveIds = setOf(live1.id, live2.id, reg1.id)

        return TestFixture(accounts, ledgers, snapshotIds, liveIds)
    }

    private suspend fun seedDb(db: AppDatabase, fixture: TestFixture) {
        db.localAccountDao().insertAll(fixture.accounts)
        // Insert one-by-one with insert() to avoid REPLACE collisions on null sourceExternalId
        for (l in fixture.ledgers) {
            db.localLedgerEntryDao().insert(l)
        }
    }

    // =====================================================================
    // TEST 1 — IDENTICAL-STATE RESTORE MERGE (Flagship BUG-02 Invariant Test)
    // =====================================================================
    @Test
    fun test01_identicalStateMerge_preservesAllSnapshotHistoryRowsWithoutDeletion() = runBlocking {
        val liveDb = AppDatabase.getDatabase(context, ByteArray(0), liveDbName)
        val backupDb = AppDatabase.getDatabase(context, ByteArray(0), backupDbName)
        val fixture = createStandardFixture()

        seedDb(liveDb, fixture)
        seedDb(backupDb, fixture)

        val beforeSnapshotEntries = liveDb.localLedgerEntryDao().getAllOneShot().filter { it.isSnapshotHistory }
        val beforeSnapshotIds = beforeSnapshotEntries.map { it.id }.toSet()
        val beforeSnapshotFingerprints = snapshotLedgerFingerprints(liveDb)
        val beforeAllIds = liveDb.localLedgerEntryDao().getAllOneShot().map { it.id }.toSet()
        val beforeBalances = accountBalanceMap(liveDb)

        assertEquals("Pre-condition: 4 snapshot rows expected", 4, beforeSnapshotIds.size)
        assertEquals("Pre-condition: 7 total rows expected", 7, beforeAllIds.size)

        // Execute Restore Merge
        val result = BackupManager.executeRestoreMergeInternal(liveDb, backupDb, mergeDecision())
        assertTrue("Restore Merge must report success", result.success)

        val afterEntries = liveDb.localLedgerEntryDao().getAllOneShot()
        val afterSnapshotIds = afterEntries.filter { it.isSnapshotHistory }.map { it.id }.toSet()
        val afterSnapshotFingerprints = snapshotLedgerFingerprints(liveDb)
        val afterAllIds = afterEntries.map { it.id }.toSet()
        val afterBalances = accountBalanceMap(liveDb)

        val deletedSnapshotIds = beforeSnapshotIds - afterSnapshotIds
        val deletedAllIds = beforeAllIds - afterAllIds

        // Assert zero deletions of snapshot rows (INV-02)
        assertTrue(
            "INV-02 VIOLATION | Snapshot-history rows were deleted during Restore Merge: $deletedSnapshotIds",
            deletedSnapshotIds.isEmpty()
        )
        assertTrue(
            "INV-02 VIOLATION | Ledger rows were deleted during identical-state Restore Merge: $deletedAllIds",
            deletedAllIds.isEmpty()
        )

        // Assert exact ID sets preserved
        assertEquals("All original snapshot ledger IDs must remain intact", beforeSnapshotIds, afterSnapshotIds)
        assertEquals("All original ledger IDs must remain intact", beforeAllIds, afterAllIds)
        assertEquals("Total row count must be exactly preserved", beforeAllIds.size, afterAllIds.size)

        // Assert exact payload equality for all pre-existing snapshot rows (INV-02 Immutability)
        assertEquals(
            "Snapshot row payload fingerprints must be exactly preserved before and after merge",
            beforeSnapshotFingerprints,
            afterSnapshotFingerprints
        )

        // Assert zero financial drift across all three currency vectors: debt, advance, loan
        assertBalanceVectorsEqual("Identical-state merge financial stability", beforeBalances, afterBalances)

        liveDb.close(); backupDb.close()
    }

    // =====================================================================
    // TEST 2 — BACKUP THEN CONTINUE WORK SCENARIO
    // =====================================================================
    @Test
    fun test02_backupThenContinueWork_preservesHistoryAndIntegratesNewTransactions() = runBlocking {
        val liveDb = AppDatabase.getDatabase(context, ByteArray(0), liveDbName)
        val backupDb = AppDatabase.getDatabase(context, ByteArray(0), backupDbName)
        val fixture = createStandardFixture()

        seedDb(liveDb, fixture)
        seedDb(backupDb, fixture)

        val originalSnapshotIds = fixture.snapshotLedgerIds
        val beforeSnapshotFingerprints = snapshotLedgerFingerprints(liveDb)

        // Scenario:
        // 1. Live database continued legitimate work: add a new payment transaction on Account 1
        //    (debt 75000 - gave 25000 => debt becomes 50000)
        val continuedLiveTx = LocalLedgerEntry(
            id = "tx_live_alpha_continued",
            accountId = "acc_snap_alpha",
            sourceExternalId = null,
            typeRaw = "gave",
            amountIqd = 25000.0,
            debtAfterIqd = 50000.0,
            occurredAt = 1700003000000L,
            isSnapshotHistory = false
        )
        liveDb.localLedgerEntryDao().insert(continuedLiveTx)
        val liveAcc1 = liveDb.localAccountDao().getByIdOneShot("acc_snap_alpha")!!
        liveDb.localAccountDao().update(liveAcc1.copy(debtIqd = 50000.0, updatedAt = System.currentTimeMillis()))

        // 2. Backup database has a new debt addition that occurred concurrently or before backup:
        //    add a new debt transaction on Account 2 (+ took 10000 => debt becomes 30000)
        val backupNewTx = LocalLedgerEntry(
            id = "tx_backup_beta_new",
            accountId = "acc_snap_beta",
            sourceExternalId = null,
            typeRaw = "took",
            amountIqd = 10000.0,
            debtAfterIqd = 30000.0,
            occurredAt = 1700002000000L,
            isSnapshotHistory = false
        )
        backupDb.localLedgerEntryDao().insert(backupNewTx)
        val backupAcc2 = backupDb.localAccountDao().getByIdOneShot("acc_snap_beta")!!
        backupDb.localAccountDao().update(backupAcc2.copy(debtIqd = 30000.0, updatedAt = System.currentTimeMillis()))

        // Execute Restore Merge
        val result = BackupManager.executeRestoreMergeInternal(liveDb, backupDb, mergeDecision())
        assertTrue("Restore Merge must succeed", result.success)

        val afterEntries = liveDb.localLedgerEntryDao().getAllOneShot()
        val afterSnapshotIds = afterEntries.filter { it.isSnapshotHistory }.map { it.id }.toSet()
        val afterSnapshotFingerprints = snapshotLedgerFingerprints(liveDb)
        val afterAllIds = afterEntries.map { it.id }.toSet()

        // Assert all original snapshot rows survived
        val deletedSnapshotIds = originalSnapshotIds - afterSnapshotIds
        assertTrue(
            "All original snapshot IDs must survive continued work merge: missing $deletedSnapshotIds",
            deletedSnapshotIds.isEmpty()
        )

        // Assert exact payload equality for all pre-existing snapshot rows
        assertEquals(
            "All original snapshot row payloads must remain identical after continued work merge",
            beforeSnapshotFingerprints,
            afterSnapshotFingerprints
        )

        // Assert continued work transaction in liveDb was NOT lost
        assertTrue("Continued live transaction must be preserved", afterAllIds.contains(continuedLiveTx.id))

        // Assert new transaction from backup was merged/inserted
        assertTrue("New transaction from backup must be inserted", afterAllIds.contains(backupNewTx.id))

        // Assert exact financial positions across all 3 balance components
        val finalBalances = accountBalanceMap(liveDb)

        // Account 1: opening 50000, + took 40000, - gave 15000, - gave 25000 => debt 50000, advance 0, loan 0
        val acc1Final = finalBalances["acc_snap_alpha"]!!
        assertEquals("Account 1 final debtIqd", 50000.0, acc1Final.debtIqd, 0.001)
        assertEquals("Account 1 final advanceIqd", 0.0, acc1Final.advanceIqd, 0.001)
        assertEquals("Account 1 final loanIqd", 0.0, acc1Final.loanIqd, 0.001)

        // Account 2: openingDebt 20000, advance 5000, loan 10000. Merged + took 10000:
        // Advance of 5000 is used first! So advance becomes 0.0, remaining debt added = 5000.
        // New debt = 20000 + 5000 = 25000. Loan remains 10000.
        val acc2Final = finalBalances["acc_snap_beta"]!!
        val oracleAcc2 = BalanceCalculator.reconstructCurrentPosition(
            openingDebt = 20000.0, openingAdvance = 5000.0, openingLoan = 10000.0,
            transactions = listOf(backupNewTx), isSnapshotBaseline = true
        ).first
        assertEquals("Account 2 final debtIqd", oracleAcc2.debtIqd, acc2Final.debtIqd, 0.001)
        assertEquals("Account 2 final advanceIqd", oracleAcc2.advanceIqd, acc2Final.advanceIqd, 0.001)
        assertEquals("Account 2 final loanIqd", oracleAcc2.loanIqd, acc2Final.loanIqd, 0.001)

        liveDb.close(); backupDb.close()
    }

    // =====================================================================
    // TEST 3 — REPEATED RESTORE MERGE (3-Cycle Idempotency Stability)
    // =====================================================================
    @Test
    fun test03_repeatedMerge_maintainsAbsoluteStabilityAcrossThreeCycles() = runBlocking {
        val liveDb = AppDatabase.getDatabase(context, ByteArray(0), liveDbName)
        val backupDb = AppDatabase.getDatabase(context, ByteArray(0), backupDbName)
        val fixture = createStandardFixture()

        seedDb(liveDb, fixture)
        seedDb(backupDb, fixture)

        val origSnapshotIds = liveDb.localLedgerEntryDao().getAllOneShot().filter { it.isSnapshotHistory }.map { it.id }.toSet()
        val origSnapshotFingerprints = snapshotLedgerFingerprints(liveDb)
        val origTotalCount = liveDb.localLedgerEntryDao().getAllOneShot().size
        val origBalances = accountBalanceMap(liveDb)

        for (cycle in 1..3) {
            val result = BackupManager.executeRestoreMergeInternal(liveDb, backupDb, mergeDecision())
            assertTrue("Cycle $cycle: Merge must succeed", result.success)

            val cycleEntries = liveDb.localLedgerEntryDao().getAllOneShot()
            val cycleSnapshotIds = cycleEntries.filter { it.isSnapshotHistory }.map { it.id }.toSet()
            val cycleSnapshotFingerprints = snapshotLedgerFingerprints(liveDb)
            val cycleTotalCount = cycleEntries.size
            val cycleBalances = accountBalanceMap(liveDb)

            assertEquals("Cycle $cycle: snapshot count must equal original count", origSnapshotIds.size, cycleSnapshotIds.size)
            assertEquals("Cycle $cycle: all original snapshot IDs must exist", origSnapshotIds, cycleSnapshotIds)
            assertEquals("Cycle $cycle: snapshot row payloads must remain identical to original", origSnapshotFingerprints, cycleSnapshotFingerprints)
            assertEquals("Cycle $cycle: total count must equal original count", origTotalCount, cycleTotalCount)
            assertBalanceVectorsEqual("Cycle $cycle 3-vector balance stability", origBalances, cycleBalances)
        }

        liveDb.close(); backupDb.close()
    }

    // =====================================================================
    // TEST 4 — DIVERGENT PAYLOAD RESOLUTION WITH USE_BACKUP (No Row Deletion)
    // =====================================================================
    @Test
    fun test04_divergentUseBackup_preservesSnapshotHistoryAndUpdatesLiveRow() = runBlocking {
        val liveDb = AppDatabase.getDatabase(context, ByteArray(0), liveDbName)
        val backupDb = AppDatabase.getDatabase(context, ByteArray(0), backupDbName)
        val fixture = createStandardFixture()

        seedDb(liveDb, fixture)
        seedDb(backupDb, fixture)

        val origSnapshotIds = fixture.snapshotLedgerIds
        val beforeSnapshotFingerprints = snapshotLedgerFingerprints(liveDb)

        // Make liveTx1 divergent: Live has amount = 40000.0, Backup has amount = 60000.0
        val backupDivergentTx = LocalLedgerEntry(
            id = "tx_live_alpha_1",
            accountId = "acc_snap_alpha",
            sourceExternalId = null,
            typeRaw = "took",
            amountIqd = 60000.0,
            debtAfterIqd = 110000.0,
            occurredAt = 1700001000000L,
            isSnapshotHistory = false
        )
        backupDb.localLedgerEntryDao().update(backupDivergentTx)

        // Resolution choice: USE_BACKUP
        val decisionUseBackup = mergeDecision(conflictDecisions = mapOf("tx_live_alpha_1" to ConflictResolutionChoice.USE_BACKUP))
        val resultBackup = BackupManager.executeRestoreMergeInternal(liveDb, backupDb, decisionUseBackup)
        assertTrue("Merge with USE_BACKUP must succeed", resultBackup.success)

        val postBackupEntries = liveDb.localLedgerEntryDao().getAllOneShot()
        val postBackupSnapshotIds = postBackupEntries.filter { it.isSnapshotHistory }.map { it.id }.toSet()
        val postBackupSnapshotFingerprints = snapshotLedgerFingerprints(liveDb)

        // Snapshot rows must still exist completely and immutably (INV-02)
        assertEquals("All snapshot IDs must remain after USE_BACKUP", origSnapshotIds, postBackupSnapshotIds)
        assertEquals(
            "Snapshot row payloads must remain untouched after USE_BACKUP resolution",
            beforeSnapshotFingerprints,
            postBackupSnapshotFingerprints
        )

        // The divergent live transaction was updated in-place to 60000.0 without deleting the account or snapshot rows
        val updatedLiveTx = liveDb.localLedgerEntryDao().getByIdOneShot("tx_live_alpha_1")!!
        assertEquals("Divergent live transaction must reflect USE_BACKUP amount", 60000.0, updatedLiveTx.amountIqd, 0.001)

        // Opening 50000, + took 60000 => 110000, - gave 15000 => 95000
        val acc1AfterBackupChoice = liveDb.localAccountDao().getByIdOneShot("acc_snap_alpha")!!
        assertEquals("Account 1 debt after USE_BACKUP", 95000.0, acc1AfterBackupChoice.debtIqd, 0.001)

        liveDb.close(); backupDb.close()
    }
}
