# Task Brief: P2-02 — Implement Restore Merge as a complete-lineage decision operation

## Context & Project Fit
Per `P2-G3-REQ-01`, `P2-G3-REQ-02`, `INV-01`, `INV-06`, `INV-11`, and Section 5.3 of `EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5).md`:
- Restore Merge must execute as a complete-lineage decision operation:
  - Lineage Rule: An accepted snapshot lineage consists of `selected baseline + associated eligible ledger history`. A baseline from Snapshot A must never be mixed with the ledger history from Snapshot B.
  - Transaction Identity Rule:
    - Same Transaction ID with identical payload -> 1 logical transaction (idempotent deduplication).
    - Same Transaction ID with materially divergent financial/immutable payload -> strict conflict detection & explicit deterministic handling (fail-closed rejection or user choice, never silent overwrite or duplicate identity).
    - Different Transaction IDs -> preserve both.
  - Account Baseline Rule: Incompatible opening/current baselines require explicit resolution before the final Room write.
  - Idempotency & Zero Double-Counting: Repeating a merge produces an identical state with zero duplicate ledger rows or distorted balances.

## Implementation Targets
- `app/src/main/java/com/example/core/backup/BackupManager.kt`
- `app/src/main/java/com/example/core/model/Models.kt`
- `app/src/main/java/com/example/data/repository/Repositories.kt` (`LocalAccountRepositoryImpl`, `LocalLedgerRepositoryImpl`)
- `app/src/test/java/com/example/Phase2RestoreMergeLineageTest.kt` — new test suite
- `contract/invariant_contract.yaml`, `contract/invariant_test_map.yaml`, `contract/test_environment_matrix.yaml` — contract mapping

## Specific Requirements
1. Implement Restore Merge Logic:
   - Lineage preservation & validation: ensure baseline and ledger transactions are paired by snapshot lineage ID.
   - Material divergence detection: check if two transactions sharing the same `transactionId` differ in `amount`, `type`, `accountId`, or `timestamp`. Throw/flag as conflict.
   - Deterministic merge algorithm in `BackupManager.kt` / `Repositories.kt`.
2. Implement Test Suite `Phase2RestoreMergeLineageTest.kt`:
   - Same transaction in both snapshots -> exactly 1 ledger record;
   - Independent transactions in both snapshots -> both retained;
   - Same-ID divergent payload -> strict conflict detected and handled (no silent overwrite, no duplicate IDs);
   - Incompatible baselines cannot be silently mixed across lineages;
   - Selected lineage carries its complete eligible history;
   - Repeated merge is idempotent;
   - Derived balances match exact financial sums (zero double-counting).
3. Contract Mapping & Verification:
   - Map `Phase2RestoreMergeLineageTest` under `INV-01`, `INV-06`, `INV-11`, and `INV-14` in contract files.
   - Run verification scripts:
     - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_invariant_contract.py`
     - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_test_environment_matrix.py`
     - `python scripts/run_verified_command.py --timeout 60 -- python scripts/scan_forbidden_patterns.py`
     - `python scripts/run_verified_command.py --timeout 300 -- .\gradlew.bat testDebugUnitTest`
4. Commit changes cleanly and write report to `.superpowers/sdd/EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5)/task-15-report.md`.
