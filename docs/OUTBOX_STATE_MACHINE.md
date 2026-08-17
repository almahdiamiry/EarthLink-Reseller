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

# Outbox State Machine Specification

This document details the lifecycle, states, and transition rules for `SyncOutbox` records managed by `OutboxManager` and `SyncRepositoryImpl`.

---

## State Definitions

| State | Status String | Description |
|---|---|---|
| `PENDING` | `"pending"` | Newly created local mutation awaiting remote upload. |
| `IN_FLIGHT` | `"syncing"` | Currently being uploaded in an active sync HTTP chunk. |
| `FAILED_RETRYABLE` | `"failed"` | Remote upload failed due to transient network error; pending retry. |
| `DEAD_LETTER` | `"dead_letter"` | Terminal failure state after max attempts (10) or non-retryable 4xx error. |
| `SYNCED` | *(Deleted)* | Upload succeeded; record deleted from `sync_outbox` table. |

---

## Allowed State Transitions

```text
[Local Mutation] → PENDING
                       ↓
                   IN_FLIGHT (markInFlight)
                 /           \
     (Success)  /             \ (Transient Error, attempt < 10)
               v               v
            SYNCED      FAILED_RETRYABLE (markRetryableFailure)
          (Deleted)            │
                               │ (Re-fetched by getPending)
                               v
                           IN_FLIGHT
                               │
                               │ (Fatal Error OR attempt >= 10)
                               v
                          DEAD_LETTER (markDeadLetter)
```

---

## Strict Invariants

1. **Dead-Letter Isolation**:
   - `DEAD_LETTER` records are NOT returned by `OutboxManager.getPending()`.
   - `DEAD_LETTER` records do NOT trigger automatic retries.
   - `DEAD_LETTER` records are NOT counted as active mutations (`OutboxManager.hasActiveMutation` returns `false`), preventing dead-letter items from blocking incoming remote updates indefinitely.

2. **Single-Flight Coalescing**:
   - Concurrent sync requests coalesce into an atomic `pendingRunAfterCurrent` pass in `SyncRepositoryImpl.kt`.
   - Parallel or competing sync pipelines cannot process the same `PENDING` outbox records simultaneously.

3. **In-Flight Memory Safety**:
   - Items in `IN_FLIGHT` (`status = "syncing"`) are not returned by subsequent calls to `getPending()`.
