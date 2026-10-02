"""
GAP-7 - expected test-count manifest.

The release gate asserts that nothing FAILED. Until this module it asserted nothing about whether
anything was still THERE, so a deleted test could not turn the gate red. The live example was r13:
deleting Scenario J took Phase1FirestoreDocumentIdentityTest from 20 tests to 19 and the gate
passed, because the deletion was correct - the gate simply had no way to tell.

THE ASYMMETRY IS THE POINT.

  DECREASE -> failure. A lost test is lost protection, and nothing else in the gate can observe it.
  INCREASE -> allowed. Adding a test is how this suite normally grows. Pinning to an exact number
               would make every legitimate addition a gate failure, which trains the next person to
               edit this manifest without reading why. That is how a count check becomes a rubber
               stamp, and it would be strictly worse than having no check at all.

So the manifest records a FLOOR per governed class, not an expectation. Each entry carries the
commit that established the floor, so a change is reviewable rather than mechanical.

Scope is deliberately narrow: only the classes `scripts/production_gate.sh` names in its
`--tests` selection are governed. Governing all 113 test classes would make the manifest a
changelog of the suite and nobody would read it.

Usage
-----
    python scripts/test_count_manifest.py            # verify against the current JUnit XMLs
    python scripts/test_count_manifest.py --write    # re-baseline (only with a stated reason)

Exit code 0 when every governed class is at or above its floor, 1 otherwise.
"""

from __future__ import annotations

import argparse
import glob
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

import yaml

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MANIFEST_PATH = os.path.join(REPO_ROOT, "scripts", "test_count_manifest.yaml")
RESULTS_DIR = os.path.join(REPO_ROOT, "app", "build", "test-results", "testDebugUnitTest")

# The classes production_gate.sh:82-97 passes to --tests. Kept here as a literal so a change to the
# gate's selection and a change to the governed set are reviewed together, in one place.
GOVERNED_CLASSES = [
    "com.example.DataIntegrityReleaseGateTest",
    "com.example.ResolveLocalVersionTest",
    "com.example.Phase2ServerConfirmedLifecycleTest",
    "com.example.Phase2RemoteVersionAdversarialTest",
    "com.example.Phase1FirestoreDocumentIdentityTest",
    "com.example.Phase1TwoDeviceConvergenceTest",
    "com.example.Phase3PersistedGenerationTest",
    "com.example.Phase1G1PendingOperationDurabilityTest",
    "com.example.Phase1AtomicityAndLostAckTest",
    "com.example.Phase1DuplicateInitiationProtectionTest",
    "com.example.Phase2RestoreReplaceHardeningTest",
    "com.example.Phase2UtowerImportHardeningTest",
    "com.example.Phase1OutboxDurabilityTest",
    "com.example.Phase1ItemIsolationTest",
    "com.example.Phase1OrphanHandlingTest",
    "com.example.Phase3CoordinatorMutexTokenTest",
]


def measure(results_dir: str = RESULTS_DIR) -> dict:
    """Return {classname: tests} from every JUnit XML in `results_dir`."""
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
            counts[name] = counts.get(name, 0) + int(suite.attrib.get("tests", 0))
    return counts


def head_commit() -> str:
    try:
        return subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=REPO_ROOT, capture_output=True, text=True, check=True,
        ).stdout.strip()
    except Exception:
        return "unknown"


def load() -> dict:
    with open(MANIFEST_PATH, "r", encoding="utf-8") as fh:
        return yaml.safe_load(fh)


def check(measured: dict, manifest: dict) -> tuple[bool, list]:
    """Compare measured against the floor. Returns (ok, lines)."""
    floors = manifest.get("classes", {}) or {}
    lines, ok = [], True

    for cls in GOVERNED_CLASSES:
        floor = floors.get(cls)
        actual = measured.get(cls)
        if floor is None:
            lines.append(f"  [UNGOVERNED] {cls}: not in the manifest but is gated by production_gate.sh")
            ok = False
            continue
        if actual is None:
            lines.append(f"  [MISSING]   {cls}: expected >= {floor} tests, but no JUnit XML was produced")
            ok = False
            continue
        if actual < floor:
            lines.append(
                f"  [DECREASED] {cls}: {actual} tests, floor is {floor} "
                f"({floor - actual} lost). Losing a test loses protection and nothing else in the "
                f"gate can see it. If the removal was intended, re-baseline with --write in the "
                f"same commit as the removal and say so in the message."
            )
            ok = False
        elif actual > floor:
            lines.append(f"  [ok +{actual - floor}]  {cls}: {actual} tests, floor is {floor} (increase allowed)")
        else:
            lines.append(f"  [ok]        {cls}: {actual} tests, floor is {floor}")

    # A governed class that has vanished from the gate's selection would otherwise go unnoticed.
    for cls in floors:
        if cls not in GOVERNED_CLASSES:
            lines.append(f"  [ORPHAN]    {cls}: in the manifest but no longer gated by production_gate.sh")
            ok = False

    return ok, lines


def write(measured: dict) -> int:
    sha = head_commit()
    body = [
        "# GAP-7 - expected test-count manifest",
        "#",
        "# The release gate used to assert only that nothing FAILED. Nothing asserted that anything",
        "# was still THERE, so deleting a test could not turn the gate red. The live example was r13",
        "# itself: deleting Scenario J took Phase1FirestoreDocumentIdentityTest from 20 tests to 19",
        "# and the gate passed.",
        "#",
        "# SEMANTICS, and the reason they are asymmetric:",
        "#   * A DECREASE is a failure. A lost test is lost protection.",
        "#   * An INCREASE is allowed. Adding a test is the normal way this suite grows, and pinning",
        "#     to an exact number would make every legitimate addition a gate failure - which trains",
        "#     the next person to edit this file without reading why, and that is how a count check",
        "#     becomes a rubber stamp.",
        "#",
        "# Each entry is a FLOOR, established by the commit recorded in `established_at_commit`.",
        "# To record an intended deletion, edit that number AND the sha in the same commit as the",
        "# removal, and say so in the commit message.",
        "#",
        "# Regenerate with:  python scripts/test_count_manifest.py --write",
        "# Verify with:      python scripts/test_count_manifest.py",
        "",
        "schema_version: 1",
        f"established_at_commit: {sha}",
        "",
        "classes:",
    ]
    for cls in sorted(GOVERNED_CLASSES):
        if cls in measured:
            body.append(f"  {cls}: {measured[cls]}")
    with open(MANIFEST_PATH, "w", encoding="utf-8") as fh:
        fh.write("\n".join(body) + "\n")
    print(f"[written] {MANIFEST_PATH} at commit {sha}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="GAP-7 expected test-count manifest")
    ap.add_argument("--write", action="store_true", help="re-baseline the manifest from current XMLs")
    args = ap.parse_args()

    if not os.path.isdir(RESULTS_DIR):
        print(f"[FAIL] no JUnit results at {RESULTS_DIR}. Run the suite first.", file=sys.stderr)
        return 1

    measured = measure()
    if args.write:
        return write(measured)

    manifest = load()
    ok, lines = check(measured, manifest)
    print(f"Test-count manifest: {len(GOVERNED_CLASSES)} governed classes, "
          f"floor established at {manifest.get('established_at_commit')}")
    for line in lines:
        print(line)
    if ok:
        print("RESULT: PASS - no governed class is below its floor.")
        return 0
    print("RESULT: FAIL - at least one governed class lost tests.")
    return 1


if __name__ == "__main__":
    sys.exit(main())