# Task Brief: P2-04 — Harden Restore Replace

## Context & Project Fit
Per `P2-G3-REQ-01`, `P2-G3-REQ-03`, `P2-G3-REQ-05`, `P2-G3-REQ-06`, `INV-11`, `INV-13`, `INV-14`, and Section 5.5 of `EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5).md`:
- Direct Atomic Room Restore Replace replaces the active local database with the contents of an approved backup archive in a single ACID transaction.
- Mandatory Behavior:
  1. Pre-restore safety backup creation before destructive operations;
  2. Backup archive parsing, decryption, and structural validation completely outside the final transaction;
  3. One atomic Room transaction (`appDatabase.withTransaction`) performing the full replacement:
     - Clear/replace business tables (accounts, ledger entries, pending operations, etc.);
     - Incomplete/uncommitted import batches quarantined per `contract/backup_state_classification.yaml`;
     - Operational outbox/cursor state reset/reconstructed per classification;
     - Restore audit trail record recorded;
     - Zero blind replay of stale historical transport outbox items;
  4. Complete rollback on failure: if any step inside the transaction fails, the database reverts 100% to its pre-restore state with zero partial visibility.

## Implementation Targets
- `app/src/main/java/com/example/core/backup/BackupManager.kt` (`restoreBackupZip`, `restoreWithDecision`, atomic replacement block)
- `app/src/test/java/com/example/Phase2RestoreReplaceHardeningTest.kt` — new test suite
- `contract/invariant_contract.yaml`, `contract/invariant_test_map.yaml`, `contract/test_environment_matrix.yaml` — contract mapping

## Specific Requirements
1. Harden Restore Replace in `BackupManager.kt`:
   - Enforce pre-restore safety backup creation.
   - Enforce atomic wipe-and-insert in single `db.withTransaction`.
   - Clear and reset transport outbox (no historical transport replay).
   - Record restore audit trail record.
2. Implement Test Suite `Phase2RestoreReplaceHardeningTest.kt`:
   - Interruption/failure before final Room transaction leaves active data untouched;
   - Exception/failure inside final Room transaction triggers 100% ACID rollback (all-or-nothing);
   - Successful replacement is 100% complete with correct accounts, ledger entries, and audit trail;
   - Historical outbox is cleared/reset and not replayed;
   - Pre-restore safety backup exists and is valid;
   - Capacity envelope validation (e.g. 5,000+ records processed cleanly within transaction limits).
3. Contract Mapping & Verification:
   - Map `Phase2RestoreReplaceHardeningTest` under `INV-11`, `INV-13`, and `INV-14`.
   - Run verification scripts:
     - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_invariant_contract.py`
     - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_test_environment_matrix.py`
     - `python scripts/run_verified_command.py --timeout 60 -- python scripts/scan_forbidden_patterns.py`
     - `python scripts/run_verified_command.py --timeout 300 -- .\gradlew.bat testDebugUnitTest`
4. Commit changes cleanly and write report to `.superpowers/sdd/EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5)/task-17-report.md`.
