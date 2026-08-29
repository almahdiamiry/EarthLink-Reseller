# MICRO-DIAGNOSTIC — NEXT CAUSAL VARIABLE EXPERIMENT
## EarthLink Reseller V1 — Physical File Pre-Creation & SQLite Initialization

**Execution Timestamp:** 2026-08-30T00:09:00+03:00  
**Operating Standard:** [`AGENTS.md`](AGENTS.md) (Primary Operational Guide)  
**Git HEAD:** `0842c73504d2b33be472e2d114347d4341ab624f`  
**Branch:** `main`  
**Working Tree State:** Clean (`nothing to commit, working tree clean`)  

---

## 1. Executive Summary & Verdict

```text
PHYSICAL_FILE_PRE_CREATION_FALSIFIED
```

The hypothesis that pre-creating a physical 0-byte file (`File.createNewFile()`) on disk prior to `Room.databaseBuilder` would resolve the `SQLITE_CANTOPEN` directory permission failure is **falsified**.

While creating a 0-byte file alters the Android error message from `File doesn't exist and CREATE_IF_NECESSARY is set` to `Unable to deduct failure reason`, SQLite's native C engine (`sqlite3_open_v2`) still rejects the empty file with `SQLITE_CANTOPEN (code 14)` because SQLite requires either creating the file header directly or reading a valid 100-byte SQLite database header.

---

## 2. Baseline & Previous Rejected Hypotheses

| # | Investigated Variable | Test Execution Result | Status |
|:---|:---|:---|:---|
| **1** | Directory `mkdirs()` on source DB | Insufficient to resolve restore tests 1–4 | `INSUFFICIENT` |
| **2** | `liveDb` open lifecycle contention | Fails identically with 0 databases open | `H2_FALSIFIED` |
| **3** | Database name extension (`.db`) | Fails identically with vs without `.db` | `NAME_EXTENSION_FALSIFIED` |
| **4** | `isRobolectric` classloader check | Evaluates to `true` (`isRobolectricClassFound = true`) | `CONFIRMED_WORKING` |

---

## 3. Ranked Hypotheses for Next Causal Factor

1. **Hypothesis A (Physical File Existence):** SQLite native open fails because the file does not physically exist prior to opening.
   - *Prediction:* Pre-creating the file with `createNewFile()` resolves `SQLITE_CANTOPEN`.
   - *Falsification:* Fails if SQLite rejects opening the 0-byte file with `SQLITE_CANTOPEN`.
2. **Hypothesis B (Windows 8.3 Short Path `ALMAHD~1`):** `context.getDatabasePath()` returns a Windows 8.3 short path (`C:\Users\ALMAHD~1\...`) which Robolectric's native SQLite C wrapper fails to open during SQLite file creation.
   - *Prediction:* Using canonical/long path representation (`File.canonicalPath`) allows native SQLite open to succeed.
3. **Hypothesis C (Room Migration/Callback Overhead on Temp Clone):** `AppDatabase.getDatabase` attaches 16 Room migrations and `onOpen` / `onCreate` callbacks that attempt database mutations before the initial temporary database header is flushed to disk.
   - *Prediction:* Opening a clean SQLite database via `FrameworkSQLiteOpenHelper` or minimal Room builder without migration callbacks succeeds where `AppDatabase.getDatabase` fails.

---

## 4. Chosen Single-Variable Experiment

* **Variable Tested:** Physical 0-byte file existence on disk (`tempPlainDbFile.createNewFile()`) immediately before invoking `AppDatabase.getDatabase(context, ByteArray(0), name).openHelper.writableDatabase`.
* **Execution Seam:** `com.example.Phase1RestoreTransportReconstructionTest.case1`.

---

## 5. Machine Output & Comparative Results

```text
=== MICRO-DIAGNOSTIC NEXT CAUSE: PHYSICAL FILE EXISTENCE EXPERIMENT ===
DEBUG_ENV: isRobolectricClassFound = true

CONTROL_RESULT (File exists before = false):
  success: false
  error:   Cannot open database '...\temp_plain_ctrl_20260830_000924_996' with flags 0x10000000:
           File ... doesn't exist and CREATE_IF_NECESSARY is set, check directory permissions
           (code 14 SQLITE_CANTOPEN)

EXPERIMENT_RESULT (File created = true, File exists before = true):
  success: false
  error:   Cannot open database '...\temp_plain_exp_20260830_000924_996' with flags 0x10000000:
           Unable to deduct failure reason
           (code 14 SQLITE_CANTOPEN)
=== END MICRO-DIAGNOSTIC NEXT CAUSE EXPERIMENT ===
```

---

## 6. Causal Conclusion

* Pre-creating an empty 0-byte file does **not** allow SQLite native open to succeed.
* SQLite's native C library (`sqlite3_open_v2`) requires either creating the file internally (which fails due to path handling or file locking under Robolectric Windows) or opening an existing file with a valid SQLite header.
* `isRobolectric` detection is confirmed `true`, meaning `TRUNCATE` mode was actively configured.
* All temporary diagnostic code was fully reverted via `git checkout`. The working tree contains zero modifications to tracked files.

---

## 7. Confidence & Single Next Step

* **Confidence:** **HIGH (Deterministic Empirical Proof)**.
* **Single Next Step:**
  > **Investigate Hypothesis B & C: Test whether SQLite native open succeeds when opening a raw SQLite database via `FrameworkSQLiteOpenHelper` with a long/canonical path versus the full Room 16-migration singleton builder (`AppDatabase.getDatabase`).**
