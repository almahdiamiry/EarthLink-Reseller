# Test-Suite Evidence Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove that all 798 tests in the suite are real evidence, and eliminate every test that passes without proving its own claim.

**Architecture:** Two phases. Phase A builds a static scanner plus a self-test that proves the scanner works against the real rules (the GATE-INTEGRITY-01 lesson: a gate that tests a copy of the rule is worthless). Phase B adjudicates candidates by **execution only** — reading can refute a candidate but can never confirm one. Confirmed fake tests are then fixed or deleted in their own task.

**Tech Stack:** Python 3 (scanner, matching `scripts/scan_forbidden_patterns.py`), Kotlin + JUnit4 + Robolectric + Mockito (audit and fixes), Gradle `:app:testDebugUnitTest`.

**Spec:** This plan is self-contained. The governing rubric is `docs/LESSONS_LEARNED/LL-BUG-HUNT-METHODOLOGY.md` §2 (triggered vs latent), §5 (execution not argument), §5.1 (assert preconditions), §2.5 (round sizing), §7 (honest reporting). The governance contract is `AGENTS.md` §9.2, §9.6, §12.1.

## Global Constraints

These apply to every task. Read them before starting.

- **Execution confirms; reading only refutes.** No candidate may be reported as `CONFIRMED` from code reading alone. This rule exists because two claims made during the Round 9-12 review were asserted from reading and both were wrong (recorded in `LL-BUG-HUNT-METHODOLOGY` §7.1).
- **Verify the verifier.** Any check that is itself a finding must be proven against a known-good and a known-bad input before its output is trusted. A broken scanner is worse than no scanner.
- **Never weaken an assertion to force green** (`AGENTS.md` §9.6). If a test asserts the wrong thing, fix the assertion's target or delete the test. Loosening it to pass is forbidden.
- **Every candidate resolves.** Each candidate ends `CONFIRMED` or `REFUTED`. Leaving a candidate open is not an outcome.
- **Every `CONFIRMED` fix is TDD.** A replacement test must be shown to FAIL before the production fix and PASS after (`git stash` the fix to prove RED).
- **No new dependencies.** Standard library only. No production code changes unless a `CONFIRMED` finding requires it.
- **The suite must be green at every commit.** Baseline is 798 passing. It may only go up, never down.
- **Commit once per task**, with the message format already used in this repo (`fix(scope): imperative summary`).
- **If you find yourself editing production code for any reason other than a `CONFIRMED` test finding, stop** and report. That is a scope violation.

---

### Task 1: Build the static evidence scanner, and prove the scanner works

**Files:**
- Create: `scripts/scan_test_evidence.py`
- Create: `scripts/test_evidence_scanner_fixtures.py`
- Reference (do not modify): `scripts/scan_forbidden_patterns.py`

**Interfaces:**
- Consumes: nothing.
- Produces: `scan_test_evidence.scan_file(path) -> list[Finding]`, where `Finding` has fields `rule: str` (one of `F1`..`F7`), `line: int` (1-indexed), `test_name: str`, `detail: str`. And `scan_test_evidence.scan_tree(root) -> list[Finding]`, sorted by `(path, line)`.

This is the tool every later task depends on. It must be correct before Task 2 begins, so the self-test is not optional.

The seven rules, each detectable statically:

| Rule | Name | Static test |
|:---|:---|:---|
| `F1` | Vacuous | A `@Test` body containing no `assert*`, `fail(`, or `verify*` call |
| `F2` | Tautological | An assertion comparing an expression to itself, e.g. `assertTrue(x)` where `x` is a literal, or `assertEquals(a, a)` |
| `F3` | Circular | The expected value of an `assertEquals` is assigned from a call into the class under test rather than a literal |
| `F4` | No precondition | A test that waits on a `while (... attempts < N)` poll loop and then asserts without first asserting that the awaited state was actually reached |
| `F5` | No RED proof | Not statically detectable. Execution-only; handled in Task 4 |
| `F6` | Mock-only | A test whose only assertions are Mockito `verify*` / `verifyNoInteractions*` |
| `F7` | Vacuous path | A test that asserts on a value that is reassigned or never observed on the exercised path |

- [ ] **Step 1: Write the failing self-test first**

Create `scripts/test_evidence_scanner_fixtures.py`. It must build fixture files on disk in a temp directory and assert the scanner classifies each correctly. Include at minimum one **known-bad** and one **known-good** fixture per rule, plus these two regressions that this session's own buggy detector produced:

- A test whose `@Suppress` annotation sits between `@Test` and `fun` — the body must still be scanned (an earlier ad-hoc detector broke here by stopping at the `fun` signature line).
- A test using `verifyNoInteractions` instead of a JUnit `assert*` — it must be counted as having an assertion, not reported as `F1`.

The meta-gate precedent applies: the fixture test must import the real `scan_test_evidence` module and call its real `scan_file`, never a re-implementation of the rule. A test of a copy of the rule is the exact defect recorded as `GATE-INTEGRITY-01`.

- [ ] **Step 2: Run it and watch it fail**

Run: `python scripts/test_evidence_scanner_fixtures.py`
Expected: FAIL with a module-not-found error for `scan_test_evidence`.

- [ ] **Step 3: Write the scanner**

Implement `scan_test_evidence.py` with the two public functions. Rules `F1` and `F2` are the required minimum for this task; implement `F3`, `F4`, `F6`, `F7` as the same per-test block scan. `F5` must be reported by the scanner as always-false so the rule set stays complete, with a comment stating it is execution-only.

When locating a test's body, walk from each `@Test` to the **next** `@Test` (or end of file) and scan that range. Do not stop at the `fun` signature. This direction of error is deliberate: it can under-report a rule but can never invent one.

- [ ] **Step 4: Run the self-test and watch it pass**

Run: `python scripts/test_evidence_scanner_fixtures.py`
Expected: PASS, all fixtures classified correctly.

- [ ] **Step 5: Run the scanner against the real suite**

Run: `python scripts/scan_test_evidence.py app/src/test`
Expected: exit 0, and a per-rule count. Record the counts; Task 2 uses them.

- [ ] **Step 6: Commit**

```bash
git add scripts/scan_test_evidence.py scripts/test_evidence_scanner_fixtures.py
git commit -m "test(audit): add a static test-evidence scanner with a self-test"
```

---

### Task 2: Adjudicate the `F1` candidates by execution

**Files:**
- Read: the files reported under `F1` by Task 1 Step 5
- Create: `docs/LESSONS_LEARNED/LL-ROUND-13-TEST-EVIDENCE-AUDIT.md`

**Interfaces:**
- Consumes: `scan_test_evidence.scan_tree` from Task 1.
- Produces: the audit results document, and for each candidate a verdict of `CONFIRMED` or `REFUTED` with the evidence that produced it.

Grounding already established, to be confirmed rather than assumed:

- A scan at `@Test` granularity found **798** tests, **4,248** JUnit assertion calls, and a ratio of **5.32** assertions per test.
- One test was reported with no JUnit assertion: `app/src/test/java/com/example/ui/viewmodels/LocalAccountsViewModelTgzSyncTriggerTest.kt:196`, `testFailedTgzImport_corruptArchive_doesNotTriggerSync`. It is **not** automatically `F1`: it calls `verifyNoInteractions(mockSyncRepo)` at line 213, so it has one Mockito assertion and may trip `F6` instead.
- The same test carries a concrete `F4` mechanism. Its wait loop at lines 207-210 exits after 50 attempts if neither `importResult.value` nor `error.value` becomes non-null, and the test then calls `verifyNoInteractions` anyway. If `importTgzFile` did nothing at all, the test would still pass. Its sibling test at line 219 avoids this by asserting `assertNotNull(viewModel.error.value)` first, proving the code actually ran.

- [ ] **Step 1: Run the scanner and list the `F1` candidates**

Run: `python scripts/scan_test_evidence.py app/src/test`
Expected: the `F1` list. If the list is empty, say so and continue to the `F6` list; an empty list is a valid result.

- [ ] **Step 2: For each `F1` candidate, read the full test and classify**

For each candidate determine which of these it is, and record which:

- Genuinely vacuous — passes with no verification whatsoever.
- Mockito-only — has a real assertion, but it is `F6`, not `F1`.
- Scanner error — the body lies outside the scanned range. Fix the scanner, not the test, and re-run Task 1 Step 4.

- [ ] **Step 3: For the `F4` mechanism at `LocalAccountsViewModelTgzSyncTriggerTest.kt:196`, prove it by execution**

Write a throwaway diagnostic that calls `importTgzFile` with a stub that does nothing, then asserts the existing test still passes. The proof that the test is fake is that it passes with the code under test disabled.

Simplest decisive form: temporarily stub the viewModel method to a no-op, run the single test, observe it still green, then restore. Use `git stash` to guarantee the restore.

Run: `gradlew.bat :app:testDebugUnitTest --tests "*LocalAccountsViewModelTgzSyncTriggerTest*" --console=plain`
Expected with the stub: BUILD SUCCESSFUL. That is the proof.

Delete the diagnostic when the verdict is recorded.

- [ ] **Step 4: Record verdicts in the results document**

Create `docs/LESSONS_LEARNED/LL-ROUND-13-TEST-EVIDENCE-AUDIT.md` with one section per candidate: the rule, the file and line, the verdict, and the evidence. A `REFUTED` candidate records the killing evidence, which is the more valuable half.

- [ ] **Step 5: Commit**

```bash
git add docs/LESSONS_LEARNED/LL-ROUND-13-TEST-EVIDENCE-AUDIT.md
git commit -m "docs(audit): adjudicate the F1 and F4 evidence candidates"
```

---

### Task 3: Adjudicate the 69 single-assertion tests

**Files:**
- Read: the `F1`/`F6`/`F7` output from Task 1 plus the single-assertion test list
- Modify: `docs/LESSONS_LEARNED/LL-ROUND-13-TEST-EVIDENCE-AUDIT.md`

**Interfaces:**
- Consumes: the scanner and the results document from Tasks 1-2.
- Produces: verdicts for the single-assertion cohort, appended to the same results document.

A single assertion is not automatically fake. It is a review priority, and the reason is `F5`: a lone assertion is easily satisfied by setup rather than by the behaviour under test.

- [ ] **Step 1: List the single-assertion tests**

Run: `python scripts/scan_test_evidence.py app/src/test --rule single-assertion`
Expected: the 69 tests identified during planning.

- [ ] **Step 2: Rank by risk**

Financial and ledger tests first: any file whose name contains `Ledger`, `Balance`, `Dispatch`, `Atomicity`, `Money`, `Convergence`, `Lineage`, `ReleaseGate`, or `Correction`. These protect RED invariants, so a fake assertion among them is the most expensive possible finding.

- [ ] **Step 3: Adjudicate the top 15 by risk, one at a time**

For each, apply the `F5` test: does the assertion actually depend on the production behaviour, or would it also hold if that behaviour were removed? Reading gives a suspicion. Only a mutation gives the answer.

For the top 5, prove it: revert or stub the specific production behaviour the test names, re-run that single test, and record whether it goes red.

Run: `gradlew.bat :app:testDebugUnitTest --tests "*<TestClass>" --console=plain`

- [ ] **Step 4: Record verdicts**

Append to the results document. State for each whether the test still passes with the behaviour removed. A test that still passes is `F5 CONFIRMED`.

- [ ] **Step 5: Commit**

```bash
git add docs/LESSONS_LEARNED/LL-ROUND-13-TEST-EVIDENCE-AUDIT.md
git commit -m "docs(audit): adjudicate the single-assertion cohort by mutation"
```

---

### Task 4: Mutation-probe the release gate and the financial core

**Files:**
- Read: `app/src/test/java/com/example/DataIntegrityReleaseGateTest.kt` (1,508 lines)
- Read: `app/src/test/java/com/example/Step3DurableDispatchTest.kt` (1,352 lines)
- Read: `app/src/test/java/com/example/ui/viewmodels/EarthlinkSearchViewModelSeamTest.kt` (1,229 lines)
- Modify: `docs/LESSONS_LEARNED/LL-ROUND-13-TEST-EVIDENCE-AUDIT.md`

**Interfaces:**
- Consumes: the results document from Tasks 2-3.
- Produces: mutation results for the three highest-value test files in the suite.

`DataIntegrityReleaseGateTest` is the named silent-corruption barrier, and it is the largest file in the suite. A fake assertion here is the single most expensive finding available, because it is the test everyone trusts.

- [ ] **Step 1: Enumerate the invariant claims in the release gate**

For each `@Test` in the file, write one line: which `INV-*` invariant or business clause it claims to protect. A test whose claim cannot be written down is `F3`, a circular assertion.

- [ ] **Step 2: Mutate one invariant at a time and record which tests notice**

For each claim, break the production behaviour it names by the smallest possible change, then run the release gate alone.

Run: `gradlew.bat :app:testDebugUnitTest --tests "*DataIntegrityReleaseGateTest*" --console=plain`
Expected: FAIL for a real barrier.

Restore with `git stash` after every mutation and confirm the restore before the next one. A mutation left in place invalidates every result after it.

- [ ] **Step 3: Report the mutation score**

For each claim: caught or not caught. A claim that is not caught is either `F5` (the test does not protect it) or a genuinely untested invariant, which is itself a finding and must be written up as a coverage gap, not as a fake test.

- [ ] **Step 4: Repeat for the other two files**

Same procedure against `Step3DurableDispatchTest` and `EarthlinkSearchViewModelSeamTest`, narrowed to their top 5 claims each.

- [ ] **Step 5: Record and commit**

```bash
git add docs/LESSONS_LEARNED/LL-ROUND-13-TEST-EVIDENCE-AUDIT.md
git commit -m "docs(audit): mutation-probe the release gate and financial core"
```

---

### Task 5: Fix every `CONFIRMED` fake test

**Files:**
- Modify: only the test files named as `CONFIRMED` in the results document
- Modify: production files, only where a corrected test legitimately exposes a real product defect

**Interfaces:**
- Consumes: the `CONFIRMED` list from Tasks 2-4.
- Produces: tests that fail when the behaviour they name is broken.

For each confirmed finding there are exactly two correct outcomes, and a third that is forbidden:

- **Repair** — the test is worth keeping, so rewrite it to assert the real claim.
- **Delete** — the claim is not worth a test, so remove it. A deleted fake test is a net gain: the suite gets more honest.
- **Forbidden** — loosening the assertion until it passes.

- [ ] **Step 1: For the `F4` finding at `LocalAccountsViewModelTgzSyncTriggerTest.kt:196`**

Add the precondition its sibling already has: after the wait loop, assert the import actually reported an error before asserting no sync occurred.

```kotlin
assertNotNull(
    "The corrupt import must report an error, otherwise this test proves nothing",
    viewModel.error.value
)
```

This makes the existing `verifyNoInteractions` meaningful, because it now runs only after proving the code executed.

- [ ] **Step 2: Prove the repaired test is RED against the bug it claims to catch**

`git stash` the precondition, confirm the test fails for the right reason, restore. Record which reason.

- [ ] **Step 3: Work the remaining `CONFIRMED` list**

Repair or delete each one, TDD-style. Where a corrected test exposes a genuine product defect in production code, fix the production code and give it a proper `BUG-*` identifier and a RED test.

- [ ] **Step 4: Run the full suite**

Run: `gradlew.bat :app:testDebugUnitTest --console=plain`
Expected: 0 failures, and a total at or above 798. If the total is lower, you deleted tests; that is permitted only where the claim was not worth a test, and each deletion must be listed in the results document.

- [ ] **Step 5: Commit**

```bash
git add app/src/test app/src/main
git commit -m "fix(test): repair or remove tests that did not prove their claim"
```

---

### Task 6: Write the lessons learned and update the GPS

**Files:**
- Modify: `docs/LESSONS_LEARNED/LL-BUG-HUNT-METHODOLOGY.md`
- Modify: `docs/LESSONS_LEARNED/LL-ROUND-13-TEST-EVIDENCE-AUDIT.md`
- Modify: `PROJECT_ROADMAP.md`

**Interfaces:**
- Consumes: every verdict from Tasks 2-5.
- Produces: the method note, the round result, and an accurate GPS.

- [ ] **Step 1: Record the round result honestly**

State the confirmed count, the refuted count, the retractions, and the weakest gate. Per `LL-BUG-HUNT-METHODOLOGY` §7, the refuted and retracted candidates are the more valuable half and must be written up at least as fully as the findings. Zero confirmed is a valid and respectable result.

- [ ] **Step 2: Add the method lesson to the methodology**

The generalisable finding is that **a test suite is itself an unverified artefact**. It was 798 green, and green meant only that the assertions it contains currently pass. Add a subsection recording the rubric `F1`..`F7`, the rule that execution confirms and reading only refutes, and the incident where an ad-hoc detector reported a live assertion as absent because of an operator-precedence bug, and a `git cat-file -e` check reported a real commit as missing.

- [ ] **Step 3: Update the GPS, as `AGENTS.md` §12.1 requires**

Measure the baseline from the Task 5 Step 4 run. Do not write a remembered number.

Set `CURRENT VERIFIED TEST BASELINE` to the measured total, and `CURRENT CHECKPOINT` to a commit proven to exist:

```bash
git cat-file -e "<sha>"; if ($LASTEXITCODE -eq 0) { "confirmed" }
```

Add a `BUG-HUNT-R13` row to the milestone table pointing at the results document.

- [ ] **Step 4: Commit**

```bash
git add docs/LESSONS_LEARNED PROJECT_ROADMAP.md
git commit -m "docs(audit): record round 13 and update the GPS"
```

---

## Self-Review

**Spec coverage.** The user's request had three parts: a plan, execution via subagents, then a round with lessons and fixes. Task 1-2 are the plan's own tooling, Tasks 3-4 the audit, Task 5 the fixes, Task 6 the lessons and GPS. Every part maps to a task.

**Placeholder scan.** No `TBD`, no "handle edge cases", no "similar to Task N". Every task names real file paths, real commands, and the expected output shape. The three largest test files and the one concrete `F4` finding are named with line numbers.

**Type consistency.** `scan_file` and `scan_tree` signatures are declared once in Task 1 and consumed by that signature in Tasks 2-3. The `Finding` fields are stated once. The rules `F1`..`F7` are defined once in Task 1 and referenced by that naming throughout.

**Known gap, stated rather than hidden.** Tasks 3 and 4 adjudicate the top 15 single-assertion tests and the top claims in three large files, not all 69 and not all claims. This is deliberate and bounded: the cohort is ranked by risk so the financial tests are covered first. The results document must state the untested remainder explicitly, or the round claims more coverage than it delivered.
