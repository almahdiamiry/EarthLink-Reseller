# EARTHLINK G1–G8 CONSOLIDATED ARCHITECTURE SUMMARY

> **Artifact under review:** `earthlink-reseller-v1 (71).zip`  
> **Artifact sequence vs application version:** `71` is the ZIP/artifact sequence; it is **not** the Android application version. The application reports `VersionCode 68 / VersionName 1.68.0`.
>
> **Purpose:** consolidated engineering freeze summary only. This document does not redesign the architecture and does not reopen fixed product decisions.

---

---

# Final Engineering Review Record

## Draft v0 — Consolidated freeze summary
Objective: consolidate the frozen G1–G8 decisions into one source-independent engineering summary while separating architecture, implementation, verification, cross-gate dependencies, contradictions, risks, and freeze recommendation.

## Review Pass 1 — Gate-by-gate consistency
- G1: bounded app-data-wipe limitation is an explicitly accepted product/recovery boundary, not an open defect.
- G2: architecture is closed, but implementation status must distinguish ZIP 71 forensic baseline from current patched source.
- G3: Direct Atomic Room remains V1; Restore Merge decisions must be completed before the final Room transaction; no UI or network wait inside that transaction.
- G4: generation is a lineage boundary, not an every-mutation counter; sign-out/full dataset clear is a lineage boundary; ordinary ledger mutation remains same-lineage unless concurrency evidence demonstrates otherwise.
- G5: source-stable historical identity is required; destination SQLite ROWID is not assumed stable.
- G6: unresolved semantic fields remain preservation-only until classified; ISP deletion cannot delete local history.
- G7: migration preserves business data; historical transport state is normalized/rebuilt, not blindly replayed.
- G8: certification remains external to the production runtime.

## Review Pass 2 — Cross-gate dependency review
Verified:
```text
G5 identity → G2 cloud idempotency
G5 identity → G3 Merge
G3 Restore/Import publication → G4 lineage invalidation
G4 stale-result protection → G3 closure
G5 identity preservation → G7 migration
G7 source/build identity → G8 certification
G2/G3/G4 runtime evidence → G8 verification
```
No dependency requires staging, an identity registry, or a generic reconciliation engine.

## Review Pass 3 — Baseline-vs-current-implementation audit
Explicitly separated:
```text
ZIP 71 = forensic/source baseline
current patched source = implementation state under verification
```
A baseline finding must not be carried forward as a patched-source defect without current-source confirmation.

## Review Pass 4 — Semantic authority audit
Confirmed:
```text
remoteVersion / updatedAt ≠ G4 generation
stored snapshot balance ≠ independent current-position authority
historical backup transport metadata ≠ current transport authority
suppression of stale transport replay ≠ deletion of valid current cloud obligations
```

## Review Pass 5 — Migration / financial-integrity audit
Verified preservation requirements for:
- existing ledger IDs;
- legitimate imported history;
- baseline/snapshot fields;
- legacy/history-only subscribers;
- credentials;
- unresolved fields (`loanIqd`, `isLegacy`, `isSnapshotHistory`, `stateSource`, `stateConfidence`);
- account→ledger financial history.

Verified that `ON DELETE CASCADE` removal must not delete historical ledger rows.

## Review Pass 6 — Adversarial implementation audit
Checked specifically:
- G2 live `DEAD_LETTER` must not be mistaken for target-compliant implementation;
- Restore/Merge final transaction cannot contain user interaction/network wait;
- old backup transport state cannot be blindly replayed;
- valid current cloud obligations cannot be silently deleted;
- ordinary local ledger mutation is not assumed to require generation increment without concurrency proof;
- session/sign-out full clear invalidates old async lineage;
- source-row identity does not rely on destination ROWID;
- certification cannot become runtime governance.

## Review Pass 7 — Final wording / status audit
Applied final corrections:
1. G1 limitation explicitly labeled an accepted product/recovery boundary.
2. G2 explicitly separates ZIP 71 forensic baseline from current patched implementation.
3. G4 ordinary mutation generation behavior is an implementation hypothesis requiring concurrency proof, not an unconditional fact.
4. Restore transport-authority invariant is explicit.
5. Architecture approval is explicitly separated from implementation and verification completion.

**FINAL CONSISTENCY RESULT: PASS**


---

## G1 — External Operation → Local Ledger Durability

### Gate
**G1 — External Operation → Local Ledger Durability**

### Decision
**CLOSED WITH BOUNDED RECOVERY LIMITATION — ACCEPTED PRODUCT/RECOVERY BOUNDARY**

Applies only to Activation, Renewal, and Refill.

Minimum model:
- application-generated stable local transaction ID;
- small local pending-operation record;
- confirmed ISP success → atomic local ledger + current-position update + cloud outbox;
- unknown outcome → retain pending record, do not blindly repeat, reopen and inspect ISP state;
- same local transaction ID prevents duplicate ledger creation;
- Firebase is not a prerequisite for executing the ISP operation.

### Current v71 problem
The pre-target API→ledger boundary allowed an external ISP operation to succeed before the local accounting record was durably materialized.

### Target rule
A successful ledger-producing ISP operation must result in one durable local accounting record.

For an uncertain outcome:
- no blind retry;
- no user “mark completed” workflow;
- recovery reads actual ISP state/evidence;
- the original local transaction identity is reused.

### Implementation consequence
- Move API→ledger accounting into the repository/domain transaction path.
- Generate the local transaction ID once and preserve it through recovery.
- Keep Firebase synchronization independent from execution of the ISP operation.
- Do not build a general external reconciliation engine.

### Verification requirement
- success → ledger exists;
- process interruption after external success → accounting obligation survives according to the bounded recovery model;
- repeated recovery → one local transaction;
- Firebase unavailable does not prevent ISP operation;
- explicit API failure does not create a ledger row.

### Open item
None as an architectural/implementation defect. The complete-app-data-wipe limitation is an explicitly accepted product/recovery boundary: if the pending obligation was never materialized into the ledger and no available backup/cloud recovery copy contains it, operation-specific accounting recovery cannot be guaranteed.

### Status
**CLOSED WITH BOUNDED RECOVERY LIMITATION — ACCEPTED PRODUCT/RECOVERY BOUNDARY**

---

## G2 — Cloud / Outbox Durability

### Gate
**G2 — Cloud / Outbox / Poison-Pill Isolation**

### Decision
**ARCHITECTURE CLOSED — IMPLEMENTATION MUST BE JUDGED AGAINST THE EXACT CURRENT ARTIFACT — VERIFICATION PENDING**

Local ledger is the business authority; outbox is only a durable cloud-transport obligation.

### Current v71 problem
The ZIP 71 forensic baseline contains transport-state complexity including `syncing`, `failed`, and `dead_letter`. This is baseline evidence only; current implementation status must be judged against the current patched source under verification.

### Target rule
**Restore/backup transport invariant:** historical backup transport metadata is not automatically current transport authority. Old outbox/cursor/retry/tombstone state must not be blindly replayed against a newly restored local business lineage. Suppressing stale replay must never be implemented by silently deleting a valid unresolved cloud-durability obligation; current obligations remain preserved/re-established under G2 rules.
Transport reconstruction must use the resulting business dataset and current sync semantics only; it must not create a second recovery state machine.

For each ledger transaction:

    Room ledger exists
        AND
    Firebase durability not yet confirmed
        ↓
    outbox obligation remains

On upload success: confirm Firebase write, then remove that outbox row.

On failure:
- retain obligation;
- bounded backoff;
- diagnostics;
- later valid outbox items continue independently.

No terminal DEAD_LETTER business state.
No retry-count deletion.
No generic queue-wide state machine.

### Implementation consequence
- Per-item outbox processing rather than batch-wide failure coupling.
- Remove terminal DEAD_LETTER semantics from the ledger transport path.
- Retain scheduling metadata such as attempt count/error/next attempt where useful.
- Orphan outbox rows are retained, isolated, and diagnosed; never silently deleted or converted into business data.
- Firestore ledger document identity remains the local transaction ID.

### Verification requirement
1. `T123 → Firestore document T123`.
2. Lost ACK → retry → exactly one logical cloud document.
3. `T1 valid / T2 poison / T3 valid` → T1 and T3 succeed; T2 remains retained/backed off.
4. Firebase success + crash before outbox deletion → retry remains idempotent.
5. Orphan outbox remains observable, isolated, and non-hot-looping.
6. Real Room transaction proves ledger + current position + outbox are atomic.

### Open item
Runtime verification remains pending, especially real Firestore/emulator retry-idempotency and real SQLite/Room failure-injection tests.

### Status
**ARCHITECTURE CLOSED / FORENSIC BASELINE = ZIP 71 / IMPLEMENTATION ARTIFACT UNDER VERIFICATION = CURRENT PATCHED SOURCE / VERIFICATION PENDING**

---

## G3 — Import / Restore

### Gate
**G3 — Import / Restore / Baseline Projection**

### Decision
**DIRECT ATOMIC ROOM IS THE V1 ARCHITECTURE**

Staging was investigated but is not justified by the measured production workload unless real Android evidence demonstrates a concrete operational or correctness limitation.

Actual supplied workload:
- 199 source user records;
- 198 valid account objects;
- 2,690 historical rows;
- 582,660 bytes compressed;
- 4,845,942 bytes uncompressed;
- ~1.80 MiB primary serialized business payload;
- ~2.71 MiB extracted SQLite database.

### Current v71 problem
- uTower Import and Restore Replace have Room transaction boundaries, but Android-device proof is incomplete.
- Restore Merge is not implemented in the current source.
- Restore/current-position semantics require deterministic baseline + eligible ledger reconstruction.
- Import identity collision remains tied to the G5 identity work.

### Target rule
**uTower Import**
- parse/validate outside the live business transaction;
- publish the validated business dataset through one direct Room transaction;
- no staging schema unless evidence proves Direct Room insufficient.

**Restore Merge boundary**
- all user conflict resolution, lineage selection, identity decisions, and required remote reads complete before the final Room business transaction;
- no UI/user interaction, Firebase/ISP network wait, or externally blocking await occurs inside that transaction.

**Restore Replace**
- pre-restore safety backup;
- prepare/validate outside final transaction;
- one final Room replacement transaction;
- current position consistent with accepted baseline semantics;
- generation invalidation occurs in the same final transaction.

**Restore Merge**
- same transaction ID → one logical transaction;
- different transaction ID → preserve both;
- conflicting baselines → user chooses the complete snapshot lineage: baseline + associated ledger history;
- no baseline mixing or double-counting.

### Implementation consequence
- Keep Direct Atomic Room.
- Implement the missing minimum Restore Merge behavior.
- Preserve baseline fields and snapshot/history markers.
- Rebuild/verify current position deterministically from baseline + eligible ledger history.
- Do not add `dataset_id`, `published_dataset_id`, or a second database without concrete evidence.

### Verification requirement
- real Android 1× uTower import;
- process interruption during Import;
- Restore Replace interruption;
- Restore Merge identity and baseline-lineage tests;
- independent current-position oracle;
- no partial business visibility;
- G4 stale-sync interaction tests.

### Open item
Real Android proof remains the principal G3 closure evidence gap. Host SQLite performance/rollback results are supporting evidence only. Restore/Merge transport-state normalization must also be verified so stale historical outbox/cursor/retry/tombstone metadata is not blindly replayed while valid current cloud obligations remain durable.

### Status
**ARCHITECTURE ACCEPTED / IMPLEMENTATION PARTIALLY OPEN (Restore Merge) / VERIFICATION PENDING**

---

## G4 — Concurrency / Stale Sync

### Gate
**G4 — Concurrency / Stale Sync / Maintenance Isolation**

### Decision
**TARGET SEMANTICS ACCEPTED; IMPLEMENTATION BOUNDARY STILL OPEN**

Required invariant:

    BEGIN Room write transaction
        read current generation
        compare expected generation
        if mismatch:
            reject stale result
        else:
            apply remote business result
    COMMIT

Restore/Import must change/invalidate the generation inside their same final business transaction.

### Current v71 problem
The current implementation lacks the accepted local generation/invalidation mechanism.

`remoteVersion`, `updatedAt`, cursors, and outbox conflict checks are not substitutes for a local dataset-generation boundary.

The stale-sync risk has been identified and requires executable adversarial proof. No source/result artifact is treated as demonstrated evidence unless it is independently located and reproducible.

The coordinator/lock design also has scope and lock-order concerns.

### Target rule
- Normal financial mutations: Room transaction only.
- Sync network I/O: no business mutation/maintenance lock and no Room transaction held across network.
- Sync apply: short Room write transaction with generation check + apply.
- Restore/Import: short maintenance/business publication transaction that changes generation + commits new business state.
- Full local dataset clear/sign-out: treated as a session/data-lineage invalidation boundary.
- A normal local ledger mutation is treated as same-lineage unless concurrency evidence demonstrates otherwise; it does **not** automatically increment generation merely because a ledger row changes.
- No AB/BA lock-order inversion.
- No broad global coordinator for ordinary CRUD/ledger mutation.

### Implementation consequence
- Add the smallest local persisted generation mechanism compatible with existing metadata.
- Capture expected generation with the sync result.
- Re-read current generation inside the same Room write transaction that applies it.
- Invalidate/increment generation inside Restore/Import final transaction.
- Reduce coordinator scope to genuine maintenance exclusion only.
- Ensure no network operation occurs under the business exclusion.
- Keep single-flight orchestration separate from maintenance business locking.

### Verification requirement
- exact stale-sync TOCTOU race;
- Restore vs in-flight sync;
- Import vs in-flight sync;
- concurrent local mutation + remote ledger apply;
- Restore/Import vs normal mutation;
- no AB/BA deadlock;
- no network await under business mutation lock.

### Open item
Exact physical generation storage key/schema, final coordinator call graph, sign-out/full-clear invalidation placement, and the concurrency proof that ordinary local ledger mutations can remain same-lineage without a generation bump must be verified against the current patched source.

### Status
**ARCHITECTURE ACCEPTED / IMPLEMENTATION OPEN / VERIFICATION OPEN**

---

## G5 — Identity / Import Collision

### Gate
**G5 — Ledger / Import / Cloud Identity**

### Decision
**CLOSED ARCHITECTURALLY; IMPLEMENTATION / MIGRATION PROOF PENDING**

### Current v71 problem
Normal runtime IDs are stable UUID/idempotency IDs.

Imported historical identity is safe when an explicit source key exists, but the source-missing fallback can collapse legitimate distinct rows when business fields are identical.

### Target rule
**Normal runtime**
- application-generated UUID or explicit idempotency key;
- generate once;
- reuse through retry/recovery.

**Historical import rows**
- same source artifact + same deterministic source-row/provenance coordinate → same transaction ID;
- distinct legitimate source rows → distinct transaction IDs;
- do not use destination SQLite `ROWID` as the stable source coordinate unless separately proven appropriate by source-format evidence.

**Import**
- explicit source ID when available;
- otherwise provenance + source-row/occurrence identity sufficient to satisfy:
  - same source row → same ID;
  - distinct legitimate rows → distinct IDs.

**Restore Merge / Firebase**
- transaction ID is the logical local/cloud identity;
- same ID → one;
- different ID → preserve both.

Existing reliable IDs must not be regenerated during migration.

### Implementation consequence
- Correct the importer identity fallback.
- Preserve existing reliable IDs.
- Do not build a generalized identity registry.
- Re-import remains idempotent.
- Historical collision recovery must use original source evidence; migration must not invent missing history.

### Verification requirement
- normal runtime idempotency key retry;
- same uTower file imported twice;
- two identical legitimate historical rows with no source key;
- Restore Merge identity behavior;
- local ID → Firebase document ID consistency;
- migration preserves existing IDs.

### Open item
Determine whether ZIP 71 already contains historically collapsed rows. If so, preserve existing surviving identity; reconstructing a genuinely lost second row requires original uTower evidence or a known-good backup.

### Status
**ARCHITECTURE CLOSED / IMPLEMENTATION OPEN FOR COLLISION FIX / VERIFICATION PENDING**

---

## G6 — Profile Semantics

### Gate
**G6 — Account/Profile/Notes/Credentials Semantics**

### Decision
**SEMANTIC FRAMEWORK CLOSED — FIELD-LEVEL CLASSIFICATION PENDING**

Classes:
1. ISP/server-owned;
2. reseller/local profile;
3. reminder notes;
4. operational credentials;
5. derived financial fields;
6. legacy/history-only.

### Current v71 problem
Some profile/settings/credential behavior is interleaved with synchronization mechanisms and needs exact field-level authority verification.

### Target rule
- ISP owns server state.
- Local profile metadata remains local unless explicitly approved for sync.
- Notes use LWW; no field-level merge.
- Credentials are a separate UID-scoped recovery domain.
- Current debt/advance is derived, not an independent sync authority.
- ISP balance is separate from subscriber debt.
- General settings sync is out of V1.
- Legacy/history-only users do not require an active ISP profile.

### Implementation consequence
- Explicitly classify remaining mutable profile fields.
- Fix UID/session credential TOCTOU where required.
- Preserve notes as simple LWW metadata.
- Prevent profile/credential operations from generating ledger entries.
- Prevent arbitrary profile edits from directly overwriting derived financial state.

### Verification requirement
- notes LWW;
- credential logout/login delayed-response isolation;
- explicit credential clear;
- ISP state refresh;
- legacy/history-only user preservation;
- ISP balance vs subscriber-debt separation;
- only Activation/Renewal/Refill produce financial ledger entries.

### Open item
Exact field-by-field ownership mapping for remaining mutable `LocalAccount` fields, especially `loanIqd`, `isLegacy`, `isSnapshotHistory`, `stateSource`, and `stateConfidence`. Until independently classified, these fields must not be silently deleted, reset, redefined, or reinterpreted during implementation or migration. ISP-side subscriber deletion must not delete local ledger/history; missing ISP profiles remain legacy/history-only locally.

### Status
**ARCHITECTURALLY CLOSED / SEMANTIC VERIFICATION PENDING**

---

## G7 — Migration Closure

### Gate
**G7 — Existing v71 → Target Migration**

### Decision
**BUSINESS-DATA-PRESERVING MIGRATION**

### Current v71 problem
Legacy schema and transport state contain:
- old outbox statuses;
- sync metadata;
- destructive account→ledger cascade semantics;
- possibly collision-prone historical identity;
- legacy snapshot/position fields;
- old backup metadata.

### Target rule
Preserve:
- reliable ledger IDs;
- ledger history;
- baselines;
- current financial meaning;
- legacy/history-only subscribers;
- credentials;
- usable recovery artifacts;
- unresolved semantic fields such as `loanIqd`, `isLegacy`, `isSnapshotHistory`, `stateSource`, and `stateConfidence` until independently classified.

Rebuild/normalize:
- obsolete transport states;
- stale/rebuildable sync metadata;
- G4 initial generation.

Remove destructive FK semantics.

### Implementation consequence
- Pre-migration backup.
- Financial row/ID conservation audit.
- Normalize legacy transport states without abandoning business obligations.
- Preserve valid remote-version data where still required.
- Never promote local timestamps to remote-version authority.
- Replace `ON DELETE CASCADE` safely.
- Verify current-position reconstruction independently.
- Preserve legacy/history-only users.
- Verify old backup compatibility.

### Verification requirement
- migration kill points;
- ID conservation;
- imported identity preservation;
- outbox legacy-state normalization;
- baseline preservation;
- independent financial-position oracle;
- credential preservation;
- legacy-user preservation;
- FK/cascade migration;
- old backup → target restore;
- G4 generation initialization.

### Open item
Exact classification of every existing `sync_metadata` key and exact old-backup compatibility matrix.

### Status
**ARCHITECTURE ACCEPTED / MIGRATION EXECUTION & VERIFICATION PENDING**

---

## G8 — Minimal Certification

### Gate
**G8 — Independent Machine-Verifiable Certification**

### Decision
**MINIMAL EVIDENCE-BUNDLE + VERIFIER MODEL**

Certification does not trust agent reports, PASS booleans, manifests, or narrative closure claims.

### Current v71 problem
Narrative test reports cannot independently prove:
- exact source artifact;
- exact test corpus;
- actual execution;
- actual instrumentation execution;
- actual release artifact.

### Target rule
Certification remains **external to the production application/runtime**. It is evidence infrastructure only and must not become a runtime governance subsystem.

One certification run produces one evidence bundle containing:

```text
source identity
test corpus
execution results
instrumentation results
release artifact identity
artifact SHA-256
machine-derived final states
```

States remain distinct:

```text
ARCHITECTURE_COMPLETE
IMPLEMENTATION_COMPLETE
VERIFIED
PRODUCTION_READY
```

### Implementation consequence
- Machine-inventory test files.
- Map mandatory test IDs to actual executable methods.
- Parse actual Gradle/Android result artifacts.
- Reject missing/stale execution results.
- Detect obvious vacuous tests.
- Require real instrumentation where applicable.
- Build the release artifact.
- Record artifact SHA-256.
- Derive final states from evidence rather than accepting agent-declared states.

### Verification requirement
Certification self-tests must reject:
- missing test;
- stale result;
- vacuous assertion;
- report-only PASS;
- unavailable instrumentation;
- wrong source artifact;
- wrong release binary;
- blocked mandatory test.

### Open item
The machine verifier must be implemented and independently exercised before G8 can close.

### Status
**ARCHITECTURE ACCEPTED / CERTIFICATION IMPLEMENTATION & EXECUTION PENDING**

---

# Cross-Gate Dependencies

## G5 → G2
Transaction identity → outbox `entityId` → Firebase document identity.

If identity changes, cloud idempotency can fail.

## G5 → G3
Restore Merge depends on stable transaction identity.

## G3 → G4
Restore/Import establish a new local business lineage; publication and generation invalidation must be one Room transaction.

## G4 → G3
Import/Restore cannot close until stale pre-publication sync results are rejected.

## G5 → G7
Migration must preserve existing reliable transaction IDs.

## G7 → G8
Certification must bind to the actual post-migration source/build identity while retaining evidence that migration started from ZIP 71.

## G2 → G8
Cloud durability requires executable lost-ACK/poison/orphan evidence.

## G3/G4 → G8
Android/device-specific G3/G4 proof must execute where required; host-only evidence cannot substitute.

---

# Contradictions

### `remoteVersion` vs G4 generation
`remoteVersion` / `updatedAt` answers remote entity freshness. G4 generation answers whether an asynchronous sync result is still valid for the current local business dataset lineage. They are separate concepts.

### DEAD_LETTER
Legacy `dead_letter` is not an accepted target business state. Failed obligations remain retained/backed off.

### Stored snapshot balance vs current-position semantics
Stored snapshot values are baseline data, not a substitute for deterministic current-position semantics.

### Restore Merge
Lineage metadata may exist, but metadata existence is not the same as a Merge implementation. Conflicting baselines require complete-lineage selection.

### ZIP 71 vs app version
ZIP/artifact sequence `71` is not `VersionCode 71`. The application reports `VersionCode 68 / VersionName 1.68.0`.

---

# Remaining Risks

## High
1. G4 generation implementation and stale-sync rejection.
2. G5 import identity collision / already-collapsed historical rows.
3. G3 Restore Merge implementation and lineage correctness.
4. G7 account→ledger FK migration safety.
5. G2 real Firestore lost-ACK idempotency.

## Medium
6. Exact sync metadata retained/reset inventory.
7. Credential clear semantics.
8. Complete mutable-profile field ownership mapping.
9. Android proof for Direct Atomic Room.
10. Old-backup compatibility after migration.

## Accepted/bounded
11. G1 complete-data-wipe recovery limitation.
12. Direct Room remains the V1 architecture unless real evidence demonstrates a concrete limitation.

---

---

# Architecture Freeze Recommendation

## **ARCHITECTURE FREEZE: APPROVED IN PRINCIPLE**

This means the **target architecture is approved for implementation freeze**. It does **not** mean every gate is implementation-complete or verification-complete.

### Overall state

```text
Architecture Freeze:
APPROVED IN PRINCIPLE

Implementation:
NOT COMPLETE

Verification:
NOT COMPLETE
```

### Frozen V1 target

```text
Direct Atomic Room
+
short local business transactions
+
minimal maintenance exclusion
+
transactional G4 generation invalidation
+
stable transaction identity
+
durable per-item outbox
+
lineage-aware Restore Merge
+
business-data-preserving migration
+
machine-verifiable external certification
```

Do **not** introduce:

```text
dataset_id
published_dataset_id
staging database
identity registry
generic reconciliation engine
generic synchronization state machine
governance registry-of-registries
```

unless future evidence demonstrates a concrete requirement that cannot be satisfied by the frozen model.

## Consolidated gate status

| Gate | Architecture | Implementation | Verification | Status |
|---|---|---|---|---|
| G1 | Closed | Accepted | Closed with explicit bounded limitation | **CLOSED — BOUNDED RECOVERY LIMITATION ACCEPTED** |
| G2 | Closed | **Forensic Baseline = ZIP 71; Implementation Artifact Under Verification = current patched source** | Pending | **IMPLEMENTATION / VERIFICATION STATUS MUST FOLLOW CURRENT ARTIFACT** |
| G3 | Accepted | Restore Merge / remaining implementation pending | Pending | **OPEN** |
| G4 | Accepted | Generation / lock boundary pending | Pending | **OPEN** |
| G5 | Closed | Collision / migration work pending | Pending | **OPEN** |
| G6 | Closed | Field-level semantic verification pending | Pending | **SEMANTIC VERIFICATION PENDING** |
| G7 | Accepted | Migration pending | Pending | **OPEN** |
| G8 | Accepted | External verifier pending | Pending | **OPEN** |

> **Interpretation rule:** `Architecture Approved in Principle` ≠ `Implementation Complete` ≠ `Verification Complete`.
>
> For G2 specifically, implementation status must always be attributed to the exact source artifact under verification. ZIP 71 describes the forensic baseline; a later patched source determines current implementation status.

Remaining work is implementation and executable evidence against the frozen design, not another architecture redesign cycle.

---

# Final Document Verification

## Draft review
- Incorporated the requested G2 artifact-baseline distinction.
- Clarified G6 as framework-closed but field-classification pending.
- Weakened stale-sync wording to evidence-safe language.
- Added the explicit transport-reconstruction/no-second-state-machine invariant.
- Preserved the accepted Direct Atomic Room direction and all fixed product semantics.

## Structural consistency review
- Exactly one G1–G8 section exists.
- Cross-Gate Dependencies, Contradictions, and Remaining Risks each appear once.
- Architecture, implementation, and verification statuses remain distinct.

## Cross-gate review
- G5 identity ↔ G2 outbox identity: consistent.
- G5 identity ↔ G3 Merge: consistent.
- G3 Restore/Import ↔ G4 generation: consistent.
- G4 session/lineage invalidation ↔ G6 account switch: consistent.
- G7 migration ↔ G5 identity preservation: consistent.
- G8 certification remains external to production runtime: consistent.

## Final review
**PASS — no architecture redesign, no fixed product decision reopened, no duplicate final sections, and no known requirement loss identified in this consolidated summary subject to the exact source/artifact distinction stated above.**
