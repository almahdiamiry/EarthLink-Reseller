#!/usr/bin/env python3
"""
scripts/scan_test_evidence.py

Static evidence scanner for the Earthlink Reseller App Kotlin test suite.

Purpose
  Task 1 of the Round 13 test-suite evidence audit. This is the triage instrument the
  rest of the audit depends on, so it is deliberately conservative: it may under-report a
  rule, but it must never invent one. Every finding it prints is a CANDIDATE that a human
  or a later task must adjudicate. It is not a gate and it does not certify anything.

Rules
  F1  Vacuous          A `@Test` body with no `assert*`, no `fail(`, no `verify*` call.
  F2  Tautological     An assertion that cannot fail: a single-argument boolean assertion
                       on a literal (`assertTrue(true)`), or `assertEquals(a, a)`.
  F3  Circular         The expected value of an `assertEquals` is a local bound to a member
                       call on the class under test rather than a literal.
  F4  No precondition  A test that polls (`while (attempts < N ...)`) and then asserts
                       without ever asserting that the awaited state was reached.
  F5  No RED proof     NOT statically detectable. This is an execution-only rule: it is
                       reported as always-false and is never guessed from source, because a
                       test that still passes with the behaviour under test removed can only
                       be established by running it.
  F6  Mock-only        The only assertions in the body are Mockito `verify*` /
                       `verifyNoInteractions*` calls.
  F7  Vacuous path     An assertion on a `var` that is overwritten later in the same body
                       with no read of that variable in between, so nothing on the exercised
                       path ever observes the asserted value.

Scoping rule (deliberate, load-bearing)
  A test body is the line range from its `@Test` to the NEXT `@Test` in the file, or to end
  of file. The scan does NOT stop at the `fun` signature line, because an annotation
  (`@Suppress`, `@DisplayName`, ...) may sit between them and stopping there hides the body
  and wrongly reports a live assertion as missing. The price of this choice is that helper
  functions declared after the last `@Test` of a class are inside that test's range; that
  can only make the scanner under-report a rule, never over-report one.

Body text handling
  Comments and string literals are blanked before the structural scan, so an `assertEquals`
  mentioned in a comment or inside a message string is never counted. Argument *content*
  is read from the comment-scrubbed text so that string literals remain visible to F2.

CLI
  python scripts/scan_test_evidence.py <root>
      Scan every `.kt` file under <root> and print every finding, followed by the
      per-rule counts Task 2 consumes.

  python scripts/scan_test_evidence.py <root> --rule <RULE>
      Print only that rule's findings (plus the per-rule summary). The rule name is
      accepted case-insensitively, as `F1`..`F7`. The alias `single-assertion` selects the
      single-assertion cohort instead, so the plan's
      `--rule single-assertion` invocation works.

  python scripts/scan_test_evidence.py <root> --single-assertion
      Print the file, line and test name of every `@Test` whose body contains exactly one
      assertion call, where an assertion call is `assert*`, `fail(` or `verify*`.

Exit codes
  0  on success, INCLUDING when findings exist. This is a triage instrument, not a release
     gate: a non-zero exit would be misread as a broken scanner when it has in fact worked.
  non-zero only on bad usage or an unreadable input.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from dataclasses import dataclass

RULES = ("F1", "F2", "F3", "F4", "F5", "F6", "F7")

RULE_TITLES = {
    "F1": "Vacuous",
    "F2": "Tautological",
    "F3": "Circular",
    "F4": "No precondition",
    "F5": "No RED proof",
    "F6": "Mock-only",
    "F7": "Vacuous path",
}

# F5 is execution-only: a test is only F5 if it still passes with the behaviour under test
# removed, and that can never be established by reading source. It is therefore declared
# here as always-false and never emitted as a finding.
ALWAYS_FALSE_RULES = frozenset({"F5"})

RULE_ALIASES = {
    "single-assertion": "single-assertion",
    "single_assertion": "single-assertion",
    "singleassertion": "single-assertion",
    "1-assertion": "single-assertion",
}

# An assertion call: assert*, fail(, verify*. verify* also covers verifyNoInteractions*.
_ASSERT_CALL_RE = re.compile(r"\b(assert[A-Za-z0-9_]*|fail|verify[A-Za-z0-9_]*)\s*\(")
_TEST_ANNOTATION_RE = re.compile(r"^[ \t]*@Test\b")
_FUN_NAME_RES = (
    re.compile(r"\bfun\s+([A-Za-z_]\w*)\s*\("),
    re.compile(r"\bfun\s+<[^>\n]*>\s+([A-Za-z_]\w*)\s*\("),
)
_CLASS_RE = re.compile(r"^[ \t]*(?:[A-Za-z_][\w\s]*\s+)?class\s+([A-Za-z_]\w*)")
_BARE_IDENT_RE = re.compile(r"^[A-Za-z_]\w*$")
_DECL_RE = re.compile(r"^[ \t]*(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+)?=\s*(.+?)\s*$")
_VAR_DECL_RE = re.compile(r"^[ \t]*var\s+([A-Za-z_]\w*)\b")
_REASSIGN_RE = re.compile(
    r"^[ \t]*([A-Za-z_]\w*)\s*(\+\+|--|\+=|-=|(?<![=!<>+\-*/%&|^])=(?!=))"
)
_MEMBER_CALL_RE = re.compile(r"^([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)\.([A-Za-z_]\w*)\s*\(")
_WHILE_RE = re.compile(r"\bwhile\s*\(")
_COMPARISON_RE = re.compile(r"^(.+?)\s*(==|!=|<=|>=|<|>)\s*(.+)$")
_COUNTER_WORDS = (
    "attempt", "retries", "retry", "tries", "try", "poll", "elapsed",
    "iteration", "loopcount", "spins",
)
_LITERAL_RE = re.compile(
    r"^(?:-?\d+(?:\.\d+)?[fFdDlL]?|\"[^\"\n]*\"|\"\"\"[\s\S]*?\"\"\"|true|false|null)$"
)
_SUT_INDICATOR_NAMES = (
    "sut", "sutInstance", "systemUnderTest", "underTest", "componentUnderTest",
)
_SUT_NAME_SUFFIXES = ("Tests", "Test", "Fixtures", "Fixture", "Spec", "Case")
_BOOLEAN_SINGLE_ARG_ASSERTS = ("assertTrue", "assertFalse", "assertNotNull", "assertNull")


@dataclass(frozen=True)
class Finding:
    """One candidate finding. `rule` is F1..F7, `line` is 1-indexed."""

    rule: str
    line: int
    test_name: str
    detail: str
    path: str = ""

    def format(self, path=None):
        shown = (path or self.path or "?").replace("\\", "/")
        return f"[{self.rule}] {shown}:{self.line} :: {self.test_name} :: {self.detail}"


@dataclass
class _TestBlock:
    class_name: str
    name: str
    start_line: int          # 1-indexed line of the @Test annotation
    end_line: int            # 1-indexed, inclusive; the line before the next @Test
    code: str                # comments and string contents blanked
    args_text: str           # comments blanked, string literals intact
    string_mask: list        # per-character: True when inside a string or char literal


# ------------------------------------------------------------------------------------------------
# Text preparation
# ------------------------------------------------------------------------------------------------

def _scrub(text, blank_string_contents=False):
    """
    Blank out Kotlin comments (and optionally string-literal contents) while preserving every
    newline and every character offset. Returns (no_comments, no_comments_no_strings, mask)
    where mask[i] is True when character i is inside a string or character literal.
    """
    no_comments = []
    no_strings = []
    mask = [False] * len(text)
    i = 0
    n = len(text)
    while i < n:
        two = text[i:i + 2]
        three = text[i:i + 3]
        if two == "//":
            j = text.find("\n", i)
            j = n if j == -1 else j
            no_comments.append(" " * (j - i))
            i = j
            continue
        if two == "/*":
            j = text.find("*/", i + 2)
            j = n if j == -1 else j + 2
            segment = text[i:j]
            no_comments.append("".join(ch if ch == "\n" else " " for ch in segment))
            i = j
            continue
        if three == '"""':
            j = text.find('"""', i + 3)
            j = n if j == -1 else j + 3
            segment = text[i:j]
            no_comments.append(segment)
            for k in range(i, j):
                mask[k] = text[k] == "\n"
            i = j
            continue
        if text[i] == '"':
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            j = min(j + 1, n)
            no_comments.append(text[i:j])
            for k in range(i, j):
                mask[k] = text[k] not in ('"', "\n")
            i = j
            continue
        if text[i] == "'":
            j = i + 1
            while j < n and text[j] != "'":
                j += 2 if text[j] == "\\" else 1
            j = min(j + 1, n)
            no_comments.append(text[i:j])
            for k in range(i, j):
                mask[k] = text[k] not in ("'", "\n")
            i = j
            continue
        no_comments.append(text[i])
        i += 1

    no_comments_text = "".join(no_comments)
    if not blank_string_contents:
        return no_comments_text, no_comments_text, mask

    out = []
    for k, ch in enumerate(no_comments_text):
        out.append(ch if ch == "\n" or not mask[k] else " ")
    return no_comments_text, "".join(out), mask


def _read_lines(path):
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read().splitlines()


def _line_of(text, index):
    return text.count("\n", 0, index) + 1


def _abs_line(block, index):
    """1-indexed absolute file line of `index` inside the block's scrubbed text."""
    return block.start_line - 1 + _line_of(block.code, index)


def _split_top_level(text, seps=(",",)):
    """
    Split `text` on any of `seps` (single or multi character), ignoring separators nested
    inside brackets. Angle brackets are deliberately NOT treated as brackets: `<` is a
    comparison operator far more often than it is a generic in a while condition.
    """
    parts = []
    depth = 0
    start = 0
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch in "([{":
            depth += 1
            i += 1
            continue
        if ch in ")]}":
            depth -= 1
            i += 1
            continue
        if depth == 0:
            hit = None
            for sep in seps:
                if text.startswith(sep, i):
                    hit = sep
                    break
            if hit:
                parts.append(text[start:i])
                i += len(hit)
                start = i
                continue
        i += 1
    parts.append(text[start:])
    return parts


def _strip_outer_parens(expr):
    """`(a && b)` -> `a && b`, but only when the parentheses really wrap the whole text."""
    current = expr.strip()
    while len(current) >= 2 and current[0] == "(" and current[-1] == ")":
        depth = 0
        wraps = True
        for idx, ch in enumerate(current):
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0 and idx != len(current) - 1:
                    wraps = False
                    break
        if not wraps:
            break
        current = current[1:-1].strip()
    return current


def _flatten_conjuncts(expr):
    """Flatten `a && (b && c) || d` into its atoms, dropping the grouping parentheses."""
    stripped = _strip_outer_parens(expr)
    atoms = [a for a in _split_top_level(stripped, seps=("&&", "||")) if a.strip()]
    if len(atoms) <= 1:
        return [stripped] if stripped else []
    flattened = []
    for atom in atoms:
        flattened.extend(_flatten_conjuncts(atom))
    return flattened


def _awaited_variants(expr):
    """
    The awaited state, plus its two-component tail (`a.b.c` also matches a bare `b.c`).
    The relaxation only ever suppresses an F4, so it cannot invent one.
    """
    variants = {expr}
    parts = expr.split(".")
    if len(parts) >= 3:
        variants.add(".".join(parts[-2:]))
    return variants



def _call_arguments(text, mask, open_index):
    """
    Split the argument list of a call whose `(` is at `open_index`. Parentheses inside string
    or character literals are ignored via the mask. Returns a list of stripped argument
    strings, or None when the call is not closed.
    """
    depth = 0
    parts = []
    start = open_index + 1
    i = open_index
    n = len(text)
    while i < n:
        if mask[i]:
            i += 1
            continue
        ch = text[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
            if depth == 0:
                parts.append(text[start:i])
                return [p.strip() for p in parts]
        elif ch == "," and depth == 1:
            parts.append(text[start:i])
            start = i + 1
        i += 1
    return None


def _normalize(expr):
    return re.sub(r"\s+", "", expr or "")


# ------------------------------------------------------------------------------------------------
# Test block extraction
# ------------------------------------------------------------------------------------------------

def _class_name_for(lines, start_index):
    for idx in range(start_index, -1, -1):
        match = _CLASS_RE.match(lines[idx])
        if match:
            return match.group(1)
    return ""


def _test_name_for(code, start_line):
    best = None
    for pattern in _FUN_NAME_RES:
        match = pattern.search(code)
        if match and (best is None or match.start() < best[0]):
            best = (match.start(), match.group(1))
    if best:
        return best[1]
    return f"<unnamed@test@{start_line}>"


def _build_blocks(path):
    lines = _read_lines(path)
    text = "\n".join(lines)
    no_comments, no_strings, mask = _scrub(text, blank_string_contents=True)
    args_text, _, _ = _scrub(text, blank_string_contents=False)
    no_comments_lines = no_comments.split("\n")

    starts = [idx for idx, line in enumerate(no_comments_lines) if _TEST_ANNOTATION_RE.match(line)]
    blocks = []
    for position, start_idx in enumerate(starts):
        end_idx = starts[position + 1] if position + 1 < len(starts) else len(lines)
        if end_idx <= start_idx:
            end_idx = start_idx + 1
        # Character range of [start_idx, end_idx) inside the joined text.
        char_start = sum(len(line) + 1 for line in lines[:start_idx])
        char_end = sum(len(line) + 1 for line in lines[:end_idx])
        code = no_strings[char_start:char_end]
        block_args = args_text[char_start:char_end]
        block_mask = mask[char_start:char_end]
        name = _test_name_for(code, start_idx + 1)
        blocks.append(_TestBlock(
            class_name=_class_name_for(no_comments_lines, start_idx),
            name=name,
            start_line=start_idx + 1,
            end_line=end_idx,
            code=code,
            args_text=block_args,
            string_mask=block_mask,
        ))
    return lines, blocks


def _assertion_calls(block):
    """[(name, line, open_paren_index)] for every assertion call in the block."""
    calls = []
    for match in _ASSERT_CALL_RE.finditer(block.code):
        calls.append((match.group(1), _abs_line(block, match.start()), match.end() - 1))
    return calls


def _declarations(block):
    decls = {}
    for raw in block.code.split("\n"):
        match = _DECL_RE.match(raw)
        if match and match.group(1) not in decls:
            decls[match.group(1)] = match.group(2)
    return decls


def _sut_names(class_name):
    names = set(_SUT_INDICATOR_NAMES)
    if class_name:
        stem = class_name
        for suffix in _SUT_NAME_SUFFIXES:
            if stem.endswith(suffix) and len(stem) > len(suffix):
                stem = stem[: -len(suffix)]
                break
        if stem:
            names.add(stem[0].lower() + stem[1:])
    return names


def _is_sut_call(expr, decls, sut_names, depth=0):
    """True when `expr` is a member call rooted at the class under test."""
    if depth > 2:
        return False
    match = _MEMBER_CALL_RE.match(expr.strip())
    if not match:
        return False
    root = match.group(1).split(".")[0]
    if root in sut_names:
        return True
    if root in decls:
        return _is_sut_call(decls[root], decls, sut_names, depth + 1)
    return False


# ------------------------------------------------------------------------------------------------
# Rule implementations
# ------------------------------------------------------------------------------------------------

def _check_f1(block, calls, path):
    if calls:
        return []
    return [Finding(
        rule="F1",
        line=block.start_line,
        test_name=block.name,
        detail="no assert*, fail( or verify* call in the test body",
        path=path,
    )]


def _check_f2(block, calls, path):
    findings = []
    reported = set()
    for name, line, open_index in calls:
        args = _call_arguments(block.args_text, block.string_mask, open_index)
        if not args:
            continue
        hit = None
        if name in _BOOLEAN_SINGLE_ARG_ASSERTS and len(args) == 1:
            if _LITERAL_RE.match(args[0].strip()):
                hit = f"{name}({args[0].strip()}) is asserted on a literal and cannot fail"
        elif name == "assertEquals" and len(args) == 2:
            left, right = _normalize(args[0]), _normalize(args[1])
            if left and right and left == right:
                hit = f"assertEquals({args[0]}, {args[1]}) compares one expression to itself"
        if hit and name not in reported:
            reported.add(name)
            findings.append(Finding("F2", line, block.name, hit, path))
    return findings


def _check_f3(block, calls, path):
    decls = _declarations(block)
    sut_names = _sut_names(block.class_name)
    for name, line, open_index in calls:
        if name != "assertEquals":
            continue
        args = _call_arguments(block.args_text, block.string_mask, open_index)
        if not args or len(args) != 2:
            continue
        for arg in args:
            arg = arg.strip()
            if not _BARE_IDENT_RE.match(arg):
                continue
            init = decls.get(arg)
            if init and _is_sut_call(init, decls, sut_names):
                return [Finding(
                    rule="F3",
                    line=line,
                    test_name=block.name,
                    detail=(
                        f"expected value '{arg}' is bound to '{init}', a call into the class "
                        f"under test, not a literal"
                    ),
                    path=path,
                )]
    return []


def _check_f4(block, calls, path):
    """
    F4: a bounded poll loop followed by assertions that never prove the awaited state was
    reached. The counter conjunct is separated from the state conjuncts so that asserting on
    the retry counter itself does not count as a precondition.
    """
    if not calls:
        return []
    first_assertion_line = min(line for _, line, _ in calls)
    asserted_text = _normalize(" ".join(
        " ".join(_call_arguments(block.args_text, block.string_mask, open_index) or [])
        for _, _, open_index in calls
    ))
    # A precondition may be proven by naming the awaited value inside the assertion, or by
    # naming a local that is bound to it. Following the binding is required: the real
    # `testSuccessfulTgzImport_...` test captures the awaited value into a local and asserts
    # on that, which is a real precondition, not a missing one.
    bound_names = {}
    for local_name, init in _declarations(block).items():
        bound_names.setdefault(_normalize(init), []).append(local_name)

    def observed_names(exprs):
        seen = set()
        frontier = list(exprs)
        while frontier:
            for local_name in bound_names.get(frontier.pop(), ()):
                if local_name not in seen:
                    seen.add(local_name)
                    frontier.append(local_name)
        return seen


    for match in _WHILE_RE.finditer(block.code):
        extracted = _call_arguments(block.code, block.string_mask, match.end() - 1)
        if not extracted or len(extracted) != 1:
            continue
        atoms = _flatten_conjuncts(extracted[0])
        counter_atoms = []
        state_atoms = []
        for atom in atoms:
            probe = _COMPARISON_RE.match(atom.strip())
            is_counter = (
                probe is not None
                and probe.group(2) in ("<", "<=")
                and any(word in probe.group(1).strip().lower() for word in _COUNTER_WORDS)
            )
            (counter_atoms if is_counter else state_atoms).append(atom)
        if not counter_atoms:
            continue

        awaited = set()
        for atom in state_atoms:
            probe = _COMPARISON_RE.match(atom.strip())
            operands = (probe.group(1), probe.group(3)) if probe else (atom,)
            for operand in operands:
                operand = operand.strip()
                if operand and not _LITERAL_RE.match(operand):
                    awaited |= _awaited_variants(_normalize(operand))
        if not awaited:
            continue

        loop_line = _abs_line(block, match.start())
        if first_assertion_line <= loop_line:
            continue
        observed = awaited | observed_names(awaited)
        if any(variant in asserted_text for variant in observed):
            continue
        return [Finding(
            rule="F4",
            line=loop_line,
            test_name=block.name,
            detail=(
                f"polls on {', '.join(sorted(counter_atoms))} until "
                f"{', '.join(sorted(state_atoms))}, then asserts at line "
                f"{first_assertion_line} without ever asserting the awaited state was reached"
            ),
            path=path,
        )]
    return []


def _check_f6(block, calls, path):
    if not calls:
        return []
    if all(name.startswith("verify") for name, _, _ in calls):
        kinds = sorted({name for name, _, _ in calls})
        return [Finding(
            rule="F6",
            line=calls[0][1],
            test_name=block.name,
            detail=f"the only assertions are Mockito calls: {', '.join(kinds)}",
            path=path,
        )]
    return []


def _check_f7(block, calls, path):
    var_names = set()
    for raw in block.code.split("\n"):
        match = _VAR_DECL_RE.match(raw)
        if match:
            var_names.add(match.group(1))
    if not var_names:
        return []

    lines = block.code.split("\n")
    writes = {}
    for idx, raw in enumerate(lines):
        match = _REASSIGN_RE.match(raw)
        if not match:
            continue
        name = match.group(1)
        if name in var_names and not _VAR_DECL_RE.match(raw):
            writes.setdefault(name, []).append(block.start_line + idx)

    for _, line, open_index in calls:
        args = _call_arguments(block.args_text, block.string_mask, open_index)
        if not args:
            continue
        for arg in args:
            arg = arg.strip()
            if not _BARE_IDENT_RE.match(arg) or arg not in var_names:
                continue
            later = [w for w in writes.get(arg, []) if w > line]
            if not later:
                continue
            next_write = min(later)
            read_in_between = any(
                re.search(rf"\b{re.escape(arg)}\b", lines[w - block.start_line])
                for w in range(line + 1, next_write)
            )
            if read_in_between:
                continue
            return [Finding(
                rule="F7",
                line=line,
                test_name=block.name,
                detail=(
                    f"asserts on '{arg}', which is overwritten at line {next_write} with no "
                    f"read of it in between, so the asserted value is never observed"
                ),
                path=path,
            )]
    return []


_RULE_CHECKS = {
    "F1": _check_f1,
    "F2": _check_f2,
    "F3": _check_f3,
    "F4": _check_f4,
    "F6": _check_f6,
    "F7": _check_f7,
}


# ------------------------------------------------------------------------------------------------
# Public API
# ------------------------------------------------------------------------------------------------

def scan_file(path):
    """Scan one Kotlin file. Returns a list of Finding, ordered by line."""
    _lines, blocks = _build_blocks(path)
    findings = []
    for block in blocks:
        calls = _assertion_calls(block)
        for rule in ("F1", "F2", "F3", "F4", "F6", "F7"):
            findings.extend(_RULE_CHECKS[rule](block, calls, path))
    findings.sort(key=lambda f: (f.line, f.rule))
    return findings


def list_tests(path):
    """Every @Test in one file: {name, line, class_name, end_line, assertion_count}."""
    _lines, blocks = _build_blocks(path)
    return [
        {
            "name": block.name,
            "line": block.start_line,
            "end_line": block.end_line,
            "class_name": block.class_name,
            "assertion_count": len(_assertion_calls(block)),
        }
        for block in blocks
    ]


def _kotlin_files(root):
    if os.path.isfile(root):
        return [root] if root.endswith(".kt") else []
    collected = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        for filename in sorted(filenames):
            if filename.endswith(".kt"):
                collected.append(os.path.join(dirpath, filename))
    return collected


def scan_tree(root):
    """Scan every .kt file under `root` (or the single file `root`). Sorted by (path, line)."""
    findings = []
    for path in _kotlin_files(root):
        findings.extend(scan_file(path))
    findings.sort(key=lambda f: (f.path, f.line, f.rule))
    return findings


def single_assertion_tests(root):
    """Every @Test whose body holds exactly one assertion call: {path, line, name}."""
    rows = []
    for path in _kotlin_files(root):
        for test in list_tests(path):
            if test["assertion_count"] == 1:
                rows.append({
                    "path": path,
                    "line": test["line"],
                    "name": test["name"],
                    "class_name": test["class_name"],
                })
    rows.sort(key=lambda r: (r["path"], r["line"]))
    return rows


def rule_counts(findings):
    """{rule: count} over the seven rules, in rule order."""
    counts = {rule: 0 for rule in RULES}
    for finding in findings:
        if finding.rule in counts:
            counts[finding.rule] += 1
    return counts


# ------------------------------------------------------------------------------------------------
# CLI
# ------------------------------------------------------------------------------------------------

def _print_summary(root, findings, files, tests_total, assertions_total, counts):
    print("=" * 78)
    print("=== Static Test-Evidence Scanner (triage instrument, not a gate) ===")
    print("=" * 78)
    print(f"Root                : {root}")
    print(f"Kotlin files scanned: {files}")
    print(f"@Test discovered    : {tests_total}")
    print(f"Assertion calls     : {assertions_total}")
    print("-" * 78)
    print("Per-rule counts")
    for rule in RULES:
        tag = " (execution-only: reported always-false, never inferred from source)" \
            if rule in ALWAYS_FALSE_RULES else ""
        print(f"  {rule}  {RULE_TITLES[rule]:<15} : {counts[rule]}{tag}")
    print("-" * 78)
    print(f"Total findings      : {len(findings)}")
    print("Exit code is 0 by design: findings are candidates for adjudication, not failures.")
    print("=" * 78)


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Statically scan Kotlin @Test bodies for evidence-quality rules F1..F7.",
    )
    parser.add_argument("root", help="Directory (or single .kt file) to scan")
    parser.add_argument(
        "--rule",
        help="Print only this rule: F1..F7, case-insensitive, or 'single-assertion'.",
    )
    parser.add_argument(
        "--single-assertion",
        action="store_true",
        help="List every @Test whose body contains exactly one assertion call.",
    )
    args = parser.parse_args(argv)

    if not os.path.exists(args.root):
        print(f"[ERROR] Path not found: {args.root}")
        return 2

    selector = None
    if args.rule:
        key = args.rule.strip().lower()
        if key in RULE_ALIASES:
            selector = RULE_ALIASES[key]
        elif key.upper() in RULES:
            selector = key.upper()
        else:
            print(f"[ERROR] Unknown rule '{args.rule}'. Expected one of "
                  f"{', '.join(RULES)} (case-insensitive) or 'single-assertion'.")
            return 2

    files = _kotlin_files(args.root)
    tests_total = 0
    assertions_total = 0
    for path in files:
        for test in list_tests(path):
            tests_total += 1
            assertions_total += test["assertion_count"]

    if args.single_assertion or selector == "single-assertion":
        rows = single_assertion_tests(args.root)
        print("=" * 78)
        print("=== Single-assertion @Test cohort (exactly one assert*/fail(/verify* call) ===")
        print("=" * 78)
        for row in rows:
            print(f"{row['path']}:{row['line']}  {row['name']}  [{row['class_name']}]")
        print("-" * 78)
        print(f"Total: {len(rows)} single-assertion tests out of {tests_total} discovered.")
        print("=" * 78)
        return 0

    all_findings = scan_tree(args.root)
    findings = [f for f in all_findings if f.rule == selector] if selector else all_findings
    counts = rule_counts(findings)

    if findings:
        print("=" * 78)
        for finding in findings:
            print(finding.format())
        print("-" * 78)
    _print_summary(args.root, findings, len(files), tests_total, assertions_total, counts)
    return 0


if __name__ == "__main__":
    sys.exit(main())
