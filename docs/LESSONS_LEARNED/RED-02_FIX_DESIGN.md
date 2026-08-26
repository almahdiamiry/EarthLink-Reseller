# RED-02 FIX DESIGN: STALE PULL EVENT AFTER IMPORT / RESTORE GENERATION RACE

## ROOT CAUSE
The generation check `appDatabase.getGeneration()` inside `RemoteSyncCoordinator.processEvent` happens *after* the potentially long network fetch inside `SyncRepositoryImpl.pullRemoteChanges()`. If a RESTORE (or IMPORT) replaces the dataset and increments the generation *during* that network wait, `processEvent` will capture the new generation (N+1) instead of the old one (N), mistakenly passing the lineage mismatch guard and applying stale events to the newly restored database. Additionally, `executeSyncPassInternal` does not hold the `DataOperationCoordinator` maintenance lock, allowing the RESTORE to occur concurrently with the sync pass in the first place.

## CANDIDATE FIX
1. Modify `RemoteSyncCoordinator.processEvent()` to accept an optional `passedCapturedGen` parameter. If provided, use it instead of capturing the generation internally, and return `EventSyncResult.FAILED_RETRYABLE` on a mismatch to explicitly halt cursor advancement.
2. In `SyncRepositoryImpl.executeSyncPassInternal()`, capture a single `passGeneration = metadataDao.getGeneration()` right after the initial maintenance checks.
3. Pass this `passGeneration` down to `pullRemoteChanges()` and subsequently into `processEvent()`.
4. Wrap all cursor-saving and timestamp-saving logic (`saveCollectionCursor` and `last_sync_timestamp`) inside an `if (metadataDao.getGeneration() == passGeneration)` condition to prevent writing stale network state over a restored database.

## BEFORE
The adversarial test demonstrated that if `pullRemoteChanges` started, paused for network, a RESTORE occurred, and then the response arrived, the stale event was applied to the freshly restored database. The generation check bypassed because it captured generation N+1 *after* the restore.

## AFTER
With the temporary fix, the captured generation (N) from the start of the pass was provided to `processEvent`. When checked inside the transaction against the new current generation (N+1), it correctly rejected the event as stale and returned `FAILED_RETRYABLE`. The cursor was not advanced, and the restored dataset remained pristine.

## DATA INTEGRITY
The actual data post-race precisely matched the expected post-restore state with exactly zero unexpected differences. Financial debt was preserved at the correct baseline, and no invalid lineage state leaked into the replacement dataset.

## LIVENESS
The fix safely avoids holding the global `DataOperationCoordinator.withOperation` lock during unbounded network fetches. Local UI mutations, imports, and restores remain unblocked during the sync pass.

## ARCHITECTURAL IMPACT
Only localized changes within `SyncRepositoryImpl.kt` and `RemoteSyncCoordinator.kt` are required. The fix does not alter `SyncConflictResolver` logic, outbox policies, or financial models.

## PERMANENT IMPLEMENTATION SCOPE
Smallest file boundary:
- `app/src/main/java/com/example/core/sync/RemoteSyncCoordinator.kt` (Signature update and mismatch return)
- `app/src/main/java/com/example/core/sync/SyncRepositoryImpl.kt` (Pass generation injection and cursor save guarding)

## REQUIRED REGRESSION SUITE
Before permanent adoption, the following tests must be executed:
- `ReplaceAllRemoteSyncTest`
- `Phase5SettingsSyncUnifiedCallerTest`
- The new `ExperimentTwoTest` (pull vs restore race)
- All `RemoteSyncCoordinator` baseline tests
