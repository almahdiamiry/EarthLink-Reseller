# EARTHLINK RESELLER APP — TARGET PRODUCT CONTRACT & ARCHITECTURAL REASSESSMENT QUESTIONS

**Version:** v0.6 — Final Product Contract Clarification Revision
**Date:** 2026-08-17
**Status:** Product Contract Clarification Revision — Final for Architecture Adjudication
**Artifact under review:** Earthlink Reseller App v71

---

## 1. PURPOSE AND READING RULE

This document defines the **target product behavior** that has already been decided during product clarification. It is **not** an invitation for the architect to redefine the product.

The architect's task is to determine:

> **How can the current v71 codebase be transformed into this target product using the smallest technically sound architecture, while preserving historical/account integrity and eliminating the failure loops already demonstrated in v71?**

The architect may challenge a technical assumption when source evidence proves it unsafe or incomplete, but must not silently change a stated business rule into a different product requirement.

The current forensic findings describe the behavior of v71. They do **not** imply that every current v71 mechanism must survive into the final architecture. In fact, the purpose of this review is specifically to distinguish:

```text
REAL PRODUCT REQUIREMENT
        vs.
CURRENT IMPLEMENTATION MECHANISM
```

A mechanism must justify its existence against the target product.

---

# 2. TARGET PRODUCT — FINAL INTENT

## 2.1 Product identity

The application is primarily:

> **A reseller/customer account ledger with Earthlink/ISP operational API integration, local persistence, Firebase cloud persistence, uTower historical import, and emergency recovery.**

It is not intended to become:

- a generic distributed database;
- a best-in-class synchronization platform;
- a general-purpose conflict-resolution framework;
- a full accounting/ERP platform;
- a generic governance/certification platform.

The primary production objective is:

> **A reseller must be able to use the application without silent loss, deletion, duplication, or corruption of account/ledger history.**

Security and maintainability remain important, but release blocking priority is financial/account-history integrity.

---

# 3. BUSINESS / PRODUCT DECISIONS — TREAT AS FIXED TARGET

## 3.1 Subscriber ledger

The application is a practical account book. Typical records include:

- debt;
- payment/settlement;
- advance/prepayment where applicable;
- notes/history;
- the reseller-side customer charge produced by an activation, renewal, or refill operation.

The application's subscriber ledger records the amount charged to the subscriber according to the reseller's own pricing/ledger practice.

The ISP's own subscription price and ISP balance/credit are different concepts:

```text
ISP Balance / Credit
        ≠
Subscriber Debt / Account Position
```

The ISP balance is read from the Earthlink/ISP API. It is not the same thing as subscriber debt in the local ledger.

## 3.2 Ledger history is protected

Historical ledger activity is the important record.

A normal correction must not erase the original financial event.

Example:

```text
Original mistake:
+100,000

Correction:
+100,000
-90,000  correction/payment with note
----------------
Net effect = +10,000
```

The exact UI wording can vary, but the historical record must remain available.

**Target rule:**

> Correct historical financial position through additional ledger activity, not by deleting the historical activity.

## 3.3 Current subscriber position

The application must display a clear current position for the subscriber.

Target semantic direction:

```text
Opening/baseline position
        +
subsequent ledger activity
        ↓
Current subscriber position
```

A current total may remain physically stored for fast UI access. It is a **materialized/derived value**, not a competing historical authority.

If the stored current total disagrees with trustworthy local history/baseline information, the system must be able to rebuild the current total without deleting history.

For imported uTower accounts, the opening/current baseline is not necessarily reconstructable from old history and therefore must be preserved from the uTower snapshot as described in Section 3.6.

## 3.4 Advance/prepayment

Advance is a real business concept.

Example:

```text
Debt = 10,000
Payment = 30,000

Result:
Debt = 0
Advance = 20,000
```

The `loanIqd` field is retained for legacy/uTower compatibility and historical-data preservation. It is not an independent V1 financial authority and must not be used as a separate debt, synchronization, or recovery state. It must not be silently deleted, reset, or redefined during implementation or migration unless new direct business evidence explicitly requires such a change.

## 3.5 Earthlink / ISP operations

Earthlink API operations are real product functionality and remain in scope.

The final application may continue to expose the operational API capabilities actually used by the reseller, including account lookup/details, ISP balance, activation/refill, renewal/extension, password-related operations and other required ISP operations.

The app does not become the ISP's authoritative server.

**Ledger-producing ISP operations are limited to:**

```text
Activation
Renewal / Extension
Refill
```

Only these operations create a financial ledger entry for the reseller's subscriber charge.
Other ISP/API operations, including lookup, details, password operations, balance/status reads, and other non-financial actions, do not create financial ledger entries.

Conceptually:

```text
Earthlink API
    ↓
External ISP operation

Local application
    ↓
records the reseller-side subscriber transaction when applicable
```

Example:

```text
ISP price = 20,000
Subscriber charge entered by reseller = 40,000
```

The subscriber ledger records the 40,000 customer-side amount. The 20,000 ISP price and ISP balance remain API-side concepts.

## 3.6 uTower import is a snapshot import

uTower import is **not** an attempt to reconstruct five years of accounting from imperfect historical data.

It is a historical snapshot import.

Example:

```text
uTower current debt = 500,000
old history = incomplete / unreliable / missing dates

Target:
opening/current baseline = 500,000
source history = preserved as supplied where available
new app transactions = continue from that baseline
```

The importer must not silently replace a trusted uTower snapshot amount by reconstructing old debt from incomplete history.

The target import model is therefore:

```text
uTower snapshot
      ↓
preserve source data
      ↓
establish opening/current baseline
      ↓
normal application ledger continues
```

## 3.7 External operation outcome — intentionally simple

The application does not need a sophisticated autonomous distributed-transaction protocol with Earthlink.

For an Activation, Renewal/Extension, or Refill operation, the external outcome is determined by the Earthlink/API result and the normal subscriber-state verification workflow.

The product does not require an autonomous distributed transaction/reconciliation engine for unknown outcomes. A user may need to verify the actual subscriber state through the normal application/API workflow before deciding whether an external operation should be repeated.

This is an intentional product simplification.

### External-operation-to-local-ledger durability boundary

The separation is explicit:

```text
Earthlink/API determines external operation outcome
        +
local durability mechanism preserves the reseller accounting record
        +
Firebase synchronization remains a separate cloud-copy concern
```

For operations that are supposed to create a local reseller charge, a successful external operation must not silently disappear from the local accounting record after a crash, OOM, lifecycle termination, or equivalent interruption.

The required implementation is the minimum mechanism necessary to preserve that local accounting obligation. It must not become an autonomous external-operation reconciliation engine.

## 3.8 Offline ledger operation

The reseller must be able to record ledger activity without active internet connectivity.

```text
Internet OFF
    ↓
record transaction locally
    ↓
history remains available
    ↓
connection returns
    ↓
cloud copy is synchronized
```

Cloud failure must never roll back or delete a confirmed local ledger transaction.

Target behavior:

```text
Local ledger = saved
Cloud sync   = pending/failed
Retry        = later
```

## 3.9 Same Firebase identity on multiple devices

The same Firebase identity may operate the same account/subscriber from multiple devices.

Example:

```text
Firebase already contains +10

Device A adds +10
Device B adds +20

Final cloud history:
+10 existing
+10 new
+20 new

Total = 40
```

Independent transactions must accumulate. They are not competing versions of the same transaction.

The target architecture must therefore avoid treating two independent ledger events as a last-write-wins conflict merely because they affect the same subscriber total.

## 3.10 Ledger transaction identity

Every newly created ledger transaction must receive a stable unique identifier.

The purpose is **retry idempotency**, not device identification.

```text
Create T123 locally
       ↓
Upload T123
       ↓
ACK lost
       ↓
Retry T123
       ↓
Cloud must not create T123 twice
```

A genuinely new transaction on another device must receive a different ID.

The architect must verify whether the existing v71 transaction IDs and Firestore document IDs already satisfy this requirement before proposing new identity mechanisms.

## 3.11 Firebase cloud copy

Firebase exists primarily so the user's account book survives:

- app deletion/reinstallation;
- device replacement;
- normal operational recovery.

The target business dataset is approximately:

```text
Subscriber/Account records
+
Ledger / transaction history
+
required notes/history
+
required operational credentials
```

Technical synchronization metadata is not itself part of the user's business history.

Examples include:

```text
cursor position
remote-version cache
transient transport state
pending outbox transport state
```

The architect must determine which technical metadata should be reconstructed locally rather than treated as part of the restored business dataset.

## 3.12 Operational credentials

These are operationally important and must be recoverable on a new/reinstalled device:

- ISP admin username;
- ISP admin password;
- deposit password where required by the existing workflow.

These are **not ordinary optional UI settings**.

They may therefore require secure Firebase-backed persistence so that after Firebase login the application can resume the Earthlink API workflow.

The exact secure storage/encryption architecture is a technical question, but the business requirement to recover the operational credentials is fixed.

## 3.13 General settings

General application settings synchronization is not a core V1 requirement.

It may be added later as a nice-to-have.

Do not preserve a large general-settings synchronization state machine merely because v71 already contains one.

Only the operational credentials required to resume the application remain in the V1 recovery requirement.

## 3.14 ISP-side deletion does not delete local history

This is a firm business rule.

```text
ISP removes subscriber
        ↓
local application keeps subscriber record
        ↓
legacy/history-only usage
        ↓
historical debt and transactions remain
```

The app does not need to delete the local customer because the ISP deleted the remote record.

Therefore remote business deletion of the local ledger/account history is **not a required V1 operation**.

## 3.15 Developer destructive tools

Some delete/clear functions exist only for development/testing.

They are not business functionality.

They may remain temporarily while development continues, but must not remain exposed as normal production functionality and should eventually be removed/disabled from production builds.

## 3.16 Restore

Restore is for recovery of the account book.

The final product offers:

```text
Restore
 ├── Replace
 └── Merge
```

### Replace

Replace the current local business dataset with the selected backup snapshot.

Before replacement, an automatic safety backup of the current dataset is desirable so that an accidental replacement remains recoverable.

### Merge

Merge current and backup business history without duplicating the same transaction.

Target rule:

```text
same transaction ID → same transaction → keep one
new transaction ID  → preserve it
```

After either mode, current subscriber totals must be rebuilt deterministically from the resulting business dataset/baseline.

### Opening-baseline rule

The uTower/imported opening or baseline position is **not equivalent to an ordinary post-import ledger transaction**. Therefore, when two datasets being merged contain conflicting opening/baseline information, the Merge algorithm must not add both baselines or mix one baseline with the other dataset's associated ledger history.

**Final product rule:**

> **When baselines conflict, the user chooses the complete snapshot lineage to retain: the selected baseline together with its associated ledger history.**

Therefore the system must never silently create combinations such as:

```text
Baseline A + Ledger history B
Baseline B + Ledger history A
```

A compatible Merge may preserve both datasets' independent transaction histories according to transaction identity, but a conflicting baseline requires explicit user selection of the complete lineage.

The final result must preserve mathematical correctness of the reconstructed current position and must not silently erase unrelated ledger history.

No temporal branch/reconciliation framework should be introduced.

## 3.17 Demo Mode

Demo Mode is out of scope.

Only one pre-removal check is required:

> Verify that no production/business/API/synchronization/certification path depends on it.

Then:

```text
DEMO MODE = REMOVE / OUT OF SCOPE
```

No redesign or dedicated architecture is required.

## 3.18 Production-ready definition

For this project, production readiness primarily means:

> **The reseller can use the application without known behavior that silently deletes, loses, duplicates, or corrupts account/ledger history.**

It does not mean:

- best-in-class distributed synchronization;
- enterprise-grade security architecture;
- zero technical debt;
- generic distributed-database capabilities;
- every conceivable Earthlink API feature.

Data integrity is the primary release-blocking criterion.

---

# 4. TARGET ARCHITECTURAL PRINCIPLE

The central principle is:

> **Do not solve every current v71 problem by adding another state, proxy, registry, authority, synchronization layer, or governance layer. First ask whether the underlying feature/state is actually required by the target product.**

Then:

```text
Required product feature
        ↓
Smallest sound technical model
        ↓
Explicit authority
        ↓
Explicit transition only where needed
        ↓
Behavioral/adversarial proof
```

Do not repeat the historical pattern:

```text
Current bug
   ↓
new flag
   ↓
new registry
   ↓
new state
   ↓
new exception
   ↓
new gate
   ↓
new report
```

The existing forensic work identified exactly this type of compensating-layer loop. fileciteturn9file7L1422-L1476

---

# 5. WHAT IS ALREADY ESTABLISHED IN THE CURRENT CODEBASE

The architect should treat the following as existing evidence, then evaluate whether the mechanisms are still necessary:

1. `LocalAccount` stores current financial fields while `LocalLedgerEntry` stores transaction history; v71 already has recalculation paths. fileciteturn8file6L1363-L1395
2. A confirmed remote-delete path can physically delete ledger history through `ON DELETE CASCADE`. fileciteturn9file1L133-L191
3. The v71 sync layer contains independent account/ledger processing and conflict semantics. fileciteturn9file5L898-L978
4. `dead_letter` is excluded from active-mutation detection but remains retryable, producing a confirmed semantic loop. fileciteturn9file8L1493-L1503
5. Coordinator and `singleFlightMutex` currently have opposite acquisition paths. fileciteturn9file8L1531-L1565
6. v71 uses local business timestamps as fallback remote-version proxies, contradicting the stated remote-version invariant. fileciteturn9file4L687-L727
7. Cursor query ordering and cursor advancement can use different coordinate domains. fileciteturn9file3L574-L654
8. The coordinator/single-flight paths contain a confirmed lock-order inversion (`singleFlight → coordinator` and `coordinator → singleFlight`). This establishes a concrete deadlock mechanism/risk; a statement that a deadlock has been **observed/reproduced at runtime** must be supported by an executable concurrency test rather than inferred from source alone.
9. General settings have their own synchronization system and confirmed state/session risks. fileciteturn9file4L731-L877
10. v71 certification evidence is internally inconsistent with its current test corpus and gates. fileciteturn9file0L23-L46 fileciteturn9file2L228-L428

These facts are **current-state evidence**, not automatic design requirements.

---

# 6. TECHNICAL QUESTIONS FOR THE ARCHITECT

These questions are deliberately technical. The target product decisions above are fixed.

The architect should answer with a **minimal route to target**, not a generic architecture essay.

## TQ-01 — Account current totals vs ledger history

Can `LocalAccount` financial totals be treated as materialized/derived values whose authoritative historical source is the account baseline plus ledger activity?

Required answer:

- how imported/opening baseline data is represented separately from post-import ledger activity;
- what remains persisted;
- what is derived;
- when recalculation runs;
- how mismatch is detected;
- how mismatch is repaired without deleting history;
- how cloud synchronization avoids treating derived totals as independent competing transactions.

## TQ-02 — Ledger as additive immutable history

Can ledger entries be synchronized as independent records using stable transaction identity rather than mutable-state conflict resolution?

Evaluate the minimum model:

```text
transaction ID
+
insert-if-absent
+
retry-safe upload
+
preserve-on-failure
```

versus the current model's broader:

```text
remote version
+
conflict arbitration
+
delete/tombstone semantics
+
last-write-wins-style decisions
```

If the simpler model is insufficient, give concrete counterexamples from the current source/API/data contract.

## TQ-03 — Which Account fields actually require independent cloud state?

Separate the current account model into:

- subscriber identity/profile;
- ISP/API metadata;
- opening/baseline position;
- current materialized totals;
- historical ledger;
- synchronization-only metadata.

For each, state whether it is:

```text
business data
projection/cache
technical metadata
```

and whether it actually needs independent synchronization.

## TQ-04 — Scope of remote-version machinery

Which entity types genuinely require remote-version comparison?

Do not assume that one global remote-version mechanism must govern every entity.

At minimum assess:

- immutable ledger entries;
- mutable account/profile data;
- uTower import/snapshot data;
- operational credentials;
- technical synchronization metadata.

The architect must explicitly identify where version comparison can be removed rather than merely repaired.

## TQ-05 — Minimum cloud synchronization algorithm

What is the smallest safe mechanism that guarantees:

```text
local transaction saved
        ↓
cloud copy eventually contains it
```

and:

```text
same transaction retried
        ↓
no duplicate
```

Assess whether the current cursor/effectiveVersion/replay machinery is necessary for expected data volume and Firebase query behavior.

If cursoring remains necessary, define one coherent ordering coordinate and eliminate competing coordinates.

## TQ-06 — Outbox state machine

Can synchronization transport state be reduced to something equivalent to:

```text
PENDING
FAILED
```

with successful records removed?

Determine whether `DEAD_LETTER`, quarantine and resurrection semantics are actually required by a product requirement.

Do not retain a terminal state merely because v71 currently contains one.

## TQ-07 — DataOperationCoordinator scope

Can ordinary ledger/account mutations rely on short Room/database transactions without entering a global operation coordinator?

Identify only the operations that genuinely require exclusive maintenance scope, such as:

- restore;
- import;
- migration;
- developer reset.

Assess whether normal CRUD/ledger operations can leave the global ownership mechanism.

## TQ-08 — Concurrency and lock simplification

If a small maintenance coordinator remains, define the minimum locking model that cannot recreate the current AB/BA relationship:

```text
singleFlight → coordinator
```

and:

```text
coordinator → singleFlight
```

No broad lock should be held across network waits.

## TQ-09 — Operational credential recovery

Design the minimum safe Firebase persistence/recovery model for:

- ISP admin username;
- ISP admin password;
- deposit password where required.

The solution must support a new-device login/recovery flow without turning every application setting into a synchronized domain.

## TQ-10 — Credential/session ownership

After an asynchronous Firestore operation begins, what is the simplest rule that guarantees a stale response from Firebase user A cannot write operational credentials into user B's current local state?

The expected model should be tied to the actual current authenticated identity, not to a cached `targetUid` alone.

The existing v71 code-path TOCTOU risk is documented. fileciteturn9file4L851-L877

## TQ-11 — uTower snapshot import

Can import be reduced to:

```text
parse source snapshot
        ↓
preserve source history as supplied
        ↓
establish opening/current baseline from trusted snapshot fields
        ↓
continue normal application ledger
```

Identify which current reset/reconciliation logic is essential and which is legacy complexity.

## TQ-12 — Restore Replace

Can Replace be a simple, atomic, auditable dataset replacement with an automatic pre-restore safety backup?

Required properties:

- no silent partial replacement;
- no replay of obsolete network intents;
- deterministic sync reinitialization;
- easy recovery to the pre-restore backup.

## TQ-13 — Restore Merge

Design the minimum merge algorithm:

```text
same transaction ID → one transaction
new transaction ID  → preserve it
```

Also define how subscriber/account metadata and current totals are rebuilt after the merge.

Do not create a broader temporal branch/reconciliation framework unless source evidence proves the simple model insufficient.

## TQ-14 — Remote delete removal and history protection

Determine the minimum changes required to remove remote business deletion from the normal product path.

Explicitly inspect:

- Firestore delete listeners;
- `APPLY_DELETE` paths;
- account/ledger DAO delete methods;
- `ON DELETE CASCADE`;
- tombstone handling;
- UI delete/reset paths.

Target outcome:

> ISP-side deletion must not delete local financial history.

## TQ-15 — Current-position rebuild contract

Define exactly when current subscriber totals are rebuilt/revalidated:

- after a local transaction;
- after a newly downloaded transaction;
- after Firebase bootstrap;
- after restore Replace;
- after restore Merge;
- after uTower import;
- after v71-to-target migration.

The rebuild must be deterministic and must not alter historical transactions.

## TQ-16 — v71 migration

Design the migration path from the existing v71 dataset and metadata into the target model without losing:

- subscribers;
- existing ledger entries;
- uTower opening/baseline values;
- current displayed totals;
- operational credentials;
- recoverable Firebase business history.

Explicitly classify old synchronization artifacts as:

```text
business data to migrate
OR
technical state to discard/rebuild
```

Do not silently treat every v71 metadata row as business data.

## TQ-17 — New-device cloud bootstrap

Define the simplest safe first-login flow:

```text
Firebase authentication
        ↓
retrieve business dataset + operational credentials
        ↓
populate Room
        ↓
rebuild/revalidate current positions
        ↓
start normal synchronization
```

Identify which old v71 technical metadata must be reconstructed rather than restored.

## TQ-18 — Minimum production verification

Replace the large fragmented certification model with a compact set of business-critical behavioral/adversarial proofs.

At minimum prove:

1. A saved ledger transaction cannot silently disappear.
2. A transaction cannot silently duplicate because of retry.
3. Two independent device transactions are both retained.
4. Cloud failure never rolls back local history.
5. ISP-side deletion does not delete local history.
6. Current subscriber position can be rebuilt from preserved baseline/history.
7. Restore Replace cannot silently destroy the pre-restore dataset.
8. Restore Merge does not duplicate transactions.
9. A stale session cannot overwrite another user's operational credentials.
10. Release readiness is proven by actual build/test execution, not manifest presence or narrative claims.
11. Restore Merge cannot silently double-count or erase incompatible opening/baseline positions.
12. Concurrent local ledger operations cannot leave ledger history and current-position materialization inconsistent.
13. A successful external ISP operation cannot silently disappear from the local reseller accounting record when a local record is required for that operation.
14. Legacy/uTower import identity generation cannot silently collapse two distinct historical transactions into one.

---


## TQ-19 — Stable Entity Identity Across All Ledger Creation Paths

Before adopting the simplified `transaction ID + insert-if-absent` model, prove that every ledger transaction receives a stable, unique identity across **all** creation paths, including:

- normal local debt creation;
- payment/settlement creation;
- advance/prepayment creation;
- activation/renewal-generated ledger entries;
- uTower import;
- restore Merge;
- migration from v71;
- retry/re-upload of the same transaction.

The architect must identify the exact source path(s) that create the ID and show why the same transaction cannot silently receive different IDs across retry/recovery, or why duplicate prevention remains correct if it does.

Required evidence:

```text
source file + function
→ ID generation rule
→ storage key / Firestore document identity
→ retry behavior
→ duplicate-prevention behavior
```

---

## TQ-20 — Legacy / Import Identity Collision Safety

Explicitly verify the identity strategy for old uTower/imported records whose source metadata may be incomplete or non-unique.

A concrete counterexample must be tested:

```text
same account
same date
same amount
same transaction type
sourceKey missing
```

The current importer/source logic must be checked for any fallback such as:

```text
sourceKey.ifEmpty { "nokey" }
```

or any equivalent deterministic-ID construction that could assign the same transaction identity to two genuinely different legacy records.

The architect must answer:

1. Can two distinct imported transactions currently receive the same identity?
2. If yes, can the current importer silently discard one as a duplicate?
3. What is the minimum collision-safe identity strategy that preserves the snapshot exactly?
4. How are already-imported v71 records migrated without changing historical meaning?

No assumption that “UUID means unique” is sufficient unless the source path actually proves how the UUID is generated.

---

## TQ-21 — Firebase Write Semantics and Retry Idempotency

Verify the exact Firebase write semantics used for ledger records.

The architect must prove what happens when:

```text
T123 created locally
        ↓
Firebase write succeeds
        ↓
ACK/response is lost
        ↓
T123 is uploaded again
```

Determine whether the current Firebase operation is effectively:

```text
same document identity → same logical transaction
```

or whether a retry can create a second document.

The answer must identify the actual Firebase API/write operation and the exact document-key construction used by v71, not merely state that “UUIDs are unique.”

Also verify whether local retry preserves the original transaction identity rather than generating a new identity.

---

## TQ-22 — Identity Consistency Across Import, Merge, Migration, and Cloud Bootstrap

The simplified model depends on transaction identity being meaningful across all recovery paths.

Verify that the same historical transaction retains a compatible identity through:

```text
uTower import
    ↓
Local Room
    ↓
Firebase upload
    ↓
Cloud bootstrap on new device
    ↓
Restore Merge
    ↓
v71 → target migration
```

The architect must identify any path that currently re-generates, transforms, or loses transaction identity.

If identity changes are unavoidable for legacy data, define the minimum deterministic mapping required so that Merge and cloud bootstrap cannot duplicate historical records.

---

## TQ-23 — Incomplete Cloud Copy + Two-Device Re-upload Scenario

Verify the simplified design against this concrete recovery case:

```text
Firebase currently contains:
    T1

Device A has:
    T1 + T2

Device B has:
    T1 + T3

Cloud copy becomes temporarily incomplete / stale.

Both devices later reconnect and upload their local histories.
```

Required result:

```text
Firebase:
    T1
    T2
    T3
```

with no silent deletion and no duplicate logical transactions.

The architect must explain how the current v71 implementation behaves today and how the target design guarantees convergence using the smallest necessary mechanism.

Do not answer only with “the sync retries”; show the actual identity, read/write, deduplication, and merge behavior.

---

## TQ-24 — Minimum Remote Metadata Actually Required

Identify the smallest set of Firebase/server-side technical metadata that must remain after simplifying the synchronization system.

Separate:

```text
Business data:
    account
    ledger/history
    operational credentials

Required sync metadata:
    ?

Disposable/rebuildable metadata:
    ?
```

For each retained metadata field, explain exactly which target requirement it protects.

In particular, explicitly classify whether the target design still needs any of the following for ledger synchronization:

- `remote_version`;
- per-collection cursor;
- `effectiveVersion`;
- tombstone metadata;
- outbox transport state;
- last-sync timestamps;
- other lineage/version markers.

The architect must not retain metadata merely because v71 currently stores it.

The preferred outcome is to **discard or rebuild technical metadata whenever the same target behavior can be safely achieved without it**.

---

## TQ-25 — Opening-Baseline Conflict During Restore Merge

The product decision is fixed:

> **When opening/baseline positions conflict, the user chooses the complete snapshot lineage to retain: baseline + its associated ledger history.**

The implementation must prevent:

- double-counting a baseline as an independent transaction;
- mixing a selected baseline with another dataset's conflicting ledger lineage;
- silently erasing unrelated ledger history;
- producing a mathematically inconsistent current position.

This is a product rule, not an invitation to invent a new generic reconciliation framework.

Required verification should prove:

```text
Dataset A baseline + Ledger A
Dataset B baseline + Ledger B
        ↓
user chooses A
        ↓
Baseline A + associated Ledger A
```

and symmetrically for B.

## TQ-26 — Atomicity of Ledger Mutation and Current-Position Update

If the global `DataOperationCoordinator` is reduced or removed from ordinary ledger operations, prove that normal local transaction processing remains safe under concurrency.

For each ledger operation, the architect must show how the following are made atomic and race-safe:

```text
ledger transaction insert
        +
current-position materialization/rebuild
        +
required local intent/outbox record
```

The proof must cover:

- two near-simultaneous local transactions;
- repeated UI submission;
- background work occurring while a local transaction commits;
- failure halfway through the transaction.

A `Room/@Transaction` proposal is not sufficient by name alone; the architect must demonstrate the actual transaction boundary and the invariant it protects.

## TQ-27 — External Operation Success vs Local Ledger Durability

The product intentionally does not require autonomous Earthlink unknown-outcome reconciliation. For the three ledger-producing ISP operations — Activation, Renewal/Extension, and Refill — the architect must close the smaller durability gap:

```text
Earthlink operation succeeds
        ↓
application crashes / OOM / lifecycle termination
        ↓
local ledger entry was never durably recorded
```

The architect must determine the minimum safe mechanism that prevents this from becoming a silent local accounting loss. Possible approaches may include a lightweight pre-operation intent record, a durable post-success operation record, or another mechanism, but **a general-purpose external reconciliation engine is not assumed or required**.

The answer must explicitly separate:

```text
external operation outcome
vs.
local reseller accounting record
vs.
Firebase cloud synchronization
```

and explain what happens on the crash/restart path.

The final mechanism must also remain consistent with the fixed product rule that the user verifies actual subscriber status rather than the application implementing an autonomous external reconciliation system. It must not silently turn the safety mechanism into a second distributed transaction/reconciliation engine.


# 7. SIMPLIFICATION REVIEW REQUIRED FROM THE ARCHITECT

For every major current v71 subsystem, return one classification:

```text
KEEP
SIMPLIFY
DESCOPE
REMOVE
REDESIGN
```

At minimum evaluate:

| v71 area | Required architectural question |
|---|---|
| `LocalAccount` financial fields | derived/materialized totals or independent authority? |
| `LocalLedgerEntry` | immutable/additive sync or mutable conflict entity? |
| `SyncConflictResolver` | required for which entity types? |
| `remote_version` | required where? |
| `RemoteSyncCursor` | required at expected scale? |
| `SyncOutbox` | minimum transport lifecycle? |
| `DEAD_LETTER` | required or removable? |
| `DataOperationCoordinator` | what truly needs global exclusivity? |
| `singleFlightMutex` | required after coordinator reduction? |
| Settings sync | general settings vs operational credentials |
| Remote delete/tombstones | can the business path be removed? |
| Restore | simple Replace + Merge? |
| uTower importer | snapshot preservation vs history reconstruction |
| Firebase bootstrap | business dataset vs technical metadata |
| Local backup | emergency recovery scope |
| Developer delete/clear | test-only isolation from production |
| certification/gates | minimum data-integrity proof |

---

# 8. ARCHITECT DELIVERABLE — EXPECTED FORMAT

The consultant should not return a generic architecture essay.

The required deliverable is:

# TARGET-TO-CURRENT ARCHITECTURE RECONCILIATION

It should contain:

## A. Target Contract Confirmation

Confirm that the Target Product Decisions in this document are understood as fixed product requirements.

## B. Current-v71 Reality Map

For each major subsystem:

```text
Current mechanism
Target requirement
Observed gap
Risk
```

## C. Simplification Matrix

```text
KEEP
SIMPLIFY
DESCOPE
REMOVE
REDESIGN
```

with a technical reason for each classification.

## D. Minimal Target Architecture

Show the smallest architecture that satisfies the target contract.

## E. Migration Strategy

Show how existing v71 data moves safely into the target model.

## F. Verification Strategy

Map each critical product invariant to actual behavioral/adversarial proof.

## G. Implementation Sequence

Only after the above:

```text
Target architecture agreement
        ↓
Migration design
        ↓
Implementation plan
        ↓
Implementation
        ↓
Independent verification
        ↓
Final adversarial audit
```

---

# 9. REVIEW RULES FOR THE ARCHITECT

The architect must not assume:

> Because v71 contains a mechanism, the final architecture must contain it.

Instead:

> **Every v71 mechanism must justify its existence against the Target Product Contract.**

### Evidence requirement for every material technical answer

Every material architectural/technical conclusion must be traceable to current evidence. At minimum, the architect must provide:

1. the exact current-source file/path and relevant function (and line range where available);
2. the observed current behavior supported by that source;
3. the fixed Target Product requirement being satisfied;
4. the proposed simplification/change;
5. any assumption that is not directly proven;
6. migration impact; and
7. the verification method that will prove the recommendation.

The following are not sufficient evidence by themselves:

```text
prior report
ADR statement
agent completion claim
manifest entry
boolean evidence flag
```

Any conclusion that cannot yet be established from current source, executable evidence, or a supplied API contract must be explicitly marked:

```text
UNVERIFIED
```

A material answer without source/executable evidence should be treated as incomplete.

The architect must also avoid these failure modes:

- preserving a state machine only because existing code already uses it;
- adding a new registry to repair an old registry;
- adding a generic synchronization engine before proving the product needs one;
- treating derived totals as independent business truth;
- treating Firebase transport state as user history;
- treating developer reset tools as product deletion capability;
- reintroducing business semantics that were explicitly descoped;
- replacing one authority proxy with another proxy of the same kind;
- proposing a rewrite solely because the existing architecture is complicated.

Any proposed new abstraction must state **which fixed target requirement requires it**.

---

# 10. WORKING ARCHITECTURAL HYPOTHESIS — NOT YET FROZEN

The current investigation suggests that the final model may be significantly simpler than v71:

```text
                    EARTHLINK API
                         │
            ┌────────────┴────────────┐
            │                         │
       ISP operations            ISP balance
            │
            ▼
      LOCAL ACCOUNT BOOK
            │
      ┌─────┴─────┐
      ▼           ▼
   Accounts     Ledger
                  │
                  ▼
       Current subscriber position
                  │
          ┌───────┴────────┐
          ▼                ▼
     Firebase Copy    Emergency Backup
```

The likely core synchronization responsibility is:

> **Preserve and transfer business history safely.**

This is only a working hypothesis. The architect must test it against the actual v71 source, Firebase/API behavior, expected data volume, migration requirements, and concrete counterexamples before declaring it final.

---

# 11. FINAL ARCHITECTURAL QUESTION

> **Can v71 be reduced to a smaller, clearer authority/state model that reaches the fixed Target Product Contract while eliminating the currently confirmed delete, synchronization, recovery, concurrency, cursor, and governance failure loops — without merely moving the same complexity into a new layer?**

If yes, define that smaller model and the safest migration/refactoring route.

If no, identify the exact fixed product requirement that prevents the simplification, provide the counterexample, and explain why the additional complexity is necessary.

---

# 12. STATUS

This document intentionally distinguishes:

```text
FIXED PRODUCT DECISION
        ≠
CURRENT v71 BEHAVIOR
        ≠
FORENSIC FINDING
        ≠
ARCHITECTURAL PROPOSAL
        ≠
IMPLEMENTATION PLAN
```

The Target Product Contract is fixed by product clarification.

The Minimal Target Architecture is **not yet frozen**.

Implementation remains blocked until architectural reconciliation and migration strategy are accepted.

**End of Target Product Contract v0.5**

---

# 13. ARCHITECT EVIDENCE REQUIREMENT

Every material technical conclusion in the architectural review must be evidence-bound.

For each substantive answer or recommendation, the architect must identify, where applicable:

1. **Exact current-source location** — file/path and relevant function, class, or line range.
2. **Observed current behavior** — what the v71 code actually does.
3. **Target requirement** — which fixed product decision the recommendation is intended to satisfy.
4. **Proposed technical route** — what should be retained, simplified, removed, redesigned, or migrated.
5. **Assumptions / uncertainty** — anything not directly proven from the current source, executable behavior, or API contract.
6. **Migration impact** — how existing v71 data or behavior is preserved or transformed.
7. **Verification method** — how the proposed result will be proven.

The following are not sufficient evidence by themselves:

- prior agent reports;
- previous audit conclusions;
- manifests or registry entries;
- ADR prose;
- YAML declarations;
- completion booleans;
- narrative PASS claims.

Where a claim cannot yet be established from current evidence, it must be explicitly marked **UNVERIFIED** rather than inferred or promoted to a confirmed fact.

This requirement exists deliberately because the v71 forensic review found repeated cases where reference integrity was mistaken for target integrity and where multiple artifacts represented the same authority differently. The target review must not reproduce that pattern.

---

# 14. TARGET DECISIONS VS. ARCHITECTURAL FREEDOM

The following are fixed product outcomes and should not be reopened as generic architecture questions unless the architect identifies a concrete contradiction in the current product requirements:

- the application remains a reseller/customer account ledger with Earthlink/ISP operational integration;
- subscriber history must be preserved;
- ledger corrections are represented by new activity rather than deleting the historical record;
- current subscriber position is derived/materialized from the preserved ledger/baseline;
- ISP balance is separate from subscriber debt;
- offline ledger recording is required;
- cloud failure must not roll back local business history;
- Firebase exists primarily to preserve and restore the user's account book and required operational credentials across reinstall/device replacement;
- general settings synchronization is not a V1 requirement;
- ISP operational credentials required to run the application on a new device remain a V1 requirement;
- ISP-side subscriber deletion does not delete local history;
- remote business deletion of local account/ledger history is not a required V1 operation;
- uTower import is a source snapshot import, not a reconstruction of unreliable multi-year history;
- Restore must support Replace and Merge;
- Merge must not duplicate the same transaction;
- Demo Mode is out of scope after dependency verification;
- production readiness prioritizes prevention of silent loss, deletion, duplication, or corruption of account/ledger history.

The architect retains freedom over implementation and structure, provided the final architecture satisfies these target outcomes and does not introduce unnecessary complexity.

---

# 15. GOVERNANCE SIMPLIFICATION DOES NOT MEAN EVIDENCE ELIMINATION

The current v71 governance/certification system is itself part of the forensic findings and contains confirmed inconsistencies. The target product does **not** require retaining the current large governance structure merely because it exists.

However, simplification must not mean deleting evidence or removing all release controls without replacement.

The intended direction is:

```text
Historical governance artifacts
        ↓
retain as evidence where needed
        ↓
extract the small set of production-critical invariants
        ↓
create one current machine-verifiable contract
        ↓
verify actual source / actual tests / actual build / actual result
```

The architect should therefore identify:

- which existing governance artifacts remain useful as historical evidence;
- which are obsolete and can be retired;
- which requirements need to migrate into the smaller production verification contract;
- which checks must be machine-enforced;
- which checks require behavioral/adversarial proof.

The objective is **governance reduction with preserved evidence integrity**, not governance deletion.

---

# 16. LOANIQD — FINAL PRODUCT DECISION

`loanIqd` is **not an independent V1 runtime financial authority**.

It is retained for **legacy/uTower compatibility and historical-data preservation**.

It must not be used as a separate debt, synchronization, or recovery state.
It must not be silently deleted, reset, or redefined during implementation or migration unless new direct business evidence explicitly requires such a change.

This decision follows the product's snapshot/opening-baseline model: uTower data is preserved as historical/import compatibility information, while the V1 financial authority remains the accepted baseline + ledger model.

This decision does not require a new V1 loan state machine.

---

# 17. ARCHITECTURAL REVIEW OUTPUT MUST BE TARGET-TO-CURRENT

The required consultant deliverable is not another generic architecture essay.

The architect should produce a **Target-to-Current Architecture Reconciliation** with at least:

### A. Target Product Contract

Confirm the fixed target decisions in this document.

### B. Current-v71 Evidence Map

For each major subsystem:

```text
Current mechanism
Target requirement
Evidence
Gap
Risk
```

### C. Simplification Matrix

```text
KEEP
SIMPLIFY
DESCOPE
REMOVE
REDESIGN
```

### D. Minimal Target Architecture

Show the smallest technically sound architecture that satisfies the target contract.

### E. Migration Strategy

Explain how current v71 data/state is moved to the target without losing history, current account position, uTower baselines, or required credentials.

### F. Verification Contract

Define the smallest set of behavioral/adversarial tests that proves the target invariants.

### G. Implementation Route

Only after the above:

```text
architecture freeze
    ↓
migration design
    ↓
implementation
    ↓
verification
    ↓
independent adversarial audit
```

---

# 18. FINAL REVIEW PRINCIPLE

The central review principle remains:

> **Every current v71 mechanism must justify its existence against the Target Product Contract.**

The architect must not preserve a mechanism merely because:

- it already exists;
- an earlier ADR proposed it;
- a prior agent reported it as fixed;
- a test references it;
- a manifest lists it;
- or removing it feels architecturally uncomfortable.

Conversely, the architect must not remove a mechanism merely because it looks complex. Its removal must be justified against the target business behavior, source evidence, migration risk, and verification requirements.

The desired outcome is therefore not “simplify everything.” It is:

> **Simplify everything that is not required, while preserving every behavior required for the target product's data integrity and practical operation.**

---

# 19. RELATIONSHIP TO THE SIMPLIFICATION MATRIX

The companion document `V71_TO_TARGET_SIMPLIFICATION_MATRIX.md` is a **working engineering bridge**, not a second product contract.

The authority order is:

```text
Target Product Contract
        ↓
fixed product requirements

V71 Forensic Findings
        ↓
current-state evidence

Simplification Matrix
        ↓
preliminary routing / questions for the architect

Architect Reconciliation
        ↓
technical decision and migration route
```

The matrix must not silently override this contract. If the architect rejects a preliminary `KEEP / SIMPLIFY / DESCOPE / REMOVE / REDESIGN` direction, the rejection must cite the concrete target requirement, source evidence, counterexample, migration constraint, or API constraint that requires the more complex design.

---

# 20. FINAL ARCHITECT QUESTION

> **Given the fixed Target Product Contract above, can the v71 codebase be reduced to a smaller, clearer, and more enforceable architecture that preserves account/ledger history, supports required Earthlink operations, supports offline operation and Firebase recovery, and eliminates the current delete, synchronization, recovery, concurrency, cursor, and governance loops without introducing another layer of proxy authority?**

If yes, the architect must define:

- the minimal target model;
- the components to keep;
- the components to simplify;
- the components to descope/remove;
- the migration path;
- and the verification contract.

If no, the architect must identify the **specific target requirement** that prevents simplification and prove why that requirement needs the additional complexity.

---

# v0.6 Revision Review Record

## Draft v0 — Four requested product clarifications
The draft applied only the four agreed product-level changes:
1. fixed Restore Merge baseline lineage selection;
2. finalized `loanIqd` as preserved legacy/uTower compatibility data, not an independent V1 financial authority;
3. explicitly limited ledger-producing ISP operations to Activation, Renewal/Extension, and Refill;
4. clarified the separation between external ISP outcome, local reseller accounting durability, and Firebase cloud synchronization.

## Pass 1 — Product-decision consistency
Verified that the four changes do not reopen G1–G8 architecture decisions and do not introduce implementation mechanisms into the product contract.

**PASS**

## Pass 2 — Requirement-loss audit
Checked that the revision retains:
- historical financial immutability;
- baseline + ledger current-position semantics;
- offline/local-first behavior;
- Restore Replace and Merge;
- ISP-side deletion preserving local history;
- operational credential recovery;
- uTower snapshot semantics;
- no autonomous external reconciliation engine;
- general settings sync out of V1.

**PASS**

## Pass 3 — Architecture-boundary audit
Confirmed that the contract does not add:
- Direct Atomic Room implementation details;
- G4 generation implementation;
- DataOperationCoordinator/singleFlight details;
- source-row hashing algorithm;
- G8 verifier implementation.

Those remain engineering architecture/implementation concerns in G1–G8.

**PASS**

## Pass 4 — Ambiguity audit
Checked the four modified areas for competing interpretations:
- baseline conflict now means complete lineage selection;
- `loanIqd` is preserved but not an independent authority;
- exactly three ISP operations create financial ledger entries;
- external outcome, local accounting durability, and Firebase copy are explicitly separate.

**PASS**

## Pass 5 — Final contract review
Confirmed no new product contradiction, no unnecessary technical constraint, and no loss of required business behavior was introduced by v0.6.

**FINAL RESULT: PASS**


**Document status:** Target Product Contract — Version 0.6 — Final Product Contract Clarification Revision
**Date:** 2026-08-17
