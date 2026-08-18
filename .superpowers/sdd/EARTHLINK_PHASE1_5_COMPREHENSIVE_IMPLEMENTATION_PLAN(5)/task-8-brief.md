# Task Brief: P1-08 — Room atomicity and Lost-ACK idempotency proof

## Context & Project Fit
Per `P1-G2-REQ-06`, `INV-11`, and `INV-13`, this task proves:
1. Room multi-table transactions (Ledger insertion + Account current position + Outbox enqueue + Pending operation resolution) are strictly atomic under simulated runtime failures / database exceptions (fail-closed, all-or-nothing rollback).
2. Lost-ACK idempotency: When a cloud write succeeds on Firestore but the client's network connection drops before receiving the acknowledgment, subsequent retry passes safely update the same deterministic document ID without duplicating transactions or corrupting ledger history.

## Implementation Targets
- `app/src/main/java/com/example/data/repository/Repositories.kt` (`LocalLedgerRepositoryImpl`) & `AppDatabase.kt`
- `app/src/main/java/com/example/core/sync/SyncRepositoryImpl.kt`
- `app/src/test/java/com/example/Phase1AtomicityAndLostAckTest.kt` — new comprehensive unit test suite
- `contract/invariant_contract.yaml`, `contract/invariant_test_map.yaml`, `contract/test_environment_matrix.yaml` — contract mapping

## Specific Requirements
1. Room Multi-Table Transaction Atomicity:
   - Prove via tests that executing `recordAccountActivation`, `recordAccountRenewal`, `recordAccountRefill`, or `recordPayment` inside `appDatabase.withTransaction` (or `runInTransaction`) provides complete rollback:
     - If ledger insert succeeds but account update throws -> 0 ledger entries, 0 outbox entries, 0 balance change.
     - If account update succeeds but outbox enqueue throws -> complete rollback.
2. Lost-ACK Idempotent Cloud Verification:
   - Prove that if an outbox push to Firestore succeeds on the server but throws a timeout/transport exception locally on client before `markSucceeded()`:
     - Outbox remains durable and retryable with backoff;
     - The next sync pass re-targets the exact same document ID `document(item.entityId)`;
     - Cloud storage ends with exactly one document with matching stable transaction ID;
     - Server read-back confirmation captures the remote version without side-effects.
3. Implement `Phase1AtomicityAndLostAckTest.kt` with tests covering all atomicity failure-injection points and lost-ACK retry cycles.
4. Verification:
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_invariant_contract.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_test_environment_matrix.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/scan_forbidden_patterns.py`
   - `python scripts/run_verified_command.py --timeout 300 -- .\gradlew.bat testDebugUnitTest`
5. Commit changes to git and write report to `.superpowers/sdd/EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5)/task-8-report.md`.
