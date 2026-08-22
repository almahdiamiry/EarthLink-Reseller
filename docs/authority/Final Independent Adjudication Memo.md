# Final Independent Adjudication Memo

---
### 🧭 NAVIGATION & CONTEXT
* **Why You Are Here:** This document provides the final architectural judgment and implementation authorization boundary.
* **What This Document Owns:** Architectural Rulings & Boundaries.
* **Where To Go Next:**
  * For current project state $\rightarrow$ [PROJECT_ROADMAP.md](../../PROJECT_ROADMAP.md)
  * For plan execution status $\rightarrow$ [PLAN_STATUS.md](PLAN_STATUS.md)
  * For behavioral rules & router $\rightarrow$ [AGENTS.md](../../AGENTS.md)
---

## Earthlink Reseller App

**Document:** Final Independent Architecture Adjudication
**Product Contract:** Target Product Contract v0.6
**Engineering Basis:** G1–G8 Consolidated Architecture Summary
**Forensic Baseline:** ZIP 71 (`earthlink-reseller-v1 (71).zip`)
**Implementation Artifact Under Verification:** The exact current patched source/artifact presented for implementation verification

---

# 1. Scope and Authority

This memo is the final independent architectural adjudication of the
accepted Earthlink Reseller App target.

The authority hierarchy is:

1. **Target Product Contract v0.6**
   - Product and business authority.

2. **G1–G8 Consolidated Architecture Summary**
   - Engineering interpretation of the accepted product contract.

3. **This Final Independent Adjudication Memo**
   - Final architectural judgment and implementation authorization boundary.

4. **Current implementation source/artifact**
   - Evidence for implementation-state verification.

ZIP 71 is the **forensic baseline artifact**.

ZIP 71 must not be confused with the Android application VersionCode /
VersionName. The reported application identifiers are VersionCode `68` /
VersionName `1.68.0`.

The implementation state must always be judged against the exact current
implementation artifact being verified, not inferred from the ZIP 71 baseline.

---

# 2. Final Adjudication Verdict

## ARCHITECTURE FROZEN — APPROVED IN PRINCIPLE;
## TARGETED IMPLEMENTATION CORRECTIONS REQUIRED

The accepted architecture is sufficiently coherent to proceed with
implementation.

No architecture redesign is required by the current evidence.

Implementation is authorized against the frozen target, subject to the
mandatory corrections and verification gates defined in this memo.

The following states remain explicitly distinct:

```text
Architecture:
    FROZEN / APPROVED IN PRINCIPLE

Implementation:
    AUTHORIZED TO PROCEED

Verification:
    REQUIRED

Production:
    NOT YET AUTHORIZED
```

Architecture must be reopened only if new implementation evidence demonstrates
a contradiction with a frozen requirement or an unsatisfied correctness
invariant.

---

# 3. Frozen Architecture Decisions

The following decisions are frozen and must not be reopened during normal
implementation.

## 3.1 Direct Atomic Room

The V1 architecture remains **Direct Atomic Room**.

Room/database transactions remain the authority for atomic business-state
changes.

No staging database is required by the current evidence.

The implementation must not introduce:

* dataset_id;
* published_dataset_id;
* staging database;
* generic reconciliation engine;
* generic synchronization state machine;
* identity registry;
* runtime governance registry.

Such mechanisms may only be reconsidered if new concrete evidence demonstrates
that the accepted correctness invariants cannot be satisfied otherwise.

---

## 3.2 Financial Ledger

The ledger remains immutable financial history.

Only the accepted financial operations create financial ledger entries:

* Activation;
* Renewal;
* Refill.

ISP lookup, details, password, balance, status, and other non-financial
operations must not independently create financial ledger entries.

ISP balance remains distinct from subscriber debt.

Current financial position is derived according to the accepted:

```text
baseline
    +
eligible ledger history
    =
current position
```

model.

---

## 3.3 Restore / Import Lineage

Restore and Import must preserve the accepted snapshot-lineage semantics.

For compatible datasets, transaction history may merge according to the
accepted transaction-identity rules.

For conflicting baselines:

```text
user selects complete snapshot lineage
        +
selected baseline
        +
associated eligible ledger history
```

must be used.

The implementation must not construct invalid combinations such as:

```text
Baseline A + Ledger B
```

or:

```text
Baseline B + Ledger A
```

All user conflict resolution and decision-making must occur before the final
business Room transaction.

The final Room transaction must not contain:

* user interaction;
* UI waiting;
* network I/O;
* network-dependent decision-making.

---

## 3.4 G4 Lineage Invalidation

G4 generation is a local lineage/session invalidation mechanism.

It must remain distinct from:

```text
remoteVersion
updatedAt
```

Normal local financial mutation remains within the same lineage unless
concurrency evidence demonstrates that the mutation itself must invalidate an
in-flight result.

The following operations establish a new lineage when they actually clear or
replace the full local business dataset:

* Restore;
* Import where it replaces the dataset;
* full dataset clear;
* full dataset/session replacement;
* sign-out only when it actually performs such a clear/replace.

The invariant is:

```text
old asynchronous result
        cannot
write into a newer local dataset lineage
```

Generation validation and the corresponding business apply must remain inside
the same Room write transaction.

---

## 3.5 Transaction Identity

Existing reliable transaction identity must be preserved.

For imported historical rows without explicit source IDs, the identity rule
must be deterministic and reproducible:

```text
same source row
    →
same stable identity

distinct legitimate source rows
    →
distinct identities
```

The identity must remain stable across repeated imports of the same source
artifact.

SQLite ROWID must not be used as a stable source-row identity unless
independently demonstrated to satisfy these requirements.

---

## 3.6 Outbox and Transport

The Outbox remains a durable transport mechanism and must not become business
authority.

Transport failure must not silently destroy financial history.

A failed transport item must remain independently recoverable without allowing
one poison item to silently discard unrelated obligations.

`DEAD_LETTER` must not constitute a hidden permanent business state.

Backup transport metadata is not automatically current transport authority.

After Restore or Merge:

```text
historical backup transport metadata
        ≠
current transport authority
```

Transport state must be reconstructed from the resulting business dataset and
current synchronization semantics.

This must not introduce a second recovery/reconciliation state machine.

Existing current cloud obligations must not be silently abandoned merely
because a backup contained stale transport metadata.

---

# 4. Mandatory Implementation Corrections

The following are implementation corrections required before the corresponding
verification gates can close.

## 4.1 G2 — Outbox / Failure Isolation

The implementation must ensure that transport failure does not silently
abandon subsequent independent obligations.

The exact current implementation artifact must be checked for any remaining
production `DEAD_LETTER` semantics and transport paths.

The final implementation state must be explicitly identified by artifact,
rather than described generically as "source-dependent."

```text
Forensic Baseline:
    ZIP 71

Implementation Artifact Under Verification:
    exact current patched source/artifact
```

No baseline finding may be used to claim that the current patched
implementation is incomplete unless that behavior remains present in the exact
artifact under verification.

---

## 4.2 G3 — Restore Merge Transaction Boundary

All Restore/Merge user decisions must be completed before the final Room
business transaction.

The final transaction must perform the selected business-state application
atomically.

No user interaction or network wait may occur inside that transaction.

No staging architecture is required.

---

## 4.3 G3/G2 — Restore Transport Reconstruction

Restoring a backup must not blindly reactivate historical transport obligations.

The implementation must distinguish:

```text
backup transport metadata
```

from:

```text
current transport authority
```

while preserving current legitimate cloud obligations.

Transport reconstruction must use the resulting business dataset and current
sync semantics only.

It must not create a generic reconciliation engine or second synchronization
state machine.

---

## 4.4 G4 — Lineage / Session Isolation

Any operation that actually clears or replaces the full local business dataset
must establish a new lineage.

This includes Restore Replace and equivalent full dataset replacement.

Sign-out is a lineage boundary only when it actually clears or replaces the
local business dataset.

Asynchronous work belonging to the previous lineage must not be able to mutate
the new dataset.

---

## 4.5 G4 — Stale Remote Result Protection

The implementation must prove the following invariant:

```text
remote result from previous lineage
        cannot
apply to current lineage
```

The generation check and business-state application must remain within the
same Room write transaction.

Normal local ledger mutations must not automatically increment generation
unless executable concurrency evidence demonstrates that this is necessary.

---

## 4.6 G5 — Historical Import Identity

Historical rows without explicit external IDs require a deterministic,
reproducible source-row identity.

The implementation must preserve:

```text
same source row → same identity
distinct legitimate rows → distinct identity
```

Repeated import of the same source artifact must therefore be idempotent
without collapsing legitimately distinct historical rows.

---

## 4.7 G6/G7 — Legacy Semantic Fields

The following fields remain protected pending independent field-level
classification:

```text
loanIqd
isLegacy
isSnapshotHistory
stateSource
stateConfidence
```

They must not be silently:

* deleted;
* reset;
* redefined;
* repurposed;
* converted into a different financial authority

during implementation or migration.

`loanIqd` is retained for legacy/uTower compatibility and historical-data
preservation.

It is not an independent V1 financial authority and must not be used as a
separate debt/sync/recovery state.

---

# 5. Financial History Deletion Protection

This correction is mandatory and explicit.

The implementation must eliminate every **production** path that can
physically delete subscriber financial history, including:

```text
RemoteSyncCoordinator.applyAccountDelete()
RemoteSyncCoordinator.applyLedgerDelete()
account → ledger ON DELETE CASCADE
```

The production application must not physically delete local financial history
as a consequence of ISP-side subscriber deletion.

The implementation must therefore ensure that:

* `applyAccountDelete()` cannot physically delete protected subscriber
  financial history;
* `applyLedgerDelete()` cannot physically delete protected local ledger
  history;
* the account-to-ledger foreign-key relationship cannot cascade-delete
  financial history.

Any developer-only destructive reset tooling must remain explicitly
non-production and must not constitute a customer/business deletion path.

Developer reset tooling is therefore classified separately from production
remote deletion behavior.

---

## 5.1 Required Deletion Verification

The implementation must provide executable evidence for:

```text
remote account deletion event
    →
local account/history survives

remote ledger deletion event
    →
local ledger history survives

production account deletion
    →
cannot cascade-delete ledger history

developer-only destructive tools
    →
not exposed in production
```

This is a mandatory correctness gate.

---

# 6. G6 — Profile / Identity Semantics

The accepted profile model remains:

### ISP/server-owned fields

Authority:

```text
ISP/server
```

Local offline editing is restricted according to the accepted product
contract.

Conflict handling follows the accepted server-owned semantics.

---

### Reseller/local profile fields

Authority:

```text
local reseller
```

Only fields explicitly classified as local profile data may be edited locally.

---

### Reminder notes

Notes are simple reminder metadata.

They use:

```text
LWW
```

semantics.

They are not financial history.

---

### Operational credentials

Credentials remain a dedicated UID-scoped recovery domain.

They must not be converted into generic profile synchronization state.

Credential/session isolation must prevent data belonging to one identity/session
from mutating another identity's active local dataset.

---

### Derived financial fields

Derived balances and positions are not independent financial authorities.

They must remain mathematically consistent with the accepted baseline and
eligible ledger history.

---

### Legacy/history-only fields

Legacy/history-only information must remain locally available when required by
the product contract.

ISP-side absence does not authorize deletion of local history.

---

# 7. G6 — ISP-side Deletion

The following invariant is frozen:

> ISP-side subscriber deletion must never delete local ledger/history.

Remote disappearance from the ISP must not be interpreted as authorization to
physically erase local financial history.

Local financial history must remain available according to the accepted
history-only semantics.

---

# 8. G7 — Migration Safety

Migration must preserve existing financial/account history.

The following invariants are mandatory:

* no ledger row is silently deleted;
* no legitimate transaction is silently merged with another;
* reliable existing transaction identity is preserved;
* current financial position remains mathematically consistent;
* legacy/history-only subscribers remain locally available;
* old transport obligations are not silently abandoned;
* local timestamps are never promoted into remote-version authority;
* removal of `ON DELETE CASCADE` must not delete historical rows.

Migration interruption must be tested:

```text
migration starts
    →
process killed at controlled points
    →
app reopens
    →
financial history intact
```

Migration must be recoverable without silent financial-history loss.

---

# 9. G8 — Certification Boundary

Certification remains external to the production application/runtime.

The certification mechanism exists to prove implementation reality and evidence
integrity.

It must independently establish:

* actual source/artifact identity;
* actual test corpus;
* existence of required adversarial tests;
* actual test execution;
* captured real execution results;
* instrumentation-test execution where applicable;
* release artifact creation;
* artifact identity/hash;
* distinction between architecture-complete;
* implementation-complete;
* verified;
* production-ready.

It must reject or detect:

* vacuous tests;
* stale or missing test files;
* manifest-only PASS;
* report-to-report evidence chains;
* agent-declared PASS without executable evidence.

G8 must not become:

* a runtime governance subsystem;
* a production registry;
* a generic compliance framework;
* another synchronization state machine.

---

# 10. Verification Gates

The following are proof requirements, not architecture redesign requirements.

## G2

Verify:

* Firestore lost-ACK behavior;
* outbox durability;
* failure isolation;
* current implementation's actual transport-state behavior.

---

## G3

Verify:

* Import;
* Restore Replace;
* Restore Merge;
* complete-lineage selection;
* no network/UI wait inside the final Room transaction;
* transport-state reconstruction.

---

## G4

Verify:

* stale remote result protection;
* generation invalidation;
* same-transaction generation validation and business apply;
* Restore Replace lineage invalidation;
* full dataset clear/replacement isolation;
* previous-session asynchronous result isolation.

---

## G5

Verify:

* repeated import idempotency;
* identical historical-row collision resistance;
* distinct-row preservation;
* deterministic identity reproducibility.

---

## G6

Verify:

* profile semantics;
* notes LWW;
* credential/session isolation;
* ISP-side deletion preserving local history;
* legacy/history-only availability;
* protected legacy fields.

---

## G7

Verify:

* schema migration;
* interrupted migration;
* transaction identity preservation;
* baseline/ledger mathematical consistency;
* removal of cascade behavior without financial-history loss.

---

## G8

Verify:

* external certification execution;
* artifact identity;
* test execution;
* evidence capture;
* release artifact identity;
* separation of architecture, implementation, verification, and production
  states.

---

# 11. Accepted Bounded Limitation

G1 includes an accepted bounded recovery limitation concerning complete
application-data wipe before financial ledger materialization.

This is an accepted product/recovery boundary.

It is not considered an unresolved architecture defect.

It must not be represented as a guarantee of complete recovery after every
possible application-data wipe scenario.

---

# 12. Explicitly Descoped / Rejected Architecture

The following remain outside the frozen V1 architecture:

```text
dataset_id
published_dataset_id
staging database
generic reconciliation engine
generic synchronization state machine
identity registry
runtime governance registry
general settings synchronization
```

They must not be introduced merely to address implementation defects that can
be solved within the frozen Direct Atomic Room model.

---

# 13. Architecture Reopening Rule

No architecture redesign is required by the current evidence.

Implementation may proceed against the frozen target.

Architecture should be reopened only if new evidence demonstrates:

1. a direct contradiction with a frozen product decision; or
2. an unsatisfied correctness invariant that cannot be resolved within the
   accepted architecture.

A normal implementation defect is not, by itself, evidence that the
architecture is wrong.

---

# 14. Final Authorization

```text
Architecture Freeze
    APPROVED IN PRINCIPLE

Implementation
    AUTHORIZED TO PROCEED

Targeted Corrections
    REQUIRED

Verification
    MANDATORY

Production
    NOT YET AUTHORIZED
```

The implementation team must not interpret architectural approval as evidence
that the implementation has already satisfied the verification gates.

Production authorization requires successful executable verification of the
mandatory invariants in this memo.

---

# 15. Final Review Record

This memo incorporates the final targeted corrections identified during
independent review:

1. Explicit protection against production deletion of subscriber financial
   history, including:

   * `RemoteSyncCoordinator.applyAccountDelete()`;
   * `RemoteSyncCoordinator.applyLedgerDelete()`;
   * account-to-ledger `ON DELETE CASCADE`.

2. Explicit distinction between production deletion paths and developer-only
   destructive reset tooling.

3. Restoration of the G4 wording covering operations that **clear or replace**
   the full local business dataset.

4. Explicit treatment of Restore Replace as a lineage-invalidating operation.

5. Explicit preservation of unresolved legacy semantic fields.

6. Explicit separation of forensic baseline ZIP 71 from the current
   implementation artifact under verification.

7. Explicit preservation of the external-only G8 certification boundary.

8. Uniform terminology: every verdict/status block in this memo reads
   `FROZEN / APPROVED IN PRINCIPLE`, with no shortened variant implying a
   different strength of approval.

9. No architecture redesign has been introduced by these corrections.

## Final Adjudication

**ARCHITECTURE FROZEN — APPROVED IN PRINCIPLE; TARGETED IMPLEMENTATION
CORRECTIONS REQUIRED.**

Implementation may proceed only against the exact current implementation
artifact and must subsequently pass the required G1–G8 executable verification
gates.

```text
Architecture:
    FROZEN / APPROVED IN PRINCIPLE

Implementation:
    AUTHORIZED

Verification:
    REQUIRED

Production:
    NOT YET AUTHORIZED
```
