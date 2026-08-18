#!/usr/bin/env python3
"""
False-Pass Runner Fixtures
Permanent test suite verifying that scripts/run_verified_command.py fails closed under:
1. Command non-zero exit code failure (Failing command fails runner)
2. Command timeout exceeded (Timed-out command fails runner & terminates tree)
3. Missing command / non-existent executable (Blocked command)
4. NO-SOURCE detection in test output (Fail-closed on NO-SOURCE)
5. Child process failure propagation
"""

import sys
import os
import tempfile
import json

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from scripts.run_verified_command import run_verified_command

def test_failing_command():
    print("Testing runner on failing command (exit 1)...")
    res = run_verified_command([sys.executable, "-c", "import sys; sys.exit(1)"], timeout_seconds=10)
    assert res["status"] == "FAIL", f"Expected FAIL, got {res['status']}"
    assert res["exit_code"] == 1, f"Expected exit_code 1, got {res['exit_code']}"
    print("✅ test_failing_command PASS")

def test_timeout_command():
    print("Testing runner on timeout command...")
    res = run_verified_command([sys.executable, "-c", "import time; time.sleep(10)"], timeout_seconds=2, heartbeat_interval=1)
    assert res["status"] == "TIMEOUT", f"Expected TIMEOUT, got {res['status']}"
    assert res["exit_code"] == 124, f"Expected exit_code 124, got {res['exit_code']}"
    assert res["timed_out"] is True, "Expected timed_out == True"
    print("✅ test_timeout_command PASS")

def test_blocked_missing_command():
    print("Testing runner on missing executable...")
    res = run_verified_command(["non_existent_binary_12345"], timeout_seconds=5)
    assert res["status"] == "BLOCKED", f"Expected BLOCKED, got {res['status']}"
    assert res["exit_code"] != 0, "Expected non-zero exit code"
    print("✅ test_blocked_missing_command PASS")

def test_no_source_fail_closed():
    print("Testing runner on NO-SOURCE detection...")
    res = run_verified_command([sys.executable, "-c", "print('> Task :app:testDebugUnitTest NO-SOURCE')"], timeout_seconds=5, fail_on_no_source=True)
    assert res["status"] == "FAIL", f"Expected FAIL on NO-SOURCE, got {res['status']}"
    assert res["exit_code"] == 2, f"Expected exit code 2 on NO-SOURCE, got {res['exit_code']}"
    assert res["no_source_detected"] is True, "Expected no_source_detected == True"
    print("✅ test_no_source_fail_closed PASS")

def test_metadata_json_output():
    print("Testing runner metadata JSON artifact writing...")
    with tempfile.TemporaryDirectory() as tmpdir:
        meta_path = os.path.join(tmpdir, "out", "result.json")
        res = run_verified_command([sys.executable, "-c", "print('hello metadata')"], timeout_seconds=5, output_metadata_path=meta_path)
        assert os.path.exists(meta_path), "Metadata JSON file was not created"
        with open(meta_path, 'r', encoding='utf-8') as f:
            data = json.load(f)
        assert data["status"] == "PASS", f"Expected metadata status PASS, got {data['status']}"
        assert data["exit_code"] == 0, f"Expected exit code 0, got {data['exit_code']}"
        assert "hello metadata" in data["stdout"], "Expected stdout in metadata JSON"
    print("✅ test_metadata_json_output PASS")

def main():
    print("=== Executing Verified Runner False-Pass Fixtures ===")
    test_failing_command()
    test_timeout_command()
    test_blocked_missing_command()
    test_no_source_fail_closed()
    test_metadata_json_output()
    print("=====================================================")
    print("ALL VERIFIED RUNNER FALSE-PASS FIXTURES PASSED")

if __name__ == "__main__":
    main()
