# Canonical Ledger Creation & Identity Inventory Map (P4-01 / G5 Identity)

| Creation Path | Source Event / Data | ID Generation Rule | Local Storage Key | Firestore Document Key | Retry Behavior | Migration / Recovery Behavior |
|---|---|---|---|---|---|---|
| **Local Debt** (`addTransaction`) | User manual debt entry | Caller ID or `UUID.randomUUID()` | `local_ledger_entries.id` | `local_ledger_entries/{id}` | Idempotent on same ID; new ID creates new record | Preserved exactly |
| **Payment / Settlement** (`recordPayment`) | User payment entry | Caller ID or `pay_${ts}_${uuid8}` | `local_ledger_entries.id` | `local_ledger_entries/{id}` | Idempotent on same ID; divergent payload rejected (INV-01) | Preserved exactly |
| **Activation** (`confirmActivation`) | G1 Activation ISP Gateway | Caller `bizTxId` or `act_${ts}_${uuid8}` | `local_ledger_entries.id` | `local_ledger_entries/{id}` | Intent deduplicated via PendingExternalOperation | Materialized atomically with G1 record |
| **Renewal / Extension** (`confirmRenewal`) | G1 Renewal ISP Gateway | Caller `bizTxId` or `ren_${ts}_${uuid8}` | `local_ledger_entries.id` | `local_ledger_entries/{id}` | Intent deduplicated via PendingExternalOperation | Materialized atomically with G1 record |
| **Quick Refill** (`quickRefill`) | G1 Refill ISP Gateway | Caller `bizTxId` or `refill_${ts}_${uuid8}` | `local_ledger_entries.id` | `local_ledger_entries/{id}` | Intent deduplicated via PendingExternalOperation | Materialized atomically with G1 record |
| **uTower Import (with ID)** | uTower record with `id` / `sourceExternalId` | Preserved explicit `sourceExternalId` | `local_ledger_entries.id` | `local_ledger_entries/{id}` | Idempotent on re-import | `isSnapshotHistory=1` |
| **uTower Import (Fallback)** | uTower record without explicit ID | Deterministic `import_${batchId}_${accId}_${occurrenceIndex}` | `local_ledger_entries.id` | `local_ledger_entries/{id}` | Deterministic on re-import of same file | Preserved across restarts |
| **Restore Merge** | Backup ZIP JSON/SQLite archive | Preserved backup `tx.id` | `local_ledger_entries.id` | `local_ledger_entries/{id}` | Same ID deduplicated; divergent rejected | Rebuilt with deterministic position |
| **Cloud Re-sync / Pull** | Firestore incoming `LedgerUpsert` | Preserved Firestore `documentId` (`entityId`) | `local_ledger_entries.id` | `local_ledger_entries/{id}` | Idempotent update; no echo outbox | Tombstone tracked on delete |
