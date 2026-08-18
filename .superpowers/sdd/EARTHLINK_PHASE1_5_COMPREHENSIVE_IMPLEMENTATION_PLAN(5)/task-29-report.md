# Task P4-05, P4-06, P4-07 Execution Report

## 1. Executive Summary
- **Tasks**:
  - **Task P4-05**: Preserve identity across Restore Merge and Firebase (`INV-01`, `INV-05`, `INV-11`).
  - **Task P4-06**: Adversarial identity fixture and historical-source preservation (`P4-G5-REQ-01`, `P4-G5-REQ-03`, `INV-01`, `INV-05`, `INV-11`).
  - **Task P4-07**: Prove source identity plus immutable-content integrity (`P4-G5-REQ-01`, `P4-G5-REQ-02`, `P4-G5-REQ-04`, `INV-01`, `INV-05`, `INV-06`, `INV-11`).
- **Status**: `COMPLETED_AND_VERIFIED`
- **Invariants Certified**: `INV-01`, `INV-05`, `INV-06`, `INV-11`

---

## 2. Implementation Details

### A. Deterministic uTower Import Batch Identity & Idempotent Re-Import
- Updated `UtowerImporter.kt` (`importFromFile` and `importFromPreview`) to derive `batchId` deterministically from the SHA-256 file content hash (`calculateHash(sourceFile)` / `fileHash`) prior to session instantiation:
  `val existingBatch = appDatabase.importBatchDao().getByFileHash(hash)`
  `var batchId = existingBatch?.id ?: UUID.nameUUIDFromBytes(hash.toByteArray(Charsets.UTF_8)).toString()`
- Ensured `session.batchId` is identical from the start of streaming parsing through database publishing.
- Re-importing identical uTower files produces 100% identical transaction IDs and 0 duplicate rows.

### B. Restore Merge & Remote Event Idempotency (Task P4-05)
- Verified `BackupManager.executeRestoreMergeInternal`:
  - Same transaction ID in live and backup resolves to 1 logical transaction when payload is identical.
  - Different transaction IDs are cleanly preserved across both datasets.
  - Same transaction ID with divergent payload strictly throws `DivergentPayloadConflictException` unless explicitly resolved.
- Verified `RemoteSyncCoordinator.processEvent`:
  - Inbound ledger upserts map document key directly to local ledger ID (`entityId`).
  - Replaying identical cloud events is idempotent (`SKIPPED_DUPLICATE`).

### C. Adversarial Counterexample & Immutable History Preservation (Task P4-06 & P4-07)
- Created adversarial test fixture:
  - Account with two distinct source rows sharing identical amount, date, transaction type, and comment, but no explicit `sourceKey`.
  - Proved that `rowA ID != rowB ID` (distinct provenance coordinates).
  - Proved that re-importing the same file produces `rowA re-import ID == rowA first ID` and `rowB re-import ID == rowB first ID` with 0 duplicate rows created.
- Proved that incoming remote event with same ID but divergent immutable payload is quarantined with `QUARANTINED_CONFLICT` and logged in `audit_log`, leaving local financial history completely unmodified.

---

## 3. Verification & Compliance Evidence
1. **Unit Test Suite**:
   - `Phase4IdentityIntegrityAdversarialTest.kt` (4/4 tests pass).
   - `Phase4RuntimeLedgerIdentityTest.kt` (7/7 tests pass).
2. **Contract & Matrix Validation**:
   - `python scripts/verify_invariant_contract.py` -> **PASS (Exit Code: 0)**
   - `python scripts/verify_test_environment_matrix.py` -> **PASS (Exit Code: 0)**
   - `python scripts/scan_forbidden_patterns.py` -> **PASS (0 Violations)**
   - `.\gradlew.bat testDebugUnitTest` -> **PASS (260/260 tests pass)**
