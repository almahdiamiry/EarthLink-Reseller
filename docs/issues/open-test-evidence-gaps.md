# Open test-evidence gaps — recorded, not fixed

Recorded 2026-09-30 during pre-merge verification of `audit/test-evidence-r13`.

**Scope of this file.** These are *observations with a named mechanism*. Nothing here is a confirmed
defect, and none is repaired by the branch that recorded it. Each is out of scope for a repair plan
because fixing it requires a production change or a new artefact — see the "Why not this branch"
column. `AGENTS.md` §7.4 forbids the "while we're here" expansion that fixing them would be.

---

## GAP-1 — `UserDetailScreenV2.kt:382` fabricates a 0-IQD balance on gateway failure
> **OPEN - untouched by r13b. Its fix is an app/src/main extraction and is PR 2.**


| | |
|:--|:--|
| **Location** | `app/src/main/java/com/example/ui/viewmodels/UserDetailScreenV2.kt:382` |
| **Mechanism** | `resellerBalance = null` inside the `catch` is the **only** place the screen produces the null that `UserDetailScreenV2.kt:410` consumes. `getResellerBalance()` (`EarthlinkSearchViewModel.kt:144-145`) returns a non-null `Double`. |
| **Demonstrated** | Mutating `:382` to `0.0` leaves `EarthlinkSearchViewModelSeamTest` at **40 tests / 0 failures**. The result is a real-looking `0 IQD` "Balance After Renewal" row on gateway failure — the identical user-visible harm RED Invariant 1 exists to prevent. |
| **What guards it today** | Nothing. The Task 3 repair bound the **consumption** at `:410` by source-scanning the line's text. It does not bind the **production** of the null. |
| **Why not this branch** | Closing it needs a runtime seam. `balanceAfter` is a `val` local inside a `@Composable` and `resellerBalance` is Compose state; a JVM test cannot observe either. The **strongest** fix is to extract the balance computation into a pure, injectable function so a JVM test can call it directly - that is an `app/src/main` change, so it is out of scope for this branch and is recorded here instead. A weaker alternative that stays in test code would pin `resellerBalance = null` at `:382` by source-scan the way `:410` is pinned; that proves text rather than behaviour, so it is recorded as insufficient. |
| **Severity** | **Highest of the four.** It is a financial display value, and it is the only one of the four where a wrong value reaches the screen. |

## GAP-2 — `Workstream1StatementCorrelationTest.kt:24` never reaches the production parser
> **CLOSED in r13b. Rewritten to route the DESERIALIZED date through the production 
parser, with a negative control and two positive controls. Proven by M-G2 (collapse the 
format-conditional zone branch to UTC): 797 tests completed, 3 failed, and the failing 
name included testContractStatementFieldsDeserialization - the test that had been green 
against this exact mutation.**


| | |
|:--|:--|
| **Location** | `app/src/test/java/com/example/Workstream1StatementCorrelationTest.kt:24` |
| **Mechanism** | A case-sensitive grep for `parseStatementTimestamp` over the file returns **0 hits**. The test asserts on `occurredAt` as a raw `String` (`Models.kt:327`) and never calls the statement correlator. |
| **Demonstrated** | The Task 4 pre-repair mutant that collapsed the zone branch left this sibling **green**, which is how the audit's claim that it "went red under the same mutation" was falsified. |
| **What guards it today** | Nothing. Its sibling `:194` was repaired in Task 4; this one was not, and it is the **same defect in the same file**. |
| **Why not this branch** | It is not among the fourteen adjudicated findings — it surfaced during repair, after the audit's scope was fixed. Repairing it is a new adjudication, not a repair. |

## GAP-3 — `conflictingExtId` (`SubscriberMatcher.kt:105`, `:121`) has never been covered in its rejecting role
> **CLOSED in r13b. Four fixtures added: negative and positive controls on both 
conflictingExtId lines. Proven twice, one mutation per line:
  M-G3a (:105 forced false): 801 tests completed, 1 failed - testPhoneMatching_conflictingExtId_rejectsActiveCandidate
  M-G3b (:121 forced false): 801 tests completed, 1 failed - testNameMatching_conflictingExtId_rejectsActiveCandidate**


| | |
|:--|:--|
| **Location** | `app/src/main/java/com/example/core/sync/SubscriberMatcher.kt:105` and `:121` |
| **Mechanism** | `conflictingExtId` is only evaluated when neither `:103` nor `:119` has already returned `false` for a history-only candidate. Every caller in the suite either passes no `extId`, passes a **matching** `extId` (which returns `Unique` at `:94` before Stage 3/4), or supplies a history-only candidate (which short-circuits first). |
| **Demonstrated** | A sweep of all five test files that drive `matchSubscriber`: `conflictingUsername` **is** covered with positive and negative controls; `conflictingExtId` is not exercised as `true` anywhere. |
| **What guards it today** | Nothing, and this is **not a regression** from the Task 6 repair — before it, the candidates were also history-only, so the flag was equally unevaluated. |
| **Why not this branch** | Repairing it needs a fixture whose candidate is **not** history-only and whose incoming `extId` conflicts — a new adjudication of a production rule the audit never classified. |
| **Lesson** | Repairing a double-guarded fixture can leave the second guard with **less** coverage than before, because the test that incidentally reached it has just been redirected. |

## GAP-4 — `Step3DurableDispatchTest.kt:48-67` is dead code carrying the collapsed-zone bug
> **CLOSED in r13b. The dead parseStatementTimestamp was deleted after grep proved it had 
exactly one hit in the file - its own declaration - and zero call sites. Step3DurableDispatchTest 
still runs 23 tests, unchanged.**


| | |
|:--|:--|
| **Location** | `app/src/test/java/com/example/Step3DurableDispatchTest.kt:48-67` |
| **Mechanism** | A transcription of the production statement parser that **hardcodes UTC for all five format patterns** — which is the collapsed-zone bug the Task 4 repair exists to catch. Case-sensitive grep over all 1352 lines returns exactly **1** hit: the declaration. Genuinely dead. |
| **Demonstrated** | Not reachable by any test, so no mutant reaches it. It is a trap for the next person who wires it up. |
| **What guards it today** | Nothing — and nothing needs to, because nothing calls it. |
| **Why not this branch** | It is outside all fourteen findings. Deleting it or wiring it up is a separate decision about a test file the branch did not otherwise touch. |

---

## Two scanner facts, recorded here because they are properties of the shipped instrument

**GAP-5 — the scanner's word-class rule had two opposite defects, now both pinned.**
`scan_test_evidence.py:_starts_new_declaration` bounded the expression body. Stopping **early**
truncated a test's own assertions and manufactured an F1; stopping **late** let a test borrow the next
declaration's assertions. Over the 113-file suite the fix changed **14** verdicts `True→False` (all
modifier words in ordinary positions — `data:` named arguments, `data = …` assignments, and one
`actual: Map<String, AccountBalanceVector>` at `Bug02RestoreMergeHistoryLossTest.kt:113`) and **1**
`False→True` (`@Volatile var gateAwaited = false` at `BugSrch02StaleSelectionOverwriteTest.kt:67`, a
real declaration the old annotation walk swallowed). **The findings list is identical before and after:
both empty.** The defect was never `data`-specific; it was a modifier word in an ordinary position.

**GAP-6 — a multi-line annotation broke the annotation walk; found by re-probing, fixed.**
`_starts_new_declaration('@Suppress(\n "a"\n)\nfun f()')` returned `False` where `True` is correct,
because the balanced-paren walk deliberately refused to cross a newline. "Balanced" now means
balanced. Pinned by fixture, and by a mutation that restores the newline abort.

**Not a defect, recorded because it looks like one.** `LL-ROUND-13-TEST-EVIDENCE-AUDIT.md:250` renders
an em dash as `??` in PowerShell output. Codepoint enumeration gives `U+2014`; the file contains zero
`U+FFFD`. This is console rendering, not corruption.

## GAP-7 — the release gate does not assert a test count, and this branch proved it
> **CLOSED in r13b.** `scripts/test_count_manifest.json` records a per-class FLOOR for the classes
> `production_gate.sh` governs, with **ONE `established_at_commit` for the whole manifest** — not one
> sha per class, because the floors were all established by a single run of a single commit.
> `scripts/test_count_manifest.py` fails on a DECREASE and allows an INCREASE; the governed set is
> **parsed from `production_gate.sh` on every run**, so adding a gated class without re-baselining
> fails the check. `collect_closure_evidence.py` folds the verdict into its exit code, and
> `verify_closure_evidence.py` now **requires** the verdict rather than merely accepting the key.
>
> **PROVEN**, and re-done because the first version of this claim in this file overstated it.
> Deleting `testScenarioI_financialSemanticsPreservedWithoutRawJson`, running gradle for that class
> alone — `BUILD SUCCESSFUL`, `Phase1FirestoreDocumentIdentityTest` at **18 against a floor of 19** —
> and then:
>
> ```
> python scripts/collect_closure_evidence.py   ->  exit 1   (was 0 before this branch)
> python scripts/verify_closure_evidence.py    ->  exit 1
> ```
>
> On a clean full run (113 XML, 801 tests, 0 failures) all three return 0 and the verdict is
> `READY_FOR_CLOSURE`.
>
> **Link by link.** The verdict travels through four hops, three measured and one a guarantee:
>
> | # | link | status |
> |--|:--|:--|
> | 1 | `test_count_manifest.check()` returns False on a decrease | **measured** |
> | 2 | `collect_closure_evidence.py` process exits 1 | **measured** — exit 1 |
> | 3 | `run_verified_command.py` propagates the child's code | **measured** — 1→1, 0→0, 3→3 |
> | 4 | `production_gate.sh` `set -e` aborts the script | **NOT EXECUTED** — bash semantics only |
>
> Hop 4 is the one still resting on a language guarantee rather than a run, and WSL is
> unavailable on this machine, so the bash script itself was never executed.
>
> Because a guarantee is exactly what a later edit breaks quietly, hop 4's preconditions are now
> asserted on every run by `gate_chain_intact()`: `set -euo pipefail` must be present, both
> evidence stages must actually be invoked, and neither invocation may swallow its status with
> `|| true`, `|| exit 0` or a trailing `;`. Appending `|| true` to line 114 would restore the
> exact failure r13 had — a gate that exits 0 while looking perfectly healthy — and the check
> now says so instead of passing quietly. Four fixtures pin it.


| | |
|:--|:--|
| **Location** | `scripts/production_gate.sh:86` (the `--tests` selection) and `scripts/collect_closure_evidence.py:214` |
| **Mechanism** | The gate's exit code is `0 if (failed_tests == 0 and error_tests == 0 and total_tests > 0) else 1`. There is no lower bound on `total_tests`. |
| **Live example — this branch** | Deleting `testScenarioJ_counterfactualRawPayloadContainsRawJson` changed `Phase1FirestoreDocumentIdentityTest` from **20 tests to 19**. The gate still passed. The deletion was correct and independently verified; the point is that **the gate could not have noticed**, and would equally not notice a test lost by accident, by a bad merge, or by a future deletion nobody reviewed. |
| **Second live example** | `DatabaseMigration17To18Test` cannot pass outside this machine — it loads a gitignored golden backup by bare filename. In a clean worktree the suite is 797 tests with 1 failure, so the gate fails for an unrelated reason and the real cause is not surfaced. |
| **What is checked today** | That nothing failed, and — since r13b — that no class the gate selects has lost tests. Still not checked: whether the bash wrapper propagates a non-zero exit. |
| **Why not this branch** | Changing the gate is a release-process change, and this branch's charter was test repairs. Recording it is the correct action. |
| **Suggested fix** (APPLIED in r13b) | Add a committed **expected-count manifest** — a file listing, per gated class, the test count and the commit that established it — and have `collect_closure_evidence.py` compare the measured count against it, failing on a **decrease** while tolerating an increase. A decrease is the direction that loses protection; an increase is the normal result of adding a test. Pinning to an exact number instead would make every legitimate new test a gate failure and train the next person to update the number without reading why, which is how a count check becomes a rubber stamp. The manifest should carry the commit sha so a count change is reviewable rather than mechanical. |

---

## GAP-7 addendum - DatabaseMigration17To18Test now SKIPS on a clean clone

| | |
|:--|:--|
| **Problem** | `earthlink_backup_1789281798680.zip` is untracked by design (`.gitignore:40`) because it wraps a 4 MB SQLite database of real subscriber history - 216 accounts, 2761 ledger rows. On a clean clone the test had no fixture, called `error(...)`, and FAILED, so the suite was permanently red for a reason unrelated to the code under test. |
| **Fix chosen** | **Skip, not track.** Committing the archive would publish subscriber financial history to the repository, so that option is excluded on privacy grounds, not convenience. `findGoldenZip()` now uses `org.junit.Assume.assumeTrue(..., false)` with the searched paths in the message. |
| **What this costs** | On a clean clone the 17 -> 18 migration is **UNVERIFIED**. A skip is not a pass. The class KDoc and the skip message both say so. |
| **How to run it** | Put the zip at the repository root, or one or two levels up. Nothing else changes. |

