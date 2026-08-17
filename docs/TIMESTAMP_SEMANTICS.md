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

# Explicit Timestamp Semantics

This document defines the official timestamp semantics and conflict resolution rules for the Earthlink Reseller App synchronization engine.

---

## Timestamp Definitions

| Field Name | Type | Mutability | Description |
|---|---|---|---|
| `createdAt` | Epoch MS (`Long`) | Immutable | The system timestamp when the entity record was created initially. Never updated after creation. |
| `businessUpdatedAt` (`updatedAt`) | Epoch MS (`Long`) | Mutable | The domain business mutation timestamp set ONLY when user action or business operations modify entity state locally. |
| `occurredAt` | Epoch MS (`Long`) | Immutable | Authoritative transaction execution timestamp for ledger entries indicating when the financial event occurred. |
| `remoteUpdatedAt` / `serverTimestamp` | Epoch MS (`Long`) | Server-Assigned | Authoritative timestamp assigned by Firestore/Backend server upon transaction commitment. |
| `derivedCalculatedAt` | Transient | Local Only | Internal execution timestamp for client-side balance recalculations. Recalculations **MUST NOT** mutate `businessUpdatedAt`. |
| `syncCursor` (`last_sync_timestamp`) | Epoch MS (`Long`) | Cursor State | High-water mark timestamp stored in `metadataDao` representing the latest server-confirmed state synced locally. |

---

## Conflict Resolution Rules

### Rule 1: Zero Artificial Timestamps on Remote Apply
During remote apply (both pull and realtime snapshot), incoming entities **MUST NOT** set `updatedAt = System.currentTimeMillis()`. They must preserve the remote or business timestamp to prevent local changes from appearing newer than they are.

### Rule 2: Explicit Timestamp Comparison
When resolving modification conflicts in `SyncConflictResolver`:
- For `local_accounts`: compare `local.updatedAt` against `remote.updatedAt`.
- For `local_ledger_entries`: compare `local.createdAt` (or `local.occurredAt`) against `remote.updatedAt`.
- **CRITICAL**: Never compare `local.createdAt` against `remote.updatedAt` for mutable entities like accounts to determine modification conflict winners.

### Rule 3: Tie-Breaking Policy
When `effectiveRemoteTimestamp == localTimestamp`:
- The remote state is authoritative and wins to guarantee data convergence across all connected client nodes.
