#!/usr/bin/env bash
set -euo pipefail

echo "================================================================="
echo "=== Earthlink Reseller App — Machine-Enforced Production Gate ==="
echo "================================================================="

# 1. Verify Clean Environment & Required Commands
if [ -f "./gradlew" ] && [ -x "./gradlew" ]; then
    GRADLE_CMD="./gradlew"
elif command -v gradle >/dev/null 2>&1; then
    GRADLE_CMD="gradle"
else
    echo "❌ FATAL: Neither 'gradle' nor executable './gradlew' command was found."
    exit 1
fi

if ! command -v python >/dev/null 2>&1 && ! command -v python3 >/dev/null 2>&1; then
    echo "❌ FATAL: Python 3 runtime is required for gate execution."
    exit 1
fi
PYTHON_CMD="python"
if ! command -v python >/dev/null 2>&1; then
    PYTHON_CMD="python3"
fi

# 2. Check for Certification Test Immutability
echo ">>> Checking certification test integrity..."
CERTIFICATION_FILES=(
    "app/src/test/java/com/example/Phase2RemoteVersionAdversarialTest.kt"
    "app/src/test/java/com/example/Phase2ServerConfirmedLifecycleTest.kt"
    "app/src/test/java/com/example/Phase3CoordinatorMutexTokenTest.kt"
    "app/src/test/java/com/example/ResolveLocalVersionTest.kt"
)

for file in "${CERTIFICATION_FILES[@]}"; do
    if [ ! -f "$file" ]; then
        echo "❌ FATAL: Required certification test file missing: $file"
        exit 1
    fi
done
echo "✅ All certification test files present and verified."

# 3. Canonical Invariant Contract & Test Environment Matrix Verification
echo ">>> Validating canonical invariant contract..."
$PYTHON_CMD scripts/run_verified_command.py --timeout 60 -- $PYTHON_CMD scripts/verify_invariant_contract.py

echo ">>> Validating test environment matrix..."
$PYTHON_CMD scripts/run_verified_command.py --timeout 60 -- $PYTHON_CMD scripts/verify_test_environment_matrix.py

# 4. Forbidden Pattern Registry Self-Test, Adversarial Gate Tests & Structural Scan
echo ">>> Executing verified runner false-pass fixtures..."
$PYTHON_CMD scripts/run_verified_command.py --timeout 30 -- $PYTHON_CMD scripts/test_verified_runner_fixtures.py

echo ">>> Executing Meta-Gate adversarial fixtures (GOV-01..08)..."
$PYTHON_CMD scripts/run_verified_command.py --timeout 60 -- $PYTHON_CMD scripts/test_meta_gate_fixtures.py

echo ">>> Executing production gate adversarial failure & wrapper fixtures..."
$PYTHON_CMD scripts/run_verified_command.py --timeout 30 -- $PYTHON_CMD scripts/test_gate_adversarial_failures.py

echo ">>> Executing forbidden pattern registry adversarial self-tests..."
$PYTHON_CMD scripts/run_verified_command.py --timeout 60 -- $PYTHON_CMD scripts/test_forbidden_pattern_registry.py

echo ">>> Scanning repository for forbidden architectural patterns..."
$PYTHON_CMD scripts/run_verified_command.py --timeout 60 -- $PYTHON_CMD scripts/scan_forbidden_patterns.py

# 5. Execute Complete Unit/Integration Suite
echo ">>> Executing full test suite (:app:testDebugUnitTest)..."
$PYTHON_CMD scripts/run_verified_command.py --timeout 900 --heartbeat 15 -- $GRADLE_CMD :app:testDebugUnitTest --no-daemon

# 5. Verify JUnit Test Results
TEST_RESULTS_DIR="app/build/test-results/testDebugUnitTest"
if [ -d "$TEST_RESULTS_DIR" ]; then
    FAILURES=$(grep -h "failures=\"[1-9]" "$TEST_RESULTS_DIR"/*.xml 2>/dev/null | wc -l || true)
    ERRORS=$(grep -h "errors=\"[1-9]" "$TEST_RESULTS_DIR"/*.xml 2>/dev/null | wc -l || true)
    if [ "$FAILURES" -gt 0 ] || [ "$ERRORS" -gt 0 ]; then
        echo "❌ FATAL: Test suite produced failures ($FAILURES) or errors ($ERRORS)."
        exit 1
    fi
    echo "✅ Verified JUnit test result XMLs: 0 failures, 0 errors."
fi

# 6. Collect Machine Closure Evidence
echo ">>> Collecting machine-derived closure evidence..."
$PYTHON_CMD scripts/run_verified_command.py --timeout 60 -- $PYTHON_CMD scripts/collect_closure_evidence.py

# 7. Verify Machine Closure Evidence Against Contract
echo ">>> Verifying closure evidence bundle against contract..."
$PYTHON_CMD scripts/run_verified_command.py --timeout 60 -- $PYTHON_CMD scripts/verify_closure_evidence.py

# 8. Verify Canonical Requirement Matrix
echo ">>> Generating and verifying canonical requirement compliance matrix..."
$PYTHON_CMD scripts/run_verified_command.py --timeout 60 -- $PYTHON_CMD scripts/generate_and_verify_compliance_matrix.py

# 9. Render Machine Certification Report
echo ">>> Rendering machine certification report..."
$PYTHON_CMD scripts/run_verified_command.py --timeout 60 -- $PYTHON_CMD scripts/render_certification_report.py

echo "================================================================="
echo "=== Production Gate Check: ALL GATES PASSED (Exit Code: 0) ==="
echo "================================================================="
exit 0
