# EARTHLINK RESELLER V1 — EXPERIMENT TWO VERDICT

## GOAL
Evaluate whether `pullRemoteChanges()` can concurrently write into Room while `IMPORT` / `RESTORE` is replacing the local dataset, bypassing generation guards.

## DATASET
REAL dataset: `utower_data_c.tgz` (216 accounts, ~2,690 ledger entries).

## VERDICT
**REAL RACE — DO NOT IMPLEMENT TEMPORARY FIX WITHOUT PERMANENT ARCHITECTURAL DISCUSSION**
The suspected race condition is theoretically valid and reproducible under specific restore timings. However, modifying `executeSyncPassInternal` to hold the `DataOperationCoordinator` lock for its entire duration constitutes a significant architectural change that affects liveness (blocking UI).

## MECHANISM
1. `executeSyncPassInternal()` reads `DataOperationCoordinator.isMaintenanceActive` at the start of the pass but **does not acquire the lock**.
2. `pullRemoteChanges()` fetches from Firestore (network delay).
3. Concurrently, a user initiates a `RESTORE` from backup. `RESTORE` acquires the `IMPORT` lock, replaces the database (including sync cursors and sync state), and increments the global generation to `N+1`.
4. The network fetch in `pullRemoteChanges()` completes, returning an event meant for generation `N`.
5. `pullRemoteChanges()` calls `RemoteSyncCoordinator.processEvent()`.
6. `processEvent()` captures the generation *after* acquiring the `coordinatorMutex`. Because `RESTORE` has completed, it captures the NEW generation `N+1`.
7. `processEvent()` enters the Room `withTransaction` block, reads the current generation (`N+1`), and compares it with the captured generation (`N+1`). They match.
8. The generation-mismatch guard (`Lineage generation mismatch... Rejecting stale remote result`) is bypassed.
9. `SyncConflictResolver` evaluates the event. If the event's remote timestamp is newer than the restored local dataset's timestamp, it falsely applies the stale event.
10. The freshly restored database is corrupted with state that was valid *before* the restore occurred.

## REPRODUCTION
The race was successfully simulated in Robolectric (`ExperimentTwoTest`) using the real `utower_data_c.tgz` dataset and `BackupManager`:
* An account's state was captured (Gen 1).
* A slightly newer remote event was mocked to simulate a slow Firestore pull.
* The database was replaced via `BackupManager.restoreBackupZip()`.
* The mocked remote event was passed into `RemoteSyncCoordinator.processEvent()`.
* The event was successfully applied onto the restored database, corrupting the restored state.

## JUSTIFICATION
The race condition allows stale events (belonging to the pre-restore state) to corrupt a freshly restored database. The generation check fails to prevent this because the generation is captured *after* the network delay, rather than *before* it.

While the race is real, it requires precise timing (a restore must occur exactly while a pull is waiting on the network).

## TEMPORARY IMPLEMENTATION OUTCOME
The temporary fix of acquiring `DataOperationCoordinator.withOperation(SYNC)` around the entire `executeSyncPassInternal` prevents the race by ensuring mutual exclusion. However, because `executeSyncPassInternal` involves potentially long network operations, holding this global lock can lead to undesirable liveness regressions (blocking user maintenance operations for the duration of a slow sync).

## CONCLUSION
**DO NOT IMPLEMENT** the lock change without explicit authorization. The race is proven, but the fix introduces liveness risks that must be weighed against the likelihood of the race occurring in production.
