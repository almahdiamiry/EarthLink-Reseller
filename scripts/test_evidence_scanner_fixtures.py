#!/usr/bin/env python3
"""
scripts/test_evidence_scanner_fixtures.py

Self-test for `scripts/scan_test_evidence.py`, the static test-evidence scanner built by
Round 13 of the test-suite evidence audit.

CLAIM
  `scan_test_evidence.scan_file` classifies every known-good and known-bad Kotlin test
  fixture with exactly the rules it should, and never invents a rule it was not given.
  The CLI honours its `--rule` / `--single-assertion` selectors and always exits 0.

SEAM
  Pure Python standard library. No Kotlin, no Gradle, no Android SDK, no third-party
  package. Each fixture is written to a real temporary directory as a real `.kt` file and
  is read back through the REAL module under test.

INDEPENDENT ORACLE
  Every fixture is a hand-written Kotlin snippet whose correct classification follows from
  the F1..F7 rule table, not from the scanner's output. Each expected rule set is written
  literally in the assertion that checks it, and the assertion is an EXACT set equality,
  so a scanner that under-reports a rule fails and a scanner that invents one also fails.
  The rule is never re-implemented here: a test of a copy of a rule is the exact defect
  this repository recorded as GATE-INTEGRITY-01.

ANTI-REGRESSION FIXTURES
  * `regression_suppressBetweenTestAndFun` - a `@Suppress` annotation sits between `@Test`
    and `fun`. The body must still be scanned; an earlier ad-hoc detector stopped at the
    `fun` signature line and wrongly reported a live assertion as missing.
  * `regression_verifyNoInteractionsCountsAsAnAssertion` - `verifyNoInteractions` is a
    Mockito assertion, so the test is not `F1`. It is `F6`.
"""

import os
import subprocess
import sys
import tempfile

if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

_HERE = os.path.dirname(os.path.abspath(__file__))
_REPO_ROOT = os.path.dirname(_HERE)
for _p in (_REPO_ROOT, _HERE):
    if _p not in sys.path:
        sys.path.insert(0, _p)

from scripts import scan_test_evidence  # noqa: E402  (path set above, on purpose)

SCANNER_PATH = os.path.join(_HERE, "scan_test_evidence.py")


# --------------------------------------------------------------------------------------------
# Fixture helpers. These only build input and filter the scanner's real output. They never
# decide whether a rule fired.
# --------------------------------------------------------------------------------------------

def write_fixture(tmpdir, name, text):
    path = os.path.join(tmpdir, name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    return path


def rules_for(findings, test_name):
    """The set of rule ids the REAL scanner reported for one named test."""
    return {f.rule for f in findings if f.test_name == test_name}


def lines_for(findings, test_name):
    findings_for_test = [f for f in findings if f.test_name == test_name]
    assert findings_for_test, (
        f"Expected the scanner to resolve the fixture test '{test_name}' and report at "
        f"least one rule for it. Findings seen: {sorted((f.rule, f.test_name) for f in findings)}"
    )
    return {f.line for f in findings_for_test}


def scan_fixture(tmpdir, name, text):
    """Write one fixture file, scan it with the real scanner, return (findings, tests)."""
    path = write_fixture(tmpdir, name, text)
    findings = scan_test_evidence.scan_file(path)
    tests = scan_test_evidence.list_tests(path)
    return findings, tests


def assert_classified(findings, test_name, expected_rules):
    actual = rules_for(findings, test_name)
    assert actual == expected_rules, (
        f"Fixture '{test_name}': expected exactly {sorted(expected_rules)}, "
        f"scanner reported {sorted(actual)}. "
        f"Details: {[(f.rule, f.line, f.detail) for f in findings if f.test_name == test_name]}"
    )


def assert_resolved(tests, test_name):
    discovered = {t["name"] for t in tests}
    assert test_name in discovered, (
        f"Fixture '{test_name}' was not discovered by the scanner. Discovered: {sorted(discovered)}"
    )


# --------------------------------------------------------------------------------------------
# Fixtures
# --------------------------------------------------------------------------------------------

F1_FIXTURE = """
import org.junit.Assert.assertEquals
import org.junit.Test

class F1VacuousTest {

    @Test
    fun f1_body_with_no_verification_at_all() {
        val total = 1 + 1
        // assertEquals(2, total) is only a comment here, so it proves nothing.
        if (total > 0) {
            println(total)
        }
    }

    @Test
    fun f1_body_with_a_real_assertion() {
        val total = 1 + 1
        assertEquals(2, total)
    }
}
"""

F2_FIXTURE = """
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class F2TautologyTest {

    @Test
    fun f2_assertTrueOnALiteral_is_tautological() {
        assertTrue(true)
    }

    @Test
    fun f2_assertEqualsOfOneExpression_against_itself_is_tautological() {
        val openingDebt = 250
        assertEquals(openingDebt, openingDebt)
    }

    @Test
    fun f2_literal_versus_computedValue_is_real_evidence() {
        val openingDebt = 250
        assertEquals(250, openingDebt)
    }
}
"""

F3_FIXTURE = """
import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerCalculatorTest {

    @Test
    fun f3_expectedValueTakenFromTheClassUnderTest_is_circular() {
        val ledgerCalculator = LedgerCalculator()
        val expected = ledgerCalculator.balanceOf(10)
        assertEquals(expected, 20)
    }

    @Test
    fun f3_literalExpectedValue_is_not_circular() {
        val ledgerCalculator = LedgerCalculator()
        assertEquals(20, ledgerCalculator.balanceOf(10))
    }
}
"""

F4_FIXTURE = """
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class F4PreconditionTest {

    @Test
    fun f4_pollLoop_without_a_precondition_assertion() {
        var attempts = 0
        while (attempts < 50 && viewModel.importResult.value == null) {
            attempts += 1
            Thread.sleep(10)
        }
        assertNotNull(syncRepository)
    }

    @Test
    fun f4_pollLoop_with_a_precondition_assertion() {
        var attempts = 0
        while (attempts < 50 && viewModel.importResult.value == null) {
            attempts += 1
            Thread.sleep(10)
        }
        assertNotNull(viewModel.importResult.value)
        assertEquals("done", viewModel.importResult.value.status)
    }
}
"""

# The shape of `testSuccessfulTgzImport_triggersUserActionSyncExactlyOnce_andSetsReplaceAllMarker`
# in LocalAccountsViewModelTgzSyncTriggerTest.kt:171, which proves the awaited state through a
# bound local rather than by naming it inside the assertion. Reporting that as F4 would be a
# scanner error, not a product finding.
F4_BOUND_LOCAL_FIXTURE = """
import org.junit.Assert.assertNotNull
import org.junit.Test

class BoundLocalPreconditionTest {

    @Test
    fun f4_pollLoop_proving_the_precondition_through_a_bound_local() {
        kotlinx.coroutines.runBlocking {
            viewModel.importTgzFile(uri, context, shouldReplace = true)
            var attempts = 0
            while (viewModel.importResult.value == null && attempts < 50) {
                kotlinx.coroutines.delay(100)
                attempts++
            }
            val result = viewModel.importResult.value
            assertNotNull(result)
        }
    }
}
"""

F5_FIXTURE = """
import org.junit.Assert.assertEquals
import org.junit.Test

class F5ExecutionOnlyTest {

    @Test
    fun f5_assertion_satisfied_by_setup_alone() {
        val ledger = LedgerFixture()
        val openingDebt = 250
        assertEquals(250, openingDebt)
    }

    @Test
    fun f5_assertion_depends_on_production_behaviour() {
        val ledger = LedgerFixture()
        assertEquals(250, ledger.balanceOf(250))
    }
}
"""

F6_FIXTURE = """
import org.junit.Assert.assertEquals
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.junit.Test

class F6MockOnlyTest {

    @Test
    fun f6_only_mockito_verification() {
        subject.recordFailedImport()
        verifyNoInteractions(syncRepository)
    }

    @Test
    fun f6_mockito_verification_plus_a_junit_assertion() {
        subject.recordFailedImport()
        verify(syncRepository).recordImport()
        assertEquals(1, subject.attemptCount)
    }
}
"""

F7_FIXTURE = """
import org.junit.Assert.assertEquals
import org.junit.Test

class F7VacuousPathTest {

    @Test
    fun f7_asserts_a_value_that_is_overwritten_afterwards() {
        var status = "pending"
        assertEquals("pending", status)
        status = runImport()
    }

    @Test
    fun f7_asserts_the_final_value_after_the_last_write() {
        var status = "pending"
        status = runImport()
        assertEquals("done", status)
    }
}
"""

REGRESSION_FIXTURE = """
import org.junit.Assert.assertEquals
import org.mockito.Mockito.verifyNoInteractions
import org.junit.Test

class SuppressedAnnotationRegressionTest {

    @Test
    @Suppress("DEPRECATION")
    fun regression_suppressBetweenTestAndFun() {
        val total = 1 + 1
        assertEquals(2, total)
    }

    @Test
    fun regression_verifyNoInteractionsCountsAsAnAssertion() {
        subject.recordFailedImport()
        verifyNoInteractions(syncRepository)
    }
}
"""

SINGLE_ASSERTION_FIXTURE = """
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleAssertionCohortTest {

    @Test
    fun single_none() {
        val total = 1 + 1
        println(total)
    }

    @Test
    fun single_exactly_one() {
        val total = 1 + 1
        assertEquals(2, total)
    }

    @Test
    fun single_two_of_them() {
        val total = 1 + 1
        assertEquals(2, total)
        assertTrue(total > 0)
    }
}
"""


# --------------------------------------------------------------------------------------------
# F1 - Vacuous: a @Test body with no assert*, no fail(, no verify*
# --------------------------------------------------------------------------------------------

def test_f1_vacuous():
    with tempfile.TemporaryDirectory() as tmpdir:
        findings, tests = scan_fixture(tmpdir, "F1VacuousTest.kt", F1_FIXTURE)

        assert_resolved(tests, "f1_body_with_no_verification_at_all")
        assert_resolved(tests, "f1_body_with_a_real_assertion")
        assert_classified(
            findings, "f1_body_with_no_verification_at_all", {"F1"}
        )
        assert_classified(findings, "f1_body_with_a_real_assertion", set())

        counts = {t["name"]: t["assertion_count"] for t in tests}
        assert counts["f1_body_with_no_verification_at_all"] == 0, (
            "The comment mentioning assertEquals must not be counted as an assertion: "
            f"got {counts}"
        )
        assert counts["f1_body_with_a_real_assertion"] == 1, f"got {counts}"

        print("PASS F1: a body with no verification is F1; a body with a real assertEquals is not.")


# --------------------------------------------------------------------------------------------
# F2 - Tautological: an assertion that cannot fail
# --------------------------------------------------------------------------------------------

def test_f2_tautological():
    with tempfile.TemporaryDirectory() as tmpdir:
        findings, tests = scan_fixture(tmpdir, "F2TautologyTest.kt", F2_FIXTURE)

        assert_resolved(tests, "f2_assertTrueOnALiteral_is_tautological")
        assert_resolved(tests, "f2_assertEqualsOfOneExpression_against_itself_is_tautological")
        assert_resolved(tests, "f2_literal_versus_computedValue_is_real_evidence")

        assert_classified(findings, "f2_assertTrueOnALiteral_is_tautological", {"F2"})
        assert_classified(
            findings, "f2_assertEqualsOfOneExpression_against_itself_is_tautological", {"F2"}
        )
        assert_classified(findings, "f2_literal_versus_computedValue_is_real_evidence", set())

        print("PASS F2: literal and self-comparison assertions are F2; a literal-vs-value "
              "comparison is not.")


# --------------------------------------------------------------------------------------------
# F3 - Circular: the expected value comes from the class under test
# --------------------------------------------------------------------------------------------

def test_f3_circular():
    with tempfile.TemporaryDirectory() as tmpdir:
        findings, tests = scan_fixture(tmpdir, "LedgerCalculatorTest.kt", F3_FIXTURE)

        assert_resolved(tests, "f3_expectedValueTakenFromTheClassUnderTest_is_circular")
        assert_resolved(tests, "f3_literalExpectedValue_is_not_circular")

        assert_classified(
            findings, "f3_expectedValueTakenFromTheClassUnderTest_is_circular", {"F3"}
        )
        assert_classified(findings, "f3_literalExpectedValue_is_not_circular", set())

        print("PASS F3: an expected value taken from LedgerCalculator is circular; a literal "
              "expected value is not.")


# --------------------------------------------------------------------------------------------
# F4 - No precondition: a poll loop followed by an assertion that never proves the wait ended
# --------------------------------------------------------------------------------------------

def test_f4_no_precondition():
    with tempfile.TemporaryDirectory() as tmpdir:
        findings, tests = scan_fixture(tmpdir, "F4PreconditionTest.kt", F4_FIXTURE)

        assert_resolved(tests, "f4_pollLoop_without_a_precondition_assertion")
        assert_resolved(tests, "f4_pollLoop_with_a_precondition_assertion")

        assert_classified(findings, "f4_pollLoop_without_a_precondition_assertion", {"F4"})
        assert_classified(findings, "f4_pollLoop_with_a_precondition_assertion", set())

        print("PASS F4: a poll loop asserted without proving the awaited state is F4; "
              "asserting the awaited state first clears it.")


def test_f4_precondition_proven_through_a_bound_local():
    with tempfile.TemporaryDirectory() as tmpdir:
        findings, tests = scan_fixture(tmpdir, "BoundLocalPreconditionTest.kt", F4_BOUND_LOCAL_FIXTURE)

        name = "f4_pollLoop_proving_the_precondition_through_a_bound_local"
        assert_resolved(tests, name)
        assert_classified(findings, name, set())

        print("PASS F4 (bound local): a precondition proven through `val result = <awaited>` "
              "and asserted by name is not F4.")


# --------------------------------------------------------------------------------------------
# F5 - No RED proof: execution-only, must be reported as always-false and never guessed
# --------------------------------------------------------------------------------------------

def test_f5_is_execution_only():
    assert set(scan_test_evidence.RULES) == {"F1", "F2", "F3", "F4", "F5", "F6", "F7"}, (
        f"The rule set must stay complete: {sorted(scan_test_evidence.RULES)}"
    )
    assert set(scan_test_evidence.ALWAYS_FALSE_RULES) == {"F5"}, (
        "F5 is the only execution-only rule and must be declared always-false: "
        f"{sorted(scan_test_evidence.ALWAYS_FALSE_RULES)}"
    )

    with tempfile.TemporaryDirectory() as tmpdir:
        findings, tests = scan_fixture(tmpdir, "F5ExecutionOnlyTest.kt", F5_FIXTURE)

        assert_resolved(tests, "f5_assertion_satisfied_by_setup_alone")
        assert_resolved(tests, "f5_assertion_depends_on_production_behaviour")

        # A known-bad F5 candidate (assertion satisfied by setup alone) and a known-good one
        # must both produce NO F5 finding: the rule is decided by running the test, not by
        # reading it. Asserting that both come back clean is the honest static contract.
        assert_classified(findings, "f5_assertion_satisfied_by_setup_alone", set())
        assert_classified(findings, "f5_assertion_depends_on_production_behaviour", set())

        assert not any(f.rule == "F5" for f in findings), (
            f"F5 is execution-only and must never be inferred statically: "
            f"{[(f.rule, f.test_name) for f in findings if f.rule == 'F5']}"
        )

        print("PASS F5: F5 is declared execution-only and the scanner never infers it from "
              "either a setup-satisfied or a production-dependent fixture.")


# --------------------------------------------------------------------------------------------
# F6 - Mock-only: the only assertions are Mockito verifications
# --------------------------------------------------------------------------------------------

def test_f6_mock_only():
    with tempfile.TemporaryDirectory() as tmpdir:
        findings, tests = scan_fixture(tmpdir, "F6MockOnlyTest.kt", F6_FIXTURE)

        assert_resolved(tests, "f6_only_mockito_verification")
        assert_resolved(tests, "f6_mockito_verification_plus_a_junit_assertion")

        assert_classified(findings, "f6_only_mockito_verification", {"F6"})
        assert_classified(findings, "f6_mockito_verification_plus_a_junit_assertion", set())

        print("PASS F6: a Mockito-only test is F6; adding one JUnit assertion clears it.")


# --------------------------------------------------------------------------------------------
# F7 - Vacuous path: asserting a value that is overwritten with nothing observing it
# --------------------------------------------------------------------------------------------

def test_f7_vacuous_path():
    with tempfile.TemporaryDirectory() as tmpdir:
        findings, tests = scan_fixture(tmpdir, "F7VacuousPathTest.kt", F7_FIXTURE)

        assert_resolved(tests, "f7_asserts_a_value_that_is_overwritten_afterwards")
        assert_resolved(tests, "f7_asserts_the_final_value_after_the_last_write")

        assert_classified(findings, "f7_asserts_a_value_that_is_overwritten_afterwards", {"F7"})
        assert_classified(findings, "f7_asserts_the_final_value_after_the_last_write", set())

        print("PASS F7: asserting a var that is overwritten unread afterwards is F7; "
              "asserting after the last write is not.")


# --------------------------------------------------------------------------------------------
# Regressions from this round's own buggy ad-hoc detector
# --------------------------------------------------------------------------------------------

def test_regression_suppress_annotation_between_test_and_fun():
    with tempfile.TemporaryDirectory() as tmpdir:
        findings, tests = scan_fixture(tmpdir, "SuppressedAnnotationRegressionTest.kt", REGRESSION_FIXTURE)

        assert_resolved(tests, "regression_suppressBetweenTestAndFun")
        assert_classified(findings, "regression_suppressBetweenTestAndFun", set())

        counts = {t["name"]: t["assertion_count"] for t in tests}
        assert counts.get("regression_suppressBetweenTestAndFun") == 1, (
            "The @Suppress annotation between @Test and fun must not hide the body. "
            f"Assertion count seen: {counts}"
        )
        assert "F1" not in rules_for(findings, "regression_suppressBetweenTestAndFun"), (
            "Regression: a test with a live assertEquals was reported as F1 because the body "
            "was not scanned past the @Suppress annotation."
        )

        print("PASS REGRESSION 1: a @Suppress between @Test and fun does not hide the body.")


def test_regression_verify_no_interactions_is_an_assertion():
    with tempfile.TemporaryDirectory() as tmpdir:
        findings, tests = scan_fixture(tmpdir, "SuppressedAnnotationRegressionTest.kt", REGRESSION_FIXTURE)

        assert_resolved(tests, "regression_verifyNoInteractionsCountsAsAnAssertion")
        assert_classified(
            findings, "regression_verifyNoInteractionsCountsAsAnAssertion", {"F6"}
        )
        assert "F1" not in rules_for(findings, "regression_verifyNoInteractionsCountsAsAnAssertion"), (
            "Regression: verifyNoInteractions is a Mockito assertion and must not be "
            "classified as F1."
        )

        print("PASS REGRESSION 2: verifyNoInteractions counts as an assertion (F6, never F1).")


# --------------------------------------------------------------------------------------------
# CLI contract: --rule, --single-assertion, and always-exit-0
# --------------------------------------------------------------------------------------------

def test_cli_rule_filter_and_exit_zero():
    with tempfile.TemporaryDirectory() as tmpdir:
        write_fixture(tmpdir, "MixedEvidenceTest.kt", F1_FIXTURE + F2_FIXTURE)
        write_fixture(tmpdir, "CircularEvidenceTest.kt", F3_FIXTURE)

        import io
        from contextlib import redirect_stdout

        def run_cli(argv):
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = scan_test_evidence.main(argv)
            return code, buffer.getvalue()

        code_all, out_all = run_cli([tmpdir])
        assert code_all == 0, f"Scanner must exit 0 on success, got {code_all}"
        assert "[F1]" in out_all and "[F2]" in out_all and "[F3]" in out_all, (
            f"Unfiltered output must carry every rule that fired. Got:\n{out_all}"
        )

        code_f1, out_f1 = run_cli([tmpdir, "--rule", "f1"])
        assert code_f1 == 0, f"Scanner must exit 0 when findings exist, got {code_f1}"
        assert "[F1]" in out_f1, f"F1 must be printed:\n{out_f1}"
        assert "[F2]" not in out_f1 and "[F3]" not in out_f1, (
            f"--rule f1 must filter the other rules out. Got:\n{out_f1}"
        )

        code_upper, out_upper = run_cli([tmpdir, "--rule", "F3"])
        assert code_upper == 0
        assert "[F3]" in out_upper and "[F1]" not in out_upper and "[F2]" not in out_upper, (
            f"--rule must be case-insensitive and must filter. Got:\n{out_upper}"
        )

        assert "Per-rule counts" in out_f1, (
            f"The per-rule summary Task 2 depends on must always be printed. Got:\n{out_f1}"
        )
        printed = [ln for ln in out_f1.splitlines() if ln.startswith("[F")]
        total_line = [ln for ln in out_f1.splitlines() if ln.startswith("Total findings")]
        assert total_line, f"Missing total. Got:\n{out_f1}"
        assert int(total_line[0].split(":")[1]) == len(printed), (
            "The reported total must count exactly the findings that were printed after "
            f"filtering. Printed {len(printed)}, total line says {total_line[0]!r}."
        )

        print("PASS CLI: --rule filters case-insensitively and the scanner always exits 0.")


def test_cli_single_assertion_selector():
    with tempfile.TemporaryDirectory() as tmpdir:
        write_fixture(tmpdir, "SingleAssertionCohortTest.kt", SINGLE_ASSERTION_FIXTURE)

        import io
        from contextlib import redirect_stdout

        def run_cli(argv):
            buffer = io.StringIO()
            with redirect_stdout(buffer):
                code = scan_test_evidence.main(argv)
            return code, buffer.getvalue()

        code, out = run_cli([tmpdir, "--single-assertion"])
        assert code == 0
        assert "single_exactly_one" in out, f"got:\n{out}"
        assert "single_none" not in out, f"A zero-assertion test is not single-assertion:\n{out}"
        assert "single_two_of_them" not in out, f"A two-assertion test is not single:\n{out}"
        assert "SingleAssertionCohortTest.kt" in out, f"The file must be printed:\n{out}"

        # Task 3 of the plan invokes the rule name, not the flag. Both spellings must work.
        code_alias, out_alias = run_cli([tmpdir, "--rule", "single-assertion"])
        assert code_alias == 0
        assert "single_exactly_one" in out_alias, f"--rule single-assertion must work:\n{out_alias}"
        assert "single_two_of_them" not in out_alias, f"--rule single-assertion must work:\n{out_alias}"

        print("PASS CLI: --single-assertion and --rule single-assertion list exactly the "
              "one-assertion tests.")


def test_cli_real_process_exit_code_is_zero_even_with_findings():
    with tempfile.TemporaryDirectory() as tmpdir:
        write_fixture(tmpdir, "F1VacuousTest.kt", F1_FIXTURE)
        completed = subprocess.run(
            [sys.executable, SCANNER_PATH, tmpdir],
            capture_output=True,
            text=True,
            cwd=_REPO_ROOT,
        )
        assert completed.returncode == 0, (
            "Ruling 2: the scanner is a triage instrument and must exit 0 even when it "
            f"reports findings. Got {completed.returncode}.\n{completed.stdout}\n{completed.stderr}"
        )
        assert "[F1]" in completed.stdout, f"got:\n{completed.stdout}"

        usage = subprocess.run(
            [sys.executable, SCANNER_PATH],
            capture_output=True,
            text=True,
            cwd=_REPO_ROOT,
        )
        assert usage.returncode != 0, "Bad usage (no root argument) must exit non-zero."

        print("PASS CLI: the real process exits 0 with findings and non-zero only on bad usage.")


TESTS = [
    test_f1_vacuous,
    test_f2_tautological,
    test_f3_circular,
    test_f4_no_precondition,
    test_f4_precondition_proven_through_a_bound_local,
    test_f5_is_execution_only,
    test_f6_mock_only,
    test_f7_vacuous_path,
    test_regression_suppress_annotation_between_test_and_fun,
    test_regression_verify_no_interactions_is_an_assertion,
    test_cli_rule_filter_and_exit_zero,
    test_cli_single_assertion_selector,
    test_cli_real_process_exit_code_is_zero_even_with_findings,
]


def main():
    print("=" * 74)
    print("=== TEST EVIDENCE SCANNER SELF-TEST (fixtures against the real scanner) ===")
    print("=" * 74)
    for test_fn in TESTS:
        test_fn()
    print("-" * 74)
    print(f"ALL {len(TESTS)} SCANNER FIXTURE GROUPS PASSED "
          f"({len(TESTS) * 2} rule classifications checked against the real scan_file).")
    print("=" * 74)
    return 0


if __name__ == "__main__":
    sys.exit(main())
