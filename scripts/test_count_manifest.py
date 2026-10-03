"""
GAP-7 - expected test-count manifest.

The release gate asserted that nothing FAILED and said nothing about whether anything was still
THERE, so a deleted test could not turn it red. The live example was r13: deleting Scenario J took
Phase1FirestoreDocumentIdentityTest from 20 tests to 19 and the gate passed, because the deletion
happened to be correct - the gate simply had no way to tell.

THE ASYMMETRY IS THE POINT.

  DECREASE -> failure. A lost test is lost protection, and nothing else in the gate can observe it.
  INCREASE -> allowed. Adding a test is how this suite normally grows. Pinning to an exact number
               would make every legitimate addition a gate failure, which trains the next person to
               edit this manifest without reading why. That is how a count check becomes a rubber
               stamp, and it would be strictly worse than having no check at all.

So the manifest records a FLOOR per governed class, not an expectation.

DEPENDENCIES: none. The manifest is JSON and this module is stdlib-only, deliberately. The gate
chain's OTHER scripts do import PyYAML (collect_closure_evidence.py for closure_contract.yaml), and
that dependency is declared in scripts/requirements.txt rather than left implicit - but the floor
check itself must not add a second reason for the gate to fail to start.

SCOPE, and why it is checked rather than trusted: the governed set is derived by PARSING
scripts/production_gate.sh, not copied from it. A hand-copied list rots the first time someone adds
a class to the gate, and a floor check that silently stops covering a newly gated class is worse than
no check at all - it reads as coverage. `gate_selections()` returns what the script actually asks
for, and `check()` fails when that disagrees with the manifest.

Usage
-----
    python scripts/test_count_manifest.py            # verify against the current JUnit XMLs
    python scripts/test_count_manifest.py --write    # re-baseline (only with a stated reason)

Exit code 0 when every governed class is at or above its floor, 1 otherwise.
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MANIFEST_PATH = os.path.join(REPO_ROOT, "scripts", "test_count_manifest.json")
GATE_PATH = os.path.join(REPO_ROOT, "scripts", "production_gate.sh")
RESULTS_DIR = os.path.join(REPO_ROOT, "app", "build", "test-results", "testDebugUnitTest")

_TEST_SELECT = re.compile(r'--tests\s+"([^"]+)"')


def gate_selections(gate_path: str = GATE_PATH) -> list[str]:
    """
    Parse the class list straight out of production_gate.sh.

    Every `--tests "..."` in the file is collected, in order, duplicates removed. Parsing rather
    than hardcoding is the whole point: if someone adds a gated class and forgets this module, the
    mismatch check in `check()` fails and says so, instead of the manifest quietly ceasing to cover
    the new class.
    """
    try:
        with open(gate_path, "r", encoding="utf-8", errors="replace") as fh:
            text = fh.read()
    except FileNotFoundError:
        return []
    seen, out = set(), []
    for cls in _TEST_SELECT.findall(text):
        if cls not in seen:
            seen.add(cls)
            out.append(cls)
    return out


def measure(results_dir: str = RESULTS_DIR) -> dict:
    """
    Return {classname: (tests, skipped)} from every JUnit XML in `results_dir`.

    The floor must never be satisfied by a test that did not run, so `tests` alone is the wrong
    number to compare against a floor: in JUnit a skipped testcase is still counted in `tests`. The
    executed count is `tests - skipped`, and `check()` needs the raw skipped value as well so it can
    say WHY a suite failed rather than only that it did.

    Returning a pair keeps the two facts together and keeps this the single place the executed-count
    rule lives. `write()` re-baselines from the same measurement, so a suite cannot acquire a floor
    that its own skips are quietly propping up.
    """
    counts: dict = {}
    for path in glob.glob(os.path.join(results_dir, "*.xml")):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            print(f"[WARN] could not parse {os.path.basename(path)}: {exc}")
            continue
        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        for suite in suites:
            name = suite.attrib.get("name", "")
            tests = int(suite.attrib.get("tests", 0))
            skipped = int(suite.attrib.get("skipped", 0))
            if name in counts:
                prev_tests, prev_skipped = counts[name]
                counts[name] = (prev_tests + tests, prev_skipped + skipped)
            else:
                counts[name] = (tests, skipped)
    return counts


def head_commit() -> str:
    try:
        return subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=REPO_ROOT, capture_output=True, text=True, check=True,
        ).stdout.strip()
    except Exception:
        return "unknown"


def load(manifest_path: str = MANIFEST_PATH) -> dict:
    with open(manifest_path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def check(measured: dict, manifest: dict, gate_path: str = GATE_PATH) -> tuple[bool, list]:
    """Compare measured against the floor. Returns (ok, lines)."""
    floors = manifest.get("classes", {}) or {}
    governed = gate_selections(gate_path)
    lines, ok = [], True

    chain_ok, chain_lines = gate_chain_intact(gate_path)
    ok = ok and chain_ok
    lines.extend(chain_lines)

    # --- the governed set must match production_gate.sh, in both directions -------------
    if not governed:
        lines.append(
            "  [NO-GATE]    could not read any `--tests` selection out of scripts/production_gate.sh. "
            "The floor check has no governed set, so it is checking nothing."
        )
        ok = False
    else:
        missing = [c for c in governed if c not in floors]
        if missing:
            lines.append(
                f"  [UNGOVERNED] {len(missing)} class(es) are gated by production_gate.sh but absent "
                f"from the manifest: {missing}. Either the manifest is stale or the gate selection "
                f"changed without a re-baseline."
            )
            ok = False
        orphan = [c for c in floors if c not in governed]
        if orphan:
            lines.append(
                f"  [ORPHAN]     {len(orphan)} class(es) are in the manifest but no longer gated by "
                f"production_gate.sh: {orphan}. A floor for an ungated class is bookkeeping nobody "
                f"reads."
            )
            ok = False

    # --- each governed class must be at or above its floor, counting only EXECUTED tests ----
    # The invariant, in one place: a governed suite may satisfy the floor only with tests that
    # actually ran. `tests - skipped` is the executed count, and skipped must be zero outright -
    # a skip is not a partial pass, it is an absence, and the floor is a promise about protection
    # that a skipped testcase does not keep.
    for cls in governed:
        floor = floors.get(cls)
        pair = measured.get(cls)
        if floor is None:
            continue  # already reported as UNGOVERNED above
        if pair is None:
            lines.append(
                f"  [MISSING]   {cls}: expected >= {floor} tests, but no JUnit XML was produced. "
                f"A gated class that reports nothing is a gate that ran nothing."
            )
            ok = False
            continue
        actual_tests, skipped = pair
        executed = actual_tests - skipped
        if skipped > 0:
            lines.append(
                f"  [SKIPPED]   {cls}: {skipped} of {actual_tests} test(s) were SKIPPED, leaving "
                f"{executed} executed against a floor of {floor}. A skipped test is not a partial "
                f"pass - it is an absence, and it does not satisfy the floor. The gate cannot prove "
                f"the protection that test was providing."
            )
            ok = False
        if executed < floor:
            lines.append(
                f"  [DECREASED] {cls}: {executed} executed test(s), floor is {floor} "
                f"({floor - executed} short, of {actual_tests} reported). "
                f"Losing a test loses protection and nothing else here can see it. If the removal "
                f"was intended, re-baseline with --write in the same commit as the removal."
            )
            ok = False
        elif executed > floor:
            lines.append(
                f"  [ok +{executed - floor}]  {cls}: {executed} executed test(s), floor is {floor} "
                f"(increase allowed)")
        else:
            lines.append(
                f"  [ok]        {cls}: {executed} executed test(s), floor is {floor}"
                + (f" ({skipped} skipped)" if skipped else ""))

    return ok, lines


def write(measured: dict) -> int:
    governed = gate_selections()
    if not governed:
        print("[FAIL] production_gate.sh yielded no `--tests` selections; refusing to write a manifest.",
              file=sys.stderr)
        return 1
    sha = head_commit()
    doc = {
        "_comment": [
            "GAP-7 expected test-count manifest.",
            "A DECREASE below the floor fails the gate. An INCREASE is allowed: adding a test is",
            "how this suite normally grows, and pinning to an exact number would make every",
            "legitimate addition a failure - which trains the next person to bump the number",
            "without reading why. A count check that becomes a rubber stamp is worse than none.",
            "ONE commit is recorded for the whole manifest below, not one per class: the floors",
            "were all established by a single run of a single commit. Re-baselining rewrites that",
            "one field.",
            "The governed set is NOT stored here as the source of truth - it is parsed from",
            "scripts/production_gate.sh on every run, and a mismatch fails the check.",
            "Regenerate: python scripts/test_count_manifest.py --write",
            "Verify:     python scripts/test_count_manifest.py",
        ],
        "schema_version": 1,
        "established_at_commit": sha,
        "governed_source": "scripts/production_gate.sh (--tests selections, parsed at run time)",
        # Floors are EXECUTED counts. Baselining from `tests` would let a suite's own skips
        # raise its floor, which is the bug in reverse: the manifest would then require fewer
        # real runs than the suite actually performs. A suite that skipped is refused outright
        # so a re-baseline can never be taken from a partial run.
        "classes": {cls: measured[cls][0] - measured[cls][1]
                    for cls in sorted(governed) if cls in measured and measured[cls][1] == 0},
    }
    with open(MANIFEST_PATH, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, indent=2, ensure_ascii=False)
        fh.write("\n")
    skipped_suites = [c for c in sorted(governed)
                      if c in measured and measured[c][1] > 0]
    if skipped_suites:
        print(
            f"[REFUSED]  {len(skipped_suites)} governed suite(s) skipped tests and were NOT given "
            f"a floor: {skipped_suites}"
        )
    print(f"[written] {MANIFEST_PATH}")
    print(f"  established_at_commit = {sha}  (ONE sha for the whole manifest, not per class)")
    print(f"  governed classes     = {len(doc['classes'])} parsed from {os.path.relpath(GATE_PATH, REPO_ROOT)}")
    return 0


def gate_chain_intact(gate_path: str = GATE_PATH) -> tuple[bool, list]:
    """
    GAP-7, second half: prove the verdict can REACH production_gate.sh's own exit code.

    The floor verdict travels through four links, and each was checked or measured separately:

      1. test_count_manifest.check()      -> False on a decrease          (this module, measured)
      2. collect_closure_evidence.py      -> process exit 1               (measured)
      3. run_verified_command.py          -> propagates the child's code  (measured: 1->1, 3->3)
      4. production_gate.sh `set -e`      -> aborts the script            (bash semantics, NOT executed
                                             here - WSL is unavailable on this machine)

    Link 4 is the one still resting on a language guarantee rather than a run, and a guarantee is
    exactly the kind of thing a later edit quietly breaks. So the structural preconditions for it
    are asserted here, on every run: `set -euo pipefail` must be present, both evidence stages must
    actually be invoked, and neither invocation may be followed by anything that swallows a non-zero
    status (`|| true`, `|| exit 0`, a trailing `;`). If somebody later appends `|| true` to one of
    those lines, the gate goes back to exiting 0 while looking perfectly healthy - which is the
    precise failure mode r13 had.

    Returns (ok, lines).
    """
    lines, ok = [], True
    try:
        with open(gate_path, "r", encoding="utf-8", errors="replace") as fh:
            lines_src = fh.read().splitlines()
    except FileNotFoundError:
        return False, ["  [NO-GATE]    scripts/production_gate.sh not found"]

    if not any(l.strip().startswith("set -e") for l in lines_src):
        lines.append(
            "  [NO-SET-E]   production_gate.sh has no `set -e`. Without it a non-zero exit from "
            "the evidence stages is ignored and the script runs on to `exit 0`."
        )
        ok = False

    # `set -e` at the top is not the whole story: a later `set +e` silently turns errexit back off
    # for the rest of the script, and nothing else in the file would reveal it. That is the easiest
    # possible way for this chain to rot - one word, no visible diff of consequence - so it is
    # checked explicitly rather than left to a reader to notice.
    for i, l in enumerate(lines_src, 1):
        st = l.strip()
        if st.startswith("set ") and "+e" in st.replace(" ", "").replace("-", "", 1):
            lines.append(
                f"  [ERREXIT-OFF] production_gate.sh:{i} runs `{st}`, which disables errexit for "
                f"the rest of the script. Everything after that point ignores a non-zero exit from "
                f"the evidence stages, which is the r13 failure mode."
            )
            ok = False

    for stage in ("collect_closure_evidence.py", "verify_closure_evidence.py"):
        hits = [l for l in lines_src if stage in l and not l.strip().startswith("#")]
        if not hits:
            lines.append(
                f"  [NOT-INVOKED] production_gate.sh never invokes {stage}. The floor verdict "
                f"cannot reach the gate if the stage is not called."
            )
            ok = False
            continue
        for l in hits:
            if "|| true" in l or "|| exit 0" in l or "|| : " in l or l.rstrip().endswith(";"):
                lines.append(
                    f"  [SWALLOWED]  the {stage} line swallows a non-zero status: {l.strip()!r}. "
                    f"The gate would keep going and exit 0."
                )
                ok = False

    if ok:
        lines.append(
            f"  [ok]        gate chain intact: `set -e` present, both evidence stages invoked, "
            f"no swallowed status."
        )
    return ok, lines


def self_test(tmpdir: str) -> int:
    """
    Fixtures for the floor logic and for the governed-set agreement.

    Every case is synthetic - a temporary gate script and a temporary manifest - so this runs in
    milliseconds and needs neither gradle nor a JUnit run. The governed-set cases matter most: the
    whole reason the governed list is PARSED instead of hardcoded is that a hardcoded list rots
    silently, and a floor check that quietly stops covering a newly gated class still reads as
    coverage. That failure has to be pinned, not just asserted once by hand.

    Each case states its oracle in terms of the RULE - a decrease is a failure, an increase is not,
    and the two sets must agree in both directions - not in terms of this implementation.
    """
    import tempfile

    NL = chr(10)

    def gate_with(classes):
        # A REALISTIC gate, not a bare --tests list: check() also asserts the chain, so a
        # fixture gate missing `set -e` or the evidence stages would fail for a reason that
        # has nothing to do with the floor rule under test. Realism here is what makes the
        # floor cases isolate the floor.
        body = "".join(f'    --tests "{c}" \\\n' for c in classes)
        return (
            "#!/usr/bin/env bash" + NL + "set -euo pipefail" + NL + NL +
            "gradlew.bat :app:testDebugUnitTest " + chr(92) + NL + body +
            "    --no-daemon" + NL + NL +
            "$PYTHON_CMD scripts/run_verified_command.py --timeout 60 -- "
            "$PYTHON_CMD scripts/collect_closure_evidence.py" + NL +
            "$PYTHON_CMD scripts/run_verified_command.py --timeout 60 -- "
            "$PYTHON_CMD scripts/verify_closure_evidence.py" + NL + NL +
            "exit 0" + NL
        )

    def write_gate(path, classes):
        with open(path, "w", encoding="utf-8", newline="") as fh:
            fh.write(gate_with(classes))

    def run(gate_classes, manifest_classes, measured):
        gpath = os.path.join(tmpdir, "gate.sh")
        write_gate(gpath, gate_classes)
        manifest = {"schema_version": 1, "established_at_commit": "fixture",
                    "classes": manifest_classes}
        return check(measured, manifest, gate_path=gpath)

    passed, failed = 0, 0

    def case(name, gate_classes, manifest_classes, measured, expect_ok, expect_marker):
        nonlocal passed, failed
        ok, lines = run(gate_classes, manifest_classes, measured)
        hit = any(expect_marker in l for l in lines)
        good = (ok is expect_ok) and hit
        print(f"    {'PASS' if good else 'FAIL'}  {name}  (ok={ok}, saw {expect_marker!r}={hit})")
        if not good:
            for l in lines:
                print(f"           {l}")
            failed += 1
        else:
            passed += 1

    A, B, C = "com.example.Aaa", "com.example.Bbb", "com.example.Ccc"

    # 1. Exact agreement, every class exactly at its floor -> PASS.
    case("exact match at the floor passes", [A, B], {A: 10, B: 5}, {A: (10, 0), B: (5, 0)}, True, "[ok]")

    # 2. A class the gate selects but the manifest omits -> FAIL. This is the rotted-list case.
    case("class gated but absent from the manifest fails", [A, B, C], {A: 10, B: 5},
         {A: (10, 0), B: (5, 0), C: (3, 0)}, False, "[UNGOVERNED]")

    # 3. The reverse: a floor for something no longer gated -> FAIL, it is unread bookkeeping.
    case("manifest entry no longer gated fails", [A], {A: 10, B: 5}, {A: (10, 0), B: (5, 0)}, False, "[ORPHAN]")

    # 4. The case the whole module exists for: one test lost from a gated class -> FAIL.
    case("a decrease fails", [A], {A: 10}, {A: (9, 0)}, False, "[DECREASED]")

    # 5. The asymmetry, stated as its own fixture: adding tests is NOT a failure.
    case("an increase is allowed", [A], {A: 10}, {A: (14, 0)}, True, "[ok +4]")

    # 6. A gated class that produced no XML at all -> FAIL, not silently skipped.
    case("a gated class with no results fails", [A, B], {A: 10, B: 5}, {A: (10, 0)}, False, "[MISSING]")

    # 7. An empty gate selection must not read as "nothing to check, therefore pass".
    case("an unreadable gate does not pass vacuously", [], {A: 10}, {A: (10, 0)}, False, "[NO-GATE]")

    # --- gate-chain fixtures: the floor verdict must be able to REACH the gate's exit code ----
    GOOD_GATE = (
        "#!/usr/bin/env bash" + NL + "set -euo pipefail" + NL + NL +
        '$PYTHON_CMD scripts/run_verified_command.py --timeout 60 -- '
        '$PYTHON_CMD scripts/collect_closure_evidence.py' + NL +
        '$PYTHON_CMD scripts/run_verified_command.py --timeout 60 -- '
        '$PYTHON_CMD scripts/verify_closure_evidence.py' + NL +
        "exit 0" + NL
    )

    def chain_case(name, gate_src, expect_ok, expect_marker):
        nonlocal passed, failed
        gp = os.path.join(tmpdir, "chain_gate.sh")
        with open(gp, "w", encoding="utf-8", newline="") as fh:
            fh.write(gate_src)
        ok2, cls = gate_chain_intact(gp)
        hit = any(expect_marker in l for l in cls)
        good = (ok2 is expect_ok) and hit
        print(f"    {'PASS' if good else 'FAIL'}  {name}  (ok={ok2}, saw {expect_marker!r}={hit})")
        if not good:
            for l in cls:
                print(f"           {l}")
            failed += 1
        else:
            passed += 1

    # --- CASE A / B / D: the skipped-test rule, at the floor layer --------------------
    # The oracle is the invariant itself, not this implementation: a governed suite satisfies its
    # floor only with tests that RAN, so `executed = tests - skipped` is what is compared, and
    # skipped must be zero outright.
    case("CASE A skipped test cannot satisfy the floor",
         [A], {A: 9}, {A: (9, 1)}, False, "[SKIPPED]")
    case("CASE A the same suite also reports a shortfall against its floor",
         [A], {A: 9}, {A: (9, 1)}, False, "[DECREASED]")
    case("CASE B all tests executed and passing passes",
         [A], {A: 9}, {A: (9, 0)}, True, "[ok]")
    case("CASE B one skip on an otherwise over-floor suite still fails",
         [A], {A: 4}, {A: (9, 1)}, False, "[SKIPPED]")
    case("CASE D failures are unaffected - a real decrease is still caught",
         [A], {A: 9}, {A: (8, 0)}, False, "[DECREASED]")
    case("CASE D a skip inside a multi-suite run cannot be absorbed elsewhere",
         [A, B], {A: 9, B: 5}, {A: (9, 1), B: (14, 0)}, False, "[SKIPPED]")
    case("CASE A executed count is tests minus skipped, never tests",
         [A], {A: 9}, {A: (9, 1)}, False, "[SKIPPED]")

    # CASE 2 - a skip in a class the gate does NOT govern must not reject closure. The rule is
    # deliberately scoped to the governed set: production_gate.sh parses its `--tests` selections,
    # and a class outside them is nobody's contractual business. `DatabaseMigration17To18Test`
    # is the live case - it skips on a clean clone by design, and a global skip rule would make
    # every clean clone permanently red for a reason unrelated to the code under test.
    case("CASE 2 a skip in a NON-governed class does not reject closure",
         [A], {A: 9}, {A: (9, 0), "com.example.NotGatedTest": (4, 2)}, True, "[ok]")

    chain_case("an intact chain passes", GOOD_GATE, True, "[ok]")
    chain_case("a chain without set -e fails", GOOD_GATE.replace("set -euo pipefail", "# none"),
               False, "[NO-SET-E]")
    chain_case("a chain that never invokes verify fails",
               GOOD_GATE.replace("scripts/verify_closure_evidence.py", "scripts/something_else.py"),
               False, "[NOT-INVOKED]")
    chain_case("a chain that turns errexit back off fails",
               GOOD_GATE.replace("set -euo pipefail", "set -euo pipefail" + chr(10) + "set +e"),
               False, "[ERREXIT-OFF]")

    chain_case("a chain that swallows the status fails",
               GOOD_GATE.replace(
                   "$PYTHON_CMD scripts/collect_closure_evidence.py",
                   "$PYTHON_CMD scripts/collect_closure_evidence.py || true"),
               False, "[SWALLOWED]")



    print(f"\n  {passed} passed, {failed} failed")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser(description="GAP-7 expected test-count manifest")
    ap.add_argument("--write", action="store_true", help="re-baseline the manifest from current XMLs")
    ap.add_argument("--self-test", action="store_true",
                    help="run the synthetic fixtures for the floor logic (no gradle, no JUnit XML)")
    args = ap.parse_args()

    # The self-test is deliberately handled BEFORE the RESULTS_DIR existence check. The fixtures are
    # synthetic - a temporary gate script and a temporary manifest - so they need neither gradle nor a
    # JUnit run, and making them depend on app/build/test-results existing couples the check for
    # "is the floor logic correct" to the check for "was the suite run". The second can fail for
    # reasons that have nothing to do with the first, and then the fixtures become unrunnable exactly
    # when someone needs them. Proven by pointing RESULTS_DIR at a non-existent path and by deleting
    # app/build: --self-test still exits 0.
    if args.self_test:
        import tempfile
        with tempfile.TemporaryDirectory() as td:
            return self_test(td)

    if not os.path.isdir(RESULTS_DIR):
        print(f"[FAIL] no JUnit results at {RESULTS_DIR}. Run the suite first.", file=sys.stderr)
        return 1

    measured = measure()
    if args.write:
        return write(measured)

    try:
        manifest = load()
    except FileNotFoundError:
        print(f"[FAIL] {MANIFEST_PATH} not found. The gate cannot prove any test is still present.",
              file=sys.stderr)
        return 1

    governed = gate_selections()
    ok, lines = check(measured, manifest)
    print(f"Test-count manifest: {len(governed)} governed classes parsed from production_gate.sh, "
          f"one established_at_commit = {manifest.get('established_at_commit')} "
          f"(single sha for the whole manifest)")
    for line in lines:
        print(line)
    if ok:
        print("RESULT: PASS - every gated class is at or above its floor.")
        return 0
    print("RESULT: FAIL - a gated class is below its floor, or the governed set disagrees with the gate.")
    return 1


if __name__ == "__main__":
    sys.exit(main())