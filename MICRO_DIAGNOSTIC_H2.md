# MICRO-DIAGNOSTIC — H2 HYPOTHESIS TEST
## EarthLink Reseller V1 — Database Lifecycle & Native Open Contention

**Document Status:** COMPLETE (DIAGNOSTIC-ONLY)  
**Execution Timestamp:** 2026-08-29T23:37:00+03:00  
**Git HEAD:** `847412a762155538639146935dbf7a4eec549d04`  
**Branch:** `main`  
**Operating Standard:** [`AGENTS.md`](AGENTS.md) (Primary Operational Guide)  

---

## 1. Executive Result

```text
H2_FALSIFIED
```

The hypothesis **H2** (that an open `liveDb` / Room instance from test setup prevents `BackupManager.createLocalBackupZipInternal()` from opening `temp_plain_*` under Robolectric on Windows) is **falsified**.

Explicitly closing and evicting all open Room database instances (`AppDatabase.closeDatabase()`) immediately prior to invoking `BackupManager.restoreBackupZip()` produced the exact same failure with identical stack trace and exception code:
```text
android.database.sqlite.SQLiteCantOpenDatabaseException: unknown error (code 14 SQLITE_CANTOPEN)
at BackupManager.kt:1153 -> BackupManager.kt:80 -> SQLiteConnection.java:262
```

---

## 2. Baseline & Fixed Point

* **Git HEAD:** `847412a762155538639146935dbf7a4eec549d04`
* **Branch:** `main`
* **Working Tree State:** Clean (`nothing to commit, working tree clean`).
* **Target Test Target:** `com.example.Phase1RestoreTransportReconstructionTest.case1_backupContainsStaleOutboxAndMetadata_restoreDiscardsItAndDoesNotReplay`

---

## 3. Control vs. Experiment Results

### CONTROL (Baseline Lifecycle)
* **Configuration:** `liveDb` initialized during `setup()` and kept open throughout test execution.
* **Command:**
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "com.example.Phase1RestoreTransportReconstructionTest.case1_backupContainsStaleOutboxAndMetadata_restoreDiscardsItAndDoesNotReplay"
  ```
* **Result:** **FAILED (Exit Code 1)**
* **Failure Signature:**
  ```text
  Phase1RestoreTransportReconstructionTest > case1_backupContainsStaleOutboxAndMetadata_restoreDiscardsItAndDoesNotReplay FAILED
      java.lang.IllegalStateException at BackupManager.kt:1153
          Caused by: java.lang.IllegalStateException at BackupManager.kt:1153
              Caused by: java.lang.IllegalStateException at BackupManager.kt:80
                  Caused by: java.lang.IllegalStateException at BackupManager.kt:80
                      Caused by: android.database.sqlite.SQLiteCantOpenDatabaseException at SQLiteConnection.java:262
                          Caused by: android.database.sqlite.SQLiteCantOpenDatabaseException at SQLiteConnectionNatives.java:-2
  ```

---

### EXPERIMENT (Lifecycle Intervention)
* **Configuration:** Explicit `AppDatabase.closeDatabase()` invoked immediately prior to `BackupManager.restoreBackupZip()`.
* **Exact Code Change:**
  ```kotlin
  AppDatabase.closeDatabase()
  val success = BackupManager.restoreBackupZip(context, zipFile, force = true)
  ```
* **Variables Changed:** Database lifecycle ONLY (zero journal changes, zero timeout/sleeps, zero production changes).
* **Command:**
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "com.example.Phase1RestoreTransportReconstructionTest.case1_backupContainsStaleOutboxAndMetadata_restoreDiscardsItAndDoesNotReplay"
  ```
* **Result:** **FAILED (Exit Code 1)**
* **Failure Signature:** **IDENTICAL**
  ```text
  Phase1RestoreTransportReconstructionTest > case1_backupContainsStaleOutboxAndMetadata_restoreDiscardsItAndDoesNotReplay FAILED
      java.lang.IllegalStateException at BackupManager.kt:1153
          Caused by: java.lang.IllegalStateException at BackupManager.kt:1153
              Caused by: java.lang.IllegalStateException at BackupManager.kt:80
                  Caused by: java.lang.IllegalStateException at BackupManager.kt:80
                      Caused by: android.database.sqlite.SQLiteCantOpenDatabaseException at SQLiteConnection.java:262
                          Caused by: android.database.sqlite.SQLiteCantOpenDatabaseException at SQLiteConnectionNatives.java:-2
  ```

---

## 4. Controlled Comparison Table

| Metric | CONTROL | EXPERIMENT | Comparison |
|:---|:---|:---|:---|
| **`liveDb` State Before Restore** | Open in memory / instance cache | Explicitly closed & cleared | **Isolated Variable** |
| **`AppDatabase.INSTANCES`** | Contained `"earthlink_reseller_db"` | Cleared via `closeDatabase()` | **Isolated Variable** |
| **Other Variables Changed** | None | None | **Identical** |
| **Execution Result** | FAIL (Exit Code 1) | FAIL (Exit Code 1) | **Identical** |
| **Exception Class** | `SQLiteCantOpenDatabaseException` | `SQLiteCantOpenDatabaseException` | **Identical** |
| **Error Code** | Code 14 `SQLITE_CANTOPEN` | Code 14 `SQLITE_CANTOPEN` | **Identical** |
| **Failure Point** | `BackupManager.kt:80` | `BackupManager.kt:80` | **Identical** |

---

## 5. Causal Analysis & Mechanism

Why did closing `liveDb` in test setup fail to change the outcome?

1. **Internal Re-Opening:** Inside [`BackupManager.kt:51`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/core/backup/BackupManager.kt#L51), `createLocalBackupZipInternal` unconditionally acquires `liveDb` via `val liveDb = app?.database ?: AppDatabase.getDatabase(context, passphrase)` BEFORE calling `AppDatabase.getDatabase(context, ByteArray(0), tempPlainDbName)` on line 52.
2. **True Failure Seam:** The `SQLITE_CANTOPEN` failure at `diskDb.openHelper.writableDatabase` (line 53) occurs when SQLite's native open helper (`SQLiteConnectionNatives.nativeOpen`) attempts to create the new physical SQLite file for `temp_plain_$timeStamp` under Robolectric's Windows shadow environment. The test-level `liveDb` lifecycle is not the cause of this native open failure.

---

## 6. Confidence & Working Tree Integrity

* **Confidence:** **HIGH**. The experiment isolated the exact lifecycle variable in a controlled before/after run with zero extraneous modifications.
* **Working Tree State:** All temporary experiment edits were completely reverted (`git checkout`). `git status` is clean.

---

## 7. Single Recommended Next Step

> **Focus diagnostic investigation on SQLite native file creation parameters for `temp_plain_*` under Robolectric Windows (specifically database name extension conventions and directory handle management during `diskDb.openHelper.writableDatabase`) rather than test-level `liveDb` caching.**
