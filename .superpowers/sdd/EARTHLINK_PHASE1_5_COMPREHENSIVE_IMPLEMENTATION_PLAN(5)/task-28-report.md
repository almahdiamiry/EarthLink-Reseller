# Task Execution Report: Tasks P4-01 to P4-04 (G5 Identity & Import Collision Safety)

## 1. Executive Summary
- **Tasks**:
  - P4-01: Inventory every ledger-creation path (`contract/ledger_identity_inventory.md`)
  - P4-02: Fix source-row identity fallback in uTower importer (`UtowerImporter.kt`)
  - P4-03: Reconcile deduplication semantics with identity semantics (`TransactionDeduplicator.kt`)
  - P4-04: Preserve runtime idempotency-key identity (`Repositories.kt`, `Phase4RuntimeLedgerIdentityTest.kt`)
- **Status**: DONE (ALL REQUIREMENTS PASS)
- **Governing Spec**: `EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5).md` Section 7 (Tasks P4-01 to P4-04)
- **Target Invariants**: `INV-01`, `INV-05`, `INV-06`, `INV-11`
- **Test Suite**: `Phase4RuntimeLedgerIdentityTest.kt` (7/7 tests pass)
- **Full Test Matrix**: `testDebugUnitTest` 256/256 tests PASSING (0 failures, 0 skipped).

---

## 2. Technical Implementation Details

### Task P4-01 (Ledger Creation Path Inventory)
- Documented in `docs/authority/ledger_identity_inventory.md` covering all 10 creation and retry paths:
  - Local Debt (`addDebt`)
  - Payment (`addPayment`)
  - ISP Activation, Renewal, Refill
  - uTower Import (with explicit ID and fallback provenance)
  - Restore Merge
  - Firestore Realtime & Pull Ingestion

### Task P4-02 (Source-Row Identity Fallback)
- In `UtowerImporter.kt`:
  - When historical rows lack explicit `sourceExternalId`, a deterministic provenance coordinate is assigned:
    `sourceExtId = "import_${batchId}_${transactionsRead}"`
  - Seed for transaction UUID: `tx_${accountId}_${sourceExtId}`
  - Satisfies: `same source artifact + same source row -> same ID` & `distinct legitimate source rows -> distinct IDs`.

### Task P4-03 (Deduplication Reconciled with Identity)
- In `TransactionDeduplicator.kt`:
  - If a transaction has an explicit or provenance `sourceExternalId`, it checks ONLY `existingByExtId`.
  - Fallback matching (`matchKey`) only executes when `sourceExternalId` is absent, preventing legitimate identical rows (same timestamp/amount/note) from being falsely collapsed.

### Task P4-04 (Runtime Idempotency Key Identity)
- In `LocalLedgerRepositoryImpl` (`Repositories.kt`):
  - Idempotency key reuse returns existing row without creating duplicate ledger entries or mutating balances.
  - Divergent payload with same key throws `DivergentPayloadConflictException` (INV-01).
  - Concurrent invocations with same key produce exactly 1 ledger record.

---

## 3. Machine Evidence & Verification Proofs
- `verify_invariant_contract.py`: Exit Code 0 (PASS)
- `verify_test_environment_matrix.py`: Exit Code 0 (PASS)
- `scan_forbidden_patterns.py`: Exit Code 0 (PASS - 0 Violations)
- `Phase4RuntimeLedgerIdentityTest`: 7/7 tests PASS
- `testDebugUnitTest`: 256/256 tests PASS (Exit Code 0)
