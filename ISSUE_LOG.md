# Issue Log and Systematic Debugging

| Issue ID | Description | Status | Suspected Root Cause / Resolution | Priority |
| :--- | :--- | :--- | :--- | :--- |
| BUG-001 | Google Login: Accounts show empty on first login | Resolved | Added sync trigger and initial restore/hydration flow after Google Sign-In authentication. | High |
| BUG-002 | Add Debt/Payment: Works once, then UI becomes greyed out (disabled) | Resolved | Fast non-blocking UI transaction path, proper state resetting, and deterministic unique transaction ID generation. | High |
| BUG-003 | Extend Subscription: Extends by 48h (1 day 23h) and consumes 1 Test User | By Design (ISP Behavior) | Earthlink API natively handles extensions by granting ~48 hours of grace period and deducting 1 unit from the "Test Users" balance. This is ISP-side logic, not a local app bug. | Low |
| BUG-004 | Activation: No UI feedback provided | Resolved | Added user-facing snackbar feedback and operation status notification for activation flow. | Low |
| BUG-005 | MoneyParser: Improper scaling of 500 (IQD vs 500k) | Resolved | Fixed logic to strictly scale only when `amount < 100` (e.g., 50 -> 50,000), allowing exact values for 250, 500, 750. | High |
| BUG-006 | Debt/Payment: Add debt not working (as reported by user) | Resolved | Resolved via non-blocking UI state management and deterministic idempotency coordinates. | High |
| BUG-008 | Dashboard clear data deletes all ledger history | Resolved | Preserved ledger entries via soft-deletion / history protection and decoupled lifecycle states. | Critical |
| ARCH-001 | Missing ISP Deletion Reconciliation & History-Only Decoupling | Resolved | Implemented `IspDisappearanceReconciler` using authoritative `earthlinkUsername` ↔ `userID` mapping and dedicated `isHistoryOnlySubscriber` field (MIGRATION_12_13). | Critical |

## Multi-pass Deep Dive & Architecture Findings

### 1. Synchronization (Firestore Storage & ISP Data)
- **Finding:** Stripping ISP-provided metadata (`rawJson`, `stateSource`, `latitude`, `longitude`, etc.) from the Firestore sync payload aligns with the Business Need.
- **Rationale:** 
  1. The ISP (Earthlink) is the ultimate authority for these fields.
  2. Local fields mapped to reseller input (Debt, Advance, Loan, Phone, Notes, Names) must be synced.
  3. Re-syncing ISP data to Firestore wastes bandwidth. The app pulls these from Earthlink API, not Firebase.
- **Action:** The stripping logic implemented in `SyncRepositoryImpl.kt` protects the business logic.

### 2. ISP Deletion Reconciliation & History Preservation (RC-03 / RC-04)
- **Finding:** ISP-departed subscribers must retain local debt and transaction history without being purged or conflated with version tracking metadata.
- **Resolution:**
  1. Decoupled `LocalAccount.isLegacy` (version-tracking fallback suppression) from subscriber lifecycle.
  2. Added monotonic `LocalAccount.isHistoryOnlySubscriber` via `MIGRATION_12_13`.
  3. Implemented `IspDisappearanceReconciler.kt` comparing authoritative ISP subscriber lists against local accounts with valid `earthlinkUsername`. Missing accounts with active debt/ledger history transition monotonically to history-only status.
  4. Filtered active queries to exclude history-only subscribers while preserving them in dedicated historical ("محذوفة") filters.

### 3. Extend Subscription Logic (BUG-003)
- **Finding:** The extension duration (1 day 23 hours) and the drop in Test Users (7 to 6) is controlled entirely by Earthlink's API (`gateway.extendUser`).
- **Rationale:** Earthlink deducts a "Test User" credit to grant a temporary 48-hour extension. Our app simply sends the `extend-subscription` HTTP request. We cannot change how Earthlink processes the extension mathematically.
- **Action:** This is an ISP behavior (By Design), not an app bug. No code change needed; user education required.

---

## Code Review Audit — 2026-08-19 (v1.96.0 / commit a641921)

Full code review against Target Product Contract v0.6, G1–G8 Consolidated Architecture Summary, and Final Independent Adjudication Memo. All findings below are source-verified.

| Issue ID | Description | Evidence (file:line) | Contract/Authority Ref | Severity | Status |
| :--- | :--- | :--- | :--- | :--- | :--- |
| AUDIT-001 | Remote sync delete physically erases local financial history (account + all child ledger rows), contradicting "ISP-side deletion must not delete local history" | `RemoteSyncCoordinator.kt:398-411`, `:600` | Adjudication Memo §5, Contract §3.14/TQ-14 | Critical | Open |
| AUDIT-002 | `ON DELETE CASCADE` on ledger→account FK with `PRAGMA foreign_keys=ON`; is an active production path | `Models.kt:358-366`, `AppDatabase.kt:780` | Adjudication Memo §5 | Critical | Open |
| AUDIT-003 | G1 crash-recovery (`verifyAndResolvePendingOperation`) implemented but has **zero production callers** — no startup sweep, no worker; crashed pending ops stay `PENDING` forever | `Repositories.kt:1290-1445`; grep shows no callers outside tests | Contract §3.7/TQ-27, G1 gate | Critical | Open |
| AUDIT-004 | `loanIqd` silently repurposed as debt shadow: every recalc overwrites current `loanIqd` with running debt, discarding imported loan value | `BalanceCalculator.kt:17,24`, `Repositories.kt:2616` | Contract §16, Memo §4.7 | Critical | Open |
| AUDIT-005 | Money stored and computed as `Double`/SQL `REAL` end-to-end; floating-point drift risk on cumulative IQD reconstruction | `Models.kt:330-333,383-384`, `BalanceCalculator.kt:10-46` | Contract §3.18 (data integrity) | High | Open |
| AUDIT-006 | Cloud credential encryption is obfuscation-grade: AES-GCM key = PBKDF2(uid, uid + static-compiled salt); both inputs obtainable from Firestore path + APK | `CloudSecretEncryptor.kt:17-34` | Contract §3.12/TQ-09, G6 | High | Open |
| AUDIT-007 | "Clear All Local Data" destructive tool visible in release builds (wipes local DB + Firestore); only Demo toggle inside is DEBUG-gated | `SettingsScreen.kt:753-755`, `SyncRepositoryImpl.kt:1452-1466` | Contract §3.15, Memo §5 | High | Open |
| AUDIT-008 | Mixed-domain version comparison: local sub-1e12 timestamps used as fallback remote version vs server-ms versions → remote always wins for untracked entities | `RemoteSyncCoordinator.kt:170-195` | AGENTS.md Invariant #3, G7 | High | Open |
| AUDIT-009 | Unknown transaction types preserved in ledger but financially neutralized (no-op in balance math); hardcoded alias list duplicated in normalizer + migration SQL | `BalanceCalculator.kt:26`, `TransactionTypeNormalizer.kt:11-14`, `AppDatabase.kt:524-525` | Contract §3.1 (ledger integrity) | High | Open |
| AUDIT-010 | Restore Merge never bumps G4 generation and doesn't clear `pending_external_operations` (Replace does both) | `BackupManager.kt:1054-1086` vs `:487-490` | G3/G4 gates, Memo §4.2/4.4 | Medium | Open |
| AUDIT-011 | UtowerDebtResolver Priority-3 reconstructs debt from 0.0 over incomplete history instead of anchoring on explicit snapshot debt | `UtowerDebtResolver.kt:48-59` | Contract §3.6, G5 | Medium | Open |
| AUDIT-012 | uTower import `shouldReplace` wipes accounts/ledgers without tombstoning already-queued outbox pushes for deleted entities | `UtowerImporter.kt:284-288`, `:612-616` | G2/G3, Memo §3.6 | Medium | Open |
| AUDIT-013 | Credential sync applies remote creds after network I/O without re-asserting `auth.currentUser.uid == targetUid` (TOCTOU window) | `SyncRepositoryImpl.kt:1354,1397-1401` | Contract §TQ-10, G6 | Medium | Open |
| AUDIT-014 | Global coordinator/snapshot mutexes held across network I/O (full sync pass; Firestore `get(SERVER)` awaits); wedges import/restore on slow networks | `SyncRepositoryImpl.kt:964-988` | Memo TQ-08, G4 | Medium | Open |
| AUDIT-015 | Repository-level `deleteAccount` / `deleteAllLedgerEntries` are business paths that physically destroy ledger rows (account delete pre-emptively tombstones children) | `Repositories.kt:1098-1102`, `:1702-1723` | Contract §3.2 (history immutable) | Medium | Open |
| AUDIT-016 | `clearPendingByEntity` wipes in-flight `"syncing"` outbox rows on re-enqueue; mid-push obligations drop from tracking | `AppDatabase.kt:232-233`, `OutboxManager.kt:74` | G2 | Medium | Open |
| AUDIT-017 | Remote financial fields are LWW inside validator with no version comparison; `debtAfterIqd` defaults 0.0 on missing; `isLegacy` can regress from remote `false` | `RemoteEntityValidator.kt:67,116-117` | G6, Memo §6 | Medium | Open |
| AUDIT-018 | G4 generation-mismatch events return `SKIPPED_DUPLICATE` which advances cursor — skipped events never reprocessed (possibly intended lineage design; needs confirmation) | `EventSyncResult.kt:50-52`, `RemoteSyncCoordinator.kt:233-246` | G4 | Low | Open |
| AUDIT-019 | Backup passphrase blob wrapped with UID-derived (non-Keystore) key + legacy AES/ECB path; debug trace prints passphrase candidates | `BackupManager.kt:147-168`, `:1244-1256`, `:1327` | G6, Memo §6 | Low | Open |
| AUDIT-020 | uTower account IDs are random UUIDs (non-deterministic); re-import idempotency depends entirely on fuzzy SubscriberMatcher — re-export losing match fields can spawn duplicate accounts | `UtowerImporter.kt:1156` | G5, TQ-19/TQ-20 | Low | Open |
| AUDIT-021 | Restore Replace `force=true` silently bypasses failed pre-restore safety backup | `BackupManager.kt:1167-1179` | Contract §3.16, G3 | Low | Open |

### Passing Areas (verified, no action)

| Area | Verdict | Evidence |
| :--- | :--- | :--- |
| Ledger creation atomicity (all paths: insert + balance + outbox in one Room tx) | PASS | `Repositories.kt:1585-1684`, `UtowerImporter.kt:603-650` |
| Transaction identity: UUID generated once, reused on retry; deterministic import IDs (`tx_{acct}_{extId}`, fallback `import_{batch}_{idx}`); re-import idempotent | PASS | `Repositories.kt:1483,1536,1947`, `UtowerImporter.kt:213,1400-1429` |
| Firestore write idempotency: `set(doc, payload, merge())` with doc ID = local tx ID | PASS | `SyncRepositoryImpl.kt:386-387,571-572` |
| Outbox: no DEAD_LETTER, no retry-count abandonment, per-item failure isolation | PASS | `OutboxManager.kt:12-15,279-314` |
| Multi-device accumulation: insert-if-absent, same-ID divergence quarantined | PASS | `RemoteSyncCoordinator.kt:469-488` |
| Offline ledger recording (no network gate) | PASS | `Repositories.kt:1585-1662` |
| uTower snapshot debt preserved as opening baseline (primary path) | PASS | `UtowerImporter.kt:1170-1176` |
| Restore Replace: pre-backup, atomic tx, G4++ in-tx with TOCTOU guard | PASS | `BackupManager.kt:468-490,1151-1178,1356-1364` |
| Restore Merge lineage selection + purity validation | PASS | `BackupManager.kt:621-650,888-905` |
| ISP disappearance → history-only mark, ledger untouched | PASS | `IspDisappearanceReconciler.kt:50-99` |
| Demo mode hard-gated in release | PASS | `PreferenceManager.kt:667-671`, `MainActivity.kt:296-308` |
| No second mutation channel / one-state-one-authority | PASS | All writers funnel through coordinator + outbox |

---

## 5-Whys Root Cause Analysis

Methodology per [5-whys-skill/references/software-patterns.md](https://github.com/awesome-skills/5-whys-skill/blob/main/references/software-patterns.md).

### 5-Whys: AUDIT-001 + AUDIT-002 — Remote delete physically destroys financial history

**Problem:** Despite a frozen mandate (Adjudication Memo §5) and a dedicated history-only mechanism, local financial history can still be physically deleted by a remote sync event.

| Why | Question | Answer | Evidence |
|---|---|---|---|
| 1 | Why can remote deletes erase local history? | `applyAccountDelete`/`applyLedgerDelete` execute physical `DELETE FROM` + FK cascade | `RemoteSyncCoordinator.kt:406-407,600` |
| 2 | Why do these paths still exist if history must survive? | The deletion protection was implemented as a **new parallel mechanism** (`IspDisappearanceReconciler`) without removing the old delete path | IspDisappearanceReconciler added in v1.96.0; applyAccountDelete untouched |
| 3 | Why was the old path kept? | It serves multi-device convergence semantics (a user deliberately deleted on Device A expects deletion on Device B); the product rule never resolved the tension between "user-initiated cross-device delete" vs "ISP-side disappearance" | Contract §3.14 only covers ISP-side deletion |
| 4 | Why was this tension never resolved in implementation? | No executable test covers the remote-delete path — grep of test suite finds zero references to `applyAccountDelete`/`deleteByAccountId` on these events | `app/src/test/**` has no coverage for this path |
| 5 | Why is there no test for a frozen mandatory invariant? | Memo §5.1 requires executable deletion-protection evidence, but `FinancialHistoryDeletionProtectionTest` was written against a different surface (guard checks), not against the actual sync apply path | Verification gate not bound to the real code path |

- **Root Cause Category:** **Architecture Gap** — deletion policy has two competing authorities (physical delete channel vs history-only channel) instead of one unified "no production delete" rule enforced at the DAO boundary.
- **Statement:** The system added a history-preserving reconciliation path alongside the existing physical-delete path instead of eliminating physical deletion at the mutation boundary; the old path survived because no test was bound to it.

| Priority | Action | Status |
|---|---|---|
| P0 | Remove physical delete from `applyAccountDelete`/`applyLedgerDelete`; convert to `isHistoryOnlySubscriber` mark + tombstone; write executable test hitting the real sync apply path | |
| P1 | Remove `onDelete=CASCADE` via migration (table rebuild) or convert to `NO ACTION`; eliminate `deleteByAccountId` DAO method | |
| P2 | Add certification binding: grep-level check that no `DELETE FROM local_ledger_entries` exists outside dev-only/reset code paths | |

### 5-Whys: AUDIT-003 — G1 crash recovery never invoked

**Problem:** A successful ISP operation followed by a crash can silently lose the local ledger record, despite a correct recovery mechanism existing.

| Why | Question | Answer | Evidence |
|---|---|---|---|
| 1 | Why do crashed pending ops stay `PENDING` forever? | No production code calls `verifyAndResolvePendingOperation` | Grep: only ViewModel method def + tests reference it |
| 2 | Why is the recovery never called? | It was built as a library capability but no trigger was wired (no startup sweep, no WorkManager job, no SyncWorker hook) | `EarthlinkApp.onCreate`, `SyncWorker` contain no pending-op sweep |
| 3 | Why was no trigger wired? | Implementation focused on the happy path + unit-test proof of the resolver; the "who calls it" integration step was never in the phase plan checklist | CHANGELOG P3 entries prove resolver tests, not trigger wiring |
| 4 | Why did the checklist miss the trigger? | G1 gate verification lists describe the recovery *capability* ("verification reads actual ISP state") but the executable proof required ("process interruption after external success → obligation survives") cannot pass without a trigger | G1 verification requirement vs implementation artifact |
| 5 | Why did the artifact pass G8 certification with this gap? | Certification checks test-method presence/execution, not end-to-end behavioral completeness of crash-recovery wiring | G8 verifier model checks corpus, not integration closure |

- **Root Cause Category:** **Process Gap** — verification was satisfied by component-level tests instead of end-to-end behavioral proof.
- **Statement:** The recovery resolver was implemented and unit-tested in isolation; the invocation contract (when/who triggers it) was never treated as a separate requirement, so the mechanism is dead code in production.

| Priority | Action | Status |
|---|---|---|
| P0 | Wire startup/sync sweep: on app init + SyncWorker runs, query `PENDING`/`FAILED` ops and invoke `verifyAndResolvePendingOperation` | |
| P1 | Add behavioral test: simulate crash between ISP success and ledger write (kill process), restart, assert ledger materializes with same tx ID | |
| P2 | Dashboard surface for ops pending > N hours so user can trigger manual verification | |

### 5-Whys: AUDIT-004 — loanIqd silently repurposed as debt shadow

**Problem:** The contract says `loanIqd` must be preserved but not used as an independent debt state; the runtime overwrites it with running debt on every recalc.

| Why | Question | Answer | Evidence |
|---|---|---|---|
| 1 | Why does imported loan value get destroyed? | Every `applyTransaction` returns `loanIqd = newDebt` | `BalanceCalculator.kt:17,24` |
| 2 | Why was loan set equal to debt in the calculator? | The balance engine treats loan as a "shadow of debt" for legacy display compatibility — a design assumption carried from v71 | `AccountBalances` always carries loanIqd through |
| 3 | Why did this assumption survive the freeze? | The field-level classification (G6 open item) was never closed; `loanIqd` remained in recalc outputs because nothing enforced its classification | G6 status: "field-level semantic verification pending" |
| 4 | Why did the protected-fields rule not stop the mutation? | Memo §4.7 protects against *deletion/redefinition*, but the recalc code path was not audited against the protection list — protection was checked only at migration/schema level | CHANGELOG P5 describes preservation at persistence level only |
| 5 | Why was the recalc path excluded from the audit? | Audit scope was "deletion/reset/repurposing during migration," not "runtime mutation semantics" | Memo §4.7 wording vs runtime behavior |

- **Root Cause Category:** **Knowledge Gap** — the contractual semantics of `loanIqd` (preserve, never use as authority) were documented but not translated into a runtime invariant enforced in the computation engine.
- **Statement:** The balance calculator embedded a v71 assumption (loan = debt shadow) that contradicts the frozen contract; no test asserts that recalc must leave imported `loanIqd` untouched.

| Priority | Action | Status |
|---|---|---|
| P0 | Stop writing `loanIqd` in `applyTransaction`/recalc; treat as immutable-preserved field (only import writes it) | |
| P1 | Add test: import account with distinct loanIqd → run recalc → assert loanIqd unchanged while debt/advance recompute | |
| P2 | Close G6 field classification for loanIqd in authority docs | |

### 5-Whys: AUDIT-005 — Double/REAL money representation

**Problem:** Financial amounts are stored and computed as floating point, risking reconstruction drift on IQD sums.

| Why | Question | Answer | Evidence |
|---|---|---|---|
| 1 | Why can balance reconstruction drift? | All math is `Double` subtraction/addition over many rows | `BalanceCalculator.kt:10-46` |
| 2 | Why Double? | Schema stores `REAL`; entities model amounts as `Double` from v71 | `Models.kt:383-384`, `amountIqd REAL` |
| 3 | Why wasn't it migrated to integer/BigDecimal? | Migration phases prioritized identity, lineage, and deletion safety; precision was treated as "IQD is whole dinars, so Double is fine" | Migration SQL keeps REAL; no phase task covers precision |
| 4 | Why is that assumption unverified? | No test constructs a large-row reconstruction to measure drift; epsilon guards (0.0001) mask rather than detect drift | `Repositories.kt:1542,2606` epsilon checks |
| 5 | Why is there no precision invariant in the contract? | Contract §3.18 focuses on loss/duplication/corruption of history, not numeric representation fidelity | Contract wording |

- **Root Cause Category:** **Architecture Gap** — no monetary representation layer; the domain's core type (money) is a general-purpose float.
- **Statement:** Money has no dedicated type discipline; the whole pipeline (schema → entity → calculator → UI) uses Double by inheritance from v71, with epsilon comparisons standing in for exact arithmetic.

| Priority | Action | Status |
|---|---|---|
| P1 | Introduce Long-IQD (or BigDecimal) representation at calculator/schema boundary; migrate with value-preservation test | |
| P2 | Add drift test: 10k-row reconstruction must equal sum of independent oracle; fail on any epsilon adjustment | |

### 5-Whys: AUDIT-006 + AUDIT-019 — Weak credential encryption

**Problem:** ISP admin credentials stored in Firestore can be decrypted by anyone who knows the UID.

| Why | Question | Answer | Evidence |
|---|---|---|---|
| 1 | Why are cloud credentials decryptable without a secret? | Key = PBKDF2(uid, uid + static compiled salt) — all inputs public | `CloudSecretEncryptor.kt:17-28` |
| 2 | Why derive from public values? | No server-side KMS/secret exists; client-only design chose deterministic per-user derivation | No backend component in project |
| 3 | Why was this accepted? | Contract §3.12 fixes the *recovery requirement*, states "exact secure storage architecture is a technical question" — no security bar was set | Contract §3.12 wording |
| 4 | Why did G6/adjudication not set a bar? | Field classification and credential isolation address *scoping/TOCTOU*, not at-rest crypto strength | Memo §6 scope |
| 5 | Why no security threat review for credential persistence? | Release-blocking priority is financial integrity; security explicitly secondary per Contract §3.18 | Contract §2.1, §3.18 |

- **Root Cause Category:** **Policy Gap** — no minimum security standard defined for cloud-persisted secrets; "encrypted" was satisfied without threat-modeling the key.
- **Statement:** Encryption was implemented as an interface checkbox with a publicly-derivable key; the absence of a key-management policy made strong cryptography structurally impossible in a client-only app without design acknowledgement.

| Priority | Action | Status |
|---|---|---|
| P0 | Document as accepted bounded risk (like G1), OR escalate to a real secret channel (e.g., Firebase-exchange-derived key, user passphrase) | |
| P1 | At minimum: remove legacy ECB path, stop printing passphrase candidates in logs | |
| P2 | Add security section to contract defining the credential threat model | |

### 5-Whys: AUDIT-007 — Destructive tool in production

**Problem:** "Clear All Local Data" (wipes DB + Firestore) is reachable by end users in release builds.

| Why | Question | Answer | Evidence |
|---|---|---|---|
| 1 | Why is the wipe card visible in release? | It renders unconditionally in the "Developer Mode" section | `SettingsScreen.kt:753-755` |
| 2 | Why wasn't it gated like the Demo toggle? | Only the Demo switch inside the card was wrapped in `BuildConfig.DEBUG`; the card itself was assumed to be "developer" by its header label | `SettingsScreen.kt:680` gates toggle, not card |
| 3 | Why is UI labeling the gate instead of code? | Wipe legitimately exists for support/recovery scenarios (pre-restore safety), so it wasn't treated as dev-only tooling | Contract §3.15 distinguishes dev reset from business |
| 4 | Why did the distinction not reach the UI? | No build-variant policy exists mapping destructive actions to debug-only; protection relied on double-confirm dialogs | No BuildConfig policy for destructive ops |
| 5 | Why no such policy? | Contract §3.15 says tools "may remain temporarily while development continues" — no deadline or enforcement mechanism defined | Contract §3.15 wording |

- **Root Cause Category:** **Process Gap** — the "eventually removed" instruction had no enforcement (no lint/test/gate checking release builds for destructive entry points).
- **Statement:** Production-safety of destructive tools depends on UI placement assumptions and confirmation dialogs rather than build-variant gating; the contract's "temporary" clause was never converted into a checkable rule.

| Priority | Action | Status |
|---|---|---|
| P0 | Wrap the clear-data card (and any wipe call) in `BuildConfig.DEBUG`, or move behind explicit signed-in developer flag | |
| P1 | Add certification check: release APK must not contain callable clear-all path | |

### 5-Whys: AUDIT-008 + AUDIT-013 + AUDIT-017 — Version/session hygiene cluster

**Problem:** Three related defects: mixed-domain version fallback, credential TOCTOU, and unversioned remote LWW.

| Why | Question | Answer | Evidence |
|---|---|---|---|
| 1 | Why these three? | All compare/apply state without validating the domain of the comparison key or the acting identity | `RemoteSyncCoordinator.kt:170-195`, `SyncRepositoryImpl.kt:1397`, `RemoteEntityValidator.kt:116` |
| 2 | Why no domain/identity validation at apply boundaries? | Apply handlers trust their inputs: version taken as-is from event, session assumed unchanged, field values accepted raw | Handler code paths |
| 3 | Why trusted inputs? | Each was written against its own happy-path unit test (version parsing, settings sync, remote mapping) with valid inputs | Test corpus focus |
| 4 | Why no adversarial inputs? | Adversarial tests exist for ordering/identity, but not for cross-domain version injection, session switching mid-await, or missing-field resets | Test matrix coverage map |
| 5 | Why are these surfaces outside the adversarial matrix? | The matrix was built around the frozen invariants list; "input domain validity" and "session continuity during await" were never enumerated as invariants | Invariant contract scope |

- **Root Cause Category:** **Process Gap (test matrix scope)** — adversarial verification covers listed invariants but not the general class of "never trust the comparison domain or the acting session."
- **Statement:** Apply-boundary handlers lack a uniform contract: (a) version must be server-domain, (b) identity must be re-validated after every await, (c) missing remote field must never silently zero. These three rules were never codified, so each site re-implemented trust differently.

| Priority | Action | Status |
|---|---|---|
| P0 | Remove sub-1e12 fallback → treat untracked as untracked (no cross-domain comparison); re-check uid after credentials await; default-missing financial fields to existing value, not 0.0 | |
| P1 | Add three adversarial tests (cross-domain version, account-switch-during-await, missing-field reset) | |

### 5-Whys: AUDIT-010 + AUDIT-012 — Restore/import lineage bookkeeping asymmetries

**Problem:** Restore Replace correctly bumps G4 + clears pending ops; Restore Merge and replace-import do not fully mirror this.

| Why | Question | Answer | Evidence |
|---|---|---|---|
| 1 | Why the asymmetry? | Replace and Merge were implemented in separate phases with separate checklists | CHANGELOG timeline |
| 2 | Why didn't Merge inherit Replace's bookkeeping? | Merge was judged "same-lineage" by design (partial change, not full replacement) | Design decision in P3 |
| 3 | Why is that safe? | It is only safe if no in-flight async result can apply post-merge — which is exactly what the missing G4 bump fails to prevent | G4 invariant |
| 4 | Why wasn't this caught? | Merge lineage tests cover identity/baseline selection, not interaction with in-flight sync | Test names confirm scope |
| 5 | Why no cross-operation interaction tests? | Phase task decomposition treated each operation's invariants independently rather than testing pairwise race interactions | Phase plan structure |

- **Root Cause Category:** **Architecture Gap (boundary conditions)** — lineage invalidation rules are correct for full-replacement but undefined for partial-replacement (merge/replace-import), leaving races at the exactly-defined boundary.
- **Statement:** Same-lineage classification for Merge was a semantic decision without concurrency proof; G4's "old async result cannot write into newer dataset" invariant has no enforcement for the merge path.

| Priority | Action | Status |
|---|---|---|
| P0 | Decide: bump generation in merge final transaction (simplest, matches G4 spirit) OR prove same-lineage safety with a race test; tombstone orphaned outbox entries on replace-import | |
| P2 | Add merge-vs-in-flight-sync adversarial test | |

### Summary of Root Causes

| Category | Issues | Systemic Fix |
|---|---|---|
| Architecture Gap | AUDIT-001/002, 005, 010/012 | Unify deletion policy at DAO boundary; introduce money type discipline; close partial-replacement lineage rules |
| Process Gap | AUDIT-003, 007, 008/013/017 | Bind verification to end-to-end behavioral proof, not component presence; enumerate "untrusted input domain/session" as an invariant class |
| Knowledge Gap | AUDIT-004 | Translate protected-field semantics into runtime invariants + tests, not just migration rules |
| Policy Gap | AUDIT-006/019 | Define security bar for cloud-persisted secrets or formally accept bounded risk |

