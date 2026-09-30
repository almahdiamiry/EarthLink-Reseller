#!/usr/bin/env python3
"""
scripts/scan_test_evidence.py

Static evidence scanner for the Earthlink Reseller App Kotlin test suite.

Purpose
  Task 1 of the Round 13 test-suite evidence audit. This is the triage instrument the
  rest of the audit depends on, so it is deliberately conservative: every rule is written to
  under-report rather than over-report. That is a design bias, not a guarantee, and it is not
  unconditional. Every finding it prints is a CANDIDATE that a human or a later task must
  adjudicate. It is not a gate and it does not certify anything.

Rules
  F1  Vacuous          A `@Test` body with no `assert*`, no `fail(`, no `verify*` call, and
                       no call to a helper in the same file that asserts or throws
                       AssertionError.
  F2  Tautological     An assertion that cannot fail: a single-argument boolean assertion
                       on a literal (`assertTrue(true)`), or `assertEquals(a, a)` in its
                       two-argument form. The three-argument
                       `assertEquals(message, expected, actual)` overload is deliberately NOT
                       compared, because the message occupies an ambiguous argument position.
  F3  Circular         The expected value of an `assertEquals` is a local bound to a member
                       call on the class under test rather than a literal. A local whose
                       declared or constructed type is a test double (stub/fake/mock/spy) is
                       not the class under test, however similar its name.
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
  A test body runs from its `@Test` to the end of that test's own function body, located by
  matching the braces of its `fun`. It does NOT stop at the `fun` signature line, because an
  annotation (`@Suppress`, `@DisplayName`, ...) may sit between them and stopping there hides
  the body and wrongly reports a live assertion as missing. It is also NOT bounded at the
  next `@Test`, because a test that delegates its only assertion to a helper declared after
  the next `@Test` is still verified, and bounding there reported it as vacuous.

  What the brace match guarantees, precisely: the block never stops early, so it cannot hide
  an assertion and invent a vacuity finding. It does NOT bound the block from running past
  the test's own end: a private helper declared between two `@Test`s, or after the last one,
  is inside the preceding test's range. That can inflate the preceding test's assertion count
  and can move a finding's line number. It cannot create a vacuity finding, because F1 counts
  only calls and a helper's assertion is a call the delegating test would reach anyway.

  A brace match that cannot be resolved — an expression-bodied `fun x() = ...`, or a file
  that does not balance — falls back to the next `@Test`, or to end of file. That fallback
  is the weaker of the two bounds and is the one place where a vacuity finding could be
  invented; the self-test pins the resolvable case.

Body text handling
  Comments and string literals are blanked before the structural scan, so an `assertEquals`
  mentioned in a comment, or inside a message string, is never counted and never becomes a
  finding. This holds for triple-quoted raw strings too: a raw string is a literal, and text
  inside one is text, not an assertion. Argument *content* for F2 is read from the
  comment-scrubbed text, which keeps string literals visible to the literal test but still
  excludes raw-string interiors from the call scan.

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
# A local whose declared type is a test double is not the class under test, however similar its
# name is. Without this, a stub that hard-codes its return value would be read as circular.
_TEST_DOUBLE_MARKERS = ("stub", "fake", "mock", "spy", "double", "dummy")
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
    end_line: int            # 1-indexed, inclusive; the test's own closing brace
    code: str                # comments and string contents blanked
    args_text: str           # comments blanked, string literals intact
    string_mask: list        # per-character: True when inside a string or char literal
    helpers: dict            # function name -> scrubbed body, for the whole file


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
            # Every character of a raw string is literal content, not only its newlines. A raw
            # string is still a string: an `assertEquals` inside one is text, and counting it
            # as a call both invents an F2 and suppresses a real F1.
            for k in range(i, j):
                mask[k] = text[k] != "\n"
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


def _function_body_end(code, mask, fun_start):
    """
    The character index one past the `}` that closes the function whose `fun` keyword starts at
    `fun_start`, or None when the body cannot be located (an expression-bodied function, or a
    file that does not balance).

    The block bound is what stops F1 from inventing findings, so it has to be the function's own
    closing brace and not merely the next `@Test`: a test that delegates its only assertion to a
    helper declared after the next `@Test` is still verified, and bounding at the next `@Test`
    reports it as vacuous.
    """
    depth = 0
    i = fun_start
    n = len(code)
    while i < n:
        if mask[i]:
            i += 1
            continue
        ch = code[i]
        if ch in "([":
            depth += 1
        elif ch in ")]":
            depth -= 1
        elif ch == "{" and depth == 0:
            # Walk the function's own braces to their match.
            brace = 0
            j = i
            while j < n:
                if not mask[j]:
                    if code[j] == "{":
                        brace += 1
                    elif code[j] == "}":
                        brace -= 1
                        if brace == 0:
                            return j + 1
                j += 1
            return None
        elif ch == "=" and depth == 0:
            # Expression body: `fun x() = runBlocking { ... }` is the dominant shape in this
            # suite (578 of 798 tests). The body is the first `{` after the `=`, which may sit
            # on the next line when the declaration is wrapped. Returning end-of-line instead
            # truncates the body and manufactures F1 findings.
            j = i
            while j < n:
                if code[j] == "{" and not mask[j]:
                    brace = 0
                    k = j
                    while k < n:
                        if not mask[k]:
                            if code[k] == "{":
                                brace += 1
                            elif code[k] == "}":
                                brace -= 1
                                if brace == 0:
                                    return k + 1
                        k += 1
                    return None
                if code[j] == "\n" and _starts_new_declaration(code, j + 1):
                    return j
                j += 1
            return n
        i += 1
    return None


_DECLARATION_STARTS = (
    "fun ", "private fun ", "internal fun ", "public fun ", "override fun ",
    "@", "}", "class ", "private class ", "object ", "companion object", "val ", "var ",
)


def _starts_new_declaration(code, index):
    """True when the next line begins a new declaration, ending an expression body."""
    k = index
    while k < len(code) and code[k] in " \t":
        k += 1
    rest = code[k:k + 24].lstrip()
    return any(rest.startswith(prefix) for prefix in _DECLARATION_STARTS)


def _index_helper_functions(code, mask):
    """
    Map every `fun name(...)` in the file to its scrubbed body, so a test that delegates its
    verification to a helper can be recognised as non-vacuous.

    A helper counts as verifying when its body contains an assertion call or throws
    AssertionError, which is the same contract JUnit gives it. A test whose only call is such a
    helper is verified; reporting it F1 would be inventing a finding.
    """
    helpers = {}
    for match in re.finditer(r"\bfun\s+([A-Za-z_]\w*)\s*\(", code):
        name = match.group(1)
        end = _function_body_end(code, mask, match.start())
        if end is None:
            continue
        helpers.setdefault(name, code[match.start():end])
    return helpers


def _assertion_calls_in(text):
    return [m.group(1) for m in _ASSERT_CALL_RE.finditer(text)]


def _verifies_via_helper(block, depth=0):
    """
    True when the test body calls a helper in this file that itself asserts or throws
    AssertionError. Depth-limited to keep the walk bounded; a helper chain deeper than this is
    reported as delegating anyway, which can only suppress an F1, never invent one.
    """
    if depth > 2:
        return False
    called = set(re.findall(r"\b([A-Za-z_]\w*)\s*\(", block.code))
    for name in called:
        body = block.helpers.get(name)
        if not body or name == block.name:
            continue
        if _assertion_calls_in(body) or "AssertionError" in body:
            return True
        nested = _TestBlock(
            class_name=block.class_name,
            name=block.name,
            start_line=block.start_line,
            end_line=block.end_line,
            code=body,
            args_text=body,
            string_mask=[False] * len(body),
            helpers=block.helpers,
        )
        if _verifies_via_helper(nested, depth + 1):
            return True
    return False


def _build_blocks(path):
    lines = _read_lines(path)
    text = "\n".join(lines)
    no_comments, no_strings, mask = _scrub(text, blank_string_contents=True)
    args_text, _, _ = _scrub(text, blank_string_contents=False)
    no_comments_lines = no_comments.split("\n")

    starts = [idx for idx, line in enumerate(no_comments_lines) if _TEST_ANNOTATION_RE.match(line)]
    helpers = _index_helper_functions(no_strings, mask)
    blocks = []
    for position, start_idx in enumerate(starts):
        char_start = sum(len(line) + 1 for line in lines[:start_idx])
        next_test_idx = starts[position + 1] if position + 1 < len(starts) else None

        # Locate this test's own `fun` and the end of its body.
        fun_match = None
        for pattern in _FUN_NAME_RES:
            candidate = pattern.search(no_strings, char_start)
            if candidate and (fun_match is None or candidate.start() < fun_match.start()):
                fun_match = candidate
        body_end = None
        if fun_match is not None:
            body_end = _function_body_end(no_strings, mask, fun_match.start())

        if body_end is not None:
            end_char = body_end
        elif next_test_idx is not None:
            end_char = sum(len(line) + 1 for line in lines[:next_test_idx])
        else:
            end_char = len(text)
        # Never let the block end before the next @Test: trailing helpers between two tests
        # belong to neither, and shrinking the range can only hide assertions.
        if next_test_idx is not None:
            end_char = max(end_char, sum(len(line) + 1 for line in lines[:next_test_idx]))
        end_char = min(end_char, len(text))
        end_idx = no_strings.count("\n", 0, end_char) + 1
        if end_idx <= start_idx:
            end_idx = start_idx + 1

        code = no_strings[char_start:end_char]
        block_args = args_text[char_start:end_char]
        block_mask = mask[char_start:end_char]
        name = _test_name_for(code, start_idx + 1)
        blocks.append(_TestBlock(
            class_name=_class_name_for(no_comments_lines, start_idx),
            name=name,
            start_line=start_idx + 1,
            end_line=end_idx,
            code=code,
            args_text=block_args,
            string_mask=block_mask,
            helpers=helpers,
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


def _is_test_double(local_name, decls):
    """
    True when `local_name` is bound to something whose type is a test double, either from an
    explicit `val x: FooStub = ...` or from a constructor call `val x = FooStub()`.
    """
    init = (decls.get(local_name) or "").strip()
    if not init:
        return False
    typed = re.match(r"^[A-Za-z_][\w.<>?]*\s*:\s*([A-Za-z_][\w.]*)", init)
    if typed:
        type_name = typed.group(1).rsplit(".", 1)[-1].lower()
        return any(marker in type_name for marker in _TEST_DOUBLE_MARKERS)
    constructed = re.match(r"^([A-Z][\w.]*)\s*[({]", init)
    if constructed:
        type_name = constructed.group(1).rsplit(".", 1)[-1].lower()
        return any(marker in type_name for marker in _TEST_DOUBLE_MARKERS)
    return False


def _is_sut_call(expr, decls, sut_names, depth=0):
    """True when `expr` is a member call rooted at the class under test."""
    if depth > 2:
        return False
    match = _MEMBER_CALL_RE.match(expr.strip())
    if not match:
        return False
    root = match.group(1).split(".")[0]
    if root in sut_names:
        # A name match alone is not enough: a stub or fake named after the SUT is a test double,
        # and reading a hard-coded value out of one is not circularity.
        return not _is_test_double(root, decls)
    if root in decls:
        return _is_sut_call(decls[root], decls, sut_names, depth + 1)
    return False


# ------------------------------------------------------------------------------------------------
# Rule implementations
# ------------------------------------------------------------------------------------------------

def _check_f1(block, calls, path):
    if calls:
        return []
    # A test that delegates its verification to a helper in this file is not vacuous. The
    # helper may assert directly or throw AssertionError; both are real verification. Without
    # this, any test whose only assertion lives in a helper is reported F1, which is the one
    # finding this scanner must never invent.
    if _verifies_via_helper(block):
        return []
    return [Finding(
        rule="F1",
        line=block.start_line,
        test_name=block.name,
        detail="no assert*, fail( or verify* call in the test body, directly or via a helper",
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
