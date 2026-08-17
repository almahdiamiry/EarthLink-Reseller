# Task Brief: P1-05 — Enforce deterministic Firestore document identity

## Context & Project Fit
Per `P1-G2-REQ-04` and `INV-01`/`INV-13`, the system must enforce deterministic, stable Firestore document keys:
- 1:1 mapping between local entity IDs (`entityId`, `transactionId`, `accountId`) and Firestore document IDs.
- `syncMutationId` is purely for write correlation, NOT the document key.
- Lost-ACK retry updates the exact same cloud document (idempotent upsert), never creating duplicate documents or divergent shadow IDs.

## Implementation Targets
- `app/src/main/java/com/example/core/sync/SyncRepositoryImpl.kt` — verify and enforce canonical document path construction (`document(item.entityId)` across all entity collections: `accounts`, `ledger`, `import_batches`, `audit_logs`, `resellers`).
- `app/src/main/java/com/example/core/sync/OutboxManager.kt` — ensure `entityId` is strictly preserved and mapped 1:1 to outbox obligations.
- `app/src/main/java/com/example/core/sync/RemoteSyncCoordinator.kt` — ensure incoming remote events resolve against document ID == entity ID.
- `app/src/test/java/com/example/Phase1FirestoreDocumentIdentityTest.kt` — comprehensive unit test suite.
- `contract/invariant_contract.yaml`, `contract/invariant_test_map.yaml`, `contract/test_environment_matrix.yaml` — map `Phase1FirestoreDocumentIdentityTest`.

## Specific Requirements
1. Canonical Document ID Construction:
   - For all entity types (`account`, `ledger`, `import_batch`, `audit_log`), Firestore document reference MUST be `collection(collectionName).document(item.entityId)`.
   - Never use random UUIDs, timestamps, or client-side nonces as Firestore document IDs when syncing existing entity records.
   - `syncMutationId` is a field in the document payload for idempotency / server correlation, NOT the Firestore document ID itself.
2. Idempotent Retry / Lost-ACK Cloud Safety:
   - When a sync pass pushes an item with entityId `tx-123`, if network drops before acknowledgment (lost ACK), a subsequent retry will push to `document("tx-123")` again, updating the existing document rather than duplicating it.
3. Distinct Entities -> Distinct Documents:
   - Distinct transactions with different entity IDs always produce distinct document IDs.
4. Implement `Phase1FirestoreDocumentIdentityTest.kt` verifying:
   - 1:1 entityId -> documentId mapping across all collections;
   - Same local transaction over repeated sync passes targeting same document path;
   - Distinct transactions targeting distinct document paths;
   - Lost-ACK simulated retry updates the existing document;
   - `syncMutationId` separation from document identity.
5. Verification & Matrix Validation:
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_invariant_contract.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_test_environment_matrix.py`
   - `python scripts/run_verified_command.py --timeout 60 -- python scripts/scan_forbidden_patterns.py`
   - `python scripts/run_verified_command.py --timeout 300 -- .\gradlew.bat testDebugUnitTest`
6. Commit changes to git and write report to `.superpowers/sdd/EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5)/task-5-report.md`.
