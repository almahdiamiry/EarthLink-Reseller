$ErrorActionPreference = "Stop"

Write-Output "================================================================="
Write-Output "=== Earthlink Reseller App -- Production Gate Pipeline (PowerShell) ==="
Write-Output "================================================================="

# 1. Check certification test integrity
Write-Output ">>> Checking certification test integrity..."
$CertificationFiles = @(
    "app/src/test/java/com/example/FinalTestMatrixCertificationTest.kt",
    "app/src/test/java/com/example/ProductionCertificationPipelineTest.kt",
    "app/src/test/java/com/example/ProductionExecutableInvariantsTest.kt",
    "app/src/test/java/com/example/DeepCrossLayerInvariantsTest.kt",
    "app/src/test/java/com/example/GoldenSnapshotRoundTripTest.kt",
    "app/src/test/java/com/example/SyncConflictResolverTest.kt",
    "app/src/test/java/com/example/RemoteSyncCoordinatorTest.kt"
)

foreach ($f in $CertificationFiles) {
    if (-not (Test-Path $f)) {
        throw "Required certification test file missing: $f"
    }
}
Write-Output "All certification test files present and verified."

# 2. Invariant Contract & Test Environment Matrix Verification
Write-Output ">>> Validating canonical invariant contract..."
python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_invariant_contract.py
if ($LASTEXITCODE -ne 0) {
    throw "Invariant contract validation failed."
}

Write-Output ">>> Validating test environment matrix..."
python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_test_environment_matrix.py
if ($LASTEXITCODE -ne 0) {
    throw "Test environment matrix validation failed."
}

# 3. Forbidden Pattern Registry Self-Test, Adversarial Gate Tests & Structural Scan
Write-Output ">>> Executing verified runner false-pass fixtures..."
python scripts/run_verified_command.py --timeout 30 -- python scripts/test_verified_runner_fixtures.py
if ($LASTEXITCODE -ne 0) {
    throw "Verified runner false-pass fixtures failed."
}

Write-Output ">>> Executing production gate adversarial failure & wrapper fixtures..."
python scripts/run_verified_command.py --timeout 30 -- python scripts/test_gate_adversarial_failures.py
if ($LASTEXITCODE -ne 0) {
    throw "Production gate adversarial failure fixtures failed."
}

Write-Output ">>> Executing forbidden pattern registry adversarial self-tests..."
python scripts/run_verified_command.py --timeout 60 -- python scripts/test_forbidden_pattern_registry.py
if ($LASTEXITCODE -ne 0) {
    throw "Forbidden pattern registry self-tests failed."
}

Write-Output ">>> Scanning repository for forbidden architectural patterns..."
python scripts/run_verified_command.py --timeout 60 -- python scripts/scan_forbidden_patterns.py
if ($LASTEXITCODE -ne 0) {
    throw "Forbidden architectural patterns detected."
}

# 4. Execute full test suite
Write-Output ">>> Executing full test suite (:app:testDebugUnitTest)..."
python scripts/run_verified_command.py --timeout 900 --heartbeat 15 -- .\gradlew.bat :app:testDebugUnitTest --no-daemon
if ($LASTEXITCODE -ne 0) {
    throw "Test execution failed."
}

# 4. Verify JUnit test results
Write-Output ">>> Verifying JUnit test results..."
$TestResultsDir = "app/build/test-results/testDebugUnitTest"
if (Test-Path $TestResultsDir) {
    $failures = (Select-String -Path "$TestResultsDir\*.xml" -Pattern 'failures="[1-9]').Count
    $errors = (Select-String -Path "$TestResultsDir\*.xml" -Pattern 'errors="[1-9]').Count
    if ($failures -gt 0 -or $errors -gt 0) {
        throw "Test suite produced failures ($failures) or errors ($errors)."
    }
    Write-Output "Verified JUnit test result XMLs: 0 failures, 0 errors."
}

# 5. Collect machine closure evidence
Write-Output ">>> Collecting machine-derived closure evidence..."
python scripts/run_verified_command.py --timeout 60 -- python scripts/collect_closure_evidence.py
if ($LASTEXITCODE -ne 0) {
    throw "Evidence collection failed."
}

# 6. Verify machine closure evidence against contract
Write-Output ">>> Verifying closure evidence bundle against contract..."
python scripts/run_verified_command.py --timeout 60 -- python scripts/verify_closure_evidence.py
if ($LASTEXITCODE -ne 0) {
    throw "Closure evidence verification failed."
}

# 7. Render machine certification report
Write-Output ">>> Rendering machine certification report..."
python scripts/run_verified_command.py --timeout 60 -- python scripts/render_certification_report.py
if ($LASTEXITCODE -ne 0) {
    throw "Certification report rendering failed."
}

Write-Output "================================================================="
Write-Output "=== Production Gate Check: ALL GATES PASSED (Exit Code: 0) ==="
Write-Output "================================================================="
exit 0
