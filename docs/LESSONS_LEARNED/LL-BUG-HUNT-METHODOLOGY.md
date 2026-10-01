# Lesson Learned: Bug-Hunting Methodology That Actually Eliminated False Positives

**Identifier:** `LL-BUG-HUNT-METHODOLOGY`
**Status:** Historical/operational engineering knowledge; non-authoritative practical note.
**Rounds:** 4-12 (Rounds 4-7 initial; extended through 12); §7.2 added by Round 13
**Outcome:** 12 confirmed bugs, 0 false positives shipped. ~127 candidates rejected on evidence.

> For the twelve defects themselves — what each one was, its RED proof, its fix, and its
> commit — see [`LL-ROUND-4-12-RESULTS`](LL-ROUND-4-12-RESULTS.md). This document covers the
> *method* only.
>
> **§7.2 covers a different subject**: Round 13 audited *the tests themselves* rather than the
> product. Read it before writing or repairing any test, not only before hunting bugs.

---

## 1. Authority Boundary & Purpose

> **Important Boundary:** This document records a *process* and a set of *rejected patterns*. It is **NOT** a replacement for current code, `AGENTS.md`, the Target Product Contract, architectural authority, or active verification. Current code and authoritative project documents remain primary.

Its purpose is to stop future agents from re-deriving the mistakes that produced three consecutive zero-yield rounds, and to give them a repeatable pipeline that produced seven genuine finds.

**Baseline for comparison:** Rounds 1-2 produced 5+5 confirmed bugs. Round 3 produced **zero**. Rounds 4-7 produced **seven**.

---

## 2. The Core Principle

> **A finding is not a production defect until the full production path is traced from source state to observable consequence, AND the triggering state is shown to occur in real data.**

This extends the Round-2 principle with a second, non-negotiable half. Round 2 correctly demanded a production path but still shipped two fixes (`BUG-38`, `BUG-39`) whose triggering conditions **cannot occur in the actual dataset**:

| Fix | Triggering state | Real data | Verdict |
|---|---|---|:---|
| BUG-38 preserve times in notes | a note containing `HH:MM` | **0 of 2,692** notes contain a time | latent only |
| BUG-39 paginate backup past 100k | >100,000 ledger rows | **2,761** total; max **69** per account | latent only |

Both fixes are *correct* and worth keeping as defence in depth. The defect was one of **labelling**: they were recorded with the same confidence as three genuinely triggered bugs. Future notes must distinguish **triggered** from **latent hardening**.

---

## 2.5 Round Sizing — Full Round vs Targeted Probe

Not every bug investigation deserves the full pipeline. The nine-round ceremony costs real time, and
running it on a single already-identified candidate is how it gets skipped entirely. Size the work by
**candidate provenance**, not by candidate count or file count.

| | **FULL ROUND** | **TARGETED PROBE** |
|---|---|:---|
| **Trigger** | Candidates **not yet identified**; hunting across subsystems | Candidate **already in hand** — a code-review concern, a named seam, a regression suspicion |
| **Pipeline (§3)** | Full: Recon → Hunter → Skeptic → Referee → RED | Branching and fan-out **not required** |
| **Separate Skeptic (§3.1)** | Required | Not required |
| **Real-data measurement (§4)** | Required before naming a lead | Not required |
| **Six gates (§3.2)** | All six | Gates 1 (reachability) and 4 (existing guard) still apply |
| **Execution** | Required | **Required — unchanged** |

A **targeted probe** must still, at minimum:

1. **Name the exact production caller.** A parameter with a `null` default is not evidence about
   production behaviour — count the real call sites.
2. **Execute a diagnostic that settles the verdict.** Argument is not a substitute.
3. **Assert preconditions**, so the probe cannot pass for the wrong reason.
4. **State triggered vs latent**, per §2.
5. **Report the verdict**, including retractions and downgrades, per §7.

> **The load-bearing line: the light tier drops the branching, never the execution.** A probe that
> may *reason* instead of *run* would have shipped both §7.1 claims as confident findings. The first
> looked mechanical and was killed only by a counting delegate (`productionDuplicateGenReads = 0`);
> the second reproduced a real intermediate state (`SKIPPED_DUPLICATE`) and was still not a defect
> because a downstream guard neutralized it — knowable only by running the recovery leg.

**Escalation.** If a probe confirms a genuine production defect, it **becomes a full-round finding**:
assign a `BUG-*` identifier, write the RED test, fix, run the full suite, and record it in the results
document. The lighter *discovery* process never licenses a lighter *record*.

---

## 3. The Pipeline

```text
Recon  →  Hunter  →  Skeptic  →  Referee  →  RED test  →  fix  →  full suite
            │           │          │
            └─ gates    └─ kill    └─ verdict
```

### 3.1 Role separation is what kills false positives

The single highest-value structural decision. Round 6 produced 5 candidates from two hunters; the Skeptic **refuted 1, downgraded 2, and materially corrected a third**:

- BUG-IMP-1's *mechanism* was right but its *trigger description* was wrong (the hunter described a dead path; the Skeptic found a narrower live one, and reframed the harm from "destructive wipe" to "false success on an authorized wipe").
- BUG-SET-2's *chain* was self-contradictory: the hunter claimed a partial ISP password reaches `clearAuthToken()`, but the consumer of that password takes the Google branch, which does **not** call `clearAuthToken()`. Harm downgraded MEDIUM → LOW.
- BUG-ACT-1: the Skeptic found a **shorter** reachable path than the Hunter reported, and explicitly refused to inflate severity.

**Rule: never let the agent that found a bug also be the agent that rules on it.**

### 3.2 The six gates (applied to every candidate)

1. **5 WHYS** — reach root cause in five steps, or drop it.
2. **Gate 1 — reachability** — name the exact production caller. Tests, `BuildConfig.DEBUG` gates, and unreachable helpers do not count.
3. **Gate 2 — observable harm** — name a concrete consequence. Presentation-only is not harm.
4. **Gate 3 — concrete divergence** — exact input, actual output, expected output.
5. **Gate 4 — constructibility + existing guard** — prove the state occurs under real invariants AND that no guard prevents it.
6. **Authority** — the expected result must come from the Target Product Contract, an invariant, or an existing intentional behaviour. Not from intuition.

### 3.3 The anti-false-positive instruction that did the work

Every hunter prompt ended with a *completeness contract*:

> A false positive costs far more than a missed bug. **An empty array is a valid, respectable outcome.**

Rounds 4-7 returned roughly 5, 2, 5, 0 findings. Two hunters correctly returned **zero** and listed nine dropped candidates each with the specific killing evidence. That behaviour is the goal, not a shortfall.

---

## 4. Real-Data Constructibility — The Discipline That Changed Yield

> **Before calling a candidate a lead, prove the triggering state exists in `.forensic-bug02/real_data.json`.**

Round 3 produced 40+ candidates and **all** were false positives. Rounds 4-7 produced 7 genuine bugs, and **every one was killed or confirmed by a real-data check first**.

Measurements that killed candidates outright (none required a code change):

| Measurement | Killed |
|---|---|
| 216/216 accounts have non-empty unique `sourceExternalId`; 0 empty | any "empty identity collision" theory |
| 0/2761 amounts are non-multiples of 250 | any "bad amount" theory |
| 0/216 have lat+lon | any "coordinate" theory |
| `loanIqd` = 0 for 216/216 | "Settled hides an outstanding loan" |
| min non-zero `debtIqd` = 15,000 | `parseUiThousandsAmount` ×1000 corruption |
| 216 distinct `account.id.hashCode()` values, 0 collisions | "notification ID collision" |
| 0 accounts with lat+lon / 0 history-only | "local prepaid fallback includes history-only" |

And the measurement that **confirmed** a real bug: **27 rows across 20 accounts carry a negative `debtAfterIqd`, totalling 995,000 IQD** → BUG-MONEY-1 (the PDF was printing "0 settled" to 20 real customers).

**Fixture-provenance rule (from the Round-3 failure):** verify the artifact is what the production path consumes. Round 3 measured "2,979 subscribers missing `source_key`" against the app's *own DB export* — a meaningless number from the wrong file.

---

## 5. Execution, Not Argument

> **When a finding's verdict turns on what a value becomes downstream, run the production path. Do not reason about it.**

Reasoning produced a confidently wrong conclusion twice in this session:
- Candidate B (Round 4): code reading suggested a cross-subscriber identity collision. A single-variable control test (A/B/C/D) **refuted** it — 2 distinct accounts, no collapse.
- BUG-SRCH-1: the Skeptic reported a wrong harm chain. Only execution showed the true failure was a mis-binding, and only a second execution showed the downstream mis-booking.

Execution also caught two of my own test-design errors: a control that set the same value on both sides (making it not a control), and a fixture whose tar header omitted the size field. Both produced false results that would have been reported had execution not been required.

**Rule: a candidate that cannot be demonstrated by a test is a hypothesis, not a finding.** State that plainly rather than shipping an unexecuted claim.

### 5.1 Proving RED honestly

The fix is only credible if the test fails **before** it. `BUG-IMP-3` was verified by `git stash`-ing the fix, confirming RED, then restoring and confirming GREEN. An earlier draft of that test also proved RED for the *wrong reason* (it exercised a path where the result flow is reassigned, so no stale card ever exists) — caught only because a precondition assertion was added rather than assuming the setup worked.

**Rule: assert preconditions.** A test that passes for the wrong reason is indistinguishable from a correct one.

### 5.2 Async determinism

`AGENTS.md` §9.3 requires deterministic concurrency. For UI races, a gateway hook invoked *inside* the suspend call is the clean oracle:

```kotlin
override suspend fun refillUserDeposit(...): Boolean {
    onRefillInFlight?.invoke(userId)   // fires exactly at the suspend point
    return delegate.refillUserDeposit(...)
}
```

For ordering between two concurrent loads, use a handshake (`while (!gateAwaited) yield()`) rather than `Thread.sleep`. An early version of the BUG-SRCH-2 test was flaky under `Dispatchers.Unconfined`; the handshake made it deterministic. **Use `Thread.sleep` only as a last resort, and never as the ordering oracle.**

---

## 6. Patterns That Repeatedly Yielded False Positives

**1. Unreachable routes.** `composable("search")`, `composable("statement")`, and `onNavigateToAccounts` have **no `navigate()` caller** at all. `SearchScreen.kt` + `StatementScreen.kt` are ~1,700 lines of dead UI. A callback that is *declared* as a parameter but never *invoked* by its parent is dead — this killed many candidates.

**2. The same guard in 3 of 4 places.** This repo is systematically defensive. Before reporting, grep for the guard: `_isFreeAdminAccount`, `isNotEmpty()`, `!= ''`, `% 250.0 == 0.0`, `isHistoryOnlySubscriber`, `DataOperationCoordinator.withOperation(...)`. The surviving `BUG-ACT-1`/`BUG-IMP-3` fixes are precisely the sites where the guard was **missing** — which is how you know they are real.

**3. R4 precedent — dead code is not a bug.** `revertTransaction`, `deriveAccountBalance`, `rollbackImportBatch`, and `OutboxManager.enqueue/clear/resetInFlight/calculateBackoffDelay` all have **zero production callers**. Correctly classified as accepted V1 debt under `AGENTS.md` §5, not defects.

**4. Already closed.** R2, R3, R5, N2, N3, R4, G1-G, DATA-02. Always read `LL-ROUND-2-CLOSED-FINDINGS.md` and `LL-ROUND-2-ERROR-SEMANTICS.md` before scanning; a subagent given that exclusion map rejected candidates the earlier rounds had already adjudicated.

**5. Adjudicated error semantics.** DATA-02 rules that a parse-failure value collapsing to a domain zero is VALID when no mutation consumes it. Do not re-report a display-only fallback.

**6. Intentional peer containers.** N2/R6 permits multiple local accounts to share a `sourceExternalId`. That is architecture, not duplication.

**7. `OnlineNoNet` classification.** `== "online"` excludes it from both buckets — this is **required** by the API documentation, and a permanent test asserts it. The `contains("online")` dot in the card is the imprecise side, and a dot colour is presentation.

---

## 7. Honest Reporting

Every round ended by stating what was **dropped and why**, including candidates the investigator had previously called strong and then had to retract. Two retractions are recorded on the record (`LL-ROUND-3-REJECTED-FINDINGS.md` §4.2, §5.3) and they were more valuable than the confirmed findings, because they document where the method fails.

Also report, plainly:
- which gate you believe is **weakest** for each finding;
- what was **not** executed;
- findings that were **downgraded** and why.

### 7.1 Third recorded retraction and downgrade — the RSC-1 review (2026-09-29)

While reviewing the BUG-RSC-1 diff (`evictCacheIfStale` in `RemoteSyncCoordinator`), two concerns
were raised in prose and then **adjudicated by execution**. Both failed. They are recorded because
both were asserted confidently before the caller set was traced, which is the exact failure this
methodology exists to catch.

**Claim 1 — "the moved `getGeneration()` read now costs an extra SQL query per duplicate event."**
**RETRACTED — false positive.** `processEvent`'s parameter is `passedCapturedGen: Long? = null`, and
the read is `passedCapturedGen ?: metadataDao.getGeneration()` — the elvis short-circuits. Both
production callers (`SyncRepositoryImpl.kt:1267` `snapshotGen`, `:1432` `capturedPassGen`) pass a
non-null generation, and `processEvents()` has **zero production callers**. A counting delegate over
the real `SyncMetadataDao` measured **`productionDuplicateGenReads = 0`** on the duplicate path
(versus `1` on the null shape, which is test-only). Cost of the reordering in production: **zero**.

**Claim 2 — "a wipe landing mid-batch skips the dedup eviction, so a re-delivered event is lost."**
**DOWNGRADED — latent, non-harmful.** The premise is **true and was reproduced**:
`capturedPassGen=1 → genAfterWipe=2 → SKIPPED_DUPLICATE, accounts=0`. The consequence is not. The
skip happens, but `SyncRepositoryImpl.kt:1316` saves the chunk cursor only when
`getGeneration() == snapshotGen`, so a mid-batch generation advance **discards the chunk's progress**.
The next pass reads a fresh generation, `evictCacheIfStale` clears the cache, and the event applies:
`recoveredPass=APPLIED, accounts=1`. Three independent layers — cache eviction, the in-transaction
re-read at `:241` (`FAILED_RETRYABLE`, no cursor advance), and the cursor-save guard at `:1316`.

**Process lessons:**

1. **A parameter with a `null` default is not evidence about production behaviour.** Count the
   production callers before costing anything. One `grep` of `processEvent(` would have killed
   Claim 1 outright; it was instead asserted and hedged.
2. **A reproduced intermediate state is not a defect.** Claim 2's `SKIPPED_DUPLICATE` is real, and
   the finding would have been wrong anyway, because the guard downstream neutralizes it. This is
   the `BUG-38`/`BUG-39` triggered-vs-latent distinction applied to concurrency.
3. **A broken check is not evidence.** In the same session, `git cat-file -e` was used to declare
   commit `ff574cc` nonexistent; the PowerShell `$?` test was invalid and the commit exists. The
   stale-value conclusion survived, the stated reason did not. Verify the verifier.

### 7.2 Testing the tests — Round 13 (2026-09-30)

Round 13 inverted this document's subject. Rounds 4–12 asked *what bugs exist in the product*; Round 13
asked *how many tests prove what they claim to prove*. Its full record is
[`LL-ROUND-13-TEST-EVIDENCE-AUDIT.md`](LL-ROUND-13-TEST-EVIDENCE-AUDIT.md) — 14 CONFIRMED fake tests,
13 repaired and 1 deleted. The seven lessons below are the generalisable ones, and each is traceable
to a measured result in that round.

**1. A green test proves its assertions pass, not that the behaviour is guarded.**
The silent-corruption barrier (`DataIntegrityReleaseGateTest`, `scripts/production_gate.sh:76`) held
**five** tests that could not observe the behaviour they named. One passed while
`LocalLedgerRepositoryImpl.deleteTransaction` was physically deleting ledger rows — the exact RED
Invariant 2 violation — and printed `BUILD SUCCESSFUL in 57s` while doing it. The other four compared
counts without establishing the operation ran, asserted a hardcoded copy of a migration's defaults,
asserted something arithmetically independent of its type claim, and transcribed a production line.
**A green barrier is not evidence that the barrier works.** Only breaking the code and watching the gate
fail — *or pass* — tells you which one you are holding.

**2. Three tiers, and the single label is dangerous.**
- **Tier A — proves nothing about the product.** The assertion is satisfied by a literal, by the
  language, or by a line transcribed from production. *Repair or delete.*
- **Tier B — the assertion is sound; the awaited state is unestablished.** One precondition line each.
- **Tier C — the rule is real but not load-bearing for the fixture.** A fixture or anchor change, never
  an added assertion.

Nine of the fourteen fell in Tier B or C. Labelling all fourteen with one phrase — *"passes without
proving what they name"* — **conflates two opposite defects and invites deleting working tests**: a
controller reading the flat list would have deleted the four Tier-B tests, which work. **Never label a
test by its symptom; classify it by which repair it needs.** Corollary for the repair phase: **adding an
assertion to a Tier-C finding produces a second blind test.**

**3. Blindness is set by which production line the fixture routes through — not by assertion count.**
The round's cleanest contrast is three tests, one production file, one `assertNull` shape: two blind and
one sound, separated **only** by which line the fixture routed through. `:237` and `:259` were
double-guarded and `:330` was not; all three look identical. A fourth pair sits ~20 lines apart in the
barrier file and splits the same way. **Count assertions to prioritise a review; determine blindness by
tracing the routed line.**

**4. Assertion count is a bad proxy for evidence.**
`Step3DurableDispatchTest.test19_refillCreatesLocalAccountWhenMissingAndMaterializesLedgerSuccessfully`
carries **12** assertion sites; `ApiErrorSemanticsRegressionTest.testApi03_dashboard_networkFailureYieldsNullUnavailable`
carries **1** and is the more valuable of the two after repair. A 12-assertion test that transcribes
production is worth less than a 1-assertion test bound to a live seam. **A file can hold 146 assertion
call sites across 23 tests and still contain a test that cannot observe what it names.**

**5. A pattern screen measured at 50% misprediction cannot triage without execution — and its errors
run in both directions.**
The P1–P3 screen predicted the wrong verdict on **5 of 10** pattern matches (**50%**). Over-accusation
is the loud, expensive direction: **five false accusations**, every one of which was a sound test that
caught its mutation — two textbook P2 shapes whose expected value *is* a framework default, three
`assertNull` shapes. **Under-accusation is the quiet one: a missed test is invisible where a false
accusation is loud.** The screen missed `testScenarioJ_counterfactualRawPayloadContainsRawJson`
**because its name reads as a control in a file that has the thing it controls** — the file really does
contain a treatment arm. That is the hardest case for any pattern reasoning about *shape* rather than
*reachability*. **The screen narrows the field; only the run decides. Never let "no pattern match"
graduate to "cleared".**

**6. Verify the verifier, including yourself. This is not a lesson about other people.**
Five instances of the same class — a self-inflicted evidence error — occurred in this one programme:
1. A PowerShell `$?` test reported a real commit as missing (§7.1, Claim 3).
2. An ad-hoc detector reported a live assertion absent, because of an operator-precedence bug in the
   detector.
3. Line numbers re-derived with hand-added `Get-Content | Select-Object -Skip` offsets came back
   wrong; re-probing with `Select-String`'s `.LineNumber` reproduced the reviewer's citations exactly.
4. `git show > file` under PowerShell wrote a **BOM**. The compile failed, **the test task never ran**,
   and a **stale JUnit XML read as `tests=3 failures=0`** — a green state that no run produced. Caught
   only by reading the `.err` log. The sibling artefact `rep1c` was a *real* green run whose XML was
   the live stale candidate, so this class of error can also be a genuine green from the wrong setup.
5. A second, later run was launched concurrently with another; `.log` said `BUILD SUCCESSFUL` while
   `.err` said `BUILD FAILED`.

**A green number from a run that did not execute is not evidence.** Three disciplines, all used in the
round and all load-bearing: **delete the target JUnit XML before every run**; **confirm the log names
the test task** (`> Task :app:testDebugUnitTest`, and zero `UP-TO-DATE` occurrences); and **read the
`.err` stream**, because Gradle prints no `N tests completed` line on success and a green `tests=N`
figure is otherwise transcription-only.

**7. A release gate is only as good as the tests guarding it, and a test can sit *inside* the gate
while being structurally incapable of detecting the violation it names.**
`production_gate.sh` runs `DataIntegrityReleaseGateTest` as the gate. One of its five named invariants
passed while physical deletion of financial history was live in production. The gate also cannot see a
wrong-*amount* contra-entry (its sibling in the suite pins the amount; the gate pins only the shape), and
a same-ID silent overwrite on a duplicate apply (the second `processEvent` return value is still
discarded). **Auditing the gate means auditing each test in it against the mutation that would break
the invariant — not counting the tests and trusting the total.**

**8. Trace a repair recipe before you write it, or expect to inherit the finding's error.**
Round 13's own findings document carried **recipes that would have produced still-blind tests**, and the
repair plan — written from that document — inherited them. Counted and recorded:
- **Two** would have produced still-blind tests: both branches of the `SubscriberMatcher` fixture recipe
  (one matches at `:89-92` and returns `Unique` before Stage 3; the other leaves `conflictingUsername`
  `true`, so the candidate stays double-guarded).
- **One** tested the precondition rather than the assertion — `:776`'s mutant suppressed every remote
  event, which breaks what the repair *adds*, not what it guards.
- **One** mutated the thing it claimed to mutate **at a no-op** (`:719` *is* the strip).
- **One** cited a stale line. Plus a **missing requirement**, not a wrong citation: running the migration
  was not sufficient, because the fixture also needed a row with `isSnapshotHistory = true` — otherwise
  the branch was unreachable even after the migration ran.

**Seven defects were inherited into the repair plan, and every one was caught by the implementer
reading the source rather than by a reviewer checking the arithmetic.** The root cause is constant:
**briefs and plans written *from a findings document* instead of *from the source*.** Two of them
surfaced twice independently — the same false claim in the plan and in the brief derived from it. The
standing countermeasure, used from Task 4 onward: **no brief asserts what a test or a sibling does; it
says what to read, and the implementer reads.**

**9. The repair plan's own method constraint was internally contradictory, and the outcome supersedes
it.** Global Constraint #1 demanded a mutant *"faithful to the test's own name"* that leaves the test
green. **Unsatisfiable by construction for a Tier-B finding**, because Tier B means the assertion is
already sound — so every name-faithful mutant turns it red. **The working rule: for a Tier-B repair, the
faithful mutant is the one that breaks the awaited state.** Two consequences worth keeping: a Tier-B
repair may need **two** mutants (the name-faithful one proves the assertion was already live; the
awaited-state one proves the defect), and **the distinction between them is the result** — one tests the
assertion, the other tests the binding.

---

## 8. Severities Assigned, With the Harm Named

| Bug | Severity | Harm | Triggered by real data? |
|---|:---|:---|
| BUG-SRCH-1 | HIGH | cross-subscriber charge mis-booked onto the wrong ledger | yes (unbound imported accounts are the default) |
| BUG-IMP-1 | HIGH | irreversible replace-import reported as success; rollback unavailable | yes (2,761-row dataset destroyed) |
| BUG-MONEY-1 | MEDIUM | 20 real customers told "settled" while holding 995,000 IQD credit | **yes — 27 real rows** |
| BUG-OB-1 | MEDIUM | expiry alerts silently dead on Android 13+ with false "notified" log | yes (targetSdk 36, permission never requested) |
| BUG-SRCH-2 | MEDIUM | confirm dialog names subscriber A while Extend targets B | yes (ISP latency) |
| BUG-ACT-1 | MEDIUM | stale banner shows a previous subscriber's live password beside a red error | yes (Activity-scoped VM) |
| BUG-IMP-3 | LOW | stale green "Import Completed" card beside a failure banner | yes (null-stream path) |

Note the distribution: **the two HIGH-severity bugs both destroy or mis-route money**; nothing high-severity was found in pure display code. That is consistent with the product's risk profile, and it is a useful prior for future rounds — lead with ledger and dispatch paths, not with UI.

---

## 9. Operational Checklist for the Next Round

1. Read the closed-findings registry **first**; pass the exclusion map into every hunter prompt.
2. Triage by risk; prefer `core/` and `data/` over `ui/`.
3. State real-data measurements **before** naming a candidate.
4. Six gates per candidate; 5 WHYS to root cause.
5. **Skeptic is a separate agent** and must try to kill, not to confirm.
6. Only a hunter with a measured real-data divergence may report.
7. **RED test must fail before the fix** (`git stash` to prove it).
8. Assert preconditions so a test cannot pass for the wrong reason.
9. Deterministic concurrency: hooks inside suspend points and `yield()` handshakes.
10. Full suite after every fix.
11. Report dropped candidates and weakest gates as prominently as the findings.
12. Distinguish **triggered** from **latent hardening** in the write-up.
13. **When auditing or writing a test** (§7.2): delete the target JUnit XML before every run, confirm
    the log names the test task, read the `.err` stream, and never treat "no pattern match" as a
    clearance.
14. **When repairing a test** (§7.2): classify it Tier A / B / C first, trace the recipe at source
    before writing it, and never delete a test that is the only record of an intent for an unguarded
    production line.

---

## 10. Result

| Round | Scope | Confirmed | Note |
|:---|:---|:---|:---|
| 3 | candidate cross-checks | **0** | all 40+ candidates false positives |
| 4 | ViewModels, `EarthlinkSearchViewModel` | 2 | first round with a real find |
| 5 | `ui/` ViewModels, `safeApiCall` | 2 | `safeApiCall` executed and then correctly dismissed |
| 6 | outbox/workers, money/PDF | 2 | real-data distributions killed ~30 candidates |
| 7 | `ui/screens/`, Settings, Import | 1 | 5 candidates → 1 after Skeptic |
| 8 | `Repositories`, auth/network | 1 | cross-account credential leak |
| 9 | `RemoteSyncCoordinator`, `SyncRepositoryImpl` | 2 | dedup cache outlived a wipe; audit sink unwired |
| 10 | `AppDatabase`, `BackupManager` | 1 | safety snapshot quota-pruned |
| 11 | `OutboxManager`, `Models`, `Interfaces` | 0 | backoff math recomputed and proven sound |
| 12 | remaining 19 files | 0 | mutual exclusion proven by execution |
| — | GATE-INTEGRITY-01 | 1 | the meta-gate tested a copy of the rule, not the rule |

**12 confirmed bugs, all proven by execution, all fixed, zero false positives shipped.**
Rounds 5, 11 and 12 returned **zero**, and that was the correct result each time.
