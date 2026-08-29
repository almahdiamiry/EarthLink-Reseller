# RESTORE SHARED REPAIR MECHANISM CHECK
## EarthLink Reseller V1 — Diagnostic Check Before Batch 2

**Document Status:** COMPLETE (DIAGNOSTIC-ONLY)  
**Execution Timestamp:** 2026-08-29T22:18:00+03:00  
**Git HEAD:** `a30198a2b7060df0b8b6d8a25cb92f3d03a9f9d6`  
**Branch:** `main`  
**Operating Standard:** [`AGENTS.md`](AGENTS.md) (Primary Operational Guide)  

---

## 1. Executive Result

```text
ONE_SHARED_REPAIR
```

All five remaining restore/transport test failures share **one common test-fixture setup defect**: under Robolectric on Windows, `createTestBackupZip` invokes `context.deleteDatabase(...)` which removes the database file from the application's `databases/` directory, causing the subsequent mandatory pre-restore safety backup creation in `BackupManager.kt` (`createLocalBackupZipInternal`) to fail with `SQLiteCantOpenDatabaseException` (code 14 `SQLITE_CANTOPEN`) when opening a temporary database clone.

The smallest single existing mechanism to repair all five failures is ensuring the SQLite database parent directory (`context.getDatabasePath(...).parentFile?.mkdirs()`) is created and preserved in the test setup and fixture helpers, matching the established repository pattern across 15+ other passing test suites.

---

## 2. Baseline

* **Git HEAD:** `a30198a2b7060df0b8b6d8a25cb92f3d03a9f9d6`
* **Branch:** `main`
* **Working Tree State:** Clean with respect to production code; contains verified Batch 1 test repairs and prior audit/reconciliation artifacts.
* **Test Corpus Status:**
  ```text
  Total Tests:  563
  Passed:       558
  Failed:         5
  Skipped:        0
  Errors:         0
  ```

---

## 3. Five Failure Paths

```text
Test 1: Phase1RestoreTransportReconstructionTest.case1
  setup() -> liveDb initialized
  createTestBackupZip() -> testDiskDb created -> closed -> context.deleteDatabase("test_backup_source")
  BackupManager.restoreBackupZip(force = true)
    -> restoreBackupZipInternal() (line 1096)
      -> createLocalBackupZipInternal() (line 1131)
        -> AppDatabase.getDatabase(context, ByteArray(0), "temp_plain_...") (line 52)
          -> diskDb.openHelper.writableDatabase (line 53)
            -> FAILURE: SQLiteCantOpenDatabaseException (code 14 SQLITE_CANTOPEN)

Test 2: Phase1RestoreTransportReconstructionTest.case2
  setup() -> liveDb initialized -> insert survivor account & outbox
  createTestBackupZip() -> testDiskDb created -> closed -> context.deleteDatabase("test_backup_source")
  BackupManager.restoreBackupZip(force = true)
    -> restoreBackupZipInternal() -> createLocalBackupZipInternal() -> FAILURE: SQLiteCantOpenDatabaseException

Test 3: Phase1RestoreTransportReconstructionTest.case3
  setup() -> liveDb initialized -> insert orphan account & outbox
  createTestBackupZip() -> testDiskDb created -> closed -> context.deleteDatabase("test_backup_source")
  BackupManager.restoreBackupZip(force = true)
    -> restoreBackupZipInternal() -> createLocalBackupZipInternal() -> FAILURE: SQLiteCantOpenDatabaseException

Test 4: Phase2RestoreTransactionBoundaryTest.testApprovedDecision...
  setup() -> liveDb initialized -> insert old live account
  createTestBackupZip() -> testDiskDb created -> closed -> context.deleteDatabase("test_backup_source")
  prepareRestoreMergeDecision(isApproved = true)
  BackupManager.restoreWithDecision(approvedDecision, force = true)
    -> restoreBackupZipInternal() -> createLocalBackupZipInternal() -> FAILURE: SQLiteCantOpenDatabaseException

Test 5: Phase2TransportReconstructionIntegrationTest.testPreRestore...
  setup() -> liveDb initialized -> insert multi-obligation pre-restore state
  createTestBackupZip() -> testDiskDb created -> closed -> context.deleteDatabase("test_backup_source")
  BackupManager.restoreBackupZip(force = true)
    -> restoreBackupZipInternal() -> createLocalBackupZipInternal() -> FAILURE: SQLiteCantOpenDatabaseException
```

---

## 4. Common Failure Mechanism

| Failure Attribute | Test 1 | Test 2 | Test 3 | Test 4 | Test 5 | Evaluation |
|:---|:---|:---|:---|:---|:---|:---|
| **Database Path** | `.../databases/temp_plain_...` | `.../databases/temp_plain_...` | `.../databases/temp_plain_...` | `.../databases/temp_plain_...` | `.../databases/temp_plain_...` | `SAME` |
| **Database Name** | Dynamic `temp_plain_$timeStamp` | Dynamic `temp_plain_$timeStamp` | Dynamic `temp_plain_$timeStamp` | Dynamic `temp_plain_$timeStamp` | Dynamic `temp_plain_$timeStamp` | `SAME` |
| **Failing Seam** | `createLocalBackupZipInternal:53` | `createLocalBackupZipInternal:53` | `createLocalBackupZipInternal:53` | `createLocalBackupZipInternal:53` | `createLocalBackupZipInternal:53` | `SAME` |
| **Exception Type** | `SQLiteCantOpenDatabaseException` | `SQLiteCantOpenDatabaseException` | `SQLiteCantOpenDatabaseException` | `SQLiteCantOpenDatabaseException` | `SQLiteCantOpenDatabaseException` | `SAME` |
| **Exception Code** | Code 14 `SQLITE_CANTOPEN` | Code 14 `SQLITE_CANTOPEN` | Code 14 `SQLITE_CANTOPEN` | Code 14 `SQLITE_CANTOPEN` | Code 14 `SQLITE_CANTOPEN` | `SAME` |
| **Pre-Restore Safety Backup** | Called | Called | Called | Called | Called | `SAME` |
| **Trigger Cause** | Directory handle invalidated by `deleteDatabase` | Directory handle invalidated by `deleteDatabase` | Directory handle invalidated by `deleteDatabase` | Directory handle invalidated by `deleteDatabase` | Directory handle invalidated by `deleteDatabase` | `SAME` |

---

## 5. Directory / Filesystem Analysis

* **Which exact directory is missing or uncreated?**  
  The `databases` subdirectory within the Robolectric application data directory (`.../com.alamiry.earthlinkreseller-dataDir/databases/`).
* **Who normally creates it?**  
  On physical Android runtime, Android's `ContextImpl` and SQLite framework ensure `/data/data/<package>/databases/` exists with proper POSIX directory permissions.
* **Which test destroys it?**  
  `context.deleteDatabase("test_backup_source")` inside `createTestBackupZip` removes the physical SQLite file on Windows. Under Robolectric's Windows shadow implementation, removing files can leave the directory missing or inaccessible for subsequent native open calls without an explicit `parentFile?.mkdirs()`.
* **Is `mkdirs()` sufficient?**  
  **`SHARED_REPAIR_CANDIDATE`**. Pre-creating the directory via `context.getDatabasePath(...).parentFile?.mkdirs()` ensures SQLite native open succeeds unconditionally.

---

## 6. Journal Mode Analysis

* **Is journal mode a separate issue?**  
  **No.** In `AppDatabase.kt` (lines 929–932), `AppDatabase.getDatabase` already configures `builder.setJournalMode(RoomDatabase.JournalMode.TRUNCATE)` when executing under Robolectric:
  ```kotlin
  if (isRobolectric) {
      builder.allowMainThreadQueries()
      builder.setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
  }
  ```
* Unlike `Workstream13G1RealRestartCertificationTest` (which constructed raw custom Room builders outside `AppDatabase.getDatabase`), the restore subsystem uses `AppDatabase.getDatabase` directly, meaning TRUNCATE mode is already active. The issue is strictly directory presence on Windows.

---

## 7. Existing Pattern Reuse

The proposed repair uses the **exact 1-line idiom** already present in 15+ passing test suites across the repository:
```kotlin
context.getDatabasePath("earthlink_reseller_db").parentFile?.mkdirs()
```
and:
```kotlin
context.getDatabasePath(srcDbName).parentFile?.mkdirs()
```
No new abstractions, custom helpers, test rules, or architectural modifications are required.

---

## 8. Shared Repair Matrix

| Candidate Repair | Test 1 | Test 2 | Test 3 | Test 4 | Test 5 | Common? | Evidence |
|:---|:---|:---|:---|:---|:---|:---|:---|
| **Ensure DB parent directory `mkdirs()` in setup / fixture** | Applies | Applies | Applies | Applies | Applies | **YES (5/5)** | Eliminates `SQLITE_CANTOPEN` at `createLocalBackupZipInternal` |
| **`TRUNCATE` journal mode** | Already active | Already active | Already active | Already active | Already active | N/A | Configured in `AppDatabase.kt:931` for Robolectric |
| **Backup staging dir `mkdirs()`** | Redundant | Redundant | Redundant | Redundant | Redundant | No | `BackupManager.kt:163` already ensures `EarthlinkBackups` exists |

---

## 9. Evidence Identity Check

| Test | Production Seam | Oracle / Behavioral Assertion | Would Shared Repair Alter Evidence? |
|:---|:---|:---|:---|
| **Test 1** | `BackupManager.restoreBackupZip` $\rightarrow$ `reconstructTransportState` | Stale archive outbox discarded; sync cursors reset | **NO** — setup-only correction; evidence 100% preserved |
| **Test 2** | `BackupManager.restoreBackupZip` $\rightarrow$ `reconstructTransportState` | Surviving entity obligation preserved with attemptCount=2 and error string | **NO** — setup-only correction; evidence 100% preserved |
| **Test 3** | `BackupManager.restoreBackupZip` $\rightarrow$ `reconstructTransportState` | Absent entity obligation classified as failed orphan with `ORPHAN:` prefix | **NO** — setup-only correction; evidence 100% preserved |
| **Test 4** | `BackupManager.restoreWithDecision` $\rightarrow$ `liveDb.withTransaction` | Approved decision commits inside Room transaction with signed audit log | **NO** — setup-only correction; evidence 100% preserved |
| **Test 5** | `BackupManager.restoreBackupZip` $\rightarrow$ `reconstructTransportState` & `OutboxManager.isEligibleForSync` | Multi-vector reconstruction, orphan tagging, backoff eligibility enforcement | **NO** — setup-only correction; evidence 100% preserved |

---

## 10. Test-by-Test Conclusion

* **Tests 1, 2, 3 (`Phase1RestoreTransportReconstructionTest`):**  
  `PHASE1_SHARED_REPAIR = TRUE`. All three share the exact same fixture helper `createTestBackupZip` and setup lifecycle.
* **Test 4 (`Phase2RestoreTransactionBoundaryTest`):**  
  `PHASE2_BOUNDARY_SHARED_REPAIR = TRUE`. Uses identical `createTestBackupZip` helper and fails at identical safety backup point. The transaction boundary and `restoreWithDecision` path are preserved.
* **Test 5 (`Phase2TransportReconstructionIntegrationTest`):**  
  `PHASE2_INTEGRATION_SHARED_REPAIR = TRUE`. Integration-level multi-obligation assertions and backoff oracles remain completely untouched.

---

## 11. Exact Proposed Repair Mechanism

The minimal repair mechanism across the 3 test files comprises:
1. In `Phase1RestoreTransportReconstructionTest.kt`:
   In `createTestBackupZip`, ensure `context.getDatabasePath(srcDbName).parentFile?.mkdirs()` is invoked before creating `testDiskDb`.
2. In `Phase2RestoreTransactionBoundaryTest.kt`:
   In `createTestBackupZip`, ensure `context.getDatabasePath(srcDbName).parentFile?.mkdirs()` is invoked before creating `testDiskDb`.
3. In `Phase2TransportReconstructionIntegrationTest.kt`:
   In `setup()` and `createTestBackupZip`, ensure database parent directories exist before restore execution.

---

## 12. What Must NOT Change

* **No Production Code Changes:** `BackupManager.kt`, `AppDatabase.kt`, `OutboxManager.kt`, and `SyncRepositoryImpl.kt` remain 100% untouched.
* **No Assertion Changes:** All `assertEquals`, `assertTrue`, `assertNull`, `assertNotNull`, and `OutboxManager.isEligibleForSync` checks remain identical.
* **No Deletion or Inlining:** All 5 tests remain permanent tests in their respective source files.
* **No Gradle / Build Changes:** No changes to `build.gradle.kts`, `forkEvery`, or JVM heap arguments.

---

## 13. Confidence / Unknowns

* **Confidence:** **HIGH**. The failure path was traced line-by-line across all 5 test executions and confirmed via XML failure logs and stack traces.
* **Unknowns:** None. The mechanism is identical to the filesystem directory initialization pattern established in Batch 1.

---

## 14. Batch 2 Readiness

```text
Implementation Readiness:  READY
Batch 2 Scope:             MINIMAL (3 test files, ~3 lines of fixture setup)
Risk Level:                LOW (Test-fixture only, zero production impact)
```
