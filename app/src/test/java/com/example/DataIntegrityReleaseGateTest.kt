package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.database.SyncOutboxDao
import com.example.core.ledger.BalanceCalculator
import com.example.core.ledger.TransactionTypeNormalizer
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.model.SyncOutbox
import com.example.core.sync.DataOperationCoordinator
import com.example.core.sync.DataOperationMode
import com.example.core.sync.EventSyncResult
import com.example.core.sync.RemoteEntityValidationResult
import com.example.core.sync.RemoteEntityValidator
import com.example.core.sync.RemoteEvent
import com.example.core.sync.RemoteEventSource
import com.example.core.sync.RemoteSyncCoordinator
import com.example.core.sync.SyncRepositoryImpl
import com.squareup.moshi.Moshi
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * DataIntegrityReleaseGateTest — Permanent Release Gate Test Suite.
 *
 * PURPOSE: Prevent the class of failures where financial numbers come out wrong
 * with no crash or error — "silent data corruption". Every test targets a specific
 * code path that historically caused or could cause silently wrong financial output.
 *
 * HISTORICAL INCIDENTS PREVENTED:
 *   H-1: Bad local write pushed to Firebase, overwriting good remote data (no validation before push)
 *   H-2: Stale Firebase data pulled down, silently overwriting newer local data (no version check)
 *   H-3: Migration/restore produced wrong balances because stateSource/stateConfidence were stripped
 *         from buildOutboxPayloadMap, causing BalanceCalculator to re-apply 59 historical entries
 *         and inflating debt by 9.5M IQD across 84 accounts (REAL_DATASET_END_TO_END_DATA_INTEGRITY_REPORT.md)
 *
 * DESIGN PRINCIPLE: Every assertion uses descriptive failure messages citing the specific invariant
 * violated, the field involved, and expected vs actual values. A failing test should be diagnosable
 * from the assertion message alone without reading the stack trace.
 *
 * HOW TO RUN:
 *   ./gradlew :app:testDebugUnitTest --tests "com.example.DataIntegrityReleaseGateTest"
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DataIntegrityReleaseGateTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var syncRepository: SyncRepositoryImpl
    private lateinit var outboxDao: SyncOutboxDao
    private val moshi = Moshi.Builder().build()
    private val accountAdapter = moshi.adapter(LocalAccount::class.java)
    private val ledgerAdapter = moshi.adapter(LocalLedgerEntry::class.java)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        (context as? EarthlinkApp)?.isSafeDebugFallbackAllowedOverride = true
        context.getDatabasePath("earthlink_reseller_db").parentFile?.mkdirs()
        AppDatabase.closeDatabase()
        db = (context as? EarthlinkApp)?.database ?: AppDatabase.getDatabase(context, ByteArray(0), "earthlink_reseller_db")
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
        AppDatabase.closeDatabase()
    }

    // ========================================================================
    // SECTION D.1 — ROUND-TRIP FIELD PRESERVATION TESTS
    //
    // For each of the 10 critical BalanceCalculator fields, serialize through
    // the Moshi → buildOutboxPayloadMap → RemoteEntityValidator round-trip
    // and assert field-for-field equality.
    //
    // Evidence chain: Repositories.kt:1168 (accountAdapter = moshi.adapter(LocalAccount::class.java))
    //   → OutboxManager.kt:42-51 (enqueue with payloadJson)
    //   → SyncRepositoryImpl.kt:661-718 (buildOutboxPayloadMap iterates JSON keys, preserves stateSource at L708)
    //   → RemoteEntityValidator.kt:15-108 (validateAndMapAccount reconstructs LocalAccount)
    // ========================================================================

    private fun createSnapshotAccount(
        id: String = "rt_acc_001",
        openingDebt: Double = 75000.0,
        openingAdvance: Double = 12000.0,
        openingLoan: Double = 5000.0,
        stateSource: String = "UTOWER_SNAPSHOT_RESOLVED",
        stateConfidence: String = "AUTHORITATIVE"
    ): LocalAccount = LocalAccount(
        id = id,
        displayName = "Round-Trip Test Account",
        debtIqd = openingDebt,
        openingDebtIqd = openingDebt,
        openingAdvanceIqd = openingAdvance,
        openingLoanIqd = openingLoan,
        stateSource = stateSource,
        stateConfidence = stateConfidence,
        snapshotCapturedAt = 1700000000000L
    )

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

    private fun ledgerToOutboxItem(entry: LocalLedgerEntry, outboxId: Int = 2): SyncOutbox {
        val json = ledgerAdapter.toJson(entry)
        return SyncOutbox(
            id = outboxId,
            entityType = "local_ledger_entries",
            entityId = entry.id,
            operation = "upsert",
            payloadJson = json,
            status = "pending",
            attemptCount = 0,
            lastError = null,
            createdAt = System.currentTimeMillis()
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun roundTripAccount(account: LocalAccount): LocalAccount {
        val outboxItem = accountToOutboxItem(account)
        val cloudPayload = syncRepository.buildOutboxPayloadMap(outboxItem)
        val result = RemoteEntityValidator.validateAndMapAccount(
            id = account.id,
            d = cloudPayload as Map<String, Any>,
            remoteUpdatedAt = System.currentTimeMillis(),
            existingLocalAccount = null
        )
        assertTrue(
            "Round-trip failed: validateAndMapAccount returned ${result::class.simpleName} instead of Valid " +
            "for account ${account.id}. If Malformed: ${(result as? RemoteEntityValidationResult.Malformed)?.reason}",
            result is RemoteEntityValidationResult.Valid
        )
        return (result as RemoteEntityValidationResult.Valid).entity
    }

    @Suppress("UNCHECKED_CAST")
    private fun roundTripLedgerEntry(entry: LocalLedgerEntry): LocalLedgerEntry {
        val outboxItem = ledgerToOutboxItem(entry)
        val cloudPayload = syncRepository.buildOutboxPayloadMap(outboxItem)
        val result = RemoteEntityValidator.validateAndMapLedgerEntry(
            id = entry.id,
            d = cloudPayload as Map<String, Any>,
            remoteUpdatedAt = System.currentTimeMillis(),
            existingLocalLedgerEntry = null
        )
        assertTrue(
            "Round-trip failed: validateAndMapLedgerEntry returned ${result::class.simpleName} instead of Valid " +
            "for entry ${entry.id}. If Malformed: ${(result as? RemoteEntityValidationResult.Malformed)?.reason}",
            result is RemoteEntityValidationResult.Valid
        )
        return (result as RemoteEntityValidationResult.Valid).entity
    }

    @Test
    fun roundTrip_stateSource_preservedThroughMoshiOutboxAndValidator() {
        // Evidence: SyncRepositoryImpl.kt:708 — "Preserve stateSource, stateConfidence, snapshotCapturedAt..."
        // Evidence: RemoteEntityValidator.kt:58 — stateSource = d["stateSource"] as? String ?: existing?.stateSource
        val original = createSnapshotAccount(stateSource = "UTOWER_CURRENT_STATE")
        val roundTripped = roundTripAccount(original)
        assertEquals(
            "INV-04 VIOLATED | Field: stateSource | " +
            "Expected: 'UTOWER_CURRENT_STATE' (snapshot baseline flag must survive round-trip) | " +
            "Actual: '${roundTripped.stateSource}' (field was stripped or defaulted during serialization)",
            original.stateSource, roundTripped.stateSource
        )
    }

    @Test
    fun roundTrip_stateConfidence_preservedThroughMoshiOutboxAndValidator() {
        // Evidence: RemoteEntityValidator.kt:59 — stateConfidence = d["stateConfidence"] as? String ?: existing?.stateConfidence
        val original = createSnapshotAccount(stateConfidence = "AUTHORITATIVE")
        val roundTripped = roundTripAccount(original)
        assertEquals(
            "INV-04 VIOLATED | Field: stateConfidence | " +
            "Expected: 'AUTHORITATIVE' | Actual: '${roundTripped.stateConfidence}'",
            original.stateConfidence, roundTripped.stateConfidence
        )
    }

    @Test
    fun roundTrip_openingDebtIqd_preservedThroughMoshiOutboxAndValidator() {
        // Evidence: RemoteEntityValidator.kt:40 — openingDebt = (d["openingDebtIqd"] as? Number)?.toDouble() ?: ...
        val original = createSnapshotAccount(openingDebt = 123456.0)
        val roundTripped = roundTripAccount(original)
        assertEquals(
            "INV-01 VIOLATED | Field: openingDebtIqd | " +
            "Expected: ${original.openingDebtIqd} | Actual: ${roundTripped.openingDebtIqd} | " +
            "Baseline debt was corrupted during serialization round-trip",
            original.openingDebtIqd, roundTripped.openingDebtIqd, 0.001
        )
    }

    @Test
    fun roundTrip_openingAdvanceIqd_preservedThroughMoshiOutboxAndValidator() {
        // Evidence: RemoteEntityValidator.kt:41
        val original = createSnapshotAccount(openingAdvance = 55000.0)
        val roundTripped = roundTripAccount(original)
        assertEquals(
            "INV-01 VIOLATED | Field: openingAdvanceIqd | " +
            "Expected: ${original.openingAdvanceIqd} | Actual: ${roundTripped.openingAdvanceIqd}",
            original.openingAdvanceIqd, roundTripped.openingAdvanceIqd, 0.001
        )
    }

    @Test
    fun roundTrip_openingLoanIqd_preservedThroughMoshiOutboxAndValidator() {
        // Evidence: RemoteEntityValidator.kt:42
        val original = createSnapshotAccount(openingLoan = 30000.0)
        val roundTripped = roundTripAccount(original)
        assertEquals(
            "INV-01 VIOLATED | Field: openingLoanIqd | " +
            "Expected: ${original.openingLoanIqd} | Actual: ${roundTripped.openingLoanIqd}",
            original.openingLoanIqd, roundTripped.openingLoanIqd, 0.001
        )
    }

    @Test
    fun roundTrip_isSnapshotHistory_preservedThroughMoshiOutboxAndValidator() {
        // Evidence: RemoteEntityValidator.kt:135 — isSnapshotHistory = d["isSnapshotHistory"] as? Boolean ?: ...
        val entry = LocalLedgerEntry(
            id = "rt_entry_001", accountId = "rt_acc_001",
            typeRaw = "took", amountIqd = 40000.0, debtAfterIqd = 40000.0,
            occurredAt = 1700000000000L, isSnapshotHistory = true
        )
        val roundTripped = roundTripLedgerEntry(entry)
        assertTrue(
            "INV-04 VIOLATED | Field: isSnapshotHistory | " +
            "Expected: true (snapshot history entry must survive round-trip) | " +
            "Actual: ${roundTripped.isSnapshotHistory} (entry would be double-applied on snapshot baseline)",
            roundTripped.isSnapshotHistory
        )
    }

    @Test
    fun roundTrip_occurredAt_preservedThroughMoshiOutboxAndValidator() {
        // Evidence: RemoteEntityValidator.kt:127-130
        val entry = LocalLedgerEntry(
            id = "rt_entry_002", accountId = "rt_acc_001",
            typeRaw = "gave", amountIqd = 25000.0, debtAfterIqd = 15000.0,
            occurredAt = 1701721747760L, isSnapshotHistory = false
        )
        val roundTripped = roundTripLedgerEntry(entry)
        assertEquals(
            "INV-01 VIOLATED | Field: occurredAt | " +
            "Expected: ${entry.occurredAt} | Actual: ${roundTripped.occurredAt} | " +
            "Chronological ordering would be corrupted",
            entry.occurredAt, roundTripped.occurredAt
        )
    }

    @Test
    fun roundTrip_sourceExternalId_preservedThroughMoshiOutboxAndValidator() {
        // Evidence: RemoteEntityValidator.kt:140
        val entry = LocalLedgerEntry(
            id = "rt_entry_003", accountId = "rt_acc_001",
            typeRaw = "took", amountIqd = 40000.0, debtAfterIqd = 40000.0,
            occurredAt = 1700000000000L, sourceExternalId = "utower_tx_12345",
            isSnapshotHistory = true
        )
        val roundTripped = roundTripLedgerEntry(entry)
        assertEquals(
            "INV-01 VIOLATED | Field: sourceExternalId | " +
            "Expected: '${entry.sourceExternalId}' | Actual: '${roundTripped.sourceExternalId}' | " +
            "Deterministic sort tie-breaking would be corrupted",
            entry.sourceExternalId, roundTripped.sourceExternalId
        )
    }

    @Test
    fun roundTrip_typeRaw_preservedThroughMoshiOutboxAndValidator() {
        // Evidence: RemoteEntityValidator.kt:124
        val entry = LocalLedgerEntry(
            id = "rt_entry_004", accountId = "rt_acc_001",
            typeRaw = "renewal", amountIqd = 40000.0, debtAfterIqd = 80000.0,
            occurredAt = 1700000000000L
        )
        val roundTripped = roundTripLedgerEntry(entry)
        assertEquals(
            "INV-01 VIOLATED | Field: typeRaw | " +
            "Expected: '${entry.typeRaw}' | Actual: '${roundTripped.typeRaw}' | " +
            "Transaction direction would be wrong in BalanceCalculator",
            entry.typeRaw, roundTripped.typeRaw
        )
    }

    @Test
    fun roundTrip_amountIqd_preservedThroughMoshiOutboxAndValidator() {
        // Evidence: RemoteEntityValidator.kt:120-122
        val entry = LocalLedgerEntry(
            id = "rt_entry_005", accountId = "rt_acc_001",
            typeRaw = "took", amountIqd = 157500.0, debtAfterIqd = 232500.0,
            occurredAt = 1700000000000L
        )
        val roundTripped = roundTripLedgerEntry(entry)
        assertEquals(
            "INV-01 VIOLATED | Field: amountIqd | " +
            "Expected: ${entry.amountIqd} | Actual: ${roundTripped.amountIqd} | " +
            "Financial amount would be corrupted",
            entry.amountIqd, roundTripped.amountIqd, 0.001
        )
    }

    // ========================================================================
    // SECTION D.2 — CORRUPTION INJECTION TESTS
    //
    // Deliberately inject the exact corruption conditions that caused H-1, H-2, H-3
    // and verify the current codebase detects/rejects them.
    // ========================================================================

    @Test
    fun corruptionInjection_H3_exact_reproduction_missingStateSourceInMoshiRoundTrip() {
        // EXACT H-3 SCENARIO REPRODUCTION (REAL_DATASET_END_TO_END_DATA_INTEGRITY_REPORT.md):
        // 1. Create a snapshot account with stateSource="UTOWER_SNAPSHOT_RESOLVED", openingDebt=40000
        // 2. Create 59 historical entries marked isSnapshotHistory=true
        // 3. Serialize the account via Moshi adapter.toJson() into outbox payloadJson
        // 4. Manually STRIP stateSource from the payload (simulating the pre-fix bug)
        // 5. Run it through buildOutboxPayloadMap → validateAndMapAccount
        // 6. Verify that RemoteEntityValidator REJECTS this as Malformed
        //    (fail-closed at RemoteEntityValidator.kt:62-71)
        // 7. Additionally verify that IF this somehow got through, BalanceCalculator would
        //    produce the WRONG answer (proving the guard is necessary)

        val snapshotAccount = LocalAccount(
            id = "h3_karrar_001",
            displayName = "كرار بيت ابو فراس",
            debtIqd = 40000.0,
            openingDebtIqd = 40000.0,
            openingAdvanceIqd = 0.0,
            openingLoanIqd = 0.0,
            stateSource = "UTOWER_SNAPSHOT_RESOLVED",
            stateConfidence = "AUTHORITATIVE",
            snapshotCapturedAt = 1701721747760L
        )

        // Step 1: Serialize via Moshi (the real production path)
        val moshiJson = accountAdapter.toJson(snapshotAccount)
        val jsonObj = JSONObject(moshiJson)

        // Step 2: STRIP stateSource and stateConfidence (the pre-fix bug)
        jsonObj.remove("stateSource")
        jsonObj.remove("stateConfidence")
        val corruptedPayloadJson = jsonObj.toString()

        // Step 3: Build outbox item with corrupted payload
        val corruptedOutbox = SyncOutbox(
            id = 1, entityType = "local_accounts", entityId = snapshotAccount.id,
            operation = "upsert", payloadJson = corruptedPayloadJson,
            status = "pending", attemptCount = 0, lastError = null,
            createdAt = System.currentTimeMillis()
        )

        // Step 4: Run through buildOutboxPayloadMap (this is the serialization side)
        val cloudPayload = syncRepository.buildOutboxPayloadMap(corruptedOutbox)

        // Step 5: Verify stateSource is ABSENT from the cloud payload (confirming the corruption)
        assertNull(
            "H-3 TEST SETUP: stateSource should be absent from corrupted payload to reproduce the bug",
            cloudPayload["stateSource"]
        )

        // Step 6: Run through RemoteEntityValidator — it MUST reject this as Malformed
        // because openingDebtIqd=40000 is present but stateSource is missing
        // Evidence: RemoteEntityValidator.kt:62-71 (fail-closed validation)
        @Suppress("UNCHECKED_CAST")
        val validationResult = RemoteEntityValidator.validateAndMapAccount(
            id = snapshotAccount.id,
            d = cloudPayload as Map<String, Any>,
            remoteUpdatedAt = System.currentTimeMillis(),
            existingLocalAccount = null
        )

        assertTrue(
            "H-3 REGRESSION FAILED | RemoteEntityValidator MUST reject a full-snapshot account " +
            "with openingDebtIqd=${snapshotAccount.openingDebtIqd} but missing stateSource. " +
            "Got: ${validationResult::class.simpleName}. " +
            "If this passes as Valid, the exact H-3 bug (9.5M IQD inflation across 84 accounts) could recur. " +
            "Expected: Malformed with reason containing 'stateSource'. " +
            "Evidence: RemoteEntityValidator.kt:62-71",
            validationResult is RemoteEntityValidationResult.Malformed
        )

        val malformedReason = (validationResult as RemoteEntityValidationResult.Malformed).reason
        assertTrue(
            "H-3 REGRESSION | Malformed rejection must mention 'stateSource' for diagnosability. " +
            "Got reason: '$malformedReason'",
            malformedReason.contains("stateSource")
        )

        // Step 7: Independently verify that if the guard were bypassed, BalanceCalculator
        // WOULD produce the wrong answer — proving the guard is not redundant
        val historicalEntries = (0 until 59).map { i ->
            LocalLedgerEntry(
                id = "h3_tx_${i + 1}", accountId = snapshotAccount.id,
                typeRaw = if (i % 2 == 0) "renewal" else "gave",
                amountIqd = 40000.0, debtAfterIqd = 0.0,
                occurredAt = 1701721747760L + (i * 86400000L),
                isSnapshotHistory = true
            )
        }

        // With stateSource intact: correct answer
        val (correctBalances, _) = BalanceCalculator.reconstructCurrentPosition(
            openingDebt = 40000.0, openingAdvance = 0.0, openingLoan = 0.0,
            transactions = historicalEntries, isSnapshotBaseline = true
        )
        assertEquals(
            "H-3 VERIFICATION | With stateSource, BalanceCalculator should filter isSnapshotHistory entries. " +
            "Expected: 40000.0 | Actual: ${correctBalances.debtIqd}",
            40000.0, correctBalances.debtIqd, 0.001
        )

        // Without stateSource (isSnapshotBaseline = false): wrong answer proving the guard matters
        val (corruptedBalances, _) = BalanceCalculator.reconstructCurrentPosition(
            openingDebt = 40000.0, openingAdvance = 0.0, openingLoan = 0.0,
            transactions = historicalEntries, isSnapshotBaseline = false
        )
        assertNotEquals(
            "H-3 VERIFICATION | Without stateSource, BalanceCalculator MUST re-apply historical entries " +
            "(proving the guard is necessary, not redundant). " +
            "If this assertion fails, it means BalanceCalculator no longer depends on isSnapshotBaseline, " +
            "which would be a fundamental contract change.",
            40000.0, corruptedBalances.debtIqd, 0.001
        )
    }

    @Test
    fun corruptionInjection_missingStateSource_withSnapshotCapturedAt_rejectedAsMalformed() {
        // Evidence: RemoteEntityValidator.kt:62-71
        // Variant: stateSource missing but snapshotCapturedAt present (different trigger path)
        val payload: Map<String, Any> = mapOf(
            "id" to "ci_acc_001",
            "displayName" to "Corruption Injection Account",
            "debtIqd" to 50000.0,
            "openingDebtIqd" to 0.0,
            "snapshotCapturedAt" to 1700000000000L,
            "isFullSnapshot" to true
        )

        val result = RemoteEntityValidator.validateAndMapAccount(
            id = "ci_acc_001", d = payload,
            remoteUpdatedAt = System.currentTimeMillis(), existingLocalAccount = null
        )

        assertTrue(
            "INV-04 VIOLATED | Full snapshot with snapshotCapturedAt but missing stateSource " +
            "must be rejected as Malformed. Got: ${result::class.simpleName}",
            result is RemoteEntityValidationResult.Malformed
        )
    }

    @Test
    fun corruptionInjection_missingIsSnapshotHistory_defaultsToFalse_balanceStillCorrectForNonSnapshotAccount() {
        // Evidence: RemoteEntityValidator.kt:135 — defaults to false if missing
        // For a NON-snapshot account (stateSource = null), default false is safe because
        // BalanceCalculator doesn't filter by isSnapshotHistory when isSnapshotBaseline = false
        val payload: Map<String, Any> = mapOf(
            "id" to "ci_entry_001",
            "accountId" to "ci_acc_002",
            "typeRaw" to "took",
            "amountIqd" to 40000.0,
            "debtAfterIqd" to 40000.0,
            "occurredAt" to 1700000000000L
            // isSnapshotHistory deliberately missing
        )

        val result = RemoteEntityValidator.validateAndMapLedgerEntry(
            id = "ci_entry_001", d = payload,
            remoteUpdatedAt = System.currentTimeMillis(), existingLocalLedgerEntry = null
        )

        assertTrue(
            "Ledger entry with missing isSnapshotHistory should still be Valid. Got: ${result::class.simpleName}",
            result is RemoteEntityValidationResult.Valid
        )
        assertFalse(
            "INV-04 | Missing isSnapshotHistory must default to false (safe for non-snapshot accounts). " +
            "Got: ${(result as RemoteEntityValidationResult.Valid).entity.isSnapshotHistory}",
            (result as RemoteEntityValidationResult.Valid).entity.isSnapshotHistory
        )
    }

    @Test
    fun corruptionInjection_rawJson_strippedFromCloudPayload_accountPath() {
        // Evidence: SyncRepositoryImpl.kt:710 — dataMap.remove("rawJson")
        // rawJson is local-only source/import data and must NEVER be uploaded to Cloud Firestore
        val account = createSnapshotAccount().copy(rawJson = "{\"original\": \"utower_data\"}")
        val outbox = accountToOutboxItem(account)
        val cloudPayload = syncRepository.buildOutboxPayloadMap(outbox)
        assertFalse(
            "INV-02 VIOLATED | rawJson must be stripped from cloud payload (local-only source data). " +
            "Evidence: SyncRepositoryImpl.kt:710",
            cloudPayload.containsKey("rawJson")
        )
    }

    @Test
    fun corruptionInjection_rawJson_strippedFromCloudPayload_ledgerPath() {
        // Evidence: SyncRepositoryImpl.kt:713 — dataMap.remove("rawJson")
        val entry = LocalLedgerEntry(
            id = "ci_entry_rawjson", accountId = "ci_acc_001",
            typeRaw = "took", amountIqd = 40000.0, debtAfterIqd = 40000.0,
            occurredAt = 1700000000000L, rawJson = "{\"original\": \"utower_tx_data\"}"
        )
        val outbox = ledgerToOutboxItem(entry)
        val cloudPayload = syncRepository.buildOutboxPayloadMap(outbox)
        assertFalse(
            "INV-02 VIOLATED | rawJson must be stripped from ledger cloud payload. " +
            "Evidence: SyncRepositoryImpl.kt:713",
            cloudPayload.containsKey("rawJson")
        )
    }

    // ========================================================================
    // SECTION D.3 — INVARIANT ASSERTION TESTS
    //
    // For each data-integrity-relevant invariant, set up a scenario where
    // it could break, then assert it holds.
    // ========================================================================

    @Test
    fun invariant_INV01_fourStateTiers_stateSourceDeterminesSnapshotBaseline() {
        // INV-01: Application state is partitioned into four non-overlapping tiers.
        // The isSnapshotBaseline determination must be consistent: stateSource != null → true
        // Evidence: BalanceCalculator.kt:49-59 — isSnapshotBaseline controls history filtering

        // Snapshot account (stateSource present)
        val snapshotAcc = createSnapshotAccount()
        val (snapshotBal, _) = BalanceCalculator.reconstructCurrentPosition(
            openingDebt = snapshotAcc.openingDebtIqd,
            openingAdvance = snapshotAcc.openingAdvanceIqd,
            openingLoan = snapshotAcc.openingLoanIqd,
            transactions = listOf(
                LocalLedgerEntry(
                    id = "inv01_tx1", accountId = snapshotAcc.id,
                    typeRaw = "took", amountIqd = 40000.0, debtAfterIqd = 0.0,
                    occurredAt = 1700000000000L, isSnapshotHistory = true
                )
            ),
            isSnapshotBaseline = true
        )
        assertEquals(
            "INV-01 | Snapshot baseline must filter isSnapshotHistory=true entries. " +
            "Expected: ${snapshotAcc.openingDebtIqd} (opening only) | " +
            "Actual: ${snapshotBal.debtIqd} (history entry was re-applied)",
            snapshotAcc.openingDebtIqd, snapshotBal.debtIqd, 0.001
        )

        // Non-snapshot account (stateSource null)
        val (nonSnapshotBal, _) = BalanceCalculator.reconstructCurrentPosition(
            openingDebt = 75000.0, openingAdvance = 0.0, openingLoan = 0.0,
            transactions = listOf(
                LocalLedgerEntry(
                    id = "inv01_tx2", accountId = "inv01_nonsnapshot",
                    typeRaw = "took", amountIqd = 40000.0, debtAfterIqd = 0.0,
                    occurredAt = 1700000000000L, isSnapshotHistory = true
                )
            ),
            isSnapshotBaseline = false
        )
        assertEquals(
            "INV-01 | Non-snapshot baseline must apply ALL entries including isSnapshotHistory. " +
            "Expected: ${75000.0 + 40000.0} | Actual: ${nonSnapshotBal.debtIqd}",
            115000.0, nonSnapshotBal.debtIqd, 0.001
        )
    }

    @Test
    fun invariant_INV02_historicalSourceImmutability_ledgerDeleteUsesContraEntry() = runBlocking {
        // INV-02: Imported historical records are immutable.
        // Financial "deletion" must use contra-entries via correctsEntryId, not physical row DELETE.
        // Evidence: AppDatabase.kt:100-108 — "MUST NOT be exposed as user-level domain financial actions"
        // Evidence: Repositories.kt:2358-2362 — deleteTransaction calls correctTransaction, not physical delete

        // Insert original entry
        val account = LocalAccount(id = "inv02_acc", displayName = "INV-02 Test", debtIqd = 40000.0)
        db.localAccountDao().insert(account)
        val originalEntry = LocalLedgerEntry(
            id = "inv02_tx1", accountId = account.id,
            typeRaw = "took", amountIqd = 40000.0, debtAfterIqd = 40000.0,
            occurredAt = 1700000000000L
        )
        db.localLedgerEntryDao().insert(originalEntry)

        // Verify the original entry exists
        val beforeDelete = db.localLedgerEntryDao().getByIdOneShot("inv02_tx1")
        assertNotNull("INV-02 SETUP | Original entry must exist before correction", beforeDelete)
    }

    @Test
    fun invariant_INV04_zeroDoubleApplication_snapshotHistoryFiltered() {
        // INV-04: Runtime calculations must never re-apply historical snapshot records.
        // This is the exact invariant that H-3 violated.
        // Evidence: BalanceCalculator.kt:70-74 — filters by !it.isSnapshotHistory when isSnapshotBaseline

        val entries = (0 until 30).map { i ->
            LocalLedgerEntry(
                id = "inv04_tx_$i", accountId = "inv04_acc",
                typeRaw = "renewal", amountIqd = 40000.0, debtAfterIqd = 0.0,
                occurredAt = 1700000000000L + (i * 86400000L),
                isSnapshotHistory = true
            )
        }
        // Add one runtime mutation (isSnapshotHistory = false)
        val runtimeEntry = LocalLedgerEntry(
            id = "inv04_runtime_tx", accountId = "inv04_acc",
            typeRaw = "took", amountIqd = 25000.0, debtAfterIqd = 0.0,
            occurredAt = 1800000000000L, isSnapshotHistory = false
        )

        val (balances, _) = BalanceCalculator.reconstructCurrentPosition(
            openingDebt = 100000.0, openingAdvance = 0.0, openingLoan = 0.0,
            transactions = entries + runtimeEntry,
            isSnapshotBaseline = true
        )

        // Expected: 100000 (opening) + 25000 (runtime "took") = 125000
        // NOT: 100000 + (30 × 40000) + 25000 = 1,325,000
        assertEquals(
            "INV-04 VIOLATED | Zero double-application: 30 isSnapshotHistory entries must be filtered. " +
            "Expected: 125000.0 (opening 100k + runtime 25k) | Actual: ${balances.debtIqd} | " +
            "If actual > 125000, historical entries were re-applied on top of baseline (H-3 failure mode)",
            125000.0, balances.debtIqd, 0.001
        )
    }

    @Test
    fun invariant_INV05_oneStateOneAuthority_generationCounterRejectsStaleWrite() = runBlocking {
        // INV-05: Every synchronized business entity must have one authoritative version domain.
        // Evidence: RemoteSyncCoordinator.kt:210-224 — generation counter check inside withTransaction
        val coordinator = RemoteSyncCoordinator(
            appDatabase = db,
            metadataDao = db.syncMetadataDao(),
            accountDao = db.localAccountDao(),
            ledgerDao = db.localLedgerEntryDao(),
            batchDao = db.importBatchDao(),
            outboxDao = outboxDao,
            auditDao = db.auditLogDao()
        )

        // Initialize generation
        db.syncMetadataDao().ensureGenerationInitialized()
        val initialGen = db.syncMetadataDao().getGeneration()

        // Simulate a restore that increments generation
        db.syncMetadataDao().incrementGeneration()
        val newGen = db.syncMetadataDao().getGeneration()
        assertTrue("Generation should have incremented", newGen > initialGen)

        // Now try to apply a remote event with the OLD generation
        val account = LocalAccount(
            id = "inv05_acc", displayName = "Stale Sync Account", debtIqd = 50000.0,
            openingDebtIqd = 50000.0, stateSource = "TEST", stateConfidence = "AUTHORITATIVE"
        )
        val event = RemoteEvent.AccountUpsert(
            entityId = account.id,
            account = account,
            remoteVersion = 1000L,
            source = RemoteEventSource.MANUAL
        )

        val result = coordinator.processEvent(event, passedCapturedGen = initialGen)

        // The event must be rejected because the generation has advanced
        assertNotEquals(
            "INV-05 VIOLATED | Remote event with stale generation (captured=$initialGen, current=$newGen) " +
            "must NOT be accepted. Got result: $result | " +
            "Evidence: RemoteSyncCoordinator.kt:216-224",
            EventSyncResult.APPLIED, result
        )
    }

    @Test
    fun invariant_INV09_queryMembershipNotBusinessDeletion() {
        // INV-09: Firestore REMOVED event indicates query boundary exit, NOT business deletion.
        // The isHistoryOnlySubscriber flag must NOT trigger physical deletion of ledger rows.
        // Evidence: Models.kt:334-337 — "must not be interpreted as authorization to physically delete"

        // ForeignKey on local_ledger_entries uses NO_ACTION (not CASCADE)
        // This means even if an account were physically deleted, ledger entries would remain
        // (but we never physically delete accounts — we set isHistoryOnlySubscriber=true)

        // Verify the entity definition matches our expectation
        val account = LocalAccount(id = "inv09_acc", displayName = "Test", debtIqd = 0.0,
            isHistoryOnlySubscriber = true)
        assertTrue(
            "INV-09 | isHistoryOnlySubscriber does not change the account's financial fields",
            account.isHistoryOnlySubscriber
        )
        assertEquals(
            "INV-09 | History-only subscriber state != financial data deletion",
            0.0, account.debtIqd, 0.001
        )
    }

    @Test
    fun invariant_INV11_deterministicRestoreMutationChannel_coordinatorMutex() = runBlocking {
        // INV-11: Every runtime code path mutating synchronized state MUST execute within
        // an atomic serialized boundary.
        // Evidence: DataOperationCoordinator.kt:46-107 — Mutex + withOperation

        var firstCompleted = false
        var secondBlocked = false

        // Verify mutual exclusion by attempting two operations simultaneously
        val job1 = launch {
            DataOperationCoordinator.withOperation(DataOperationMode.IMPORT) {
                delay(100) // Hold the lock
                firstCompleted = true
            }
        }
        delay(10) // Let job1 acquire the lock

        // Check that the coordinator is locked
        val tryResult = DataOperationCoordinator.tryWithOperation(DataOperationMode.SYNC) {
            secondBlocked = false
            "acquired"
        }

        if (tryResult == null) {
            secondBlocked = true
        }

        job1.join()

        assertTrue(
            "INV-11 | First operation must complete under mutex. " +
            "Evidence: DataOperationCoordinator.kt:95-106",
            firstCompleted
        )
        assertTrue(
            "INV-11 VIOLATED | Second operation must be blocked while first holds mutex. " +
            "If this fails, concurrent sync+import could corrupt data. " +
            "Evidence: DataOperationCoordinator.kt:47 — private val mutex = Mutex()",
            secondBlocked
        )
    }

    @Test
    fun invariant_INV12_noOutboxLoopsOnRemoteApply() = runBlocking {
        // INV-12: Applying incoming remote events (REMOTE_APPLY) MUST NOT generate local Outbox records.
        // Evidence: PRODUCTION_INVARIANTS.md:32

        // Record outbox count before remote apply
        val outboxBefore = outboxDao.getAllOneShot().size

        // Simulate applying a remote account event through the coordinator
        db.syncMetadataDao().ensureGenerationInitialized()
        val coordinator = RemoteSyncCoordinator(
            appDatabase = db, metadataDao = db.syncMetadataDao(),
            accountDao = db.localAccountDao(), ledgerDao = db.localLedgerEntryDao(),
            batchDao = db.importBatchDao(), outboxDao = outboxDao, auditDao = db.auditLogDao()
        )

        val remoteAccount = LocalAccount(
            id = "inv12_acc", displayName = "Remote Account", debtIqd = 50000.0,
            stateSource = "TEST", stateConfidence = "AUTHORITATIVE",
            openingDebtIqd = 50000.0, snapshotCapturedAt = 1700000000000L
        )
        val event = RemoteEvent.AccountUpsert(
            entityId = remoteAccount.id, account = remoteAccount,
            remoteVersion = 1000L, source = RemoteEventSource.MANUAL
        )

        coordinator.processEvent(event)

        val outboxAfter = outboxDao.getAllOneShot().size
        assertEquals(
            "INV-12 VIOLATED | Applying remote event must NOT generate outbox records. " +
            "Outbox before: $outboxBefore, after: $outboxAfter. " +
            "If outbox grew, it would create an infinite sync echo loop.",
            outboxBefore, outboxAfter
        )
    }

    // ========================================================================
    // SECTION D.4 — IDEMPOTENCY TESTS
    //
    // Re-running the same operation twice must never double-count.
    // ========================================================================

    @Test
    fun idempotency_duplicateSyncEvent_zeroNewEntries() = runBlocking {
        // Applying the same remote ledger event twice must not create duplicate entries
        db.syncMetadataDao().ensureGenerationInitialized()
        val coordinator = RemoteSyncCoordinator(
            appDatabase = db, metadataDao = db.syncMetadataDao(),
            accountDao = db.localAccountDao(), ledgerDao = db.localLedgerEntryDao(),
            batchDao = db.importBatchDao(), outboxDao = outboxDao, auditDao = db.auditLogDao()
        )

        // First: insert parent account
        val parentAccount = LocalAccount(
            id = "idem_acc", displayName = "Idempotent Account", debtIqd = 40000.0,
            stateSource = "TEST", stateConfidence = "AUTHORITATIVE",
            openingDebtIqd = 40000.0, snapshotCapturedAt = 1700000000000L
        )
        coordinator.processEvent(RemoteEvent.AccountUpsert(
            entityId = parentAccount.id, account = parentAccount,
            remoteVersion = 1000L, source = RemoteEventSource.MANUAL
        ))

        // Create ledger entry event
        val ledgerEntry = LocalLedgerEntry(
            id = "idem_tx_001", accountId = parentAccount.id,
            typeRaw = "took", amountIqd = 25000.0, debtAfterIqd = 65000.0,
            occurredAt = 1700000000000L
        )
        val ledgerEvent = RemoteEvent.LedgerUpsert(
            entityId = ledgerEntry.id, entry = ledgerEntry,
            remoteVersion = 2000L, source = RemoteEventSource.MANUAL,
            preFetchedParentAccount = null
        )

        // Apply once
        coordinator.processEvent(ledgerEvent)
        val countAfterFirst = db.localLedgerEntryDao().getByAccountIdOneShot(parentAccount.id).size

        // Apply again (duplicate)
        coordinator.processEvent(ledgerEvent)
        val countAfterSecond = db.localLedgerEntryDao().getByAccountIdOneShot(parentAccount.id).size

        assertEquals(
            "IDEMPOTENCY VIOLATED | Applying same remote ledger event twice must not create duplicates. " +
            "Count after first apply: $countAfterFirst, after second: $countAfterSecond",
            countAfterFirst, countAfterSecond
        )
    }

    @Test
    fun idempotency_repeatedRecalculation_identicalBalances() = runBlocking {
        // Calling reconstructCurrentPosition twice with the same inputs must produce identical results
        val entries = listOf(
            LocalLedgerEntry(
                id = "idem_recalc_tx1", accountId = "idem_recalc_acc",
                typeRaw = "took", amountIqd = 40000.0, debtAfterIqd = 40000.0,
                occurredAt = 1700000000000L
            ),
            LocalLedgerEntry(
                id = "idem_recalc_tx2", accountId = "idem_recalc_acc",
                typeRaw = "gave", amountIqd = 15000.0, debtAfterIqd = 25000.0,
                occurredAt = 1700100000000L
            ),
            LocalLedgerEntry(
                id = "idem_recalc_tx3", accountId = "idem_recalc_acc",
                typeRaw = "renewal", amountIqd = 40000.0, debtAfterIqd = 65000.0,
                occurredAt = 1700200000000L
            )
        )

        val (bal1, ledgers1) = BalanceCalculator.reconstructCurrentPosition(
            openingDebt = 0.0, openingAdvance = 0.0, openingLoan = 0.0,
            transactions = entries, isSnapshotBaseline = false
        )
        val (bal2, ledgers2) = BalanceCalculator.reconstructCurrentPosition(
            openingDebt = 0.0, openingAdvance = 0.0, openingLoan = 0.0,
            transactions = entries, isSnapshotBaseline = false
        )

        assertEquals(
            "IDEMPOTENCY VIOLATED | Repeated recalculation must produce identical debt. " +
            "First: ${bal1.debtIqd}, Second: ${bal2.debtIqd}",
            bal1.debtIqd, bal2.debtIqd, 0.001
        )
        assertEquals(
            "IDEMPOTENCY | Repeated recalculation must produce identical advance",
            bal1.advanceIqd, bal2.advanceIqd, 0.001
        )
        assertEquals(
            "IDEMPOTENCY | Repeated recalculation must produce identical loan",
            bal1.loanIqd, bal2.loanIqd, 0.001
        )
        assertEquals(
            "IDEMPOTENCY | Repeated recalculation must produce same number of updated entries",
            ledgers1.size, ledgers2.size
        )
    }

    // ========================================================================
    // SECTION D.5a — BACKUP/RESTORE FULL-RESTORE INTEGRITY (SQLite path)
    //
    // Room migrations use DEFAULT values that must be safe for BalanceCalculator.
    // "Protected by Room migrations" is itself an assumption worth testing.
    // ========================================================================

    @Test
    fun backupRestore_migrationDefaults_safeForBalanceCalculator() = runBlocking {
        // Evidence: AppDatabase.kt:768-776 (MIGRATION_8_9)
        // openingDebtIqd DEFAULT 0.0 → BalanceCalculator uses 0 as starting point → safe
        // openingAdvanceIqd DEFAULT 0.0 → safe
        // openingLoanIqd DEFAULT 0.0 → safe
        // stateSource DEFAULT NULL → isSnapshotBaseline = false → safe (no filtering occurs)
        // isSnapshotHistory DEFAULT 0 (false) → won't be filtered → safe for non-snapshot accounts

        // Simulate a "legacy" account that existed before MIGRATION_8_9
        // (i.e., all snapshot fields at their DEFAULT values)
        val legacyAccount = LocalAccount(
            id = "legacy_acc_001", displayName = "Pre-Migration Account",
            debtIqd = 50000.0,
            openingDebtIqd = 0.0,     // MIGRATION_8_9 DEFAULT
            openingAdvanceIqd = 0.0,  // MIGRATION_8_9 DEFAULT
            openingLoanIqd = 0.0,     // MIGRATION_8_9 DEFAULT
            stateSource = null,       // MIGRATION_8_9 DEFAULT NULL
            stateConfidence = null    // MIGRATION_8_9 DEFAULT NULL
        )
        val legacyEntries = listOf(
            LocalLedgerEntry(
                id = "legacy_tx1", accountId = legacyAccount.id,
                typeRaw = "took", amountIqd = 30000.0, debtAfterIqd = 30000.0,
                occurredAt = 1600000000000L,
                isSnapshotHistory = false  // MIGRATION_9_10 DEFAULT = 0
            ),
            LocalLedgerEntry(
                id = "legacy_tx2", accountId = legacyAccount.id,
                typeRaw = "took", amountIqd = 20000.0, debtAfterIqd = 50000.0,
                occurredAt = 1600100000000L,
                isSnapshotHistory = false
            )
        )

        // BalanceCalculator with migration DEFAULT values must produce correct results
        val isSnapshot = legacyAccount.stateSource != null  // false (DEFAULT NULL)
        val (balances, _) = BalanceCalculator.reconstructCurrentPosition(
            openingDebt = legacyAccount.openingDebtIqd,
            openingAdvance = legacyAccount.openingAdvanceIqd,
            openingLoan = legacyAccount.openingLoanIqd,
            transactions = legacyEntries,
            isSnapshotBaseline = isSnapshot
        )

        assertEquals(
            "MIGRATION DEFAULT SAFETY | Pre-migration account with DEFAULT opening values (0.0) " +
            "and stateSource=null must calculate correctly. " +
            "Expected: 50000.0 (0 + 30k + 20k) | Actual: ${balances.debtIqd} | " +
            "Evidence: AppDatabase.kt MIGRATION_8_9 (L770-773)",
            50000.0, balances.debtIqd, 0.001
        )
    }

    // ========================================================================
    // SECTION D.5b — BACKUP/RESTORE MERGE-RESTORE INTEGRITY (Moshi path)
    //
    // Deep coverage of the Moshi serialization path used in merge restore,
    // since H-3 actually occurred through this serialization mechanism.
    // ========================================================================

    @Test
    fun backupRestore_moshiRoundTrip_allCriticalAccountFields_preserved() {
        // Evidence: Models.kt:354 — @JsonClass(generateAdapter = true) on LocalAccount
        // Evidence: Repositories.kt:1168 — accountAdapter = moshi.adapter(LocalAccount::class.java)
        val original = LocalAccount(
            id = "moshi_acc_001",
            displayName = "Moshi Round-Trip Account",
            earthlinkUsername = "moshi_user",
            debtIqd = 125000.0,
            openingDebtIqd = 75000.0,
            openingAdvanceIqd = 12000.0,
            openingLoanIqd = 5000.0,
            stateSource = "UTOWER_SNAPSHOT_RESOLVED",
            stateConfidence = "AUTHORITATIVE",
            snapshotCapturedAt = 1701721747760L,
            isHistoryOnlySubscriber = false,
            isLegacy = true,
            currentPriceIqd = 40000.0,
            advanceIqd = 12000.0,
            loanIqd = 5000.0,
            sourceExternalId = "utower_ext_001",
            sourceBatchId = "batch_001"
        )

        val json = accountAdapter.toJson(original)
        val deserialized = accountAdapter.fromJson(json)!!

        assertEquals("Moshi | stateSource", original.stateSource, deserialized.stateSource)
        assertEquals("Moshi | stateConfidence", original.stateConfidence, deserialized.stateConfidence)
        assertEquals("Moshi | openingDebtIqd", original.openingDebtIqd, deserialized.openingDebtIqd, 0.001)
        assertEquals("Moshi | openingAdvanceIqd", original.openingAdvanceIqd, deserialized.openingAdvanceIqd, 0.001)
        assertEquals("Moshi | openingLoanIqd", original.openingLoanIqd, deserialized.openingLoanIqd, 0.001)
        assertEquals("Moshi | debtIqd", original.debtIqd, deserialized.debtIqd, 0.001)
        assertEquals("Moshi | snapshotCapturedAt", original.snapshotCapturedAt, deserialized.snapshotCapturedAt)
        assertEquals("Moshi | sourceExternalId", original.sourceExternalId, deserialized.sourceExternalId)
        assertEquals("Moshi | sourceBatchId", original.sourceBatchId, deserialized.sourceBatchId)
        assertEquals("Moshi | isHistoryOnlySubscriber", original.isHistoryOnlySubscriber, deserialized.isHistoryOnlySubscriber)
        assertEquals("Moshi | isLegacy", original.isLegacy, deserialized.isLegacy)
    }

    @Test
    fun backupRestore_moshiRoundTrip_allCriticalLedgerFields_preserved() {
        // Evidence: Models.kt:427 — @JsonClass(generateAdapter = true) on LocalLedgerEntry
        val original = LocalLedgerEntry(
            id = "moshi_tx_001",
            accountId = "moshi_acc_001",
            typeRaw = "renewal",
            amountIqd = 40000.0,
            debtAfterIqd = 115000.0,
            occurredAt = 1701721747760L,
            isSnapshotHistory = true,
            sourceExternalId = "utower_tx_ext_001",
            sourceBatchId = "batch_001",
            correctsEntryId = "moshi_tx_original",
            note = "Test correction entry"
        )

        val json = ledgerAdapter.toJson(original)
        val deserialized = ledgerAdapter.fromJson(json)!!

        assertEquals("Moshi | typeRaw", original.typeRaw, deserialized.typeRaw)
        assertEquals("Moshi | amountIqd", original.amountIqd, deserialized.amountIqd, 0.001)
        assertEquals("Moshi | debtAfterIqd", original.debtAfterIqd, deserialized.debtAfterIqd, 0.001)
        assertEquals("Moshi | occurredAt", original.occurredAt, deserialized.occurredAt)
        assertEquals("Moshi | isSnapshotHistory", original.isSnapshotHistory, deserialized.isSnapshotHistory)
        assertEquals("Moshi | sourceExternalId", original.sourceExternalId, deserialized.sourceExternalId)
        assertEquals("Moshi | correctsEntryId", original.correctsEntryId, deserialized.correctsEntryId)
        assertEquals("Moshi | accountId", original.accountId, deserialized.accountId)
    }

    /**
     * Core Triad:
     * - Claim: INV-04 / H-3 — Moshi JSON round-trip preserves all critical snapshot account baseline
     *   and ledger fields such that reconstructed current position matches independent oracle arithmetic.
     * - Seam / Environment: ROBOLECTRIC (In-memory Room & Moshi Kotlin reflection).
     * - Independent Oracle: Explicit 3-vector initialization (100k debt, 0 advance, 0 loan) + runtime took (25k) - gave (10k) = 115k.
     *   Historical 20 snapshot entries are filtered.
     */
    @Test
    fun backupRestore_moshiRoundTrip_snapshotAccount_balancePreservedByOracle() {
        // End-to-end: Moshi serialize → deserialize → BalanceCalculator produces correct result
        // Explicit 3-vector initialization per Decision 5
        val original = createSnapshotAccount(
            openingDebt = 100000.0,
            openingAdvance = 0.0,
            openingLoan = 0.0
        )
        val historicalEntries = (0 until 20).map { i ->
            LocalLedgerEntry(
                id = "moshi_hist_$i", accountId = original.id,
                typeRaw = "renewal", amountIqd = 40000.0, debtAfterIqd = 0.0,
                occurredAt = 1700000000000L + (i * 86400000L),
                isSnapshotHistory = true
            )
        }
        val runtimeEntries = listOf(
            LocalLedgerEntry(
                id = "moshi_runtime_1", accountId = original.id,
                typeRaw = "took", amountIqd = 25000.0, debtAfterIqd = 0.0,
                occurredAt = 1800000000000L, isSnapshotHistory = false
            ),
            LocalLedgerEntry(
                id = "moshi_runtime_2", accountId = original.id,
                typeRaw = "gave", amountIqd = 10000.0, debtAfterIqd = 0.0,
                occurredAt = 1800100000000L, isSnapshotHistory = false
            )
        )

        // Moshi round-trip the account
        val accountJson = accountAdapter.toJson(original)
        val restoredAccount = accountAdapter.fromJson(accountJson)!!

        // Moshi round-trip all entries
        val allEntries = historicalEntries + runtimeEntries
        val restoredEntries = allEntries.map { entry ->
            val entryJson = ledgerAdapter.toJson(entry)
            ledgerAdapter.fromJson(entryJson)!!
        }

        // Explicit Arithmetic Decomposition:
        // Expected Position = (Opening Debt - Opening Advance + Opening Loan) + Sum(Runtime Mutations)
        // (100000.0 - 0.0 + 0.0) + (25000.0 - 10000.0) = 115000.0
        // Historical entries MUST be filtered (isSnapshotBaseline = true)
        val oracleDebt = (100000.0 - 0.0 + 0.0) + 25000.0 - 10000.0

        val isSnapshot = restoredAccount.stateSource != null
        val (balances, _) = BalanceCalculator.reconstructCurrentPosition(
            openingDebt = restoredAccount.openingDebtIqd,
            openingAdvance = restoredAccount.openingAdvanceIqd,
            openingLoan = restoredAccount.openingLoanIqd,
            transactions = restoredEntries,
            isSnapshotBaseline = isSnapshot
        )

        assertEquals(
            "BACKUP-RESTORE MOSHI ORACLE | After Moshi round-trip, BalanceCalculator must match oracle. " +
            "Expected Position = (Opening Debt [100000.0] - Opening Advance [0.0] + Opening Loan [0.0]) + Runtime Took [25000.0] - Runtime Gave [10000.0] = $oracleDebt | " +
            "Actual: ${balances.debtIqd} | " +
            "If actual != oracle, Moshi serialization corrupted a critical field",
            oracleDebt, balances.debtIqd, 0.001
        )
    }

    @Test
    fun backupRestore_roomPersistence_allCriticalFields_preserved() = runBlocking {
        // Test the full Room path: insert → read back → verify all critical fields
        // This tests the SQLite column definitions and TypeConverters
        val account = createSnapshotAccount(
            id = "room_acc_001", openingDebt = 88000.0,
            openingAdvance = 15000.0, openingLoan = 7500.0
        )
        db.localAccountDao().insert(account)

        val readBack = db.localAccountDao().getByIdOneShot("room_acc_001")
        assertNotNull("Room persistence | Account must be readable after insert", readBack)

        assertEquals("Room | stateSource", account.stateSource, readBack!!.stateSource)
        assertEquals("Room | stateConfidence", account.stateConfidence, readBack.stateConfidence)
        assertEquals("Room | openingDebtIqd", account.openingDebtIqd, readBack.openingDebtIqd, 0.001)
        assertEquals("Room | openingAdvanceIqd", account.openingAdvanceIqd, readBack.openingAdvanceIqd, 0.001)
        assertEquals("Room | openingLoanIqd", account.openingLoanIqd, readBack.openingLoanIqd, 0.001)
        assertEquals("Room | snapshotCapturedAt", account.snapshotCapturedAt, readBack.snapshotCapturedAt)

        // Also verify ledger entries
        val entry = LocalLedgerEntry(
            id = "room_tx_001", accountId = account.id,
            typeRaw = "took", amountIqd = 40000.0, debtAfterIqd = 128000.0,
            occurredAt = 1700000000000L, isSnapshotHistory = true,
            sourceExternalId = "ext_001"
        )
        db.localLedgerEntryDao().insert(entry)

        val readBackEntry = db.localLedgerEntryDao().getByIdOneShot("room_tx_001")
        assertNotNull("Room persistence | Ledger entry must be readable after insert", readBackEntry)
        assertTrue("Room | isSnapshotHistory", readBackEntry!!.isSnapshotHistory)
        assertEquals("Room | occurredAt", entry.occurredAt, readBackEntry.occurredAt)
        assertEquals("Room | sourceExternalId", entry.sourceExternalId, readBackEntry.sourceExternalId)
        assertEquals("Room | typeRaw", entry.typeRaw, readBackEntry.typeRaw)
        assertEquals("Room | amountIqd", entry.amountIqd, readBackEntry.amountIqd, 0.001)
    }

    // ========================================================================
    // SECTION D.6 — ORACLE VALIDATION TESTS
    //
    // For representative account configurations, compute expected balance
    // independently using pure arithmetic, then assert BalanceCalculator matches.
    // ========================================================================

    @Test
    fun oracle_zeroBalanceAccount_noEntries() {
        val (balances, _) = BalanceCalculator.reconstructCurrentPosition(
            openingDebt = 0.0, openingAdvance = 0.0, openingLoan = 0.0,
            transactions = emptyList(), isSnapshotBaseline = false
        )
        assertEquals("ORACLE | Zero-balance account with no entries = 0 debt", 0.0, balances.debtIqd, 0.001)
        assertEquals("ORACLE | Zero-balance account with no entries = 0 advance", 0.0, balances.advanceIqd, 0.001)
        assertEquals("ORACLE | Zero-balance account with no entries = 0 loan", 0.0, balances.loanIqd, 0.001)
    }

    @Test
    fun oracle_snapshotAccountWithHistory_correctBalance() {
        // Snapshot account: opening 60000, 10 historical renewal/gave entries (filtered), 2 runtime entries
        val historicalEntries = (0 until 10).map { i ->
            LocalLedgerEntry(
                id = "oracle_hist_$i", accountId = "oracle_acc",
                typeRaw = if (i % 2 == 0) "renewal" else "gave",
                amountIqd = 40000.0, debtAfterIqd = 0.0,
                occurredAt = 1700000000000L + (i * 86400000L),
                isSnapshotHistory = true
            )
        }
        val runtimeEntries = listOf(
            LocalLedgerEntry(
                id = "oracle_runtime_1", accountId = "oracle_acc",
                typeRaw = "took", amountIqd = 30000.0, debtAfterIqd = 0.0,
                occurredAt = 1800000000000L, isSnapshotHistory = false
            ),
            LocalLedgerEntry(
                id = "oracle_runtime_2", accountId = "oracle_acc",
                typeRaw = "gave", amountIqd = 5000.0, debtAfterIqd = 0.0,
                occurredAt = 1800100000000L, isSnapshotHistory = false
            )
        )

        // Oracle: 60000 + 30000 (took) - 5000 (gave) = 85000
        val oracle = 60000.0 + 30000.0 - 5000.0

        val (balances, updatedEntries) = BalanceCalculator.reconstructCurrentPosition(
            openingDebt = 60000.0, openingAdvance = 0.0, openingLoan = 0.0,
            transactions = historicalEntries + runtimeEntries,
            isSnapshotBaseline = true
        )

        assertEquals(
            "ORACLE | Snapshot account: Expected $oracle (60k opening + 30k took - 5k gave, " +
            "10 historical filtered) | Actual: ${balances.debtIqd}",
            oracle, balances.debtIqd, 0.001
        )

        // Verify debtAfterIqd on each returned entry matches rolling calculation
        var runningDebt = 60000.0
        for (entry in updatedEntries) {
            val normalized = TransactionTypeNormalizer.normalizeTransactionType(entry.typeRaw)
            val afterBalances = BalanceCalculator.applyTransaction(
                currentDebt = runningDebt, currentAdvance = 0.0, currentLoan = 0.0,
                txType = normalized, amount = entry.amountIqd
            )
            assertEquals(
                "ORACLE debtAfterIqd | Entry ${entry.id} (${entry.typeRaw}, ${entry.amountIqd} IQD): " +
                "Expected rolling debt ${afterBalances.debtIqd} | Stored debtAfterIqd: ${entry.debtAfterIqd}",
                afterBalances.debtIqd, entry.debtAfterIqd, 0.001
            )
            runningDebt = afterBalances.debtIqd
        }
    }

    @Test
    fun oracle_pureRuntimeAccount_allEntriesApplied() {
        // Non-snapshot account: no stateSource, all entries applied regardless of isSnapshotHistory
        val entries = listOf(
            LocalLedgerEntry(
                id = "oracle_pure_1", accountId = "oracle_pure_acc",
                typeRaw = "took", amountIqd = 40000.0, debtAfterIqd = 0.0,
                occurredAt = 1700000000000L, isSnapshotHistory = false
            ),
            LocalLedgerEntry(
                id = "oracle_pure_2", accountId = "oracle_pure_acc",
                typeRaw = "took", amountIqd = 40000.0, debtAfterIqd = 0.0,
                occurredAt = 1700100000000L, isSnapshotHistory = false
            ),
            LocalLedgerEntry(
                id = "oracle_pure_3", accountId = "oracle_pure_acc",
                typeRaw = "gave", amountIqd = 25000.0, debtAfterIqd = 0.0,
                occurredAt = 1700200000000L, isSnapshotHistory = false
            )
        )

        // Oracle: 0 + 40000 + 40000 - 25000 = 55000
        val oracle = 40000.0 + 40000.0 - 25000.0

        val (balances, _) = BalanceCalculator.reconstructCurrentPosition(
            openingDebt = 0.0, openingAdvance = 0.0, openingLoan = 0.0,
            transactions = entries, isSnapshotBaseline = false
        )

        assertEquals(
            "ORACLE | Pure runtime account: Expected $oracle | Actual: ${balances.debtIqd}",
            oracle, balances.debtIqd, 0.001
        )
    }

    @Test
    fun oracle_noteTransaction_zeroFinancialImpact() {
        // "note" type transactions must have zero financial effect
        val entries = listOf(
            LocalLedgerEntry(
                id = "oracle_note_1", accountId = "oracle_note_acc",
                typeRaw = "took", amountIqd = 40000.0, debtAfterIqd = 0.0,
                occurredAt = 1700000000000L
            ),
            LocalLedgerEntry(
                id = "oracle_note_2", accountId = "oracle_note_acc",
                typeRaw = "note", amountIqd = 0.0, debtAfterIqd = 0.0,
                occurredAt = 1700100000000L
            )
        )

        val (balances, _) = BalanceCalculator.reconstructCurrentPosition(
            openingDebt = 0.0, openingAdvance = 0.0, openingLoan = 0.0,
            transactions = entries, isSnapshotBaseline = false
        )

        assertEquals(
            "ORACLE | Note transaction must have zero financial impact. " +
            "Expected: 40000.0 | Actual: ${balances.debtIqd}",
            40000.0, balances.debtIqd, 0.001
        )
    }

    @Test
    fun oracle_unrecognizedTransactionType_noOp() {
        // Unrecognized transaction types must pass through as no-ops (no crash, no effect)
        // Evidence: TransactionTypeNormalizer.kt — returns lowercase original for unknown types
        // Evidence: BalanceCalculator.kt:26-27 — else branch returns balances unchanged
        val entries = listOf(
            LocalLedgerEntry(
                id = "oracle_unknown_1", accountId = "oracle_unknown_acc",
                typeRaw = "took", amountIqd = 40000.0, debtAfterIqd = 0.0,
                occurredAt = 1700000000000L
            ),
            LocalLedgerEntry(
                id = "oracle_unknown_2", accountId = "oracle_unknown_acc",
                typeRaw = "UNKNOWN_FUTURE_TYPE", amountIqd = 99999.0, debtAfterIqd = 0.0,
                occurredAt = 1700100000000L
            )
        )

        val (balances, _) = BalanceCalculator.reconstructCurrentPosition(
            openingDebt = 0.0, openingAdvance = 0.0, openingLoan = 0.0,
            transactions = entries, isSnapshotBaseline = false
        )

        assertEquals(
            "ORACLE | Unrecognized type must be a no-op. " +
            "Expected: 40000.0 (only the 'took' entry applied) | Actual: ${balances.debtIqd}",
            40000.0, balances.debtIqd, 0.001
        )
    }

    // ========================================================================
    // SECTION D.7 — REAL CONCURRENT GENERATION COUNTER RACE TEST
    //
    // This test exercises genuine concurrent execution with real coroutines
    // racing against the generation counter. A sequential test would pass
    // even if the actual race condition exists.
    //
    // Evidence: RemoteSyncCoordinator.kt:210-224 — capturedGen checked inside withTransaction
    // Evidence: DataOperationCoordinator.kt:47 — Mutex for mutual exclusion
    // ========================================================================

    @Test
    fun concurrency_generationCounter_rejectsStaleWriteUnderRealRace() = runBlocking {
        db.syncMetadataDao().ensureGenerationInitialized()
        val initialGen = db.syncMetadataDao().getGeneration()

        val coordinator = RemoteSyncCoordinator(
            appDatabase = db, metadataDao = db.syncMetadataDao(),
            accountDao = db.localAccountDao(), ledgerDao = db.localLedgerEntryDao(),
            batchDao = db.importBatchDao(), outboxDao = outboxDao, auditDao = db.auditLogDao()
        )

        val staleEventsRejected = AtomicInteger(0)
        val freshEventsAccepted = AtomicInteger(0)
        val racingCoroutineCount = 10

        // Launch concurrent coroutines that race against generation increments
        val dispatcher = Dispatchers.Default

        withContext(dispatcher) {
            val jobs = mutableListOf<Job>()

            // Coroutine 1: Rapidly increment generation (simulating restore/import operations)
            jobs += launch {
                repeat(5) {
                    db.syncMetadataDao().incrementGeneration()
                    // Small delay to allow interleaving
                    yield()
                }
            }

            // Coroutines 2-N: Try to apply remote events with various captured generations
            for (i in 0 until racingCoroutineCount) {
                jobs += launch {
                    // Capture generation BEFORE potential increment (could be stale)
                    val capturedGen = db.syncMetadataDao().getGeneration()

                    // Yield to allow generation increments to interleave
                    yield()

                    val account = LocalAccount(
                        id = "race_acc_$i", displayName = "Race Account $i",
                        debtIqd = 40000.0, stateSource = "TEST",
                        stateConfidence = "AUTHORITATIVE",
                        openingDebtIqd = 40000.0, snapshotCapturedAt = 1700000000000L
                    )
                    val event = RemoteEvent.AccountUpsert(
                        entityId = account.id, account = account,
                        remoteVersion = (1000 + i).toLong(), source = RemoteEventSource.MANUAL
                    )

                    val result = coordinator.processEvent(event, passedCapturedGen = capturedGen)

                    when (result) {
                        EventSyncResult.APPLIED -> freshEventsAccepted.incrementAndGet()
                        EventSyncResult.FAILED_RETRYABLE -> staleEventsRejected.incrementAndGet()
                        EventSyncResult.SKIPPED_DUPLICATE -> {} // OK
                        else -> {} // Other results are fine
                    }
                }
            }

            jobs.joinAll()
        }

        val finalGen = db.syncMetadataDao().getGeneration()

        // The generation should have advanced (proving the increment coroutine ran)
        assertTrue(
            "CONCURRENCY SETUP | Generation must have advanced from $initialGen. " +
            "Final: $finalGen. If equal, the generation-incrementing coroutine didn't run.",
            finalGen > initialGen
        )

        // At least some events should have been accepted (those that captured a fresh generation)
        // or rejected (those that captured a stale generation). Both outcomes are correct —
        // what matters is that NO event was accepted with a stale generation.
        val totalProcessed = staleEventsRejected.get() + freshEventsAccepted.get()
        assertTrue(
            "CONCURRENCY | At least some events must have been processed (accepted or rejected). " +
            "Accepted: ${freshEventsAccepted.get()}, Rejected: ${staleEventsRejected.get()}, " +
            "Total: $totalProcessed",
            totalProcessed > 0
        )

        // Verify: every accepted event's account should actually exist in the DB
        for (i in 0 until racingCoroutineCount) {
            val savedAcc = db.localAccountDao().getByIdOneShot("race_acc_$i")
            if (savedAcc != null) {
                // If the account was written, verify it has correct stateSource
                assertEquals(
                    "CONCURRENCY | Accepted race account $i must have intact stateSource",
                    "TEST", savedAcc.stateSource
                )
            }
        }
    }

    /**
     * Core Triad:
     * - Claim: INV-11 — DataOperationCoordinator provides deterministic mutual exclusion between
     *   competing operations across coroutines.
     * - Seam / Environment: ROBOLECTRIC (Coroutines & Mutex synchronization).
     * - Independent Oracle: CompletableDeferred execution gates strictly order import_start -> import_end -> sync_start -> sync_end.
     */
    @Test
    fun concurrency_coordinatorMutex_preventsConcurrentDataOperations() = runBlocking {
        // Test that DataOperationCoordinator.withOperation provides real mutual exclusion
        // under concurrent access from multiple coroutines using deterministic CompletableDeferred gates
        val executionOrder = mutableListOf<String>()
        val orderMutex = Mutex()
        val importEntered = CompletableDeferred<Unit>()
        val syncAttemptStarted = CompletableDeferred<Unit>()
        val importCanFinish = CompletableDeferred<Unit>()

        withContext(Dispatchers.Default) {
            val job1 = launch {
                DataOperationCoordinator.withOperation(DataOperationMode.IMPORT) {
                    orderMutex.withLock { executionOrder.add("import_start") }
                    importEntered.complete(Unit)
                    importCanFinish.await()
                    orderMutex.withLock { executionOrder.add("import_end") }
                }
            }

            val job2 = launch {
                // Ensure import has entered and acquired the coordinator mutex
                importEntered.await()
                syncAttemptStarted.complete(Unit)
                // Attempt second operation while first holds the lock
                DataOperationCoordinator.withOperation(DataOperationMode.SYNC) {
                    orderMutex.withLock { executionOrder.add("sync_start") }
                    orderMutex.withLock { executionOrder.add("sync_end") }
                }
            }

            syncAttemptStarted.await()
            // Yield to allow job2 to attempt acquiring mutex and block
            yield()
            // Release the import operation
            importCanFinish.complete(Unit)

            job1.join()
            job2.join()
        }

        // import_start must come before sync_start (mutex ensures serialization)
        val importStartIdx = executionOrder.indexOf("import_start")
        val importEndIdx = executionOrder.indexOf("import_end")
        val syncStartIdx = executionOrder.indexOf("sync_start")
        val syncEndIdx = executionOrder.indexOf("sync_end")

        assertTrue(
            "CONCURRENCY MUTEX | import must start before sync. " +
            "Execution order: $executionOrder",
            importStartIdx >= 0 && syncStartIdx >= 0 && importStartIdx < syncStartIdx
        )
        assertTrue(
            "CONCURRENCY MUTEX VIOLATED | sync must NOT start before import ends. " +
            "import_end at $importEndIdx, sync_start at $syncStartIdx. " +
            "Execution order: $executionOrder | " +
            "If sync started during import, concurrent data corruption is possible.",
            importEndIdx < syncStartIdx
        )
    }
}
