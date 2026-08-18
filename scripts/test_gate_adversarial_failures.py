#!/usr/bin/env python3
"""
Adversarial Verification of Production Gate & Runner Failures
Verifies that:
1. Command Failure causes the gate step and overall runner to exit with non-zero code.
2. Timeout causes immediate termination and fail-closed exit code (124).
3. NO-SOURCE in output causes fail-closed exit code (2).
4. No verification command can bypass the runner wrapper.
"""

import sys
import os
import subprocess
import json

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from scripts.run_verified_command import run_verified_command

def test_gate_command_failure():
    print("\n--- [ADVERSARIAL 1] Simulating Gate Command Failure (Exit 1) ---")
    res = run_verified_command([sys.executable, "-c", "import sys; print('Gate stage failed!'); sys.exit(1)"], timeout_seconds=10)
    print(f"Result Status: {res['status']}, Exit Code: {res['exit_code']}")
    assert res["status"] == "FAIL", f"Expected FAIL, got {res['status']}"
    assert res["exit_code"] == 1, f"Expected 1, got {res['exit_code']}"
    print("✅ Gate successfully fails closed on command failure.")

def test_gate_timeout():
    print("\n--- [ADVERSARIAL 2] Simulating Gate Command Timeout (Infinite Loop / Hanging Task) ---")
    res = run_verified_command([sys.executable, "-c", "import time; print('Starting hang...'); time.sleep(30)"], timeout_seconds=2, heartbeat_interval=1)
    print(f"Result Status: {res['status']}, Exit Code: {res['exit_code']}, Timed Out: {res['timed_out']}")
    assert res["status"] == "TIMEOUT", f"Expected TIMEOUT, got {res['status']}"
    assert res["exit_code"] == 124, f"Expected 124, got {res['exit_code']}"
    assert res["timed_out"] is True, "Expected timed_out == True"
    print("✅ Gate successfully terminates hanging task and fails closed on timeout.")

def test_gate_no_source():
    print("\n--- [ADVERSARIAL 3] Simulating NO-SOURCE Test Task Execution ---")
    # Simulate gradle output that has NO-SOURCE (which would otherwise exit 0 silently)
    res = run_verified_command(
        [sys.executable, "-c", "import sys; print('> Task :app:testDebugUnitTest NO-SOURCE'); print('BUILD SUCCESSFUL'); sys.exit(0)"],
        timeout_seconds=10,
        fail_on_no_source=True
    )
    print(f"Result Status: {res['status']}, Exit Code: {res['exit_code']}, NO-SOURCE Detected: {res['no_source_detected']}")
    assert res["status"] == "FAIL", f"Expected FAIL, got {res['status']}"
    assert res["exit_code"] != 0, f"Expected non-zero exit code, got {res['exit_code']}"
    assert res["no_source_detected"] is True, "Expected no_source_detected == True"
    print("✅ Gate successfully fails closed when test task outputs NO-SOURCE.")

def test_gate_script_wrapper_coverage():
    print("\n--- [ADVERSARIAL 4] Verifying all verification commands in production_gate.sh are wrapped ---")
    gate_path = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "scripts", "production_gate.sh")
    with open(gate_path, "r", encoding="utf-8") as f:
        lines = f.readlines()
    
    unwrapped_commands = []
    for idx, line in enumerate(lines, 1):
        clean = line.strip()
        if clean.startswith("$PYTHON_CMD scripts/") and "run_verified_command.py" not in clean:
            unwrapped_commands.append((idx, clean))
        elif clean.startswith("$GRADLE_CMD") and "run_verified_command.py" not in clean:
            unwrapped_commands.append((idx, clean))

    print(f"Unwrapped verification commands found: {len(unwrapped_commands)}")
    assert len(unwrapped_commands) == 0, f"Found unwrapped commands in production_gate.sh: {unwrapped_commands}"
    print("✅ 100% of verification commands in production_gate.sh pass through run_verified_command.py.")

def test_meta_gate_fixtures_execution():
    print("\n--- [ADVERSARIAL 5] Executing Meta-Gate GOV-01..08 Fixtures ---")
    meta_script = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "scripts", "test_meta_gate_fixtures.py")
    res = run_verified_command([sys.executable, meta_script], timeout_seconds=60)
    assert res["status"] == "PASS", f"Expected Meta-Gate fixtures to PASS, got {res['status']}"
    assert res["exit_code"] == 0, f"Expected exit code 0, got {res['exit_code']}"
    print("✅ Meta-Gate adversarial fixtures (GOV-01..08) executed and passed.")

def main():
    print("=====================================================================")
    print("=== EXECUTING PRODUCTION GATE ADVERSARIAL FAILURE & WRAPPER SUITE ===")
    print("=====================================================================")
    test_gate_command_failure()
    test_gate_timeout()
    test_gate_no_source()
    test_gate_script_wrapper_coverage()
    test_meta_gate_fixtures_execution()
    print("\n=====================================================================")
    print("=== ALL PRODUCTION GATE ADVERSARIAL TESTS PASSED (100% FAIL-CLOSED) ===")
    print("=====================================================================")

if __name__ == "__main__":
    main()
