# Test-Evidence Repairs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make 14 confirmed-fake tests actually prove what they name, without deleting any of them and without weakening any existing assertion.

**Architecture:** Three tiers, each with a different repair shape, applied in ascending order of blast radius. Every repair is proven by a mutation that is **faithful to the test's own name** — a test is repaired only after a mutant that breaks the behaviour it claims to guard has been shown to leave it green. Repairs bind tests to production code; they never add assertions that merely restate the test.

**Tech Stack:** Kotlin, JUnit4, Robolectric, Mockito, Room, Gradle `:app:testDebugUnitTest`.

**Spec:** `docs/LESSONS_LEARNED/LL-ROUND-13-TEST-EVIDENCE-AUDIT.md` (all 14 findings, their mutations, and the evidence) and `.superpowers/sdd/2026-09-30-test-suite-evidence-audit/progress.md` (the controller's three rulings and the Skeptic's three rejected recipes). The audit is self-contained; read it rather than re-deriving the findings.

## Global Constraints

These apply to every task. Read them before starting.

- **RED before GREEN, without exception.** Each repair is proved by a mutation that is faithful to the test's name: break the production behaviour the test names, show the test is **green** (that is the RED for this task — the defect), then repair, then show the test is **red under the mutation** and **green on clean code**. Record all three states. A repair whose mutant was never shown to leave the test green is unproven.
- **Never delete a test.** Zero deletions. The Skeptic refused deletion on findings #5 and #13 specifically: nothing in the tree asserts on the production line they transcribe, so deleting them would remove the only surviving record of an intent for an unguarded line.
- **Never weaken an existing assertion.** Repair by adding, or by changing a fixture, or by binding to production. Loosening an assertion so a test passes is forbidden.
- **Every mutant is transient.** Never committed. `git status --porcelain` must be empty before you begin the next mutation, and `git diff --stat <BASE> -- "*.kt"` must show only your intended repairs when you finish.
- **Gradle must be forced to execute.** Always `--rerun-tasks`. After every run read the JUnit XML in `app/build/test-results/testDebugUnitTest/` and record `tests`/`failures`/`errors`. `BUILD SUCCESSFUL` alone is not evidence. Logs named `r13-t5-<task>-<purpose>.log`.
- **No new dependencies.** No new test source files unless a task says to create one.
- **The full suite must be green at every commit**, at 798 tests or above.
- **A single `assert` is the minimum acceptable test and is never bloat.** Do not "fix" a test by adding assertions to make it look thorough. Every added assertion must constrain something a mutation can break.
- **Commit once per task**, `fix(test): …`.
- **If a mutant does not turn your test red after repair, STOP.** Do not proceed to the next repair. That means either the mutant is unfaithful or the repair is wrong, and both need a ruling before more work builds on it.

---

### Task 1: The four Tier-B repairs — one precondition each

**Files:**
- Modify: `app/src/test/java/com/example/ui/viewmodels/LocalAccountsViewModelTgzSyncTriggerTest.kt:196-217`
- Modify: `app/src/test/java/com/example/DataIntegrityReleaseGateTest.kt:776-808`
- Modify: `app/src/test/java/com/example/DataIntegrityReleaseGateTest.kt:819-864`
- Modify: `app/src/test/java/com/example/ui/viewmodels/ApiErrorSemanticsRegressionTest.kt:343-371`

**Interfaces:**
- Consumes: nothing. First task.
- Produces: four tests that assert their awaited state was reached before asserting the outcome. This establishes the precondition pattern the later tasks reuse.

Tier B means the assertion is **sound**; only the awaited state is unestablished. Each repair is one assertion, and each must reference the specific observable the test already polls for — not a proxy.

- [ ] **Step 1: Add the precondition to each of the four**

The awaited state is specified here; match the sibling that already does it correctly — `LocalAccountsViewModelTgzSyncTriggerTest:232` and `ApiErrorSemanticsRegressionTest:315` — rather than inventing a new shape.

| Test | Awaited state | Where production establishes it |
|---|---|---|
| `LocalAccountsViewModelTgzSyncTriggerTest:207` | `viewModel.error.value != null` after a corrupt `.tgz` | `LocalAccountsViewModel.kt:330` (failure message) or `:335` (catch) |
| `DataIntegrityReleaseGateTest.kt:776` | the remote `AccountUpsert` was actually **applied** | `RemoteSyncCoordinator.processEvent` returns `EventSyncResult`; the return value is currently discarded at `:802` |
| `DataIntegrityReleaseGateTest.kt:819` | the first `processEvent` **created a ledger entry** | `applyLedgerUpsert` inserts one; `countAfterFirst` must be the pre-insert count + 1 |
| `ApiErrorSemanticsRegressionTest.kt:343` | `loadDashboardData` **ran** and the fetch was attempted | `DashboardViewModel.kt:184` launches the job; `:190` sets null in the catch |

For `:776`, bind the discarded return value:

```kotlin
val result = coordinator.processEvent(event)
assertEquals(
    "PRECONDITION | The remote AccountUpsert must be APPLIED for this invariant to be meaningful. " +
        "Got $result instead.",
    EventSyncResult.APPLIED,
    result
)
```

Then the existing `assertEquals(outboxBefore, outboxAfter, …)` becomes meaningful, because we now know an apply happened.

For `:819`, assert the first apply created exactly one entry, capturing `countBeforeFirst` before the first `processEvent`:

```kotlin
assertEquals(
    "PRECONDITION | The first apply must create exactly one ledger entry, otherwise " +
        "'no duplicates' is satisfied by nothing happening. Count after first: $countAfterFirst",
    countBeforeFirst + 1,
    countAfterFirst
)
```

The test discards the return value. Bind it:

```kotlin
val result = coordinator.processEvent(event)
assertEquals(
    "PRECONDITION | The remote AccountUpsert must be APPLIED for this invariant to be meaningful. " +
        "Got $result instead.",
    EventSyncResult.APPLIED,
    result
)
```

Then the existing `assertEquals(outboxBefore, outboxAfter, …)` becomes meaningful, because we now know an apply happened.

- [ ] **Step 2: Prove each repair RED-then-GREEN**

For each of the four, write the faithful mutant — the one that breaks what the test's **name** claims — show the repaired test goes **red** under it, and red on the mutant alone. Record the command, the XML numbers, and the log filename.

**For `:776` specifically, the audit's existing mutant is not faithful and you must not reuse it.** The earlier mutant suppressed every remote event, which tests the precondition rather than the assertion. The faithful mutant is an outbox write added into `RemoteSyncCoordinator`'s account-upsert path. If you cannot construct a faithful one, say so and stop rather than reusing the old mutant.

- [ ] **Step 3: Run the full suite**

Run: `gradlew.bat :app:testDebugUnitTest --rerun-tasks --console=plain`
Expected: 0 failures, 798 tests or above.

- [ ] **Step 4: Commit**

```bash
git add app/src/test
git commit -m "fix(test): add the missing preconditions to four sound-but-unbound assertions"
```

---

### Task 2: Replace the vacuous `INV-02` gate test with one that calls the seam

**Files:**
- Modify: `app/src/test/java/com/example/DataIntegrityReleaseGateTest.kt:605-625`
- Read only: `app/src/test/java/com/example/Workstream9AFinancialCorrectionTest.kt:255-305`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: a gate test that exercises the deletion path and asserts the contra-entry.

`invariant_INV02_historicalSourceImmutability_ledgerDeleteUsesContraEntry` inserts a row, reads it back, and asserts it exists. It never calls `deleteTransaction` — the only mention of it in the file is a comment at `:610`. Its sole assertion even carries the message `"INV-02 SETUP | Original entry must exist before correction"`.

**Controller ruling 1: replace it, do not delete it and do not swap in the sibling.** `scripts/production_gate.sh:76` runs this class as the release gate. `Workstream9AFinancialCorrectionTest.kt:262` already guards Invariant 2 in the suite, but deleting this one would remove Invariant 2 **from the release gate itself**. The gate must hold a real check of its own.

- [ ] **Step 1: Read the sibling to learn the shape the repo already accepts**

`Workstream9AFinancialCorrectionTest.kt:262` is the working model: it calls `deleteTransaction` at `:285`, asserts the original survives with `assertNotNull` at `:289`, asserts `assertEquals(2, allEntries.size)` at `:298` so a no-op cannot pass, and locates the contra-entry by `correctsEntryId` at `:299-302`. Read it before writing anything.

- [ ] **Step 2: Establish the RED mutant**

Mutate the deletion path to perform a **physical** row delete — the exact RED-Invariant-2 violation. `Repositories.kt:2693` and `AppDatabase.kt:229` are the cited lines. Show the current test is **green** under this mutant. That green is the defect, proved.

- [ ] **Step 3: Rewrite the test to call the seam**

The rewritten test must:
- call the real `deleteTransaction` on the original entry,
- assert the original row **still exists**,
- assert the total row count is **2** (original + contra-entry), so a no-op cannot satisfy it,
- assert a contra-entry exists carrying `correctsEntryId` pointing at the original,
- assert the contra-entry's `typeRaw` is the reversal type, not the original type.

Name it so the name matches what it does. The current name is fine once the body earns it.

- [ ] **Step 4: Prove RED-then-GREEN**

Show the rewritten test goes **red** under the physical-delete mutant, and green on clean code. Record the command, the XML numbers, and the log.

- [ ] **Step 5: Run the full suite**

then the full suite. Expected 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/test
git commit -m "fix(test): make the INV-02 gate test call the deletion seam and assert the contra-entry"
```

---

### Task 3: Bind the two transcriptions to the production line they copy

**Files:**
- Modify: `app/src/test/java/com/example/ui/viewmodels/EarthlinkSearchViewModelSeamTest.kt:1198-1226`

**Interfaces:**
- Consumes: nothing from Task 2.
- Produces: two tests that fail if `UserDetailScreenV2.kt:410` changes.

`testBalanceAfterMath_unknownBalance_isNull` (`:1223`) and `testBalanceAfterMath_knownBalance_computesCorrectly` (`:1214`) both transcribe this production line:

```kotlin
// UserDetailScreenV2.kt:410
val balanceAfter = resellerBalance?.let { it - packageCost }
```

The tests copy it into themselves and run it against a local. Change `:410` to `?: 0.0` and both stay green.

**Controller ruling 2 and the Skeptic's refusal: do not delete these.** Nothing in the tree asserts on `:410` or on its `balanceAfter` consumption. `testGetResellerBalance_*` at `:1169`-`:1210` bind the **source** of `resellerBalance`, not the **consumption**. Deleting the pair removes the only surviving record of an intent for an unguarded production line — a net coverage loss dressed as a cleanup.

The fix is a source-scanning assertion: assert that the production file still contains the consumption, so a change to `:410` is detected. `Phase5DestructiveActionReleaseGateTest` already uses this pattern in this repo — follow it rather than inventing one.

- [ ] **Step 1: Establish the RED mutant**

Change `UserDetailScreenV2.kt:410` from `resellerBalance?.let { it - packageCost }` to a form that no longer null-guards, e.g. `(resellerBalance ?: 0.0) - packageCost`. Show **both** tests are currently **green** under it. That green is the defect.

- [ ] **Step 2: Add the source-scanning assertion**

Assert that `UserDetailScreenV2.kt` still contains the null-guarded consumption at `:410`. Model the assertion on `Phase5DestructiveActionReleaseGateTest`. Keep the existing behavioural assertions — they are not wrong, they are just unbound.

- [ ] **Step 3: Prove RED-then-GREEN**

Show both tests go **red** under the mutant from Step 1, and green on clean code.

- [ ] **Step 4: Run the full suite**

Expected 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/test
git commit -m "fix(test): bind the balanceAfter transcriptions to UserDetailScreenV2.kt:410"
```

---

### Task 4: Bind the timezone test to the branch it names, not the offset it computes

**Files:**
- Modify: `app/src/test/java/com/example/Workstream1StatementCorrelationTest.kt:24-60`

**Interfaces:**
- Consumes: nothing from Task 3.
- Produces: a test that fails if the `"T"`→UTC / else→Baghdad branch collapses.

`testBaghdadTimezoneConversion` (`:46`) builds two local `SimpleDateFormat` instances and asserts a fixed offset. It calls no production code, while the class KDoc at `:12-16` claims INV-05 / RED Invariant 5.

**The named behaviour is the format-conditional branch at `Repositories.kt:1640-1644`** — `"T"` selects UTC, anything else selects Baghdad — not the tzdb offset. Making both branches UTC shifts every non-ISO statement by 3 hours and breaks the ±90 s correlation window of RED Invariant 5, and this test cannot see it. This is the most financially material of the fourteen.

The sibling at `:24` (which *does* deserialize through production) is the model for what binding looks like, and the prior mutation confirmed it goes red.

- [ ] **Step 1: Establish the RED mutant**

Force `Repositories.kt:1640-1644` to select UTC for both branches. Show `testBaghdadTimezoneConversion` is currently **green** — the defect — and note whether the `:24` sibling goes red.

- [ ] **Step 2: Bind the test to the branch**

Rewrite so the assertion depends on the production branch rather than on locally-computed arithmetic. Route the value through the production statement-correlator for both an ISO `"T"` statement and a non-ISO one, and assert the two resolve to **different** zones. The invariant is the branch's discrimination, not one offset constant.

- [ ] **Step 3: Prove RED-then-GREEN**

Show the test goes **red** under the both-UTC mutant and green on clean code.

- [ ] **Step 4: Run the full suite**

Expected 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/test
git commit -m "fix(test): bind the timezone test to the format-conditional branch it names"
```

---

### Task 5: Delete the control that protects nothing

**Files:**
- Modify: `app/src/test/java/com/example/Phase1FirestoreDocumentIdentityTest.kt:818-833`

**Interfaces:**
- Consumes: nothing from Task 4.
- Produces: a suite without a control-for-a-control.

`testScenarioJ_counterfactualRawPayloadContainsRawJson` builds a `mapOf` literal and asserts `containsKey("rawJson")` on that same literal. `SyncRepositoryImpl.kt:719` is never called.

This is the **one deletion** in the plan, and the Skeptic endorsed it specifically: Scenario I's own inline `JSONObject` at `:786-793` already carries `rawJson` through `OutboxManager.enqueue` and establishes Scenario I's non-vacuity. Scenario J's `mapOf` bypasses both, so it is a control for a control and protects nothing.

- [ ] **Step 1: Confirm Scenario I is the real guard, read-only**

Read `:786-793` and confirm the inline `JSONObject` carries `rawJson` through the production outbox path. If it does not, **stop** — the deletion is unsafe and you must report that instead.

- [ ] **Step 2: Establish the RED mutant**

Mutate `SyncRepositoryImpl.kt:719` so `rawJson` is stripped. Show Scenario I goes red and Scenario J stays green. That green is the defect.

- [ ] **Step 3: Delete the test and its now-unused scaffolding**

Delete only what exists solely to serve it. If Scenario I shares scaffolding, keep the shared part.

- [ ] **Step 4: Prove**

Re-run the full suite: Scenario I still catches the mutant, and the total test count drops by exactly one. Record both.

- [ ] **Step 5: Run the full suite**

Expected 0 failures. The count may now be **797 or above** — a drop of exactly one is correct and expected here, and this is the only task where that is true.

- [ ] **Step 6: Commit**

```bash
git add app/src/test
git commit -m "test(audit): delete the counterfactual control that protected nothing"
```

---

### Task 6: Tier C — repair the four whose named rule is not load-bearing for their fixture

**Files:**
- Modify: `app/src/test/java/com/example/DataIntegrityReleaseGateTest.kt:923-975`
- Modify: `app/src/test/java/com/example/HistoricalSubscriberMatchingSafetyTest.kt:230-275`
- Modify: `app/src/test/java/com/example/Step3DurableDispatchTest.kt:593-660`

**Interfaces:**
- Consumes: nothing from Task 5.
- Produces: three tests whose fixtures actually route through the gate each names.

These need a **fixture change**, not an added assertion. Adding an assertion here produces a second blind test.

- [ ] **Step 1: Repair `:923` — the migration-defaults test**

It hand-writes MIGRATION_8_9's defaults as Kotlin literals (`0.0`, `null`) and never runs the migration, so it asserts a copy. Note the Skeptic's extra finding: `:959` *also* derives `isSnapshot = stateSource != null` in Kotlin, so **both the input and the branch are copies** — running the migration alone will not fix it. Route the values through the real migration, and stop deriving the snapshot branch in the test.

- [ ] **Step 2: Repair `:237` and `:259` — with the corrected fixture, not the audit's recipe**

**The audit's recipe is wrong in both branches, and you must not use it.**
- *"Give the incoming record the same `sourceExternalId`"* → matches at `SubscriberMatcher.kt:89-92` and returns `Unique` at `:94`; the test **fails** and never reaches Stage 3.
- *"Omit it"* (incoming `extId = null`) → `conflictingUsername` is still `true` at `:106`, so the candidate remains double-guarded.

The only fixture that isolates the historical gate at `SubscriberMatcher.kt:103` / `:119` is:

| | value |
|---|---|
| historical candidate | `isHistoryOnlySubscriber = true`, **`sourceExternalId = null`**, **`earthlinkUsername = null`**, `phone1 = P` |
| incoming | `extId = "e_22222"`, **`username = null`**, `phone = P` |

Then Stage 1 is skipped (no username), Stage 2 misses (null extId, `id ≠ extId`), and both `conflicting*` flags are `false` — leaving the historical gate as the only thing standing.

Verify by tracing `SubscriberMatcher` that both `conflicting*` flags are false **before** you rely on the test. If they are not, the fixture is wrong and you must say so.

- [ ] **Step 3: Repair `Step3:593` — assert which guard rejected, not just that something did**

The test proves the safety outcome (no debt materialised) but not that the 4-tuple did the rejecting. With `matchesUser = true` (`Repositories.kt:1684`) the wrong user's statement matches, and the test's outcome is reached via `resolvePendingOperationVerifiedSuccess` → `IllegalStateException` → `catch` → `INCONCLUSIVE`, not via the 4-tuple.

Add an assertion on the **diagnostic message** that distinguishes the two paths — `:2006` "no single matching ledger transaction" versus `:2055` "Renewal inspection inconclusive". That is the assertion the audit did not name.

- [ ] **Step 4: Prove RED-then-GREEN for each of the three**

Each needs a faithful mutant: the RED-8-to-9 default change for `:923`, the `SubscriberMatcher.kt:103`/`:119` gate removal for `:237`/`:259`, and the `Repositories.kt:1684` `matchesUser` flip for `Step3:593`. Record the command, XML numbers, and log for each.

- [ ] **Step 5: Run the full suite**

Expected 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/test
git commit -m "fix(test): route the Tier-C fixtures through the gates they name"
```

---

### Task 7: Close the round — the unproven coverage, the lessons, and the GPS

**Files:**
- Modify: `docs/LESSONS_LEARNED/LL-ROUND-13-TEST-EVIDENCE-AUDIT.md` — add §5 and §6; correct the record defects the Task 4 review found
- Modify: `docs/LESSONS_LEARNED/LL-BUG-HUNT-METHODOLOGY.md` — add the audit's method lessons
- Modify: `docs/LESSONS_LEARNED/LL-ROUND-3-REJECTED-FINDINGS.md` — update the closed-seam line
- Modify: `PROJECT_ROADMAP.md` — the GPS, as `AGENTS.md` §12.1 requires

**Interfaces:**
- Consumes: every repair's measured evidence from Tasks 1-6.
- Produces: the closed round and an accurate GPS.

- [ ] **Step 1: Write §5 — the repairs as executed**

Per repair: the file and line, the tier, the faithful mutant, the three recorded states (green under the mutant before the repair, red under it after, green on clean code), the commit, and the log filename. State plainly that `:776`'s earlier mutant was unfaithful and was replaced.

- [ ] **Step 2: Write §6 — what is still unproven**

The audit adjudicated 30 of 69 cohort members; **39 remain**, and roughly 90 of 113 files were never audited. Add what the repairs changed. State that "no pattern match" is a screening result, not a clearance, and that the P1-P3 pattern screen is **not** validated for triage without execution.

- [ ] **Step 3: Correct the four record defects the Task 4 review found**

These are load-bearing and must be fixed before the section is published anywhere:
- The `20%` pattern-misprediction figure is **false**; the true rate is **50%** (5 of 10). The same error recurs at three sites in §4 and two in the report.
- `:1738` reads "7 CONFIRMED, 10 REFUTED, 1 LEAD, 0 OPEN — across 17", which sums to 18; the correct line is 7/9/1.
- §4 states `Step3DurableDispatchTest` has 0 pattern matches while §4.4 #14 grades `:593` as one and confirms it. Restate as two pattern-shaped items, one strict P3 and one P2-family.
- The claim that `Step3DurableDispatchTest` "caught every mutation" is contradicted by finding #14 in the same file.

Also fix the seven deferred minors logged in the ledger: `Step3` has **146** assertion sites, not 144; **38** of 40 Seam tests were unexecuted, not 39; and the three off-by-two `Repositories.kt` citations (`:1963` not `:1965`, `:1974` not `:1976`, `:2042` not `:2044`).

- [ ] **Step 4: Write the method lessons into the methodology**

The generalisable findings, each traceable to this round:
- A green test proves its assertions pass, not that the behaviour is guarded. The silent-corruption barrier held five tests that could not observe the behaviour they named.
- The three-tier taxonomy: assertion satisfied by a literal/language/transcription; sound assertion with a missing precondition; and a real rule that is not load-bearing for the fixture. **The single label is dangerous — it invites deleting working tests.**
- A test's blindness is set by **which production line its fixture routes through**, not by its assertion count. Three tests, one production file, one `assertNull` shape, two blind and one sound, separated only by the routed line.
- **Assertion count is a bad proxy.** A 12-assertion test that transcribes production is worth less than a 1-assertion test bound to a live seam.
- A pattern screen validated at 50% misprediction cannot triage without execution. In both directions: it accused five sound tests and cleared a test that transcribes a production line.
- The repair recipe must be traced before it is written. Three of the audit's own recipes were wrong — two would have produced still-blind tests, and one tested the precondition rather than the assertion.
- A release gate is only as good as the tests that guard it, and a test can sit inside the gate while being structurally incapable of detecting the violation it names.

- [ ] **Step 5: Update the GPS**

Measure the baseline from the final full-suite run. Do not write a remembered number. Set `CURRENT VERIFIED TEST BASELINE` to the measured total and `CURRENT CHECKPOINT` to a commit proven to exist via `git cat-file -e "<sha>"; if ($LASTEXITCODE -eq 0)`. Add a `BUG-HUNT-R13` milestone row pointing at this results document. If the count is below 798, say so in the row — Task 5's one deletion accounts for it.

- [ ] **Step 6: Commit**

```bash
git add docs/LESSONS_LEARNED PROJECT_ROADMAP.md
git commit -m "docs(audit): close round 13 and update the GPS"
```

---

## Self-Review

**Spec coverage.** The 14 findings map to Tasks 1-6: Tier B → Task 1 (four), Tier A → Tasks 2, 3, 4, 5 (`:606`, `:1223`+`:1214`, `Workstream1:46`, `Phase1:820`), Tier C → Task 6 (four). Task 7 closes the round. Every finding has a task and no task covers a finding that does not exist.

**The three rejected recipes are corrected in the plan, not carried forward.** Task 6 Step 2 replaces the `SubscriberMatcher` fixture with the one traceable form and states why both audit branches fail. Task 1 Step 5 forbids reuse of the unfaithful `:776` mutant. Task 3 forbids the deletion the Skeptic refused. Each correction is written where the implementer will read it.

**The rulings are encoded as constraints, not advice.** "Never delete a test" in Global Constraints is what makes Task 3 a repair rather than a deletion; Task 5's deletion is the single explicit exception, with a precondition that stops the task if Scenario I is not the real guard.

**Three tasks are left unmerged on purpose.** Tasks 3, 4 and 5 are the same shape (mutate → prove green → bind → prove red) across three files, and the `subagent-driven-development` skill says to batch same-shape work. They are kept separate because the release gate replacement (Task 2, `DataIntegrityReleaseGateTest.kt:606`) is the highest-blast-radius repair in the plan and deserves its own gate, and because each needs a distinct RED mutant — merging them puts three unrelated mutations in one agent's context and one reviewer covering all three, which is the failure mode that skill warns about when it says batching is for edits "of the same kind", not for unrelated investigations.

**Placeholder scan.** No `TBD`, no "handle the edge cases", no "similar to Task N". Every task names real files, real line numbers, real production lines to mutate, the exact assertions to add where the shape is determined, and the exact XML figures to record. The three tasks whose repair shape is a judgement call (`:923`, `:237`/`:259`, `Step3:593`) say what to trace before writing and what to do if the trace contradicts the plan.

**Type consistency.** `EventSyncResult` is the type `processEvent` returns at `:776`. The `countBeforeFirst` capture introduced in Task 1 Step 3 is local to that test. The diagnostic-message strings at `Repositories.kt:2006` and `:2055` are named once and reused in Task 6 Step 3.

**Known gap, stated rather than hidden.** 39 cohort members and roughly 90 files stay unadjudicated, and the P1-P3 screen is not validated for triage. Task 7 Step 2 requires that gap to be written down, so the round cannot overstate what it covered.
