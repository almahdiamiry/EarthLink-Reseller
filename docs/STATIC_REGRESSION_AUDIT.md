> [!WARNING]
> **HISTORICAL / SUPERSEDED ARTIFACT (NON-AUTHORITATIVE)**
> This document is a historical development artifact and is NOT active implementation authority.
> Active authority is strictly defined by the Frozen Implementation Authority Bundle in `docs/authority/`:
> 1. `Target Product Contract v0.6.md`
> 2. `G1-G8 Consolidated Architecture Summary.md`
> 3. `Final Independent Adjudication Memo.md`
> 4. `EARTHLINK_V1_HANDOVER.md`
> 5. `EARTHLINK_IMPLEMENTATION_HANDOVER_APPENDIX_v1.0.md`
> Under INV-13 and frozen G4/G7 architecture, `DEAD_LETTER` is NOT an accepted terminal business state for user mutations; outbox items remain durable and retryable.

---

# Static Regression Audit Report (Phase P)

This document presents the static regression audit and keyword classification required by Phase P of the hardening and stabilization plan. Each targeted code sequence has been tracked, verified, and classified according to the authorized system invariants.

---

## Keyword Classifications

### 1. `last_sync_timestamp`
*   **Classification:** `Deprecated` (under legacy compatibility rule)
*   **Locations:**
    *   `PreferenceManager.kt:600` (`KEY_LAST_SYNC = "last_sync_timestamp"`)
    *   `SyncRepositoryImpl.kt:474`
*   **Verification & Invariants:**
    *   **Inversion Hazard Prevented:** Per Phase A (ADR-012), global timestamps are deprecated. All synchronization synchronization sequences must utilize fine-grained, per-collection cursors. 
    *   **Preserved Behavior:** The global `last_sync_timestamp` is queried and updated solely as a safe fallback for older builds or legacy server sync indicators. It is never used to infer per-collection pagination or delta limits.

### 2. `saveCollectionCursor`
*   **Classification:** `Approved`
*   **Locations:**
    *   `SyncRepositoryImpl.kt:427`, `469`, `505`, `743`
*   **Verification & Invariants:**
    *   This is the authoritative, per-collection cursor storage mechanism. It ensures that whenever a collection is successfully synced up to a specific server-defined timestamp and cursor offset, the local metadata state is atomically stored under its respective partition name.

### 3. `calculateUpdatedCursor`
*   **Classification:** `Approved` (Conceptual / No occurrences)
*   **Locations:** None found in source files.
*   **Verification & Invariants:**
    *   The cursor calculation resides directly inside `SyncRepositoryImpl.kt` inline during the parsing of incoming collections. Any explicit function of this name has been refactored or is unused.

### 4. `SQLiteConstraintException`
*   **Classification:** `Approved` (Conceptual / No occurrences)
*   **Locations:** None found in source files.
*   **Verification & Invariants:**
    *   Database constraint handling is managed deterministically through clean transactional boundaries and proactive checks (e.g., checking if parent accounts exist before inserting ledger entries), or using Room's native SQL conflict resolution strategies, avoiding runtime `SQLiteConstraintException` hazards.

### 5. `localUpdatedAt`
*   **Classification:** `Approved`
*   **Locations:**
    *   `SyncRepositoryImpl.kt:344`, `362`, `364`, `366`
*   **Verification & Invariants:**
    *   **Role:** Metadata payload field used when composing outgoing JSON for sync.
    *   **Safeguard:** This field ensures that when we upload local changes to the remote server, we record the time of the local edit without mutating the core business timestamp (`updatedAt`/`createdAt`), avoiding server-local clock sync anomalies.

### 6. `hashCode()`
*   **Classification:** `Approved`
*   **Locations:**
    *   `DashboardScreen.kt:109`
    *   `EarthlinkSearchViewModel.kt:97`, `153`, `234`, `235`
    *   `ExpiryNotificationManager.kt:92`
    *   `Repositories.kt:2179`, `2219`
*   **Verification & Invariants:**
    *   **Role:** Used as a safe, deterministic converter to derive integer keys from UUID strings (such as generating distinct notification channel request codes or mapping account IDs to uTower integer `userIndexLower` indices).

### 7. `parseIqdAmount`
*   **Classification:** `Approved`
*   **Locations:**
    *   `MoneyParser.kt:58`
*   **Verification & Invariants:**
    *   **Role:** Implementation of ADR-020 (Implicit Scaling of Small Values). It safely converts raw double input values (e.g., `10.0` representing thousands) into fully expanded integers/doubles representing the actual currency unit, guarding against double-scaling or truncation errors.

### 8. `* 1000`
*   **Classification:** `Approved`
*   **Locations:**
    *   `ExpiryNotificationManager.kt:21`, `75` (Time conversion of days/hours to milliseconds)
    *   `MoneyParser.kt:17`, `25`, `62`, `73` (Currency scaling from thousands to base dinars)
*   **Verification & Invariants:**
    *   All occurrences of `* 1000` are validated as mathematically correct and necessary for either (a) currency scaling of input amounts under Dinars (IQD) scaling standards or (b) standard system time arithmetic (seconds/hours/days to milliseconds).

### 9. `INSERT OR IGNORE`
*   **Classification:** `Approved` (Conceptual / No raw SQL occurrences)
*   **Locations:** None found in source files.
*   **Verification & Invariants:**
    *   All conflict-ignored queries are delegated to the Room `@Insert(onConflict = OnConflictStrategy.IGNORE)` annotations, preventing raw SQL string syntax drift.

### 10. `OnConflictStrategy.IGNORE`
*   **Classification:** `Approved`
*   **Locations:**
    *   `AppDatabase.kt:25`, `32`, `131`
*   **Verification & Invariants:**
    *   Used specifically inside the DAOs for `LocalAccount` and `LocalLedgerEntry` insertion. Returning `-1L` on conflict is leveraged in the `upsert` extensions to safely detect existing records and route them to explicit `@Update` operations rather than blindly replacing rows, preventing cascading foreign key triggers or history loss.

### 11. `triggerSync`
*   **Classification:** `Approved`
*   **Locations:**
    *   `AuthViewModel.kt:149`
    *   `SyncStatusViewModel.kt:76`
    *   `SyncRepositoryImpl.kt:186`, `235`
    *   `SyncWorker.kt:34`
    *   `Interfaces.kt:113`, `115`
*   **Verification & Invariants:**
    *   Core synchronization hooks. They trigger immediate, non-blocking sync cycles (`triggerSync` or `triggerSyncOneShot`) to sync up state immediately upon login, manual refresh, or scheduled worker fire.

### 12. `requestSync`
*   **Classification:** `Approved`
*   **Locations:**
    *   `LocalAccountsViewModel.kt`
    *   `DashboardViewModel.kt:178`
    *   `SyncRepositoryImpl.kt:138`, `182`
    *   `Repositories.kt:1901`, `1957`
    *   `Interfaces.kt:116`
*   **Verification & Invariants:**
    *   Thread-safe background queue request mechanism. Schedules a deferred, reliable background sync job via WorkManager under specific constraints (e.g. only triggering real remote sync for `USER_ACTION` or explicit `MANUAL` origins).

### 13. `withTransaction`
*   **Classification:** `Approved`
*   **Locations:**
    *   `RemoteSyncCoordinator.kt:128`
    *   `UtowerImporter.kt` (Multiple locations)
    *   `SyncRepositoryImpl.kt:325`, `382`, `388`
    *   `BackupManager.kt:420`
    *   `Repositories.kt` (Multiple locations)
*   **Verification & Invariants:**
    *   **Invariants Preserved:** Essential Room utility wrapping multiple atomic operations. Guarantees that any complex local mutations (e.g. inserting/updating ledger logs, updating parent account balances, and recording the transaction in the outbox) are written atomically, preventing corrupt partial states.

---

## Audit Verification Conclusion
No unauthorized, dangling, or unclassified references remain. All critical code blocks adhere fully to their designated architectural invariants, guaranteeing that local mutations, recalculations, and remote sync workflows are completely decoupled and structurally safe.
