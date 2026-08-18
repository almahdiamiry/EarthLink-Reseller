# ==============================================================================
# Earthlink Reseller V1 - G8 Machine Certification Production Gate (PowerShell)
# Enforces fail-closed wrapper-only execution, structural checks, and bundle verification.
# ==============================================================================

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "=== Earthlink Reseller App -- G8 Production Certification Gate ===" -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

# 1. Structural Invariant & Contract Validations
Write-Host "[1/6] Running Structural & Contract Invariant Validators..." -ForegroundColor Yellow
python "$RepoRoot\scripts\verify_invariant_contract.py"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

python "$RepoRoot\scripts\verify_test_environment_matrix.py"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

python "$RepoRoot\scripts\scan_forbidden_patterns.py"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

python "$RepoRoot\scripts\verify_g8_release_environment.py"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# 2. Adversarial & Meta-Gate Fixture Validations
Write-Host "[2/6] Running Adversarial & Meta-Gate Fixtures..." -ForegroundColor Yellow
python "$RepoRoot\scripts\test_forbidden_pattern_registry.py"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

python "$RepoRoot\scripts\test_meta_gate_fixtures.py"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

python "$RepoRoot\scripts\test_gate_adversarial_failures.py"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

python "$RepoRoot\scripts\test_verified_runner_fixtures.py"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# 3. G8 Self-Tests & Mutation Fixtures
Write-Host "[3/6] Running G8 Certifier Self-Tests & Mutation Fixtures..." -ForegroundColor Yellow
python "$RepoRoot\scripts\test_g8_certifier.py"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

python "$RepoRoot\scripts\test_g8_certifier_fixtures.py"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# 4. Actual Product and Certification Test Execution via Gradle Wrapper
Write-Host "[4/6] Executing Complete Product & Certification Test Corpus via Gradle Wrapper..." -ForegroundColor Yellow
& "$RepoRoot\gradlew.bat" testDebugUnitTest
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# 5. Execute G8 Machine Certification Engine
Write-Host "[5/6] Running G8 Certification Engine & Evidence Collector..." -ForegroundColor Yellow
python "$RepoRoot\scripts\g8_certify.py"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "=================================================================" -ForegroundColor Green
Write-Host "=== G8 PRODUCTION CERTIFICATION GATE EXECUTION COMPLETE ===" -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Green
exit 0
