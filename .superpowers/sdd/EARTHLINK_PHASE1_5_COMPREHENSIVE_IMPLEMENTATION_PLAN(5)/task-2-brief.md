# Task Brief: P1-02 — Remove terminal DEAD_LETTER semantics from the outbox

## Context & Project Fit
Phase 1 eliminates terminal loss in the sync transport pipeline per `INV-13` and `P1-G2-REQ-01`.
The sync outbox must treat every record as a durable, retryable transport obligation. No attempt threshold (like 10 attempts) may transition an item into a dead state or stop it from being retried.

## Implementation Targets
- `app/src/main/java/com/example/core/model/Models.kt` — `SyncOutbox` (status enum/strings, documentation)
- `app/src/main/java/com/example/core/database/AppDatabase.kt` — `SyncOutboxDao` (remove dead_letter queries, provide `getFailedCount()` or `getPending()`)
- `app/src/main/java/com/example/core/sync/OutboxManager.kt` — remove `markDeadLetter()`, ensure failures update `attemptCount`, `lastError`, `updatedAt`, and remain pending/retryable with exponential backoff
- `app/src/main/java/com/example/core/sync/SyncRepositoryImpl.kt` — eliminate `dead_letter` transitions from `executeSyncPassInternal()`, update `getFailedCount()` / retry methods
- `app/src/main/java/com/example/domain/repository/Interfaces.kt` — update `SyncRepository` interface (replace getDeadLetterCount/retryDeadLetters with retry/failed count if needed)
- `app/src/main/java/com/example/ui/viewmodels/SyncStatusViewModel.kt` & `app/src/main/java/com/example/ui/screens/SyncStatusScreen.kt` — update UI terminology from "Dead-Letter" to "Failed / Retrying Items"
- `contract/forbidden_patterns.yaml` — ensure pattern checking forbids terminal dead-letter transitions in production code
- `contract/invariant_contract.yaml` & `contract/invariant_test_map.yaml` & `contract/test_environment_matrix.yaml` — map `Phase1OutboxDurabilityTest`
- `app/src/test/java/com/example/Phase1OutboxDurabilityTest.kt` — new comprehensive unit test suite

## Requirements & Exact Invariants
1. Outbox states are strictly: `"pending"`, `"syncing"`, `"failed"` (or `"pending"` with `attemptCount > 0`). Never `"dead_letter"`.
2. Failed items remain durable in the SQLite database until successfully confirmed on the server.
3. Attempt count increments on failure, `lastError` is recorded, but the item is never deleted or moved to an un-retryable terminal status due to attempt count.
4. Add `Phase1OutboxDurabilityTest.kt` verifying:
   - attempt count increase without obligation deletion;
   - long-running failure keeps the row retryable in `getPending()`;
   - no `dead_letter` status can be produced;
   - poison item remains isolated and observable;
   - outbox rows survive multiple failed sync attempts without data loss.
5. Verify test pass using `python scripts/run_verified_command.py --timeout 300 -- .\gradlew.bat testDebugUnitTest` and matrix validations.
6. Commit changes to git and write report to `.superpowers/sdd/EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5)/task-2-report.md`.
