#!/usr/bin/env python3
"""
scripts/test_forbidden_pattern_registry.py

Adversarial self-test suite for the Forbidden Pattern Registry and Scanner.
Verifies that:
1. The registry self-validator correctly rejects malformed registry definitions:
   - Duplicate pattern IDs
   - Invalid or missing invariant IDs (outside INV-01..INV-16)
   - Malformed / uncompilable regular expressions
   - Missing required fields (check_type, file_glob, required_symbols, etc.)
   - Unknown check types
2. The scanner reliably detects every seeded violation type in adversarial fixture files:
   - RC-1 regex pattern (remote timestamp fallback)
   - RC-3 semantic combo (device clock winner selection)
   - RC-4 regex pattern (coordinator bypass)
   - RC-6 regex pattern (release dry-run)
   - INV-03 regex pattern (direct Firestore in UI)
   - INV-16 regex pattern (hardcoded closure status)
   - Disallowed file glob detection
3. Clean fixture codebases pass with 0 violations and exit code 0.
4. Non-matching/partial symbols in semantic combo do not trigger false positives.
"""

import os
import shutil
import sys
import tempfile
import unittest
import yaml

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.join(REPO_ROOT, "scripts"))

from scan_forbidden_patterns import validate_registry, scan_patterns, DEFAULT_REGISTRY_PATH


class ForbiddenPatternRegistrySelfTest(unittest.TestCase):
    """Unit and Adversarial tests for forbidden pattern registry and scanner."""

    def test_canonical_registry_is_valid(self):
        """Canonical forbidden_patterns.yaml must pass self-validation with zero errors."""
        self.assertTrue(os.path.exists(DEFAULT_REGISTRY_PATH), f"Registry missing: {DEFAULT_REGISTRY_PATH}")
        with open(DEFAULT_REGISTRY_PATH, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)
        errors = validate_registry(data)
        self.assertEqual(errors, [], f"Canonical registry validation errors: {errors}")

    def test_validator_rejects_duplicate_ids(self):
        """Registry validator must reject duplicate pattern IDs."""
        bad_registry = {
            "patterns": [
                {
                    "id": "DUP-ID",
                    "invariant": "INV-01",
                    "description": "First definition",
                    "check_type": "regex",
                    "file_glob": "foo/*.kt",
                    "forbidden_regexes": ["forbidden_1"]
                },
                {
                    "id": "DUP-ID",
                    "invariant": "INV-02",
                    "description": "Duplicate definition",
                    "check_type": "regex",
                    "file_glob": "bar/*.kt",
                    "forbidden_regexes": ["forbidden_2"]
                }
            ]
        }
        errors = validate_registry(bad_registry)
        self.assertTrue(any("Duplicate pattern ID" in e for e in errors), f"Expected duplicate ID error, got: {errors}")

    def test_validator_rejects_invalid_invariant_id(self):
        """Registry validator must reject invalid or out-of-range invariant IDs."""
        bad_registry = {
            "patterns": [
                {
                    "id": "TEST-BAD-INV",
                    "invariant": "INV-99",
                    "description": "Invalid invariant reference",
                    "check_type": "regex",
                    "file_glob": "foo/*.kt",
                    "forbidden_regexes": ["foo"]
                }
            ]
        }
        errors = validate_registry(bad_registry)
        self.assertTrue(any("must be one of" in e for e in errors), f"Expected invalid invariant error, got: {errors}")

    def test_validator_rejects_malformed_regex(self):
        """Registry validator must reject uncompilable regular expressions."""
        bad_registry = {
            "patterns": [
                {
                    "id": "TEST-BAD-REGEX",
                    "invariant": "INV-06",
                    "description": "Malformed regex test",
                    "check_type": "regex",
                    "file_glob": "foo/*.kt",
                    "forbidden_regexes": ["[unclosed-bracket("]
                }
            ]
        }
        errors = validate_registry(bad_registry)
        self.assertTrue(any("Invalid regular expression" in e for e in errors), f"Expected regex compile error, got: {errors}")

    def test_validator_rejects_unknown_check_type(self):
        """Registry validator must reject unrecognized check types."""
        bad_registry = {
            "patterns": [
                {
                    "id": "TEST-BAD-CHECK-TYPE",
                    "invariant": "INV-06",
                    "description": "Unknown check type",
                    "check_type": "magical_check",
                    "file_glob": "foo/*.kt"
                }
            ]
        }
        errors = validate_registry(bad_registry)
        self.assertTrue(any("check_type" in e for e in errors), f"Expected check_type error, got: {errors}")

    def test_validator_rejects_empty_semantic_combo_symbols(self):
        """Registry validator must reject semantic_combo with empty required_symbols."""
        bad_registry = {
            "patterns": [
                {
                    "id": "TEST-BAD-COMBO",
                    "invariant": "INV-06",
                    "description": "Combo missing symbols",
                    "check_type": "semantic_combo",
                    "file_glob": "foo/*.kt",
                    "required_symbols": []
                }
            ]
        }
        errors = validate_registry(bad_registry)
        self.assertTrue(any("required_symbols" in e for e in errors), f"Expected symbols error, got: {errors}")


class AdversarialFixtureScannerTest(unittest.TestCase):
    """Adversarial seeded violation tests using isolated temporary workspaces."""

    def setUp(self):
        self.test_dir = tempfile.mkdtemp(prefix="earthlink_adv_test_")
        self.registry_file = os.path.join(self.test_dir, "forbidden_patterns.yaml")
        shutil.copy(DEFAULT_REGISTRY_PATH, self.registry_file)

    def tearDown(self):
        if os.path.exists(self.test_dir):
            shutil.rmtree(self.test_dir, ignore_errors=True)

    def _write_fixture(self, rel_path: str, content: str):
        full_path = os.path.join(self.test_dir, rel_path)
        os.makedirs(os.path.dirname(full_path), exist_ok=True)
        with open(full_path, "w", encoding="utf-8") as f:
            f.write(content)
        return full_path

    def test_adversarial_rc1_remote_version_fallback_detection(self):
        """Scanner must detect seeded RC-1 local timestamp fallback."""
        fixture_file = "app/src/main/java/com/example/core/sync/RemoteSyncCoordinator.kt"
        seeded_content = """
        package com.example.core.sync
        class RemoteSyncCoordinator {
            fun resolve(event: SyncEvent, existing: AccountEntity?): Long {
                // Seeded forbidden pattern:
                val localTimestamp = metadataDao.get("key")?.toLongOrNull() ?: existing?.updatedAt ?: 0L
                return localTimestamp
            }
        }
        """
        self._write_fixture(fixture_file, seeded_content)

        result = scan_patterns(root_dir=self.test_dir, registry_path=self.registry_file)
        self.assertEqual(result["status"], "FAIL")
        pat_res = result["pattern_results"].get("RC-1-remote-version-fallback", {})
        self.assertEqual(pat_res.get("status"), "FAIL")
        self.assertTrue(any("RC-1-remote-version-fallback" in v for v in result["violations"]))

    def test_adversarial_rc3_semantic_combo_detection_and_no_false_positive(self):
        """Scanner must detect RC-3 semantic combo and avoid false positives on partial symbols."""
        fixture_file = "app/src/main/java/com/example/core/sync/SyncRepositoryImpl.kt"

        # Case 1: Partial symbols only (no forbidden comparison) -> Should NOT trigger violation
        safe_content = """
        package com.example.core.sync
        class SyncRepositoryImpl {
            fun sync() {
                val localMutatedAt = prefManager.getSettingsLocalMutatedAt()
                val remoteUpdatedAt = 100L
                // Safe server timestamp check
                if (remoteUpdatedAt > 0L) {
                    println("safe")
                }
            }
        }
        """
        self._write_fixture(fixture_file, safe_content)
        result = scan_patterns(root_dir=self.test_dir, registry_path=self.registry_file)
        pat_res = result["pattern_results"].get("RC-3-settings-device-clock", {})
        self.assertEqual(pat_res.get("status"), "PASS", "Safe code must not trigger RC-3")

        # Case 2: Seeded forbidden combo (all symbols + device clock winner comparison) -> MUST trigger
        seeded_content = """
        package com.example.core.sync
        class SyncRepositoryImpl {
            fun sync() {
                val localMutatedAt = prefManager.getSettingsLocalMutatedAt()
                val remoteUpdatedAt = 100L
                val now = System.currentTimeMillis()
                // Seeded forbidden comparison:
                if (localMutatedAt > remoteUpdatedAt) {
                    println("Device clock won over server timestamp!")
                }
            }
        }
        """
        self._write_fixture(fixture_file, seeded_content)
        result = scan_patterns(root_dir=self.test_dir, registry_path=self.registry_file)
        pat_res = result["pattern_results"].get("RC-3-settings-device-clock", {})
        self.assertEqual(pat_res.get("status"), "FAIL", "Forbidden combo must trigger RC-3")

    def test_adversarial_rc4_coordinator_bypass_detection(self):
        """Scanner must detect seeded RC-4 coordinator bypass pattern."""
        fixture_file = "app/src/main/java/com/example/core/backup/BackupManager.kt"
        seeded_content = """
        package com.example.core.backup
        class BackupManager {
            fun backup() {
                if (DataOperationCoordinator.currentMode == DataOperationMode.BACKUP) {
                    // Bypass lock
                }
            }
        }
        """
        self._write_fixture(fixture_file, seeded_content)
        result = scan_patterns(root_dir=self.test_dir, registry_path=self.registry_file)
        pat_res = result["pattern_results"].get("RC-4-coordinator-bypass", {})
        self.assertEqual(pat_res.get("status"), "FAIL")

    def test_adversarial_rc6_release_dry_run_detection(self):
        """Scanner must detect seeded --dry-run bypass in release scripts."""
        fixture_file = "scripts/production_gate.sh"
        seeded_content = """
        #!/usr/bin/env bash
        ./gradlew assembleRelease --dry-run
        """
        self._write_fixture(fixture_file, seeded_content)
        result = scan_patterns(root_dir=self.test_dir, registry_path=self.registry_file)
        pat_res = result["pattern_results"].get("RC-6-release-dry-run", {})
        self.assertEqual(pat_res.get("status"), "FAIL")

    def test_adversarial_inv03_direct_firestore_ui_detection(self):
        """Scanner must detect direct Firestore calls in UI layer."""
        fixture_file = "app/src/main/java/com/example/ui/screens/AccountScreen.kt"
        seeded_content = """
        package com.example.ui.screens
        class AccountScreen {
            fun fetch() {
                val db = FirebaseFirestore.getInstance()
            }
        }
        """
        self._write_fixture(fixture_file, seeded_content)
        result = scan_patterns(root_dir=self.test_dir, registry_path=self.registry_file)
        pat_res = result["pattern_results"].get("INV-03-direct-firestore-ui", {})
        self.assertEqual(pat_res.get("status"), "FAIL")

    def test_adversarial_inv16_hardcoded_closure_status_detection(self):
        """Scanner must detect hardcoded CLOSED status in report scripts."""
        fixture_file = "scripts/render_certification_report.py"
        seeded_content = """
        report_status = "CLOSED"
        """
        self._write_fixture(fixture_file, seeded_content)
        result = scan_patterns(root_dir=self.test_dir, registry_path=self.registry_file)
        pat_res = result["pattern_results"].get("INV-16-hardcoded-closure-status", {})
        self.assertEqual(pat_res.get("status"), "FAIL")

    def test_adversarial_rc1_v2_inline_version_resolution_detection_and_exemption(self):
        """Scanner must detect inline local timestamp resolution outside resolveLocalVersion and allow it inside."""
        fixture_file = "app/src/main/java/com/example/core/sync/RemoteSyncCoordinator.kt"

        # Case A: Inside resolveLocalVersion -> PASS (Allowed)
        clean_content = """
        package com.example.core.sync
        class RemoteSyncCoordinator {
            suspend fun resolveLocalVersion(entityType: String, entityId: String): LocalVersionState {
                val existing = accountDao.getByIdOneShot(entityId)
                val legacyTs = existing?.updatedAt
                val takeIfTs = existing?.takeIf { it.isLegacy }?.updatedAt
                val storedMetaTs = metadataDao.get("key") ?: "0"
                return LocalVersionState.Untracked(legacyTs)
            }
        }
        """
        self._write_fixture(fixture_file, clean_content)
        result = scan_patterns(root_dir=self.test_dir, registry_path=self.registry_file)
        pat_res = result["pattern_results"].get("RC-1-v2-inline-version-resolution", {})
        self.assertEqual(pat_res.get("status"), "PASS", f"Inside resolveLocalVersion must pass: {pat_res.get('violations')}")

        # Case B: Direct existing?.updatedAt outside resolveLocalVersion -> FAIL
        seeded_b = """
        package com.example.core.sync
        class RemoteSyncCoordinator {
            fun applyAccountUpsert(event: SyncEvent, existing: AccountEntity?) {
                val fallback = existing?.updatedAt
            }
        }
        """
        self._write_fixture(fixture_file, seeded_b)
        result = scan_patterns(root_dir=self.test_dir, registry_path=self.registry_file)
        pat_res = result["pattern_results"].get("RC-1-v2-inline-version-resolution", {})
        self.assertEqual(pat_res.get("status"), "FAIL", "Direct existing?.updatedAt outside resolveLocalVersion must fail")

        # Case C: takeIf-based updatedAt outside resolveLocalVersion -> FAIL
        seeded_c = """
        package com.example.core.sync
        class RemoteSyncCoordinator {
            fun applyLedgerUpsert(event: SyncEvent, existing: LedgerEntity?) {
                val fallback = existing?.takeIf { it.isLegacy }?.occurredAt
            }
        }
        """
        self._write_fixture(fixture_file, seeded_c)
        result = scan_patterns(root_dir=self.test_dir, registry_path=self.registry_file)
        pat_res = result["pattern_results"].get("RC-1-v2-inline-version-resolution", {})
        self.assertEqual(pat_res.get("status"), "FAIL", "takeIf-based updatedAt outside resolveLocalVersion must fail")

        # Case D: duplicate-based timestamp fallback outside resolveLocalVersion -> FAIL
        seeded_d = """
        package com.example.core.sync
        class RemoteSyncCoordinator {
            fun applyBatchUpsert(event: SyncEvent, duplicate: BatchEntity?) {
                val fallback = duplicate?.createdAt
            }
        }
        """
        self._write_fixture(fixture_file, seeded_d)
        result = scan_patterns(root_dir=self.test_dir, registry_path=self.registry_file)
        pat_res = result["pattern_results"].get("RC-1-v2-inline-version-resolution", {})
        self.assertEqual(pat_res.get("status"), "FAIL", "duplicate?.createdAt outside resolveLocalVersion must fail")

        # Case E: storedMetaTs fallback outside resolveLocalVersion -> FAIL
        seeded_e = """
        package com.example.core.sync
        class RemoteSyncCoordinator {
            fun applyAccountUpsert(event: SyncEvent) {
                val v = storedMetaTs ?: 0L
            }
        }
        """
        self._write_fixture(fixture_file, seeded_e)
        result = scan_patterns(root_dir=self.test_dir, registry_path=self.registry_file)
        pat_res = result["pattern_results"].get("RC-1-v2-inline-version-resolution", {})
        self.assertEqual(pat_res.get("status"), "FAIL", "storedMetaTs ?: outside resolveLocalVersion must fail")

    def test_adversarial_rc1_v3_push_without_version_record_detection(self):
        """Scanner must detect fabricated local timestamp version recording during push markSucceeded."""
        fixture_file = "app/src/main/java/com/example/core/sync/SyncRepositoryImpl.kt"
        seeded_content = """
        package com.example.core.sync
        class SyncRepositoryImpl {
            fun push() {
                val s = Source.SERVER
                OutboxManager.markSucceeded(outboxDao, ids)
                metadataDao.put("remote_version:acc:1", System.currentTimeMillis().toString())
            }
        }
        """
        self._write_fixture(fixture_file, seeded_content)
        result = scan_patterns(root_dir=self.test_dir, registry_path=self.registry_file)
        pat_res = result["pattern_results"].get("RC-1-v3-push-without-version-record", {})
        self.assertEqual(pat_res.get("status"), "FAIL", "Fabricated timestamp in markSucceeded must fail RC-1-v3")

    def test_adversarial_rc5_direct_settings_sync_caller_detection_and_exemption(self):
        """Scanner must flag direct calls to syncUserSettings outside triggerSettingsSync."""
        # Case A: Inside triggerSettingsSync or syncUserSettings definition -> PASS
        valid_repo = "app/src/main/java/com/example/core/sync/SyncRepositoryImpl.kt"
        clean_repo = """
        package com.example.core.sync
        class SyncRepositoryImpl {
            fun triggerSettingsSync(uid: String?, reason: String) {
                syncScope.launch {
                    syncUserSettings(uid)
                }
            }
            internal suspend fun syncUserSettings(uid: String?): Boolean {
                return true
            }
        }
        """
        self._write_fixture(valid_repo, clean_repo)
        result = scan_patterns(root_dir=self.test_dir, registry_path=self.registry_file)
        pat_res = result["pattern_results"].get("RC-5-direct-settings-sync-caller", {})
        self.assertEqual(pat_res.get("status"), "PASS", f"Valid triggerSettingsSync caller must pass: {pat_res.get('violations')}")

        # Case B: Calling syncUserSettings from ViewModel or outside triggerSettingsSync -> FAIL
        invalid_vm = "app/src/main/java/com/example/ui/viewmodels/AuthViewModel.kt"
        seeded_vm = """
        package com.example.ui.viewmodels
        class AuthViewModel {
            fun login() {
                syncRepo.syncUserSettings()
            }
        }
        """
        self._write_fixture(invalid_vm, seeded_vm)
        result = scan_patterns(root_dir=self.test_dir, registry_path=self.registry_file)
        pat_res = result["pattern_results"].get("RC-5-direct-settings-sync-caller", {})
        self.assertEqual(pat_res.get("status"), "FAIL", "Direct syncUserSettings call from ViewModel must fail RC-5")

    def test_clean_workspace_passes_with_zero_violations(self):
        """When all seeded violations are cleaned, scanner must return clean PASS."""
        # Create compliant clean files
        self._write_fixture("app/src/main/java/com/example/core/sync/RemoteSyncCoordinator.kt", "class RemoteSyncCoordinator")
        self._write_fixture("app/src/main/java/com/example/core/sync/SyncRepositoryImpl.kt", "class SyncRepositoryImpl")
        self._write_fixture("app/src/main/java/com/example/core/backup/BackupManager.kt", "class BackupManager")
        self._write_fixture("scripts/production_gate.sh", "echo 'production gate'")
        self._write_fixture("app/src/main/java/com/example/ui/screens/HomeScreen.kt", "class HomeScreen")
        self._write_fixture("scripts/render_certification_report.py", "report_status = computed_from_evidence")
        self._write_fixture(
            "app/src/test/java/com/example/Phase2RemoteVersionAdversarialTest.kt",
            "class Phase2RemoteVersionAdversarialTest {\n"
            "    fun caseD_versionAheadOfLocalState_cannotPersistVersionWithoutApplyingState() {}\n"
            "    fun caseF_replayAfterCaptureFailure_neverReplaysSuccessfulPush() {}\n"
            "}"
        )

        result = scan_patterns(root_dir=self.test_dir, registry_path=self.registry_file)
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(result["violations_count"], 0)
        self.assertEqual(result["violations"], [])


def run_self_tests() -> bool:
    suite = unittest.TestLoader().loadTestsFromTestCase(ForbiddenPatternRegistrySelfTest)
    suite.addTests(unittest.TestLoader().loadTestsFromTestCase(AdversarialFixtureScannerTest))
    runner = unittest.TextTestRunner(verbosity=2)
    res = runner.run(suite)
    return res.wasSuccessful()


if __name__ == "__main__":
    print("=================================================================")
    print("=== Forbidden Pattern Registry Adversarial Self-Test Suite ===")
    print("=================================================================")
    success = run_self_tests()
    print("=================================================================")
    if success:
        print("=== ALL REGISTRY ADVERSARIAL SELF-TESTS PASSED (Exit: 0) ===")
    else:
        print("=== REGISTRY SELF-TESTS FAILED (Exit: 1) ===")
    print("=================================================================")
    sys.exit(0 if success else 1)
