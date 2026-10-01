# Open test-evidence gaps — recorded, not fixed

Recorded 2026-09-30 during pre-merge verification of `audit/test-evidence-r13`.

**Scope of this file.** These are *observations with a named mechanism*. Nothing here is a confirmed
defect, and none is repaired by the branch that recorded it. Each is out of scope for a repair plan
because fixing it requires a production change or a new artefact — see the "Why not this branch"
column. `AGENTS.md` §7.4 forbids the "while we're here" expansion that fixing them would be.

---

## GAP-1 — `UserDetailScreenV2.kt:382` fabricates a 0-IQD balance on gateway failure

| | |
|:--|:--|
| **Location** | `app/src/main/java/com/example/ui/viewmodels/UserDetailScreenV2.kt:382` |
| **Mechanism** | `resellerBalance = null` inside the `catch` is the **only** place the screen produces the null that `UserDetailScreenV2.kt:410` consumes. `getResellerBalance()` (`EarthlinkSearchViewModel.kt:144-145`) returns a non-null `Double`. |
| **Demonstrated** | Mutating `:382` to `0.0` leaves `EarthlinkSearchViewModelSeamTest` at **40 tests / 0 failures**. The result is a real-looking `0 IQD` "Balance After Renewal" row on gateway failure — the identical user-visible harm RED Invariant 1 exists to prevent. |
| **What guards it today** | Nothing. The Task 3 repair bound the **consumption** at `:410` by source-scanning the line's text. It does not bind the **production** of the null. |
| **Why not this branch** | Closing it needs a runtime seam. `balanceAfter` is a `val` local inside a `@Composable` and `resellerBalance` is Compose state; a JVM test cannot observe either. Fixing it means extracting a pure function and injecting it — a production change. |
| **Severity** | **Highest of the four.** It is a financial display value, and it is the only one of the four where a wrong value reaches the screen. |

## GAP-2 — `Workstream1StatementCorrelationTest.kt:24` never reaches the production parser

| | |
|:--|:--|
| **Location** | `app/src/test/java/com/example/Workstream1StatementCorrelationTest.kt:24` |
| **Mechanism** | A case-sensitive grep for `parseStatementTimestamp` over the file returns **0 hits**. The test asserts on `occurredAt` as a raw `String` (`Models.kt:327`) and never calls the statement correlator. |
| **Demonstrated** | The Task 4 pre-repair mutant that collapsed the zone branch left this sibling **green**, which is how the audit's claim that it "went red under the same mutation" was falsified. |
| **What guards it today** | Nothing. Its sibling `:194` was repaired in Task 4; this one was not, and it is the **same defect in the same file**. |
| **Why not this branch** | It is not among the fourteen adjudicated findings — it surfaced during repair, after the audit's scope was fixed. Repairing it is a new adjudication, not a repair. |

## GAP-3 — `conflictingExtId` (`SubscriberMatcher.kt:105`, `:121`) has never been covered in its rejecting role

| | |
|:--|:--|
| **Location** | `app/src/main/java/com/example/core/sync/SubscriberMatcher.kt:105` and `:121` |
| **Mechanism** | `conflictingExtId` is only evaluated when neither `:103` nor `:119` has already returned `false` for a history-only candidate. Every caller in the suite either passes no `extId`, passes a **matching** `extId` (which returns `Unique` at `:94` before Stage 3/4), or supplies a history-only candidate (which short-circuits first). |
| **Demonstrated** | A sweep of all five test files that drive `matchSubscriber`: `conflictingUsername` **is** covered with positive and negative controls; `conflictingExtId` is not exercised as `true` anywhere. |
| **What guards it today** | Nothing, and this is **not a regression** from the Task 6 repair — before it, the candidates were also history-only, so the flag was equally unevaluated. |
| **Why not this branch** | Repairing it needs a fixture whose candidate is **not** history-only and whose incoming `extId` conflicts — a new adjudication of a production rule the audit never classified. |
| **Lesson** | Repairing a double-guarded fixture can leave the second guard with **less** coverage than before, because the test that incidentally reached it has just been redirected. |

## GAP-4 — `Step3DurableDispatchTest.kt:48-67` is dead code carrying the collapsed-zone bug

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