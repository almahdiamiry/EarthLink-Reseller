# Scanner Declaration-Bound Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop `scan_test_evidence.py`'s declaration-bound recogniser from inventing F1 findings on ordinary Kotlin, and from missing a same-line declaration, without weakening any rule's real detection.

**Architecture:** The recogniser conflates two word classes that behave differently, and treats them with one rule. A **declaration starter** (`fun`, `class`, `val`…) makes a line a declaration by itself. A **modifier** (`data`, `value`, `inner`, `private`…) makes a line a declaration **only when a starter follows it**. One set plus the rule `words[0] in set or words[1] in set` is why `data = mapOf(` is read as a declaration. The fix separates the classes, matches starters case-sensitively, and stops the annotation walk after the annotation's own arguments rather than at end of line.

**Tech Stack:** Python 3 standard library only. No new dependencies, no Kotlin changes, no Gradle.

**Spec:** `docs/LESSONS_LEARNED/LL-ROUND-13-TEST-EVIDENCE-AUDIT.md` (§4 discloses four other latent scanner paths; this is the fifth and is not yet recorded), and the controller ledger at `.superpowers/sdd/2026-09-30-test-suite-evidence-audit/progress.md` under "RULING (mine) THE BRANCH IS NOT MERGE-READY".

## Global Constraints

- **A starter-only first word must never match by case.** Kotlin's `object` is a declaration keyword; `Object` is a type. The current `.lower()` collapses them.
- **A modifier alone is never a declaration.** `data = mapOf(` and `value.also {` are continuation lines. They must return `False`.
- **Preserve the behaviour that fixed C1.** An annotation standing alone on its line must still lead into the declaration on the **next** line. That behaviour is pinned by the `annotation_boundary` fixture group and must stay green.
- **Never weaken detection.** After the fix, a genuine declaration must still be recognised: `private suspend fun f() {`, `inner class I`, `value class Inner`, `data class D`, `@Test fun b() {`.
- **The self-test must report `ALL 27 SCANNER FIXTURE GROUPS PASSED` before and after**, plus any groups added here.
- **A scan of `app/src/test` must report 797 tests, 4305 assertion calls, and 0 findings across all seven rules, before and after.** If it does not, stop and report the difference rather than accepting a changed baseline.
- **Python standard library only. No Kotlin changes. Do not run Gradle.**
- **Every line number you cite, open at source first.** This branch has produced fifteen inherited citation and count defects, each caught downstream.
- Commit once per task.

---

### Task 1: Separate the word classes, match starters case-sensitively, and stop the annotation walk at the annotation

**Files:**
- Modify: `scripts/scan_test_evidence.py` — `_DECLARATION_KEYWORDS` at `:554-562`, `_starts_new_declaration` at `:565-627`
- Modify: `scripts/test_evidence_scanner_fixtures.py`

**Interfaces:**
- Consumes: nothing. First task.
- Produces: `_DECLARATION_STARTERS: frozenset[str]` and `_DECLARATION_MODIFIERS: frozenset[str]`, replacing `_DECLARATION_KEYWORDS`. **Check whether any other module references `_DECLARATION_KEYWORDS` before deleting it** — `rg`-equivalent it first; if something does, update that reference rather than leaving a dangling name.

- [ ] **Step 1: Write the failing fixtures first**

Add a fixture group covering every confirmed-defective shape. Each must be a **known-bad** case asserting `_starts_new_declaration` returns `False`, because each currently returns `True`:

```python
# continuation lines that must NOT read as declarations
("value.also { }", False),
("data.also { }", False),
("Data(1).also { }", False),      # case-sensitive: Data is a type, not `data`
("Object.foo()", False),          # case-sensitive: Object is a type, not `object`
("data = mapOf(", False),         # occurs 5x in the real suite
("value = compute()", False),
```

And the same-line-declaration cases that must now return `True` (each currently returns `False`):

```python
("@Test fun b() {", True),
('@Suppress("x") private fun helper() {', True),
```

Keep the existing genuine-declaration assertions green as regression protection: `private suspend fun f() {`, `inner class I`, `value class Inner`, `data class D`, `internal inline val x = 1`.

- [ ] **Step 2: Run the self-test and watch the new fixtures fail**

Run: `python scripts/test_evidence_scanner_fixtures.py`
Expected: FAIL naming the new cases. Record the failure output. **If it passes, the fixtures do not constrain the scanner and you must fix the fixtures before touching the scanner.**

- [ ] **Step 3: Split the keyword set**

```python
_DECLARATION_STARTERS = frozenset({
    "fun", "class", "object", "interface", "val", "var", "typealias", "init", "constructor",
})

_DECLARATION_MODIFIERS = frozenset({
    "companion", "enum", "annotation", "data", "sealed", "inner", "value", "open",
    "abstract", "override", "private", "public", "internal", "protected", "lateinit",
    "const", "inline", "suspend", "operator", "infix", "tailrec", "external", "final",
    "expect", "actual", "crossinline", "noinline", "reified", "vararg",
})
```

The starters are unchanged from today's first comment line. The modifiers are unchanged from today's second and third. Move nothing between them — the split is the whole fix, and a word in the wrong class reintroduces a defect.

- [ ] **Step 4: Make the decision rule class-aware and case-sensitive**

Replace the return at `:625-627`:

```python
if not words:
    return False
if words[0] in _DECLARATION_STARTERS:
    return True
return len(words) > 1 and words[0] in _DECLARATION_MODIFIERS and words[1] in _DECLARATION_STARTERS
```

**Stop calling `.lower()` when appending to `words`.** Starters are lowercase Kotlin keywords and a capitalised word is a different token. Note the consequence explicitly in a comment: `object` matches, `Object` does not.

- [ ] **Step 5: Stop the annotation walk after the annotation, not at end of line**

The current loop at `:589-611` consumes everything to the newline, so `@Test fun b() {` loses its `fun`. After consuming an annotation's name and its balanced `(`/`[` arguments, **peek at what follows on the same line**:

- If the rest of the line is blank or a `//` comment → the annotation stood alone → advance to the next line and re-enter the annotation loop. **This is the C1 behaviour and must be preserved.**
- If there is content → stop walking annotations and let the word reader read **that** content.

Work out the exact restructure yourself against the current code; the constraint is the two-branch behaviour above, not the particular loop shape.

- [ ] **Step 6: Rewrite the docstring's direction claim — it is currently backwards**

The docstring says the bound "may stop early, never late, because stopping late is what invents" a finding. Both directions are wrong and the docstring admits only one:

- **Stopping early** invents F1 — the test's assertions fall outside the range and it is reported vacuous.
- **Stopping late** under-reports — a genuinely vacuous test keeps its assertions inside the range and is not reported.

State that the recogniser now avoids both by construction for the shapes pinned in Step 1, and that the historical C1 defect was an early stop — which this docstring previously denied.

- [ ] **Step 7: Run the self-test and watch it pass**

Run: `python scripts/test_evidence_scanner.py` → wrong path; run `python scripts/test_evidence_scanner_fixtures.py`
Expected: PASS, with your new groups present in the count.

- [ ] **Step 8: Scan the real suite and confirm the baseline is unchanged**

Run: `python scripts/scan_test_evidence.py app/src/test`
Expected: **797 tests, 4305 assertion calls, 0 findings** across all seven rules — identical to before your change. If any number moves, stop and report it.

- [ ] **Step 9: Commit**

```bash
git add scripts/scan_test_evidence.py scripts/test_evidence_scanner_fixtures.py
git commit -m "fix(scan): separate declaration starters from modifiers in the expression bound"
```

---

### Task 2: Repair the fixtures' false claims and record the fifth latent path

**Files:**
- Modify: `scripts/test_evidence_scanner_fixtures.py` — the `inner`/`value` follower fixture comments, and `test_every_kotlin_modifier_is_recognised_as_a_declaration`
- Modify: `docs/LESSONS_LEARNED/LL-ROUND-13-TEST-EVIDENCE-AUDIT.md` — the disclosure list of latent scanner paths
- Modify: `scripts/scan_test_evidence.py` — the module docstring's never-invent sentence, if Task 1 left it qualified only for the fallback arm

**Interfaces:**
- Consumes: Task 1's split keyword sets.
- Produces: fixtures whose comments are true, a completeness check that is not a list-equality tautology, and a record that names five latent paths instead of four.

- [ ] **Step 1: Correct the two false fixture comments**

Both are false as written, and the fixtures themselves disprove them:

- The `inner class` comment says an inner class is only legal inside another `inner class`. **It is legal in any class** — and the fixture nests one in a normal test class, which is why it compiles.
- The `value class` comment says a value class may carry no members. **It may carry member functions** — and the fixture has one, which is why it compiles.

Because both fixtures compile, the surrounding "lexical-only fixtures" caveat is unnecessary. Correct the two comments; drop the caveat only if nothing else depends on it.

- [ ] **Step 2: Make the completeness check non-tautological**

`test_every_kotlin_modifier_is_recognised_as_a_declaration` compares two lists written by the same author. It catches drift, not omission. Replace it with a check that can fail for a reason the author did not intend:

- Assert that **every word in `_DECLARATION_STARTERS` and `_DECLARATION_MODIFIERS` is a lowercase ASCII token** — which is what makes case-sensitivity meaningful and catches a type name being added to either set.
- Assert that **no word is in both sets** — a word in both classes is a latent early-stop, the exact defect this task fixes.
- Assert that the two sets are **disjoint from a small, explicitly-written list of ordinary identifiers** that appear as locals in this suite: `data`, `value`, `type`, `object` are already handled by the class split, so the meaningful assertion is that **a bare identifier followed by `=` or `.` is never a declaration** — which is Step 1's fixture, restated as a set-level property.

Delete the docstring's claim that the list comes from Kotlin's **hard** keywords. `inner`, `value`, `data`, `sealed`, `open` and the rest are modifier and soft keywords. Say which class each word is in.

- [ ] **Step 3: Record this as the fifth latent path**

`LL-ROUND-13-TEST-EVIDENCE-AUDIT.md` currently discloses **four**: three in the fallback arm, and the class-attribution path. Add this one, with:

- the mechanism: one set plus `words[0] in set or words[1] in set` made a modifier alone sufficient, and `.lower()` made capitalised types match starters;
- the five real lines in the suite that match the shape, with file and line: `Change5SingleItemFallbackReadbackRemovalRegressionTest.kt:359` and `:405`, `Phase1FirestoreDocumentIdentityTest.kt:266`, `:300`, `:412`;
- **why it did not produce a wrong verdict**: all five sit in brace-bodied tests, where the expression-body bound at `:536` is not consulted, and the scanner reports 0 findings on 797 tests;
- **what makes it live instead of latent**: `data = …` is an ordinary assignment shape occurring five times in this suite, so one brace-to-expression conversion makes it live;
- the corrected direction of the error: early stopping invents F1, late stopping under-reports.

- [ ] **Step 4: Qualify the module docstring's guarantee if it is still unconditional**

If the never-invent sentence still covers every rule without naming this path, qualify it. State the direction that was **measured**, per the branch's existing standard: the real-suite scan shows 0 findings across all seven rules on 797 tests, and this path is unreachable in brace-bodied tests.

- [ ] **Step 5: Run both checks**

`python scripts/test_evidence_scanner_fixtures.py` → PASS with the group count you now report.
`python scripts/scan_test_evidence.py app/src/test` → 797 / 4305 / 0.

- [ ] **Step 6: Commit**

```bash
git add scripts docs/LESSONS_LEARNED
git commit -m "docs(scan): correct the fixture claims and record the fifth latent path"
```

---

## Self-Review

**Spec coverage.** The reviewer's six findings map to tasks: claim 1 (early stop invents F1) → Task 1 Steps 1, 3, 4; claim 2 (same-line annotation missed) → Task 1 Step 5; claim 3 (the direction claim is backwards) → Task 1 Step 6 and Task 2 Step 3; claim 4 (the completeness test is a tautology, and its docstring mislabels the keyword classes) → Task 2 Step 2; claim 5 (two false fixture comments) → Task 2 Step 1; claim 6 (the second-word path widens the surface) → Task 1 Step 4, which removes it — under the new rule `words[1]` is consulted only when `words[0]` is a modifier.

**No placeholders.** Every failing input, every replacement expression, and every expected scan figure is written out. Where the implementation is a judgement call — the annotation-loop restructure in Step 5 — the required behaviour is specified as two named branches and the constraint is stated, rather than dictating a loop shape that may not fit the existing code.

**Type consistency.** `_DECLARATION_STARTERS` and `_DECLARATION_MODIFIERS` are defined once in Task 1 Step 3 and consumed by name in Steps 4 and in Task 2 Steps 2 and 3. `_DECLARATION_KEYWORDS` is explicitly retired, with a check for other references before deletion.

**The one thing this plan cannot verify.** Whether 0 findings is the correct post-fix figure, or whether the fix reveals a real finding that was previously hidden by an over-long bound. Global Constraints requires stopping and reporting a moved baseline rather than accepting it, so the failure mode is a stop, not a silent acceptance.