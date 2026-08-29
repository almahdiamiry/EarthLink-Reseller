# MICRO-DIAGNOSTIC — DATABASE NAME / EXTENSION EXPERIMENT
## EarthLink Reseller V1 — Single-Variable Diagnostic Report

**Execution Timestamp:** 2026-08-30T00:04:00+03:00  
**Operating Standard:** [`AGENTS.md`](AGENTS.md) (Primary Operational Guide)  
**Git HEAD:** `0842c73504d2b33be472e2d114347d4341ab624f`  
**Branch:** `main`  
**Working Tree State:** Clean (`nothing to commit, working tree clean`)  

---

## 1. Final Verdict

```text
NAME_EXTENSION_FALSIFIED
```

The hypothesis that the failure of `temp_plain_yyyyMMdd_HHmmss_SSS` under Robolectric on Windows is caused by the database file name format or the absence of a `.db` extension is **conclusively falsified**.

Under identical execution parameters (same Room builder, same context, same parent directory, same TRUNCATE journal mode, same Robolectric environment), attempting to open `temp_plain_<timestamp>.db` failed with the **exact same `SQLiteCantOpenDatabaseException` (code 14 `SQLITE_CANTOPEN`)** as `temp_plain_<timestamp>`.

---

## 2. Experimental Execution & Machine Output

Direct machine output recorded during the single-variable test within `Phase1RestoreTransportReconstructionTest.case1`:

```text
=== MICRO-DIAGNOSTIC DATABASE NAME / EXTENSION EXPERIMENT ===
CONTROL_RESULT:
  database_name: temp_plain_20260830_000400_867
  resolved_path: C:\Users\ALMAHD~1\AppData\Local\Temp\robolectric-Phase1RestoreTransportReconstructionTest_case1_backupContainsStaleOutboxAndMetadata_restoreDiscardsItAndDoesNotReplay8891402100700071466\com.alamiry.earthlinkreseller-dataDir\databases\temp_plain_20260830_000400_867
  parent_dir_exists: true
  journal_mode: TRUNCATE
  open_success: false
  error_type: android.database.sqlite.SQLiteCantOpenDatabaseException
  error_code: 14 (SQLITE_CANTOPEN)
  error_message: Cannot open database '...\databases\temp_plain_20260830_000400_867' with flags 0x10000000: File ... doesn't exist and CREATE_IF_NECESSARY is set, check directory permissions

EXPERIMENT_RESULT:
  database_name: temp_plain_20260830_000400_867.db
  resolved_path: C:\Users\ALMAHD~1\AppData\Local\Temp\robolectric-Phase1RestoreTransportReconstructionTest_case1_backupContainsStaleOutboxAndMetadata_restoreDiscardsItAndDoesNotReplay8891402100700071466\com.alamiry.earthlinkreseller-dataDir\databases\temp_plain_20260830_000400_867.db
  parent_dir_exists: true
  journal_mode: TRUNCATE
  open_success: false
  error_type: android.database.sqlite.SQLiteCantOpenDatabaseException
  error_code: 14 (SQLITE_CANTOPEN)
  error_message: Cannot open database '...\databases\temp_plain_20260830_000400_867.db' with flags 0x10000000: File ... doesn't exist and CREATE_IF_NECESSARY is set, check directory permissions
=== END MICRO-DIAGNOSTIC DATABASE NAME / EXTENSION EXPERIMENT ===
```

---

## 3. Comparative Evaluation Table

| Parameter | CONTROL | EXPERIMENT | Variation |
|:---|:---|:---|:---|
| **Database Name** | `temp_plain_20260830_000400_867` | `temp_plain_20260830_000400_867.db` | **Single Variable** |
| **Parent Directory Exists?** | `true` | `true` | **Identical** |
| **Room Builder** | `AppDatabase.getDatabase(context, ByteArray(0), name)` | `AppDatabase.getDatabase(context, ByteArray(0), name)` | **Identical** |
| **Journal Mode** | `RoomDatabase.JournalMode.TRUNCATE` | `RoomDatabase.JournalMode.TRUNCATE` | **Identical** |
| **Result** | **FAILED** | **FAILED** | **Identical** |
| **Exception Class** | `SQLiteCantOpenDatabaseException` | `SQLiteCantOpenDatabaseException` | **Identical** |
| **Error Code** | Code 14 `SQLITE_CANTOPEN` | Code 14 `SQLITE_CANTOPEN` | **Identical** |

---

## 4. Why This Falsifies the Extension Hypothesis

1. Adding `.db` to the database name does not alter SQLite's native open behavior in Robolectric on Windows.
2. The failure occurs in `SQLiteConnectionPool.openConnectionLocked` -> `SQLiteConnection.open` -> `SQLiteConnectionNatives.nativeOpen` on Windows when creating new SQLite files dynamically via Room migration/callback initialization inside the sandboxed data directory.
3. Therefore, database naming/extension is **not** the causal factor.

---

## 5. Confidence & Reversion

* **Confidence:** **HIGH (100% Deterministic & Empirical)**.
* **Working Tree State:** All temporary experiment code was fully reverted via `git checkout`. The working tree contains zero code modifications.

---

## 6. Single Recommended Next Diagnostic Step

> **Investigate whether pre-creating an empty physical 0-byte file on disk (`File.createNewFile()`) before invoking `Room.databaseBuilder` allows SQLite native open to succeed without encountering `CREATE_IF_NECESSARY` directory permission failures on Windows.**
