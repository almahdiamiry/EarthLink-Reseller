# Task Report: P1-02 — Remove terminal DEAD_LETTER semantics from the outbox

## Executive Summary
- **Task ID**: `P1-02`
- **Governing Invariants**: `INV-13` (Outbox Durability & High-Impact Mutation Protection), `P1-G2-REQ-01`
- **Objective**: Completely eliminate terminal `DEAD_LETTER` semantics from the sync transport pipeline. Ensure that every outbox mutation represents a permanently durable and retryable transport obligation with bounded backoff and diagnostic metadata until confirmed by the server or explicitly cancelled by the user.
- **Status**: **DONE / PASSED**

---

## 1. Summary of Changes

### 1.1 Model & Invariant Documentation (`Models.kt`)
- Updated [`Models.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/core/model/Models.kt) documentation on `SyncOutbox` to establish explicit invariant rules:
  - Allowed status values: strictly `"pending"`, `"syncing"`, `"failed"`.
  - Prohibited status: `"dead_letter"`.
  - Documented that outbox obligations are non-terminal and persist until server confirmation.

### 1.2 Room DAO Modernization (`AppDatabase.kt`)
- Updated [`AppDatabase.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/core/database/AppDatabase.kt):
  - Removed `getDeadLetterCount()`, `getDeadLetters()`, and `resetDeadLetters()`.
  - Added `getFailedCount()`, `getFailedItems()`, and `resetFailedItems()`.
  - Enhanced `insert(item: SyncOutbox): Long` and `insertAll(items: List<SyncOutbox>): List<Long>` to return auto-generated row IDs.

### 1.3 Outbox Protocol Hardening (`OutboxManager.kt`)
- Updated [`OutboxManager.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/core/sync/OutboxManager.kt):
  - Completely removed `markDeadLetter()`.
  - Updated `enqueue` and `enqueueOrReplace` to return `SyncOutbox` instances populated with actual inserted row IDs.
  - Updated `markRetryableFailure()` to sanitize and bound error diagnostics to 1000 characters maximum.
  - Updated `getRetryable()` to return all pending and failed items without arbitrary attempt thresholds (e.g. `< 10`).

### 1.4 Sync Repository Modernization (`SyncRepositoryImpl.kt` & `Interfaces.kt`)
- Updated [`Interfaces.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/domain/repository/Interfaces.kt) and [`SyncRepositoryImpl.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/core/sync/SyncRepositoryImpl.kt):
  - Replaced `getDeadLetterCount()` and `retryDeadLetters()` with `getFailedCount()` and `retryFailedItems()`.
  - Simplified chunk push failure handling: eliminated `deadLetterItems` list and 10-attempt threshold, routing all failed chunk items to `OutboxManager.markRetryableFailure()`.

### 1.5 UI Layer Modernization (`SyncStatusViewModel.kt` & `SyncStatusScreen.kt`)
- Updated [`SyncStatusViewModel.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/viewmodels/SyncStatusViewModel.kt) and [`SyncStatusScreen.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/screens/SyncStatusScreen.kt):
  - Replaced dead-letter terminology with "Retrying Failed Items".
  - Provided explicit "Reset Backoff & Retry Failed Items" control.

### 1.6 Forbidden Pattern Registry (`forbidden_patterns.yaml`)
- Added pattern `INV-13-no-terminal-dead-letter` to [`contract/forbidden_patterns.yaml`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/contract/forbidden_patterns.yaml) to structurally prevent `dead_letter` status mutations or `markDeadLetter` calls from entering `app/src/main/java/com/example/core/`.

### 1.7 Contract & Matrix Mappings
- Updated [`contract/invariant_contract.yaml`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/contract/invariant_contract.yaml) with `Phase1OutboxDurabilityTest.kt` under `INV-13`.
- Updated [`contract/invariant_test_map.yaml`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/contract/invariant_test_map.yaml) with `Phase1OutboxDurabilityTest.kt` under `INV-13`.
- Updated [`contract/test_environment_matrix.yaml`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/contract/test_environment_matrix.yaml) registering `Phase1OutboxDurabilityTest` in `test_suites` and `INV-13`.

---

## 2. Test Suite & Verification Proof

### 2.1 `Phase1OutboxDurabilityTest.kt` Suite
Implemented 8 exhaustive test cases in [`Phase1OutboxDurabilityTest.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/test/java/com/example/Phase1OutboxDurabilityTest.kt):
1. `case1_attemptCountIncreasesWithoutObligationDeletion`: Proves 15 consecutive failure cycles increment `attemptCount` to 15 while retaining the record in `failed` status without dropping.
2. `case2_longRunningFailure_remainsRetryableInGetPending`: Proves 50 failure cycles keep the record retryable in `getPending()` and `getRetryable()`.
3. `case3_noDeadLetterStatusCanBeProduced`: Proves 20 items subjected to 12 failure rounds never enter `dead_letter`.
4. `case4_poisonItemRemainsIsolatedAndObservable`: Proves a failing poison item does not block valid preceding or subsequent items from syncing.
5. `case5_outboxRowsSurviveMultipleFailedSyncAttemptsWithoutDataLoss`: Proves payload JSON, entity metadata, and import batch IDs remain uncorrupted over 25 failure cycles.
6. `case6_stressRetainedFailurePopulation_withValidItemBehindIt_fairnessAndBoundedDiagnostics`: Proves a population of 100 retained failures maintains FIFO fairness and bounded diagnostic string length (<= 1000 chars) while allowing new tail items to sync.
7. `case7_resetFailedItems_resetsAllFailedItemsToPending`: Proves manual retry cleanly resets status to `pending`, `attemptCount` to 0, and `lastError` to null.
8. `case8_inFlightCrashRecovery_resetsToPending`: Proves items interrupted in `syncing` status cleanly reset to `pending` on crash recovery.

### 2.2 Machine Verification Results
1. **Invariant Contract Validation**:
   `python scripts/verify_invariant_contract.py` -> **PASS (Exit code: 0)**
2. **Test Environment Matrix Validation**:
   `python scripts/verify_test_environment_matrix.py` -> **PASS (Exit code: 0)**
3. **Forbidden Pattern Scanner**:
   `python scripts/scan_forbidden_patterns.py` -> **PASS (15/15 patterns verified, 0 violations, Exit code: 0)**
4. **Gradle Unit Tests**:
   `.\gradlew.bat testDebugUnitTest` -> **BUILD SUCCESSFUL (46 tests completed, 0 failed, Exit code: 0)**

---

## 3. Conclusion & Next Steps
Task `P1-02` is complete with full machine evidence and zero test weakenings.
Ready for Task `P1-03`: Convert chunk processing to per-item failure isolation.
