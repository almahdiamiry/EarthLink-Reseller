# EARTHLINK RESELLER V1
# REMEDIATION PLAN — POST-AUDIT (v1.96.0 / commit a641921)

## Financial Integrity Closure + Production Safety Gate + Durability Wiring + Trust Boundary Hardening

---

# 0. PLAN STATUS

```text
FINAL — IMPLEMENTATION READY
```

This plan is based on the **Code Review Audit — 2026-08-19 (v1.96.0 / commit a641921)**
and on independent source verification performed against the same commit. Every task
below cites an exact file:line finding that was confirmed by reading current source —
not by trusting the audit narrative alone.

No implementation is authorized outside the scope of this plan.

---

# 1. AUTHORITY

Unchanged from the prior FINAL plan:

```text
docs/authority/Target Product Contract v0.6.md
docs/authority/G1-G8 Consolidated Architecture Summary.md
docs/authority/Final Independent Adjudication Memo.md
contract/phase_requirements.yaml
contract/invariant_contract.yaml
contract/invariant_test_map.yaml
contract/test_environment_matrix.yaml
contract/forbidden_patterns.yaml
```

Additional input for this plan:

```text
Code Review Audit — 2026-08-19 (v1.96.0 / commit a641921)  [ISSUE_LOG.md]
```

**Correction to ISSUE_LOG.md:** `BUG-008` and `ARCH-001` are logged as `Resolved`.
Source verification shows this is inaccurate. `IspDisappearanceReconciler.kt` and
`isHistoryOnlySubscriber` were added correctly, but the original destructive paths
(`RemoteSyncCoordinator.applyAccountDelete`, the ledger→account `ON DELETE CASCADE`)
were **not removed**. The system now has two competing mechanisms — a preserving one
and a destroying one — active at the same time. `ISSUE_LOG.md` must be corrected to
`Open` for these two rows once this plan starts, and only set back to `Resolved` after
Task R-1 lands with passing evidence.

G8 is CLOSED and SEALED (commit `399b120`, `6e027e3`). **Do NOT reopen G8.** These are
post-certification remediation items, same posture as the original plan.

---

# 2. FROZEN ARCHITECTURAL CONSTRAINTS

Unchanged — do NOT introduce:

```text
generic reconciliation engine
generic synchronization state machine
staging database
identity registry
dataset_id / published_dataset_id
runtime certification state
runtime governance registry
generic immutable-ledger conflict resolver
second money type migration disguised as a bug fix (see WS-6, scoped separately)
```

`isHistoryOnlySubscriber` and `IspDisappearanceReconciler` are now part of the accepted
architecture (added in commit a641921) and must be **reused**, not duplicated or
reopened, by the tasks below.

The central rule remains:

```text
implementation defect ≠ permission to invent architecture
adding a correct mechanism ≠ removing the incorrect one
```

---

# 3. ROOT CAUSE MAP (Audit → Root Cause)

| RC | Audit IDs | Statement |
|----|-----------|-----------|
| RC-05 | AUDIT-001, AUDIT-002, AUDIT-015 | A parallel history-preserving mechanism (`IspDisappearanceReconciler`) was added without removing the original physical-delete mechanism (`applyAccountDelete`, `applyLedgerDelete`, `deleteAccount`, `deleteAllLedgerEntries`, and the live `ON DELETE CASCADE` FK). Two competing deletion authorities exist simultaneously. |
| RC-06 | AUDIT-004 | `loanIqd` is contractually a preserved, non-authority field (P5-G6-REQ-03), but `BalanceCalculator.applyTransaction`/`revertTransaction` unconditionally overwrite it with the running debt on every recalculation. |
| RC-07 | AUDIT-007 | The "Clear All Local Data" destructive action (wipes device **and** Firestore) is gated only by a confirmation dialog, not by build variant. It is reachable in release builds. |
| RC-08 | AUDIT-003 | `verifyAndResolvePendingOperation` (G1 crash-recovery) is implemented and unit-tested but has no production trigger — no startup sweep, no worker, no automatic call site. A crash between ISP success and local persistence leaves the operation `PENDING` forever with no path to resolution. |
| RC-09 | AUDIT-008, AUDIT-013, AUDIT-017 | Apply-boundary handlers trust inputs without validating input domain or re-checking identity after an `await`: (a) local timestamps under `1e12` are compared against server-domain remote versions as if comparable: `RemoteSyncCoordinator.kt:170-195`; (b) `auth.currentUser.uid` is not re-asserted after credential network I/O, opening a TOCTOU window: `SyncRepositoryImpl.kt:1354,1397-1401`; (c) `RemoteEntityValidator` applies remote financial fields with no version comparison and defaults missing fields to `0.0` instead of the existing local value: `RemoteEntityValidator.kt:67,116-117`. |
| RC-10 | AUDIT-014 | `DataOperationCoordinator.withOperation(DataOperationMode.SYNC)` still wraps the entire `executeSyncPassInternal()` network pass (`SyncRepositoryImpl.kt:260-261`), holding the single global mutex across Firestore `.await()` calls and blocking local business mutations and maintenance operations on network latency. This is the same RC-02 from the original plan; it was never addressed. |
| RC-11 | AUDIT-006, AUDIT-019 | Cloud-persisted ISP credentials are encrypted with a key derived entirely from publicly-obtainable inputs (`uid` + a static compiled salt), making the encryption obfuscation-grade rather than a real security boundary; a legacy AES/ECB path and passphrase-candidate debug logging still exist. |
| RC-12 | AUDIT-009 | Unknown ledger transaction types are preserved in storage but silently financially neutralized (no-op in balance math), with the type-alias list duplicated between `TransactionTypeNormalizer` and migration SQL — a single missed alias silently drops a transaction's financial effect with no error signal. |
| RC-13 | AUDIT-016 | `OutboxManager.enqueueOrReplace` calls `clearPendingByEntity` unconditionally before inserting a new pending row, including when the existing row's status is `"syncing"` (mid-network-push), losing local tracking of an in-flight remote write. |
| RC-14 | AUDIT-010, AUDIT-012 | Restore Replace bumps the G4 generation and clears `pending_external_operations`; Restore Merge and uTower `shouldReplace` import do neither, leaving a window where an in-flight async result from before the operation can still apply into the post-operation dataset, and leaving orphaned outbox pushes for entities deleted by a replace-import. |
| RC-15 | AUDIT-011, AUDIT-020 | `UtowerDebtResolver` Priority-3 reconstructs debt from `0.0` over incomplete history instead of anchoring on the explicit ISP snapshot debt; uTower account IDs are random UUIDs with idempotency resting entirely on fuzzy `SubscriberMatcher` fields, so an ISP export that changes match fields can spawn duplicate accounts on re-import. |
| RC-16 | AUDIT-021 | Restore Replace with `force=true` silently skips the pre-restore safety backup on backup failure instead of surfacing the failure. |
| RC-17 | AUDIT-005 | Money is `Double`/`REAL` end-to-end with epsilon-based comparisons masking rather than detecting drift. Flagged as accepted, bounded risk for this plan (see WS-7) rather than an in-scope fix, pending an explicit decision from you — this is a schema-wide representation change and is treated the same way the original plan treated out-of-scope architecture changes: named, not silently done. |
| RC-18 | AUDIT-018 | G4 generation-mismatch events resolve to `SKIPPED_DUPLICATE`, which advances the sync cursor — a skipped event is never reprocessed. Possibly intentional lineage design; needs an explicit design confirmation before being treated as a defect. |

---

# 3.1 CROSS-REVIEW RECONCILIATION (READ BEFORE STARTING)

A second, independently-produced review of the same audit reached the same
conclusions on substance but used **incorrect AUDIT-ID labels** for several items.
If you (the implementing agent) encounter any other document referencing these IDs,
use this table as the authoritative mapping — do not trust a differing label found
elsewhere:

| ID as sometimes mislabeled | What it was mislabeled as | Actual content (per original 2026-08-19 audit) | Where it lives in THIS plan |
|---|---|---|---|
| AUDIT-004 | "Data Deduplication Failure" | `loanIqd` silently repurposed as a debt shadow | Workstream 2 (RC-06) |
| AUDIT-015 | "Remote Financial Field Defaults (0.0)" | `deleteAccount()` / `deleteAllLedgerEntries()` physically destroy ledger rows | Workstream 1 (RC-05), Task 1.3 |
| AUDIT-017 | "TOCTOU Vulnerability" | Remote financial fields applied with no version comparison; missing field defaults to `0.0`; `isLegacy` can regress from remote `false` | Workstream 5 (RC-09c), Task 5.3 |
| AUDIT-013 | (correctly identified as TOCTOU elsewhere) | Credential sync applies remote values without re-asserting `auth.currentUser.uid` after network I/O | Workstream 5 (RC-09b), Task 5.2 |
| AUDIT-020 | (the actual dedup issue) | uTower account IDs are random UUIDs; idempotency rests entirely on fuzzy `SubscriberMatcher` | Workstream 8 (RC-15), Task 8.2 |

Do not create new tasks for "AUDIT-004 dedup" or "AUDIT-015 field defaults" or
"AUDIT-017 TOCTOU" — those are mislabeled restatements of work already scoped above
under the correct ID. Creating parallel tasks under the wrong ID risks either
duplicate work or, worse, silently skipping the real Task 5.2/5.3/8.2 because
"AUDIT-013/017/020 sounds like it's already done" when a wrongly-labeled version was
actioned instead.

## Two recommendations from that second review are explicitly REJECTED

1. **"Fix P5-G6-REQ-01 by replacing Double with BigDecimal"** — `P5-G6-REQ-01` was
   checked directly against `contract/phase_requirements.yaml`: it is "Establish clear
   field ownership mapping between local Room entities, remote Firestore documents,
   and imported historical structures" — it has nothing to do with money precision.
   There is no verified blocking requirement mandating a money-type change. RC-17
   (Double/REAL precision) stays in Workstream 9 as a **deferred, flagged item
   requiring your explicit go-ahead**, exactly as in the original version of this
   plan. Do not elevate it to P0 on the strength of that citation.

2. **"Certification/testing coverage gaps per P6-G8-REQ-03/04, INV-16/17"** — G8 is
   sealed (commits `399b120`, `6e027e3`). Reopening certification verification work is
   the exact thing both this plan and the original plan forbid ("do not reopen G8").
   Reject this recommendation outright. **Positive confirmation, so you don't have to
   guess:** the three test files this plan asks you to rewrite —
   `FinancialHistoryDeletionProtectionTest.kt`,
   `Phase3SameLineageFinancialMutationTest.kt`, `Phase1FirestoreDocumentIdentityTest.kt`
   — do **not** appear in INV-16's `required_behavior_tests` list, nor in
   `evidence/baseline_test_manifest.json`. They are not part of the frozen
   certification corpus. Editing their assertions per Task 1.6/1.7 is safe and does
   not constitute reopening G8.

## One correction accepted from that second review

`P5-G6-REQ-02` ("Enforce strict credential and session isolation across reseller
accounts to prevent cross-account state leakage") is a real, verified, blocking
requirement, and it is a better authority citation for **Task 5.2 (RC-09b, the
credential-sync TOCTOU fix)** than anything in the original plan text. Task 5.2 below
has been updated to cite it. It does **not** apply to RC-11 (credential *encryption
strength*, AUDIT-006/019) — that remains a policy gap with no blocking requirement
forcing a specific cryptographic bar, per Contract §3.12's own wording ("exact secure
storage architecture is a technical question"), and stays deferred in Workstream 9.

---

# 4. NON-NEGOTIABLE BUSINESS SEMANTICS (carried forward, unchanged)

```text
ISP account disappears
        ↓
local account remains
        ↓
isHistoryOnlySubscriber = true
        ↓
history remains
        ↓
financial ledger remains
```

This must now hold **regardless of the trigger**: ISP disappearance (already correct
via `IspDisappearanceReconciler`), a remote `AccountDelete` sync event from another
device, or a local production account-retirement action. Today only the first trigger
is protected. This plan closes the other two.

---

# 5. WORKSTREAM 1 (P0 — CRITICAL) — RC-05

# UNIFY DELETION AUTHORITY: REMOVE THE PHYSICAL-DELETE PATH

## Objective

There must be exactly **one** authority for what happens when an account "goes away,"
regardless of trigger. That authority is the existing `isHistoryOnlySubscriber` /
`IspDisappearanceReconciler` mechanism. The old physical-delete mechanism must be
retired, not run in parallel with it.

## Task 1.1 — Retire physical delete in `applyAccountDelete`

File: `RemoteSyncCoordinator.kt:378-430` (current `ConflictDecision.APPLY_DELETE` branch,
lines ~398-411).

Remove:
```text
ledgerDao.deleteByAccountId(event.entityId)
accountDao.deleteById(event.entityId)
```

Replace with the history-only transition already used by `IspDisappearanceReconciler`:
mark the local account `isHistoryOnlySubscriber = true`, preserve all ledger rows,
and still write the `tombstone:account:` / `tombstone:ledger:` metadata entries so
transport-level convergence (another device's outbox knows this was acknowledged)
is unaffected. Do not invent a second tombstone format — reuse the existing metadata
keys, only change what happens to the Room rows.

## Task 1.2 — Retire the equivalent in `applyLedgerDelete`

Audit report cites `RemoteSyncCoordinator.kt:600` as the second physical-delete site.
Locate the exact current line (line numbers will have shifted since the audit) and
apply the same rule: a remote ledger-delete event must not physically remove a row
that is part of financial history. If ledger-level deletion is a legitimate feature
for correcting a single mistaken manual entry (distinct question — see Task 1.5), keep
that as a **separate, explicitly local, non-remote-triggered** code path; a remote
event must never physically delete a ledger row.

## Task 1.3 — Retire physical delete in production account-lifecycle repository calls

Files: `Repositories.kt` — `deleteAccount()` (~1095-1108) and `deleteAllLedgerEntries()`
(~1702-1723).

`deleteAccount()` must stop calling `ledgerDao.deleteByAccountId()` +
`accountDao.deleteById()` for the production "retire this subscriber" UI action
(`LocalAccountsViewModel.deleteAccountLocal`). It must instead mark
`isHistoryOnlySubscriber = true` through the same path `IspDisappearanceReconciler`
uses, so there is one write path for "this account is no longer active," not two.

`deleteAllLedgerEntries()` must not be reachable from any production lifecycle action.
Confirm its only caller (if any) is a genuinely developer-only reset; if it has no
caller in `app/src/main`, delete the dead function rather than leave it as an unused
landmine (Task 1.6 will need this decision recorded either way).

## Task 1.4 — Remove the `ON DELETE CASCADE`, finally

`MIGRATION_12_13` (added in commit a641921) only added the `isHistoryOnlySubscriber`
column. The cascade from `MIGRATION_5_6` is still live and `PRAGMA foreign_keys=ON`
is still set in `onOpen`. This is the actual database-level danger — it does not care
which application code path issued the `DELETE`, including future code no one has
written yet.

Add `MIGRATION_13_14`:

```text
CREATE new local_ledger_entries table with the same schema
but FOREIGN KEY (accountId) REFERENCES local_accounts(id) ON DELETE NO ACTION
copy all rows from the old table
drop the old table
rename the new table into place
recreate indices
```

This is the identical table-rebuild pattern already used twice in this codebase
(MIGRATION_3_4, MIGRATION_5_6) — SQLite cannot `ALTER TABLE` a foreign-key action
directly.

### Verification

```text
fresh database at version 14
existing-schema migration path 1 -> 14
exported schema at version 14 shows local_ledger_entries FK as NO ACTION
```

## Task 1.5 — Preserve legitimate single-entry ledger correction, separately

`Repositories.kt: deleteTransaction(id)` (single ledger-entry delete, ~1685-1694) is a
plausible legitimate feature (undo a manually mis-entered transaction) and is not, by
itself, the account-lifecycle problem this workstream targets. Do not remove it. Do
confirm it is reachable only from an explicit, local, user-initiated "delete this one
transaction" UI action — never from a remote sync event or an account-lifecycle action
— and add a comment at the call site stating this constraint so a future change does
not accidentally wire a remote event into it.

## Task 1.6 — Replace the regression test with one that hits the real paths

`FinancialHistoryDeletionProtectionTest.kt` is the designated `behavioral_test_location`
for **two blocking, frozen requirements**: `P5-G6-REQ-03` ("preserve legacy semantic
fields ... without repurposing") and `P5-G6-REQ-04` ("ISP-side deletion MUST NOT
physically delete local financial history"). Today it does not exercise
`applyAccountDelete`, `applyLedgerDelete`, or `deleteAccount()` at all — every test in
it either pre-seeds an already-legacy account or tests something unrelated (a field
update, or a `LedgerDelete` event on a standalone entry).

Required tests (replace, do not merely add alongside the old ones — the old assertions
in `Phase3SameLineageFinancialMutationTest.kt` and `Phase1FirestoreDocumentIdentityTest.kt`
that assert `assertNull(db.localAccountDao().getByIdOneShot(account.id))` after a remote
`AccountDelete` are now testing the wrong behavior and must be rewritten per Task 1.7):

```text
1. create account -> create >=2 ledger entries -> call real production
   deleteAccount() -> assert account exists with isHistoryOnlySubscriber=true
   -> assert ALL ledger rows still present

2. create account -> create >=2 ledger entries -> apply a real
   RemoteEvent.AccountDelete through RemoteSyncCoordinator.processEvent
   -> assert account exists with isHistoryOnlySubscriber=true
   -> assert ALL ledger rows still present

3. same as (2) but for a standalone RemoteEvent.LedgerDelete on one entry
   without an account delete -> assert only that entry's fate matches
   whatever Task 1.2/1.5 decided (do not assume; assert the decided behavior)

4. import an account through the legacy uTower JSON path -> assert
   isLegacy / isHistoryOnlySubscriber are not silently repurposed
   (this closes the gap between P5-G6-REQ-03's "no repurposing" clause
   and the importer naming risk noted in the prior review — confirm
   commit a641921's isLegacy/isHistoryOnlySubscriber decoupling actually
   holds under re-import of an already-history-only account)
```

## Task 1.7 — Fix the two contradictory existing tests

`Phase3SameLineageFinancialMutationTest.kt` (~line 600-614) and
`Phase1FirestoreDocumentIdentityTest.kt` (~line 353-362) both currently assert the
account is physically gone from Room after a remote `AccountDelete`. Rewrite these
assertions to match the corrected semantic (account preserved, `isHistoryOnlySubscriber
= true`). Do not weaken or delete these tests to make the suite pass — correct the
asserted behavior; the tests should still prove something (that the account converges
correctly across devices), just not the old, wrong thing.

## Definition of Done — Workstream 1

```text
[ ] No code path — local production delete, remote AccountDelete, remote
    LedgerDelete cascade — physically removes a ledger row belonging to
    financial history.
[ ] ON DELETE CASCADE removed from local_ledger_entries FK via table-rebuild
    migration (version 14), verified against a fresh DB and an upgrade path.
[ ] deleteAccount() production path preserves history via
    isHistoryOnlySubscriber, reusing the existing IspDisappearanceReconciler
    write path rather than a new one.
[ ] deleteAllLedgerEntries() is either provably unreachable from production
    or removed.
[ ] FinancialHistoryDeletionProtectionTest.kt exercises the real
    applyAccountDelete / applyLedgerDelete / deleteAccount() paths.
[ ] Phase3SameLineageFinancialMutationTest.kt and
    Phase1FirestoreDocumentIdentityTest.kt no longer assert physical deletion.
[ ] ISSUE_LOG.md BUG-008 / ARCH-001 corrected to reflect true status until
    this workstream's tests pass, then marked Resolved with the commit hash.
```

---

# 6. WORKSTREAM 2 (P0 — CRITICAL) — RC-06

# STOP OVERWRITING loanIqd

## Objective

`loanIqd` is a contractually protected field (`P5-G6-REQ-03`). It must never be written
by balance recalculation — only by import / explicit manual edit.

## Task 2.1

File: `BalanceCalculator.kt`. Remove `loanIqd = newDebt` from both branches of
`applyTransaction` and both branches of `revertTransaction`. `AccountBalances.loanIqd`
in the return value should instead carry through `currentLoan` unchanged.

## Task 2.2

Grep every caller of `BalanceCalculator.applyTransaction` /
`.revertTransaction` (`Repositories.kt`, `UtowerImporter.kt`) and confirm none of them
is relying on the old "loan mirrors debt" value for a UI display or a downstream
calculation. If any UI surface currently shows `loanIqd` expecting it to track debt,
that surface needs its own explicit source (read `debtIqd` directly), not a
side-effect of the balance calculator.

## Task 2.3 — Test

```text
import an account with a distinct, non-zero loanIqd different from its debt
run several debt/payment transactions through the real repository path
assert loanIqd is unchanged after each transaction
assert debtIqd/advanceIqd still recompute correctly
```

## Definition of Done

```text
[ ] BalanceCalculator never writes loanIqd.
[ ] loanIqd only changes via import or an explicit manual edit path.
[ ] Regression test proves loanIqd survives a transaction sequence untouched.
[ ] G6 field-classification for loanIqd formally closed in authority docs.
```

---

# 7. WORKSTREAM 3 (P0 — CRITICAL) — RC-07

# GATE THE DESTRUCTIVE "CLEAR ALL LOCAL DATA" ACTION

## Objective

An action that permanently deletes both the local device **and** the Firestore cloud
data for the entire business must not be reachable by an ordinary user of a release
build.

## Task 3.1

File: `SettingsScreen.kt`. The "Clear All Local Data" `TextButton` and its two
`AlertDialog`s (~line 686 onward) currently render unconditionally inside a
"Developer Mode" card where only the separate "Demo Mode" `Switch` is wrapped in
`BuildConfig.DEBUG`. Wrap the entire destructive block (button + both dialogs) in the
same `BuildConfig.DEBUG` check, or, if a support/recovery use case for a shipped build
is genuinely required (the audit notes this is plausible per Contract §3.15), replace
the build-time gate with a runtime gate that cannot be reached without a deliberate,
logged, signed-in "developer/support mode" flag — not a UI section label alone.

## Task 3.2

Decide and record which of the two is the actual product requirement (debug-only vs.
support-mode-gated) before implementing — this is a product decision, not an
implementation detail, and the plan should not guess. Default recommendation if no
other guidance exists: `BuildConfig.DEBUG`, matching how "Demo Mode" is already gated
in the same file.

## Task 3.3 — Certification check

Add a build-time or CI check (grep-level is sufficient, per the pattern already used
in `contract/forbidden_patterns.yaml`) asserting a release build cannot reach
`dashboardViewModel.clearLocalData(...)` from any Composable that isn't behind the
chosen gate.

## Definition of Done

```text
[ ] Clear-all-data entry point is not reachable in a release build (or is
    behind an explicit, deliberate, non-label-only gate).
[ ] Certification check exists preventing this from silently regressing.
```

---

# 8. WORKSTREAM 4 (P1 — HIGH) — RC-08

# WIRE UP G1 CRASH-RECOVERY

## Objective

`verifyAndResolvePendingOperation` must actually run against real `PENDING`/`FAILED`
operations, not only in unit tests.

## Task 4.1

Add a startup sweep: on `EarthlinkApp.onCreate` (or the first authenticated app open,
if the operation needs a signed-in gateway), query pending operations older than a
short grace window and invoke `verifyAndResolvePendingOperation` for each, using the
existing per-account lock pattern already present in `EarthlinkSearchViewModel.
resolvePendingOperation` (do not build a second locking mechanism — call that existing
function, or extract its guarded body into a shared entry point both the ViewModel and
the sweep can call).

## Task 4.2

Add the same sweep as part of the existing `SyncWorker` run, so recovery does not
depend solely on the app being foregrounded at the right moment.

## Task 4.3 — Test

```text
simulate: ISP call succeeds -> process killed before local ledger write
-> restart -> sweep runs -> assert ledger materializes with the same
   transaction ID (idempotent, not duplicated)
```

## Task 4.4 (P2, optional but recommended)

Dashboard surface for operations pending longer than N hours, so a reseller can see
and manually trigger verification if the automatic sweep hasn't resolved something
(e.g., still no network).

## Definition of Done

```text
[ ] verifyAndResolvePendingOperation has at least one production trigger
    (startup sweep and/or SyncWorker).
[ ] Behavioral test proves recovery survives a real process kill scenario.
[ ] No second locking/idempotency mechanism introduced — reuses existing
    per-account lock and transaction-identity guarantees.
```

---

# 9. WORKSTREAM 5 (P1 — HIGH) — RC-09

# TRUST-BOUNDARY HYGIENE (VERSION DOMAIN / SESSION / MISSING-FIELD)

## Objective

Codify and enforce three rules at every apply/merge boundary that currently assumes
its inputs:

```text
a) a comparison timestamp must be known to be in the same domain
   (local ms-since-epoch vs. server-assigned version) before being compared
b) the acting Firebase session must be re-validated after any await that
   crossed a network boundary, not assumed unchanged since the call started
c) a missing remote field must never silently reset a financial value to 0.0
```

## Task 5.1 — RC-09a

File: `RemoteSyncCoordinator.kt:170-195`. Remove the sub-`1e12` local-timestamp
fallback that gets compared directly against server-ms remote versions. An untracked
entity should be treated as untracked (falls through to `UNKNOWN_MISSING` in
`SyncConflictResolver`, which already exists and already resolves correctly), not
given a synthetic cross-domain version that can accidentally lose a comparison it
should have won.

## Task 5.2 — RC-09b

File: `SyncRepositoryImpl.kt:1354,1397-1401`. After the credential-sync network I/O
completes, re-read `auth.currentUser?.uid` and compare it against the `targetUid`
captured before the `await`. If they differ (user switched/signed out mid-operation),
abort applying the result rather than writing it under the old identity. This task is
the direct implementation of the blocking requirement `P5-G6-REQ-02` ("Enforce strict
credential and session isolation across reseller accounts to prevent cross-account
state leakage") — treat it accordingly, not as a nice-to-have.

## Task 5.3 — RC-09c

File: `RemoteEntityValidator.kt:67,116-117`. Where a financial field
(`debtAfterIqd` and siblings) is absent from the incoming remote document, fall back to
the existing local value, not `0.0`. This mirrors the pattern already correctly used
for the ISP/cache-owned fields (`d["towerName"] as? String ?: existingLocalAccount?.
towerName`) — apply the same `?:` fallback-to-existing pattern to the financial fields
that currently default to a hardcoded zero.

Also close the `isLegacy` regression noted in the audit (AUDIT-017: "isLegacy can
regress from remote false") — confirm this is now moot given commit a641921's
decoupling of `isLegacy` from `isHistoryOnlySubscriber`; if `isLegacy` is still
present as a field the validator applies from remote data, verify a remote `false`
cannot silently clear a locally-meaningful state. If `isLegacy` no longer carries any
lifecycle meaning post-decoupling, this line is likely already safe — confirm with a
test rather than assuming.

## Task 5.4 — Adversarial tests (one per rule)

```text
cross-domain version injection: local entity with no tracked remote
version, receiving a remote event -> must not be rejected by a bogus
timestamp comparison

account-switch-during-await: begin credential sync, swap signed-in
user mid-network-call, assert result is discarded not applied

missing-field reset: remote account document omitting debtAfterIqd
-> local value must survive unchanged, not become 0.0
```

## Definition of Done

```text
[ ] No cross-domain timestamp comparison remains.
[ ] Session identity re-checked after every credential-sync await.
[ ] Missing remote financial fields fall back to existing local value.
[ ] Three adversarial tests above pass.
[ ] ISSUE_LOG.md gets new rows for AUDIT-013 and AUDIT-017 (using these
    exact IDs, not the swapped labels from the second review) if they are
    not already present, so this workstream's closure is traceable.
```

---

# 10. WORKSTREAM 6 (P1 — HIGH) — RC-10

# SAME AS THE ORIGINAL PLAN'S BUG-002 — STILL OPEN

This is unchanged from the original FINAL plan's Workstream 2 (BUG-002); it was never
implemented in commit a641921. Re-run that workstream's tasks as written (map
coordinator boundaries → define the local-transaction/remote-transport split → protect
G1 durability → deterministic blocking regression test → lost-ACK/race verification),
against current line numbers:

```text
DataOperationCoordinator.withOperation(DataOperationMode.SYNC) still wraps
executeSyncPassInternal() in full: SyncRepositoryImpl.kt:260-261
```

One addition based on this audit round: also check `SyncRepositoryImpl.kt:712`, a
second `withOperation(DataOperationMode.SYNC)` call site found during this review —
classify it the same way (local business mutation / remote transport / maintenance)
before deciding whether it needs to move.

Also re-confirm the `INV-11` canonical-definition tension flagged in the prior review
(the invariant's text names `DataOperationCoordinator` as "an active coordination
mechanism" for a single serialized boundary — update that text once the local/remote
split lands, not just the test-map files, so `contract/invariant validation` stays
truthful).

## Definition of Done

Same as the original plan's BUG-002 DoD — carried forward unchanged.

---

# 11. WORKSTREAM 7 (P2 — MEDIUM) — RC-12, RC-13

# LEDGER SEMANTICS SAFETY NET

## Task 7.1 — RC-12: unknown transaction types must not silently no-op

Files: `BalanceCalculator.kt:26`, `TransactionTypeNormalizer.kt:11-14`,
`AppDatabase.kt:524-525` (duplicated alias list). Two changes:

```text
a) single source of truth for the type-alias list — normalizer and
   migration SQL must not maintain separate copies
b) an unrecognized type after normalization must be surfaced (audit log
   entry / quarantine), not silently treated as financially neutral
```

## Task 7.2 — RC-13: don't clear an in-flight outbox row

File: `OutboxManager.kt:74` (`enqueueOrReplace` → `clearPendingByEntity`). Only clear
existing pending rows whose `status` is `"pending"` or `"failed"`; a row with status
`"syncing"` is mid-network-push and must not be silently dropped from tracking. Decide
explicitly what should happen instead — most likely: let the in-flight push finish and
its own completion handler supersede/replace, or queue the new mutation to apply after
the in-flight one resolves. Do not build a generic queueing engine for this — a status
check before the delete is sufficient.

## Definition of Done

```text
[ ] Alias list exists in exactly one place.
[ ] Unknown transaction type after normalization produces a visible signal.
[ ] enqueueOrReplace never deletes a "syncing" row.
```

---

# 12. WORKSTREAM 8 (P2 — MEDIUM) — RC-14, RC-15, RC-16

# RESTORE / IMPORT LINEAGE + DEBT RECONSTRUCTION EDGE CASES

## Task 8.1 — RC-14: Restore Merge / replace-import bookkeeping parity

File: `BackupManager.kt` (Merge path ~1054-1086 vs. Replace path ~487-490). Either (a)
bump the G4 generation inside Merge's final transaction the same way Replace does, or
(b) produce a race test proving same-lineage merge is safe without a generation bump —
pick one and implement it; do not leave the asymmetry undocumented. Separately, for
uTower `shouldReplace` import (`UtowerImporter.kt:284-288,612-616`), tombstone
already-queued outbox pushes for entities the replace-import is about to delete/replace,
so a stale push doesn't resurrect a wiped entity remotely.

## Task 8.2 — RC-15: debt reconstruction anchor + duplicate-import guard

`UtowerDebtResolver.kt:48-59` Priority-3 path: when an explicit ISP snapshot debt value
is available, anchor reconstruction on it rather than rebuilding from `0.0` over
possibly-incomplete history. `UtowerImporter.kt:1156`: since uTower account IDs are
random UUIDs, idempotency depends entirely on `SubscriberMatcher`'s fuzzy fields —
add a regression test that re-imports the same subscriber after a plausible field
change (e.g., a corrected phone number) and confirms it merges into the existing
account rather than creating a duplicate; if `SubscriberMatcher` currently can't
guarantee this, tighten its matching rather than accepting silent duplication.

## Task 8.3 — RC-16: don't silently bypass a failed safety backup

`BackupManager.kt:1167-1179`. `force=true` on Restore Replace currently proceeds even
if the pre-restore safety backup failed. Surface the backup failure to the caller (so
the "Force Clear" confirmation dialog, if that is even where this is reachable from,
can tell the user their safety net didn't get created) rather than swallowing it.

## Definition of Done

```text
[ ] Merge either bumps G4 or has a proven race-safety test; decision recorded.
[ ] Replace-import tombstones outbox entries for entities it deletes.
[ ] Debt reconstruction anchors on explicit snapshot debt when available.
[ ] Duplicate-import regression test passes for a field-changed re-import.
[ ] Failed pre-restore backup surfaces to the caller instead of being silent.
```

---

# 13. WORKSTREAM 9 (DEFERRED — NEEDS YOUR DECISION, NOT AUTO-IMPLEMENTED)

## RC-11 — Credential encryption strength (AUDIT-006, AUDIT-019)

The audit's own 5-Whys correctly frames this as a **policy gap**, not an
implementation bug: there is no backend/KMS in this client-only app, so a real secret
channel doesn't currently exist to derive a non-public key from. Two real options:

```text
a) accept this as a documented, bounded risk (same posture the project
   already takes with the G1 gap) — requires an explicit decision, not
   silence
b) introduce a real secret channel (e.g., a user-supplied passphrase used
   as an additional KDF input that is never stored, or a server-mediated
   key exchange) — this is a real architecture addition, out of scope for
   a "fix the bug" pass and should be its own planned workstream if chosen
```

Minimum hygiene regardless of the above decision (safe to do now, doesn't require the
decision): remove the legacy AES/ECB path (`BackupManager.kt:1244-1256`), stop printing
passphrase candidates in debug logs (`BackupManager.kt:1327`).

## RC-17 — Double/REAL money representation (AUDIT-005)

Same posture: a schema-wide representation change (Double → Long-IQD or BigDecimal)
touching entity, schema, calculator, and UI layers. Flagged, not silently implemented.
If you want this scheduled, it should be its own plan with its own migration and
value-preservation test corpus — not folded into this remediation pass.

## RC-18 — G4 `SKIPPED_DUPLICATE` cursor advance (AUDIT-018)

Needs a design confirmation before being treated as a defect: is a skipped
generation-mismatch event *supposed* to never be reprocessed (intentional lineage
design), or is that a gap? Get an explicit answer, then either close this as "by
design" (mirroring how the original plan closed BUG-003 as ISP behavior) or open a
proper task.

---

# 14. EXECUTION ORDER

```text
1. Workstream 1  (RC-05 — unify deletion authority)       — P0
2. Workstream 2  (RC-06 — loanIqd)                          — P0
3. Workstream 3  (RC-07 — gate destructive tool)            — P0
4. Workstream 4  (RC-08 — G1 recovery wiring)                — P1
5. Workstream 5  (RC-09 — trust-boundary hygiene)            — P1
6. Workstream 6  (RC-10 — coordinator/transport split)       — P1
7. Workstream 7  (RC-12/13 — ledger + outbox safety net)    — P2
8. Workstream 8  (RC-14/15/16 — restore/import edge cases)  — P2
9. Workstream 9  — deferred, requires your decision before any code changes
```

Do not implement 1-3 simultaneously with each other's tests still red; each
workstream needs its own passing verification before the next begins, same
discipline as the original plan.

---

# 15. FORBIDDEN CHANGES (carried forward + additions)

```text
[ ] do not reopen G8
[ ] do not modify G8 certification contracts
[ ] do not create a generic reconciliation engine
[ ] do not create a generic sync state machine
[ ] do not introduce a second "account is gone" mechanism — reuse
    isHistoryOnlySubscriber / IspDisappearanceReconciler for every trigger
[ ] do not weaken or delete a failing test to make the suite pass
[ ] do not silently implement the Double->Long-IQD migration (WS-9) or the
    credential-KMS redesign (WS-9) without an explicit go-ahead
[ ] do not leave ISSUE_LOG.md rows marked Resolved without a passing test
    tied to that specific claim
```

---

# 16. FINAL VERIFICATION COMMAND SET

Same discipline as the original plan — after each workstream, run and record actual
exit codes and test artifacts, not narrative claims:

```text
targeted Workstream tests (per workstream, as specified above)
FinancialHistoryDeletionProtectionTest full suite
Phase3SameLineageFinancialMutationTest / Phase1FirestoreDocumentIdentityTest
Phase5IspLifecycleAndHistoryOnlyTest (existing, from a641921 — must still pass)
contract/invariant validation
forbidden-pattern validation
full current JVM/Robolectric corpus
```

Do not claim a workstream done from narrative output alone.

---

# END OF REMEDIATION PLAN
