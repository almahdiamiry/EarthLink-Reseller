# EARTHLINK RESELLER V1
# Comprehensive Implementation Plan — Phases 1–5 (G2/G1 through G6/G7)

## 0. Plan status and governing procedure

This is one integrated implementation plan for the five implementation workstreams, with explicit separation between implementation order and closure dependencies:

```text
IMPLEMENTATION ORDER

Phase 1 — G2 / Transport + G1 Durability Lane
        ↓
Phase 2 — G3 / Restore & Import
        ↓
Phase 3 — G4 / Concurrency & Lineage
        ↓
Phase 4 — G5 / Identity
        ↓
Phase 5 — G6/G7 / Semantics + Migration
        ↓
Phase 6 / G8 — later certification boundary only

CLOSURE DEPENDENCIES

G5 identity evidence ───────→ G2 final closure
G5 identity evidence ───────→ G3 final closure
G4 stale-result evidence ────→ G3 final closure
G5 identity preservation ────→ G7 final closure
G3 publication + G4 generation → integrated restore/lineage proof
```

The five phases are therefore an implementation sequence, not a claim that each gate can close independently at the end of its implementation phase.

Phase 6/G8 is not implemented by this plan. It remains the external certification boundary after Phases 1–5, followed by full adversarial verification, independent final audit, and production authorization decision.

The implementation plan follows the `obra/superpowers` writing-plans discipline for bite-sized, independently testable tasks and frequent commits/checkpoints, while the Earthlink-specific execution protocol below adapts those principles to the project's 480-second bounded agent constraint. The source skill explicitly calls for task-by-task execution, independently testable deliverables, and commit boundaries.

The planning protocol applied to this document is:

```text
DRAFT
  ↓
PRIMARY REVIEW
  ↓
SECONDARY REVIEW
  ↓
ADVERSARIAL / FROZEN-SPEC REVIEW
  ↓
FINAL REQUIREMENTS / CONSISTENCY CHECK
  ↓
FINAL PLAN
```

The Handover is treated as transferred Phase-0 context. No Phase-0 reopening is required because the current artifacts contain implementation contradictions/gaps against the frozen target, but no concrete contradiction that invalidates the frozen architecture itself. Those current implementation gaps are handled as implementation work, not as an architecture reset.

---

## 0.1 Agent Execution Protocol — Mandatory

This document is the implementation master plan, but **it must never be executed as one uninterrupted agent run**. The execution protocol below is mandatory for every Phase-1–5 implementation change. It operationalizes the writing-plans requirement for bite-sized, independently testable work and frequent commits/checkpoints. The upstream writing-plans skill explicitly requires task-by-task execution, independently testable task cycles, and commit boundaries; this protocol adapts those principles to the Earthlink 480-second execution constraint without changing product/architecture authority.

### Hard execution rules

```text
ONE AGENT RUN
    ↓
ONE BOUNDED EXECUTION PACKET
    ↓
ONE TEST / VERIFICATION CYCLE
    ↓
CHECKPOINT OR STOP
```

1. **Never ask an agent to execute the complete master plan in one run.** The plan is consumed packet-by-packet.
2. **Default packet boundary = one named plan task.** A task may be split into smaller micro-steps when it cannot safely fit into one bounded run; the split is procedural only and must not change the task's acceptance criteria.
3. **No packet may begin without a verified baseline identity:** current source/tree identity, current test-corpus identity, applicable allowlist, and the current clean/known working-tree state.
4. **No new implementation path may be modified before it is in the frozen task allowlist.**
5. **Each packet must end with targeted verification and a checkpoint.** A checkpoint is a trusted local commit or equivalent exact working-tree snapshot containing the packet's changed files, test result, and evidence identity.
6. **Any blocking failure is stop-the-line.** Do not advance to another task, weaken a test/contract/validator, broaden scope, or hide the failure by reclassifying it as historical.
7. **A failed packet may be retried only from the preserved failure state or from an explicitly created corrective checkpoint.** The failure record must name the exact command, result, changed paths, and relevant artifact/environment identity.
8. **Passing one packet does not unlock the next gate automatically.** Phase/gate transitions remain subject to the closure topology in Section 1 and the phase evidence gates in Section 10.
9. **A packet may stop before the 480-second execution boundary whenever its targeted test cycle and checkpoint are complete.** Throughput is subordinate to correctness.
10. **No agent may silently cross a phase boundary.** The next phase/packet is started only after the preceding packet's required checkpoint and gate conditions are satisfied.

### Mandatory packet lifecycle

For every packet, the executor must perform this exact lifecycle:

```text
PRECHECK
  → verify baseline identity
  → verify packet allowlist and prerequisites

IMPLEMENT
  → make only packet-scoped changes

VERIFY
  → run the smallest targeted failing/current test first
  → implement/fix
  → run targeted test(s) again
  → run required compile/build check for the touched source set

CHECKPOINT
  → capture changed-file list
  → capture source/build/test/environment identity
  → create checkpoint commit/snapshot

STOP
  → return control; do not silently consume the next packet
```

The plan-document reviewer procedure also requires review after the complete plan is written and focuses on completeness, spec alignment, and task decomposition; this execution protocol is the operational counterpart that enforces those task boundaries during implementation.

### Stop-the-line conditions

Execution must stop immediately when any of the following occurs:

- a required test, compile, instrumentation, or validator command fails;
- the executor discovers a file/function/path not covered by the current packet allowlist;
- the implementation requires a new architecture mechanism not already authorized by the frozen architecture;
- a requirement or invariant appears contradictory to the current task;
- the current source/build/test identity differs from the packet's recorded baseline without an explicit new checkpoint;
- a proposed workaround would weaken or suppress a test, validator, contract, invariant, or evidence rule;
- the packet exceeds its safe scope and cannot finish with a trustworthy checkpoint in the current run.

### Checkpoint record

Every successful packet checkpoint must record, at minimum:

```text
packet ID
task ID
baseline source/tree identity
resulting checkpoint identity
changed file list
targeted tests and exact results
compile/build result
test-corpus identity
toolchain identity
execution environment identity
external fixture/config identity where applicable
blocking/non-blocking observations
next packet ID
```

### Execution packets

The following packet map keeps the single master plan intact while making agent execution bounded. Unless explicitly marked as a micro-step split, each packet corresponds to exactly one named task and its own test cycle.

| Phase | Packet | Tasks | Boundary |
|---|---|---|---|
| P1 | P1-A | P1-01 | Control-plane inventory, allowlist, test-corpus identity; no runtime changes before checkpoint |
| P1 | P1-B | P1-02 | Outbox state semantics only |
| P1 | P1-C | P1-03 | Per-item failure isolation only |
| P1 | P1-D | P1-04 | Orphan handling only |
| P1 | P1-E | P1-05 | Firestore/document identity only |
| P1 | P1-F | P1-06 | Restore transport decision table + current-source transport reconstruction; generation binding is deferred to P3-05 |
| P1 | P1-G | P1-07 | G1 durable pending-operation model and call-path integration; split into micro-steps if the DB/model/repository/ViewModel/UI boundary cannot fit one run |
| P1 | P1-H | P1-08 | Lost-ACK/current Room atomicity proof |
| P1 | P1-I | P1-09 | Concurrent duplicate-initiation protection |
| P1 | P1-J | P1-10 | Unknown-outcome verification/resolution |
| P1 | P1-K | P1-11 | Same-ID divergent-payload immutability |
| P1 | P1-L | P1-12 | Two-device convergence fixture and proof contract |
| P1 | P1-M | P1-13 | Phase-1 evidence/exit gate only |
| P2 | P2-A through P2-G | P2-01 through P2-07 | One packet per task; P2-07 is evidence-only and cannot modify runtime behavior |
| P3 | P3-A through P3-H | P3-01 through P3-08 | One packet per task; P3-05 is the binding point for persisted generation + restore lineage disposition |
| P4 | P4-A through P4-I | P4-01 through P4-09 | One packet per task; identity changes must not broaden into synchronization architecture |
| P5 | P5-A through P5-K | P5-01 through P5-11 | One packet per task; migration/destructive-restore work must stop at its own checkpoint after every destructive-step test |

### Resume protocol after timeout/interruption

When a run ends because of timeout, process termination, tool failure, or operator stop:

```text
1. Read the last checkpoint record.
2. Verify the checkpoint identity against the working tree.
3. Re-run only the packet's baseline/targeted verification necessary to establish state.
4. Resume from the explicit unfinished micro-step inside that packet.
5. Do not reopen completed packets unless their evidence identity is invalidated by a source change.
```

A timeout is not permission to continue in a different task, create a broader patch, or assume unverified partial work is correct.

### Development rollback boundary

This is distinct from runtime rollback. If a packet fails:

```text
packet baseline checkpoint
        ↓
failed working state preserved
        ↓
STOP
```

The executor may either repair from that failed state or restore to the packet baseline checkpoint and retry. It must not carry unverified partial edits into the next packet. Runtime restore/rollback requirements remain separately governed by the phase-specific tasks.

# 1. Frozen authority and non-regression baseline

## 1.1 Authority hierarchy

The following remain the controlling product/architecture authority:

1. `docs/authority/Target Product Contract v0.6.md`
2. `docs/authority/G1-G8 Consolidated Architecture Summary.md`
3. `docs/authority/Final Independent Adjudication Memo.md`

The following remain subordinate transition/implementation guidance:

4. `docs/authority/EARTHLINK_V1_HANDOVER.md`
5. `docs/authority/EARTHLINK_IMPLEMENTATION_HANDOVER_APPENDIX_v1.0.md`

Current source is implementation-state evidence. Current executable tests, instrumentation, build outputs, captured execution results, artifacts, and hashes are verification evidence. Historical plans/reports/manifests are not current proof merely because they say PASS or COMPLETED.

## 1.2 Implementation order versus gate closure topology

The current authority bundle establishes a linear implementation entry sequence but a non-linear cross-gate dependency graph. This plan preserves the implementation sequence while making gate closure dependency-aware.

```text
Phase 1 implementation
    ├── G1 durability evidence
    └── G2 transport evidence
             │
             └───────────────┐
                             ↓
Phase 2 implementation → provisional G3 evidence
                             │
Phase 3 implementation → G4 lineage/stale-result evidence
                             │
Phase 4 implementation → G5 identity evidence
                             │        │
                             │        ├── completes G2 identity dependency
                             │        └── completes G3 identity dependency
                             ↓
                     G3 FINAL CLOSURE

Phase 5 implementation → G6/G7 evidence
                             │
                             └── G5 identity-preservation evidence is required
                                 for G7 FINAL CLOSURE
```

A phase may therefore produce **provisional evidence** without claiming final gate closure. Final closure is granted only when the phase's own requirements and all explicitly declared upstream/downstream dependency evidence are bound to the same verified implementation artifact.

## 1.3 Frozen architecture

The implementation must remain within:

```text
Direct Atomic Room
+
short local business transactions
+
minimal maintenance exclusion
+
transactional G4 lineage invalidation
+
stable transaction identity
+
durable per-item outbox
+
lineage-aware Restore Merge
+
business-data-preserving migration
+
external machine-verifiable certification
```

Do not introduce merely to resolve ordinary implementation defects:

```text
dataset_id
published_dataset_id
staging database
identity registry
generic reconciliation engine
generic synchronization state machine
runtime governance registry
```

## 1.4 Three-Identity Contract — mandatory semantic separation

The implementation must keep three distinct identity domains. No field, helper, or test may silently use one identity as another domain's authority.

```text
1. Business Transaction ID
   = stable identity of one accepted ledger-producing business transaction.

2. Source Provenance ID
   = deterministic identity of a historical/import source row when no explicit
     source transaction ID exists; it is provenance, not a new runtime authority.

3. Operation Intent ID
   = application-created identity for one user/business action intent, generated
     once at the action boundary and reused across duplicate UI/coroutine submission,
     pending recovery, and retry handling.
```

The Operation Intent ID is **not** derived only from:

```text
account + operation + amount
account + package
createdAt/timestamp
```

Those values can identify two legitimate operations that happen to have identical business parameters at different times.

Required semantics:

```text
same user action / repeated tap / duplicate coroutine submission
    → same Operation Intent ID
    → one Business Transaction ID

new legitimate renewal later
    → new Operation Intent ID
    → new Business Transaction ID

historical import row without explicit source ID
    → Source Provenance ID
    → must not be reused as the runtime Operation Intent ID
```

`PendingExternalOperation` must persist the Operation Intent ID together with the Business Transaction ID so duplicate initiation can be suppressed before a second external side effect is issued. The current product architecture remains unchanged; this is an identity-contract clarification, not an identity registry.

## 1.5 External Verification Capability Gate — G1 feasibility precondition

The frozen product contract requires uncertain ISP outcomes to be resolved by reopening and inspecting actual subscriber state/evidence, while explicitly rejecting an autonomous external reconciliation engine. Before implementing P1-10, the current source must therefore establish the operation-specific verification capability that already exists in the accepted Earthlink/API workflow.

Current-source inspection shows the network surface contains normal subscriber lookup/detail and balance/status-style operations but no dedicated transaction-status-by-local-transaction-ID endpoint in the current `EarthlinkApiService`. Therefore the implementation must **not assume** a nonexistent operation-status API.

For each ledger-producing operation:

```text
Activation
Renewal / Extension
Refill
```

document the existing authoritative verification primitive and correlation inputs used to decide:

```text
SUCCESS
FAILURE
INCONCLUSIVE
```

The verification primitive may be the normal subscriber-state workflow defined by the Product Contract. It must be demonstrably authoritative enough to distinguish a completed operation from a non-completed one for the specific business operation.

Precondition gate:

```text
verification capability identified + executable fixture proven
    → P1-10 may proceed

capability absent / cannot distinguish outcome safely
    → STOP implementation
    → do not invent a status service, autonomous reconciliation engine,
      or user-only `mark completed` workflow
    → escalate as a product/authority feasibility decision
```

This preserves the accepted G1 bounded recovery limitation: if the complete app dataset is wiped before the pending obligation is materialized and no available backup/cloud recovery copy contains it, operation-specific accounting recovery cannot be guaranteed.

## 1.6 G2 Remote Durability Confirmation Contract

The phrase **remote durability confirmed** is a semantic contract, not a generic synonym for “the client call returned without throwing.”

For the current Firebase implementation, the accepted confirmation sequence must be explicit and executable:

```text
server write accepted
    ↓
server-side read-back using Source.SERVER
    ↓
not from cache
not pending local writes
valid server ordering/version marker
mutation correlation matches the submitted stable mutation identity
    ↓
REMOTE DURABILITY CONFIRMED
```

A client-side `batch.commit()` success with a lost/ambiguous response is not by itself proof of captured confirmation for the obligation lifecycle. The existing current-source server read-back/version-capture path must be normalized under this contract; any failure to obtain the confirmation keeps the transport obligation diagnosable/retryable according to G2 policy and must not fabricate confirmation.

This contract must be encoded in the machine requirements/test map and exercised by lost-ACK plus emulator/instrumentation tests. It does not turn Firestore into the business authority: Ledger remains business authority and the outbox remains transport only.

## 1.7 Liveness, capacity, security, and time-domain baseline contracts

### Outbox liveness

Durability alone is insufficient. The plan must prove the actual current scheduler path from source to execution, including:

```text
foreground/manual trigger
WorkManager one-shot trigger
periodic trigger
network-recovery trigger
process restart
network returning after idle/offline period
```

The current source uses `SyncWorker` and WorkManager one-shot/periodic scheduling. Phase 1 must preserve the existing minimal mechanism and make its trigger, retry, fairness, and restart behavior executable; it must not add a generic scheduler/state-machine architecture.

### Quantified bounds

Where the frozen authority says “bounded,” the implementation must use a machine-defined bound rather than prose. Bounds must exist for at least:

```text
retry/backoff ceiling
retry metadata footprint
error/diagnostic payload size
per-pass queue scan/page size
orphan diagnostic footprint
```

If the product authority deliberately leaves a numeric value open, the value must be established in an active machine contract/configuration surface before the implementation task closes; it may not remain an unbounded implementation constant.

### Fairness and storage safety

The outbox must prove:

```text
poison item does not hot-loop
valid items are not starved behind poison items
large retained obligation populations do not block scheduler progress
bounded diagnostics do not grow without limit
obligations are never deleted merely to relieve storage pressure
```

Stress evidence must cover a large retained failure population and a valid item placed behind it. The test must verify progress/fairness plus bounded metadata footprint, not merely absence of `DEAD_LETTER`.

### Direct Atomic Room capacity envelope

The Direct Atomic Room architecture remains frozen, but its feasibility must be demonstrated for the supported dataset scale. The plan must define, from current product/workload evidence:

```text
supported dataset size envelope
expected restore/import duration envelope
transaction memory/WAL/disk pressure envelope
acceptable background execution behavior
observable failure/timeout threshold
```

This is a feasibility/verification contract, not permission to add a staging database. Real measured evidence may reopen architecture only if the accepted Direct Atomic Room invariant cannot be satisfied within the measured target workload.

Long-term immutable financial-history retention is an explicit growth boundary, not an implicit promise of unlimited storage/query/backup performance. Phases 1–5 do not implement archival/deletion of financial history; they must record the supported growth assumption and treat archival as a later separately authorized concern.

### Secret/evidence handling

Credentials and other secrets must never appear in:

```text
application logs
error messages
checkpoint records
test output
evidence artifacts
screenshots/failure dumps
```

The test/evidence layer must use redacted fixtures and explicit secret-scrubbing assertions. Credential lifecycle proof must include at-rest protection, redaction, backup exposure classification, and cleanup/zeroization behavior required by the existing security surface.

### Time-domain table

Every persisted/currently interpreted time field must be classified by:

```text
field
producer
clock/domain
trust level
ordering meaning
allowed comparisons
```

At minimum include `createdAt`, `updatedAt`, `occurredAt`, `remoteVersion`/server timestamps, tombstone timestamps, `effectiveVersion`, retry scheduling timestamps, and any cursor coordinate. Device wall-clock values must not silently become authoritative remote ordering coordinates.

### State-domain boundaries — no implicit generic state machines

Each local state-bearing domain must have an explicit owner, persisted authority, allowed states, allowed transitions, and non-goals:

| Domain | State authority | Must not become |
|---|---|---|
| `PendingExternalOperation` | G1 durable local pending record + external verification result | generic external reconciliation engine |
| `SyncOutbox` | durable transport obligation + retry/diagnostic metadata | business ledger state machine or terminal dead-letter authority |
| G4 generation | single persisted local lineage value | generic sync/version registry |
| remoteVersion/tombstone/cursor | transport event ordering metadata | local lineage authority |
| restore decision | one deterministic validated decision object | persistent workflow/governance registry |

Local states may exist where the frozen semantics require them, but transitions must remain bounded to the owning invariant and must not silently form a generalized synchronization state machine.


## 1.8 Frozen business rules that every phase must preserve
### Financial ledger

- Ledger is immutable financial history.
- Only Activation, Renewal/Extension, and Refill create reseller financial ledger entries.
- ISP lookup/details/password/balance/status and other non-financial operations do not create ledger entries.
- Current position derives from accepted baseline + eligible ledger history.
- ISP-side disappearance/deletion does not physically delete local financial history.

### Protected legacy/history fields

Preserve unless independently classified:

```text
loanIqd
isLegacy
isSnapshotHistory
stateSource
stateConfidence
```

Do not delete, reset, repurpose, redefine, or promote these fields into a different financial authority.

### G1

For Activation/Renewal/Extension/Refill:

- stable local transaction ID generated once;
- durable local pending-operation record before the external operation;
- confirmed external success leads to durable local ledger/current-position/outbox materialization;
- uncertain result remains pending and is not blindly retried;
- recovery reuses the original transaction ID;
- Firebase availability is not a prerequisite for execution of the ISP operation;
- accepted complete-data-wipe limitation remains the bounded recovery limitation defined by the frozen authority.

### G2

- Ledger is business authority.
- Outbox is transport only.
- Each obligation remains durable until remote durability is confirmed.
- Retry/backoff and diagnostics are bounded.
- No terminal `DEAD_LETTER` business state.
- No retry-count deletion of a business obligation.
- Poison items are isolated; unrelated valid items continue.
- Orphaned outbox items are observable and retained, not silently deleted or converted into business data.
- Firestore document identity is deterministic and stable.
- Historical backup transport metadata is not current transport authority.
- Valid current obligations are not silently abandoned.
- Lost ACK must not duplicate the logical cloud transaction.

### G3

- Direct Atomic Room remains the V1 architecture.
- Parse/validate/decide outside the final Room business transaction.
- Restore Merge decisions are completed before final Room commit.
- No UI interaction, network wait, or external service call inside the final Room transaction.
- Restore Replace/Restore Merge must preserve baseline/history meaning.
- Current position is deterministically rebuilt from accepted baseline + eligible ledger history.
- Restore/Import reconstruct transport state from the resulting business state rather than blindly replaying historical transport metadata.
- Bulk interruption must roll back safely.

### G4

- Local generation is the authoritative lineage invalidation mechanism.
- Generation check + remote business-data application happen atomically in the same Room write transaction.
- Restore Replace, full dataset wipe, and sign-out with actual data clear create a new lineage.
- Normal local financial mutations are same-lineage and do not increment generation unless executable concurrency evidence requires invalidation.
- Network I/O/unbounded waits are forbidden while holding the relevant local business/maintenance locks.

### G5

- Runtime ledger IDs are stable UUID/idempotency IDs generated once and reused.
- Historical import rows with explicit source IDs preserve them.
- Historical rows without explicit source IDs use deterministic source provenance/row identity.
- Same source row → same identity.
- Distinct legitimate source rows → distinct identities.
- SQLite ROWID is not the stable source identity.
- Existing reliable IDs are preserved through migration/synchronization.

### G6/G7

- Field ownership must be explicit: ISP/server-owned, local reseller-owned, reminder-note/LWW, credential/session domain, derived financial fields, and legacy/history-only fields.
- Credentials remain a dedicated UID-scoped recovery domain.
- Cross-session/account leakage is prohibited.
- ISP-side deletion never physically deletes local financial history.
- `ON DELETE CASCADE` must not delete financial ledger history.
- Migration is business-data preserving, interruption-safe, and identity-preserving.
- Old backup import/export remains compatible across application versions in the supported matrix.

---

# 2. Exact current-artifact findings that drive the plan

The plan is based on the supplied current source tree, not on historical ZIP-71 or older completion reports.

## 2.1 Verification baseline drift is real

The current source snapshot must be treated as a **multi-source-set test corpus**, not as `app/src/test` alone. The inventory baseline therefore includes:

```text
app/src/test/**
app/src/androidTest/**
Gradle test/instrumentation source-set configuration
Gradle task-to-source-set mapping
contract/invariant_test_map.yaml
contract/test_environment_matrix.yaml
```

Historical completion JSON, historical compliance matrices, and older manifests may reference test classes that are not present in the current source snapshot. Those references remain stale identity/evidence until recreated and executed from the current source tree.

This is a mandatory Phase-1 control-plane prerequisite and remains true throughout all phases.

## 2.2 Cross-phase identity contract freeze

Because the frozen architecture makes G5 identity foundational to G2 cloud idempotency and G3 Restore Merge, Phase 1 must freeze the **identity contract** before implementation uses it, without implementing G5's historical-source repair early.

The Phase-1 identity contract is limited to these already-frozen semantics:

- a logical ledger transaction has one stable transaction identity;
- the same logical transaction maps to the same Firestore document identity;
- distinct legitimate historical source rows must not be collapsed merely because their business fields are identical;
- historical explicit source IDs are preserved;
- fallback identity for rows without source IDs must be deterministic from source provenance rather than SQLite ROWID.

The Phase-4 G5 implementation remains the workstream that repairs and proves the historical import/source-identity implementation. Phase 1 may consume the contract but must not create an identity registry or move G5 implementation scope forward.

## 2.3 Phase 1 current defects

### Outbox still contains terminal DEAD_LETTER semantics

Current source still contains:

- `SyncRepositoryImpl.getDeadLetterCount()`;
- `SyncRepositoryImpl.retryDeadLetters()`;
- `SyncOutboxDao.getDeadLetterCount()`;
- `SyncOutboxDao.getDeadLetters()`;
- `SyncOutboxDao.resetDeadLetters()`;
- `OutboxManager.markDeadLetter()`;
- `SyncRepositoryImpl.executeSyncPassInternal()` which sends items to `dead_letter` after ten attempts.

This directly contradicts P1-G2-REQ-01 and INV-13’s durable retryable-obligation rule.

### Outbox processing is still chunk coupled

Current `executeSyncPassInternal()` groups/deduplicates and processes chunks; a chunk-level exception drives failure handling for the entire `latestItemsInChunk` list. The implementation therefore requires explicit per-item isolation rather than relying on the current batch exception path.

### Lost-ACK path is not yet sufficient as business proof

The current write uses `document(item.entityId)` and therefore has the right identity shape, but proof still requires a retry/interruption test that demonstrates:

```text
local transaction ID
→ same Firestore document ID
→ write succeeds
→ ACK lost
→ retry
→ one logical cloud document
```

### Room atomicity is not yet proven for G1/G2

Current repository methods use Room transactions, but the plan must create executable failure-injection tests proving the local ledger/current-position/outbox boundary is atomic.

## 2.3 Phase 1 current G1 defect

The current renewal path in `EarthlinkSearchViewModel.refillUser()` performs the external ISP operation first and calls a success callback afterward. The callback in `UserDetailScreenV2.kt` then calls `LocalLedgerRepository.recordAccountRenewal()`.

That means the current source has a real gap between:

```text
external ISP success
```

and:

```text
durable local accounting obligation
```

There is currently no durable pending-operation record that survives process interruption across this boundary.

The G1 plan therefore introduces only the minimum allowed pending-operation mechanism, not a general reconciliation system.

## 2.4 Phase 2 current G3 gaps

`BackupManager.restoreBackupZip()` currently performs a direct snapshot replacement in one final Room transaction and clears operational sync state, but:

- there is no lineage generation field/mechanism yet;
- Restore Merge is not present as the frozen minimum behavior;
- current-position rebuild semantics require explicit deterministic proof;
- transport reconstruction needs executable proof that historical backup outbox/cursor metadata is not blindly replayed while current valid obligations are preserved/re-established.

## 2.5 Phase 3 current G4 gaps

`DataOperationCoordinator` exists and already provides mutual exclusion with coroutine ownership tokens, but `AppDatabase`, `Models`, and `RemoteSyncCoordinator` do not currently implement the frozen G4 generation model.

The plan therefore adds the minimum persisted local generation and same-transaction stale-result checks. The existing coordinator remains the exclusion mechanism; no new synchronization state machine is introduced.

## 2.6 Phase 3 current financial-history deletion defect

Current `RemoteSyncCoordinator.applyAccountDelete()` physically deletes all child ledger rows and then the account.

Current `RemoteSyncCoordinator.applyLedgerDelete()` physically deletes the local ledger row.

Current `LocalLedgerEntry` has `onDelete = ForeignKey.CASCADE` against `LocalAccount`.

The frozen adjudication explicitly forbids these production behaviors. Phase 5 must remove them and prove that developer-only destructive tools are separate from ISP-side deletion.

## 2.7 Phase 4 current G5 identity defect

`UtowerImporter` currently creates a deterministic fallback ID from:

```text
tx_${accountId}_${sourceKey-or-nokey}_${occurredAt}_${amountIqd}_${typeNormalized}
```

When `sourceKey` is absent, two legitimate source rows with the same account, timestamp, amount, and type can collapse to the same destination ID. This is exactly the frozen G5 counterexample.

`TransactionDeduplicator` also uses timestamp + amount + type + note as the fallback duplicate key. That behavior must be reviewed against the source-row/provenance identity rule rather than treated as authoritative simply because a deduplicator already exists.

## 2.8 Phase 5 current G6/G7 gaps

Current `LocalAccount` still contains all protected fields, but there is no machine-proven field ownership map for the complete mutable account/profile surface.

Credential/session handling is present in `PreferenceManager` and settings sync, but Phase 5 must explicitly prove UID/session isolation under delayed/asynchronous responses.

Database schema version is currently `11`. The current `LocalLedgerEntry` entity still declares `ON DELETE CASCADE`, while an earlier migration (`MIGRATION_5_6`) also creates a ledger table using `ON DELETE CASCADE`. Migration work must therefore remove destructive cascade semantics without deleting existing history.

The current backup format is effectively a database snapshot plus `backup_info.json`; backwards compatibility must be verified across supported prior application versions, not assumed from current parsing logic.

## 2.9 Execution-environment evidence limitation

Attempting to run:

```text
./gradlew :app:testDebugUnitTest --no-daemon
```

from the supplied snapshot did not reach test execution because Gradle Wrapper attempted to download Gradle 9.3.1 and the environment returned `UnknownHostException` for `services.gradle.org`.

This is not product evidence and not a source defect. The final implementation process must perform real test execution in an environment where the pinned build toolchain is available. No phase may close from historical `xxx/xxx passed` text alone.

## 2.10 Red-team findings added in Amendment Round 2

The second independent destruction review identified four blocking correctness gaps and several additional high-risk scenarios. These are treated as implementation/verification gaps inside the frozen architecture, not as grounds to reopen architecture.

### Two-device incomplete-cloud convergence

The plan must prove that two independent devices sharing the same Firebase identity can converge when cloud state is incomplete: Device A contains `T1 + T2`, Device B contains `T1 + T3`, cloud initially contains only `T1`, and both devices reconnect/retry. The required result is preservation of `T1`, `T2`, and `T3` exactly once, with no overwrite-based loss and no current-position regression.

### Duplicate initiation before identity creation

Stable transaction identity protects replay of one logical transaction, but it does not by itself prove that two concurrent UI invocations for the same logical ISP operation cannot create two external operations. The plan therefore adds an explicit same-input concurrent-initiation proof for Activation/Renewal/Extension/Refill at the current ViewModel/repository entry points.

### Complete unknown-outcome resolution protocol

The frozen G1 boundary is not satisfied by merely leaving an operation pending. Unknown external outcomes require an executable, verification-based resolution path with four cases: verified success, verified failure, still inconclusive, and process restart before resolution. No blind retry and no user-only `mark completed` path is allowed.

### Same-ID / divergent immutable payload protection

Stable identity must also protect immutable financial meaning. If the same transaction/document ID is presented with a materially different financial payload, the system must not apply last-write-wins semantics to historical ledger data. The plan therefore adds a conflict/rejection/quarantine proof using the existing transport/invariant surfaces rather than a new reconciliation engine.

### Restore lineage attachment rule

Restore transport reconstruction cannot decide validity from entity ID existence alone. A retained obligation must be attached to the resulting business lineage and its semantic identity, not merely to an account ID that happens to survive a restore. The integrated G3/G4 proof therefore binds obligation disposition to generation/lineage semantics.

### Ledger-producing ISP operations versus ordinary local ledger activity

The Product Contract distinguishes subscriber-ledger activity such as debt, payment/settlement, and advance/prepayment from the narrower rule that only Activation, Renewal/Extension, and Refill create a reseller subscriber-charge entry as part of an ISP operation. This plan adopts that exact distinction: the G1/G2 boundary governs ISP-driven subscriber-charge operations, while ordinary local debt/payment/advance/correction ledger activity remains supported and receives stable identity on its own creation paths.

---

# 3. Integrated implementation sequence and dependency map

The five implementation phases are one dependency chain, not five unrelated plans.

```text
Phase 1
G2 outbox semantics + G1 external-operation durability
        ↓
Phase 2
G3 Restore/Import + transport reconstruction
        ↓
Phase 3
G4 generation/lineage + stale-result rejection
        ↓
Phase 4
G5 stable historical identity
        ↓
Phase 5
G6 field/credential/deletion semantics + G7 migration
        ↓
G8 certification boundary only
```

Critical cross-phase dependencies:

```text
G1 stable transaction identity
    └── required by G2 lost-ACK idempotency

G2 stable cloud identity
    └── consumed by G3 transport reconstruction

G3 Restore/Import
    └── requires G4 lineage boundary
    └── requires G5 stable historical identity

G4 lineage
    └── must protect G3 remote/background work after full replacement

G5 identity
    └── must be completed before G7 migration identity conservation

G6 deletion/field semantics
    └── must be frozen before G7 schema/data migration

G7 migration
    └── must preserve outputs/identity/lineage assumptions required by G8
```

No later phase may be pulled forward merely to compensate for an unresolved earlier phase unless the dependency is explicit in the frozen contract.

---

# 4. Phase 1 — G2 Transport + G1 Durability Lane

## 4.1 Phase 1 objective

Replace the current terminal-loss/chunk-coupled outbox behavior with durable per-item transport semantics, while simultaneously closing the G1 external-operation-to-local-ledger durability boundary for Activation/Renewal/Extension/Refill.

Phase 1 must finish with executable proof for:

```text
P1-G2-REQ-01 … P1-G2-REQ-07
+
G1 stable identity / pending durability / uncertain-outcome recovery
```

## 4.2 Task P1-01 — Freeze the Phase-1 working allowlist and rebuild current test identity

### Implementation targets

- `contract/phase_requirements.yaml`
- `contract/invariant_contract.yaml`
- `contract/invariant_test_map.yaml`
- `contract/test_environment_matrix.yaml`
- `contract/forbidden_patterns.yaml`
- `scripts/scan_forbidden_patterns.py`
- `scripts/test_forbidden_pattern_registry.py`
- `scripts/verify_test_environment_matrix.py`
- `scripts/generate_and_verify_compliance_matrix.py`
- `app/src/test/**` current tree
- `app/src/androidTest/**` current tree
- Gradle test/instrumentation source-set configuration and task mapping

### Actions

1. Inventory current production/test files directly from the source tree.
2. Create a Phase-1 frozen allowlist before modifying new files.
3. Reconcile machine-contract metadata and semantics for P1-G2-REQ-01..07 so the contract points to current tests that actually exist or are created in this phase.
4. Do not mark missing historical tests as “historical” merely to obtain a gate PASS.
5. Keep active-guidance scans distinct from frozen-authority integrity scans and historical-reference scans.

### Verification

- Current source-derived test inventory exists.
- P1 requirement IDs have one current implementation target and one executable verification target.
- No historical completion JSON is used as the current identity source.

### Governance cleanup required before Phase-1 runtime implementation

As part of the Phase-1 control-plane inventory, reconcile current subordinate governance material against the frozen authority without reopening Phase 0 or changing the frozen architecture. Specifically:

- inspect `DESIGN_DECISIONS.md` `ADR-028`;
- classify or rewrite its `DataOperationCoordinator`-as-exclusive-channel wording so it cannot redirect implementation away from frozen `INV-11`;
- the resulting classification must be either an explicit `SUPERSEDED BY FROZEN AUTHORITY` marking or wording that matches the frozen invariant and clearly treats `DataOperationCoordinator` as an implementation mechanism rather than canonical architecture authority.

This is a governance-alignment preflight, not a new architecture decision and not permission to restore historical ADR authority.

### Definition of done

The Phase-1 task map is internally consistent with the actual current repository tree and frozen contracts before runtime implementation changes begin, and `ADR-028` cannot redirect an executor toward the superseded exclusive-`DataOperationCoordinator` rule.

## 4.3 Task P1-02 — Remove terminal DEAD_LETTER semantics from the outbox

### Implementation targets

- `app/src/main/java/com/example/core/model/Models.kt` — `SyncOutbox`
- `app/src/main/java/com/example/core/database/AppDatabase.kt` — `SyncOutboxDao`
- `app/src/main/java/com/example/core/sync/OutboxManager.kt`
- `app/src/main/java/com/example/core/sync/SyncRepositoryImpl.kt`
- `app/src/main/java/com/example/domain/repository/Interfaces.kt` — remove obsolete public dead-letter controls if no longer semantically valid
- `contract/forbidden_patterns.yaml`

### Actions

1. Remove `dead_letter` as an active transport state.
2. Remove `markDeadLetter()` and the DAO query/reset methods that make it a terminal business state.
3. Retain attempt count, last error, updated-at, and bounded next-attempt/backoff metadata needed for diagnostics and scheduling.
4. Keep failed obligations durable; no attempt threshold may delete the business obligation.
5. Ensure `getPending()`/equivalent retrieval includes all retryable obligations and excludes only successfully confirmed/safely cleared rows.
6. Replace the current user-facing `getDeadLetterCount()`/`retryDeadLetters()` concept with the surviving retry/diagnostic semantics, without introducing a second business state machine.

### Verification

Add `Phase1OutboxDurabilityTest.kt` covering:

- attempt count increase without obligation deletion;
- long-running failure keeps the row retryable;
- no `dead_letter` status can be produced;
- poison item remains isolated and observable;
- historical code patterns containing `DEAD_LETTER` inside rejected/frozen documentation are not falsely flagged by context-blind scanners.

Extend the forbidden-pattern registry with a context-sensitive production-code rule if required by the contract; do not rely on a raw repository-wide substring absence test.

### Definition of done

P1-G2-REQ-01 has source, behavioral, adversarial/registry, and evidence coverage and the production transport path has no terminal `DEAD_LETTER` business state.

## 4.4 Task P1-03 — Convert chunk processing to per-item failure isolation

### Remote durability confirmation rule used by P1-03/P1-05

A transport obligation is not considered durably confirmed merely because the Firebase batch `commit()` returns successfully. The accepted current-source confirmation sequence is:

```text
server write accepted
→ Source.SERVER read-back
→ no pending local writes
→ not cache-only
→ valid remote version/timestamp
→ submitted syncMutationId matches server document correlation
→ confirmation accepted
```

If the read-back/correlation step cannot prove remote durability, the obligation remains governed by the retry/diagnostic contract. The implementation must never mark business transport complete merely to suppress retries.


### Implementation targets

- `SyncRepositoryImpl.executeSyncPassInternal()`
- `OutboxManager.markInFlight()` / retry helpers
- `SyncOutboxDao` queries

### Actions

1. Preserve deterministic ordering where required, but establish each outbox obligation as its own failure unit.
2. A poison item must not cause valid neighboring items to remain blocked.
3. Retryable failure updates only the failed item.
4. A successfully committed item is removed only after the remote write is accepted according to the G2 confirmation rule.
5. `syncing` recovery after process death must return each in-flight obligation to a retryable state without duplication.
6. Do not create a generic queue-wide state machine.

### Verification

Add a focused behavioral suite with the exact required sequence:

```text
T1 valid / T2 poison / T3 valid
→ T1 succeeds
→ T2 retained with diagnostics/backoff
→ T3 succeeds
```

Also prove that an interrupted `syncing` item returns to retryable handling without impacting unrelated items.

### Definition of done

P1-G2-REQ-02 is independently executable: poison isolation is demonstrated at item granularity, not inferred from source shape.


### Liveness/fairness/capacity extension

The same packet must additionally prove the existing scheduler path remains live across:

```text
manual/foreground trigger
WorkManager retry
periodic execution
network recovery
process restart
```

Use a stress fixture with a large retained poison population and valid items behind it. The test must demonstrate that valid items make progress, poison items do not hot-loop, diagnostics remain within the machine-defined configured bound, and no obligation is deleted solely because the failure population is large. The actual configured numeric bounds must be captured in the evidence record.

## 4.5 Task P1-04 — Implement explicit orphan handling

### Implementation targets

- `SyncRepositoryImpl.executeSyncPassInternal()`
- `OutboxManager`
- `SyncOutboxDao`
- `RemoteSyncCoordinator` only where current outbox/entity checks intersect remote apply

### Actions

1. Detect an outbox item whose target local entity has been superseded or removed.
2. Classify the item as an orphaned transport obligation rather than silently deleting it.
3. Preserve diagnostics sufficient to identify entity type/id and last failure reason.
4. Ensure orphan items do not hot-loop indefinitely.
5. Do not turn orphan handling into a business-data authority or reconciliation engine.

### Verification

Add tests for:

- deleted local entity + pending outbox item;
- superseded local entity + older outbox item;
- orphan survives restart;
- orphan does not block unrelated valid obligations;
- orphan does not produce a ledger mutation.

### Definition of done

P1-G2-REQ-03 passes from current executable behavior.

## 4.6 Task P1-05 — Enforce deterministic Firestore document identity

### Implementation targets

- `SyncRepositoryImpl.executeSyncPassInternal()`
- `OutboxManager`
- `SyncOutbox` payload/mutation ID handling
- `RemoteSyncCoordinator` remote event identity handling
- test suite for cloud idempotency

### Actions

1. Keep local ledger transaction ID as the logical ledger identity.
2. Keep `document(item.entityId)` as the canonical Firestore document-key construction for ledger/account/batch entities where the frozen architecture specifies 1:1 mapping.
3. Remove any alternate document-key construction discovered during implementation.
4. Separate stable document identity from `syncMutationId`; mutation ID correlates a specific write, while entity/transaction ID remains the logical storage identity.

### Verification

Add tests covering:

- same local transaction → same Firestore document ID over repeated sync passes;
- different valid transactions → different document IDs;
- retry after lost ACK updates the same document rather than creating another.

### Definition of done

P1-G2-REQ-04 and the product contract’s transaction identity rule are both proven.

## 4.7 Task P1-06 — Define and implement the Restore/Backup transport reconstruction decision table

### Implementation targets

- `app/src/main/java/com/example/core/backup/BackupManager.kt`
- `contract/backup_state_classification.yaml`
- `SyncRepositoryImpl`
- `SyncOutboxDao`
- `SyncMetadataDao`

### Required decision table

The transport reconstruction algorithm must be explicit before implementation changes the restore/import paths. It is a fixed decision table, not a generic reconciliation engine. Before evaluating disposition, capture a restore-operation snapshot identity consisting of the selected input artifact/hash, a **lineage snapshot token**, and the pre-restore unresolved-obligation set. In Phase 1 this lineage token is an abstract restore precondition; the persisted G4 generation that supplies the executable token is introduced by P3-01 and bound to this contract by P3-05. Phase 1 must not introduce a second or early persisted generation mechanism. The final destructive Room transaction must validate that the snapshot is still applicable; a new local mutation or outbox obligation created after the snapshot must either be included through the same maintenance boundary or cause the restore operation to abort/restart rather than silently disappear.

| Input condition | Disposition | Required property |
|---|---|---|
| Historical backup outbox/cursor metadata from the selected backup | Discard as historical transport metadata | Must never be replayed merely because it exists in the backup |
| Current unresolved cloud obligation whose logical transaction remains represented in the resulting business dataset **and whose business identity is valid for the resulting lineage** | Retain/reconstruct as a current obligation | Same stable transaction/document identity is preserved and the obligation is attached to the resulting lineage, not merely to a reused account ID |
| Current unresolved obligation whose target business entity is absent **or whose historical lineage is not semantically valid for the resulting dataset** | Do not replay blindly | Must be explicitly surfaced/classified as orphaned/unresolved rather than silently deleted or reattached by ID alone |
| Resulting business state requires a fresh transport publication under current sync semantics | Reconstruct a current obligation from the resulting business state | No historical mutation/cursor is replayed as current authority |
| Completed historical transport metadata with no current business obligation | Do not recreate | No duplicate outbox storm |

The pre-restore live obligation set, selected resulting business dataset, backup transport metadata, and current identity semantics are the inputs to this table. Each obligation must receive one disposition before the destructive restore/replace transaction commits.

### Actions

1. Snapshot the relevant current unresolved obligations before restore/replace begins.
2. Load/validate selected backup transport metadata but mark it historical by classification.
3. Compute each current obligation's disposition against the resulting business dataset and stable identity contract.
4. Preserve only obligations whose logical target remains represented in the resulting state; reconstruct their durable outbox rows using the same identity.
5. Route obligations that cannot attach to the resulting state through the existing orphan/unresolved handling; never silently delete them.
6. Never replay historical backup outbox rows or cursors merely because they are present in the archive.
7. Keep the algorithm local to restore/import transport semantics; do not introduce a generic reconciliation engine or second synchronization state machine.

Do not create a runtime "reconstruction audit event" unless an existing authority-defined operational metadata surface already owns such events. The proof record for this decision belongs to the verification evidence, not automatically to business/runtime data.

### Verification

Add tests for:

- backup contains stale outbox/cursor metadata → restore does not replay it blindly;
- pre-restore valid unresolved obligation targeting a resulting business row → obligation survives with stable identity;
- pre-restore valid unresolved obligation targeting a removed/non-resulting row → obligation is surfaced/classified, not silently deleted or replayed;
- restore of a completed business snapshot produces no duplicate outbox event storm;
- operational sync metadata is rebuilt/reset according to `backup_state_classification.yaml`;
- repeated restore using the same identity inputs yields the same transport disposition.

### Definition of done

P1-G2-REQ-05 has an explicit deterministic disposition algorithm, source implementation, and executable proof. Phase 2 may consume this rule without inventing a second transport policy.

## 4.8 Task P1-07 — Close the G1 external-operation durability boundary

### Implementation targets

- `app/src/main/java/com/example/core/model/Models.kt`
- `app/src/main/java/com/example/core/database/AppDatabase.kt`
- `app/src/main/java/com/example/domain/repository/Interfaces.kt`
- `app/src/main/java/com/example/data/repository/Repositories.kt`
- `app/src/main/java/com/example/ui/viewmodels/EarthlinkSearchViewModel.kt`
- `app/src/main/java/com/example/ui/screens/UserDetailScreenV2.kt`
- `app/src/main/java/com/example/core/network/EarthlinkNetwork.kt` only if request/response information required for stable recovery is already part of the accepted API

### Design to implement

Add the smallest local pending-operation record required by the frozen G1 model. The implementation symbol should be `PendingExternalOperation` and its DAO should be part of `AppDatabase`.
The record must persist both `operationIntentId` and `transactionId`. `operationIntentId` is the application-created identity for one user/business action intent; `transactionId` is the stable business ledger identity. Duplicate UI/coroutine submissions for the same intent reuse the first intent/transaction pair, while a later legitimate operation creates a new pair. Neither identity is derived solely from business parameters or timestamps.

The record contains only what is needed to recover the approved operation:

- stable local transaction ID;
- canonical ledger-producing operation type (`ACTIVATION`, `RENEWAL`, `REFILL`), where persisted `RENEWAL` is the canonical operation category for the Product Contract's **Renewal / Extension** business operation;

- external target identity required for verification;
- local business/account identity required for accounting;
- reseller-side charge data already determined by the product flow;
- created/updated timestamps;
- minimal lifecycle marker sufficient to distinguish unresolved/pending from confirmed local materialization.

### Renewal / Extension normalization

The frozen Product Contract names the business operation as **Renewal / Extension**, while the current source already persists/normalizes renewal semantics through `recordAccountRenewal(...)` and `typeRaw == "renewal"`. The canonical persisted G1 operation category is therefore `RENEWAL`, and the external `extendUser(...)` path is normalized into that same canonical business category when it is a ledger-producing operation. UI/API wording may remain "extension"; persisted financial operation identity must not fork into a second ledger authority.

This normalization is a semantic mapping, not a new architecture. The implementation must prove that the extension flow and renewal flow both obey the same stable-transaction-id, pending, accounting, and outbox boundary.

Do not add a distributed reconciliation registry or autonomous external-operation state machine.

### Required execution sequence

```text
1. Create stable transaction ID once.
2. Persist pending-operation record in a short Room transaction.
3. Execute ISP operation outside the Room transaction.
4. On explicit ISP failure:
      no ledger mutation;
      clear/resolve the pending obligation according to the failure result.
5. On confirmed ISP success:
      in one short Room transaction:
          ledger mutation(s)
          current-position update/recompute
          outbox obligation(s)
          pending-operation resolution
6. On process interruption between 3 and 5:
      pending record survives;
      recovery reuses original transaction ID;
      no blind external retry.
```

### Scope clarification

Only Activation, Renewal/Extension, and Refill participate in the ISP-driven subscriber-charge durability boundary. Existing trial-user creation and non-financial ISP operations must not gain subscriber-charge ledger entries merely because they pass through the same ViewModel; ordinary local debt/payment/advance/correction activity remains valid local ledger activity.

The existing `UserDetailScreenV2.kt` callback-based pattern must be removed as the authority for post-success ledger creation. The ViewModel/repository path must call the single durability boundary.

The paid-activation path in `EarthlinkSearchViewModel.createUserUsingDeposit()` must be routed through the same boundary if the product flow defines the resulting operation as a ledger-producing Activation. The current `EarthlinkSearchViewModel.extendUser()` path must also be inspected and routed through the same boundary because the frozen Product Contract treats Renewal / Extension as ledger-producing. The implementation must use the already-authoritative reseller-side charge input for the ledger; it must not silently substitute an ISP package cost merely because `previewPackageCost()` exists.

### Verification

Create `G1ExternalOperationDurabilityTest.kt` covering:

1. pending record created before external operation;
2. confirmed success creates one ledger obligation, current-position state, and outbox obligation atomically;
3. process interruption after external success but before local commit leaves the pending record intact;
4. recovery reuses the same transaction ID;
5. repeated recovery cannot create duplicate ledger rows;
6. explicit external failure creates no ledger row;
7. Firebase unavailable does not prevent the external ISP operation from executing;
8. unknown external result is retained pending and is not blindly retried;
9. `extendUser()` and `refillUser()` cannot create divergent financial-operation identity or bypass the same pending/accounting/outbox boundary.

### Definition of done

P1-G2-REQ-06 is proven together with the frozen G1 recovery boundary, without building a general external reconciliation engine.

## 4.9 Task P1-08 — Prove lost-ACK idempotency and current Room atomicity

### Implementation targets

- `LocalLedgerRepositoryImpl` in `Repositories.kt`
- `SyncRepositoryImpl`
- `OutboxManager`
- `AppDatabase`

### Verification scenarios

### Scenario A — lost cloud ACK

```text
T123 local ledger
→ outbox T123
→ Firestore write succeeds
→ ACK lost
→ T123 retried
→ exactly one logical T123 document
```

### Scenario B — local transaction rollback

Force an exception after ledger mutation but before outbox persistence and prove that neither business mutation nor partial obligation remains.

### Scenario C — external success atomic accounting

Force an exception between ledger/current-position/outbox writes and prove the Room transaction rolls back as one unit.

### Scenario D — no Firebase prerequisite

Execute the G1 path with Firebase unavailable and prove the ISP operation still reaches the external gateway path; cloud publication remains a later obligation.

### Definition of done

P1-G2-REQ-07 plus the G1 atomicity boundary have current executable proof.

## 4.10 Task P1-09 — Prove same-logical-operation duplicate-initiation suppression

### Implementation targets

- `app/src/main/java/com/example/ui/viewmodels/EarthlinkSearchViewModel.kt`
- `app/src/main/java/com/example/data/repository/Repositories.kt`
- the persisted pending-operation model/DAO introduced by P1-07
- tests under `app/src/test/**` and, where lifecycle/process behavior is required, `app/src/androidTest/**`

### Required proof

For each ISP-driven operation class (Activation, Renewal/Extension, Refill), issue two concurrent invocations with identical logical business parameters before the first operation completes. The implementation must resolve both invocations to one logical operation identity and one external execution, or reject/serialize the second invocation before any second external side effect. The proof must distinguish this from two legitimately distinct operations with intentionally different logical parameters.
The deduplication identity is the persisted `Operation Intent ID`, generated once at the application/business-action boundary. It must not be a hash of `account + operation + amount`, a timestamp bucket, or a package tuple. A second legitimate renewal days later with the same business parameters receives a new intent ID.

```text
invocation A + invocation B
→ same logical operation key/identity decision
→ one PendingExternalOperation
→ one external ISP call
→ one transaction identity
→ one ledger materialization
→ one outbox obligation
```

Also test process interruption between the shared-operation decision and external invocation; restart must not manufacture a second logical operation.

### Verification

Add executable tests that assert: no second external call is made for the same logical operation while the first is pending; only one persisted transaction identity is created; only one ledger entry is materialized after confirmed success; only one outbox obligation exists for that transaction; two distinct legitimate operations with different logical parameters remain distinct; retry/recovery of the same operation reuses the original identity.

### Definition of done

The test suite fails on the current implementation's independent coroutine launches and passes only after a deterministic duplicate-initiation boundary exists without introducing a generic synchronization state machine.

---

## 4.11 Task P1-10 — Prove full unknown-outcome resolution without blind retry

### Implementation targets

- the pending-operation repository/model/DAO from P1-07
- ISP operation verification/recovery path in `app/src/main/java/com/example/data/repository/Repositories.kt`
- existing Earthlink network gateway interfaces under `app/src/main/java/com/example/core/network/**`
- unit/instrumentation fixtures under `app/src/test/**` and `app/src/androidTest/**`

### Mandatory external-verification precondition

Before any UNKNOWN-resolution code is implemented, inventory the current accepted Earthlink/API verification workflow for each of Activation, Renewal/Extension, and Refill. The current `EarthlinkApiService` has subscriber/detail and balance/status-style reads but no dedicated transaction-status-by-local-ID endpoint; therefore the implementation must prove the exact existing verification primitive and correlation evidence rather than invent a new status API.

If the existing workflow cannot distinguish `SUCCESS` from `FAILURE` for a specific operation, classify the branch as `INCONCLUSIVE` and stop implementation of autonomous resolution. Do not substitute a blind retry or `mark completed` workflow. This is a feasibility gate derived from the frozen product rule that the user verifies actual subscriber state.

### Required state protocol

```text
UNKNOWN
 ├── verify ISP state = SUCCESS → materialize locally exactly once
 ├── verify ISP state = FAILURE → resolve without ledger materialization
 ├── verify ISP state = INCONCLUSIVE → remain pending; no external retry
 └── app/process interruption → restart, verify actual state, resolve once
```

The verification path must not create a new transaction identity and must not expose a user action whose only meaning is `mark completed` without external evidence.

### Verification

Test all four branches, including restart after the unknown result. Assert that success and failure resolution are mutually exclusive, that inconclusive verification leaves the durable pending obligation intact, and that no blind retry occurs in any unknown branch.

### Definition of done

Unknown-outcome handling is an executable state-resolution protocol, not merely a pending flag, and all branches reuse the original logical transaction identity.

### Pending-operation user/recovery contract

When a durable pending/unknown operation exists, the UI must represent the operation as unresolved rather than falsely reporting failure or success. The user-visible action set must follow the verified operation state:

```text
PENDING/UNKNOWN
    → show unresolved/recovery state
    → offer verification/recovery path
    → do not silently launch a second external operation

VERIFIED SUCCESS
    → materialize once / show success

VERIFIED FAILURE
    → resolve without financial materialization

INCONCLUSIVE
    → remain pending and preserve the original identity
```

Screen abandonment, activity recreation, or process restart must not discard the pending identity. This is a behavior contract, not a new workflow/state-machine architecture.

---

## 4.12 Task P1-11 — Protect immutable ledger payloads when transaction identity collides

### Implementation targets

- ledger/outbox write path used by `SyncRepositoryImpl` and the current Room ledger DAO
- Firestore ledger write/read adapter under `app/src/main/java/com/example/core/sync/**`
- invariant/contract tests for immutable ledger identity

### Required behavior

```text
existing T100 = {account=A, amount=50_000, type=DEBT, ...}
incoming T100 = {account=A, amount=90_000, type=DEBT, ...}
→ do not overwrite history
→ do not create a second identity
→ surface deterministic conflict evidence using the existing error/diagnostic surface
```

Same-ID + same immutable payload remains an idempotent no-op. Same-ID + divergent immutable payload is a correctness failure, not a last-write-wins merge.

### Verification

Add tests for same-ID/same-payload replay and same-ID/divergent-payload replay against both the local ledger write path and the Firestore adapter/emulator fixture used for G2 proof.

### Definition of done

A stable identity can no longer be used to mutate an existing immutable financial history row by replaying a different payload.

---

## 4.13 Task P1-12 — Establish the cross-device convergence fixture and proof contract

### Implementation targets

- synchronization integration-test fixtures under `app/src/test/**` or `app/src/androidTest/**` as required by the actual Firebase/Room environment
- `SyncRepositoryImpl`, `RemoteSyncCoordinator`, and current ledger/outbox persistence surfaces
- `contract/invariant_test_map.yaml` and the phase evidence mapping only; no new runtime reconciliation component

### Scenario

```text
Cloud:  T1
Device A: T1 + T2
Device B: T1 + T3

A reconnects and uploads T2
B reconnects and uploads T3
A/B subsequently consume stale or incomplete cloud state and retry

Required final business set:
Cloud    = T1 + T2 + T3
Device A = T1 + T2 + T3
Device B = T1 + T2 + T3
```

The proof must show no duplicate T1, no loss of T2/T3, no last-write-wins overwrite of independent ledger events, and equal current-position derivation from the same resulting ledger set.

### Definition of done

The two-device fixture passes with the same stable identities and existing per-item transport/lineage mechanisms. No generic synchronization state machine or reconciliation engine is introduced.

---

## 4.14 Task P1-13 — Phase 1 evidence and exit gate

### Evidence

Generate current-source-derived evidence only:

- source SHA;
- current test inventory;
- unit test execution output;
- targeted G1/G2 behavioral tests;
- adversarial fixtures;
- forbidden-pattern scan;
- contract validation;
- Room/instrumentation evidence where required;
- updated compliance matrix.

### Phase 1 exit criteria

Phase 1 may close its implementation workstream only when:

- G1 durability evidence is independently PASS;
- G2 transport evidence is independently PASS for all requirements that do not depend on later G5 implementation;
- the Phase-1 identity contract is frozen and consumed consistently;
- P1 source/test/evidence artifacts are bound to one current implementation identity.

**Final G2 closure is deferred until the Phase-4 G5 identity evidence is available**, because the frozen cross-gate dependency explicitly makes G5 identity foundational to G2 cloud idempotency. Phase 1 therefore exits as `IMPLEMENTATION COMPLETE / G2 PROVISIONAL`, not as an unconditional final G2 closure. No requirement may be “satisfied” from historical completion JSON.

Blocking failures:

```text
any terminal obligation loss
any poison item halting unrelated work
any orphan silent deletion
any unstable document identity
any blind replay of stale backup transport metadata
any external success that can disappear locally
any duplicate ledger mutation after lost ACK
any Firebase prerequisite for external operation
any fabricated remote-durability confirmation
any unproven unknown-outcome verification capability
any scheduler/liveness path that can leave valid durable obligations permanently unexecuted
```

---

# 5. Phase 2 — G3 Restore & Import

## 5.0 Exact Phase-2 requirement coverage

| Requirement | Plan coverage | Executable verification target |
|---|---|---|
| `P2-G3-REQ-01` | Resolve Restore Merge conflict decisions before final Room write | Restore/Merge decision tests + final transaction boundary test |
| `P2-G3-REQ-02` | Preserve complete lineage: selected baseline + its eligible ledger history | lineage-conflict matrix + mixed-lineage rejection test |
| `P2-G3-REQ-03` | Keep UI/network/external calls outside the final Room business transaction | transaction-boundary/static guard + instrumented execution |
| `P2-G3-REQ-04` | Recompute current position only from accepted baseline + eligible ledger history | deterministic rebuild tests |
| `P2-G3-REQ-05` | Reconstruct transport state without duplicate outbox or uncoordinated mutations | restore/import transport tests |
| `P2-G3-REQ-06` | Rollback-safe bulk restore/import under process death/crash/resource pressure | kill-point/instrumented rollback tests |

## 5.1 Phase 2 objective

Complete Direct Atomic Room Restore/Import semantics, implement the missing minimum Restore Merge path, establish deterministic current-position rebuild, and reconstruct transport state safely without staging architecture.

## 5.2 Task P2-01 — Define the final Restore/Import business transaction boundary

### Restore decision contract

An operator-approved Restore Merge decision must be a deterministic decision object, not an arbitrary boolean. It must identify:

```text
input artifact identity
selected baseline/source
selected ledger-lineage scope
conflict decisions
computed target dataset identity
approval/cancellation state
```

The decision object must be recomputed/invalidated if the validated input artifact or restore snapshot changes. Operator cancellation, screen abandonment, or process restart must not silently commit a previously computed destructive decision.


### Implementation targets

- `app/src/main/java/com/example/core/backup/BackupManager.kt`
- `app/src/main/java/com/example/core/sync/UtowerImporter.kt`
- `app/src/main/java/com/example/data/repository/Repositories.kt`
- `app/src/main/java/com/example/core/database/AppDatabase.kt`
- `contract/backup_state_classification.yaml`

### Required separation

```text
Outside final Room transaction:
    parse
    validate
    load backup
    decrypt/verify
    select/resolve conflicts
    perform required remote reads
    compute merge decisions
    compute identity mapping
    compute target dataset

Inside final Room transaction:
    only deterministic local business-state application
```

No UI interaction, Firebase wait, ISP wait, or externally blocking await may occur inside the final Room business transaction.

### Verification

Add a structural test that rejects network/Firebase/API calls inside the final transaction block and a behavioral test that proves conflict decisions are complete before commit.

## 5.3 Task P2-02 — Implement Restore Merge as a complete-lineage decision operation

### Required identity-content conflict rule

Restore Merge must reject or surface a deterministic decision whenever the same immutable Business Transaction ID appears with materially divergent financial/immutable payloads across source snapshots. It must never silently choose one payload as “latest” merely because both rows share the same ID.

Required adversarial fixture:

```text
snapshot A: T100 = amount 50,000 / type DEBT
snapshot B: T100 = amount 90,000 / type DEBT
→ merge requires explicit deterministic conflict handling
→ no silent overwrite
→ no duplicate identity
→ financial history remains immutable
```


### Implementation targets

- `BackupManager.kt`
- `Models.kt` for explicit merge decision input/output objects where required
- `Repositories.kt` for business-state application if the current repository layer owns the write
- `LocalAccountRepositoryImpl` / `LocalLedgerRepositoryImpl` only where needed for atomic materialization

### Required rules

A Merge decision must select a complete snapshot lineage:

```text
selected baseline
+
associated eligible ledger history
```

Do not mix one baseline with another snapshot’s ledger history.

For transaction identity:

```text
same transaction ID → one logical transaction
different transaction IDs → preserve both
```

For incompatible opening/current baselines, the user/approved decision is made before the final Room transaction.

### Verification

Create `RestoreMergeLineageTest.kt` covering:

- same transaction present in both snapshots → one result;
- independent transactions in both snapshots → both retained;
- incompatible baselines cannot be silently mixed;
- selected lineage carries the complete eligible history;
- repeated merge is idempotent;
- no ledger double-counting.

## 5.4 Task P2-03 — Make current-position reconstruction deterministic

### Implementation targets

- `app/src/main/java/com/example/core/ledger/BalanceCalculator.kt`
- `app/src/main/java/com/example/data/repository/Repositories.kt` — `recalculateAccountHistory(...)` and related rebuild paths
- `BackupManager.kt`
- `UtowerImporter.kt`

### Rules

Current position must be derived from:

```text
accepted baseline
+
eligible ledger history
```

Stored balance totals are not an independent financial authority.

Preserve the uTower snapshot semantics: do not reconstruct a historical debt from incomplete source history when the trusted source snapshot is the opening/current baseline.

### Verification

Create an independent oracle test that derives the expected current position from baseline + eligible ledger rows and compares it to the materialized account state after import, replace, and merge.

## 5.5 Task P2-04 — Harden Restore Replace

### Capacity envelope and operational feasibility

Before destructive Restore Replace is declared implementation-complete, execute the largest supported measured dataset fixture against the Direct Atomic Room boundary and record:

```text
row/account/ledger counts
transaction duration
peak memory
WAL/database growth
disk headroom
background/ANR behavior
failure/timeout behavior
```

The envelope must be machine-verifiable from the actual current Android execution environment. A failure caused by the measured supported workload is an implementation/feasibility blocker; it is not permission to introduce staging architecture without the separate authority reopening rule.


### Implementation targets

- `BackupManager.restoreBackupZip()`
- `AppDatabase.withTransaction` restore block

### Required behavior

1. pre-restore safety backup as already mandated;
2. backup decryption and structural validation outside final live transaction;
3. one final Room replacement transaction;
4. business snapshot replaced atomically;
5. incomplete import batches quarantined according to `backup_state_classification.yaml`;
6. operational outbox/cursor state cleared/reset according to classification;
7. restore audit trail preserved;
8. no historical outbox blindly replayed;
9. G4 generation invalidation added in Phase 3 in the same final transaction.

### Verification

Android instrumentation test cases:

- process interruption before final transaction;
- failure inside final transaction;
- restored business dataset is either entirely old or entirely new;
- no partial business visibility;
- stale backup outbox is not replayed.

## 5.6 Task P2-05 — Harden Import as Direct Atomic Room

### Implementation targets

- `UtowerImporter.importFromFile()`
- `UtowerImporter.importFromPreview()`
- `Repositories.commitImport()`
- `TransactionDeduplicator.kt`
- `SubscriberMatcher.kt`

### Required behavior

- parse and validate first;
- publish the validated dataset through the approved atomic Room boundary;
- no staging database;
- import batch remains the operational guard but does not become a business authority;
- interruption leaves the database consistent;
- later retry/recovery does not silently duplicate historical rows.

### Verification

Android instrumentation:

- supplied realistic uTower dataset;
- process interruption at controlled import points;
- complete rollback on failed final transaction;
- no partial business visibility;
- repeated import behaves consistently.

The supplied `utower_data_c.tgz` remains the source artifact for workload realism; do not invent a synthetic dataset as the only proof.

## 5.7 Task P2-06 — Restore/Import transport reconstruction

### Implementation targets

- `BackupManager.kt`
- `UtowerImporter.kt`
- `OutboxManager.kt`
- `SyncRepositoryImpl.kt`

### Required behavior

After business-state replacement/import:

- stale backup transport rows are not replayed;
- stale cursors are rebuilt/reset;
- transport obligations are reconstructed from current business-state semantics only where required;
- current valid obligations that must survive remain representable;
- no generic reconciliation engine is introduced.

### Definition of done

P2-G3-REQ-05 is satisfied without adding a second synchronization state machine.

## 5.8 Task P2-07 — Phase 2 evidence and provisional exit gate

Evidence must include:

- Android instrumentation execution;
- Restore Replace interruption proof;
- Restore Merge lineage proof;
- import interruption proof;
- deterministic current-position oracle;
- current source/test inventory;
- updated invariant/requirement matrix.

Blocking failures:

```text
mixed baseline/ledger lineage
network inside final Room transaction
partial business visibility
non-deterministic current-position rebuild
auto-replay of historical transport metadata
restore/import duplicate business mutation
```

### Closure status

Phase 2 may close as an **implementation/provisional G3 gate** after its own executable requirements pass. Final G3 closure is intentionally deferred until both dependent evidence sets exist:

```text
G4 stale-result / generation invalidation evidence
        +
G5 stable identity / Merge identity evidence
        +
current G3 Restore/Import evidence
        ↓
FINAL G3 CLOSURE
```

This follows the frozen cross-gate dependencies and prevents a G3 PASS from being declared against an identity or lineage implementation that is later changed.

---

# 6. Phase 3 — G4 Concurrency & Lineage

## 6.0 Exact Phase-3 requirement coverage

| Requirement | Plan coverage | Executable verification target |
|---|---|---|
| `P3-G4-REQ-01` | Persist local G4 generation as lineage authority; do not substitute `remoteVersion` | generation persistence/restart tests |
| `P3-G4-REQ-02` | Validate generation and apply remote business data in one Room write transaction | stale-generation transaction tests |
| `P3-G4-REQ-03` | Increment generation on Restore Replace, full wipe, and sign-out data clear; invalidate in-flight remote work immediately | replacement/wipe/session-clear tests |
| `P3-G4-REQ-04` | Keep normal same-lineage financial mutations from incrementing generation without concurrency evidence | normal mutation regression tests |
| `P3-G4-REQ-05` | Enforce lock hierarchy and prohibit network I/O/unbounded waits while holding business/maintenance locks | lock-order/static + instrumented timing tests |

## 6.1 Phase 3 objective

Add the minimum persisted local generation model and make every full dataset replacement/removal and remote result application obey transactional lineage semantics, while preserving the existing `DataOperationCoordinator` as the canonical exclusion mechanism.

## 6.2 Task P3-01 — Add persisted G4 generation state

### Migration choreography contract

The current Room schema is version `11`. The persisted generation introduced by this task owns the first required schema transition:

```text
v11 → v12   = G4 persisted lineage generation
v12 → v13   = Phase-5 G6/G7 schema/deletion/FK semantics, unless current source inspection proves an existing intervening version is required
```

The exact next versions must be frozen in the migration graph before coding. No task may create a `11→13` migration that bypasses the authoritative `11→12→13` chain. Existing v11 backups must be proven to reach the current schema through the actual supported migration/parser path, not an undocumented shortcut.

Migration owners:

```text
P3-01 owns v11→v12 generation migration
P5-05/P5-06 own the subsequent G6/G7 schema semantics migration(s)
```

The phase plan must update the Room migration graph and its executable compatibility tests together. A source file that references a newer Room version without an executable predecessor chain is a blocking migration error.


P3-01 is the first implementation task that introduces the persisted generation mechanism referenced abstractly by P1-06. No Phase-1 task may create a duplicate generation store or an alternative lineage counter.

### Implementation targets

- `app/src/main/java/com/example/core/model/Models.kt`
- `app/src/main/java/com/example/core/database/AppDatabase.kt`
- `app/src/main/java/com/example/core/sync/RemoteSyncCoordinator.kt`
- schema migration from current database version `11` to the next version required by the implementation

### Design

Use one persisted local generation value as the authoritative lineage marker. The symbol may be implemented as a dedicated `SyncData`/metadata key if that preserves the exact frozen semantics and avoids an unnecessary architectural entity; the plan requires a single authoritative persisted generation, not an additional state registry.

The generation must be initialized deterministically on database creation/open and advanced only at actual full-data lineage boundaries.

## 6.3 Task P3-02 — Capture lineage at remote operation start and validate in the same transaction

### Implementation targets

- `RemoteSyncCoordinator.processEvent()`
- `RemoteSyncCoordinator.applyAccountUpsert()`
- `applyAccountDelete()`
- `applyLedgerUpsert()`
- `applyLedgerDelete()`
- `applyBatchUpsert()`
- any remote-event call sites that currently read/apply remote state through a multi-step path

### Required sequence

```text
read current generation
        ↓
perform remote fetch/interpretation outside Room write
        ↓
open Room write transaction
        ↓
re-read current generation
        ↓
if generation changed:
    reject stale result
else:
    apply remote business data atomically
```

The generation check and business-data application must be inside the same Room write transaction.

### Verification

Create `G4LineageStaleResultTest.kt` with:

- stale remote result after Restore Replace is rejected;
- stale remote result after full clear is rejected;
- same-generation remote result applies;
- generation change cannot occur between the check and business-data write in a way that allows stale mutation.

## 6.4 Task P3-03 — Advance generation on full replacement/clear only

### Implementation targets

- `BackupManager.restoreBackupZip()`
- `SyncRepositoryImpl.signOut()`
- developer/data-clear paths in `Repositories.kt` / `LocalAccountRepositoryImpl` where they actually represent full dataset clear
- `UtowerImporter.importFromFile(..., shouldReplace = true)` if it replaces the entire business dataset

### Required rule

Increment generation transactionally with the full dataset replacement/clear.

Sign-out only changes lineage when it actually clears/replaces the local business dataset. Authentication-only transitions do not increment generation.

### Verification

Cover:

- Restore Replace increments generation inside final transaction;
- full clear increments generation;
- sign-out with data clear increments generation;
- sign-out without data clear, if supported, does not invent a lineage transition.

## 6.5 Task P3-04 — Preserve same-lineage normal mutations

### Implementation targets

- `Repositories.kt` financial mutation methods;
- G1 commit path from Phase 1;
- `UtowerImporter.kt` ordinary import path;
- `RemoteSyncCoordinator` ordinary remote apply path.

### Required rule

Normal local financial mutations do not increment generation.

This preserves the frozen distinction:

```text
remote version
≠
local lineage generation
```

### Verification

Concurrent same-lineage ledger writes must prove:

- no unnecessary generation changes;
- no ledger/current-position inconsistency;
- no stale-result acceptance.

## 6.6 Task P3-05 — Prove restore obligation lineage and generation linearization

### Implementation targets

- `app/src/main/java/com/example/core/database/AppDatabase.kt`
- generation metadata model/DAO introduced by P3-01
- `BackupManager.restoreBackupZip()` and Restore Merge final transaction path
- `RemoteSyncCoordinator` stale-result application path
- outbox obligation snapshot/reconstruction path introduced by P1-06/P2-06

### Required proof

Restore Replace/Merge must establish one linearization point for the resulting business lineage and the disposition of pre-existing unresolved transport obligations. P3-05 is the first task allowed to bind the Phase-1 abstract lineage snapshot token to the persisted G4 generation introduced by P3-01. The final Room transaction must not accept an obligation merely because an account ID still exists. It must validate that the obligation's logical business identity is still attached to the resulting lineage and that the captured generation/token remains current at commit.

Adversarial fixture:

```text
Generation 41: account A + pending T55
Restore Replace/Merge creates Generation 42
Account A still exists in the resulting dataset
T55 is valid only if its business identity remains semantically attached to Generation 42
```

TOCTOU fixture:

```text
snapshot unresolved obligations = {T1}
concurrent local operation creates T2
restore final commit attempts to publish dataset
→ either the maintenance boundary prevents T2 from existing during snapshot/commit
  or the restore detects the changed snapshot and aborts/restarts
→ T2 is never silently dropped
```

### Definition of done

Restore publication, generation invalidation, and obligation disposition form one verifiable linearization boundary without adding a generic reconciliation state machine.

---

## 6.7 Task P3-06 — Prove lock hierarchy and no network while business lock is held

### Implementation targets

- `DataOperationCoordinator.kt`
- `DataMaintenanceLock.kt`
- `RemoteSyncCoordinator.kt`
- `SyncRepositoryImpl.kt`
- `BackupManager.kt`
- `UtowerImporter.kt`

### Rules

Keep the existing coordinator as the canonical runtime mutation channel.

Do not hold a Room business transaction or maintenance exclusion while awaiting:

- Firebase;
- Earthlink API;
- unbounded network I/O;
- UI interaction.

The existing coroutine ownership-token work remains valid and must not be replaced with another global synchronization authority.

### Verification

Create `G4LockOrderAndNetworkIsolationTest.kt` covering:

- coordinator serialization;
- nested direct re-entry where already allowed;
- child coroutine cannot bypass mutex;
- network call does not occur inside final Room business transaction;
- concurrent restore/import/sync exclusion works without deadlock.

## 6.8 Task P3-07 — Normalize remote ordering coordinates and delete/upsert adversarial ordering

### Implementation targets

- `app/src/main/java/com/example/core/sync/RemoteSyncCursor.kt`
- `app/src/main/java/com/example/core/sync/RemoteSyncCoordinator.kt`
- remoteVersion/effectiveVersion/tombstone persistence surfaces used by the current source
- `contract/invariant_contract.yaml` and `contract/invariant_test_map.yaml` only where current requirement mapping is incomplete

### Required proof

Inventory every retained remote ordering field and classify its invariant. The query ordering domain and cursor-advancement domain must be the same ordering coordinate unless an authority-approved transformation is explicitly proven. G4 generation remains separate from remote freshness/versioning.

Add adversarial ordering tests for:

```text
update → delete
delete → stale upsert
duplicate delete
newer update → older update
newer update → older delete
```

Required result: stale events cannot resurrect newer state or roll back derived business meaning; local financial history remains preserved; tombstone/remote metadata ordering remains deterministic.

### Definition of done

Remote event ordering and cursor advancement use one proven coordinate domain, while G4 generation remains the separate local-lineage boundary.

---

## 6.9 Task P3-08 — Phase 3 exit evidence

Evidence must include:

- current source identity;
- unit tests;
- Android concurrency/instrumentation where practical;
- stale-result adversarial tests;
- lock-order structural checks;
- updated requirement/invariant matrix.

Blocking failures:

```text
stale previous-lineage mutation reaches current dataset
generation used as a second business state machine
normal local transaction incorrectly changes lineage
network wait inside protected business transaction
deadlock / mutex bypass
```

---

# 7. Phase 4 — G5 Identity / Import Collision Safety

## 7.0 Exact Phase-4 requirement coverage

| Requirement | Plan coverage | Executable verification target |
|---|---|---|
| `P4-G5-REQ-01` | Stable source-row identity for repeated imports; distinct legitimate rows remain distinct | same-row/different-row identity tests |
| `P4-G5-REQ-02` | Repeated uTower import is idempotent and stable | repeated-import integration tests |
| `P4-G5-REQ-03` | Distinct identical historical rows require distinct provenance identity; no SQLite `ROWID` authority | two-identical-row adversarial import test |
| `P4-G5-REQ-04` | Preserve existing reliable transaction IDs through migration/sync | migration/sync identity-preservation tests |

## 7.1 Phase 4 objective

Correct historical source-row identity without introducing an identity registry, preserve reliable existing IDs, and prove that repeated import is idempotent while distinct legitimate rows remain distinct.

## 7.2 Task P4-01 — Inventory every ledger-creation path

### Implementation targets

- `LocalLedgerRepositoryImpl` in `Repositories.kt`
- G1 external-operation durability path
- `UtowerImporter.kt`
- `Repositories.commitImport()`
- `BackupManager.kt` Restore Merge
- `SyncRepositoryImpl` cloud write identity
- Room schema/migration paths

### Deliverable

A current-source identity map with these columns:

```text
creation path
→ source event/data
→ ID generation rule
→ local storage key
→ Firestore document key
→ retry behavior
→ migration behavior
```

Required paths:

- local debt;
- payment/settlement;
- advance/prepayment when represented by a ledger transaction;
- Activation;
- Renewal/Extension;
- Refill;
- uTower import;
- Restore Merge;
- v71 migration;
- retry/re-upload.

## 7.3 Task P4-02 — Fix source-row identity fallback in uTower importer

### Implementation targets

- `app/src/main/java/com/example/core/sync/UtowerImporter.kt`
- `app/src/main/java/com/example/core/sync/TransactionDeduplicator.kt`
- `app/src/main/java/com/example/core/model/Models.kt` only if source provenance fields must be preserved

### Required strategy

For historical rows:

```text
explicit source ID
    → preserve/use it

otherwise
    → deterministic source artifact provenance + source-row/occurrence coordinate
```

The stable coordinate must come from the source representation itself, not destination SQLite ROWID.

The generated ID must satisfy:

```text
same source artifact + same source row
    → same ID

distinct legitimate source rows
    → different IDs
```

The implementation must not derive a no-key identity only from:

```text
account + occurredAt + amount + type
```

when the source can contain two legitimate rows with identical business fields.

### Important preservation rule

Do not rewrite already reliable historical IDs merely because the fallback algorithm changes. Existing reliable IDs are preserved; only rows that genuinely need deterministic identity normalization may be migrated, and migration must be based on original source evidence when reconstructing missing identity.

## 7.4 Task P4-03 — Reconcile deduplication semantics with identity semantics

### Implementation targets

- `TransactionDeduplicator.kt`
- `LocalLedgerEntryDao.findDuplicateTx()`
- `UtowerImporter` maps/caches used by import
- `Repositories.commitImport()`

### Rule

Deduplication must never be stronger than the frozen identity semantics.

A row with no source ID that is identical in amount/timestamp/type is not automatically the same row if the source provenance indicates distinct source occurrences.

The database uniqueness constraints may remain useful for explicit source IDs, but fallback equality must not collapse legitimate distinct rows.

## 7.5 Task P4-04 — Preserve runtime idempotency-key identity

### Implementation targets

- `LocalLedgerRepositoryImpl.addPaymentInternal()`
- `addDebtInternal()`
- `recordAccountRenewal()`
- G1 external-operation commit path

### Required behavior

- application-generated ID is created once;
- idempotency key is reused through retry/recovery;
- existing row with same accepted transaction ID is returned rather than duplicated;
- a legitimate new transaction receives a different ID.

### Verification

`RuntimeLedgerIdentityTest.kt` must cover repeated invocation with the same idempotency key and concurrent/retried invocation without double application.

## 7.6 Task P4-05 — Preserve identity across Restore Merge and Firebase

### Implementation targets

- `BackupManager.kt`
- `SyncRepositoryImpl.kt`
- `RemoteSyncCoordinator.kt`

### Verification

- same transaction ID in two datasets resolves to one logical transaction;
- different IDs are preserved;
- local ledger ID equals Firestore ledger document ID;
- replaying the same cloud event does not create another local transaction.

### Local ledger duplicate-initiation semantics

The identity contract must explicitly cover ordinary local financial activity (`payment`, `debt`, `advance/prepayment`, and any correction path) separately from ISP-driven subscriber-charge operations. For every local method that already accepts an idempotency key, tests must prove:

```text
same idempotency key + same logical mutation
    → one ledger row

new legitimate local mutation
    → new idempotency key / new ledger identity

same key + divergent immutable payload
    → reject/surface conflict; never last-write-wins
```

If a specific local financial method intentionally permits repeated identical calls without deduplication, that decision must be explicit in the domain contract and test matrix rather than inferred from the absence of a test.

## 7.7 Task P4-06 — Adversarial identity fixture and historical-source preservation

### Required fixture

Construct the frozen counterexample from actual import parsing semantics:

```text
same account
same date/time
same amount
same transaction type
source key missing
row A ≠ row B at source provenance level
```

Expected:

```text
row A ID ≠ row B ID
re-import row A → same ID as first import
re-import row B → same ID as first import
```

Use the supplied uTower data/source structure as the basis for the fixture rather than inventing an unrelated source format.

## 7.8 Task P4-07 — Prove source identity plus immutable-content integrity

### Implementation targets

- `app/src/main/java/com/example/core/sync/UtowerImporter.kt`
- `TransactionDeduplicator` and its tests
- local ledger identity/write path
- Firebase ledger document adapter used by the identity tests

### Required proof

G5 identity is two-dimensional:

```text
identity equality
+
immutable financial-content equality
```

The same transaction ID with the same immutable payload is idempotent. The same transaction ID with a materially divergent amount/type/account/source meaning must not overwrite history.

### Verification

Run repeated identical import row → one stable row; identical source ID + identical payload → idempotent; identical source ID + divergent immutable payload → deterministic rejection/conflict evidence; source-missing rows with equal business fields but distinct source occurrence identities → distinct rows; Firestore replay with divergent payload → no historical mutation.

### Definition of done

Stable identity cannot be abused as a mutable last-write-wins key for immutable financial history.

---

## 7.9 Task P4-08 — Prove two-device identity/convergence closure dependency

Bind the P1-12 two-device convergence fixture to the final G5 identity implementation artifact. The evidence must show that both devices preserve independent T2/T3 transactions and that final cloud/business state does not depend on reconnect order.

### Definition of done

The two-device proof is artifact-bound to the same source/build identity as final G5 evidence and is available as a closure dependency for G2/G3.

---

## 7.10 Task P4-09 — Phase 4 evidence and cross-gate identity closure

Evidence must include:

- current source-derived identity map;
- unit tests;
- adversarial identity fixture;
- repeated-import test;
- migration preservation test;
- local-ID→Firestore-ID proof;
- requirement matrix update.

Blocking failures:

```text
same source row gets different IDs
legitimate distinct rows collapse
ROWID becomes identity authority
reliable existing IDs are regenerated
retry creates duplicate ledger history
```

### Cross-gate closure actions

After G5 identity implementation and its executable evidence pass, re-run the dependency-bound closure suites rather than treating prior G2/G3 provisional evidence as automatically final.

1. Re-run G2 lost-ACK/idempotency tests against the final stable identity implementation.
2. Re-run Restore Merge identity tests against the final historical/source identity implementation.
3. Bind G4 stale-result evidence to the same source/build identity used for the G3 final-closure run.
4. Record G2 and G3 as final only if their own evidence plus required G5/G4 dependency evidence are all bound to the same implementation artifact.

### Definition of done

G5 is PASS, and the cross-gate identity evidence required for final G2/G3 closure is available and artifact-bound.

---

# 8. Phase 5 — G6/G7 Semantics + Migration

## 8.0 Exact Phase-5 requirement coverage

| Requirement | Plan coverage | Executable verification target |
|---|---|---|
| `P5-G6-REQ-01` | Explicit field-ownership map for Room/Firestore/imported historical data | ownership matrix test suite |
| `P5-G6-REQ-02` | Credential/session isolation across reseller accounts and delayed responses | account-switch/delayed-response tests |
| `P5-G6-REQ-03` | Preserve `loanIqd`, `isLegacy`, `isSnapshotHistory`, `stateSource`, `stateConfidence` semantics | protected-field regression/migration tests |
| `P5-G6-REQ-04` | ISP-side deletion cannot physically delete local financial history; remove destructive cascade semantics | remote-delete + schema-FK tests |
| `P5-G6-REQ-05` | Business-data-preserving, interruption-safe migration | migration kill-point tests |
| `P5-G6-REQ-06` | Backwards-compatible backup import/export schema handling | prior-format fixture tests |

## 8.1 Phase 5 objective

Freeze the exact semantics of account/profile fields and credentials, eliminate production financial-history deletion on ISP-side events, remove destructive schema cascade semantics, and execute a business-data-preserving migration that is interruption-safe and backup-compatible.

Phase 5 closes both semantic and migration correctness. It is not a generic cleanup phase.

## 8.2 Task P5-01 — Build the field ownership matrix from frozen authority

### Implementation targets

- `app/src/main/java/com/example/core/model/Models.kt`
- `app/src/main/java/com/example/core/model/StateOwnershipContract.kt`
- `app/src/main/java/com/example/data/repository/Repositories.kt`
- `app/src/main/java/com/example/core/sync/RemoteSyncCoordinator.kt`
- `app/src/main/java/com/example/core/sync/SyncRepositoryImpl.kt`
- `docs/authority/Final Independent Adjudication Memo.md`
- `docs/authority/Target Product Contract v0.6.md`

### Deliverable

Every mutable `LocalAccount` field is classified as one of the authority domains already defined by the frozen model:

```text
ISP/server-owned
reseller/local-owned
reminder-note/LWW
operational credential/session
derived financial
legacy/history-only
```

Protected fields must remain present and retain their semantics:

```text
loanIqd
isLegacy
isSnapshotHistory
stateSource
stateConfidence
```

`loanIqd` remains historical/uTower compatibility data, never a second V1 financial authority.

## 8.3 Task P5-02 — Credential/session isolation proof and fixes

### Security evidence policy and Firebase authorization dependency

In addition to UID/session isolation, verify the cloud authorization boundary itself:

```text
UID-A authenticated
→ cannot read/write UID-B documents
```

The test must use the actual Firebase rules/emulator or other authority-approved security-rule verification surface. Deterministic Firestore document IDs are not an authorization guarantee.

Credential proof must also include:

```text
at-rest protection
log/output redaction
backup exposure classification
checkpoint/evidence scrubbing
cleanup/zeroization behavior required by the current security surface
```

No password/token may appear in test failures, checkpoint records, screenshots, or evidence artifacts.


### Implementation targets

- `app/src/main/java/com/example/core/security/PreferenceManager.kt`
- `app/src/main/java/com/example/core/sync/SyncRepositoryImpl.kt`
- `app/src/main/java/com/example/ui/viewmodels/AuthViewModel.kt`
- `app/src/main/java/com/example/domain/repository/Interfaces.kt`
- settings-sync code in `SyncRepositoryImpl`

### Required behavior

1. Credential state is scoped to the active Firebase UID/session model already accepted by the architecture.
2. Delayed async responses from a previous session cannot write into the newly active session.
3. Sign-out clears the correct operational credential state.
4. Credentials are not converted into generic profile synchronization state.
5. Settings sync remains through the canonical `triggerSettingsSync(uid, reason)` path.
6. Server timestamps remain the only remote ordering authority; device clocks do not select distributed winners.

### Verification

Add `CredentialSessionIsolationTest.kt` covering:

- logout → delayed old response → no cross-session write;
- login A → login B → A response cannot mutate B;
- credential clear works;
- remote settings for UID A cannot populate UID B;
- canonical settings sync caller remains intact.

## 8.4 Task P5-03 — Prove functional new-device credential recovery

### Implementation targets

- `PreferenceManager` / current credential persistence surface
- Firebase UID-scoped credential/recovery path actually used by the source
- migration/restore credential handling
- tests under `app/src/test/**` and `app/src/androidTest/**`

### Required proof

The recovery test must cover:

```text
Device A / UID-A
→ persist required operational credentials/recovery material
→ sync/backup using the current authorized recovery surface
→ reinstall or new-device simulation
→ authenticate UID-A
→ recover required credentials
→ execute a supported ISP operation
```

Combine this with the existing delayed-response isolation test so UID-A material cannot mutate UID-B state. No global credential registry may be introduced.

### Definition of done

Credential semantics are proven both for cross-session isolation and for the supported new-device/reinstall recovery path.

---

## 8.5 Task P5-04 — Protect financial history from ISP-side deletion

### Implementation targets

- `RemoteSyncCoordinator.applyAccountDelete()`
- `RemoteSyncCoordinator.applyLedgerDelete()`
- `LocalLedgerEntry` entity in `Models.kt`
- `AppDatabase.kt` migrations
- any remote-delete callers in `SyncRepositoryImpl`
- `data/repository/Repositories.kt :: LocalAccountRepositoryImpl.deleteAccount()`
- `ui/viewmodels/LocalAccountsViewModel.kt :: deleteAccountLocal()`
- `ui/screens/LocalAccountDetailScreen.kt :: EditLocalAccountDialog` `onDelete` callback

### Required behavior

ISP-side delete event must never physically delete local financial history.

The ordinary UI delete path is a separate live production deletion surface and must be explicitly classified; it may not remain an unexamined third path. Before implementation proceeds, select and document exactly one frozen-contract-compatible outcome:

1. **Production-safe classification:** remove the ordinary destructive behavior from the production UI path and move the destructive implementation behind the same explicit `BuildConfig.DEBUG`/developer-only boundary used by other destructive reset tools; or
2. **History-preserving user action:** retain the UI action but convert it to history-only/deactivation semantics that do not physically delete local financial ledger history and do not emit a destructive tombstone that propagates deletion to other devices.

For account disappearance from an ISP-side delete event:

- preserve the local account/history according to legacy/history-only semantics;
- record remote tombstone/version state only as transport metadata;
- do not delete child ledger rows.

For a remote ledger-delete event:

- do not physically delete the local financial history row;
- preserve history and only update remote/tombstone interpretation as allowed by the frozen model.

For developer destructive reset tools:

- remain explicitly non-production;
- are not treated as customer/business deletion semantics;
- cannot be invoked by ISP-side remote events.

No implementation path may leave `LocalAccountRepositoryImpl.deleteAccount()` as an unclassified production deletion route.

### Verification

`FinancialHistoryDeletionProtectionTest.kt`:

1. remote account deletion → local account/history survives;
2. remote ledger deletion → local ledger survives;
3. production account deletion cannot cascade-delete ledger;
4. developer destructive reset is isolated from production remote deletion;
5. ordinary UI account deletion is exercised end-to-end and proves either the selected developer-only boundary or the selected history-only/deactivation semantics; when the history-preserving option is selected, local financial history survives and no destructive tombstone propagates.

## 8.6 Task P5-05 — Remove destructive ON DELETE CASCADE semantics without data loss

### Implementation targets

- `Models.kt` `LocalLedgerEntry` foreign key
- `AppDatabase.kt`
  - current schema
  - historical migration path containing `ON DELETE CASCADE`
  - next migration from version `11`
- generated Room schema JSONs under `app/src/main/assets/com.example.core.database.AppDatabase/`

### Required migration behavior

1. Final schema uses non-destructive FK semantics appropriate to the frozen contract.
2. Existing ledger rows survive migration intact.
3. Existing account IDs remain unchanged.
4. No cascade cleanup is executed during the migration.
5. Historical migrations that previously used CASCADE remain historically reproducible but the final schema is corrected.
6. Migration test matrix includes prior versions represented in the generated Room schema assets.

### Verification

Migration tests must compare pre/post:

```text
ledger row count
ledger IDs
account IDs
account→ledger relationships
financial values
protected semantic fields
```

Zero silent loss is required.

## 8.7 Task P5-06 — Business-data-preserving migration from v11

The migration oracle must prove:

```text
schema conservation
+ identity conservation
+ financial conservation
+ semantic conservation
+ lineage conservation
```

This includes baseline/opening-position meaning, source provenance, protected legacy fields, ledger interpretation, and current-position derivation—not merely row counts and IDs.


### Implementation targets

- `AppDatabase.kt`
- schema assets under `app/src/main/assets/com.example.core.database.AppDatabase/`
- `PreferenceManager.kt` for credential preservation where migration touches encryption/key state
- `SyncOutbox`/metadata normalization if required by G7

### Migration graph requirement

P5 migration work must consume the migration graph established by P3-01 and extend it from the next schema version forward; it must not create a parallel `v11→latest` path that bypasses the tested intermediate generation migration. Every supported source schema must have an executable upgrade path to the final Phase-5 schema.

### Migration rules

Preserve:

- reliable ledger IDs;
- ledger history;
- baseline data;
- legacy/history-only subscribers;
- protected semantic fields;
- required credentials/recovery artifacts;
- valid business data.

Rebuild/normalize:

- obsolete transport states;
- rebuildable sync metadata;
- initial G4 generation;
- final FK/cascade semantics.

Never promote local timestamps into the remote-version domain.

## 8.8 Task P5-07 — Migration interruption safety

### Implementation targets

- `AppDatabase` migration(s)
- migration test harness under `app/src/test`
- Android instrumentation migration harness where SQLite behavior must be validated on-device

### Verification

Introduce controlled kill points around each materially destructive/rebuild step:

```text
migration starts
→ kill at point N
→ reopen
→ inspect financial state
```

Expected result is either:

- old valid schema/data, or
- new valid schema/data,

but never a partially migrated business dataset with silent ledger loss.

Do not claim proof from a narrative report.

## 8.9 Task P5-08 — Backup compatibility matrix

### Implementation targets

- `BackupManager.kt`
- `contract/backup_state_classification.yaml`
- `app/src/main/assets/com.example.core.database.AppDatabase/*.json`
- backup/restore tests

### Required verification matrix

Use every supported historical database/backup schema represented by the repository’s actual compatibility assets and historical backup artifacts.

For each supported source:

```text
old backup
→ current parser/decryption
→ current Room schema/migration
→ restored business dataset
→ financial/identity/semantic audit
```

Verify:

- business data preserved;
- protected fields preserved;
- reliable IDs preserved;
- operational transport state reconstructed/reset correctly;
- incomplete batches quarantined;
- no stale outbox replay;
- restore audit trail preserved.

## 8.10 Task P5-09 — Prove destructive-restore input integrity and decision binding

### Required negative fixtures

Test modified backup payloads, truncated/corrupted archives, wrong encryption credentials, partial extraction, structurally valid but schema-invalid backups, and structurally valid backups whose business content violates required baseline/ledger invariants. Every rejection must prove the live database is unchanged.


### Authenticity/trust-root classification

For destructive restore, record which frozen security mechanism establishes artifact authenticity. The implementation must distinguish:

```text
encryption/decryption success
structural/schema validation
integrity check
cryptographic authenticity / MAC / signature / trusted-hash comparison, if authorized
```

A plain ordinary SHA-256 calculated over an attacker-modified artifact is not an authenticity primitive by itself. The plan must use only the authenticity mechanism actually authorized by the existing security model; it must not invent a new certificate/key registry.

The Restore decision object must bind to the exact validated input artifact identity and computed target dataset. The final commit must reject stale decision material that no longer matches the validated input or restore snapshot. This is a decision-integrity rule, not runtime governance.

### Definition of done

No malformed/tampered backup can reach the destructive Room commit path, and no stale restore decision can be committed against a different input artifact or business snapshot.

---

## 8.11 Task P5-10 — Phase 5 semantic regression matrix

### Required behavior tests

Create/update current-source tests so the matrix covers:

- only Activation/Renewal/Refill create ISP-driven subscriber-charge ledger entries; ordinary local debt/payment/advance/correction ledger activity remains separately supported;
- lookup/details/password/status/balance operations do not create ledger rows;
- notes retain LWW semantics and are not financial history;
- derived positions remain consistent with accepted baseline + ledger;
- protected legacy fields remain intact through sync/import/restore/migration;
- ISP-side deletion preserves local financial history;
- settings/credentials remain session isolated;
- existing reliable IDs are preserved.

The test inventory must be derived from current source, not copied from stale manifests.

## 8.12 Task P5-11 — Phase 5 evidence and exit gate

Evidence must include:

- field ownership matrix;
- credential/session isolation tests;
- financial deletion protection tests;
- final schema/cascade scan;
- migration tests from supported versions;
- controlled interruption tests;
- backup compatibility matrix;
- identity conservation report;
- current-position oracle results;
- source SHA and actual test execution results;
- updated requirement/invariant compliance matrix.

Blocking failures:

```text
ISP delete removes financial history
ON DELETE CASCADE can remove financial rows
protected semantic field is silently altered
credential response crosses session boundary
reliable transaction ID changes during migration
migration loses a row or merges legitimate rows
backup replay creates stale transport mutations
backup restore loses valid business data
```

---

# 9. Cross-phase verification architecture

## 9.1 One verification chain

Every phase uses the same evidence model:

```text
current source
+
current test corpus
+
actual test execution
+
actual instrumentation execution where required
+
actual build/release artifact where required
+
artifact/hash identity
```

Narrative reports, historical manifests, completion JSON, and agent assertions are supporting context only.

## 9.2 Validator self-test requirement

Whenever `scripts/*` verification tooling changes, the existing adversarial fixture family must still prove:

- command failure;
- timeout;
- NO-SOURCE;
- missing tests;
- fake/narrative PASS;
- weakened requirement regex/rules;
- forbidden implementation patterns.

No validator change may close based only on its own PASS output.

## 9.3 Required context-aware scanning

Keep three scan concepts distinct:

```text
active-guidance scan
frozen-authority integrity scan
historical-reference scan
```

Do not use blind substring absence where rejected terms can legitimately occur inside frozen authority documents.

## 9.4 Allowlist control

For each phase:

```text
inventory
→ proposed allowed paths
→ freeze allowlist
→ modify
→ verify changed paths
```

No new path is added after modification as retrospective authorization.

## 9.5 Current test-corpus control

At each phase start and closure:

1. enumerate actual test files under `app/src/test/**` and `app/src/androidTest/**`;
2. enumerate Gradle test/instrumentation source sets and task mapping;
3. compare actual corpus against machine contract expectations;
4. create missing current tests;
5. execute the required JVM and Android/instrumentation tasks;
6. capture result artifacts;
7. regenerate current test identity.

Historical test manifests may not be used as current test identity.

## 9.6 Artifact-bound evidence dependency

Final evidence identity is not source SHA alone. Every gate evidence bundle must bind:

```text
source identity
build/release artifact identity
current test-corpus identity
toolchain identity (JDK/Gradle/Android tooling)
execution environment identity (API/device/emulator/network mode)
required external fixture/config identity
```

A matching source/build SHA with materially different execution configuration is not equivalent evidence.


A dependent gate may not consume evidence merely by file name, report title, or narrative claim. Every dependency edge must bind its evidence to the **same verified source/build identity**.

For every final gate closure, record at minimum:

```text
implementation source SHA / exact artifact identity
test corpus identity
required upstream dependency evidence identity
required downstream closure evidence identity where applicable
execution result identity
release/build artifact identity when required
```

Example for final G3 closure:

```text
G3 source/build identity = X
G5 identity proof identity = X
G4 stale-result proof identity = X
G3 Restore/Import execution evidence identity = X
```

Evidence from a different implementation identity is not acceptable for final closure.

---


## 9.7 Evidence Invalidation Matrix — mandatory after every shared-surface change

Final gate evidence is valid only for the exact implementation artifact on which it was executed. A later phase change to a shared implementation surface can invalidate an earlier gate even when the earlier gate's own tests previously passed.

The following baseline invalidation classes are mandatory. The exact impacted-gate map must be frozen into the current phase evidence manifest before implementation begins and must be updated, not weakened, when a new shared surface is discovered.

| Changed surface/family | Minimum impacted gates | Mandatory re-run scope before final closure |
|---|---|---|
| `AppDatabase.kt`, Room entities/DAOs, schema/migration assets, shared `Models.kt` | G1,G2,G3,G4,G5,G6,G7 | all persistence/transaction/identity/migration suites that consume the changed schema/model |
| `SyncRepositoryImpl.kt`, `OutboxManager.kt`, `RemoteSyncCoordinator.kt`, sync metadata/cursor surfaces | G1,G2,G3,G4,G5,G6 | G1 durability + G2 transport + G3 restore transport + G4 concurrency/order + affected G5 identity sync suites |
| `Repositories.kt`, domain repository interfaces, account/ledger write paths | G1,G3,G5,G6,G7 | G1 atomicity + G3 position/restore + G5 identity/idempotency + G6/G7 semantic/migration suites |
| `BackupManager.kt`, importer/restore surfaces | G3,G4,G5,G6,G7 | restore/merge/lineage + identity + migration/backup compatibility + destructive-input safety |
| `EarthlinkNetwork.kt`, external-operation gateway adapters, operation call sites | G1,G6 | external-operation durability + unknown-outcome capability + credential/semantic regression |
| `PreferenceManager.kt`, auth/session/security surfaces, credential-related UI/viewmodels | G1,G5,G6,G7 | identity/session isolation + credential recovery + secret/evidence handling |
| contract/validator/test-environment files | every gate whose requirement, invariant, fixture, or evidence rule is affected | validator self-tests + affected product suites; never accept a contract-only PASS without product execution |

The matrix is intentionally conservative: shared persistence/sync changes invalidate multiple gates rather than assuming a narrow local effect.

### Mandatory invalidation rule

```text
change shared surface
    ↓
compute impacted gates from matrix
    ↓
invalidate prior evidence for impacted gates
    ↓
re-run required suites on final/current artifact
    ↓
issue new evidence identity
```

No gate may retain a “FINAL PASS” label on historical evidence after an impacted shared surface has changed.

## 9.8 Final Artifact Closure Sweep — mandatory after Phase 5

Before the G8 boundary, create one final source/build artifact identity `F` from the Phase-5-complete source tree and perform a final closure sweep.

At minimum re-run:

```text
G1 affected suites
G2 affected suites
G3 affected suites
G4 affected suites
G5 affected suites
G6/G7 suites
```

The exact suite list is produced by the Evidence Invalidation Matrix and the final changed-surface inventory. Only evidence bound to `F` (including test corpus, toolchain, execution environment, and fixture/config identity) counts as final evidence.

Final artifact closure is not a repetition of every historical test for its own sake. It is a dependency-aware invalidation/revalidation pass that proves the final implementation artifact has not regressed an earlier gate.

## 9.9 Build dependency provenance for final evidence

Final evidence must additionally record effective dependency provenance sufficient to distinguish:

```text
same source SHA
≠
same effective binary
```

At minimum capture, where available from the build system:

```text
resolved dependency graph / lock identity
plugin versions and resolution source
reproducible build inputs used by the tested artifact
artifact repository/source identity when material
```

This is a final evidence-hardening requirement. It does not require introducing a new runtime component.

## 9.10 Test-sensitivity / known-bad mutation proof

For each gate, select a small mandatory subset of known-bad mutations that should make the corresponding test fail. This is not full-codebase mutation testing; it is an oracle-sensitivity proof.

Minimum mutation set:

```text
G1: remove pending durability / reuse a fresh transaction ID on recovery
G2: reintroduce terminal obligation deletion / skip server confirmation
G3: merge same-ID divergent payloads silently / perform network inside final Room transaction
G4: skip generation check / advance remote cursor in a different ordering domain
G5: replace stable source provenance with destination ROWID / permit same-ID divergent financial payload overwrite
G6/G7: restore ON DELETE CASCADE / alter protected legacy meaning / bypass migration identity conservation
```

For every selected mutation:

```text
apply isolated known-bad change
→ execute the targeted proof suite
→ expected result = FAIL
→ restore checkpoint
```

A test suite that stays green under a required known-bad mutation is insufficiently sensitive and blocks gate closure until the oracle is strengthened.

# 10. Phase gates and evidence ledger

The ledger distinguishes **implementation/provisional evidence** from **final gate closure**. A phase workstream can complete without closing every gate whose frozen dependencies live in later phases.

## 10.0 Final-artifact closure precondition

No final G1–G7 closure claim survives unchanged into the G8 boundary when later phases have modified a shared surface that can affect the earlier gate. The Evidence Invalidation Matrix in Section 9.7 determines the required re-runs.

After Phase 5:

```text
final source/build artifact = F
        ↓
compute changed-surface inventory from Phase 1 checkpoint to F
        ↓
invalidate impacted historical gate evidence
        ↓
re-run required affected suites on F
        ↓
issue final evidence identity for F
        ↓
only then declare final G1–G7 closure
```

This final-artifact sweep is mandatory even when every phase previously reported a local PASS.

## Gate G1 / Phase 1 durability sub-gate

Must prove independently:

- pending-operation durability;
- confirmed success → durable local accounting/current/outbox obligation;
- unknown result remains pending without blind retry;
- original transaction identity is reused;
- Firebase is not a prerequisite for ISP operation.

G1 is independently closed at the end of Phase 1 once its own executable evidence is complete.

## Gate G2 / Phase 1 transport sub-gate — provisional then final

Phase-1 provisional evidence must prove:

- no terminal DEAD_LETTER business state;
- per-item poison isolation;
- orphan handling;
- deterministic Firestore identity under the frozen identity contract;
- safe transport reconstruction algorithm;
- direct atomic local ledger/current/outbox commit;
- lost-ACK behavioral proof;
- same-logical-operation duplicate-initiation suppression;
- same-ID/divergent-payload immutability protection;
- two-device incomplete-cloud convergence fixture.

Final G2 closure additionally requires:

```text
G5 stable identity implementation + evidence
        ↓
re-run G2 identity/lost-ACK suite
        ↓
artifact-bound final G2 closure
```

## Gate G3 / Phase 2 — provisional then final

Provisional G3 evidence must prove:

- Restore Merge complete-lineage selection;
- final Room boundary has no network/UI waits;
- deterministic current-position rebuild;
- safe transport reconstruction;
- interruption/rollback safety;
- actual Android import/restore execution;
- restore obligation disposition bound to resulting lineage;
- restore snapshot/TOCTOU linearization proof.

Final G3 closure additionally requires:

```text
G5 stable identity / Merge identity evidence
+
G4 generation + stale-result protection evidence
+
G3 Restore/Import evidence
+
common artifact identity
```

## Gate G4 / Phase 3 evidence

Must prove:

- persisted generation exists and initializes correctly;
- same-transaction generation validation;
- full-replacement lineage invalidation;
- same-lineage normal mutations;
- stale remote result rejection;
- lock order and no network under protected business transaction;
- remote cursor/query coordinate consistency;
- delete/upsert/tombstone ordering safety;
- restore lineage attachment and obligation linearization.

G4 evidence is consumed by final G3 closure.

## Gate G5 / Phase 4 identity evidence

Must prove:

- stable identity on every ledger creation path;
- repeated-import idempotency;
- distinct legitimate identical rows remain distinct;
- local ID = Firestore document ID;
- reliable existing IDs preserved;
- source-provenance identity is deterministic and not based on destination ROWID;
- same-ID/divergent-immutable-payload protection;
- two-device convergence evidence is bound to final G5 identity semantics.

G5 evidence is consumed by final G2, final G3, and final G7 closure.

## Gates G6/G7 / Phase 5 evidence

Must prove:

- field ownership and protected-field preservation;
- credential/session isolation;
- ISP deletion preserves history;
- no destructive cascade;
- migration ID/data/semantic/lineage conservation;
- migration interruption safety;
- old-backup compatibility;
- credential new-device/reinstall recovery;
- migration semantic conservation;
- destructive-restore input integrity and decision binding.

Final G7 closure additionally requires the final G5 identity evidence bound to the same implementation/build identity.

# 11. Final cross-phase adversarial review before G8 boundary

Before entering the later G8 certification phase, run one integrated adversarial review against the final Phase-5 source tree.

The review must answer all of these directly with source + executable proof:

1. Can any ledger obligation disappear silently?
2. Can a ledger transaction duplicate after retry/lost ACK?
3. Can two distinct legitimate historical rows collapse to one ID?
4. Can a remote ISP delete event physically delete local financial history?
5. Can account deletion cascade to ledger rows?
6. Can a stale previous-lineage remote operation mutate a newly restored/cleared dataset?
7. Can a network call or UI wait occur inside the final business Room transaction?
8. Can Firebase unavailability prevent an ISP business operation that is otherwise executable?
9. Can unknown external outcome trigger blind external retry?
10. Can protected legacy semantic fields be reset or repurposed?
11. Can a delayed credential/session response mutate another active account/session?
12. Can migration silently change a reliable ledger ID?
13. Can migration silently merge or delete a legitimate transaction?
14. Can backup restore replay historical transport metadata as if it were current authority?
15. Can a poison transport item block unrelated valid obligations?
16. Can an orphaned outbox obligation disappear or spin indefinitely?
17. Can any historical report/manifest still redirect implementation or gate decisions away from the current authority?
18. Can any validator be weakened instead of fixing the product behavior it guards?
19. Has any phase introduced a rejected architecture by another name?
20. Is every phase closure supported by current executable evidence rather than narrative PASS?
21. Can two devices with incomplete/stale cloud copies converge to the union of independent ledger transactions without loss or duplication?
22. Can the same logical ISP operation be initiated twice concurrently before a shared stable identity is established?
23. Can an unknown external outcome be resolved later from actual ISP evidence without blind retry or user-only `mark completed` semantics?
24. Can the same ledger ID arrive with a materially different immutable financial payload and mutate history?
25. Can a late remote upsert resurrect state after a newer delete/tombstone, or can an older delete erase newer state?
26. Can restore preparation miss a new local/outbox obligation created after the obligation snapshot?
27. Can an old-lineage outbox obligation be reattached after restore merely because a local entity ID is reused?
28. Can a final PASS be produced with matching source/build identity but materially different toolchain, device/API, network/mock mode, or external fixture configuration?
29. Can two identical business-parameter renewals at different times be incorrectly deduplicated because the Operation Intent ID was derived from parameters rather than a per-action intent?
30. Can a valid external operation remain `UNKNOWN` forever because the implementation assumed a transaction-status API that the current ISP surface does not provide?
31. Is `REMOTE DURABILITY CONFIRMED` based on an authoritative, executable server-confirmation contract rather than client ACK alone?
32. Can a later phase modify a shared surface and leave earlier gate evidence falsely labeled FINAL?
33. Does the final schema have one executable Room migration chain from v11 through all intermediate versions to the Phase-5 schema?
34. Can the outbox remain durable but never execute because its scheduler/restart/network-recovery triggers are not proven?
35. Can a large retained failure population starve valid obligations or exhaust storage before delivery resumes?
36. Can Direct Atomic Room restore/import fail at the supported dataset envelope due to transaction duration, memory, WAL, disk, or background-execution pressure?
37. Can a modified backup pass ordinary hashing while lacking the authority-defined authenticity/trust root?
38. Can credentials leak into logs, failure output, checkpoint records, or evidence artifacts?
39. Can an authenticated UID read/write another UID's Firebase documents despite correct deterministic document IDs?
40. Can Restore Merge silently choose one payload when the same immutable transaction ID carries divergent financial meaning?
41. Can ordinary local payment/debt/advance/correction calls duplicate or silently overwrite because idempotency semantics were only tested for ISP-driven operations?
42. Does every affected-gate re-run decision derive mechanically from the changed-surface/evidence invalidation matrix?
43. Can a test suite be green while an invariant has been deliberately removed, because no known-bad mutation demonstrates test sensitivity?
44. Can a new implicit local state machine emerge under another name without an explicit state/transition/owner/persistence boundary?
45. Is long-term financial-history retention explicitly treated as a growth/non-goal boundary rather than silently assuming unlimited backup/query capacity?
46. Are all time/order fields classified by producer, clock, trust, and allowed comparison domain so device clock skew cannot become remote ordering authority?
47. Does the final artifact preserve build dependency provenance sufficiently to distinguish the effective binary from a source-identical but dependency-different build?

A failure on any item blocks the G8 boundary.

---

# 12. Explicit non-goals for Phases 1–5

Do not implement or introduce:

```text
Phase-6/G8 external certification runtime
runtime certification subsystem

dataset_id
published_dataset_id
staging database
identity registry
generic reconciliation engine
generic synchronization state machine
runtime governance registry
terminal DEAD_LETTER business state
```

Do not use Phases 1–5 as a generic cleanup pass for unrelated API/UI concerns.

Do not reopen the frozen architecture unless current executable evidence demonstrates:

1. a direct contradiction with a frozen product/architecture decision; or
2. a correctness invariant that cannot be satisfied within the accepted architecture.

An ordinary code defect remains an implementation defect.

---

# 13. Definition of done for the integrated Phases 1–5 plan

The implementation is ready to cross into the later G8 certification boundary only when:

```text
Phase 1 implementation complete
    ├── G1 FINAL PASS
    └── G2 PROVISIONAL PASS (identity-dependent final closure deferred)

Phase 2 implementation complete
    └── G3 PROVISIONAL PASS

Phase 3 implementation complete
    └── G4 FINAL PASS

Phase 4 implementation complete
    ├── G5 FINAL PASS
    ├── re-run/finalize G2 dependency closure
    └── re-run/finalize G3 dependency closure

Phase 5 implementation complete
    ├── G6 FINAL PASS
    └── G7 FINAL PASS with final G5 identity evidence

Then:
    integrated cross-phase adversarial review = PASS
    all evidence artifact-bound to one final implementation identity
    all evidence also bound to the required toolchain/execution environment/fixture identity
    current test corpus matches machine contracts
    validator/meta-gate self-tests pass
    forbidden-pattern/context scans pass
    no phase was closed from historical narrative evidence
```

The final closure state is therefore: **G1, G2, G3, G4, G5, G6, and G7 all final PASS**, with the dependency edges below explicitly satisfied:

```text
G5 identity → G2 final closure
G5 identity → G3 final closure
G4 stale-result protection → G3 final closure
G5 identity preservation → G7 final closure
```

Only then does the project move to:

```text
Phase 6 / G8 — External Certification
        ↓
Full Adversarial Verification
        ↓
Independent Final Zero-Trust Audit
        ↓
Production Authorization Decision
```

Phase 6/G8 remains outside this implementation plan.

# 14. Planning review record

## 14.1 Primary review — completeness and dependency check

Checked:

- all P1-G2-REQ-01..07 included;
- all P2-G3-REQ-01..06 included;
- all P3-G4-REQ-01..05 included;
- all P4-G5-REQ-01..04 included;
- all P5-G6-REQ-01..06 included;
- G1 durability lane explicitly included;
- current source targets named for each implementation family;
- current test targets named/created where missing;
- verification follows implementation rather than being deferred entirely to final closure;
- implementation order is separated from gate closure order;
- G1 and G2 are separate sub-gates inside Phase 1;
- G3 is explicitly provisional until G4 + G5 dependencies are proven;
- G2 final closure is explicitly dependent on G5 identity evidence;
- G7 final closure is explicitly dependent on final G5 identity evidence;
- Phase 6 is boundary-only.

## 14.2 Secondary review — hidden assumption challenge

Rejected assumptions that would otherwise weaken the plan:

- historical completion JSON proves current source completeness;
- test manifests prove current test existence;
- `DEAD_LETTER` comments/docs are enough to claim the state is gone;
- UUID generation alone proves import identity safety;
- Room transactions alone prove business atomicity without failure injection;
- Firestore document identity alone proves lost-ACK behavior without retry execution;
- successful API response plus a UI callback is sufficient G1 durability;
- sign-out always means lineage change even if no data is cleared;
- all old backup transport metadata is current authority;
- any existing deduplicator is automatically correct identity policy;
- `ON DELETE CASCADE` is harmless if current code rarely calls delete;
- Phase 2 can claim final G3 closure before G4/G5 evidence exists;
- Phase 1 can claim final G2 closure before G5 identity evidence exists;
- a historical backup outbox row can be treated as a current obligation merely because it is present in the archive;
- a current unresolved obligation can be silently discarded because its target is absent after Restore Replace;
- Renewal and Extension may silently fork into separate persisted financial operation identities.
- a validator PASS is sufficient after changing the validator itself.

## 14.3 Adversarial / frozen-spec review

Explicitly checked:

- no task introduces rejected architecture;
- outbox remains transport-only;
- ledger remains business authority;
- no generic reconciliation/state machine is required;
- no terminal business-state `DEAD_LETTER` is reintroduced under another name;
- G4 generation remains distinct from remoteVersion;
- Restore Merge remains complete-lineage selection;
- protected legacy fields remain protected;
- ISP-side deletion remains non-destructive to local financial history;
- migration remains business-data-preserving;
- external operation recovery remains bounded and user-verification-based rather than autonomous distributed reconciliation.

## 14.4 Final requirements / consistency check

Every planned task has:

```text
implementation target
verification target
definition of done
```

No task depends on historical source identity in place of current source inspection.

No phase is closed by narrative PASS.

No requirement is intentionally demoted merely because its implementation is inconvenient.

Final closure uses the same current source/build identity for all dependency evidence consumed by the gate.

The current test-corpus inventory includes JVM unit tests, Android instrumentation tests, and their Gradle task/source-set mapping.

The restore transport rule is expressed as an explicit fixed disposition table rather than a prose-only principle.

Renewal / Extension is normalized to the existing canonical persisted `RENEWAL` financial category; the external extension path must not bypass the G1 durability boundary.

---

## 14.5 Amendment review — Reviewer 1 Zero-Trust findings

The independent review identified six required amendments, all applied in this revision:

1. **Closure topology correction:** implementation order remains G2 → G3 → G4 → G5 → G6/G7, while final G2/G3/G7 closure depends on later evidence.
2. **Restore transport algorithm:** prose-only reconstruction was replaced by a fixed disposition table and pre-restore obligation inventory.
3. **G1 operation normalization:** Renewal / Extension is explicitly mapped to the canonical persisted `RENEWAL` category used by the current source, and the extension path is required to share the same durability boundary.
4. **Test-corpus governance:** inventory now includes `app/src/test`, `app/src/androidTest`, Gradle source sets, and task mapping.
5. **Artifact-bound dependency evidence:** final gate closure requires source/build identity alignment across dependent evidence.
6. **Runtime audit-event caution:** reconstruction proof remains verification evidence unless an already-authorized runtime metadata surface exists; no new business/runtime authority is introduced merely for logging.

This amendment pass preserves the frozen architecture and the canonical implementation sequence; it corrects only the plan's closure topology, explicit transport disposition, identity normalization, corpus inventory, and evidence binding.

## 14.6 Adversarial / frozen-spec re-run after amendments

Re-checked:

- no staging database, identity registry, generic reconciliation engine, generic synchronization state machine, runtime governance registry, or dataset-ID architecture was introduced;
- ledger remains business authority and outbox remains transport-only;
- G1 remains the bounded durability lane and does not become autonomous unknown-outcome reconciliation;
- `DEAD_LETTER` remains forbidden as a terminal business state;
- Restore Replace/Import transport reconstruction does not turn historical backup metadata into current authority;
- current obligations are not silently abandoned;
- G3 cannot claim final closure before G4/G5 dependency evidence;
- G2 cannot claim final identity closure before G5 evidence;
- G7 cannot claim final migration closure without G5 identity-preservation evidence;
- G8 remains external and outside the implementation plan.

## 14.7 Final requirements / consistency check after amendments

Every critical task now carries or inherits an explicit traceability chain:

```text
authority / requirement
→ implementation target
→ verification target
→ definition of done
→ dependency evidence requirement where applicable
```

The plan distinguishes:

```text
implementation complete
≠
provisional gate evidence
≠
final gate closure
```

No final gate may consume evidence from a different source/build identity.

The current test-corpus inventory includes JVM unit tests, Android instrumentation tests, and their Gradle task/source-set mapping.

The restore transport rule is expressed as an explicit fixed disposition table rather than a prose-only principle.

Renewal / Extension is normalized to the existing canonical persisted `RENEWAL` financial category; the external extension path must not bypass the G1 durability boundary.

## 14.8 Amendment Round 2 — Red-Team destruction review disposition

Reviewer 1 returned `AMENDMENT REQUIRED — RED TEAM FOUND MATERIAL GAPS`. The plan was amended with executable coverage for all reported blockers and high-risk cases:

1. Two-device incomplete-cloud convergence;
2. concurrent same-logical-operation duplicate initiation;
3. complete unknown-outcome resolution protocol;
4. same-ID/divergent immutable-payload protection;
5. restore obligation attachment to resulting lineage rather than entity ID alone;
6. explicit distinction between ISP-driven subscriber-charge entries and ordinary local debt/payment/advance/correction activity;
7. credential new-device/reinstall recovery;
8. remote delete/upsert/tombstone ordering and cursor-coordinate correctness;
9. restore TOCTOU/snapshot linearization;
10. semantic conservation in migration;
11. backup tamper/corruption rejection and restore decision binding;
12. full evidence environment identity.

The amendments remain within the frozen architecture and add no generic reconciliation or synchronization layer.

## 14.9 Adversarial / frozen-spec re-run after Red-Team Amendment Round 2

Checked explicitly:

- no staging database, dataset-ID layer, identity registry, generic reconciliation engine, generic synchronization state machine, or runtime governance registry was introduced;
- two-device convergence uses stable transaction identity and existing per-item transport semantics;
- duplicate initiation is resolved at the existing operation boundary rather than by a new synchronization architecture;
- unknown-outcome resolution remains bounded and verification-based;
- immutable ledger payload divergence cannot overwrite financial history;
- Restore transport validity is tied to resulting lineage, not merely entity ID;
- G4 generation remains distinct from remoteVersion/updatedAt/cursor semantics;
- ordinary debt/payment/advance/correction ledger activity remains supported and distinct from ISP-driven subscriber-charge creation;
- credential recovery remains UID-scoped and does not create a global identity/credential registry;
- migration remains business-data/semantic preserving;
- malformed backup inputs cannot reach destructive commit;
- dependent-gate evidence is rejected when toolchain/execution environment/fixture identity is materially different;
- G8 remains external and outside this implementation plan.

## 14.10 Final requirements / consistency check after Red-Team Amendment Round 2

All reported P0/P1 blockers now map to exact implementation targets, executable verification, and closure dependencies. The plan explicitly distinguishes:

```text
implementation complete
≠ provisional gate evidence
≠ final gate closure
```

No phase reaches final closure merely because its local tests pass. Cross-gate dependencies and artifact/environment identity must also match the verified implementation.

---

## 14.11 Amendment Round 3 — Agent execution safety review

Reviewer 1 identified execution-protocol gaps rather than a new product/architecture defect. The plan was amended without changing the frozen architecture.

Mandatory additions: 

1. bounded execution packets instead of one-shot execution of the complete five-phase plan;
2. explicit precheck → implement → verify → checkpoint → stop lifecycle;
3. stop-the-line behavior for compile/test/validator/scope/evidence failures;
4. checkpoint identity and resume protocol for timeout/interruption;
5. development rollback boundary distinct from runtime rollback;
6. explicit prevention of silent phase-to-phase progression;
7. P1 test-corpus target expansion to `app/src/test/**`, `app/src/androidTest/**`, and Gradle task/source-set mapping;
8. P1-06/P3-05 split so Phase 1 defines only the abstract lineage snapshot contract and Phase 3 binds it to persisted G4 generation;
9. correction of P3-05 `AppDatabase.kt` to the verified current path `app/src/main/java/com/example/core/database/AppDatabase.kt`;
10. packet map preserving one master plan while constraining each agent run to one bounded task/test cycle, with micro-step splits allowed only inside an oversized task.

## 14.12 Adversarial / frozen-spec re-run after Amendment Round 3

Checked explicitly: 

- the execution protocol changes process orchestration only and introduces no product/architecture mechanism;
- no phase may be executed as a single 480-second one-shot implementation;
- timeout/interruption cannot silently advance the task sequence;
- failed tests/validators cannot be weakened or reclassified to obtain PASS;
- checkpoint identity is tied to source/build/test/environment identity;
- Phase 1 does not introduce a duplicate G4 persisted generation mechanism;
- P3-05 is the only planned binding point from the Phase-1 abstract lineage token to persisted G4 generation;
- current test corpus control is aligned with the actual source sets `app/src/test/**` and `app/src/androidTest/**`;
- the verified `AppDatabase.kt` path is consistent across the plan;
- phase closure topology remains unchanged and still requires dependent-gate evidence;
- G8 remains external certification only.

## 14.13 Final requirements / consistency check after Amendment Round 3

The implementation master plan now contains both: 

```text
PRODUCT / ARCHITECTURE / INVARIANT PLAN
        +
AGENT-SAFE EXECUTION PROTOCOL
```

The plan remains one integrated Phases-1–5 document, but execution is explicitly packetized, checkpointed, resumable, and stop-the-line. Every packet retains the original task's implementation target, verification target, and definition of done. No new architecture, business state machine, or runtime governance mechanism has been added by the execution protocol.

## 14.14 Amendment Round 4 — multi-lens hardening review disposition

Reviewer 1 returned `NOT YET FINAL — 5 BLOCKERS + 8 HIGH RISKS` after independent passes for Authority/Requirements Conformance, Zero-Trust Architecture & Evidence, Red-Team/Destruction, and Execution Feasibility + Agent Safety.

The master plan is amended without changing the frozen architecture. The following hardening controls are now mandatory:

### Blocker resolutions

1. **Three-Identity Contract:** Business Transaction ID, Source Provenance ID, and Operation Intent ID are explicitly separated; duplicate-initiation semantics use Operation Intent ID, while legitimate repeated business actions receive new intent/transaction identities.
2. **Final Artifact Closure Sweep:** later shared-surface changes invalidate earlier gate evidence; final G1–G7 closure is revalidated against the final Phase-5 artifact identity.
3. **Schema Migration Choreography:** Room migration graph is explicitly chained from v11 through the G4 generation schema version and then the G6/G7 schema semantics version(s), with executable predecessor/successor coverage.
4. **External Verification Capability Gate:** P1-10 must prove the existing accepted Earthlink subscriber-state verification workflow before implementing UNKNOWN resolution; absence of a safe discriminator stops implementation rather than inventing a new status/reconciliation architecture.
5. **Remote Durability Confirmation Contract:** Firebase transport completion requires the defined server-side read-back/correlation semantics; client ACK alone is insufficient for final transport confirmation.

### High-risk hardening

6. Outbox scheduler/liveness and process/network recovery are explicit verification targets using the current WorkManager/SyncWorker mechanism.
7. Retry/backoff/diagnostic/scan bounds are machine-defined rather than merely described as “bounded.”
8. Fairness/starvation and retained-failure storage stress are mandatory.
9. Direct Atomic Room restore/import receives a measured capacity envelope without introducing staging architecture.
10. Backup tamper rejection now requires explicit authenticity/trust-root classification consistent with the frozen security authority.
11. Credential/evidence secret redaction and cleanup are explicit; Firebase cross-UID authorization is tested separately from deterministic identity.
12. Restore Merge includes same-ID/divergent-immutable-payload conflict handling.
13. Ordinary local payment/debt/advance/correction idempotency semantics are explicitly covered.
14. Evidence invalidation is governed by a changed-surface matrix rather than narrative judgment.
15. A mandatory subset of known-bad mutations is required to prove test sensitivity.
16. The plan now records time-domain semantics and build dependency provenance as final evidence inputs.

## 14.15 Adversarial / frozen-spec re-run after Amendment Round 4

Checked explicitly:

- all five blocker resolutions remain inside the frozen Direct Atomic Room + stable identity + per-item outbox + G4 lineage + lineage-aware Restore Merge architecture;
- Operation Intent ID is not an identity registry;
- the final-artifact sweep prevents earlier gate evidence from surviving later shared-surface changes without revalidation;
- schema version choreography cannot bypass the tested predecessor chain;
- unknown-outcome handling remains user/subscriber-state verification based and does not become autonomous reconciliation;
- remote durability confirmation remains transport evidence and does not promote outbox into business authority;
- WorkManager/SyncWorker is used only as the current scheduling mechanism, not as a generic synchronization state machine;
- bounded retry/diagnostic/storage controls do not delete business obligations;
- Direct Atomic Room remains the implementation architecture unless measured evidence proves the invariant cannot be satisfied;
- backup authenticity classification and secret scrubbing add evidence/security controls only and do not add runtime governance architecture;
- Firebase UID authorization is tested independently from Firestore document identity;
- same-ID/divergent-payload conflicts are rejected rather than silently merged;
- migration graph remains one executable chain;
- G8 remains external certification only.

## 14.16 Final requirements / consistency check after Amendment Round 4

The final integrated plan now contains:

```text
Frozen authority + invariants
        +
Current-source implementation targets
        +
Cross-gate closure topology
        +
Three-Identity Contract
        +
G1 external-verification feasibility gate
        +
G2 remote-durability contract
        +
G2 liveness/fairness/capacity controls
        +
Direct Atomic Room capacity envelope
        +
Security/evidence secret policy
        +
Evidence invalidation matrix
        +
Final Artifact Closure Sweep
        +
Schema migration choreography
        +
Test-sensitivity proof
        +
Agent-safe bounded execution protocol
```

No new rejected architecture mechanism was introduced. No final gate is considered proven solely by historical reports or a prior phase PASS. Final G8 readiness remains dependent on one final implementation artifact and its bound executable evidence.

# 15. Governing source references used to author this plan

Primary authority/architecture:

```text
docs/authority/Target Product Contract v0.6.md
docs/authority/G1-G8 Consolidated Architecture Summary.md
docs/authority/Final Independent Adjudication Memo.md
docs/authority/EARTHLINK_IMPLEMENTATION_HANDOVER_APPENDIX_v1.0.md
```

Machine requirements/invariants:

```text
contract/phase_requirements.yaml
contract/invariant_contract.yaml
contract/invariant_test_map.yaml
contract/backup_state_classification.yaml
contract/forbidden_patterns.yaml
contract/test_environment_matrix.yaml
```

Current implementation surfaces inspected for planning:

```text
app/src/main/java/com/example/core/database/AppDatabase.kt
app/src/main/java/com/example/core/model/Models.kt
app/src/main/java/com/example/core/model/StateOwnershipContract.kt
app/src/main/java/com/example/core/sync/OutboxManager.kt
app/src/main/java/com/example/core/sync/SyncRepositoryImpl.kt
app/src/main/java/com/example/core/sync/RemoteSyncCoordinator.kt
app/src/main/java/com/example/core/sync/DataOperationCoordinator.kt
app/src/main/java/com/example/core/backup/BackupManager.kt
app/src/main/java/com/example/core/sync/UtowerImporter.kt
app/src/main/java/com/example/core/sync/TransactionDeduplicator.kt
app/src/main/java/com/example/core/sync/SubscriberMatcher.kt
app/src/main/java/com/example/data/repository/Repositories.kt
app/src/main/java/com/example/domain/repository/Interfaces.kt
app/src/main/java/com/example/core/security/PreferenceManager.kt
app/src/main/java/com/example/ui/viewmodels/EarthlinkSearchViewModel.kt
app/src/main/java/com/example/ui/viewmodels/AuthViewModel.kt
app/src/main/java/com/example/ui/screens/UserDetailScreenV2.kt
contract/closure_contract.yaml
contract/closure_schema.json
scripts/production_gate.sh
scripts/run_verified_command.py
scripts/scan_forbidden_patterns.py
scripts/test_forbidden_pattern_registry.py
scripts/verify_invariant_contract.py
scripts/verify_test_environment_matrix.py
scripts/generate_and_verify_compliance_matrix.py
build configuration / resolved dependency evidence used for final artifact provenance
```

Historical reports/evidence were used only to understand why earlier decisions were made and to avoid repeating known failures. They are not used as present-tense implementation authority or closure proof.
