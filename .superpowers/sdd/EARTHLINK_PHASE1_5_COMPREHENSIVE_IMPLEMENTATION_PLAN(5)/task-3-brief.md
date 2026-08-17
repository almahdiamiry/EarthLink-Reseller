# Task Brief: P1-03 — Convert chunk processing to per-item failure isolation

## Context & Project Fit
Per `P1-G2-REQ-02` and `INV-13`, outbox obligations must be isolated at the single-item level so that a single corrupted/poison payload does not fail or block valid neighboring items in the same sync chunk.

## Implementation Targets
- `app/src/main/java/com/example/core/sync/SyncRepositoryImpl.kt` — `executeSyncPassInternal()`: ensure each item in a chunk or batch is handled with individual failure isolation, or if batch-committed to Firestore, an error is caught and isolated per item so non-failing items can succeed and be acknowledged.
- `app/src/main/java/com/example/core/sync/OutboxManager.kt` — item isolation helpers, recovery of stale `syncing` items back to retryable state.
- `app/src/main/java/com/example/core/database/AppDatabase.kt` — `SyncOutboxDao` query helpers if needed.
- `app/src/test/java/com/example/Phase1ItemIsolationTest.kt` — unit tests proving per-item failure isolation.
- `contract/invariant_contract.yaml`, `contract/invariant_test_map.yaml`, `contract/test_environment_matrix.yaml` — contract mapping.

## Specific Requirements
1. Implement per-item failure isolation during outbox processing:
   - Sequence: Given `T1 (valid)`, `T2 (poison/malformed/rejected)`, `T3 (valid)`:
     - `T1` succeeds and is removed/acknowledged.
     - `T2` fails, its failure is recorded (attemptCount incremented, error diagnostics recorded), and it remains durable with backoff.
     - `T3` succeeds and is removed/acknowledged.
   - `T2` failure must NOT abort the pass for `T3`.
2. Stale `syncing` recovery: If app crashes/restarts while an item is in `syncing` state, `resetInFlight()` or startup recovery resets `syncing` items to `pending`/retryable without losing or duplicating data.
3. Stress & fairness: Valid items make progress even when preceded by multiple retained failing items.
4. Implement `Phase1ItemIsolationTest.kt` validating all the above.
5. Verify with:
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_invariant_contract.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_test_environment_matrix.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/scan_forbidden_patterns.py`
   - `python scripts/run_verified_command.py --timeout 300 -- .\gradlew.bat testDebugUnitTest`
6. Commit changes to git and write report to `.superpowers/sdd/EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5)/task-3-report.md`.
