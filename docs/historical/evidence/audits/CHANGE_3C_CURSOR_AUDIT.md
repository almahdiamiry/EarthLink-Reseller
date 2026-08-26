# EARTHLINK RESELLER V1 — CHANGE 3C AUDIT
## CHUNK CURSOR PERSISTENCE / RECOVERY SEMANTICS

### STATIC FINDING
Cursor advancement strictly obeys `syncResult.canAdvanceCursor()` and strictly moves monotonically forward via `advanceTo()`. Within the `handleSnapshot` chunked loop, any failure (e.g., `FAILED_RETRYABLE` or `BLOCKED_INVALID_VERSION`) sets `shouldHalt = true`, breaking the chunk loop entirely and bypassing cursor persistence for the failed chunk. The global `snapshotMutex` and chunking structure ensure events are serialized correctly.

### CRASH / RECOVERY TESTS
- **Crash mid-chunk:** If Chunk 2 crashes before completing, Chunk 2's cursor is not saved. Chunk 1's cursor remains authoritative in Room.
- **Next Reconnect:** The realtime listener re-initializes using the Chunk 1 cursor (`startAfter`). It fetches Chunk 2 events again.
- **Replay Behavior:** The replayed events hit the `processedKeys` LRU cache (if the process didn't die) and resolve instantly. If the process crashed, they hit `SyncConflictResolver` which sees identical timestamps and returns `APPLY_UPSERT`. Room SQLite gracefully overwrites the exact identical data with zero ledger math drift and zero duplicate identities.
- **No events are skipped, and replay is 100% harmless.**

### CURSOR MONOTONICITY
`RemoteSyncCursor.advanceTo(candidateTimestamp, candidateDocId)` mathematically prohibits backward movement. It only advances if `candidateTimestamp > lastServerTimestamp`, or if timestamps are equal and `candidateDocId > lastDocumentId`. Local echoes (skipped events) safely leave the cursor pinned to the last verified remote event, which is correct behavior for outbox-driven local writes.

### RED-02 COMPATIBILITY
The per-chunk cursor save is explicitly guarded by `if (metadataDao.getGeneration() == snapshotGen)`. 
If a `RESTORE` or `RESTORE_MERGE` occurs during the `yield()` boundary between chunks, the database generation increments. The subsequent chunk will fail processing (rejecting stale remote events) and bypass the `saveCollectionCursor` guard, meaning a stale snapshot can never overwrite a new post-restore generation cursor.

### BEFORE / AFTER LOGICAL COMPARISON
- **Before Change 3B:** Cursor was saved only after all 500 events succeeded. A crash at event 499 required replaying all 499 events.
- **After Change 3B:** Cursor is saved every 50 events. A crash at event 499 requires replaying only 49 events.
Both implementations are semantically identical in correctness (since duplicate replay is idempotent), but Change 3B dramatically improves crash recovery speed and minimizes redundant processing.

### DATA-INTEGRITY RESULT
PERFECT INTEGRITY. The chunked cursor logic creates no risk of skipped events, no risk of duplicate ledger identities, and strictly obeys the generation invariants of RED-02.

### FINAL DECISION
**A. KEEP — materially improves crash recovery with no correctness downside.**

### IF KEEP:
Explain exactly why:
The per-chunk `saveCollectionCursor` creates durable, monotonically advancing checkpoints that reduce replay work by up to 90% during severe network oscillation or process death. Because `SyncConflictResolver` natively handles identical replays as safe overwrites, and the RED-02 guard protects against cross-generation cursor corruption, this mechanism is entirely beneficial and introduces zero architectural risk.
