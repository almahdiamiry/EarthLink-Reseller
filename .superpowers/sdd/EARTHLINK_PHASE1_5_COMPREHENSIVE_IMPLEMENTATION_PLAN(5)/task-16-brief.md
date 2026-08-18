# Task Brief: P2-03 — Make current-position reconstruction deterministic

## Context & Project Fit
Per `P2-G3-REQ-04`, `INV-01`, `INV-06`, `INV-11`, and Section 5.4 of `EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5).md`:
- Account current balances and financial positions MUST be strictly derived from:
  `accepted baseline + eligible ledger history`
- Stored balance totals on `LocalAccount` are cached values and NEVER an independent source of financial authority.
- When performing Import, Restore Replace, Restore Merge, or ledger recalculation:
  - Rebuilding balance position must be a pure, deterministic function of baseline + un-deleted ledger transactions.
  - Snapshot semantics: uTower opening snapshot establishes the baseline, and active ledger history adds/subtracts without re-applying historical snapshot debt twice.

## Implementation Targets
- `app/src/main/java/com/example/core/ledger/BalanceCalculator.kt`
- `app/src/main/java/com/example/data/repository/Repositories.kt` (`recalculateAccountHistory`, `rebuildAccountBalances`, `LocalAccountRepositoryImpl`, `LocalLedgerRepositoryImpl`)
- `app/src/main/java/com/example/core/backup/BackupManager.kt`
- `app/src/main/java/com/example/core/sync/UtowerImporter.kt`
- `app/src/test/java/com/example/Phase2CurrentPositionReconstructionTest.kt` — new test suite
- `contract/invariant_contract.yaml`, `contract/invariant_test_map.yaml`, `contract/test_environment_matrix.yaml` — contract mapping

## Specific Requirements
1. Implement / Verify Deterministic Rebuild:
   - Ensure `BalanceCalculator` and repository recalculation derive balance purely from baseline + valid ledger entries.
   - Tombstoned / deleted transactions are excluded from active balance.
   - Baseline debt + Ledger DEBT - Ledger PAYMENT = exact current balance.
2. Implement Test Suite `Phase2CurrentPositionReconstructionTest.kt`:
   - Independent oracle derivation compared against materialized account state across Import, Restore Replace, and Restore Merge;
   - Proof that stored balance corruption is completely healed by deterministic rebuild;
   - Multi-account batch rebuild accuracy;
   - Baseline preservation (uTower opening baseline + incremental ledger history);
   - Zero double-counting under multiple recalculations.
3. Contract Mapping & Verification:
   - Map `Phase2CurrentPositionReconstructionTest` under `INV-01`, `INV-06`, and `INV-11`.
   - Run all verification scripts and gradle unit tests:
     - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_invariant_contract.py`
     - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_test_environment_matrix.py`
     - `python scripts/run_verified_command.py --timeout 60 -- python scripts/scan_forbidden_patterns.py`
     - `python scripts/run_verified_command.py --timeout 300 -- .\gradlew.bat testDebugUnitTest`
4. Commit changes cleanly and write report to `.superpowers/sdd/EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5)/task-16-report.md`.
