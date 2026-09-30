# Round 13 — Test-Suite Evidence Audit

> **Living document.** Tasks 1–6 of the plan
> (`docs/superpowers/plans/2026-09-30-test-suite-evidence-audit.md`) append to this file.
> Each task owns its own section and must not rewrite another's. The claim/evidence/
> result/limits block is repeated per task on purpose: a reader who opens this file at
> Task 5 must be able to see, without scrolling, exactly what was and was not executed.

## Authority boundary and what this document is

This is an audit of **the test suite itself**, not of the product. The question is narrow:
of the 798 tests in this repository, how many actually prove the thing they claim to
prove? The product code is not under audit here, and no product defect is asserted by this
round unless a corrected test exposes one (that is Task 5's work).

This is round 13 of a test-evidence audit. Earlier rounds — including two claims that were
asserted from reading and were both wrong, and one `git cat-file -e` check that reported a
real commit as missing — are recorded in
[`LL-BUG-HUNT-METHODOLOGY.md`](LL-BUG-HUNT-METHODOLOGY.md) §7.1. That history is the reason
for the rule in §1 below.

## The fact that governs the whole file

**The suite is 798/798 green. That means only that the assertions the suite contains
currently pass.** It does not mean the code is correct, and it does not mean the tests
discriminate. A test that asserts nothing, or that asserts something already guaranteed by
its own setup, is green forever while protecting nothing. This round exists because
"798 green" was, until now, being read as "798 tests are real evidence", and that
inference has never been checked.

The scanner below is a **triage instrument, not a gate**. Its output is a *candidate*.
Nothing in it is a verdict. Every candidate in this file reached `CONFIRMED` or `REFUTED`
by **executing** something.

---

## Task 1 — The scanner (built and certified; not adjudicated here)

`scripts/scan_test_evidence.py`, with its self-test `scripts/test_evidence_scanner_fixtures.py`.
The scanner is **certified**; Task 2 did not modify it and must not. Per the plan's
`GATE-INTEGRITY-01` precedent, the self-test imports the real module and calls the real
`scan_file`; it is not a re-implementation of the rule.

### Rubric

| Rule | Name | Meaning |
|:---|:---|:---|
| `F1` | Vacuous | A `@Test` body with no `assert*`, `fail(`, or `verify*` call |
| `F2` | Tautological | An assertion comparing an expression to itself |
| `F3` | Circular | An expected value taken from a call into the class under test |
| `F4` | No precondition | Polls a bounded loop, then asserts without proving the awaited state was reached |
| `F5` | No RED proof | **Not statically detectable.** Execution-only; reported always-false by design |
| `F6` | Mock-only | The only assertions are Mockito `verify*` / `verifyNoInteractions*` |
| `F7` | Vacuous path | Asserts on a value that is reassigned or never observed on the exercised path |

### Controller fact-check of the scanner figures

Reproduced by Task 2 on 2026-09-30 with `python scripts/scan_test_evidence.py app/src/test`.
Every figure matched the fact-check exactly; none was contradicted.

```
Kotlin files scanned: 113
@Test discovered    : 798
Assertion calls     : 4285
F1  Vacuous         : 0
F2  Tautological    : 0
F3  Circular        : 0
F4  No precondition : 1
F5  No RED proof    : 0 (execution-only, reported always-false)
F6  Mock-only       : 1
F7  Vacuous path    : 0
Total findings      : 2
single-assertion cohort: 69
```

**2 findings on a 113-file, 798-test suite, both on a single test.** The scanner's
direction-of-error bias is deliberate: when locating a test body it walks from each `@Test`
to the *next* `@Test` rather than stopping at the `fun` signature, so it can under-report a
rule but cannot invent one. A low count is the expected shape of a working scanner, not
evidence of a broken one.

### Ruling 2 note — `F2` on backtick-named blocks

The scanner's fallback arm has two known latent over-report paths that can invent `F2` on
ordinary compiling Kotlin: a helper declared between two backtick-named tests, and a
`private fun` with an ordinary identifier between two backtick-named tests. Neither occurs
in this suite's data (0 of the 7 `NoteCleanerTest` fallback blocks have any text after their
closing brace). **Task 2 produced no `F2` on any block, backtick-named or otherwise**, so
this latent path was not triggered and nothing was settled by execution on this point. It
remains a latent property of the scanner, not a demonstrated defect.

---

## Task 2 — Adjudicating the `F1`, `F4` and `F6` candidates by execution

**Scope: 3 rules, 2 candidate findings, 1 test file.** No `.kt` file was modified. The only
file created by this task was a throwaway diagnostic, which has been deleted (§2.5).

### 2.1 The `F1` list is EMPTY

```console
$ python scripts/scan_test_evidence.py app/src/test --rule F1
Kotlin files scanned: 113
  F1  Vacuous         : 0
Total findings      : 0
```

**Plan Step 1 and Step 2 had nothing to do as written, and that is the result.** There is no
`F1` candidate to classify as genuinely vacuous / Mockito-only / scanner error, because the
list has no members.

This is recorded explicitly rather than passed over, because "0 findings" and "I looked and
found nothing" are different claims and only the first is supported. Per
`LL-BUG-HUNT-METHODOLOGY` §3.3, an empty result is a valid and respectable outcome:
*"A false positive costs far more than a missed bug. An empty array is a valid, respectable
outcome."* The relevant distinction is that the emptiness here is **measured** (a scanner run
whose counts were fact-checked and reproduced), not asserted.

**No candidates were invented to fill the gap.** The scan produced 2; 2 were adjudicated.

### 2.2 `F4` — No precondition — **CONFIRMED**

| Field | Value |
|:---|:---|
| Rule | `F4` No precondition |
| File | `app/src/test/java/com/example/ui/viewmodels/LocalAccountsViewModelTgzSyncTriggerTest.kt` |
| Line | `207` (the `while` loop), assertion at `213` |
| Test | `testFailedTgzImport_corruptArchive_doesNotTriggerSync` |
| Verdict | **CONFIRMED** |

#### The mechanism

```kotlin
204:            viewModel.importTgzFile(uri, context, shouldReplace = true)
205:
206:            var attempts = 0
207:            while (viewModel.importResult.value == null && viewModel.error.value == null && attempts < 50) {
208:                kotlinx.coroutines.delay(100)
209:                attempts++
210:            }
211:
212:            // B. Verify sync was NEVER requested
213:            verifyNoInteractions(mockSyncRepo)
```

The loop's exit condition is `attempts < 50`. Reaching 50 attempts is a **normal exit**,
indistinguishable at line 213 from the awaited state having been reached. The test never
asserts that the import reported anything. Its sibling, `testInvalidUri_doesNotTriggerSync`
at line 219, closes the same loop and then asserts `assertNotNull("Error should be reported",
viewModel.error.value)` *before* its own `verifyNoInteractions`. That asymmetry is the whole
finding.

#### The proof

Per Ruling 1, no production code was stubbed or modified. Instead a throwaway test
replicated the body with **exactly one line removed** — the `importTgzFile` call — and
nothing else changed. No assertion was weakened.

```kotlin
@Test
fun probe1_clone_withoutImportCall_stillPasses() {
    runBlocking {
        val uri = corruptArchive()

        // >>> the single removed line: viewModel.importTgzFile(uri, context, shouldReplace = true)

        var attempts = 0
        while (viewModel.importResult.value == null && viewModel.error.value == null && attempts < 50) {
            kotlinx.coroutines.delay(100)
            attempts++
        }

        verifyNoInteractions(mockSyncRepo)
    }
}
```

**Result: the clone PASSES.** Measured, **8 executed runs, 8 passes** (see §2.6 for the run
accounting and the one caveat that applies to a *different* probe).

```console
R13EvidenceDiagnostic > probe1_clone_withoutImportCall_stillPasses
BUILD SUCCESSFUL
```

The clone is green while the code under test is never invoked. Therefore the original test
**cannot distinguish "the import failed correctly" from "the import never ran"**, and it
passes identically in both worlds. It does not prove its claim. `F4` is `CONFIRMED`.

This is the stronger form of proof the plan originally asked for: it demonstrates the test is
*insensitive* to the behaviour it claims to test, rather than merely that a stub was
tolerated, and it required no production edit.

#### The proposed repair was itself tested, not assumed

The plan's Task 5 Step 1 proposes adding the precondition. Whether that repair actually
discriminates is a claim, so it was executed as a negative control rather than reasoned about:

```kotlin
@Test
fun probe4_negativeControl_precondition_rejectsAnImportThatNeverRan() {
    runBlocking {
        val uri = corruptArchive()
        // >>> the import is still not called
        var attempts = 0
        while (viewModel.importResult.value == null && viewModel.error.value == null && attempts < 50) {
            kotlinx.coroutines.delay(100)
            attempts++
        }
        var preconditionRejected = false
        try {
            assertNotNull(
                "The corrupt import must report an error, otherwise this test proves nothing",
                viewModel.error.value
            )
        } catch (e: AssertionError) {
            preconditionRejected = true
        }
        assertTrue("NEGATIVE CONTROL FAILED: ...", preconditionRejected)
    }
}
```

**Result: PASSED, and the precondition was rejected**, with the loop exhausting all 50
attempts:

```console
PROBE4 attempts=50 preconditionRejected=true
    detail=The corrupt import must report an error, otherwise this test proves nothing
```

So the repair separates the two worlds: with the import never called the precondition
**fails** (`attempts=50`, nothing was reported), and with the import called it **succeeds**
(§2.3). The Task 5 repair is therefore sound and executable, not merely plausible.

### 2.3 The precondition the test is missing is genuinely reachable

Before the repair can be trusted, the awaited state must actually occur on the real path.
Executed as a probe that is the original body **plus** the precondition:

```console
PROBE2 attempts=1 error=Could not find uTower database in the provided file.
      importResultSuccess=false
      importResultError=Could not find uTower database in the provided file.
```

The corrupt archive **does** produce a reported failure: `attempts=1` (the loop exited on
its state condition, not on the counter), `importResult.value.success == false`, and
`error.value` set. Both awaited observables are populated. The precondition is reachable, and
`verifyNoInteractions(mockSyncRepo)` still holds after it — so the repair is executable
against real behaviour and does not require any production change.

This also independently corroborates the `F4` verdict: the awaited state is reached on the
real path, so a correctly-asserted precondition passes there, while the un-asserted original
would have passed equally well if it had never been reached.

### 2.4 `F6` — Mock-only — **REFUTED**

| Field | Value |
|:---|:---|
| Rule | `F6` Mock-only |
| File | `app/src/test/java/com/example/ui/viewmodels/LocalAccountsViewModelTgzSyncTriggerTest.kt` |
| Line | `213` |
| Test | `testFailedTgzImport_corruptArchive_doesNotTriggerSync` |
| Verdict | **REFUTED** (as a standalone defect) |

#### The claim, and the specific question Ruling 3 asks

`F6` flags a test whose only assertions are Mockito calls. Ruling 3 requires a separate
verdict and asks the sharp question: **once the `F4` precondition is supplied, does the
`verifyNoInteractions` become meaningful, or is it still weak on its own?**

The answer is that it is **already meaningful on its own**, and the `F6` flag is a
**triage signal about assertion style, not a defect**. Two measured facts settle it.

**Fact 1 — the assertion has real discriminating power.** A negative control drove the
*success* path, where production does call `syncRepo.requestSync(USER_ACTION)`
(`LocalAccountsViewModel.kt:332`), and then ran the very same `verifyNoInteractions`:

```console
PROBE3 attempts=3 importResultSuccess=true importResultError=null error=null
      rejectedSyncRequest=true
PROBE3 utowerImportFromFileInvocations=1
PROBE3 syncRepoInvocations=1
```

`verifyNoInteractions` **rejected** the assertion (an `AssertionError` was raised and
caught) whenever sync was genuinely requested, and passed when it was not. A vacuous
assertion cannot reject anything. This one can, so it is a live observation of a real
negative, not a rubber stamp.

**Fact 2 — with the precondition supplied, the assertion means what it says.** §2.3
executed the repaired form: the precondition passes (proving the import really ran and
really failed) and `verifyNoInteractions(mockSyncRepo)` still holds (proving no sync was
requested). That is exactly the test's stated claim, and both halves are now measured.

**Therefore the `verifyNoInteractions` is the *right* assertion for this test and is not
weak.** The harm in this test came entirely from the missing `F4` precondition, not from the
mock-only form. Ruling 3's question resolves cleanly: the `verifyNoInteractions` does not
become meaningful only after the precondition is added — it was already a genuine
observation, and the precondition is what makes it *reachable as a claim about this
scenario* rather than trivially true.

**Why this is the more useful half of the round.** Had `F6` been reported `CONFIRMED` on
the strength of "the only assertion is a Mockito call", Task 5 would have been directed to
rewrite or delete a **correct** assertion, destroying real coverage. A `verifyNoInteractions`
on a seam that production genuinely calls is legitimate evidence. The scanner was right to
flag it and wrong to imply it was a defect; only execution could tell the difference.

### 2.5 What was created, executed, and deleted

The only artefact this task created was `app/src/test/java/com/example/ui/viewmodels/R13EvidenceDiagnostic.kt`
(308 lines, 4 probes, 0 production edits). It was deleted before the commit, as Ruling 1
requires:

```console
$ Remove-Item -LiteralPath "app\src\test\java\com\example\ui\viewmodels\R13EvidenceDiagnostic.kt" -Force
$ Test-Path -LiteralPath "app\src\test\java\com\example\ui\viewmodels\R13EvidenceDiagnostic.kt"
False
$ git ls-files | Select-String -Pattern "R13EvidenceDiagnostic"
none tracked
$ Get-ChildItem -Path "app\src" -Recurse -Filter "R13EvidenceDiagnostic*"
none on disk
$ git diff --name-only HEAD -- "*.kt"
no .kt changes vs HEAD
```

Post-deletion, the class under audit was re-run to confirm the suite is green:

```console
$ gradlew.bat :app:testDebugUnitTest --tests "*LocalAccountsViewModelTgzSyncTriggerTest*" --console=plain --rerun-tasks
BUILD SUCCESSFUL in 1m
suite=...LocalAccountsViewModelTgzSyncTriggerTest tests=3 failures=0 errors=0
  testInvalidUri_doesNotTriggerSync                            time=11.364 passed
  testFailedTgzImport_corruptArchive_doesNotTriggerSync        time=0.351  passed
  testSuccessfulTgzImport_..._andSetsReplaceAllMarker          time=0.792  passed
```

### 2.6 Run accounting, and one honest correction

Repetition counts in this audit are easy to overstate, so the accounting is explicit.
**Eight runs of the diagnostic class actually executed all four probes**, plus **one
additional `probe3`-only run**, giving `probe3` nine executions. An earlier batch of eight
attempted repetitions was discarded: Gradle reported the test task `UP-TO-DATE` and
`BUILD SUCCESSFUL in 1s`, meaning **no test executed**. Those were a failure of my own
verification loop, not passes, and are not counted below.

| Probe | Executed runs | Pass | Fail | Measured output |
|:---|:---|:---|:---|:---|
| `probe1` (F4 clone, no import call) | 8 | 8 | 0 | green with the code under test never invoked |
| `probe2` (original + precondition) | 8 | 8 | 0 | `attempts=1`, `importResultSuccess=false`, error reported |
| `probe3` (F6 negative control) | 9 | 8 | **1** | 8× `rejectedSyncRequest=true syncRepoInvocations=1`; 1× no rejection |
| `probe4` (precondition negative control) | 8 | 8 | 0 | `attempts=50 preconditionRejected=true` |

**The one `probe3` failure is disclosed, not buried.** On the first cold run the negative
control did not reject a sync request. The cause was not established: the failing run
predates the instrumentation that prints `syncRepoInvocations` and `importResultSuccess`, so
the recorded evidence for that specific run does not distinguish "the success import did not
complete" from "the control is unsound". Eight subsequent executed runs — seven of them full
class, four forced with `--rerun-tasks` — all rejected correctly with `syncRepoInvocations=1`
and `importResultSuccess=true`.

The `F6` `REFUTED` verdict does not rest on the single failing run. It rests on the
**conjunction** of §2.4 Fact 1 (8 measured rejections, proving the assertion discriminates)
and Fact 2 (§2.3, the repaired form passes with the precondition proven). A control that is
merely *usually* right would not be adequate evidence, and the caveat is recorded so a later
reader can weigh it: if Task 5 finds `verifyNoInteractions` behaving inconsistently under
load, this is the first place to look.

Note also that this instability is in **my throwaway diagnostic**, not in the suite. The
audited class ran green in every execution, before and after the clone was deleted.

### 2.7 Verdicts

| Rule | Line | Test | Verdict |
|:---|:---|:---|:---|
| `F1` Vacuous | — | — | **EMPTY LIST** — 0 candidates, nothing to adjudicate |
| `F4` No precondition | `:207` | `testFailedTgzImport_corruptArchive_doesNotTriggerSync` | **CONFIRMED** |
| `F6` Mock-only | `:213` | `testFailedTgzImport_corruptArchive_doesNotTriggerSync` | **REFUTED** |

**One confirmed, one refuted, one empty.** The confirmed finding is a missing precondition in
a test that cannot distinguish a correct failure from an absent attempt. The refuted finding
is a `verifyNoInteractions` that is a genuine, discriminating assertion and must not be
rewritten or deleted on the strength of a style flag.

### 2.8 What this task did NOT execute

Stated as plainly as what it did, per `LL-BUG-HUNT-METHODOLOGY` §7.

- **The full 798-test suite was not run.** No Kotlin was modified, so the suite is unchanged
  by construction. Only `LocalAccountsViewModelTgzSyncTriggerTest` and the throwaway
  diagnostic class were executed. **The claim "the other 796 tests are unaffected" rests on
  the absence of edits, not on an observed green run.**
- **Only one test class was audited.** 112 of 113 test files were not read, executed, or
  adjudicated. The scanner found nothing there, but "the scanner found nothing" is not
  "there is nothing" — `F5` and `F7` in particular are weak rules.
- **The 69 single-assertion tests were not adjudicated.** That is Task 3.
- **The three largest test files were not mutation-probed.** That is Task 4. In particular
  `DataIntegrityReleaseGateTest` — the named silent-corruption barrier — is **entirely
  unexamined** by this task. Its being green says nothing yet about whether it discriminates.
- **No RED proof was performed for any test.** `F5` is execution-only and is reported
  always-false by design; **0 does not mean 0 fake tests, it means the rule was not
  evaluated.** It is Task 4's job.
- **No production code was modified, so no product defect was found or excluded.** This task
  says nothing about whether `LocalAccountsViewModel.importTgzFile` is correct.
- **The scanner was not audited for correctness beyond reproducing its counts.** Its
  certified self-test was not re-run. Ruling 2's latent `F2` over-report paths were not
  exercised, because this adjudication produced no `F2`.
- **The flaky `probe3` first run was not root-caused.** See §2.6.

### 2.9 Gate assessment for this task

The weakest gate was **Gate 1 (reachability) applied to the repair**, not the adjudication
itself. The adjudication is strong: the `F4` proof is an execution result, not an argument,
and it is backed by a negative control on the repair. What remains unproven is whether the
suite *as a whole* is sound — which is the entire subject of Tasks 3–5 and is not something
two findings on one file can speak to.

**The lesson worth carrying forward:** a scanner that reports 2 findings on 798 tests is
either an excellent instrument or a broken one, and the output alone cannot distinguish
those. Only adjudication can. Here, one of the two was real and one was a false positive —
which is precisely the ratio that justifies the discipline of adjudicating every candidate
by execution rather than acting on the scan.
