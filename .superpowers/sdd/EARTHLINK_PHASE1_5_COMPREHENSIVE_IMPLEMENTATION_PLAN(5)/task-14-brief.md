# Task Brief: P2-01 — Define the final Restore/Import business transaction boundary

## Context & Project Fit
Per `P2-G3-REQ-01`, `P2-G3-REQ-03`, `INV-11`, and Section 5.2 of `EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5).md`:
- Restore and Import operations must strictly segregate pre-commit operations (parsing, validation, decryption, conflict resolution, remote inspection/reads) from the final atomic Room write transaction.
- Outside final Room transaction:
  - Parse, structural validation, decryption, conflict detection, user decision resolution, remote reads.
  - Construct a deterministic `RestoreDecision` / `RestoreMergeDecision` object.
- Inside final Room transaction:
  - Strictly deterministic, non-blocking local business-state application inside `appDatabase.withTransaction`. Zero UI interaction, zero network calls, zero Firebase wait.

## Implementation Targets
- `app/src/main/java/com/example/core/backup/BackupManager.kt`
- `app/src/main/java/com/example/core/model/Models.kt` (Restore Decision objects)
- `app/src/main/java/com/example/core/sync/UtowerImporter.kt`
- `app/src/test/java/com/example/Phase2RestoreTransactionBoundaryTest.kt` — new test suite
- `contract/invariant_contract.yaml`, `contract/invariant_test_map.yaml`, `contract/test_environment_matrix.yaml` — contract mapping

## Specific Requirements
1. Restore Decision Contract:
   - Define a deterministic `RestoreMergeDecision` object encapsulating:
     - `artifactIdentity: String`
     - `selectedBaselineId: String`
     - `selectedLineageScope: String`
     - `conflictDecisions: Map<String, ConflictResolutionChoice>` (or equivalent)
     - `targetDatasetSummary: String`
     - `isApproved: Boolean`
   - Invalidation rule: if backup artifact or source state changes, decision is invalidated and must be recomputed.
2. Boundary Enforcement:
   - All network / remote / file parsing happens outside `db.withTransaction`.
   - The final commit is purely local database mutation in a single ACID transaction block.
3. Test Suite `Phase2RestoreTransactionBoundaryTest.kt`:
   - Structural/Behavioral test: rejects network/remote calls inside final Room transaction;
   - Proves conflict decisions are completely resolved before the Room transaction begins;
   - Cancellation/abandonment before Room transaction commits leaves live database 100% untouched;
   - Execution inside Room transaction applies the pre-computed decision deterministically without side effects.
4. Verification:
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_invariant_contract.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_test_environment_matrix.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/scan_forbidden_patterns.py`
   - `python scripts/run_verified_command.py --timeout 300 -- .\gradlew.bat testDebugUnitTest`
5. Commit changes to git and write report to `.superpowers/sdd/EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5)/task-14-report.md`.
