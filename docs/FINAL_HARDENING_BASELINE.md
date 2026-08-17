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

# Final Hardening Baseline

- **Date:** 2026-08-12
- **Git Commit:** `aac25a8408dd6658c4b69d7185b76b2f1931dc21`
- **Build Status:** SUCCESSFUL (`compile_applet` passed)
- **Known Warnings:** None affecting compilation

## Core Architectural Inventories

### 1. Money Conversions (* 1000)
Direct money multiplication violations were identified in `SharedComponents.kt` lines 1277-1280:
```kotlin
currentPriceIqd = (price.toDoubleOrNull()?.times(1000.0)) ?: account.currentPriceIqd,
debtIqd = (debtLimit.toDoubleOrNull()?.times(1000.0)) ?: account.debtIqd,
loanIqd = (debtLimit.toDoubleOrNull()?.times(1000.0)) ?: account.debtIqd,
advanceIqd = (advanceBalance.toDoubleOrNull()?.times(1000.0)) ?: account.advanceIqd,
```
These will be refactored in Phase 1 to use `MoneyParser.normalizeUiInputToIqd()`.

### 2. Synchronization Entry Points & Listeners
- Realtime listeners currently attached in `SyncRepositoryImpl.kt` via Firestore `addSnapshotListener`.
- One-shot pull methods: `pullRemoteChanges()`, `bootstrapSync()`, `triggerSync()`.
- Unification into `RemoteSyncCoordinator` will occur in Phase 2.

### 3. Outbox and Audit Feedback Paths
- `OutboxDao`: Handles `PENDING`, `IN_FLIGHT`, `FAILED_RETRYABLE`, `DEAD_LETTER`, `SYNCED`.
- `AuditRepository`: System-generated failure audits checked to ensure no outbox creation.

### 4. Timestamp Semantics
- `updatedAt` / `createdAt` fields reviewed in `AccountEntity`, `LedgerEntity`, and `OutboxEntity`.
