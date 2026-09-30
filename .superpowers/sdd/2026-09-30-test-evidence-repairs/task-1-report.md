# Task 1 Report — The Four Tier-B Repairs (one precondition each)

**Plan:** `docs/superpowers/plans/2026-09-30-test-evidence-repairs.md`, "Task 1"
**BASE for diff:** `1b9fff7`
**Workspace:** `C:\Users\Almahdi-BOC\antigravity\el-audit-r13`
**Date:** 2026-09-30
**Gradle:** `.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --console=plain`, always with `--rerun-tasks`.
All figures below are read from the JUnit XML in `app\build\test-results\testDebugUnitTest\`, never from `BUILD SUCCESSFUL`.

**Log location.** `*.log` is not in `.gitignore`, so writing logs into the repo would have made
`git status --porcelain` non-empty and violated the transient-mutant constraint. All logs were
therefore written to `C:\Users\ALMAHD~1\AppData\Local\Temp\opencode\r13-t5-t1\` and are named per
the plan convention `r13-t5-t1-<purpose>.log`. The `<purpose>` names below are the filenames.

**Clean-code baseline before any change** (`r13-t5-t1-baseline.log`):
`DataIntegrityReleaseGateTest` tests=36 failures=0 errors=0; `ApiErrorSemanticsRegressionTest`
tests=11 failures=0 errors=0; `LocalAccountsViewModelTgzSyncTriggerTest` tests=3 failures=0 errors=0.

---

## Repair 1 — `LocalAccountsViewModelTgzSyncTriggerTest.kt:207` `testFailedTgzImport_corruptArchive_doesNotTriggerSync`

**Awaited state (per plan):** `viewModel.error.value != null` after a corrupt `.tgz`, established by
`LocalAccountsViewModel.kt:330` (failure message) or `:335` (catch).

**Assertion added** (matches the sibling at `:232`, which already does this correctly):

```kotlin
assertNotNull(
    "PRECONDITION | The corrupt .tgz must be reported as a failed import, otherwise " +
        "'failed import does not trigger sync' is satisfied by nothing having happened. " +
        "error=${viewModel.error.value}, importResult=${viewModel.importResult.value}",
    viewModel.error.value
)
```

**Mutant:** `LocalAccountsViewModel.kt:330` — the `if (!result.success)` branch no longer assigns
`_error.value`, so a failed import reports nothing. Transient, never committed.

| State | Command (abbreviated) | Log | tests | failures | errors |
|:--|:--|:--|--:|--:|--:|
| GREEN under mutant, **before** repair (the defect) | `--tests ...LocalAccountsViewModelTgzSyncTriggerTest` | `r13-t5-t1-m1-pre-repair.log` | 3 | 0 | 0 |
| RED under same mutant, after repair | same | `r13-t5-t1-m1-post-repair.log` | 3 | 1 | 0 |
| GREEN on clean code | same | `r13-t5-t1-rep1-clean.log` | 3 | 0 | 0 |

The RED assertion message confirms the awaited state was genuinely reached before the repair —
`error=null, importResult=ImportResult(..., success=false, ... errorMessage=Could not find uTower
database in the provided file.)`. Without the precondition the poll loop exits on `importResult != null`
at attempt 0, and `verifyNoInteractions` passes regardless of whether the failure was ever reported.

**`git status --porcelain` after reverting the mutant:**

```
 M app/src/test/java/com/example/ui/viewmodels/LocalAccountsViewModelTgzSyncTriggerTest.kt
```

(production file clean; only this task's intended test repair present)

---

## Repair 2 — `DataIntegrityReleaseGateTest.kt:776` `invariant_INV12_noOutboxLoopsOnRemoteApply`

**Awaited state (per plan):** the remote `AccountUpsert` was actually **applied**;
`processEvent` returns `EventSyncResult` and the return value was discarded at `:802`.

**Assertion added** (the plan's exact snippet — the discarded return value is now bound):

```kotlin
val result = coordinator.processEvent(event)
assertEquals(
    "PRECONDITION | The remote AccountUpsert must be APPLIED for this invariant to be meaningful. " +
        "Got $result instead.",
    EventSyncResult.APPLIED,
    result
)
```

The existing `assertEquals(outboxBefore, outboxAfter, …)` is **unchanged and unweakened**; it is now
meaningful because an apply is known to have happened.

### Faithfulness of the mutant — a finding worth recording

**The brief was right that the audit's old mutant is unfaithful, and I did not reuse it.** The old
mutant (`if (true || …)` suppressing every remote event) tests the *precondition* rather than the
*assertion*. I used **two new mutants**, because a single faithful mutant cannot show both halves of
this repair's evidence:

**Mutant A (the assertion-breaking mutant) — `MUTANT-R13-T1-2b`.** An outbox write added into
`RemoteSyncCoordinator.applyAccountUpsert`'s `APPLY_UPSERT` branch, right after `accountDao.upsert(account)`:

```kotlin
outboxDao.insert(SyncOutbox(
    entityType = "local_accounts", entityId = event.entityId,
    operation = "upsert", payloadJson = "{}", status = "pending",
    createdAt = System.currentTimeMillis()
))
```

This is a faithful echo-loop mutation: it breaks exactly what the test's own name claims
(`noOutboxLoopsOnRemoteApply`) while the event still applies. Result: **RED both before and after the
repair** — the existing assertion was already live against a real INV-12 violation, and the new
precondition does not mask it.

**Mutant B (the vacuity mutant) — `MUTANT-R13-T1-2a`.** `applyAccountUpsert`'s `APPLY_UPSERT` branch
silently drops the incoming upsert and returns `SKIPPED_DUPLICATE` instead of applying. This is the
mutant that proves the *defect*: without the precondition the test cannot tell an applied event from a
dropped one.

| State | Log | tests | failures | errors | Result |
|:--|:--|--:|--:|--:|:--|
| A, before repair (existing assertion is live) | `r13-t5-t1-m1-pre-repair.log` | 1 | 1 | 0 | RED — `INV-12 VIOLATED … Outbox before: 0, after: 1` |
| A, after repair (precondition does not mask the assertion) | `r13-t5-t1-m1-post-repair.log` | 1 | 1 | 0 | RED — same `INV-12 VIOLATED` message |
| **B, before repair — the defect** | `r13-t5-t1-m2-pre-repair.log` | 2 | 0 | 0 | **GREEN** (`INV12` and `idempotency` both pass with the upsert dropped) |
| **B, after repair** | `r13-t5-t1-m2-post-repair.log` | 1 | 1 | 0 | RED — `Got SKIPPED_DUPLICATE instead. expected:<APPLIED> but was:<SKIPPED_DUPLICATE>` |
| GREEN on clean code (with repairs 2+3) | `r13-t5-t1-rep23-clean.log` | 2 | 0 | 0 | GREEN |

Both mutants are transient and were reverted; the `MUTANT-R13-T1-2b` run required temporarily
restoring the base test file, after which repair 2 was re-applied verbatim.

**`git status --porcelain` after reverting both mutants:**

```
 M app/src/test/java/com/example/DataIntegrityReleaseGateTest.kt
 M app/src/test/java/com/example/ui/viewmodels/ApiErrorSemanticsRegressionTest.kt
 M app/src/test/java/com/example/ui/viewmodels/LocalAccountsViewModelTgzSyncTriggerTest.kt
```

(production file `RemoteSyncCoordinator.kt` clean)

---

## Repair 3 — `DataIntegrityReleaseGateTest.kt:819` `idempotency_duplicateSyncEvent_zeroNewEntries`

**Awaited state (per plan):** the first `processEvent` **created a ledger entry**; `countAfterFirst`
must be the pre-insert count + 1.

**Assertion added** (the plan's exact snippet, plus the `countBeforeFirst` capture the plan requires):

```kotlin
val countBeforeFirst = db.localLedgerEntryDao().getByAccountIdOneShot(parentAccount.id).size
coordinator.processEvent(ledgerEvent)
val countAfterFirst = db.localLedgerEntryDao().getByAccountIdOneShot(parentAccount.id).size
assertEquals(
    "PRECONDITION | The first apply must create exactly one ledger entry, otherwise " +
        "'no duplicates' is satisfied by nothing happening. Count after first: $countAfterFirst",
    countBeforeFirst + 1,
    countAfterFirst
)
```

The existing `assertEquals(countAfterFirst, countAfterSecond, …)` is unchanged. Note the plan's
Step 1 also prints a `processEvent` return-value block for this test; it is byte-identical to the
`:776` block, which is already present. I added it only once, at `:776`, to avoid a duplicate
assertion — the plan's own Self-Review states the `:819` shape is the `countBeforeFirst` capture.

**Mutant:** `MUTANT-R13-T1-3` — in `RemoteSyncCoordinator.applyLedgerUpsert`'s `APPLY_UPSERT` branch,
`ledgerDao.upsert(entry)` is removed so the coordinator still reports `APPLIED` and advances the
cursor, but no row is ever inserted. This is a faithful mutation of the awaited state (an apply that
reports success but inserts nothing). Transient, never committed.

| State | Log | tests | failures | errors | Result |
|:--|:--|--:|--:|--:|:--|
| GREEN under mutant, **before** repair (the defect) | `r13-t5-t1-m3-pre-repair.log` | 1 | 0 | 0 | **GREEN** — `0 == 0` with nothing inserted |
| RED under same mutant, after repair | `r13-t5-t1-m3-post-repair.log` | 1 | 1 | 0 | RED — `Count after first: 0 expected:<1> but was:<0>` |
| GREEN on clean code | `r13-t5-t1-rep23-clean.log` | 2 | 0 | 0 | GREEN (alongside repair 2) |

**`git status --porcelain` after reverting the mutant:**

```
 M app/src/test/java/com/example/DataIntegrityReleaseGateTest.kt
 M app/src/test/java/com/example/ui/viewmodels/LocalAccountsViewModelTgzSyncTriggerTest.kt
```

---

## Repair 4 — `ApiErrorSemanticsRegressionTest.kt:343` `testApi03_dashboard_networkFailureYieldsNullUnavailable`

**Awaited state (per plan):** `loadDashboardData` **ran** and the fetch was attempted;
`DashboardViewModel.kt:184` launches the job, `:190` sets null in the catch.

**Assertion added:**

```kotlin
verify(mockGateway, atLeastOnce()).getTestUsersCount()
```

placed immediately before the existing `assertNull(…, vm.testCount.value)`, which is unchanged.

### A deviation from the brief, and why

**The brief and the plan both direct me to match the sibling at `:315`, which asserts a *state value*
(`assertEquals(Integer.valueOf(0), vm.testCount.value)`). That shape is not available here, and I
did not invent a substitute for it — I used the gateway interaction instead, for a concrete reason.**

`_testCount` is initialised to `MutableStateFlow<Int?>(null)` at `DashboardViewModel.kt:40`, and the
catch block at `:190` assigns `null`. The awaited state and the value under test are the *same
observable with the same value*, so **no state assertion can distinguish "the fetch was attempted and
failed" from "the fetch never ran."** The sibling at `:315` escapes this because its expected value
`0` is reachable *only* by a completed fetch. This test's expected value `null` is reachable by doing
nothing. The awaited state here is not a state at all — it is the occurrence of the fetch — so the
only non-proxy observable is the gateway interaction. Using `_error` or `_isLoading` as a proxy would
have satisfied the letter of "add a precondition" while proving nothing about the named path, which
the plan explicitly forbids ("must reference the specific observable the test already polls for —
not a proxy").

**`atLeastOnce()`, not `times(1)` — a correction made mid-task, recorded here.** My first attempt used
`times(1)` and it failed on clean code with `TooManyActualInvocations: Wanted 1 time, but was 2 times`.
`DashboardViewModel.kt:63-65` has an `init { loadDashboardData() }` block, so construction *and* the
explicit `vm.loadDashboardData()` at `:366` each fetch. `atLeastOnce()` states exactly the awaited
state ("the fetch was attempted") without over-constraining an incidental count, and it still turns
red under the mutant (zero invocations). The deviation is recorded in the code comment at the
assertion.

**Mutant:** `MUTANT-R13-T1-4` — `DashboardViewModel.kt:184`'s `testCountJob` body no longer calls
`gateway.getTestUsersCount()`, so the fetch is never attempted and `_testCount` keeps its initial
`null`. Transient, never committed.

| State | Log | tests | failures | errors | Result |
|:--|:--|--:|--:|--:|:--|
| GREEN under mutant, **before** repair (the defect) | `r13-t5-t1-m4b-pre-repair.log` | 11 | 1 | 0 | **the target test PASSES**; only the sound sibling `:315` fails |
| RED under same mutant, after repair | `r13-t5-t1-m4b-post-repair.log` | 11 | 2 | 0 | target test RED — `WantedButNotInvoked: earthlinkGateway.getTestUsersCount` |
| GREEN on clean code | `r13-t5-t1-allfour-clean.log` | 11 | 0 | 0 | GREEN |

The pre-repair run is the cleanest proof of this repair's value: under the same mutant, the sound
sibling `testApi03_dashboard_legitimateZeroPreserved` **fails** while the blind test
`testApi03_dashboard_networkFailureYieldsNullUnavailable` **passes** — the two are separated only by
the awaited state each one establishes.

**An earlier mutant attempt was discarded as unfaithful.** My first `#4` mutant
(`_testCount.value = 0` instead of the fetch) made the target test red *before* the repair
(`r13-t5-t1-m4-pre-repair.log`, 11 tests / 1 failure). That proves nothing about the missing
precondition — it breaks the assertion, not the awaited state. I discarded it and used the
fetch-removal form above. Recording this because it is the same class of error the brief warned about
for repair 2.

**`git status --porcelain` after reverting the mutant:**

```
 M app/src/test/java/com/example/DataIntegrityReleaseGateTest.kt
 M app/src/test/java/com/example/ui/viewmodels/ApiErrorSemanticsRegressionTest.kt
 M app/src/test/java/com/example/ui/viewmodels/LocalAccountsViewModelTgzSyncTriggerTest.kt
```

(production file `DashboardViewModel.kt` clean)

---

## Final full-suite run

`r13-t5-t1-fullsuite-final.log` — `:app:testDebugUnitTest --rerun-tasks --console=plain`, 113 XML files,
summed from `app\build\test-results\testDebugUnitTest\`:

```
FINAL FULL SUITE: tests=798 failures=1 errors=0 skipped=0
FAILING SUITES: TEST-com.example.DatabaseMigration17To18Test.xml
```

My three classes within that run:

```
TEST-com.example.DataIntegrityReleaseGateTest.xml:                        tests=36 failures=0 errors=0
TEST-com.example.ui.viewmodels.LocalAccountsViewModelTgzSyncTriggerTest.xml: tests=3 failures=0 errors=0
TEST-com.example.ui.viewmodels.ApiErrorSemanticsRegressionTest.xml:        tests=11 failures=0 errors=0
```

Test count is **798**, unchanged by Task 1, as the plan requires.

### The one failure is pre-existing and environmental — proven, not assumed

`DatabaseMigration17To18Test.testMigration17To18_goldenDatabasePreservedAndMigratedSuccessfully`:

```
java.lang.IllegalStateException: Golden backup zip earthlink_backup_1789281798680.zip not found in
candidate paths: [...\app\earthlink_backup_1789281798680.zip, ...\app\..\..., ...\app\..\..\...]
```

Proof it is not mine:

1. `Test-Path earthlink_backup_1789281798680.zip` → `False`; a recursive search finds no such file.
2. `git check-ignore -v` → `.gitignore:40` — the fixture is **gitignored**, so it can never be
   committed and is absent in any clean checkout.
3. `git ls-files | Select-String earthlink_backup` → no output; it is untracked.
4. **Executed at the base commit with my work stashed** (`git stash push -- app/src/test`, HEAD
   `1b9fff7`, tree exactly at base), log `r13-t5-t1-baseline-pre-existing.log`:
   `tests=1 failures=1 errors=0` with the byte-identical message.

I touched no production file in the committed diff, so this failure cannot be attributable to Task 1.
**This is a `FIXTURE / SETUP DEFECT` in the environment, not a `PRODUCT DEFECT` or a `TEST DEFECT`**
(AGENTS.md §9.6 taxonomy). Task 1 is therefore complete, but the "full suite green" gate is
environmentally blocked by a missing golden backup — recorded as a concern below.

---

## Commit

```
fix(test): add the missing preconditions to four sound-but-unbound assertions
```

Scope: `git add app/src/test` — the three test files only. `git diff --stat 1b9fff7 -- "*.kt"`:

```
 .../java/com/example/DataIntegrityReleaseGateTest.kt  | 19 ++++++++++++++++++-
 .../ui/viewmodels/ApiErrorSemanticsRegressionTest.kt  |  8 ++++++++
 .../LocalAccountsViewModelTgzSyncTriggerTest.kt       | 11 +++++++++++
 3 files changed, 37 insertions(+), 1 deletion(-)
```

Purely additive: **37 insertions, 1 deletion** — and that single deletion is the `coordinator.processEvent(event)`
statement on `:802` being rewritten to `val result = coordinator.processEvent(event)`, i.e. the
discarded return value being bound, exactly as the plan prescribes. No test was deleted, no existing
assertion was weakened, and no production file is in the diff.

---

## Concerns and contradictions with the brief

1. **The full suite is not green, and cannot be in this workspace.** 798 tests, 1 failure —
   `DatabaseMigration17To18Test`, a gitignored golden-backup fixture that does not exist on disk.
   Proven pre-existing by execution at the base commit (§ above). The plan's "798 tests, 0 failures"
   expectation is not met and is not meetable here without the fixture file. This needs a ruling from
   the controller: supply the fixture, or amend the gate to exclude it. I did not touch it, as
   fixing it is outside Task 1's scope.

2. **Repair 4 could not use the sibling's assertion shape, contrary to the brief's instruction.**
   The brief said to match `ApiErrorSemanticsRegressionTest:315` and not invent a new shape. That is
   impossible here for a structural reason (the awaited value and the asserted value are both `null`),
   so I used a gateway-interaction precondition — the only non-proxy observable of the awaited state.
   A shape was effectively introduced, but inventing a *state* assertion would have produced a second
   blind test, which the plan forbids more emphatically. Flagging because it contradicts an explicit
   instruction.

3. **Repair 2 required two mutants, not one.** The brief said the faithful mutant is the outbox write
   and forbade reusing the old one — done. But that mutant is red *before* the repair, so on its own
   it cannot demonstrate the "green before repair" state the Global Constraints require. I therefore
   used a second mutant, **the audit's M9 narrowed** from the dedup gate to one branch of
   `applyAccountUpsert` (`SKIPPED_DUPLICATE` instead of applying), to establish the defect, and
   reported both. **Lineage corrected in fix round 1: the audit's blanket
   `if (true || processedKeys.containsKey(key))` (M9, `LL-ROUND-13-TEST-EVIDENCE-AUDIT.md:957-965`)
   was NOT reused verbatim; mine is M9 narrowed from "every remote event" to one branch of one
   handler.** The narrowing is a genuine improvement — it isolates a single path instead of disabling
   the whole coordinator — but the round must record that it descends from M9 rather than present it
   as independently novel. The controller has since ruled that Global Constraint #1 is unsatisfiable by
   construction for Tier-B repairs, and that the only correct probe for a Tier-B finding is one that
   breaks the **awaited state**; mutant B is exactly that probe.

4. **Repair 2's existing assertion was already live.** Under `MUTANT-R13-T1-2b` the *unrepaired* test
   failed with `INV-12 VIOLATED … Outbox before: 0, after: 1`. The audit's Tier-B classification is
   correct that the binding was missing, but the characterisation "the only assertion is
   `assertEquals(outboxBefore, outboxAfter)` with nothing establishing the event applied" could be
   read as implying the assertion was unguarded against the echo loop. It was not. The repair closes
   the binding gap; it does not newly arm the assertion.

5. **I corrected my own unfaithful mutant once** (repair 4, first attempt). Recorded in full in the
   repair-4 section rather than quietly discarded, since it is the same failure mode the brief flagged
   for repair 2.

---

# Fix round 1

The reviewer accepted all four repairs as genuine and returned Changes Requested on the **evidence
record** only. This round corrects the record and re-proves Repair 1. No repair code changed; the
committed test code at `6a53357` is untouched by this round.

## Critical — Repair 1's RED-before-GREEN proof cited logs belonging to a different test

### The reviewer's finding is correct, and I independently confirmed it

I searched the entire temp log tree for any artefact naming the tgz class:

```
=== does ANY log mention the tgz test class? ===
r13-t5-t1-baseline.log : 1 hits
```

`baseline.log` is the clean pre-change run, which legitimately lists the class among all executed
tests. **No other log mentions `LocalAccountsViewModelTgzSyncTriggerTest` at all.** The logs I cited
for Repair 1 — `r13-t5-t1-m1-pre-repair.log` and `r13-t5-t1-m1-post-repair.log` — contain, in their
stdout and stderr respectively:

```
r13-t5-t1-m1-pre-repair.log  → DataIntegrityReleaseGateTest > invariant_INV12_noOutboxLoopsOnRemoteApply FAILED
r13-t5-t1-m1-post-repair.err.log → 1 test completed, 1 failed
```

Both are Repair 2's **mutant A** runs. **The reviewer's diagnosis of the cause is exactly right:** my
Repair 1 runs used the `m1-` prefix, and when I moved to Repair 2 I reused `m1-`/`m2-` for *its*
mutants A and B, overwriting the Repair 1 files. The timestamps agree (Repair 1 ~19:59–20:05;
Repair 2 `m1-` 20:14, `m2-` 20:07), which is why the reviewer read it as transcription error rather
than fabrication — and that is what it was. **It was a naming collision, not fabricated evidence, and
not a transcription slip in the prose: the numbers I reported for Repair 1 (3/0/0 and 3/1/0) were read
from real XML at the time. But the artefacts backing them no longer exist under those names, so the
proof was unverifiable.** The reviewer was right to refuse to accept it.

### Re-run: all three states, new unambiguous filenames

The repair was already in place at `HEAD`, so no test edit was required. Per round 1, each state
below was captured by **deleting the target XML before the run and transcribing it immediately
after**, so a green reading cannot be a stale artefact from a previous run.

| State | Setup | Log (stdout / `.err`) | tests | failures | errors | XML transcribed at |
|:--|:--|:--|--:|--:|--:|:--|
| **A** — green under mutant, **unrepaired** | test file at base `1b9fff7` via `git checkout 1b9fff7 -- …`; mutant applied | `r13-t5-t1-fixr1-rep1-stateA-mutant-unrepaired.log` | **3** | **0** | **0** | 2026-09-30 20:29:04 |
| **B** — red under same mutant, **repaired** | test file at `HEAD`; mutant still applied | `r13-t5-t1-fixr1-rep1-stateB-mutant-repaired.log` | **3** | **1** | **0** | 2026-09-30 20:32:56 |
| **C** — green on clean code | mutant reverted; repair at `HEAD` | `r13-t5-t1-fixr1-rep1-stateC-clean-repaired.log` | **3** | **0** | **0** | 2026-09-30 20:36:52 |

Command for all three (only the class under test, never the full suite):
`.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --console=plain --tests com.example.ui.viewmodels.LocalAccountsViewModelTgzSyncTriggerTest`

Each state was additionally confirmed to have actually executed, from its own log:

- **A** — `> Task :app:testDebugUnitTest` … `BUILD SUCCESSFUL in 1m 28s`
- **B** — `LocalAccountsViewModelTgzSyncTriggerTest > testFailedTgzImport_corruptArchive_doesNotTriggerSync FAILED`,
  and `err`: `3 tests completed, 1 failed`
- **C** — `> Task :app:testDebugUnitTest` … `BUILD SUCCESSFUL in 1m 36s`

State B's failing test is named in the log as `testFailedTgzImport_corruptArchive_doesNotTriggerSync`
— the correct test, not Repair 2's. The failure message is the precondition's, and it still carries
the full `ImportResult(..., success=false, ..., errorMessage=Could not find uTower database in the
provided file.)`, confirming the corrupt-archive path was genuinely exercised.

**Setup was verified, not assumed**, at every state: `PRECONDITION` grep hit count 0 before state A
and 2 before states B and C; file first bytes `70 61 63` (`pac`, i.e. clean ASCII, no BOM) each time;
`FIXR1-MUT-A` present in `LocalAccountsViewModel.kt` for A and B, absent for C.

### A second, deeper defect I found while re-running — and the reason the state-A method matters

My **first** state-A attempt was invalid and I discarded it. I had produced the unrepaired file with
`git show 1b9fff7:path > path`; under PowerShell that redirect wrote a BOM-corrupted file, so
`:app:compileDebugUnitTestKotlin` failed and **`:app:testDebugUnitTest` never ran**. I had already
read `tests=3 failures=0` and was about to record it as state A. Two things caught it:

1. the run's `.err` log said `Execution failed for task ':app:compileDebugUnitTestKotlin'`, and
2. the read XML had a stale modification time.

A green number read from an XML file that no run just rewrote is **not evidence of anything**. This
is the generalisable form of the reviewer's Critical item, and it is why this round deletes the target
XML before each run and cross-checks the log for the `:app:testDebugUnitTest` line. Had I not checked,
I would have reported a second unverifiable green — and this time genuinely fabricated, because no
mutant run existed at all. The discarded attempt's log (`r13-t5-t1-fixr1-rep1a-mutant-unrepaired.log`)
is retained as a worked example of the failure mode; the reviewer should not read state A above as
citing it.

### `git status --porcelain` after reverting the mutant

```
(empty)
```

The mutant is transient and uncommitted. `git diff --stat 1b9fff7 -- "*.kt"` is unchanged from
`6a53357` and still shows only the three test files.

## Important 1 — Repair 4 proves ATTEMPTED, not HANDLED (recorded, not fixed)

`ApiErrorSemanticsRegressionTest.kt:375` (`verify(mockGateway, atLeastOnce()).getTestUsersCount()`)
establishes that the fetch was **invoked**. It does not establish that the failure was **handled**.
`DashboardViewModel.kt:190`'s `_testCount.value = null` is **unobservable**, because the flow is
already `null` from `:40` — the same structural fact that forced the `atLeastOnce()` choice, and the
same reason the sibling's state-assertion shape was unusable.

**The mutation that survives this repair: delete the catch block at `DashboardViewModel.kt:188-191`.**
With `gateway.getTestUsersCount()` still invoked (so the precondition passes) and `_testCount` never
assigned in the catch (so it stays at its initial `null` and the `assertNull` passes), **both
assertions remain green while the failure is no longer handled at all** — the exception would
propagate and the dashboard would lose its unavailable-state semantics.

This is a **real residual and it is inherent to the production design**, not a defect in the repair:
the production code makes "handled" and "never ran" observationally identical. **Not fixed here** — it
is outside Task 1's four repairs and the plan forbids repairs beyond them.

> **For Task 7 §6 ("what is still unproven").** Repair 4 of Task 1 proves *attempted*, not *handled*.
> The unobservable residual is `DashboardViewModel.kt:188-191` (the catch that assigns
> `_testCount.value = null`), and the demonstrating mutation is deletion of that catch block. Any claim
> that a network failure in the dashboard is *handled* — as opposed to merely *initiated* — is
> **currently unproven anywhere in the suite**.

## Important 2 — Mutant B's lineage corrected

The original report called mutant B "a second, **new**, account-upsert-scoped vacuity mutant". That
overstated its novelty. It is the audit's **M9**
(`docs/LESSONS_LEARNED/LL-ROUND-13-TEST-EVIDENCE-AUDIT.md:957-965`, the blanket
`if (true || processedKeys.containsKey(key))` on the dedup gate), **narrowed** from the dedup gate to
one branch of `applyAccountUpsert`. Corrected in place at `task-1-report.md:333-345`.

The narrowing is a genuine improvement — it isolates a single path rather than disabling the entire
coordinator — but the round must not present a narrowed prior mutant as novel method.

**The A/B distinction, stated plainly and retained:**

- **Mutant A is faithful to the test's name.** An outbox write in the account-upsert path *is* the
  INV-12 echo loop; the test's own name (`noOutboxLoopsOnRemoteApply`) claims to exclude exactly this.
- **Mutant B is faithful to the precondition, not the name.** It represents "remote apply silently
  stops working" — a different defect from "outbox loop on remote apply". It is the correct probe for
  the *awaited state* (`processEvent` returned `APPLIED`), which is precisely what the controller has
  since ruled is the only valid probe for a Tier-B finding.

Both are kept. The distinction is the point: A tests the assertion, B tests the binding.

## M1 — Stale diffstat corrected

`task-1-report.md:302-309` quoted `ApiErrorSemanticsRegressionTest | 6` and `35 insertions(+), 1
deletion(-)`, captured before the `times(1)` → `atLeastOnce()` correction. Corrected to:

```
 .../java/com/example/DataIntegrityReleaseGateTest.kt  | 19 ++++++++++++++++++-
 .../ui/viewmodels/ApiErrorSemanticsRegressionTest.kt  |  8 ++++++++
 .../LocalAccountsViewModelTgzSyncTriggerTest.kt       | 11 +++++++++++
 3 files changed, 37 insertions(+), 1 deletion(-)
```

Verified against the live tree: `git diff --stat 1b9fff7 -- "*.kt"`.

## M2 — Green-state evidence is transcribed, not independently re-checkable

A workflow property of this plan, recorded so a later reader knows which numbers can be audited:

- Gradle prints **no** `N tests completed` line on a **successful** run — that line only appears in
  `.err` when tests fail. The reviewer is right that a green `tests=N` figure cannot be re-read from a
  log.
- Each run **overwrites** `app/build/test-results/testDebugUnitTest/*.xml`, so a green figure survives
  only in whatever transcribed it at that moment.
- **Red states are independently checkable**: the `.err` log carries `N tests completed, M failed` and
  the stdout names the failing test, so a red claim can always be re-verified from a log.
- **Green states are transcribed-only**: they rest on the XML read at the time, corroborated by the
  log's `> Task :app:testDebugUnitTest` line and `BUILD SUCCESSFUL`.

Consequence for this round: Repair 1's green states A and C were re-run with the XML deleted
pre-emptively and transcribed immediately (timestamps above), and each was cross-checked against its
own log. **That is the strongest form of green-state evidence obtainable under this harness** — it is
not the same as a log-recheckable claim, and should not be described as such. A harness change (a
test-logger emitting counts on success, or per-run XML archival) would remove the asymmetry; that is
out of scope for this plan but worth recording for any future round.

## M3 — Adjacent gap at `DataIntegrityReleaseGateTest.kt:874` (recorded, not fixed)

`idempotency_duplicateSyncEvent_zeroNewEntries` binds the **first** apply's effect (the new
`countBeforeFirst + 1` precondition) but still **discards the second apply's return value** at
`:874`. A same-ID silent overwrite on the second apply — where the entry is replaced rather than
duplicated — keeps the count at 1 and stays green.

Out of scope and correctly so: the test's name is `zeroNewEntries`, which is what is asserted, and the
row count cannot distinguish "second apply skipped as duplicate" from "second apply silently
overwrote". Binding the second result would be a *fifth* repair, which the plan forbids here.

> **For Task 7 §6 ("what is still unproven").** `DataIntegrityReleaseGateTest.kt:874` discards the
> second `processEvent` return value, so a same-ID silent overwrite on the duplicate apply is
> undetectable by that test. `idempotency_duplicateSyncEvent_zeroNewEntries` proves *no extra row*,
> not *no overwrite*.

## Full suite after fix round 1

`r13-t5-t1-fixr1-fullsuite.log` — `:app:testDebugUnitTest --rerun-tasks --console=plain`, 113 XML files:

```
FIX ROUND 1 FULL SUITE: tests=798 failures=0 errors=0 skipped=0
FAILING SUITES: (none)
TEST-com.example.DataIntegrityReleaseGateTest.xml:                        tests=36 failures=0 errors=0
TEST-com.example.ui.viewmodels.LocalAccountsViewModelTgzSyncTriggerTest.xml: tests=3 failures=0 errors=0
TEST-com.example.ui.viewmodels.ApiErrorSemanticsRegressionTest.xml:        tests=11 failures=0 errors=0
TEST-com.example.DatabaseMigration17To18Test.xml:                         tests=1 failures=0 errors=0
```

**798 tests, 0 failures, 0 errors.** The `DatabaseMigration17To18Test` failure reported in the
original submission is now **passing** — the golden-backup fixture has since been provisioned in this
workspace, confirming the reviewer's finding that the fixture is a genuine production golden database
rather than a stub. Concern #1 from the original submission is therefore **resolved**; the concern was
accurate about the environment at the time and required no change from me.

## Commit

```
docs(audit): correct the task-1 repair record and re-prove repair 1
```

Report-only. No test file, no production file, and no other task's section was modified.
