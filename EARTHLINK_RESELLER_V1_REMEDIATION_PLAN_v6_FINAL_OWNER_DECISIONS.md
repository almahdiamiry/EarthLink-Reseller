# EARTHLINK RESELLER V1

# REMEDIATION PLAN v6 — FINAL, RECONCILED, OWNER-DECISIONS LOCKED, INTERNALLY REVIEWED

Baseline: HEAD `e404f75`.

Final owner decisions are now locked into the plan: rollback is only for an unaccepted import,
uTower Replace establishes the new canonical starting dataset and must reconcile cloud state
accordingly, corrections are calculated by difference, unknown transaction types remain
financially non-authoritative but auditable, and G1 ambiguous-success handling has one bounded
STOP GATE only when the existing API/codebase cannot prove that the observed server state belongs
to the original activation operation.

This plan supersedes v4. It incorporates the full finding set from the prior deep reviews,
the deletion-authority forensic sweep, and the final review of v4 itself.

The governing rule for this remediation is:

> A capability is not "done" because code or a test exists. A behavioral boundary is
> closed only when the relevant production path is exercised and the required invariant
> is proven at that boundary.

---

# 0. FINAL SCOPE / NON-GOALS

This round closes confirmed implementation defects and the specific verification gaps that
can conceal regressions in the affected boundaries.

Do NOT reopen G8 behavioral decisions already settled elsewhere.

The following remain behaviorally out of scope except where explicitly referenced below:

```text
- Remote business LedgerDelete remains history-preserving/no-op.
- Account disappearance remains history-preserving/history-only.
- FK cascade protection remains unchanged.
- loanIqd semantics remain unchanged.
- credential-sync TOCTOU fix remains unchanged.
- RemoteEntityValidator missing-field fallback semantics remain unchanged.
```

Important distinction:

`applyAccountDelete`'s **history-preserving deletion behavior** remains settled. Its
remote-version metadata participation is NOT a reopening of deletion semantics; the
systemic metadata-monotonicity issue is handled separately in Workstream 10.5.

---

# 1. AUTHORITY

```text
docs/authority/Target Product Contract v0.6.md
docs/authority/G1-G8 Consolidated Architecture Summary.md
docs/authority/Final Independent Adjudication Memo.md
contract/phase_requirements.yaml
contract/invariant_contract.yaml
contract/forbidden_patterns.yaml
```

Relevant contract rules for this round:

```text
§3.2
A normal correction must not erase the original financial event.
Correct historical financial position through additional ledger activity,
not by deleting the historical activity.

§3.11
Firebase exists primarily so the user's account book survives app deletion,
device replacement, and normal recovery. The target business dataset includes
accounts/subscribers plus ledger/transaction history and required history/notes.

§3.14
Remote business deletion of the local ledger/account history is not a required
V1 operation.
```

G8 behavioral decisions remain sealed. Any new issue below is addressed only at its
actual boundary; do not reopen unrelated settled work.

---

# 2. EXECUTION / EVIDENCE DISCIPLINE

## 2.1 Atomic remediation discipline

Do NOT batch the whole round.

Use:

```text
WS8.5 inventory  -> artifact/commit containing inventory
WS9A             -> one independently verifiable commit + proof
WS9B             -> one independently verifiable commit + proof
WS9C             -> one independently verifiable commit + proof
WS9D             -> one independently verifiable commit + proof if implementation changes
WS10             -> one independently verifiable commit + proof
WS10.5           -> one independently verifiable commit + proof
WS11             -> one independently verifiable commit + proof
WS12             -> one independently verifiable commit + proof
WS13             -> one independently verifiable commit + proof
WS14             -> one independently verifiable commit + proof
WS15             -> one independently verifiable commit + proof
```

"One independently verifiable sub-workstream per commit" is intentional. WS9A/9B/9C are
different deletion authorities and must not be bundled into one opaque remediation commit.

## 2.2 RED -> GREEN behavioral proof

For every newly added certification/regression test:

```text
[ ] Identify the pre-fix behavior it is intended to catch.
[ ] The test must be capable of failing against that pre-fix behavior.
[ ] After implementation, the same behavioral assertion passes.
[ ] No source-string presence test substitutes for the runtime behavior.
```

For concurrency/restart/metadata tasks, "the code compiles" is not evidence of closure.

## 2.3 Execution liveness / bounded investigation

There is exactly ONE owner STOP GATE in this plan: Workstream 13.4 (ambiguous-success proof
for an ISP activation when the API response was lost). All other decisions are owner-locked
below and are implementation requirements, not questions to ask again.

For any ambiguous implementation problem that is not that single STOP GATE:

```text
[ ] Re-check the current source, authority/contract, and existing tests once as a bounded pass.
[ ] If the evidence is sufficient, implement the smallest contract-consistent solution.
[ ] If the issue is a pre-existing/unrelated infrastructure failure, record the exact failure
    and do not rewrite unrelated code merely to make the current task pass.
[ ] Do not repeatedly retry the same failing approach without new evidence.
[ ] Do not invent a product rule, API behavior, identity field, or cloud semantic.
```

The agent must not enter an open-ended investigation loop. If the single STOP GATE is reached,
follow Workstream 13.4 exactly: stop once, report the evidence gap, and wait for the missing owner
decision. Do not keep searching or retrying indefinitely.

---

## 2.4 Owner-decided product semantics locked before implementation

The following decisions are final for this remediation round. The agent must implement them and
must NOT ask the owner to choose among alternatives already settled here:

```text
ROLLBACK
- rollbackImportBatch is only for cancelling an import that has not yet been accepted as canonical business history.
- rollback sends no remote delete/tombstone for those temporary records.
- accepted financial history is not removable through rollback.

UTOWER REPLACE
- shouldReplace=true establishes the new canonical starting dataset from uTower.
- the cloud copy used for normal multi-device recovery must be reconciled to that new dataset.
- obsolete pre-replacement data must not silently return on the next sync.
- local application backup/restore is a separate long-term recovery mechanism and is not given the same meaning as the uTower establishment import. Merge remains distinct from Replace: Merge is the supported way to add/merge uTower data needed to fill missing history without declaring the entire uTower dataset the new starting baseline.

CORRECTION
- corrections are made by difference: the user specifies the amount the original transaction should have been, and the system posts the economic difference as a new ledger event.
- multiple independent corrections against the same original are allowed; correction chains are not.

UNKNOWN TYPE
- a genuinely unrecognized transaction type does not change the calculated financial balance, but it is observable through AuditLog.

G1 DUPLICATE RESULT
- if a second caller discovers the operation already completed, return the existing idempotent successful outcome instead of reporting a false failure.
```

These are product/behavior decisions, not implementation suggestions. Do not replace them with a
new interpretation merely because the current code uses different semantics; change the code to
match the locked decision and prove the behavior with tests.

---

# 3. WORKSTREAM 8.5 — PRODUCTION MUTATION / DELETION / RECOVERY BOUNDARY INVENTORY

## Objective

Before modifying any affected boundary, enumerate the actual production graph from HEAD
`e404f75`. Do not trust call-site counts copied from prior reports or plans.

## Task 8.5.1 — Build and record the inventory

Enumerate and record exact file + function + role for:

### A. Financial reconstruction / unknown-type callers

```text
- every production caller of deriveAccountBalance
- every production caller of reconstructCurrentPosition
- every production caller of any wrapper that feeds these functions
```

### B. G1 resolution entry points

```text
- every production entry point that can reach verifyAndResolvePendingOperation
  (UI, startup, worker, sweep, or any other path)
```

### C. Delete/tombstone emitters

Enumerate every caller of:

```text
OutboxManager.deleteWithTombstone()
OutboxManager.deleteWithTombstoneBatch()
```

Classify each caller by operation semantics.

### D. Physical deletion authorities

Enumerate every caller of every destructive DAO method, including at minimum:

```text
LocalLedgerEntryDao:
    deleteById
    deleteByIds
    deleteByBatchId
    deleteByAccountId
    deleteAll

LocalAccountDao:
    deleteById
    deleteByIds
    deleteAll
```

and any additional delete primitive discovered during inventory.

For each caller classify:

```text
NORMAL BUSINESS CORRECTION
RESTORE / RECONSTRUCTION
DATASET REPLACEMENT
DEVELOPER RESET
OTHER / UNCLASSIFIED (must be resolved before implementation)
```

### E. Whole-dataset / replacement / restore paths

Enumerate every path that can:

```text
- replace the local dataset
- rollback an import
- restore backup data
- rewrite ledger history
- clear the whole local dataset
- sign out while clearing local data
```

### F. Remote-apply entry points

Enumerate:

```text
- RemoteSyncCoordinator.processEvent callers
- realtime listener path
- one-shot pull path
- bootstrap/pull loop
- any other remote-apply boundary
```

### G. Metadata writers

Enumerate every production caller of:

```text
SyncMetadataDao.put(...)
any wrapper around metadata writes
```

Then specifically enumerate every write touching:

```text
remote_version:*
tombstone:*
```

The inventory must not rely only on searching for literal key strings; include dynamic-key
construction and helper/wrapper paths.

### H. Release BuildConfig references

Enumerate every release-build-reachable raw:

```text
com.alamiry.earthlinkreseller.BuildConfig.*
```

reference, excluding `AppBuildConfig`.

## Definition of Done

```text
[ ] A persistent inventory artifact exists with actual counts and exact locations.
[ ] Every deletion/replacement path has a Section-2 classification.
[ ] Every Workstream 9-15 uses the inventory, not assumed counts.
[ ] The inventory explicitly records the six current remote_version:* write sites
    if the source still contains six after re-verification.
[ ] No unclassified destructive path remains before WS9 changes begin.
```

---

# 4. WORKSTREAM 9 — FINANCIAL HISTORY DELETION AUTHORITY CLOSURE (P0)

WS9 is deliberately split into independently verifiable sub-workstreams.

---

## 9A — `deleteTransaction()` becomes correction/reversal

### Objective

This is a normal business correction path. Physical deletion of the original financial
event is forbidden by §3.2.

### Task 9A.1 — Correction-by-difference semantics (owner decision locked)

Replace the delete action with an additive correction entry. The user provides the amount that
the original transaction SHOULD have been, and the system creates a new correction entry for the
economic difference between the recorded amount and the intended amount. The original row remains
unchanged.

Examples:

```text
original: took 100,000
intended: took 70,000
correction: gave 30,000
final economic effect: 70,000

original: gave 100,000
intended: gave 70,000
correction: took 30,000
final economic effect: gave 70,000

full reversal is the special case where intended amount = 0.
```

The correction amount is calculated from the difference; the user does not manually enter an
inverse transaction. Use the existing canonical `took`/`gave`/`renewal` model. Do not implement
money correction by negative signed amounts unless an authority explicitly requires that model.

### Task 9A.2 — Canonical correction identity and idempotency

The correction entry must have one stable business/ledger identity.

A retry of the same correction intent must reuse the same correction entry ID /
businessTransactionId.

Required property:

```text
same correction intent
    -> same ledger identity
    -> retry cannot create a second correction row
```

Do not generate a fresh correction UUID on each retry and depend on later cleanup to dedupe.

### Task 9A.3 — Lineage field

Add:

```text
correctsEntryId: String? = null
```

No existing LocalLedgerEntry field may be repurposed.

Audit and update the entire pipeline:

```text
[ ] LocalLedgerEntry entity
[ ] schema export
[ ] DB migration: VERSION 14 -> 15, MIGRATION_14_15
[ ] plain nullable ADD COLUMN migration
[ ] MIGRATION_14_15 is executed against a representative real-device/production-derived
    database copy (for example, a valid pre-15 backup from a real device, with sensitive
    data handled appropriately), not only a fresh empty test database
[ ] generated Moshi adapter serialize/deserialize behavior verified
[ ] outbox payload includes the field
[ ] Firestore document round-trip preserves the field
[ ] RemoteEntityValidator reads it and preserves existing local value when absent
[ ] RemoteSyncCoordinator standard upsert path preserves it
[ ] Backup export
[ ] Backup import
[ ] Restore Merge
[ ] Restore Replace
[ ] uTower import leaves legacy imported rows null unless an authoritative lineage exists

Representative live-data migration fixture:

```text
[ ] Use the attached Version-14 backup fixture `./earthlink_backup.zip` in the repository working tree as a representative
    migration input; do not use only a fresh empty Room database.
[ ] Work only on a disposable copy; never mutate the original fixture.
[ ] Restore/open/decrypt through the application's normal backup/database path or the same
    authenticated test harness; do not treat the encrypted ZIP as plaintext SQLite.
[ ] Run MIGRATION_14_15 on the restored Version-14 database copy.
[ ] Verify representative pre-existing accounts, ledger rows, import history, outbox state,
    and other preserved business data remain intact after migration.
[ ] Verify `correctsEntryId` exists as nullable and legacy rows remain null.
[ ] Verify the original fixture is never committed to the public repository, packaged into the APK, or copied into production resources. Add it to `.gitignore` if it is not already excluded.
```

Do not invent handwritten Moshi adapter work if the generated adapter already handles the
new field correctly; verify behavior instead.

### Task 9A.4 — Anti-chain

A correction must ultimately reference an original business transaction.

Allowed:

```text
T1 original
T2 correction -> T1
T3 another independent correction -> T1
```

Forbidden:

```text
T1 original
T2 correction -> T1
T3 correction -> T2
```

If the selected target is already a correction, resolve/redirect to its original target.
Owner decision: multiple independent corrections against the same original are allowed, but
each new correction must calculate a deliberate economic difference; correction chains are forbidden.

### Task 9A.5 — Outbox boundary

```text
[ ] correction is a normal LedgerUpsert
[ ] original entry receives no delete tombstone
[ ] remote application of the correction never modifies/deletes the referenced original
```

### Task 9A.6 — Regression tests

At minimum:

```text
[ ] full correction
[ ] partial correction
[ ] retry of same correction intent -> no duplicate row
[ ] correction of correction -> re-anchors to original / rejects
[ ] local + simulated remote convergence
[ ] backup export/import preserves correctsEntryId
```

---

## 9B — `rollbackImportBatch()` semantics (owner decision locked)

### Objective

Rollback is ONLY for cancelling an import that has not yet been accepted as part of the
canonical business dataset. It is not a later financial-history deletion feature.

Plain-language rule:

```text
Import not yet accepted
    -> rollback may remove the temporary import result locally
    -> no remote delete/tombstone is sent for those temporary records

Imported data already accepted as business history
    -> rollback is NOT the mechanism for removing that history
    -> use the normal correction/history-preserving rules instead
```

The current source contains an import-batch rollback path/control; do not assume it is absent
from the UI. Verify its actual reachability from the current HEAD. If it is user-reachable for
completed/accepted imports, enforce the owner-decided rule at the appropriate production boundary
and adjust the UI only as needed; do not invent a different product behavior.

### Task 9B.1 — Implement the locked semantic

```text
[ ] Rollback applies only to an unaccepted import.
[ ] Rollback removes only the temporary local effects of that import.
[ ] Rollback creates no Firebase delete/tombstone for those temporary records.
[ ] Rollback does not erase previously accepted financial history.
[ ] If the current UI exposes rollback after acceptance, block the operation or adjust the
    UI so the user cannot invoke a semantic that violates this rule.
```

### Task 9B.2 — Test

```text
A. Unaccepted import:
   import temporary batch -> rollback before acceptance
   -> local temporary records removed
   -> no remote delete/tombstone emitted

B. Accepted history:
   accepted historical ledger rows are not removable through rollback.

C. Existing current-HEAD UI reachability is exercised so the test proves the actual
   production path, not only the repository method.
```

### Definition of Done

```text
[ ] Rollback semantics are owner-locked and implemented exactly as above.
[ ] No remote delete/tombstone is emitted for an unaccepted import rollback.
[ ] Accepted financial history cannot be removed through rollback.
[ ] The actual current-HEAD UI/entry path is covered if reachable.
```

---

## 9C — `UtowerImporter.shouldReplace = true` dataset-replacement semantics

### Objective

This is an explicit dataset replacement/restore authority, not a normal correction.

Current source evidence shows that `shouldReplace=true` clears local accounts, local ledger rows,
and the outbox without tombstoning those wiped business rows. This creates a credible
resurrection risk during later remote pull.

### Task 9C.1 — Reproduction first

Before fixing:

```text
Device A:
    existing synced accounts + ledger
    run Replace import
    complete import
    run normal sync/pull

Assert whether pre-existing wiped data reappears.
```

The result determines whether the suspected resurrection is confirmed in the current build.

### Task 9C.2 — Owner-locked replacement semantics

For uTower import, `shouldReplace=true` means: **the new uTower dataset is the new canonical
starting dataset for the application**, including the cloud copy used for normal multi-device
recovery. This is a one-time/rare establishment operation, not the normal recurring backup/restore
mechanism. Local application backups remain a separate long-term recovery mechanism.

Therefore implementation must produce one coherent result:

```text
uTower Replace
    -> new dataset becomes the intended canonical starting state
    -> local state reflects that dataset
    -> cloud state is reconciled to the same intended dataset
    -> obsolete pre-existing cloud/local business records do not silently reappear later
    -> pending obligations that contradict the replacement are discarded/reconstructed
       according to the replacement transition, not blindly replayed
    -> remote cursors/version state are reset/reconciled so the next sync cannot resurrect
       the old pre-replacement dataset
```

Do NOT implement this as "clear local tables and hope the next sync converges". The old dataset
must not return after replacement. The exact safe cloud-reconciliation mechanism is an implementation
decision: use the existing contract-compatible deletion/reconciliation primitives where appropriate;
do not invent a new product mode.

The Replace UI must clearly communicate that this establishes a new starting dataset and can
overwrite the current dataset. Keep/strengthen an explicit warning/confirmation; do not silently
reinterpret Replace as ordinary merge. Keep Merge as the separate non-replacement import path
for adding/merging missing uTower data/history.

### Task 9C.3 — Regression

The reproduction test becomes the regression test.

After the fix:

```text
[ ] wiped pre-existing data does not unexpectedly reappear after a normal sync/pull
[ ] local state and the intended cloud canonical state match the replacement dataset
[ ] obsolete pre-replacement outbox/cursor/version obligations cannot resurrect the old dataset
[ ] Replace remains explicitly distinct from recurring local-backup restore
```

### Definition of Done — WS9

```text
[ ] 9A closed with correction/idempotency/lineage behavioral proof.
[ ] 9B semantics explicitly decided and cross-device behavior proven.
[ ] 9C resurrection risk explicitly reproduced and either confirmed+fixed
    or proven absent.
[ ] No normal-correction path physically deletes ledger history.
[ ] applyLedgerDelete behavior remains unchanged.
```

---

## 9D — Correction lineage / full-pipeline verification

If 9A adds `correctsEntryId`, verify one canonical artifact through:

```text
Room -> outbox -> Firestore -> pull -> validator -> Room
Room -> backup export -> backup import
Room -> restore merge
Room -> restore replace
```

and verify no path drops or rewrites the lineage.

---

# 5. WORKSTREAM 10 — G1 LOCK UNIFICATION (P0)

## Objective

One repository-owned account lock protects both background and UI resolution entry points.

### Task 10.1

Implement one repository entry point:

```text
resolvePendingOperationSerialized(...)
```

Behavior:

```text
1. load pending operation by businessTransactionId without lock only to discover accountId
2. acquire repository-owned per-account lock
3. re-read the pending operation under the lock
4. if already resolved -> return current result without a second ISP verification
5. otherwise verify/resolve
```

### Task 10.2

Both:

```text
EarthlinkSearchViewModel.resolvePendingOperation
sweepAndResolvePendingOperations
```

must call through this single serialized boundary.

Delete both old independent lock maps.

### Task 10.3 — Concurrency tests

Owner decision: when a second caller discovers that the same pending operation already completed,
it returns the existing successful result/idempotent outcome rather than presenting a false error.


```text
A. same account + same operation + concurrent UI/sweep
   -> only one verification; second observes in-progress/resolved state

B. same account + two different pending operations + concurrent
   -> explicitly confirm whether per-account serialization is intended
      and test that decision
```

### Definition of Done

```text
[ ] exactly one lock owner/map
[ ] reread-under-lock
[ ] both real entry points use it
[ ] both concurrency cases are behavioral tests
[ ] the same-account/different-operation semantic is recorded
```

---

# 6. WORKSTREAM 10.5 — GLOBAL `remote_version:*` MONOTONICITY (P0)

## Objective

Every production writer of `remote_version:*` must obey one monotonic invariant:

```text
storedVersion_after = max(storedVersion_before, incomingVersion)
```

This is systemic. Do not patch only the account-delete child-ledger case.

### Task 10.5.1 — Central atomic DAO boundary

Introduce one metadata-layer primitive, for example:

```text
putMonotonicRemoteVersion(key, newVersion)
```

It must enforce monotonicity **atomically at the database/DAO boundary**.

A Kotlin read -> `max()` -> write sequence alone is forbidden because two concurrent writers can
still produce a downgrade.

The database/DAO operation must ensure concurrent:

```text
200
160
```

cannot leave the stored value at 160.

### Task 10.5.2 — All writer sites

Use the same monotonic boundary for every current production writer discovered by WS8.5.

The previously identified source has six `remote_version:*` write sites; re-count them from HEAD
before implementation and replace every applicable direct writer.

After implementation:

```text
[ ] no production remote_version:* writer bypasses the monotonic helper
[ ] future code has one authoritative write boundary
```

### Task 10.5.3 — Behavioral tests

At minimum:

```text
1. 200 accepted
2. 150 arrives
   -> stored version remains 200
3. 160 arrives
   -> real remote-apply/conflict boundary rejects it as stale
4. incomingVersion == storedVersion
   -> idempotent no-op: stored value remains unchanged and the event is not
      treated as a version downgrade or an erroneous rejection solely because
      the versions are equal
```

Also test concurrent writers if the implementation exposes meaningful concurrency at the DAO boundary.

### Definition of Done

```text
[ ] monotonicity enforced atomically
[ ] all current remote_version writers use the shared boundary
[ ] stale-event rejection is proven at the real application boundary
[ ] no direct bypass remains
```

---

# 7. WORKSTREAM 11 — UNKNOWN TRANSACTION TYPE OBSERVABILITY (P1)

## Task 11.0

Use WS8.5 inventory to determine the actual production call-site count.

## Task 11.1

Thread `onUnrecognizedType` through `deriveAccountBalance` to the canonical reconstruction path.

## Task 11.2 — Canonical type registry

Use the project's canonical transaction-type normalization/registry as the source of truth.

Do not create a second hardcoded "recognized type" list in this remediation.

## Task 11.3 — Three explicit input states

Verify and document separate semantics for:

```text
A. null/blank typeRaw
B. recognized-neutral type
C. nonblank genuinely-unrecognized/malformed type
```

Only state C is the unknown/malformed transaction audit case for this remediation. Null/blank
values retain their current explicitly tested handling. The owner decision is locked: a genuinely
unrecognized type is financially non-authoritative (it must not change the calculated account
balance) but must be observable through AuditLog.

## Task 11.4 — Audit sink

At every confirmed production caller, write:

```text
entityId
raw typeRaw
accountId
```

to the correct operational AuditLog store.

For backup reconstruction callers, verify the audit record does not accidentally mutate the
backup artifact or inject runtime audit rows into a restored business dataset.

## Task 11.5 — Behavioral test

Drive each confirmed production caller with a genuinely unrecognized nonblank type and assert:

```text
[ ] audit row exists
[ ] correct entity/type/account fields
[ ] financial reconstruction remains neutral/non-authoritative
```

and explicitly test A/B/C handling.

---

# 8. WORKSTREAM 12 — GOVERNANCE / ISSUE_LOG ACCURACY (P1)

## Task 12.1 — Open defects vs verification gaps

`ISSUE_LOG.md` must not claim zero defects while this plan is open.

Use two categories:

```text
OPEN IMPLEMENTATION DEFECTS
    WS9A
    WS9B
    WS9C
    WS10
    WS10.5
    WS11

OPEN VERIFICATION GAPS
    WS13
    WS14
    WS15
```

Reclassify any item if implementation results prove the original assumption wrong.

## Task 12.2 — Stale audit wording

Update:

```text
"Hard deleted subscriber from local DB"
```

to wording that matches the current history-only transition.

Audit every log call touched by this remediation for equivalent stale semantics.

## Task 12.3 — Deferred registry

Record the following as explicitly out of scope for this round:

```text
D1  raw destructive DAO primitives remain broad/reusable
    (future architecture: confine them to explicit maintenance/restore paths)

D2  misleading deleteAllLedgerEntries() API
    (rename/quarantine/remove only after caller inventory)

D3  broad clearAllData()/AppDatabase.clearAllData() architecture
    (future narrowing pass)

D4  forced sign-out with force=true + clearData=true
    (future audit of every production caller and UX/data-loss warning)

D5  REMOTE_APPLY / coordinator naming taxonomy cleanup

D6  broader Backup/Restore architecture refactor beyond WS9 boundaries
```

Do not implement D1-D6 in this round.

### Definition of Done

```text
[ ] issue log accurately distinguishes defects from verification gaps
[ ] stale audit wording corrected
[ ] deferred registry persisted
[ ] no "zero defects / 100% passing" claim remains while open rows exist
```

---

# 9. WORKSTREAM 13 — G1 REAL RESTART CERTIFICATION (P1)

## Objective

Prove the persistence boundary, not merely repeated calls against one in-memory DB instance.

### Task 13.1

Use:

```text
file-backed Room database
-> create PENDING operation
-> close old DB completely
-> construct fresh DB/repository state against same file
-> invoke the same recovery boundary production uses
```

Prefer `SyncWorker.doWork()` itself. If direct worker construction is impractical because
the worker is hard-wired to the Application object, extract a narrow production recovery operation
used by `SyncWorker.doWork()`; do not use reflection to fake an unrelated dependency graph.

The test must prove:

```text
[ ] old DB instance is closed and unusable
[ ] no in-memory pending state is reused
[ ] fresh persistence-backed state is loaded
```

### Task 13.2 — Startup wiring

Add a separate light test proving `EarthlinkApp.onCreate` fires/schedules the same recovery
trigger. Do not re-run full Firebase/WorkManager integration in the core durability test.

### Task 13.3 — Assertions

The real recovery/verification logic must distinguish these cases:

```text
A. request definitely failed
B. request definitely succeeded
C. request result is unknown because the response was lost
```

For case C, use only evidence that already exists in the API contract/current source to prove
that the later observed server state belongs to the original activation operation (for example,
a stable operation/business identity, server-side history, or an equivalent authoritative marker).
Do not treat "the subscriber is active now" by itself as proof that this particular activation
request succeeded.

### Task 13.4 — ONLY STOP GATE IN THIS PLAN: ambiguous API success proof

Before implementing any new correlation/identity mechanism, inspect the current API contract,
response/payload models, server-side status/history fields exposed to the app, and existing tests.

```text
IF the existing system already provides authoritative evidence tying the later state to the
original activation operation:
    -> implement the reconciliation using that existing identity/evidence.

IF the existing system does NOT provide enough evidence to prove that relationship:
    -> STOP ONCE.
    -> Report exactly what evidence exists, what is missing, and the smallest concrete
       implementation decision that would be required.
    -> Do not guess, invent a correlation rule, or keep searching indefinitely.
    -> Do not modify unrelated code to avoid the STOP GATE.
    -> Resume only after the owner provides the missing authority/decision.
```

This is the only owner STOP GATE in the plan. All other decisions in this document are already
owner-locked and are not grounds for stopping for clarification.


```text
[ ] exactly one ledger entry
[ ] original businessTransactionId preserved
[ ] no duplicate/orphaned outbox row
[ ] pending operation = COMPLETED
[ ] account balance correct
[ ] second sweep is idempotent
```

### Task 13.5 — Definition of Done

```text
[ ] exactly one ledger entry
[ ] original businessTransactionId preserved
[ ] no duplicate/orphaned outbox row
[ ] pending operation = COMPLETED
[ ] account balance correct
[ ] second sweep is idempotent
[ ] any ambiguous-success reconciliation either uses existing authoritative operation identity
    or has passed the single STOP GATE with an owner-approved decision
```

No test is named "process kill/restart" unless it crosses the persistence/application boundary
it claims to test.

---

# 10. WORKSTREAM 14 — BUILDCONFIG CONSISTENCY (P2)

This is a precautionary consistency fix, not a confirmed R8 crash.

### Task 14.1

Replace raw:

```text
com.alamiry.earthlinkreseller.BuildConfig.DEBUG
```

references in `SettingsScreen.kt` with:

```text
AppBuildConfig.DEBUG
```

### Task 14.2

If CI budget allows, add a real release artifact smoke check.

Otherwise, explicitly document that the existing source-text certification test proves source
presence/shape, not real R8 artifact execution.

### Definition of Done

```text
[ ] no raw SettingsScreen BuildConfig.DEBUG remains
[ ] issue log labels this as verification/consistency, not confirmed defect
```

---

# 11. WORKSTREAM 15 — COORDINATOR / TRANSPORT CONCURRENCY PROOF (P2)

## Objective

Prove final financial coherence across the local-mutation vs remote-apply boundary.

### Task 15.1 — Real production entry points

Remote side:

```text
RemoteSyncCoordinator.processEvent(...)
```

Local side:

```text
real repository mutation (e.g. addPayment/addDebt)
```

Do not call private apply helpers directly and do not use raw DAO mutations.

### Task 15.2 — Two orderings

Run both:

```text
A. local mutation enters first
B. remote event application enters first
```

### Task 15.3 — Canonical expected result

Derive the expected financial result from the documented `SyncConflictResolver` semantics and
actual transaction rules.

Do not assume both orderings must have identical intermediate states. What must hold is the
documented conflict-resolution semantics and eventual financial invariant after both operations
settle.

### Definition of Done

```text
[ ] both orderings are genuinely concurrent
[ ] real production entry points are used
[ ] expected outcome is canonical and deterministic
[ ] final assertion checks settled Room/outbox/business state, not merely no exception
```

---

# 12. FORBIDDEN CHANGES

```text
[ ] Do not reopen G8 behavioral decisions.
[ ] Do not change applyLedgerDelete behavior.
[ ] Do not add local-only divergent-delete semantics.
[ ] Do not treat every physical DAO delete as an automatic bug; classify first.
[ ] Do not treat a local wipe alone as a completed uTower Replace; the implementation must reconcile the cloud dataset to the owner-locked new canonical starting state.
[ ] Do not implement WS10.5 as read-max-write in application code; monotonicity must be
    atomic at the DAO/database boundary.
[ ] Do not leave any direct remote_version:* writer bypassing the shared helper.
[ ] Do not create a second hardcoded transaction-type registry.
[ ] Do not fake process-restart tests with the same in-memory DB/Application state.
[ ] Do not batch unrelated WS9A/9B/9C behavioral changes into one opaque commit.
[ ] Do not write certification tests that prove only source presence or uncontested execution.
[ ] Do not implement D1-D6 in this round.
[ ] Do not mark a verification gap as a confirmed defect, or a confirmed defect as a gap.
```

---

# 13. STOP-GATE SUMMARY

There is exactly one STOP GATE in this plan:

```text
WS13.4 — ambiguous-success proof after a lost ISP activation response
```

No STOP GATE exists for WS9B or WS9C: their product semantics are already owner-decided in
Section 2.4 and Workstream 9. No other task may pause for clarification unless the agent discovers
that an authority source directly contradicts an owner-locked decision; in that exceptional case,
it must report the contradiction rather than silently choose a new product rule.

---

# 14. PRIORITY SUMMARY

```text
P0:
    WS9A  normal correction semantics
    WS9B  rollbackImportBatch semantics/reconciliation
    WS9C  dataset replacement semantics + resurrection closure
    WS10  G1 lock unification
    WS10.5 global remote_version monotonicity

P1:
    WS11  unknown-type observability
    WS12  governance / ISSUE_LOG / stale audit wording
    WS13  real G1 restart certification

P2:
    WS14  BuildConfig consistency
    WS15  coordinator/transport concurrency proof

Deferred:
    D1-D6
```

---

# 15. RELEASE / CLOSURE GATE

This remediation round is not considered closed until:

```text
[ ] WS8.5 inventory is complete and persisted
[ ] WS9A/9B/9C independently close with behavioral proof
[ ] WS10 concurrency proof passes
[ ] WS10.5 monotonic metadata proof passes at real application boundary
[ ] WS11 production observability proof passes at every confirmed call site
[ ] WS12 ISSUE_LOG is accurate
[ ] WS13 restart proof crosses a real persistence boundary
[ ] WS14 consistency fix is complete
[ ] WS15 concurrency proof passes
[ ] all tests/certification added by the round are green
[ ] no forbidden change was introduced
[ ] deferred D1-D6 remain documented and unimplemented
```

---

# 16. FIVE-PASS INTERNAL REVIEW RECORD

## PASS 1 — Finding Coverage

```text
[✓] C1 corrected: deleteTransaction, not applyLedgerDelete
[✓] C2 dual G1 locks
[✓] C3 unknown transaction callback not wired
[✓] C4 BuildConfig consistency / R8 verification gap
[✓] C5 coordinator/transport concurrency verification gap
[✓] original AUDIT-001 deletion authority
[✓] original AUDIT-003 production G1 wiring
[✓] original AUDIT-007 destructive UI gating
[✓] rollbackImportBatch physical delete/tombstone path
[✓] Utower shouldReplace local wipe + resurrection risk
[✓] stale "Hard deleted subscriber" audit wording
[✓] global remote_version monotonicity across all current writers
[✓] correction idempotency
[✓] correction lineage and full pipeline
[✓] anti-chain / multiple-correction semantics
[✓] real process/restart certification
[✓] deferred destructive primitives / force-signout / taxonomy cleanup
```

## PASS 2 — Boundary / Call-Graph Coverage

```text
[✓] production call graph is enumerated first
[✓] all tombstone emitters are inventoried
[✓] all physical DAO delete callers are inventoried
[✓] all remote apply entry points are inventoried
[✓] all metadata writers are inventoried
[✓] remote_version writers are required to use one authoritative helper
[✓] no "deleteTransaction is the only emitter" assumption remains
```

## PASS 3 — Reliability / Concurrency / Idempotency Coverage

```text
[✓] G1 lock ownership unified
[✓] lock-then-reread required
[✓] same-operation and same-account/different-operation concurrency tested
[✓] correction retry idempotency required
[✓] remote_version write must be atomic
[✓] stale-event rejection tested at real application boundary
[✓] process/restart test crosses persistence boundary
[✓] coordinator test covers both execution orderings
```

## PASS 4 — Contract / Invariant Compliance

```text
[✓] normal financial correction preserves original event (§3.2)
[✓] Firebase recovery semantics are respected (§3.11)
[✓] remote business LedgerDelete remains history-preserving (§3.14)
[✓] local/cloud divergence is not accepted as a normal correction mechanism
[✓] maintenance/restore deletes are classified separately from normal corrections
[✓] no plan item silently authorizes a new contract behavior
[✓] G8 settled behaviors are not reopened
```

## PASS 5 — Implementation-Readiness / Regression Review

```text
[✓] migration version is explicitly 14 -> 15 for correctsEntryId
[✓] generated Moshi behavior is verified rather than over-prescribed
[✓] backup/restore/uTower pipeline is explicitly included
[✓] WS9 sub-workstreams are independently committable
[✓] RED -> GREEN behavioral proof is required
[✓] certification tests cannot rely on source-string presence alone
[✓] deferred work is explicitly recorded
[✓] release closure requires all workstreams and tests to be green
[✓] remote_version monotonicity is atomic, centralized, and behaviorally verified
[✓] WS9B/WS9C semantics are owner-locked; the only unresolved semantic gate is WS13.4
    for ambiguous activation-success proof after a lost API response
[✓] WS10.5 explicitly defines equal-version behavior as an idempotent no-op and tests it
[✓] WS9A migration verification includes a representative real-device/production-derived
    database copy, not only a fresh empty test database
```

## PASS 6 — OWNER-DECISION / EXECUTION-LIVENESS REVIEW

```text
[✓] WS9B no longer stops for a product choice: rollback semantics are owner-locked.
[✓] WS9C no longer stops for a product choice: uTower Replace is owner-locked as the canonical
    starting-dataset replacement, with cloud reconciliation required.
[✓] Correction method is owner-locked to correction-by-difference.
[✓] Unknown-type policy is owner-locked: financially neutral/non-authoritative + AuditLog.
[✓] G1 duplicate callers return an idempotent successful outcome after re-read.
[✓] There is exactly one STOP GATE: ambiguous proof that a later observed ISP state belongs
    to the original activation operation after a lost API response.
[✓] The STOP GATE includes a bounded evidence search and an explicit no-loop rule.
[✓] Other investigation failures have bounded handling and cannot trigger open-ended retries.
[✓] Rollback outbox behavior is explicit: no remote delete/tombstone for unaccepted temporary import data.
[✓] Replace is explicitly distinct from recurring local-backup restore.
[✓] remote_version equality is explicitly idempotent.
[✓] correction migration uses the representative live Version-14 fixture on a disposable copy.
[✓] No earlier workstream or deferred item was removed while applying these owner decisions.
```

## FINAL INTERNAL REVIEW RESULT

```text
PLAN STATUS: APPROVED FOR IMPLEMENTATION

Execution model: the agent is expected to proceed through all owner-locked decisions without
asking for clarification. It may STOP only at Workstream 13.4 if the existing API/source cannot
prove that a later observed activation state belongs to the original activation operation after
a lost response. The stop is bounded and must not become an open-ended investigation loop.

Remaining work after this plan:
    only explicitly listed Deferred D1-D6 architectural hardening, plus the single owner decision
    that may arise from the Workstream 13.4 STOP GATE if the current API genuinely lacks an
authoritative operation-identity proof.

No additional confirmed P0/P1 defect was identified during the final review beyond the items
explicitly covered above.
```

# END OF PLAN v6
