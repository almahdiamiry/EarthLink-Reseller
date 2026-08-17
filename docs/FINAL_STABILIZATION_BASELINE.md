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

# Earthlink Reseller App — Final Structural Stabilization Baseline

This document establishes the reproducible architectural and repository inventory baseline for the Earthlink Reseller App, satisfying all tasks and acceptance criteria of **Phase 0 — Baseline and Repository Inventory**.

---

## 1. Module & Component Inventory

### 1.1 Source Modules & Packages
The codebase is structured under the `com.example` root package, adhering to clean architecture concepts with distinct layers:

*   **`com.example.core.database`**: Houses Room Database declarations, DAO interfaces (`LocalAccountDao`, `LocalLedgerEntryDao`, `ImportBatchDao`, `SyncOutboxDao`, `SyncMetadataDao`, `AuditLogDao`), database migrations, and SQLiteCipher initialization helpers.
*   **`com.example.core.model`**: Holds data models for both API envelopes (`ApiEnvelope`, `LoginResponse`, `UserListItem`, `ActiveSessionItem`, `UserDetail`, etc.) and Room entities (`LocalAccount`, `LocalLedgerEntry`, `ImportBatch`, `SyncOutbox`, `SyncData`, `AuditLog`).
*   **`com.example.core.ledger`**: Contains core financial business rules including `BalanceCalculator` and `MoneyParser`.
*   **`com.example.core.sync`**: Contains components responsible for synchronization lifecycle, conflict resolution, outbox mutations, and importer workflows (`SyncRepositoryImpl`, `OutboxManager`, `SyncConflictResolver`, `UtowerImporter`, `SyncWorker`, etc.).
*   **`com.example.core.backup`**: Contains SQLite backup & atomic restore algorithms (`BackupManager`, `LocalAutoBackupWorker`).
*   **`com.example.core.security`**: Secure storage components (`PreferenceManager`, `SecurityFallbackGuard`).
*   **`com.example.domain.repository`**: Defines repository interfaces for loose coupling (`EarthlinkGateway`, `LocalAccountRepository`, `LocalLedgerRepository`, `UtowerImportRepository`, `SyncRepository`, `AuditRepository`).
*   **`com.example.data.repository`**: Authoritative implementations of the domain interfaces (`Repositories.kt`).
*   **`com.example.ui`**: Composable screen layouts and Jetpack Compose state viewmodels (`MainActivity.kt`, `EarthlinkApp.kt`, viewmodels, and screen composables).

---

## 2. Repositories, DAOs, & Entities

### 2.1 Database Entities (Mmapped from `com.example.core.model.Models.kt`)
1.  **`local_accounts` (`LocalAccount`)**: Stores user-reseller subscriber accounts (including debt, advance, loan, package price, credentials, and custom metadata). Key unique index: `index_local_accounts_sourceExternalId` on `sourceExternalId`.
2.  **`local_ledger_entries` (`LocalLedgerEntry`)**: Ledger ledger entries. Bound to `local_accounts` via `ForeignKey` with `CASCADE` on delete. Unique index: `index_local_ledger_entries_accountId_sourceExternalId` on `(accountId, sourceExternalId)`.
3.  **`import_batches` (`ImportBatch`)**: Metadata tracking external `.tgz` import histories.
4.  **`sync_outbox` (`SyncOutbox`)**: Local mutation outbox queue storing PENDING mutations for Firestore synchronization.
5.  **`sync_metadata` (`SyncData`)**: Key-value metadata table (e.g., storing sync cursors).
6.  **`audit_log` (`AuditLog`)**: Security and system event logs.

### 2.2 DAOs (Defined in `com.example.core.database.AppDatabase.kt`)
*   **`LocalAccountDao`**: Handles subscribers queries, upsert operations, duplicate searches, and Full Text Search (FTS) queries.
*   **`LocalLedgerEntryDao`**: CRUD for ledger entries, duplicate transaction lookups, and account-level cascading deletions.
*   **`ImportBatchDao`**: Tracking and rollback utilities for import batches.
*   **`SyncOutboxDao`**: Outbox queue state accessors: `getPending()`, `insert()`, `update()`, `delete()`, `clearPendingByEntity()`, `hasPending()`, `getDeadLetters()`, `resetDeadLetters()`, `resetSyncingToPending()`.
*   **`SyncMetadataDao`**: Synchronization state and timestamp cursor persistence (`last_sync_timestamp`).
*   **`AuditLogDao`**: Inserting and querying system audit logs.

### 2.3 Repositories (Defined in `com.example.data.repository.Repositories.kt`)
*   **`LocalAccountRepositoryImpl`**: Implements `LocalAccountRepository`. Manages CRUD for `LocalAccount` and FTS search.
*   **`LocalLedgerRepositoryImpl`**: Implements `LocalLedgerRepository`. Controls payment/debt entries insertions. Important: Bypasses direct DAO inserts by invoking `recalculateAccountHistoryInternal` to dynamically adjust the balance of the parent account.
*   **`UtowerImportRepositoryImpl`**: Implements `UtowerImportRepository`. Manages file parsing, dry-run preview, batch commit, and rollback capabilities.
*   **`AuditRepositoryImpl`**: Implements `AuditRepository`. Writes audit logs.

---

## 3. Synchronization & Outbox Flow

### 3.1 Sync Infrastructure
*   **`SyncRepositoryImpl`**: Orchestrates offline-first sync. It acts as the gateway to Firebase Auth, Cloud Firestore, and real-time document listeners.
*   **`SyncWorker`**: WorkManager class scheduling recurring background sync.
*   **`SyncConflictResolver`**: Deterministic conflict decision engine resolving incoming remote document changes against local modifications using clock-skew-safe updatedAt comparisons.
*   **`OutboxManager`**: Singleton authority wrapping Outbox manipulation. Provides high-level transactional utilities:
    *   `upsertWithOutbox(...)`: Queues an upsert to the outbox after purging older pending updates for the same entity.
    *   `deleteWithTombstone(...)`: Purges pending outbox updates and writes a deletion tombstone.
    *   `deleteWithTombstoneBatch(...)`: Batch deletes.

### 3.2 Key Synchronization Paths
*   **Upward Sync**: Pushes local `SyncOutbox` records of status `pending` or `failed` in chunked batches (up to 500 records) to Firestore. After a successful Firestore commit, local outbox items are deleted.
*   **Downward Sync (Pull)**: Queries Firestore collections for updates where `updatedAt` > `last_sync_timestamp`. It processes pages of 500 items. Incoming changes are applied locally via `SyncConflictResolver`.
*   **Real-time Downward Listeners**: Listens to collection snapshots on active Firestore collections, ignoring echo mutations (using `snapshot.metadata.hasPendingWrites()`).

---

## 4. Backup & Restore Architecture

### 4.1 Backup Pipeline
Implemented inside `BackupManager.kt`:
1.  **Atomic Snapshot (VACUUM INTO)**: Leverages SQLite's `VACUUM INTO` command to generate an uncorrupted, single-file copy of the database, bypassing risks associated with active WAL/SHM file-lock states.
2.  **WAL Checkpoint Fallback**: If `VACUUM INTO` is unsupported, triggers a full `wal_checkpoint(TRUNCATE)` to flush the Write-Ahead Log to the main database file before copying files sequentially.
3.  **Encrypted Metadata Packing**: Packs the database, optional journal sidecars, and `backup_info.json` containing an AES-encrypted copy of the SQLCipher passphrase (derived from user UID) into a compressed `.zip` archive.

### 4.2 Restore and Merging Engine
To ensure zero data-loss:
1.  **Pre-Restore Rolling Backup**: Automatically creates an archive of the active database before any overwrite.
2.  **Multiphase Decryption Verification**: Attempts to decrypt the candidate database using derived credentials: the extracted backup metadata key, current runtime passphrase, fallback preferences key, or Firebase UID.
3.  **Smart Entity-Level Merge**: Iterates through database chunks:
    *   Preserves local records if the local `updatedAt` is newer than the backup record.
    *   Applies tombstones if an active local outbox tombstone is pending.
    *   Restores and appends backup pending outbox queues to avoid losing offline mutations.
4.  **Balance Reconstruction**: Triggers `recalculateAccountHistoryInternal` for all modified account records to reconstruct consistent ledger balances.

---

## 5. Non-Negotiable Invariant Mapping & Heuristics

### 5.1 Financial Math & Parsing Heuristics
*   **`MoneyParser`**: Implements heuristic normalization where values `< 200.0` are scaled by `1000.0` (e.g. converting "15" -> "15000" IQD) to accommodate rapid seller entries.
*   **`BalanceCalculator`**: Translates financial transaction types ("took", "gave", "payment", etc.) to adjust debt, advance, and loan balances.
*   **`recalculateAccountHistoryInternal`**: Iterates through all ledger items associated with an account in occurred-at order, calculating rolling balances (`debtAfterIqd`).

---

## 6. Global Code Search & Pattern Index

To establish a strict static baseline, here is a global search map of core structural behaviors inside `app/src/`:

| Keyword/Pattern | Key Location | Purpose |
| :--- | :--- | :--- |
| **`outboxDao.`** | `SyncRepositoryImpl.kt`, `Repositories.kt` | Inserting, updating, clearing, and polling sync queue entries. |
| **`insert` / `insertAll`** | `AppDatabase.kt` (DAOs) | Core insert boundaries. |
| **`delete`** | `AppDatabase.kt`, `BackupManager.kt` | Deletes records or clean temporary backup/restore paths. |
| **`clearPending`** | `AppDatabase.kt`, `OutboxManager.kt`, `Repositories.kt` | Discarding stale mutations before writing new state. |
| **`hasPending`** | `SyncRepositoryImpl.kt` (lines 470, 495) | Conflict resolution state checking. |
| **`updatedAt`** | `Models.kt`, `SyncRepositoryImpl.kt`, `Repositories.kt` | Entity version control and sync cursor calculations. |
| **`System.currentTimeMillis()`**| `SyncRepositoryImpl.kt`, `Models.kt`, `Repositories.kt` | Writing creation/modification times for local mutations. |
| **`withTransaction`** | `SyncRepositoryImpl.kt`, `Repositories.kt`, `BackupManager.kt` | Enforcing atomic boundaries for Room updates. |
| **`await()`** | `SyncRepositoryImpl.kt`, `UtowerImporter.kt` | Suspending coroutine calls for Firestore and storage tasks. |
| **`Firebase`** | `SyncRepositoryImpl.kt`, `BackupManager.kt`, `EarthlinkApp.kt` | Firebase SDK interfaces (Auth, Firestore). |
| **`parseAmount`** | `UtowerImporter.kt`, `Repositories.kt` | Utility extracting values from JSON payloads. |
| **`* 1000` / `/ 1000`** | `MoneyParser.kt` | Scaling heuristics for UI display and data entry. |
| **`restore` / `backup`** | `BackupManager.kt`, `SettingsScreen.kt` | System rollback and state archiving. |

---

## 7. Testing & Verification Infrastructure

### 7.1 Existing Tests (Located in `app/src/test/java/com/example/`)
*   **`BalanceCalculatorTest.kt`**: Tests apply/revert state equations.
*   **`DatabaseMigrationTest.kt`**: Tests database schemas version migration 1 to 6.
*   **`DateTest.kt`**: Validates date formatting and calculations.
*   **`EarthlinkAppTest.kt`**: Integration and dependency injection tests.
*   **`EarthlinkGatewayTest.kt`**: Mocked and direct tests for the Earthlink ISP reseller API.
*   **`ExampleRobolectricTest.kt`**: Robolectric core activity and view testing.
*   **`LocalLedgerRepositoryTest.kt`**: Tests local transactional logic.
*   **`MoneyParserTest.kt`**: Exercises scale heuristics and inputs normalization.
*   **`OutboxManagerTest.kt`**: Ensures atomic outbox queue operations and tombstones.
*   **`PreferenceManagerTest.kt`**: Tests credential preferences and encryption keys.
*   **`RecalculateAccountHistoryTest.kt`**: Assures rolling balance recalculations logic.
*   **`RestoreProtocolTest.kt`**: Verifies atomic pre-restore and entity merging.
*   **`SecurityFallbackGuardTest.kt`**: Verifies secure keychain lookups.
*   **`SubscriberMatcherTest.kt`**: Validates phone/name matches heuristics.
*   **`SyncConflictResolverTest.kt`**: Verifies clock-skew thresholds and conflict decisions.
*   **`TransactionDeduplicatorTest.kt`**: Exercises transaction de-duplication heuristics.

### 7.2 Verification Scripts (Located in `scripts/`)
*   **`analyze_concurrency.sh`**: Scans the codebase for thread-safety and block lock usages.
*   **`check_use_blocks.sh`**: Verifies that resources are properly closed using `.use { }` blocks.

---

### Phase 0 Acceptance Criteria Verification
*   **No major synchronization or persistence path remains unclassified**: All DAOs, repositories, sync paths, outbox managers, backup managers, parser classes, tests, and scripts have been fully mapped, indexed, and analyzed.
*   **Reproducible baseline is established**: Clean compile verified (`compile_applet` build success).

This baseline is finalized and approved as the architectural blueprint for Phase 1.
