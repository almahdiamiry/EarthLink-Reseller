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

# RemoteSyncCoordinator Documentation

This document describes the design, responsibilities, and architecture of `RemoteSyncCoordinator`.

---

## Overview

`RemoteSyncCoordinator` is the central ingestion pipeline for all remote sync events (both `REALTIME` snapshot listeners and `PULL` sync cycles). It eliminates duplicate synchronization pipelines and ensures deterministic, idempotent, and monotonic state application across all local Room database tables.

---

## Core Responsibilities

1. **Unified Event Ingestion**: Single pipeline processing both `REALTIME` and `PULL` events via `processEvent(event: RemoteEvent)`.
2. **Deduplication**: Drops duplicate remote events arriving within active sync sessions or across parallel channels.
3. **Active Local Mutation Guarding**: Rejects incoming remote updates for entities that have active in-flight or pending local outbox mutations (`OutboxManager.hasActiveMutation`), preserving uncommitted local edits.
4. **Deletion Ordering and Tombstone Protection**: Stores deletion tombstones (`tombstone:account:$id` and `tombstone:ledger:$id`) in `metadataDao` with remote versions. Prevents late-arriving ledger entries or stale account upserts from resurrecting deleted accounts or leaving orphaned records.
5. **Version Monotonicity**: Rejects stale remote events whose remote version is less than or equal to local state or tombstone versions.
6. **Zero-Outbox Remote Application**: Applying remote changes directly updates local Room tables without creating `SyncOutbox` records, breaking recursive sync feedback loops.
7. **Timestamp Preservation**: Reapplying remote updates or recalculating derived ledger balances updates local balances without mutating entity business `updatedAt` timestamps.
8. **Independent Per-Collection Watermarks (Data Loss Prevention)**: Tracks sync cursors strictly per collection (`last_sync_local_accounts`, `last_sync_local_ledger_entries`, `last_sync_import_batches`, `last_sync_audit_logs`). Prevents high timestamps in one collection (e.g. accounts at 5000) from advancing the query filter of another collection (e.g. ledgers at 1000) and missing valid entries. Seeding falls back cleanly to legacy global `last_sync_timestamp` if upgraded.

---

## Pipeline Flow

```text
REALTIME / PULL Remote Event
        ↓
RemoteSyncCoordinator.processEvent()
        ↓
Tombstone Check (metadataDao) -> [Tombstone Exists & Version <= Tombstone?] -> Discard (Zombie Protection)
        ↓
Active Outbox Mutation Check (outboxDao) -> [Active Mutation?] -> Ignore / Defer
        ↓
Version Monotonicity Check -> [Remote Version <= Local Version?] -> Discard Stale Event
        ↓
Apply to Local Room DAO (No Outbox Insertion)
        ↓
Recalculate Derived Balances (Preserving Business updatedAt)
```
