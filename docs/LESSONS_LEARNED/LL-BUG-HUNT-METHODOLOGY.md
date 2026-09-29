# Lesson Learned: Bug-Hunting Methodology That Actually Eliminated False Positives

**Identifier:** `LL-BUG-HUNT-METHODOLOGY`
**Status:** Historical/operational engineering knowledge; non-authoritative practical note.
**Rounds:** 4, 5, 6, 7 (Rounds 4-7 of the 2026-09-29 session)
**Outcome:** 7 confirmed bugs, 0 false positives shipped. ~30 candidates rejected on evidence.

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

---

## 10. Result

| Round | Scope | Confirmed | Note |
|:---|:---|:---|:---|
| 3 | candidate cross-checks | **0** | all 40+ candidates false positives |
| 4 | ViewModels, `EarthlinkSearchViewModel` | 2 | first round with a real find |
| 5 | `ui/` ViewModels, `safeApiCall` | 2 | `safeApiCall` executed and then correctly dismissed |
| 6 | outbox/workers, money/PDF | 2 | real-data distributions killed ~30 candidates |
| 7 | `ui/screens/`, Settings, Import | 1 | 5 candidates → 1 after Skeptic |

**7 confirmed bugs, all proven by execution, all fixed, 793 tests green, zero false positives shipped.**
