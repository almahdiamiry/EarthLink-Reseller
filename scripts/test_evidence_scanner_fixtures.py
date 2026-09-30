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

import ast
import inspect
import os
import subprocess
import sys
import tempfile
import textwrap

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


# The reviewer's C1 fixture: the helper is declared AFTER the next @Test, so a block bounded
# at the next @Test cannot see its assertion and reports the delegating test as F1.
C1_DELEGATION_FIXTURE = """
import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerTest {

    @Test
    fun c1_delegates_its_only_assertion_to_a_helper() {
        checkDebtIs(250)
    }

    @Test
    fun c1_neighbouring_test() {
        assertEquals(1, 1)
    }

    private fun checkDebtIs(amount: Int) {
        assertEquals(250, amount)
    }
}
"""

# I1c: the helper-throws-AssertionError idiom. The test verifies; it just does not do it inline.
I1C_HELPER_THROWS_FIXTURE = """
import org.junit.Test

class HelperThrowsTest {

    @Test
    fun i1c_delegates_to_a_helper_that_throws_assertion_error() {
        expectDebtIs(250)
    }

    private fun expectDebtIs(amount: Int) {
        if (amount != 250) {
            throw AssertionError("expected 250 but was " + amount)
        }
    }
}
"""

# I1b: the only `assertEquals` in the file is inside a `"""` raw string. A raw string is a
# literal, not an assertion, so the test is genuinely F1 and must never be called tautological.
I1B_RAW_STRING_FIXTURE = '''
import org.junit.Assert.assertNotNull
import org.junit.Test

class RawStringAssertionTest {

    @Test
    fun i1b_only_a_raw_string_mentions_an_assertion() {
        val expectedSql = """
            SELECT assertEquals(1, 1)
        """
        println(expectedSql)
    }

    @Test
    fun i1b_raw_string_mention_does_not_inflate_the_assertion_count() {
        val documentation = """
            call assertEquals(250, amount) to check the debt
        """
        assertNotNull(documentation)
    }
}
'''

# I1a: a test double whose name is derived from the class under test is NOT the class under
# test, so a value read from it is not circular.
I1A_STUB_FIXTURE = """
import org.junit.Assert.assertEquals
import org.junit.Test

class BalanceSheetTest {

    @Test
    fun i1a_stub_named_like_the_sut_is_not_the_class_under_test() {
        val balanceSheet = BalanceSheetStub()
        val expected = balanceSheet.total()
        assertEquals(expected, 500)
    }
}
"""

# I2: the SUT gate. A value bound to a member call on some other collaborator is not circular,
# and removing the gate makes this fixture fail.
I2_NON_SUT_FIXTURE = """
import org.junit.Assert.assertEquals
import org.junit.Test

class LedgerCalculatorTest {

    @Test
    fun i2_value_from_a_non_sut_collaborator_is_not_circular() {
        val factory = LedgerFixtureFactory()
        val expected = factory.expectedBalance()
        assertEquals(expected, 20)
    }
}
"""

# I3: the four F4 guards, each currently unexercised.
I3_F4_GUARDS_FIXTURE = """
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class F4GuardTest {

    @Test
    fun i3a_while_loop_without_a_counter_conjunct_is_not_a_poll() {
        var attempts = 0
        while (queue.hasNext()) {
            attempts += 1
            queue.remove()
        }
        assertEquals(0, queue.size())
    }

    @Test
    fun i3b_first_assertion_precedes_the_loop() {
        var attempts = 0
        assertNotNull(viewModel)
        while (attempts < 50 && viewModel.state.value == null) {
            attempts += 1
            Thread.sleep(10)
        }
    }

    @Test
    fun i3c_counter_only_loop_awaits_nothing() {
        var attempts = 0
        while (attempts < 50) {
            attempts += 1
            retryLater()
        }
        assertNotNull(syncRepository)
    }

    @Test
    fun i3d_message_overload_is_deliberately_not_compared() {
        val openingDebt = 250
        assertEquals("debt must match", openingDebt, openingDebt)
    }
}
"""

# The expression-body path. 577 of the 798 real tests are written `fun x() = runBlocking { ... }`,
# so this is the highest-volume branch in the scanner. It previously had no fixture at all, and
# the bug it shipped (78 spurious F1) was invisible to the suite.
EXPRESSION_BODY_FIXTURE = '''
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ExpressionBodyTest {

    @Test
    fun eb_plain_expression_body_has_an_inline_assertion() = runBlocking {
        val total = 1 + 1
        assertEquals(2, total)
    }

    @Test
    fun eb_wrapped_declaration_puts_the_brace_on_the_next_line() =
        runBlocking {
            val marker = computeMarker()
            assertNotNull(marker)
        }

    @Test
    fun eb_sibling_is_still_separate() = runBlocking {
        assertEquals(4, computeMarker())
    }

    // A `val` line inside the `runBlocking {` block is part of the body, not the start of a
    // new declaration. Terminating the expression at the first `val` truncates this test.
    @Test
    fun eb_local_val_inside_the_block_does_not_end_the_body() = runBlocking {
        val first = computeMarker()
        val second = computeMarker()
        val third = computeMarker()
        assertEquals(first, second)
        assertEquals(second, third)
    }
}
'''

# The tail shape. `= runBlocking { ... }.also { assertEquals(1, it) }` continues past the first
# `{` block, so stopping there truncates the body and invents an F1.
EXPRESSION_BODY_TAIL_FIXTURE = '''
import org.junit.Assert.assertEquals
import org.junit.Test

class ExpressionBodyTailTest {

    @Test
    fun tail_on_one_line_is_not_truncated() =
        runBlocking { val computed = computeValue() }.also { assertEquals(1, it) }

    @Test
    fun tail_wrapped_onto_its_own_line_is_not_truncated() = runBlocking { val computed = computeValue() }
        .also { assertEquals(1, it) }

    @Test
    fun tail_is_the_last_test_in_the_file() = runBlocking { val computed = computeValue() }
        .also { assertEquals(1, it) }
}
'''

# The expression-body upper bound must not run past the test into a following declaration.
# `_DECLARATION_STARTS` omitted `suspend` and `inline` modifiers, so `private suspend fun` was
# not recognised as a new declaration and the helper's assertions were attributed to the test,
# inventing an F2 and an F7 on a test that is neither tautological nor vacuous.
EXPRESSION_BODY_UNLISTED_DECL_FIXTURE = '''
import org.junit.Assert.assertEquals
import org.junit.Test

class UnlistedDeclarationTest {

    @Test
    fun test_before_suspend_helper_is_not_tautological() = runBlocking {
        val total = 1 + 1
        assertEquals(2, total)
    }

    private suspend fun suspendHelperContainsTautology() {
        assertEquals(2, 2)
    }

    @Test
    fun test_before_suspend_helper_reassigning_is_not_vacuous_path() = runBlocking {
        var status = "pending"
        assertEquals("pending", status)
    }

    private suspend fun suspendHelperReassignsAfterAsserting() {
        var other = "x"
        assertEquals("x", other)
        other = compute()
    }

    @Test
    fun test_before_inline_helper_is_not_affected() = runBlocking {
        val left = compute()
        assertEquals(3, left)
    }

    private inline fun inlineHelperContainsTautology() {
        assertEquals(3, 3)
    }
}
'''

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
# C1 - a test that delegates its only assertion to a helper is not vacuous
# --------------------------------------------------------------------------------------------

def test_c1_delegated_assertion_is_not_vacuous():
    with tempfile.TemporaryDirectory() as tmpdir:
        findings, tests = scan_fixture(tmpdir, "LedgerTest.kt", C1_DELEGATION_FIXTURE)

        assert_resolved(tests, "c1_delegates_its_only_assertion_to_a_helper")
        assert_resolved(tests, "c1_neighbouring_test")

        assert_classified(
            findings, "c1_delegates_its_only_assertion_to_a_helper", set()
        )
        assert_classified(findings, "c1_neighbouring_test", {"F2"})

        # The block must also STOP at the test's own closing brace. If it ran on to the next
        # @Test it would swallow the helper's assertion as well, so the neighbour would report
        # 2 assertions instead of 1 and Task 3's single-assertion cohort would be wrong.
        counts = {t["name"]: t["assertion_count"] for t in tests}
        assert counts["c1_neighbouring_test"] == 1, (
            "A test's block must end at its own closing brace, not at the next @Test. "
            f"assertion_count seen: {counts}"
        )
        assert counts["c1_delegates_its_only_assertion_to_a_helper"] == 0, (
            "The delegating test has no inline assertion; that is why the helper walk, not "
            f"the count, is what clears it. assertion_count seen: {counts}"
        )

        print("PASS C1: a test whose only assertion lives in a helper it calls is not F1.")


def test_i1c_helper_throwing_assertion_error_is_not_vacuous():
    with tempfile.TemporaryDirectory() as tmpdir:
        findings, tests = scan_fixture(
            tmpdir, "HelperThrowsTest.kt", I1C_HELPER_THROWS_FIXTURE
        )

        name = "i1c_delegates_to_a_helper_that_throws_assertion_error"
        assert_resolved(tests, name)
        assert_classified(findings, name, set())

        print("PASS I1c: a test delegating to a helper that throws AssertionError is not F1.")


# --------------------------------------------------------------------------------------------
# I1b - a raw string is a literal, not an assertion
# --------------------------------------------------------------------------------------------

def test_i1b_assertion_inside_a_raw_string_is_not_an_assertion():
    with tempfile.TemporaryDirectory() as tmpdir:
        findings, tests = scan_fixture(
            tmpdir, "RawStringAssertionTest.kt", I1B_RAW_STRING_FIXTURE
        )

        only_raw = "i1b_only_a_raw_string_mentions_an_assertion"
        assert_resolved(tests, only_raw)
        assert_classified(findings, only_raw, {"F1"})

        counts = {t["name"]: t["assertion_count"] for t in tests}
        assert counts[only_raw] == 0, (
            "An assertEquals inside a \"\"\" raw string is a string literal, not a call. "
            f"assertion_count seen: {counts}"
        )

        with_real = "i1b_raw_string_mention_does_not_inflate_the_assertion_count"
        assert_resolved(tests, with_real)
        assert_classified(findings, with_real, set())
        assert counts[with_real] == 1, (
            "The raw string must not be counted as a second assertion. "
            f"assertion_count seen: {counts}"
        )

        print("PASS I1b: an assertEquals inside a raw string is neither F2 nor an assertion count.")


# --------------------------------------------------------------------------------------------
# I1a / I2 - F3's SUT gate
# --------------------------------------------------------------------------------------------

def test_i1a_stub_named_like_the_sut_is_not_the_sut():
    with tempfile.TemporaryDirectory() as tmpdir:
        findings, tests = scan_fixture(tmpdir, "BalanceSheetTest.kt", I1A_STUB_FIXTURE)

        name = "i1a_stub_named_like_the_sut_is_not_the_class_under_test"
        assert_resolved(tests, name)
        assert_classified(findings, name, set())

        print("PASS I1a: a test double whose name matches the SUT is not the class under test.")


def test_i2_f3_sut_gate_rejects_a_non_sut_collaborator():
    with tempfile.TemporaryDirectory() as tmpdir:
        findings, tests = scan_fixture(tmpdir, "LedgerCalculatorTest.kt", I2_NON_SUT_FIXTURE)

        name = "i2_value_from_a_non_sut_collaborator_is_not_circular"
        assert_resolved(tests, name)
        assert_classified(findings, name, set())

        print("PASS I2: a value bound to a member call on a non-SUT collaborator is not F3.")


# --------------------------------------------------------------------------------------------
# I3 - the F4 and F2 guards that are load-bearing but were unexercised
# --------------------------------------------------------------------------------------------

def test_i3_f4_guards():
    with tempfile.TemporaryDirectory() as tmpdir:
        findings, tests = scan_fixture(tmpdir, "F4GuardTest.kt", I3_F4_GUARDS_FIXTURE)

        for name in (
            "i3a_while_loop_without_a_counter_conjunct_is_not_a_poll",
            "i3b_first_assertion_precedes_the_loop",
            "i3c_counter_only_loop_awaits_nothing",
        ):
            assert_resolved(tests, name)
            assert_classified(findings, name, set())

        print("PASS I3: a non-poll while, an assertion preceding the loop, and a counter-only "
              "loop are all excluded from F4.")


def test_i3_f2_does_not_compare_the_message_overload():
    with tempfile.TemporaryDirectory() as tmpdir:
        findings, tests = scan_fixture(tmpdir, "F4GuardTest.kt", I3_F4_GUARDS_FIXTURE)

        name = "i3d_message_overload_is_deliberately_not_compared"
        assert_resolved(tests, name)
        assert_classified(findings, name, set())

        print("PASS I3: the three-argument assertEquals(message, expected, actual) is not F2.")


# --------------------------------------------------------------------------------------------
# The expression-body path: 577 of 798 real tests, previously pinned by nothing
# --------------------------------------------------------------------------------------------

def test_eb_expression_bodies_are_scanned():
    with tempfile.TemporaryDirectory() as tmpdir:
        findings, tests = scan_fixture(
            tmpdir, "ExpressionBodyTest.kt", EXPRESSION_BODY_FIXTURE
        )

        names = (
            "eb_plain_expression_body_has_an_inline_assertion",
            "eb_wrapped_declaration_puts_the_brace_on_the_next_line",
            "eb_sibling_is_still_separate",
            "eb_local_val_inside_the_block_does_not_end_the_body",
        )
        counts = {t["name"]: t["assertion_count"] for t in tests}
        for name in names:
            assert_resolved(tests, name)
            assert_classified(findings, name, set())
        # One assertion each, except the multi-`val` test, which has two.
        expected_counts = {name: 1 for name in names}
        expected_counts["eb_local_val_inside_the_block_does_not_end_the_body"] = 2
        for name, expected in expected_counts.items():
            assert counts[name] == expected, (
                f"An expression body `fun x() = runBlocking {{ ... }}` must be scanned to its "
                f"real end, and a `val` inside the block must not terminate it. '{name}' "
                f"reported {counts.get(name)} assertions, expected {expected}. Counts: {counts}"
            )

        print("PASS EB: an expression-bodied test is scanned, including a wrapped declaration "
              "and a block containing local `val` lines.")


def test_eb_expression_body_tail_is_not_truncated():
    with tempfile.TemporaryDirectory() as tmpdir:
        findings, tests = scan_fixture(
            tmpdir, "ExpressionBodyTailTest.kt", EXPRESSION_BODY_TAIL_FIXTURE
        )

        names = (
            "tail_on_one_line_is_not_truncated",
            "tail_wrapped_onto_its_own_line_is_not_truncated",
            "tail_is_the_last_test_in_the_file",
        )
        counts = {t["name"]: t["assertion_count"] for t in tests}
        for name in names:
            assert_resolved(tests, name)
            assert_classified(findings, name, set())
            assert counts[name] == 1, (
                "An expression body with a tail (`= runBlocking { ... }.also { assert... }`) "
                f"runs past the first brace block. '{name}' reported {counts.get(name)} "
                f"assertions, expected 1. Counts: {counts}"
            )

        print("PASS EB (tail): an expression body with a trailing call is not truncated.")


def test_eb_expression_body_stops_at_an_unlisted_declaration():
    """
    The expression-body upper bound must be structural, not a list of declaration keywords.

    `_DECLARATION_STARTS` omitted `suspend` and `inline`, so a following `private suspend fun`
    helper was not seen as a new declaration, its assertions were attributed to the test, and
    the scanner invented an F2 and an F7 on a test that is neither tautological nor vacuous.
    This is the direction the brief forbids: it may under-report a rule, never invent one.
    """
    with tempfile.TemporaryDirectory() as tmpdir:
        findings, tests = scan_fixture(
            tmpdir, "UnlistedDeclarationTest.kt", EXPRESSION_BODY_UNLISTED_DECL_FIXTURE
        )

        expected = {
            "test_before_suspend_helper_is_not_tautological": 1,
            "test_before_suspend_helper_reassigning_is_not_vacuous_path": 1,
            "test_before_inline_helper_is_not_affected": 1,
        }
        counts = {t["name"]: t["assertion_count"] for t in tests}
        for name, count in expected.items():
            assert_resolved(tests, name)
            assert_classified(findings, name, set())
            assert counts[name] == count, (
                f"An expression body must stop at the next declaration whatever its modifiers. "
                f"'{name}' reported {counts.get(name)} assertions, expected {count}; a larger "
                f"count means a following helper's assertions were attributed to this test. "
                f"Counts: {counts}"
            )

        print("PASS EB (unlisted declaration): an expression body stops at `private suspend fun` "
              "and `private inline fun`, so a helper's assertions are never attributed to a test.")


# The expression-body upper bound must recognise every Kotlin class modifier, not only the ones
# somebody happened to enumerate. `inner` and `value` are class modifiers, and the terminator
# read only the FIRST word of the line, so `inner class Helper` and `value class Wrapper` were
# not recognised as new declarations: the walk ran on into them and the scanner invented an F2
# and an F7 on tests that are neither tautological nor vacuous.
#
# The two followers are LEXICAL fixtures. `inner class` is only legal inside another `inner
# class`, and a `value class` may carry no members, so neither can hold the tautology / F7 shape
# inside compiling Kotlin. The recogniser under test is lexical, so these are written for the
# recogniser; `test_every_kotlin_modifier_is_recognised_as_a_declaration` is what pins the whole
# modifier set, and it needs no such contortion.
EXPRESSION_BODY_MODIFIER_FOLLOWER_FIXTURE = '''
import org.junit.Assert.assertEquals
import org.junit.Test

class ModifierFollowerTest {

    @Test
    fun test_before_inner_class_is_not_tautological() = runBlocking {
        val total = 1 + 1
        assertEquals(2, total)
    }

    inner class HelperWithTautology {
        fun check() { assertEquals(2, 2) }
    }

    @Test
    fun test_before_value_class_is_not_vacuous_path() = runBlocking {
        var status = "pending"
        assertEquals("pending", status)
    }

    value class ReassigningWrapper(val a: Int) {
        fun check() {
            var other = "x"
            assertEquals("x", other)
            other = compute()
        }
    }
}
'''

# A declaration-led line that is only an ANNOTATION must be skipped, not treated as "not a
# declaration". Returning False there made the expression-body walk run one line past its own
# test, so the following test's `@Test` annotation ended up inside the previous test's range -
# measured on the real suite at 498 of 798 blocks. Inert for every rule, but it made the
# scanner's own docstring untrue.
EXPRESSION_BODY_ANNOTATION_BOUNDARY_FIXTURE = '''
import org.junit.Assert.assertEquals
import org.junit.Test

class AnnotationBoundaryTest {

    @Test
    fun a_expression_body_ends_before_the_next_tests_annotation() = runBlocking {
        val total = 1 + 1
        assertEquals(2, total)
    }

    @Test
    fun b_brace_bodied_neighbour() {
        assertEquals(1, 1)
    }
}
'''

# The block-bound fallback. `_FUN_NAME_RES` requires an identifier after `fun`, so a Kotlin
# backtick-quoted test name (`fun \`a name between backticks\``) cannot be located and the block
# falls back to the next `@Test`. That is the real trigger: 7 of the suite's 798 tests, all in
# core/ledger/NoteCleanerTest.kt. It is ordinary compiling Kotlin, so it is representable as a
# fixture - the previous claim that it was not was wrong.
BACKTICK_NAMED_FALLBACK_FIXTURE = '''
import org.junit.Assert.assertEquals
import org.junit.Test

class BacktickNamedTest {

    @Test
    fun `a name written between backticks`() {
        assertEquals(250, computeDebt())
    }
}
'''

# Every keyword that can head a Kotlin declaration. This list is the INDEPENDENT ORACLE for
# "the terminator sees every modifier": it is transcribed from the Kotlin grammar's hard
# keywords, not read out of the scanner, so the two can disagree and the test fails when they
# do. It replaces the previous unfalsifiable claim that a keyword list "cannot be defeated by
# adding a modifier" - a token enumeration IS a list, and only this equality is checkable.
KOTLIN_DECLARATION_STARTERS = (
    "class", "companion", "constructor", "enum", "fun", "init", "interface",
    "object", "typealias", "val", "var",
)
KOTLIN_MODIFIERS = (
    "abstract", "actual", "annotation", "const", "crossinline", "data", "enum",
    "expect", "external", "final", "infix", "inline", "inner", "internal",
    "lateinit", "noinline", "open", "operator", "override", "private", "protected",
    "public", "reified", "sealed", "suspend", "tailrec", "value", "vararg",
)
KOTLIN_DECLARATION_WORDS = frozenset(KOTLIN_DECLARATION_STARTERS) | frozenset(KOTLIN_MODIFIERS)


def test_eb_expression_body_stops_at_a_class_modifier_follower():
    """
    The terminator must see EVERY Kotlin class modifier, not a hand-picked subset.

    `inner` and `value` are the two class modifiers missing from the scanner's keyword set, and
    because the terminator read only the first word of the line, `inner class Helper` and
    `value class Wrapper` were not recognised as new declarations. The walk ran on into them and
    the scanner invented an F2 and an F7. That is the direction the brief forbids: it may
    under-report a rule, never invent one.
    """
    with tempfile.TemporaryDirectory() as tmpdir:
        findings, tests = scan_fixture(
            tmpdir, "ModifierFollowerTest.kt", EXPRESSION_BODY_MODIFIER_FOLLOWER_FIXTURE
        )

        expected = {
            "test_before_inner_class_is_not_tautological": 1,
            "test_before_value_class_is_not_vacuous_path": 1,
        }
        counts = {t["name"]: t["assertion_count"] for t in tests}
        for name, count in expected.items():
            assert_resolved(tests, name)
            assert_classified(findings, name, set())
            assert counts[name] == count, (
                f"An expression body must stop at an `inner class` or a `value class` exactly as "
                f"it stops at any other declaration. '{name}' reported {counts.get(name)} "
                f"assertions, expected {count}; a larger count means the follower's assertions "
                f"were attributed to this test. Counts: {counts}"
            )

        print("PASS EB (class-modifier follower): an expression body stops at `inner class` and "
              "`value class`, so neither an F2 nor an F7 is invented.")


def test_eb_expression_body_stops_before_the_next_tests_annotation():
    """
    A line that is only an annotation is a DECLARATION LEAD-IN, not a continuation.

    The annotation branch consumed the annotation to end-of-line, then read words at the newline
    and found none, so it returned False. The walk then stopped one line late and the following
    test's `@Test` annotation landed inside the previous test's range - 498 of the 798 real
    blocks. No rule reads `@Test`, so every count was unaffected; what it broke was the
    scanner's own stated guarantee that such a line is outside both ranges.
    """
    with tempfile.TemporaryDirectory() as tmpdir:
        findings, tests = scan_fixture(
            tmpdir, "AnnotationBoundaryTest.kt", EXPRESSION_BODY_ANNOTATION_BOUNDARY_FIXTURE
        )

        first = "a_expression_body_ends_before_the_next_tests_annotation"
        second = "b_brace_bodied_neighbour"
        assert_resolved(tests, first)
        assert_resolved(tests, second)
        assert_classified(findings, first, set())
        assert_classified(findings, second, {"F2"})

        by_name = {t["name"]: t for t in tests}
        assert by_name[first]["end_line"] < by_name[second]["line"], (
            "The previous test's @Test annotation belongs to NEITHER test and must be outside "
            "both ranges. The expression body ran on to the line after its own, so it reported "
            f"end_line {by_name[first]['end_line']} while the next @Test starts at "
            f"{by_name[second]['line']}."
        )

        counts = {t["name"]: t["assertion_count"] for t in tests}
        assert counts[first] == 1, (
            f"The expression body must stop before the next @Test annotation. Counts: {counts}"
        )

        print("PASS EB (annotation boundary): a lone @Test line is skipped, so the following "
              "test's annotation is outside both blocks.")


def test_every_kotlin_modifier_is_recognised_as_a_declaration():
    """
    The checkable version of "the terminator cannot be defeated by adding a modifier".

    A keyword set IS a list, so a comment cannot honestly promise completeness. This test can:
    the oracle is `KOTLIN_DECLARATION_WORDS`, transcribed from the Kotlin grammar's hard
    keywords, and the assertion is a two-way set equality against the scanner's own set. If a
    modifier is missing from the scanner, or the scanner has drifted to a word that is not a
    Kotlin declaration word at all, this fails.

    The behavioural half drives `_starts_new_declaration` with a line whose SECOND word is not a
    keyword (`<modifier> helper() { }`), so only the modifier itself can satisfy the test. That
    is the exact shape that failed for `inner class` and `value class`.
    """
    scanner_set = scan_test_evidence._DECLARATION_KEYWORDS

    missing = sorted(KOTLIN_DECLARATION_WORDS - scanner_set)
    assert not missing, (
        f"Every Kotlin declaration starter and modifier must be in the scanner's keyword set; "
        f"missing: {missing}. A declaration led by one of these would not be recognised, so an "
        f"expression body would run past it and invent an F2/F3/F7."
    )

    extra = sorted(scanner_set - KOTLIN_DECLARATION_WORDS)
    assert not extra, (
        f"The scanner's keyword set holds words that are not Kotlin declaration starters or "
        f"modifiers: {extra}. A stray entry makes a continuation line look like a declaration "
        f"and truncates the expression body, which invents an F1."
    )

    unrecognised = [m for m in KOTLIN_MODIFIERS
                    if not scan_test_evidence._starts_new_declaration(
                        "    %s helper() { }" % m, 0)]
    assert not unrecognised, (
        f"These modifiers do not read as a declaration on a line whose second word is not a "
        f"keyword, which is the shape that invented an F2 for `inner class`: {unrecognised}"
    )

    # The SECOND word. `inner class` and `value class` are already covered by the set-equality
    # half above, so nothing else reaches the `words[1]` test - which is exactly how the
    # two-word path stayed dead code through three rounds with a comment claiming it worked.
    # This asserts it directly.
    #
    # Stated plainly: there is NO compiling Kotlin line whose first word is not a declaration
    # word and whose second word is, so this is a DEFENSIVE path, not a real case. It is kept
    # because it is one line of code that would otherwise be untested, and it is pinned here
    # rather than left to a comment. The input is lexical, not compiled.
    defensive = [
        "    notAKeyword fun helper() { }",
        "    someReceiver value = 1",
        "    leading inner class Helper",
    ]
    missed = [line for line in defensive
              if not scan_test_evidence._starts_new_declaration(line, 0)]
    assert not missed, (
        f"The second word must be read, with the whitespace before it skipped. These lines are "
        f"led by a non-keyword and are not recognised as declarations: {missed}. Without the "
        f"skip the second word was never read at all and this test path was dead code."
    )

    # The same recogniser must still say NO to a continuation line, or a tightened bound would
    # truncate a body and invent an F1. A multi-line argument list begins with a bare
    # identifier, and a chained call begins with a dot; neither is a declaration.
    continuations = [
        "    leading, trailing fun helper() { }",
        "    .also { assertEquals(1, it) }",
        "    it is not null",
    ]
    wrongly = [line for line in continuations
               if scan_test_evidence._starts_new_declaration(line, 0)]
    assert not wrongly, (
        f"These lines continue an expression and must not read as a declaration; treating one "
        f"as a declaration truncates the expression body and invents an F1: {wrongly}"
    )

    print(f"PASS MODIFIERS: all {len(KOTLIN_MODIFIERS)} Kotlin modifiers and "
          f"{len(KOTLIN_DECLARATION_STARTERS)} declaration starters are recognised, in both "
          f"directions (none missing, none stray), and the second word is read.")


def test_fallback_resolves_a_test_whose_fun_cannot_be_located():
    """
    The block-bound fallback is reachable by ordinary compiling Kotlin, so it is pinned.

    The trigger is a `fun` the identifier pattern cannot match - a backtick-quoted test name.
    7 of the suite's 798 tests are in this state, all in core/ledger/NoteCleanerTest.kt. The
    previous report gave "a file that does not balance is not representable as a fixture" as
    the reason the fallback had no pin; that was wrong, and this is the pin.

    What this proves: the test is DISCOVERED, not skipped, and its own assertions are counted
    through the fallback path. What it does NOT prove: that the fallback bounds the block. It
    does not - it runs to the next `@Test`, so a following test's assertions are inside the
    range. Pinning that would enshrine the over-report rather than close it, so it is stated
    here instead of asserted.
    """
    with tempfile.TemporaryDirectory() as tmpdir:
        findings, tests = scan_fixture(
            tmpdir, "BacktickNamedTest.kt", BACKTICK_NAMED_FALLBACK_FIXTURE
        )

        assert len(tests) == 1, (
            f"The backtick-named test must still be discovered through the fallback, got "
            f"{[(t['name'], t['line']) for t in tests]}"
        )
        found = tests[0]
        unresolved = [t for t in tests if t["name"].startswith("<unnamed@test@")]
        assert unresolved, (
            "This fixture exists to exercise the fallback. The test name resolved to "
            f"{found['name']!r}, which means `fun` was located and the fallback was NOT taken."
        )
        assert found["assertion_count"] == 1, (
            "The fallback must still count the test's own assertion, got "
            f"{found['assertion_count']} for {found['name']}"
        )
        assert rules_for(findings, found["name"]) == set(), (
            f"The fallback must not invent a rule here; got "
            f"{[(f.rule, f.detail) for f in findings if f.test_name == found['name']]}"
        )

        print("PASS FALLBACK: a backtick-named test whose `fun` cannot be located is still "
              "discovered and its own assertion is counted.")


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
    test_c1_delegated_assertion_is_not_vacuous,
    test_i1c_helper_throwing_assertion_error_is_not_vacuous,
    test_i1b_assertion_inside_a_raw_string_is_not_an_assertion,
    test_i1a_stub_named_like_the_sut_is_not_the_sut,
    test_i2_f3_sut_gate_rejects_a_non_sut_collaborator,
    test_i3_f4_guards,
    test_i3_f2_does_not_compare_the_message_overload,
    test_eb_expression_bodies_are_scanned,
    test_eb_expression_body_tail_is_not_truncated,
    test_eb_expression_body_stops_at_an_unlisted_declaration,
    test_eb_expression_body_stops_at_a_class_modifier_follower,
    test_eb_expression_body_stops_before_the_next_tests_annotation,
    test_every_kotlin_modifier_is_recognised_as_a_declaration,
    test_fallback_resolves_a_test_whose_fun_cannot_be_located,
    test_cli_rule_filter_and_exit_zero,
    test_cli_single_assertion_selector,
    test_cli_real_process_exit_code_is_zero_even_with_findings,
]


def count_assertion_calls():
    """
    Count of `assert` statements written literally in the functions named in TESTS.

    This is a count of assert STATEMENTS IN SOURCE, not of assertions executed against the
    scanner, and the printed label says so. Two things it deliberately does not claim:
      * the assertions inside the `assert_classified` / `assert_resolved` helpers, which do
        the classification work, are not counted here;
      * a statement inside a loop executes once per iteration, so this is not an execution
        count. `test_i3_f4_guards`, for example, writes two helper calls that run three times.

    It is measured from this module's own AST rather than derived from a multiplication that
    has no relationship to anything in the source.
    """
    import ast
    import textwrap

    source = textwrap.dedent(inspect.getsource(sys.modules[__name__]))
    tree = ast.parse(source)
    by_name = {node.name: node for node in tree.body if isinstance(node, ast.FunctionDef)}
    total = 0
    for test_fn in TESTS:
        node = by_name.get(test_fn.__name__)
        assert node is not None, f"Test {test_fn.__name__} is not defined in this module"
        total += sum(1 for child in ast.walk(node) if isinstance(child, ast.Assert))
    return total


def main():
    print("=" * 74)
    print("=== TEST EVIDENCE SCANNER SELF-TEST (fixtures against the real scanner) ===")
    print("=" * 74)
    for test_fn in TESTS:
        test_fn()
    print("-" * 74)
    print(f"ALL {len(TESTS)} SCANNER FIXTURE GROUPS PASSED "
          f"({count_assertion_calls()} assert statements written in those groups, not counting "
          f"the assertions inside the classification helpers or loop re-executions).")
    print("=" * 74)
    return 0


if __name__ == "__main__":
    sys.exit(main())
