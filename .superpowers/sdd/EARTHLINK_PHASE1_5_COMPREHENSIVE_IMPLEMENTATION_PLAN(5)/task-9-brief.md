# Task Brief: P1-09 — Concurrent duplicate-initiation protection

## Context & Project Fit
Per `INV-11` and `EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5).md` Section 2.10 & Section 4.10, stable transaction identity protects replay of an existing transaction, but the system must also protect against duplicate initiation BEFORE identity creation:
- Rapid double-tap on UI or concurrent coroutine launches for the same logical operation (Activation, Renewal/Extension, Refill) must NOT trigger multiple concurrent external API calls or duplicate ledger entries.
- Single inflight intent per action is enforced using intent deduplication / pending operation checks.

## Implementation Targets
- `app/src/main/java/com/example/ui/viewmodels/EarthlinkSearchViewModel.kt` & `app/src/main/java/com/example/data/repository/Repositories.kt` (`LocalLedgerRepositoryImpl` / `PendingExternalOperationDao`) — implement intent deduplication and inflight mutex/state check for operations.
- `app/src/test/java/com/example/Phase1DuplicateInitiationProtectionTest.kt` — comprehensive unit test suite.
- `contract/invariant_contract.yaml`, `contract/invariant_test_map.yaml`, `contract/test_environment_matrix.yaml` — map `Phase1DuplicateInitiationProtectionTest`.

## Specific Requirements
1. Inflight & Intent Protection:
   - When a user initiates a financial ISP operation for account `A` with intent `I`:
     - An inflight tracking lock / check checks if a pending or active external operation for that intent or account action is currently in progress.
     - Concurrent duplicate requests (e.g. 10 simultaneous coroutines launched on button click) are safely collapsed / rejected with a single external network invocation.
   - Once completed or failed, subsequent new legitimate operations for the same account with a new user action intent execute independently.
2. Test Suite `Phase1DuplicateInitiationProtectionTest.kt`:
   - 10 concurrent coroutines attempting to trigger renewal for account X produce exactly 1 external network call, 1 `PendingExternalOperation`, and 1 local ledger entry;
   - Sequential duplicate tap with same `operationIntentId` reuses existing pending result without re-executing external network call;
   - Subsequent distinct legitimate renewal with new intent produces a new operation;
   - Inflight failure unlocks the account for subsequent attempts.
3. Verification:
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_invariant_contract.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_test_environment_matrix.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/scan_forbidden_patterns.py`
   - `python scripts/run_verified_command.py --timeout 300 -- .\gradlew.bat testDebugUnitTest`
4. Commit changes to git and write report to `.superpowers/sdd/EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5)/task-9-report.md`.
