# EARTHLINK RESELLER APP V1
# Implementation Handover Appendix v1.0

## Repository, Documentation & Governance Alignment

**Purpose:** This appendix supplements the existing Implementation Handover. It does not replace it and does not reopen the frozen architecture.

**Controlling frozen artifacts:**
1. `Target Product Contract v0.6` — Product / business authority
2. `G1–G8 Consolidated Architecture Summary` — Engineering interpretation
3. `Final Independent Adjudication Memo` — Final architectural judgment and implementation boundary

**Forensic baseline:** `earthlink-reseller-v1 (71).zip`

**Important artifact rule:** ZIP/artifact `71` is the forensic/source baseline and is not the Android application VersionCode. The application identity previously observed is VersionCode `68` / VersionName `1.68.0`. The implementation status must always be judged against the exact current implementation artifact under verification.

---

# 1. Current Position — Architecture Phase Complete

The architecture phase is complete.

```text
Target Product Contract v0.6
        ↓
G1–G8 Consolidated Architecture Summary
        ↓
Final Independent Adjudication Memo
        ↓
FROZEN TARGET
```

Current state:

```text
Architecture:
    FROZEN / APPROVED IN PRINCIPLE

Implementation:
    AUTHORIZED TO PROCEED

Verification:
    MANDATORY

Production:
    NOT YET AUTHORIZED
```

The implementation team must not restart the G1–G8 architectural investigation unless new executable evidence demonstrates:

1. a direct contradiction with a frozen product decision; or
2. an unsatisfied correctness invariant that cannot be resolved within the frozen architecture.

A normal implementation defect is not, by itself, evidence that the architecture is wrong.

---

# 2. Frozen V1 Architecture — Do Not Reopen During Normal Implementation

The accepted target is:

```text
Direct Atomic Room
+
short local business transactions
+
minimal maintenance exclusion
+
transactional G4 generation / lineage invalidation
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

Do not introduce merely as a response to implementation inconvenience:

```text
dataset_id
published_dataset_id
staging database
identity registry
generic reconciliation engine
generic synchronization state machine
runtime governance registry
```

General Settings synchronization is out of V1.

There is no user-facing “mark external operation completed” workflow.

Certification is external to the production runtime.

---

# 3. Critical Frozen Safety Invariants

## Financial history

The production application must not physically delete local financial history as a consequence of ISP-side deletion.

The implementation must explicitly protect against:

```text
RemoteSyncCoordinator.applyAccountDelete()
RemoteSyncCoordinator.applyLedgerDelete()
account → ledger ON DELETE CASCADE
```

Developer-only destructive reset tooling is a separate category and must not be exposed as a production customer/business deletion capability.

Required outcome:

```text
ISP-side deletion
    ↓
local account/history survives
```

## Ledger identity

```text
same source row
    → same stable identity

distinct legitimate source rows
    → distinct identities
```

Existing reliable transaction IDs must not be regenerated during migration.

Destination SQLite `ROWID` is not an assumed stable source-row identity.

## Current position

```text
accepted baseline
+
eligible ledger history
=
current position
```

Stored totals are not an independent historical authority.

## G4 lineage

G4 generation is a local lineage/session invalidation mechanism.

`remoteVersion` / `updatedAt` are not substitutes for it.

A generation change applies when the local business dataset is actually cleared or replaced, including Restore Replace and equivalent full dataset replacement. Sign-out is a lineage boundary only when it actually clears/replaces the local business dataset.

Normal local financial mutation is treated as same-lineage unless executable concurrency evidence demonstrates that a mutation itself must invalidate an in-flight result. It does not automatically increment generation merely because a ledger row changes.

The generation check and remote business-data apply must occur inside the same Room write transaction.

## Restore Merge

All conflict decisions occur **before** the final Room business transaction.

For conflicting baselines:

```text
user selects complete snapshot lineage
=
selected baseline
+
its associated eligible ledger history
```

Never construct:

```text
Baseline A + Ledger B
Baseline B + Ledger A
```

No UI interaction, network wait, or network-dependent decision-making is allowed inside the final Room business transaction.

## Outbox

The ledger is the business authority. The outbox is transport only.

Failed obligations remain recoverable with bounded backoff and diagnostics.

No terminal `DEAD_LETTER` business state.

No retry-count deletion of business obligations.

Historical backup transport metadata is not automatically current transport authority. It must not be blindly replayed after Restore/Merge, but valid current cloud obligations must not be silently abandoned either.

## External operations

Only:

```text
Activation
Renewal
Refill
```

create financial ledger entries.

Lookup, details, password, balance, status, and other non-financial ISP/API operations must not independently create financial ledger entries.

For uncertain external outcomes: no blind retry and no user “mark completed” workflow. The accepted G1 bounded limitation regarding complete application-data wipe remains explicit.

---

# 4. Legacy / Protected Semantic Fields

Until independently classified, preserve exactly as business-compatible data:

```text
loanIqd
isLegacy
isSnapshotHistory
stateSource
stateConfidence
```

They must not be silently deleted, reset, redefined, repurposed, or converted into a different financial authority.

`loanIqd` is retained for legacy/uTower compatibility and historical-data preservation. It is not an independent V1 financial authority.

---

# 5. Exact Current-Artifact Rule

Implementation agents must distinguish:

```text
ZIP 71
    = forensic/source baseline

Current patched source/artifact
    = implementation state under verification
```

A historical finding from ZIP 71 must not automatically be reported as a defect in the current implementation.

Conversely, a current implementation defect must not be excused merely because a later architecture document says the target is correct.

Every implementation claim must identify the exact artifact/source being evaluated.

---

# 6. Pre-Implementation Repository Alignment — REQUIRED BEFORE CODING

The repository must be aligned to the frozen architecture before substantial implementation begins.

## 6.1 Inspect the actual repository

First inspect:

```text
current source tree
current tests
current Gradle/build configuration
current agent/development instructions
current architecture/implementation plans
current governance documents
current audit/checklists
current generated evidence that may be stale
```

Do not assume that older documentation matches the frozen architecture.

## 6.2 Documentation drift audit

Search the repository for:

```text
plan
roadmap
implementation plan
architecture plan
migration plan
sync plan
restore plan
outbox plan
agent instructions
AI_DEVELOPMENT_GUIDE
AGENTS.md
ADR
audit gate
certification
DEAD_LETTER
DataOperationCoordinator
DataMaintenanceLock
staging
published_dataset_id
dataset_id
```

Classify every relevant document as:

```text
CURRENT / ALIGNED
OUTDATED / MUST UPDATE
OBSOLETE / ARCHIVE OR REMOVE
```

No obsolete document may remain as an apparently authoritative instruction.

## 6.3 Governance alignment

Review and update agent/development governance files such as:

```text
AGENTS.md
AI_DEVELOPMENT_GUIDE.md
agent instructions
implementation checklists
repository-level developer guides
```

They must point to the three frozen artifacts rather than superseding them.

The repository must not contain an instruction that reintroduces:

```text
global CRUD coordinator
dead_letter terminal business state
generic reconciliation engine
staging by default
identity registry
generic synchronization state machine
runtime governance framework
```

If an old governance file gives conflicting guidance, update or archive it before relying on it for implementation.

## 6.4 Plan alignment

Any historical implementation plan must be compared against:

```text
Target Product Contract v0.6
        ↓
G1–G8 Summary
        ↓
Final Independent Adjudication Memo
```

If an older plan conflicts with these artifacts:

> **Update the plan. Do not change the frozen architecture merely to satisfy the old plan.**

## 6.5 Stale narrative evidence

Old reports, PASS summaries, manifests, and audit narratives are informational only.

They must not override the current source or executable evidence.

The final certification model is evidence-first:

```text
actual source
actual test corpus
actual execution
actual instrumentation
actual release artifact
```

---

# 7. Implementation Entry Sequence

Implementation should begin with the following order.

## Phase 0 — Repository / Documentation / Governance Alignment

```text
inspect exact current artifact
        ↓
remove/archive/update obsolete plans
        ↓
align AGENTS / AI_DEVELOPMENT_GUIDE / governance
        ↓
verify no stale architecture instruction remains
```

Do not perform large code changes before this alignment is understood.

## Phase 1 — G2 / Transport

Address and verify:

- remaining production `DEAD_LETTER` behavior, if present in the exact artifact;
- per-item outbox processing;
- poison isolation;
- orphan handling;
- deterministic Firebase document identity;
- backup transport reconstruction semantics.

## Phase 2 — G3 / Restore & Import

Address:

- Restore Merge implementation;
- complete-lineage baseline conflict resolution;
- final Room transaction boundary;
- deterministic current-position rebuild;
- Restore/Import transport-state reconstruction.

## Phase 3 — G4 / Concurrency & Lineage

Address:

- local generation storage/initialization;
- same-transaction generation validation + remote apply;
- Restore/Import invalidation;
- full dataset clear/replacement invalidation;
- sign-out behavior where it actually clears/replaces data;
- lock-order cleanup;
- no network under business/maintenance exclusion.

## Phase 4 — G5 / Identity

Address:

- deterministic source-row identity;
- repeated-import stability;
- preservation of identical legitimate historical rows;
- preservation of existing reliable IDs.

## Phase 5 — G6/G7 / Semantics + Migration

Address:

- field ownership mapping;
- credential/session isolation;
- legacy semantic field preservation;
- ISP-side deletion protection;
- non-destructive FK migration;
- backup compatibility;
- migration interruption safety.

## Phase 6 — G8 / Certification

Implement the external verifier and bind evidence to the exact implementation artifact.

No production-readiness claim is valid until executable verification and release-artifact proof pass.

---

# 8. Implementation Rules for the New Session

1. **Do not re-run the architecture investigation merely because implementation is difficult.**
2. **Inspect actual source before relying on any previous implementation report.**
3. **Identify the exact current artifact for every implementation claim.**
4. **Treat the three frozen documents as controlling.**
5. **Older plans and governance documents are subordinate and may require updating.**
6. **Do not preserve a mechanism merely because v71 already has it.**
7. **Do not add an abstraction merely because it makes one implementation problem convenient.**
8. **Do not declare PASS without executable evidence where the gate requires it.**
9. **A code defect does not automatically invalidate the architecture.**
10. **If a real contradiction with a frozen invariant is discovered, stop and document it before redesigning anything.**

---

# 9. Current Known Implementation / Verification Gaps

These are expected implementation/verification targets, not proof that the architecture is invalid:

### G2
- exact current `DEAD_LETTER` status in the implementation artifact;
- runtime Firestore lost-ACK proof;
- real Room atomicity proof;
- poison/orphan runtime proof.

### G3
- Restore Merge implementation;
- deterministic current-position proof;
- Restore/Import transport reconstruction;
- real Android Direct Atomic Room interruption/resource proof.

### G4
- generation implementation;
- stale result rejection;
- sign-out/full-clear lineage behavior;
- lock-order/deadlock proof;
- normal-mutation same-lineage concurrency proof.

### G5
- source-row identity correction;
- identical-row collision proof;
- migration identity preservation.

### G6/G7
- exact field ownership classification;
- credential/session isolation;
- ISP-side deletion protection;
- `ON DELETE CASCADE` migration removal;
- migration interruption and backup compatibility.

### G8
- external verifier implementation;
- machine-derived certification states;
- actual release artifact proof.

---

# 10. Lessons Learned — Keep These Permanent

## Lesson 1 — Product authority must remain above implementation history

Do not derive the target from v71 mechanisms. Start from the Product Contract.

## Lesson 2 — Architecture, implementation, verification, and production are separate states

```text
Architecture approved
≠
Implementation complete
≠
Verification complete
≠
Production ready
```

## Lesson 3 — Never trust narrative PASS evidence

A report saying “PASS” is not equivalent to an executed test result.

## Lesson 4 — Proxies are not authorities

Examples:

```text
remoteVersion ≠ local generation
updatedAt ≠ remote authority
outbox ≠ business history
snapshot total ≠ independent financial authority
```

## Lesson 5 — Do not solve every race with another global lock

Room transaction boundaries are the preferred local business serialization mechanism.

## Lesson 6 — Identity must represent the source event, not only its values

Two identical historical rows can still be two legitimate financial events.

## Lesson 7 — Transport metadata is not business history

But ignoring transport metadata must not silently abandon valid current cloud obligations.

## Lesson 8 — Developer tooling and production semantics are different

A developer reset utility is not automatically a production deletion capability, but production wiring must be checked explicitly.

## Lesson 9 — Migration must simplify technical state, never financial history

```text
business history → preserve
technical transport state → normalize/rebuild where justified
```

## Lesson 10 — Fresh review is valuable, but it must review the correct artifact

A reviewer must inspect the exact current implementation artifact, not only ZIP 71 or old reports.

---

# 11. Handover Reference Set

The existing Handover remains the primary operational context document.

The following are the frozen authoritative artifacts for the next implementation session:

```text
1. Target Product Contract v0.6
2. G1–G8 Consolidated Architecture Summary
3. Final Independent Adjudication Memo
4. Existing Implementation Handover
5. This Appendix
```

Older architecture/review artifacts may be retained for forensic history, but
must not override the three frozen authoritative artifacts above.

---

# 12. Next Session Opening Objective

The next fresh implementation session should begin with:

```text
STEP 1
Read the Existing Implementation Handover + this Appendix.

STEP 2
Inspect the exact current implementation artifact.

STEP 3
Inventory repository plans, AGENTS/AI_DEVELOPMENT_GUIDE files, governance,
audit instructions, and implementation documents.

STEP 4
Identify and align/remove/archive obsolete guidance so repository instructions
match the three frozen artifacts.

STEP 5
Produce a compact Repository Alignment Report.

STEP 6
Only after alignment, begin implementation corrections in the agreed sequence.
```

The first implementation-session deliverable is therefore **repository/governance alignment**, not another architecture memo.

---

# 13. Final Handover State

```text
PRODUCT CONTRACT
    Target Product Contract v0.6

ENGINEERING INTERPRETATION
    G1–G8 Consolidated Architecture Summary

ARCHITECTURAL JUDGMENT
    Final Independent Adjudication Memo

IMPLEMENTATION ENTRY CONDITION
    Repository + Documentation + Governance Alignment

IMPLEMENTATION STATUS
    Authorized to proceed

VERIFICATION STATUS
    Mandatory / not complete

PRODUCTION STATUS
    Not authorized
```

**End of Appendix.**
