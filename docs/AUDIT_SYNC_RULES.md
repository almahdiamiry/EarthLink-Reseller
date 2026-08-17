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

# Audit & Sync Classification Rules

This document specifies the audit classification system and its isolation rules relative to the synchronization outbox.

---

## Audit Classification System (`AuditOrigin`)

Audit logs are strictly categorized into explicit origins:

1. `USER_ACTION`: Triggered directly by explicit user interaction in the UI (e.g., manual account edits, new ledger creation, manual sync request).
2. `SYSTEM_ACTION`: Internal maintenance, database migrations, local cleanup, background health checks.
3. `SYNC_EVENT`: Routine synchronization progress notifications and completion logs.
4. `SYNC_FAILURE`: Errors encountered during remote API communication or outbox processing.
5. `RESTORE_EVENT`: Local database backup or restore operations.
6. `MIGRATION_EVENT`: Schema migration or batch import lifecycle events.

---

## Outbox Isolation Policy

To prevent infinite synchronization feedback loops, the following strict rule is enforced in `AuditRepositoryImpl`:

```kotlin
val isEligibleForSync = (origin == AuditOrigin.USER_ACTION)
```

### Invariants:
- **`USER_ACTION`**: Generates a `SyncOutbox` record (`entityType = "audit_logs"`) and triggers sync execution.
- **`SYSTEM_ACTION`, `SYNC_EVENT`, `SYNC_FAILURE`, `RESTORE_EVENT`, `MIGRATION_EVENT`**:
  - Inserted ONLY into local `audit_logs` database table.
  - **NEVER** inserted into `SyncOutbox`.
  - **NEVER** triggers `syncRepo.triggerSync()`.

---

## Echo / Feedback Loop Protection

```text
Sync Fails permanently
        ↓
Audit Logged (origin = SYNC_FAILURE)
        ↓
Stored in local audit_logs table
        ↓
isEligibleForSync == FALSE
        ↓
NO Outbox Record Created
        ↓
NO Sync Triggered
        ↓
System Remains Stable (Loop Prevented)
```
