# Lesson Learned: Consolidated Forensic Results — Rounds 4 through 12

**Identifier:** `LL-ROUND-4-12-RESULTS`
**Status:** Historical/operational engineering knowledge; non-authoritative practical note.
**Baseline at start of Round 4:** `c648a1e`
**Final commit covered:** `5676827`
**Outcome:** **12 confirmed bugs**, all reproduced by execution, all fixed, all pushed.
**False positives shipped:** **0**.

---

## 1. Authority Boundary

> This document records *results and method*, not live truth. Current code, `AGENTS.md`, the Target Product Contract, and the architectural authorities remain primary. If code has changed since these commits, re-verify rather than trusting this note.

Companion documents: `LL-BUG-HUNT-METHODOLOGY.md` (the pipeline and gates), `LL-ROUND-2-CLOSED-FINDINGS.md` and `LL-ROUND-3-REJECTED-FINDINGS.md` (prior rounds and retractions).

---

## 2. Result Summary

| Round | Scope | Confirmed | Note |
|:---|:---|:---|:---|
| 3 | candidate cross-checks | **0** | 40+ candidates, all false positives; this round produced the method that fixed that |
| 4 | `EarthlinkSearchViewModel` | 2 | cross-subscriber identity bind; stale selection |
| 5 | `ui/` ViewModels, `safeApiCall` | 0 | `safeApiCall` executed then correctly dismissed |
| 6 | outbox/workers, money/PDF | 2 | PDF hid a real credit; expiry alerts dead |
| 7 | `ui/screens/`, Settings, Import | 1 | empty replace-import reported as success |
| 8 | `Repositories`, auth/network | 1 | cross-account ISP credential leak |
| 9 | `RemoteSyncCoordinator`, `SyncRepositoryImpl` | 2 | dedup cache outlived a wipe; audit sink unwired |
| 10 | `AppDatabase`, `BackupManager` | 1 | safety snapshot quota-pruned |
| 11 | `OutboxManager`, `Models`, `Interfaces` | 0 | backoff math recomputed and proven sound |
| 12 | remaining 19 files | 0 | mutual exclusion proven by execution |
| — | GATE-INTEGRITY-01 | 1 | the meta-gate tested a copy of the rule, not the rule |
| **Total** | | **12** | |

Rounds 5, 11 and 12 returned **zero**, and that was the correct result each time, not a shortfall.

---

## 3. The Twelve Confirmed Defects

Every entry was demonstrated by a test that **failed before the fix and passes after**, most via `git stash` of the production change.

### Round 4 — `EarthlinkSearchViewModel.kt`

**BUG-SRCH-1 (High) — cross-subscriber identity bind.** `refillUser` re-read the Activity-scoped `_selectedUser` *after* the gateway suspend and wrote that index onto `effectiveAcc.id`. The sibling read at line 817 guards with `userID.equals(userId)`; line 888 did not. `bindIspIdentity` only rejects an *already-bound* account, and unbound is the default for every imported account. `findActiveAccountsBySubscriberIdentity` matches `ispUserIndex` first, so subscriber B's next charge would book onto subscriber A's ledger.
*RED:* `acc_sub_a.ispUserIndex = 2002` when the refill was dispatched for `sub_a` (index 1001).
*Fix:* reuse the identity captured before the suspend. One line.

**BUG-SRCH-2 (Medium) — stale selection.** `loadUserDetail` assigned the fetched detail unconditionally, so a slower earlier request resurrected an abandoned subscriber. Fixed with a `stillSelected` guard.

### Round 6 — PDF and notifications

**BUG-MONEY-1 (Medium) — PDF hid a real credit.** The statement folded every `debtAfterIqd <= 0.0` into `"0 IQD (settled)"`, while the app itself encodes a negative `debtAfterIqd` as an advance.
*Measured:* 27 ledger rows across 20 real active accounts, **995,000 IQD**, told they were settled.
*Fix:* `balanceTextFor()` renders a negative as a visible credit. Red proved exactly one failure (the negative case) with zero and positive still passing.

**BUG-OB-1 (Medium) — expiry alerts dead, delivery faked.** `POST_NOTIFICATIONS` was declared with `targetSdk 36` but **never requested anywhere in `app/src/main`**; on Android 13+ `notify()` is a silent no-op. The only catch was `SecurityException`, which this path never raises. Worse, the throttle marker was written in the *caller*, outside the guarded region, so the app logged "Notified expiry" for an alert nobody saw and suppressed the account for 24h.
*Fix:* request once per app data lifetime via AndroidX (no new dependency); `postNotification` now returns actual delivery.

### Round 7 — import

**BUG-IMP-1 (High) — empty replace-import reported as success.** A `shouldReplace=true` import commits an irreversible wipe. If the container produced nothing, the dataset was destroyed and the result still returned `success=true` with every counter at zero.
*RED:* `success=true found=0 imported=0 accountsAfter=0 ledgersAfter=0 error=null`.
*Note:* the operator *did* authorize the wipe by confirming "Wipe & Replace", so deleting is permitted — the defect is the false success. It is also irreversible in practice: the batch is stamped `completed`, which hides the rollback button and makes `rollbackImportBatch` reject it.
*Fix:* report `success=false` with an explanatory message when nothing was produced.

**BUG-ACT-1 (Medium) — stale success banner carrying a live password.** `_actionSuccess` was never reset at the start of an attempt, only `_error`. A green "created successfully / Password: ..." sat above a red error for a different username. The success string is the only place the generated password is ever shown.
*Fix:* clear it alongside `_error` in both create paths. The same guard already existed on four other destinations.

**BUG-IMP-3 (Low) — stale import card.** Same class in `LocalAccountsViewModel`. The surviving path is narrow: a normal failure *replaces* the result, so only the `inputStream == null` and `catch` branches leak it. Fix proven RED then GREEN.

### Round 8 — credentials

**BUG-NET-1 (High) — cross-account ISP credential leak.** A Firebase identity boundary is not a credential-scope boundary. After a 401 and a re-sign-in as account B, account A's ISP admin pair was still resident, and `syncUserSettings` would upload it into `users/{uidB}` — encrypted under the new uid, so B could read A's ISP password. The clearing guard existed in 2 of 4 sites; the implicit session-loss route had none.
*RED:* `ispUser='reseller_A_isp'`, password present, `token='google_oauth_session_uidB'`.
*Fix:* bind credentials to an owning session token. Comparing against the *previous* token was insufficient — the 401 path clears it before re-auth, so the switch is invisible that way.

### Round 9 — sync

**BUG-RSC-1 (High) — dedup cache outlived the data.** The in-memory LRU had no lineage-generation component, so it survived every dataset wipe; `clearCache()` was called only by `BackupManager` and `UtowerImporter`, never by `signOut(clearData = true)`, and `syncRepository` is an Application-scoped singleton. A re-delivered event was skipped, and `SKIPPED_DUPLICATE.canAdvanceCursor() == true` advanced the cursor past it.
*RED:* `first=APPLIED`, then after `clearAllData()` the re-delivery returned `SKIPPED_DUPLICATE`, `accountsAfterSecondDelivery=0`.
*Fix:* generation-scoped the cache using the existing `g4_local_generation` counter, which `clearAllData()` already increments — covering logout, restore and replace-import at once rather than patching one call site.

**BUG-SRI-1 (Medium) — audit sink unwired.** `SyncRepositoryImpl` holds a working `auditDao` and uses it itself, but omitted it when constructing the `RemoteSyncCoordinator`, leaving it `null`. Nine quarantine / identity-collision / unrecognized-type records were discarded in the shipping app while tests that pass the DAO explicitly still asserted them. Found independently by two hunters.
*Fix:* pass the DAO. RED proved 2 failures without it, 0 with.

### Round 10 — backup

**BUG-BK-2 (High) — safety snapshot quota-pruned.** The retention prune selected every `*.zip` with no notion of artifact class, so `pre_restore_backup_*` competed for the same 30-file quota and was evicted by age.
*RED:* `created=true zipsBefore=41 zipsAfter=30 preRestoreSurvives=false`.
*Authority:* TQ-12 — "easy recovery to the pre-restore backup"; "Restore Replace cannot silently destroy the pre-restore dataset".
*Note:* the first test attempt **passed vacuously** because `createDailyRollingBackup` failed before the prune. A precondition assertion was added so the test cannot pass without proving the prune ran.

### Gate integrity

**GATE-INTEGRITY-01 (High) — the meta-gate tested a copy of the rule.** `GOV-04` built a rule dict inline and asserted it against a hardcoded ID set it had also defined inline, printing "System successfully detects and rejects regex downgrade" while the real scanner accepted a schema-valid downgrade of a mandatory `behavioral_fixture` into a regex that never matches.
*Proof:* four gates all reported "100% FAIL-CLOSED" with both INV-06 rules fully disabled.
*Fix:* the policy lives in `contract/invariant_contract.yaml` — deliberately **not** in the registry, so a downgrade cannot delete the check that forbids it. `validate_registry` rejects a mismatched check type. GOV-04 now drives the real scanner against a copy of the real registry, and reads the rule IDs from the policy rather than hardcoding them.

---

## 4. Severity Distribution

| Severity | Count | Defects |
|:---|:---|:---|
| High | 6 | BUG-SRCH-1, BUG-IMP-1, BUG-NET-1, BUG-RSC-1, BUG-BK-2, GATE-INTEGRITY-01 |
| Medium | 5 | BUG-SRCH-2, BUG-MONEY-1, BUG-OB-1, BUG-ACT-1, BUG-SRI-1 |
| Low | 1 | BUG-IMP-3 |

**Every High-severity defect destroys or mis-routes money, credentials, or the guarantee of verification.** Nothing High was found in pure display code. That is a useful prior for future rounds: lead with ledger, dispatch, sync and credential paths, not with UI.

---

## 5. Rejected for Lack of Authority (Not Fixed)

Recorded because the reasoning is reusable, and because "it is technically questionable" is not a licence to change code.

**Unencrypted pre-restore safety backup.** Real: the artifact is plaintext SQLite (`SQLite format 3\0`) while exports support AES-GCM/PBKDF2 and the live database is SQLCipher. Not fixed because Target Product Contract line 357 states the exact secure-storage and encryption architecture is *"a technical question"* rather than a fixed requirement. There is no authority for the expected result, so the candidate is UNPROVEN — and it is a **product decision**, explicitly confirmed as such.

**Schema-valid downgrade** was a *confirmed* defect only because TQ-12 gave an authority. Without that clause, the same class of "the check is weak" argument would have produced a speculative rewrite.

---

## 6. Real-Data Measurements That Settled Rounds

The single highest-yield discipline: measure before naming a candidate.

| Measurement | Killed |
|:---|:---|
| 216/216 accounts non-empty unique `sourceExternalId` | every identity-collision theory (rounds 3, 9, 10) |
| 0/2761 amounts non-multiples of 250 | every "bad amount" theory |
| 0/216 divergent balances under full replay | the entire "derived cache corrupted" family |
| 0 active pending operations; `attemptCount ≡ 0` | 13 outbox retry/backoff/orphan candidates |
| 0 `tombstone:*` rows; 0 `remote_version > cursor` | `applyAccountDelete` wedge; `IGNORE_STALE_REMOTE` |
| 0/216 duplicate identities; N2/R6 permits sharing | the account-upsert collision branch |
| **27 rows / 20 accounts with negative `debtAfterIqd`** | — this one **confirmed** BUG-MONEY-1 |
| SQLite treats NULLs as distinct (executed) | the unique-index/NULL collision theory |

The same measurement both **confirmed** a bug (round 6) and **killed 13 candidates** (round 12). The data is the judge, not the code.

---

## 7. False-Positive Patterns That Recurred

1. **Unreachable routes.** `composable("search")`, `composable("statement")` and `onNavigateToAccounts` have no `navigate()` caller at all — roughly 1,700 lines of dead UI. A callback declared as a parameter but never invoked by its parent is dead.
2. **The same guard in 3 of 4 places.** The repo is systematically defensive. Before reporting, grep for the guard. Every *surviving* fix is precisely a site where it was **missing** — which is also how you know they are real.
3. **Dead code is not a bug.** `revertTransaction`, `deriveAccountBalance`, `rollbackImportBatch`, `OutboxManager.enqueue/clear/resetInFlight` — zero production callers, accepted V1 debt under `AGENTS.md` §5, not to be "repaired".
4. **Already adjudicated.** R2, R3, R5, N2, N3, R4, G1-G, DATA-02.
5. **N2/R6 peer containers.** Multiple local accounts may share a `sourceExternalId` **by design**.
6. **Presentation-only.** `OnlineNoNet` excluding the card from both buckets is *required* by the API documentation and asserted by a permanent test.
7. **Mechanism without constructibility.** The 16-account `searchAccounts(limit=200)` page is real and measured (2,140,000 IQD unreachable) but unreachable: all three navigation sites supply `userId`, and 0/216 usernames contain a URI-hostile character. **UNPROVEN, not fixed.**

---

## 8. Method Failures Worth Remembering

Three process defects occurred, all caught by re-verification rather than initial review:

1. **A candidate reported as "strong" before Gate 1.** Plausible Kotlin semantics, genuine code asymmetry, wrong conclusion — the import branch that real data selects was never established.
2. **A wrong correction.** Rejecting it as "dead code" was itself wrong: a JSON-only archive is a deliberately supported format, and the test that supposedly refuted it proved a *different* thing.
3. **A test that passed for the wrong reason.** `BUG-BK-2`'s first version passed because the backup failed before the prune. Two other tests passed vacuously for the same class of reason. **A precondition assertion is not optional.**

Also recorded: flag-based oracles in concurrency tests produced false results twice. Recorded-order oracles (`[parent_in, parent_out, child_in]`) are the reliable form.

---

## 9. Verification Standard Used

```
Claim:            exact behaviour or invariant asserted
Evidence:         a test observed FAILING before the fix and PASSING after
Verification:     full unit suite, 798 tests / 0 failures / 0 errors across 113 suites
                  (35 tasks executed on a forced --rerun-tasks, not cached)
                  plus 16 certification suites, 180 tests / 0 failures
Result:           PASS
What this proves: the specific defect, and that the fix does not regress the suite
What it does NOT prove: live Firestore behaviour, WorkManager scheduling, SQLCipher
                  key handling, or real multi-device convergence — none were executed
```

Two honesty notes from the record: an earlier `--rerun-tasks` run failed with a **test-executor JVM crash** (connection reset), which was investigated, found to be a transient executor failure, and passed on both re-runs. `production_gate.sh` itself **could not be executed** on the Windows host — `bash` resolves to the WSL stub with no distribution — so every gate step was run individually through the repository's own `run_verified_command.py` wrapper instead. That limitation was stated rather than glossed.

---

## 10. Final State

```
Commits (Round 4-12):  06fd128, 88f509f, 6821de3, d3ee77c, b76dc01, 0edc7c5,
                       bd176b1, 8bfb3a1, cac7a61, 6a51be6, 45e45ab, 5676827
Regression tests added: 16 files
Unit suite:             798 tests, 0 failures
Coverage:               61/61 source files audited (100% by file and by line)
False positives shipped: 0
```

Coverage detail is tracked outside the repository, because it is session state rather than project truth:

```
%TEMP%\opencode\COVERAGE-TRACKER.tsv
```
