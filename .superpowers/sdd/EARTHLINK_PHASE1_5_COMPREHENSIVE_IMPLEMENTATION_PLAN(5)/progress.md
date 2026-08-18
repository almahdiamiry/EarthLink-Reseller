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
- [x] Task P1-07: Implement G1 pending-operation durability and call-path integration (Commit: `37cf26b32b6f9e52bf2304bd6a5e8b811d853e2a`)
- [x] Task P1-08: Room atomicity and Lost-ACK idempotency proof (Commit: `726258d77f6954916c1febacc90ff0f51d9c7809`)
- [x] Task P1-09: Concurrent duplicate-initiation protection (Commit: `5551bf25c1326da0443770f35b56dfac141a3d36`)
- [x] Task P1-10: Unknown-outcome verification/resolution protocol (Commit: `6b65fb5`)
- [x] Task P1-11: Same-ID divergent-payload immutability protection (Commit: `b0ad87d360c3e46bea43c5b98cb084b0c4177f3d`)
- [x] Task P1-12: Two-device convergence fixture and proof (Commit: `a97c9ef59d77f4c1e9c070478bfcf1ef84809497`)
- [x] Task P1-13: Phase-1 evidence collection and gate closure (Commit: `3121a271862860c00b87a7b41b6e6a1a5974a86e`)

### Phase 2: G3 Restore & Import
- [x] Task P2-01: Define the final Restore/Import business transaction boundary
- [x] Task P2-02: Implement Restore Merge as a complete-lineage decision operation
- [x] Task P2-03: Make current-position reconstruction deterministic
- [x] Task P2-04: Harden Restore Replace
- [x] Task P2-05: Harden Import as Direct Atomic Room
- [x] Task P2-06: Restore/Import transport reconstruction
- [x] Task P2-07: Phase 2 evidence and provisional exit gate

### Phase 3: G4 Concurrency & Lineage
- [x] Task P3-01: Add persisted G4 generation state (Commit: `2f3eec9`)
- [x] Task P3-02: Capture lineage at remote operation start and validate in the same transaction (Commit: `7a275c8`)
- [x] Task P3-03: Advance generation on full replacement/clear only
- [ ] Task P3-04: Validate normal mutations as same-lineage
- [ ] Task P3-05: Bind generation to the final Restore/Import transaction boundary
- [ ] Task P3-06: Align lock order and eliminate network-under-lock
- [ ] Task P3-07: Add the full two-device concurrent operation fixture
- [ ] Task P3-08: Phase-3 evidence collection and gate closure
