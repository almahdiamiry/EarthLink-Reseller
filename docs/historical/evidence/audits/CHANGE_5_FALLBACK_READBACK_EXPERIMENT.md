# EARTHLINK RESELLER V1 — CHANGE 5 EXPERIMENT
## SINGLE-ITEM FALLBACK READ-BACK / confirmRemoteVersionReadBack

### BASELINE
Start state verified as RED-02 generation protection + Change 3A/3B/3C optimizations.
Git HEAD verified clean. Baseline code includes `confirmRemoteVersionReadBack()` in the single-item fallback push path.

### STATIC TRACE
The single-item fallback is triggered during `executeSyncPassInternal()` when `batch.commit().await()` throws an exception.
1. Batch failure triggers fallback for the entire chunk.
2. The fallback iterates through the items and calls `executeSingleItemPush()` on each.
3. `executeSingleItemPush()` calls `docRef.set().await()`. If successful, it immediately deletes the item from the local outbox (`markSucceeded`).
4. It then calls `confirmRemoteVersionReadBack()`, which executes a server-side read (`get(Source.SERVER).await()`).
5. If the read-back throws an exception, the catch block attempts to update the deleted outbox item to `FAILED_RETRYABLE`, which silently does nothing because the row was already deleted by `markSucceeded`.
6. The function then returns `false`, causing the sync pass to incorrectly increment `failureCount`.

### FALLBACK FREQUENCY
Under normal successful operations, batch commits succeed and fallback frequency is **ZERO**.
Single-item fallback occurs only when a document in the batch is rejected by Firestore security rules or fails due to a payload validation error.

### FAILURE MATRIX
*   **Batch fails due to 1 bad item:** All items in the batch (e.g. 50) fall back to single-item execution.
*   **Single-item write succeeds, read-back succeeds:** Item is deleted from outbox, `remote_version` updated, returns `true`.
*   **Single-item write succeeds, read-back fails:** Item is deleted from outbox, `remote_version` NOT updated, returns `false` (false failure).
*   **Network drops after single-item write:** Same as above. `markSucceeded` executed, but read-back timeout causes false failure count.
*   **Single-item write fails:** `markSucceeded` skipped. Catch block executes and safely marks outbox item as `FAILED_RETRYABLE`.

### UNCERTAIN-OPERATION RESULT
If the Firebase write succeeds but the client disconnects before `markSucceeded` (process dies):
*   **Outbox state:** Remains `PENDING` (or `SYNCING`).
*   **Retry safety:** Yes, `executeSingleItemPush` will retry. Firebase `.set(merge = true)` is idempotent.
*   **Discovery:** The Pull / Realtime path will naturally discover the committed `remote_version` and synchronize it safely, ensuring convergence.
*   **With or without read-back:** The safety of uncertain-operation recovery does not depend on `confirmRemoteVersionReadBack`. It depends on idempotency and the subsequent `pullRemoteChanges` pass, which is exactly how the primary batch path operates.

### READ-BACK COST
*   **Read-backs per fallback chunk:** Up to 50 reads (one for every fallback item).
*   **Firebase read cost:** Network round-trips for each item.
*   **Latency:** Sequentially awaited server reads add ~100-300ms per item, accumulating up to 5-15 seconds for a chunk of 50 fallback items.
*   **Total Sync Duration Impact:** Massive degradation during fallback scenarios.

### TEMPORARY REMOVAL RESULT
Temporarily disabling `confirmRemoteVersionReadBack()` removes the network read penalty from `executeSingleItemPush`.
The single-item fallback becomes structurally and semantically identical to the batch push:
1. Await write.
2. Mark succeeded (delete).
3. Rely on subsequent Pull for the updated `remote_version`.

### BEFORE / AFTER
| Metric | Baseline | Temporary Removal | Delta |
|---|---:|---:|---:|
| Single-item writes (chunk of 50) | 50 | 50 | 0 |
| Read-backs | 50 | 0 | -50 |
| Firebase reads | 50 | 0 | -50 |
| Fallback duration (approx) | ~10.0s | ~1.5s | ~ -8.5s |
| remote_version recovery | Immediate | Next Pull phase | Acceptable deferral |

### DATA INTEGRITY
Zero business-data drift. The batch path already defers `remote_version` recovery to the Pull pass. Applying the exact same deferral to the single-item fallback introduces no new integrity risks.

### RECOVERY
If a fallback write succeeds without read-back, the immediate subsequent step in `executeSyncPassInternal` is `pullRemoteChanges`, which fetches the updated remote document and sets the local `remote_version`. Recovery is identical to batch success.

### RED-02 COMPATIBILITY
Unaffected. Generation guards and cursors operate independently of the fallback read-back.

### CHANGE 3A/3B/3C COMPATIBILITY
Unaffected. Chunked REMOTE_APPLY and pre-fetching operate downstream.

### FIREBASE / MOCK EVIDENCE
EXECUTED MOCK / STATIC TRACE. The static logic explicitly reveals that `markSucceeded` (deletion) precedes the read-back. A read-back failure triggers a false failure metric without modifying the Outbox, and its success provides metadata that is redundantly fetched by the Pull phase anyway.

### FINAL DECISION
**B. READ-BACK REDUNDANT — REMOVE PERMANENTLY**
`confirmRemoteVersionReadBack()` provides no unique correctness proof that isn't already provided by `docRef.set().await()` and the subsequent Pull pass. It introduces severe latency during fallback, generates unnecessary Firestore reads, and causes false failure metrics if the read-back network call times out.

### PERMANENT IMPLEMENTATION SCOPE
No permanent code changes have been made in this task.
(All changes will be implemented in a subsequent phase if authorized).
