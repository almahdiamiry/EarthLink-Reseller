# SDD ledger — plan: EARTHLINK_PHASE1_5_COMPREHENSIVE_IMPLEMENTATION_PLAN(5).md

## Pre-flight Plan Scan
| Task / Interface | Consumes / Produces | Status / Finding | Ruling |
|---|---|---|---|
| P1-01: Freeze Phase-1 Allowlist | Baseline contracts & test corpus | Clean | Base contracts in sync with current repository |
| P1-02: Remove DEAD_LETTER | Models.kt, AppDatabase.kt, OutboxManager.kt, SyncRepositoryImpl.kt | Outbox durability invariant (INV-13 / P1-G2-REQ-01) | Remove terminal dead letter state, keep failed obligations durable with backoff |
| P1-03: Per-item isolation | SyncRepositoryImpl.kt, OutboxManager.kt | Item-level isolation (P1-G2-REQ-02) | Ensure poison items do not block valid queue items |
| P1-04: Explicit orphan handling | SyncRepositoryImpl.kt, OutboxManager.kt | Orphan classification (P1-G2-REQ-03) | Keep orphan obligations observable without silent drop |
| P1-05: Deterministic Firestore ID | SyncRepositoryImpl.kt, OutboxManager.kt | 1:1 entity ID mapping (P1-G2-REQ-04) | Document identity derived deterministically from entity ID |
| P1-06: Restore transport decision | BackupManager.kt, backup_state_classification.yaml | Transport reconstruction table (P1-G2-REQ-05) | Transport state reconstructed from business data |
| P1-07: G1 pending operation model | PendingExternalOperation, LocalLedgerRepository, ViewModels | G1 local-first durability (INV-11) | Local pending operation record before external call |
| P1-08: Lost-ACK / Room atomicity | SyncRepositoryImpl.kt, LocalLedgerRepository.kt | Atomicity proofs (P1-G2-REQ-06) | Proves atomic Room writes and lost-ACK idempotency |
| P1-09: Concurrent duplicate protection | ViewModels, LocalLedgerRepository.kt | Operation Intent ID (INV-11) | Single inflight intent per action |
| P1-10: Unknown-outcome resolution | PendingExternalOperation, LocalLedgerRepository.kt | Verification protocol (INV-11) | Authorized subscriber state inspection |
| P1-11: Same-ID divergent payload | SyncRepositoryImpl.kt, RemoteSyncCoordinator.kt | Immutability protection (INV-01) | Reject divergent payloads with identical transaction ID |
| P1-12: Two-device convergence fixture | Tests, Multi-device simulation | Convergence invariant | Cloud convergence without transaction loss |
| P1-13: Phase-1 exit gate | Evidence collection, compliance matrix | Gate verification | Machine-verified exit gate |

## Task Execution Status
- [x] Task P1-01: Freeze the Phase-1 working allowlist and rebuild current test identity
- [x] Task P1-02: Remove terminal DEAD_LETTER semantics from the outbox
- [x] Task P1-03: Convert chunk processing to per-item failure isolation
- [x] Task P1-04: Implement explicit orphan handling
- [x] Task P1-05: Enforce deterministic Firestore document identity
- [x] Task P1-06: Define and implement the Restore/Backup transport reconstruction decision table
- [x] Task P1-07: Implement G1 pending-operation durability and call-path integration
- [x] Task P1-08: Room atomicity and Lost-ACK idempotency proof
- [x] Task P1-09: Concurrent duplicate-initiation protection
- [ ] Task P1-10: Unknown-outcome verification/resolution protocol
- [ ] Task P1-11: Same-ID divergent-payload immutability protection
- [ ] Task P1-12: Two-device convergence fixture and proof
- [ ] Task P1-13: Phase-1 evidence collection and gate closure
