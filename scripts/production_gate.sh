#!/usr/bin/env bash
set -euo pipefail

echo "================================================================="
echo "=== Earthlink Reseller App — Machine-Enforced Production Gate ==="
echo "================================================================="

# 1. Verify Clean Environment & Required Commands
if command -v gradle >/dev/null 2>&1; then
    GRADLE_CMD="gradle"
elif [ -f "./gradlew" ] && [ -x "./gradlew" ]; then
    GRADLE_CMD="./gradlew"
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

# 4b. Data Integrity Release Gate (Silent Corruption Prevention)
echo ">>> Executing Data Integrity Release Gate (DataIntegrityReleaseGateTest)..."
echo "    This gate prevents the class of failures where financial numbers come out wrong"
echo "    with no crash or error — covering H-1 (bad push), H-2 (stale pull), H-3 (field stripping)."
DATA_INTEGRITY_GATE_FILE="app/src/test/java/com/example/DataIntegrityReleaseGateTest.kt"
if [ ! -f "$DATA_INTEGRITY_GATE_FILE" ]; then
    echo "❌ FATAL: Data Integrity Release Gate test file missing: $DATA_INTEGRITY_GATE_FILE"
    exit 1
fi
$PYTHON_CMD scripts/run_verified_command.py --timeout 300 --heartbeat 15 -- $GRADLE_CMD :app:testDebugUnitTest --tests "com.example.DataIntegrityReleaseGateTest" --no-daemon
echo "✅ Data Integrity Release Gate: ALL TESTS PASSED."

# 5. Execute Authoritative Primary Invariant Test Suites
echo ">>> Executing Authoritative Primary Invariant Test Suites..."
$PYTHON_CMD scripts/run_verified_command.py --timeout 900 --heartbeat 15 -- $GRADLE_CMD :app:testDebugUnitTest \
    --tests "com.example.DataIntegrityReleaseGateTest" \
    --tests "com.example.ResolveLocalVersionTest" \
    --tests "com.example.Phase2ServerConfirmedLifecycleTest" \
    --tests "com.example.Phase2RemoteVersionAdversarialTest" \
    --tests "com.example.Phase1FirestoreDocumentIdentityTest" \
    --tests "com.example.Phase1TwoDeviceConvergenceTest" \
    --tests "com.example.Phase3PersistedGenerationTest" \
    --tests "com.example.Phase1G1PendingOperationDurabilityTest" \
    --tests "com.example.Phase1AtomicityAndLostAckTest" \
    --tests "com.example.Phase1DuplicateInitiationProtectionTest" \
    --tests "com.example.Phase2RestoreReplaceHardeningTest" \
    --tests "com.example.Phase2UtowerImportHardeningTest" \
    --tests "com.example.Phase1OutboxDurabilityTest" \
    --tests "com.example.Phase1ItemIsolationTest" \
    --tests "com.example.Phase1OrphanHandlingTest" \
    --tests "com.example.Phase3CoordinatorMutexTokenTest" \
    --no-daemon

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
