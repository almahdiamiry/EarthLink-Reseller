# Lesson Learned: Causal Verification

**Identifier:** `LL-CAUSAL-VERIFICATION`
**Status:** Practical testing technique; non-authoritative.

## Core Principle

A test must execute the real production target and causally observe its behavior. 

Avoid **tautological tests** — tests that duplicate the production rule inside the test helper or mock, and then assert that the test agrees with its own duplication.

---

## The Causal Testing Pattern

When writing unit, integration, or regression tests, apply this standard structure:

```text
1. Admissible Baseline
   Construct a clean, valid fixture that satisfies all prerequisites.

2. Real Target Invocation
   Call the actual production class, method, or entrypoint under test.
   Do NOT call a test-only replica or bypass the real workflow.

3. Negative Twin (Targeted Mutation)
   Mutate ONLY the specific attribute or invariant being verified.
   Keep all other parameters identical to the valid baseline.

4. Target-Specific Observation
   Verify that the production system rejects the invalid twin with the
   precise, expected error code, exception, or state transition.

5. Unrelated Failure Isolation
   Ensure that general crashes (e.g., missing dependencies, malformed test setup)
   do NOT get falsely interpreted as passing the negative test case.
```

---

## Anti-Patterns to Avoid

* **Self-Fulfilling Mocks:** Mocking a repository to return a synthetic value, then asserting that the caller received that exact synthetic value, without ever executing the underlying logic.
* **Coincidental Passes:** Asserting only `result == false` or `isError == true` without checking whether the failure was caused by the specific condition being tested or by an unrelated syntax/environment error.
* **Contract/Implementation Echoing:** Re-writing the business validation inside the test assertion logic so that any bug in the business logic is mirrored in the test.

---

## Practical Rule of Thumb

If you temporarily delete the production check/guard:
* The **valid baseline** should still succeed.
* The **negative twin** MUST fail the test (i.e., the test must turn RED).

If removing the production check still leaves all tests green, the test is not causally verifying the behavior.

---

> **Note for Developers & AI Agents:**  
> This is a **testing technique** for writing dependable automated tests, not an administrative gate, certification requirement, or audit protocol.
