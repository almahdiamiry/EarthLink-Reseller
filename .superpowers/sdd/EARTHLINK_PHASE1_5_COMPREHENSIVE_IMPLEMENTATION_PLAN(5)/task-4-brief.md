# Task Brief: P1-04 — Implement explicit orphan handling

## Context & Project Fit
Per `P1-G2-REQ-03` and `INV-13`, when an outbox item's local entity has been deleted or superseded locally prior to cloud push, the outbox item must be explicitly classified and handled as an orphaned transport obligation rather than being silently deleted or corrupting remote business state.

## Implementation Targets
- `app/src/main/java/com/example/core/sync/SyncRepositoryImpl.kt` — detect when a pending outbox item's entity is missing/superseded locally during outbox processing; handle orphan classification with diagnostics and exponential backoff/quarantine.
- `app/src/main/java/com/example/core/sync/OutboxManager.kt` & `SyncOutboxDao` (`AppDatabase.kt`) — helper queries/methods for orphan recording and diagnostics retention.
- `app/src/test/java/com/example/Phase1OrphanHandlingTest.kt` — unit tests verifying orphan behavior.
- `contract/invariant_contract.yaml`, `contract/invariant_test_map.yaml`, `contract/test_environment_matrix.yaml` — contract mapping for `Phase1OrphanHandlingTest`.

## Specific Requirements
1. Detect orphaned outbox items:
   - When preparing an outbox payload (e.g. Account, Ledger, Profile) where the local entity no longer exists in SQLite (or has been superseded):
     - The item must NOT be silently dropped.
     - The item must be marked as `failed` with explicit diagnostic error explaining that the target entity was locally missing/deleted (e.g., `ORPHAN: Entity <id> of type <type> not found in local database`).
     - Bounded backoff is applied so orphan items do not hot-loop indefinitely.
     - Orphan items must NOT block other valid outbox items from being synced.
     - Orphan items must NOT create fraudulent or empty ledger entries in SQLite or Firestore.
2. Interruption and restart:
   - Orphaned items remain persisted in SQLite across app restarts and outbox scans.
3. Test Suite `Phase1OrphanHandlingTest.kt`:
   - Deleted local entity + pending outbox item -> marked as orphan failure, retained with diagnostics, does not throw unhandled exception.
   - Superseded local entity + older outbox item -> handled safely without reverting newer local state.
   - Orphan survives restart and remains observable in outbox diagnostics.
   - Orphan does not block unrelated valid outbox items from syncing.
   - Orphan never creates unintended local ledger mutations.
4. Verification & Matrix Validation:
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_invariant_contract.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_test_environment_matrix.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/scan_forbidden_patterns.py`
   - `python scripts/run_verified_command.py --timeout 300 -- .\gradlew.bat testDebugUnitTest`
5. Commit changes to git and write report to `.superpowers/sdd/EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5)/task-4-report.md`.
