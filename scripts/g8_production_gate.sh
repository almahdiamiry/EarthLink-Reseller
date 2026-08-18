#!/usr/bin/env bash
# ==============================================================================
# Earthlink Reseller V1 - G8 Machine Certification Production Gate (Bash)
# Enforces fail-closed wrapper-only execution, structural checks, and bundle verification.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "================================================================="
echo "=== Earthlink Reseller App -- G8 Production Certification Gate ==="
echo "================================================================="

# 1. Structural Invariant & Contract Validations
echo "[1/6] Running Structural & Contract Invariant Validators..."
python3 "${REPO_ROOT}/scripts/verify_invariant_contract.py"
python3 "${REPO_ROOT}/scripts/verify_test_environment_matrix.py"
python3 "${REPO_ROOT}/scripts/scan_forbidden_patterns.py"
python3 "${REPO_ROOT}/scripts/verify_g8_release_environment.py"

# 2. Adversarial & Meta-Gate Fixture Validations
echo "[2/6] Running Adversarial & Meta-Gate Fixtures..."
python3 "${REPO_ROOT}/scripts/test_forbidden_pattern_registry.py"
python3 "${REPO_ROOT}/scripts/test_meta_gate_fixtures.py"
python3 "${REPO_ROOT}/scripts/test_gate_adversarial_failures.py"
python3 "${REPO_ROOT}/scripts/test_verified_runner_fixtures.py"

# 3. G8 Self-Tests & Mutation Fixtures
echo "[3/6] Running G8 Certifier Self-Tests & Mutation Fixtures..."
python3 "${REPO_ROOT}/scripts/test_g8_certifier.py"
python3 "${REPO_ROOT}/scripts/test_g8_certifier_fixtures.py"

# 4. Actual Product and Certification Test Execution via Gradle Wrapper
echo "[4/6] Executing Complete Product & Certification Test Corpus via Gradle Wrapper..."
"${REPO_ROOT}/gradlew" testDebugUnitTest

# 5. Execute G8 Machine Certification Engine
echo "[5/6] Running G8 Certification Engine & Evidence Collector..."
python3 "${REPO_ROOT}/scripts/g8_certify.py"

echo "================================================================="
echo "=== G8 PRODUCTION CERTIFICATION GATE EXECUTION COMPLETE ==="
echo "================================================================="
exit 0
