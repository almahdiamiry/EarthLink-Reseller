# EARTHLINK RESELLER APP V1 — CONVERSATION HANDOVER

**Purpose:** Transfer the complete architectural context from the prior investigation into a fresh implementation-focused conversation without reopening settled architecture decisions.

**Product:** Earthlink Reseller App V1
**Forensic baseline:** `earthlink-reseller-v1 (71).zip`
**Artifact sequence:** 71 (ZIP/artifact sequence, NOT Android VersionCode)
**Application version observed in source:** VersionCode 68 / VersionName 1.68.0
**Product contract:** `Target Product Contract v0.6`
**Engineering architecture summary:** `G1–G8 Consolidated Architecture Summary`
**Final adjudication:** `Final Independent Adjudication Memo`

---

# 1. HANDOVER PURPOSE

This handover transfers the final state reached after a long forensic, architectural, adversarial, and cross-gate review process.

The key rule for the next conversation is:

> **The architecture is frozen in principle. The next phase is implementation and executable verification, not another architecture redesign cycle.**

Do not reopen the architecture because a current source defect exists. First classify it as an implementation or verification defect unless evidence proves that the frozen architecture cannot satisfy a product requirement or correctness invariant.

---

# 2. AUTHORITY HIERARCHY

Use this hierarchy for all future decisions:

```text
Target Product Contract v0.6
        ↓
Product / business authority

G1–G8 Consolidated Architecture Summary
        ↓
Engineering interpretation

Final Independent Adjudication Memo
        ↓
Final architectural judgment / implementation boundary

Current implementation artifact
        ↓
Actual implementation state to verify

Executable tests / build artifacts
        ↓
Actual verification evidence
```

Important distinction:

```text
ZIP 71 forensic baseline
    ≠
current patched implementation
    ≠
verified implementation
    ≠
production-ready release
```

Never claim a current patched artifact is defective solely because ZIP 71 had that defect. Re-check the exact artifact under verification.

---

# 3. FINAL ARCHITECTURE STATE

## Verdict

**ARCHITECTURE FROZEN — APPROVED IN PRINCIPLE; TARGETED IMPLEMENTATION CORRECTIONS REQUIRED.**

Operational meaning:

```text
Architecture
    FROZEN / APPROVED

Implementation
    AUTHORIZED TO PROCEED

Verification
    MANDATORY

Production
    NOT YET AUTHORIZED
```

Architecture should only be reopened if new evidence demonstrates:

1. a direct contradiction with a frozen product decision; or
2. a correctness invariant that cannot be satisfied within the frozen architecture.

A normal implementation defect is not, by itself, architecture failure.

---

# 4. FROZEN V1 TARGET ARCHITECTURE

The final target is intentionally small:

```text
Earthlink / ISP API
        ↓
Minimal local durability boundary for ledger-producing operations
        ↓
Local Account Book (Room)
        ├── account/profile semantics
        ├── opening/baseline
        ├── immutable ledger
        └── materialized current position
        ↓
Durable per-item cloud outbox
        ↓
Firebase recovery copy
```

Core principles:

- Local Room account book is the business authority.
- Ledger history is immutable/additive.
- Current financial position is derived/materialized from baseline + eligible ledger history.
- Synchronization transports business data; it does not become business authority.
- External ISP operations are not modeled as a general distributed transaction engine.
- Transport metadata is technical state, not financial history.
- Certification is external evidence infrastructure, not application runtime.

---

# 5. EXPLICITLY REJECTED / DESCOPED ARCHITECTURE

Do not introduce these merely because the implementation is difficult:

```text
dataset_id
published_dataset_id
large dataset staging architecture
separate staging database
identity registry
_GENERIC_ reconciliation engine
generic distributed synchronization state machine
runtime governance registry
runtime certification engine
broad global coordinator for ordinary CRUD
```

Also outside V1 product scope:

```text
general Settings synchronization
remote business deletion as a destructive local capability
user-facing “Mark external operation completed” workflow
autonomous Earthlink reconciliation engine
```

Reconsider only if concrete executable evidence proves the frozen model insufficient.

---

# 6. KEY PRODUCT DECISIONS THAT ARE NOW FIXED

## 6.1 Ledger-producing ISP operations

Only these create reseller financial ledger entries:

```text
Activation
Renewal
Refill
```

Other ISP/API operations such as lookup, details, password, balance, status, and other non-financial calls do not independently create financial ledger entries.

## 6.2 ISP balance vs subscriber debt

These are separate concepts.

```text
ISP balance/credit → ISP authority
Subscriber debt/current position → local baseline + ledger
```

## 6.3 ISP-side subscriber deletion

Fixed rule:

```text
ISP subscriber disappears/deleted
        ↓
local account/history remains
        ↓
legacy/history-only semantics
```

ISP-side deletion must never erase local financial history.

## 6.4 Restore Merge baseline conflict

Fixed product decision:

```text
conflicting baselines
        ↓
user chooses complete snapshot lineage
        ↓
selected baseline
        +
its associated ledger history
```

Never mix:

```text
Baseline A + Ledger B
Baseline B + Ledger A
```

## 6.5 Notes

Notes are reminder metadata only.

```text
notes → simple LWW
```

No field-level merge engine and no ledger semantics.

## 6.6 Legacy/history-only users

If the ISP profile disappears, the local record remains available with the required historical identity, current position, payments/ledger, and notes. Active ISP profile synchronization is not required for these users.

## 6.7 Credentials

Operational ISP credentials are a dedicated UID-scoped recovery domain, not general Settings synchronization.

Credentials must not become ledger state.

## 6.8 loanIqd and legacy semantic fields

Current final position:

- `loanIqd` is retained for legacy/uTower compatibility and historical-data preservation.
- It is NOT an independent V1 financial authority, debt state machine, sync authority, or recovery authority.
- Until implementation/migration has independently verified all surrounding semantics, preserve:
  - `loanIqd`
  - `isLegacy`
  - `isSnapshotHistory`
  - `stateSource`
  - `stateConfidence`
- Do not silently delete, reset, redefine, repurpose, or reinterpret these fields without evidence.

---

# 7. G1–G8 CURRENT STATE

## G1 — External Operation → Local Ledger Durability

### Final decision
**CLOSED WITH BOUNDED RECOVERY LIMITATION.**

Scope: Activation / Renewal / Refill only.

Minimum mechanism:

```text
Generate stable local transaction ID T123
        ↓
Persist small local pending-operation record
        ↓
Call ISP API
        ↓
Confirmed success
        ↓
Room transaction:
    ledger T123
    current position
    outbox obligation
        ↓
complete/remove pending intent
```

Unknown result:

```text
unknown
    ↓
do not blindly repeat
    ↓
retain pending record
    ↓
reopen / inspect actual ISP state/evidence
    ↓
materialize T123 exactly once if operation is established
```

Firebase availability is NOT a prerequisite for executing the ISP operation.

Accepted limitation:

> If complete application data is wiped before the pending obligation is materialized into the ledger and before it exists in any available backup/cloud recovery copy, operation-specific accounting recovery cannot be guaranteed.

This limitation is accepted and does not justify a generic external reconciliation engine.

### Current implementation focus
- move API→ledger accounting into repository/domain boundary;
- generate transaction identity once;
- preserve it through recovery;
- keep Firebase sync independent.

---

## G2 — Cloud / Outbox Durability

### Final decision
**Architecture CLOSED. Implementation status must be judged against exact current artifact. Verification pending.**

Core invariant:

```text
Room ledger exists
AND Firebase durability not confirmed
    ↓
outbox obligation remains
```

Success:

```text
Firebase write confirmed
    ↓
remove corresponding outbox row
```

Failure:

```text
retain + bounded backoff + diagnostic
```

No terminal `DEAD_LETTER` business state.
No retry-count deletion.
No queue-wide poison blocking.

Per-item processing is required.

Ledger Firestore identity:

```text
LocalLedgerEntry.id
    ↓
Firestore document ID
```

### Important backup rule

Historical backup transport metadata is NOT automatically current transport authority.

Do not blindly reactivate old outbox/cursor/retry/tombstone state after Restore/Merge.

But do NOT solve that by silently deleting valid current cloud obligations.

Current transport obligations must be reconstructed/re-established from the resulting business dataset and current sync semantics.

### Known evidence
ZIP 71 had live dead-letter/batch-coupling behavior. A G2-patched artifact has already been reported as removing dead-letter and per-item processing, but runtime verification has not been executed and future claims must identify the exact artifact.

### Required proof
- real Firestore lost-ACK idempotency;
- real Room atomicity;
- poison-pill isolation;
- orphan retention/isolation;
- crash after Firebase success before outbox deletion.

---

## G3 — Import / Restore / Baseline Projection

### Final architecture
**Direct Atomic Room.**

Staging was investigated and deliberately rejected for V1 because the supplied workload does not currently justify the extra dataset/publication architecture.

Actual supplied uTower workload:

```text
199 source user records
198 valid account objects
2,690 historical rows
582,660 bytes compressed
4,845,942 bytes uncompressed
~1.80 MiB primary serialized business payload
~2.71 MiB extracted SQLite database
```

Direct Room is acceptable unless real Android evidence proves an operational limitation.

### Import

```text
parse / validate outside live transaction
        ↓
one direct Room business transaction
```

### Restore Replace

```text
safety backup
    ↓
prepare/validate outside transaction
    ↓
one final Room replacement transaction
    ↓
generation invalidation in same transaction
```

### Restore Merge

Still an implementation gate.

All decisions must finish before final Room transaction:

```text
inspect
compare
identify
resolve baseline lineage
user decision
prepare final dataset
        ↓
FINAL ROOM TRANSACTION
        ↓
apply chosen dataset atomically
```

No UI waiting.
No network waiting.
No externally blocking await inside the final transaction.

### Required Merge semantics

```text
same ID → one
new ID → preserve
baseline conflict → complete-lineage selection
```

### Required proof
- real Android import;
- Restore Replace interruption;
- Restore Merge;
- independent current-position oracle;
- no partial business visibility;
- stale-sync interaction;
- restore transport reconstruction.

---

## G4 — Concurrency / Stale Sync / Lineage

### Final architecture
The Room database transaction is the local business serialization boundary.

Required remote apply model:

```text
remote result fetched under expected generation G
        ↓
BEGIN Room write transaction
        ↓
read current generation
        ↓
compare to G
        ↓
if mismatch → reject stale result
if match → apply remote business result
        ↓
COMMIT
```

Restore/Import/full dataset clear-or-replace changes the generation in the same business transaction.

### Lineage boundaries

A new lineage is established when the operation actually clears/replaces the full local business dataset:

- Restore;
- Import when it replaces the dataset;
- full dataset clear;
- full dataset/session replacement;
- sign-out only when it actually clears/replaces the dataset.

A normal local ledger mutation stays in the same lineage unless executable concurrency evidence demonstrates otherwise. It must not automatically increment generation just because a ledger row was added.

### Lock rules

```text
Normal mutation → Room transaction
Sync network → no business lock / no Room transaction across await
Sync apply → Room transaction
Restore/Import → short business transaction
```

No AB/BA lock inversion.
No global coordinator around ordinary CRUD.

### Required proof
- stale sync vs Restore;
- stale sync vs Import;
- stale sync vs full clear/session replacement;
- local mutation vs remote apply;
- Restore/Import vs mutation;
- deadlock/lock order;
- no network under business exclusion;
- sign-out/session isolation.

---

## G5 — Identity / Import Collision

### Final architecture
Stable transaction identity is a core invariant.

Normal runtime:

```text
new transaction
    ↓
generate ID exactly once
    ↓
persist
    ↓
retries/recovery reuse same ID
```

Import:

```text
explicit source ID where available
otherwise deterministic source provenance / source-row coordinate
```

Invariant:

```text
same source row → same ID
distinct legitimate rows → distinct IDs
```

Do not use destination SQLite `ROWID` as an assumed source identity.

Existing reliable IDs must never be regenerated merely to adopt a better algorithm.

### Known current risk
Current v71 importer fallback can use missing-source-key/business-field deduplication and may collapse legitimate identical historical rows.

### Required proof
- retry idempotency;
- same-file reimport;
- identical historical rows remain distinct;
- Merge identity;
- local ID = Firebase document identity;
- migration identity conservation.

---

## G6 — Profile Semantics

### Final semantic classes

```text
1. ISP/server-owned
2. reseller/local profile
3. reminder notes
4. operational credentials
5. derived financial fields
6. legacy/history-only
```

### Fixed rules

- ISP-owned fields are server authority.
- Local profile fields remain local unless explicitly approved for sync.
- Notes use simple LWW.
- Credentials are dedicated UID-scoped recovery data.
- Current debt/advance are derived.
- ISP balance is separate from subscriber debt.
- General Settings sync is out of V1.
- ISP absence does not delete local history.

### Open implementation classification
Exact field-by-field ownership must still be verified, especially legacy semantic fields listed above.

### Required proof
- notes LWW;
- credential delayed-response isolation;
- credential clear;
- ISP refresh semantics;
- legacy/history-only preservation;
- ISP balance vs debt separation;
- only Activation/Renewal/Refill create ledger entries;
- ISP-side deletion never erases local financial history.

---

## G7 — Migration

### Final architecture
**Business-data-preserving migration.**

Preserve:

- reliable ledger IDs;
- ledger history;
- baseline/snapshot semantics;
- current financial meaning;
- legacy/history-only users;
- credentials;
- usable recovery artifacts;
- unresolved legacy semantic fields until classified.

Normalize/rebuild technical state where required:

- obsolete outbox states;
- obsolete sync metadata;
- initial G4 generation;
- other rebuildable transport state.

Remove destructive financial-history cascade semantics.

### Irreversible-risk rules

Never:

- regenerate a reliable existing ledger ID;
- invent a missing historical transaction;
- delete dead-letter obligations merely because they are old;
- promote device timestamps into remote authority;
- rebuild baseline from incomplete history;
- allow account deletion to cascade into ledger deletion;
- break old backup recovery silently.

### Required proof
- migration kill points;
- identity conservation;
- identical historical rows;
- transport-state migration;
- baseline preservation;
- independent current-position oracle;
- legacy user preservation;
- credential preservation;
- non-destructive FK migration;
- old v71 backup compatibility;
- G4 generation initialization.

---

## G8 — Certification

### Final architecture
Certification is external evidence infrastructure.

It is NOT:

- runtime business state;
- runtime governance registry;
- runtime synchronization authority.

One certification run should produce one evidence bundle containing:

```text
source identity
actual test corpus
actual execution results
instrumentation results
release artifact identity
artifact SHA-256
machine-derived final states
```

Required derived states:

```text
ARCHITECTURE_COMPLETE
IMPLEMENTATION_COMPLETE
VERIFIED
PRODUCTION_READY
```

These are machine-derived, not agent-declared.

### Required self-tests

Certification must detect/reject:

- missing test;
- stale test result;
- vacuous assertion;
- report-only PASS;
- unavailable instrumentation;
- wrong source artifact;
- wrong release artifact;
- blocked mandatory test.

---

# 8. Critical Implementation Corrections Still Required

These are the highest-priority items before any production claim.

## P0 / release-blocking

### P0-1 — Financial history deletion protection

Must eliminate/neutralize production paths:

```text
RemoteSyncCoordinator.applyAccountDelete()
RemoteSyncCoordinator.applyLedgerDelete()
account → ledger ON DELETE CASCADE
```

Required: ISP/local delete events cannot physically erase financial history.

Developer-only destructive reset tools must not be production capabilities.

### P0-2 — G4 generation / stale-result protection

Must implement the local generation mechanism and same-transaction validation/apply.

### P0-3 — G5 historical identity collision

Must replace collision-prone missing-source-key identity and prove repeat-import stability.

### P0-4 — G3 Restore Merge

Must implement complete-lineage baseline conflict resolution before the final Room transaction.

### P0-5 — G7 non-destructive FK migration

Must remove cascade semantics without losing existing ledger rows.

---

## P1 — major implementation / verification items

- Remove terminal `DEAD_LETTER` behavior from exact target implementation artifact if still present.
- Remove global coordinator scope from ordinary CRUD.
- Remove any AB/BA lock-order inversion.
- Remove network awaits under business/maintenance exclusion.
- Implement sign-out/full-clear lineage invalidation.
- Complete credential/session isolation.
- Complete restore transport-state reconstruction.
- Prove real Firestore lost-ACK idempotency.
- Prove Room failure rollback.
- Complete external certification verifier.

---

# 9. LESSONS LEARNED

## Lesson 1 — Bugs were symptoms of duplicated authority

Repeated failures came from multiple modules treating different proxies as canonical authority:

```text
remoteVersion
updatedAt
effectiveVersion
outbox status
settings mutation flags
tombstones
sync metadata
```

The correct response was not another registry; it was to reduce the number of authorities.

## Lesson 2 — Business data and transport state must remain separate

```text
ledger/account/baseline
    = business meaning

outbox/cursor/tombstone/retry state
    = transport mechanism
```

A transport mechanism must never silently become business history.

## Lesson 3 — Immutable ledger does not need LWW conflict resolution

A ledger is additive history:

```text
T1 + T2 + T3
```

not:

```text
latest copy wins
```

This simplification removed a major source of complexity.

## Lesson 4 — Exact identity is more important than clever deduplication

Business-field similarity is not transaction identity.

Two legitimate transactions can have identical:

```text
date
amount
type
note
```

Therefore deduplication must be based on stable identity/provenance.

## Lesson 5 — A failed transport item must not become a reason to lose business data

The earlier idea of deleting dead-letter obligations was rejected.

Correct principle:

```text
failure
→ retain obligation
→ backoff
→ isolate
```

not:

```text
failure
→ delete intent
```

## Lesson 6 — Remote version and local lineage are different concepts

```text
remoteVersion
    = freshness of remote entity

generation
    = validity of an asynchronous result relative to local dataset lineage
```

One cannot substitute for the other.

## Lesson 7 — Transactions solve atomicity, not semantic freshness

A transaction ensures a write is atomic.

It does not by itself prove that the write is still semantically valid after Restore/Import/session replacement.

That is why the G4 generation check belongs inside the same Room transaction as remote apply.

## Lesson 8 — Human decisions must happen before the final DB transaction

Never hold a business transaction open while waiting for:

- UI decision;
- Firebase;
- ISP;
- network;
- external computation.

Correct pattern:

```text
prepare
→ validate
→ decide
→ final Room transaction
```

## Lesson 9 — Migration must preserve meaning, not merely schema

A migration that keeps row counts but changes transaction identity, baseline meaning, or user history is not safe.

The central migration rule is:

> **Simplify technical state, never simplify business history.**

## Lesson 10 — Reports are not proof

A report saying:

```text
PASS
306/306 tests passed
```

is not evidence by itself.

Actual source, actual test files, actual execution output, actual artifact, and actual artifact hash are the authoritative evidence chain.

## Lesson 11 — Fresh review is valuable only if it distinguishes baseline from current source

Many false contradictions came from mixing:

```text
ZIP 71 baseline
```

with:

```text
later patched source
```

Always identify the exact artifact being reviewed.

## Lesson 12 — Do not add architecture to solve an implementation defect

The project repeatedly approached a loop of:

```text
bug
→ new flag
→ new state
→ new registry
→ new gate
→ new report
```

The frozen architecture intentionally breaks that loop.

---

# 10. WHAT NOT TO DO IN THE NEXT CONVERSATION

Do not:

- reopen Direct Atomic Room merely because Restore Merge is missing;
- reintroduce staging without concrete Android evidence;
- create another identity registry;
- recreate a generic `SyncConflictResolver` for immutable ledger entries;
- introduce `DEAD_LETTER` under another name;
- use `remoteVersion` as G4 generation;
- use local timestamps as remote server versions;
- build a generic Earthlink reconciliation engine;
- make certification runtime state;
- treat narrative reports as test proof;
- regenerate historical transaction IDs without source evidence;
- delete old ledger history to make migration “clean.”

---

# 11. EXACT CURRENT IMPLEMENTATION / VERIFICATION STATUS

This is the operational state to carry into the new conversation.

| Gate | Architecture | Implementation | Verification | Current state |
|---|---|---|---|---|
| G1 | Closed | Target accepted | Bounded limitation accepted | **CLOSED — BOUNDED LIMITATION** |
| G2 | Closed | Current artifact must be checked; ZIP 71 baseline had dead-letter, later G2 patch reported removal | Pending | **OPEN FOR EXACT ARTIFACT VERIFICATION** |
| G3 | Direct Atomic Room | Restore Merge still required | Pending | **OPEN** |
| G4 | Accepted | Generation/lock boundary still required | Pending | **OPEN** |
| G5 | Closed architecturally | Identity collision fix still required | Pending | **OPEN** |
| G6 | Semantic framework closed | Field-level mapping/credential details still required | Pending | **OPEN** |
| G7 | Accepted | Migration work pending | Pending | **OPEN** |
| G8 | Accepted | External verifier not yet complete | Pending | **OPEN** |

Overall:

```text
Architecture = FROZEN / APPROVED
Implementation = AUTHORIZED
Verification = REQUIRED
Production = NOT AUTHORIZED
```

---

# 12. IMPLEMENTATION STARTING ORDER

Do not implement randomly file-by-file.

Use this dependency-aware order:

```text
PHASE 0 — Artifact / source freeze
    ↓
PHASE 1 — P0 financial-history protection
    ↓
PHASE 2 — G4 generation + concurrency boundaries
    ↓
PHASE 3 — G5 identity/import collision fix
    ↓
PHASE 4 — G2 outbox/cloud durability simplification
    ↓
PHASE 5 — G3 Restore Merge + Restore/Import semantics
    ↓
PHASE 6 — G6 profile/credential semantic completion
    ↓
PHASE 7 — G7 migration
    ↓
PHASE 8 — G8 certification system
    ↓
PHASE 9 — Full adversarial verification
    ↓
PHASE 10 — Independent final zero-trust audit
    ↓
PRODUCTION AUTHORIZATION
```

### Phase 0 must produce

```text
exact implementation artifact hash
source inventory
current test inventory
baseline database backup
known implementation gaps
```

Do not allow “current code” to remain ambiguous.

---

# 13. NEXT CONVERSATION — FIRST MESSAGE / FIRST TASK

The new conversation should begin with the frozen documents attached/referenced:

```text
1. Target Product Contract v0.6
2. G1–G8 Consolidated Architecture Summary
3. Final Independent Adjudication Memo
4. Exact current implementation artifact under verification
```

The first task should NOT be “review the architecture again.”

It should be:

> **Establish the exact implementation artifact, run a source-level implementation gap audit against the frozen G1–G8 corrections, and produce the implementation dependency plan without changing the architecture.**

The first audit must specifically confirm:

- financial-history delete paths;
- FK cascade;
- G4 generation/lock model;
- G5 import identity path;
- G2 outbox/dead-letter state;
- Restore Merge implementation status;
- credential/session isolation;
- migration path;
- certification tooling.

Only after this exact source audit should individual implementation tasks begin.

---

# 14. DEFINITION OF DONE FOR THE IMPLEMENTATION PHASE

Implementation is not complete when the agent says “fixed.”

The implementation phase is complete only when:

```text
Frozen architecture implemented
        ↓
All P0/P1 implementation corrections closed
        ↓
All required adversarial tests exist
        ↓
Tests actually execute
        ↓
Android/device-specific tests execute where required
        ↓
Migration proven
        ↓
Release artifact built
        ↓
Artifact hash recorded
        ↓
Independent final audit PASS
```

Only then may production readiness be considered.

---

# 15. FINAL HANDOVER STATEMENT

The Earthlink Reseller App has completed its architecture-definition cycle.

The target architecture is intentionally smaller than v71:

```text
Local account book
+
immutable ledger
+
baseline/projection
+
minimal local durability
+
minimal sync transport
+
explicit lineage protection
+
minimal recovery semantics
+
external evidence certification
```

The next challenge is no longer to invent architecture.

The next challenge is to prove that the implementation actually behaves like
this architecture under adversarial conditions without reintroducing the
complexity that the architecture was designed to remove.

**Final handover state:**

> **ARCHITECTURE FROZEN / IMPLEMENTATION AUTHORIZED / VERIFICATION REQUIRED / PRODUCTION NOT AUTHORIZED.**
