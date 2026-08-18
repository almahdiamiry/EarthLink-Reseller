# Task Brief: P1-06 — Define and implement the Restore/Backup transport reconstruction decision table

## Context & Project Fit
Per `P1-G2-REQ-05` and `INV-13`/`INV-14`, restoring a backup or performing an import must reconstruct transport state from the resulting business state rather than blindly replaying historical/stale transport metadata found inside the backup archive.

## Implementation Targets
- `app/src/main/java/com/example/core/backup/BackupManager.kt` — implement/refine transport state reconstruction after backup restoration:
  - Historical `sync_outbox` entries in the backup archive MUST NOT be executed as active transport obligations.
  - Reset / clean obsolete sync operational state (`sync_outbox`, operational metadata).
  - Enqueue fresh outbox obligations for any local business entities (`LocalAccount`, `LocalLedgerEntry`, `ImportBatch`) that require synchronization to the cloud.
  - Reset remote sync cursors / sync metadata so that sync pull resumes from a clean baseline rather than using stale cursors from the backup device.
- `contract/backup_state_classification.yaml` — ensure backup classification rules align with the transport decision table.
- `app/src/test/java/com/example/Phase1RestoreTransportReconstructionTest.kt` — unit test suite for transport reconstruction.
- `contract/invariant_contract.yaml`, `contract/invariant_test_map.yaml`, `contract/test_environment_matrix.yaml` — matrix mapping.

## Specific Requirements
1. Transport Reconstruction Decision Table:
   - Historical Outbox from ZIP -> Discarded / Cleared (never replayed blindly).
   - Historical Remote Version Cursors from ZIP -> Reset to clean baseline.
   - Restored Business Entities -> Scanned, and fresh outbox items created with deterministic entity IDs.
   - Pre-existing local un-synced obligations prior to restore -> Handled consistently according to restore mode (in full restore replace, replaced cleanly).
2. Atomicity & Crash Safety:
   - Restore and transport reconstruction execute inside the controlled restore lifecycle. If interrupted before completion, partial operational sync state does not leak into the active scheduler.
3. Test Suite `Phase1RestoreTransportReconstructionTest.kt`:
   - Verify historical outbox in backup zip is not replayed;
   - Verify fresh outbox items are created for restored business entities;
   - Verify sync cursors and sync metadata are reset;
   - Verify outbox integrity after restore.
4. Verification:
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_invariant_contract.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_test_environment_matrix.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/scan_forbidden_patterns.py`
   - `python scripts/run_verified_command.py --timeout 300 -- .\gradlew.bat testDebugUnitTest`
5. Commit changes to git and write report to `.superpowers/sdd/EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5)/task-6-report.md`.
