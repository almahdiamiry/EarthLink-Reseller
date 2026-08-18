# Task Brief: P1-07 — Implement G1 pending-operation durability and call-path integration

## Context & Project Fit
Per `INV-11`, `G1`, and `EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5).md`, before calling external ISP APIs for financial operations (Activation, Renewal/Extension, Refill), the app must durably record a `PendingExternalOperation` in Room SQLite with a pre-allocated stable `businessTransactionId` and `operationIntentId`. Upon confirmed external success, local ledger materialization, balance update, outbox enqueue, and pending operation resolution occur atomically in a single Room transaction.

## Implementation Targets
- `app/src/main/java/com/example/core/model/Models.kt` — ensure `PendingExternalOperation` entity is defined with all required fields (`operationIntentId`, `businessTransactionId`, `accountId`, `operationType`, `amountIqd`, `payloadJson`, `status`, `createdAt`, `updatedAt`, `lastError`).
- `app/src/main/java/com/example/core/database/AppDatabase.kt` — `PendingExternalOperationDao` entity and DAO declaration in `AppDatabase`.
- `app/src/main/java/com/example/data/repository/Repositories.kt` (`LocalLedgerRepository` / new pending operation methods).
- `app/src/main/java/com/example/ui/viewmodels/EarthlinkSearchViewModel.kt` / `UserDetailScreenV2.kt` / `CreateTestUserScreen.kt` / `CreateUsingDepositScreen.kt` — integrate pre-call pending persistence and atomic post-call materialization.
- `app/src/test/java/com/example/Phase1G1PendingOperationDurabilityTest.kt` — comprehensive unit test suite.
- `contract/invariant_contract.yaml`, `contract/invariant_test_map.yaml`, `contract/test_environment_matrix.yaml` — map `Phase1G1PendingOperationDurabilityTest`.

## Specific Requirements
1. `PendingExternalOperation` Model & Table:
   - `@Entity(tableName = "pending_external_operations", primaryKeys = ["businessTransactionId"])` (or `operationIntentId` with unique index).
   - Fields: `operationIntentId: String`, `businessTransactionId: String`, `accountId: String`, `operationType: String` ("ACTIVATION", "RENEWAL", "REFILL"), `amountIqd: Long`, `payloadJson: String`, `status: String` ("PENDING", "RESOLVING", "COMPLETED", "FAILED"), `createdAt: Long`, `updatedAt: Long`, `lastError: String?`.
2. Durable Call-Path Execution Lifecycle:
   - 1. User initiates financial operation -> generate `operationIntentId = UUID.randomUUID().toString()` and `businessTransactionId = "tx_" + UUID.randomUUID().toString()`.
   - 2. Persist `PendingExternalOperation` in Room (`dao.insert()`).
   - 3. Execute external API call.
   - 4. On external success -> Atomically in Room transaction:
        a. Record `LocalLedgerEntry` using `businessTransactionId`;
        b. Update `LocalAccount` balance / state;
        c. Enqueue `SyncOutbox` entry with entityId `businessTransactionId`;
        d. Mark `PendingExternalOperation` COMPLETED (or delete).
   - 5. On unknown/interrupted outcome -> `PendingExternalOperation` remains durable in SQLite.
3. Test Suite `Phase1G1PendingOperationDurabilityTest.kt`:
   - Verify pending operation written before external call;
   - Verify crash between external call and local record leaves pending record intact;
   - Verify successful completion commits ledger, account, outbox, and pending status atomically in Room;
   - Verify re-execution with same `operationIntentId` does not create duplicate ledger entries;
   - Verify operations survive restart.
4. Verification:
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_invariant_contract.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_test_environment_matrix.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/scan_forbidden_patterns.py`
   - `python scripts/run_verified_command.py --timeout 300 -- .\gradlew.bat testDebugUnitTest`
5. Commit changes to git and write report to `.superpowers/sdd/EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5)/task-7-report.md`.
