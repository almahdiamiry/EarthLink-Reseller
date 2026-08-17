# Task P1-07 Report: G1 Pending-Operation Durability and Call-Path Integration

## 1. Overview & Objective
Task P1-07 implemented the G1 durable pending-operation model and call-path integration for financial ISP operations (Activation, Renewal/Extension, Refill) in full compliance with `INV-11` and `G1` product contract requirements.

Before dispatching external ISP requests, the application creates a pre-allocated stable `businessTransactionId` and `operationIntentId` and records a `PendingExternalOperation` in Room SQLite. Upon confirmed external success, local ledger materialization, balance recalculation, outbox enqueue, and pending operation completion occur atomically in a single Room transaction.

---

## 2. Implementation Summary

### A. Data Models & Entity Definition
- **File:** `app/src/main/java/com/example/core/model/Models.kt`
- Added `@Entity(tableName = "pending_external_operations")` `PendingExternalOperation` with:
  - `businessTransactionId: String` (Primary Key, unique)
  - `operationIntentId: String` (Unique Index)
  - `accountId: String` (Indexed)
  - `operationType: String` ("ACTIVATION", "RENEWAL", "REFILL")
  - `amountIqd: Long`
  - `payloadJson: String`
  - `status: String` ("PENDING", "RESOLVING", "COMPLETED", "FAILED")
  - `createdAt: Long`, `updatedAt: Long`, `lastError: String?`

### B. DAO & Database Migration
- **File:** `app/src/main/java/com/example/core/database/AppDatabase.kt`
- Defined `PendingExternalOperationDao` with reactive flows, one-shot lookup by `businessTransactionId` / `operationIntentId`, status updates, and lifecycle operations.
- Registered `PendingExternalOperation::class` in `@Database(entities = [...])`.
- Added `MIGRATION_11_12` creating `pending_external_operations` and its unique/search indices.
- Bumped database version to `AppDatabase.VERSION = 12`.

### C. Local Ledger Repository Integration
- **Files:** `app/src/main/java/com/example/domain/repository/Interfaces.kt`, `app/src/main/java/com/example/data/repository/Repositories.kt`
- Added repository methods:
  - `recordPendingOperation(operation)`: Idempotently stores pending operations before external call dispatch.
  - `getPendingOperationByIntentId(operationIntentId)`
  - `getPendingOperationByTransactionId(businessTransactionId)`
  - `getAllPendingOperations()`
  - `markPendingOperationFailed(businessTransactionId, error)`
  - `completePendingOperation(businessTransactionId, accountId, ledgerEntryId)`
  - `deletePendingOperation(businessTransactionId)`
- Updated `recordAccountRenewal(...)`, `recordAccountPayment(...)`, and `recordAccountDebt(...)` to atomically mark pending operations `COMPLETED` when an `idempotencyKey` / `businessTransactionId` is provided.

### D. ViewModel & Screen Integration
- **Files:** `app/src/main/java/com/example/ui/viewmodels/EarthlinkSearchViewModel.kt`, `app/src/main/java/com/example/ui/screens/UserDetailScreenV2.kt`
- Integrated pre-call durable pending persistence and post-call atomic materialization into `refillUser`, `extendUser`, `createUserUsingDeposit`, and `createTestUser`.
- `UserDetailScreenV2.kt` now passes the stable `txId` to `recordAccountRenewal(..., idempotencyKey = txId)`.

---

## 3. Test Suite: `Phase1G1PendingOperationDurabilityTest`
- **File:** `app/src/test/java/com/example/Phase1G1PendingOperationDurabilityTest.kt`
- 7 comprehensive Robolectric test cases verified:
  1. `test1_pendingOperationWrittenBeforeExternalCall`: Verifies pending operation is durably written to Room before external network dispatch, with zero premature ledger/outbox entries.
  2. `test2_interruptionAfterExternalCallLeavesPendingRecordIntact`: Verifies crash/interruption between external call and local record leaves pending record intact across disk close and reopen.
  3. `test3_successfulCompletionCommitsLedgerAccountOutboxPendingAtomically`: Verifies atomic Room transaction commits ledger entry, updates account balance, enqueues outbox, and sets pending status to `COMPLETED`.
  4. `test4_duplicateSubmissionWithSameIntentIdSuppressedIdempotently`: Verifies repeated submissions with identical `operationIntentId` return existing pending record without duplicate rows, and replay of `recordAccountRenewal` is strictly idempotent.
  5. `test5_operationsSurviveRestartAndDatabaseReopen`: Verifies persistence of ACTIVATION, RENEWAL, and REFILL across full database close/reopen cycles on disk.
  6. `test6_failedExternalCallMarksPendingOperationFailed`: Verifies external failure transitions pending record to `FAILED` with diagnostics and without ledger mutation.
  7. `test7_renewalExtensionOperationNormalization`: Verifies renewal and extension flows normalize into canonical `RENEWAL` category under G1 boundaries.

---

## 4. Contract & Matrix Mapping
- Updated `contract/invariant_contract.yaml`: Mapped `Phase1G1PendingOperationDurabilityTest.kt` and production sources under `INV-11`.
- Updated `contract/invariant_test_map.yaml`: Mapped `Phase1G1PendingOperationDurabilityTest.kt` under `INV-11`.
- Updated `contract/test_environment_matrix.yaml`: Added `Phase1G1PendingOperationDurabilityTest` as active Robolectric suite under `INV-01`, `INV-05`, `INV-11`, `INV-13`.

---

## 5. Machine Verification Results

```text
1. python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_invariant_contract.py
   [PASS] Verified all 16 canonical invariants (INV-01 through INV-16).
   [PASS] All referenced production source files exist.
   [PASS] All referenced test suites exist.
   Exit Code: 0

2. python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_test_environment_matrix.py
   [PASS] All 16 canonical invariants verified in matrix.
   [PASS] All 28 active test suites & scripts verified on disk.
   [PASS] Zero unmapped test files detected.
   Exit Code: 0

3. python scripts/run_verified_command.py --timeout 60 -- python scripts/scan_forbidden_patterns.py
   [PASS] 15 registered patterns scanned. 0 violations found.
   Exit Code: 0

4. python scripts/run_verified_command.py --timeout 300 -- .\gradlew.bat testDebugUnitTest
   BUILD SUCCESSFUL in 44s (83 tests completed, 0 failed).
   Exit Code: 0
```

---

## 6. Status
**Status:** DONE
