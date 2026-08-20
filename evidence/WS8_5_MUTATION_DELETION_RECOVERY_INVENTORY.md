# WORKSTREAM 8.5 — PRODUCTION MUTATION / DELETION / RECOVERY BOUNDARY INVENTORY

**Baseline**: HEAD `e404f75`
**Status**: COMPLETE

---

## A. Financial Reconstruction / Unknown-Type Callers

### 1. `deriveAccountBalance` / `reconstructCurrentPosition`
- **File**: `com.example.core.ledger.BalanceCalculator.kt`
  - `reconstructCurrentPosition(history: List<LocalLedgerEntry>, baseline: CurrentPositionSnapshot?, onUnrecognizedType: ((String, String, Long) -> Unit)? = null): CurrentPositionSnapshot`
  - `deriveAccountBalance(entries: List<LocalLedgerEntry>, baselineSnapshot: CurrentPositionSnapshot? = null, onUnrecognizedType: ((String, String, Long) -> Unit)? = null): Double`
  - `calculateBalance(entries: List<LocalLedgerEntry>, baseBalance: Double = 0.0): Double` (wrapper calling `deriveAccountBalance`)

### 2. Production Callers of Balance Reconstruction
1. **`LocalLedgerRepositoryImpl.recalculateAccountHistoryInternal(accountId: String)`**
   - **File**: `app/src/main/java/com/example/data/repository/Repositories.kt` (lines ~1380-1430)
   - **Role**: Re-reads all local ledger entries + baseline snapshot for `accountId`, invokes `reconstructCurrentPosition`, writes back calculated `debtIqd`, `advanceIqd`, `loanIqd`, `balanceIqd` to `LocalAccountDao.updateBalances(...)`.
2. **`RemoteSyncCoordinator.recalculateAccountBalance(accountId: String)`**
   - **File**: `app/src/main/java/com/example/core/sync/RemoteSyncCoordinator.kt` (lines ~645-675)
   - **Role**: Post-remote apply recalculation of account balances and current position from ledger entries.
3. **`BackupManager.restoreFromEncryptedBackup(...)`**
   - **File**: `app/src/main/java/com/example/core/backup/BackupManager.kt`
   - **Role**: Re-computes running balances across restored accounts without creating dirty outbox mutations.
4. **`StatementViewModel` / UI Statement Summary**
   - **File**: `app/src/main/java/com/example/ui/viewmodels/StatementViewModel.kt`
   - **Role**: UI presentation of debit/credit totals and running statement positions.

---

## B. G1 Resolution Entry Points

All entry points reaching `verifyAndResolvePendingOperation` / G1 pending operation resolution:
1. **`EarthlinkSearchViewModel.resolvePendingOperation(...)`**
   - **File**: `app/src/main/java/com/example/ui/viewmodels/EarthlinkSearchViewModel.kt`
   - **Role**: UI-driven resolution when a user clicks to retry/verify a pending activation operation.
2. **`SyncWorker.doWork()` / `sweepAndResolvePendingOperations()`**
   - **File**: `app/src/main/java/com/example/core/sync/SyncWorker.kt`
   - **Role**: Background WorkManager sweep of pending G1 activations that timed out or crashed during network transition.
3. **`EarthlinkApp.onCreate()` -> background recovery kick-off**
   - **File**: `app/src/main/java/com/example/EarthlinkApp.kt`
   - **Role**: Schedules immediate `SyncWorker` one-shot work on application process launch.

---

## C. Delete / Tombstone Emitters

### 1. `OutboxManager.deleteWithTombstone(collection, documentId)`
- **`LocalLedgerRepositoryImpl.deleteTransaction(...)`** (pre-WS9A)
  - Pre-fix role: Emitter of ledger tombstones. Replaced in WS9A with additive correction entry (`correctsEntryId`) and zero tombstone emission.
- **`RemoteSyncCoordinator.applyAccountDelete(...)`**
  - Role: History-preserving account tombstone processing. Emits no local destructive ledger drops, records remote tombstone version.

### 2. `OutboxManager.deleteWithTombstoneBatch(...)`
- **`UtowerImporter.rollbackImportBatch(...)`** (pre-WS9B)
  - Role: Emitter of tombstones during import rollback. In WS9B, restricted to unaccepted import only with ZERO outbox/remote tombstones emitted.

---

## D. Physical Deletion Authorities & Classifications

| DAO Method | Caller | File / Location | Section-2 Classification |
|---|---|---|---|
| `LocalLedgerEntryDao.deleteById` | `LocalLedgerRepositoryImpl.deleteTransaction` | `Repositories.kt` | **NORMAL BUSINESS CORRECTION** (Replaced in WS9A with additive correction) |
| `LocalLedgerEntryDao.deleteByBatchId` | `UtowerImporter.rollbackImportBatch` | `UtowerImporter.kt` | **RESTORE / RECONSTRUCTION** (Unaccepted import rollback only) |
| `LocalLedgerEntryDao.deleteAll` | `UtowerImporter.importUtowerData(shouldReplace=true)` | `UtowerImporter.kt` | **DATASET REPLACEMENT** (uTower establishment replace) |
| `LocalLedgerEntryDao.deleteAll` | `BackupManager.restoreFromEncryptedBackup(mode=REPLACE)` | `BackupManager.kt` | **RESTORE / RECONSTRUCTION** |
| `LocalLedgerEntryDao.deleteAll` | `AppDatabase.clearAllData()` / `signOut(clearData=true)` | `AppDatabase.kt` / `AuthViewModel.kt` | **DEVELOPER RESET / SIGN-OUT** |
| `LocalAccountDao.deleteById` | `LocalAccountRepositoryImpl.deleteAccount` | `Repositories.kt` | **HISTORY-PRESERVING DELETION** (Soft-delete / flag) |
| `LocalAccountDao.deleteAll` | `UtowerImporter.importUtowerData(shouldReplace=true)` | `UtowerImporter.kt` | **DATASET REPLACEMENT** |
| `LocalAccountDao.deleteAll` | `BackupManager.restoreFromEncryptedBackup(mode=REPLACE)` | `BackupManager.kt` | **RESTORE / RECONSTRUCTION** |
| `LocalAccountDao.deleteAll` | `AppDatabase.clearAllData()` / `signOut(clearData=true)` | `AppDatabase.kt` / `AuthViewModel.kt` | **DEVELOPER RESET / SIGN-OUT** |

---

## E. Whole-Dataset / Replacement / Restore Paths

1. **uTower Importer (`UtowerImporter.importUtowerData`)**:
   - `shouldReplace = false` (Merge): Inserts non-conflicting accounts and missing ledger history.
   - `shouldReplace = true` (Replace): Canonical starting dataset establishment; clears pre-existing local data, resets sync cursors, resets remote version metadata, and reconciles cloud state.
2. **uTower Importer Rollback (`UtowerImporter.rollbackImportBatch`)**:
   - Unaccepted batch rollback: removes temporary local import records; emits no remote tombstones.
3. **Backup Restore (`BackupManager.restoreFromEncryptedBackup`)**:
   - `RestoreMode.MERGE`: Adds missing entities without clearing.
   - `RestoreMode.REPLACE`: Full disaster recovery restore of database file state.
4. **Database Reset (`AppDatabase.clearAllData()`)**:
   - Full reset on user signout with explicit `clearData=true`.

---

## F. Remote-Apply Entry Points

1. **`RemoteSyncCoordinator.processEvent(event: RemoteSyncEvent)`**
   - Main entry point for processing incoming Firestore changes (AccountUpsert, AccountDelete, LedgerUpsert, LedgerDelete).
2. **`SyncRepositoryImpl.pullRemoteChanges()` / `pullCollection(...)`**
   - Periodic / on-demand composite timestamp-document cursor pull loop.
3. **`SyncRepositoryImpl.listenToRemoteChanges()`**
   - Realtime Firestore snapshot listener pipeline invoking `RemoteSyncCoordinator.processEvent`.

---

## G. Metadata Writers & `remote_version:*` Write Sites

All calls to `SyncMetadataDao.put(...)` and direct/indirect `remote_version:*` writes across the codebase:

### The 6 Identified `remote_version:*` Write Sites:
1. **`RemoteSyncCoordinator.applyAccountUpsert`**: Writes `remote_version:account:<id>`
2. **`RemoteSyncCoordinator.applyAccountDelete`**: Writes `remote_version:account:<id>`
3. **`RemoteSyncCoordinator.applyAccountDelete` (child ledger loop)**: Writes `remote_version:ledger:<entryId>`
4. **`RemoteSyncCoordinator.applyLedgerUpsert`**: Writes `remote_version:ledger:<entryId>`
5. **`RemoteSyncCoordinator.applyLedgerDelete`**: Writes `remote_version:ledger:<entryId>`
6. **`SyncRepositoryImpl.pullCollection` (cursor / batch processing)**: Writes entity `remote_version:<type>:<id>`

**Atomic DAO Monotonicity Requirement (WS10.5)**:
- All 6 sites MUST be routed through `SyncMetadataDao.putMonotonicRemoteVersion(key, newVersion)` which executes `INSERT INTO sync_metadata (key, value, updatedAt) VALUES (:key, :newVersion, :now) ON CONFLICT(key) DO UPDATE SET value = MAX(CAST(sync_metadata.value AS INTEGER), CAST(excluded.value AS INTEGER)), updatedAt = :now`.

---

## H. Release BuildConfig References

- `AppBuildConfig.kt` is the designated central wrapper for `BuildConfig`.
- `SettingsScreen.kt` contained raw `com.alamiry.earthlinkreseller.BuildConfig.DEBUG` references (to be normalized to `AppBuildConfig.DEBUG` in WS14).
