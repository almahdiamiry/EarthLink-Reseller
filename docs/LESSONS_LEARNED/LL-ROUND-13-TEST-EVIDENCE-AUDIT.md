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

> **Attribution note (corrected 2026-09-30).** The block below was previously captioned as
> output of the single command above, but **`single-assertion cohort: 69` is not emitted by
> that command.** It requires `python scripts/scan_test_evidence.py app/src/test
> --rule single-assertion` (equivalently `--single-assertion`, per `:122-130`). Every other
> line is genuine output of the plain invocation. The value is correct; the attribution was
> not, and it is corrected here rather than left in a document whose thesis is "verify the
> verifier".

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
single-assertion cohort: 69   <-- NOT from the command above; needs --rule single-assertion
```

**2 findings on a 113-file, 798-test suite, both on a single test.**

**How the scanner bounds a test body** (corrected 2026-09-30; the previous wording here
attributed fallback behaviour to the instrument in general, which is wrong for 99% of the
suite). Per `scripts/scan_test_evidence.py:39-45`, a test body runs from its `@Test` to the
end of **that test's own function body**, located by matching the braces of its `fun`. It
does *not* stop at the `fun` signature line, because an annotation (`@Suppress`,
`@DisplayName`) may sit between `@Test` and `fun` and stopping there hides the body. It is
also **not** bounded at the next `@Test`.

The next-`@Test` bound is the **fallback only**: it applies when no `fun` can be located at
all, which the trigger is an unlocatable `fun` — a Kotlin backtick-quoted test name — and
not unbalanced braces. In this suite it is reached by **7 of 798 tests, all in
`core/ledger/NoteCleanerTest.kt`** (`:87-92`).

**On "can never invent a finding".** The scanner's own stated direction of error is that it
"may under-report, and must never invent a finding" (`:79-81`), enforced by four guards, each
added after the class was demonstrated to be violated. For the **791 resolved** blocks that
guarantee is well-founded: the bound neither stops early (so it cannot hide an assertion and
invent a vacuity finding) nor over-runs (so it cannot borrow another function's assertions and
invent an `F2`/`F3`/`F7`).

**It is not unconditional, and the exception is this document's own Ruling 2 note below.** The
guarantee that a *helper between two tests* falls outside both ranges holds on resolved shapes
but **not** on the fallback shape, where a helper sitting between two backtick-named tests
**would** be inside the preceding range and can invent an `F2` on ordinary compiling Kotlin
(`:72-77`). So the never-invent property is scoped to the resolved shapes; on the 7 fallback
blocks it carries a known latent exception. No such case exists in this suite's data. A low
count is the expected shape of a working scanner, not evidence of a broken one.

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
```

Full output, unedited (`Root`, the discovery and assertion counts, and all seven rule lines
are emitted here too; previously only three lines were quoted with no marker, which read as
complete output when it was not):

```console
==============================================================================
=== Static Test-Evidence Scanner (triage instrument, not a gate) ===
==============================================================================
Root                : app/src/test
Kotlin files scanned: 113
@Test discovered    : 798
Assertion calls     : 4285
------------------------------------------------------------------------------
Per-rule counts
  F1  Vacuous         : 0
  F2  Tautological    : 0
  F3  Circular        : 0
  F4  No precondition : 1
  F5  No RED proof    : 0 (execution-only: reported always-false, never inferred from source)
  F6  Mock-only       : 1
  F7  Vacuous path    : 0
------------------------------------------------------------------------------
Total findings      : 0
Exit code is 0 by design: findings are candidates for adjudication, not failures.
```

`--rule F1` filters the *findings* to that rule but still prints the whole summary, so the
per-rule counts above are the same as a full run; only `Total findings` differs (0, because
the one `F4` and one `F6` finding are filtered out).

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
(`LocalAccountsViewModel.kt:332`), and then ran the very same `verifyNoInteractions`. The
suite itself already pins that method name on that mock: the sibling success test asserts
`verify(mockSyncRepo, times(1)).requestSync(SyncReason.USER_ACTION)` at
`LocalAccountsViewModelTgzSyncTriggerTest.kt:185`, followed by `verifyNoMoreInteractions`
at `:186`. So `syncRepoInvocations=1` below is the count of calls to *that* method, not an
anonymous interaction:

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

**Disclosure — this control did not assert its own precondition (`F4` in miniature).**
`probe3` *prints* `importResultSuccess` but its only assertion is `assertTrue(rejectedSync)`
(preserved clone, `:258-261`). It never asserts that the success-path import actually
succeeded before treating the rejection as meaningful. That is the very defect class this
round exists to catch, committed by the control that was meant to rule the defect out, and it
was not disclosed when the `F6` verdict was first recorded.

It is also the most likely explanation of the single unexplained failure in §2.6. Because the
control never checked that the import succeeded, it could not distinguish *"the import
succeeded and requested sync"* from *"the import never got far enough to request sync"*. In
the second case `verifyNoInteractions` has nothing to reject, `rejectedSync` stays `false`,
and the control **fails** — which is what the recorded run did. Had the control asserted
`importResultSuccess == true` first, that run would have failed loudly at the precondition
instead, naming the real cause instead of surfacing as an unexplained rejection failure.

**This does not weaken the `F6` verdict, and the direction of the error is the reason.** The
control's only assertion is `assertTrue(rejectedSync)`, so a missing precondition can only
make it fail, never pass: the unsound path produces a **false failure** — demanding a
rejection that could not occur — which is exactly the failure observed. It cannot produce a
false pass, because a pass requires `rejectedSync == true`, which requires an actual
`AssertionError` from `verifyNoInteractions`. The 8 correct rejections recorded in §2.6 are
therefore uncontaminated by this defect. The `REFUTED` verdict additionally rests on Fact 2
below, which is a different probe.

**Fact 2 — with the precondition supplied, the assertion means what it says.** §2.3
executed the repaired form: the precondition passes (proving the import really ran and
really failed) and `verifyNoInteractions(mockSyncRepo)` still holds (proving no sync was
requested). That is exactly the test's stated claim, and both halves are now measured.

**Carry-forward for Task 5 (which repairs this very test).** Any control added to prove the
repaired test discriminates must assert *its own* precondition before its verdict counts.
A control that skips this is `F4` in miniature, and it fails in the direction that flatters
the conclusion — or, as here, in the direction that produces a failure whose cause has to be
guessed at afterwards.

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

#### Where the raw execution evidence lives — and why it is not durable

Every measured output quoted in §2.2–§2.6 comes from artefacts that are **outside the
repository**, in the OS temp directory:

| Artefact | Path | Contents |
|:---|:---|:---|
| Run logs | `C:\Users\ALMAHD~1\AppData\Local\Temp\opencode\r13-*.log` | The full `--console=plain -i` Gradle output of every executed run, including the `PROBn` stdout lines quoted above |
| Preserved clone | `C:\Users\ALMAHD~1\AppData\Local\Temp\opencode\R13EvidenceDiagnostic.kt.preserved` | Verbatim 308-line copy of the deleted `R13EvidenceDiagnostic.kt`, so the clone in §2.2 can be diffed against what actually ran |
| Runner script | `C:\Users\ALMAHD~1\AppData\Local\Temp\opencode\r13-stability.ps1` | The harness that drove the `--rerun-tasks` repetitions behind the §2.6 counts |

**These are not durable evidence and are not part of the repository.** They sit outside the
worktree, they are not tracked by git, and a temp-directory clean will destroy them. A later
task therefore **cannot re-verify the 8-run count or the clone text from the repository** —
the §2.6 table is a claim this document makes on the strength of evidence that no longer
ships with it. Anyone re-deriving those numbers must re-run the diagnostic, and the probe
bodies are quoted here in §2.2–§2.4 precisely so that is possible without the original file.

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
control did not reject a sync request. The cause was not established at the time; §2.4 now
records the most likely mechanism — the control asserted no precondition of its own, so a run
in which the success import produced no sync left `verifyNoInteractions` with nothing to
reject. Note this is **not** a load or timing hypothesis: nothing in the recorded evidence
points to load. The failing run predates the instrumentation that prints
`syncRepoInvocations` and `importResultSuccess`, so the evidence for that specific run cannot
discriminate between that mechanism and a sound control that was simply defeated.
Eight subsequent executed runs — seven of them full class, four forced with `--rerun-tasks`
— all rejected correctly with `syncRepoInvocations=1` and `importResultSuccess=true`.

The `F6` `REFUTED` verdict does not rest on the single failing run. It rests on the
**conjunction** of §2.4 Fact 1 (8 measured rejections, proving the assertion discriminates)
and Fact 2 (§2.3, the repaired form passes with the precondition proven). A control that is
merely *usually* right would not be adequate evidence. As §2.4 sets out, the missing
precondition in `probe3` could only cost the control a spurious **failure** (its sole
assertion is `assertTrue(rejectedSync)`), never a spurious **pass**, so the 8 correct
rejections are not inflated by that defect.

**If Task 5 revisits this control, the first thing to change is the missing precondition
assertion — not the load.** An earlier version of this paragraph pointed at load and
"behaving inconsistently under load"; nothing in the recorded evidence supports a load
hypothesis, and the corrected mechanism is the absent precondition.

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
- **The flaky `probe3` first run was not root-caused.** See §2.6, and the disclosure in §2.4
  that the control asserted no precondition of its own.
- **This worktree could not build until two gitignored files were copied in.** Tasks 3, 4
  and 5 all need Gradle, and each would otherwise burn two build cycles on
  `SDK location not found` then `File google-services.json is missing`. Before running any
  Gradle command in this worktree, confirm these two exist:
  - `local.properties` — absent; copy from the main checkout
    `C:\Users\Almahdi-BOC\antigravity\Earthlink-Reseller-V1\local.properties`
  - `app\google-services.json` — absent; copy from
    `C:\Users\Almahdi-BOC\antigravity\Earthlink-Reseller-V1\app\google-services.json`

  Both are gitignored (`.gitignore`: `/local.properties`, `/app/google-services.json`), so
  copying them cannot affect a commit — `git status` was verified clean after each copy in
  this task. This is a worktree-provisioning gap, not an audit finding.

### 2.9 Gate assessment for this task

The weakest gate was **Gate 1 (reachability) applied to the repair**, not the adjudication
itself. *Gate 1* here means the requirement, inherited from `LL-BUG-HUNT-METHODOLOGY` §3.2,
that a claimed defect be shown to be reachable by naming the exact production path that
produces it — in this case, whether the awaited state is actually reachable on the real
production path so that the proposed repair has something real to assert. (§3.2 numbers its
gates within the bug-hunt pipeline; this document is a test-evidence audit, so the name is
borrowed for the analogous requirement. The plan defines only `GATE-INTEGRITY-01`.) It is
assessed as reached for the repair: §2.3 executed the real path and observed the awaited
state, and §2.2's negative control showed the precondition fails when that state is absent.
The adjudication itself is strong: the `F4` proof is an execution result, not an argument,
and it is backed by that negative control. What remains unproven is whether the suite *as a
whole* is sound — which is the entire subject of Tasks 3–5 and is not something two findings
on one file can speak to.

**The lesson worth carrying forward:** a scanner that reports 2 findings on 798 tests is
either an excellent instrument or a broken one, and the output alone cannot distinguish
those. Only adjudication can. Here, one of the two was real and one was a false positive —
which is precisely the ratio that justifies the discipline of adjudicating every candidate
by execution rather than acting on the scan.

---

## Task 3 — Adjudicating the single-assertion cohort by mutation

**Scope: 1 review priority (`F5`), 69 candidates, 15 adjudicated, 10 mutations across 9
production files, 16 executed Gradle runs.** No `.kt` file is modified by this commit. Nine
production files were mutated transiently under the Ruling 1 gate and every one was reverted
and byte-verified (§3.7). `PdfStatementGenerator.kt` carries two of the ten mutations (M6 and
M10), which is why the file count is one lower than the mutation count.

**Verdict count: 6 CONFIRMED, 9 REFUTED, 0 OPEN.**

### 3.1 Why a single assertion is a review priority at all

A single assertion is not automatically fake, and this round did not treat it as such. It is
a **review priority** for one reason, and it is `F5`:

> A lone assertion is easily satisfied by *setup* rather than by the behaviour under test.

The two ways this happens, both observed below, are:

1. **The assertion reads a value the test itself just wrote.** A fixture that inserts a row
   and then asserts the row exists cannot fail for any reason except a broken database.
2. **The assertion is arithmetically independent of the property it names.** A test can
   assert a real value and still be incapable of distinguishing the correct implementation
   from a wrong one, because the input makes the property irrelevant.

Only the second class is invisible to reading with certainty, and both classes are settled the
same way: **remove the production behaviour and see whether the test notices.**

**`F5` is not statically detectable**, which is why the certified scanner reports it
always-false by design. Everything in §3.4 therefore rests on execution. Where a verdict rests
only on reading, it is labelled `reading` and is a strictly weaker claim — per this audit's
global constraint, *execution confirms, reading only refutes*.

### 3.2 The cohort, and the ranking rule

The cohort was re-derived rather than assumed:

```console
$ python scripts/scan_test_evidence.py app/src/test --rule single-assertion
==============================================================================
=== Single-assertion @Test cohort (exactly one assert*/fail(/verify* call) ===
==============================================================================
[69 lines, one per test: path:line  testName  [ClassName]]
------------------------------------------------------------------------------
Total: 69 single-assertion tests out of 798 discovered.
==============================================================================
```

The 69 distribute unevenly, and that distribution drove the ranking:

| File | Cohort members |
|:---|:---|
| `DataIntegrityReleaseGateTest.kt` | **22** |
| `GetRemainingTimeTest.kt` | 6 |
| `DefectRemediationSeamTest.kt` | 3 |
| `Phase2RemoteVersionAdversarialTest.kt` | 3 |
| `SurgicalFixAdvanceAndRenewalTest.kt` | 3 |
| `HistoricalSubscriberMatchingSafetyTest.kt` | 3 |
| `ApiErrorSemanticsRegressionTest.kt` | 3 |
| `EarthlinkSearchViewModelSeamTest.kt` | 3 |
| `Phase5DestructiveActionReleaseGateTest.kt` | 2 |
| `Workstream7ImportMatchingCollisionTest.kt` | 2 |
| `BugMoney01PdfHidesSubscriberCreditTest.kt` | 2 |
| `BugSri01CoordinatorAuditDaoWiringTest.kt` | 2 |
| `UtowerDateParserTest.kt` | 2 |
| 13 further files | 1 each |

The 13 named multi-member files account for 56 of the 69; the 13 single-member files account
for the other 13.

**A third of the cohort (22 of 69) sits in one file** — and that file is the one
`AGENTS.md` §9.1 names as a `RELEASE-REQUIRED` suite directly protecting `INV-01`..`INV-16`
and as *the silent-corruption barrier*. A fake assertion there is the most expensive finding
this audit could produce, so the ranking is deliberately weighted toward it. This is a
judgement, stated as one, not a measurement.

**The ranking rule, in order:**

1. The brief's keyword set — `Ledger`, `Balance`, `Dispatch`, `Atomicity`, `Money`,
   `Convergence`, `Lineage`, `ReleaseGate`, `Correction` — because those files hold RED
   invariants.
2. Inside `DataIntegrityReleaseGateTest`, the named silent-corruption barrier.
3. A single assertion guarding a **quantity of money** or a **history-preservation** rule.
4. **Locus where setup could plausibly satisfy the assertion** — the `F5` mechanism.
5. Tie-break, deliberately ranking **loop-wrapped and source-scanning assertions lower**,
   because the scanner's *syntactic* count understates their runtime assertion count
   (§3.5). They are the weakest members of the cohort, not the strongest.

**The three tests the brief named first are not the top three.**
`DataIntegrityReleaseGateTest.kt:192` and `:206` (`roundTrip_stateSource_…`,
`roundTrip_stateConfidence_…`) are real production round trips through
`buildOutboxPayloadMap` → `RemoteEntityValidator`; they rank mid-cohort, not top. The
brief's caution was correct and the ranking was done independently.

#### The 15 adjudicated, in rank order

| # | File : line | Test | Why this rank |
|:---|:---|:---|:---|
| 1 | `DataIntegrityReleaseGateTest.kt:606` | `invariant_INV02_historicalSourceImmutability_ledgerDeleteUsesContraEntry` | `Ledger`; RED Invariant 2; the only cohort member guarding physical deletion. Rule 4 fires on sight |
| 2 | `DataIntegrityReleaseGateTest.kt:923` | `backupRestore_migrationDefaults_safeForBalanceCalculator` | `Balance`; H-3 class; its own section header claims a migration assumption is being tested |
| 3 | `DataIntegrityReleaseGateTest.kt:1275` | `oracle_noteTransaction_zeroFinancialImpact` | financial claim in the barrier; rule 4 plausible (a `0.0` amount makes a type claim untestable) |
| 4 | `EarthlinkSearchViewModelSeamTest.kt:1223` | `testBalanceAfterMath_unknownBalance_isNull` | `Balance`; RED Invariant 1 null-vs-zero. Rule 4: computes its own value |
| 5 | `DataIntegrityReleaseGateTest.kt:776` | `invariant_INV12_noOutboxLoopsOnRemoteApply` | RED Invariant 8; a *count* comparison, the shape most exposed to `0 == 0` |
| 6 | `DataIntegrityReleaseGateTest.kt:819` | `idempotency_duplicateSyncEvent_zeroNewEntries` | RED Invariant 3, zero duplicate charges; same count-compare exposure |
| 7 | `DataIntegrityReleaseGateTest.kt:627` | `invariant_INV04_zeroDoubleApplication_snapshotHistoryFiltered` | the exact H-3 invariant the whole file exists for |
| 8 | `DataIntegrityReleaseGateTest.kt:534` | `corruptionInjection_rawJson_strippedFromCloudPayload_ledgerPath` | `Ledger`; names one exact production line |
| 9 | `EarthlinkSearchViewModelSeamTest.kt:1168` | `testGetResellerBalance_success_returnsValue` | `Balance`; sibling of #4, same file |
| 10 | `EarthlinkSearchViewModelSeamTest.kt:1200` | `testGetResellerBalance_apiFailure_resultsInNullNotZero` | `Balance`; the null-not-zero rule itself |
| 11 | `Workstream7And8SafetyNetTest.kt:65` | `testUtowerDebtResolverPriority3AnchorsOnExplicitDebt` | financial baseline anchoring; independent literal oracle |
| 12 | `BugMoney01PdfHidesSubscriberCreditTest.kt:47` | `exactlyZeroDebtAfter_stillRendersAsSettled` | `Money`; the boundary of a fixed financial-display defect |
| 13 | `BugMoney01PdfHidesSubscriberCreditTest.kt:56` | `positiveDebtAfter_rendersAsOutstandingAmount` | `Money`; same file |
| 14 | `DataIntegrityReleaseGateTest.kt:324` | `roundTrip_amountIqd_preservedThroughMoshiOutboxAndValidator` | financial field through a real production round trip |
| 15 | `Phase5DestructiveActionReleaseGateTest.kt:81` | `testNoOtherUiScreens_callClearLocalData` | `ReleaseGate`; ranked last by rule 5 — see §3.5 |

### 3.3 Execution discipline

Per Ruling 2, `--rerun-tasks` was passed to **every** run and the JUnit XML was read after
every run. `BUILD SUCCESSFUL` was never treated as evidence.

- **16 log files, all executed.** Every log reports `35 actionable tasks: 35 executed`.
- **`Task :app:testDebugUnitTest UP-TO-DATE` occurs zero times across all 16 logs.** The
  `UP-TO-DATE` lines present belong to non-test tasks (`generateDebugAssets`, `preBuild`,
  `preDebugUnitTestBuild`, …), which is normal and harmless.
- **The failure mode Task 2 hit — `BUILD SUCCESSFUL in 1s` with nothing executed — did not
  occur in this task.** Recorded because its absence is a measurement, not an assumption.

All logs are outside the repository, in the OS temp directory, for the reason given in §2.5:
an untracked file inside the worktree would appear in `git status --porcelain` and
**destroy the Ruling 1 revert gate**. Path pattern:
`C:\Users\ALMAHD~1\AppData\Local\Temp\opencode\r13-t3-*.log`.

### 3.4 Verdicts, one at a time

Legend for evidence class: **`mutation`** = a production behaviour was removed and the test
was re-run; **`reading`** = production-path trace only, which can refute but never confirm.

---

#### 1. `DataIntegrityReleaseGateTest.kt:606` — `invariant_INV02_historicalSourceImmutability_ledgerDeleteUsesContraEntry` — **CONFIRMED**

**Production behaviour named:** `LedgerRepository.deleteTransaction` must create a
contra-entry and never physically `DELETE` a ledger row. The test's own comment cites this:
`"Evidence: Repositories.kt:2358-2362 — deleteTransaction calls correctTransaction, not
physical delete"`.

**What the test actually does:** inserts an account, inserts one ledger entry, and asserts
`assertNotNull("INV-02 SETUP | Original entry must exist before correction", beforeDelete)`.
It **never calls `deleteTransaction` or `correctTransaction` at all**, and the assertion
message says `SETUP`. The row it asserts on is the row it just inserted — setup case 1.

**The mutation (M1)** — `Repositories.kt:2693-2697`, the exact production path named:

```kotlin
// BEFORE (the real implementation)
override suspend fun deleteTransaction(id: String) {
    // Normal financial correction: physical deletion of original financial event is forbidden (§3.2).
    // Full reversal is the special case where intended amount = 0.0.
    correctTransaction(originalEntryId = id, intendedAmount = 0.0, note = "[FULL REVERSAL for $id]")
}

// AFTER (R13-T3 transient mutation M1 — the exact RED-Invariant-2 violation)
override suspend fun deleteTransaction(id: String) {
    ledgerDao.deleteById(id)   // AppDatabase.kt:229 — DELETE FROM local_ledger_entries WHERE id = :id
}
```

**Result: the test still passes.** Production now performs the physical row deletion that
this release gate exists to forbid, and the gate is green.

```console
$ gradlew.bat :app:testDebugUnitTest \
    --tests "*DataIntegrityReleaseGateTest.invariant_INV02_historicalSourceImmutability_ledgerDeleteUsesContraEntry" \
    --rerun-tasks --console=plain
```

| | XML `tests` | `failures` | `errors` |
|:---|:---|:---|:---|
| Before (from the class baseline, §3.3) | 1 | 0 | 0 |
| After mutation M1 | **1** | **0** | **0** |

Log: `r13-t3-m1-inv02-physical-delete.log`. `BUILD SUCCESSFUL in 1m 16s`, 35 tasks executed.

**Why this is the most serious finding in the round.** RED Invariant 2 (`AGENTS.md` §4.2) is
*"No physical row deletion on `local_ledger_entries`"*, and
`AppDatabase.kt:152` states the same rule in production. This test is named for that
invariant, sits in the file `AGENTS.md` names as the barrier, and is blind to the violation
**by construction**: it holds no reference to `deleteTransaction` or `correctTransaction` at
all. No mutation of `Repositories.kt` can change its outcome, because the mutated object is
not in its input set. That is the strongest form of `F5` — not *tolerating* a stub, but being
*incapable* of observing the change.

#### 1a. `Workstream9AFinancialCorrectionTest.kt:262` — the working guard this finding is *not* about

> **Correction, added after re-review.** This subsection was previously an unnumbered `####`
> sitting at the same level as the 15 numbered candidate entries, so a reader scanning §3.4
> could not tell a candidate verdict from a note about one. It is numbered `1a` to mark it as
> belonging to candidate #1, and it is **not** a sixteenth adjudicated candidate: no verdict
> is claimed for this test and no run of it was performed by me.

**The invariant is not undefended — this is a finding about *this test*, not about Invariant 2**

**Correction, added after review.** Everything above is true, and a reader would still finish
this section with a false impression: that RED Invariant 2 has no working guard. **It has
one.** `Workstream9AFinancialCorrectionTest.kt:262`,
`testFullReversal_viaDeleteTransaction_createsZeroIntendedCorrectionWithoutPhysicalDeletion`,
is a genuine and effective guard:

- `:285` calls `ledgerRepository.deleteTransaction("tx_reversal_target")` — the real seam.
- `:289` `assertNotNull("Original transaction must NOT be physically deleted", origInDb)` —
  the exact RED-Invariant-2 assertion, on the row the deletion targeted.
- `:298` `assertEquals(2, allEntries.size)` — the original *plus* the contra-entry, so a
  silent no-op cannot pass.
- `:299-302` locates the `correctsEntryId == "tx_reversal_target"` row and asserts its type
  and amount.

**Verified by execution, by the reviewer, not by me.** M1 was re-applied and this test was run
alongside the gate test: **`2 tests completed, 1 failed`**, the single failure being this one.
So the identical mutation that the gate test cannot see is caught immediately by a test
elsewhere in the suite.

**The finding is therefore precisely scoped: `DataIntegrityReleaseGateTest.kt:606` is
structurally blind; RED Invariant 2 is defended.** The consequence for Task 5 is a repair,
not an addition — see the carry-forward in §3.10. I did not adjudicate
`Workstream9AFinancialCorrectionTest.kt:262` in this task; it is **not** in the
single-assertion cohort (it carries **seven** assertion calls — `:289`, `:293`, `:294`, `:298`,
`:300`, `:301`, `:302`, counted mechanically rather than estimated), so no verdict is claimed
for it beyond the one measured fact above.

**F5 CONFIRMED**, scoped to the gate test. Task 5 owns the repair (call the real
`deleteTransaction`, assert the original row survives and a `correctsEntryId` contra-entry
appears — which is what `Workstream9AFinancialCorrectionTest.kt:262` already does).

---

#### 2. `DataIntegrityReleaseGateTest.kt:923` — `backupRestore_migrationDefaults_safeForBalanceCalculator` — **CONFIRMED**

**Production behaviour named:** `MIGRATION_8_9` (`AppDatabase.kt:830-838`) and its `DEFAULT`
values. The test's own section header (`D.5a`) states the intent precisely:

> *"Room migrations use DEFAULT values that must be safe for BalanceCalculator.
> **"Protected by Room migrations" is itself an assumption worth testing.**"*

**What the test actually does:** constructs a `LocalAccount` **in Kotlin** with the values
`0.0 / 0.0 / 0.0 / null / null` hardcoded, and asserts `BalanceCalculator` returns `50000.0`.
No Room migration is ever executed — a fresh Robolectric database is created at the current
version, so `MIGRATION_8_9` is inert. The test hardcodes a *copy* of the defaults and tests
the copy.

**The mutation (M3)** — `AppDatabase.kt:832-835`, the migration itself, made demonstrably
unsafe:

```kotlin
// AFTER (R13-T3 transient mutation M3)
db.execSQL("ALTER TABLE `local_accounts` ADD COLUMN `openingDebtIqd` REAL NOT NULL DEFAULT 999999.0")
db.execSQL("ALTER TABLE `local_accounts` ADD COLUMN `openingAdvanceIqd` REAL NOT NULL DEFAULT 0.0")
db.execSQL("ALTER TABLE `local_accounts` ADD COLUMN `openingLoanIqd` REAL NOT NULL DEFAULT 0.0")
db.execSQL("ALTER TABLE `local_accounts` ADD COLUMN `stateSource` TEXT DEFAULT 'UTOWER_SNAPSHOT_RESOLVED'")
```

Every migrated legacy account would now carry a 999,999 IQD phantom opening debt **and** a
snapshot `stateSource` that suppresses history filtering — the H-3 shape, manufactured
directly in the migration.

**Result: the test still passes.**

| | XML `tests` | `failures` | `errors` |
|:---|:---|:---|:---|
| Before (class baseline) | 1 | 0 | 0 |
| After mutation M3 | **1** | **0** | **0** |

Log: `r13-t3-migration-defaults-unsafe.log` (full name
`r13-t3-m3-migration-defaults-unsafe.log`). `BUILD SUCCESSFUL in 1m 20s`.

**The nuance, stated precisely, because overstating it would be its own error.** This test
is *not* vacuous: it makes a real call into production `BalanceCalculator.reconstructCurrentPosition`
and asserts a real additive result, and that half is sound. What it provides **no evidence
for** is the claim in its own name and in its own section header — that the *migration
defaults* are safe. Measured: those defaults can be made arbitrarily unsafe and the test is
unaffected.

**F5 CONFIRMED for the named claim.** This is the exact failure of method
`LL-BUG-HUNT-METHODOLOGY` warns about — testing a *copy* of a value and believing the
original was tested — and it is the more dangerous half of this audit's remit, because
`AGENTS.md` §11 records that H-3 was a *migration* incident.

---

#### 3. `DataIntegrityReleaseGateTest.kt:1275` — `oracle_noteTransaction_zeroFinancialImpact` — **CONFIRMED**

**Production behaviour named:** `"note"` is a financial no-op transaction type
(`TransactionTypeNormalizer.kt:25`, consumed by `BalanceCalculator.applyTransaction`'s
`else` branch at `BalanceCalculator.kt:26`).

**What the test actually does:** builds two entries — `took 40000.0` and
**`note` with `amountIqd = 0.0`** — and asserts the result is `40000.0`. Because the note's
amount is zero, the assertion is **arithmetically independent of the type claim**: adding
zero to a debt leaves it unchanged for *every* possible classification. This is setup case 2.

**The mutation (M7)** — `TransactionTypeNormalizer.kt:25`, the named behaviour, destroyed:

```kotlin
// BEFORE
"note", "NOTE" -> "note"
// AFTER (R13-T3 transient mutation M7)
"note", "NOTE" -> "took"
```

`"note"` is now a debt-adding type. `"note"` also remains a *recognised* canonical type
(`RECOGNIZED_CANONICAL_TYPES` contains `"took"`), so no other guard compensates.

**Result: the test still passes.**

| | XML `tests` | `failures` | `errors` |
|:---|:---|:---|:---|
| Before (class baseline) | 1 | 0 | 0 |
| After mutation M7 | **1** | **0** | **0** |

Log: `r13-t3-m7-note-reclassified-as-took.log`. `BUILD SUCCESSFUL in 1m 28s`.

**F5 CONFIRMED.** The test named `oracle_noteTransaction_zeroFinancialImpact` proves that a
**zero-amount** transaction does not move a balance — which is true of the type system, not
of the note semantics. A note carrying a real amount misclassified as `took` would inflate
debt and this gate would stay green. A non-zero `amountIqd` on the note entry is the missing
ingredient.

---

#### 4. `EarthlinkSearchViewModelSeamTest.kt:1223` — `testBalanceAfterMath_unknownBalance_isNull` — **CONFIRMED**

**Production behaviour it appears to name:** the reseller-balance null-vs-zero rule. **It names
no production behaviour at all.** The entire body is:

```kotlin
val resellerBalance: Double? = null
val packageCost = 40000.0
val balanceAfter = resellerBalance?.let { it - packageCost }
assertNull(balanceAfter)
```

This asserts that Kotlin's safe-call operator returns `null` when the receiver is `null`. No
gateway, no ViewModel, no repository, no production code of any kind is reached. It is a
`kotlin` language test wearing a `Balance` name.

**The mutation (M2)** — `EarthlinkSearchViewModel.kt:144-146`, the balance seam, destroyed:

```kotlin
// BEFORE
suspend fun getResellerBalance(): Double = withContext(Dispatchers.IO) { gateway.getBalance() }
// AFTER (R13-T3 transient mutation M2)
suspend fun getResellerBalance(): Double = withContext(Dispatchers.IO) { 0.0 }
```

**Result: the test still passes — and its sibling in the same file, in the same run, goes
red.** This is the single most decisive result in the round, because one mutation and one run
produce opposite verdicts on two tests in the same file:

| Test | Before (baseline) | After mutation M2 |
|:---|:---|:---|
| `testBalanceAfterMath_unknownBalance_isNull` | pass | **pass — mutation undetected** |
| `testGetResellerBalance_success_returnsValue` | pass | **FAIL — `expected:<150000.0> but was:<0.0>`** |

XML: before `tests=2 failures=0 errors=0`; after `tests=2 failures=1 errors=0`.

Log: `r13-t3-m2-resellerbalance-ignores-gateway.log`. `BUILD FAILED in 2m 8s`.

**F5 CONFIRMED**, with the contrast as the killing evidence: the production method it is named
after was replaced by a constant, and the test could not tell.

**A twin exists outside the cohort, and the cohort cut hides it.**
`testBalanceAfterMath_knownBalance_computesCorrectly` (same file, `:1214`) has the same
defect — it asserts `100000.0 - 40000.0 == 60000.0` in the test file — but because it carries
**two** assertions it is not in the single-assertion cohort at all. It was therefore never a
candidate for this task, this audit, or (on the evidence so far) any prior review. Flagged
for Task 4, which owns the largest files; it is not adjudicated here and no claim is made
about it beyond the reading.

---

#### 5. `DataIntegrityReleaseGateTest.kt:776` — `invariant_INV12_noOutboxLoopsOnRemoteApply` — **CONFIRMED**

**Production behaviour named:** applying a remote event via `RemoteSyncCoordinator.processEvent`
must not enqueue a local outbox record (RED Invariant 8 — no sync echo loop).

**What the test does:** counts outbox rows before, calls the real `processEvent`, counts after,
asserts `outboxBefore == outboxAfter`. It **never asserts that the event was applied**. The two
counting worlds are indistinguishable: "applied and correctly produced no echo" and "never
applied at all" both yield an unchanged count.

**The mutation (M9)** — `RemoteSyncCoordinator.kt:232`, the dedup gate, widened so that
**every** remote event is suppressed:

```kotlin
// BEFORE
if (processedKeys.containsKey(key)) {
// AFTER (R13-T3 transient mutation M9)
if (true || processedKeys.containsKey(key)) {
```

No remote event is ever applied anywhere in the process.

**Result: the test still passes.**

| | XML `tests` | `failures` | `errors` |
|:---|:---|:---|:---|
| Before (class baseline) | 1 | 0 | 0 |
| After mutation M9 (run with #6 below) | **1** | **0** | **0** |

Log: `r13-t3-m9-all-events-suppressed.log`. `BUILD SUCCESSFUL in 55s`.

Corroborating signal, not the evidence: the test's runtime fell from **17.22s** (baseline) to
**5.878s**, consistent with no database work occurring. The XML numbers are the authority; the
timing is a supporting observation only.

**F5 CONFIRMED.** The gate is blind to the case where remote apply stops working altogether —
which is the more dangerous neighbour of an echo loop, and the one an operator would
diagnose from this test's green.

---

#### 6. `DataIntegrityReleaseGateTest.kt:819` — `idempotency_duplicateSyncEvent_zeroNewEntries` — **CONFIRMED**

**Production behaviour named:** applying the same remote ledger event twice must not create
duplicate entries (RED Invariant 3 — zero duplicate charges).

**What the test does:** applies the event, counts entries (`countAfterFirst`), applies it
again, counts again, asserts `countAfterFirst == countAfterSecond`. The name says
`zeroNewEntries`, but **nothing asserts the first apply created anything**. If both applies
create nothing, `0 == 0` passes. The same `0 == 0` exposure as #5.

**The mutation:** the same M9 as #5 — every remote event suppressed — was run against this
test in the same invocation, which is what settled it.

**Result: the test still passes.**

| | XML `tests` | `failures` | `errors` |
|:---|:---|:---|:---|
| Before (class baseline) | 1 | 0 | 0 |
| After mutation M9 (run with #5 above) | **1** | **0** | **0** |

Log: `r13-t3-m9-all-events-suppressed.log` (`tests=2 failures=0 errors=0` for the pair).

**F5 CONFIRMED.** This was the one candidate I expected to have to record as `OPEN` (§3.7
records why it did not need to be). Asserting `countAfterFirst == 1` before the comparison
would close it — the same missing-precondition shape Task 2 confirmed at `F4`.

---

#### 7. `DataIntegrityReleaseGateTest.kt:627` — `invariant_INV04_zeroDoubleApplication_snapshotHistoryFiltered` — **REFUTED**

**Production behaviour named:** `BalanceCalculator.reconstructCurrentPosition` must filter
`isSnapshotHistory` entries when `isSnapshotBaseline` is true — **the exact H-3 invariant**
this whole file was written to prevent, per the file's own header (`H-3: … inflating debt by
9.5M IQD across 84 accounts`).

**The mutation (M4)** — `BalanceCalculator.kt:70-74`, the filter, removed:

```kotlin
// BEFORE
val eligibleTxs = if (isSnapshotBaseline) {
    transactions.filter { !it.isSnapshotHistory }
} else { transactions }
// AFTER (R13-T3 transient mutation M4)
val eligibleTxs = transactions
```

**Result: the test goes RED, with the mutant's value exactly as the test's own comment
predicted it** (`:655` — *"NOT: 100000 + (30 × 40000) + 25000 = 1,325,000"*):

| | XML `tests` | `failures` | `errors` |
|:---|:---|:---|:---|
| Before (class baseline) | 1 | 0 | 0 |
| After mutation M4 | 1 | **1** | 0 |

```console
java.lang.AssertionError: INV-04 VIOLATED | Zero double-application: 30 isSnapshotHistory
entries must be filtered. Expected: 125000.0 (opening 100k + runtime 25k) |
Actual: 1325000.0 | If actual > 125000, historical entries were re-applied on top of
baseline (H-3 failure mode) expected:<125000.0> but was:<1325000.0>
```

Log: `r13-t3-m4-balancecalculator-no-history-filter.log`. `BUILD FAILED in 1m 21s`.

**REFUTED — the killing evidence is that the test fails.** Its oracle is explicit
arithmetic written in the test (`125000.0` = 100k opening + one 25k runtime entry, per the
Testing Playbook §9.3 3-vector rule), the 30 historical entries are the discriminating
input, and the failure message is self-diagnosing. This is what a real barrier looks like,
and it is the direct counterpart to #1 in the same file: two single assertions, same class,
one proves the invariant and one does not.

---

#### 8. `DataIntegrityReleaseGateTest.kt:534` — `corruptionInjection_rawJson_strippedFromCloudPayload_ledgerPath` — **REFUTED**

**Production behaviour named:** one exact line — `SyncRepositoryImpl.kt:719`,
`dataMap.remove("rawJson")` in the `local_ledger_entries` branch of `buildOutboxPayloadMap`.

**The mutation (M5):** that statement deleted, leaving the branch empty.

**Result: the test goes RED.**

| | XML `tests` | `failures` | `errors` |
|:---|:---|:---|:---|
| Before (class baseline) | 1 | 0 | 0 |
| After mutation M5 | 1 | **1** | 0 |

```console
java.lang.AssertionError: INV-02 VIOLATED | rawJson must be stripped from ledger cloud payload.
Evidence: SyncRepositoryImpl.kt:713
```

Log: `r13-t3-m5-rawjson-ledger-strip-removed.log`. `BUILD FAILED in 1m 24s`.

**REFUTED.** The assertion reads a map built by real production code and rejects exactly the
condition it names. Note the *account*-path twin at `:521` was not run — it exercises the
sibling branch at `SyncRepositoryImpl.kt:716`; see §3.7.

---

#### 9. `EarthlinkSearchViewModelSeamTest.kt:1168` — `testGetResellerBalance_success_returnsValue` — **REFUTED** (`mutation`)

Same mutation M2, same run as #4. The gateway reports `150000.0`; the ViewModel is made to
return a constant `0.0`.

**Result: RED** — `expected:<150000.0> but was:<0.0>`, XML `tests=2 failures=1 errors=0`.
Log: `r13-t3-m2-resellerbalance-ignores-gateway.log`.

**REFUTED**, and it is the control that makes #4's verdict trustworthy: two tests, one file,
one mutation, opposite outcomes. Had M2 produced two greens, #4's green would have been
uninformative.

---

#### 10. `EarthlinkSearchViewModelSeamTest.kt:1200` — `testGetResellerBalance_apiFailure_resultsInNullNotZero` — **REFUTED** (`reading`)

**Production behaviour named:** a gateway balance failure must not surface as `0.0`
(RED Invariant 1 — a legitimate zero is not a missing value).

**Killing evidence.** The test seeds the local as `0.0` and then either assigns the call
result or `null` in the `catch`. `assertNull(resellerBalance)` therefore **rejects the
zero case**: if the ViewModel returned `0.0` on failure, the assertion fails. That is a
real discriminating power, not a tautology — the same structure Task 2 used to clear `F6`.
It reaches real production (`vm.getResellerBalance()` → `gateway.getBalance()`).

**What reading cannot settle, stated as such:** the test cannot distinguish *"threw"* from
*"returned null"*, so it does not pin which mechanism production uses, and it was not
mutation-proven in this task. The verdict is refutation, not confirmation, and rests on the
production-path trace.

---

#### 11. `Workstream7And8SafetyNetTest.kt:65` — `testUtowerDebtResolverPriority3AnchorsOnExplicitDebt` — **REFUTED** (`mutation`)

**Production behaviour named:** `UtowerDebtResolver.resolveDebtForAccount` Priority 3 must
anchor on `explicitSourceDebt` — the uTower authoritative baseline (RED Invariant 1). The
test's comment carries its own counterfactual: *"Without explicitSourceDebt, openingDebt is
50000.0 + 10000.0 = 60000.0."*

**The mutation (M8)** — `UtowerDebtResolver.kt:49`, `explicitSourceDebt` dropped from the
opening-debt expression:

```kotlin
// BEFORE
val openingDebt = explicitSourceDebt ?: account.openingDebtIqd.takeIf { it >= 0.0 } ?: account.debtIqd
// AFTER (R13-T3 transient mutation M8)
val openingDebt = account.openingDebtIqd.takeIf { it >= 0.0 } ?: account.debtIqd
```

**Result: the test goes RED, with the counterfactual value its own comment predicted.**

| | XML `tests` | `failures` | `errors` |
|:---|:---|:---|:---|
| Before (post-revert clean-tree run, §3.3) | 1 | 0 | 0 |
| After mutation M8 | 1 | **1** | 0 |

```console
java.lang.AssertionError: expected:<35000.0> but was:<60000.0>
```

Log: `r13-t3-m8-debtresolver-ignores-explicit.log`. `BUILD FAILED in 1m 19s`.

**REFUTED.** The expected value `35000.0` is primitive arithmetic written in the test
(`25000.0` explicit + `10000.0` took), derived from the business rule and not from
production — exactly the independent-oracle standard.

---

#### 12 & 13. `BugMoney01PdfHidesSubscriberCreditTest.kt:47` and `:56` — **REFUTED** (`mutation`), with a measured coverage limitation

**Production behaviour named:** `PdfStatementGenerator.balanceTextFor`
(`PdfStatementGenerator.kt:38-42`) rendering a running balance for a customer-facing PDF.

**The mutation (M6)** — the file's own regression defect, `BUG-MONEY-1`, re-introduced
verbatim from the test's KDoc (every value `<= 0.0` collapsed to "settled"):

```kotlin
// AFTER (R13-T3 transient mutation M6)
internal fun balanceTextFor(debtAfterIqd: Double): String = when {
    debtAfterIqd <= 0.0 -> "0 د.ع (خالص)"
    else -> String.format(Locale.US, "%,.0f د.ع", debtAfterIqd)
}
```

**Result — and this is the finding:**

| Test | in cohort? | Baseline | After M6 |
|:---|:---|:---|:---|
| `negativeDebtAfter_rendersAsVisibleCredit_notAsZeroSettled` | **no** — 2 assertions | pass | **FAIL** |
| `exactlyZeroDebtAfter_stillRendersAsSettled` (`:47`) | yes | pass | **pass** |
| `positiveDebtAfter_rendersAsOutstandingAmount` (`:56`) | yes | pass | **pass** |

XML: before `tests=3 failures=0 errors=0`; after M6 `tests=3 failures=1 errors=0`.
Log: `r13-t3-m6-bugmoney01-reintroduced.log`. `BUILD FAILED in 1m 10s`.

**Measured: the only test in the file that detects `BUG-MONEY-1` is the one with two
assertions, and it is therefore outside the cohort entirely.** Both single-assertion members
pass with the bug present. This is a structural bias in the cohort, not bad luck: a
regression test for a boundary bug needs a negative case to be worth anything, and the
negative case is what pushes it to two assertions and out of the cohort.

**The verdict is nevertheless REFUTED, and here is the executed evidence for that** — M6 does
*not* answer the `F5` question for these two, because it did not touch the behaviour *they*
name, so I ran a second mutation rather than rest on reading.

**The mutation (M10)** — the two rendered strings these two tests pin:

```kotlin
debtAfterIqd == 0.0 -> "0 د.ع (خالص) MUTATED"
else -> String.format(Locale.US, "%,.0f د.ع MUTATED", debtAfterIqd)
```

**Result: both go RED.** XML `tests=3 failures=2 errors=0` — the two cohort members failed,
the negative-credit test passed (its string was untouched). Log:
`r13-t3-m10-pdf-strings-changed.log`. `BUILD FAILED in 45s`.

**REFUTED** — executed, not inferred. Both assertions are bound to production output with an
independent literal oracle (the KDoc derives the expected rendering from
`.forensic-bug02/real_data.json`, not from production code). **The recorded limitation stands
as a coverage gap for Task 5, not as a fake-evidence finding:** these two tests cannot detect
a re-introduction of the bug whose regression they accompany.

---

#### 14. `DataIntegrityReleaseGateTest.kt:324` — `roundTrip_amountIqd_preservedThroughMoshiOutboxAndValidator` — **REFUTED** (`reading`)

**Production behaviour named:** a ledger `amountIqd` must survive Moshi serialisation →
`SyncRepositoryImpl.buildOutboxPayloadMap` → `RemoteEntityValidator.validateAndMapLedgerEntry`
(H-3's actual serialisation mechanism).

**Killing evidence:** the value asserted on can only be produced by that production chain —
the test builds a `LocalLedgerEntry` with `amountIqd = 157500.0`, round-trips it through real
Moshi adapters and the real repository and validator, and compares against the literal
`157500.0` with a `0.001` delta. The shared `roundTripLedgerEntry` helper (`:175-190`) also
asserts the validator returned `Valid`, so a fail-closed rejection cannot pass silently.

**Not mutation-proven in this task.** The class-level mutation M4 that would have exercised
the BalanceCalculator half of the chain was not aimed here, and no dedicated mutation was run
for this test within the run budget. The verdict is a refutation by production-path trace
only, and is labelled as such.

---

#### 15. `Phase5DestructiveActionReleaseGateTest.kt:81` — `testNoOtherUiScreens_callClearLocalData` — **REFUTED** (`reading`, with a measured caveat)

**Production behaviour named:** no UI screen outside the debug-gated `SettingsScreen` may call
or expose `clearLocalData` (RC-07 / `INV-15`).

**Killing evidence:** the assertion reads real source files from disk and rejects the
forbidden call. It is not vacuous in the way a loop can be — the directory is resolved by
`findSourceDir`, which calls `error(...)` (`:44`) if the directory is absent, so an empty walk
fails loudly rather than passing silently. That is the `F7` failure mode, and it is guarded.

**The caveat, which is why this ranks last (§3.2 rule 5):** see §3.5.

---

### 3.5 The scanner counts assertions *syntactically*, and a loop hides them

`testNoOtherUiScreens_callClearLocalData` and its sibling
`testNoRawBuildConfigReferencesInUiCode` (`:115`) each contain **one** `assert*` call,
syntactically inside a `for` loop over every UI source file. Measured on this tree:

```console
UI .kt files walked by the loop: 27
  skipped by the allowlist (SettingsScreen.kt / DashboardViewModel.kt): 2
=> runtime assertion executions in testNoOtherUiScreens_callClearLocalData: 25
```

**One syntactic assertion is 25 runtime assertions.** The scanner's cohort is a *syntactic*
count, so these two tests are the **strongest** members of the cohort by discriminating
power and the scanner cannot tell. They are ranked 15th and 13th-of-file on that basis, which
is the opposite of their true weight.

**This is a property of the certified instrument, reported and not acted upon.** Per the
binding constraint the scanner was not edited and its fixtures were not touched; the
self-test was not re-run. If the controller wants the cohort to rank by *runtime* assertion
count, that is a scanner change and a new certification, not a Task 3 repair.

---

### 3.6 Stale evidence citations, found while tracing (not findings about evidence)

Four tests cite production line numbers that no longer point at the code they name. These are
**documentation-accuracy observations**, not evidence defects, and no verdict rests on them —
in each case the cited behaviour exists and the tests were adjudicated against the real
location.

| Test | Cites | Actually at |
|:---|:---|:---|
| `DataIntegrityReleaseGateTest.kt:606` | `Repositories.kt:2358-2362` — "`deleteTransaction` calls `correctTransaction`" | `deleteTransaction` is `Repositories.kt:2693`; `:2358-2362` is inside `addPaymentInternal`'s idempotency block |
| `DataIntegrityReleaseGateTest.kt:923` | `AppDatabase.kt:768-776 (MIGRATION_8_9)` | `MIGRATION_8_9` is `AppDatabase.kt:830-838` |
| `DataIntegrityReleaseGateTest.kt:534` | `SyncRepositoryImpl.kt:713` | `dataMap.remove("rawJson")` (ledger path) is `SyncRepositoryImpl.kt:719` |
| `DataIntegrityReleaseGateTest.kt:521` | `SyncRepositoryImpl.kt:710` | account-path strip is `SyncRepositoryImpl.kt:716` |

The first row matters beyond tidiness: the INV-02 test cites a line that does not contain the
function it names. That is consistent with the behaviour having been *moved* out of the test
at some point, but **I did not establish that history and do not assert it** — git history was
not consulted. The current-state finding stands on its own: the behaviour is not called.

---

### 3.7 The revert gate, verified

Ruling 1 required `git status --porcelain` to be empty before each new mutation, and the
production files byte-identical to `c09918d` at the end. Both were **checked, not assumed**,
after every one of the ten mutations.

> **Correction, added after review — read this before the transcript below.** An earlier
> version of this section presented a single unqualified transcript as end-state proof, and
> **two of its lines were false at `HEAD`**: `git diff --stat c09918d` and
> `git diff c09918d --name-only` were shown as empty / `Count: 0`. Only the `*.kt`-scoped
> lines were and are true. For a section whose thesis is *"a green barrier is not evidence
> that the barrier works"*, a transcript that is green for the wrong reason is the single
> least tolerable error available, and this was one.
>
> **The cause, corrected after re-review — my first account of it was arithmetically
> impossible.** I originally wrote that the empty output came from a tree state that *"still
> held the uncommitted 900-line addition"*. It cannot have. A worktree holding that addition
> makes `git diff --stat c09918d` return 900 insertions and `--name-only` return `Count: 1` —
> the same figures the end state below shows, which would also have made my own sentence
> "they differ from the pre-commit figures above" self-contradictory.
>
> **The only tree state that yields empty output is one identical to `c09918d`** — that is,
> before §3 was written at all. And that is exactly when the check was run: after the last
> post-revert execution and **before** the document was edited, when the working tree
> genuinely was at `c09918d` and the figures were **true**. The recorded output at that moment
> was:
>
> ```console
> PS> git status --porcelain
> PS> [clean]
> PS> diff-vs-base files: 0
> ```
>
> **So nothing was fabricated, and that is not exculpatory.** The defect is sharper than
> invention: a *valid* measurement was carried into §3.7 and there presented as the end-state
> proof, where it is false. It is the same class as the thesis of this document — a real
> result, in the wrong place, doing duty it cannot do. A gate that was checked and passed is
> not evidence that the gate is green *now*; a diff measured before the edit is not evidence
> of the diff after it. Both are true statements about the wrong moment.
>
> The commands are now split by the tree state each was run against, and the end-state
> figures are labelled as the commit state they describe.

**The gate, during the mutation phase.** The first, third and fourth commands below were run
after each of the ten reverts, before the next mutation began, with the worktree clean and
nothing staged. **The `*.kt`-scoped diff is attested at the end state only** — see the note
after the block:

```console
PS> git status --porcelain
PS> [empty -- this empty output IS the evidence, and it was checked 10 times]

PS> git diff --stat
PS> [empty]

PS> Get-ChildItem app\src -Recurse -Include *.kt |
      Select-String -Pattern "R13-T3 TRANSIENT MUTATION"
Count: 0
```

Every mutation carried an `R13-T3 TRANSIENT MUTATION` marker comment so a missed revert would
be greppable rather than invisible. The marker count is `0` across the whole of `app\src` —
which is the tree the marker scan covered, not the entire repository, and the distinction is
stated rather than rounded away.

> **Correction, added after re-review.** This block previously carried
> `git diff c09918d --name-only -- "*.kt" | Measure-Object` → `Count: 0` and asserted that
> *all four* commands *"was run after each of the ten reverts"*. The per-revert evidence
> attests `git status --porcelain`, `git diff --stat` and the marker count; the `*.kt`-scoped
> figure was checked once, at the end. `git diff --stat` being empty is in fact equivalent to
> the `*.kt` claim at that point in the task, because the document had not been written yet —
> but the honest statement is that the byte-identity check is an **end-state** measurement, not
> a per-revert one, and it is presented below as such.

**The end state, as at commit `8832454`** (the Task 3 commit; the worktree was clean at that
commit). These are the figures for *that* commit state, not a rolling `HEAD`:

```console
PS> git status --porcelain
PS> [empty]

PS> git diff --stat c09918d
 .../LL-ROUND-13-TEST-EVIDENCE-AUDIT.md             | 900 +++++++++++++++++++++
 1 file changed, 900 insertions(+)

PS> git diff c09918d --name-only | Measure-Object
Count: 1                       <-- the document itself; expected, not leakage

PS> git diff c09918d --name-only -- "*.kt" | Measure-Object
Count: 0                       <-- the claim that matters: no production file differs
```

> **Correction, added after re-review — this label decays.** The heading previously read
> *"re-derived at `HEAD` = `8832454`"*, which is a label that stops being true at the next
> commit: each subsequent commit to this document adds lines, so the same command returns a
> larger insertion count while `Count: 1` and the `*.kt` `Count: 0` hold. It is now labelled as
> the commit state it describes. The `*.kt` figure is the one that is supposed to be
> invariant, and it is.

**The one figure that carries the Ruling 1 guarantee is the last one: `Count: 0` for
`*.kt`.** Every `.kt` file in the repository is byte-identical to `c09918d`. The single
differing file is this document, which is the intended output of the task and contains no
code.

**Post-revert re-execution.** Re-run *from a clean tree* after the mutation phase, to show
the reverts landed rather than asserting it. The true chronological order, taken from the
log files' own timestamps, is below — **it is not the order an earlier version of this
document implied**, and the correction matters (see the disclosure immediately after the
table):

| Order | Run | Class | Baseline | Post-revert | Log mtime |
|:---|:---|:---|:---|:---|:---|
| 1 | PA | `Workstream7And8SafetyNetTest.testUtowerDebtResolver…` | 1 / 0 / 0 † | **1 / 0 / 0** | 13:41:46 |
| 2 | PC | `DataIntegrityReleaseGateTest` (full) | 36 / 0 / 0 | **36 / 0 / 0** | 13:43:45 |
| 3 | PB | `BugMoney01PdfHidesSubscriberCreditTest` | 3 / 0 / 0 | **3 / 0 / 0** | 13:50:22 |

† this single method had no pre-mutation baseline run of its own; its "before" number is the
post-revert run, recorded as such in §3.8 rather than back-filled.

> **Correction, added after review — M9's revert is evidenced by the `git status` gate only,
> not by a post-revert execution.** The timestamps show the true order was
> `M8 (13:39:44) → PA (13:41:46) → PC (13:43:45) → M9 (13:46:11) → M10 (13:48:27) → PB
> (13:50:22)`. **The full-class `DataIntegrityReleaseGateTest` re-execution (PC, 13:43:45)
> therefore completed *before* M9 was ever applied** — and M9 mutated
> `RemoteSyncCoordinator.kt`, and `#5`/`:776` and `#6`/`:819` both live in the very class PC
> ran. An earlier version of this section presented the order as `M8 → M9 → M10 → PA → PB →
> PC`, which placed PC last and made it look as though every revert had been re-executed.
>
> Consequence, stated plainly: **M9's revert rests on the `git status --porcelain` gate, on
> the `*.kt` byte-identity check against `c09918d`, and on the zero-marker count — not on a
> post-revert test run.**
>
> **Correction, added after re-review — the coverage count is six of ten, not nine and not
> eight.** The earlier text here claimed *"M1–M8 and M10 are covered by re-execution: M1/M3/M4/
> M5/M7 mutate files exercised by PC"*. That silently credited M1 and M3 to PC, and **both are
> wrong**: the only occurrences of `deleteTransaction` and `MIGRATION_8_9` anywhere in
> `DataIntegrityReleaseGateTest.kt` are inside **comments** (`:610`, and `:925/:932/:937-941/
> :972`). There is no call to either. A fresh Robolectric database is created at the current
> schema version, so `MIGRATION_8_9` does not execute either. PC therefore exercises neither
> M1's nor M3's mutation, whatever else it runs.
>
> Re-derived by counting call sites, not by recalling the previous sentence:

> | Mutation | Mutated symbol | Executed by a post-revert run? |
> |:---|:---|:---|
> | M4 | `BalanceCalculator.reconstructCurrentPosition` | **yes** — 14 call sites in the class PC re-ran |
> | M5 | `SyncRepositoryImpl.buildOutboxPayloadMap` | **yes** — 5 real calls in the class PC re-ran |
> | M7 | `TransactionTypeNormalizer.normalizeTransactionType` | **yes** — 1 real call (`:1226`) in the class PC re-ran |
> | M6 | `PdfStatementGenerator.balanceTextFor` | **yes** — 3 calls in the class PB re-ran |
> | M10 | `PdfStatementGenerator.balanceTextFor` | **yes** — same class PB re-ran |
> | M8 | `UtowerDebtResolver.resolveDebtForAccount` | **yes** — 1 real call (`:84`) in the class PA re-ran |
> | M1 | `Repositories.deleteTransaction` | **no** — comment-only reference in PC; no call |
> | M2 | `EarthlinkSearchViewModel.getResellerBalance` | **no** — zero references in all three classes; no `postrevert-seambalance` log exists |
> | M3 | `AppDatabase.MIGRATION_8_9` | **no** — comment-only references, and no migration runs on a fresh database |
> | M9 | `RemoteSyncCoordinator.processEvent` | **no** — PC exercises it, but PC ran before M9 was applied |
>
> **Six of the ten reverts are backed by post-revert re-execution; four (M1, M2, M3, M9) rest on
> the `git status` gate, the `*.kt` byte-identity check and the zero-marker count alone.** The
> re-reviewer identified M2 as uncovered and put the figure at eight; counting the call sites
> myself gives six, because M1 and M3 are uncovered for the same reason M2 is. I am recording
> my count rather than the reviewer's, because the arithmetic is checkable and the reviewer's
> figure credits PC with exercising two mutations it provably does not touch.
>
> **The reason those four are uncovered is the same fact as findings `#1` and `#2`.** PC cannot
> confirm M1's revert because the gate class never calls `deleteTransaction`; PC cannot confirm
> M3's revert because the gate class never runs a migration. **The blind spots in the evidence
> and the blind spots in the tests are the same blind spots.** That is worth more than the
> coverage number itself.
>
> The reviewer independently confirmed no leakage resulted. The lesson is procedural and is
> recorded in the fix rounds of the task report: **a post-revert re-execution only covers
> mutations whose mutated code that run actually calls, applied before it ran; an ordering
> claim needs a timestamp, and a coverage claim needs a counted call site.**

---

### 3.8 Every run, and every run discarded

All 16 runs, all with `--rerun-tasks`, all reporting `35 actionable tasks: 35 executed`.

**The order below is chronological, taken from the log files' own `LastWriteTime`, not from
my narrative of what I did.** An earlier version of this table was grouped mutations together
and post-revert runs together, which silently implied an execution order that was wrong —
see the M9 disclosure in §3.7.

| # | Purpose | Selection | `tests`/`failures`/`errors` | Log | mtime |
|:---|:---|:---|:---|:---|:---|
| R1 | baseline | `*DataIntegrityReleaseGateTest*` | 36/0/0 | `r13-t3-baseline-dataintegrity.log` | 13:15:52 |
| M1 | `deleteTransaction` → physical `DELETE` | INV-02 | 1/**0**/0 | `r13-t3-m1-inv02-physical-delete.log` | 13:17:31 |
| B1 | baseline | 2 seam-balance methods | 2/0/0 | `r13-t3-baseline-seambalance.log` | 13:19:37 |
| B2 | baseline | `*BugMoney01*` | 3/0/0 | `r13-t3-baseline-bugmoney01.log` | 13:22:35 |
| M2 | `getResellerBalance` ignores gateway | 2 seam-balance methods | 2/**1**/0 | `r13-t3-m2-resellerbalance-ignores-gateway.log` | 13:25:18 |
| M3 | `MIGRATION_8_9` defaults unsafe | migrationDefaults | 1/**0**/0 | `r13-t3-m3-migration-defaults-unsafe.log` | 13:28:22 |
| M4 | BalanceCalculator filter removed | INV-04 | 1/**1**/0 | `r13-t3-m4-balancecalculator-no-history-filter.log` | 13:30:35 |
| M5 | ledger `rawJson` strip removed | rawJson ledgerPath | 1/**1**/0 | `r13-t3-m5-rawjson-ledger-strip-removed.log` | 13:32:53 |
| M6 | `BUG-MONEY-1` re-introduced | `*BugMoney01*` | 3/**1**/0 | `r13-t3-m6-bugmoney01-reintroduced.log` | 13:34:55 |
| M7 | `"note"` → `"took"` | oracle_noteTransaction | 1/**0**/0 | `r13-t3-m7-note-reclassified-as-took.log` | 13:37:29 |
| M8 | resolver ignores `explicitSourceDebt` | debt resolver | 1/**1**/0 | `r13-t3-m8-debtresolver-ignores-explicit.log` | 13:39:44 |
| PA | post-revert | debt resolver | 1/0/0 | `r13-t3-postrevert-workstream78.log` | 13:41:46 |
| PC | post-revert | `*DataIntegrityReleaseGateTest*` | 36/0/0 | `r13-t3-postrevert-dataintegrity-full.log` | 13:43:45 |
| M9 | all remote events suppressed | INV-12 + idempotency | 2/**0**/0 | `r13-t3-m9-all-events-suppressed.log` | 13:46:11 |
| M10 | the two pinned PDF strings changed | `*BugMoney01*` | 3/**2**/0 | `r13-t3-m10-pdf-strings-changed.log` | 13:48:27 |
| PB | post-revert | `*BugMoney01*` | 3/0/0 | `r13-t3-postrevert-bugmoney01.log` | 13:50:22 |

**PA and PC fall between M8 and M9.** That is not a formatting quirk: it means the
full-class re-execution happened before M9 existed, which is why M9's revert is evidenced by
the `git status` gate rather than by a re-run (§3.7). The ordering is stated here so the next
task does not have to rediscover it.

**These 16 logs are all still on disk** in
`C:\Users\ALMAHD~1\AppData\Local\Temp\opencode\`, and the reviewer re-verified this entire
table from them. They are outside the repository for a reason specific to this task: an
untracked file inside the worktree would appear in `git status --porcelain` and **destroy the
Ruling 1 revert gate**. They remain untracked and would not survive a temp-directory clean,
so the mutation text, commands, XML numbers and log names are all transcribed into §3 to
make re-derivation possible without them.

**Runs discarded — one, and it is mine:**

- **A chained double launch produced no run at all.** B1 and B2 were launched as a single
  `cmd /c` string containing an embedded newline. B1 executed (its log and XML exist); **B2
  never started** — no log file was created and no XML was written. I detected it by polling
  for `r13-t3-baseline-bugmoney01.log`, confirmed no Gradle test process was alive, and
  re-launched B2 standalone, which ran normally (3/0/0). **Discarded: nothing was counted
  from it.** The lesson is the one Ruling 2 exists to enforce, arriving from a different
  direction: a silent no-op is indistinguishable from a pass unless you check for the
  artefact. Checking the XML, not the exit path, is what caught it.

**No repetition batch was run, and none is claimed.** Unlike Task 2, no test in this cohort
was executed more than twice (once mutated, once post-revert), so there is no stability
count here and none is implied. Each `F5` verdict rests on a single decisive execution, which
is sufficient for the question asked — *does this mutation change the outcome* — and would not
be sufficient for a flakiness claim, which is not made.

---

### 3.9 What this task did NOT execute

Stated as plainly as what it did, per `LL-BUG-HUNT-METHODOLOGY` §7.

- **54 of the 69 cohort members were not adjudicated.** They fall below the cut on the §3.2
  rule, and the rule weighted toward financial/ledger files, `DataIntegrityReleaseGateTest`,
  and the `F5` mechanism. **The total of 54 is correct and was correct before this
  correction; the breakdown that produced it was not.** Re-derived by matching the 15
  adjudicated test names against the scanner's cohort list, the 54 break down as:

  | Remaining | Count | Note |
  |:---|:---|:---|
  | `DataIntegrityReleaseGateTest.kt` | **14** | 8 of its 22 were adjudicated (`:606 :923 :1275 :776 :819 :627 :534 :324`); the rest are the *other* round-trip fields, the account-path `rawJson` twin at `:521`, and the oracle / backup-restore cases |
  | `GetRemainingTimeTest.kt` | 6 | pure UI string formatting |
  | matcher group — `DefectRemediationSeamTest` (3) + `Workstream7ImportMatchingCollisionTest` (2) + `HistoricalSubscriberMatchingSafetyTest` (3) | **8** | `assertFalse(matches…)` on a single input |
  | `ApiErrorSemanticsRegressionTest.kt` | 3 | see the escalation below — **not** a uniform group |
  | `Phase2RemoteVersionAdversarialTest.kt` | 3 | |
  | `SurgicalFixAdvanceAndRenewalTest.kt` | 3 | financial operation classification; omitted from the earlier breakdown entirely |
  | `UtowerDateParserTest.kt` | 2 | date parsing |
  | `BugSri01CoordinatorAuditDaoWiringTest.kt` | 2 | wiring assertions |
  | `Phase5DestructiveActionReleaseGateTest.kt` | 1 | `:115`, the `BuildConfig` twin of `#15` |
  | single-member files | **12** | 13 in the cohort, less the one adjudicated (`Workstream7And8SafetyNetTest.kt:65` = `#11`) |
  | `EarthlinkSearchViewModelSeamTest.kt` | **0** | all 3 adjudicated (`#4`, `#9`, `#10`) |
  | `BugMoney01PdfHidesSubscriberCreditTest.kt` | **0** | both adjudicated (`#12`, `#13`) |
  | **Total** | **54** | |

  **No claim is made about any of them.** "Below the cut" is a ranking statement, not a
  clearance.

- **The escalation, corrected and reordered.** An earlier version flagged all three
  `ApiErrorSemanticsRegressionTest` members as *"structurally close to what `#5` and `#6`
  turned out to be."* **That was wrong for two of the three, and the order matters.** Re-derived
  by reading each test against the initial state of the value it asserts:

  | Test | Shape | Verdict on exposure |
  |:---|:---|:---|
  | **`:343`** `testApi03_dashboard_networkFailureYieldsNullUnavailable` | mocks `getTestUsersCount` to throw, then asserts `assertNull(vm.testCount.value)`. **`_testCount` initialises to `null`** (`DashboardViewModel.kt:40`), so a `loadDashboardData` that did nothing would leave it `null` and the assertion would **pass** | **The sharpest — exactly the `#5`/`#6` shape.** Absence satisfies it. Escalate first |
  | **`:304`** `testApi03_repository_legitimateZeroReturned` | mocks `{"value":0}`, asserts `count == 0` off `gatewayImpl.getTestUsersCount()`. A gateway returning a constant `0` would pass | A **different** shape — closer to `#4` (asserts something real that a stub satisfies) than to `#5`/`#6`. Escalate second |
  | **`:315`** `testApi03_dashboard_legitimateZeroPreserved` | asserts `assertEquals(Integer.valueOf(0), vm.testCount.value)` off an initial value of `null`, so it *demands* `0` and **rejects** `null` | **Not exposed at all.** Absence cannot satisfy it. **Dropped from the escalation** |

  These are reading-based classifications of tests I did **not** adjudicate. They rank the
  escalation; they are not verdicts, and none of the three was executed in this task.
- **`corruptionInjection_rawJson_strippedFromCloudPayload_accountPath` (`:521`) was not run.**
  It is the account-path twin of #8 and would be expected to behave identically against a
  mutation at `SyncRepositoryImpl.kt:716`. Expected is not measured; it is unadjudicated.
- **`testBalanceAfterMath_knownBalance_computesCorrectly` (`:1214`) was not adjudicated.** It
  is outside the cohort (2 assertions) but appears to share #4's defect. Flagged, unproven.
- **The full 798-test suite was not run**, per Ruling 3. **The claim "the other 783 tests are
  unaffected" rests on the absence of committed changes, not on an observed green run.**
- **The 9 mutated production files were each touched at exactly one point**, and most were
  read only around that point: `Repositories.kt` (M1), `EarthlinkSearchViewModel.kt` (M2),
  `AppDatabase.kt` (M3), `BalanceCalculator.kt` (M4), `SyncRepositoryImpl.kt` (M5),
  `PdfStatementGenerator.kt` (M6, M10), `TransactionTypeNormalizer.kt` (M7),
  `UtowerDebtResolver.kt` (M8), `RemoteSyncCoordinator.kt` (M9). **Nothing in this section
  says anything about the rest of their behaviour**, and the counts elsewhere in this section
  should not be read as a claim that these files were audited.
- **No product defect is asserted.** Every mutation was reverted. Where a mutation would have
  caused real damage (M1, M3, M4), that is a statement about the *test's blindness*, not
  about the shipped code, which is correct as committed.
- **The scanner was not audited and not modified.** §3.5 is a reported property, not a change.
  The certified self-test `scripts/test_evidence_scanner_fixtures.py` was **not re-run**.
- **The `F4` finding from Task 2 was not touched**, per Ruling 4 — not repaired, not
  re-adjudicated, not pre-conditioned. The six deferred minors N1–N6 were left alone.
- **No git history was consulted**, so no claim is made about *when* or *why* the INV-02 test
  stopped calling `deleteTransaction` (§3.6).
- **`#10`, `#14` and `#15` are refutations by reading, not by execution.** They are the
  weakest three verdicts in this section and are labelled as such at each entry.

---

### 3.10 Verdict table

| # | Location | Test | Verdict | Evidence |
|:---|:---|:---|:---|:---|
| 1 | `DataIntegrityReleaseGateTest.kt:606` | `invariant_INV02_…ledgerDeleteUsesContraEntry` | **CONFIRMED** | mutation M1 |
| 2 | `DataIntegrityReleaseGateTest.kt:923` | `backupRestore_migrationDefaults_safeForBalanceCalculator` | **CONFIRMED** | mutation M3 |
| 3 | `DataIntegrityReleaseGateTest.kt:1275` | `oracle_noteTransaction_zeroFinancialImpact` | **CONFIRMED** | mutation M7 |
| 4 | `EarthlinkSearchViewModelSeamTest.kt:1223` | `testBalanceAfterMath_unknownBalance_isNull` | **CONFIRMED** | mutation M2 |
| 5 | `DataIntegrityReleaseGateTest.kt:776` | `invariant_INV12_noOutboxLoopsOnRemoteApply` | **CONFIRMED** | mutation M9 |
| 6 | `DataIntegrityReleaseGateTest.kt:819` | `idempotency_duplicateSyncEvent_zeroNewEntries` | **CONFIRMED** | mutation M9 |
| 7 | `DataIntegrityReleaseGateTest.kt:627` | `invariant_INV04_zeroDoubleApplication_…` | **REFUTED** | mutation M4 → red |
| 8 | `DataIntegrityReleaseGateTest.kt:534` | `corruptionInjection_rawJson_…_ledgerPath` | **REFUTED** | mutation M5 → red |
| 9 | `EarthlinkSearchViewModelSeamTest.kt:1168` | `testGetResellerBalance_success_returnsValue` | **REFUTED** | mutation M2 → red |
| 10 | `EarthlinkSearchViewModelSeamTest.kt:1200` | `testGetResellerBalance_apiFailure_resultsInNullNotZero` | **REFUTED** | reading |
| 11 | `Workstream7And8SafetyNetTest.kt:65` | `testUtowerDebtResolverPriority3AnchorsOnExplicitDebt` | **REFUTED** | mutation M8 → red |
| 12 | `BugMoney01PdfHidesSubscriberCreditTest.kt:47` | `exactlyZeroDebtAfter_stillRendersAsSettled` | **REFUTED** | mutations M6, M10 |
| 13 | `BugMoney01PdfHidesSubscriberCreditTest.kt:56` | `positiveDebtAfter_rendersAsOutstandingAmount` | **REFUTED** | mutations M6, M10 |
| 14 | `DataIntegrityReleaseGateTest.kt:324` | `roundTrip_amountIqd_preservedThrough…` | **REFUTED** | reading |
| 15 | `Phase5DestructiveActionReleaseGateTest.kt:81` | `testNoOtherUiScreens_callClearLocalData` | **REFUTED** | reading |

**6 CONFIRMED, 9 REFUTED, 0 OPEN.**

Every candidate resolves. No candidate was left OPEN, and none was given a verdict to tidy
the list: #6 was genuinely heading for `OPEN` and was settled only because mutation M9 turned
out to answer it.

**The refuted half is the load-bearing half, twice over.**

1. **#7 vs #1 is the whole thesis in one pair of rows.** Two single assertions, same class,
   same file, the same RED-invariant family. One names the H-3 filter and *fails* when it is
   removed. The other names the no-physical-deletion rule and *passes* when physical deletion
   is introduced. A single-assertion count cannot tell them apart. Only the mutation did.
   **Scope note, added after review:** `#1`'s blindness is a fact about `#1`, not about
   Invariant 2 — `Workstream9AFinancialCorrectionTest.kt:262` guards the same invariant
   correctly and was confirmed red under the same mutation (§3.4 #1).
2. **#4 vs #9 is the same result in miniature**, from one mutation and one run: two tests in
   one file, opposite verdicts, the difference being whether the test actually calls
   production. Had #4 been cleared on reading ("it asserts something real"), the defect would
   have shipped — and had #9 been condemned on the same reading, real coverage would have
   been destroyed. Both errors were available on the evidence available *before* the runs.
   Neither survived them.

**What the six confirmations share.** This is a *different partition* from the "five of the six
in the barrier file" count in the closing lesson — that one groups by **where** the test lives,
this one groups by **how the assertion is satisfied**. By mechanism: four of the six (`#1`,
`#2`, `#3`, `#4`) are satisfied by setup or by the *absence* of the behaviour: an assertion on
a row the test just inserted, a hardcoded copy of a migration's defaults, a zero amount that
makes a type claim arithmetically irrelevant, and a test that never leaves the test file. The
other two (`#5`, `#6`) are satisfied by `0 == 0`. **In all six, the assertion passes in a
world where the production behaviour is absent, wrong, or never invoked** — which is
precisely and only what `F5` asks.

**Carry-forward for Task 5 (repairs), in the order the evidence supports:**

- **`#1` is the priority, and it is a REPAIR, not a new test.** RED Invariant 2 already has a
  working guard — `Workstream9AFinancialCorrectionTest.kt:262` calls the real
  `deleteTransaction` at `:285` and asserts the original row survives (`:289`), that there are
  exactly two entries (`:298`), and that a `correctsEntryId` contra-entry exists (`:299-302`);
  the reviewer confirmed it goes red under M1. **`DataIntegrityReleaseGateTest.kt:606` should be
  made to do the same thing, or deleted in favour of it. Do not author a new INV-02 test** —
  one already exists and works, and the defect is that the *gate* test cannot see what the
  other test sees.
- `#2` and `#3` need *input* changes, not assertion changes — a real migration, a non-zero
  note amount. Adding assertions alone will not fix either.
- `#5` and `#6` need one precondition each (`assertNotNull(EventSyncResult.APPLIED)`,
  `assertEquals(1, countAfterFirst)`) and are otherwise sound. These are the cheapest real
  repairs in the set.
- `#4` and its out-of-cohort twin `:1214` are, on the evidence here, removable or should be
  rewritten to call production. **I did not establish that `:1214` is defective — only that
  it looks the same. Do not delete either on the strength of this section.**

---

### 3.11 Gate assessment for this task

The gates that mattered, and how each fared.

- **Execution confirms, reading only refutes — held.** All 6 `CONFIRMED` verdicts rest on a
  run that shows the test green with the behaviour removed or absent. The 3 reading-only
  verdicts (`#10`, `#14`, `#15`) are refutations, are labelled `reading` at each entry, and
  are named in §3.9 as the weakest claims in the section.
- **The revert gate — held, with one coverage limit now disclosed.** §3.7. `git status
  --porcelain` was empty after each of the ten reverts and was re-checked, not assumed; zero
  mutation markers remain; `git diff c09918d --name-only -- "*.kt"` is `Count: 0`, so every
  production file is byte-identical to the base. **Six of the ten reverts are additionally
  covered by post-revert re-execution; M1, M2, M3 and M9 are not** (§3.7, which gives the
  per-mutation table and the counted call sites behind that figure). M9 is uncovered because
  the full-class re-execution ran before M9 was applied (§3.7, ordering taken from log
  timestamps); M1 and M3 because the re-run class references the mutated symbols only in
  comments; M2 because no re-run class references `getResellerBalance` at all. Those four rest
  on the `git status` gate, the `*.kt` byte-identity check and the zero-marker count. Earlier
  versions of this bullet claimed nine of ten, then (in the fix round) implied eight; both
  overstated coverage, and both were corrected by counting call sites rather than by adjusting
  the previous sentence. An even earlier version claimed all three classes were re-executed
  post-revert under a heading saying the reverts were *"proven by execution"*.
- **Ruling 2 (Gradle will lie) — held.** 16 runs, `--rerun-tasks` on every one, XML read
  after every one, `testDebugUnitTest UP-TO-DATE` observed zero times. The one discarded run
  is disclosed in §3.8 with its cause.
- **Ruling 4 (adjudicating, not fixing) — held.** No repair was applied. The Task 2 `F4`
  finding was not touched. Ten production mutations, all reverted, none committed.
- **Ruling 5 (append only) — held.** This is a new `##` section. §1–§2 are byte-unchanged,
  as are Task 1's and Task 2's subsections.
- **The gate that got closest to failing — "is this test's *name* the claim I am judging?"
  Findings #2 and #12 turned on it.** In both, the test does something real and the *name*
  claims something else. The rule I applied, and recommend: **judge the test against the
  claim in its own name and section header, and report the mismatch explicitly rather than
  quietly grading the narrower thing it actually does.** Reporting only the narrow grade would
  have turned #2 and #12 into `REFUTED` and lost both findings; reporting only the name would
  have overstated them. Both are `CONFIRMED`/`REFUTED` *with the mismatch stated*.
- **Not reached — whether the suite as a whole is sound.** 15 of 69 adjudicated, 6 of 15 by
  mutation. The remaining 54 and the 3 largest files are Tasks 4–6, and nothing in this
  section speaks for them.

**The lesson worth carrying forward.** The suite's 798/798 green is compatible with *all six*
of these confirmations, because a test that is green for the wrong reason is green. **Five of
the six are in the one file this project names as its silent-corruption barrier** — only `#4`
(`EarthlinkSearchViewModelSeamTest.kt:1223`) is outside it — and that makes the concentration
worse than an earlier version of this paragraph stated, not better.

**All five share one shape: the test cannot observe the behaviour it names.** `#1` never
invokes it. `#2` asserts a hardcoded copy of it rather than the artefact. `#3`'s assertion is
arithmetically independent of it. `#5` and `#6` compare counts without ever asserting the
event was applied. `AGENTS.md` §9.1 names this file as the barrier generally and §4.2 names
Invariant 2 specifically for physical deletion — and `#1` is the Invariant 2 case, blind in
exactly the way §4.2 describes.

**A green barrier is not evidence that the barrier works; only breaking the code and watching
the gate fail — or pass — tells you which one you are holding.** And the converse, which is
the useful half: one mutation run against two tests in the same class is enough to tell a
barrier that works (`#7`, red) from one that cannot (`#1`, green). Nothing short of execution
distinguished them, and they sit 20 lines apart.
