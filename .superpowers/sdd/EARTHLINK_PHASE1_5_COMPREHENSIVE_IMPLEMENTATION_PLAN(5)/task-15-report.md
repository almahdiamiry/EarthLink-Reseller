# Task P2-02 Implementation Report: Complete-Lineage Restore Merge & Material Divergence Protection

**Author:** Antigravity Implementer Subagent  
**Date:** 2026-08-18  
**Task:** P2-02 (Restore Merge as complete-lineage decision operation with material divergence detection, lineage pairing, and idempotency)  
**Status:** DONE  
**Invariants:** INV-01, INV-06, INV-11, INV-14  
**Requirements:** P2-G3-REQ-01, P2-G3-REQ-02  

---

## 1. Executive Summary

Task P2-02 has been fully implemented and verified with machine evidence.
We implemented Restore Merge as a deterministic, lineage-safe decision operation that:
1. **Enforces Complete-Lineage Pairing (P2-G3-REQ-02):** An accepted snapshot lineage consists of `selected baseline + associated eligible ledger history`. A baseline from Snapshot A cannot be contaminated with the ledger history from Snapshot B.
2. **Deduplicates Same-ID Identical Payloads (INV-01 / P2-G3-REQ-02):** Transactions present in both snapshots with identical payload collapse into exactly 1 logical transaction without double counting.
3. **Detects Same-ID Material Divergence & Fails Closed (P2-G3-REQ-01):** Any transaction present in both snapshots with the same ID but divergent immutable payload (differing amount, transaction type, or account) throws `DivergentPayloadConflictException` unless explicitly resolved by user decision (`USE_LIVE` / `USE_BACKUP`).
4. **Guarantees Idempotency & Mathematical Exactness (INV-04 / INV-11):** Repeating a restore merge yields identical database state, zero duplicate ledger rows, and balances matching exact sequential recalculation.
5. **Maintains Pre-Transaction Boundary (INV-11 / INV-14):** Pre-commit evaluation and inspection happen 100% outside the Room write transaction. The write transaction applies pre-computed decisions atomically under `DataOperationCoordinator.withOperation(DataOperationMode.RESTORE)`.

---

## 2. Production Code Changes

### `app/src/main/java/com/example/core/model/Models.kt`
- Added `MixedLineageConflictException` (inherits `IllegalStateException`) for lineage purity violations.
- Added `IncompatibleBaselineConflictException` (inherits `IllegalStateException`) for unresolved baseline opening conflicts.
- Added `SnapshotLineage` data class (`lineageId`, `baselineAccounts`, `ledgerHistory`, `importBatches`).
- Added `RestoreMergeEvaluation` data class for pre-commit decision analysis.
- Added `RestoreMergeResult` data class (`success`, `accountsMerged`, `ledgersMerged`, `ledgersDeduplicated`, `conflictsResolved`, `summary`).

### `app/src/main/java/com/example/core/backup/BackupManager.kt`
- Added `restoreMergeWithDecision(context, backupFile, decision, force)` executing inside `DataOperationCoordinator.withOperation(DataOperationMode.RESTORE)` with persistent pre-restore safety snapshots and post-restore transport reconstruction.
- Added `executeRestoreMergeInternal(liveDb, backupDb, decision): RestoreMergeResult`:
  - Enforces complete lineage baseline compatibility (`isOpeningBaselineIdentical` / `isBaselineConflict`).
  - Identifies same-ID identical transactions and idempotently deduplicates them.
  - Detects same-ID materially divergent payloads and strictly fails closed unless explicitly mapped in `decision.conflictDecisions`.
  - Recalculates account balances deterministically using `BalanceCalculator.applyTransaction` from the accepted baseline.
  - Refreshes ledger entries with recalculated `debtAfterIqd`.
  - Merges import batches and records signed `DATABASE_RESTORE_MERGE` audit log.
- Added `mergeSnapshotLineages(liveLineage, backupLineage, decision): SnapshotLineage` for direct lineage merging.
- Added `validateLineagePairing(baseline, ledgerEntries, expectedLineageId)` for lineage boundary invariant enforcement.

---

## 3. Test Suite Implementation

### `app/src/test/java/com/example/Phase2RestoreMergeLineageTest.kt`
Exhaustive Robolectric test suite covering all 7 core verification requirements:
1. `testSameTransactionInBothSnapshotsDeduplicatesToSingleRecord`: Verifies 1 logical record preserved and balance not inflated.
2. `testIndependentTransactionsInBothSnapshotsBothRetained`: Verifies disjoint transaction history from both snapshots is combined.
3. `testSameIdDivergentPayloadWithoutResolutionFailsClosed`: Required adversarial fixture (T100 50,000 vs 90,000) throws `DivergentPayloadConflictException`.
4. `testSameIdDivergentPayloadWithExplicitDecisionResolvesDeterministically`: Resolves conflict deterministically per operator choice (`USE_BACKUP` -> 90,000).
5. `testIncompatibleBaselinesCannotBeSilentlyMixedAcrossLineages`: Incompatible opening baselines fail closed with `IncompatibleBaselineConflictException`.
6. `testSelectedLiveLineageCarriesCompleteHistoryWithoutCrossContamination`: Selecting Live lineage applies Live baseline + Live history only (zero cross-contamination from Backup history).
7. `testSelectedBackupLineageCarriesCompleteHistoryWithoutCrossContamination`: Selecting Backup lineage applies Backup baseline + Backup history only.
8. `testRepeatedMergeIsIdempotent`: 2x merge produces identical ledger count and exact balance values.
9. `testDerivedBalancesMatchExactFinancialSums`: Verifies multi-transaction debts/payments match arithmetic sums with zero double-counting.
10. `testPreCommitDecisionPreparationLeavesLiveDbUntouched`: Validates pre-commit evaluation creates zero side effects on live DB.
11. `testLineagePurityValidationRejectsMixedLineages`: Validates `validateLineagePairing` throws `MixedLineageConflictException` on mismatched batches/accounts.
12. `testFullBackupZipRestoreMergeIntegration`: End-to-end backup ZIP archive restore merge with signed audit trail.

---

## 4. Invariant Contract Mapping

Updated the following contract registries:
- `contract/invariant_contract.yaml`: Mapped `Phase2RestoreMergeLineageTest` under `INV-01`, `INV-06`, `INV-11`, and `INV-14`.
- `contract/invariant_test_map.yaml`: Mapped `Phase2RestoreMergeLineageTest` under `INV-01`, `INV-06`, `INV-11`, and `INV-14`.
- `contract/test_environment_matrix.yaml`: Added entry for `Phase2RestoreMergeLineageTest` under `ROBOLECTRIC` tier.

---

## 5. Machine Verification Results

| Verification Step | Command | Exit Code | Result |
|---|---|---|---|
| Invariant Contract Validator | `python scripts/verify_invariant_contract.py` | 0 | PASS (All 16 invariants verified) |
| Test Environment Matrix Validator | `python scripts/verify_test_environment_matrix.py` | 0 | PASS (All 35 test suites verified) |
| Forbidden Pattern Scanner | `python scripts/scan_forbidden_patterns.py` | 0 | PASS (0 violations across 15 patterns) |
| Full Gradle Unit Test Suite | `.\gradlew.bat testDebugUnitTest` | 0 | PASS (155 tests completed, 0 failed) |

---

## 6. Commit Summary

- **Task ID:** P2-02
- **Files Modified:**
  - `app/src/main/java/com/example/core/model/Models.kt`
  - `app/src/main/java/com/example/core/backup/BackupManager.kt`
  - `app/src/test/java/com/example/Phase2RestoreMergeLineageTest.kt`
  - `contract/invariant_contract.yaml`
  - `contract/invariant_test_map.yaml`
  - `contract/test_environment_matrix.yaml`
  - `.superpowers/sdd/EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5)/task-15-report.md`
