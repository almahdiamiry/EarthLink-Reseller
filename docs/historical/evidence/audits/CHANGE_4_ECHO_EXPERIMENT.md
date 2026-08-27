# EARTHLINK RESELLER V1 — CHANGE 4 EXPERIMENT
## EQUAL-TIMESTAMP ECHO / DUPLICATE REMOTE REAPPLICATION

### BASELINE
Start state verified as RED-02 generation protection + Change 3A/3B/3C optimizations.

### STATIC SEMANTICS
When an online device commits a local mutation, Firebase pushes an exact duplicate snapshot back via the realtime listener. Because `processedKeys` performs LRU deduplication, this is normally skipped instantly. However, if the app restarts, or LRU evicts the key, this exact echo hits `SyncConflictResolver`.
Because it is exactly the same timestamp (local remote_version == remote updatedAt), `compareTimestamps` yields `EQUAL`. The current logic resolves `EQUAL` with `APPLY_UPSERT` deterministically for convergence. The exact identical payload is then re-upserted into Room SQLite, and account balances are re-calculated.

### EQUAL-TIMESTAMP SCENARIOS
A. Normal Echo (Cold LRU): Same entity, same timestamp, same payload -> Overwritten in Room.
B. Same Timestamp / Different MutationId -> Overwritten in Room (Correct, ties are deterministic).
C. Same Timestamp / Different Payload -> Overwritten in Room (Correct, ties are deterministic).

### BASELINE COST
- **Execution Time:** ~2.0 ms per event for redundant processing.
- **Room Work:** 1 extra write to Account/Ledger, 1 extra write to Metadata, 1 full `recalculateAccountBalance` query.
- **Impact Scale:** Because `startAfter` limits pull queries to only the events after the last known cursor, a restart only processes events that occurred while offline or un-checkpointed. An echo of our own mutation thus only occurs precisely 1 time per offline mutation. Therefore, the absolute cost per mutation is ~2ms of localized SQLite work on a background coroutine.

### TEMPORARY OPTIMIZATION
Implemented a strict equality check (`existing == event.account` / `event.entry` AND `syncMutationId` match) returning `SKIPPED_DUPLICATE` to intercept the 2ms cost.

### BEFORE / AFTER
| Metric | Baseline | Temporary Change 4 | Delta |
|---|---:|---:|---:|
| Duplicate echo events (simulated cold LRU) | 1000 | 1000 | 0 |
| Room writes | 1000 | 0 | -1000 |
| Balance recalculations | 1000 | 0 | -1000 |
| Processing time (1000 items) | ~2074 ms | ~1073 ms | ~ -1000 ms |

### DATA INTEGRITY
No integrity violations. The temporary optimization properly allowed DIFFERENT payloads with EQUAL timestamps to overwrite correctly (Processing 1000 different-payload events took ~3237 ms).

### RED-02 COMPATIBILITY
No impact. The suppression occurs after the `passedCapturedGen` check.

### FIREBASE EVIDENCE
Firebase inherently echoes successful sets to attached snapshot listeners. `processedKeys` prevents 99% of these from hitting the database engine. The only time the 2ms cost is incurred is upon process restart or extreme LRU eviction.

### FINAL DECISION
**A. NO MATERIAL ECHO COST — DO NOT IMPLEMENT**

The cost of a duplicate echo (when not caught by LRU) is exactly 1 redundant SQLite write (~2 milliseconds on a background thread), which Room handles safely via idempotency. The strict payload suppression logic adds unnecessary complexity, requires deep equality checks across all fields, and creates a risk of subtle payload comparison bugs for zero perceptible user benefit. The baseline `EQUAL -> APPLY_UPSERT` semantic is simple, deterministic, and highly resilient.

### PERMANENT IMPLEMENTATION SCOPE
No code changes will be retained. All temporary harnesses and patches will be reverted.
