# Task Brief: P1-11 — Same-ID divergent-payload immutability protection

## Context & Project Fit
Per `INV-01`, `INV-11`, and Section 4.12 of `EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5).md`:
- Financial transaction IDs (`businessTransactionId` / `LocalLedgerEntry.id`) are permanently immutable once committed.
- When processing inbound sync updates, local writes, or outbox retry cycles:
  - Same ID + IDENTICAL payload -> Idempotent no-op (safe retry / convergence).
  - Same ID + DIVERGENT payload (different amount, different account ID, different transaction type, or conflicting timestamp) -> Fail closed with explicit conflict error (`DivergentPayloadConflictException` or conflict result), protecting original committed ledger entry from silent corruption or mutation.

## Implementation Targets
- `app/src/main/java/com/example/core/sync/SyncRepositoryImpl.kt` & `app/src/main/java/com/example/data/repository/Repositories.kt` (`LocalLedgerRepositoryImpl`).
- `app/src/test/java/com/example/Phase1SameIdDivergentPayloadTest.kt` — comprehensive unit test suite.
- `contract/invariant_contract.yaml`, `contract/invariant_test_map.yaml`, `contract/test_environment_matrix.yaml` — contract mapping.

## Specific Requirements
1. Immutability & Conflict Verification:
   - Ensure `LocalLedgerRepository` and sync processing logic check whether an existing entry with the given ID already exists before inserting/updating:
     - If existing entry has identical business fields (accountId, amountIqd, type, note), accept as idempotent no-op without duplicating balances or modifying the ledger.
     - If existing entry differs in amount, account, or type, fail closed and throw/return an explicit conflict error, preventing ledger tampering.
2. Test Suite `Phase1SameIdDivergentPayloadTest.kt`:
   - Same ID + identical payload -> idempotent no-op (balance and ledger count unchanged).
   - Same ID + divergent amount (e.g. 50,000 IQD vs 75,000 IQD) -> conflict rejected, original 50,000 IQD preserved.
   - Same ID + divergent account ID -> conflict rejected, original account assignment preserved.
   - Same ID + divergent transaction type (e.g. DEBT vs PAYMENT) -> conflict rejected.
   - Inbound sync remote payload with existing local divergent transaction -> flagged as conflict without silently overwriting local truth.
3. Verification:
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_invariant_contract.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_test_environment_matrix.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/scan_forbidden_patterns.py`
   - `python scripts/run_verified_command.py --timeout 300 -- .\gradlew.bat testDebugUnitTest`
4. Commit changes to git and write report to `.superpowers/sdd/EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5)/task-11-report.md`.
