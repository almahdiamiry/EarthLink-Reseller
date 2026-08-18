# Task Brief: P1-10 — Unknown-outcome verification/resolution protocol

## Context & Project Fit
Per `INV-11`, `G1`, and Section 1.5 of `EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5).md`, when an external financial ISP operation (Activation, Renewal, Refill) experiences a network timeout or unknown outcome:
- The operation must NOT be blindly retried.
- The operation must NOT be manually marked complete without authoritative verification.
- An authoritative subscriber-state inspection workflow resolves the outcome into Verified Success, Verified Failure, or Inconclusive.

## Implementation Targets
- `app/src/main/java/com/example/data/repository/Repositories.kt` (`LocalLedgerRepositoryImpl` / pending resolution logic).
- `app/src/main/java/com/example/ui/viewmodels/EarthlinkSearchViewModel.kt` (or dedicated verification resolution handler).
- `app/src/main/java/com/example/core/database/AppDatabase.kt` (`PendingExternalOperationDao` updates).
- `app/src/test/java/com/example/Phase1UnknownOutcomeResolutionTest.kt` — comprehensive unit test suite.
- `contract/invariant_contract.yaml`, `contract/invariant_test_map.yaml`, `contract/test_environment_matrix.yaml` — map `Phase1UnknownOutcomeResolutionTest`.

## Specific Requirements
1. Four-Case Resolution Protocol:
   - Case 1 (Verified Success): Inspection of subscriber profile/expiration/status confirms the operation was executed on ISP. Materializes local ledger entry using original pre-allocated `businessTransactionId`, updates account current position, enqueues outbox obligation, and marks pending operation COMPLETED.
   - Case 2 (Verified Failure): Inspection confirms the operation was NOT executed (e.g. expiration date unchanged, balance un-refilled). Marks pending operation FAILED with diagnostic error, 0 ledger entries, 0 balance change.
   - Case 3 (Inconclusive): Subscriber inspection fails (e.g. network still unreachable or ambiguous). Pending operation remains in PENDING/RESOLVING status with backoff; no blind retry.
   - Case 4 (Process Restart): App restart recovers pending operations in PENDING state and allows verification resolution on subsequent check.
2. Test Suite `Phase1UnknownOutcomeResolutionTest.kt`:
   - Test Case 1: Verified Success -> atomic ledger materialization with original `businessTransactionId` and outbox enqueue.
   - Test Case 2: Verified Failure -> failed status, zero balance mutation, zero outbox enqueue.
   - Test Case 3: Inconclusive -> retained pending status, diagnostic message preserved, no blind retry.
   - Test Case 4: Process restart -> durable persistence across simulated crash, resolution completes cleanly after restart.
3. Verification:
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_invariant_contract.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_test_environment_matrix.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/scan_forbidden_patterns.py`
   - `python scripts/run_verified_command.py --timeout 300 -- .\gradlew.bat testDebugUnitTest`
4. Commit changes to git and write report to `.superpowers/sdd/EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5)/task-10-report.md`.
